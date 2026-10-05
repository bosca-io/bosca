package bosca.cli.ci

import bosca.cli.api.NetworkClient
import bosca.graphql.gen.AllocateBuildNumberFromJobData
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlin.coroutines.cancellation.CancellationException

class StepExecutorAllocateBuildNumberTest {

    @Test
    fun `iOS allocation is exported to later build steps using the durable bundle value`() = runTest {
        val root = Files.createTempDirectory("allocate-build-number-step").toFile()
        try {
            val api = RecordingCiApi()
            val logs = TestLogBuffer()
            val jobId = Uuid.random()
            val sharedEnv = File(root, ".bosca_env").also { it.createNewFile() }
            val executor = executor(root, sharedEnv, logs, api, jobId)

            val result = executor.execute(
                StepDefinition(
                    name = "Allocate iOS build",
                    uses = "allocate-build-number",
                    with = mapOf(
                        "platform" to "ios",
                        "applicationId" to "com.example.app",
                        "version" to "6.2.0",
                        "key" to "release",
                        "minimum" to "7",
                    ),
                ),
                ExpressionContext(),
            )

            assertTrue(result.success, logs.lines.joinToString("\n"))
            assertEquals(jobId, api.jobId)
            assertEquals("ios", api.platform)
            assertEquals("com.example.app", api.applicationId)
            assertEquals("6.2.0", api.sourceVersion)
            assertEquals("release", api.buildKey)
            assertEquals("7", api.minimum)
            assertEquals("APP_BUILD_NUMBER=7\nIOS_BUILD_NUMBER=7\n", sharedEnv.readText())
            assertTrue(logs.lines.any { "Allocated IOS_BUILD_NUMBER=7" in it })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `allocation rejects an unsupported platform without calling the server`() = runTest {
        val root = Files.createTempDirectory("allocate-build-number-invalid").toFile()
        try {
            val logs = TestLogBuffer()
            val sharedEnv = File(root, ".bosca_env").also { it.createNewFile() }

            val result = executor(root, sharedEnv, logs, null, null).execute(
                StepDefinition(
                    name = "Allocate invalid build",
                    uses = "allocate-build-number",
                    with = mapOf(
                        "platform" to "desktop",
                        "applicationId" to "com.example.app",
                        "version" to "6.2.0",
                    ),
                ),
                ExpressionContext(),
            )

            assertTrue(!result.success)
            assertTrue(logs.lines.any { "android" in it && "ios" in it })
            assertEquals("", sharedEnv.readText())

            val missing = executor(root, sharedEnv, logs, null, null).execute(
                StepDefinition(
                    name = "Allocate missing platform",
                    uses = "allocate-build-number",
                    with = mapOf("applicationId" to "com.example.app", "version" to "6.2.0"),
                ),
                ExpressionContext(),
            )
            assertFalse(missing.success)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Android retry exports the durable version code and reports reuse`() = runTest {
        withFixture("allocate-build-number-android") { root, sharedEnv, logs ->
            val api = RecordingCiApi(number = 42, value = "42", reused = true)
            val result = executor(root, sharedEnv, logs, api, Uuid.random()).execute(
                StepDefinition(
                    name = "Allocate Android build",
                    uses = "allocate-build-number",
                    with = mapOf(
                        "platform" to " ANDROID ",
                        "applicationId" to "io.bosca.app",
                        "version" to "6.2.0",
                    ),
                ),
                ExpressionContext(),
            )

            assertTrue(result.success, logs.lines.joinToString("\n"))
            assertEquals("APP_BUILD_NUMBER=42\nANDROID_VERSION_CODE=42\n", sharedEnv.readText())
            assertTrue(logs.lines.any { "Reused ANDROID_VERSION_CODE=42" in it })
        }
    }

    @Test
    fun `allocation rejects each missing required input before calling the server`() = runTest {
        withFixture("allocate-build-number-required") { root, sharedEnv, logs ->
            val api = RecordingCiApi()
            val jobId = Uuid.random()
            val cases = listOf(
                mapOf("platform" to "android", "version" to "6.2.0"),
                mapOf("platform" to "android", "applicationId" to " ", "version" to "6.2.0"),
                mapOf("platform" to "android", "applicationId" to "io.bosca.app"),
                mapOf("platform" to "android", "applicationId" to "io.bosca.app", "version" to " "),
            )

            cases.forEach { with ->
                val result = executor(root, sharedEnv, logs, api, jobId).execute(
                    StepDefinition("Allocate invalid build", uses = "allocate-build-number", with = with),
                    ExpressionContext(),
                )
                assertFalse(result.success)
            }

            assertFalse(api.called)
            assertTrue(logs.lines.any { "applicationId" in it })
            assertTrue(logs.lines.any { "version" in it })
            assertEquals("", sharedEnv.readText())
        }
    }

    @Test
    fun `allocation fails closed when runner server context is unavailable`() = runTest {
        withFixture("allocate-build-number-context") { root, sharedEnv, logs ->
            val valid = StepDefinition(
                "Allocate build",
                uses = "allocate-build-number",
                with = mapOf(
                    "platform" to "android",
                    "applicationId" to "io.bosca.app",
                    "version" to "6.2.0",
                ),
            )

            assertFalse(executor(root, sharedEnv, logs, null, Uuid.random()).execute(valid, ExpressionContext()).success)
            assertFalse(executor(root, sharedEnv, logs, RecordingCiApi(), null).execute(valid, ExpressionContext()).success)
            assertTrue(logs.lines.count { "server API" in it } == 2)
            assertEquals("", sharedEnv.readText())
        }
    }

    @Test
    fun `allocation reports ordinary server failures without writing a partial environment`() = runTest {
        withFixture("allocate-build-number-failure") { root, sharedEnv, logs ->
            val result = executor(
                root, sharedEnv, logs, FailingCiApi(IllegalStateException("counter unavailable")), Uuid.random(),
            ).execute(validAndroidStep(), ExpressionContext())

            assertFalse(result.success)
            assertEquals("", sharedEnv.readText())
            assertTrue(logs.lines.any { "counter unavailable" in it })
        }
    }

    @Test
    fun `allocation never swallows coroutine cancellation`() = runTest {
        withFixture("allocate-build-number-cancel") { root, sharedEnv, logs ->
            assertFailsWith<CancellationException> {
                executor(
                    root, sharedEnv, logs, FailingCiApi(CancellationException("stopped")), Uuid.random(),
                ).execute(validAndroidStep(), ExpressionContext())
            }
            assertEquals("", sharedEnv.readText())
        }
    }

    private fun validAndroidStep() = StepDefinition(
        "Allocate build",
        uses = "allocate-build-number",
        with = mapOf(
            "platform" to "android",
            "applicationId" to "io.bosca.app",
            "version" to "6.2.0",
        ),
    )

    private suspend fun withFixture(
        prefix: String,
        block: suspend (root: File, sharedEnv: File, logs: TestLogBuffer) -> Unit,
    ) {
        val root = Files.createTempDirectory(prefix).toFile()
        try {
            block(root, File(root, ".bosca_env").also { it.createNewFile() }, TestLogBuffer())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun executor(
        root: File,
        sharedEnv: File,
        logs: TestLogBuffer,
        api: CiApi?,
        jobId: Uuid?,
    ): StepExecutor = StepExecutor(
        workDir = File(root, "work").apply { mkdirs() },
        serverUrl = "http://localhost:0",
        agentToken = "token",
        commitSha = "a".repeat(40),
        ref = "refs/tags/6.2.0",
        repositoryId = Uuid.random().toString(),
        api = api,
        jobId = jobId,
        env = emptyMap(),
        secrets = emptyMap(),
        logBuffer = logs,
        sharedEnvFile = sharedEnv,
        sharedPathFile = File(root, ".bosca_path").also { it.createNewFile() },
    )

    private class RecordingCiApi(
        private val number: Long = 7,
        private val value: String = "7",
        private val reused: Boolean = false,
    ) : CiApi(NetworkClient("http://localhost:0")) {
        var called = false
        lateinit var jobId: Uuid
        lateinit var platform: String
        lateinit var applicationId: String
        lateinit var sourceVersion: String
        var buildKey: String? = null
        var minimum: String? = null

        override suspend fun allocateBuildNumberFromJob(
            jobId: Uuid,
            platform: String,
            applicationId: String,
            sourceVersion: String,
            buildKey: String?,
            minimum: String?,
        ): AllocateBuildNumberFromJobData.Git.AllocateBuildNumberFromJob {
            called = true
            this.jobId = jobId
            this.platform = platform
            this.applicationId = applicationId
            this.sourceVersion = sourceVersion
            this.buildKey = buildKey
            this.minimum = minimum
            return AllocateBuildNumberFromJobData.Git.AllocateBuildNumberFromJob(
                number = number,
                value = value,
                reused = reused,
            )
        }
    }

    private class FailingCiApi(
        private val failure: Exception,
    ) : CiApi(NetworkClient("http://localhost:0")) {
        override suspend fun allocateBuildNumberFromJob(
            jobId: Uuid,
            platform: String,
            applicationId: String,
            sourceVersion: String,
            buildKey: String?,
            minimum: String?,
        ): AllocateBuildNumberFromJobData.Git.AllocateBuildNumberFromJob = throw failure
    }
}
