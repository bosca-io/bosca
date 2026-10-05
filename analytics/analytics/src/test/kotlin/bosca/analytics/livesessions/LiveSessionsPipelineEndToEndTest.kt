package bosca.analytics.livesessions

import bosca.analytics.livesessions.nats.NatsLiveSessions
import bosca.analytics.model.Device
import bosca.analytics.model.Event
import bosca.analytics.model.EventContext
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.transform.SessionHeartbeatTransform
import bosca.analytics.transform.geo.CloudflareGeoTransform
import bosca.counter.Counter
import bosca.di.ObjectProvider
import bosca.nats.NatsConnectionPool
import bosca.server.headersOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import bosca.test.resources.SharedNatsContainer
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * End-to-end pipeline test. A heartbeat batch flows through the REAL
 * collector chain — [CloudflareGeoTransform] enriches lat/lon from CF headers, then
 * [SessionHeartbeatTransform] publishes to a REAL NATS-backed [LiveSessionsService] — and a subscriber
 * receives it. Covers the cross-component behaviours: appear, version segmentation, and no-geo drop. (TTL
 * expiry and the raw NATS/Redis snapshot+tail are covered by the primitive's own end-to-end tests; the
 * live-fed visualization render path by the `@bosca/ui-analytics` unit tests.)
 */
class LiveSessionsPipelineEndToEndTest {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var natsPool: NatsConnectionPool
    private lateinit var liveSessions: NatsLiveSessions
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        natsContainer = SharedNatsContainer()
            .withExposedPorts(4222)
            .withCommand("-js")
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

    private fun device() = Device(
        installationId = "inst", manufacturer = "m", model = "mo", platform = "p",
        primaryLocale = "en", systemName = "s", timezone = "UTC", type = "t", version = "1",
    )

    private fun heartbeat(appId: String, sessionId: String, appVersion: String) = Events(
        context = EventContext(appId = appId, appVersion = appVersion, device = device(), sessionId = sessionId, geo = null),
        events = listOf(Event(created = 1000L, type = EventType.Heartbeat)),
        sent = 1000L,
        sentMicros = 0,
    )

    private fun headers(vararg pairs: Pair<String, String>): EventPipelineContext =
        EventPipelineContext(headersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

    private val cfGeo get() = headers("cf-iplatitude" to "40.7", "cf-iplongitude" to "-74.0")
    private val noGeo get() = headers()

    /** Runs a batch through geo-enrichment then the heartbeat transform, exactly as the collector pipeline does. */
    private suspend fun ingest(pipelineContext: EventPipelineContext, batch: Events) {
        val provider = mockk<ObjectProvider<LiveSessionsService>>()
        coEvery { provider.get() } returns liveSessions
        val counter = mockk<Counter>(relaxed = true)
        val enriched = CloudflareGeoTransform().transform(pipelineContext, batch)
        SessionHeartbeatTransform(counter, provider).transform(pipelineContext, enriched)
    }

    @Test
    fun `a geo-tagged heartbeat flows through the pipeline and appears on the live map`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "app-${System.nanoTime()}"
            val subscriber = async(Dispatchers.Default) { liveSessions.subscribe(appId).first() }
            delay(1_000)

            ingest(cfGeo, heartbeat(appId, "s1", "1.0.0"))

            val session = subscriber.await()
            assertEquals("s1", session.sessionId)
            assertEquals(40.7, session.latitude)
            assertEquals(-74.0, session.longitude)
            assertEquals("1.0.0", session.appVersion)
        }
    }

    @Test
    fun `version segmentation filters the map by app version`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "app-${System.nanoTime()}"
            ingest(cfGeo, heartbeat(appId, "s1", "1.0.0"))
            ingest(cfGeo, heartbeat(appId, "s2", "2.0.0"))

            val v2 = liveSessions.subscribe(appId, "2.0.0").first()
            val all = liveSessions.subscribe(appId).take(2).toList()

            assertEquals("s2", v2.sessionId)
            assertEquals(setOf("s1", "s2"), all.map { it.sessionId }.toSet())
        }
    }

    @Test
    fun `a heartbeat without Cloudflare geo does not appear on the map`(): Unit = runBlocking {
        withTimeout(30_000) {
            val appId = "app-${System.nanoTime()}"
            ingest(noGeo, heartbeat(appId, "s1", "1.0.0"))

            // Nothing published → the snapshot is empty and the subscription just waits.
            val received = withTimeoutOrNull(2_000) { liveSessions.subscribe(appId).first() }
            assertNull(received)
        }
    }
}
