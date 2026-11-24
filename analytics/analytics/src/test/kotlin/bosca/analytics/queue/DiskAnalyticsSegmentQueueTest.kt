package bosca.analytics.queue

import bosca.analytics.model.Event
import bosca.analytics.model.Events
import bosca.analytics.model.EventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class DiskAnalyticsSegmentQueueTest {
    private val directory = Files.createTempDirectory("analytics-segments")
    private val queues = mutableListOf<DiskAnalyticsEventQueue>()
    private fun queue(events: Int = 2, age: Duration = 1.hours, bytes: Long = 1_048_576) =
        DiskAnalyticsEventQueue(directory, Json, maxBytes = bytes, maxSegmentEvents = events, maxSegmentAge = age).also(queues::add)
    private fun batch(id: Long, size: Int = 1) = Events(events = List(size) { Event(id, type = EventType.Session, clientId = "$id-$it") },
        sent = id, sentMicros = id, received = id, receivedMicros = id)
    private fun segments() = Files.list(directory).use { paths -> paths.filter { it.toString().endsWith(".segment") }.toList() }

    @AfterTest fun cleanup(): Unit = runBlocking {
        queues.forEach { it.shutdown() }
        check(directory.toFile().deleteRecursively())
    }

    @Test fun `one delivery retains the original batches and is deleted only after acknowledgment`(): Unit = runBlocking {
        val queue = queue()
        queue.enqueue(batch(1)); assertNull(queue.receive(Duration.ZERO))
        queue.enqueue(batch(2))
        val accepted = Files.readAllBytes(segments().single())
        val delivery = assertNotNull(queue.receive(Duration.ZERO))
        assertEquals(listOf(batch(1), batch(2)), delivery.batches)
        assertContentEquals(accepted, Files.readAllBytes(segments().single()))
        assertNull(queue.receive(Duration.ZERO))
        delivery.renew(); delivery.acknowledge()
        assertTrue(segments().isEmpty())
        assertEquals(0L, queue.status().pending)
        assertFailsWith<IllegalStateException> { delivery.acknowledge() }
        assertFailsWith<IllegalStateException> { delivery.renew() }
    }

    @Test fun `failed segments replay as units without blocking healthy segments`(): Unit = runBlocking {
        val queue = queue()
        repeat(4) { queue.enqueue(batch(it.toLong())) }
        val failed = assertNotNull(queue.receive(Duration.ZERO))
        failed.retry(200.milliseconds)
        val healthy = assertNotNull(queue.receive(Duration.ZERO))
        assertTrue(failed.batches.map { it.sent }.toSet().intersect(healthy.batches.map { it.sent }.toSet()).isEmpty())
        healthy.acknowledge()
        assertNull(queue.receive(Duration.ZERO))
        val replay = assertNotNull(queue.receive(2.seconds))
        assertEquals(failed.batches, replay.batches)
        assertFailsWith<IllegalStateException> { failed.retry(Duration.ZERO) }
        replay.acknowledge()
        assertEquals(0L, queue.status().pending)
    }

    @Test fun `consumer restart replays the entire unacknowledged segment without checkpoint files`(): Unit = runBlocking {
        val first = queue()
        first.enqueue(batch(1)); first.enqueue(batch(2))
        val original = assertNotNull(first.receive(Duration.ZERO))
        first.shutdown()
        val restarted = queue()
        val replay = assertNotNull(restarted.receive(Duration.ZERO))
        assertEquals(original.batches, replay.batches)
        replay.acknowledge()
        val names = Files.list(directory).use { it.map { p -> p.fileName.toString() }.toList() }
        assertFalse(names.any { it.endsWith(".json") || it.endsWith(".journal") || it.endsWith(".dedup") })
    }

    @Test fun `separate producer and consumer processes can share the spool but not the same role`(): Unit = runBlocking {
        val producer = queue(events = 1)
        val consumer = queue(events = 1)
        producer.enqueue(batch(1))
        assertFailsWith<IllegalStateException> { queue(events = 1).enqueue(batch(2)) }
        val delivery = assertNotNull(consumer.receive(Duration.ZERO))
        assertFailsWith<IllegalStateException> { queue().receive(Duration.ZERO) }
        assertFailsWith<IllegalStateException> { producer.acknowledgeBatch(listOf(delivery)) }
        assertFailsWith<IllegalArgumentException> { consumer.acknowledgeBatch(listOf(delivery, delivery)) }
        delivery.acknowledge()
    }

    @Test fun `count and idle rollover preserve all accepted events`(): Unit = runBlocking {
        val count = queue(events = 2)
        count.enqueue(batch(1, 5)); count.shutdown()
        val reader = queue()
        val ids = mutableListOf<String?>()
        while (true) {
            val delivery = reader.receive(Duration.ZERO) ?: break
            ids += delivery.batches.flatMap { it.events }.map { it.clientId }
            delivery.acknowledge()
        }
        assertEquals(List(5) { "1-$it" }.toSet(), ids.toSet())
        reader.shutdown()
        val idle = queue(age = 20.milliseconds)
        idle.enqueue(batch(2))
        val delivered = assertNotNull(idle.receive(2.seconds))
        assertEquals(listOf(batch(2)), delivered.batches)
        delivered.acknowledge()
    }

    @Test fun `capacity rejects new data and resumes after acknowledged segments are removed`(): Unit = runBlocking {
        val producer = queue(events = 1, bytes = 600)
        producer.enqueue(batch(1))
        val acceptedPath = segments().single()
        val accepted = Files.readAllBytes(acceptedPath)
        assertFailsWith<IOException> { producer.enqueue(batch(2, 100)) }
        assertContentEquals(accepted, Files.readAllBytes(acceptedPath))
        val consumer = queue()
        while (true) { (consumer.receive(Duration.ZERO) ?: break).acknowledge() }
        producer.enqueue(batch(3))
        assertEquals(3L, assertNotNull(consumer.receive(Duration.ZERO)).batches.single().sent)
    }

    @Test fun `sealed corruption is preserved and other segments remain deliverable`(): Unit = runBlocking {
        val producer = queue(events = 1)
        producer.enqueue(batch(1)); producer.enqueue(batch(2)); producer.shutdown()
        val damaged = segments().first()
        val bytes = Files.readAllBytes(damaged)
        bytes[40] = (bytes[40].toInt() xor 1).toByte()
        Files.write(damaged, bytes)
        val consumer = queue()
        assertNotNull(consumer.receive(Duration.ZERO)).acknowledge()
        assertNull(consumer.receive(Duration.ZERO))
        val quarantined = damaged.resolveSibling("${damaged.fileName}.quarantine")
        assertContentEquals(bytes, Files.readAllBytes(quarantined))
        assertEquals(1L, consumer.status().quarantined)
    }

    @Test fun `unfinished active tail is repaired without dropping complete accepted frames`(): Unit = runBlocking {
        val producer = queue()
        producer.enqueue(batch(1)); producer.shutdown()
        val sealed = segments().single()
        val active = sealed.resolveSibling(sealed.fileName.toString().replace(".segment", ".open"))
        Files.move(sealed, active)
        RandomAccessFile(active.toFile(), "rw").use { file ->
            file.setLength(file.length() - 24)
            file.seek(file.length()); file.write(byteArrayOf(1, 2, 3))
        }
        val recovered = queue()
        recovered.enqueue(batch(2)); recovered.shutdown()
        val consumer = queue()
        val batches = mutableListOf<Events>()
        while (true) {
            val delivery = consumer.receive(Duration.ZERO) ?: break
            batches += delivery.batches; delivery.acknowledge()
        }
        assertEquals(setOf(batch(1), batch(2)), batches.toSet())
    }

    @Test fun `interrupted segment keeps its accepted frames and preserves only the damaged tail`(): Unit = runBlocking {
        for (tail in listOf(ByteArray(64), ByteArray(40) { (it + 1).toByte() })) {
            val folder = Files.createDirectory(directory.resolve("tail-${tail.size}"))
            val path = folder.resolve("00000000000000000001.open")
            RandomAccessFile(path.toFile(), "rw").use { file ->
                DiskAnalyticsFormat.header(file, 1, 1)
                for (id in 1L..2L) file.write(DiskAnalyticsFormat.frame(Json.encodeToString(DiskAnalyticsRecord.serializer(), DiskAnalyticsRecord(batch(id), id)).toByteArray()))
                file.write(tail)
            }
            val queue = DiskAnalyticsEventQueue(folder, Json, maxSegmentEvents = 1)
            try {
                queue.enqueue(batch(3))
                val delivered = mutableListOf<Events>()
                while (true) {
                    val delivery = queue.receive(Duration.ZERO) ?: break
                    delivered += delivery.batches; delivery.acknowledge()
                }
                assertEquals(listOf(batch(1), batch(2), batch(3)), delivered.sortedBy { it.sent })
                assertContentEquals(tail, Files.readAllBytes(folder.resolve("00000000000000000001.open.tail.quarantine")))
                assertEquals(1L, queue.status().quarantined)
            } finally { queue.shutdown() }
        }
    }

    @Test fun `a batch that exceeds capacity is rejected before any part of it is sealed`(): Unit = runBlocking {
        val producer = queue(events = 2, bytes = 2_000)
        assertFailsWith<IOException> { producer.enqueue(batch(1, 20)) }
        assertTrue(segments().isEmpty())
        producer.enqueue(batch(2)); producer.shutdown()
        val reader = queue()
        val delivered = mutableListOf<Events>()
        while (true) {
            val delivery = reader.receive(Duration.ZERO) ?: break
            delivered += delivery.batches; delivery.acknowledge()
        }
        assertEquals(listOf(batch(2)), delivered)
    }

    @Test fun `damaged active and sealed files retain their exact bytes while healthy work continues`(): Unit = runBlocking {
        for (extension in listOf("open", "segment")) for (damage in listOf("header", "sequence", "length", "payload", "json", "footer")) {
            val folder = Files.createDirectory(directory.resolve("$extension-$damage"))
            val path = folder.resolve("00000000000000000001.$extension")
            RandomAccessFile(path.toFile(), "rw").use { file ->
                DiskAnalyticsFormat.header(file, if (damage == "sequence") 99 else 1, 1)
                val payload = if (damage == "json") "{}" else Json.encodeToString(DiskAnalyticsRecord.serializer(), DiskAnalyticsRecord(batch(1), 1))
                file.write(DiskAnalyticsFormat.frame(payload.toByteArray()))
                DiskAnalyticsFormat.footer(file, 1, 1)
                when (damage) {
                    "header" -> { file.seek(0); file.writeLong(0) }
                    "length" -> { file.seek(DiskAnalyticsFormat.HEADER_BYTES); file.writeInt(Int.MAX_VALUE) }
                    "payload" -> { file.seek(DiskAnalyticsFormat.HEADER_BYTES + DiskAnalyticsFormat.FRAME_HEADER_BYTES); file.writeByte(0) }
                    "footer" -> { file.seek(file.length() - 20); file.writeInt(0) }
                }
            }
            val accepted = Files.readAllBytes(path)
            val queue = DiskAnalyticsEventQueue(folder, Json, maxSegmentEvents = 1)
            try {
                queue.enqueue(batch(2))
                queue.status()
                val delivered = mutableListOf<Events>()
                while (true) {
                    val delivery = queue.receive(Duration.ZERO) ?: break
                    delivered += delivery.batches; delivery.acknowledge()
                }
                assertEquals(listOf(batch(2)), delivered)
                assertContentEquals(accepted, Files.readAllBytes(path.resolveSibling("${path.fileName}.quarantine")))
            } finally { queue.shutdown() }
        }
    }

    @Test fun `startup discards unaccepted short headers and repairs interrupted footer writes`(): Unit = runBlocking {
        for (length in listOf(0, 8)) {
            val folder = Files.createDirectory(directory.resolve("header-$length"))
            Files.write(folder.resolve("00000000000000000001.open"), ByteArray(length))
            val queue = DiskAnalyticsEventQueue(folder, Json, maxSegmentEvents = 1)
            try {
                queue.enqueue(batch(1))
                assertEquals(listOf(batch(1)), assertNotNull(queue.receive(Duration.ZERO)).batches)
            } finally { queue.shutdown() }
        }
        for (length in listOf(2L, 8L)) {
            val folder = Files.createDirectory(directory.resolve("footer-$length"))
            val path = folder.resolve("00000000000000000001.open")
            RandomAccessFile(path.toFile(), "rw").use { file ->
                DiskAnalyticsFormat.header(file, 1, 1)
                file.write(DiskAnalyticsFormat.frame(Json.encodeToString(DiskAnalyticsRecord.serializer(), DiskAnalyticsRecord(batch(1), 1)).toByteArray()))
                val end = file.filePointer
                DiskAnalyticsFormat.footer(file, 1, 1); file.setLength(end + length)
            }
            val queue = DiskAnalyticsEventQueue(folder, Json)
            try {
                queue.enqueue(batch(0, 0))
                assertEquals(listOf(batch(1)), assertNotNull(queue.receive(Duration.ZERO)).batches)
            } finally { queue.shutdown() }
        }
    }

    @Test fun `status counts sealed data and active bytes without acquiring either role`(): Unit = runBlocking {
        val producer = queue()
        val observer = queue()
        producer.enqueue(batch(1))
        assertEquals(0L, observer.status().pending)
        assertTrue(observer.status().storageBytes > 0)
        producer.enqueue(batch(2))
        assertEquals(2L, observer.status().pending)
        assertNotNull(observer.status().oldestPendingMillis)
        assertNotNull(observer.receive(Duration.ZERO)).acknowledge()
        observer.checkReadiness()
        assertEquals(0L, producer.status().pending)
    }

    @Test fun `cancelled receives propagate cancellation and closed queues reject further use`(): Unit = runBlocking {
        val queue = queue()
        val receive = async { queue.receive(1.hours) }
        delay(20); receive.cancelAndJoin()
        assertTrue(receive.isCancelled)
        queue.shutdown(); queue.shutdown()
        assertFailsWith<IllegalStateException> { queue.enqueue(batch(1)) }
        assertFailsWith<IllegalStateException> { queue.receive(Duration.ZERO) }
        assertFailsWith<IllegalStateException> { queue.status() }
    }

    @Test fun `invalid limits and legacy per-event files fail explicitly`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { queue(events = 0) }
        assertFailsWith<IllegalArgumentException> { queue(bytes = 0) }
        assertFailsWith<IllegalArgumentException> { queue(age = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { queue(age = Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { queue(age = 1.nanoseconds) }
        val queue = queue()
        assertFailsWith<IllegalArgumentException> { queue.receiveBatch(0, Duration.ZERO) }
        queue.acknowledgeBatch(emptyList()); queue.retryBatch(emptyList(), Duration.ZERO)
        Files.write(directory.resolve("old.event"), byteArrayOf(1))
        assertFailsWith<IllegalStateException> { queue.enqueue(batch(1)) }
        assertFailsWith<IllegalStateException> { queue.receive(Duration.ZERO) }
        assertFailsWith<IllegalStateException> { queue.checkReadiness() }
    }

    @Test fun `lowering capacity blocks new writes but still permits draining accepted data`(): Unit = runBlocking {
        val producer = queue(events = 1)
        producer.enqueue(batch(1)); producer.shutdown()
        val reader = queue(bytes = 1)
        assertFailsWith<IllegalStateException> { reader.checkReadiness() }
        val accepted = assertNotNull(reader.receive(Duration.ZERO))
        assertEquals(listOf(batch(1)), accepted.batches)
        accepted.acknowledge()
        reader.checkReadiness()
    }

    @Test fun `a queue cannot acknowledge a delivery owned by another queue or transport`(): Unit = runBlocking {
        val original = queue(events = 1)
        original.enqueue(batch(1))
        val delivery = assertNotNull(original.receive(Duration.ZERO))
        val folder = Files.createDirectory(directory.resolve("other-queue"))
        val other = DiskAnalyticsEventQueue(folder, Json)
        try {
            assertNull(other.receive(Duration.ZERO))
            assertFailsWith<IllegalStateException> { other.acknowledgeBatch(listOf(delivery)) }
            assertFailsWith<IllegalStateException> { other.retryBatch(listOf(delivery), Duration.ZERO) }
            val foreign = io.mockk.mockk<AnalyticsEventDelivery>()
            assertFailsWith<IllegalStateException> { original.acknowledgeBatch(listOf(foreign)) }
            delivery.acknowledge()
        } finally { other.shutdown() }
    }

    @Test fun `an empty queue is ready and a bounded receive times out normally`(): Unit = runBlocking {
        val queue = queue(age = 20.milliseconds)
        queue.checkReadiness()
        assertEquals(0L, queue.status().pending)
        assertNull(queue.receive(50.milliseconds))
        queue.enqueue(batch(1, 0))
        assertNull(queue.receive(Duration.ZERO))
    }

    @Test fun `missing frames and incorrect footer counts preserve sealed and interrupted files`(): Unit = runBlocking {
        for (extension in listOf("segment", "open")) for (damage in listOf("missing-frame", "events", "records")) {
            val folder = Files.createDirectory(directory.resolve("counts-$extension-$damage"))
            val path = folder.resolve("00000000000000000001.$extension")
            RandomAccessFile(path.toFile(), "rw").use { file ->
                DiskAnalyticsFormat.header(file, 1, 1)
                file.write(DiskAnalyticsFormat.frame(Json.encodeToString(DiskAnalyticsRecord.serializer(), DiskAnalyticsRecord(batch(1), 1)).toByteArray()))
                if (damage != "missing-frame") file.write(DiskAnalyticsFormat.frame(Json.encodeToString(DiskAnalyticsRecord.serializer(), DiskAnalyticsRecord(batch(2), 2)).toByteArray()))
                DiskAnalyticsFormat.footer(file, if (damage == "events") 3 else 2, if (damage == "records") 3 else 2)
            }
            val accepted = Files.readAllBytes(path)
            val producer = DiskAnalyticsEventQueue(folder, Json, maxSegmentEvents = 1)
            val consumer = DiskAnalyticsEventQueue(folder, Json)
            try {
                producer.startProducer()
                producer.enqueue(batch(3))
                val healthy = assertNotNull(consumer.receive(Duration.ZERO))
                assertEquals(listOf(batch(3)), healthy.batches)
                healthy.acknowledge()
                assertNull(consumer.receive(Duration.ZERO))
                assertContentEquals(accepted, Files.readAllBytes(path.resolveSibling("${path.fileName}.quarantine")))
            } finally { producer.shutdown(); consumer.shutdown() }
        }
    }
}
