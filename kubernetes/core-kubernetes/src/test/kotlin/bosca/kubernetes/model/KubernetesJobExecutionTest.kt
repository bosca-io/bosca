package bosca.kubernetes.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.every
import io.mockk.mockk
import java.sql.ResultSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

class KubernetesJobExecutionTest {

    private val json = Json {
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    @Test
    fun `execution supports every explicit durable field`() {
        val dispatchId = UUID.random()
        val clusterId = UUID.random()
        val now = OffsetDateTime.now()
        val execution = KubernetesJobExecution(
            dispatchId = dispatchId,
            profile = "training",
            idempotencyKey = "model-1",
            request = kotlinx.serialization.json.JsonPrimitive("request"),
            status = KubernetesJobExecutionStatus.RUNNING,
            clusterId = clusterId,
            namespace = "gpu",
            jobName = "training-model-1",
            message = "running",
            createdAt = now,
            modifiedAt = now,
            publishedAt = now,
            materializedAt = now,
            startedAt = now,
            finishedAt = now,
        )

        assertEquals(clusterId, execution.clusterId)
        assertEquals(now, execution.finishedAt)
        val explicit = json.encodeToString(KubernetesJobExecution.serializer(), execution)
        assertEquals(
            execution,
            json.decodeFromString(KubernetesJobExecution.serializer(), explicit),
        )
        val defaults = KubernetesJobExecution(dispatchId, "training", "model-2")
        assertEquals(
            defaults.copy(
                createdAt = defaults.createdAt,
                modifiedAt = defaults.modifiedAt,
            ),
            json.decodeFromString(
                KubernetesJobExecution.serializer(),
                json.encodeToString(KubernetesJobExecution.serializer(), defaults),
            ),
        )
    }

    @Test
    fun `result supports explicit optional fields`() {
        val now = OffsetDateTime.now()
        val result = KubernetesJobResult(
            dispatchId = UUID.random(),
            profile = "training",
            idempotencyKey = "model-1",
            status = KubernetesJobResultStatus.SUCCEEDED,
            message = "complete",
            startedAt = now,
            finishedAt = now,
        )

        assertEquals("complete", result.message)
        assertEquals(now, result.startedAt)
        val explicit = json.encodeToString(KubernetesJobResult.serializer(), result)
        assertEquals(result, json.decodeFromString(KubernetesJobResult.serializer(), explicit))
        val defaults = result.copy(message = null, startedAt = null)
        assertEquals(
            defaults,
            json.decodeFromString(
                KubernetesJobResult.serializer(),
                json.encodeToString(KubernetesJobResult.serializer(), defaults),
            ),
        )
    }

    @Test
    fun `only completed execution states are terminal`() {
        val nonTerminal = listOf(
            KubernetesJobExecutionStatus.QUEUED,
            KubernetesJobExecutionStatus.MATERIALIZED,
            KubernetesJobExecutionStatus.RUNNING,
            KubernetesJobExecutionStatus.CANCEL_REQUESTED,
        )

        nonTerminal.forEach { assertFalse(it.isTerminal, "$it must remain active") }
        assertTrue(KubernetesJobExecutionStatus.SUCCEEDED.isTerminal)
        assertTrue(KubernetesJobExecutionStatus.FAILED.isTerminal)
        assertTrue(KubernetesJobExecutionStatus.CANCELLED.isTerminal)
    }

    @Test
    fun `database status mapper accepts postgres enum casing`() {
        KubernetesJobExecutionStatus.entries.forEach { status ->
            val result = mockk<ResultSet>()
            every { result.getString(1) } returns status.name.lowercase()
            assertEquals(
                status,
                KubernetesJobExecutionStatusMapper.map(
                    KubernetesJobExecutionStatus::class,
                    emptyList(),
                    result,
                    1,
                ),
            )
        }
    }

    @Test
    fun `active executions have no terminal result`() {
        val dispatchId = UUID.random()

        listOf(
            KubernetesJobExecutionStatus.QUEUED,
            KubernetesJobExecutionStatus.MATERIALIZED,
            KubernetesJobExecutionStatus.RUNNING,
            KubernetesJobExecutionStatus.CANCEL_REQUESTED,
        ).forEach { status ->
            assertNull(
                KubernetesJobExecution(
                    dispatchId = dispatchId,
                    profile = "training",
                    idempotencyKey = "model-1",
                    status = status,
                ).toResultOrNull()
            )
        }
    }

    @Test
    fun `terminal executions map to the generic durable result contract`() {
        val dispatchId = UUID.random()
        val startedAt = OffsetDateTime.now().minusMinutes(2)
        val finishedAt = OffsetDateTime.now()
        val expected = mapOf(
            KubernetesJobExecutionStatus.SUCCEEDED to KubernetesJobResultStatus.SUCCEEDED,
            KubernetesJobExecutionStatus.FAILED to KubernetesJobResultStatus.FAILED,
            KubernetesJobExecutionStatus.CANCELLED to KubernetesJobResultStatus.CANCELLED,
        )

        expected.forEach { (executionStatus, resultStatus) ->
            val result = KubernetesJobExecution(
                dispatchId = dispatchId,
                profile = "training",
                idempotencyKey = "model-1",
                status = executionStatus,
                message = "settled",
                startedAt = startedAt,
                finishedAt = finishedAt,
            ).toResultOrNull()

            assertEquals(dispatchId, result?.dispatchId)
            assertEquals("training", result?.profile)
            assertEquals("model-1", result?.idempotencyKey)
            assertEquals(resultStatus, result?.status)
            assertEquals("settled", result?.message)
            assertEquals(startedAt, result?.startedAt)
            assertEquals(finishedAt, result?.finishedAt)
        }
    }

    @Test
    fun `terminal execution without a finish time is rejected`() {
        val dispatchId = UUID.random()

        val failure = assertFailsWith<IllegalArgumentException> {
            KubernetesJobExecution(
                dispatchId = dispatchId,
                profile = "training",
                idempotencyKey = "model-1",
                status = KubernetesJobExecutionStatus.FAILED,
            ).toResultOrNull()
        }

        assertTrue(failure.message.orEmpty().contains(dispatchId.toString()))
    }

    @Test
    fun `execution serialization rejects every missing required identity`() {
        val dispatchId = UUID.random()
        listOf(
            "{}",
            """{"dispatchId":"$dispatchId"}""",
            """{"dispatchId":"$dispatchId","profile":"training"}""",
            """{"profile":"training","idempotencyKey":"model-1"}""",
        ).forEach { value ->
            assertFailsWith<SerializationException> {
                json.decodeFromString(KubernetesJobExecution.serializer(), value)
            }
        }
    }

    @Test
    fun `result serialization rejects missing terminal contract fields`() {
        val dispatchId = UUID.random()
        val finishedAt = OffsetDateTime.now()
        listOf(
            "{}",
            """{"dispatchId":"$dispatchId"}""",
            """{"dispatchId":"$dispatchId","profile":"training"}""",
            """{"dispatchId":"$dispatchId","profile":"training","idempotencyKey":"model-1"}""",
            """{"dispatchId":"$dispatchId","profile":"training","idempotencyKey":"model-1","status":"FAILED"}""",
            """{"dispatchId":"$dispatchId","profile":"training","idempotencyKey":"model-1","finishedAt":"$finishedAt"}""",
        ).forEach { value ->
            assertFailsWith<SerializationException> {
                json.decodeFromString(KubernetesJobResult.serializer(), value)
            }
        }
    }
}
