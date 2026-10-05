package bosca.git.ci.service

import bosca.git.service.LogLine
import bosca.git.service.LogStream
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PipelineLogServiceImplTest {

    private val objectStorage = mockk<ObjectStorageService>(relaxed = true)
    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private val json = Json
    private lateinit var service: PipelineLogServiceImpl

    private val repoId = UUID.random()
    private val runId = UUID.random()
    private val jobId = UUID.random()
    private val stepId = UUID.random()

    @BeforeTest
    fun setup() {
        service = PipelineLogServiceImpl(objectStorage, pubSubService, json)
    }

    @Test
    fun `appendLog writes NDJSON to correct path`() = runTest {
        coEvery { objectStorage.getString(any()) } throws ObjectNotFoundException(StringObjectPath("missing"))

        val pathSlot = slot<StringObjectPath>()
        val streamSlot = slot<InputStream>()
        val lengthSlot = slot<Long>()
        coEvery { objectStorage.setInputStream(capture(pathSlot), capture(streamSlot), capture(lengthSlot)) } returns 100L

        val lines = listOf(
            LogLine(lineNumber = 1, timestamp = "2026-05-10T00:00:00Z", content = "$ ./gradlew build", stream = LogStream.STDOUT),
            LogLine(lineNumber = 2, timestamp = "2026-05-10T00:00:01Z", content = "BUILD SUCCESSFUL", stream = LogStream.STDOUT)
        )

        service.appendLog(repoId, runId, jobId, stepId, lines)

        assertTrue(lengthSlot.captured > 0)
        coVerify { objectStorage.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `appendLog appends to existing content`() = runTest {
        val existingContent = """{"ln":1,"ts":"2026-05-10T00:00:00Z","content":"line 1","stream":"stdout"}"""
        coEvery { objectStorage.getString(any()) } returns existingContent + "\n"

        val streamSlot = slot<InputStream>()
        coEvery { objectStorage.setInputStream(any(), capture(streamSlot), any()) } returns 200L

        val lines = listOf(
            LogLine(lineNumber = 2, timestamp = "2026-05-10T00:00:01Z", content = "line 2", stream = LogStream.STDOUT)
        )

        service.appendLog(repoId, runId, jobId, stepId, lines)

        val written = streamSlot.captured.readAllBytes().decodeToString()
        assertTrue(written.contains("line 1"))
        assertTrue(written.contains("line 2"))
    }

    @Test
    fun `appendLog writes stderr stream`() = runTest {
        coEvery { objectStorage.getString(any()) } throws ObjectNotFoundException(StringObjectPath("missing"))

        val streamSlot = slot<InputStream>()
        coEvery { objectStorage.setInputStream(any(), capture(streamSlot), any()) } returns 100L

        val lines = listOf(
            LogLine(lineNumber = 1, timestamp = "2026-05-10T00:00:00Z", content = "error!", stream = LogStream.STDERR)
        )

        service.appendLog(repoId, runId, jobId, stepId, lines)

        val written = streamSlot.captured.readAllBytes().decodeToString()
        assertTrue(written.contains("\"stream\":\"stderr\""))
    }

    @Test
    fun `getLogs reads and parses NDJSON`() = runTest {
        val content = listOf(
            """{"ln":1,"ts":"2026-05-10T00:00:00Z","content":"line 1","stream":"stdout"}""",
            """{"ln":2,"ts":"2026-05-10T00:00:01Z","content":"line 2","stream":"stderr"}"""
        ).joinToString("\n")

        coEvery { objectStorage.getString(any()) } returns content

        val logs = service.getLogs(repoId, runId, jobId, stepId)

        assertEquals(2, logs.size)
        assertEquals(1, logs[0].lineNumber)
        assertEquals("line 1", logs[0].content)
        assertEquals(LogStream.STDOUT, logs[0].stream)
        assertEquals(2, logs[1].lineNumber)
        assertEquals("line 2", logs[1].content)
        assertEquals(LogStream.STDERR, logs[1].stream)
    }

    @Test
    fun `getLogs returns empty when no logs exist`() = runTest {
        coEvery { objectStorage.getString(any()) } throws ObjectNotFoundException(StringObjectPath("missing"))

        val logs = service.getLogs(repoId, runId, jobId, stepId)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `getLogs supports offset and limit`() = runTest {
        val content = (1..10).joinToString("\n") { i ->
            """{"ln":$i,"ts":"2026-05-10T00:00:0${i}Z","content":"line $i","stream":"stdout"}"""
        }

        coEvery { objectStorage.getString(any()) } returns content

        val logs = service.getLogs(repoId, runId, jobId, stepId, offset = 3, limit = 2)

        assertEquals(2, logs.size)
        assertEquals(4, logs[0].lineNumber)
        assertEquals(5, logs[1].lineNumber)
    }

    @Test
    fun `getLogs tail returns the last limit lines`() = runTest {
        val content = (1..10).joinToString("\n") { i ->
            """{"ln":$i,"ts":"2026-05-10T00:00:0${i}Z","content":"line $i","stream":"stdout"}"""
        }

        coEvery { objectStorage.getString(any()) } returns content

        val logs = service.getLogs(repoId, runId, jobId, stepId, limit = 3, tail = true)

        assertEquals(3, logs.size)
        assertEquals(8, logs[0].lineNumber)
        assertEquals(10, logs[2].lineNumber)
    }

    @Test
    fun `getLogs tail ignores offset`() = runTest {
        val content = (1..5).joinToString("\n") { i ->
            """{"ln":$i,"ts":"2026-05-10T00:00:0${i}Z","content":"line $i","stream":"stdout"}"""
        }

        coEvery { objectStorage.getString(any()) } returns content

        val logs = service.getLogs(repoId, runId, jobId, stepId, offset = 4, limit = 2, tail = true)

        assertEquals(2, logs.size)
        assertEquals(4, logs[0].lineNumber)
        assertEquals(5, logs[1].lineNumber)
    }

    @Test
    fun `getLogs tail returns everything when limit exceeds line count`() = runTest {
        val content = (1..3).joinToString("\n") { i ->
            """{"ln":$i,"ts":"2026-05-10T00:00:0${i}Z","content":"line $i","stream":"stdout"}"""
        }

        coEvery { objectStorage.getString(any()) } returns content

        val logs = service.getLogs(repoId, runId, jobId, stepId, limit = 100, tail = true)

        assertEquals(3, logs.size)
        assertEquals(1, logs[0].lineNumber)
    }

    @Test
    fun `getLogs beforeLine returns the last limit lines below it`() = runTest {
        val content = (1..10).joinToString("\n") { i ->
            """{"ln":$i,"ts":"2026-05-10T00:00:0${i}Z","content":"line $i","stream":"stdout"}"""
        }

        coEvery { objectStorage.getString(any()) } returns content

        val logs = service.getLogs(repoId, runId, jobId, stepId, limit = 3, beforeLine = 8)

        assertEquals(3, logs.size)
        assertEquals(5, logs[0].lineNumber)
        assertEquals(6, logs[1].lineNumber)
        assertEquals(7, logs[2].lineNumber)
    }

    @Test
    fun `getLogs beforeLine pages across line-number gaps that positional offsets miss`() = runTest {
        // The agent's LogBuffer drops a batch after upload-retry exhaustion,
        // leaving gaps in the stored line numbers. Here lines 4..6 were lost:
        // the file holds ln 1,2,3,7,8,9,10 (7 physical lines).
        val numbers = listOf(1, 2, 3, 7, 8, 9, 10)
        val content = numbers.joinToString("\n") { i ->
            """{"ln":$i,"ts":"t$i","content":"line $i","stream":"stdout"}"""
        }
        coEvery { objectStorage.getString(any()) } returns content

        // A viewer showing lines 7..10 pages backwards from line 7. The old
        // positional math (offset = 7 - 1 - limit) would re-read lines the
        // viewer already has; beforeLine returns the actual pre-gap lines.
        val logs = service.getLogs(repoId, runId, jobId, stepId, limit = 4, beforeLine = 7)

        assertEquals(listOf(1, 2, 3), logs.map { it.lineNumber })
    }

    @Test
    fun `getLogs beforeLine returns empty when nothing precedes it`() = runTest {
        val content = (5..8).joinToString("\n") { i ->
            """{"ln":$i,"ts":"t$i","content":"line $i","stream":"stdout"}"""
        }
        coEvery { objectStorage.getString(any()) } returns content

        val logs = service.getLogs(repoId, runId, jobId, stepId, limit = 100, beforeLine = 5)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `getLogs beforeLine ignores offset and tail`() = runTest {
        val content = (1..10).joinToString("\n") { i ->
            """{"ln":$i,"ts":"t$i","content":"line $i","stream":"stdout"}"""
        }
        coEvery { objectStorage.getString(any()) } returns content

        val logs = service.getLogs(repoId, runId, jobId, stepId, offset = 9, limit = 2, tail = true, beforeLine = 6)

        assertEquals(listOf(4, 5), logs.map { it.lineNumber })
    }

    @Test
    fun `getLogs skips malformed lines`() = runTest {
        val content = listOf(
            """{"ln":1,"ts":"2026-05-10T00:00:00Z","content":"good","stream":"stdout"}""",
            "not json at all",
            """{"ln":3,"ts":"2026-05-10T00:00:02Z","content":"also good","stream":"stdout"}"""
        ).joinToString("\n")

        coEvery { objectStorage.getString(any()) } returns content

        val logs = service.getLogs(repoId, runId, jobId, stepId)

        assertEquals(2, logs.size)
        assertEquals("good", logs[0].content)
        assertEquals("also good", logs[1].content)
    }

    @Test
    fun `getLogs skips blank lines`() = runTest {
        val content = """{"ln":1,"ts":"t","content":"line","stream":"stdout"}""" + "\n\n\n"

        coEvery { objectStorage.getString(any()) } returns content

        val logs = service.getLogs(repoId, runId, jobId, stepId)
        assertEquals(1, logs.size)
    }

    @Test
    fun `getLogs defaults unknown stream to STDOUT`() = runTest {
        val content = """{"ln":1,"ts":"t","content":"line","stream":"unknown"}"""

        coEvery { objectStorage.getString(any()) } returns content

        val logs = service.getLogs(repoId, runId, jobId, stepId)
        assertEquals(LogStream.STDOUT, logs[0].stream)
    }

    @Test
    fun `concurrent appendLog calls for the same step preserve every line`() = runBlocking {
        // Regression test for the lost-update race that was dropping CI logs
        // in production. appendLog reads the existing S3 object, concatenates,
        // and writes the result. Without per-step serialization, two
        // overlapping calls both read the same "existing" content and the
        // second write clobbers the first. With the per-step mutex, the read
        // is guaranteed to see every previously-committed write.
        val stored = AtomicReference<String?>(null)

        coEvery { objectStorage.getString(any()) } coAnswers {
            // Brief delay enlarges the race window so the test would fail
            // deterministically without serialization.
            delay(2)
            stored.get() ?: throw ObjectNotFoundException(StringObjectPath("missing"))
        }
        coEvery { objectStorage.setInputStream(any(), any(), any()) } coAnswers {
            val body = secondArg<InputStream>().readAllBytes().decodeToString()
            delay(2)
            stored.set(body)
            body.length.toLong()
        }

        val lineCount = 50
        coroutineScope {
            (1..lineCount).map { i ->
                async {
                    service.appendLog(
                        repoId, runId, jobId, stepId,
                        listOf(LogLine(lineNumber = i, timestamp = "ts-$i", content = "line-$i", stream = LogStream.STDOUT))
                    )
                }
            }.awaitAll()
        }

        val finalContent = stored.get().orEmpty()
        val missing = (1..lineCount).filterNot { finalContent.contains("\"content\":\"line-$it\"") }
        assertTrue(missing.isEmpty(), "Lost lines under concurrent appendLog: $missing")
    }

    @Test
    fun `appendLog fails without overwriting when the existing log cannot be read`() = runTest {
        coEvery { objectStorage.getString(any()) } throws IOException("storage unavailable")

        assertFailsWith<IOException> {
            service.appendLog(
                repoId, runId, jobId, stepId,
                listOf(LogLine(lineNumber = 7, timestamp = "ts", content = "late line", stream = LogStream.STDOUT))
            )
        }
        coVerify(exactly = 0) { objectStorage.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `getLogs surfaces read failures instead of reporting an empty log`() = runTest {
        coEvery { objectStorage.getString(any()) } throws IOException("storage unavailable")

        assertFailsWith<IOException> { service.getLogs(repoId, runId, jobId, stepId, 0, 100, false, null) }
    }

    @Test
    fun `deleteRunLogs does not throw`() = runTest {
        service.deleteRunLogs(repoId, runId)
    }

    @Test
    fun `deleteStepLog removes the exact persisted log object`() = runTest {
        service.deleteStepLog(repoId, runId, jobId, stepId)

        coVerify {
            objectStorage.delete(match {
                it.toString() == "git/$repoId/ci/logs/$runId/$jobId/$stepId.log"
            })
        }
    }

    @Test
    fun `deleteExpiredLogs does not throw`() = runTest {
        service.deleteExpiredLogs(repoId, 31)
    }
}
