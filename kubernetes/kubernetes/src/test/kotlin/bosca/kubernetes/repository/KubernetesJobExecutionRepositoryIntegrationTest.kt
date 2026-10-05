@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.kubernetes.repository

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.kubernetes.model.KubernetesJobExecutionStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import bosca.test.resources.SharedPostgreSQLContainer

/** Verifies the generated lifecycle queries and transition guards against PostgreSQL. */
class KubernetesJobExecutionRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_kubernetes_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 3,
                ),
                key = "test",
            )
        )
        private var schemaInitialized = false
    }

    private val repository = KubernetesJobExecutionRepositoryImpl()
    private lateinit var clusterId: UUID

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        if (!schemaInitialized) {
            withDb {
                transaction {
                    connection().useStatement("drop schema if exists kubernetes cascade") { it.execute() }
                    executeResource("/db/migrations/V1__kubernetes_initial.sql")
                    executeResource("/db/migrations/V3__kubernetes_job_executions.sql")
                }
            }
            schemaInitialized = true
        }
        clusterId = UUID.random()
        withDb {
            transaction {
                connection().useStatement("delete from kubernetes.job_execution") { it.execute() }
                connection().useStatement("delete from kubernetes.cluster") { it.execute() }
                connection().useStatement(
                    """
                    insert into kubernetes.cluster (id, name, provider, region, environment)
                    values ('$clusterId'::uuid, 'test', 'kind', 'local', 'DEVELOPMENT')
                    """.trimIndent()
                ) { it.execute() }
            }
        }
    }

    @Test
    fun `execution status is stored as a postgres enum`() = withDb {
        connection().useStatement(
            """
            select data_type, udt_schema, udt_name
            from information_schema.columns
            where table_schema = 'kubernetes'
              and table_name = 'job_execution'
              and column_name = 'status'
            """.trimIndent()
        ) { statement ->
            statement.executeQuery().use { result ->
                assertEquals(true, result.next())
                assertEquals("USER-DEFINED", result.getString("data_type"))
                assertEquals("kubernetes", result.getString("udt_schema"))
                assertEquals("job_execution_status", result.getString("udt_name"))
            }
        }
    }

    @Test
    fun `cancellation intent survives concurrent materialization`() = withDb {
        val dispatchId = UUID.random()
        assertNotNull(repository.create(dispatchId, "android", "ci-1"))

        val requested = repository.requestCancellation(dispatchId)
        assertEquals(KubernetesJobExecutionStatus.CANCEL_REQUESTED, requested?.status)

        val materialized = repository.markMaterialized(
            dispatchId,
            clusterId,
            "workers",
            "android-job",
        )
        assertEquals(KubernetesJobExecutionStatus.CANCEL_REQUESTED, materialized?.status)
        assertEquals("android-job", materialized?.jobName)
        assertNotNull(materialized?.materializedAt)

        val cancelled = repository.markCancelled(dispatchId)
        assertEquals(KubernetesJobExecutionStatus.CANCELLED, cancelled?.status)
        assertNotNull(cancelled?.finishedAt)
    }

    @Test
    fun `failed execution is terminal and retains its reason`() = withDb {
        val dispatchId = UUID.random()
        repository.create(dispatchId, "gpu", "training-1")
        repository.markMaterialized(dispatchId, clusterId, "training", "gpu-job")
        repository.markRunning(dispatchId)

        val failed = assertNotNull(repository.markFailed(dispatchId, "Pod failed to start: ImagePullBackOff"))
        assertEquals(KubernetesJobExecutionStatus.FAILED, failed.status)
        assertEquals("Pod failed to start: ImagePullBackOff", failed.message)
        assertNotNull(failed.startedAt)
        assertNotNull(failed.finishedAt)
        assertNull(repository.markSucceeded(dispatchId))
        assertEquals(KubernetesJobExecutionStatus.FAILED, repository.getById(dispatchId)?.status)
    }

    @Test
    fun `successful execution is terminal and records completion time`() = withDb {
        val dispatchId = UUID.random()
        repository.create(dispatchId, "gpu", "embeddings-1")
        repository.markMaterialized(dispatchId, clusterId, "embeddings", "gpu-job")
        repository.markRunning(dispatchId)

        val succeeded = assertNotNull(repository.markSucceeded(dispatchId))
        assertEquals(KubernetesJobExecutionStatus.SUCCEEDED, succeeded.status)
        assertNotNull(succeeded.startedAt)
        assertNotNull(succeeded.finishedAt)
        assertNull(repository.markFailed(dispatchId, "late failure"))
        assertEquals(KubernetesJobExecutionStatus.SUCCEEDED, repository.getById(dispatchId)?.status)
    }

    @Test
    fun `profile idempotency key returns the original dispatch`() = withDb {
        val firstId = UUID.random()
        val request = buildJsonObject { put("profile", "gpu") }
        val first = repository.create(firstId, "gpu", "training-idempotent", request)
        val duplicate = repository.create(
            UUID.random(),
            "gpu",
            "training-idempotent",
            request,
        )

        assertEquals(firstId, first.dispatchId)
        assertEquals(firstId, duplicate.dispatchId)
        assertEquals(
            firstId,
            repository.findByIdempotencyKey("gpu", "training-idempotent")?.dispatchId,
        )
    }

    @Test
    fun `published queued execution is not repeatedly claimed for recovery`() = withDb {
        val dispatchId = UUID.random()
        repository.create(
            dispatchId,
            "gpu",
            "publication-once",
            buildJsonObject {
                put("profile", "gpu")
                put("idempotencyKey", "publication-once")
                put("environment", buildJsonObject { put("BOSCA_TOKEN", "secret") })
            },
        )

        val claimed = repository.claimQueuedForPublication(
            OffsetDateTime.now().plusMinutes(1),
            10,
        )
        assertEquals(listOf(dispatchId), claimed.map { it.dispatchId })

        repository.markPublished(dispatchId)

        val published = assertNotNull(repository.getById(dispatchId))
        assertNotNull(published.publishedAt)
        assertNull(published.request.jsonObject["environment"])
        assertEquals(
            "publication-once",
            published.request.jsonObject.getValue("idempotencyKey").jsonPrimitive.content,
        )
        assertEquals(
            emptyList(),
            repository.claimQueuedForPublication(OffsetDateTime.now().plusMinutes(1), 10),
        )
    }

    @Test
    fun `terminal execution removes per-workload values from its outbox request`() = withDb {
        val dispatchId = UUID.random()
        val request = buildJsonObject {
            put("profile", "gpu")
            put("environment", buildJsonObject {
                put("BOSCA_TOKEN", "secret")
                put("BOSCA_CI_AGENT_TOKEN", "legacy-secret")
                put("SAFE_VALUE", "retained")
            })
        }
        repository.create(dispatchId, "gpu", "credential-cleanup", request)

        val failed = assertNotNull(repository.markFailed(dispatchId, "profile unavailable"))
        assertNull(failed.request.jsonObject["environment"])
        assertEquals("gpu", failed.request.jsonObject.getValue("profile").jsonPrimitive.content)
    }

    private suspend fun executeResource(path: String) {
        val sql = requireNotNull(javaClass.getResourceAsStream(path))
            .bufferedReader()
            .readText()
        connection().useStatement(sql) { it.execute() }
    }

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) {
                    block()
                }
            } finally {
                withContext(NonCancellable) {
                    manager.release()
                }
            }
        }
    }
}
