package bosca.analytics.service

import bosca.analytics.model.Event
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.repository.nats.NatsEventRepository
import bosca.nats.NatsConnectionPool
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.testcontainers.containers.GenericContainer
import java.io.IOException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

class NatsEventRepositoryEndToEndTest {
    @Test
    fun `publisher recovers after an unresponsive server and a closed connection`() = runBlocking {
        GenericContainer("nats:2.12-alpine")
            .withExposedPorts(4222)
            .withCommand("-js")
            .use { server ->
                server.start()
                val pool = NatsConnectionPool("nats://${server.host}:${server.getMappedPort(4222)}", "")
                try {
                    val repository = NatsEventRepository(pool, Json)
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

                    connection.flush(Duration.ofSeconds(10))
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
