package bosca.analytics.queue

import bosca.analytics.model.Event
import bosca.analytics.model.Events
import bosca.analytics.model.EventType
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DiskAnalyticsIoFailureTest {
    @Test fun `failed segment reads release all resources and leave other segments deliverable`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("analytics-read-failure")
        val queue = DiskAnalyticsEventQueue(directory, Json, maxSegmentEvents = 2)
        var fail = false
        mockkConstructor(RandomAccessFile::class)
        try {
            every {
                constructedWith<RandomAccessFile>(EqMatcher(DiskAnalyticsFormat.segmentPath(directory, 1).toFile()), EqMatcher("r"))
                    .readFully(any<ByteArray>())
            } answers {
                if (fail) throw IOException("Synthetic segment read failure")
                callOriginal()
            }
            fun batch(id: Long) = Events(events = listOf(Event(id, type = EventType.Session)), sent = id, sentMicros = 0)
            queue.enqueue(batch(0)); queue.enqueue(batch(1))
            fail = true
            assertFailsWith<IOException> { queue.receive(Duration.ZERO) }
            queue.enqueue(batch(2)); queue.enqueue(batch(3))
            val healthy = assertNotNull(queue.receive(Duration.ZERO))
            assertEquals(listOf(2L, 3L), healthy.batches.map { it.sent })
            healthy.acknowledge()
            fail = false
            val recovered = assertNotNull(queue.receive(2.seconds))
            assertEquals(listOf(0L, 1L), recovered.batches.map { it.sent })
            recovered.acknowledge()
            assertEquals(0L, queue.status().pending)
        } finally { queue.shutdown(); unmockkConstructor(RandomAccessFile::class); check(directory.toFile().deleteRecursively()) }
    }

    @Test fun `a partial append failure retains accepted frames and truncates only the failed tail on restart`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("analytics-partial-write")
        val first = DiskAnalyticsEventQueue(directory, Json)
        val restarted = DiskAnalyticsEventQueue(directory, Json)
        var fail = false
        mockkConstructor(RandomAccessFile::class)
        try {
            every {
                constructedWith<RandomAccessFile>(EqMatcher(directory.resolve("00000000000000000001.open").toFile()), EqMatcher("rw"))
                    .write(any<ByteArray>())
            } answers {
                if (fail) {
                    val bytes = firstArg<ByteArray>()
                    (self as RandomAccessFile).write(bytes, 0, bytes.size / 2)
                    throw IOException("Synthetic partial append failure")
                }
                callOriginal()
            }
            val batch = Events(events = listOf(Event(1, type = EventType.Session)), sent = 1, sentMicros = 0)
            first.enqueue(batch)
            fail = true
            assertFailsWith<IOException> { first.enqueue(batch.copy(sent = 2)) }
            assertFailsWith<IllegalStateException> { first.checkReadiness() }
            first.shutdown()
            fail = false
            restarted.enqueue(batch.copy(events = emptyList()))
            val accepted = assertNotNull(restarted.receive(Duration.ZERO))
            assertEquals(listOf(batch), accepted.batches)
            accepted.acknowledge()
            assertNull(restarted.receive(Duration.ZERO))
        } finally { first.shutdown(); restarted.shutdown(); unmockkConstructor(RandomAccessFile::class); check(directory.toFile().deleteRecursively()) }
    }

    @Test fun `a failed acknowledgment leaves undeleted data available for another attempt`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("analytics-ack-failure")
        val queue = DiskAnalyticsEventQueue(directory, Json, maxSegmentEvents = 1)
        try {
            val events = Events(events = listOf(Event(1, type = EventType.Session)), sent = 1, sentMicros = 0)
            queue.enqueue(events)
            val delivery = assertNotNull(queue.receive(Duration.ZERO))
            val path = DiskAnalyticsFormat.segmentPath(directory, 1)
            val saved = path.resolveSibling("saved")
            Files.move(path, saved)
            assertFailsWith<IOException> { delivery.acknowledge() }
            Files.move(saved, path)
            val replay = assertNotNull(queue.receive(Duration.ZERO))
            assertEquals(delivery.batches, replay.batches)
            replay.acknowledge()
        } finally { queue.shutdown(); check(directory.toFile().deleteRecursively()) }
    }
}
