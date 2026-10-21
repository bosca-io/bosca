package bosca.bml.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.jar.JarFile
import groovy.json.JsonSlurper
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises Gradle's real continuous deployment with a compiled project library and HTTP server. */
class BmlLibraryDevelopmentTest {
    @Test
    fun `editable library changes reach the same running consumer and failed builds retain its generation`() {
        val work = createTempDirectory("bml-library-development").toFile()
        var process: Process? = null
        var reloadStream: InputStream? = null
        var readers: ExecutorService? = null
        try {
            val fixture = BmlCompilerFixture(work)
            fun source(path: String, body: String) = File(work, path).apply {
                parentFile.mkdirs(); writeText(body.trimIndent())
            }
            val plugins = Properties().apply {
                BmlLibraryDevelopmentTest::class.java.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")
                    .use { load(requireNotNull(it)) }
            }.getProperty("implementation-classpath").split(File.pathSeparator).map(::File)
            val runtime = System.getProperty("bml.developmentFixtureClasspath").split(File.pathSeparator).map(::File)
            source("settings.gradle", "rootProject.name = 'development'; include 'tokens', 'design', 'app'")
            source("gradle.properties", """
                kotlin.compiler.execution.strategy=in-process
                org.gradle.jvmargs=-Xmx1g
                org.gradle.workers.max=2
            """)
            val build = source("build.gradle", """
                buildscript { dependencies { classpath files(${plugins.joinToString(",", transform = ::quote)}) } }
                subprojects {
                    apply plugin: 'org.jetbrains.kotlin.jvm'
                    apply plugin: 'io.bosca.bml'
                    group = 'example'; version = '1'
                    ${fixture.shared}
                    bml { clientBundler.set(file(${quote(File("../bml-runtime/tools/bundle.mjs").canonicalFile)})) }
                }
                project(':tokens') {
                    bml { packageName.set('example.tokens.generated'); library { exports.set(['token-label']) } }
                }
                project(':design') {
                    apply plugin: 'java-library'
                    dependencies { api project(':tokens') }
                    bml { packageName.set('example.design.generated'); library { exports.set(['library-label', 'extra']) } }
                }
                project(':app') {
                    apply plugin: 'application'
                    dependencies { implementation project(':design'); implementation files(${runtime.joinToString(",", transform = ::quote)}) }
                    bml {
                        packageName.set('example.app.generated')
                        clientRuntime.set(file(${quote(File("../bml-runtime/src/index.ts").canonicalFile)}))
                    }
                    application { mainClass = 'example.app.MainKt' }
                }
                tasks.register('prepareDevelopment') {
                    doLast { file('gradle-home.txt').text = gradle.gradleHomeDir.absolutePath }
                }
            """)
            val model = source("design/src/main/kotlin/example/design/Message.kt", """
                package example.design
                object Message {
                    val text: String get() = "library-one:" + requireNotNull(Message::class.java.getResourceAsStream("/library-value.txt"))
                        .bufferedReader().use { it.readText() }
                }
            """)
            val token = source("tokens/src/main/kotlin/example/tokens/Token.kt", """
                package example.tokens
                object Token { val text: String get() = "token-one" }
            """)
            source("tokens/src/main/bml/label.bml", """<component tag="token-label"><span>{ example.tokens.Token.text }</span></component>""")
            source("design/src/main/bml/imports.bml", """<import library="example:tokens"/>""")
            val label = source("design/src/main/bml/label.bml", """
                <component tag="library-label">
                  <span class="library-label">{ example.design.Message.text } bml-one <token-label/></span>
                  <script client scoped>
                    import { revision } from '../client/revision'
                    ctx.root.setAttribute('data-revision', revision)
                  </script>
                  <style scoped>.library-label { background-image: url('./pattern.svg'); }</style>
                </component>
            """)
            val script = source("design/src/main/client/revision.ts", "export const revision = 'client-one'")
            val styles = source("design/src/main/client/design.css", ":root { --library-revision: style-one; }")
            val asset = source("design/src/main/bml/pattern.svg", """<svg xmlns="http://www.w3.org/2000/svg"><title>asset-one</title></svg>""")
            val resource = source("design/src/main/resources/library-value.txt", "resource-one")
            val extra = source("design/src/main/bml/extra.bml", """<component tag="extra"><span>extra-one</span></component>""")
            val imports = source("app/src/main/bml/imports.bml", """<import library="example:design"/>""")
            val home = source("app/src/main/bml/home.bml", """<page route="/"><html><body><library-label/></body></html></page>""")
            val global = source("app/src/main/bml/global.bml", """<page route="/global"><html><body><library-label/></body></html></page>""")
            val extraPage = source("app/src/main/bml/extra.bml", """<page route="/extra"><html><body><extra/></body></html></page>""")
            source("app/src/main/kotlin/example/app/Main.kt", """
                package example.app
                import bosca.bml.project.CompiledProject
                import bosca.bml.server.BmlServer
                import example.app.generated.*
                fun main() {
                    println("DEVELOPMENT_PID=" + ProcessHandle.current().pid())
                    BmlServer(project=CompiledProject(name="development", version="1"), pages=BmlPages.all,
                        components=BmlComponents.all, componentRenderers=BmlComponents.renderers,
                        installationEndpoint=null, port=0).start()
                }
            """)
            GradleRunner.create().withProjectDir(work)
                .withArguments("prepareDevelopment", "--stacktrace", "--no-build-cache", "--max-workers=2").build()
            val gradle = File(File(work, "gradle-home.txt").readText(), "bin/gradle")
            val log = File(work, "development.log")
            process = ProcessBuilder(gradle.absolutePath, ":app:bmlDev", "--continuous", "--console=plain",
                "--no-build-cache", "--max-workers=2").directory(work)
                .redirectErrorStream(true).redirectOutput(log).start()
            val running = process
            fun await(description: String, seconds: Long = 120, condition: () -> Boolean) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
                while (System.nanoTime() < deadline) {
                    assertTrue(running.isAlive, log.readText().takeLast(16000))
                    if (condition()) return
                    Thread.sleep(100)
                }
                error("Timed out waiting for $description: ${log.readText().takeLast(16000)}")
            }
            val startup = Regex("Bosca server started on port (\\d+) with")
            await("the first successful bind") {
                val output = log.readText()
                check("BUILD FAILED" !in output) { output }
                startup.containsMatchIn(output)
            }
            val port = requireNotNull(startup.find(log.readText())).groupValues[1].toInt()
            val client = HttpClient.newHttpClient()
            val base = URI("http://localhost:$port/")
            fun request(path: String): HttpResponse<String> = client.send(HttpRequest.newBuilder(base.resolve(path))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
            fun body(path: String = "/"): String {
                val response = request(path)
                assertEquals(200, response.statusCode(), response.body())
                return response.body()
            }
            fun assets(kind: String): String {
                val urls = if (kind == "js") Regex("""<script\b[^>]*\bsrc="([^"]+)"""").findAll(body())
                    .map { it.groupValues[1] }.toList()
                else Regex("""<link\b[^>]*\bhref="([^"]+\.css[^"]*)"""").findAll(body())
                    .map { it.groupValues[1] }.toList()
                assertTrue(urls.isNotEmpty(), "$kind must be emitted by the library consumer")
                val pending = ArrayDeque(urls.map { base.resolve(it.replace("&amp;", "&")) })
                val loaded = linkedMapOf<URI, String>()
                while (pending.isNotEmpty()) {
                    val uri = pending.removeFirst()
                    if (uri in loaded) continue
                    assertEquals(base.authority, uri.authority)
                    val content = body(uri.toString())
                    loaded[uri] = content
                    if (kind == "js") Regex("""\b(?:from\s+|import\s+)["']([./][^"']+)["']""")
                        .findAll(content).forEach { pending.add(uri.resolve(it.groupValues[1])) }
                }
                return loaded.values.joinToString("\n")
            }
            fun svg(): String {
                val path = requireNotNull(Regex("""url\(["']?([^)'"\s]+\.svg)["']?\)""").find(assets("css")))
                    .groupValues[1]
                return body(path)
            }
            fun rebuild(success: Boolean = true, edit: () -> Unit) {
                val output = log.readText()
                val successful = Regex("BUILD SUCCESSFUL").findAll(output).count()
                val failed = Regex("BUILD FAILED").findAll(output).count()
                val waits = Regex("Waiting for changes").findAll(output).count()
                edit()
                await(if (success) "a successful rebuild" else "the expected failed build") {
                    val current = log.readText()
                    if (success) {
                        check(Regex("BUILD FAILED").findAll(current).count() == failed) { current.takeLast(16000) }
                        Regex("BUILD SUCCESSFUL").findAll(current).count() > successful
                    } else Regex("BUILD FAILED").findAll(current).count() > failed
                }
                await("the next watch cycle") { Regex("Waiting for changes").findAll(log.readText()).count() > waits }
            }
            val marker = File(work, "app/build/bml/dev/current-generation")
            await("the initial library rendering") { "library-one" in body() }
            await("Gradle's next watch cycle") { "Waiting for changes" in log.readText() }
            assertTrue("token-one" in body() && "resource-one" in body() && "bml-one" in body())
            assertTrue("client-one" in assets("js"))
            assertTrue("style-one" in assets("css"))
            assertTrue("asset-one" in svg())
            val response = client.send(HttpRequest.newBuilder(base.resolve("/_bml/reload"))
                .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            assertEquals(200, response.statusCode())
            reloadStream = response.body()
            val reader = reloadStream.bufferedReader()
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            readers = executor
            fun nextToken(previous: String? = null): String = executor.submit<String> {
                var found: String? = null
                while (found == null) {
                    val line = requireNotNull(reader.readLine()) { "Reload stream closed unexpectedly" }
                    if (line.startsWith("data:")) {
                        val value = line.removePrefix("data:").trim()
                        if (previous == null || value != previous) found = value
                    }
                }
                found
            }.get(10, TimeUnit.SECONDS)
            val firstToken = nextToken()
            rebuild { model.writeText(model.readText().replace("library-one", "library-two")) }
            await("the changed library in the persistent server", seconds = 10) { "library-two" in body() }
            val changedToken = nextToken(firstToken)
            assertEquals(firstToken.substringBefore(':'), changedToken.substringBefore(':'), "The server boot identity must stay stable")
            rebuild { token.writeText(token.readText().replace("token-one", "token-two")) }
            await("the changed transitive library", seconds = 10) { "token-two" in body() }
            rebuild { label.writeText(label.readText().replace("bml-one", "bml-two")) }
            await("the changed BML component", seconds = 10) { "bml-two" in body() }
            rebuild { styles.writeText(styles.readText().replace("style-one", "style-two")) }
            await("the changed global library CSS", seconds = 10) { "style-two" in assets("css") }
            rebuild { script.writeText(script.readText().replace("client-one", "client-two")) }
            await("the changed library client helper", seconds = 10) { "client-two" in assets("js") }
            rebuild { asset.writeText(asset.readText().replace("asset-one", "asset-two")) }
            await("the changed library asset", seconds = 10) { "asset-two" in svg() }
            rebuild { resource.writeText("resource-two") }
            await("the changed library resource", seconds = 10) { "resource-two" in body() }
            val workingModel = model.readText()
            val workingGeneration = marker.readText()
            rebuild(success = false) { model.writeText("invalid Kotlin source") }
            assertEquals(workingGeneration, marker.readText())
            assertTrue("library-two" in body() && "resource-two" in body())
            rebuild { model.writeText(workingModel.replace("library-two", "library-three")) }
            await("the repaired library", seconds = 10) { "library-three" in body() }
            rebuild {
                imports.writeText("""<import library="example:design" as="ui"/>""")
                for (page in listOf(home, global, extraPage)) page.writeText(page.readText()
                    .replace("<library-label/>", "<ui:library-label/>").replace("<extra/>", "<ui:extra/>"))
            }
            await("the changed root import", seconds = 10) { "library-three" in body("/global") }
            val aliasedGeneration = marker.readText()
            rebuild(success = false) { assertTrue(imports.delete()) }
            assertEquals(aliasedGeneration, marker.readText())
            assertTrue("library-three" in body("/global"))
            rebuild {
                for (page in listOf(home, global, extraPage)) page.writeText("""<import library="example:design" as="ui"/>""" + page.readText())
            }
            await("file-local imports after removing the global import", seconds = 10) { "library-three" in body("/global") }
            rebuild {
                imports.writeText("""<import library="example:design" as="ui"/>""")
                for (page in listOf(home, global, extraPage)) page.writeText(page.readText().substringAfter("/>"))
            }
            await("the recreated global import", seconds = 10) { "library-three" in body("/global") }
            assertTrue("extra-one" in body("/extra"))
            rebuild {
                assertTrue(extra.delete())
                assertTrue(extraPage.delete())
                build.writeText(build.readText().replace("['library-label', 'extra']", "['library-label']"))
            }
            await("the removed route", seconds = 10) { request("/extra").statusCode() == 404 }
            val snapshots = marker.readLines().drop(1).filter { it.endsWith(".jar") }
            assertEquals(2, snapshots.size, "Both direct and transitive libraries are isolated")
            snapshots.forEach { snapshot -> JarFile(snapshot).use { jar ->
                val manifest = JsonSlurper().parse(jar.getInputStream(jar.getJarEntry("META-INF/bml/library-manifest.json"))) as Map<*, *>
                val components = manifest["components"] as List<*>
                assertFalse(components.any { (it as Map<*, *>)["tag"] == "extra" })
                assertFalse(jar.entries().asSequence().any { it.name.endsWith("/ExtraPage.class") })
            } }
            assertEquals(1, startup.findAll(log.readText()).count(), "Netty must keep its original listener")
            val pids = Regex("DEVELOPMENT_PID=(\\d+)").findAll(log.readText()).map { it.groupValues[1] }.toList()
            assertTrue(pids.size > 1)
            assertEquals(1, pids.distinct().size, "The application JVM must stay alive across generations")
        } finally {
            reloadStream?.close()
            readers?.shutdownNow()
            process?.let { running ->
                running.outputStream.close()
                if (!running.waitFor(10, TimeUnit.SECONDS)) {
                    running.destroy()
                    if (!running.waitFor(10, TimeUnit.SECONDS)) running.destroyForcibly().waitFor()
                }
            }
            File(work, "development.log").takeIf { it.isFile }?.copyTo(
                File("build/reports/library-development/development.log").apply { parentFile.mkdirs() }, overwrite = true,
            )
            work.deleteRecursively()
        }
    }
}
