package bosca.design.example

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the actual library artifact through Maven resolution in a separate application build. */
class PublishedDesignLibraryTest {
    @Test
    fun `real Maven design artifact compiles and runs without producer sources`() {
        val work = createTempDirectory("published-bosca-design-").toFile().canonicalFile
        val original = File(System.getProperty("design.fixture.project"))
        val reports = File(original, "build/reports/published-consumer").apply { mkdirs() }
        try {
            val repository = File(work, "repository")
            val artifacts = System.getProperty("design.fixture.artifacts").lineSequence().mapIndexed { index, line ->
                val artifact = line.split('\t')
                if (artifact[0] == "io.bosca") artifact else listOf("fixture.runtime", "artifact-$index", "1", "", artifact[4])
            }.toList()
            for ((group, name, version, classifier, path) in artifacts) {
                val directory = File(repository, "${group.replace('.', '/')}/$name/$version").apply { mkdirs() }
                File(path).copyTo(File(directory, "$name-$version${if (classifier.isEmpty()) "" else "-$classifier"}.jar"), overwrite = true)
                File(directory, "$name-$version.pom").writeText(pom(group, name, version))
            }
            val version = System.getProperty("design.fixture.version")
            val design = File(repository, "io/bosca/bml-design/$version")
            File(System.getProperty("design.fixture.pom")).copyTo(File(design, "bml-design-$version.pom"), overwrite = true)
            assertFalse(repository.walkTopDown().any { it.extension == "bml" || it.name.endsWith("-sources.jar") })

            val compiler = artifacts.single { it[0] == "io.bosca" && it[1] == "bml-compiler" }
            val compilerFiles = System.getProperty("design.fixture.compiler").split(File.pathSeparator)
            val compilerDependencies = artifacts.filter { it != compiler && it[4] in compilerFiles }.distinctBy { it.take(4) }.joinToString("\n") {
                "<dependency><groupId>${it[0]}</groupId><artifactId>${it[1]}</artifactId><version>${it[2]}</version>" +
                    (if (it[3].isEmpty()) "" else "<classifier>${it[3]}</classifier>") + "</dependency>"
            }
            File(repository, "io/bosca/bml-compiler/${compiler[2]}/bml-compiler-${compiler[2]}.pom")
                .writeText(pom(compiler[0], compiler[1], compiler[2], compilerDependencies))

            val plugins = System.getProperty("design.fixture.plugin").split(File.pathSeparator).mapIndexed { index, path ->
                File(path).copyTo(File(work, "plugins/$index-${File(path).name}").apply { parentFile.mkdirs() })
            }
            val runtimeSource = File(System.getProperty("design.fixture.runtime"))
            val runtime = File(work, "toolchain/bml-runtime").apply { mkdirs() }
            for (directory in listOf("src", "tools")) File(runtimeSource, directory).copyRecursively(File(runtime, directory))
            val installed = File(runtimeSource, "node_modules")
            installed.copyRecursively(File(runtime, "node_modules"))
            for (source in installed.walkTopDown().filter { it.isFile && it.canExecute() }) {
                assertTrue(File(runtime, "node_modules/${source.relativeTo(installed).path}").setExecutable(true, false), source.path)
            }

            val consumer = File(work, "consumer").apply { mkdirs() }
            File(original, "src/main").copyRecursively(File(consumer, "src/main"))
            File(original, "src/test/types").copyRecursively(File(consumer, "src/test/types"))
            File(consumer, "tsconfig.json").writeText(File(original, "tsconfig.json").readText()
                .replace("../../bml-runtime/src/index.ts", File(runtime, "src/index.ts").invariantSeparatorsPath))
            File(consumer, "settings.gradle").writeText("rootProject.name = 'published-design-consumer'\n")
            File(consumer, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx2g\n")
            val hostDependencies = artifacts.filter { it[4] in System.getProperty("design.fixture.classpath").split(File.pathSeparator) }
                .filterNot { it[0] == "io.bosca" && it[1] == "bml-design" }.distinctBy { it.take(4) }
                .joinToString("\n") { "implementation '${it[0]}:${it[1]}:${it[2]}${if (it[3].isEmpty()) "" else ":${it[3]}"}'" }
            File(consumer, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'org.jetbrains.kotlin.plugin.serialization'; id 'io.bosca.bml' }
                group = 'example'; version = '1'
                repositories { maven { url = uri(${quote(repository)}) }; mavenCentral() }
                kotlin { jvmToolchain(25) }
                dependencies {
                    implementation 'io.bosca:bml-design:$version'
                    $hostDependencies
                }
                bml {
                    packageName.set('bosca.design.example.generated')
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

            runProcess(listOf(System.getProperty("design.fixture.node"), System.getProperty("design.fixture.typechecker"),
                "--project", File(consumer, "tsconfig.json").absolutePath), consumer, File(reports, "types.log"), 30)
            for (development in listOf(false, true)) {
                val mode = if (development) "development" else "production"
                withServer(classpath, development, File(reports, "$mode-server.log")) { base ->
                    runProcess(listOf(System.getProperty("design.fixture.node"), System.getProperty("design.fixture.browser"),
                        base, "published-$mode"), consumer, File(reports, "$mode-browser.log"), 90)
                }
            }
            File(consumer, "build/reports/browser").copyRecursively(File(reports, "browser"), overwrite = true)
        } finally {
            Files.walk(work.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun assets(binary: File): Map<String, String> = JarFile(binary).use { jar ->
        jar.entries().asSequence().filter { it.name.endsWith(".js") || it.name.endsWith(".css") || it.name.endsWith(".woff2") }
            .associate { it.name to bosca.bml.project.ContentDigest.sha256(jar.getInputStream(it).readBytes()) }
            .also { assertTrue(it.isNotEmpty()) }
    }

    private fun withServer(classpath: String, development: Boolean, log: File, check: (String) -> Unit) {
        var base: String? = null
        val process = ProcessBuilder(System.getProperty("design.fixture.java"), "-Dbml.dev=$development", "-cp", classpath,
            "bosca.design.example.MainKt", "0").redirectErrorStream(true).redirectOutput(log).start()
        try {
            var boundPort: Int? = null
            for (attempt in 0 until 200) {
                assertTrue(process.isAlive, log.readText())
                boundPort = designBoundPort(log.readText())
                if (boundPort != null) break
                Thread.sleep(25)
            }
            val port = requireNotNull(boundPort) { "Published consumer did not report a successful bind: ${log.readText()}" }
            val endpoint = "http://localhost:$port"
            base = endpoint
            val response = requestDesignFixture(HttpClient.newHttpClient(),
                HttpRequest.newBuilder(URI(endpoint)).timeout(Duration.ofSeconds(10)).GET().build())
            assertEquals(200, response.statusCode(), response.body())
            check(endpoint)
        } catch (failure: Throwable) {
            val state = if (process.isAlive) "alive" else "exit=${process.exitValue()}"
            failure.addSuppressed(IllegalStateException("Published design development=$development at ${base ?: "unbound port"} " +
                "(pid=${process.pid()} $state): ${log.readText()}"))
            throw failure
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun runProcess(command: List<String>, directory: File, log: File, seconds: Long) {
        val process = ProcessBuilder(command).directory(directory).redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue(process.waitFor(seconds, TimeUnit.SECONDS), "Process timed out: ${log.readText()}")
            assertEquals(0, process.exitValue(), log.readText())
        } finally { process.destroyForcibly() }
    }

    private fun quote(file: File) = "'" + file.absolutePath.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun pom(group: String, name: String, version: String, dependencies: String = "") = """
        <project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$name</artifactId><version>$version</version>
        <dependencies>$dependencies</dependencies></project>
    """.trimIndent()
}
