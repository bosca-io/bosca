package bosca.cli.swarm

import kotlinx.coroutines.runBlocking
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SwarmImagesTest {
    @Test
    fun `command check and failed lookup leave the config byte for byte unchanged`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var published = true
        var tag = "9.0.0"
        server.createContext("/v2/bosca/bosca-server/tags/list") { exchange ->
            val body = (if (published) "{\"tags\":[\"$tag\"]}" else "{\"tags\":[]}").toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val directory = Files.createTempDirectory("swarm-image-command-")
        val path = directory.resolve("config.json")
        try {
            // No secrets and a missing default image exercise normal load-time config upgrades.
            saveConfig(path, SwarmConfig().let { it.copy(images = it.images - "pgbouncer") })
            val original = Files.readString(path)
            val args = listOf("--config", path.toString(), "--registry", "http://127.0.0.1:${server.address.port}/bosca", "--image", "server")
            val check = SwarmUpdateImagesCommand().test(args + "--check")
            assertEquals(0, check.statusCode, check.output)
            assertEquals(original, Files.readString(path))
            published = false
            val failure = SwarmUpdateImagesCommand().test(args)
            assertEquals(1, failure.statusCode, failure.output)
            assertEquals(original, Files.readString(path))
            published = true
            val update = SwarmUpdateImagesCommand().test(args)
            assertEquals(0, update.statusCode, update.output)
            assertEquals("127.0.0.1:${server.address.port}/bosca/bosca-server:9.0.0", loadConfig(path).images["server"])
            assertEquals("http", loadConfig(path).registrySchemes["127.0.0.1:${server.address.port}"])
            val saved = Files.readString(path)
            tag = "9.1.0"
            val repeatArgs = listOf("--config", path.toString(), "--image", "server")
            val repeatCheck = SwarmUpdateImagesCommand().test(repeatArgs + "--check")
            assertEquals(0, repeatCheck.statusCode, repeatCheck.output)
            assertEquals(saved, Files.readString(path))
            val repeatUpdate = SwarmUpdateImagesCommand().test(repeatArgs)
            assertEquals(0, repeatUpdate.statusCode, repeatUpdate.output)
            assertEquals("127.0.0.1:${server.address.port}/bosca/bosca-server:9.1.0", loadConfig(path).images["server"])
        } finally {
            server.stop(0)
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `update preserves custom repositories infrastructure sites and secrets`() = runBlocking {
        val original = SwarmConfig().withSecrets().let {
            it.copy(images = it.images + ("server" to "registry.example:5000/custom/server@sha256:abc"))
        }
        val requested = mutableListOf<String>()
        val updated = updateSwarmImages(original, null, setOf("server", "bml")) { repository ->
            requested.add(repository)
            "6.100.0"
        }
        assertEquals(listOf("registry.example:5000/custom/server", "ghcr.io/bosca-io/bosca/bml-message-server"), requested)
        assertEquals("registry.example:5000/custom/server:6.100.0", updated.images["server"])
        assertEquals(original.images - setOf("server", "bml"), updated.images - setOf("server", "bml"))
        assertEquals(original.sites, updated.sites)
        assertEquals(original.secrets, updated.secrets)
    }

    @Test
    fun `registry override updates all Bosca images including formerly pinned auxiliary images`() = runBlocking {
        val original = SwarmConfig().withSecrets()
        val updated = updateSwarmImages(original, "https://registry.example/bosca/", emptySet()) { "9.0.0" }
        assertEquals("registry.example/bosca/recommendation-model-loader:9.0.0", updated.images["recommendation-model-loader"])
        assertEquals("registry.example/bosca/imageprocessor:9.0.0", updated.images["imageprocessor"])
        assertEquals("registry.example/bosca/profiles-web:9.0.0", updated.images["profiles-web"])
        assertEquals(original.images["postgres"], updated.images["postgres"])
        assertEquals(original.images["tf-serving"], updated.images["tf-serving"])
    }

    @Test
    fun `HTTPS override replaces a saved HTTP transport`() = runBlocking {
        val original = SwarmConfig().copy(registrySchemes = mapOf("registry.example:5000" to "http"))
        val updated = updateSwarmImages(original, "https://registry.example:5000/bosca", setOf("server")) {
            assertEquals("registry.example:5000/bosca/bosca-server", it)
            "9.0.0"
        }
        assertEquals("https", updated.registrySchemes["registry.example:5000"])
        assertEquals("registry.example:5000/bosca/bosca-server:9.0.0", updated.images["server"])
    }

    @Test
    fun `transport settings reject invalid schemes and registry hosts`() {
        for (schemes in listOf(mapOf("registry.example" to "ftp"), mapOf("registry.example/bosca" to "http"),
            mapOf("user@registry.example" to "https"))) {
            assertFailsWith<IllegalArgumentException> { SwarmConfig().copy(registrySchemes = schemes).validate() }
        }
    }

    @Test
    fun `lookup failure does not mutate the source config`() = runBlocking<Unit> {
        val original = SwarmConfig()
        val pins = original.images.toMap()
        var calls = 0
        assertFailsWith<IllegalStateException> {
            updateSwarmImages(original, null, emptySet()) {
                if (++calls == 2) error("registry unavailable")
                "9.0.0"
            }
        }
        assertEquals(pins, original.images)
        assertFailsWith<IllegalArgumentException> {
            updateSwarmImages(original, null, setOf("postgres")) { error("should not query") }
        }
    }

    @Test
    fun `updated pins preserve encrypted secrets exactly on disk`() = runBlocking {
        val directory = Files.createTempDirectory("swarm-images-")
        val path = directory.resolve("config.json")
        val passphrase = "test passphrase for swarm images".toCharArray()
        try {
            val original = SwarmConfig().withSecrets()
            saveConfig(path, original)
            transformSwarmConfig(path, path, encrypt = true, passphrase = passphrase)
            val encrypted = swarmJson.parseToJsonElement(Files.readString(path)).jsonObject
            SwarmConfigFile(path) { passphrase.copyOf() }.use { file ->
                val loaded = file.load()
                file.save(updateSwarmImages(loaded, "http://registry.example:5000/bosca", setOf("server")) { "9.0.0" })
            }
            val stored = Files.readString(path)
            val updated = swarmJson.parseToJsonElement(stored).jsonObject
            assertEquals(encrypted - setOf("images", "registrySchemes"), updated - setOf("images", "registrySchemes"))
            assertTrue(stored.contains("bosca-server:9.0.0"))
            assertTrue(!stored.contains(original.secrets.postgresAdmin))
            SwarmConfigFile(path) { passphrase.copyOf() }.use { file ->
                assertEquals(original.secrets, file.load().secrets)
                val loaded = file.load()
                assertEquals("registry.example:5000/bosca/bosca-server:9.0.0", loaded.images["server"])
                assertEquals("http", loaded.registrySchemes["registry.example:5000"])
                val next = updateSwarmImages(loaded, null, setOf("server")) {
                    assertEquals("http://registry.example:5000/bosca/bosca-server", it)
                    "9.1.0"
                }
                assertEquals("registry.example:5000/bosca/bosca-server:9.1.0", next.images["server"])
                file.save(next)
                val repeated = swarmJson.parseToJsonElement(Files.readString(path)).jsonObject
                assertEquals(encrypted - setOf("images", "registrySchemes"), repeated - setOf("images", "registrySchemes"))
                val unchanged = Files.readString(path)
                SwarmConfigFile(path) { passphrase.copyOf() }.use { it.save(next) }
                assertEquals(unchanged, Files.readString(path))
            }
        } finally {
            passphrase.fill('\u0000')
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
