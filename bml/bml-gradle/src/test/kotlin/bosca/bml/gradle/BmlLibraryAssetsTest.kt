package bosca.bml.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Publishes actual compiled BML/CSS/TypeScript, then uses the archive after removing its producer. */
class BmlLibraryAssetsTest {
    private val libraryScope = "bml-6578616d706c653a64657369676e"
    private val buttonId = "$libraryScope-button"
    private val fieldId = "$libraryScope-field"
    @Test
    fun `published browser assets retain inline and file dependencies without producer sources`() {
        val work = createTempDirectory("bml-library-assets").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val producer = File(work, "producer").apply { mkdirs() }
            val markup = File(work, "shared-markup").apply { mkdirs() }
            Files.createSymbolicLink(File(producer, "markup").toPath(), markup.toPath())
            val publicAssets = File(work, "shared-public").apply { mkdirs() }
            File(publicAssets, "logo.svg").writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"><text>logo</text></svg>")
            File(publicAssets, "fonts/design.woff2").apply { parentFile.mkdirs(); writeText("linked font fixture") }
            File(producer, "public").mkdirs()
            Files.createSymbolicLink(File(producer, "public/logo.svg").toPath(), File(publicAssets, "logo.svg").toPath())
            Files.createSymbolicLink(File(producer, "public/fonts").toPath(), File(publicAssets, "fonts").toPath())
            val shared = File(work, "packages/shared").apply { mkdirs() }
            File(shared, "package.json").writeText("""{"name":"@design/shared","main":"index.js","browser":{"./server.js":false,"./server.css":false}}""")
            File(shared, "server.js").writeText("throw new Error('disabled-server-code');")
            File(shared, "server.css").writeText(".disabled-server-style { color: blue; }")
            val sharedScript = File(shared, "index.js").apply { writeText("import './server.js'; export const value = '-workspace-first';") }
            val sharedStyle = File(shared, "theme.css").apply { writeText("@import './server.css'; @media (min-width: 600px) { .workspace { color: #abcdef; } }") }
            val installed = File(work, "node_modules/@design/shared")
            installed.parentFile.mkdirs()
            Files.createSymbolicLink(installed.toPath(), shared.toPath())
            Files.createSymbolicLink(File(installed.parentFile, "producer").toPath(), producer.toPath())
            val bundler = File("../bml-runtime/tools/bundle.mjs").canonicalFile
            fun source(path: String, body: String) = File(producer, path).apply { parentFile.mkdirs(); writeText(body) }
            source("settings.gradle", "rootProject.name = 'design'")
            source("gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            val script = source("build.gradle", """
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'maven-publish' }
                group = 'example'
                version = '1.2.3'
                ${fixture.shared}
                layout.buildDirectory.set(layout.projectDirectory.dir('out'))
                bml {
                    sourceDir.set(layout.projectDirectory.dir('markup'))
                    packageName.set('example.design.generated')
                    library { exports.set(['button']) }
                    clientBundler.set(file(${quote(bundler)}))
                }
                publishing {
                    publications { maven(MavenPublication) { from components.java } }
                    repositories { maven { name = 'fixture'; url = uri(${quote(fixture.repository)}) } }
                }
            """.trimIndent())
            // A Kotlin source triggers main compilation in the standalone fixture.
            source("src/main/kotlin/example/Marker.kt", "package example\nobject Marker")
            source("markup/controls/button.bml", """
                <component tag="button">
                  <html:button class="button"><slot/></html:button>
                  <style scoped>@media (min-width: 600px) { .responsive { color: red; } }</style>
                  <style scoped>@import '@design/shared/theme.css';</style>
                  <style scoped>@import 'data:text/css,.embedded%7Bcolor:green%7D';</style>
                  <style scoped>@import './styles/theme.css'; .button { background-image: url('./icon.svg'); }</style>
                  <style>.plain { background-image: url('/logo.svg'); }</style>
                  <script client scoped>
                    import { label } from './button'
                    import dataSuffix from 'data:text/javascript,export default %22-data%22'
                    ctx.root.setAttribute('data-label', label + dataSuffix)
                  </script>
                </component>
            """.trimIndent())
            source("markup/controls/button.ts", "import { suffix } from './nested/label'; import { extra } from '../../producer/support'; import { value } from '@design/shared'; export const label = 'published-' + suffix + extra + value;")
            source("support.ts", "export const extra = '';")
            val nested = source("markup/controls/nested/label.ts", "export const suffix = 'first';")
            val theme = source("markup/controls/styles/theme.css", "@font-face { font-family: Design; src: url('./design.woff2'); } .theme { color: #123456; }")
            source("markup/controls/styles/design.woff2", "font fixture")
            source("markup/controls/icon.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>icon</text></svg>")
            source("src/main/client/tokens.css", "@import './nested/colors.css';")
            source("src/main/client/tokens.ts", "import './nested/reset.css'; globalThis.tokensStarted = true;")
            source("src/main/client/nested/reset.css", ".reset { margin: 0; }")
            source("src/main/client/nested/colors.css", ":root { --design-accent: #112233; }")
            val extra = source("src/main/client/start.ts", "globalThis.libraryStarted = 'global-script';")
            val result = runner(producer, "publishMavenPublicationToFixtureRepository").build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":bmlBundleLibraryStyles")?.outcome)
            val published = File(fixture.repository, "example/design/1.2.3/design-1.2.3.jar")
            val sources = File(published.parentFile, "design-1.2.3-sources.jar")
            val initial = inspect(published, sources)
            val prefix = initial["resourcePrefix"] as String
            JarFile(published).use { jar ->
                val js = text(jar, "$prefix/modules/ControlsButtonPage.js")
                assertTrue("published-" in js && "first" in js && "@bosca/bml" in js, js)
                assertTrue("-workspace-first" in js, js)
                assertFalse("disabled-server-code" in js, js)
                assertTrue("logo" in text(jar, "$prefix/public/logo.svg"))
                assertEquals("linked font fixture", text(jar, "$prefix/public/fonts/design.woff2"))
                assertTrue("--design-accent" in text(jar, "$prefix/styles/tokens.css"))
                assertTrue(".reset" in text(jar, "$prefix/client/tokens.css"))
                assertTrue((initial["globalStyles"] as List<*>).containsAll(listOf("styles/tokens.css", "client/tokens.css")))
                val manifest = text(jar, "META-INF/bml/library-manifest.json")
                assertTrue("#123456" in manifest && "/_bml/libraries/" in manifest, manifest)
                assertTrue(".theme[data-bml-c=\\\"$buttonId\\\"]" in manifest, manifest)
                val css = ((JsonSlurper().parseText(manifest) as Map<*, *>)["components"] as List<*>)
                    .map { (it as Map<*, *>)["styles"] as String }.joinToString("\n")
                assertTrue("@media (min-width: 600px) {" in css && "@font-face {" in css, css)
                assertTrue(".responsive[data-bml-c=\"$buttonId\"]" in css && ".workspace[data-bml-c=\"$buttonId\"]" in css, css)
                assertTrue(".embedded[data-bml-c=\"$buttonId\"]" in css, css)
                assertFalse("@font-face[" in css || "600px)[" in css, css)
                assertFalse(".disabled-server-style" in css, css)
                assertFalse(jar.entries().asSequence().any { it.name.startsWith("bml/public/") })
            }
            assertFalse((initial["sources"] as List<*>).any { "packages/shared" in (it as Map<*, *>)["path"].toString() })
            val reused = runner(producer, "publishMavenPublicationToFixtureRepository").build()
            assertTrue("Reusing configuration cache" in reused.output, reused.output)
            assertEquals(TaskOutcome.UP_TO_DATE, reused.task(":bmlBundleClient")?.outcome)
            sharedScript.writeText("import './server.js'; export const value = '-workspace-changed';")
            val linkedScriptChange = runner(producer, "publishMavenPublicationToFixtureRepository").build()
            assertTrue("Reusing configuration cache" in linkedScriptChange.output, linkedScriptChange.output)
            assertEquals(TaskOutcome.SUCCESS, linkedScriptChange.task(":bmlBundleClient")?.outcome)
            JarFile(published).use { jar -> assertTrue("-workspace-changed" in text(jar, "$prefix/modules/ControlsButtonPage.js")) }
            sharedStyle.writeText(sharedStyle.readText().replace("#abcdef", "#fedcba"))
            val linkedStyleChange = runner(producer, "publishMavenPublicationToFixtureRepository").build()
            assertTrue("Reusing configuration cache" in linkedStyleChange.output, linkedStyleChange.output)
            assertEquals(TaskOutcome.SUCCESS, linkedStyleChange.task(":bmlBundleLibraryStyles")?.outcome)
            JarFile(published).use { jar -> assertTrue("#fedcba" in text(jar, "META-INF/bml/library-manifest.json")) }
            nested.writeText("export const suffix = 'changed';")
            theme.writeText(theme.readText().replace("#123456", "#654321"))
            extra.delete()
            val changed = runner(producer, "publishMavenPublicationToFixtureRepository").build()
            assertTrue("Reusing configuration cache" in changed.output, changed.output)
            inspect(published, sources)
            JarFile(published).use { jar ->
                assertTrue("changed" in text(jar, "$prefix/modules/ControlsButtonPage.js"))
                assertFalse(jar.entries().asSequence().any { it.name.endsWith("client/start.js") })
                assertTrue("#654321" in text(jar, "META-INF/bml/library-manifest.json"))
            }
            val originalScript = script.readText()
            for ((exclusion, expected) in listOf(
                "tasks.named('jar') { exclude('**/modules/*.js') }" to "Binary archive is missing library asset",
                "tasks.named('jar') { exclude('**/assets/*.woff2') }" to "Binary archive is missing library asset",
                "tasks.named('jar') { exclude('**/public/fonts/*.woff2') }" to "Binary archive is missing library asset",
                "tasks.withType(Jar).configureEach { if (name == 'sourcesJar') exclude('**/button.ts') }" to "Sources archive is missing library asset",
            )) {
                script.writeText(originalScript + "\n" + exclusion)
                val rejected = runner(producer, "publishMavenPublicationToFixtureRepository").buildAndFail()
                assertTrue(expected in rejected.output, rejected.output)
            }
            script.writeText(originalScript)
            runner(producer, "clean", "publishMavenPublicationToFixtureRepository").build()
            inspect(published, sources)
            producer.deleteRecursively()
            shared.deleteRecursively()
            markup.deleteRecursively()
            publicAssets.deleteRecursively()
            // Rebundle only archived ESM with the consumer runtime. No producer tree or npm library copy exists.
            val consumer = File(work, "consumer").apply { mkdirs() }
            File(consumer, "settings.gradle").writeText("rootProject.name = 'consumer'")
            File(consumer, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            File(consumer, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'application' }
                ${fixture.shared}
                dependencies { implementation 'example:design:1.2.3' }
                application { mainClass = 'consumer.MainKt' }
            """.trimIndent())
            File(consumer, "src/main/kotlin/consumer/Main.kt").apply {
                parentFile.mkdirs()
                writeText("""
                    package consumer
                    fun main() = kotlinx.coroutines.runBlocking {
                        val context = bosca.bml.render.RenderContext()
                        example.design.generated.ButtonComponent.render(context, emptyMap()) { context.writer.text("Published button") }
                        val html = context.toString()
                        check("Published button" in html && "/_bml/libraries/" in html && ".plain" in html)
                        check("url('/logo.svg')" !in html && "@import" !in html)
                        println("Published styles rendered")
                    }
                """.trimIndent())
            }
            assertTrue("Published styles rendered" in runner(consumer, "run").build().output)
            JarFile(published).use { jar ->
                jar.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("$prefix/") }.forEach { entry ->
                    File(consumer, entry.name.removePrefix("$prefix/")).apply {
                        parentFile.mkdirs(); writeBytes(jar.getInputStream(entry).use { it.readBytes() })
                    }
                }
            }
            File(consumer, "runtime.ts").writeText("export function defineComponent(tag, setup) { globalThis.tag = tag; setup({root:{setAttribute: (key,value) => globalThis.label = value}}) }; export function mountAll() {}; export function defineIsland() {}; export function renderFragment() {};")
            val verify = File(consumer, "verify.mjs").apply { writeText("""
                import { bundleIslands } from ${jsString(bundler.toURI().toString())};
                import { readFileSync } from 'node:fs';
                await bundleIslands({entryPoints:[new URL('./modules/ControlsButtonPage.js', import.meta.url).pathname], outdir:new URL('./out', import.meta.url).pathname, runtime:new URL('./runtime.ts', import.meta.url).pathname});
                const js = readFileSync(new URL('./out/ControlsButtonPage.js', import.meta.url), 'utf8');
                new Function(js)();
                if (globalThis.tag !== '$buttonId' || globalThis.label !== 'published-changed-workspace-changed-data') throw new Error('Published client behavior was lost');
            """.trimIndent()) }
            val process = ProcessBuilder("node", verify.absolutePath).directory(consumer).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
        } finally { work.deleteRecursively() }
    }

    @Test
    fun `nested npm dependencies bundle imported assets without publishing their source trees`() {
        val work = createTempDirectory("bml-nested-npm").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val producer = File(work, "producer").apply { mkdirs() }
            fun source(path: String, body: String) = File(producer, path).apply { parentFile.mkdirs(); writeText(body) }
            fun dependency(path: String, name: String, label: String): Pair<File, File> {
                source("$path/package.json", """{"name":"$name","exports":{".":"./index.js","./theme.css":"./theme.css"}}""")
                val script = source("$path/index.js", "import './theme.css'; export const label = '$label';")
                val style = source("$path/theme.css", ".token { color: #123456; background: url('./icon.svg'); }")
                source("$path/icon.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>$label</text></svg>")
                source("$path/server-tool.js", "import { readFileSync } from 'node:fs'; console.log(readFileSync('package.json'));")
                source("$path/unused.css", ".unused { background: url('./not-shipped.png'); }")
                source("$path/internal-source.ts", "export const internal = 'private-source-sentinel';")
                return script to style
            }
            val (_, bmlStyle) = dependency("src/main/bml/node_modules/@design/bml", "@design/bml", "bml-before")
            val (clientScript, _) = dependency("src/main/client/nested/node_modules/@design/client", "@design/client", "client-before")
            val unusedSources = listOf("src/main/bml/node_modules/@design/bml" to "Direct",
                "src/main/bml/controls/node_modules/@design/example" to "Nested").flatMap { (path, name) ->
                listOf(
                    source("$path/android/${name}NativeFeature.kt", "package dependency.nativecode\nobject ${name}NativeFeature"),
                    source("$path/build.gradle.kts", "this dependency build script is not a producer source"),
                    source("$path/examples/demo.bml", """
                        <component tag="dependency-${name.lowercase()}">
                          <style scoped>@import './not-shipped.css';</style>
                          <span>Unused dependency fixture</span>
                        </component>
                    """.trimIndent()),
                )
            }
            source("public/node_modules/private/internal-source.ts", "export const internal = 'private-public-source';")
            source("public/theme.css", ".public-theme { color: red; }")
            source("settings.gradle", "rootProject.name = 'design'")
            source("gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            val bundler = File("../bml-runtime/tools/bundle.mjs").canonicalFile
            source("build.gradle", """
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'maven-publish' }
                group = 'example'; version = '1.2.3'
                ${fixture.shared}
                bml {
                    packageName.set('example.design.generated')
                    library { exports.set(['button']) }
                    clientBundler.set(file(${quote(bundler)}))
                }
                publishing {
                    publications { maven(MavenPublication) { from components.java } }
                    repositories { maven { name = 'fixture'; url = uri(${quote(fixture.repository)}) } }
                }
            """.trimIndent())
            source("src/main/kotlin/Marker.kt", "object Marker")
            source("src/main/bml/AuthoredModel.kt", "package example.design\nobject AuthoredModel { val label = \"Save\" }")
            source("src/main/bml/button.bml", """
                <component tag="button">
                  <html:button>{ example.design.AuthoredModel.label }</html:button>
                  <style scoped>@import '@design/bml/theme.css';</style>
                  <script client scoped>
                    import { label } from '@design/bml';
                    ctx.root.setAttribute('data-label', label);
                  </script>
                </component>
            """.trimIndent())
            source("src/main/client/start.ts", "import { label } from './nested/helper'; globalThis.libraryLabel = label;")
            source("src/main/client/nested/helper.ts", "export { label } from '@design/client';")
            val binary = File(fixture.repository, "example/design/1.2.3/design-1.2.3.jar")
            val sources = File(binary.parentFile, "design-1.2.3-sources.jar")
            fun publish(label: String, color: String) = runner(producer, "publishMavenPublicationToFixtureRepository").build().also {
                val manifest = inspect(binary, sources)
                val paths = (manifest["sources"] as List<*>).map { source -> (source as Map<*, *>)["path"] }
                assertTrue(paths.containsAll(listOf("bml/button.bml", "client/start.ts", "client/nested/helper.ts", "public/theme.css")))
                JarFile(sources).use { jar ->
                    assertTrue(jar.getJarEntry("AuthoredModel.kt") != null)
                    assertEquals(null, jar.getJarEntry("bml/AuthoredModel.kt"), "Kotlin beside BML is archived once")
                }
                for (archive in listOf(binary, sources)) JarFile(archive).use { jar ->
                    assertFalse(jar.entries().asSequence().any { entry -> "node_modules" in entry.name.split('/') }, archive.path)
                }
                JarFile(binary).use { jar ->
                    assertTrue(jar.getJarEntry("example/design/AuthoredModel.class") != null)
                    assertFalse(jar.entries().asSequence().any { entry -> entry.name.startsWith("dependency/nativecode/") })
                    val components = JsonSlurper().parseText(text(jar, "META-INF/bml/library-manifest.json")) as Map<*, *>
                    assertEquals(listOf("button"), (components["components"] as List<*>).map { component -> (component as Map<*, *>)["tag"] })
                    val prefix = manifest["resourcePrefix"] as String
                    val js = jar.entries().asSequence().filter { entry -> entry.name.startsWith(prefix) && entry.name.endsWith(".js") }
                        .joinToString("\n") { entry -> text(jar, entry.name) }
                    assertTrue(label in js && "bml-before" in js, js)
                    assertFalse("private-source-sentinel" in js, js)
                    assertTrue(color in text(jar, "META-INF/bml/library-manifest.json"))
                    assertTrue(jar.entries().asSequence().any { entry -> entry.name.startsWith("$prefix/assets/") && entry.name.endsWith(".svg") })
                }
            }
            publish("client-before", "#123456")
            unusedSources.forEach { it.appendText("\n// unused dependency edit") }
            val unusedChange = publish("client-before", "#123456")
            assertTrue("Reusing configuration cache" in unusedChange.output, unusedChange.output)
            assertEquals(TaskOutcome.UP_TO_DATE, unusedChange.task(":bmlExtractLibraryStyles")?.outcome)
            assertEquals(TaskOutcome.UP_TO_DATE, unusedChange.task(":sourcesJar")?.outcome)
            clientScript.writeText(clientScript.readText().replace("client-before", "client-after"))
            bmlStyle.writeText(bmlStyle.readText().replace("#123456", "#654321"))
            val changed = publish("client-after", "#654321")
            assertTrue("Reusing configuration cache" in changed.output, changed.output)
            assertEquals(TaskOutcome.SUCCESS, changed.task(":bmlBundleClient")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, changed.task(":bmlBundleLibraryStyles")?.outcome)
            val unchanged = publish("client-after", "#654321")
            assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":bmlBundleClient")?.outcome)
            assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":bmlBundleLibraryStyles")?.outcome)
            val metadata = inspect(binary, sources)
            producer.deleteRecursively()
            val consumer = File(work, "consumer").apply { mkdirs() }
            JarFile(binary).use { jar ->
                val prefix = "${metadata["resourcePrefix"]}/"
                for (entry in jar.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(prefix) }) {
                    File(consumer, entry.name.removePrefix(prefix)).apply {
                        parentFile.mkdirs(); writeBytes(jar.getInputStream(entry).use { it.readBytes() })
                    }
                }
            }
            File(consumer, "entry.ts").writeText("import './modules/ButtonPage.js'; import './client/start.js';")
            File(consumer, "runtime.ts").writeText("export function defineComponent(tag, setup) { setup({root:{setAttribute: (_, value) => globalThis.bmlLabel = value}}); } export function mountAll() {}")
            File(consumer, "verify.mjs").writeText("""
                import { bundleIslands } from ${jsString(bundler.toURI().toString())};
                import { readFileSync } from 'node:fs';
                await bundleIslands({entryPoints:[new URL('./entry.ts', import.meta.url).pathname], outdir:new URL('./out', import.meta.url).pathname, runtime:new URL('./runtime.ts', import.meta.url).pathname});
                new Function(readFileSync(new URL('./out/entry.js', import.meta.url), 'utf8'))();
                if (globalThis.bmlLabel !== 'bml-before' || globalThis.libraryLabel !== 'client-after') throw new Error('Imported dependency behavior was lost');
            """.trimIndent())
            val process = ProcessBuilder("node", File(consumer, "verify.mjs").absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
        } finally { work.deleteRecursively() }
    }

    @Test
    fun `dependencies beside a symlinked source tree invalidate published code and styles`() {
        verifyLinkedDependencies(DependencyLayout.SOURCE_ROOT)
    }

    @Test
    fun `dependencies beside a nested source link invalidate published code and styles`() {
        verifyLinkedDependencies(DependencyLayout.SOURCE_DIRECTORY)
    }

    @Test
    fun `ancestor dependencies of linked npm packages invalidate published code and styles`() {
        verifyLinkedDependencies(DependencyLayout.NPM_PACKAGE)
    }

    private enum class DependencyLayout { SOURCE_ROOT, SOURCE_DIRECTORY, NPM_PACKAGE }

    @Test
    fun `shared resolver configurations invalidate an ordinary subproject publication`() {
        verifyResolverConfigurations(false)
    }

    @Test
    fun `canonical resolver configurations and package config inheritance invalidate publication`() {
        verifyResolverConfigurations(true)
    }

    private fun verifyResolverConfigurations(linked: Boolean) {
        val work = createTempDirectory("bml-library-resolver-config").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val producer = File(work, "workspace/design").apply { mkdirs() }
            val ancestor = if (linked) File(work, "shared").apply { mkdirs() } else producer.parentFile
            val markup = File(if (linked) ancestor else producer, "markup").apply { mkdirs() }
            if (linked) Files.createSymbolicLink(File(producer, "markup").toPath(), markup.toPath())
            for (label in listOf("before", "after")) {
                val pkg = File(ancestor, "node_modules/design-$label").apply { mkdirs() }
                File(pkg, "package.json").writeText("""{"name":"design-$label","main":"index.js"}""")
                File(pkg, "index.js").writeText("export const label = '$label';")
            }
            val packageConfig = File(ancestor, "package.json")
            fun packageMapping(label: String) = packageConfig.writeText("""{"imports":{"#token":"design-$label"}}""")
            fun paths(label: String) = """{"compilerOptions":{"paths":{"@theme":[${jsString(File(ancestor, "node_modules/design-$label/index.js").absolutePath)}]}}}"""
            val base = File(work, "config/base.json").apply { parentFile.mkdirs(); writeText(paths("before")) }
            val extends = if (linked) {
                val pkg = File(ancestor, "node_modules/@design/tsconfig").apply { mkdirs() }
                File(pkg, "package.json").writeText("""{"name":"@design/tsconfig","exports":{"./*":{"require":"./config/*"}}}""")
                File(pkg, "config/shared").apply {
                    parentFile.mkdirs(); writeText("""{"extends":${jsString(base.absolutePath)}}""")
                }
                "@design/tsconfig/shared"
            } else "../config/base"
            File(ancestor, "jsconfig.json").writeText("""
                {
                  // Inherited JSONC must be tracked outside both the producer and its source root.
                  "extends": [${jsString(extends)},],
                  "compilerOptions": { "useDefineForClassFields": false },
                }
            """.trimIndent())
            if (linked) File(producer, "tsconfig.json").writeText("""{"compilerOptions":{"useDefineForClassFields":false}}""")
            packageMapping("before")
            val fields = File(markup, "fields").apply { mkdirs() }
            val fieldConfig = File(fields, "tsconfig.json").apply {
                writeText("""{"compilerOptions":{"useDefineForClassFields":true}}""")
            }
            val classFields = """
                class Base {}
                Object.defineProperty(Base.prototype, 'label', {
                    set(value) { this.stored = value; }, get() { return 'setter:' + this.stored; },
                });
                class Label extends Base { label = 'raw'; }
            """.trimIndent()
            File(fields, "helper.ts").writeText("$classFields\nexport const label = new Label().label;")
            File(fields, "field.bml").writeText("""
                <component tag="field">
                  <html:span/>
                  <script client scoped>
                    import { label } from './helper';
                    $classFields
                    ctx.root.setAttribute('data-label', new Label().label + '/' + label);
                  </script>
                </component>
            """.trimIndent())
            File(markup, "helper.ts").writeText("""
                import { label as token } from '#token';
                import { label as theme } from '@theme';
                export const label = token + '/' + theme;
            """.trimIndent())
            File(markup, "button.bml").writeText("""
                <component tag="button">
                  <html:button/>
                  <style scoped>.button { color: #123456; }</style>
                  <script client scoped>
                    import { label } from './helper';
                    ctx.root.setAttribute('data-label', label);
                  </script>
                </component>
            """.trimIndent())
            File(producer, "src/main/kotlin/Marker.kt").apply { parentFile.mkdirs(); writeText("object Marker") }
            File(producer, "settings.gradle").writeText("rootProject.name = 'design'")
            File(producer, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            File(producer, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'maven-publish' }
                group = 'example'; version = '1.2.3'
                ${fixture.shared}
                bml {
                    sourceDir.set(layout.projectDirectory.dir('markup'))
                    packageName.set('example.design.generated')
                    library { exports.set(['button', 'field']) }
                    clientBundler.set(file(${quote(File("../bml-runtime/tools/bundle.mjs").canonicalFile)}))
                }
                publishing {
                    publications { maven(MavenPublication) { from components.java } }
                    repositories { maven { name = 'fixture'; url = uri(${quote(fixture.repository)}) } }
                }
            """.trimIndent())
            val binary = File(fixture.repository, "example/design/1.2.3/design-1.2.3.jar")
            val sources = File(binary.parentFile, "design-1.2.3-sources.jar")
            fun publish(token: String, theme: String, field: String = "raw") = runner(producer, "publishMavenPublicationToFixtureRepository").build().also {
                val metadata = inspect(binary, sources)
                val consumer = File(work, "consumer").apply { mkdirs() }
                JarFile(binary).use { jar ->
                    val prefix = "${metadata["resourcePrefix"]}/"
                    for (entry in jar.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(prefix) }) {
                        File(consumer, entry.name.removePrefix(prefix)).apply {
                            parentFile.mkdirs(); writeBytes(jar.getInputStream(entry).use { input -> input.readBytes() })
                        }
                    }
                }
                File(consumer, "entry.ts").writeText("import './modules/ButtonPage.js'; import './modules/FieldsFieldPage.js';")
                File(consumer, "runtime.ts").writeText("export function defineComponent(tag, setup) { setup({root:{setAttribute: (_, value) => (globalThis.labels ??= {})[tag] = value}}); } export function mountAll() {}")
                File(consumer, "verify.mjs").writeText("""
                    import { bundleIslands } from ${jsString(File("../bml-runtime/tools/bundle.mjs").canonicalFile.toURI().toString())};
                    import { readFileSync } from 'node:fs';
                    await bundleIslands({entryPoints:[new URL('./entry.ts', import.meta.url).pathname], outdir:new URL('./out', import.meta.url).pathname, runtime:new URL('./runtime.ts', import.meta.url).pathname});
                    new Function(readFileSync(new URL('./out/entry.js', import.meta.url), 'utf8'))();
                    if (globalThis.labels['$buttonId'] !== '$token/$theme') throw new Error(globalThis.labels['$buttonId']);
                    if (globalThis.labels['$fieldId'] !== '$field/$field') throw new Error(globalThis.labels['$fieldId']);
                """.trimIndent())
                val process = ProcessBuilder("node", File(consumer, "verify.mjs").absolutePath).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor(), output)
            }
            publish("before", "before")
            packageMapping("after")
            val remapped = publish("after", "before")
            assertTrue("Reusing configuration cache" in remapped.output, remapped.output)
            assertEquals(TaskOutcome.SUCCESS, remapped.task(":bmlBundleClient")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, remapped.task(":bmlBundleLibraryStyles")?.outcome)
            base.writeText(paths("after"))
            val inherited = publish("after", "after")
            assertTrue("Reusing configuration cache" in inherited.output, inherited.output)
            assertEquals(TaskOutcome.SUCCESS, inherited.task(":bmlBundleClient")?.outcome)
            val tsconfig = File(ancestor, "tsconfig.json")
            tsconfig.writeText(paths("before"))
            assertEquals(TaskOutcome.SUCCESS, publish("after", "before").task(":bmlBundleClient")?.outcome)
            tsconfig.delete()
            assertEquals(TaskOutcome.SUCCESS, publish("after", "after").task(":bmlBundleClient")?.outcome)
            fieldConfig.writeText("""{"compilerOptions":{"useDefineForClassFields":false}}""")
            val transformed = publish("after", "after", "setter:raw")
            assertTrue("Reusing configuration cache" in transformed.output, transformed.output)
            assertEquals(TaskOutcome.SUCCESS, transformed.task(":bmlBundleClient")?.outcome)
            val stable = publish("after", "after", "setter:raw")
            assertTrue("Reusing configuration cache" in stable.output, stable.output)
            assertEquals(TaskOutcome.UP_TO_DATE, stable.task(":bmlBundleLibraryStyles")?.outcome)
            assertEquals(TaskOutcome.UP_TO_DATE, stable.task(":bmlBundleClient")?.outcome)
        } finally { work.deleteRecursively() }
    }

    private fun verifyLinkedDependencies(layout: DependencyLayout) {
        val work = createTempDirectory("bml-library-linked-dependencies").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val producer = File(work, "producer").apply { mkdirs() }
            val shared = File(work, "shared")
            val linkedPath = when (layout) {
                DependencyLayout.SOURCE_ROOT -> "src/main/bml"
                DependencyLayout.SOURCE_DIRECTORY -> "controls"
                DependencyLayout.NPM_PACKAGE -> "packages/shared"
            }
            val linkedDirectory = File(shared, linkedPath).apply { mkdirs() }
            val link = File(producer, when (layout) {
                DependencyLayout.SOURCE_ROOT -> "markup"
                DependencyLayout.SOURCE_DIRECTORY -> "markup/controls"
                DependencyLayout.NPM_PACKAGE -> "node_modules/@design/shared"
            }).apply { parentFile.mkdirs() }
            val sourceLink = link.toPath()
            Files.createSymbolicLink(sourceLink, linkedDirectory.toPath())
            val markup = if (layout == DependencyLayout.NPM_PACKAGE) File(producer, "markup").apply { mkdirs() } else linkedDirectory
            val dependency = File(shared, "node_modules/design-token").apply { mkdirs() }
            File(dependency, "package.json").writeText("""{"name":"design-token","main":"index.js"}""")
            val script = File(dependency, "index.js").apply { writeText("export const label = 'dependency-before';") }
            val style = File(dependency, "theme.css").apply { writeText(".button { color: #123456; }") }
            val importedPackage = if (layout == DependencyLayout.NPM_PACKAGE) "@design/shared" else "design-token"
            File(markup, "helper.ts").writeText("export { label } from '$importedPackage';")
            val shadow = File(producer, "node_modules/design-token").apply { mkdirs() }
            File(shadow, "package.json").writeText("""{"name":"design-token","main":"index.js"}""")
            File(shadow, "index.js").writeText("export const label = 'wrong-producer-package';")
            File(shadow, "theme.css").writeText(".button { color: #111111; }")
            if (layout == DependencyLayout.NPM_PACKAGE) {
                File(linkedDirectory, "package.json").writeText("""{"name":"@design/shared","main":"index.js"}""")
                File(linkedDirectory, "index.js").writeText("export { label } from 'design-token';")
                File(linkedDirectory, "theme.css").writeText("@import 'design-token/theme.css';")
            }
            File(markup, "button.bml").writeText("""
                <component tag="button">
                  <html:button/>
                  <style scoped>@import '$importedPackage/theme.css';</style>
                  <script client scoped>
                    import { label } from './helper'
                    ctx.root.setAttribute('data-label', label)
                  </script>
                </component>
            """.trimIndent())
            File(producer, "src/main/kotlin/Marker.kt").apply { parentFile.mkdirs(); writeText("object Marker") }
            File(producer, "settings.gradle").writeText("rootProject.name = 'design'")
            File(producer, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            File(producer, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'maven-publish' }
                group = 'example'; version = '1.2.3'
                ${fixture.shared}
                bml {
                    sourceDir.set(layout.projectDirectory.dir('markup'))
                    packageName.set('example.design.generated')
                    library { exports.set(['button']) }
                    clientBundler.set(file(${quote(File("../bml-runtime/tools/bundle.mjs").canonicalFile)}))
                }
                publishing {
                    publications { maven(MavenPublication) { from components.java } }
                    repositories { maven { name = 'fixture'; url = uri(${quote(fixture.repository)}) } }
                }
            """.trimIndent())
            val publication = "publishMavenPublicationToFixtureRepository"
            val binary = File(fixture.repository, "example/design/1.2.3/design-1.2.3.jar")
            val sources = File(binary.parentFile, "design-1.2.3-sources.jar")
            val module = if (layout == DependencyLayout.SOURCE_DIRECTORY) "ControlsButtonPage.js" else "ButtonPage.js"
            fun assertPublished(label: String, color: String) {
                val metadata = inspect(binary, sources)
                JarFile(binary).use { jar ->
                    assertTrue(label in text(jar, "${metadata["resourcePrefix"]}/modules/$module"))
                    assertTrue(color in text(jar, "META-INF/bml/library-manifest.json"))
                }
            }
            // Both targets are installed from the start. Retargeting must invalidate resolution even
            // when the dependency-directory set and every package's file contents remain unchanged.
            val replacement = File(work, "replacement")
            shared.copyRecursively(replacement)
            File(replacement, "node_modules/design-token/index.js").writeText("export const label = 'dependency-retargeted';")
            File(replacement, "node_modules/design-token/theme.css").writeText(".button { color: #abcdef; }")
            val installed = File(producer, "node_modules/@design").apply { mkdirs() }
            Files.createSymbolicLink(File(installed, "original").toPath(), linkedDirectory.toPath())
            Files.createSymbolicLink(File(installed, "alternate").toPath(), File(replacement, linkedPath).toPath())
            runner(producer, publication).build()
            assertPublished("dependency-before", "#123456")
            val unchanged = runner(producer, publication).build()
            assertTrue("Reusing configuration cache" in unchanged.output, unchanged.output)
            assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":bmlBundleLibraryStyles")?.outcome)
            assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":bmlBundleClient")?.outcome)

            script.writeText("export const label = 'dependency-after';")
            val changedScript = runner(producer, publication).build()
            assertTrue("Reusing configuration cache" in changedScript.output, changedScript.output)
            assertEquals(TaskOutcome.SUCCESS, changedScript.task(":bmlBundleClient")?.outcome)
            assertPublished("dependency-after", "#123456")

            style.writeText(".button { color: #654321; }")
            val changedStyle = runner(producer, publication).build()
            assertTrue("Reusing configuration cache" in changedStyle.output, changedStyle.output)
            assertEquals(TaskOutcome.SUCCESS, changedStyle.task(":bmlBundleLibraryStyles")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, changedStyle.task(":compileKotlin")?.outcome)
            assertPublished("dependency-after", "#654321")

            // Retargeting the link leaves the authored sources identical but changes npm resolution.
            Files.delete(sourceLink)
            Files.createSymbolicLink(sourceLink, File(replacement, linkedPath).toPath())
            val retargeted = runner(producer, publication).build()
            assertEquals(TaskOutcome.SUCCESS, retargeted.task(":bmlBundleLibraryStyles")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, retargeted.task(":bmlBundleClient")?.outcome)
            assertPublished("dependency-retargeted", "#abcdef")
            val stable = runner(producer, publication).build()
            assertTrue("Reusing configuration cache" in stable.output, stable.output)
            assertEquals(TaskOutcome.UP_TO_DATE, stable.task(":bmlBundleLibraryStyles")?.outcome)
            assertEquals(TaskOutcome.UP_TO_DATE, stable.task(":bmlBundleClient")?.outcome)
        } finally { work.deleteRecursively() }
    }

    @Test
    fun `library with client code cannot silently publish without a bundler`() {
        val work = createTempDirectory("bml-library-no-bundler").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val project = File(work, "producer").apply { mkdirs() }
            File(project, "settings.gradle").writeText("rootProject.name = 'design'")
            File(project, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml' }
                group = 'example'; version = '1.0'
                ${fixture.shared}
                bml { packageName.set('example.design'); library { exports.set(['button']) } }
            """.trimIndent())
            File(project, "src/main/bml/button.bml").apply { parentFile.mkdirs(); writeText("<component tag=\"button\"><html:button/><script client>console.log('client')</script></component>") }
            File(project, "src/main/kotlin/Marker.kt").apply { parentFile.mkdirs(); writeText("object Marker") }
            val failure = runner(project, "jar").buildAndFail()
            assertTrue("BML library client assets require" in failure.output, failure.output)
        } finally { work.deleteRecursively() }
    }

    private fun inspect(binary: File, sources: File): Map<*, *> = JarFile(binary).use { jar ->
        val manifest = JsonSlurper().parseText(text(jar, "META-INF/bml/client-manifest.json")) as Map<*, *>
        val prefix = manifest["resourcePrefix"] as String
        for (value in manifest["files"] as List<*>) {
            val file = value as Map<*, *>
            val bytes = jar.getInputStream(jar.getJarEntry("$prefix/${file["path"]}")).use { it.readBytes() }
            assertEquals(file["digest"], digest(bytes))
        }
        JarFile(sources).use { sourceJar ->
            for (value in manifest["sources"] as List<*>) {
                val file = value as Map<*, *>
                val bytes = sourceJar.getInputStream(sourceJar.getJarEntry(file["path"] as String)).use { it.readBytes() }
                assertEquals(file["digest"], digest(bytes))
            }
        }
        manifest
    }
    private fun digest(bytes: ByteArray) = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun text(jar: JarFile, path: String) = jar.getInputStream(requireNotNull(jar.getJarEntry(path)) { path }).bufferedReader().use { it.readText() }
    private fun jsString(value: String) = groovy.json.JsonOutput.toJson(value)
    private fun runner(project: File, vararg tasks: String) = GradleRunner.create().withProjectDir(project).withPluginClasspath()
        .withArguments(*tasks, "--configuration-cache", "--no-build-cache", "--stacktrace", "--max-workers=2")
}
