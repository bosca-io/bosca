package bosca.cli.ci

import bosca.cli.api.NetworkClient
import bosca.graphql.gen.LogLineInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class LogBufferTest {

    private val repoId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val runId = Uuid.parse("22222222-2222-2222-2222-222222222222")
    private val jobId = Uuid.parse("33333333-3333-3333-3333-333333333333")
    private val stepId = Uuid.parse("44444444-4444-4444-4444-444444444444")
    private val agentId = Uuid.parse("77777777-7777-7777-7777-777777777777")

    private val capturedStderr = java.io.ByteArrayOutputStream()
    private val originalStderr = System.err

    @AfterTest
    fun restoreStderr() {
        System.setErr(originalStderr)
    }

    private fun captureStderr() {
        System.setErr(java.io.PrintStream(capturedStderr))
    }

    private fun makeBuffer(api: CiApi, flushIntervalMs: Long = 50) = LogBuffer(
        api = api,
        repositoryId = repoId,
        runId = runId,
        jobId = jobId,
        stepId = stepId,
        secretValues = emptySet(),
        flushIntervalMs = flushIntervalMs,
    )

    @Test
    fun `flush uploads all buffered lines`() = runTest {
        val api = RecordingCiApi()
        val buf = makeBuffer(api)

        buf.addLine("first", "stdout")
        buf.addLine("second", "stderr")
        buf.flush()

        assertEquals(1, api.uploads.size)
        assertEquals(listOf("first", "second"), api.uploads[0].map { it.content })
        assertEquals(listOf("stdout", "stderr"), api.uploads[0].map { it.stream })

        buf.close()
    }

    @Test
    fun `flush retries on transient failure and eventually delivers`() = runTest {
        val api = RecordingCiApi(failuresBeforeSuccess = 2)
        val buf = makeBuffer(api)

        buf.addLine("payload", "stdout")
        buf.flush()

        assertEquals(3, api.attempts, "Expected 2 failed attempts followed by 1 successful")
        assertEquals(1, api.uploads.size)
        assertEquals(listOf("payload"), api.uploads[0].map { it.content })

        buf.close()
    }

    @Test
    fun `flush drops batch and warns to stderr after retry exhaustion`() = runTest {
        captureStderr()
        val api = RecordingCiApi(failuresBeforeSuccess = Int.MAX_VALUE)
        val buf = makeBuffer(api)

        buf.addLine("doomed", "stdout")
        buf.flush()

        assertEquals(3, api.attempts, "Expected exactly 3 attempts before giving up")
        assertTrue(api.uploads.isEmpty(), "No successful upload should have occurred")

        val stderr = capturedStderr.toString()
        assertTrue(stderr.contains("Dropped log batch after retry exhaustion"), "Expected drop warning on stderr but got: $stderr")
        assertTrue(stderr.contains("\"lines\":1"), "Expected line count in warning but got: $stderr")
        assertTrue(stderr.contains(stepId.toString()), "Expected stepId in warning")

        // The next flush should find an empty buffer (the failed batch was dropped).
        val api2Attempts = api.attempts
        buf.flush()
        assertEquals(api2Attempts, api.attempts, "Empty buffer should produce no further attempts")

        buf.close()
    }

    @Test
    fun `lines added while a flush is in-flight survive to next flush`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = GatedCiApi(gate)
        val buf = makeBuffer(api)

        buf.addLine("first", "stdout")

        // Start a flush that will park on the gate.
        coroutineScope {
            val flushing = async { buf.flush() }

            // Append more lines while the upload is "in flight".
            api.firstCallEntered.await()
            buf.addLine("during-upload-1", "stdout")
            buf.addLine("during-upload-2", "stdout")

            gate.complete(Unit)
            flushing.await()
        }

        // First upload should have only the pre-flight content.
        assertEquals(listOf("first"), api.uploads[0].map { it.content })

        // A second flush picks up exactly what was appended during the first upload.
        buf.flush()
        assertEquals(listOf("during-upload-1", "during-upload-2"), api.uploads[1].map { it.content })

        buf.close()
    }

    @Test
    fun `close drains pending lines and stops background flush`() = runTest {
        val api = RecordingCiApi()
        val buf = makeBuffer(api, flushIntervalMs = 10_000) // Long interval — background won't run

        buf.start()
        buf.addLine("final", "stdout")
        buf.close()

        assertEquals(1, api.uploads.size)
        assertEquals(listOf("final"), api.uploads[0].map { it.content })
    }

    @Test
    fun `close rejects further additions`() = runTest {
        val api = RecordingCiApi()
        val buf = makeBuffer(api)

        buf.addLine("kept", "stdout")
        buf.close()

        // Drop any line added after close — buffer is sealed.
        buf.addLine("ignored", "stdout")
        buf.flush()

        // Only the pre-close line should appear in any upload.
        val all = api.uploads.flatten().map { it.content }
        assertTrue(all.contains("kept"))
        assertFalse(all.contains("ignored"), "Lines added after close must not be uploaded")
    }

    @Test
    fun `background flush sends batches periodically while running`() = runBlocking {
        // runBlocking (not runTest) because the background flush runs on its own
        // newSingleThreadContext dispatcher with real wall-clock delays — the
        // virtual-time scheduler in runTest would never let it tick.
        val api = RecordingCiApi()
        val buf = makeBuffer(api, flushIntervalMs = 20)

        buf.start()
        buf.addLine("batch-1", "stdout")

        val deadline = System.currentTimeMillis() + 2000
        while (api.uploads.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(api.uploads.isNotEmpty(), "Background flush should have uploaded at least once within 2s")
        assertTrue(api.uploads.flatten().map { it.content }.contains("batch-1"))

        buf.close()
    }

    @Test
    fun `CiLogger tees INFO and above into the active buffer`() = runTest {
        val api = RecordingCiApi()
        val buf = makeBuffer(api)
        val logger = CiLogger(agentId, "test").also { it.setActiveBuffer(buf) }

        logger.debug("debug-line")           // should NOT tee
        logger.info("step started", "step" to "build")
        logger.warn("slow heartbeat", "ms" to 250)
        logger.error("upload failed", "url" to "https://example")

        buf.flush()

        val uploaded = api.uploads.flatten()
        val contents = uploaded.map { it.content }

        assertFalse(contents.any { it.contains("debug-line") }, "DEBUG should not be teed")
        assertTrue(contents.any { it == "[bosca-agent INFO] step started (step=build)" })
        assertTrue(contents.any { it == "[bosca-agent WARN] slow heartbeat (ms=250)" })
        assertTrue(contents.any { it == "[bosca-agent ERROR] upload failed (url=https://example)" })

        val streamByContent = uploaded.associate { it.content to it.stream }
        assertEquals("stdout", streamByContent["[bosca-agent INFO] step started (step=build)"])
        assertEquals("stderr", streamByContent["[bosca-agent WARN] slow heartbeat (ms=250)"])
        assertEquals("stderr", streamByContent["[bosca-agent ERROR] upload failed (url=https://example)"])

        buf.close()
    }

    @Test
    fun `concurrent flushes never overlap on the wire`() = runBlocking {
        // This is the regression test for the bug that caused logs to go
        // missing in production: the server does a read-modify-write on S3
        // with no concurrency control, so two overlapping appendPipelineLogs
        // calls trample each other (lost update). LogBuffer must serialize
        // all flushes so the server only ever sees one in-flight call per
        // step at a time.
        val tracker = OverlapTrackingApi()
        val buf = makeBuffer(tracker, flushIntervalMs = 20)

        buf.start()
        repeat(50) { i ->
            buf.addLine("line-$i", "stdout")
            // Issue an external flush in parallel with the background loop to
            // maximize the chance of overlap without the mutex.
            if (i % 5 == 0) buf.flush()
        }
        buf.close()

        assertEquals(0, tracker.maxConcurrent.coerceAtLeast(0) - 1,
            "Expected mutual exclusion of appendPipelineLogs (saw ${tracker.maxConcurrent} concurrent calls)")
        // All 50 lines must be delivered exactly once across the (now serialized) batches.
        val delivered = tracker.uploads.flatten().map { it.content }
        assertEquals((0 until 50).map { "line-$it" }, delivered.sorted().let { it.sortedBy { c -> c.removePrefix("line-").toInt() } })
    }

    @Test
    fun `CiLogger stops teeing after setActiveBuffer null`() = runTest {
        val api = RecordingCiApi()
        val buf = makeBuffer(api)
        val logger = CiLogger(agentId, "test").also { it.setActiveBuffer(buf) }

        logger.info("during-step")
        logger.setActiveBuffer(null)
        logger.info("after-step")

        buf.flush()
        val contents = api.uploads.flatten().map { it.content }
        assertTrue(contents.any { it.contains("during-step") })
        assertFalse(contents.any { it.contains("after-step") }, "Detached logger must not tee")

        buf.close()
    }

    @Test
    fun `tailLines survives a flush that clears the upload buffer`() = runTest {
        val api = RecordingCiApi()
        val buf = makeBuffer(api)

        buf.addLine("compiling", "stdout")
        buf.addLine("FAILURE: Build failed with an exception.", "stderr")
        buf.flush()

        assertEquals(1, api.uploads.size)
        assertEquals(
            listOf("compiling", "FAILURE: Build failed with an exception."),
            buf.tailLines().map { it.content },
        )
        assertEquals(listOf("stdout", "stderr"), buf.tailLines().map { it.stream })

        buf.close()
    }

    @Test
    fun `tailLines is bounded by maxTailLines keeping the newest lines`() = runTest {
        val api = RecordingCiApi()
        val buf = LogBuffer(
            api = api,
            repositoryId = repoId,
            runId = runId,
            jobId = jobId,
            stepId = stepId,
            secretValues = emptySet(),
            flushIntervalMs = 50,
            maxTailLines = 3,
        )

        for (i in 1..5) buf.addLine("line-$i", "stdout")

        assertEquals(listOf("line-3", "line-4", "line-5"), buf.tailLines().map { it.content })

        buf.close()
    }

    @Test
    fun `tailLines masks secrets`() = runTest {
        val api = RecordingCiApi()
        val buf = LogBuffer(
            api = api,
            repositoryId = repoId,
            runId = runId,
            jobId = jobId,
            stepId = stepId,
            secretValues = setOf("hunter2"),
        )

        buf.addLine("token=hunter2 rejected", "stderr")

        assertEquals("token=*** rejected", buf.tailLines().single().content)

        buf.close()
    }
}

private open class RecordingCiApi(
    private val failuresBeforeSuccess: Int = 0,
) : CiApi(NetworkClient("http://localhost:0")) {
    val uploads = mutableListOf<List<LogLineInput>>()
    var attempts = 0
        private set

    override suspend fun appendPipelineLogs(
        repositoryId: Uuid,
        runId: Uuid,
        jobId: Uuid,
        stepId: Uuid,
        lines: List<LogLineInput>,
    ): Boolean {
        attempts++
        if (attempts <= failuresBeforeSuccess) throw RuntimeException("simulated failure $attempts")
        uploads.add(lines)
        return true
    }
}

private class OverlapTrackingApi : CiApi(NetworkClient("http://localhost:0")) {
    val uploads = mutableListOf<List<LogLineInput>>()
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile var maxConcurrent: Int = 0
        private set

    override suspend fun appendPipelineLogs(
        repositoryId: Uuid,
        runId: Uuid,
        jobId: Uuid,
        stepId: Uuid,
        lines: List<LogLineInput>,
    ): Boolean {
        val now = inFlight.incrementAndGet()
        try {
            // Track the peak observed concurrency. Any value > 1 proves the
            // bug we're trying to prevent.
            while (true) {
                val prev = maxConcurrent
                if (now <= prev) break
                @Suppress("AssignedValueIsNeverRead")
                maxConcurrent = now
            }
            // Brief artificial latency so an unserialized caller would actually
            // overlap with a concurrent call rather than completing instantly.
            kotlinx.coroutines.delay(5)
            synchronized(uploads) { uploads.add(lines) }
            return true
        } finally {
            inFlight.decrementAndGet()
        }
    }
}

private class GatedCiApi(
    private val gate: CompletableDeferred<Unit>,
) : CiApi(NetworkClient("http://localhost:0")) {
    val uploads = mutableListOf<List<LogLineInput>>()
    val firstCallEntered = CompletableDeferred<Unit>()
    private var firstCall = true

    override suspend fun appendPipelineLogs(
        repositoryId: Uuid,
        runId: Uuid,
        jobId: Uuid,
        stepId: Uuid,
        lines: List<LogLineInput>,
    ): Boolean {
        if (firstCall) {
            firstCall = false
            firstCallEntered.complete(Unit)
            gate.await()
        }
        uploads.add(lines)
        return true
    }
}
