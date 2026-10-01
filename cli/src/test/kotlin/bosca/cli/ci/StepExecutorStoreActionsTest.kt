package bosca.cli.ci

import bosca.cli.api.NetworkClient
import bosca.graphql.gen.AppStoreReviewFromJobData
import bosca.graphql.gen.GitReleaseAppStoreReviewMode
import bosca.graphql.gen.StoreHealthFromJobData
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class StepExecutorStoreActionsTest {

    @Test
    fun `app-store-review polls typed beta state until approved and preserves target context`() = runTest {
        withRoot("app-store-review") { root ->
            val logs = TestLogBuffer()
            val api = RecordingStoreApi(
                reviews = ArrayDeque(
                    listOf(
                        review("IN_REVIEW", complete = false, approved = false),
                        review("APPROVED", complete = true, approved = true),
                    ),
                ),
            )
            val jobId = Uuid.random()

            val result = executor(root, logs, api, jobId).execute(
                StepDefinition(
                    name = "Review",
                    uses = "app-store-review",
                    with = mapOf(
                        "environment" to "production",
                        "mode" to "beta",
                        "repository" to "mobile",
                        "target" to "app_store",
                        "timeoutSeconds" to "10",
                        "intervalSeconds" to "1",
                    ),
                ),
                ExpressionContext(),
            )

            assertTrue(result.success, logs.lines.joinToString("\n"))
            assertEquals(2, api.reviewCalls)
            assertEquals(jobId, api.jobId)
            assertEquals("production", api.environment)
            assertEquals(GitReleaseAppStoreReviewMode.BETA, api.mode)
            assertEquals("mobile", api.repository)
            assertEquals("app_store", api.target)
            assertTrue(logs.lines.any { "IN_REVIEW" in it })
            assertTrue(logs.lines.any { "approved" in it })
        }
    }

    @Test
    fun `app-store-review fails on rejection invalid input missing context and cancellation`() = runTest {
        withRoot("app-store-review-errors") { root ->
            val logs = TestLogBuffer()
            val jobId = Uuid.random()
            val rejected = RecordingStoreApi(
                reviews = ArrayDeque(listOf(review("REJECTED", complete = true, approved = false))),
            )
            assertFalse(executor(root, logs, rejected, jobId).execute(reviewStep(), ExpressionContext()).success)

            listOf(
                mapOf("mode" to "beta"),
                mapOf("environment" to "production", "mode" to "unknown"),
                mapOf("environment" to "production", "mode" to "beta", "timeoutSeconds" to "0"),
                mapOf("environment" to "production", "mode" to "beta", "intervalSeconds" to "bad"),
            ).forEach { with ->
                assertFalse(
                    executor(root, logs, RecordingStoreApi(), jobId).execute(
                        StepDefinition("Invalid", uses = "app-store-review", with = with), ExpressionContext(),
                    ).success,
                )
            }
            assertFalse(executor(root, logs, null, jobId).execute(reviewStep(), ExpressionContext()).success)
            assertFalse(executor(root, logs, RecordingStoreApi(), null).execute(reviewStep(), ExpressionContext()).success)

            val cancelled = executor(
                root, logs, RecordingStoreApi(), jobId, cancelled = { true },
            ).execute(reviewStep(), ExpressionContext())
            assertTrue(cancelled.cancelled)

            assertFailsWith<CancellationException> {
                executor(
                    root, logs, ThrowingStoreApi(CancellationException("stopped")), jobId,
                ).execute(reviewStep(), ExpressionContext())
            }
            assertFalse(
                executor(
                    root, logs, ThrowingStoreApi(IllegalStateException("ASC unavailable")), jobId,
                ).execute(reviewStep(), ExpressionContext()).success,
            )
            assertTrue(logs.lines.any { "ASC unavailable" in it })
        }
    }

    @Test
    fun `verify-store-health performs one typed observation and parses release windows`() = runTest {
        withRoot("store-health") { root ->
            val logs = TestLogBuffer()
            val api = RecordingStoreApi(health = health(0.25, 0.5, 604_800, healthy = true))
            val jobId = Uuid.random()

            val result = executor(root, logs, api, jobId).execute(
                StepDefinition(
                    "Health",
                    uses = "verify-store-health",
                    with = mapOf(
                        "environment" to "production",
                        "maxCrashRate" to "0.5",
                        "window" to "7d",
                        "repository" to "mobile",
                        "target" to "google_play",
                    ),
                ),
                ExpressionContext(),
            )

            assertTrue(result.success)
            assertEquals(1, api.healthCalls)
            assertEquals(604_800, api.windowSeconds)
            assertEquals(0.5, api.maxCrashRate)
            assertTrue(logs.lines.any { "0.25%" in it && "passed" in it })

            api.health = health(0.75, 0.5, 1_800, healthy = false)
            assertFalse(
                executor(root, logs, api, jobId).execute(
                    StepDefinition(
                        "Health", uses = "verify-store-health",
                        with = mapOf("environment" to "production", "maxCrashRate" to "0.5", "window" to "30m"),
                    ),
                    ExpressionContext(),
                ).success,
            )
            assertEquals(1_800, api.windowSeconds)
        }
    }

    @Test
    fun `verify-store-health rejects invalid input and never swallows server cancellation`() = runTest {
        withRoot("store-health-errors") { root ->
            val logs = TestLogBuffer()
            val jobId = Uuid.random()
            val invalid = listOf(
                mapOf("maxCrashRate" to "0.5"),
                mapOf("environment" to "production", "maxCrashRate" to "NaN"),
                mapOf("environment" to "production", "maxCrashRate" to "-0.1"),
                mapOf("environment" to "production", "maxCrashRate" to "100.1"),
                mapOf("environment" to "production", "maxCrashRate" to "0.5", "window" to "0h"),
                mapOf("environment" to "production", "maxCrashRate" to "0.5", "window" to "huge"),
                mapOf("environment" to "production", "maxCrashRate" to "0.5", "window" to "999999999999999999d"),
            )
            invalid.forEach { with ->
                assertFalse(
                    executor(root, logs, RecordingStoreApi(), jobId).execute(
                        StepDefinition("Invalid", uses = "verify-store-health", with = with), ExpressionContext(),
                    ).success,
                )
            }
            assertFalse(executor(root, logs, null, jobId).execute(healthStep(), ExpressionContext()).success)
            assertFalse(executor(root, logs, RecordingStoreApi(), null).execute(healthStep(), ExpressionContext()).success)
            assertFalse(
                executor(root, logs, ThrowingStoreApi(IllegalStateException("vitals unavailable")), jobId)
                    .execute(healthStep(), ExpressionContext()).success,
            )
            assertFailsWith<CancellationException> {
                executor(root, logs, ThrowingStoreApi(CancellationException("stopped")), jobId)
                    .execute(healthStep(), ExpressionContext())
            }
        }
    }

    @Test
    fun `app-store-upload downloads the IPA writes a private key for Transporter and cleans everything`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse.Builder().code(200).body("IPA-BYTES").build())
            withRoot("app-store-upload") { root ->
                val logs = TestLogBuffer()
                val commands = mutableListOf<String>()
                var temporaryDirectory: File? = null
                val result = executor(
                    root = root,
                    logs = logs,
                    api = null,
                    jobId = null,
                    registryUrl = server.url("").toString(),
                    secrets = mapOf(
                        "asc" to """{"issuerId":"69a6de00-0000-0000-0000-000000000000","keyId":"ABC123DEFG","privateKey":"PRIVATE-KEY-CONTENT"}""",
                    ),
                    actionShellExecutor = { command, environment ->
                        assertTrue(environment.isEmpty())
                        commands += command
                        temporaryDirectory = File(root, "work").listFiles().orEmpty()
                            .single { it.name.startsWith(".bosca-app-store-") }
                        assertEquals("IPA-BYTES", File(temporaryDirectory, "app.ipa").readText())
                        assertEquals(
                            "PRIVATE-KEY-CONTENT",
                            File(temporaryDirectory, "private_keys/AuthKey_ABC123DEFG.p8").readText(),
                        )
                        StepResult(true, 0)
                    },
                ).execute(uploadStep(), ExpressionContext())

                assertTrue(result.success, logs.lines.joinToString("\n"))
                assertEquals(1, commands.size)
                assertTrue("xcrun iTMSTransporter -m upload" in commands.single())
                assertTrue("ABC123DEFG" in commands.single())
                assertFalse("PRIVATE-KEY-CONTENT" in commands.single())
                assertFalse(requireNotNull(temporaryDirectory).exists())
                val request = server.takeRequest()
                assertEquals("/raw/bosca-raw/mobile/1.4.0/app.ipa", request.target)
                assertEquals(Credentials.basic("api_token", "agent-token"), request.headers["Authorization"])
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `app-store-upload validates macOS coordinates and credentials before Transporter`() = runTest {
        withRoot("app-store-upload-invalid") { root ->
            val logs = TestLogBuffer()
            var calls = 0
            suspend fun run(
                with: Map<String, String>,
                registryUrl: String = "http://localhost:1",
                secrets: Map<String, String> = mapOf("asc" to validCredential()),
                os: String = "Mac OS X",
            ) = executor(
                root, logs, null, null, registryUrl, secrets,
                actionShellExecutor = { _, _ -> calls++; StepResult(true, 0) },
                operatingSystem = os,
            ).execute(StepDefinition("Upload", uses = "app-store-upload", with = with), ExpressionContext())

            assertFalse(run(uploadStep().with, os = "Linux").success)
            assertFalse(run(uploadStep().with, registryUrl = "").success)
            listOf("namespace", "name", "version", "filename", "credentialSecret").forEach { key ->
                assertFalse(run(uploadStep().with - key).success)
            }
            assertFalse(run(uploadStep().with + ("filename" to "app.zip")).success)
            assertFalse(run(uploadStep().with + ("namespace" to "../unsafe")).success)
            assertFalse(run(uploadStep().with + ("name" to "..")).success)
            assertFalse(run(uploadStep().with + ("version" to ".")).success)
            assertFalse(run(uploadStep().with, secrets = emptyMap()).success)
            assertFalse(run(uploadStep().with, secrets = mapOf("asc" to "not-json")).success)
            assertFalse(run(uploadStep().with, secrets = mapOf("asc" to validCredential(issuer = "bad"))).success)
            assertFalse(run(uploadStep().with, secrets = mapOf("asc" to validCredential(keyId = "bad"))).success)
            assertFalse(run(uploadStep().with, secrets = mapOf("asc" to validCredential(privateKey = ""))).success)
            assertEquals(0, calls)
        }
    }

    private fun reviewStep() = StepDefinition(
        "Review", uses = "app-store-review",
        with = mapOf("environment" to "production", "mode" to "appstore", "intervalSeconds" to "1"),
    )

    private fun healthStep() = StepDefinition(
        "Health", uses = "verify-store-health",
        with = mapOf("environment" to "production", "maxCrashRate" to "0.5", "window" to "24h"),
    )

    private fun uploadStep() = StepDefinition(
        "Upload", uses = "app-store-upload",
        with = mapOf(
            "namespace" to "bosca-raw",
            "name" to "mobile",
            "version" to "1.4.0",
            "filename" to "app.ipa",
            "credentialSecret" to "asc",
        ),
    )

    private fun executor(
        root: File,
        logs: TestLogBuffer,
        api: CiApi?,
        jobId: Uuid?,
        registryUrl: String = "",
        secrets: Map<String, String> = emptyMap(),
        cancelled: () -> Boolean = { false },
        actionShellExecutor: (suspend (String, Map<String, String>) -> StepResult)? = null,
        operatingSystem: String = "Mac OS X",
    ) = StepExecutor(
        workDir = File(root, "work").apply { mkdirs() },
        serverUrl = "http://localhost:0",
        agentToken = "agent-token",
        registryUrl = registryUrl,
        commitSha = "abc",
        ref = "refs/tags/1.4.0",
        repositoryId = Uuid.random().toString(),
        api = api,
        jobId = jobId,
        env = emptyMap(),
        secrets = secrets,
        logBuffer = logs,
        sharedEnvFile = File(root, ".bosca_env").also { it.createNewFile() },
        sharedPathFile = File(root, ".bosca_path").also { it.createNewFile() },
        cancelled = cancelled,
        actionShellExecutor = actionShellExecutor,
        operatingSystem = operatingSystem,
    )

    private suspend fun withRoot(name: String, block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory(name).toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validCredential(
        issuer: String = "69a6de00-0000-0000-0000-000000000000",
        keyId: String = "ABC123DEFG",
        privateKey: String = "PRIVATE",
    ) = """{"issuerId":"$issuer","keyId":"$keyId","privateKey":"$privateKey"}"""

    private class RecordingStoreApi(
        private val reviews: ArrayDeque<AppStoreReviewFromJobData.Git.AppStoreReviewFromJob> = ArrayDeque(),
        var health: StoreHealthFromJobData.Git.StoreHealthFromJob = health(0.25, 0.5, 86_400, true),
    ) : CiApi(NetworkClient("http://localhost:0")) {
        var reviewCalls = 0
        var healthCalls = 0
        lateinit var jobId: Uuid
        lateinit var environment: String
        lateinit var mode: GitReleaseAppStoreReviewMode
        var repository: String? = null
        var target: String? = null
        var maxCrashRate = Double.NaN
        var windowSeconds = -1L

        override suspend fun appStoreReviewFromJob(
            jobId: Uuid,
            environmentKey: String,
            mode: GitReleaseAppStoreReviewMode,
            repository: String?,
            target: String?,
        ): AppStoreReviewFromJobData.Git.AppStoreReviewFromJob {
            reviewCalls++
            this.jobId = jobId
            environment = environmentKey
            this.mode = mode
            this.repository = repository
            this.target = target
            return if (reviews.isEmpty()) review("APPROVED", true, true) else reviews.removeFirst()
        }

        override suspend fun storeHealthFromJob(
            jobId: Uuid,
            environmentKey: String,
            maxCrashRate: Double,
            windowSeconds: Long,
            repository: String?,
            target: String?,
        ): StoreHealthFromJobData.Git.StoreHealthFromJob {
            healthCalls++
            this.jobId = jobId
            environment = environmentKey
            this.maxCrashRate = maxCrashRate
            this.windowSeconds = windowSeconds
            this.repository = repository
            this.target = target
            return health
        }
    }

    private class ThrowingStoreApi(private val failure: Exception) : CiApi(NetworkClient("http://localhost:0")) {
        override suspend fun appStoreReviewFromJob(
            jobId: Uuid,
            environmentKey: String,
            mode: GitReleaseAppStoreReviewMode,
            repository: String?,
            target: String?,
        ): Nothing = throw failure

        override suspend fun storeHealthFromJob(
            jobId: Uuid,
            environmentKey: String,
            maxCrashRate: Double,
            windowSeconds: Long,
            repository: String?,
            target: String?,
        ): Nothing = throw failure
    }

    private companion object {
        fun review(state: String, complete: Boolean, approved: Boolean) =
            AppStoreReviewFromJobData.Git.AppStoreReviewFromJob(state, complete, approved)

        fun health(crashRate: Double, max: Double, window: Long, healthy: Boolean) =
            StoreHealthFromJobData.Git.StoreHealthFromJob(crashRate, max, window, healthy)
    }
}
