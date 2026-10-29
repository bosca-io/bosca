package bosca.bml.gradle

import bosca.bml.library.BmlLibraryArtifacts
import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlComponentInfo
import bosca.bml.render.BmlComponentRenderer
import bosca.bml.render.BmlIslandActionDispatcher
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.BmlProviderPage
import bosca.bml.render.RenderContext
import bosca.bml.server.BmlServer
import bosca.di.DuplicateProviderException
import bosca.di.MissingProviderException
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import groovy.json.JsonSlurper
import kotlinx.coroutines.runBlocking
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLClassLoader
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.jar.JarFile
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Actual KSP -> Maven -> source-free application -> standard DI -> Netty live-state wiring. */
@OptIn(InternalDI::class)
class BmlLibraryDiConsumerTest {
    @Test
    fun `compiled service registrars compose once across a diamond and preserve host bindings and live state`() {
        val work = createTempDirectory("bml-di-consumer").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val bundler = File("../bml-runtime/tools/bundle.mjs").canonicalFile
            val runtime = File("../bml-runtime/src/index.ts").canonicalFile
            val processors = System.getProperty("bml.diFixtureProcessors").split(File.pathSeparator).map(::File)
            fun write(root: File, path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
            fun project(name: String, exports: String, dependencies: String = "", registrars: String = "", ksp: Boolean = false): File {
                val directory = File(work, name).apply { mkdirs() }
                write(directory, "settings.gradle", "rootProject.name = '$name'")
                write(directory, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
                write(directory, "build.gradle", """
                    plugins { id 'org.jetbrains.kotlin.jvm'; id 'java-library'; id 'io.bosca.bml'; id 'maven-publish'
                        ${if (ksp) "id 'org.jetbrains.kotlin.plugin.serialization'; id 'com.google.devtools.ksp'" else ""} }
                    group = 'example'; version = '1.0'
                    ${fixture.shared}
                    dependencies { $dependencies ${if (ksp) "; ksp files(${processors.joinToString(",", transform = ::quote)})" else ""} }
                    ${if (ksp) "ksp { arg('ProviderRegistrarPrefix', 'Design'); arg('enableSerializersRegistrar', 'false'); arg('enableSerializerRegistrar', 'false') }" else ""}
                    bml { packageName.set('example.$name.generated'); clientBundler.set(file(${quote(bundler)}))
                        library { exports.set([$exports]); providerRegistrars.set([$registrars]) } }
                    publishing {
                        publications { maven(MavenPublication) { from components.java } }
                        repositories { maven { name = 'fixture'; url = uri(${quote(fixture.repository)}) } }
                    }
                """)
                return directory
            }
            val producer = project("design", "'counter'", registrars = "'bosca.di.DesignProviderRegistrar'", ksp = true)
            write(producer, "src/main/kotlin/example/design/Models.kt", """
                package example.design
                fun interface HostLabels { fun label(): String }
                interface CounterFactory : bosca.service.Service { fun create(): Counter }
                object DiProbe { var factories = 0; val registrations = mutableListOf<String>() }
                @kotlinx.serialization.Serializable
                data class Counter(val label: String = "", var count: Int = 0) { fun increment() { count++ } }
                @bosca.service.annotation.ServiceImplementation
                class CounterFactoryImpl(private val labels: HostLabels) : CounterFactory {
                    init { DiProbe.factories++ }
                    override fun create() = Counter(labels.label())
                }
                @bosca.di.annotation.Provider
                class CounterProvider : bosca.di.ObjectProvider<Counter> {
                    override val type = Counter::class
                    override suspend fun get() = bosca.di.provide<CounterFactory>().create()
                }
                class ExtraRegistrar : bosca.di.ProviderRegistrar {
                    override fun register() { DiProbe.registrations += "design" }
                }
            """)
            write(producer, "src/main/bml/counter.bml", """
                <component tag="counter"><prop name="id" type="String"/>
                  <inject server name="counter" type="example.design.Counter" scope="server-session"/>
                  <island name="view" :key="id"><html:button @click="counter.increment">{ counter.label }:{ counter.count }</html:button></island>
                  <island name="details" render="deferred"><private-detail/><fallback>Waiting</fallback></island>
                </component>
            """)
            write(producer, "src/main/bml/private-detail.bml", """
                <component tag="private-detail"><inject name="factory" type="example.design.CounterFactory"/><span>DETAIL { factory.create().label }</span></component>
            """)
            val publish = "publishMavenPublicationToFixtureRepository"
            runner(producer, publish).build()
            val buildFile = File(producer, "build.gradle")
            buildFile.writeText(buildFile.readText().replace("providerRegistrars.set(['bosca.di.DesignProviderRegistrar'])",
                "providerRegistrars.set(['bosca.di.DesignProviderRegistrar', 'example.design.ExtraRegistrar'])"))
            val metadataOnly = runner(producer, publish).build()
            assertEquals(TaskOutcome.SUCCESS, metadataOnly.task(":compileKotlin")?.outcome)
            val repeated = runner(producer, publish).build()
            assertTrue("Reusing configuration cache" in repeated.output, repeated.output)
            assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":compileKotlin")?.outcome)
            val binary = File(fixture.repository, "example/design/1.0/design-1.0.jar")
            val validBytes = binary.readBytes()
            JarFile(binary).use { jar ->
                assertTrue(jar.getJarEntry("bosca/di/DesignProviderRegistrar.class") != null)
                assertTrue(jar.getJarEntry("example/design/CounterFactoryImplProvider.class") != null)
            }
            val validBuild = buildFile.readText()
            buildFile.appendText("\ntasks.named('jar') { exclude('bosca/di/DesignProviderRegistrar.class') }\n")
            val invalid = runner(producer, publish).buildAndFail()
            assertTrue("Binary archive is missing BML provider registrar bosca.di.DesignProviderRegistrar" in invalid.output, invalid.output)
            assertTrue(validBytes.contentEquals(binary.readBytes()))
            buildFile.writeText(validBuild)
            assertTrue(producer.deleteRecursively())
            for ((name, tag) in listOf("other" to "tile", "wrapper" to "card")) {
                val wrapper = project(name, "'$tag'", "api 'example:design:1.0'", "'example.$name.WrapperRegistrar'")
                write(wrapper, "src/main/bml/imports.bml", """<import library="example:design"/>""")
                write(wrapper, "src/main/bml/$tag.bml", """<component tag="$tag"><prop name="id" type="String"/><counter :id="id"/></component>""")
                write(wrapper, "src/main/kotlin/example/$name/WrapperRegistrar.kt", """
                    package example.$name
                    class WrapperRegistrar : bosca.di.ProviderRegistrar {
                        override fun register() { example.design.DiProbe.registrations += "$name" }
                    }
                """)
                runner(wrapper, publish).build()
                assertTrue(wrapper.deleteRecursively())
            }
            val consumer = File(work, "consumer").apply { mkdirs() }
            write(consumer, "settings.gradle", "rootProject.name = 'consumer'")
            write(consumer, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            write(consumer, "build.gradle", """
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml' }
                ${fixture.shared}
                dependencies { implementation 'example:wrapper:1.0'; implementation 'example:other:1.0' }
                bml { packageName.set('consumer.generated'); clientBundler.set(file(${quote(bundler)})); clientRuntime.set(file(${quote(runtime)})) }
            """)
            write(consumer, "src/main/bml/imports.bml", """<import library="example:wrapper"/><import library="example:other"/>""")
            write(consumer, "src/main/bml/home.bml", """
                <import library="example:wrapper" as="ui"/>
                <page route="/"><html><body><card id="one"/><ui:card id="two"/><tile id="three"/></body></html></page>
            """)
            write(consumer, "src/main/bml/next.bml", """<page route="/next"><html><body><card id="four"/></body></html></page>""")
            write(consumer, "src/main/kotlin/consumer/Bindings.kt", """
                package consumer
                object Bindings {
                    fun host() { bosca.di.provides<example.design.HostLabels> { example.design.HostLabels { "HOST" } } }
                    fun conflict() { bosca.di.provides<example.design.Counter> { example.design.Counter("CONFLICT") } }
                    fun overrideCounter() { bosca.di.provides<example.design.Counter>(overrideExisting = true) { example.design.Counter("OVERRIDE") } }
                    fun registrations() = example.design.DiProbe.registrations.toList()
                    fun factories() = example.design.DiProbe.factories
                    fun reset() { example.design.DiProbe.registrations.clear(); example.design.DiProbe.factories = 0 }
                }
            """)
            runner(consumer, "jar").build()
            val cached = runner(consumer, "jar").build()
            assertTrue("Reusing configuration cache" in cached.output, cached.output)
            assertEquals(TaskOutcome.UP_TO_DATE, cached.task(":compileKotlin")?.outcome)
            val applicationJar = File(work, "application.jar")
            File(consumer, "build/libs/consumer.jar").copyTo(applicationJar)
            assertTrue(consumer.deleteRecursively())
            val dependencies = listOf("design", "other", "wrapper").map { File(fixture.repository, "example/$it/1.0/$it-1.0.jar") }
            URLClassLoader((listOf(applicationJar) + dependencies).map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader).use { loader ->
                fun registry(name: String, property: String): Any {
                    val type = loader.loadClass("consumer.generated.$name")
                    return type.getMethod("get" + property.replaceFirstChar(Char::uppercaseChar)).invoke(type.getField("INSTANCE").get(null))
                }
                val bindings = loader.loadClass("consumer.Bindings")
                val owner = bindings.getField("INSTANCE").get(null)
                fun call(name: String): Any? = bindings.getMethod(name).invoke(owner)
                @Suppress("UNCHECKED_CAST") val pages = registry("BmlPages", "all") as List<BmlPageRenderer>
                @Suppress("UNCHECKED_CAST") val components = registry("BmlComponents", "all") as List<BmlComponentInfo>
                @Suppress("UNCHECKED_CAST") val renderers = registry("BmlComponents", "renderers") as Map<String, BmlComponentRenderer>
                @Suppress("UNCHECKED_CAST") val dispatchers = registry("BmlIslands", "componentDispatchers") as Map<String, BmlIslandActionDispatcher>
                assertEquals(listOf("bosca.di.DesignProviderRegistrar", "example.design.ExtraRegistrar", "example.other.WrapperRegistrar", "example.wrapper.WrapperRegistrar"),
                    (pages.first() as BmlProviderPage).providerRegistrars.map { it.javaClass.name })
                fun server(port: Int) = BmlServer(CompiledProject(name = "di", version = "1"), pages, port,
                    components = components, componentRenderers = renderers, componentIslandDispatchers = dispatchers)
                fun app(port: Int) = BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: true\n".byteInputStream()))
                ProviderRegistry.clear(); call("reset"); call("host"); call("conflict")
                assertFailsWith<DuplicateProviderException> { server(0).install(app(0)) }
                ProviderRegistry.clear(); call("reset")
                val missing = server(0)
                missing.install(app(0))
                assertFailsWith<MissingProviderException> {
                    runBlocking {
                        renderers.getValue(BmlLibraryArtifacts.componentId("example:design", "private-detail"))
                            .render(RenderContext(), emptyMap()) {}
                    }
                }
                for (override in listOf(false, true)) {
                    ProviderRegistry.clear(); call("reset"); call("host")
                    val port = 0
                    val host = server(port)
                    val application = app(port)
                    host.install(application)
                    assertEquals(listOf("design", "other", "wrapper"), call("registrations"))
                    if (override) call("overrideCounter")
                    application.freezeMiddleware()
                    val engine = NettyServerEngine(application, port)
                    thread(isDaemon = true, name = "bml-ksp-di-test") { engine.start() }
                    try {
                        val boundPort = awaitServer(engine)
                        HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
                            .connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build().use { client ->
                            fun request(path: String, body: String? = null): HttpResponse<String> {
                                val request = HttpRequest.newBuilder(URI("http://[::1]:$boundPort$path")).timeout(Duration.ofSeconds(10))
                                if (body == null) request.GET() else request.POST(HttpRequest.BodyPublishers.ofString(body))
                                return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
                            }
                            val label = if (override) "OVERRIDE" else "HOST"
                            assertTrue("$label:0" in request("/").body())
                            val id = BmlLibraryArtifacts.componentId("example:design", "counter")
                            for (key in listOf("one", "two", "three")) {
                                val action = request("/_bml/action", """{"page":"/","path":"/","stateKey":"$id.counter:$key","method":"increment"}""")
                                assertEquals(200, action.statusCode(), action.body())
                                assertTrue("$label:1" in action.body(), action.body())
                            }
                            assertTrue("$label:0" in request("/next").body())
                            val fragment = request("/_bml/render/$id", """{"id":"fragment"}""")
                            assertEquals(200, fragment.statusCode(), fragment.body())
                            assertTrue("$label:0" in fragment.body(), fragment.body())
                            val deferred = request("/_bml/deferred/" + URLEncoder.encode("component:$id:details", Charsets.UTF_8),
                                """{"page":"/","path":"/","query":{},"props":{}}""")
                            assertEquals(200, deferred.statusCode(), deferred.body())
                            assertTrue("DETAIL HOST" in deferred.body(), deferred.body())
                            assertEquals(1, call("factories"), "The generated singleton must survive page navigation and requests")
                            assertEquals(listOf("design", "other", "wrapper"), call("registrations"))
                        }
                    } finally { engine.stopWithoutHalting() }
                }
            }
        } finally { ProviderRegistry.clear(); work.deleteRecursively() }
    }

    private fun awaitServer(engine: NettyServerEngine): Int {
        repeat(100) {
            engine.boundPort?.let { return it }
            Thread.sleep(25)
        }
        error("BML DI fixture server did not start")
    }

    private fun runner(project: File, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project).withPluginClasspath()
        .withArguments(*tasks, "--stacktrace", "--configuration-cache", "--no-watch-fs", "--max-workers=2")
}
