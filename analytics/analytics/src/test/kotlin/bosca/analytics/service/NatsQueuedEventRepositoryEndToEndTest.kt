@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics.service

import bosca.analytics.model.Event
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.model.EventContext
import bosca.analytics.model.Device
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.transform.EventPipelineTransform
import bosca.analytics.queue.NatsAnalyticsEventQueue
import bosca.analytics.repository.QueuedEventRepository
import bosca.analytics.repository.CompositeEventRepository
import bosca.analytics.repository.iceberg.IcebergEventRepository
import bosca.analytics.configuration.EventPipelineTransforms
import bosca.analytics.configuration.EventProcessingConfiguration
import bosca.analytics.iceberg.EventSchema
import bosca.analytics.transform.iceberg.IcebergEventsToRecordTransform
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.nats.NatsConnectionPool
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.apache.iceberg.hadoop.HadoopTables
import org.apache.iceberg.data.IcebergGenerics
import org.testcontainers.containers.GenericContainer
import java.io.IOException
import java.time.Duration
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

class NatsQueuedEventRepositoryEndToEndTest {
    @Test
    fun `poison delivery retries without restaging or blocking healthy batch peers`(): Unit = runBlocking {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        val connections = mockk<ConnectionPool>()
        every { connections.connection() } answers { ConnectionManager(connections) }
        provides<ConnectionPool> { connections }
        val directory = Files.createTempDirectory("analytics-nats-poison")
        val table = HadoopTables().create(EventSchema, directory.resolve("table").toString())
        val storage = IcebergEventRepository(table, IcebergEventsToRecordTransform(EventSchema), directory.resolve("staging").toString(), ProtoBuf)
        var failing = true
        val transform = object : EventPipelineTransform {
            override suspend fun transform(context: EventPipelineContext, events: Events): Events {
                if (failing && events.events.single().clientId == "poison") throw IllegalStateException("Synthetic poison event")
                return events
            }
        }
        try {
            GenericContainer("nats:2.12-alpine").withExposedPorts(4222).withCommand("-js").use { server ->
                server.start()
                val pool = NatsConnectionPool("nats://${server.host}:${server.getMappedPort(4222)}", "")
                val queue = NatsAnalyticsEventQueue(pool, Json, EventProcessingHealth())
                try {
                    queue.enqueue(Events(events = listOf(Event(1, type = EventType.Session, clientId = "healthy"), Event(2, type = EventType.Session, clientId = "poison")), sent = 1, sentMicros = 0, received = 1, receivedMicros = 0))
                    val consumer = AnalyticsEventConsumer(queue, storage, EventPipelineTransforms(listOf(transform)), EventProcessingConfiguration())
                    val group = queue.receiveBatch(2, kotlin.time.Duration.parse("6s"))
                    assertEquals(2, group.size)
                    consumer.processDeliveries(group)
                    assertEquals(1L, queue.status().pending)
                    assertEquals(listOf("healthy"), IcebergGenerics.read(table).build().use { rows -> rows.map { it.getField("client_id") } })
                    val retry = queue.receiveBatch(2, kotlin.time.Duration.parse("6s"))
                    assertEquals(listOf("poison"), retry.map { it.batches.single().events.single().clientId })
                    consumer.processDeliveries(retry)
                    assertEquals(1, table.snapshots().count())
                    failing = false
                    consumer.processDeliveries(queue.receiveBatch(2, kotlin.time.Duration.parse("6s")).also { assertEquals(1, it.size) })
                    assertEquals(0L, queue.status().pending)
                    assertEquals(setOf("healthy", "poison"), IcebergGenerics.read(table).build().use { rows -> rows.map { it.getField("client_id") }.toSet() })
                } finally { queue.shutdown(); pool.close() }
            }
        } finally { storage.shutdown(); directory.toFile().deleteRecursively(); ProviderRegistry.clear() }
    }

    @Test
    fun `processor fetches bounded JetStream groups and commits each group together`(): Unit = runBlocking {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        val connections = mockk<ConnectionPool>()
        every { connections.connection() } answers { ConnectionManager(connections) }
        provides<ConnectionPool> { connections }
        val directory = Files.createTempDirectory("analytics-nats-batches")
        val table = HadoopTables().create(EventSchema, directory.resolve("table").toString())
        val storage = IcebergEventRepository(table, IcebergEventsToRecordTransform(EventSchema), directory.resolve("staging").toString(), ProtoBuf)
        try {
            GenericContainer("nats:2.12-alpine").withExposedPorts(4222).withCommand("-js").use { server ->
                server.start()
                val pool = NatsConnectionPool("nats://${server.host}:${server.getMappedPort(4222)}", "")
                val queue = NatsAnalyticsEventQueue(pool, Json, EventProcessingHealth())
                try {
                    queue.enqueue(Events(events = List(25) { Event(it.toLong(), type = EventType.Session, clientId = "event-$it") }, sent = 1, sentMicros = 0, received = 1, receivedMicros = 0))
                    val consumer = AnalyticsEventConsumer(queue, storage, EventPipelineTransforms(emptyList()), EventProcessingConfiguration())
                    var expectedRows = 0
                    for (size in listOf(10, 10, 5)) {
                        val group = queue.receiveBatch(10, kotlin.time.Duration.parse("1s"))
                        assertEquals(size, group.size)
                        consumer.processDeliveries(group)
                        expectedRows += size
                        assertEquals(expectedRows, IcebergGenerics.read(table).build().use { it.count() })
                    }
                    assertEquals(3, table.snapshots().count())
                    assertEquals(0L, pool.systemConnection().jetStreamManagement().getStreamInfo(NatsAnalyticsEventQueue.STREAM_NAME).streamState.msgCount)
                } finally { queue.shutdown(); pool.close() }
            }
        } finally { storage.shutdown(); directory.toFile().deleteRecursively(); ProviderRegistry.clear() }
    }

    @Test
    fun `JetStream deduplicates retries within each app installation and user`() = runBlocking {
        GenericContainer("nats:2.12-alpine").withExposedPorts(4222).withCommand("-js").use { server ->
            server.start()
            val pool = NatsConnectionPool("nats://${server.host}:${server.getMappedPort(4222)}", "")
            try {
                val repository = EventProcessingHealth().let { health -> QueuedEventRepository(NatsAnalyticsEventQueue(pool, Json, health), health) }
                val event = Event(1, type = EventType.Session, clientId = "stable-event-id")
                val context = EventContext(appId = "app-one", appVersion = "1", sessionId = "session", userId = "user-one",
                    device = Device("installation", "test", "test", "JVM", "en", "test", "UTC", "server", "1"))
                val batch = Events(context = context, events = listOf(event), sent = 1, sentMicros = 0)
                val contexts = listOf(
                    context,
                    context.copy(appId = "app-two"),
                    context.copy(device = context.device.copy(installationId = "another-installation")),
                    context.copy(userId = "user-two"),
                    context.copy(userId = null),
                    context.copy(userId = ""),
                )
                val management = pool.systemConnection().jetStreamManagement()
                for ((index, scope) in contexts.withIndex()) {
                    repository.process(batch.copy(context = scope))
                    repository.process(batch.copy(context = scope, sent = 2, received = 20))
                    assertEquals(index + 1L, management.getStreamInfo(NatsAnalyticsEventQueue.STREAM_NAME).streamState.msgCount)
                }
                repository.process(batch.copy(events = listOf(event.copy(clientId = "another-event"))))
                val anonymous = batch.copy(events = listOf(event.copy(clientId = null), event.copy(clientId = null)))
                repository.process(anonymous)
                repository.process(anonymous.copy(received = 40, receivedMicros = 1))
                repository.process(batch.copy(context = null))
                repository.process(batch.copy(context = null, sent = 3))
                assertEquals(contexts.size + 7L, management.getStreamInfo(NatsAnalyticsEventQueue.STREAM_NAME).streamState.msgCount)
            } finally {
                pool.close()
            }
        }
    }

    @Test
    fun `processor leaves failed disk writes in JetStream and stores the redelivery`() = runBlocking {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        val connections = mockk<ConnectionPool>()
        every { connections.connection() } answers { ConnectionManager(connections) }
        provides<ConnectionPool> { connections }
        val directory = Files.createTempDirectory("analytics-redelivery")
        try {
            val table = HadoopTables().create(EventSchema, directory.resolve("table").toString())
            val staging = Files.createFile(directory.resolve("staging"))
            val storage = IcebergEventRepository(table, IcebergEventsToRecordTransform(EventSchema), staging.toString(), ProtoBuf)
            try {
                GenericContainer("nats:2.12-alpine").withExposedPorts(4222).withCommand("-js").use { server ->
                    server.start()
                    val pool = NatsConnectionPool("nats://${server.host}:${server.getMappedPort(4222)}", "")
                    try {
                        val repository = EventProcessingHealth().let { health -> QueuedEventRepository(NatsAnalyticsEventQueue(pool, Json, health), health) }
                        val batch = Events(events = listOf(Event(1, type = EventType.Session, clientId = "retry")),
                            sent = 1, sentMicros = 0, received = 1, receivedMicros = 0)
                        repository.process(batch)
                        val consumer = NatsConsumerTestFixture(pool, Json, CompositeEventRepository(storage, emptyList()),
                            EventPipelineTransforms(emptyList()), EventProcessingConfiguration())
                        val (_, subscription) = consumer.initializeJetStream()
                        val first = subscription.fetch(1, Duration.ofSeconds(5)).single()
                        consumer.processMessage(first)
                        val connection = pool.systemConnection()
                        connection.flush(Duration.ofSeconds(5))
                        assertEquals(1L, connection.jetStreamManagement().getStreamInfo(NatsAnalyticsEventQueue.STREAM_NAME).streamState.msgCount)

                        Files.delete(staging)
                        Files.createDirectory(staging)
                        val redelivered = subscription.fetch(1, Duration.ofSeconds(10)).single()
                        assertTrue(redelivered.metaData().deliveredCount() > 1)
                        consumer.processMessage(redelivered)
                        connection.flush(Duration.ofSeconds(5))
                        assertEquals(0L, connection.jetStreamManagement().getStreamInfo(NatsAnalyticsEventQueue.STREAM_NAME).streamState.msgCount)
                        storage.flush()
                        assertEquals(1, IcebergGenerics.read(table).build().use { it.count() })
                    } finally {
                        pool.close()
                    }
                }
            } finally {
                storage.shutdown()
            }
        } finally {
            directory.toFile().deleteRecursively()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `publisher recovers after an unresponsive server and a closed connection`() = runBlocking {
        GenericContainer("nats:2.12-alpine")
            .withExposedPorts(4222)
            .withCommand("-js")
            .use { server ->
                server.start()
                val pool = NatsConnectionPool("nats://${server.host}:${server.getMappedPort(4222)}", "")
                try {
                    val health = EventProcessingHealth()
                    val repository = QueuedEventRepository(NatsAnalyticsEventQueue(pool, Json, health), health)
                    val events = Events(events = listOf(Event(1, type = EventType.Session)), sent = 1, sentMicros = 0)
                    repository.process(events)
                    val connection = pool.systemConnection()

                    // Pause only this test's server so the real client must time out waiting for an acknowledgement.
                    server.dockerClient.pauseContainerCmd(server.containerId).exec()
                    try {
                        assertFailsWith<IOException> {
                            withTimeout(15_000) { repository.process(events.copy(sent = 2)) }
                        }
                    } finally {
                        server.dockerClient.unpauseContainerCmd(server.containerId).exec()
                    }

                    assertFalse(health.isReady)
                    connection.flush(Duration.ofSeconds(10))
                    health.recordQueueReadiness(NatsAnalyticsEventQueue(pool, Json, health))
                    assertTrue(health.isReady)
                    withTimeout(15_000) { repository.process(events.copy(sent = 3)) }
                    val recovered = connection.jetStreamManagement()
                        .getLastMessage(NatsAnalyticsEventQueue.STREAM_NAME, NatsAnalyticsEventQueue.STREAM_SUBJECT)
                    assertEquals(3L, Json.decodeFromString(Events.serializer(), assertNotNull(recovered.data).decodeToString()).sent)

                    connection.close()
                    withTimeout(15_000) { repository.process(events.copy(sent = 4)) }
                    val replacement = pool.systemConnection()
                    assertNotSame(connection, replacement)
                    val reconnected = replacement.jetStreamManagement()
                        .getLastMessage(NatsAnalyticsEventQueue.STREAM_NAME, NatsAnalyticsEventQueue.STREAM_SUBJECT)
                    assertEquals(4L, Json.decodeFromString(Events.serializer(), assertNotNull(reconnected.data).decodeToString()).sent)
                } finally {
                    pool.close()
                }
            }
    }
}
