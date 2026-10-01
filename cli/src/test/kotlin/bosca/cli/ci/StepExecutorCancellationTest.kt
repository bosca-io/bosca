package bosca.cli.ci

import bosca.cli.api.NetworkClient
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * job control: a cancelled job must stop its IN-FLIGHT step, not just skip the remaining
 * ones. The executor polls the runner's cancellation signal while the step's process runs and tears
 * the process tree down the moment it flips — without it, a cancelled 40-minute build keeps burning
 * the agent until completion.
 */
class StepExecutorCancellationTest {

    private val zero = Uuid.parse("00000000-0000-0000-0000-000000000000")

    private class CapturingLogBuffer : LogBuffer(
        api = CancellationNoNetworkCiApi,
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

    private fun tempWorkDir(): File =
        File.createTempFile("bosca-step", "").apply { delete(); mkdirs() }

    private fun newExecutor(workDir: File, buffer: LogBuffer, cancelled: () -> Boolean) = StepExecutor(
        workDir = workDir,
        serverUrl = "http://localhost:0",
        agentToken = "",
        commitSha = "deadbeef",
        ref = "refs/heads/main",
        repositoryId = zero.toString(),
        env = emptyMap(),
        secrets = emptyMap(),
        logBuffer = buffer,
        drainTimeoutMs = 2000,
        cancelled = cancelled,
        cancelPollMs = 50,
    )

    @Test
    fun `a cancellation signal stops a running step and reports it cancelled`() = runBlocking {
        val workDir = tempWorkDir()
        try {
            val buffer = CapturingLogBuffer()
            val cancelAtMs = System.currentTimeMillis() + 300
            val executor = newExecutor(workDir, buffer) { System.currentTimeMillis() >= cancelAtMs }

            val start = System.currentTimeMillis()
            val result = executor.execute(StepDefinition(name = "long", run = "sleep 60"), ExpressionContext())
            val elapsedMs = System.currentTimeMillis() - start

            assertTrue(result.cancelled, "result should be marked cancelled")
            assertFalse(result.success)
            assertTrue(elapsedMs < 30_000, "cancellation should interrupt the sleep, took ${elapsedMs}ms")
            assertTrue(
                buffer.contents().any { it.contains("cancelled") },
                "the log should record the cancellation: ${buffer.contents()}",
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `a step that finishes before any cancellation completes normally`() = runBlocking {
        val workDir = tempWorkDir()
        try {
            val buffer = CapturingLogBuffer()
            val executor = newExecutor(workDir, buffer) { false }

            val result = executor.execute(StepDefinition(name = "quick", run = "echo done"), ExpressionContext())

            assertTrue(result.success)
            assertFalse(result.cancelled)
            assertTrue(buffer.contents().contains("done"))
        } finally {
            workDir.deleteRecursively()
        }
    }
}

private object CancellationNoNetworkCiApi : CiApi(NetworkClient("http://localhost:0"))
