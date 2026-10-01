package bosca.cli.ci

import bosca.cli.api.NetworkClient
import bosca.graphql.gen.RollbackFromJobData
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class StepExecutorReleaseActionsTest {

    @Test
    fun `rollback sends the revision target and config overrides through the typed API`() = runTest {
        withRoot("rollback-step") { root ->
            val logs = TestLogBuffer()
            val api = RecordingCiApi()
            val jobId = Uuid.random()
            val executor = executor(root, logs, api, jobId)

            val result = executor.execute(
                StepDefinition(
                    name = "Rollback production",
                    uses = "rollback",
                    with = mapOf(
                        "environment" to "production",
                        "repository" to "operations",
                        "target" to "helm",
                        "toRevision" to "4",
                        "resetValues" to "true",
                    ),
                ),
                ExpressionContext(),
            )

            assertTrue(result.success, logs.lines.joinToString("\n"))
            assertEquals(jobId, api.rollbackJobId)
            assertEquals("production", api.rollbackEnvironment)
            assertEquals("operations", api.rollbackRepository)
            assertEquals("helm", api.rollbackTarget)
            assertEquals(4, api.rollbackRevision)
            assertEquals("true", (api.rollbackOverrides as JsonObject)["resetValues"]?.jsonPrimitive?.content)
            assertTrue(logs.lines.any { "bosca@4" in it && "ROLLED_BACK" in it })

            assertTrue(
                executor(root, logs, api, jobId).execute(
                    StepDefinition(
                        name = "Previous revision",
                        uses = "rollback",
                        with = mapOf("environment" to "production", "repository" to " ", "target" to " "),
                    ),
                    ExpressionContext(),
                ).success,
            )
            assertNull(api.rollbackRepository)
            assertNull(api.rollbackTarget)
            assertNull(api.rollbackRevision)
            assertNull(api.rollbackOverrides)
        }
    }

    @Test
    fun `rollback rejects missing environment and invalid revisions before calling the server`() = runTest {
        withRoot("rollback-invalid") { root ->
            val logs = TestLogBuffer()
            val api = RecordingCiApi()
            val invalid = listOf(
                emptyMap(),
                mapOf("environment" to " "),
                mapOf("environment" to "production", "toRevision" to "nope"),
                mapOf("environment" to "production", "toRevision" to "-1"),
                mapOf("environment" to "production", "toRevision" to "999999999999999999999"),
            )

            invalid.forEach { with ->
                assertFalse(
                    executor(root, logs, api, Uuid.random()).execute(
                        StepDefinition(name = "Invalid rollback", uses = "rollback", with = with),
                        ExpressionContext(),
                    ).success,
                )
            }
            assertEquals(0, api.rollbackCalls)
        }
    }

    @Test
    fun `rollback requires server context and reports failures without swallowing cancellation`() = runTest {
        withRoot("rollback-failure") { root ->
            val logs = TestLogBuffer()
            assertFalse(executor(root, logs, null, Uuid.random()).execute(rollbackStep(), ExpressionContext()).success)
            assertFalse(executor(root, logs, RecordingCiApi(), null).execute(rollbackStep(), ExpressionContext()).success)

            val failed = executor(
                root, logs, ThrowingCiApi(IllegalStateException("rollback unavailable")), Uuid.random(),
            ).execute(rollbackStep(), ExpressionContext())
            assertFalse(failed.success)
            assertTrue(logs.lines.any { "rollback unavailable" in it })

            assertFailsWith<CancellationException> {
                executor(
                    root, logs, ThrowingCiApi(CancellationException("stopped")), Uuid.random(),
                ).execute(rollbackStep(), ExpressionContext())
            }
        }
    }

    @Test
    fun `verify-deployment polls until healthy using coroutine time`() = runTest {
        withRoot("verify-healthy") { root ->
            val logs = TestLogBuffer()
            val api = RecordingCiApi(healthStatuses = ArrayDeque(listOf("DEGRADED", "HEALTHY")))
            val jobId = Uuid.random()

            val result = executor(root, logs, api, jobId).execute(
                verifyStep(
                    "timeoutSeconds" to "5",
                    "intervalSeconds" to "1",
                    "repository" to "operations",
                ),
                ExpressionContext(),
            )

            assertTrue(result.success, logs.lines.joinToString("\n"))
            assertEquals(2, api.healthCalls)
            assertEquals(jobId, api.healthJobId)
            assertEquals("production", api.healthEnvironment)
            assertEquals("operations", api.healthRepository)
            assertTrue(logs.lines.any { "DEGRADED" in it })
            assertTrue(logs.lines.any { "is healthy" in it })

            val blankRepositoryApi = RecordingCiApi()
            val blankRepository = executor(root, logs, blankRepositoryApi, jobId).execute(
                verifyStep("repository" to " "),
                ExpressionContext(),
            )
            assertTrue(blankRepository.success)
            assertNull(blankRepositoryApi.healthRepository)
        }
    }

    @Test
    fun `verify-deployment rejects invalid inputs and missing server context`() = runTest {
        withRoot("verify-invalid") { root ->
            val logs = TestLogBuffer()
            val api = RecordingCiApi()
            val invalid = listOf(
                emptyMap(),
                mapOf("environment" to " "),
                mapOf("environment" to "production", "timeoutSeconds" to "nope"),
                mapOf("environment" to "production", "timeoutSeconds" to "0"),
                mapOf("environment" to "production", "timeoutSeconds" to "-1"),
                mapOf("environment" to "production", "intervalSeconds" to "nope"),
                mapOf("environment" to "production", "intervalSeconds" to "0"),
                mapOf("environment" to "production", "intervalSeconds" to "-1"),
            )
            invalid.forEach { with ->
                assertFalse(
                    executor(root, logs, api, Uuid.random()).execute(
                        StepDefinition(name = "Invalid verify", uses = "verify-deployment", with = with),
                        ExpressionContext(),
                    ).success,
                )
            }
            assertEquals(0, api.healthCalls)

            assertFalse(executor(root, logs, null, Uuid.random()).execute(verifyStep(), ExpressionContext()).success)
            assertFalse(executor(root, logs, api, null).execute(verifyStep(), ExpressionContext()).success)
        }
    }

    @Test
    fun `verify-deployment reports timeout callback cancellation and server failures`() = runTest {
        withRoot("verify-failure") { root ->
            val logs = TestLogBuffer()
            val timeoutApi = RecordingCiApi(defaultHealthStatus = "UNKNOWN")
            val timeout = executor(root, logs, timeoutApi, Uuid.random()).execute(
                verifyStep("timeoutSeconds" to "2", "intervalSeconds" to "1"),
                ExpressionContext(),
            )
            assertFalse(timeout.success)
            assertTrue(logs.lines.any { "within 2s" in it && "UNKNOWN" in it })

            val cancelledApi = RecordingCiApi()
            val cancelled = executor(
                root, logs, cancelledApi, Uuid.random(), cancelled = { true },
            ).execute(verifyStep(), ExpressionContext())
            assertTrue(cancelled.cancelled)
            assertEquals(130, cancelled.exitCode)
            assertEquals(0, cancelledApi.healthCalls)

            val failed = executor(
                root, logs, ThrowingCiApi(IllegalStateException("probe unavailable")), Uuid.random(),
            ).execute(verifyStep(), ExpressionContext())
            assertFalse(failed.success)
            assertTrue(logs.lines.any { "probe unavailable" in it })

            assertFailsWith<CancellationException> {
                executor(
                    root, logs, ThrowingCiApi(CancellationException("stopped")), Uuid.random(),
                ).execute(verifyStep(), ExpressionContext())
            }
        }
    }

    @Test
    fun `mark-released requires context succeeds only on true and propagates cancellation`() = runTest {
        withRoot("mark-released") { root ->
            val logs = TestLogBuffer()
            val api = RecordingCiApi(markReleased = true)
            val jobId = Uuid.random()
            assertTrue(executor(root, logs, api, jobId).execute(markReleasedStep(), ExpressionContext()).success)
            assertEquals(jobId, api.markReleasedJobId)
            assertTrue(logs.lines.any { "Release marked released" in it })

            assertFalse(executor(root, logs, null, jobId).execute(markReleasedStep(), ExpressionContext()).success)
            assertFalse(executor(root, logs, api, null).execute(markReleasedStep(), ExpressionContext()).success)
            assertFalse(
                executor(root, logs, RecordingCiApi(markReleased = false), jobId)
                    .execute(markReleasedStep(), ExpressionContext()).success,
            )
            assertFalse(
                executor(root, logs, ThrowingCiApi(IllegalStateException("release unavailable")), jobId)
                    .execute(markReleasedStep(), ExpressionContext()).success,
            )
            assertFailsWith<CancellationException> {
                executor(root, logs, ThrowingCiApi(CancellationException("stopped")), jobId)
                    .execute(markReleasedStep(), ExpressionContext())
            }
        }
    }

    @Test
    fun `generate-release-notes requires context succeeds only on true and propagates cancellation`() = runTest {
        withRoot("generate-release-notes") { root ->
            val logs = TestLogBuffer()
            val api = RecordingCiApi(generateReleaseNotes = true)
            val jobId = Uuid.random()
            assertTrue(executor(root, logs, api, jobId).execute(generateReleaseNotesStep(), ExpressionContext()).success)
            assertEquals(jobId, api.generateReleaseNotesJobId)
            assertTrue(logs.lines.any { "release notes generated" in it })

            assertFalse(executor(root, logs, null, jobId).execute(generateReleaseNotesStep(), ExpressionContext()).success)
            assertFalse(executor(root, logs, api, null).execute(generateReleaseNotesStep(), ExpressionContext()).success)
            assertFalse(
                executor(root, logs, RecordingCiApi(generateReleaseNotes = false), jobId)
                    .execute(generateReleaseNotesStep(), ExpressionContext()).success,
            )
            assertFalse(
                executor(root, logs, ThrowingCiApi(IllegalStateException("notes unavailable")), jobId)
                    .execute(generateReleaseNotesStep(), ExpressionContext()).success,
            )
            assertFailsWith<CancellationException> {
                executor(root, logs, ThrowingCiApi(CancellationException("stopped")), jobId)
                    .execute(generateReleaseNotesStep(), ExpressionContext())
            }
        }
    }

    private fun rollbackStep() = StepDefinition(
        name = "Rollback", uses = "rollback", with = mapOf("environment" to "production"),
    )

    private fun verifyStep(vararg extra: Pair<String, String>) = StepDefinition(
        name = "Verify",
        uses = "verify-deployment",
        with = mapOf("environment" to "production") + extra,
    )

    private fun markReleasedStep() = StepDefinition(name = "Mark released", uses = "mark-released")

    private fun generateReleaseNotesStep() =
        StepDefinition(name = "Generate release notes", uses = "generate-release-notes")

    private fun executor(
        root: File,
        logs: TestLogBuffer,
        api: CiApi?,
        jobId: Uuid?,
        cancelled: () -> Boolean = { false },
    ): StepExecutor {
        val workDir = File(root, "work").apply { mkdirs() }
        return StepExecutor(
            workDir = workDir,
            serverUrl = "http://localhost:0",
            agentToken = "token",
            commitSha = "abc",
            ref = "refs/tags/1.0.0",
            repositoryId = Uuid.random().toString(),
            api = api,
            jobId = jobId,
            env = emptyMap(),
            secrets = emptyMap(),
            logBuffer = logs,
            sharedEnvFile = File(root, ".bosca_env").also { it.createNewFile() },
            sharedPathFile = File(root, ".bosca_path").also { it.createNewFile() },
            cancelled = cancelled,
        )
    }

    private suspend fun withRoot(name: String, block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory(name).toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class RecordingCiApi(
        private val healthStatuses: ArrayDeque<String> = ArrayDeque(),
        private val defaultHealthStatus: String = "HEALTHY",
        private val markReleased: Boolean = true,
        private val generateReleaseNotes: Boolean = true,
    ) : CiApi(NetworkClient("http://localhost:0")) {
        var rollbackCalls = 0
        var rollbackJobId: Uuid? = null
        var rollbackEnvironment: String? = null
        var rollbackRepository: String? = null
        var rollbackTarget: String? = null
        var rollbackRevision: Int? = null
        var rollbackOverrides: JsonElement? = null
        var healthCalls = 0
        var healthJobId: Uuid? = null
        var healthEnvironment: String? = null
        var healthRepository: String? = null
        var markReleasedJobId: Uuid? = null
        var generateReleaseNotesJobId: Uuid? = null

        override suspend fun rollbackFromJob(
            jobId: Uuid,
            environmentKey: String,
            repository: String?,
            target: String?,
            toRevision: Int?,
            overrides: JsonElement?,
        ): RollbackFromJobData.Git.RollbackFromJob {
            rollbackCalls++
            rollbackJobId = jobId
            rollbackEnvironment = environmentKey
            rollbackRepository = repository
            rollbackTarget = target
            rollbackRevision = toRevision
            rollbackOverrides = overrides
            return RollbackFromJobData.Git.RollbackFromJob("bosca@4", "ROLLED_BACK")
        }

        override suspend fun jobDeploymentHealth(
            jobId: Uuid,
            environmentKey: String,
            repository: String?,
        ): String {
            healthCalls++
            healthJobId = jobId
            healthEnvironment = environmentKey
            healthRepository = repository
            return if (healthStatuses.isEmpty()) defaultHealthStatus else healthStatuses.removeFirst()
        }

        override suspend fun markReleasedFromJob(jobId: Uuid): Boolean {
            markReleasedJobId = jobId
            return markReleased
        }

        override suspend fun generateReleaseNotesFromJob(jobId: Uuid): Boolean {
            generateReleaseNotesJobId = jobId
            return generateReleaseNotes
        }
    }

    private class ThrowingCiApi(
        private val failure: Exception,
    ) : CiApi(NetworkClient("http://localhost:0")) {
        override suspend fun rollbackFromJob(
            jobId: Uuid,
            environmentKey: String,
            repository: String?,
            target: String?,
            toRevision: Int?,
            overrides: JsonElement?,
        ): Nothing = throw failure

        override suspend fun jobDeploymentHealth(
            jobId: Uuid,
            environmentKey: String,
            repository: String?,
        ): Nothing = throw failure

        override suspend fun markReleasedFromJob(jobId: Uuid): Nothing = throw failure

        override suspend fun generateReleaseNotesFromJob(jobId: Uuid): Nothing = throw failure
    }
}
