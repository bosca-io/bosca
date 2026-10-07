package bosca.cli.helm

import bosca.cli.images.boscaImages
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import org.yaml.snakeyaml.Yaml
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HelmImagesTest {
    @Test
    fun `service selection only queries selected images and failures preserve existing output`() {
        val requests = mutableListOf<String>()
        var loaderAvailable = true
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.add(path)
            val available = path == "/v2/bosca/bosca-server/tags/list" ||
                (loaderAvailable && path == "/v2/bosca/recommendation-model-loader/tags/list")
            val body = (if (available) "{\"tags\":[\"6.100.0\"]}" else "{}").toByteArray()
            exchange.sendResponseHeaders(if (available) 200 else 404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val output = Files.createTempFile("selected-helm-images-", ".yaml")
        try {
            val args = listOf("--registry", "http://127.0.0.1:${server.address.port}/bosca",
                "--service", "bosca-server", "--service", "tf-serving", "--output", output.toString())
            val result = HelmImagesCommand().test(args)
            assertEquals(0, result.statusCode, result.output)
            val values = Yaml().load<Map<String, Any>>(Files.readString(output))
            assertEquals(setOf("bosca-server", "tf-serving", "global"), values.keys)
            assertEquals(listOf("/v2/bosca/bosca-server/tags/list", "/v2/bosca/recommendation-model-loader/tags/list"), requests)
            assertEquals("6.100.0", (values.getValue("bosca-server") as Map<*, *>).let { it["image"] as Map<*, *> }["tag"])

            val original = Files.readString(output)
            loaderAvailable = false
            val failure = HelmImagesCommand().test(args)
            assertEquals(1, failure.statusCode, failure.output)
            assertTrue(failure.output.contains("HTTP 404"))
            assertEquals(original, Files.readString(output))
        } finally {
            server.stop(0)
            Files.delete(output)
        }
    }

    @Test
    fun `invalid service selections fail before registry lookup`() {
        val invalid = HelmImagesCommand().test("--service missing --registry invalid")
        assertEquals(1, invalid.statusCode)
        assertTrue(invalid.output.contains("Unknown Bosca service"))
        val standalone = HelmImagesCommand().test("--chart bosca-server --service bosca-server --registry invalid")
        assertEquals(1, standalone.statusCode)
        assertTrue(standalone.output.contains("--service requires --chart bosca-services"))
    }

    @Test
    fun `umbrella values cover all published images and the loader without changing upstream images`() {
        val tags = boscaImages.associate { it.repository to "6.100.0" }
        val values = helmImageValues("bosca-services", "registry.example/bosca", tags)
        for (image in boscaImages) {
            var nested = values.getValue(image.chart) as Map<*, *>
            for (key in image.imagePath.split('.')) nested = nested[key] as Map<*, *>
            assertEquals(image.repository, nested["repository"])
            assertEquals(tags[image.repository], nested["tag"])
            assertEquals("registry.example/bosca", nested["registry"])
            assertEquals("", nested["digest"])
        }
        assertEquals(setOf("loader"), (values.getValue("tf-serving") as Map<*, *>).keys)
        assertEquals(mapOf("imageRegistry" to "registry.example/bosca"), values["global"])
    }

    @Test
    fun `standalone chart values use the chart's image path`() {
        val values = helmImageValues("tf-serving", "registry/bosca", mapOf("recommendation-model-loader" to "6.2.0"))
        assertEquals(setOf("loader", "global"), values.keys)
        val image = ((values.getValue("loader") as Map<*, *>)["image"] as Map<*, *>)
        assertEquals("6.2.0", image["tag"])
    }

    @Test
    fun `command writes usable YAML from a real registry HTTP response`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v2/bosca/bosca-server/tags/list") { exchange ->
            val body = "{\"tags\":[\"6.9.0\",\"6.100.0\",\"latest\"]}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val output = Files.createTempFile("helm-images-", ".yaml")
        try {
            val registry = "127.0.0.1:${server.address.port}/bosca"
            val result = HelmImagesCommand().test(listOf("--chart", "bosca-server", "--registry", "http://$registry", "--output", output.toString()))
            assertEquals(0, result.statusCode, result.output)
            val values = Yaml().load<Map<String, Any>>(Files.readString(output))
            assertEquals("6.100.0", (values.getValue("image") as Map<*, *>)["tag"])
            assertEquals(registry, (values.getValue("image") as Map<*, *>)["registry"])
        } finally {
            server.stop(0)
            Files.delete(output)
        }
    }

    @Test
    fun `invalid chart fails before touching the output`() {
        val result = HelmImagesCommand().test("--chart missing")
        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("Unknown Bosca chart"))
    }
}
