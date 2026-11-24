@file:OptIn(bosca.di.annotation.InternalDI::class)
package bosca.analytics.queue

import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.iceberg.EventSchema
import bosca.analytics.model.Event
import bosca.analytics.model.Events
import bosca.analytics.model.EventType
import bosca.analytics.repository.CompositeEventRepository
import bosca.analytics.repository.iceberg.IcebergEventRepository
import bosca.analytics.service.AnalyticsEventConsumer
import bosca.analytics.transform.iceberg.IcebergEventsToRecordTransform
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.apache.iceberg.data.IcebergGenerics
import org.apache.iceberg.hadoop.HadoopTables
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DiskAnalyticsPipelineIntegrationTest {
    @Test fun `Parquet record failure stages nothing and the redelivery commits each event once`(): Unit = runBlocking {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        val connections = mockk<ConnectionPool>()
        every { connections.connection() } answers { ConnectionManager(connections) }
        provides<ConnectionPool> { connections }
        val directory = Files.createTempDirectory("disk-analytics-parquet-failure")
        val table = HadoopTables().create(EventSchema, directory.resolve("table").toString())
        val records = IcebergEventsToRecordTransform(EventSchema)
        var rejectRecord = true
        val transform = object : bosca.analytics.transform.EventsTransform<List<org.apache.iceberg.data.Record>> {
            override suspend fun transform(item: Events) =
                if (rejectRecord) listOf(mockk<org.apache.iceberg.data.Record>()) else records.transform(item)
        }
        val staging = directory.resolve("staging")
        val storage = IcebergEventRepository(table, transform, staging.toString(), ProtoBuf)
        val queue = DiskAnalyticsEventQueue(directory.resolve("spool"), Json, maxSegmentEvents = 1)
        try {
            queue.enqueue(Events(events = listOf(Event(1, type = EventType.Session, clientId = "preserved")), sent = 1, sentMicros = 0, received = 1, receivedMicros = 0))
            val consumer = AnalyticsEventConsumer(queue, storage, EventPipelineTransforms(emptyList()), EventProcessingConfiguration())
            consumer.processDeliveries(queue.receiveBatch(1, Duration.ZERO))
            assertEquals(1L, queue.status().pending)
            assertEquals(0, table.snapshots().count())
            assertEquals(0L, Files.list(staging).use { it.count() }, "A failed group must not leave staged data behind")
            storage.flush()
            assertEquals(0, table.snapshots().count())
            rejectRecord = false
            consumer.processDeliveries(queue.receiveBatch(1, 6.seconds).also { assertEquals(1, it.size) })
            assertEquals(0L, queue.status().pending)
            assertEquals(listOf("preserved"), IcebergGenerics.read(table).build().use { rows -> rows.map { it.getField("client_id") } })
            assertEquals(0L, Files.list(staging).use { it.count() })
        } finally { queue.shutdown(); storage.shutdown(); directory.toFile().deleteRecursively(); ProviderRegistry.clear() }
    }

    @Test fun `disk handoff retries a failed primary store and acknowledges only after Iceberg commit`(): Unit = runBlocking {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        val connections = mockk<ConnectionPool>()
        every { connections.connection() } answers { ConnectionManager(connections) }
        provides<ConnectionPool> { connections }
        val directory = Files.createTempDirectory("disk-analytics-pipeline")
        val table = HadoopTables().create(EventSchema, directory.resolve("table").toString())
        val staging = Files.createFile(directory.resolve("staging"))
        val storage = IcebergEventRepository(table, IcebergEventsToRecordTransform(EventSchema), staging.toString(), ProtoBuf)
        val queue = DiskAnalyticsEventQueue(directory.resolve("spool"), Json, maxSegmentEvents = 2)
        try {
            val events = Events(events = listOf(Event(1, type = EventType.Session, clientId = "retry")), sent = 1, sentMicros = 0, received = 1, receivedMicros = 0)
            queue.enqueue(events)
            queue.enqueue(events.copy(events = listOf(events.events.single().copy(clientId = "second"))))
            val consumer = AnalyticsEventConsumer(queue, CompositeEventRepository(storage, emptyList()), EventPipelineTransforms(emptyList()), EventProcessingConfiguration())
            consumer.processDeliveries(queue.receiveBatch(10, Duration.ZERO).also { assertEquals(1, it.size) })
            assertEquals(2L, queue.status().pending)
            assertEquals(0, IcebergGenerics.read(table).build().use { it.count() })
            Files.delete(staging); Files.createDirectory(staging)
            consumer.processDeliveries(queue.receiveBatch(10, 6.seconds).also { assertEquals(1, it.size) })
            assertNull(queue.receive(Duration.ZERO))
            assertEquals(2, IcebergGenerics.read(table).build().use { it.count() })
            assertEquals(1, table.snapshots().count(), "One processing group must produce one Iceberg commit")
        } finally {
            queue.shutdown(); storage.shutdown(); directory.toFile().deleteRecursively(); ProviderRegistry.clear()
        }
    }

    @Test fun `failed transform in an earlier segment leaves later Iceberg commits progressing`(): Unit = runBlocking {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        val connections = mockk<ConnectionPool>()
        every { connections.connection() } answers { ConnectionManager(connections) }
        provides<ConnectionPool> { connections }
        val directory = Files.createTempDirectory("disk-analytics-isolated-pipeline")
        val table = HadoopTables().create(EventSchema, directory.resolve("table").toString())
        val storage = IcebergEventRepository(table, IcebergEventsToRecordTransform(EventSchema), directory.resolve("staging").toString(), ProtoBuf)
        val producer = DiskAnalyticsEventQueue(directory.resolve("spool"), Json, maxSegmentEvents = 1)
        var consumerQueue = DiskAnalyticsEventQueue(directory.resolve("spool"), Json)
        var failing = true
        val transform = object : bosca.analytics.transform.EventPipelineTransform {
            override suspend fun transform(context: bosca.analytics.model.EventPipelineContext, events: Events): Events {
                if (failing && events.sent == 0L) throw java.io.IOException("Synthetic earlier transform failure")
                return events
            }
        }
        fun consumer() = AnalyticsEventConsumer(consumerQueue, storage, EventPipelineTransforms(listOf(transform)), EventProcessingConfiguration())
        try {
            repeat(3) { id -> producer.enqueue(Events(events = listOf(Event(id.toLong(), createdMicros = 0, type = EventType.Session, clientId = "event-$id")), sent = id.toLong(), sentMicros = 0, received = id.toLong(), receivedMicros = 0)) }
            repeat(3) { consumer().processDeliveries(consumerQueue.receiveBatch(100, Duration.ZERO).also { assertEquals(1, it.size) }) }
            assertEquals(1L, consumerQueue.status().pending)
            assertEquals(listOf("event-1", "event-2"), IcebergGenerics.read(table).build().use { rows -> rows.map { it.getField("client_id") as String }.sorted() })
            consumerQueue.shutdown()
            failing = false
            consumerQueue = DiskAnalyticsEventQueue(directory.resolve("spool"), Json)
            consumer().processDeliveries(consumerQueue.receiveBatch(100, 6.seconds).also { assertEquals(1, it.size) })
            assertNull(consumerQueue.receive(Duration.ZERO))
            assertEquals(listOf("event-0", "event-1", "event-2"), IcebergGenerics.read(table).build().use { rows -> rows.map { it.getField("client_id") as String }.sorted() })
            assertEquals(3, table.snapshots().count())
        } finally { producer.shutdown(); consumerQueue.shutdown(); storage.shutdown(); directory.toFile().deleteRecursively(); ProviderRegistry.clear() }
    }
}
