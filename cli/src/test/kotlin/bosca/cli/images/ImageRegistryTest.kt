package bosca.cli.images

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageRegistryTest {
    @Test
    fun `platform inspection verifies registry bytes and reads image deployment labels`(): Unit = runBlocking {
        val config = buildJsonObject { put("os", "linux"); put("architecture", "amd64"); putJsonObject("config") { putJsonObject("Labels") { put("io.bosca.kit.versions", "image-owned metadata") } } }.toString()
        fun digest(value: String) = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHexString()
        val configDigest = digest(config)
        val manifest = """{"schemaVersion":2,"config":{"digest":"$configDigest"}}"""
        val manifestDigest = digest(manifest)
        val index = """{"manifests":[{"digest":"$manifestDigest","platform":{"os":"linux","architecture":"amd64"}}]}"""
        server { _, exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") == null) {
                exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"registry\"")
                exchange.reply(401, "{}")
            } else when (exchange.requestURI.path.substringAfterLast('/')) {
                "6.0.0" -> exchange.reply(200, index)
                manifestDigest -> exchange.reply(200, manifest)
                configDigest -> exchange.reply(200, config)
                else -> exchange.reply(404, "{}")
            }
        }.use { fixture ->
            val image = ImageRegistry("operator", "secret").inspect("${fixture.base}/bosca/kit:6.0.0")
            assertEquals("${fixture.base.removePrefix("http://")}/bosca/kit@$manifestDigest", image.reference)
            assertEquals(configDigest, image.id)
            assertEquals("linux", image.os)
            assertEquals("amd64", image.architecture)
            assertEquals("image-owned metadata", image.labels["io.bosca.kit.versions"])
            assertFailsWith<IllegalStateException> { ImageRegistry("operator", "secret").inspect("${fixture.base}/bosca/kit:6.0.0", "linux/arm64") }
        }
    }

    @Test
    fun `single manifest preserves its actual platform and an index cannot mislabel it`(): Unit = runBlocking {
        fun digest(value: String) = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHexString()
        val config = """{"os":"linux","architecture":"arm64","config":{"Labels":{}}}"""
        val configDigest = digest(config)
        val manifest = """{"config":{"digest":"$configDigest"}}"""
        val manifestDigest = digest(manifest)
        val index = """{"manifests":[{"digest":"$manifestDigest","platform":{"os":"linux","architecture":"amd64"}}]}"""
        server { _, exchange ->
            exchange.reply(200, when (exchange.requestURI.path.substringAfterLast('/')) {
                "index" -> index
                configDigest -> config
                else -> manifest
            })
        }.use { fixture ->
            val image = ImageRegistry().inspect("${fixture.base}/bosca/kit@$manifestDigest")
            assertEquals("linux", image.os)
            assertEquals("arm64", image.architecture)
            assertFailsWith<IllegalArgumentException> { ImageRegistry().inspect("${fixture.base}/bosca/kit:index") }
        }
    }

    @Test
    fun `inspection rejects a changed manifest or configuration instead of accepting new image identity`(): Unit = runBlocking {
        val missingConfig = "sha256:" + "a".repeat(64)
        server { _, exchange ->
            if (exchange.requestURI.path.contains("/manifests/")) exchange.reply(200, """{"config":{"digest":"$missingConfig"}}""")
            else exchange.reply(200, "{}")
        }.use { fixture ->
            assertFailsWith<IllegalArgumentException> { ImageRegistry().inspect("${fixture.base}/bosca/kit@$missingConfig") }
            assertFailsWith<IllegalArgumentException> { ImageRegistry().inspect("${fixture.base}/bosca/kit:6.0.0") }
        }
    }

    @Test
    fun `stable releases compare numerically and exclude aliases and prereleases`() {
        assertEquals("v6.100.2", latestStableTag(listOf("latest", "6.99.9", "v6.100.2", "7.0.0-rc1", "sha-123", "7.0.0-amd64")))
        assertEquals("6.100.2+build.1", latestStableTag(listOf("6.100.2+build.1", "6.9.20")))
        assertNull(latestStableTag(listOf("latest", "main", "7.0.0-rc1")))
    }

    @Test
    fun `bearer challenge and linked pages resolve a release from the final page`() = runBlocking {
        server { base, exchange ->
            when (exchange.requestURI.path) {
                "/token" -> {
                    assertTrue(exchange.requestURI.rawQuery.contains("scope=repository%3Abosca%2Fbosca-server%3Apull"))
                    exchange.reply(200, """{"access_token":"test-token"}""")
                }
                else -> if (exchange.requestHeaders.getFirst("Authorization") != "Bearer test-token") {
                    exchange.responseHeaders.add("WWW-Authenticate", "Bearer realm=\"$base/token\",service=\"registry\"")
                    exchange.reply(401, "{}")
                } else if (exchange.requestURI.query.contains("last=")) {
                    exchange.reply(200, """{"tags":["6.100.0","7.0.0-rc1"]}""")
                } else {
                    exchange.responseHeaders.add("Link", "</v2/bosca/bosca-server/tags/list?n=2&last=6.9.0>; rel=\"next\"")
                    exchange.reply(200, """{"tags":["6.9.0","latest"]}""")
                }
            }
        }.use { fixture ->
            assertEquals("6.100.0", ImageRegistry().latest("${fixture.base}/bosca/bosca-server"))
        }
    }

    @Test
    fun `Bosca basic authentication and pagination without links include all tags`() = runBlocking {
        val expected = "Basic " + Base64.getEncoder().encodeToString("api_token:secret".toByteArray())
        server { _, exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") != expected) {
                exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"Bosca Registry\"")
                exchange.reply(401, "{}")
            } else if (exchange.requestURI.query.contains("last=")) {
                assertTrue(exchange.requestURI.query.contains("last=1.0.999"))
                exchange.reply(200, """{"tags":["9.0.0"]}""")
            } else {
                exchange.reply(200, "{\"tags\":[${(0..999).joinToString { "\"1.0.$it\"" }}]}")
            }
        }.use { fixture ->
            assertEquals("9.0.0", ImageRegistry("api_token", "secret").latest("${fixture.base}/bosca/server"))
        }
    }

    @Test
    fun `failures and empty tag lists remain failures`() = runBlocking {
        for ((status, body) in listOf(404 to "{}", 200 to "{\"tags\":null}", 200 to "{\"tags\":[\"latest\"]}")) {
            server { _, exchange -> exchange.reply(status, body) }.use { fixture ->
                assertFailsWith<RuntimeException> { ImageRegistry().latest("${fixture.base}/bosca/server") }
            }
        }
    }

    @Test
    fun `pagination cannot send credentials to another registry`() = runBlocking<Unit> {
        server { _, exchange ->
            exchange.responseHeaders.add("Link", "<http://example.invalid/v2/bosca/server/tags/list>; rel=\"next\"")
            exchange.reply(200, "{\"tags\":[\"1.0.0\"]}")
        }.use { fixture ->
            assertFailsWith<IllegalArgumentException> { ImageRegistry().latest("${fixture.base}/bosca/server") }
        }
    }

    private class Fixture(val server: HttpServer) : AutoCloseable {
        val base = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private fun server(handler: (String, HttpExchange) -> Unit): Fixture {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val fixture = Fixture(server)
        server.createContext("/") { exchange -> handler(fixture.base, exchange) }
        server.start()
        return fixture
    }

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
