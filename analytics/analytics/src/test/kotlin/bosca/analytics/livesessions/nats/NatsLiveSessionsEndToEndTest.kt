package bosca.analytics.livesessions.nats

import bosca.analytics.model.LiveSession
import bosca.nats.NatsConnectionPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import bosca.test.resources.SharedNatsContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end tests for [NatsLiveSessions] against a real JetStream-enabled NATS server (TestContainers).
 * Each test uses a unique appId so they share one stream without interfering.
 */
class NatsLiveSessionsEndToEndTest {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var natsPool: NatsConnectionPool
    private lateinit var liveSessions: NatsLiveSessions
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        natsContainer = SharedNatsContainer()
            .withExposedPorts(4222)
            .withCommand("-js") // JetStream — the live-sessions stream requires it
            .withReuse(true)
            .waitingFor(Wait.forListeningPort())
        natsContainer.start()

        natsPool = natsContainer.newConnectionPool(5)
        liveSessions = NatsLiveSessions(natsPool, json)
    }

    @AfterTest
    fun teardown() {
        if (::natsContainer.isInitialized) natsContainer.stop()
    }

    private fun session(id: String, version: String? = null) =
        LiveSession(sessionId = id, latitude = 40.7, longitude = -74.0, appVersion = version)

    @Test
    fun `publish then subscribe replays the retained session as a snapshot`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "app-${System.nanoTime()}"
            liveSessions.publish(appId, session("s1", "1.0.0"))

            val received = liveSessions.subscribe(appId).first()

            assertEquals("s1", received.sessionId)
            assertEquals("1.0.0", received.appVersion)
        }
    }

    @Test
    fun `subscribe then publish delivers the live heartbeat`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "app-${System.nanoTime()}"

            val received = async(Dispatchers.Default) { liveSessions.subscribe(appId).first() }
            delay(1_000)
            liveSessions.publish(appId, session("s-live", "2.0.0"))

            assertEquals("s-live", received.await().sessionId)
        }
    }

    @Test
    fun `version filter selects a single version and the wildcard yields all`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "app-${System.nanoTime()}"
            liveSessions.publish(appId, session("s1", "1.0.0"))
            liveSessions.publish(appId, session("s2", "2.0.0"))

            val v1 = liveSessions.subscribe(appId, "1.0.0").first()
            val v2 = liveSessions.subscribe(appId, "2.0.0").first()
            val all = liveSessions.subscribe(appId).take(2).toList()

            assertEquals("s1", v1.sessionId)
            assertEquals("s2", v2.sessionId)
            assertEquals(setOf("s1", "s2"), all.map { it.sessionId }.toSet())
        }
    }

    @Test
    fun `all-applications subscribe spans apps and stamps each session's appId`(): Unit = runBlocking {
        withTimeout(30_000) {
            // The stream is shared (and the container reused), so scope assertions to this test's
            // sessions — an unfiltered subscribe legitimately sees other tests' retained heartbeats.
            val prefix = "all-${System.nanoTime()}"
            liveSessions.publish("$prefix-a", session("$prefix-s1", "1.0.0"))
            liveSessions.publish("$prefix-b", session("$prefix-s2", "2.0.0"))

            val received = liveSessions.subscribe()
                .filter { it.sessionId.startsWith(prefix) }
                .take(2).toList()

            assertEquals(
                mapOf("$prefix-s1" to "$prefix-a", "$prefix-s2" to "$prefix-b"),
                received.associate { it.sessionId to it.appId },
            )
        }
    }

    @Test
    fun `all-applications subscribe narrowed to one version spans apps but filters versions`(): Unit = runBlocking {
        withTimeout(30_000) {
            val prefix = "allv-${System.nanoTime()}"
            val version = "9.9.$prefix" // unique per run so retained cross-test sessions can't match
            liveSessions.publish("$prefix-a", session("$prefix-s1", version))
            liveSessions.publish("$prefix-b", session("$prefix-s2", "1.0.0"))

            val received = liveSessions.subscribe(appId = null, appVersion = version).first()

            assertEquals("$prefix-s1", received.sessionId)
            assertEquals("$prefix-a", received.appId)
        }
    }

    @Test
    fun `creates the stream with a 15-minute retention window`(): Unit = runBlocking {
        withTimeout(30_000) {
            liveSessions.publish("app-${System.nanoTime()}", session("s1"))

            val info = natsPool.systemConnection().jetStreamManagement().getStreamInfo(NatsLiveSessions.STREAM)

            assertEquals(Duration.ofMinutes(15), info.configuration.maxAge)
        }
    }
}
