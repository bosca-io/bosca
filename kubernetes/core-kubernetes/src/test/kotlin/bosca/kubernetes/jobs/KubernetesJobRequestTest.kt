package bosca.kubernetes.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class KubernetesJobRequestTest {

    @Test
    fun `profile must be a Kubernetes DNS subdomain`() {
        KubernetesJobRequest("android-builds", "job-1").validate()
        KubernetesJobRequest("ci.example", "job-1").validate()

        assertFailsWith<IllegalArgumentException> {
            KubernetesJobRequest("Android Builds", "job-1").validate()
        }
        assertFailsWith<IllegalArgumentException> {
            KubernetesJobRequest("-android", "job-1").validate()
        }
        assertFailsWith<IllegalArgumentException> {
            KubernetesJobRequest("ci..example", "job-1").validate()
        }
        assertFailsWith<IllegalArgumentException> {
            KubernetesJobRequest("${"a".repeat(64)}.example", "job-1").validate()
        }
        assertFailsWith<IllegalArgumentException> {
            KubernetesJobRequest("a".repeat(254), "job-1").validate()
        }
    }

    @Test
    fun `idempotency key must be present and bounded`() {
        assertFailsWith<IllegalArgumentException> {
            KubernetesJobRequest("android", "").validate()
        }
        assertFailsWith<IllegalArgumentException> {
            KubernetesJobRequest("android", "   ").validate()
        }
        assertFailsWith<IllegalArgumentException> {
            KubernetesJobRequest("android", "x".repeat(1_025)).validate()
        }
    }

    @Test
    fun `dispatch wait timeout must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            KubernetesJobRequest(
                profile = "android",
                idempotencyKey = "job-1",
                dispatchWaitTimeoutSeconds = 0,
            ).validate()
        }
    }

    @Test
    fun `request supports every explicit workload override`() {
        val request = KubernetesJobRequest(
            profile = "training",
            idempotencyKey = "recommendations-42",
            environment = mapOf("MODEL" to "recommendations"),
            arguments = listOf("train", "--epochs=4"),
            labels = mapOf("team" to "recommendations"),
            annotations = mapOf("trace" to "test"),
            dispatchWaitTimeoutSeconds = 300,
        )

        request.validate()
        assertEquals(listOf("train", "--epochs=4"), request.arguments)
        assertEquals(300, request.dispatchWaitTimeoutSeconds)

        val explicit = Json.encodeToString(KubernetesJobRequest.serializer(), request)
        val defaults = Json.encodeToString(
            KubernetesJobRequest.serializer(),
            KubernetesJobRequest("training", "recommendations-43"),
        )
        assertEquals(request, Json.decodeFromString(KubernetesJobRequest.serializer(), explicit))
        assertEquals(
            KubernetesJobRequest("training", "recommendations-43"),
            Json.decodeFromString(KubernetesJobRequest.serializer(), defaults),
        )
    }

    @Test
    fun `request serialization rejects missing required and unknown fields`() {
        listOf(
            "{}",
            """{"profile":"android"}""",
            """{"idempotencyKey":"job-1"}""",
            """{"profile":"android","idempotencyKey":"job-1","unknown":true}""",
        ).forEach { value ->
            assertFailsWith<SerializationException> {
                Json.decodeFromString(KubernetesJobRequest.serializer(), value)
            }
        }
        val lenient = Json { ignoreUnknownKeys = true }
        assertEquals(
            KubernetesJobRequest("android", "job-1"),
            lenient.decodeFromString(
                KubernetesJobRequest.serializer(),
                """{"profile":"android","idempotencyKey":"job-1","unknown":true}""",
            ),
        )
    }
}
