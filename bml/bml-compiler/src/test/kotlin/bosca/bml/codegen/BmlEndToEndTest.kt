package bosca.bml.codegen

import bosca.bml.graphql.GraphQLClient
import bosca.bml.parser.BmlParser
import bosca.bml.parser.Severity
import bosca.bml.render.RenderContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.net.URLClassLoader
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.functions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

fun featureFlags(): String = "authored-feature-source"
fun `helper as source`(): String = "authored-feature-source"

/**
 * Proves the whole pipeline: `.bml` -> parser -> generator -> Kotlin source ->
 * (compile with the embedded Kotlin compiler) -> run `render()` -> HTML.
 */
class BmlEndToEndTest {

    @Test
    fun `generated deferred island compiles and renders across the request boundary`() {
        val bml = """
            <component tag="account-shell">
              <island name="private" render="deferred"><p>Account</p></island>
            </component>
            <component tag="profile-shell">
              <island name="private" render="deferred"><p>Profile</p></island>
            </component>
            <page route="/profiles/{id}" cache="shared" maxAge="60">
              <account-shell/>
              <profile-shell/>
              <island name="profile" render="deferred" :id="id">
                <prop name="id" type="String" required/>
                <fallback><p>Loading profile</p></fallback>
                <script server provides="title">"Profile " + id</script>
                <h2>{ title }</h2>
              </island>
            </page>
        """.trimIndent()
        val parsed = BmlParser.parse(bml)
        assertFalse(parsed.hasErrors, "parse: ${parsed.diagnostics}")
        val result = BmlCodeGenerator("gen", "ProfilesPage", "profiles.bml").generate(parsed.document)
        assertFalse(result.diagnostics.any { it.severity == Severity.Error }, "codegen: ${result.diagnostics}")

        val tmp = File.createTempFile("bml-deferred-e2e", "").apply { delete(); mkdirs() }
        val source = File(tmp, "ProfilesPage.kt").apply { writeText(result.source) }
        val outDir = File(tmp, "out").apply { mkdirs() }
        val messages = StringBuilder()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?,
            ) {
                if (severity.isError) messages.appendLine("$severity: $message")
            }
        }
        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(source.absolutePath)
            destination = outDir.absolutePath
            classpath = System.getProperty("java.class.path")
            jvmTarget = "25"
            noStdlib = true
            noReflect = true
        }
        assertEquals(
            ExitCode.OK,
            runEmbeddedCompiler(collector, args),
            "compile failed:\n$messages\n--- generated ---\n${result.source}",
        )

        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader)
        val page = loader.loadClass("gen.ProfilesPage").kotlin.objectInstance as bosca.bml.render.BmlPageRenderer
        val shell = RenderContext(params = mapOf("id" to "42")).apply { requestPath = "/profiles/42" }
        runBlocking { page.render(shell) }
        assertTrue("Loading profile" in shell.toString(), shell.toString())
        assertTrue("data-bml-page=\"/profiles/{id}\"" in shell.toString(), shell.toString())
        assertFalse("Profile 42" in shell.toString(), shell.toString())
        assertEquals(60L, page.sharedCacheMaxAgeSeconds)
        assertEquals(60L, page.sharedCacheStaleWhileRevalidateSeconds)

        val deferred = page.deferredRenderers.single()
        val fragment = RenderContext(params = mapOf("id" to "42")).apply { requestPath = "/profiles/42" }
        runBlocking { deferred.render(fragment, mapOf("id" to "42")) }
        assertEquals("<h2>Profile 42</h2>", fragment.toString())
    }

    @Test
    fun `static prop values are compiled as typed literals and survive the deferred round trip`() {
        val bml = """
            <component tag="meter">
              <prop name="count" type="Int"/>
              <prop name="open" type="Boolean"/>
              <prop name="ratio" type="Double"/>
              <span>{ count + 1 }|{ !open }|{ ratio * 2 }</span>
            </component>
            <page route="/stats">
              <meter count="3" open="false" ratio="0.25"/>
              <island name="stats" render="deferred" total="9007199254740993" level="-8" weight="1.5"
                      code="x" enabled label="n-{ 1 + 1 }" smallest="-2147483648">
                <prop name="total" type="Long"/>
                <prop name="level" type="Byte"/>
                <prop name="weight" type="Float"/>
                <prop name="code" type="Char"/>
                <prop name="enabled" type="Boolean"/>
                <prop name="label" type="String"/>
                <prop name="smallest" type="Int"/>
                <p>{ total + 1 }|{ level }|{ weight * 2 }|{ code }|{ enabled }|{ label }|{ smallest }</p>
              </island>
            </page>
        """.trimIndent()
        val parsed = BmlParser.parse(bml)
        assertFalse(parsed.hasErrors, "parse: ${parsed.diagnostics}")
        val result = BmlCodeGenerator("gen", "StatsPage", "stats.bml").generate(parsed.document)
        assertFalse(result.diagnostics.any { it.severity == Severity.Error }, "codegen: ${result.diagnostics}")

        val tmp = File.createTempFile("bml-static-props-e2e", "").apply { delete(); mkdirs() }
        val source = File(tmp, "StatsPage.kt").apply { writeText(result.source) }
        val outDir = File(tmp, "out").apply { mkdirs() }
        val messages = StringBuilder()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?,
            ) {
                if (severity.isError) messages.appendLine("$severity: $message")
            }
        }
        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(source.absolutePath)
            destination = outDir.absolutePath
            classpath = System.getProperty("java.class.path")
            jvmTarget = "25"
            noStdlib = true
            noReflect = true
        }
        assertEquals(
            ExitCode.OK,
            runEmbeddedCompiler(collector, args),
            "compile failed:\n$messages\n--- generated ---\n${result.source}",
        )

        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader)
        val page = loader.loadClass("gen.StatsPage").kotlin.objectInstance as bosca.bml.render.BmlPageRenderer
        val shell = RenderContext().apply { requestPath = "/stats" }
        runBlocking { page.render(shell) }
        val html = shell.toString()
        // The component received typed values: arithmetic and negation run on Int, Boolean, and Double.
        assertTrue("<span>4|true|0.5</span>" in html, html)

        // Decode the public props exactly as bml-server does for POST /_bml/deferred/{id}.
        val props = Regex("""data-bml-props="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?.replace("&quot;", "\"")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&")
            ?: error("no deferred props in shell: $html")
        val request = bosca.bml.render.parseDeferredRenderRequest(
            """{"props":$props,"page":"/stats","path":"/stats","query":{}}""",
        ) ?: error("server rejected the shell's props: $props")
        val fragment = RenderContext().apply { requestPath = "/stats" }
        runBlocking { page.deferredRenderers.single().render(fragment, request.props) }
        assertEquals("<p>9007199254740994|-8|3.0|x|true|n-2|-2147483648</p>", fragment.toString())
    }

    @Test
    fun `component static values compile for aliases and supertypes and mismatches fail in Kotlin`() {
        val aliases = """
            package gen

            typealias Url = String
            typealias Flag = Boolean
            class Row(val id: String)
        """.trimIndent()
        fun page(call: String) = """
            <component tag="doc-link">
              <prop name="href" type="Url"/>
              <prop name="wide" type="Flag" default="false"/>
              <prop name="key" type="Comparable<String>?"/>
              <a>{ href }|{ wide }|{ key }</a>
            </component>
            <component tag="row-card">
              <prop name="row" type="Row"/>
              <p>{ row.id }</p>
            </component>
            <page route="/">$call</page>
        """.trimIndent()

        fun compile(bml: String): Pair<ExitCode, String> {
            val parsed = BmlParser.parse(bml)
            assertFalse(parsed.hasErrors, "parse: ${parsed.diagnostics}")
            val result = BmlCodeGenerator("gen", "LinksPage", "links.bml").generate(parsed.document)
            assertFalse(result.diagnostics.any { it.severity == Severity.Error }, "codegen: ${result.diagnostics}")
            val tmp = File.createTempFile("bml-component-types-e2e", "").apply { delete(); mkdirs() }
            val source = File(tmp, "LinksPage.kt").apply { writeText(result.source) }
            val types = File(tmp, "Types.kt").apply { writeText(aliases) }
            val outDir = File(tmp, "out").apply { mkdirs() }
            val messages = StringBuilder()
            val collector = object : MessageCollector {
                override fun clear() {}
                override fun hasErrors() = false
                override fun report(
                    severity: CompilerMessageSeverity,
                    message: String,
                    location: CompilerMessageSourceLocation?,
                ) {
                    if (severity.isError) messages.appendLine("$severity: $message")
                }
            }
            val args = K2JVMCompilerArguments().apply {
                freeArgs = listOf(source.absolutePath, types.absolutePath)
                destination = outDir.absolutePath
                classpath = System.getProperty("java.class.path")
                jvmTarget = "25"
                noStdlib = true
                noReflect = true
            }
            val code = runEmbeddedCompiler(collector, args)
            if (code == ExitCode.OK) {
                val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader)
                val rendered = loader.loadClass("gen.LinksPage").kotlin.objectInstance as bosca.bml.render.BmlPageRenderer
                val ctx = RenderContext().apply { requestPath = "/" }
                runBlocking { rendered.render(ctx) }
                return code to ctx.toString()
            }
            return code to "$messages\n--- generated ---\n${result.source}"
        }

        val (ok, html) = compile(page("""<doc-link href="/docs" wide key="k-{ 1 }"/>"""))
        assertEquals(ExitCode.OK, ok, html)
        assertEquals("<a>/docs|true|k-1</a>", html)

        // A static value that really cannot be the declared class now fails the Kotlin compile of the
        // generated page instead of failing its cast on every render.
        val (mismatch, output) = compile(page("""<row-card row="primary"/>"""))
        assertEquals(ExitCode.COMPILATION_ERROR, mismatch, output)
        assertTrue("mismatch" in output, output)
    }

    @Test
    fun `a deferred prop can default to the route param it shadows`() {
        val bml = """
            <page route="/accounts/{id}">
              <island name="acct" render="deferred">
                <prop name="id" type="String" :default="id"/>
                <p>account {id}</p>
              </island>
            </page>
        """.trimIndent()
        val parsed = BmlParser.parse(bml)
        assertFalse(parsed.hasErrors, "parse: ${parsed.diagnostics}")
        val result = BmlCodeGenerator("gen", "AccountsPage", "accounts.bml").generate(parsed.document)
        assertFalse(result.diagnostics.any { it.severity == Severity.Error }, "codegen: ${result.diagnostics}")

        val tmp = File.createTempFile("bml-route-shadow-e2e", "").apply { delete(); mkdirs() }
        val source = File(tmp, "AccountsPage.kt").apply { writeText(result.source) }
        val outDir = File(tmp, "out").apply { mkdirs() }
        val messages = StringBuilder()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?,
            ) {
                if (severity.isError) messages.appendLine("$severity: $message")
            }
        }
        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(source.absolutePath)
            destination = outDir.absolutePath
            classpath = System.getProperty("java.class.path")
            jvmTarget = "25"
            noStdlib = true
            noReflect = true
        }
        assertEquals(ExitCode.OK, runEmbeddedCompiler(collector, args), "compile failed:\n$messages\n${result.source}")

        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader)
        val page = loader.loadClass("gen.AccountsPage").kotlin.objectInstance as bosca.bml.render.BmlPageRenderer
        val renderer = page.deferredRenderers.single()
        fun render(props: Map<String, Any?>): String {
            val ctx = RenderContext(params = mapOf("id" to "42")).apply { requestPath = "/accounts/42" }
            runBlocking { renderer.render(ctx, props) }
            return ctx.toString()
        }
        assertEquals("<p>account 42</p>", render(emptyMap()))        // default reads the route param
        assertEquals("<p>account 7</p>", render(mapOf("id" to "7")))  // an explicit prop wins
    }

    @Test
    fun `generated page compiles and renders html`() {
        val bml = """
            <page route="/hello">
              <script server provides="greeting">"Hello, " + "world"</script>
              <script server provides="authoredFlagSource">
                import java.time.*; import bosca.bml.codegen.`helper as source` /* authored helper */ as featureFlags
                featureFlags()
              </script>
              <script server provides="featureFlags">"documentation"</script>
              <h1>{ greeting }</h1>
              <p>{ authoredFlagSource }</p>
              <ul><for x in listOf("a", "b", "c")><li>{ x }</li></for></ul>
              <if (1 < 2)><span>ok</span></if>
              <if flag="new-checkout"><span>flagged</span></if>
            </page>
        """.trimIndent()

        val parsed = BmlParser.parse(bml)
        assertFalse(parsed.hasErrors, "parse: ${parsed.diagnostics}")
        val result = BmlCodeGenerator("gen", "HelloPage", "hello.bml").generate(parsed.document)
        assertFalse(result.diagnostics.any { it.severity == Severity.Error }, "codegen: ${result.diagnostics}")

        val tmp = File.createTempFile("bml-e2e", "").apply { delete(); mkdirs() }
        val srcFile = File(tmp, "HelloPage.kt").apply { writeText(result.source) }
        val outDir = File(tmp, "out").apply { mkdirs() }

        val messages = StringBuilder()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) messages.appendLine("$severity: $message")
            }
        }
        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(srcFile.absolutePath)
            destination = outDir.absolutePath
            classpath = System.getProperty("java.class.path")
            noStdlib = true
            noReflect = true
        }
        val exit = runEmbeddedCompiler(collector, args)
        assertEquals(ExitCode.OK, exit, "compile failed:\n$messages\n--- generated ---\n${result.source}")

        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader)
        val obj = loader.loadClass("gen.HelloPage").kotlin.objectInstance
            ?: error("HelloPage object instance not found")
        val ctx = RenderContext(
            gql = object : GraphQLClient {
                override suspend fun execute(
                    query: String,
                    variables: JsonObject?,
                    operationName: String?,
                    token: String?,
                ): JsonElement = Json.parseToJsonElement(
                    """{"featureFlags":{"evaluate":{"flagKey":"new-checkout","variationKey":"on","value":true,"experimentId":null,"degraded":false}}}""",
                )
            },
            cookies = mapOf(bosca.bml.render.BML_INSTALLATION_COOKIE to "installation-1"),
        )
        val render = obj::class.functions.first { it.name == "render" }
        runBlocking { render.callSuspend(obj, ctx) }
        val html = ctx.toString()

        assertTrue(html.contains("<h1>Hello, world</h1>"), "html was: $html")
        assertTrue(html.contains("<p>authored-feature-source</p>"), "authored featureFlags import failed: $html")
        assertTrue(html.contains("<li>a</li>") && html.contains("<li>b</li>") && html.contains("<li>c</li>"), "html was: $html")
        assertTrue(html.contains("<span>ok</span>"), "html was: $html")
        assertTrue(html.contains("<span>flagged</span>"), "feature flag branch was absent: $html")
    }

    @Test
    fun `authored wildcard feature helper compiles without a generated import collision`() {
        val bml = """
            <page route="/wildcard">
              <script server provides="value">
                import bosca.bml.codegen.*
                featureFlags()
              </script>
              <p>{ value }</p>
            </page>
        """.trimIndent()
        val parsed = BmlParser.parse(bml)
        assertFalse(parsed.hasErrors, "parse: ${parsed.diagnostics}")
        val result = BmlCodeGenerator("gen", "WildcardPage", "wildcard.bml").generate(parsed.document)
        assertFalse(result.diagnostics.any { it.severity == Severity.Error }, "codegen: ${result.diagnostics}")
        val tmp = File.createTempFile("bml-feature-imports", "").apply { delete(); mkdirs() }
        val source = File(tmp, "WildcardPage.kt").apply { writeText(result.source) }
        val outDir = File(tmp, "out").apply { mkdirs() }
        val messages = StringBuilder()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) messages.appendLine("$severity: $message")
            }
        }
        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(source.absolutePath)
            destination = outDir.absolutePath
            classpath = System.getProperty("java.class.path")
            noStdlib = true
            noReflect = true
        }
        assertEquals(ExitCode.OK, runEmbeddedCompiler(collector, args), "compile failed:\n$messages")

        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader)
        val page = loader.loadClass("gen.WildcardPage").kotlin.objectInstance
            ?: error("WildcardPage object instance not found")
        val ctx = RenderContext()
        val render = page::class.functions.first { it.name == "render" }
        runBlocking { render.callSuspend(page, ctx) }
        assertTrue("<p>authored-feature-source</p>" in ctx.toString(), "html was: $ctx")
    }

    @Test
    fun `ambient t() localizes text, attributes, and plurals without imports or a wrapping host`() {
        val bml = """
            <page route="/localized">
              <h1>{ t("home.title") }</h1>
              <p>{ t("greeting", "name" to "Ada") }</p>
              <button :aria-label="t("nav.close")">x</button>
              <span>{ t("cart.items", 3) }</span>
            </page>
        """.trimIndent()

        val parsed = BmlParser.parse(bml)
        assertFalse(parsed.hasErrors, "parse: ${parsed.diagnostics}")
        val result = BmlCodeGenerator("gen", "LocalizedPage", "localized.bml").generate(parsed.document)
        assertFalse(result.diagnostics.any { it.severity == Severity.Error }, "codegen: ${result.diagnostics}")

        val tmp = File.createTempFile("bml-i18n-e2e", "").apply { delete(); mkdirs() }
        val srcFile = File(tmp, "LocalizedPage.kt").apply { writeText(result.source) }
        val outDir = File(tmp, "out").apply { mkdirs() }

        val messages = StringBuilder()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) messages.appendLine("$severity: $message")
            }
        }
        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(srcFile.absolutePath)
            destination = outDir.absolutePath
            classpath = System.getProperty("java.class.path")
            noStdlib = true
            noReflect = true
        }
        val exit = runEmbeddedCompiler(collector, args)
        assertEquals(ExitCode.OK, exit, "compile failed:\n$messages\n--- generated ---\n${result.source}")

        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader)
        val obj = loader.loadClass("gen.LocalizedPage").kotlin.objectInstance
            ?: error("LocalizedPage object instance not found")
        val source = bosca.bml.i18n.MessageSource.of(
            catalogs = mapOf(
                "es" to bosca.bml.i18n.MessageCatalog(
                    messages = mapOf(
                        "home.title" to "Bienvenido",
                        "greeting" to "¡Hola, {name}!",
                        "nav.close" to "Cerrar",
                    ),
                    plurals = mapOf(
                        "cart.items" to mapOf(
                            bosca.bml.i18n.PluralCategory.ONE to "un artículo",
                            bosca.bml.i18n.PluralCategory.OTHER to "{count} artículos",
                        ),
                    ),
                ),
            ),
        )
        // Deliberately a BARE render call — no withRenderContext here. The generated render
        // must self-install the ambient context for t() to resolve.
        val ctx = RenderContext(locale = java.util.Locale.forLanguageTag("es"), messages = source)
        val render = obj::class.functions.first { it.name == "render" }
        runBlocking { render.callSuspend(obj, ctx) }
        val html = ctx.toString()

        assertTrue(html.contains("<h1>Bienvenido</h1>"), "html was: $html")
        assertTrue(html.contains("¡Hola, Ada!"), "named args must format: $html")
        assertTrue(html.contains("""aria-label="Cerrar""""), "bound attr t() must localize: $html")
        assertTrue(html.contains("3 artículos"), "plural t() must select and format: $html")
    }

    @Test
    fun `t-attribute markup localizes, pluralizes, and falls back to authored text`() {
        val bml = """
            <page route="/t">
              <script server provides="user">"Ada"</script>
              <h1 t="welcome.back">Welcome back, { user }!</h1>
              <span t="cart.items" t:count="3">
                <t:one>You have one item</t:one>
                <t:other>You have { 3 } items</t:other>
              </span>
              <input t:placeholder="search.hint" placeholder="Search here"/>
              <p t="only.authored">Authored &amp; faithful</p>
            </page>
        """.trimIndent()

        val parsed = BmlParser.parse(bml)
        assertFalse(parsed.hasErrors, "parse: ${parsed.diagnostics}")
        val result = BmlCodeGenerator("gen", "TAttrPage", "tattr.bml").generate(parsed.document)
        assertFalse(result.diagnostics.any { it.severity == Severity.Error }, "codegen: ${result.diagnostics}")

        val tmp = File.createTempFile("bml-tattr-e2e", "").apply { delete(); mkdirs() }
        val srcFile = File(tmp, "TAttrPage.kt").apply { writeText(result.source) }
        val outDir = File(tmp, "out").apply { mkdirs() }

        val messages = StringBuilder()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) messages.appendLine("$severity: $message")
            }
        }
        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(srcFile.absolutePath)
            destination = outDir.absolutePath
            classpath = System.getProperty("java.class.path")
            noStdlib = true
            noReflect = true
        }
        val exit = runEmbeddedCompiler(collector, args)
        assertEquals(ExitCode.OK, exit, "compile failed:\n$messages\n--- generated ---\n${result.source}")

        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader)
        val obj = loader.loadClass("gen.TAttrPage").kotlin.objectInstance
            ?: error("TAttrPage object instance not found")
        val source = bosca.bml.i18n.MessageSource.of(
            catalogs = mapOf(
                "es" to bosca.bml.i18n.MessageCatalog(
                    messages = mapOf(
                        "welcome.back" to "¡Bienvenido, {user}!",
                        "search.hint" to "Buscar",
                    ),
                    plurals = mapOf(
                        "cart.items" to mapOf(
                            bosca.bml.i18n.PluralCategory.ONE to "un artículo",
                            bosca.bml.i18n.PluralCategory.OTHER to "{count} artículos",
                        ),
                    ),
                ),
            ),
        )

        // Translated render (es): catalog wins everywhere it has strings.
        val es = RenderContext(locale = java.util.Locale.forLanguageTag("es"), messages = source)
        val render = obj::class.functions.first { it.name == "render" }
        runBlocking { render.callSuspend(obj, es) }
        val esHtml = es.toString()
        assertTrue("¡Bienvenido, Ada!" in esHtml, "esHtml: $esHtml")
        assertTrue("3 artículos" in esHtml, "esHtml: $esHtml")
        assertTrue("""placeholder="Buscar"""" in esHtml, "esHtml: $esHtml")
        assertTrue("Authored &amp; faithful" in esHtml, "authored fallback must render (escaped): $esHtml")
        assertTrue("t=" !in esHtml && "t:count" !in esHtml, "t vocabulary must be stripped: $esHtml")

        // Untranslated render (en): every t path falls back to the authored text — never keys.
        val en = RenderContext(messages = source)
        runBlocking { render.callSuspend(obj, en) }
        val enHtml = en.toString()
        assertTrue("Welcome back, Ada!" in enHtml, "enHtml: $enHtml")
        assertTrue("You have 3 items" in enHtml, "authored OTHER form must apply: $enHtml")
        assertTrue("""placeholder="Search here"""" in enHtml, "enHtml: $enHtml")
        assertTrue("welcome.back" !in enHtml && "cart.items" !in enHtml, "keys must never render: $enHtml")
    }

    @Test
    fun `generated message compiles and renders its email channel`() {
        val bml = """
            <message key="welcome">
              <script server provides="course">
                import kotlinx.serialization.json.jsonObject
                import kotlinx.serialization.json.jsonPrimitive
                message.payload.jsonObject["course"]?.jsonPrimitive?.content ?: "?"
              </script>
              <email>
                <subject>Welcome, { message.recipientName ?: "friend" }!</subject>
                <html>
                  <body>
                    <h1>{ course }</h1>
                    <p>See you in class.</p>
                  </body>
                </html>
              </email>
            </message>
        """.trimIndent()

        val parsed = BmlParser.parse(bml)
        assertFalse(parsed.hasErrors, "parse: ${parsed.diagnostics}")
        val result = BmlCodeGenerator("gen", "WelcomeMessage", "welcome.bml").generate(parsed.document)
        assertFalse(result.diagnostics.any { it.severity == Severity.Error }, "codegen: ${result.diagnostics}")

        val tmp = File.createTempFile("bml-message-e2e", "").apply { delete(); mkdirs() }
        val srcFile = File(tmp, "WelcomeMessage.kt").apply { writeText(result.source) }
        val outDir = File(tmp, "out").apply { mkdirs() }

        val messages = StringBuilder()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) messages.appendLine("$severity: $message")
            }
        }
        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(srcFile.absolutePath)
            destination = outDir.absolutePath
            classpath = System.getProperty("java.class.path")
            noStdlib = true
            noReflect = true
        }
        val exit = runEmbeddedCompiler(collector, args)
        assertEquals(ExitCode.OK, exit, "compile failed:\n$messages\n--- generated ---\n${result.source}")

        // Load through a child classloader sharing core-bml via the parent — the hosting shape.
        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader)
        val template = loader.loadClass("gen.WelcomeMessage").kotlin.objectInstance
            as bosca.bml.message.BmlMessageTemplate
        val context = bosca.bml.message.BmlMessageContext(
            recipientName = "Ada",
            payload = kotlinx.serialization.json.Json.parseToJsonElement("""{"course":"Scripture Narrative"}"""),
        )
        val rendered = runBlocking { template.renderMessage(context) }.email ?: error("email channel missing")

        assertEquals("Welcome, Ada!", rendered.subject)
        assertTrue(rendered.html.contains("<h1>Scripture Narrative</h1>"), "html was: ${rendered.html}")
        assertTrue(rendered.text.contains("Scripture Narrative"), "text was: ${rendered.text}")
        assertFalse(rendered.text.contains("<h1>"), "text must drop markup: ${rendered.text}")
    }

    @Test
    fun `message email channels localize via the recipient locale and host message source`() {
        val bml = """
            <message key="welcome">
              <email>
                <subject>{ t("mail.subject") }</subject>
                <html>
                  <body>
                    <p t="mail.body">Thanks, { message.recipientName }!</p>
                  </body>
                </html>
              </email>
            </message>
        """.trimIndent()

        val parsed = BmlParser.parse(bml)
        assertFalse(parsed.hasErrors, "parse: ${parsed.diagnostics}")
        val result = BmlCodeGenerator("gen", "L10nMessage", "l10n.bml").generate(parsed.document)
        assertFalse(result.diagnostics.any { it.severity == Severity.Error }, "codegen: ${result.diagnostics}")

        val tmp = File.createTempFile("bml-l10n-message", "").apply { delete(); mkdirs() }
        val srcFile = File(tmp, "L10nMessage.kt").apply { writeText(result.source) }
        val outDir = File(tmp, "out").apply { mkdirs() }
        val messages = StringBuilder()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) messages.appendLine("$severity: $message")
            }
        }
        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(srcFile.absolutePath)
            destination = outDir.absolutePath
            classpath = System.getProperty("java.class.path")
            noStdlib = true
            noReflect = true
        }
        assertEquals(ExitCode.OK, runEmbeddedCompiler(collector, args), "compile failed:\n$messages\n${result.source}")

        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader)
        val template = loader.loadClass("gen.L10nMessage").kotlin.objectInstance
            as bosca.bml.message.BmlMessageTemplate
        val source = bosca.bml.i18n.MessageSource.of(
            catalogs = mapOf(
                "es" to bosca.bml.i18n.MessageCatalog(
                    messages = mapOf(
                        "mail.subject" to "Bienvenido",
                        "mail.body" to "¡Gracias, {recipientName}!",
                    ),
                ),
            ),
        )

        // Localized: the recipient's locale + the host's source drive both subject and body.
        val es = runBlocking {
            template.renderMessage(
                bosca.bml.message.BmlMessageContext(
                    recipientName = "Ada",
                    locale = "es",
                    messages = source,
                ),
            ).email ?: error("email channel missing")
        }
        assertEquals("Bienvenido", es.subject)
        assertTrue("¡Gracias, Ada!" in es.html, "es html: ${es.html}")

        // Unconfigured host: authored fallback for t markup; the t() function renders its key.
        val plain = runBlocking {
            template.renderMessage(bosca.bml.message.BmlMessageContext(recipientName = "Ada"))
        }.email ?: error("email channel missing")
        assertEquals("mail.subject", plain.subject)
        assertTrue("Thanks, Ada!" in plain.html, "plain html: ${plain.html}")
    }

    /** Runs the embedded Kotlin compiler reflectively (avoids a literal shell-exec token). */
    private fun runEmbeddedCompiler(collector: MessageCollector, args: K2JVMCompilerArguments): ExitCode {
        val compiler = K2JVMCompiler()
        // Select by parameter TYPE, not count: CLITool has another 3-arg overload of the same
        // name taking (PrintStream, MessageRenderer, vararg String), and getMethods() order is
        // unspecified per JVM run — picking by count alone flaked with "argument type mismatch".
        val method = compiler.javaClass.methods.first {
            it.name == "exec" && it.parameterTypes.firstOrNull() == MessageCollector::class.java
        }
        return method.invoke(compiler, collector, Services.EMPTY, args) as ExitCode
    }
}
