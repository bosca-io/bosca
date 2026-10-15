@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.analytics.service

import bosca.analytics.model.Event
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.model.EventContext
import bosca.analytics.model.Device
import bosca.analytics.repository.nats.NatsEventRepository
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

class NatsEventRepositoryEndToEndTest {
    @Test
    fun `JetStream deduplicates client IDs per app and preserves every ID-less event`() = runBlocking {
        GenericContainer("nats:2.12-alpine").withExposedPorts(4222).withCommand("-js").use { server ->
            server.start()
            val pool = NatsConnectionPool("nats://${server.host}:${server.getMappedPort(4222)}", "")
            try {
                val repository = NatsEventRepository(pool, Json, EventProcessingHealth())
                val event = Event(1, type = EventType.Session, clientId = "stable-event-id")
                val batch = Events(events = listOf(event), sent = 1, sentMicros = 0)
                repository.process(batch)
                repository.process(batch.copy(sent = 2, received = 20))
                val anonymous = batch.copy(events = listOf(event.copy(clientId = null), event.copy(clientId = null)))
                repository.process(anonymous)
                repository.process(anonymous.copy(received = 40, receivedMicros = 1))
                val context = EventContext(appId = "app-one", appVersion = "1", sessionId = "session",
                    device = Device("installation", "test", "test", "JVM", "en", "test", "UTC", "server", "1"))
                repository.process(batch.copy(context = context))
                repository.process(batch.copy(context = context.copy(appId = "app-two")))
                val info = pool.systemConnection().jetStreamManagement().getStreamInfo(NatsEventRepository.STREAM_NAME)
                assertEquals(7L, info.streamState.msgCount)
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
                        val repository = NatsEventRepository(pool, Json, EventProcessingHealth())
                        val batch = Events(events = listOf(Event(1, type = EventType.Session, clientId = "retry")),
                            sent = 1, sentMicros = 0, received = 1, receivedMicros = 0)
                        repository.process(batch)
                        val consumer = NatsEventConsumer(pool, Json, CompositeEventRepository(storage, emptyList()),
                            EventPipelineTransforms(emptyList()), EventProcessingConfiguration())
                        val (_, subscription) = consumer.initializeJetStream()
                        val first = subscription.fetch(1, Duration.ofSeconds(5)).single()
                        consumer.processMessage(first)
                        val connection = pool.systemConnection()
                        connection.flush(Duration.ofSeconds(5))
                        assertEquals(1L, connection.jetStreamManagement().getStreamInfo(NatsEventRepository.STREAM_NAME).streamState.msgCount)

                        Files.delete(staging)
                        Files.createDirectory(staging)
                        val redelivered = subscription.fetch(1, Duration.ofSeconds(5)).single()
                        assertTrue(redelivered.metaData().deliveredCount() > 1)
                        consumer.processMessage(redelivered)
                        connection.flush(Duration.ofSeconds(5))
                        assertEquals(0L, connection.jetStreamManagement().getStreamInfo(NatsEventRepository.STREAM_NAME).streamState.msgCount)
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
                    val repository = NatsEventRepository(pool, Json, health)
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
                    NatsEventRepository.checkReadiness(pool, health)
                    assertTrue(health.isReady)
                    withTimeout(15_000) { repository.process(events.copy(sent = 3)) }
                    val recovered = connection.jetStreamManagement()
                        .getLastMessage(NatsEventRepository.STREAM_NAME, NatsEventRepository.STREAM_SUBJECT)
                    assertEquals(3L, Json.decodeFromString(Events.serializer(), assertNotNull(recovered.data).decodeToString()).sent)

                    connection.close()
                    withTimeout(15_000) { repository.process(events.copy(sent = 4)) }
                    val replacement = pool.systemConnection()
                    assertNotSame(connection, replacement)
                    val reconnected = replacement.jetStreamManagement()
                        .getLastMessage(NatsEventRepository.STREAM_NAME, NatsEventRepository.STREAM_SUBJECT)
                    assertEquals(4L, Json.decodeFromString(Events.serializer(), assertNotNull(reconnected.data).decodeToString()).sent)
                } finally {
                    pool.close()
                }
            }
    }
}
