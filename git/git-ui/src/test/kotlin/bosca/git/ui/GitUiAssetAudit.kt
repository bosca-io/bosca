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
internal class GitUiAssetAudit(
    private val client: HttpClient,
    private val base: URI,
    private val reportFile: File = File("build/reports/design-assets/git-page-assets.json"),
) {
    private val assets = linkedMapOf<URI, Pair<ByteArray, String>>()

    fun verify() {
        val prefix = "bml/applications/bml.generated/"
        val manifest = requireNotNull(javaClass.classLoader.getResourceAsStream(prefix + "client-manifest.json"))
            .use { kotlinx.serialization.json.Json.decodeFromString(bosca.bml.library.BmlClientManifest.serializer(), it.readAllBytes().decodeToString()) }
        val expectedFonts = manifest.files.filter { it.path.endsWith(".woff2") }.associateBy { it.digest }
        assertEquals(11, expectedFonts.size)
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
            assertEquals(1, scripts.count { Regex("""/_bml/content/app-[a-f0-9]{64}\.js""").matches(it.path) }, path)
            assertEquals(1, scripts.count { Regex("""/_bml/js/[^/]+\.page-[A-Z0-9]{8}\.js""").matches(it.path) }, path)
            assertEquals(2, scripts.size, "$path declares global authentication and its page entry")
            assertTrue(styles.isNotEmpty(), path)

            val modules = linkedSetOf<URI>()
            fun visit(uri: URI) {
                if (!modules.add(uri)) return
                // Esbuild emits these static import declarations; dynamic grammars load on demand.
                Regex("""(?:import|from)\s*["']([^"']+)["']""").findAll(asset(uri).decodeToString()).forEach { match ->
                    if (match.groupValues[1].startsWith(".")) visit(resolve(uri, match.groupValues[1]))
                }
            }
            scripts.forEach(::visit)
            val js = modules.joinToString("\n") { asset(it).decodeToString() }
            styles.forEach { asset(it) }
            for (key in listOf("@bosca/bml/island-runtime", "@bosca/bml/action-runtime")) {
                assertEquals(1, Regex(Regex.escape(key)).findAll(js).count(), "$path duplicates $key")
            }
            assertEquals("source" in engines, "data-bml-source-view" in js, "$path source helper")
            assertEquals("json" in engines, "jse-main" in js, "$path structured editor engine")
            assertTrue("ProseMirror" !in js && "data-document-ready" !in js, "$path must exclude the unused document editor")
            assertTrue("vue-flow__node" !in js && "data-chart-ready" !in js && "data-grid-ready" !in js, "$path must exclude unused visualization engines")
            assertEquals(engines.isNotEmpty(), "cm-editor" in js, "$path CodeMirror engine")
            assertEquals(if (engines.isNotEmpty()) 1 else 0,
                Regex("multiple instances of @codemirror/state").findAll(js).count(),
                "$path must include at most one CodeMirror state implementation")
            if (engines.isNotEmpty()) {
                val budget = if ("source" in engines) 380_000 else 360_000
                assertTrue((modules + styles).sumOf { gzipSize(asset(it)) } < budget,
                    "$path exceeds its measured editor asset budget of $budget gzip bytes")
            }

            val fonts = styles.flatMap { style ->
                Regex("""url\(["']?([^)'"\s]+\.woff2)["']?\)""")
                    .findAll(asset(style).decodeToString()).map { resolve(style, it.groupValues[1]) }.toList()
            }
            assertEquals(11, fonts.size, "$path retains the packaged font subsets")
            assertEquals(fonts.distinct(), fonts, "$path repeats a font subset")
            fonts.forEach { font ->
                val bytes = asset(font)
                assertEquals("wOF2", bytes.take(4).toByteArray().decodeToString(), font.toString())
                val resource = prefix + expectedFonts.getValue(bosca.bml.project.ContentDigest.sha256(bytes)).path
                val expected = requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) { resource }
                    .use { it.readAllBytes() }
                assertEquals(digest(expected), digest(bytes), "$font must retain the packaged font bytes")
            }
            assertEquals(expectedFonts.keys, fonts.map { bosca.bml.project.ContentDigest.sha256(asset(it)) }.toSet())
            buildJsonObject {
                put("path", path)
                put("engines", JsonArray(engines.map(::JsonPrimitive)))
                put("scripts", paths(scripts))
                put("modules", paths(modules.toList()))
                put("stylesheets", paths(styles))
                put("referencedFonts", paths(fonts))
                put("jsRawBytes", modules.sumOf { asset(it).size })
                put("jsGzipBytes", modules.sumOf { gzipSize(asset(it)) })
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
        reportFile.apply {
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
        // Tree-shaken shared ESM barrels can be empty; page entries, styles and fonts must have bytes.
        assertTrue(response.body().isNotEmpty() || uri.path.startsWith("/_bml/js/chunks/"), uri.toString())
        response.body() to type
    }.first

    private fun gzipSize(bytes: ByteArray): Int = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
        output.size()
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
