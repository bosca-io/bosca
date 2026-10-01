package bosca.cli.ci

import bosca.cli.config.CliConfigStore
import bosca.cli.config.CliInvocation
import com.github.ajalt.clikt.testing.test
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RunRerunCommandTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("run-controls-test").toFile()
        CliConfigStore.directoryOverride = File(tempDir, "config")
        CliConfigStore.workingDirectoryOverride = tempDir
        CliInvocation.selectProfile(null)
    }

    @AfterTest
    fun tearDown() {
        CliInvocation.selectProfile(null)
        CliConfigStore.workingDirectoryOverride = null
        CliConfigStore.directoryOverride = null
        tempDir.deleteRecursively()
    }

    @Test
    fun `job rerun calls the exact-job mutation`() {
        MockWebServer().use { server ->
            val jobId = Uuid.random()
            server.enqueue(jobResponse("rerunPipelineJob", jobId))
            server.start()

            val result = RunRerunCommand().test(
                "--url ${server.url("/graphql")} --token test-token --job-id $jobId",
            )

            assertEquals(0, result.statusCode, result.stderr)
            assertTrue(result.stdout.contains("Pipeline job re-queued."))
            assertTrue(result.stdout.contains(jobId.toString()))
            assertTrue(result.stdout.contains("build-server"))
            assertTrue(result.stdout.contains("QUEUED"))

            val body = requireNotNull(server.takeRequest().body).utf8()
            assertTrue(body.contains("RerunPipelineJob"))
            assertTrue(body.contains("rerunPipelineJob"))
            assertTrue(body.contains(jobId.toString()))
            assertTrue(!body.contains("rerunPipeline(runId"))
        }
    }

    @Test
    fun `run rerun preserves the full pipeline behavior`() {
        MockWebServer().use { server ->
            val runId = Uuid.random()
            server.enqueue(runResponse("rerunPipeline", runId))
            server.start()

            val result = RunRerunCommand().test(
                "--url ${server.url("/graphql")} --token test-token --id $runId",
            )

            assertEquals(0, result.statusCode, result.stderr)
            assertTrue(result.stdout.contains("Pipeline re-triggered."))

            val body = requireNotNull(server.takeRequest().body).utf8()
            assertTrue(body.contains("RerunPipeline"))
            assertTrue(body.contains("rerunPipeline"))
            assertTrue(!body.contains("rerunPipelineJob"))
        }
    }

    @Test
    fun `job cancel calls the exact-job mutation`() {
        MockWebServer().use { server ->
            val jobId = Uuid.random()
            server.enqueue(jobResponse("cancelPipelineJob", jobId, "CANCELLED"))
            server.start()

            val result = RunCancelCommand().test(
                "--url ${server.url("/graphql")} --token test-token --job-id $jobId",
            )

            assertEquals(0, result.statusCode, result.stderr)
            assertTrue(result.stdout.contains("Pipeline job cancelled."))
            val body = requireNotNull(server.takeRequest().body).utf8()
            assertTrue(body.contains("CancelPipelineJob"))
            assertTrue(body.contains("cancelPipelineJob"))
            assertTrue(body.contains(jobId.toString()))
        }
    }

    @Test
    fun `run controls reject ambiguous targets before making a request`() {
        MockWebServer().use { server ->
            server.start()
            val runId = Uuid.random()
            val jobId = Uuid.random()

            val result = RunRerunCommand().test(
                "--url ${server.url("/graphql")} --token test-token --id $runId --job-id $jobId",
            )

            assertTrue(result.statusCode != 0)
            assertTrue(result.stderr.contains("Exactly one of --id or --job-id"))
            assertEquals(0, server.requestCount)
        }
    }

    private fun jobResponse(field: String, jobId: Uuid, status: String = "QUEUED") =
        MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body(
                """
                {
                  "data": {
                    "git": {
                      "$field": {
                        "id": "$jobId",
                        "name": "build-server",
                        "status": "$status"
                      }
                    }
                  }
                }
                """.trimIndent(),
            )
            .build()

    private fun runResponse(field: String, runId: Uuid): MockResponse {
        val pipelineId = Uuid.random()
        val repositoryId = Uuid.random()
        return MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body(
                """
                {
                  "data": {
                    "git": {
                      "$field": {
                        "id": "$runId",
                        "pipelineId": "$pipelineId",
                        "repositoryId": "$repositoryId",
                        "commitSha": "abcdef123456",
                        "ref": "refs/heads/main",
                        "triggerType": "RELEASE",
                        "status": "QUEUED",
                        "number": 42,
                        "created": "2026-07-27T00:00:00Z"
                      }
                    }
                  }
                }
                """.trimIndent(),
            )
            .build()
    }
}
