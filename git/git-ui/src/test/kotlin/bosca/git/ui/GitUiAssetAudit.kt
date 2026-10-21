package bosca.git.ui

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Measures the actual production HTTP assets without changing product records or palettes. */
internal class GitUiAssetAudit(private val client: HttpClient, private val base: URI) {
    private val assets = linkedMapOf<URI, Pair<ByteArray, String>>()

    fun verify() {
        val cases = mapOf(
            "/login" to emptySet(),
            "/repositories" to emptySet(),
            "/pulls/pull?repo=repo&number=1" to emptySet(),
            "/pipelines/pipeline" to emptySet(),
            "/repositories/repo?file=README.md" to setOf("source"),
            "/ci-agents/orchestrator" to setOf("json"),
        )
        val pages = cases.map { (path, engines) ->
            val request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Cookie", "_git_test=ui-test-token").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode(), "$path: ${response.body()}")
            val html = response.body()
            val scripts = Regex("""<script\b[^>]*\bsrc="([^"]+)"""").findAll(html)
                .map { resolve(base, it.groupValues[1]) }.toList()
            val styles = Regex("""<link\b[^>]*>""").findAll(html).map { it.value }
                .filter { Regex("""\brel="stylesheet"""").containsMatchIn(it) }
                .map { tag -> resolve(base, requireNotNull(Regex("""\bhref="([^"]+)"""").find(tag)).groupValues[1]) }
                .toList()
            assertEquals(scripts.distinct(), scripts, "$path repeats a script")
            assertEquals(styles.distinct(), styles, "$path repeats a stylesheet")
            assertEquals(1, scripts.count { it.path == "/_bml/app.js" }, path)
            assertEquals(1, scripts.count { it.path.endsWith(".page.js") }, path)
            assertEquals(2, scripts.size, "$path loads only global authentication and its merged page bundle")
            assertTrue(styles.isNotEmpty(), path)

            val js = scripts.joinToString("\n") { asset(it).decodeToString() }
            styles.forEach { asset(it) }
            for (key in listOf("@bosca/bml/island-runtime", "@bosca/bml/action-runtime")) {
                assertEquals(1, Regex(Regex.escape(key)).findAll(js).count(), "$path duplicates $key")
            }
            assertEquals("source" in engines, "data-bml-source-view" in js, "$path source helper")
            assertEquals("json" in engines, "jse-main" in js, "$path structured editor engine")
            assertEquals(engines.isNotEmpty(), "cm-editor" in js, "$path CodeMirror engine")

            val fonts = styles.flatMap { style ->
                Regex("""url\(["']?([^)'"\s]+\.woff2)["']?\)""")
                    .findAll(asset(style).decodeToString()).map { resolve(style, it.groupValues[1]) }.toList()
            }
            assertEquals(11, fonts.size, "$path retains the packaged font subsets")
            assertEquals(fonts.distinct(), fonts, "$path repeats a font subset")
            fonts.forEach { font -> assertEquals("wOF2", asset(font).take(4).toByteArray().decodeToString(), font.toString()) }
            buildJsonObject {
                put("path", path)
                put("engines", JsonArray(engines.map(::JsonPrimitive)))
                put("scripts", paths(scripts))
                put("stylesheets", paths(styles))
                put("referencedFonts", paths(fonts))
                put("jsRawBytes", scripts.sumOf { asset(it).size })
                put("jsGzipBytes", scripts.sumOf { gzipSize(asset(it)) })
                put("cssRawBytes", styles.sumOf { asset(it).size })
                put("cssGzipBytes", styles.sumOf { gzipSize(asset(it)) })
                put("referencedFontRawBytes", fonts.sumOf { asset(it).size })
            }
        }
        val notices = listOf("geist", "lucide", "source-view", "json-editor").associate { name ->
            val resource = "META-INF/licenses/bml-design/$name.txt"
            val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) { resource }
                .use { it.readAllBytes() }
            assertTrue(bytes.isNotEmpty(), resource)
            resource to JsonPrimitive(digest(bytes))
        }
        val report = buildJsonObject {
            put("mode", "production")
            put("fontMeasurement", "All referenced subsets; a browser downloads subsets matching the rendered text.")
            put("pages", JsonArray(pages))
            put("assets", JsonArray(assets.map { (uri, entry) -> buildJsonObject {
                put("path", uri.rawPath)
                put("contentType", entry.second)
                put("rawBytes", entry.first.size)
                put("gzipBytes", gzipSize(entry.first))
                put("sha256", digest(entry.first))
            } }))
            put("notices", JsonObject(notices))
        }
        File("build/reports/design-assets/git-page-assets.json").apply {
            parentFile.mkdirs()
            writeText(report.toString())
        }
    }

    private fun paths(uris: List<URI>) = JsonArray(uris.map { JsonPrimitive(it.rawPath) })

    private fun resolve(owner: URI, path: String): URI = owner.resolve(path.replace("&amp;", "&")).also {
        assertEquals(base.scheme, it.scheme, path)
        assertEquals(base.authority, it.authority, "$path must be served by the Git UI origin")
        assertTrue(it.path.startsWith("/_bml/"), "$path must use public BML asset serving")
    }

    private fun asset(uri: URI): ByteArray = assets.getOrPut(uri) {
        val response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, response.statusCode(), uri.toString())
        val type = response.headers().firstValue("Content-Type").orElse("")
        when {
            uri.path.endsWith(".js") -> assertTrue("javascript" in type, "$uri: $type")
            uri.path.endsWith(".css") -> assertTrue(type.startsWith("text/css"), "$uri: $type")
            uri.path.endsWith(".woff2") -> assertEquals("font/woff2", type, uri.toString())
            else -> error("Unexpected asset $uri")
        }
        assertTrue(response.body().isNotEmpty(), uri.toString())
        response.body() to type
    }.first

    private fun gzipSize(bytes: ByteArray): Int = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
        output.size()
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
