package bosca.design.example

import bosca.bml.codegen.BmlProjectGenerator
import bosca.bml.library.BmlLibraryArtifacts
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesignLibraryTest {
    private val classpath = System.getProperty("design.fixture.classpath")
    private val designJar = classpath.split(File.pathSeparator).map(::File).single { it.name.startsWith("bml-design-") }

    @Test
    fun `binary publishes implemented short exports with typed props assets and icon notice`() {
        JarFile(designJar).use { jar ->
            val manifest = BmlLibraryArtifacts.decode(jar.getInputStream(jar.getJarEntry(BmlLibraryArtifacts.MANIFEST_PATH)).reader().readText())
            assertEquals("io.bosca:bml-design", manifest.library)
            assertEquals("bosca.design.generated", manifest.generatedPackage)
            assertEquals(setOf("alert", "app-header", "badge", "bosca-mark", "button", "card", "checkbox", "confirm-dialog", "copy", "copy-field", "dialog", "disclosure-heading",
                "empty-state", "field", "icon", "icon-button", "icon-link", "input", "loading-state", "page-heading", "page-shell", "panel", "popover",
                "section", "section-heading", "spinner", "switch", "table-scroll", "tooltip"),
                manifest.components.filter { it.exported }.map { it.tag }.toSet())
            assertTrue(manifest.requirements.isEmpty())
            assertTrue(manifest.providerRegistrars.isEmpty())
            val button = manifest.components.single { it.tag == "button" }
            assertEquals("Boolean", button.props.single { it.name == "loading" }.declaredType)
            assertEquals("Int", manifest.components.single { it.tag == "icon" }.props.single { it.name == "size" }.declaredType)
            assertEquals(setOf("icon", "spinner").map { BmlLibraryArtifacts.componentId(manifest.library, it) }.toSet(), button.dependencies.toSet())
            assertTrue(button.hasDefaultSlot)
            val heading = manifest.components.single { it.tag == "page-heading" }
            assertEquals("List<bosca.design.model.NavigationItem>", heading.props.single { it.name == "trail" }.declaredType)
            assertTrue(jar.getJarEntry("bosca/design/model/NavigationItem.class") != null)
            assertTrue(manifest.components.single { it.tag == "app-header" }.clientModule != null)
            assertTrue(listOf("tooltip", "icon-button", "icon-link", "dialog", "popover", "copy", "copy-field").all { tag ->
                manifest.components.single { it.tag == tag }.clientModule != null
            })
            assertTrue(jar.getJarEntry("META-INF/bml/client-manifest.json") != null)
            val notice = jar.getInputStream(jar.getJarEntry("META-INF/licenses/bml-design/lucide.txt")).reader().readText()
            assertTrue("ISC License" in notice, notice)
            assertFalse(jar.entries().asSequence().any { it.name.startsWith("bosca/git/") })
        }
    }

    @Test
    fun `real dependency resolves nested imports local precedence collisions and static prop diagnostics`() {
        val work = createTempDirectory("design-library-imports").toFile()
        try {
            val root = File(work, "bml").apply { mkdirs() }
            File(root, "imports.bml").writeText("""<import library="io.bosca:bml-design"/>""")
            val pages = File(root, "pages/nested").apply { mkdirs() }
            File(pages, "home.bml").writeText("""
                <import library="io.bosca:bml-design" as="design"/>
                <page route="/"><button>Local</button><design:button>Library</design:button><html:button>Native</html:button></page>
            """.trimIndent())
            File(root, "local.bml").writeText("""<component tag="button"><html:button><slot/></html:button></component>""")
            val output = File(work, "generated")
            val generator = BmlProjectGenerator("example.fixture")
            val local = generator.generateAll(root, output, listOf(designJar))
            assertTrue(local.errors.isEmpty(), local.diagnostics.toString())
            val generated = File(output, "PagesNestedHomePage.kt").readText()
            assertTrue("bosca.design.generated.ButtonComponent.render" in generated, generated)
            assertTrue("ButtonComponent.render" in generated.replace("bosca.design.generated.ButtonComponent.render", ""), generated)
            File(root, "local.bml").delete()
            File(pages, "home.bml").writeText("""<page route="/"><button loading="not-a-boolean">Save</button></page>""")
            assertTrue(generator.generateAll(root, output, listOf(designJar)).errors.isNotEmpty())
            val manifest = JarFile(designJar).use { jar ->
                BmlLibraryArtifacts.decode(jar.getInputStream(jar.getJarEntry(BmlLibraryArtifacts.MANIFEST_PATH)).reader().readText())
            }
            val second = File(work, "conflicting-library").apply { mkdirs() }
            File(second, BmlLibraryArtifacts.MANIFEST_PATH).apply {
                parentFile.mkdirs()
                writeText(BmlLibraryArtifacts.encode(manifest.copy(library = "example:other", generatedPackage = "example.other",
                    components = listOf(manifest.components.single { it.tag == "button" }.copy(
                        renderer = "example.other.ButtonComponent", dependencies = emptyList(), eagerDependencies = emptyList())))))
            }
            File(root, "imports.bml").appendText("\n<import library=\"example:other\"/>")
            val collision = generator.generateAll(root, output, listOf(designJar, second))
            assertTrue(collision.errors.isNotEmpty())
            val diagnostic = collision.errors.joinToString { it.message }
            assertTrue("io.bosca:bml-design" in diagnostic && "example:other" in diagnostic && "imports.bml" in diagnostic, diagnostic)
        } finally { work.deleteRecursively() }
    }

    @Test
    fun `jar-only consumer serves components native controls aliases and declarative action patches`() {
        for (development in listOf(false, true)) withServer(development) { base, client ->
            val first = request(client, base, "/")
            assertEquals(200, first.statusCode(), first.body())
            val html = first.body()
            assertTrue("Light preferences" in html && "Dark preferences" in html, html)
            assertTrue("name=\"displayName\"" in html && " required" in html && "autocomplete=\"name\"" in html, html)
            assertTrue("data-bml-event=\"submit\"" in html && "data-bml-method=\"increment\"" in html, html)
            assertTrue("id=\"native-button\"" in html && "id=\"native-input\"" in html, html)
            assertFalse("/_git/" in html)
            val links = Regex("<link[^>]+href=\"([^\"]+)\"").findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.toList()
            assertTrue(links.any { it.startsWith("/_bml/libraries/") }, html)
            for (link in links) assertEquals(200, request(client, base, link).statusCode(), link)
            val layout = request(client, base, "/layout").body()
            assertTrue("Workspace" in layout && "Components" in layout && "Layout preview" in layout, layout)
            assertTrue("data-page-heading" in layout && "id=\"layout-details\"" in layout, layout)
            assertFalse("bosca.git" in layout || "/_git/" in layout, layout)
            val actions = request(client, base, "/actions").body()
            assertTrue("aria-label=\"Increment\"" in actions && "title=\"Increment\"" in actions, actions)
            assertTrue("name=\"submitAction\"" in actions && "value=\"save\"" in actions, actions)
            assertTrue("href=\"#link-target\"" in actions && "rel=\"help\"" in actions, actions)
            val overlays = request(client, base, "/overlays").body()
            assertTrue("data-bml-design-dialog-panel" in overlays && "data-modal-form=\"light\"" in overlays, overlays)
            assertTrue("aria-label=\"Edit display name\"" in overlays && "Remove this item?" in overlays, overlays)
            assertTrue("id=\"native-dialog\"" in overlays && "role=\"region\"" in overlays, overlays)
            assertFalse("bosca.git" in overlays || "/_git/" in overlays, overlays)
            val clipboard = request(client, base, "/clipboard").body()
            assertTrue("data-copy-field=\"light\"" in clipboard && " readonly" in clipboard, clipboard)
            assertTrue("aria-label=\"Copy token\"" in clipboard && "Token &quot;alpha&quot; &amp; beta" in clipboard, clipboard)
            assertFalse("bosca.git" in clipboard || "/_git/" in clipboard, clipboard)
            val preferencesId = "preferences.preferences:light"
            val saved = request(client, base, "/_bml/action", """{"page":"/","path":"/","stateKey":"$preferencesId","method":"save","args":["Grace"]}""")
            assertEquals(200, saved.statusCode(), saved.body())
            assertTrue("Saved Grace (1)" in saved.body(), saved.body())
            assertTrue("data-save=\\\"light\\\"" in saved.body(), saved.body())
            val clicked = request(client, base, "/_bml/action", """{"page":"/","path":"/","stateKey":"$preferencesId","method":"increment"}""")
            assertEquals(200, clicked.statusCode(), clicked.body())
            assertTrue("Count 1" in clicked.body(), clicked.body())
            val aliases = request(client, base, "/aliases").body()
            val buttonId = BmlLibraryArtifacts.componentId("io.bosca:bml-design", "button")
            assertTrue(Regex("data-bml-c=\"$buttonId\"").findAll(aliases).count() >= 2, aliases)
            val aliasLinks = Regex("<link[^>]+href=\"([^\"]+)\"").findAll(aliases).map { it.groupValues[1] }.toList()
            assertEquals(aliasLinks.distinct(), aliasLinks, "aliases must not duplicate stylesheet links")
        }
    }

    @Test
    fun `browser preserves native semantics geometry themes and action context`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            val log = File.createTempFile("design-browser-", ".log")
            val process = ProcessBuilder(System.getProperty("design.fixture.node"), System.getProperty("design.fixture.browser"), base,
                if (development) "development" else "production")
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Browser check timed out: ${log.readText()}")
                assertEquals(0, process.exitValue(), log.readText())
            } finally { process.destroyForcibly(); log.delete() }
        }
    }

    private fun withServer(development: Boolean, test: (String, HttpClient) -> Unit) {
        assertTrue(classpath.split(File.pathSeparator).all { File(it).isFile && it.endsWith(".jar") }, classpath)
        val port = ServerSocket(0).use { it.localPort }
        val base = "http://localhost:$port"
        val log = File.createTempFile("design-fixture-", ".log")
        val process = ProcessBuilder(System.getProperty("design.fixture.java"), "-Dbml.dev=$development", "-cp", classpath,
            "bosca.design.example.MainKt", port.toString())
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            var ready = false
            for (attempt in 0 until 200) {
                assertTrue(process.isAlive, log.readText())
                try { java.net.Socket("localhost", port).use { ready = true; break } }
                catch (_: java.net.ConnectException) { Thread.sleep(25) }
            }
            assertTrue(ready, log.readText())
            val client = HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()
            test(base, client)
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            log.delete()
        }
    }

    private fun request(client: HttpClient, base: String, path: String, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(base + path)).timeout(Duration.ofSeconds(10))
        if (body == null) builder.GET() else builder.POST(HttpRequest.BodyPublishers.ofString(body))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
