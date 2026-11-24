@file:OptIn(bosca.di.annotation.InternalDI::class)
package bosca.analytics.queue

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.iceberg.EventSchema
import bosca.analytics.model.Event
import bosca.analytics.model.Events
import bosca.analytics.model.EventType
import bosca.analytics.repository.EventRepository
import bosca.analytics.repository.iceberg.IcebergEventRepository
import bosca.analytics.service.AnalyticsEventConsumer
import bosca.analytics.transform.EventsTransform
import bosca.analytics.transform.iceberg.IcebergEventsToRecordTransform
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.apache.iceberg.data.IcebergGenerics
import org.apache.iceberg.data.Record
import org.apache.iceberg.hadoop.HadoopTables
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DiskAnalyticsFlushRecoveryTest {
    @Test fun `failed group commits leave nothing staged so each redelivered event is committed once`(): Unit = runBlocking {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        val pool = mockk<ConnectionPool>()
        every { pool.connection() } answers { ConnectionManager(pool) }
        provides<ConnectionPool> { pool }
        val root = Files.createTempDirectory("analytics-shared-flush")
        val table = HadoopTables().create(EventSchema, root.resolve("table").toString())
        val recordTransform = IcebergEventsToRecordTransform(EventSchema)
        val failing = AtomicBoolean(true)
        val transform = object : EventsTransform<List<Record>> {
            override suspend fun transform(item: Events): List<Record> {
                if (failing.get() && item.events.none { it.clientId == "request" }) throw IOException("Synthetic storage transform failure")
                return recordTransform.transform(item)
            }
        }
        val storage = IcebergEventRepository(table, transform, root.resolve("staging").toString(), ProtoBuf)
        val queue = DiskAnalyticsEventQueue(root.resolve("spool"), Json, maxSegmentEvents = 1)
        val started = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val repository = object : EventRepository by storage {
            override suspend fun store(batches: List<Events>) {
                if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                bothStarted.await()
                storage.store(batches)
            }
        }
        fun clientIds() = IcebergGenerics.read(table).build().use { rows -> rows.map { it.getField("client_id") as String }.sorted() }
        try {
            // A request-path caller shares the staging file; consumer failures must not disturb it.
            storage.process(Events(events = listOf(Event(9, type = EventType.Session, clientId = "request")), sent = 9, sentMicros = 0, received = 9, receivedMicros = 0))
            repeat(2) { id -> queue.enqueue(Events(events = listOf(Event(id.toLong(), type = EventType.Session, clientId = "event-$id")), sent = id.toLong(), sentMicros = 0, received = id.toLong(), receivedMicros = 0)) }
            val first = assertNotNull(queue.receive(Duration.ZERO))
            val second = assertNotNull(queue.receive(Duration.ZERO))
            val consumer = AnalyticsEventConsumer(queue, repository, EventPipelineTransforms(emptyList()), EventProcessingConfiguration())
            coroutineScope {
                launch { consumer.processDelivery(first) }
                launch { consumer.processDelivery(second) }
            }
            assertEquals(0, table.snapshots().count())
            assertEquals(2L, queue.status().pending)

            failing.set(false)
            storage.flush()
            assertEquals(listOf("request"), clientIds())
            repeat(2) { consumer.processDelivery(assertNotNull(queue.receive(6.seconds))) }
            assertEquals(0L, queue.status().pending)
            assertEquals(listOf("event-0", "event-1", "request"), clientIds())
        } finally {
            queue.shutdown(); storage.shutdown(); root.toFile().deleteRecursively(); ProviderRegistry.clear()
        }
    }
}
