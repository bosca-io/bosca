package bosca.design.example

import bosca.bml.codegen.BmlProjectGenerator
import bosca.bml.library.BmlLibraryArtifacts
import bosca.bml.library.BmlLibraryClientManifest
import bosca.bml.project.ContentDigest
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.channels.ServerSocketChannel
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.Properties
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Execution(ExecutionMode.CONCURRENT)
class DesignLibraryTest {
    private val classpath = System.getProperty("design.fixture.classpath")
    private val designJar = classpath.split(File.pathSeparator).map(::File).single { it.name.startsWith("bml-design-") }

    @Test
    fun `standalone consumer embeds application stylesheet and dependency notices`() {
        val log = File.createTempFile("design-resources-", ".log")
        withLauncher { command, directory ->
            val process = ProcessBuilder(command + "--check-resources").directory(directory)
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Resource check timed out: ${log.readText()}")
                assertEquals(0, process.exitValue(), log.readText())
                val actual = log.readLines().filter { '\t' in it }.associate {
                    val (path, digest) = it.split('\t'); path to digest
                }
                val expected = listOf("design-example.css", "logback.xml", "META-INF/licenses/bml-design/geist.txt",
                    "META-INF/licenses/bml-design/lucide.txt", "META-INF/licenses/bml-design/source-view.txt",
                    "META-INF/licenses/bml-design/json-editor.txt", "META-INF/licenses/bml-design/document-editor.txt",
                    "META-INF/licenses/bml-design/flow-editor.txt", "META-INF/licenses/bml-design/chart.txt").associateWith { path ->
                    val bytes = checkNotNull(DesignLibraryTest::class.java.getResourceAsStream("/$path")).use { it.readBytes() }
                    ContentDigest.sha256(bytes)
                }
                assertEquals(expected, actual)
            } finally { process.destroyForcibly(); log.delete() }
        }
    }

    @Test
    fun `generated adapter and public select payloads type check against linked binary declarations`() {
        val log = File.createTempFile("design-types-", ".log")
        val process = ProcessBuilder(System.getProperty("design.fixture.node"),
            System.getProperty("design.fixture.typechecker"), "--project", System.getProperty("design.fixture.tsconfig"))
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Type check timed out: ${log.readText()}")
            assertEquals(0, process.exitValue(), log.readText())
        } finally { process.destroyForcibly(); log.delete() }
    }

    @Test
    fun `binary publishes implemented short exports with typed props assets and icon notice`() {
        JarFile(designJar).use { jar ->
            val manifest = BmlLibraryArtifacts.decode(jar.getInputStream(jar.getJarEntry(BmlLibraryArtifacts.MANIFEST_PATH)).reader().readText())
            assertEquals("io.bosca:bml-design", manifest.library)
            assertEquals("bosca.design.generated", manifest.generatedPackage)
            assertEquals(setOf("alert", "app-header", "app-shell", "badge", "bosca-mark", "button", "card", "chart", "checkbox", "comment-editor", "confirm-dialog", "copy", "copy-field", "dashboard-grid", "dashboard-item", "dialog", "disclosure-heading",
                "document-editor", "empty-state", "field", "flow-editor", "fullscreen-button", "icon", "icon-button", "icon-link", "input", "json-editor", "loading-state", "page-heading", "page-shell", "panel", "popover", "profile", "profile-menu",
                "search-input", "select", "section", "section-heading", "segmented-control", "source-view", "spinner", "switch", "table-scroll", "tabs", "time", "toast", "tree", "tree-item", "tooltip", "view-tabs", "workspace"),
                manifest.components.filter { it.exported }.map { it.tag }.toSet())
            assertTrue(manifest.requirements.isEmpty())
            assertTrue(manifest.providerRegistrars.isEmpty())
            for ((tag, expected) in mapOf(
                "segmented-control" to ("options" to "List<bosca.design.model.SegmentOption>"),
                "view-tabs" to ("tabs" to "List<bosca.design.model.ViewTab>"),
                "button" to ("size" to "String"),
                "toast" to ("tone" to "String"),
                "tooltip" to ("placement" to "String"),
                "chart" to ("config" to "bosca.design.model.ChartConfiguration"),
                "flow-editor" to ("value" to "bosca.design.model.FlowGraph"),
                "dashboard-grid" to ("value" to "List<bosca.design.model.DashboardLayoutItem>"),
                "document-editor" to ("value" to "kotlinx.serialization.json.JsonObject"),
                "json-editor" to ("value" to "kotlinx.serialization.json.JsonElement"),
            )) {
                assertEquals(expected.second, manifest.components.single { it.tag == tag }.props.single { it.name == expected.first }.declaredType)
            }
            for ((tag, names) in mapOf(
                "button" to listOf("size"), "spinner" to listOf("size"), "input" to listOf("size"),
                "select" to listOf("size"), "icon-button" to listOf("size"), "dialog" to listOf("size", "triggerStyle"),
                "confirm-dialog" to listOf("triggerStyle"), "alert" to listOf("tone"), "badge" to listOf("tone"),
                "page-heading" to listOf("badgeTone"), "toast" to listOf("tone"), "tooltip" to listOf("placement"),
                "json-editor" to listOf("mode"),
            )) for (name in names) {
                assertEquals("String", manifest.components.single { it.tag == tag }.props.single { it.name == name }.declaredType, "$tag.$name")
            }
            assertEquals("Set<String>", manifest.components.single { it.tag == "dashboard-grid" }.props.single { it.name == "lockedIds" }.declaredType)
            assertEquals("String?", manifest.components.single { it.tag == "json-editor" }.props.single { it.name == "text" }.declaredType)
            assertEquals("bosca.content.metadata.model.Document?", manifest.components.single { it.tag == "document-editor" }.props.single { it.name == "document" }.declaredType)
            val button = manifest.components.single { it.tag == "button" }
            assertEquals("Boolean", button.props.single { it.name == "loading" }.declaredType)
            assertEquals("Int", manifest.components.single { it.tag == "icon" }.props.single { it.name == "size" }.declaredType)
            assertEquals(setOf("icon", "spinner").map { BmlLibraryArtifacts.componentId(manifest.library, it) }.toSet(), button.dependencies.toSet())
            assertTrue(button.hasDefaultSlot)
            val heading = manifest.components.single { it.tag == "page-heading" }
            assertEquals("List<bosca.design.model.NavigationItem>", heading.props.single { it.name == "trail" }.declaredType)
            assertTrue(jar.getJarEntry("bosca/design/model/NavigationItem.class") != null)
            assertTrue(manifest.components.single { it.tag == "app-header" }.clientModule != null)
            assertTrue(listOf("tooltip", "icon-button", "icon-link", "dialog", "popover", "copy", "copy-field", "toast", "search-input", "segmented-control", "view-tabs", "tabs", "select", "tree", "comment-editor", "source-view", "json-editor", "document-editor", "chart", "flow-editor", "dashboard-grid").all { tag ->
                manifest.components.single { it.tag == tag }.clientModule != null
            })
            assertTrue(manifest.components.single { it.tag == "workspace" }.clientModule != null)
            assertTrue(manifest.components.single { it.tag == "profile-menu" }.clientModule != null)
            assertEquals("Boolean", manifest.components.single { it.tag == "source-view" }.props.single { it.name == "fill" }.declaredType)
            val toast = manifest.components.single { it.tag == "toast" }
            assertFalse(toast.hasDefaultSlot)
            assertEquals("Int", toast.props.single { it.name == "sequence" }.declaredType)
            assertEquals("Int", toast.props.single { it.name == "duration" }.declaredType)
            assertTrue(jar.getJarEntry("META-INF/bml/client-manifest.json") != null)
            val notice = jar.getInputStream(jar.getJarEntry("META-INF/licenses/bml-design/lucide.txt")).reader().readText()
            assertTrue("ISC License" in notice, notice)
            val sourceNotice = jar.getInputStream(jar.getJarEntry("META-INF/licenses/bml-design/source-view.txt")).reader().readText()
            assertTrue("codemirror 6.0.2" in sourceNotice && "Permission is hereby granted" in sourceNotice, sourceNotice)
            val jsonNotice = jar.getInputStream(jar.getJarEntry("META-INF/licenses/bml-design/json-editor.txt")).reader().readText()
            assertTrue("vanilla-jsoneditor 3.13.0" in jsonNotice && "Font Awesome Free License" in jsonNotice, jsonNotice)
            val documentNotice = jar.getInputStream(jar.getJarEntry("META-INF/licenses/bml-design/document-editor.txt")).reader().readText()
            assertTrue("@tiptap/core 2.27.3" in documentNotice && "Copyright (c) 2025, Tiptap GmbH" in documentNotice, documentNotice)
            assertFalse(jar.entries().asSequence().any { it.name.startsWith("bosca/git/") })
        }
    }

    @Test
    fun `jar-only consumer serves original fonts once with source assets and license notices`() {
        val hashes = Properties().apply {
            requireNotNull(DesignLibraryTest::class.java.getResourceAsStream("/geist-sha256.properties")).use { load(it) }
        }
        val expected = hashes.stringPropertyNames().associateWith { "sha256:${hashes.getProperty(it)}" }
        assertEquals(11, expected.size)
        val originalBytes = JarFile(designJar).use { binary ->
            val manifest = BmlLibraryClientManifest.decode(binary.getInputStream(binary.getJarEntry(BmlLibraryClientManifest.PATH)).reader().readText())
            val fonts = manifest.files.filter { it.path.endsWith(".woff2") }
            assertEquals(11, fonts.size)
            val bytesByDigest = fonts.associate { font ->
                val bytes = binary.getInputStream(binary.getJarEntry("${manifest.resourcePrefix}/${font.path}")).readBytes()
                assertEquals(font.digest, ContentDigest.sha256(bytes))
                font.digest to bytes
            }
            assertEquals(expected.values.toSet(), bytesByDigest.keys)
            JarFile(File(designJar.parentFile, "${designJar.nameWithoutExtension}-sources.jar")).use { sources ->
                for ((name, digest) in expected) {
                    val bytes = sources.getInputStream(sources.getJarEntry("client/fonts/$name")).readBytes()
                    assertEquals(digest, ContentDigest.sha256(bytes), name)
                    assertTrue(bytes.contentEquals(bytesByDigest.getValue(digest)), name)
                }
                for (name in listOf("geist", "lucide", "source-view", "json-editor", "document-editor", "flow-editor", "chart")) {
                    val path = "META-INF/licenses/bml-design/$name.txt"
                    val notice = binary.getInputStream(binary.getJarEntry(path)).readBytes()
                    assertTrue(notice.isNotEmpty(), path)
                    assertTrue(notice.contentEquals(sources.getInputStream(sources.getJarEntry(path)).readBytes()), path)
                    if (name == "geist") assertTrue("SIL OPEN FONT LICENSE Version 1.1" in notice.decodeToString())
                }
            }
            bytesByDigest
        }
        for (development in listOf(false, true)) withServer(development) { base, client ->
            val aliases = request(client, base, "/aliases")
            assertEquals(200, aliases.statusCode(), "development=$development: ${aliases.body()}")
            val html = aliases.body()
            val links = Regex("<link[^>]+href=\"([^\"]+)\"").findAll(html)
                .map { it.groupValues[1].replace("&amp;", "&") }
                .filter { it.startsWith("/_bml/libraries/") && it.substringBefore('?').endsWith(".css") }.toList()
            assertEquals(links.distinct(), links)
            val styles = links.map { request(client, base, it).also { response -> assertEquals(200, response.statusCode()) }.body() }
            val fontStyles = styles.filter { "@font-face" in it }
            assertEquals(1, fontStyles.size, "development=$development links=$links html=$html styles=$styles")
            val css = fontStyles.single()
            assertFalse("/_git/" in css || "https://" in css || "file:" in css)
            assertEquals(11, Regex("@font-face").findAll(css).count())
            val urls = Regex("""url\(["']?(/_bml/libraries/[^)"']+\.woff2)["']?\)""").findAll(css).map { it.groupValues[1] }.toList()
            assertEquals(11, urls.size)
            assertEquals(urls.distinct(), urls)
            val served = urls.map { url ->
                val response = client.send(HttpRequest.newBuilder(URI(base + url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                assertEquals(200, response.statusCode(), url)
                assertEquals("font/woff2", response.headers().firstValue("Content-Type").orElse(""))
                assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(""))
                val digest = ContentDigest.sha256(response.body())
                assertTrue(response.body().contentEquals(originalBytes.getValue(digest)), url)
                digest
            }
            assertEquals(expected.values.toSet(), served.toSet())
            assertEquals(404, request(client, base, urls.first().substringBeforeLast('/') + "/missing.woff2").statusCode())
        }
    }

    @Test
    fun `editor assets are component reachable with readable server fallbacks`() {
        val application = classpath.split(File.pathSeparator).map(::File).single { it.name.startsWith("design-library-") }
        JarFile(application).use { jar ->
            fun script(name: String): String {
                val entry = jar.entries().asSequence().single { it.name.endsWith("/prod/$name.page.js") }
                val prefix = entry.name.substringBeforeLast('/') + "/"
                val imports = jar.getInputStream(jar.getJarEntry(prefix + "bml-imports.json")).reader().use {
                    Json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), it.readText())
                }
                return imports.getValue("$name.page.js").joinToString("\n") { path ->
                    jar.getInputStream(requireNotNull(jar.getJarEntry(prefix + path))).reader().use { it.readText() }
                }
            }
            val basic = script("index")
            assertFalse("cm-editor" in basic || "source-keyword" in basic || "jse-main" in basic)
            val widgetMarkers = listOf("data-document-ready", "data-flow-ready", "data-chart-ready", "data-grid-ready")
            assertTrue(widgetMarkers.none { it in basic }, "Basic controls exclude optional widget engines")
            val widgets = script("widgets")
            assertTrue(widgetMarkers.all { it in widgets }, "The independent widget page includes all four engines")
            val json = script("json")
            assertTrue("jse-main" in json && "jse-tree-mode" in json && "jse-table-mode" in json)
            assertFalse(Regex("""(?:from|import)\s*["'](?:vanilla-jsoneditor(?:/index.js)?|svelte|@fortawesome/free-(?:regular|solid)-svg-icons)["']""").containsMatchIn(json))
            val source = script("source")
            assertFalse("jse-main" in source)
            assertTrue("cm-editor" in source && "source-keyword" in source)
            assertFalse("Discard your uncommitted changes?" in source || "Rendering preview" in source)
            assertFalse(Regex("""(?:from|import)\s*["'](?:@codemirror|codemirror|@lezer)""").containsMatchIn(source))
        }
        for (development in listOf(false, true)) withServer(development) { base, client ->
            fun styles(html: String): List<String> = Regex("<link[^>]+href=\"([^\"]+)\"").findAll(html)
                .map { it.groupValues[1].replace("&amp;", "&") }.filter { it.substringBefore('?').endsWith(".css") }
                .map { request(client, base, it).also { response -> assertEquals(200, response.statusCode()) }.body() }.toList()
            val basic = request(client, base, "/").body()
            assertFalse(styles(basic).any { ".source-keyword" in it || ".cm-editor" in it || "--jse-background-color" in it })
            val source = request(client, base, "/source").body()
            assertTrue("data-bml-source-view" in source && "data-source=\"light\"" in source, source)
            assertTrue("&lt;script&gt;unsafe()&lt;/script&gt;" in source, source)
            assertFalse("<script>unsafe()</script>" in source, source)
            assertTrue(styles(source).any { ".source-keyword" in it && ".cm-editor" in it })
            assertFalse(styles(source).any { "--jse-background-color" in it })
            val json = request(client, base, "/json").body()
            assertTrue("data-bml-json-editor" in json && "name=\"settings\"" in json && "Invalid draft" in json, json)
            assertTrue("&lt;script&gt;unsafe()&lt;/script&gt;" in json && "{invalid: " in json, json)
            assertFalse("<script>unsafe()</script>" in json || "/_git/" in json, json)
            assertTrue(styles(json).any { "--jse-background-color" in it })
            assertFalse(styles(json).any { ".source-keyword" in it })
            val widgets = request(client, base, "/widgets").body()
            assertTrue(listOf("data-bml-document-editor", "data-bml-flow-editor", "data-bml-dashboard-grid", "data-bml-chart").all { it in widgets }, widgets)
            assertTrue("Visits" in widgets && "Monday" in widgets && "name=\"document\"" in widgets && "name=\"graph\"" in widgets && "name=\"layout\"" in widgets, widgets)
            assertTrue(styles(widgets).any { ".tiptap" in it })
            assertTrue(styles(widgets).any { ".vue-flow__node" in it })
            assertFalse(styles(widgets).any { "--jse-background-color" in it || ".source-keyword" in it })
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
            val notifications = request(client, base, "/notifications").body()
            assertTrue("data-notice=\"light\"" in notifications && "data-bml-preserve=\"notice-light\"" in notifications, notifications)
            assertTrue("data-sequence=\"0\"" in notifications && "data-duration=\"4000\"" in notifications, notifications)
            assertTrue("aria-label=\"Dismiss notification\"" in notifications, notifications)
            assertFalse("bosca.git" in notifications || "/_git/" in notifications, notifications)
            val selection = request(client, base, "/selection").body()
            assertTrue("data-search-query=\"light\"" in selection && "maxlength=\"120\"" in selection, selection)
            assertTrue("role=\"radiogroup\"" in selection && "role=\"tablist\"" in selection, selection)
            assertTrue("name=\"mode\"" in selection && "data-value=\"content\"" in selection, selection)
            assertFalse("bosca.git" in selection || "/_git/" in selection, selection)
            val routes = request(client, base, "/navigation").body()
            assertTrue("data-routes=\"light\"" in routes && "aria-label=\"Routes light 0\"" in routes, routes)
            assertTrue("href=\"/navigation-target?item=8\"" in routes && "aria-current=\"page\"" in routes, routes)
            assertTrue("data-bml-ref=\"more\"" in routes && "data-clipped-routes" in routes, routes)
            assertFalse("bosca.git" in routes || "/_git/" in routes, routes)
            val selects = request(client, base, "/selects").body()
            assertTrue("data-select-single=\"light\"" in selects && "data-bml-design-select" in selects, selects)
            assertTrue("data-persistent" in selects && "name=\"toppings\"" in selects && " multiple" in selects, selects)
            assertTrue("id=\"native-select\"" in selects && "data-bml-preserve=\"remote-light-0\"" in selects, selects)
            assertFalse("bosca.git" in selects || "/_git/" in selects, selects)
            val composition = request(client, base, "/composition").body()
            assertTrue("role=\"tree\"" in composition && "data-remote-directory=\"light\"" in composition, composition)
            assertTrue("data-comment=\"light\"" in composition && "name=\"body\"" in composition, composition)
            assertTrue("Oct 18, 13:15 UTC" in composition && "2026-10-18 08:15 -05:00" in composition, composition)
            assertFalse("bosca.git" in composition || "/_git/" in composition, composition)
            val choices = request(client, base, "/_bml/contract/SelectChoices/search", "[\"apple\",0]")
            assertEquals(200, choices.statusCode(), choices.body())
            assertTrue("apple-0" in choices.body() && "apple choice 2" in choices.body(), choices.body())
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
    fun `consumer children bind distinct ports beside occupied IPv4 and IPv6 listeners`() {
        ServerSocketChannel.open(StandardProtocolFamily.INET).use { ipv4 ->
            ipv4.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            ServerSocketChannel.open(StandardProtocolFamily.INET6).use { ipv6 ->
                ipv6.bind(InetSocketAddress(InetAddress.getByName("::1"), 0))
                val occupied = listOf(ipv4, ipv6).map { (it.localAddress as InetSocketAddress).port }
                withServer(false) { production, productionClient ->
                    withServer(true) { development, developmentClient ->
                        assertFalse(URI(production).port in occupied)
                        assertFalse(URI(development).port in occupied)
                        assertFalse(production == development)
                        for ((base, client) in listOf(production to productionClient, development to developmentClient)) {
                            val response = request(client, base, "/aliases")
                            assertEquals(200, response.statusCode(), response.body())
                            assertTrue("data-alias=\"plain\"" in response.body(), response.body())
                            assertTrue("/_bml/libraries/" in response.body(), response.body())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `browser preserves native semantics geometry themes and action context`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            val log = File.createTempFile("design-browser-", ".log")
            val mode = (if (System.getProperty("design.fixture.binary") == null) "" else "native-") +
                if (development) "development" else "production"
            val process = ProcessBuilder(System.getProperty("design.fixture.node"), System.getProperty("design.fixture.browser"), base,
                mode)
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Browser check timed out: ${log.readText()}")
                assertEquals(0, process.exitValue(), log.readText())
            } finally { process.destroyForcibly(); log.delete() }
        }
    }

    @Test
    fun `selected values and active panels survive availability changes`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            val log = File.createTempFile("design-typed-choices-", ".log")
            val process = ProcessBuilder(System.getProperty("design.fixture.node"),
                System.getProperty("design.fixture.browser"), base,
                if (development) "development-typed-choices" else "production-typed-choices", "typed-choices")
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Choice check timed out: ${log.readText()}")
                assertEquals(0, process.exitValue(), log.readText())
            } finally { process.destroyForcibly(); log.delete() }
        }
    }

    @Test
    fun `structured Kotlin inputs retain editor drafts widgets and native values`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            val log = File.createTempFile("design-structured-", ".log")
            val process = ProcessBuilder(System.getProperty("design.fixture.node"),
                System.getProperty("design.fixture.browser"), base,
                if (development) "development-structured" else "production-structured", "structured")
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Structured input check timed out: ${log.readText()}")
                assertEquals(0, process.exitValue(), log.readText())
            } finally { process.destroyForcibly(); log.delete() }
        }
    }

    @Test
    fun `workspaces fill and expand from the linked library in an independent consumer`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            val log = File.createTempFile("design-workspaces-", ".log")
            val process = ProcessBuilder(System.getProperty("design.fixture.node"),
                System.getProperty("design.fixture.browser"), base,
                if (development) "development-workspace" else "production-workspace", "workspace")
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Workspace check timed out: ${log.readText()}")
                assertEquals(0, process.exitValue(), log.readText())
            } finally { process.destroyForcibly(); log.delete() }
        }
    }

    @Test
    fun `shell and profiles use shared presentation with consumer actions`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            val log = File.createTempFile("design-shell-", ".log")
            val process = ProcessBuilder(System.getProperty("design.fixture.node"),
                System.getProperty("design.fixture.browser"), base,
                if (development) "development-shell" else "production-shell", "shell")
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Shell check timed out: ${log.readText()}")
                assertEquals(0, process.exitValue(), log.readText())
            } finally { process.destroyForcibly(); log.delete() }
        }
    }

    private fun withServer(development: Boolean, test: (String, HttpClient) -> Unit) {
        withLauncher { command, directory -> withServer(command, directory, development, test) }
    }

    private fun withLauncher(test: (List<String>, File?) -> Unit) {
        val binary = System.getProperty("design.fixture.binary")?.let(::File)
        if (binary == null) {
            test(listOf(System.getProperty("design.fixture.java"), "-cp", classpath, "bosca.design.example.MainKt"), null)
            return
        }
        assertTrue(binary.isFile && binary.canExecute(), binary.path)
        val directory = createTempDirectory("native-design-consumer-").toFile().canonicalFile
        try {
            val executable = File(directory, binary.name)
            Files.copy(binary.toPath(), executable.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            assertEquals(listOf(binary.name), directory.listFiles()?.map { it.name })
            test(listOf(executable.absolutePath), directory)
        } finally {
            Files.walk(directory.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun withServer(command: List<String>, directory: File?, development: Boolean, test: (String, HttpClient) -> Unit) {
        assertTrue(classpath.split(File.pathSeparator).all { File(it).isFile && it.endsWith(".jar") }, classpath)
        var base: String? = null
        val log = File.createTempFile("design-fixture-", ".log")
        val launch = if (System.getProperty("design.fixture.binary") == null) command.toMutableList().apply {
            add(1, "-Dbml.dev=$development")
        } else command + "-Dbml.dev=$development"
        val process = ProcessBuilder(launch + "0").directory(directory)
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            var boundPort: Int? = null
            for (attempt in 0 until 200) {
                assertTrue(process.isAlive, log.readText())
                boundPort = designBoundPort(log.readText())
                if (boundPort != null) break
                Thread.sleep(25)
            }
            val port = requireNotNull(boundPort) { "Consumer did not report a successful bind: ${log.readText()}" }
            val endpoint = "http://localhost:$port"
            base = endpoint
            HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build().use { client ->
                test(endpoint, client)
            }
        } catch (failure: Throwable) {
            val state = if (process.isAlive) "alive" else "exit=${process.exitValue()}"
            failure.addSuppressed(IllegalStateException("Design fixture development=$development at ${base ?: "unbound port"} " +
                "(pid=${process.pid()} $state): ${log.readText()}"))
            throw failure
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            log.delete()
        }
    }

    private fun request(client: HttpClient, base: String, path: String, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(base + path)).timeout(Duration.ofSeconds(10))
        if (body == null) builder.GET() else builder.POST(HttpRequest.BodyPublishers.ofString(body))
        return requestDesignFixture(client, builder.build())
    }
}
