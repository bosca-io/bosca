package bosca.bml.message.server

import bosca.artifacts.model.ArtifactVersionPublished
import bosca.nats.NatsConnectionPool
import bosca.pubsub.NatsPubSubServiceImpl
import bosca.pubsub.PubSubService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.BeforeClass
import bosca.test.resources.SharedNatsContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The event-driven half of hot reload against a REAL NATS: the registry's
 * `ArtifactVersionPublished` event — not the poll (set to 10 minutes) and not the seed list
 * (empty) — must register brand-new projects, activate their versions, and hot-swap on
 * subsequent publishes, while events for other namespaces are ignored.
 */
class MessageReloaderNatsTest {

    @Test
    fun `a publish event auto-registers a new project, activates, hot-swaps, and ignores other namespaces`() {
        registry.publish("evented", "1.0", buildMessageJar("nats-one"))
        publish(ArtifactVersionPublished(namespace = "bml-message", repository = "evented", type = "raw", version = "1.0"))
        awaitActive("evented", "1.0")
        val first = runBlocking {
            projects.render("evented", "welcome", bosca.bml.message.BmlMessageContext(assetsUrl = "https://x"))
        }
        assertTrue("content-nats-one" in first.email?.html.orEmpty(), first.email?.html.orEmpty())

        // A different namespace's publish must not touch us.
        publish(ArtifactVersionPublished(namespace = "model", repository = "evented", type = "ml", version = "9.9"))
        Thread.sleep(1_000)
        assertEquals("1.0", projects.activeVersion("evented"))

        // A new version's event hot-swaps.
        registry.publish("evented", "2.0", buildMessageJar("nats-two"))
        publish(ArtifactVersionPublished(namespace = "bml-message", repository = "evented", type = "raw", version = "2.0"))
        awaitActive("evented", "2.0")
        val second = runBlocking {
            projects.render("evented", "welcome", bosca.bml.message.BmlMessageContext(assetsUrl = "https://x"))
        }
        assertTrue("content-nats-two" in second.email?.html.orEmpty(), second.email?.html.orEmpty())
    }

    private fun publish(event: ArtifactVersionPublished) = runBlocking {
        pubSub.publish(ArtifactVersionPublished.CHANNEL, ArtifactVersionPublished.serializer(), event)
    }

    private fun awaitActive(project: String, version: String) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (projects.activeVersion(project) == version) return
            Thread.sleep(100)
        }
        error("event never activated $project@$version (active=${projects.activeVersion(project)})")
    }

    companion object {
        private lateinit var nats: SharedNatsContainer
        private lateinit var pool: NatsConnectionPool
        private lateinit var pubSub: PubSubService
        private val registry = FakeRegistry()
        private lateinit var projects: MessageProjects
        private lateinit var reloader: MessageReloader

        @JvmStatic
        @BeforeClass
        fun boot() {
            nats = SharedNatsContainer()
                .withExposedPorts(4222)
                .withReuse(true)
                .waitingFor(Wait.forListeningPort())
            nats.start()
            pool = nats.newConnectionPool(2)
            pubSub = NatsPubSubServiceImpl(Json, pool)

            registry.start()
            val client = MessageArtifactClient(registry.url, token = null)
            val cache = MessageJarCache(File.createTempFile("bml-message-nats-cache", "").apply { delete(); mkdirs() }, client)
            projects = MessageProjects(cache, MessageReloaderNatsTest::class.java.classLoader)
            // Empty seeds + a poll far beyond the test window: only the EVENT can drive activation.
            reloader = MessageReloader(projects, client, seeds = emptyList(), pollInterval = 10.minutes, pubSub = pubSub)
            reloader.start()
            // The subscription races the first publish; give the dispatcher a beat to attach.
            Thread.sleep(1_000)
        }

        @JvmStatic
        @AfterClass
        fun shutdown() {
            registry.stop()
            projects.close()
        }
    }
}
