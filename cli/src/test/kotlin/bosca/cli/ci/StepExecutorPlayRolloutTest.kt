package bosca.cli.ci

import bosca.cli.api.NetworkClient
import bosca.graphql.gen.PlayRolloutFromJobData
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class StepExecutorPlayRolloutTest {

    @Test
    fun `play-rollout sends a store-neutral percentage through the typed API`() = runTest {
        val root = Files.createTempDirectory("play-rollout-step").toFile()
        try {
            val api = RecordingCiApi()
            val logs = TestLogBuffer()
            val jobId = Uuid.random()
            val executor = executor(root, logs, api, jobId)

            val result = executor.execute(
                StepDefinition(
                    name = "Advance rollout",
                    uses = "play-rollout",
                    with = mapOf(
                        "environment" to "production",
                        "rolloutPercentage" to "10",
                        "repository" to "mobile",
                        "target" to "google_play",
                    ),
                ),
                ExpressionContext(),
            )

            assertTrue(result.success, logs.lines.joinToString("\n"))
            assertEquals(jobId, api.jobId)
            assertEquals("production", api.environmentKey)
            assertEquals(10.0, api.rolloutPercentage)
            assertEquals("mobile", api.repository)
            assertEquals("google_play", api.target)
            assertTrue(logs.lines.any { "10%" in it && "app:production@42" in it })

            val fractional = executor(root, logs, api, jobId).execute(
                validStep(rolloutPercentage = "37.5"),
                ExpressionContext(),
            )
            assertTrue(fractional.success)
            assertTrue(logs.lines.any { "37.5%" in it })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `play-rollout rejects each missing or invalid input before calling the server`() = runTest {
        val root = Files.createTempDirectory("play-rollout-step-invalid").toFile()
        try {
            val logs = TestLogBuffer()
            val api = RecordingCiApi()
            val invalidInputs = listOf(
                mapOf("rolloutPercentage" to "10"),
                mapOf("environment" to " ", "rolloutPercentage" to "10"),
                mapOf("environment" to "production"),
                mapOf("environment" to "production", "rolloutPercentage" to "not-a-number"),
                mapOf("environment" to "production", "rolloutPercentage" to "NaN"),
                mapOf("environment" to "production", "rolloutPercentage" to "Infinity"),
                mapOf("environment" to "production", "rolloutPercentage" to "-0.1"),
                mapOf("environment" to "production", "rolloutPercentage" to "100.1"),
            )

            invalidInputs.forEach { with ->
                val result = executor(root, logs, api, Uuid.random()).execute(
                    StepDefinition(name = "Invalid rollout", uses = "play-rollout", with = with),
                    ExpressionContext(),
                )
                assertFalse(result.success)
            }

            assertFalse(api.called)
            assertTrue(logs.lines.any { "environment" in it })
            assertTrue(logs.lines.any { "between 0 and 100" in it })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `play-rollout fails closed when runner server context is unavailable`() = runTest {
        val root = Files.createTempDirectory("play-rollout-step-context").toFile()
        try {
            val logs = TestLogBuffer()
            assertFalse(executor(root, logs, null, Uuid.random()).execute(validStep(), ExpressionContext()).success)
            assertFalse(executor(root, logs, RecordingCiApi(), null).execute(validStep(), ExpressionContext()).success)
            assertEquals(2, logs.lines.count { "server API" in it })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `play-rollout reports server failure and never swallows cancellation`() = runTest {
        val root = Files.createTempDirectory("play-rollout-step-failure").toFile()
        try {
            val logs = TestLogBuffer()
            val failure = executor(
                root, logs, FailingCiApi(IllegalStateException("Play unavailable")), Uuid.random(),
            ).execute(validStep(), ExpressionContext())
            assertFalse(failure.success)
            assertTrue(logs.lines.any { "Play unavailable" in it })

            assertFailsWith<CancellationException> {
                executor(
                    root, logs, FailingCiApi(CancellationException("stopped")), Uuid.random(),
                ).execute(validStep(), ExpressionContext())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validStep(rolloutPercentage: String = "10") = StepDefinition(
        name = "Advance rollout",
        uses = "play-rollout",
        with = mapOf("environment" to "production", "rolloutPercentage" to rolloutPercentage),
    )

    private fun executor(root: File, logs: TestLogBuffer, api: CiApi?, jobId: Uuid?): StepExecutor {
        val workDir = File(root, "work").apply { mkdirs() }
        return StepExecutor(
            workDir = workDir,
            serverUrl = "http://localhost:0",
            agentToken = "token",
            commitSha = "abc",
            ref = "refs/tags/v1.0.0",
            repositoryId = Uuid.random().toString(),
            api = api,
            jobId = jobId,
            env = emptyMap(),
            secrets = emptyMap(),
            logBuffer = logs,
            sharedEnvFile = File(root, ".bosca_env").also { it.createNewFile() },
            sharedPathFile = File(root, ".bosca_path").also { it.createNewFile() },
        )
    }

    private class RecordingCiApi : CiApi(NetworkClient("http://localhost:0")) {
        var called = false
        lateinit var jobId: Uuid
        lateinit var environmentKey: String
        var rolloutPercentage: Double = Double.NaN
        var repository: String? = null
        var target: String? = null

        override suspend fun playRolloutFromJob(
            jobId: Uuid,
            environmentKey: String,
            rolloutPercentage: Double,
            repository: String?,
            target: String?,
        ): PlayRolloutFromJobData.Git.PlayRolloutFromJob {
            called = true
            this.jobId = jobId
            this.environmentKey = environmentKey
            this.rolloutPercentage = rolloutPercentage
            this.repository = repository
            this.target = target
            return PlayRolloutFromJobData.Git.PlayRolloutFromJob(
                reference = "app:production@42 (10%)",
                status = "DEPLOYED",
            )
        }
    }

    private class FailingCiApi(
        private val failure: Exception,
    ) : CiApi(NetworkClient("http://localhost:0")) {
        override suspend fun playRolloutFromJob(
            jobId: Uuid,
            environmentKey: String,
            rolloutPercentage: Double,
            repository: String?,
            target: String?,
        ): PlayRolloutFromJobData.Git.PlayRolloutFromJob = throw failure
    }
}
