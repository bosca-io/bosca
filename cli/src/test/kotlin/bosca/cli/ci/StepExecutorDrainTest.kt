package bosca.cli.ci

import bosca.cli.api.NetworkClient
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Regression coverage for log-tail loss during step failures.
 *
 * A step that backgrounds a child process leaves that descendant holding the
 * stdout/stderr pipe open. The reader threads only exit on EOF, so without the
 * descendant reap + forced-drain fallback in [StepExecutor.executeShellCommand]
 * the final lines — typically the error output of a failed step — never reach
 * the log buffer and so never make it to the server (and Studio).
 */
class StepExecutorDrainTest {

    private val zero = Uuid.parse("00000000-0000-0000-0000-000000000000")

    /**
     * Records every line in-memory instead of shipping it over the network, so
     * the assertions can inspect exactly what the executor handed to the buffer.
     * Reader threads call in from multiple threads, hence the synchronized list.
     */
    private class CapturingLogBuffer : LogBuffer(
        api = NoNetworkCiApi,
        repositoryId = Uuid.parse("00000000-0000-0000-0000-000000000000"),
        runId = Uuid.parse("00000000-0000-0000-0000-000000000000"),
        jobId = Uuid.parse("00000000-0000-0000-0000-000000000000"),
        stepId = Uuid.parse("00000000-0000-0000-0000-000000000000"),
        secretValues = emptySet(),
    ) {
        private val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())

        override fun addLine(content: String, stream: String) {
            recorded.add(content)
        }

        override suspend fun add(content: String, stream: String) = addLine(content, stream)
        override suspend fun flush() {}
        override suspend fun close() {}

        fun contents(): List<String> = synchronized(recorded) { recorded.toList() }
    }

    private fun newExecutor(workDir: File, buffer: LogBuffer, drainTimeoutMs: Long) = StepExecutor(
        workDir = workDir,
        serverUrl = "http://localhost:0",
        agentToken = "",
        commitSha = "deadbeef",
        ref = "refs/heads/main",
        repositoryId = zero.toString(),
        env = emptyMap(),
        secrets = emptyMap(),
        logBuffer = buffer,
        drainTimeoutMs = drainTimeoutMs,
    )

    private fun tempWorkDir(): File =
        File.createTempFile("bosca-step", "").apply { delete(); mkdirs() }

    @Test
    fun `clean step drains fully without a truncation marker`() = runBlocking {
        val workDir = tempWorkDir()
        try {
            val buffer = CapturingLogBuffer()
            val executor = newExecutor(workDir, buffer, drainTimeoutMs = 5000)

            val step = StepDefinition(name = "clean", run = "echo hello; echo world")
            val result = executor.execute(step, ExpressionContext())

            val contents = buffer.contents()
            assertTrue(contents.contains("hello"), "Expected hello in $contents")
            assertTrue(contents.contains("world"), "Expected world in $contents")
            assertTrue(
                contents.none { it.contains("did not reach EOF") },
                "A clean exit must not emit a truncation marker: $contents",
            )
            assertTrue(result.success)
        } finally {
            workDir.deleteRecursively()
        }
    }
}

private object NoNetworkCiApi : CiApi(NetworkClient("http://localhost:0"))
