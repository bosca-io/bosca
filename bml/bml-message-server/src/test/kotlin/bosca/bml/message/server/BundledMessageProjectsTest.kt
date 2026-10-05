package bosca.bml.message.server

import bosca.bml.message.BmlMessageContext
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class BundledMessageProjectsTest {

    @Test
    fun `missing registry project activates bundled fallback and later promotes published version`() = runBlocking {
        val registry = FakeRegistry().also(FakeRegistry::start)
        try {
            val bundledRoot = tempDirectory("bundled-message-projects")
            writeBundledJar(bundledRoot, "bosca-messages", "1.0", buildMessageJar("bundled"))
            val bundled = BundledMessageProjects(bundledRoot)
            val client = MessageArtifactClient(registry.url, token = null)
            val cache = MessageJarCache(tempDirectory("message-cache"), client, bundled)
            val projects = MessageProjects(cache, javaClass.classLoader)
            val reloader = MessageReloader(
                projects,
                client,
                seeds = emptyList(),
                pollInterval = 10.minutes,
                pubSub = null,
                bundled = bundled,
            )

            reloader.warmup()
            assertEquals("1.0", projects.activeVersion("bosca-messages"))
            assertTrue(
                "content-bundled" in projects.render(
                    "bosca-messages",
                    "welcome",
                    BmlMessageContext(recipientName = "Ada", assetsUrl = "https://email.example/assets"),
                ).email?.html.orEmpty(),
            )

            registry.publish("bosca-messages", "2.0", buildMessageJar("published"))
            reloader.warmup()
            assertEquals("2.0", projects.activeVersion("bosca-messages"))
            assertTrue(
                "content-published" in projects.render(
                    "bosca-messages",
                    "welcome",
                    BmlMessageContext(recipientName = "Ada", assetsUrl = "https://email.example/assets"),
                ).email?.html.orEmpty(),
            )
            projects.close()
        } finally {
            registry.stop()
        }
    }

    @Test
    fun `registry outage on cold start activates bundled fallback`() = runBlocking {
        val registry = FakeRegistry().also(FakeRegistry::start)
        val registryUrl = registry.url
        registry.stop()
        val bundledRoot = tempDirectory("bundled-email-outage")
        writeBundledJar(bundledRoot, "bosca-messages", "1.0", buildMessageJar("outage"))
        val bundled = BundledMessageProjects(bundledRoot)
        val client = MessageArtifactClient(registryUrl, token = null)
        val cache = MessageJarCache(tempDirectory("message-cache-outage"), client, bundled)
        val projects = MessageProjects(cache, javaClass.classLoader)
        val reloader = MessageReloader(
            projects,
            client,
            seeds = emptyList(),
            pollInterval = 10.minutes,
            pubSub = null,
            bundled = bundled,
        )

        reloader.warmup()

        assertEquals("1.0", projects.activeVersion("bosca-messages"))
        assertEquals(listOf("1.0"), bundled.versions("bosca-messages").map { it.version })
        assertEquals(
            bundled.jarFor("bosca-messages", "1.0"),
            cache.jarFor("bosca-messages", "1.0"),
        )
        projects.close()
    }

    @Test
    fun `registry outage keeps an already active published version`() = runBlocking {
        val registry = FakeRegistry().also(FakeRegistry::start)
        registry.publish("bosca-messages", "2.0", buildMessageJar("published-before-outage"))
        val bundledRoot = tempDirectory("bundled-email-no-rollback")
        writeBundledJar(bundledRoot, "bosca-messages", "1.0", buildMessageJar("older-bundled"))
        val bundled = BundledMessageProjects(bundledRoot)
        val client = MessageArtifactClient(registry.url, token = null)
        val cache = MessageJarCache(tempDirectory("message-cache-no-rollback"), client, bundled)
        val projects = MessageProjects(cache, javaClass.classLoader)
        val reloader = MessageReloader(
            projects,
            client,
            seeds = emptyList(),
            pollInterval = 10.minutes,
            pubSub = null,
            bundled = bundled,
        )

        reloader.warmup()
        assertEquals("2.0", projects.activeVersion("bosca-messages"))
        registry.stop()

        reloader.warmup()

        assertEquals("2.0", projects.activeVersion("bosca-messages"))
        assertTrue(
            "content-published-before-outage" in projects.render(
                "bosca-messages",
                "welcome",
                BmlMessageContext(recipientName = "Ada", assetsUrl = "https://email.example/assets"),
            ).email?.html.orEmpty(),
        )
        projects.close()
    }

    @Test
    fun `packager downloads latest artifact into bundled layout`() = runBlocking {
        val registry = FakeRegistry().also(FakeRegistry::start)
        try {
            registry.publish("bosca-messages", "1.0", buildMessageJar("old"))
            val expected = buildMessageJar("latest")
            registry.publish("bosca-messages", "2.0", expected)
            val output = tempDirectory("packaged-emails")

            val packaged = packageLatestMessageProject(
                MessageArtifactClient(registry.url, token = null),
                "bosca-messages",
                output,
            )

            assertEquals("2.0", packaged.version)
            assertEquals(expected.toList(), packaged.jar.readBytes().toList())
            assertEquals("bosca-messages/2.0/bosca-messages.jar", packaged.jar.relativeTo(output).invariantSeparatorsPath)
        } finally {
            registry.stop()
        }
    }

    @Test
    fun `registry URL normalization preserves explicit schemes`() {
        assertEquals("https://artifacts.example", registryBaseUrl("artifacts.example/"))
        assertEquals("http://localhost:8084", registryBaseUrl("http://localhost:8084/"))
    }

    private fun tempDirectory(prefix: String): File =
        File.createTempFile(prefix, "").apply { delete(); mkdirs() }

    private fun writeBundledJar(root: File, project: String, version: String, bytes: ByteArray): File =
        File(root, "$project/$version/$project.jar").also {
            it.parentFile.mkdirs()
            it.writeBytes(bytes)
        }
}
