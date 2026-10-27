package bosca.bml.gradle

import bosca.bml.i18n.MessageCatalog
import bosca.bml.i18n.MessageSource
import bosca.bml.i18n.PluralCategory
import bosca.bml.library.BmlLibraryArtifacts
import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlComponentInfo
import bosca.bml.render.BmlComponentRenderer
import bosca.bml.render.BmlLocalePolicy
import bosca.bml.render.BmlLocales
import bosca.bml.render.BmlPageRenderer
import bosca.bml.server.BmlServer
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Locale
import java.util.jar.JarFile
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Published binary declarations feed the host catalog without fetching a library-owned catalog. */
class BmlLibraryLocalizationConsumerTest {
    @Test
    fun `reachable binary messages merge with host catalogs and retain locale and cache behavior`() {
        val work = createTempDirectory("bml-library-localization").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val bundler = File("../bml-runtime/tools/bundle.mjs").canonicalFile
            fun write(root: File, path: String, source: String) = File(root, path).apply {
                parentFile.mkdirs(); writeText(source.trimIndent())
            }
            fun project(name: String, exports: String? = null, dependencies: String = ""): File {
                val directory = File(work, name).apply { mkdirs() }
                write(directory, "settings.gradle", "rootProject.name = '$name'")
                write(directory, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
                write(directory, "build.gradle", """
                    plugins { id 'org.jetbrains.kotlin.jvm'; id 'java-library'; id 'io.bosca.bml'; id 'maven-publish' }
                    group = 'example'; version = '1.0'
                    ${fixture.shared}
                    dependencies { $dependencies }
                    bml { packageName.set('example.$name.generated'); clientBundler.set(file(${quote(bundler)}))
                        ${if (exports == null) "" else "library { exports.set([$exports]) }"} }
                    publishing { publications { maven(MavenPublication) { from components.java } }
                        repositories { maven { name = 'fixture'; url = uri(${quote(fixture.repository)}) } } }
                """)
                return directory
            }
            val publish = "publishMavenPublicationToFixtureRepository"
            fun binary(name: String) = File(fixture.repository, "example/$name/1.0/$name-1.0.jar")
            val base = project("design", "'label', 'unused'")
            write(base, "src/main/bml/labels.bml", """
                <component tag="label"><prop name="count" type="Int" default="2"/>
                  <prop name="name" type="String" default="Ada"/>
                  <p t="design.greeting">Hello { name }</p><span t="design.shared">Shared</span>
                  <span t="design.items" t:count="count"><t:one>One item</t:one><t:other>{ count } items</t:other></span>
                  <input t:placeholder="design.search" placeholder="Search"/>
                  <p>{ t("design.function", "Default function") }</p><helper/>
                </component><component tag="unused"><span t="design.shared">Shared</span><p t="design.unused">Unused</p>{ t("design.unused.function", "Unused function") }</component>
            """)
            write(base, "src/main/bml/helper.bml", """<component tag="helper"><p t="design.private">Private default</p></component>""")
            runner(base, publish).build()
            val baseBuild = File(base, "build.gradle")
            val validBuild = baseBuild.readText()
            val published = binary("design").readBytes()
            baseBuild.appendText("\ntasks.named('jar') { exclude('bml/i18n-manifest.json') }\n")
            val missingCatalog = runner(base, publish).buildAndFail()
            assertTrue("Binary archive is missing library contract bml/i18n-manifest.json" in missingCatalog.output, missingCatalog.output)
            assertTrue(published.contentEquals(binary("design").readBytes()))
            baseBuild.writeText(validBuild)
            runner(base, publish).build()
            JarFile(binary("design")).use { jar ->
                val metadata = BmlLibraryArtifacts.decode(jar.getInputStream(jar.getJarEntry(BmlLibraryArtifacts.MANIFEST_PATH)).reader().readText())
                assertEquals(setOf("design.shared", "design.unused", "design.unused.function"),
                    metadata.components.single { it.tag == "unused" }.messages.map { it.key }.toSet())
                assertTrue("design.unused" !in metadata.components.single { it.tag == "label" }.messages.map { it.key })
            }
            val wrapper = project("wrapper", "'card', 'tile'", "api 'example:design:1.0'")
            write(wrapper, "src/main/bml/imports.bml", """<import library="example:design"/>""")
            val card = write(wrapper, "src/main/bml/card.bml", """
                <component tag="card"><prop name="count" type="Int" default="2"/>
                  <h2 t="wrapper.title">Card</h2><label :count="count"/>
                </component>
            """)
            write(wrapper, "src/main/bml/tile.bml", """<component tag="tile"><label/></component>""")
            runner(wrapper, publish).build()
            val conflict = project("conflict", "'badge'")
            write(conflict, "src/main/bml/badge.bml", """<component tag="badge"><p t="design.shared">Different library</p></component>""")
            runner(conflict, publish).build()
            val consumer = project("consumer", dependencies = "implementation 'example:wrapper:1.0'; implementation 'example:conflict:1.0'")
            write(consumer, "src/main/bml/imports.bml", """<import library="example:wrapper"/><import library="example:conflict"/>""")
            val page = write(consumer, "src/main/bml/home.bml", """
                <import library="example:wrapper" as="ui"/>
                <page route="/" cache="shared" maxAge="60"><html><head></head><body><p t="app.title">Host</p>
                  <card/><ui:card count="1"/><tile/>
                </body></html></page>
            """)
            val validPage = page.readText()
            val catalog = File(consumer, "build/generated/bml/resources/bml/i18n-manifest.json")
            @Suppress("UNCHECKED_CAST")
            fun strings(): List<Map<String, Any?>> = (JsonSlurper().parse(catalog) as Map<*, *>)["strings"] as List<Map<String, Any?>>
            runner(consumer, "jar").build()
            val repeated = runner(consumer, "jar").build()
            assertTrue("Reusing configuration cache" in repeated.output, repeated.output)
            assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":compileKotlin")?.outcome)
            assertEquals("Card", strings().single { it["key"] == "wrapper.title" }["message"])
            card.writeText(card.readText().replace(">Card<", ">Panel<"))
            runner(wrapper, publish).build()
            assertTrue(base.deleteRecursively()); assertTrue(wrapper.deleteRecursively()); assertTrue(conflict.deleteRecursively())
            val rebuilt = runner(consumer, "jar", "--refresh-dependencies").build()
            assertEquals(TaskOutcome.SUCCESS, rebuilt.task(":compileKotlin")?.outcome)
            val entries = strings().associateBy { it["key"] as String }
            assertEquals(setOf("app.title", "wrapper.title", "design.greeting", "design.shared", "design.items",
                "design.search", "design.function", "design.private"), entries.keys)
            assertEquals(entries.size, strings().size, "aliases and diamond dependencies must deduplicate")
            assertEquals("Panel", entries.getValue("wrapper.title")["message"])
            assertEquals("example:design:1.0!/bml/labels.bml", entries.getValue("design.greeting")["file"])
            assertEquals("FUNCTION", entries.getValue("design.function")["origin"])
            assertEquals(mapOf("ONE" to "One item", "OTHER" to "{count} items"), entries.getValue("design.items")["forms"])
            val application = File(work, "application.jar")
            File(consumer, "build/libs/consumer-1.0.jar").copyTo(application)
            for (addition in listOf("<p t=\"design.shared\">Different host</p>", "<badge/>")) {
                page.writeText(validPage.replace("</body>", "$addition</body>"))
                val failed = runner(consumer, "jar").buildAndFail()
                assertTrue("key 'design.shared' has different source text" in failed.output, failed.output)
                assertTrue("example:design:1.0!/bml/labels.bml" in failed.output, failed.output)
                assertTrue((if ("badge" in addition) "example:conflict:1.0!/bml/badge.bml" else "home.bml") in failed.output, failed.output)
                assertFalse(catalog.exists(), "a rejected rebuild must remove the stale catalog")
            }
            page.writeText("""<page route="/"><p t="app.title">Host</p></page>""")
            runner(consumer, "jar").build()
            assertEquals(listOf("app.title"), strings().map { it["key"] })
            page.writeText("""<page route="/">Plain</page>""")
            runner(consumer, "jar").build()
            assertFalse(catalog.exists(), "removing the last declaration must remove the generated catalog")
            assertTrue(consumer.deleteRecursively())
            URLClassLoader((listOf(application, binary("design"), binary("wrapper"))).map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader).use { loader ->
                fun registry(name: String, property: String): Any {
                    val type = loader.loadClass("example.consumer.generated.$name")
                    return type.getMethod("get" + property.replaceFirstChar(Char::uppercaseChar)).invoke(type.getField("INSTANCE").get(null))
                }
                @Suppress("UNCHECKED_CAST") val pages = registry("BmlPages", "all") as List<BmlPageRenderer>
                @Suppress("UNCHECKED_CAST") val components = registry("BmlComponents", "all") as List<BmlComponentInfo>
                @Suppress("UNCHECKED_CAST") val renderers = registry("BmlComponents", "renderers") as Map<String, BmlComponentRenderer>
                var greeting = "Hola {name}"
                val messages = object : MessageSource {
                    override suspend fun catalog(locale: Locale) = when (locale.toLanguageTag()) {
                        "es" -> MessageCatalog(mapOf("design.greeting" to greeting, "design.search" to "Buscar", "design.private" to "Privado"),
                            mapOf("design.items" to mapOf(PluralCategory.ONE to "Un artículo", PluralCategory.OTHER to "{count} artículos")))
                        "en" -> MessageCatalog(mapOf("design.function" to "Host source function"))
                        else -> MessageCatalog.EMPTY
                    }
                }
                for (dev in listOf(false, true)) {
                    val port = ServerSocket(0).use { it.localPort }
                    val host = BmlServer(CompiledProject(name = "localized", version = "1"), pages, port, dev = dev,
                        components = components, componentRenderers = renderers,
                        localePolicy = BmlLocalePolicy.static(BmlLocales(listOf("en", "es-MX"))), messageSource = messages)
                    val config = "bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n"
                    val app = BoscaApplication(ApplicationConfig.load(config.byteInputStream()))
                    host.install(app); app.freezeMiddleware()
                    val engine = NettyServerEngine(app, port)
                    thread(isDaemon = true, name = "bml-library-localization") { engine.start() }
                    try {
                        awaitServer(port)
                        val client = HttpClient.newHttpClient()
                        fun get(path: String, etag: String? = null): HttpResponse<String> {
                            val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).header("Accept-Language", "es-MX")
                            etag?.let { request.header("If-None-Match", it) }
                            return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
                        }
                        greeting = "Hola {name}"
                        val negotiated = get("/")
                        assertEquals("private, no-store", negotiated.headers().firstValue("Cache-Control").orElseThrow())
                        val first = get("/?lang=es-MX")
                        assertEquals(200, first.statusCode(), first.body())
                        for (text in listOf("Hola Ada", "2 artículos", "Un artículo", "Buscar", "Privado", "Host source function", "Panel")) {
                            assertTrue(text in first.body(), "Missing $text: ${first.body()}")
                        }
                        val fallback = get("/?lang=en").body()
                        for (text in listOf("Hello Ada", "2 items", "One item", "Search", "Private default")) assertTrue(text in fallback, fallback)
                        val etag = first.headers().firstValue("ETag").orElse(null)
                        if (dev) {
                            assertEquals(null, etag)
                            assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow())
                        } else {
                            assertTrue(etag != null, first.headers().toString())
                            assertEquals(304, get("/?lang=es-MX", etag).statusCode())
                        }
                        val catalogResponse = get("/_bml/i18n/es-MX.json")
                        assertTrue("Host source function" in catalogResponse.body(), catalogResponse.body())
                        val catalogEtag = catalogResponse.headers().firstValue("ETag").orElseThrow()
                        assertEquals(304, get("/_bml/i18n/es-MX.json", catalogEtag).statusCode())
                        greeting = "Saludos {name}"
                        val changed = get("/?lang=es-MX", etag)
                        assertEquals(200, changed.statusCode(), changed.body())
                        assertTrue("Saludos Ada" in changed.body(), changed.body())
                        if (!dev) assertFalse(etag == changed.headers().firstValue("ETag").orElseThrow())
                        assertEquals(200, get("/_bml/i18n/es-MX.json", catalogEtag).statusCode())
                    } finally { engine.stopWithoutHalting() }
                }
            }
        } finally { work.deleteRecursively() }
    }

    private fun awaitServer(port: Int) {
        repeat(100) {
            try { java.net.Socket("localhost", port).use { return } }
            catch (_: java.net.ConnectException) { Thread.sleep(25) }
        }
        error("BML localization fixture server did not start")
    }

    private fun runner(project: File, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project).withPluginClasspath()
        .withArguments(*tasks, "--stacktrace", "--configuration-cache", "--max-workers=2")
}
