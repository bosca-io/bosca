package bosca.bml.compiler.plugin

import bosca.bml.library.BmlLibraryArtifacts
import bosca.bml.library.BmlLibraryDependencies
import bosca.bml.library.BmlImportResolver
import bosca.bml.parser.BmlParser
import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlComponentInfo
import bosca.bml.render.BmlComponentRenderer
import bosca.bml.render.BmlIslandActionDispatcher
import bosca.bml.render.BmlPageRenderer
import bosca.bml.server.BmlServer
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.ServerSocket
import java.net.URI
import java.net.URLClassLoader
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services

/** Real K2 binary producer -> source-free consumer -> generated registries -> Netty route wiring. */
class BmlBinaryRuntimeTest {
    @Test
    fun `binary localization retains component and shared script ownership with source locations`() {
        val work = Files.createTempDirectory("bml-library-messages").toFile()
        try {
            val root = File(work, "bml").apply { mkdirs() }
            File(root, "cards.bml").writeText("""
                <script client>import { t } from "@bosca/bml"; export const title = () => t("module.title")</script>
                <component tag="card"><p t="card.title">Card</p>{ t("card.function", "Function") }</component><component tag="other"><p t="other.title">Other</p></component>
            """.trimIndent())
            val resources = File(work, "resources")
            compile(File(work, "classes"), listOf("bmlSourceRoot=$root", "bmlPackageName=example.cards",
                "bmlLibraryId=example:cards", "bmlLibraryVersion=1.0", "bmlLibraryExport=card", "bmlResourcesOutputDir=$resources"))
            val manifest = BmlLibraryArtifacts.decode(File(resources, BmlLibraryArtifacts.MANIFEST_PATH).readText())
            val card = manifest.components.single { it.tag == "card" }.messages.associateBy { it.key }
            val other = manifest.components.single { it.tag == "other" }.messages.associateBy { it.key }
            assertEquals(setOf("module.title", "card.title", "card.function"), card.keys)
            assertEquals(setOf("module.title", "other.title"), other.keys)
            assertEquals(1, card.getValue("module.title").line)
            assertEquals(2, card.getValue("card.function").line)
            assertEquals("Function", card.getValue("card.function").message)
        } finally { work.deleteRecursively() }
    }

    @Test
    fun `localization conflicts fail compilation even without resource output`() {
        val work = Files.createTempDirectory("bml-localization-validation").toFile()
        try {
            val root = File(work, "bml").apply { mkdirs() }
            File(root, "one.bml").writeText("""<page route="/one"><p>{ t("shared", "One") }</p></page>""")
            File(root, "two.bml").writeText("""<page route="/two"><p>{ t("shared", "Two") }</p></page>""")
            val output = compile(File(work, "classes"), listOf("bmlSourceRoot=$root", "bmlPackageName=example.pages"),
                expected = ExitCode.COMPILATION_ERROR)
            assertTrue("key 'shared' has different source text" in output, output)
            assertTrue("one.bml:1" in output && "two.bml:1" in output, output)
        } finally { work.deleteRecursively() }
    }

    @Test
    fun `standalone generation compiles and registers binary library providers without source scanning`() {
        val work = Files.createTempDirectory("bml-standalone-providers").toFile()
        try {
            val root = File(work, "producer/bml").apply { mkdirs() }
            File(root, "label.bml").writeText("""
                <component tag="label"><inject name="label" type="String" provider="bml-standalone-label"/><span>{ label }</span></component>
            """.trimIndent())
            val registrar = File(work, "producer/Registrar.kt").apply { writeText("""
                package example
                class DesignRegistrar : bosca.di.ProviderRegistrar {
                    override fun register() { bosca.di.provides<String>("bml-standalone-label") { "DIRECT DI" } }
                }
            """.trimIndent()) }
            val classes = File(work, "producer/classes")
            val resources = File(work, "producer/resources")
            compile(classes, listOf("bmlSourceRoot=$root", "bmlPackageName=example.generated", "bmlLibraryId=example:design",
                "bmlLibraryVersion=1.0", "bmlLibraryExport=label", "bmlResourcesOutputDir=$resources",
                "bmlLibraryProviderRegistrar=example.DesignRegistrar"), sources = listOf(registrar))
            val binary = archive(File(work, "design.jar"), listOf(classes, resources))
            assertTrue(File(work, "producer").deleteRecursively())
            val source = File(work, "consumer/bml").apply { mkdirs() }
            File(source, "imports.bml").writeText("""<import library="example:design"/>""")
            val pageSource = File(source, "home.bml").apply { writeText("""<page route="/"><label/></page>""") }
            val generated = File(work, "consumer/generated")
            val generator = bosca.bml.codegen.BmlProjectGenerator("consumer.direct")
            val output = generator.generateAll(source, generated, listOf(binary))
            assertTrue(output.errors.isEmpty(), output.errors.toString())
            val consumerClasses = File(work, "consumer/classes")
            compile(consumerClasses, emptyList(), listOf(binary), sources = output.written)
            URLClassLoader(arrayOf(consumerClasses.toURI().toURL(), binary.toURI().toURL()), javaClass.classLoader).use { loader ->
                val providers = loader.loadClass("consumer.direct.BmlProviders")
                (providers.getField("INSTANCE").get(null) as bosca.di.ProviderRegistrar).register()
                val pageType = loader.loadClass("consumer.direct.HomePage")
                val page = pageType.getField("INSTANCE").get(null) as BmlPageRenderer
                kotlinx.coroutines.runBlocking {
                    val context = bosca.bml.render.RenderContext()
                    page.render(context)
                    assertTrue("DIRECT DI" in context.toString(), context.toString())
                }
            }
            pageSource.writeText("""<page route="/">Plain</page>""")
            generator.generateAll(source, generated, listOf(binary))
            assertFalse("DesignRegistrar()" in File(generated, "BmlProviders.kt").readText())
            File(source, "imports.bml").delete()
            generator.generateAll(source, generated)
            assertFalse(File(generated, "BmlProviders.kt").exists())
        } finally { work.deleteRecursively() }
    }

    @Test
    fun `library registrar declarations must compile as public zero argument ProviderRegistrars`() {
        val work = Files.createTempDirectory("bml-registrar-types").toFile()
        try {
            val root = File(work, "bml").apply { mkdirs() }
            File(root, "card.bml").writeText("""<component tag="card"><span>Card</span></component>""")
            for ((name, declaration) in listOf(
                "WrongType" to "class WrongType",
                "RequiresArgument" to "class RequiresArgument(val value: String) : bosca.di.ProviderRegistrar { override fun register() {} }",
                "PrivateRegistrar" to "private class PrivateRegistrar : bosca.di.ProviderRegistrar { override fun register() {} }",
            )) {
                val model = File(work, "$name.kt").apply { writeText("package example\n$declaration") }
                compile(File(work, "$name/classes"), listOf(
                    "bmlSourceRoot=$root", "bmlPackageName=example.generated", "bmlLibraryId=example:design",
                    "bmlLibraryVersion=1.0", "bmlLibraryExport=card", "bmlResourcesOutputDir=${File(work, "$name/resources")}",
                    "bmlLibraryProviderRegistrar=example.$name",
                ), sources = listOf(model), expected = ExitCode.COMPILATION_ERROR)
            }
        } finally { work.deleteRecursively() }
    }

    @Test
    fun `a binary wrapper preserves its own imports through transitive actions and deferred rendering`() {
        val work = Files.createTempDirectory("bml-transitive-runtime").toFile()
        try {
            val base = producer(work, "design", 0)
            val wrapperRoot = File(work, "wrapper/bml").apply { mkdirs() }
            File(wrapperRoot, "imports.bml").writeText("""<import library="example:design"/>""")
            File(wrapperRoot, "card.bml").writeText("""
                <component tag="card"><prop name="id" type="String"/>
                  <counter :id="id"/><private-label/>
                </component>
            """.trimIndent())
            File(wrapperRoot, "private-label.bml").writeText("""<component tag="private-label"><span>WRAPPER HELPER</span></component>""")
            File(wrapperRoot, "lazy-card.bml").writeText("""
                <component tag="lazy-card"><prop name="id" type="String"/>
                  <island name="body" render="deferred" :id="id">
                    <prop name="id" type="String"/><counter :id="id"/>
                    <fallback>WRAPPER WAITING</fallback>
                  </island>
                </component>
            """.trimIndent())
            val wrapperClasses = File(work, "wrapper/classes")
            val wrapperResources = File(work, "wrapper/resources")
            compile(wrapperClasses, listOf(
                "bmlSourceRoot=$wrapperRoot", "bmlPackageName=example.wrapper.generated", "bmlLibraryId=example:wrapper",
                "bmlLibraryVersion=1.0", "bmlResourcesOutputDir=$wrapperResources", "bmlLibraryDependency=$base",
                "bmlLibraryExport=card", "bmlLibraryExport=lazy-card",
            ), classpath = listOf(base))
            val manifest = BmlLibraryArtifacts.decode(File(wrapperResources, BmlLibraryArtifacts.MANIFEST_PATH).readText())
            val requirement = manifest.requirements.single()
            assertEquals("example:design", requirement.library)
            assertEquals("1.0", requirement.version)
            assertEquals(listOf("counter"), requirement.components, "only used entry points become requirements")
            val counterId = BmlLibraryArtifacts.componentId("example:design", "counter")
            assertEquals(listOf(counterId), manifest.components.first { it.tag == "lazy-card" }.dependencies)
            assertTrue(manifest.components.first { it.tag == "lazy-card" }.eagerDependencies.isEmpty())
            val wrapper = archive(File(work, "wrapper.jar"), listOf(wrapperClasses, wrapperResources))
            assertTrue(File(work, "wrapper").deleteRecursively())
            val dependencies = BmlLibraryDependencies.read(listOf(wrapper, base))
            val imports = BmlImportResolver.parse("imports.bml", BmlParser.parse("""<import library="example:wrapper"/>""").document, true)
            assertEquals(setOf("card", "lazy-card"), BmlImportResolver(dependencies).resolve(null, imports).bindings.keys)
            val root = File(work, "consumer/bml").apply { mkdirs() }
            File(root, "imports.bml").writeText("""<import library="example:wrapper"/>""")
            File(root, "home.bml").writeText("""
                <import library="example:wrapper" as="ui"/><import library="example:design" as="base"/>
                <page route="/"><html><head></head><body>
                  <card id="one"/><ui:card id="two"/><base:counter id="direct"/><counter/>
                </body></html></page>
            """.trimIndent())
            File(root, "lazy.bml").writeText("""<page route="/lazy"><html><body><lazy-card id="later"/></body></html></page>""")
            File(root, "counter.bml").writeText("""<component tag="counter"><p>APP COUNTER</p></component>""")
            val options = listOf("bmlSourceRoot=$root", "bmlPackageName=consumer.generated", "bmlLibraryDependency=$wrapper", "bmlLibraryDependency=$base")
            val output = File(work, "consumer/classes")
            compile(output, options, listOf(wrapper, base))
            URLClassLoader(arrayOf(output, wrapper, base).map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader).use { loader ->
                fun registry(name: String, property: String): Any {
                    val type = loader.loadClass("consumer.generated.$name")
                    return type.getMethod("get" + property.replaceFirstChar(Char::uppercaseChar)).invoke(type.getField("INSTANCE").get(null))
                }
                @Suppress("UNCHECKED_CAST")
                val pages = registry("BmlPages", "all") as List<BmlPageRenderer>
                @Suppress("UNCHECKED_CAST")
                val components = registry("BmlComponents", "all") as List<BmlComponentInfo>
                @Suppress("UNCHECKED_CAST")
                val renderers = registry("BmlComponents", "renderers") as Map<String, BmlComponentRenderer>
                @Suppress("UNCHECKED_CAST")
                val dispatchers = registry("BmlIslands", "componentDispatchers") as Map<String, BmlIslandActionDispatcher>
                assertEquals(setOf("$counterId.counter"), dispatchers.keys)
                assertEquals(1, components.count { it.tag == counterId })
                assertFalse(components.any { it.tag.endsWith("unused") || it.tag.endsWith("client-counter") })
                val port = ServerSocket(0).use { it.localPort }
                val server = BmlServer(CompiledProject(name = "transitive", version = "1"), pages, port,
                    components = components, componentRenderers = renderers, componentIslandDispatchers = dispatchers)
                val app = BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: true\n".byteInputStream()))
                server.install(app)
                app.freezeMiddleware()
                val engine = NettyServerEngine(app, port)
                thread(isDaemon = true, name = "bml-transitive-runtime-test") { engine.start() }
                try {
                    awaitServer(port)
                    val client = HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()
                    fun get(path: String) = client.send(HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())
                    fun post(path: String, body: String) = client.send(HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
                    val page = get("/")
                    assertEquals(200, page.statusCode(), page.body())
                    assertTrue(listOf("APP COUNTER", "DESIGN HELPER", "WRAPPER HELPER").all { it in page.body() }, page.body())
                    for (key in listOf("one", "two", "direct")) {
                        val action = post("/_bml/action", """{"page":"/","path":"/","stateKey":"$counterId.counter:$key","method":"increment"}""")
                        assertEquals(200, action.statusCode(), action.body())
                        assertTrue("Count 1" in Json.parseToJsonElement(action.body()).jsonObject.getValue("html").jsonPrimitive.content, action.body())
                    }
                    val lazy = get("/lazy")
                    assertEquals(200, lazy.statusCode(), lazy.body())
                    assertTrue("WRAPPER WAITING" in lazy.body(), lazy.body())
                    val lazyId = BmlLibraryArtifacts.componentId("example:wrapper", "lazy-card")
                    val deferredId = URLEncoder.encode("component:$lazyId:body", Charsets.UTF_8)
                    val deferred = post("/_bml/deferred/$deferredId", """{"page":"/lazy","path":"/lazy","query":{},"props":{"id":"later"}}""")
                    assertEquals(200, deferred.statusCode(), deferred.body())
                    assertTrue("Count 0" in deferred.body() && "DESIGN HELPER" in deferred.body(), deferred.body())
                    val action = post("/_bml/action", """{"page":"/lazy","path":"/lazy","stateKey":"$counterId.counter:later","method":"increment"}""")
                    assertEquals(200, action.statusCode(), action.body())
                    assertTrue("Count 1" in Json.parseToJsonElement(action.body()).jsonObject.getValue("html").jsonPrimitive.content, action.body())
                } finally { engine.stopWithoutHalting() }
            }
            val failure = compile(File(work, "missing/classes"), options.filterNot { it == "bmlLibraryDependency=$base" },
                listOf(wrapper), expected = ExitCode.COMPILATION_ERROR)
            assertTrue("Missing BML dependency example:design:1.0" in failure, failure)
            val invalid = File(root, "shared.bml").apply { writeText("""<page route="/shared" cache="shared"><card id="shared"/></page>""") }
            val sharedFailure = compile(File(work, "shared/classes"), options, listOf(wrapper, base), expected = ExitCode.COMPILATION_ERROR)
            assertTrue("server-session state in its render closure" in sharedFailure, sharedFailure)
            invalid.delete()
        } finally { work.deleteRecursively() }
    }
    @Test
    fun `binary components compose private closures and isolated actions across aliases and libraries`() {
        val work = Files.createTempDirectory("bml-binary-runtime").toFile()
        try {
            val design = producer(work, "design", 0)
            val alternate = producer(work, "alternate", 100)
            val root = File(work, "consumer/bml").apply { mkdirs() }
            File(root, "imports.bml").writeText("""<import library="example:design"/>""")
            File(root, "home.bml").writeText("""
                <import library="example:design" as="ui"/>
                <import library="example:alternate" as="other"/>
                <page route="/">
                  <html><head></head><body>
                    <counter id="a"/><ui:counter id="b"/><other:counter id="c"/>
                    <client-counter id="client"/><site-counter/>
                    <badge/><private-label/>
                  </body></html>
                </page>
            """.trimIndent())
            File(root, "next.bml").writeText("""<page route="/next"><html><body><site-counter/></body></html></page>""")
            File(root, "unrelated.bml").writeText("""<page route="/unrelated"><html><body>Unrelated</body></html></page>""")
            File(root, "private-label.bml").writeText("""<component tag="private-label"><span>APP HELPER</span></component>""")
            val output = File(work, "consumer-classes")
            val generated = File(work, "consumer-generated")
            compile(output, listOf(
                "bmlSourceRoot=$root", "bmlPackageName=consumer.generated", "bmlKotlinOutputDir=$generated",
                "bmlLibraryDependency=$design", "bmlLibraryDependency=$alternate",
            ), listOf(design, alternate))
            val registrySource = File(generated, "BmlComponents.kt").readText()
            assertFalse("UnusedComponent" in registrySource, registrySource)
            assertTrue("example.design.generated.PrivateDetailComponent" in registrySource, registrySource)
            URLClassLoader(arrayOf(output, design, alternate).map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader).use { loader ->
                fun registry(name: String, field: String): Any {
                    val type = loader.loadClass("consumer.generated.$name")
                    return type.getMethod("get" + field.replaceFirstChar(Char::uppercaseChar))
                        .invoke(type.getField("INSTANCE").get(null))
                }
                @Suppress("UNCHECKED_CAST")
                val pages = registry("BmlPages", "all") as List<BmlPageRenderer>
                @Suppress("UNCHECKED_CAST")
                val components = registry("BmlComponents", "all") as List<BmlComponentInfo>
                @Suppress("UNCHECKED_CAST")
                val renderers = registry("BmlComponents", "renderers") as Map<String, BmlComponentRenderer>
                @Suppress("UNCHECKED_CAST")
                val dispatchers = registry("BmlIslands", "componentDispatchers") as Map<String, BmlIslandActionDispatcher>
                val id = BmlLibraryArtifacts.componentId("example:design", "counter")
                val other = BmlLibraryArtifacts.componentId("example:alternate", "counter")
                val clientId = BmlLibraryArtifacts.componentId("example:design", "client-counter")
                val siteId = BmlLibraryArtifacts.componentId("example:design", "site-counter")
                assertEquals(1, components.count { it.tag == id })
                assertEquals(components.size, components.map { it.tag }.distinct().size)
                assertEquals(components.map { it.tag }.toSet(), renderers.keys)
                assertEquals(setOf("$id.counter", "$other.counter", "$clientId.counter", "$siteId.counter"), dispatchers.keys)
                assertTrue(components.first { it.tag == id }.hasServerState)
                val page = pages.first { it.route == "/" }
                assertEquals(1, page.componentTags.count { it == id })
                assertFalse(page.componentTags.any { it.startsWith("ui:") || it.startsWith("other:") })
                val counter = components.first { it.tag == id }
                val detailId = BmlLibraryArtifacts.componentId("example:design", "private-detail")
                assertTrue(detailId in counter.deps)
                assertFalse(detailId in counter.eagerDeps)
                assertEquals(id, counter.deferredRenderers.single().ownerComponentTag)
                assertEquals(listOf(detailId), counter.deferredRenderers.single().componentTags)

                val port = ServerSocket(0).use { it.localPort }
                val server = BmlServer(
                    project = CompiledProject(name = "binary-runtime", version = "1"), pages = pages,
                    components = components, componentRenderers = renderers,
                    componentIslandDispatchers = dispatchers, port = port,
                )
                val app = BoscaApplication(ApplicationConfig.load(
                    "bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: true\n".byteInputStream(),
                ))
                server.install(app)
                app.freezeMiddleware()
                val engine = NettyServerEngine(app, port)
                thread(isDaemon = true, name = "bml-binary-runtime-test") { engine.start() }
                try {
                    awaitServer(port)
                    val client = HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()
                    fun get(path: String): HttpResponse<String> = client.send(
                        HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString(),
                    )
                    fun post(path: String, body: String, token: String? = null): HttpResponse<String> {
                        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                        if (token != null) request.header("Authorization", token)
                        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
                    }
                    fun action(key: String, path: String = "/", state: String = ""): JsonObject {
                        val result = post("/_bml/action", JsonObject(mapOf(
                            "page" to JsonPrimitive(path), "path" to JsonPrimitive(path),
                            "stateKey" to JsonPrimitive(key), "method" to JsonPrimitive("increment"), "state" to JsonPrimitive(state),
                        )).toString())
                        assertEquals(200, result.statusCode(), result.body())
                        return Json.parseToJsonElement(result.body()).jsonObject
                    }
                    val first = get("/")
                    assertEquals(200, first.statusCode(), first.body())
                    assertTrue(first.headers().allValues("Set-Cookie").isNotEmpty(), "imported state must establish a session")
                    assertTrue("APP HELPER" in first.body(), first.body())
                    assertTrue("DESIGN HELPER" in first.body(), first.body())
                    assertTrue("ALTERNATE HELPER" in first.body(), first.body())
                    assertFalse("PRIVATE DETAIL" in first.body(), first.body())
                    assertTrue("data-bml-clear-on-sign-out" in first.body(), first.body())
                    assertTrue("$id.counter:a" in first.body(), first.body())
                    assertTrue("$id.counter:b" in first.body(), first.body())
                    val changed = action("$id.counter:a")
                    assertEquals(JsonNull, changed["state"], "server state must remain server-side")
                    assertTrue("Count 1" in changed.getValue("html").jsonPrimitive.content)
                    assertTrue("Count 1" in action("$id.counter:b").getValue("html").jsonPrimitive.content)
                    assertTrue("Count 101" in action("$other.counter:c").getValue("html").jsonPrimitive.content)
                    assertTrue("Count 2" in action("$id.counter:a").getValue("html").jsonPrimitive.content)
                    val clientChanged = action("$clientId.counter:client", state = "{\"count\":4}")
                    assertEquals("{\"count\":5}", clientChanged.getValue("state").jsonPrimitive.content)
                    assertTrue("Count 5" in clientChanged.getValue("html").jsonPrimitive.content)
                    assertTrue("Count 1" in action("$siteId.counter").getValue("html").jsonPrimitive.content)
                    assertEquals(200, get("/next").statusCode())
                    assertTrue("Count 2" in action("$siteId.counter", path = "/next").getValue("html").jsonPrimitive.content)
                    val foreign = post("/_bml/action", """{"page":"/unrelated","stateKey":"$id.counter:a","method":"increment"}""")
                    assertEquals(404, foreign.statusCode(), foreign.body())
                    val deferredId = URLEncoder.encode("component:$id:details", Charsets.UTF_8)
                    val deferred = post("/_bml/deferred/$deferredId", """{"page":"/","path":"/","query":{},"props":{}}""", token = "current-token")
                    assertEquals(200, deferred.statusCode(), deferred.body())
                    assertTrue("PRIVATE DETAIL current-token" in deferred.body(), deferred.body())
                    val rejected = post("/_bml/deferred/$deferredId", """{"page":"/unrelated","path":"/unrelated","query":{},"props":{}}""")
                    assertEquals(422, rejected.statusCode(), rejected.body())
                    val badgeId = BmlLibraryArtifacts.componentId("example:design", "badge")
                    val fragment = post("/_bml/render/$badgeId", """{"label":"Changed"}""")
                    assertEquals(200, fragment.statusCode(), fragment.body())
                    assertTrue("Changed</span>" in fragment.body(), fragment.body())
                    val css = get("/_bml/css/$id.css")
                    assertEquals(200, css.statusCode(), css.body())
                    assertTrue("data-bml-c=\"$id\"" in css.body(), css.body())
                } finally {
                    engine.stopWithoutHalting()
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `binary library assets survive checkout deletion and serve both client modes with consumer palettes`() {
        val work = Files.createTempDirectory("bml-linked-assets").toFile()
        try {
            val tools = File("../bml-runtime").canonicalFile
            val bundler = File(tools, "tools/bundle.mjs")
            val runtime = File(work, "runtime.ts").apply { writeText("""
                globalThis.runtimeStarts = (globalThis.runtimeStarts ?? 0) + 1;
                export function defineComponent(id, init) { (globalThis.registered ??= []).push(id) }
                export function defineIsland() {}
                export function mountAll() {}
                export function renderFragment() {}
                export function enableDeferred() {}
            """.trimIndent()) }
            fun node(vararg arguments: String): String {
                val process = ProcessBuilder(listOf("node") + arguments).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor(), output)
                return output
            }
            fun library(name: String, base: File? = null): File {
                val directory = File(work, name).apply { mkdirs() }
                val root = File(directory, "bml").apply { mkdirs() }
                if (base != null) File(root, "imports.bml").writeText("""<import library="example:base" as="base"/>""")
                val tag = if (base == null) "button" else "card"
                File(root, "button.bml").writeText("""
                    <component tag="$tag">
                      <script client scoped>import "./button.css"; ctx.onUnmount(() => {});</script>
                      <style scoped>.label { color: var(--bml-accent); }</style>
                      <span class="label">$name</span>${if (base != null) "<base:button/>" else "<private-label/>"}
                    </component>
                """.trimIndent())
                File(root, "private-label.bml").writeText("""<component tag="private-label"><span>PRIVATE</span></component>""")
                File(root, "unused.bml").writeText("""
                    <component tag="unused"><script client>console.log("UNUSED_CLIENT_SENTINEL");</script><span>Unused</span></component>
                """.trimIndent())
                File(root, "button.css").writeText(".image-$name { background-image: url('./image.svg'); } @font-face { font-family: '$name'; src: url('./font.woff2'); }")
                File(root, "image.svg").writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"/>""")
                File(root, "font.woff2").writeBytes(byteArrayOf(0, 1, 2, -1))
                val globals = File(directory, "client").apply { mkdirs() }
                File(globals, "$name.ts").writeText("(globalThis.libraryOrder ??= []).push('$name');")
                File(globals, "theme.css").writeText(":root { --bml-accent: red; }")
                val classes = File(directory, "classes")
                val resources = File(directory, "resources")
                val ts = File(directory, "ts")
                compile(classes, listOf("bmlSourceRoot=$root", "bmlPackageName=example.$name.generated",
                    "bmlLibraryId=example:$name", "bmlLibraryVersion=1.0", "bmlLibraryExport=$tag", "bmlLibraryExport=unused",
                    "bmlResourcesOutputDir=$resources", "bmlTsOutputDir=$ts") +
                    listOfNotNull(base?.let { "bmlLibraryDependency=$it" }), classpath = listOfNotNull(base))
                val assets = File(directory, "assets")
                node(bundler.path, "--in", ts.path, "--out", File(directory, "js").path,
                    "--library-manifest", File(resources, BmlLibraryArtifacts.MANIFEST_PATH).path,
                    "--source-root", root.path, "--project-dir", directory.path,
                    "--library-styles", File(directory, "styles").path,
                    "--assets-in", globals.path, "--library-out", assets.path,
                    "--library-sources-out", File(directory, "sources").path)
                val artifact = archive(File(work, "$name.jar"), listOf(classes, resources, assets))
                assertTrue(directory.deleteRecursively())
                return artifact
            }
            val base = library("base")
            val wrapper = library("wrapper", base)
            val directory = File(work, "app").apply { mkdirs() }
            val root = File(directory, "bml").apply { mkdirs() }
            File(root, "imports.bml").writeText("""<import library="example:wrapper"/>""")
            for ((name, color) in listOf("green" to "rgb(0, 128, 0)", "blue" to "rgb(0, 0, 255)")) {
                File(root, "$name.bml").writeText("""
                    <page route="/$name"><html><head><style>:root { --bml-accent: $color; }</style></head><body><card/></body></html></page>
                """.trimIndent())
            }
            File(root, "empty.bml").writeText("""<page route="/empty"><html><head></head><body>Empty</body></html></page>""")
            val classes = File(directory, "classes")
            val resources = File(directory, "resources")
            val ts = File(directory, "ts")
            val metrics = File(directory, "metrics")
            compile(classes, listOf("bmlSourceRoot=$root", "bmlPackageName=consumer.assets", "bmlResourcesOutputDir=$resources",
                "bmlTsOutputDir=$ts", "bmlMetricsOutputDir=$metrics", "bmlLibraryDependency=$wrapper", "bmlLibraryDependency=$base"), listOf(wrapper, base))
            val packaged = File(directory, "packaged/bml/applications/consumer.assets")
            node(bundler.path, "--in", ts.path, "--out", File(directory, "dev").path,
                "--manifest", File(metrics, "manifest.tsv").path, "--prod-out", File(directory, "prod").path,
                "--runtime", runtime.path, "--application-out", packaged.path,
                "--application-resources", File(resources, "bml/applications/consumer.assets").path)
            val appJar = archive(File(work, "app.jar"), listOf(classes, resources, File(directory, "packaged")))
            assertTrue(directory.deleteRecursively())
            File(work, "package.json").writeText("""{"type":"module"}""")
            URLClassLoader(arrayOf(appJar, wrapper, base).map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader).use { loader ->
                fun registry(name: String): Any {
                    val type = loader.loadClass("consumer.assets.$name")
                    return type.getMethod("getAll").invoke(type.getField("INSTANCE").get(null))
                }
                @Suppress("UNCHECKED_CAST") val pages = registry("BmlPages") as List<BmlPageRenderer>
                @Suppress("UNCHECKED_CAST") val components = registry("BmlComponents") as List<BmlComponentInfo>
                assertEquals(2, components.mapNotNull { it.clientModule }.distinct().size, "same-named producer modules must stay distinct")
                for (development in listOf(true, false)) {
                    val port = ServerSocket(0).use { it.localPort }
                    val server = BmlServer(CompiledProject(name = "assets", version = "1"), pages, port, components = components, dev = development)
                    val app = BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: true\n".byteInputStream()))
                    server.install(app); app.freezeMiddleware()
                    val engine = NettyServerEngine(app, port)
                    thread(isDaemon = true, name = "bml-linked-assets-test") { engine.start() }
                    try {
                        awaitServer(port)
                        val client = HttpClient.newHttpClient()
                        fun get(path: String, etag: String? = null): HttpResponse<ByteArray> {
                            val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                            etag?.let { request.header("If-None-Match", it) }
                            return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                        }
                        val page = get("/green").body().decodeToString()
                        assertTrue(page.indexOf("/_bml/libraries/") < page.indexOf("<style>"), page)
                        val styles = Regex("<link[^>]+href=\"([^\"]+)\"").findAll(page).map { it.groupValues[1].replace("&amp;", "&") }.toList()
                        assertEquals(4, styles.count { "/_bml/libraries/" in it })
                        val css = styles.joinToString("\n") { path ->
                            val response = get(path)
                            assertEquals(200, response.statusCode(), path)
                            val etag = response.headers().firstValue("ETag").orElseThrow()
                            assertEquals(304, get(path, etag).statusCode(), path)
                            response.body().decodeToString()
                        }
                        val binaryUrls = Regex("/_bml/libraries/[^\\s)\\\"']+").findAll(css).map { it.value }.distinct().toList()
                        assertTrue(binaryUrls.any { it.endsWith(".woff2") } && binaryUrls.any { it.endsWith(".svg") }, css)
                        for (path in binaryUrls) {
                            val response = get(path)
                            assertEquals(200, response.statusCode(), path)
                            if (path.endsWith(".woff2")) {
                                assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("font/woff2"))
                                assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElseThrow())
                                assertTrue(response.body().contentEquals(byteArrayOf(0, 1, 2, -1)))
                            }
                        }
                        assertEquals(404, get("/_bml/libraries/unknown/secret").statusCode())
                        val scripts = Regex("<script[^>]+src=\"([^\"]+)\"").findAll(page).map { it.groupValues[1].replace("&amp;", "&") }
                            .filter { it.startsWith("/_bml/js/") }.toList()
                        assertEquals(if (development) 3 else 1, scripts.size)
                        val clientRoot = File(work, "client-$development").apply { mkdirs() }
                        val downloaded = mutableSetOf<String>()
                        fun download(path: String) {
                            val clean = path.substringBefore('?')
                            if (!downloaded.add(clean)) return
                            val response = get(path)
                            assertEquals(200, response.statusCode(), path)
                            val source = response.body().decodeToString()
                            assertFalse("UNUSED_CLIENT_SENTINEL" in source, source)
                            File(clientRoot, clean.substringAfterLast('/')).writeText(source)
                            val etag = response.headers().firstValue("ETag").orElseThrow()
                            assertEquals(304, get(path, etag).statusCode())
                            Regex("(?:from\\s*|import\\s*)[\"'](\\./[^\"']+)[\"']").findAll(source).forEach {
                                download("/_bml/js/" + it.groupValues[1].removePrefix("./"))
                            }
                        }
                        scripts.forEach(::download)
                        val check = File(clientRoot, "check.mjs").apply { writeText("""
                            ${scripts.joinToString("\n") { "await import('./" + it.substringBefore('?').substringAfterLast('/') + "');" }}
                            if (globalThis.runtimeStarts !== 1) throw new Error('runtime initialized ' + globalThis.runtimeStarts + ' times');
                            if (globalThis.registered.length !== 2) throw new Error('wrong components ' + globalThis.registered);
                            if (JSON.stringify(globalThis.libraryOrder) !== '["base","wrapper"]') throw new Error('wrong global order ' + globalThis.libraryOrder);
                        """.trimIndent()) }
                        node(check.path)
                        val empty = get("/empty").body().decodeToString()
                        assertFalse("/_bml/libraries/" in empty || "/_bml/js/" in empty, empty)
                        for ((name, color) in listOf("green" to "rgb(0, 128, 0)", "blue" to "rgb(0, 0, 255)")) {
                            var html = get("/$name").body().decodeToString()
                            for (match in Regex("<link[^>]+href=\"([^\"]+)\"[^>]*>").findAll(html).toList()) {
                                html = html.replace(match.value, "<style>" + get(match.groupValues[1].replace("&amp;", "&")).body().decodeToString() + "</style>")
                            }
                            val htmlFile = File(clientRoot, "$name.html").apply { writeText(html.replace(Regex("<script[\\s\\S]*?</script>"), "")) }
                            val palette = File(clientRoot, "$name.mjs").apply { writeText("""
                                import { Window } from ${JsonPrimitive(File(tools, "node_modules/happy-dom/lib/index.js").toURI().toString())};
                                import { readFileSync } from 'node:fs';
                                const window = new Window();
                                window.document.write(readFileSync(${JsonPrimitive(htmlFile.path)}, 'utf8'));
                                const color = window.getComputedStyle(window.document.querySelector('.label')).color;
                                if (color !== ${JsonPrimitive(color)}) throw new Error('palette: ' + color);
                                window.happyDOM.abort();
                            """.trimIndent()) }
                            node(palette.path)
                        }
                    } finally { engine.stopWithoutHalting() }
                }
            }
        } finally { work.deleteRecursively() }
    }

    private fun producer(work: File, name: String, initial: Int): File {
        val producer = File(work, name).apply { mkdirs() }
        val root = File(producer, "bml").apply { mkdirs() }
        val model = File(producer, "Counter.kt").apply { writeText("""
            package example.$name
            @kotlinx.serialization.Serializable
            data class Counter(var count: Int = $initial) {
                fun increment() { count++ }
            }
        """.trimIndent()) }
        fun counter(tag: String, scope: String, site: Boolean = false) {
            File(root, "$tag.bml").writeText("""
                <component tag="$tag" ${if (site) "scope=\"site\"" else ""}>
                  ${if (site) "" else "<prop name=\"id\" type=\"String\"/>"}
                  <script server provides="counter" scope="$scope" ${if (scope == "client-local") "clear-on-sign-out" else ""}>example.$name.Counter()</script>
                  <style scoped>button { color: red; }</style>
                  <island name="view" ${if (site) "" else ":key=\"id\""}>
                    <script client>ctx.onUnmount(() => {})</script>
                    <html:button @click="counter.increment">Count { counter.count }</html:button>
                    <private-label/>
                  </island>
                  ${if (tag == "counter") "<island name=\"details\" render=\"deferred\"><private-detail/><fallback>Waiting</fallback></island>" else ""}
                </component>
            """.trimIndent())
        }
        counter("counter", "server-session")
        counter("client-counter", "client-local")
        counter("site-counter", "server-session", site = true)
        File(root, "private-label.bml").writeText("""<component tag="private-label"><span>${name.uppercase()} HELPER</span></component>""")
        File(root, "private-detail.bml").writeText("""<component tag="private-detail"><span>PRIVATE DETAIL { ctx.token ?: "guest" }</span></component>""")
        File(root, "badge.bml").writeText("""<component tag="badge"><prop name="label" type="String" default="Badge"/><span>{ label }</span></component>""")
        File(root, "unused.bml").writeText("""<component tag="unused"><span>Unused</span></component>""")
        val output = File(producer, "classes")
        val resources = File(producer, "resources")
        val ts = File(producer, "ts")
        compile(output, listOf(
            "bmlSourceRoot=$root", "bmlPackageName=example.$name.generated", "bmlLibraryId=example:$name",
            "bmlLibraryVersion=1.0", "bmlResourcesOutputDir=$resources", "bmlTsOutputDir=$ts",
        ) + listOf("counter", "client-counter", "site-counter", "badge", "unused").map { "bmlLibraryExport=$it" }, sources = listOf(model))
        val id = BmlLibraryArtifacts.componentId("example:$name", "counter")
        assertTrue("component:$id:view" in File(ts, "CounterPage.ts").readText())
        val artifact = archive(File(work, "$name.jar"), listOf(output, resources))
        assertTrue(producer.deleteRecursively())
        return artifact
    }

    private fun archive(artifact: File, roots: List<File>): File {
        JarOutputStream(artifact.outputStream()).use { jar ->
            roots.forEach { dir ->
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    jar.putNextEntry(JarEntry(file.relativeTo(dir).invariantSeparatorsPath))
                    jar.write(file.readBytes())
                    jar.closeEntry()
                }
            }
        }
        return artifact
    }

    private fun compile(output: File, options: List<String>, classpath: List<File> = emptyList(), sources: List<File> = emptyList(), expected: ExitCode = ExitCode.OK): String {
        output.mkdirs()
        val anchor = File(output.parentFile, "Anchor.kt").apply { writeText("package anchor") }
        val messages = StringBuilder()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) messages.appendLine("$location $message")
            }
        }
        val service = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
        val plugins = System.getProperty("java.class.path").split(File.pathSeparator).filter { entry ->
            val file = File(entry)
            if (file.isDirectory) File(file, service).isFile
            else file.extension == "jar" && JarFile(file).use { it.getEntry(service) != null }
        }
        val args = K2JVMCompilerArguments().apply {
            val bmlSources = options.filter { it.startsWith("bmlSourceRoot=") }.flatMap { option ->
                File(option.substringAfter('=')).walkTopDown().filter { it.isFile && it.extension == "bml" }
                    .map { it.absolutePath }.toList()
            }
            freeArgs = listOf(anchor.absolutePath) + bmlSources + sources.map { it.absolutePath }
            destination = output.absolutePath
            this.classpath = (listOf(System.getProperty("java.class.path")) + classpath.map { it.absolutePath }).joinToString(File.pathSeparator)
            jvmTarget = "25"
            noStdlib = true
            noReflect = true
            pluginClasspaths = plugins.toTypedArray()
            pluginOptions = options.map { "plugin:bml:$it" }.toTypedArray()
        }
        assertEquals(expected, K2JVMCompiler().exec(collector, Services.EMPTY, args), messages.toString())
        return messages.toString()
    }

    private fun awaitServer(port: Int) {
        repeat(100) {
            try {
                java.net.Socket("localhost", port).use { return }
            } catch (_: java.net.ConnectException) {
                Thread.sleep(50)
            }
        }
        error("BML binary runtime server did not start")
    }
}
