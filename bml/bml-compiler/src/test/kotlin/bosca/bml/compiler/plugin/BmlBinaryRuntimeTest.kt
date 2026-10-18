package bosca.bml.compiler.plugin

import bosca.bml.library.BmlLibraryArtifacts
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
        val artifact = File(work, "$name.jar")
        JarOutputStream(artifact.outputStream()).use { jar ->
            listOf(output, resources).forEach { dir ->
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    jar.putNextEntry(JarEntry(file.relativeTo(dir).invariantSeparatorsPath))
                    jar.write(file.readBytes())
                    jar.closeEntry()
                }
            }
        }
        assertTrue(producer.deleteRecursively())
        return artifact
    }

    private fun compile(output: File, options: List<String>, classpath: List<File> = emptyList(), sources: List<File> = emptyList()) {
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
        assertEquals(ExitCode.OK, K2JVMCompiler().exec(collector, Services.EMPTY, args), messages.toString())
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
