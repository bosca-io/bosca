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
import bosca.serialization.UUID
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Verifies encrypted Helm credential mapping and repository-delete cascading against PostgreSQL. */
class HelmRepoCredentialRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_helm_repo_test")
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
            ),
        )
        private var schemaInitialized = false
    }

    private val repos = HelmRepoRepositoryImpl()
    private val credentials = HelmRepoCredentialRepositoryImpl()

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
                    executeResource("/db/migrations/V2__helm_repos.sql")
                    executeResource("/db/migrations/V4__helm_repo_credentials.sql")
                }
            }
            schemaInitialized = true
        }
        withDb {
            connection().useStatement("delete from kubernetes.helm_repo") { it.execute() }
        }
    }

    @Test
    fun `credential round trips encrypted bytes and follows repository deletion`() = withDb {
        repos.upsert("private", "https://charts.example.com", "http", "entries: {}")
        val id = UUID.random()
        val nonce = byteArrayOf(1, 2, 3)
        val data = byteArrayOf(4, 5, 6)

        credentials.upsert(HelmRepoCredential("private", id, nonce, data))

        val stored = requireNotNull(credentials.get("private"))
        assertEquals(id, stored.id)
        assertContentEquals(nonce, stored.nonce)
        assertContentEquals(data, stored.data)

        repos.delete("private")
        assertNull(credentials.get("private"))
    }

    private suspend fun executeResource(path: String) {
        val sql = requireNotNull(javaClass.getResourceAsStream(path)).bufferedReader().readText()
        connection().useStatement(sql) { it.execute() }
    }

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) { block() }
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
    }
}
