package bosca.cli.ci

import bosca.cli.api.NetworkClient
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CiApiBuildNumberTest {

    @Test
    fun `allocate build number sends every source identity field and decodes the durable value`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(
                        """{"data":{"git":{"allocateBuildNumberFromJob":{"number":7,"value":"7","reused":true}}}}""",
                    )
                    .build(),
            )
            server.start()
            val jobId = Uuid.random()
            val api = CiApi(NetworkClient(server.url("/graphql").toString()))

            val allocation = api.allocateBuildNumberFromJob(
                jobId, "ios", "com.example.app", "6.2.0", "release", "7",
            )

            assertEquals(7, allocation.number)
            assertEquals("7", allocation.value)
            assertTrue(allocation.reused)
            val request = server.takeRequest()
            val body = requireNotNull(request.body).utf8()
            assertTrue(jobId.toString() in body)
            assertTrue("com.example.app" in body)
            assertTrue("6.2.0" in body)
            assertTrue("release" in body)
            assertTrue("7" in body)
        }
    }

    @Test
    fun `play rollout sends the store-neutral percentage and decodes the deployment outcome`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(
                        """{"data":{"git":{"playRolloutFromJob":{"reference":"io.bosca.app:production@42","status":"DEPLOYED"}}}}""",
                    )
                    .build(),
            )
            server.start()
            val jobId = Uuid.random()
            val api = CiApi(NetworkClient(server.url("/graphql").toString()))

            val outcome = api.playRolloutFromJob(jobId, "production", 10.0, "mobile/app", "play")

            assertEquals("io.bosca.app:production@42", outcome.reference)
            assertEquals("DEPLOYED", outcome.status)
            val body = requireNotNull(server.takeRequest().body).utf8()
            assertTrue("10.0" in body)
            assertTrue("production" in body)
            assertTrue("mobile/app" in body)
            assertTrue("play" in body)
        }
    }

    @Test
    fun `rollback sends revision repository target and overrides and decodes the outcome`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(
                        """{"data":{"git":{"rollbackFromJob":{"reference":"bosca@4","status":"ROLLED_BACK"}}}}""",
                    )
                    .build(),
            )
            server.start()
            val jobId = Uuid.random()
            val api = CiApi(NetworkClient(server.url("/graphql").toString()))

            val outcome = api.rollbackFromJob(
                jobId,
                "production",
                "platform/operations",
                "helm",
                4,
                buildJsonObject { put("resetValues", "true") },
            )

            assertEquals("bosca@4", outcome.reference)
            assertEquals("ROLLED_BACK", outcome.status)
            val body = requireNotNull(server.takeRequest().body).utf8()
            assertTrue(jobId.toString() in body)
            assertTrue("production" in body)
            assertTrue("platform/operations" in body)
            assertTrue("helm" in body)
            assertTrue("resetValues" in body)
            assertTrue("4" in body)
        }
    }

    @Test
    fun `deployment health and release finalization mutations use the job context`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body("""{"data":{"git":{"jobDeploymentHealth":"DEGRADED"}}}""")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body("""{"data":{"git":{"markReleasedFromJob":true}}}""")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body("""{"data":{"git":{"generateReleaseNotesFromJob":true}}}""")
                    .build(),
            )
            server.start()
            val jobId = Uuid.random()
            val api = CiApi(NetworkClient(server.url("/graphql").toString()))

            assertEquals("DEGRADED", api.jobDeploymentHealth(jobId, "production", "operations"))
            val healthBody = requireNotNull(server.takeRequest().body).utf8()
            assertTrue(jobId.toString() in healthBody)
            assertTrue("production" in healthBody)
            assertTrue("operations" in healthBody)

            assertTrue(api.markReleasedFromJob(jobId))
            assertTrue(jobId.toString() in requireNotNull(server.takeRequest().body).utf8())

            assertTrue(api.generateReleaseNotesFromJob(jobId))
            assertTrue(jobId.toString() in requireNotNull(server.takeRequest().body).utf8())
        }
    }
}
