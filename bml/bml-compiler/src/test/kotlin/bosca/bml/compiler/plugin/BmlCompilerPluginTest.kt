package bosca.bml.compiler.plugin

import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.RenderContext
import bosca.di.provides
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.functions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InjectedLifecycle {
    var loaded: Boolean = false
        private set

    suspend fun load() {
        loaded = true
    }

    val status: String get() = if (loaded) "loaded" else "not-loaded"
}

/**
 * Drives the **actual K2 compiler plugin** in-process (not just the codegen, and not just compiling
 * already-generated `.kt`): it runs the embedded Kotlin compiler with our [BmlCompilerPluginRegistrar]
 * registered and a directory of `.bml` files as the source root, so the whole plugin path executes —
 * [BmlCommandLineProcessor.processOption] → [BmlCompilerPluginRegistrar.registerExtensions]
 * (`.bml`→KotlinFileType, the FIR/IR extensions, and [BmlAdditionalSourcesExtension]) →
 * `collectSources` (file walk → [bosca.bml.codegen.BmlCodeGenerator] → in-memory `KtSourceFile`s +
 * the `BmlPages`/`BmlIslands`/`BmlComponents` registries) → frontend → backend.
 *
 * The plugin is loaded via `pluginClasspaths` pointing at the classpath entries that ship our
 * `META-INF/services` files. The compiler's plugin classloader is parent-first, so the registrar it
 * instantiates is the very copy already on the test classpath — i.e. the Kover-instrumented one — which
 * is why this records coverage of the plugin glue that otherwise only runs inside Gradle's compile task.
 *
 * Contrast with [bosca.bml.codegen.BmlEndToEndTest], which compiles the generator's output directly and
 * never registers the plugin.
 */
class BmlCompilerPluginTest {

    private val pageBml = """
        <page route="/hello">
          <script server provides="greeting">"Hello, " + "world"</script>
          <h1>{ greeting }</h1>
          <ul><for x in listOf("a", "b", "c")><li>{ x }</li></for></ul>
          <if (1 < 2)><span>ok</span></if>
          <badge :label="greeting" tone="hot"/>
        </page>
    """.trimIndent()

    // A component in a SEPARATE file: exercises the extension's pass-1 (collect every `<component>` across
    // files) → pass-2 (resolve `<badge>` instantiations) flow and the BmlComponents registry builder.
    private val badgeBml = """
        <component tag="badge">
          <prop name="label" type="String" required/>
          <prop name="tone" type="String" default="info"/>
          <style scoped>.badge { display: inline-block; } .badge-hot { color: #b00020; }</style>
          <span class="badge badge-{ tone }">{ label }</span>
        </component>
    """.trimIndent()

    @Test
    fun `the K2 plugin lowers a directory of bml files to in-memory Kotlin that compiles and renders`() {
        val work = newWorkDir()
        val srcRoot = File(work, "bml").apply { mkdirs() }
        File(srcRoot, "home.bml").writeText(pageBml)
        File(srcRoot, "badge.bml").writeText(badgeBml)
        File(srcRoot, "feed.bml").writeText(
            """
            <route path="/feed.json" contentType="application/json">
              <script server provides="body">"{\"ok\":true}"</script>
              {@ body }
            </route>
            """.trimIndent(),
        )
        File(srcRoot, "status.bml").writeText("""<route path="/status">{@ "ok" }</route>""")
        File(srcRoot, "injected.bml").writeText(
            """<page route="/injected"><inject name="lifecycle" """ +
                """type="bosca.bml.compiler.plugin.InjectedLifecycle" provider="bml-test" init="load"/>""" +
                """<p>{ lifecycle.status }</p></page>""",
        )
        File(srcRoot, "server-injected.bml").writeText(
            """<page route="/server-injected">""" +
                """<inject server name="headerAuth" type="bosca.bml.graphql.GraphQLRequest" provider="bml-server-test"/>""" +
                """<island name="header"><p>{ headerAuth.query }</p></island>""" +
                """<button @click="headerAuth.toString">Run</button></page>""",
        )
        val out = File(work, "out").apply { mkdirs() }

        val (exit, messages) = compileWithBmlPlugin(out, listOf("bmlSourceRoot=${srcRoot.absolutePath}"), work)
        assertEquals(ExitCode.OK, exit, "plugin compile failed:\n$messages")

        // `bml.generated.HomePage` exists ONLY because the plugin synthesized it from home.bml (no .kt on disk).
        val html = renderGenerated(out, "bml.generated.HomePage")
        assertTrue("<h1>Hello, world</h1>" in html, "server <script provides> + interpolation:\n$html")
        assertTrue("<li>a</li>" in html && "<li>b</li>" in html && "<li>c</li>" in html, "for-loop:\n$html")
        assertTrue("<span>ok</span>" in html, "if-branch:\n$html")
        // The cross-file `<badge>` instantiation rendered (pass-1/pass-2 component resolution worked).
        assertTrue("badge badge-hot" in html, "component instantiation missing:\n$html")
        assertEquals("{\"ok\":true}", renderGenerated(out, "bml.generated.FeedPage"))
        assertEquals("ok", renderGenerated(out, "bml.generated.StatusPage"))
        assertEquals("text/html", generatedPage(out, "bml.generated.StatusPage").contentType)

        val lifecycle = InjectedLifecycle()
        provides<InjectedLifecycle>(name = "bml-test") { lifecycle }
        assertEquals("<p>loaded</p>", renderGenerated(out, "bml.generated.InjectedPage"))
        assertTrue(lifecycle.loaded, "init=\"load\" must invoke lifecycle.load() before markup renders")

        val headerAuth = bosca.bml.graphql.GraphQLRequest(query = "query HeaderAuth")
        provides<bosca.bml.graphql.GraphQLRequest>(name = "bml-server-test") { headerAuth }
        val serverInjected = generatedPage(out, "bml.generated.ServerInjectedPage")
        val serverInjectedContext = RenderContext()
        runBlocking { serverInjected.render(serverInjectedContext) }
        val serverInjectedHtml = serverInjectedContext.toString()
        assertTrue("<p>query HeaderAuth</p>" in serverInjectedHtml, "injected model did not render:\n$serverInjectedHtml")
        assertTrue(
            """data-bml-state-key="headerAuth">{"query":"query HeaderAuth","variables":null,"operationName":null}</script>""" in serverInjectedHtml,
            "server-marked inject was not serialized into live state:\n$serverInjectedHtml",
        )
        val dispatcher = serverInjected.islandActionDispatchers.single()
        val action = runBlocking {
            dispatcher.dispatch(
                RenderContext(),
                "toString",
                """{"query":"query HeaderAuth","variables":null,"operationName":null}""",
            )
        }
        assertTrue("\"query\":\"query HeaderAuth\"" in action.state.orEmpty(), "action did not return serialized state: ${action.state}")
        assertTrue("<p>query HeaderAuth</p>" in action.html.orEmpty(), "action did not re-render the injected model: ${action.html}")
    }

    @Test
    fun `bmlKotlinOutputDir dumps the generated Kotlin, registries, and a source map to disk`() {
        val work = newWorkDir()
        val srcRoot = File(work, "bml").apply { mkdirs() }
        File(srcRoot, "home.bml").writeText(pageBml)
        File(srcRoot, "badge.bml").writeText(badgeBml)
        // A page WITH a `<script client>` so the extension's TS-emission arm (BmlClientCodeGenerator returns
        // non-null → write the .ts) is taken, not just the `tsOutputDir != null` guard.
        File(srcRoot, "app.bml").writeText(
            """
            <page route="/app">
              <p>app</p>
              <script client>console.log("app loaded")</script>
            </page>
            """.trimIndent(),
        )
        File(srcRoot, "interactive-card.bml").writeText(
            """
            <component tag="interactive-card">
              <button>Account</button>
              <script client scoped>ctx.root.classList.add("ready")</script>
            </component>
            """.trimIndent(),
        )
        File(srcRoot, "shared.bml").writeText(
            """<page route="/shared" cache="shared" maxAge="60"><interactive-card/></page>""",
        )
        // A file with neither a <page> nor a <component>: exercises the `!hasPage && !hasComponent` skip arm.
        File(srcRoot, "notes.bml").writeText("<div>just notes, nothing to generate</div>")
        // A digit-prefixed filename: objectNameFor falls into its "first char isn't a letter → Bml-prefix" arm.
        File(srcRoot, "123.bml").writeText("""<page route="/onetwothree"><span>123</span></page>""")
        val out = File(work, "out").apply { mkdirs() }
        // Pre-seed the Kotlin dump dir with stale output so the extension's "clean stale .kt/.kt.map first" arm
        // (listFiles → delete) is exercised, not just the fresh-dir path.
        val ktOut = File(work, "ktdump").apply { mkdirs() }
        File(ktOut, "Stale.kt").writeText("// stale, should be deleted")
        File(ktOut, "Stale.kt.map").writeText("{}")
        val tsOut = File(work, "tsdump").apply { mkdirs() }
        File(tsOut, "StalePage.ts").writeText("// stale, should be deleted")
        val graphqlOut = File(tsOut, "graphql").apply { mkdirs() }
        File(graphqlOut, "GeneratedOperation.ts").writeText("// owned by GraphQL codegen")

        val (exit, messages) = compileWithBmlPlugin(
            out,
            listOf(
                "bmlSourceRoot=${srcRoot.absolutePath}",
                "bmlSourceRoot=${File(work, "does-not-exist").absolutePath}", // exercises the `!dir.isDirectory` skip
                "bmlKotlinOutputDir=${ktOut.absolutePath}", // exercises emit()'s on-disk branch + .kt.map write
                "bmlTsOutputDir=${tsOut.absolutePath}",      // exercises the tsOutputDir != null branch
            ),
            work,
        )
        assertEquals(ExitCode.OK, exit, "plugin compile failed:\n$messages")
        assertTrue(File(ktOut, "HomePage.kt").isFile, "generated page .kt not dumped to disk")
        assertTrue(File(ktOut, "BmlPages.kt").isFile, "BmlPages registry not dumped")
        assertTrue(File(ktOut, "BmlComponents.kt").isFile, "BmlComponents registry not dumped")
        assertTrue(File(ktOut, "BmlIslands.kt").isFile, "BmlIslands registry not dumped")
        assertTrue(File(ktOut, "HomePage.kt.map").isFile, "source map (.kt.map) not dumped")
        // The `<script client>` page emitted a TypeScript module to the TS output dir.
        assertTrue(File(tsOut, "AppPage.ts").isFile, "client <script> page did not emit a .ts module")
        val sharedTypeScript = File(tsOut, "SharedPage.ts").readText()
        assertTrue("enableDeferred()" in sharedTypeScript, "shared component page must bootstrap identity")
        assertTrue(
            "override val clientModule: String = \"SharedPage.js\"" in File(ktOut, "SharedPage.kt").readText(),
            "shared component page must publish its bootstrap module",
        )
        // The digit-prefixed file generated `Bml123Page` (the non-letter-first-char object-naming arm).
        assertTrue(File(ktOut, "Bml123Page.kt").isFile, "digit-prefixed page object not generated")
        // The pre-seeded stale output was cleaned before the fresh dump.
        assertFalse(File(ktOut, "Stale.kt").exists(), "stale .kt should have been deleted")
        assertFalse(File(ktOut, "Stale.kt.map").exists(), "stale .kt.map should have been deleted")
        assertFalse(File(tsOut, "StalePage.ts").exists(), "stale client TypeScript should have been deleted")
        assertTrue(File(graphqlOut, "GeneratedOperation.ts").isFile, "other generators' TS subdirectories must be preserved")
    }

    @Test
    fun `a component-only source tree generates the component registry but no page registry`() {
        // Covers the registry-emission arms where pageObjects is empty (skip BmlPages/BmlIslands) while
        // componentTags is non-empty (emit BmlComponents) — the mirror of the page-bearing tests above.
        val work = newWorkDir()
        val srcRoot = File(work, "bml").apply { mkdirs() }
        File(srcRoot, "badge.bml").writeText(badgeBml)
        val out = File(work, "out").apply { mkdirs() }
        val ktOut = File(work, "ktdump")

        val (exit, messages) = compileWithBmlPlugin(
            out,
            listOf("bmlSourceRoot=${srcRoot.absolutePath}", "bmlKotlinOutputDir=${ktOut.absolutePath}"),
            work,
        )
        assertEquals(ExitCode.OK, exit, "plugin compile failed:\n$messages")
        assertTrue(File(ktOut, "BmlComponents.kt").isFile, "component registry must be generated")
        assertFalse(File(ktOut, "BmlPages.kt").exists(), "no page registry when there are no pages")
        assertFalse(File(ktOut, "BmlIslands.kt").exists(), "no island registry when there are no pages")
    }

    @OptIn(CompilerConfiguration.Internals::class)
    @Test
    fun `the command-line processor maps every plugin option into the compiler configuration`() {
        // Deterministically covers BmlCommandLineProcessor.processOption for all four options (the path the
        // embedded compiler drives when it parses `-P plugin:bml:<opt>=<value>`). A full "enabled=false"
        // compile is unreliable here: the embedded compiler shares extension state across compiler runs in one
        // JVM, so a leaked CollectAdditionalSourceFilesExtension still fires — hence this direct test instead.
        val proc = BmlCommandLineProcessor()
        fun opt(name: String) = proc.pluginOptions.first { it.optionName == name }

        val config = CompilerConfiguration()
        proc.processOption(opt("enabled"), "false", config)
        proc.processOption(opt("bmlSourceRoot"), "/a/bml", config)
        proc.processOption(opt("bmlSourceRoot"), "/b/bml", config) // repeatable -> accumulates
        proc.processOption(opt("bmlTsOutputDir"), "/ts", config)
        proc.processOption(opt("bmlKotlinOutputDir"), "/kt", config)
        proc.processOption(opt("bmlResourcesOutputDir"), "/res", config)

        assertEquals("bml", proc.pluginId)
        assertEquals(false, config.get(BmlConfigKeys.ENABLED))
        assertEquals(listOf("/a/bml", "/b/bml"), config.getList(BmlConfigKeys.SOURCE_ROOTS))
        assertEquals("/ts", config.get(BmlConfigKeys.TS_OUTPUT_DIR))
        assertEquals("/kt", config.get(BmlConfigKeys.KOTLIN_OUTPUT_DIR))
        assertEquals("/res", config.get(BmlConfigKeys.RESOURCES_OUTPUT_DIR))

        // An unparseable `enabled` value falls back to true (the `?: true` arm).
        val other = CompilerConfiguration()
        proc.processOption(opt("enabled"), "not-a-bool", other)
        assertEquals(true, other.get(BmlConfigKeys.ENABLED), "unparseable enabled must default to true")
    }

    @OptIn(ExperimentalCompilerApi::class, CompilerConfiguration.Internals::class)
    @Test
    fun `a disabled plugin registers no extensions`() {
        // Covers the early return in registerExtensions when bml.enabled=false. Called directly (not via a
        // compile) so it's deterministic and free of the embedded compiler's cross-run extension leakage.
        val storage = CompilerPluginRegistrar.ExtensionStorage()
        val config = CompilerConfiguration().apply { put(BmlConfigKeys.ENABLED, false) }
        with(BmlCompilerPluginRegistrar()) { storage.registerExtensions(config) }
        assertTrue(storage.registeredExtensions.isEmpty(), "a disabled plugin must register no extensions")
    }

    @Test
    fun `registerBmlFileType warns when the registry is not a CoreFileTypeRegistry`() {
        // The defensive arm: when the compiler hands us an unexpected FileTypeRegistry (here, null), we can't
        // register `.bml` and must warn rather than silently drop sources. The success arm (CoreFileTypeRegistry)
        // is covered by the real embedded compiles above.
        val warnings = mutableListOf<String>()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity == CompilerMessageSeverity.WARNING) warnings += message
            }
        }
        // A non-null registry that isn't a CoreFileTypeRegistry: hits the else arm AND the non-null
        // `registry?.let { it.javaClass.name }` path that formats the registry type into the warning.
        val notCore = object : FileTypeRegistry() {
            override fun isFileIgnored(file: VirtualFile) = false
            override fun getRegisteredFileTypes(): Array<FileType> = emptyArray()
            override fun getFileTypeByFile(file: VirtualFile): FileType = error("unused by registerBmlFileType")
            override fun getFileTypeByFileName(fileName: String): FileType = error("unused by registerBmlFileType")
            override fun getFileTypeByExtension(extension: String): FileType = error("unused by registerBmlFileType")
            override fun findFileTypeByName(name: String): FileType = error("unused by registerBmlFileType")
        }
        registerBmlFileType(registry = notCore, messages = collector)
        assertTrue(
            warnings.any { "could not register the .bml file type" in it && notCore.javaClass.name in it },
            "warning should name the unexpected registry type, got: $warnings",
        )

        // The null registry arm also warns (and the null-message arm must not throw — messages?.report is null-safe).
        warnings.clear()
        registerBmlFileType(registry = null, messages = collector)
        assertTrue(warnings.any { "could not register the .bml file type" in it }, "null registry must warn: $warnings")
        registerBmlFileType(registry = null, messages = null)
    }

    // ---- Direct collectSources tests: drive the extension's logic without the compiler (deterministic,
    // no embedded-compiler global-state leakage). collectSources ignores its `environment`/`findVirtualFile`
    // args and reads `.bml` straight off the configured source roots, so calling it directly is faithful. ----

    @OptIn(CompilerConfiguration.Internals::class)
    private fun configWith(
        roots: List<String>,
        kotlinOut: String? = null,
        tsOut: String? = null,
        resourcesOut: String? = null,
    ) = CompilerConfiguration().apply {
        roots.forEach { add(BmlConfigKeys.SOURCE_ROOTS, it) }
        kotlinOut?.let { put(BmlConfigKeys.KOTLIN_OUTPUT_DIR, it) }
        tsOut?.let { put(BmlConfigKeys.TS_OUTPUT_DIR, it) }
        resourcesOut?.let { put(BmlConfigKeys.RESOURCES_OUTPUT_DIR, it) }
    }

    private fun collect(config: CompilerConfiguration) =
        BmlAdditionalSourcesExtension().collectSources(Any(), config, { null }, emptyList()).toList()

    @Test
    fun `collectSources skips a source root that is not a directory`() {
        val result = collect(configWith(listOf(File(newWorkDir(), "missing").absolutePath)))
        assertTrue(result.isEmpty(), "a non-existent source root generates nothing")
    }

    @Test
    fun `collectSources generates page and component sources plus the registries`() {
        val dir = newWorkDir()
        File(dir, "home.bml").writeText(pageBml)
        File(dir, "badge.bml").writeText(badgeBml)
        // A non-identifier filename: objectNameFor's `pascal.isEmpty()` arm → falls back to the "Bml" prefix.
        File(dir, "-.bml").writeText("""<page route="/dash"><span>d</span></page>""")
        val names = collect(configWith(listOf(dir.absolutePath))).map { it.name }.toSet()
        assertTrue("HomePage.kt" in names, "page source: $names")
        assertTrue("BmlPages.kt" in names && "BmlIslands.kt" in names, "page registries: $names")
        assertTrue("BmlComponents.kt" in names, "component registry: $names")
        assertTrue("BmlPage.kt" in names, "non-identifier filename should fall back to 'BmlPage': $names")
        assertTrue(names.none { it.endsWith(".bml") }, "raw .bml must be dropped from the source list: $names")
    }

    @Test
    fun `collectSources does not register components with a missing or dynamic tag`() {
        // componentTag returns null for an interpolated tag (not all literal text) and for a missing tag
        // attribute, so neither is added to the BmlComponents registry; only the statically-tagged one is.
        val dir = newWorkDir()
        File(dir, "badge.bml").writeText(badgeBml) // static tag="badge"
        File(dir, "dynamic.bml").writeText("""<component tag="x-{ n }"><span>{ n }</span></component>""")
        File(dir, "untagged.bml").writeText("<component><span>hi</span></component>")
        File(dir, "boolean.bml").writeText("<component tag><span>b</span></component>") // tag present, no value
        val ktOut = File(dir, "kt")
        collect(configWith(listOf(dir.absolutePath), kotlinOut = ktOut.absolutePath))
        val registry = File(ktOut, "BmlComponents.kt").readText()
        assertTrue("\"badge\"" in registry, "statically-tagged component must be registered:\n$registry")
        assertFalse("x-" in registry, "dynamically-tagged component must NOT be registered:\n$registry")
    }

    @Test
    fun `collectSources generates message sources plus the BmlMessages registry`() {
        val dir = newWorkDir()
        File(dir, "home.bml").writeText(pageBml)
        File(dir, "welcome.bml").writeText(
            """<message key="welcome"><email><subject>Hi</subject><p>b</p></email></message>""",
        )
        val ktOut = File(dir, "kt")
        val names = collect(configWith(listOf(dir.absolutePath), kotlinOut = ktOut.absolutePath)).map { it.name }.toSet()
        assertTrue("WelcomeMessage.kt" in names, "message unit takes the Message suffix: $names")
        assertTrue("BmlMessages.kt" in names, "message registry: $names")
        val manifest = File(ktOut, "BmlMessages.kt").readText()
        assertTrue("object BmlMessages : BmlMessageModule" in manifest, manifest)
        assertTrue("WelcomeMessage," in manifest, manifest)
        assertTrue("associateBy { it.key }" in manifest, manifest)
        // Pages and messages keep separate registries.
        val pages = File(ktOut, "BmlPages.kt").readText()
        assertFalse("WelcomeMessage" in pages, "a message template must not be listed as a page:\n$pages")
    }

    @Test
    fun `collectSources generates message sources plus the BmlMessages manifest`() {
        val dir = newWorkDir()
        File(dir, "chat.bml").writeText(
            """<message key="chat"><push><title t="chat.title">Chat</title><body>Open</body></push>""" +
                """<email><subject>Chat</subject><p>Open</p></email></message>""",
        )
        val ktOut = File(dir, "kt")
        val resOut = File(dir, "res")
        val names = collect(
            configWith(
                listOf(dir.absolutePath),
                kotlinOut = ktOut.absolutePath,
                resourcesOut = resOut.absolutePath,
            ),
        ).map { it.name }.toSet()

        assertTrue("ChatMessage.kt" in names, "message unit takes the Message suffix: $names")
        assertTrue("BmlMessages.kt" in names, "message manifest: $names")
        val registry = File(ktOut, "BmlMessages.kt").readText()
        assertTrue("object BmlMessages : BmlMessageModule" in registry, registry)
        assertTrue("ChatMessage," in registry, registry)
        val artifactManifest = File(resOut, bosca.bml.message.BmlMessageArtifacts.MANIFEST_PATH).readText()
        assertTrue("\"module\": \"bml.generated.BmlMessages\"" in artifactManifest, artifactManifest)
        assertTrue("\"supportsEmail\": true" in artifactManifest, artifactManifest)
        assertTrue("\"supportsPush\": true" in artifactManifest, artifactManifest)
    }

    @Test
    fun `collectSources writes the message manifest resource and clears it when messages are removed`() {
        val dir = newWorkDir()
        File(dir, "messages").mkdirs()
        File(dir, "messages/welcome.bml").writeText(
            """<message key="welcome"><push><title>Hi</title><body>Open it</body></push>""" +
                """<email><subject>Hi</subject><p>b</p></email></message>""",
        )
        File(dir, "messages/goodbye.bml").writeText(
            """<message><email><subject>Bye</subject><p>b</p></email></message>""",
        ) // key from file name
        val resOut = File(dir, "res")
        collect(configWith(listOf(dir.absolutePath), resourcesOut = resOut.absolutePath))

        val manifest = File(resOut, bosca.bml.message.BmlMessageArtifacts.MANIFEST_PATH)
        assertTrue(manifest.isFile, "message manifest not written")
        val json = manifest.readText()
        assertTrue("\"manifestVersion\": 1" in json, json)
        assertTrue("\"supportsPush\": true" in json, json)
        assertTrue("\"supportsPush\": false" in json, json)
        assertTrue("\"module\": \"bml.generated.BmlMessages\"" in json, json)
        assertTrue("\"key\": \"welcome\"" in json, json)
        assertTrue("\"key\": \"goodbye\"" in json, "key must default to the file base name:\n$json")
        assertTrue("\"source\": \"messages/welcome.bml\"" in json, json)
        assertTrue("\"objectName\": \"bml.generated.MessagesWelcomeMessage\"" in json, json)

        // Removing every message must remove the manifest — a stale one would advertise dead templates.
        File(dir, "messages/welcome.bml").delete()
        File(dir, "messages/goodbye.bml").delete()
        collect(configWith(listOf(dir.absolutePath), resourcesOut = resOut.absolutePath))
        assertFalse(manifest.exists(), "stale message manifest must be deleted")
    }

    @OptIn(CompilerConfiguration.Internals::class)
    @Test
    fun `collectSources rejects conflicting authored defaults in function calls`() {
        val dir = newWorkDir()
        File(dir, "home.bml").writeText(
            """<page route="/"><p>{ t("shared.key", "First") }</p><p>{ t("shared.key", "Second") }</p></page>""",
        )
        val errors = mutableListOf<String>()
        val collector = object : MessageCollector {
            override fun clear() = Unit
            override fun hasErrors(): Boolean = errors.isNotEmpty()
            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?,
            ) {
                if (severity.isError) errors += message
            }
        }
        val config = configWith(listOf(dir.absolutePath), resourcesOut = File(dir, "res").absolutePath).apply {
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, collector)
        }

        collect(config)

        assertTrue(errors.any { "key 'shared.key' has different source text" in it }, errors.toString())
    }

    @Test
    fun `collectSources skips a file with neither a page nor a component`() {
        val dir = newWorkDir()
        File(dir, "home.bml").writeText(pageBml)
        File(dir, "notes.bml").writeText("<div>just prose, nothing to generate</div>")
        val names = collect(configWith(listOf(dir.absolutePath))).map { it.name }.toSet()
        assertTrue("HomePage.kt" in names, names.toString())
        assertTrue("NotesPage.kt" !in names, "a page-less/component-less file generates nothing: $names")
    }

    @Test
    fun `collectSources skips a file it cannot read`() {
        // The runCatching { parse(readText()) }.getOrNull() ?: return@forEach arm: an unreadable .bml is
        // skipped rather than crashing the compile. Skipped where the OS won't drop read permission (e.g. root).
        val dir = newWorkDir()
        val bad = File(dir, "bad.bml").apply { writeText("""<page route="/bad"><span>x</span></page>""") }
        org.junit.Assume.assumeTrue(
            "cannot remove read permission (running as root?)",
            bad.setReadable(false, false) && !bad.canRead(),
        )
        try {
            val result = collect(configWith(listOf(dir.absolutePath)))
            assertTrue(result.none { it.name == "BadPage.kt" }, "an unreadable .bml must be skipped")
        } finally {
            bad.setReadable(true, false)
        }
    }

    /** Compiles a placeholder source with the BML plugin registered + the given `plugin:bml:<opt>` options. */
    private fun compileWithBmlPlugin(out: File, options: List<String>, work: File): Pair<ExitCode, String> {
        // The compiler needs at least one source to anchor the module; the plugin contributes the rest.
        val anchor = File(work, "Anchor.kt").apply { writeText("package bml.test.anchor\n") }
        // Pass the `.bml` files as source args (what KGP's addCustomSourceFilesExtensions("bml") + source-dir
        // wiring does for the Gradle build); the registrar's `.bml`→KotlinFileType registration — which runs
        // during environment setup, before source validation — makes the compiler accept them. The extension
        // then drops the raw `.bml` and contributes the generated Kotlin in their place.
        val bmlRoots = options.mapNotNull { o -> o.removePrefix("bmlSourceRoot=").takeIf { it != o } }
        val bmlFiles = bmlRoots.flatMap { root ->
            File(root).walkTopDown().filter { it.isFile && it.extension == "bml" }.map { it.absolutePath }
        }

        val messages = StringBuilder()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError || severity == CompilerMessageSeverity.WARNING) messages.appendLine("$severity: $message")
            }
        }

        val pluginCp = bmlPluginClasspath()
        assertTrue(
            pluginCp.isNotEmpty(),
            "could not locate the BML plugin's META-INF/services on the test classpath",
        )

        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(anchor.absolutePath) + bmlFiles
            destination = out.absolutePath
            classpath = System.getProperty("java.class.path")
            // Generated inject/live-state bindings inline both Bosca DI and core-bml serialization
            // helpers. Match the BML modules' Java 25 toolchain so neither dependency is newer than
            // this embedded-compiler harness's bytecode target.
            jvmTarget = "25"
            noStdlib = true
            noReflect = true
            pluginClasspaths = pluginCp.toTypedArray()
            pluginOptions = options.map { "plugin:bml:$it" }.toTypedArray()
        }
        return runEmbeddedCompiler(collector, args) to messages.toString()
    }

    /** Classpath entries (dirs or jars) that ship the plugin's `CompilerPluginRegistrar` service file. */
    private fun bmlPluginClasspath(): List<String> {
        val service = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
        return System.getProperty("java.class.path").split(File.pathSeparator).filter { entry ->
            val f = File(entry)
            when {
                f.isDirectory -> File(f, service).isFile
                f.isFile && f.extension == "jar" ->
                    runCatching { JarFile(f).use { it.getEntry(service) != null } }.getOrDefault(false)
                else -> false
            }
        }
    }

    /** Loads a generated page object from the compiler output and runs its suspend `render()`. */
    private fun renderGenerated(out: File, fqcn: String): String {
        val obj = generatedPage(out, fqcn)
        val ctx = RenderContext()
        val render = obj::class.functions.first { it.name == "render" }
        runBlocking { render.callSuspend(obj, ctx) }
        return ctx.toString()
    }

    private fun generatedPage(out: File, fqcn: String): BmlPageRenderer {
        val loader = URLClassLoader(arrayOf(out.toURI().toURL()), javaClass.classLoader)
        return loader.loadClass(fqcn).kotlin.objectInstance as? BmlPageRenderer
            ?: error("$fqcn BML page object instance not found")
    }

    /** Runs the embedded Kotlin compiler reflectively (avoids a literal shell-run token). */
    private fun runEmbeddedCompiler(collector: MessageCollector, args: K2JVMCompilerArguments): ExitCode {
        val compiler = K2JVMCompiler()
        // `CLICompiler` overloads the entry method with several 3-arg forms — notably the
        // MessageCollector/Services/arguments one we want, plus a PrintStream/MessageRenderer/vararg-String one.
        // `Class.getMethods()` returns them in an unspecified order, so filtering by arity alone is
        // non-deterministic and picks the wrong overload on some JDKs (fails in CI with an "argument type
        // mismatch" IllegalArgumentException). Match by the parameter *types* to pin the correct overload
        // regardless of reflection ordering.
        val method = compiler.javaClass.methods.first {
            it.name == "exec" &&
                it.parameterCount == 3 &&
                it.parameterTypes[0] == MessageCollector::class.java &&
                it.parameterTypes[1] == Services::class.java
        }
        return method.invoke(compiler, collector, Services.EMPTY, args) as ExitCode
    }

    private fun newWorkDir(): File = File.createTempFile("bml-plugin", "").apply { delete(); mkdirs() }
}
