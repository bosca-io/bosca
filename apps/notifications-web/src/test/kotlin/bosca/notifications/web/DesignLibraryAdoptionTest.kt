package bosca.notifications.web

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compiles and exercises the actual token-scoped product using workspace and source-free Maven artifacts. */
class DesignLibraryAdoptionTest {
    @Test
    fun `notification preferences use the published design library without producer sources`() {
        val work = createTempDirectory("published-notification-design-").toFile().canonicalFile
        val original = File(System.getProperty("adoption.project"))
        val reports = File(original, "build/reports/design-adoption").apply { mkdirs() }
        try {
            for (development in listOf(false, true)) {
                verifyProduct(System.getProperty("adoption.classpath"), development, "workspace-$development", reports)
            }
            val repository = File(work, "repository")
            val artifacts = System.getProperty("adoption.artifacts").lineSequence().mapIndexed { index, line ->
                val artifact = line.split('\t')
                if (artifact[0] == "io.bosca") {
                    // KMP workspace IDs use the base module; JVM POMs use the target artifact name.
                    val suffix = "-${artifact[2]}${if (artifact[3].isEmpty()) "" else "-${artifact[3]}"}.jar"
                    val publicationName = File(artifact[4]).name.removeSuffix(suffix)
                    listOf(artifact[0], publicationName, artifact[2], artifact[3], artifact[4])
                } else listOf("fixture.runtime", "artifact-$index", "1", "", artifact[4])
            }.toList()
            for ((group, name, version, classifier, path) in artifacts) {
                val directory = File(repository, "${group.replace('.', '/')}/$name/$version").apply { mkdirs() }
                File(path).copyTo(File(directory, "$name-$version${if (classifier.isEmpty()) "" else "-$classifier"}.jar"), overwrite = true)
                File(directory, "$name-$version.pom").writeText(pom(group, name, version))
            }
            val version = System.getProperty("adoption.version")
            val design = File(repository, "io/bosca/bml-design/$version")
            File(System.getProperty("adoption.pom")).copyTo(File(design, "bml-design-$version.pom"), overwrite = true)
            assertFalse(repository.walkTopDown().any { it.extension == "bml" || it.name.endsWith("-sources.jar") })

            val compiler = artifacts.single { it[0] == "io.bosca" && it[1] == "bml-compiler" }
            val compilerFiles = System.getProperty("adoption.compiler").split(File.pathSeparator)
            val compilerDependencies = artifacts.filter { it != compiler && it[4] in compilerFiles }.distinctBy { it.take(4) }.joinToString("\n") {
                "<dependency><groupId>${it[0]}</groupId><artifactId>${it[1]}</artifactId><version>${it[2]}</version>" +
                    (if (it[3].isEmpty()) "" else "<classifier>${it[3]}</classifier>") + "</dependency>"
            }
            File(repository, "io/bosca/bml-compiler/${compiler[2]}/bml-compiler-${compiler[2]}.pom")
                .writeText(pom(compiler[0], compiler[1], compiler[2], compilerDependencies))

            val plugins = System.getProperty("adoption.plugin").split(File.pathSeparator).mapIndexed { index, path ->
                File(path).copyTo(File(work, "plugins/$index-${File(path).name}").apply { parentFile.mkdirs() })
            }
            val runtimeSource = File(System.getProperty("adoption.runtime"))
            val runtime = File(work, "toolchain/bml-runtime").apply { mkdirs() }
            for (directory in listOf("src", "tools")) File(runtimeSource, directory).copyRecursively(File(runtime, directory))
            val installed = File(runtimeSource, "node_modules")
            installed.copyRecursively(File(runtime, "node_modules"))
            for (source in installed.walkTopDown().filter { it.isFile && it.canExecute() }) {
                assertTrue(File(runtime, "node_modules/${source.relativeTo(installed).path}").setExecutable(true, false), source.path)
            }

            val consumer = File(work, "consumer").apply { mkdirs() }
            File(original, "src/main").copyRecursively(File(consumer, "src/main"))
            File(original, "build/generated/bosca-graphql/kotlin").copyRecursively(
                File(consumer, "src/main/kotlin/generated-clients"))
            File(consumer, "settings.gradle").writeText("rootProject.name = 'published-design-consumer'\n")
            File(consumer, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx2g\n")
            val hostDependencies = artifacts.filter { it[4] in System.getProperty("adoption.classpath").split(File.pathSeparator) }
                .filterNot { it[0] == "io.bosca" && it[1] == "bml-design" }.distinctBy { it.take(4) }
                .joinToString("\n") { "implementation '${it[0]}:${it[1]}:${it[2]}${if (it[3].isEmpty()) "" else ":${it[3]}"}'" }
            File(consumer, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'org.jetbrains.kotlin.plugin.serialization'; id 'io.bosca.bml'; id 'application' }
                group = 'example'; version = '1'
                application { mainClass = 'bosca.notifications.web.MainKt' }
                repositories { maven { url = uri(${quote(repository)}) }; mavenCentral() }
                kotlin { jvmToolchain(25) }
                dependencies {
                    implementation 'io.bosca:bml-design:$version'
                    $hostDependencies
                }
                bml {
                    packageName.set('bml.generated')
                    clientBundler.set(file(${quote(File(runtime, "tools/bundle.mjs"))}))
                    clientRuntime.set(file(${quote(File(runtime, "src/index.ts"))}))
                }
                def runtimeFiles = files(tasks.named('jar').flatMap { it.archiveFile }, configurations.runtimeClasspath)
                def classpathFile = layout.buildDirectory.file('acceptance-classpath.txt')
                def runtimeDirectory = layout.buildDirectory.dir('acceptance-runtime')
                tasks.register('acceptanceClasspath') {
                    dependsOn tasks.named('jar')
                    inputs.files(runtimeFiles); outputs.file(classpathFile); outputs.dir(runtimeDirectory)
                    doLast {
                        def directory = runtimeDirectory.get().asFile
                        directory.mkdirs()
                        classpathFile.get().asFile.text = runtimeFiles.files.collect { source ->
                            def target = new File(directory, source.name)
                            target.bytes = source.bytes
                            target.absolutePath
                        }.join(File.pathSeparator)
                    }
                }
            """.trimIndent())
            fun build(label: String, vararg tasks: String) = try {
                GradleRunner.create().withProjectDir(consumer).withPluginClasspath(plugins)
                    .withArguments(*tasks, "--configuration-cache", "--no-build-cache", "--max-workers=2", "--stacktrace")
                    .build().also { File(reports, "$label.log").writeText(it.output) }
            } catch (failure: UnexpectedBuildFailure) {
                File(reports, "$label.log").writeText(failure.buildResult.output)
                throw failure
            }
            build("first", "acceptanceClasspath")
            val binary = File(consumer, "build/libs/published-design-consumer-1.jar")
            val firstAssets = assets(binary)
            val cached = build("cached", "acceptanceClasspath")
            assertTrue("Reusing configuration cache" in cached.output, cached.output)
            assertEquals(TaskOutcome.UP_TO_DATE, cached.task(":compileKotlin")?.outcome)
            assertEquals(TaskOutcome.UP_TO_DATE, cached.task(":bmlBundleClient")?.outcome)
            build("clean", "clean", "acceptanceClasspath")
            assertEquals(firstAssets, assets(binary), "Clean and cached builds must package identical reachable assets")
            val classpath = File(consumer, "build/acceptance-classpath.txt").readText()
            assertTrue(classpath.split(File.pathSeparator).all { File(it).isFile && it.endsWith(".jar") && File(it).canonicalPath.startsWith(work.canonicalPath) }, classpath)
            assertEquals(1, classpath.split(File.pathSeparator).count { File(it).name == "bml-design-$version.jar" })
            assertFalse(File(consumer, "src/main").walkTopDown().any { it.isFile && "bosca.design.generated" in it.readText() })
            for (development in listOf(false, true)) verifyProduct(classpath, development, "published-$development", reports)

        } finally {
            Files.walk(work.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    internal fun verifyProduct(classpath: String, development: Boolean, mode: String, reports: File) {
        NotificationApiFixture().use { api ->
            val directory = createTempDirectory("notifications-product-").toFile()
            val log = File(reports, "$mode-server.log")
            val native = System.getProperty("adoption.native.binary")?.let { binary ->
                File(binary).copyTo(File(directory, File(binary).name)).also { assertTrue(it.setExecutable(true)) }
            }
            val command = if (native == null) listOf(System.getProperty("adoption.java"), "--enable-native-access=ALL-UNNAMED",
                "-Dbml.dev=$development", "-cp", classpath, "bosca.notifications.web.MainKt", "0")
                else listOf(native.absolutePath, "0")
            val process = ProcessBuilder(command)
                .directory(directory).apply {
                    environment()["BML_GRAPHQL_ENDPOINT"] = "${api.origin}/graphql"
                    redirectErrorStream(true); redirectOutput(log)
                }.start()
            try {
                val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
                var port: Int? = null
                while (process.isAlive && System.nanoTime() < deadline && port == null) {
                    port = Regex("Bosca server started on port ([0-9]+) with").find(log.readText())?.groupValues?.get(1)?.toInt()
                    if (port == null) Thread.sleep(25)
                }
                val base = "http://localhost:${requireNotNull(port) { log.readText() }}"
                val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(base)).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode(), response.body())
                verifyAssets(base, reports, mode, development)
                runProcess(listOf(System.getProperty("adoption.node"),
                    File(System.getProperty("adoption.project"), "src/test/browser/preferences.mjs").absolutePath,
                    base, api.origin, File(reports, mode).absolutePath), directory, File(reports, "$mode-browser.log"), 120)
                api.verify()
                assertTrue(process.isAlive, log.readText())
            } finally {
                process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                }
                Files.walk(directory.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun verifyAssets(base: String, reports: File, mode: String, development: Boolean) {
        val client = HttpClient.newHttpClient()
        fun get(url: URI) = client.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofByteArray()).also { assertEquals(200, it.statusCode(), url.toString()) }.body()
        val html = get(URI("$base/preferences?token=fixture-token")).decodeToString()
        val styles = Regex("""<link[^>]+href="([^"]+)"""").findAll(html)
            .map { URI(base).resolve(it.groupValues[1].replace("&amp;", "&")) }.toList()
        val fonts = styles.flatMap { style ->
            Regex("""url\(["']?([^)'"\s]+\.woff2)["']?\)""").findAll(get(style).decodeToString())
                .map { style.resolve(it.groupValues[1]) }.toList()
        }.distinct()
        assertEquals(11, fonts.size)
        val prefix = "bml/applications/bml.generated/"
        val manifest = requireNotNull(javaClass.classLoader.getResourceAsStream(prefix + "client-manifest.json"))
            .use { kotlinx.serialization.json.Json.decodeFromString(bosca.bml.library.BmlClientManifest.serializer(), it.readAllBytes().decodeToString()) }
        val expectedFonts = manifest.files.filter { it.path.endsWith(".woff2") }.associateBy { it.digest }
        assertEquals(11, expectedFonts.size)
        val servedDigests = linkedSetOf<String>()
        val digests = fonts.associate { font ->
            assertEquals(URI(base).authority, font.authority)
            assertTrue(font.path.startsWith(if (development) "/_bml/libraries/" else "/_bml/content/"), font.toString())
            val bytes = get(font)
            val actual = bosca.bml.project.ContentDigest.sha256(bytes)
            val path = prefix + expectedFonts.getValue(actual).path
            val expected = requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { path }.use { it.readAllBytes() }
            assertTrue(expected.contentEquals(bytes), font.toString())
            servedDigests += actual
            font.path to kotlinx.serialization.json.JsonPrimitive(actual)
        }
        assertEquals(expectedFonts.keys, servedDigests, "Every packaged font subset must be served")
        File(reports, "$mode-fonts.json").writeText(kotlinx.serialization.json.JsonObject(digests).toString())
        val modules = linkedSetOf<URI>()
        fun verifyModule(uri: URI) {
            if (!modules.add(uri)) return
            val js = get(uri).decodeToString()
            assertFalse("cm-editor" in js || "jse-main" in js, "Preferences must exclude unused editor engines")
            Regex("""(?:import|from)\s*["']([^"']+)["']""").findAll(js).forEach { match ->
                if (match.groupValues[1].startsWith(".")) verifyModule(uri.resolve(match.groupValues[1]))
            }
        }
        Regex("""<script[^>]+src="([^"]+)"""").findAll(html).forEach {
            verifyModule(URI(base).resolve(it.groupValues[1].replace("&amp;", "&")))
        }
    }

    private fun assets(binary: File): Map<String, String> = JarFile(binary).use { jar ->
        jar.entries().asSequence().filter { it.name.endsWith(".js") || it.name.endsWith(".css") || it.name.endsWith(".woff2") }
            .associate { it.name to bosca.bml.project.ContentDigest.sha256(jar.getInputStream(it).readBytes()) }
            .also { assertTrue(it.isNotEmpty()) }
    }

    private fun runProcess(command: List<String>, directory: File, log: File, seconds: Long) {
        val process = ProcessBuilder(command).directory(directory).redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue(process.waitFor(seconds, TimeUnit.SECONDS), "Process timed out: ${log.readText()}")
            assertEquals(0, process.exitValue(), log.readText())
        } finally {
            if (process.isAlive) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)) }
        }
    }

    private fun quote(file: File) = "'" + file.absolutePath.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun pom(group: String, name: String, version: String, dependencies: String = "") = """
        <project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$name</artifactId><version>$version</version>
        <dependencies>$dependencies</dependencies></project>
    """.trimIndent()
}
