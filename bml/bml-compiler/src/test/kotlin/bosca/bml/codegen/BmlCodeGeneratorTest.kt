package bosca.bml.codegen

import bosca.bml.parser.BmlParser
import bosca.bml.parser.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BmlCodeGeneratorTest {

    private fun gen(src: String, path: String = "x.bml", obj: String = "XPage"): CodeGenResult {
        val parsed = BmlParser.parse(src)
        assertFalse(parsed.hasErrors, "parse errors: ${parsed.diagnostics}")
        return BmlCodeGenerator(packageName = "test", objectName = obj, sourcePath = path).generate(parsed.document)
    }

    private fun assertHas(haystack: String, needle: String) =
        assertTrue(haystack.contains(needle), "expected generated source to contain:\n  $needle\n--- actual ---\n$haystack")

    @Test
    fun `page header, server provides, and interpolation`() {
        val src = gen("""<page route="/lists/{id}"><script server provides="m">load(id)</script><h1>{ m.title }</h1></page>""").source
        assertHas(src, "package test")
        assertHas(src, """@BmlGenerated(source = "x.bml")""")
        assertHas(src, """@BmlPage(route = "/lists/{id}")""")
        assertHas(src, "public object XPage : bosca.bml.render.BmlPageRenderer {")
        assertHas(src, """override val renderRevision: String = "x.bml"""")
        assertHas(src, "override suspend fun render(ctx: RenderContext) {")
        assertHas(src, "val m = run {")
        assertHas(src, "load(id)")
        assertHas(src, """w.markup("<h1>")""")
        assertHas(src, "w.text(m.title)")
        assertHas(src, """w.markup("</h1>")""")
    }

    @Test
    fun `attribute forms`() {
        val src = gen("""<page route="/"><a class="c" :href="u" data-x {...r}>t</a></page>""").source
        // static name+attrs coalesce into one markup call; dynamic attrs flush it
        assertHas(src, """w.markup("<a class=\"c\"")""")
        assertHas(src, """w.attr("href", (u))""")
        assertHas(src, """w.markup(" data-x")""")
        assertHas(src, "w.spread((r))")
        assertHas(src, """w.markup(">")""")
        assertHas(src, """w.markup("t")""")
        assertHas(src, """w.markup("</a>")""")
    }

    @Test
    fun `interpolated attribute value`() {
        val src = gen("""<page route="/"><div class="a-{ x }">t</div></page>""").source
        assertHas(src, """w.attr("class", "a-" + (x))""")
    }

    @Test
    fun `raw interpolation`() {
        val src = gen("""<page route="/"><div>{@ trusted }</div></page>""").source
        assertHas(src, "w.raw(trusted)")
    }

    @Test
    fun `for and if lowering`() {
        val src = gen(
            """<page route="/"><for (i, x) in items><li>{ x }</li></for><if a><p>A</p><else><p>B</p></if></page>""",
        ).source
        assertHas(src, "for ((i, x) in (items).withIndex()) {")
        assertHas(src, """w.markup("<li>")""")
        assertHas(src, "w.text(x)")
        assertHas(src, "if (a) {")
        assertHas(src, "} else {")
    }

    @Test
    fun `void element has no closing tag`() {
        val src = gen("""<page route="/"><br></page>""").source
        assertHas(src, """w.markup("<br>")""")
        assertTrue(!src.contains("</br>"), "void element must not emit a closing tag")
    }

    @Test
    fun `data binding lowers to a val`() {
        val src = gen("""<page route="/"><data provides="profile">{ bosca.query(GetProfile(id)).profile }</data></page>""").source
        assertHas(src, "val profile = run { bosca.query(GetProfile(id)).profile }")
    }

    @Test
    fun `inject binding lowers to Bosca DI provide in the page scope`() {
        val src = gen(
            """<page route="/"><script server provides="instant">clock.instant()</script>""" +
                """<p>{ instant }</p><inject name="clock" type="java.time.Clock"/></page>""",
        ).source
        assertHas(src, "val clock: java.time.Clock = bosca.di.provide<java.time.Clock>()")
        assertHas(src, "clock.instant()")
        assertTrue(src.indexOf("val clock:") < src.indexOf("val instant = run"), "inject must precede server initializers:\n$src")
        assertFalse(src.contains("<inject"), "inject declarations must not render markup")
    }

    @Test
    fun `inject can select a named Bosca DI provider`() {
        val src = gen(
            """<page route="/"><inject name="clock" type="java.time.Clock" provider="utc" init="start"/><p>{ clock }</p></page>""",
        ).source
        assertHas(src, "val clock: java.time.Clock = bosca.di.provide<java.time.Clock>(\"utc\")")
        assertHas(src, "clock.start()")
    }

    @Test
    fun `component inject is emitted inside only the component render scope`() {
        val src = gen(
            """<component tag="stamp"><inject name="clock" type="java.time.Clock"/><span>{ clock.instant() }</span></component>""",
        ).source
        assertHas(src, "override suspend fun render(ctx: RenderContext, props: Map<String, Any?>")
        assertHas(src, "val clock: java.time.Clock = bosca.di.provide<java.time.Clock>()")
        assertHas(src, "w.text(clock.instant())")
    }

    @Test
    fun `message inject is shared by its channel render`() {
        val result = gen(
            """<message key="notice"><inject name="clock" type="java.time.Clock"/>""" +
                """<email><subject>Notice</subject><p>{ clock.instant() }</p></email></message>""",
            obj = "NoticeMessage",
        )
        assertFalse(result.diagnostics.any { it.severity == Severity.Error }, result.diagnostics.toString())
        assertHas(result.source, "val clock: java.time.Clock = bosca.di.provide<java.time.Clock>()")
        assertHas(result.source, "w.text(clock.instant())")
    }

    @Test
    fun `island wraps with a mount marker and drops the client script`() {
        val src = gen(
            """<page route="/"><island name="s" client="ts"><ul>x</ul><script client>const a = 1;</script></island></page>""",
        ).source
        assertHas(src, """w.markup("<div data-bml-island=\"s\"")""")
        assertHas(src, """w.markup("<ul>")""")
        assertTrue(!src.contains("const a = 1"), "client script must not be rendered into HTML")
        // A page with a client script advertises its bundle so the server can serve/reference it.
        assertHas(src, """override val clientModule: String = "XPage.js"""")
    }

    @Test
    fun `deferred island emits a public shell and a separately registered renderer`() {
        val result = gen(
            """<page route="/accounts/{id}">""" +
                """<island name="account-panel" render="deferred" :accountId="id">""" +
                """<prop name="accountId" type="String" required/>""" +
                """<fallback><p>Loading account</p></fallback>""" +
                """<script server provides="account">loadAccount(accountId)</script>""" +
                """<section>{ account.name }</section>""" +
                """</island></page>""",
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        val src = result.source
        assertHas(src, """override val clientModule: String = "XPage.js"""")
        assertHas(src, """override val deferredRenderers: List<bosca.bml.render.BmlDeferredRenderer> = listOf(AccountPanelDeferredRenderer)""")
        assertHas(src, """data-bml-page=\"/accounts/{id}\"""")
        assertHas(src, """w.attr("data-bml-deferred", "XPage:account-panel")""")
        assertHas(src, """w.attr("data-bml-props", bosca.bml.render.deferredPropsToJson(mapOf("accountId" to kotlin.run<String> { (id) })))""")
        assertHas(src, """data-bml-deferred-state=\"pending\" aria-busy=\"true\">""")
        assertHas(src, """public object AccountPanelDeferredRenderer : bosca.bml.render.BmlDeferredRenderer""")
        assertHas(src, """override val pageRoute: String = "/accounts/{id}"""")
        assertHas(
            src,
            """val accountId: String = bosca.bml.render.requiredDeferredProp<String>(props, "accountId", "String")""",
        )
        assertTrue(
            src.indexOf("Loading account") < src.indexOf("loadAccount(accountId)"),
            "the fallback belongs to the shell and the private loader belongs to the deferred renderer:\n$src",
        )
    }

    @Test
    fun `deferred island inputs require typed prop declarations`() {
        val result = gen(
            """<page route="/"><island name="private" render="deferred" :accountId="id"><p>x</p></island></page>""",
        )
        assertTrue(
            result.diagnostics.any { it.severity == Severity.Error && "matching <prop>" in it.message },
            result.diagnostics.toString(),
        )
    }

    @Test
    fun `deferred props cannot shadow generated renderer locals`() {
        for (name in listOf("ctx", "props", "w")) {
            val result = gen(
                """<page route="/"><island name="private" render="deferred" :$name="$name">""" +
                    """<prop name="$name" type="String" required/><p>{ $name }</p></island></page>""",
            )

            assertTrue(
                result.diagnostics.any {
                    it.severity == Severity.Error &&
                        "Deferred prop '$name' conflicts with a generated renderer local" in it.message
                },
                result.diagnostics.toString(),
            )
        }
    }

    @Test
    fun `deferred generic props emit complete wire type validation`() {
        val result = gen(
            """<page route="/"><island name="private" render="deferred" :ids="ids">""" +
                """<prop name="ids" type="List<Map<String, Long>>" required/>""" +
                """<p>{ ids.size }</p></island></page>""",
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        assertHas(
            result.source,
            """requiredDeferredProp<List<Map<String, Long>>>(props, "ids", "List<Map<String, Long>>")""",
        )
    }

    @Test
    fun `qualified string deferred prop defaults remain string literals`() {
        val result = gen(
            """<page route="/"><island name="private" render="deferred">""" +
                """<prop name="label" type="kotlin.String" default="Account"/>""" +
                """<p>{ label }</p></island></page>""",
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        assertHas(
            result.source,
            """deferredPropOrDefault<kotlin.String>(props, "label", "kotlin.String") { "Account" }""",
        )
    }

    @Test
    fun `deferred island inputs reject type aliases before runtime`() {
        val result = gen(
            """<page route="/"><island name="private" render="deferred" :accountId="id">""" +
                """<prop name="accountId" type="AccountId" required/>""" +
                """<p>{ accountId }</p></island></page>""",
        )

        assertTrue(
            result.diagnostics.any {
                it.severity == Severity.Error &&
                    "Deferred prop 'accountId' type 'AccountId' is not a supported wire type" in it.message
            },
            result.diagnostics.toString(),
        )
    }

    @Test
    fun `deferred islands cannot be declared under control flow`() {
        val result = gen(
            """<page route="/">""" +
                """<if show><island name="conditional" render="deferred"><p>Conditional</p></island></if>""" +
                """<for item in items><island name="repeated" render="deferred"><p>Repeated</p></island></for>""" +
                """</page>""",
        )

        assertEquals(
            2,
            result.diagnostics.count {
                it.severity == Severity.Error && "cannot be declared inside <if> or <for>" in it.message
            },
            result.diagnostics.toString(),
        )
    }

    @Test
    fun `deferred and eager islands cannot share a client registry name`() {
        val result = gen(
            """<page route="/">""" +
                """<island name="account"><p>Public</p></island>""" +
                """<island name="account" render="deferred"><p>Private</p></island>""" +
                """</page>""",
        )

        assertTrue(
            result.diagnostics.any {
                it.severity == Severity.Error && "also used by an eager island" in it.message
            },
            result.diagnostics.toString(),
        )
    }

    @Test
    fun `deferred islands cannot be declared in fallbacks directly or through components`() {
        val direct = gen(
            """<page route="/"><island name="outer" render="deferred"><fallback>""" +
                """<island name="inner" render="deferred"><p>Private</p></island>""" +
                """</fallback><p>Outer</p></island></page>""",
        )
        assertTrue(
            direct.diagnostics.any { it.severity == Severity.Error && "inside <fallback>" in it.message },
            direct.diagnostics.toString(),
        )

        val throughComponent = gen(
            """<component tag="private-card">""" +
                """<island name="private" render="deferred"><p>Private</p></island></component>""" +
                """<page route="/"><island name="outer" render="deferred"><fallback>""" +
                """<private-card/></fallback><p>Outer</p></island></page>""",
        )
        assertTrue(
            throughComponent.diagnostics.any {
                it.severity == Severity.Error && "inside <fallback>" in it.message
            },
            throughComponent.diagnostics.toString(),
        )
    }

    @Test
    fun `deferred fallbacks reject client scripts and declarative actions`() {
        val script = gen(
            """<page route="/"><island name="private" render="deferred"><fallback>""" +
                """<button>Retry</button><script client>console.log("retry")</script>""" +
                """</fallback><p>Private</p></island></page>""",
        )
        val action = gen(
            """<page route="/"><island name="private" render="deferred"><fallback>""" +
                """<button @click="account.retry">Retry</button>""" +
                """</fallback><p>Private</p></island></page>""",
        )

        for (result in listOf(script, action)) {
            assertTrue(
                result.diagnostics.any {
                    it.severity == Severity.Error &&
                        "Deferred <fallback> content cannot contain <script client> or declarative actions" in it.message
                },
                result.diagnostics.toString(),
            )
        }

        val throughComponent = gen(
            """<component tag="interactive-card"><button @click="account.retry">Retry</button></component>""" +
                """<page route="/"><island name="private" render="deferred"><fallback>""" +
                """<interactive-card/></fallback><p>Private</p></island></page>""",
        )
        assertTrue(
            throughComponent.diagnostics.any {
                it.severity == Severity.Error &&
                    "Deferred <fallback> content cannot contain <script client> or declarative actions" in it.message
            },
            throughComponent.diagnostics.toString(),
        )
    }

    @Test
    fun `messages reject direct and component-transitive deferred islands`() {
        val direct = gen(
            """<message key="direct"><email><subject>Direct</subject>""" +
                """<island name="private" render="deferred"><p>Private</p></island>""" +
                """</email></message>""",
            obj = "DirectMessage",
        )
        assertTrue(
            direct.diagnostics.any { it.severity == Severity.Error && "unavailable in <message>" in it.message },
            direct.diagnostics.toString(),
        )

        val throughComponent = gen(
            """<component tag="private-card">""" +
                """<island name="private" render="deferred"><p>Private</p></island></component>""" +
                """<component tag="card-wrapper"><private-card/></component>""" +
                """<message key="component"><email><subject>Component</subject><card-wrapper/></email></message>""",
            obj = "ComponentMessage",
        )
        assertTrue(
            throughComponent.diagnostics.any {
                it.severity == Severity.Error && "unavailable in <message>" in it.message
            },
            throughComponent.diagnostics.toString(),
        )
    }

    @Test
    fun `component deferred renderer names include their owning component`() {
        val result = gen(
            """<component tag="account-shell"><island name="private" render="deferred"><p>Account</p></island></component>""" +
                """<component tag="profile-shell"><island name="private" render="deferred"><p>Profile</p></island></component>""" +
                """<page route="/"><account-shell/><profile-shell/></page>""",
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        assertHas(result.source, "public object AccountShell_PrivateDeferredRenderer")
        assertHas(result.source, "public object ProfileShell_PrivateDeferredRenderer")
        assertHas(result.source, """data-bml-island=\"component:account-shell:private\"""")
        assertHas(result.source, """data-bml-island=\"component:profile-shell:private\"""")
    }

    @Test
    fun `deferred island body cannot capture its component slot`() {
        val invalid = gen(
            """<component tag="private-card"><island name="private" render="deferred"><slot/></island></component>""" +
                """<page route="/"><private-card>Account</private-card></page>""",
        )
        assertTrue(
            invalid.diagnostics.any {
                it.severity == Severity.Error && "slot content cannot cross the deferred request boundary" in it.message
            },
            invalid.diagnostics.toString(),
        )

        val fallback = gen(
            """<component tag="private-card"><island name="private" render="deferred">""" +
                """<fallback><slot/></fallback><p>Private</p></island></component>""" +
                """<page route="/"><private-card>Loading</private-card></page>""",
        )
        assertTrue(fallback.diagnostics.none { it.severity == Severity.Error }, fallback.diagnostics.toString())
    }

    @Test
    fun `component deferred renderer names cannot collide across owner and island boundaries`() {
        val result = gen(
            """<component tag="alpha-beta"><island name="gamma" render="deferred"><p>One</p></island></component>""" +
                """<component tag="alpha"><island name="beta-gamma" render="deferred"><p>Two</p></island></component>""" +
                """<page route="/"><alpha-beta/><alpha/></page>""",
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        assertHas(result.source, "public object AlphaBeta_GammaDeferredRenderer")
        assertHas(result.source, "public object Alpha_BetaGammaDeferredRenderer")
    }

    @Test
    fun `deferred scope owns live actions and server session state`() {
        val result = gen(
            """<page route="/account"><island name="private" render="deferred">""" +
                """<script server provides="counter" scope="server-session">Counter()</script>""" +
                """<island name="counter-view"><span>{ counter.value }</span></island>""" +
                """<button @click="counter.increment">+</button>""" +
                """</island></page>""",
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        assertHas(result.source, "override val hasServerState: Boolean = true")
        assertHas(
            result.source,
            "override val islandActionDispatchers: List<bosca.bml.render.BmlIslandActionDispatcher> = listOf(CounterStateDispatcher)",
        )
        assertHas(
            result.source,
            """ctx.session?.putIfAbsent(bosca.bml.render.bmlPageSessionStateKey(ctx.requestPath, "counter"), bosca.bml.render.encodeState(counter))""",
        )
        assertHas(result.source, "public object CounterStateDispatcher : bosca.bml.render.BmlIslandActionDispatcher")
    }

    @Test
    fun `eager and deferred scopes cannot publish the same live state key`() {
        val result = gen(
            """<page route="/account">""" +
                """<script server provides="counter">Counter()</script>""" +
                """<island name="eager-view"><span>{ counter.value }</span></island>""" +
                """<button @click="counter.increment">+</button>""" +
                """<island name="private" render="deferred">""" +
                """<script server provides="counter">Counter()</script>""" +
                """<island name="deferred-view"><span>{ counter.value }</span></island>""" +
                """<button @click="counter.increment">+</button>""" +
                """</island></page>""",
        )

        assertTrue(
            result.diagnostics.any {
                it.severity == Severity.Error &&
                    "live state `counter` is declared in more than one eager or deferred render scope" in it.message
            },
            result.diagnostics.toString(),
        )
    }

    @Test
    fun `shared page declares edge freshness and rejects identity-dependent eager work`() {
        val shared = gen(
            """<page route="/news" cache="shared" maxAge="300" staleWhileRevalidate="900">""" +
                """<h1>News</h1></page>""",
        )
        assertTrue(shared.diagnostics.none { it.severity == Severity.Error }, shared.diagnostics.toString())
        assertHas(shared.source, "override val sharedCacheMaxAgeSeconds: Long = 300L")
        assertHas(shared.source, "override val sharedCacheStaleWhileRevalidateSeconds: Long = 900L")

        val defaultStaleWindow = gen(
            """<page route="/news" cache="shared" maxAge="300"><h1>News</h1></page>""",
        )
        assertTrue(
            defaultStaleWindow.diagnostics.none { it.severity == Severity.Error },
            defaultStaleWindow.diagnostics.toString(),
        )
        assertFalse(defaultStaleWindow.source.contains("override val sharedCacheStaleWhileRevalidateSeconds"))

        val invalidStaleWindow = gen(
            """<page route="/news" cache="shared" maxAge="300" staleWhileRevalidate="0"><h1>News</h1></page>""",
        )
        assertTrue(invalidStaleWindow.diagnostics.any { "requires a positive literal" in it.message })

        val unsharedStaleWindow = gen(
            """<page route="/news" staleWhileRevalidate="60"><h1>News</h1></page>""",
        )
        assertTrue(unsharedStaleWindow.diagnostics.any { "requires cache=\"shared\"" in it.message })

        val guarded = gen("""<page route="/news" cache="shared" maxAge="300" requireAuth><h1>News</h1></page>""")
        assertTrue(guarded.diagnostics.any { "cannot requireAuth" in it.message })

        val stateful = gen(
            """<page route="/news" cache="shared" maxAge="300">""" +
                """<script server provides="counter" scope="server-session">Counter()</script>""" +
                """<island name="counter"><span>{ counter.value }</span></island>""" +
                """<button @click="counter.increment">+</button></page>""",
        )
        assertTrue(stateful.diagnostics.any { "server-session state in its render closure" in it.message })

        val deferredState = gen(
            """<page route="/news" cache="shared" maxAge="300">""" +
                """<island name="private" render="deferred">""" +
                """<script server provides="counter" scope="server-session">Counter()</script>""" +
                """<island name="counter"><button @click="counter.increment">+</button></island>""" +
                """</island></page>""",
        )
        assertTrue(
            deferredState.diagnostics.any { "server-session state in its render closure" in it.message },
            deferredState.diagnostics.toString(),
        )

        val componentState = gen(
            """<component tag="private-counter"><prop name="id" type="String" required/>""" +
                """<script server provides="counter" scope="server-session">Counter()</script>""" +
                """<island name="counter" :key="id"><button @click="counter.increment">+</button></island>""" +
                """</component><page route="/news" cache="shared" maxAge="300">""" +
                """<private-counter id="one"/></page>""",
        )
        assertTrue(
            componentState.diagnostics.any { "server-session state in its render closure" in it.message },
            componentState.diagnostics.toString(),
        )

        val componentFlag = gen(
            """<component tag="offer"><if flag="private-offer"><p>Offer</p></if></component>""" +
                """<page route="/news" cache="shared" maxAge="300"><offer/></page>""",
        )
        assertTrue(
            componentFlag.diagnostics.any { "feature flags through a component" in it.message },
            componentFlag.diagnostics.toString(),
        )

        val deferredFlag = gen(
            """<page route="/news" cache="shared" maxAge="300">""" +
                """<island name="offer" render="deferred"><if flag="private-offer"><p>Offer</p></if></island>""" +
                """</page>""",
        )
        assertTrue(
            deferredFlag.diagnostics.none { it.severity == Severity.Error },
            deferredFlag.diagnostics.toString(),
        )

        val fallbackFlag = gen(
            """<page route="/news" cache="shared" maxAge="300">""" +
                """<island name="offer" render="deferred"><fallback>""" +
                """<if flag="loading-offer"><p>Loading</p></if></fallback><p>Offer</p></island>""" +
                """</page>""",
        )
        assertTrue(
            fallbackFlag.diagnostics.any { "shared-cache shell cannot evaluate" in it.message },
            fallbackFlag.diagnostics.toString(),
        )
    }

    @Test
    fun `feature flag validation ignores Kotlin strings and comments`() {
        val page = gen(
            """<page route="/" cache="shared" maxAge="60">""" +
                """<script server provides="label">"ctx.featureFlags.enabled(\"offer\")"</script>""" +
                """<script server provides="enabled">run { /* ctx.featureFlags.enabled("offer") */ true }</script>""" +
                """<p>{ label } { enabled }</p></page>""",
        )
        assertTrue(
            page.diagnostics.none { it.severity == Severity.Error },
            page.diagnostics.toString(),
        )

        val component = gen(
            """<component tag="offer"><p>{ "currentRenderContext().featureFlags.enabled(\"offer\")" }</p></component>""" +
                """<page route="/"><offer/></page>""",
        )
        assertTrue(component.diagnostics.none { it.severity == Severity.Error }, component.diagnostics.toString())
        assertFalse(component.source.contains("hasEagerFeatureFlags = true"), component.source)

        val deferred = gen(
            """<page route="/" cache="shared" maxAge="60">""" +
                """<island name="offer" render="deferred">""" +
                """<p>{ currentRenderContext().featureFlags.enabled("offer") }</p></island></page>""",
        )
        assertTrue(deferred.diagnostics.none { it.severity == Severity.Error }, deferred.diagnostics.toString())
    }

    @Test
    fun `shared shell rejects feature flag evaluation in server code, interpolation, and attributes`() {
        val cases = mapOf(
            "server script" to """<script server provides="offer">ctx.featureFlags.enabled("offer")</script><p>{ offer }</p>""",
            "interpolation" to """<p>{ currentRenderContext().featureFlags.enabled("offer") }</p>""",
            "bound attribute" to """<p :hidden="!ctx.featureFlags.enabled(offerKey)">Offer</p>""",
            "interpolated attribute" to """<p class="offer-{ ctx.featureFlags.enabled(offerKey) }">Offer</p>""",
            "string template" to """<p>{ "offer=${'$'}{ctx.featureFlags.enabled("offer")}" }</p>""",
            "negated condition" to """<if !ctx.featureFlags.enabled(offerKey)><p>Offer</p></if>""",
            "loop iterable" to """<for offer in ctx.featureFlags.enabledKeys()><p>{ offer }</p></for>""",
            "deferred input" to """<island name="offer" render="deferred" :enabled="ctx.featureFlags.enabled(offerKey)">""" +
                """<prop name="enabled" type="Boolean"/><p>{ enabled }</p></island>""",
        )
        for ((label, body) in cases) {
            val result = gen("""<page route="/" cache="shared" maxAge="60">$body</page>""")
            assertTrue(
                result.diagnostics.any { "shared-cache shell cannot evaluate" in it.message },
                "$label: ${result.diagnostics}",
            )
        }

        val component = gen(
            """<component tag="offer"><script server provides="enabled">ctx.featureFlags.enabled("offer")</script>""" +
                """<p>{ enabled }</p></component><page route="/"><offer/></page>""",
        )
        assertHas(component.source, "hasEagerFeatureFlags = true")

        val deferredBody = gen(
            """<page route="/" cache="shared" maxAge="60"><island name="offer" render="deferred">""" +
                """<script server provides="enabled">ctx.featureFlags.enabled("offer")</script>""" +
                """<p>{ enabled }</p></island></page>""",
        )
        assertTrue(deferredBody.diagnostics.none { it.severity == Severity.Error }, deferredBody.diagnostics.toString())

        val action = gen(
            """<page route="/" cache="shared" maxAge="60">""" +
                """<script server provides="counter">Counter()</script>""" +
                """<island name="counter"><span>{ counter.n }</span></island>""" +
                """<button @click="counter.inc(ctx.featureFlags.enabled(offerKey))">+</button></page>""",
        )
        assertTrue(
            action.diagnostics.any { "shared-cache shell cannot evaluate" in it.message },
            "action arguments are evaluated into data-bml-args during the shell render: ${action.diagnostics}",
        )
    }

    @Test
    fun `literal renderFragment targets join the component closure but not the eager closure`() {
        val result = gen(
            """<component tag="cart-panel"><p>Cart</p></component>""" +
                """<component tag="unused"><p>Unused</p></component>""" +
                """<page route="/"><main>Shop</main><script client>""" +
                """const quote = /["']/; function ready() {} /["']/.test(quote); """ +
                """const html = await renderFragment("cart-panel", {}); renderFragment(`missing-tag`)""" + "\n" +
                """const unusedPattern = /renderFragment("unused")/""" + "\n" +
                """// renderFragment("unused")""" + "\n" +
                """const label = "renderFragment('unused')"""" +
                """</script></page>""",
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        assertHas(result.source, """override val componentTags: List<String> = listOf("cart-panel")""")
        assertHas(result.source, """override val eagerComponentTags: List<String> = listOf()""")
    }

    @Test
    fun `static deferred inputs are converted to the declared prop type at compile time`() {
        fun deferredInput(attribute: String, type: String) = gen(
            """<page route="/"><island name="stats" render="deferred" $attribute>""" +
                """<prop name="value" type="$type"/><p>{ value }</p></island></page>""",
        )
        fun emitted(attribute: String, type: String, value: String) {
            val result = deferredInput(attribute, type)
            assertTrue(result.diagnostics.none { it.severity == Severity.Error }, "$attribute as $type: ${result.diagnostics}")
            assertHas(result.source, """"value" to $value)""")
        }

        emitted("""value="3"""", "Int", "3")
        emitted("""value=" -7 """", "Int?", "-7")
        emitted("""value="-2147483648"""", "Int", "Int.MIN_VALUE")
        emitted("""value="9007199254740993"""", "Long", "9007199254740993L")
        emitted("""value="12"""", "Short", "(12).toShort()")
        emitted("""value="-8"""", "kotlin.Byte", "(-8).toByte()")
        emitted("""value="1.5"""", "Double", "1.5")
        emitted("""value="1.5"""", "Float", "1.5f")
        emitted("""value="false"""", "Boolean", "false")
        emitted("""value="x"""", "Char", "'\\u0078'")
        emitted("""value="7"""", "Number", "7")
        emitted("""value="acct-7"""", "String", "\"acct-7\"")
        emitted("""value="a${'$'}b"""", "Any", "\"a\\${'$'}b\"")
        emitted("value", "Boolean", "true")
        emitted("""value="acct-{ 7 }"""", "String", "(\"acct-\" + (7))")
        // Author expressions keep their own type, which the Kotlin compiler checks against the prop type.
        emitted("""value="{ 3 }"""", "Int", "kotlin.run<Int> { ((3)) }")
        emitted(""":value="listOf(1L)"""", "List<Long>", "kotlin.run<List<Long>> { (listOf(1L)) }")

        fun rejected(attribute: String, type: String, reason: String) {
            val result = deferredInput(attribute, type)
            assertTrue(
                result.diagnostics.any {
                    it.severity == Severity.Error &&
                        "Deferred prop 'value' has type '$type'" in it.message && reason in it.message
                },
                "$attribute as $type: ${result.diagnostics}",
            )
        }

        rejected("""value="abc"""", "Int", "'abc' is not a valid Int literal")
        rejected("""value="2147483648"""", "Int", "not a valid Int literal")
        rejected("""value="yes"""", "Boolean", "not a valid Boolean literal")
        rejected("""value="NaN"""", "Double", "not a valid finite Double literal")
        rejected("""value="xy"""", "Char", "not a valid single-character Char literal")
        rejected("value", "String", "a bare attribute supplies Boolean `true`")
        rejected("""value="x{ 7 }"""", "Int", "text containing an interpolation is a String")
        rejected("""value="1"""", "List<Long>", "static text cannot express that type; bind a typed value with :value=")
    }

    @Test
    fun `component props are converted and type-checked against their declarations`() {
        val result = gen(
            """<component tag="badge"><prop name="count" type="Int"/><prop name="open" type="Boolean"/>""" +
                """<prop name="ratio" type="Double?"/><prop name="label" type="String"/>""" +
                """<prop name="row" type="app.Row"/><prop name="loose"/><span>{ count }</span></component>""" +
                """<page route="/"><badge count="3" open="false" ratio="0.25" label="7" :row="rowValue" """ +
                """loose="x" untyped="1"/></page>""",
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        assertHas(
            result.source,
            """BadgeComponent.render(ctx, mapOf("count" to 3, "open" to false, "ratio" to 0.25, "label" to "7", """ +
                """"row" to kotlin.run<app.Row> { (rowValue) }, "loose" to "x", "untyped" to "1")) {}""",
        )
    }

    @Test
    fun `a component prop with a default accepts a nullable value`() {
        val result = gen(
            """<component tag="shell"><prop name="unsubscribe" type="String" default="#"/>""" +
                """<prop name="title" type="String" required/><p>{ title }{ unsubscribe }</p></component>""" +
                """<page route="/"><shell :unsubscribe="maybeUrl" :title="name"/></page>""",
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        // null selects the component's default, so the check allows String?; a required prop stays String.
        assertHas(
            result.source,
            """ShellComponent.render(ctx, mapOf("unsubscribe" to kotlin.run<String?> { (maybeUrl) }, """ +
                """"title" to kotlin.run<String> { (name) })) {}""",
        )
    }

    @Test
    fun `component values from another file are type-checked only when the type resolves without imports`() {
        val crossFile = BmlCodeGenerator(
            "bml.generated",
            "XPage",
            "x.bml",
            mapOf("badge" to "BadgeComponent"),
            componentPropTypes = mapOf(
                "badge" to mapOf(
                    "count" to "Long",
                    "row" to "Row",
                    "nested" to "Feed.TuneRow",
                    "qualified" to "com.example.web.Article?",
                    "rows" to "List<out com.example.web.Article>",
                ),
            ),
        ).generate(
            BmlParser.parse(
                """<page route="/"><badge count="3" :row="r" :nested="n" :qualified="q" :rows="rs"/></page>""",
            ).document,
        )

        assertTrue(crossFile.diagnostics.none { it.severity == Severity.Error }, crossFile.diagnostics.toString())
        assertHas(
            crossFile.source,
            """BadgeComponent.render(ctx, mapOf("count" to 3L, "row" to (r), """ +
                """"nested" to (n), "qualified" to kotlin.run<com.example.web.Article?> { (q) }, """ +
                """"rows" to kotlin.run<List<out com.example.web.Article>> { (rs) })) {}""",
        )
    }

    @Test
    fun `component values that can never match the declared type are compile errors`() {
        fun call(attributes: String) = gen(
            """<component tag="badge"><prop name="count" type="Int"/><prop name="row" type="app.Row"/>""" +
                """<span>{ count }</span></component><page route="/"><badge $attributes/></page>""",
        )
        fun assertRejected(attributes: String, reason: String) {
            val result = call(attributes)
            assertTrue(
                result.diagnostics.any { it.severity == Severity.Error && "Component <badge> prop" in it.message && reason in it.message },
                "$attributes: ${result.diagnostics}",
            )
        }

        assertRejected("""count="three"""", "static value 'three' is not a valid Int literal")
        assertRejected("""count="n-{ 1 }"""", "text containing an interpolation is a String; bind a typed value with :count=")
        assertRejected("count", "a bare attribute supplies Boolean `true`; bind a typed value with :count=")
    }

    @Test
    fun `static values for unrecognized component types are left to the Kotlin type check`() {
        // An application type may be an alias or supertype of String/Boolean, so BML cannot reject
        // static text or `true` for it; the Kotlin compiler checks the value against the real type.
        val local = gen(
            """<component tag="doc-link"><prop name="href" type="Url"/><prop name="wide" type="Flag"/>""" +
                """<prop name="key" type="java.io.Serializable"/><a>{ href }</a></component>""" +
                """<page route="/"><doc-link href="/docs" wide key="k-{ 1 }"/></page>""",
        )
        assertTrue(local.diagnostics.none { it.severity == Severity.Error }, local.diagnostics.toString())
        assertHas(
            local.source,
            """DocLinkComponent.render(ctx, mapOf("href" to kotlin.run<Url> { "/docs" }, """ +
                """"wide" to kotlin.run<Flag> { true }, "key" to kotlin.run<java.io.Serializable> { ("k-" + (1)) })) {}""",
        )

        // From another file a short type name may rely on that file's imports: pass it unchecked, as before.
        val crossFile = BmlCodeGenerator(
            "bml.generated",
            "XPage",
            "x.bml",
            mapOf("doc-link" to "DocLinkComponent"),
            componentPropTypes = mapOf("doc-link" to mapOf("href" to "Url")),
        ).generate(BmlParser.parse("""<page route="/"><doc-link href="/docs"/></page>""").document)
        assertTrue(crossFile.diagnostics.none { it.severity == Severity.Error }, crossFile.diagnostics.toString())
        assertHas(crossFile.source, """DocLinkComponent.render(ctx, mapOf("href" to "/docs")) {}""")
    }

    @Test
    fun `literal prop defaults are converted for numeric and Boolean props only`() {
        val result = gen(
            """<component tag="gauge">""" +
                """<prop name="ratio" type="Float" default="1.5"/>""" +
                """<prop name="scale" type="Double" default="3"/>""" +
                """<prop name="label" type="CharSequence" default="abc"/>""" +
                """<prop name="admin" type="Boolean" default="isAdmin"/>""" +
                """<prop name="count" type="Int" default="defaultCount()"/>""" +
                """<prop name="sep" type="Char" default="c"/>""" +
                """<span>{ ratio }</span></component>""",
        )

        assertHas(result.source, """val ratio: Float = (props["ratio"] as? Float) ?: (1.5f)""")
        assertHas(result.source, """val scale: Double = (props["scale"] as? Double) ?: (3.0)""")
        assertHas(result.source, """val label: CharSequence = (props["label"] as? CharSequence) ?: ("abc")""")
        assertHas(result.source, """val admin: Boolean = (props["admin"] as? Boolean) ?: (isAdmin)""")
        assertHas(result.source, """val count: Int = (props["count"] as? Int) ?: (defaultCount())""")
        assertHas(result.source, """val sep: Char = (props["sep"] as? Char) ?: (c)""")
    }

    @Test
    fun `an unsupported deferred wire type is reported once`() {
        val result = gen(
            """<page route="/"><island name="tags" render="deferred" tags="a">""" +
                """<prop name="tags" type="Set<String>"/><p>{ tags }</p></island></page>""",
        )
        val errors = result.diagnostics.filter { it.severity == Severity.Error }
        assertEquals(1, errors.size, errors.toString())
        assertTrue("is not a supported wire type" in errors.single().message, errors.toString())
    }

    @Test
    fun `a function-typed prop with a default accepts a nullable function`() {
        fun accepted(type: String) = StaticPropCoercion.acceptedPropTypes(
            BmlParser.parse("""<component tag="picker"><prop name="onSelect" type="$type" :default="{}"/></component>""")
                .document.nodes.filterIsInstance<bosca.bml.parser.ElementNode>().single(),
        ).getValue("onSelect")

        assertEquals("((String) -> Unit)?", accepted("(String) -> Unit"))
        assertEquals("((String) -> Unit)?", accepted("((String) -> Unit)?"))
        // A function returning a nullable value is not itself nullable.
        assertEquals("((String) -> Unit?)?", accepted("(String) -> Unit?"))
        assertEquals("String?", accepted("String"))
    }

    @Test
    fun `a deferred prop must declare its type`() {
        val result = gen(
            """<page route="/"><island name="item" render="deferred" :item="value">""" +
                """<prop name="item"/><p>{ item }</p></island></page>""",
        )
        assertTrue(
            result.diagnostics.any { it.severity == Severity.Error && "Deferred prop 'item' needs an explicit type" in it.message },
            result.diagnostics.toString(),
        )
    }

    @Test
    fun `a deferred island key may interpolate and must have a value`() {
        val interpolated = gen(
            """<page route="/"><island name="row" render="deferred" key="row-{ 7 }"><p>x</p></island></page>""",
        )
        assertTrue(interpolated.diagnostics.none { it.severity == Severity.Error }, interpolated.diagnostics.toString())
        assertHas(interpolated.source, """w.attr("data-bml-id", "row-" + (7))""")

        val bare = gen("""<page route="/"><island name="row" render="deferred" key><p>x</p></island></page>""")
        assertTrue(bare.diagnostics.any { "A deferred island key needs a value" in it.message }, bare.diagnostics.toString())
    }

    @Test
    fun `deferred route params are bound outside the render lambda and may not shadow renderer locals`() {
        val result = gen(
            """<page route="/accounts/{id}"><island name="acct" render="deferred">""" +
                """<prop name="id" type="String" :default="id"/><p>{ id }</p></island></page>""",
        )
        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        val renderer = result.source.substringAfter("object AcctDeferredRenderer")
        // The route param precedes withRenderContext, so the prop's default reads the route value.
        assertTrue(
            renderer.indexOf("val id: String = ctx.params[\"id\"].orEmpty()") < renderer.indexOf("withRenderContext(ctx)"),
            renderer,
        )

        val clash = gen(
            """<page route="/p/{props}"><island name="x" render="deferred"><p>x</p></island></page>""",
        )
        assertTrue(
            clash.diagnostics.any { "Route parameter '{props}' conflicts with a generated deferred-renderer local" in it.message },
            clash.diagnostics.toString(),
        )
    }

    @Test
    fun `a rejected deferred island reports one error without follow-on fallback errors`() {
        val unnamed = gen(
            """<page route="/"><island render="deferred"><fallback><p>Loading</p></fallback><p>x</p></island></page>""",
        )
        val errors = unnamed.diagnostics.filter { it.severity == Severity.Error }
        assertEquals(1, errors.size, errors.toString())
        assertTrue("requires a non-blank literal name" in errors.single().message, errors.toString())
    }

    @Test
    fun `deferred islands cannot be declared inside svg`() {
        val result = gen(
            """<page route="/"><svg><island name="x" render="deferred"><p>x</p></island></svg></page>""",
        )
        assertTrue(result.diagnostics.any { "cannot be declared inside <svg>" in it.message }, result.diagnostics.toString())
    }

    @Test
    fun `component eager feature flags include prop default expressions`() {
        val result = gen(
            """<component tag="offer"><prop name="on" type="Boolean" :default="ctx.featureFlags.enabled(offerKey)"/>""" +
                """<p>{ on }</p></component><page route="/"><offer/></page>""",
        )
        assertHas(result.source, "hasEagerFeatureFlags = true")
    }

    @Test
    fun `several attribute parts are joined as text even when an interpolation comes first`() {
        val result = gen("""<page route="/"><p title="{ 1 }{ 2 } items">x</p></page>""")
        assertHas(result.source, """w.attr("title", "" + (1) + (2) + " items")""")
        // A single interpolation keeps its type, so a Boolean still renders as a presence attribute.
        assertHas(gen("""<page route="/"><p data-on="{ true }">x</p></page>""").source, """w.attr("data-on", (true))""")
    }

    @Test
    fun `shared cache durations ignore surrounding whitespace`() {
        val result = gen("""<page route="/" cache="shared" maxAge=" 60 " staleWhileRevalidate="30 "><p>x</p></page>""")
        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        assertHas(result.source, "override val sharedCacheMaxAgeSeconds: Long = 60L")
        assertHas(result.source, "override val sharedCacheStaleWhileRevalidateSeconds: Long = 30L")
    }

    @Test
    fun `removed page attributes warn instead of being silently ignored`() {
        val result = gen("""<page route="/" layout="main" title="Home" render="prerender"><html><body>x</body></html></page>""")
        val warnings = result.diagnostics.filter { it.severity == Severity.Warning }.map { it.message }

        assertTrue(warnings.any { "<page> layout has no effect; pages own their full HTML document" in it }, warnings.toString())
        assertTrue(warnings.any { "<page> title has no effect; put a <title> element" in it }, warnings.toString())
        assertTrue(warnings.any { "<page> render has no effect; only <island> accepts render" in it }, warnings.toString())
        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())

        val route = gen("""<route path="/feed.json" contentType="application/json" layout="x">{ 1 }</route>""")
        assertTrue(route.diagnostics.any { "<route> layout has no effect" in it.message }, route.diagnostics.toString())
    }

    @Test
    fun `deferred and shared pages require the exact HTML media type`() {
        val invalid = gen(
            """<page route="/" contentType="text/html-fragment" cache="shared" maxAge="60">""" +
                """<island name="private" render="deferred"><p>Private</p></island></page>""",
        )
        assertTrue(
            invalid.diagnostics.any { "Deferred islands are available only on HTML pages" in it.message },
            invalid.diagnostics.toString(),
        )
        assertTrue(
            invalid.diagnostics.any { "Shared caching is available only for HTML pages" in it.message },
            invalid.diagnostics.toString(),
        )

        val parameterized = gen(
            """<page route="/" contentType="TEXT/HTML; charset=UTF-8" cache="shared" maxAge="60">""" +
                """<island name="private" render="deferred"><p>Private</p></island></page>""",
        )
        assertTrue(
            parameterized.diagnostics.none { it.severity == Severity.Error },
            parameterized.diagnostics.toString(),
        )

        val throughComponent = gen(
            """<component tag="private-card">""" +
                """<island name="private" render="deferred"><p>Private</p></island></component>""" +
                """<component tag="card-wrapper"><private-card/></component>""" +
                """<page route="/feed.json" contentType="application/json"><card-wrapper/></page>""",
        )
        assertTrue(
            throughComponent.diagnostics.any {
                it.severity == Severity.Error && "Deferred islands are available only on HTML pages" in it.message
            },
            throughComponent.diagnostics.toString(),
        )
    }

    @Test
    fun `components used only by deferred bodies stay out of the eager component closure`() {
        val result = gen(
            """<component tag="loading-card"><p>Loading</p></component>""" +
            """<component tag="private-card"><p>Private</p></component>""" +
                """<page route="/" cache="shared" maxAge="60">""" +
                """<island name="private" render="deferred"><fallback><loading-card/></fallback>""" +
                """<private-card/></island>""" +
                """</page>""",
        )
        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        assertHas(result.source, """override val componentTags: List<String> = listOf("loading-card", "private-card")""")
        assertHas(result.source, """override val eagerComponentTags: List<String> = listOf("loading-card")""")
    }

    @Test
    fun `component metadata distinguishes deferred dependencies from eager dependencies`() {
        val result = gen(
            """<component tag="loading-card"><p>Loading</p></component>""" +
            """<component tag="private-card"><p>Private</p></component>""" +
                """<component tag="account-shell"><main>""" +
                """<island name="private" render="deferred"><fallback><loading-card/></fallback>""" +
                """<private-card/></island>""" +
                """</main></component>""" +
                """<page route="/" cache="shared" maxAge="60"><account-shell/></page>""",
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        assertHas(
            result.source,
            """BmlComponentInfo("account-shell", null, "", listOf("loading-card", "private-card"), "XPage.js", false, eagerDeps = listOf("loading-card"), renderRevision = "x.bml", deferredRenderers = listOf(AccountShell_PrivateDeferredRenderer))""",
        )
    }

    @Test
    fun `shared page declares a bootstrap module for a client component`() {
        val result = gen(
            """<component tag="account-shell"><script client scoped>account()</script><main>Account</main></component>""" +
                """<page route="/" cache="shared" maxAge="60"><account-shell/></page>""",
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        assertHas(result.source, """override val clientModule: String = "XPage.js"""")
    }

    @Test
    fun `component metadata marks eager feature flag evaluation`() {
        val result = gen(
            """<component tag="offer"><if flag="offer"><p>Offer</p></if></component>""" +
                """<page route="/"><offer/></page>""",
        )
        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.toString())
        assertHas(result.source, "hasEagerFeatureFlags = true")
    }

    @Test
    fun `page implements BmlPageRenderer and binds route params`() {
        val src = gen(
            """<page route="/lists/{id}"><script server provides="m">id</script><h1>{ m }</h1></page>""",
        ).source
        assertHas(src, ": bosca.bml.render.BmlPageRenderer {")
        assertHas(src, """override val route: String = "/lists/{id}"""")
        assertHas(src, """override suspend fun render(ctx: RenderContext)""")
        // `{id}` from the route is bound into scope so the server script can use it.
        assertHas(src, """val id: String = ctx.params["id"].orEmpty()""")
        // No <script client> here, so no clientModule override.
        assertTrue(!src.contains("clientModule"), "static page must not declare a clientModule")
    }

    @Test
    fun `page content type is exposed by the generated renderer`() {
        val src = gen("""<page route="/feed.json" contentType="application/json">{@ "{}" }</page>""").source
        assertHas(src, """override val contentType: String = "application/json"""")
    }

    @Test
    fun `page content type must be a non-blank literal`() {
        val result = gen("""<page route="/feed.json" :contentType="selectedType">{@ "{}" }</page>""")
        assertTrue(result.diagnostics.any { it.severity == Severity.Error && "contentType requires" in it.message })
    }

    @Test
    fun `route path lowers to the shared page renderer`() {
        val src = gen(
            """<route path="/feeds/{format}" contentType="application/json">""" +
                """<script server provides="body">format</script>{@ body }</route>""",
        ).source
        assertHas(src, "@BmlPage(route = \"/feeds/{format}\")")
        assertHas(src, """override val route: String = "/feeds/{format}"""")
        assertHas(src, """override val contentType: String = "application/json"""")
        assertHas(src, """val format: String = ctx.params["format"].orEmpty()""")
        assertHas(src, "w.raw(body)")
    }

    @Test
    fun `route content type is optional and uses the renderer default`() {
        val src = gen("""<route path="/status">{@ "ok" }</route>""").source
        assertHas(src, """override val route: String = "/status"""")
        assertFalse(
            src.contains("override val contentType"),
            "a route without contentType must inherit BmlPageRenderer's text/html default",
        )
    }

    @Test
    fun `route requires a non-blank literal path`() {
        listOf(
            "<route></route>",
            """<route :path="selectedPath"></route>""",
            """<route path=""></route>""",
        ).forEach { source ->
            val result = gen(source)
            assertTrue(
                result.diagnostics.any {
                    it.severity == Severity.Error && "requires a non-blank literal path" in it.message
                },
                "expected invalid route path diagnostic for $source: ${result.diagnostics}",
            )
        }
    }

    @Test
    fun `message unit lowers an email channel with a subject expression`() {
        val src = gen(
            """<message key="welcome"><script server provides="name">"Ada"</script>""" +
                """<email><subject>Hi { name }!</subject><p>Hello { name }</p></email></message>""",
            obj = "XMessage",
        ).source
        assertHas(src, """@BmlMessage(key = "welcome")""")
        assertHas(src, "public object XMessage : bosca.bml.message.BmlMessageTemplate {")
        assertHas(src, """override val key: String = "welcome"""")
        assertHas(src, "override suspend fun renderMessage(message: bosca.bml.message.BmlMessageContext)")
        assertHas(
            src,
            "val ctx = RenderContext(gql = message.gql, token = message.token, " +
                "locale = java.util.Locale.forLanguageTag(message.locale), messages = message.messages)",
        )
        assertFalse(src.contains("disableFeatureFlagsForMessageRendering"))
        assertHas(src, """val __bmlSubject: String = ("" + "Hi " + (name) + "!").trim()""")
        assertHas(src, "val name = run {")
        assertHas(src, """w.markup("<p>")""")
        assertHas(src, "w.text(name)")
        // Post-processing: scripts stripped / CSS inlined + a plain-text alternative.
        assertHas(src, "html = bosca.bml.render.EmailRenderer.render(__bmlBody, ctx.collectedEmailCss),")
        assertHas(src, "images = ctx.collectedEmailImages,")
        assertHas(src, "ctx.beginEmailCollection()")
        assertHas(src, "text = bosca.bml.render.PlainTextRenderer.render(__bmlBody),")
        // The subject region itself must not render into the body markup.
        assertTrue(!src.contains("<subject>"), "the <subject> region must not be emitted as markup")
    }

    @Test
    fun `message key defaults to the file base name`() {
        val src = gen(
            """<message><email><subject>S</subject><p>b</p></email></message>""",
            path = "messages/course-welcome.bml",
            obj = "MessagesCourseWelcomeMessage",
        ).source
        assertHas(src, """override val key: String = "course-welcome"""")
    }

    @Test
    fun `email channel without a subject is an error`() {
        val parsed = BmlParser.parse("""<message key="x"><email><p>b</p></email></message>""")
        val result = BmlCodeGenerator("test", "XMessage", "x.bml").generate(parsed.document)
        assertTrue(
            result.diagnostics.any { it.message.contains("<subject>") },
            "expected a missing-<subject> diagnostic: ${result.diagnostics}",
        )
    }

    @Test
    fun `push channel lowers to structured output and is excluded from email html`() {
        val src = gen(
            """<message key="chat"><script server provides="options">pushOptions()</script>""" +
                """<push :options="options"><title>{ sender } in Chat</title><body>{ preview }</body></push>""" +
                """<email><subject>Email subject</subject><p>Email body</p></email></message>""",
            obj = "ChatMessage",
        ).source

        assertHas(src, "override val supportsPush: Boolean = true")
        assertHas(src, "val __bmlPushTitle: String =")
        assertHas(src, "val __bmlPushBody: String =")
        assertHas(src, "val __bmlPush = if (message.channel != bosca.bml.message.BmlMessageChannel.EMAIL) {")
        assertHas(src, "bosca.bml.message.RenderedPush(")
        assertHas(src, "val __bmlEmail = if (message.channel != bosca.bml.message.BmlMessageChannel.PUSH) {")
        assertHas(
            src,
            "options = (options)?.selectActions(emptyList())?.selectRichContent(image = false, attachments = false, conversation = false),",
        )
        assertTrue(!src.contains("<push"), "the push region must not render into email HTML")
        assertTrue(!src.contains("<title"), "the push title must not render into email HTML")
    }

    @Test
    fun `push inherits producer options and selects only declared rich concepts`() {
        val src = gen(
            """<message key="chat"><push><title>Chat</title><body>Project update</body>""" +
                """<image/><attachments/><conversation/></push>""" +
                """<email><subject>Email subject</subject><p>Email body</p></email></message>""",
            obj = "ChatMessage",
        ).source

        assertHas(
            src,
            "options = (message.pushOptions)?.selectActions(emptyList())?.selectRichContent(image = true, attachments = true, conversation = true),",
        )
    }

    @Test
    fun `message unit lowers sibling email and localized push regions`() {
        val result = gen(
            """<message key="chat">""" +
                """<script server provides="sender">"Ada"</script>""" +
                """<push><title t="chat.push.title">Message from { sender }</title>""" +
                """<body t="chat.push.body">Open the conversation</body>""" +
                """<action id="open-chat" default t="chat.push.action.open">Open chat</action></push>""" +
                """<email><subject>Email subject</subject><p>Email body</p></email>""" +
                """</message>""",
            obj = "ChatMessage",
        )

        assertHas(result.source, "object ChatMessage : bosca.bml.message.BmlMessageTemplate")
        assertHas(result.source, "override val supportsEmail: Boolean = true")
        assertHas(result.source, "override val supportsPush: Boolean = true")
        assertHas(result.source, "Messages.resolveOr(ctx.messages, ctx.locale, \"chat.push.title\"")
        assertHas(result.source, "val __bmlPushAction0Label: String =")
        assertHas(
            result.source,
            "options = (((message.pushOptions) ?: bosca.bml.message.BmlPushOptions()).selectActions(" +
                "listOf(bosca.bml.message.BmlPushActionSelection(id = \"open-chat\", " +
                "label = __bmlPushAction0Label, isDefault = true))))" +
                ".selectRichContent(image = false, attachments = false, conversation = false),",
        )
        assertEquals(
            setOf("chat.push.title", "chat.push.body", "chat.push.action.open"),
            result.i18n.map { it.key }.toSet(),
        )
    }

    @Test
    fun `push requires title and body regions`() {
        val parsed = BmlParser.parse(
            """<message key="chat"><email><subject>S</subject></email><push><title>T</title></push></message>""",
        )
        val result = BmlCodeGenerator("test", "ChatMessage", "chat.bml").generate(parsed.document)

        assertTrue(
            result.diagnostics.any { it.message.contains("<push> requires one <title> and one <body>") },
            "expected an invalid-push diagnostic: ${result.diagnostics}",
        )
    }

    @Test
    fun `push actions require unique literal ids and a single default`() {
        val parsed = BmlParser.parse(
            """<message key="chat"><push><title>T</title><body>B</body>""" +
                """<action default>Missing</action>""" +
                """<action id="open" default>Open</action>""" +
                """<action id="open" default>Again</action></push></message>""",
        )
        val result = BmlCodeGenerator("test", "ChatMessage", "chat.bml").generate(parsed.document)

        assertTrue(result.diagnostics.any { it.message.contains("requires a non-blank literal id") })
        assertTrue(result.diagnostics.any { it.message.contains("id 'open' is declared more than once") })
        assertTrue(result.diagnostics.any { it.message.contains("at most one default <action>") })
    }

    @Test
    fun `page and message in one file is an error`() {
        val parsed = BmlParser.parse(
            """<page route="/"><p>a</p></page><message key="x"><email><subject>s</subject></email></message>""",
        )
        val result = BmlCodeGenerator("test", "XPage", "x.bml").generate(parsed.document)
        assertTrue(
            result.diagnostics.any { it.message.contains("cannot share a file") },
            "expected a shared-file diagnostic: ${result.diagnostics}",
        )
        assertEquals("", result.source)
    }

    @Test
    fun `message feature conditions are rejected without guessing a recipient identity`() {
        val parsed = BmlParser.parse(
            """<message key="offer"><email><subject>Offer</subject>""" +
                """<if flag="message-offer"><p>Special</p></if></email></message>""",
        )
        val result = BmlCodeGenerator("test", "OfferMessage", "offer.bml").generate(parsed.document)

        assertTrue(
            result.diagnostics.any { it.severity == Severity.Error && "recipient feature identity" in it.message },
            result.diagnostics.toString(),
        )
        assertEquals("", result.source)
    }

    @Test
    fun `message feature condition detection traverses conditional branches and loops`() {
        listOf(
            """<if show><if flag="nested">x</if></if>""",
            """<if show>x<else><if flag="fallback">y</if></if>""",
            """<for item in items><if flag="repeated">x</if></for>""",
        ).forEach { content ->
            val parsed = BmlParser.parse(
                """<message key="offer"><email><subject>Offer</subject>$content</email></message>""",
            )
            assertFalse(parsed.hasErrors, parsed.diagnostics.toString())
            val result = BmlCodeGenerator("test", "OfferMessage", "offer.bml").generate(parsed.document)

            assertTrue(
                result.diagnostics.any { it.severity == Severity.Error && "recipient feature identity" in it.message },
                result.diagnostics.toString(),
            )
            assertEquals("", result.source)
        }
    }

    @Test
    fun `component declaration generates a render object and instantiation lowers to a call`() {
        val src = gen(
            """<component tag="badge"><prop name="label" type="String" required/><span class="b">{ label }</span></component>""" +
                """<page route="/"><badge label="new"/></page>""",
        ).source
        // the component becomes a render object reading typed props from the map, with the prop in scope
        assertHas(src, "public object BadgeComponent : bosca.bml.render.BmlComponentRenderer {")
        assertHas(src, "override suspend fun render(ctx: RenderContext, props: Map<String, Any?>, slot: suspend () -> Unit)")
        assertHas(src, """val label: String = props["label"] as String""")
        assertHas(src, "w.text(label)")
        // instantiation passes props as a map + an (empty) slot lambda
        assertHas(src, """BadgeComponent.render(ctx, mapOf("label" to "new")) {}""")
    }

    @Test
    fun `component supports a defaulted prop and a default slot`() {
        val src = gen(
            """<component tag="card"><prop name="elevation" type="Int" default="1"/><article class="card"><slot/></article></component>""" +
                """<page route="/"><card>hi</card></page>""",
        ).source
        assertHas(src, """val elevation: Int = (props["elevation"] as? Int) ?: (1)""")
        assertHas(src, "slot()")                          // <slot/> renders the passed children
        assertHas(src, "CardComponent.render(ctx, mapOf()) {")  // slot content is the trailing lambda
    }

    @Test
    fun `scoped component stamps a marker on its elements and rewrites its style selectors`() {
        val src = gen(
            """<component tag="badge"><prop name="label" type="String" required/>""" +
                """<style scoped>.badge { color: red; }</style>""" +
                """<span class="badge">{ label }</span></component>""" +
                """<page route="/"><badge label="x"/></page>""",
        ).source
        // scope id + scoped CSS are exposed as members for the (step-2) per-component asset chunk
        assertHas(src, """public const val scope: String = "badge"""")
        assertHas(src, """public val styles: String = ".badge[data-bml-c=\"badge\"] { color: red; }"""")
        // and inlined exactly once per render, deduped through the context
        assertHas(src, """if (ctx.useStyleOnce("badge")) w.markup("<style>" + styles + "</style>")""")
        // every element the component renders carries the scope marker so the scoped selector matches
        // (the component ROOT's open tag is split by the attribute fall-through injection, so match up to it)
        assertHas(src, """<span data-bml-c=\"badge\" class=\"badge\"""")
    }

    @Test
    fun `page declares the components it renders and components expose registry info`() {
        val src = gen(
            """<component tag="badge"><style scoped>.b{color:red}</style><span class="b"><slot/></span></component>""" +
                """<component tag="panel"><badge/></component>""" +   // panel depends on badge
                """<page route="/"><panel/><badge/></page>""",
        ).source
        // the page lists its directly-rendered components (the asset pipeline expands the closure)
        assertHas(src, """override val componentTags: List<String> = listOf("panel", "badge")""")
        // each component exposes BmlComponentInfo(tag, scope, styles, deps) for the registry
        assertHas(src, """public val info: bosca.bml.render.BmlComponentInfo = bosca.bml.render.BmlComponentInfo("badge", "badge", styles, listOf(), null, false, renderRevision = "x.bml")""")
        assertHas(src, """public val info: bosca.bml.render.BmlComponentInfo = bosca.bml.render.BmlComponentInfo("panel", null, "", listOf("badge"), null, false, renderRevision = "x.bml")""")
    }

    @Test
    fun `a component without scoped styles stamps no marker and emits no style guard`() {
        val src = gen(
            """<component tag="plain"><span class="x">y</span></component>""" +
                """<page route="/"><plain/></page>""",
        ).source
        assertFalse(src.contains("data-bml-c"), "non-scoped component must not stamp a scope marker:\n$src")
        assertFalse(src.contains("useStyleOnce"), "non-scoped component must not emit a style guard:\n$src")
    }

    @Test
    fun `imports in a server script are hoisted to the file top`() {
        val src = gen(
            """<page route="/"><script server>import java.time.LocalDate</script>""" +
                """<script server provides="d">LocalDate.MIN</script><p>{ d }</p></page>""",
        ).source
        assertHas(src, "import java.time.LocalDate")
        assertTrue(
            src.indexOf("import java.time.LocalDate") < src.indexOf("public object"),
            "user import must be hoisted above the object, not emitted inside render()",
        )
    }

    @Test
    fun `island serializes bound props into data-bml-props`() {
        val src = gen(
            """<page route="/"><island name="counter" :start="2 + 1"><button>x</button></island></page>""",
        ).source
        // Open tag is split so the dynamic props attribute can be written between name and '>'.
        assertHas(src, """w.markup("<div data-bml-island=\"counter\"")""")
        assertHas(src, """w.attr("data-bml-props", bosca.bml.render.propsToJson(mapOf("start" to (2 + 1))))""")
    }

    @Test
    fun `self-closed non-void elements emit their close tag`() {
        // HTML parses a bare `<path ...>` as unclosed and swallows following siblings as children —
        // a four-path SVG mark renders as one path. Void elements (br, img…) stay close-less.
        val src = gen(
            """<page route="/"><svg viewBox="0 0 48 48"><path d="M1 1"/><path d="M2 2"/></svg><br/></page>""",
        ).source
        assertHas(src, """w.markup("</path>")""")
        assertFalse(src.contains("</br>"), "void elements must not emit a close tag")
    }

    @Test
    fun `SVG context recognizes filters and native use despite BML tag-name overlap`() {
        val result = gen(
            """<page route="/"><svg><defs><filter id="tone"><feColorMatrix/><feComponentTransfer>""" +
                """<feFuncR/><feFuncG/><feFuncB/></feComponentTransfer></filter></defs>""" +
                """<use href="#shape"/></svg></page>""",
        )
        assertFalse(result.diagnostics.any { "Unknown tag" in it.message }, "unexpected SVG diagnostics: ${result.diagnostics}")
        assertHas(result.source, """w.markup("<filter id=\"tone\">")""")
        assertHas(result.source, """w.markup("<use href=\"#shape\">")""")
        assertHas(result.source, """w.markup("</use>")""")
    }

    @Test
    fun `standard on-event attributes pass through as plain HTML attributes`() {
        val src = gen(
            """<page route="/">""" +
                """<form onsubmit="createAccount(event)"><button type="submit">Go</button></form>""" +
                """<script client>function createAccount(e) {}</script>""" +
                """</page>""",
        ).source
        assertHas(src, """w.markup("<form onsubmit=\"createAccount(event)\">")""")
    }

    // ── live islands (declarative @click server actions) ────────────────────────

    private val clientLivePage =
        """<page route="/">""" +
            """<script server provides="counterModel">bosca.bml.sample.CounterModel()</script>""" +
            """<island name="counter"><span>{ counterModel.count }</span></island>""" +
            """<button @click="counterModel.increment">+</button>""" +
            """</page>"""

    @Test
    fun `server inject participates in serialization and server action dispatch`() {
        val src = gen(
            """<page route="/">""" +
                """<inject server name="headerAuth" type="example.HeaderAuth" provider="header-auth"/>""" +
                """<island name="header"><span>{ headerAuth.authenticated }</span></island>""" +
                """<button @click="headerAuth.signOut">Sign out</button>""" +
                """</page>""",
        ).source

        assertHas(src, "val headerAuth: example.HeaderAuth = bosca.di.provide<example.HeaderAuth>(\"header-auth\")")
        assertHas(src, "w.raw(bosca.bml.render.encodeClientState(headerAuth))")
        assertHas(src, "suspend fun renderInner(ctx: RenderContext, headerAuth: example.HeaderAuth, stateKey: String) {")
        assertHas(src, "val headerAuth: example.HeaderAuth = bosca.bml.render.decodeClientState<example.HeaderAuth>(state)")
        assertHas(src, "\"signOut\" -> headerAuth.signOut()")
        assertHas(src, "override val islandActionDispatchers: List<bosca.bml.render.BmlIslandActionDispatcher> = listOf(HeaderAuthStateDispatcher)")
    }

    @Test
    fun `unmarked inject remains a render dependency rather than live state`() {
        val result = gen(
            """<page route="/">""" +
                """<inject name="headerAuth" type="example.HeaderAuth"/>""" +
                """<island name="header"><span>{ headerAuth.authenticated }</span></island>""" +
                """<button @click="headerAuth.signOut">Sign out</button>""" +
                """</page>""",
        )

        assertHas(result.source, "val headerAuth: example.HeaderAuth = bosca.di.provide<example.HeaderAuth>()")
        assertFalse("HeaderAuthStateDispatcher" in result.source, "an ordinary inject must not become live state")
        assertTrue(result.diagnostics.any { "does not reference a live state model" in it.message })
    }

    @Test
    fun `server session inject restores serialized state before resolving a fresh DI value`() {
        val src = gen(
            """<page route="/">""" +
                """<inject server scope="server-session" name="headerAuth" type="example.HeaderAuth" provider="header-auth" init="load"/>""" +
                """<island name="header"><span>{ headerAuth.authenticated }</span></island>""" +
                """<button @click="headerAuth.signOut">Sign out</button>""" +
                """</page>""",
        ).source

        assertHas(src, "override val hasServerState: Boolean = true")
        assertHas(src, "val headerAuth: example.HeaderAuth = ctx.session?.get(bosca.bml.render.bmlPageSessionStateKey(ctx.requestPath, \"headerAuth\"))?.let { bosca.bml.render.decodeState<example.HeaderAuth>(it) } ?: run {")
        assertHas(src, "val __bmlInjected_headerAuth: example.HeaderAuth = bosca.di.provide<example.HeaderAuth>(\"header-auth\")")
        assertHas(src, "__bmlInjected_headerAuth.load()")
        assertHas(src, "ctx.session?.putIfAbsent(bosca.bml.render.bmlPageSessionStateKey(ctx.requestPath, \"headerAuth\"), bosca.bml.render.encodeState(headerAuth))")
    }

    @Test
    fun `client local inject emits persistent browser storage scope`() {
        val src = gen(
            """<page route="/">""" +
                """<inject server scope="client-local" name="headerAuth" type="example.HeaderAuth"/>""" +
                """<island name="header"><span>{ headerAuth.authenticated }</span></island>""" +
                """<button @click="headerAuth.signOut">Sign out</button>""" +
                """</page>""",
        ).source

        assertHas(src, """data-bml-state-key=\"headerAuth\" data-bml-scope=\"client-local\"""")
    }

    @Test
    fun `client-scoped live state emits a json state script, view island, and button markers`() {
        val src = gen(clientLivePage).source
        // page identity marker so an action routes to THIS page's dispatcher (state keys are per-page)
        assertHas(src, """w.markup("<script type=\"application/json\" data-bml-page=\"/\"></script>")""")
        // client state is serialized into a JSON script — the round-tripped source of truth
        assertHas(src, """w.markup("<script type=\"application/json\" data-bml-state-key=\"counterModel\">")""")
        assertHas(src, "w.raw(bosca.bml.render.encodeClientState(counterModel))")
        // the island carries the state key (the join) and renders its inner via the shared renderInner
        assertHas(src, """w.markup("<div data-bml-island=\"counter\"")""")
        assertHas(src, """w.attr("data-bml-state-key", "counterModel")""")
        assertHas(src, "CounterIsland.renderInner(ctx, counterModel, \"counterModel\")")
        // the button lives OUTSIDE the island, wired purely by the state key + method (no @click HTML)
        assertHas(src, """w.markup(bosca.bml.render.actionMarkers("counterModel", "increment", null, null))""")
        assertFalse(src.contains("@click"), "the @click HTML attribute must be dropped, not emitted")
        // live state needs the island runtime
        assertHas(src, """override val clientModule: String = "XPage.js"""")
        assertFalse(src.contains("hasServerState"), "a client-only page must not declare server state")
    }

    @Test
    fun `a click action passing ctx emits markers and a ctx-forwarding dispatcher call`() {
        // Models that reach the data plane declare `suspend fun method(ctx: RenderContext)` —
        // `@click="model.method(ctx)"` must lower like the bare form, forwarding ctx in the
        // dispatcher (the wire marker stays the bare method name).
        val src = gen(
            """<page route="/">""" +
                """<script server provides="counterModel">bosca.bml.sample.CounterModel()</script>""" +
                """<island name="counter"><span>{ counterModel.count }</span></island>""" +
                """<button @click="counterModel.increment(ctx)">+</button>""" +
                """</page>""",
        ).source
        assertHas(src, """w.markup(bosca.bml.render.actionMarkers("counterModel", "increment", null, null))""")
        assertHas(src, """"increment" -> counterModel.increment(ctx)""")
        assertHas(src, "override val islandActionDispatchers: List<bosca.bml.render.BmlIslandActionDispatcher> = listOf(CounterModelStateDispatcher)")
    }

    @Test
    fun `live state generates an island view object and a dispatcher`() {
        val src = gen(clientLivePage).source
        assertHas(src, "public object CounterIsland {")
        assertHas(src, "suspend fun renderInner(ctx: RenderContext, counterModel: bosca.bml.sample.CounterModel, stateKey: String) {")
        assertHas(src, "public object CounterModelStateDispatcher : bosca.bml.render.BmlIslandActionDispatcher {")
        assertHas(src, """override val stateKey: String = "counterModel"""")
        assertHas(src, "override val serverScoped: Boolean = false")
        assertHas(src, "val counterModel: bosca.bml.sample.CounterModel = bosca.bml.render.decodeClientState<bosca.bml.sample.CounterModel>(state)")
        assertHas(src, """"increment" -> counterModel.increment()""")
        assertHas(src, "return bosca.bml.render.IslandActionResult(bosca.bml.render.encodeClientState(counterModel), if (ctx.renderLiveStateView) ctx.writer.toString() else null)")
        // the page registers the dispatcher under its state key
        assertHas(src, "override val islandActionDispatchers: List<bosca.bml.render.BmlIslandActionDispatcher> = listOf(CounterModelStateDispatcher)")
    }

    @Test
    fun `live-state scripts are emitted inside the body, before the closing tag`() {
        // Regression guard: hydration scripts must land INSIDE <body>, not before <html> (where they used to).
        val src = gen(
            """<page route="/">""" +
                """<script server provides="counterModel">bosca.bml.sample.CounterModel()</script>""" +
                """<html><head><title>t</title></head><body>""" +
                """<island name="c"><span>{ counterModel.count }</span></island>""" +
                """<button @click="counterModel.increment">+</button>""" +
                """</body></html></page>""",
        ).source
        val lines = src.lines()
        fun idx(needle: String) = lines.indexOfFirst { it.contains(needle) }
        val bodyOpen = idx("""w.markup("<body>")""")
        val pageMarker = idx("data-bml-page")
        val stateScript = idx("""<script type=\"application/json\" data-bml-state-key=\"counterModel\">""")
        val bodyClose = idx("""w.markup("</body>")""")
        assertTrue(bodyOpen >= 0 && bodyClose > bodyOpen, "expected <body>…</body> in output:\n$src")
        assertTrue(pageMarker in (bodyOpen + 1) until bodyClose, "page marker must be inside <body>, before </body>:\n$src")
        assertTrue(stateScript in (bodyOpen + 1) until bodyClose, "client state script must be inside <body>, before </body>:\n$src")
        assertFalse("data-bml-scope" in src, "client state must be page-scoped by default:\n$src")
    }

    @Test
    fun `client live state opts into client session scope explicitly`() {
        val src = gen(
            """<page route="/">""" +
                """<script server provides="counterModel" scope="client-session">bosca.bml.sample.CounterModel()</script>""" +
                """<island name="counter"><span>{ counterModel.count }</span></island>""" +
                """<button @click="counterModel.increment">+</button>""" +
                """</page>""",
        ).source

        assertHas(
            src,
            """<script type=\"application/json\" data-bml-state-key=\"counterModel\" data-bml-scope=\"client-session\">""",
        )
    }

    @Test
    fun `client live state opts into persistent local scope explicitly`() {
        val src = gen(
            """<page route="/">""" +
                """<script server provides="counterModel" scope="client-local">bosca.bml.sample.CounterModel()</script>""" +
                """<island name="counter"><span>{ counterModel.count }</span></island>""" +
                """<button @click="counterModel.increment">+</button>""" +
                """</page>""",
        ).source

        assertHas(
            src,
            """<script type=\"application/json\" data-bml-state-key=\"counterModel\" data-bml-scope=\"client-local\">""",
        )
    }

    @Test
    fun `clear on sign out marks browser-backed live state`() {
        val result = gen(
            """<page route="/">""" +
                """<script server provides="counterModel" scope="client-local" clear-on-sign-out>bosca.bml.sample.CounterModel()</script>""" +
                """<island name="counter"><span>{ counterModel.count }</span></island>""" +
                """<button @click="counterModel.increment">+</button>""" +
                """</page>""",
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString())
        assertHas(result.source, """data-bml-scope=\"client-local\" data-bml-clear-on-sign-out>""")
    }

    @Test
    fun `clear on sign out requires a browser-backed persistent scope`() {
        val result = gen(
            """<page route="/">""" +
                """<script server provides="counterModel" scope="server-session" clear-on-sign-out>bosca.bml.sample.CounterModel()</script>""" +
                """<island name="counter"><span>{ counterModel.count }</span></island>""" +
                """<button @click="counterModel.increment">+</button>""" +
                """</page>""",
        )

        assertTrue(result.diagnostics.any { "requires `client-session` or `client-local` scope" in it.message })
    }

    @Test
    fun `explicit page scope remains page local`() {
        val src = gen(
            """<page route="/">""" +
                """<script server provides="counterModel" scope="page">bosca.bml.sample.CounterModel()</script>""" +
                """<island name="counter"><span>{ counterModel.count }</span></island>""" +
                """<button @click="counterModel.increment">+</button>""" +
                """</page>""",
        ).source

        assertHas(src, """data-bml-state-key=\"counterModel\">""")
        assertFalse("data-bml-scope" in src, "page scope must not opt into browser session storage:\n$src")
        assertHas(src, "override val serverScoped: Boolean = false")
    }

    @Test
    fun `component client state emits its client session scope marker`() {
        val src = gen(
            """<component tag="counter-card">""" +
                """<prop name="id" type="String" required/>""" +
                """<script server provides="counter" scope="client-session">bosca.bml.sample.CounterModel()</script>""" +
                """<island name="counter-view" :key="id"><button @click="counter.increment">+</button></island>""" +
                """</component><page route="/"><counter-card id="a"/></page>""",
        ).source

        assertHas(src, """w.markup(" data-bml-scope=\"client-session\"")""")
    }

    @Test
    fun `component client state emits its persistent local scope marker`() {
        val src = gen(
            """<component tag="counter-card">""" +
                """<prop name="id" type="String" required/>""" +
                """<script server provides="counter" scope="client-local">bosca.bml.sample.CounterModel()</script>""" +
                """<island name="counter-view" :key="id"><button @click="counter.increment">+</button></island>""" +
                """</component><page route="/"><counter-card id="a"/></page>""",
        ).source

        assertHas(src, """w.markup(" data-bml-scope=\"client-local\"")""")
    }

    @Test
    fun `invalid live state scope is diagnosed`() {
        val result = gen(
            """<page route="/">""" +
                """<script server provides="counter" scope="forever">Counter()</script>""" +
                """<island name="counter-view">{ counter.count }</island>""" +
                """<button @click="counter.increment">+</button></page>""",
        )

        assertTrue(result.diagnostics.any { "scope must be `page`, `client-session`, `client-local`, or `server-session`" in it.message })
    }

    @Test
    fun `obsolete persist attribute is diagnosed`() {
        val result = gen(
            """<page route="/">""" +
                """<script server provides="counter" persist="client-session">Counter()</script>""" +
                """<island name="counter-view">{ counter.count }</island>""" +
                """<button @click="counter.increment">+</button></page>""",
        )

        assertTrue(result.diagnostics.any { "no longer supports `persist`" in it.message })
    }

    @Test
    fun `obsolete server scope value is diagnosed`() {
        val result = gen(
            """<page route="/">""" +
                """<script server provides="counter" scope="server">Counter()</script>""" +
                """<island name="counter-view">{ counter.count }</island>""" +
                """<button @click="counter.increment">+</button></page>""",
        )

        assertTrue(result.diagnostics.any { "scope must be `page`, `client-session`, `client-local`, or `server-session`" in it.message })
    }

    @Test
    fun `server-session live state stays in the session and emits no client state script`() {
        val src = gen(
            """<page route="/">""" +
                """<script server provides="counterModel" scope="server-session">bosca.bml.sample.CounterModel()</script>""" +
                """<island name="counter"><span>{ counterModel.count }</span></island>""" +
                """<button @click="counterModel.increment">+</button>""" +
                """</page>""",
        ).source
        // page advertises server state; render initializes the model in the session, never in a client script
        assertHas(src, "override val hasServerState: Boolean = true")
        assertHas(src, """ctx.session?.putIfAbsent(bosca.bml.render.bmlPageSessionStateKey(ctx.requestPath, "counterModel"), bosca.bml.render.encodeState(counterModel))""")
        assertFalse(
            src.contains("""<script type=\"application/json\" data-bml-state-key=\"counterModel\">"""),
            "server-scoped state must NOT be serialized into a client state script",
        )
        // durable: the provides RESTORES the stored model from the (cookie-identified) session, only
        // constructing a fresh one when the session has none — so state survives a refresh.
        assertHas(src, """val counterModel: bosca.bml.sample.CounterModel = ctx.session?.get(bosca.bml.render.bmlPageSessionStateKey(ctx.requestPath, "counterModel"))?.let { bosca.bml.render.decodeState<bosca.bml.sample.CounterModel>(it) } ?: run {""")
        // no in-page session id — the identity is the HttpOnly bml_session cookie (never in client JS)
        assertFalse(src.contains("data-bml-session"), "server identity is a cookie now, not an in-page script")
        assertFalse(src.contains("ctx.session?.id"), "the session id must not be emitted into the page")
        // the dispatcher loads from / persists to the session
        assertHas(src, "override val serverScoped: Boolean = true")
        assertHas(src, "val counterModel: bosca.bml.sample.CounterModel = bosca.bml.render.decodeState<bosca.bml.sample.CounterModel>(ctx.session?.get(instanceKey) ?: state)")
        assertHas(src, "ctx.session?.put(instanceKey, bosca.bml.render.encodeState(counterModel))")
        assertHas(src, "return bosca.bml.render.IslandActionResult(null, if (ctx.renderLiveStateView) ctx.writer.toString() else null)")
    }

    @Test
    fun `at-click on a component falls through onto the component root element`() {
        val src = gen(
            """<component tag="button"><html:button><slot/></html:button></component>""" +
                """<page route="/">""" +
                """<script server provides="counterModel">M()</script>""" +
                """<island name="c"><span>{ counterModel.count }</span></island>""" +
                """<button @click="counterModel.increment">+</button>""" +
                """</page>""",
        ).source
        // The component re-emits forwarded attributes on its root <html:button> (fall-through, not a wrapper).
        assertHas(src, """w.markup((props["__bmlAttrs"] as? String).orEmpty())""")
        // The instantiation forwards the live markers as the reserved prop.
        assertHas(src, """"__bmlAttrs" to (bosca.bml.render.actionMarkers("counterModel", "increment", null, null))""")
        // No wrapper element is emitted around the component.
        assertFalse(src.contains("display:contents"), "fall-through must not wrap the component:\n$src")
    }

    @Test
    fun `scoped component markers and refs follow every conditional root without a wrapper`() {
        val src = gen(
            """<component tag="share-button">""" +
                """<prop name="icon" type="Boolean" default="false"/>""" +
                """<if icon><button ref="button">Icon</button><else><button ref="button">Text</button></if>""" +
                """<script client scoped>ctx.on(ctx.refs.button, "click", () => {})</script>""" +
                """</component>""",
        ).source
        val marker = "data-bml-component=\\\"share-button\\\" data-bml-ref=\\\"button\\\""
        assertEquals(2, src.split(marker).size - 1, "every conditional root must be mountable:\n$src")
        assertFalse(src.contains("display:contents"), "scoped components must remain wrapper-free:\n$src")
    }

    @Test
    fun `optional content before a stable component root does not mount a second instance`() {
        val src = gen(
            """<component tag="account-state">""" +
                """<prop name="show" type="Boolean" default="false"/>""" +
                """<if show><aside>Usage</aside></if>""" +
                """<main ref="root"><script client scoped>ctx.on(window, "auth", () => {})</script></main>""" +
                """</component>""",
        ).source
        assertEquals(1, src.split("data-bml-component=\\\"account-state\\\"").size - 1, src)
        assertHas(src, """data-bml-component=\"account-state\" data-bml-ref=\"root\"""")
    }

    @Test
    fun `an earlier exhaustive conditional remains the component root before a later sibling`() {
        val src = gen(
            """<component tag="account-state">""" +
                """<prop name="ready" type="Boolean" default="false"/>""" +
                """<if ready><header ref="root">Ready</header><else><section ref="root">Waiting</section></if>""" +
                """<main ref="later">Later</main>""" +
                """<script client scoped>ctx.on(window, "auth", () => {})</script>""" +
                """</component>""",
        ).source

        assertEquals(2, src.split("data-bml-component=\\\"account-state\\\"").size - 1, src)
        assertFalse("data-bml-component=\\\"account-state\\\" data-bml-ref=\\\"later\\\"" in src, src)
    }

    @Test
    fun `a root island can also mount its owning component client scope`() {
        val src = gen(
            """<component tag="account-state">""" +
                """<island name="account"><p>Status</p></island>""" +
                """<script client scoped>ctx.on(window, "auth", () => {})</script>""" +
                """</component>""",
        ).source
        assertHas(src, """<div data-bml-island=\"component:account-state:account\"""")
        assertHas(src, """ data-bml-component=\"account-state\"""")
    }

    @Test
    fun `root islands retain component fallthrough attributes in both render modes`() {
        val eager = gen(
            """<component tag="account-card"><island name="account"><p>Account</p></island></component>""" +
                """<page route="/"><script server provides="counter">M()</script>""" +
                """<island name="counter"><p>{ counter.count }</p></island>""" +
                """<account-card @click="counter.increment"/></page>""",
        ).source
        val deferred = gen(
            """<component tag="account-card">""" +
                """<island name="account" render="deferred"><p>Account</p></island></component>""" +
                """<page route="/"><script server provides="counter">M()</script>""" +
                """<island name="counter"><p>{ counter.count }</p></island>""" +
                """<account-card @click="counter.increment"/></page>""",
        ).source

        val fallthrough = """w.markup((props["__bmlAttrs"] as? String).orEmpty())"""
        assertHas(eager, fallthrough)
        assertHas(deferred, fallthrough)
        assertHas(eager, """"__bmlAttrs" to (bosca.bml.render.actionMarkers("counter", "increment"""")
        assertHas(deferred, """"__bmlAttrs" to (bosca.bml.render.actionMarkers("counter", "increment"""")
    }

    @Test
    fun `a click on a non-constructor provides degrades with a diagnostic`() {
        val result = gen(
            """<page route="/">""" +
                """<script server provides="greeting">"hi"</script>""" +
                """<island name="g"><span>{ greeting }</span></island>""" +
                """<button @click="greeting.shout">!</button>""" +
                """</page>""",
        )
        assertTrue(
            result.diagnostics.any { it.message.contains("not a constructor-typed") },
            "expected a diagnostic for a non-constructor live receiver: ${result.diagnostics}",
        )
        // no dispatcher is generated, and the dead @click is dropped
        assertFalse(result.source.contains("StateDispatcher"), "no dispatcher for a degraded live action")
        assertFalse(result.source.contains("@click"), "the @click HTML attribute must still be dropped")
    }

    private fun lineOf(source: String, needle: String): Int {
        val idx = source.lines().indexOfFirst { it.contains(needle) }
        assertTrue(idx >= 0, "needle not found in generated source: $needle")
        return idx + 1
    }

    @Test
    fun `source map maps generated lines back to their bml source lines`() {
        val bml = listOf(
            """<page route="/">""",            // 1
            """<script server provides="m">""", // 2
            "load(id)",                         // 3
            "</script>",                        // 4
            """<h1>{ m.title }</h1>""",          // 5
            "</page>",                          // 6
        ).joinToString("\n")
        val result = gen(bml)
        val src = result.source
        val map = result.sourceMap

        // breakpoint inside server Kotlin lands on the author's line, not the <script> tag line
        assertEquals(3, map.sourceLineFor(lineOf(src, "load(id)")))
        // interpolation + element open both come from line 5
        assertEquals(5, map.sourceLineFor(lineOf(src, "w.text(m.title)")))
        assertEquals(5, map.sourceLineFor(lineOf(src, """w.markup("<h1>")""")))
        // the package/import header is intentionally unmapped
        assertNull(map.sourceLineFor(lineOf(src, "package test")))
    }

    @Test
    fun `source map round-trips through json`() {
        val map = gen("""<page route="/"><h1>{ x }</h1></page>""").sourceMap
        val parsed = BmlSourceMap.fromJson(map.toJson())
        assertEquals(map.sourcePath, parsed.sourcePath)
        assertEquals(map.mappings, parsed.mappings)
    }

    @Test
    fun `missing page is an error`() {
        val parsed = BmlParser.parse("<div>no page</div>")
        val result = BmlCodeGenerator("test", "XPage", "x.bml").generate(parsed.document)
        assertTrue(result.diagnostics.any { it.message.contains("No <page>") })
        assertTrue(result.source.isEmpty())
    }

    // ── action arguments, @submit, and component models ─────────────────────────

    @Test
    fun `a click action with render-time arguments serializes them into the marker and decodes positionally`() {
        val src = gen(
            """<page route="/">""" +
                """<script server provides="list">bosca.bml.sample.ListModel()</script>""" +
                """<island name="l"><for item in list.items><span>{ item.name }</span>""" +
                """<button @click="list.remove(item.id, ctx)">x</button></for></island>""" +
                """</page>""",
        ).source
        // the marker carries the item's evaluated id; ctx never travels on the wire (inside the
        // island the marker references renderInner's stateKey parameter, so re-renders stay wired)
        assertHas(src, """w.markup(bosca.bml.render.actionMarkers(stateKey, "remove", null, listOf<Any?>((item.id))))""")
        // the dispatcher decodes arg 0 positionally (the model method's parameter type drives it) and injects ctx
        assertHas(src, """"remove" -> list.remove(bosca.bml.render.actionArg(args, 0), ctx)""")
    }

    @Test
    fun `a submit action reads form fields through the sentinel and binds the submit event`() {
        val src = gen(
            """<page route="/">""" +
                """<script server provides="wall">bosca.bml.sample.WallModel()</script>""" +
                """<island name="w"><span>{ wall.count }</span></island>""" +
                """<form @submit="wall.share(form.title, ctx)"><input name="title"/></form>""" +
                """</page>""",
        ).source
        assertHas(src, """w.markup(bosca.bml.render.actionMarkers("wall", "share", "submit", listOf<Any?>(mapOf("__bmlField" to "title"))))""")
        assertHas(src, """"share" -> wall.share(bosca.bml.render.actionArg(args, 0), ctx)""")
    }

    @Test
    fun `a programmatic client action generates a dispatcher without a hidden action element`() {
        val result = gen(
            """<page route="/">""" +
                """<script server provides="player">bosca.bml.sample.PlayerModel()</script>""" +
                """<island name="p"><span>{ player.saved }</span>""" +
                """<script client>ctx.dispatch(player.save(position, duration), { coalesce: true })</script>""" +
                """</island></page>""",
        )
        assertHas(result.source, """"save" -> player.save(bosca.bml.render.actionArg(args, 0), bosca.bml.render.actionArg(args, 1))""")
        assertFalse(result.source.contains("data-bml-method"), "programmatic actions need no hidden DOM sink")
    }

    @Test
    fun `programmatic actions require a mounted client scope and a matching model`() {
        val pageScript = gen(
            """<page route="/">""" +
                """<script server provides="player">PlayerModel()</script>""" +
                """<island name="p"><span>{ player.saved }</span></island>""" +
                """<script client>ctx.dispatch(player.save)</script>""" +
                """</page>""",
        )
        assertTrue(pageScript.diagnostics.any { it.severity == Severity.Error && "requires an island script" in it.message })

        val missingModel = gen(
            """<page route="/"><island name="p"><span>state</span>""" +
                """<script client>ctx.dispatch(missing.save)</script></island></page>""",
        )
        assertTrue(missingModel.diagnostics.any { it.severity == Severity.Error && "has no matching" in it.message })
    }

    @Test
    fun `any DOM event can dispatch with scheduling and persistence modifiers`() {
        val src = gen(
            """<page route="/">""" +
                """<script server provides="player">bosca.bml.sample.PlayerModel()</script>""" +
                """<island name="p"><span>{ player.position }</span>""" +
                """<video @timeupdate.throttle.30000.coalesce.keepalive.pagehide="player.save(42)"></video>""" +
                """</island></page>""",
        ).source
        assertHas(
            src,
            """bosca.bml.render.actionMarkers(stateKey, "save", "timeupdate", listOf<Any?>((42)), throttleMs = 30000L, coalesce = true, keepalive = true, flushOnPageHide = true)""",
        )
    }

    @Test
    fun `discarded actions and invalid policies are compile errors`() {
        val result = gen(
            """<page route="/">""" +
                """<script server provides="value">loadValue()</script>""" +
                """<island name="v"><span>{ value }</span></island>""" +
                """<input @input.debounce="value.save"/>""" +
                """</page>""",
        )
        assertTrue(result.diagnostics.any { it.severity == Severity.Error && "constructor-typed" in it.message })
        assertTrue(result.diagnostics.any { it.severity == Severity.Error && "millisecond" in it.message })
    }

    @Test
    fun `a component model gets a per-instance key, inline state script, and a prefixed dispatcher`() {
        val src = gen(
            """<component tag="like-button">""" +
                """<prop name="id" type="String" required/>""" +
                """<script server provides="like">bosca.bml.sample.LikeModel(id)</script>""" +
                """<island name="like-view" :key="id"><button @click="like.toggle(ctx)">{ like.count }</button></island>""" +
                """</component>""" +
                """<page route="/"><like-button id="a"/></page>""",
        ).source
        // the instance key is evaluated once per render from props, prefixed by "<tag>.<provides>"
        assertHas(src, """val __bmlKey_like: String = "like-button.like:" + (id)""")
        // island wrapper + inline state script both carry the instance key
        assertHas(src, """w.attr("data-bml-state-key", __bmlKey_like)""")
        assertHas(src, "w.raw(bosca.bml.render.encodeClientState(like))")
        // markers inside the island reference the renderInner stateKey parameter (re-render safe)
        assertHas(src, """w.markup(bosca.bml.render.actionMarkers(stateKey, "toggle", null, null))""")
        // the dispatcher registers under the component prefix and the renderer exposes it
        assertHas(src, """override val stateKey: String = "like-button.like"""")
        assertHas(src, "override val islandActionDispatchers: List<bosca.bml.render.BmlIslandActionDispatcher> = listOf(LikeButtonLikeStateDispatcher)")
        // the component advertises a client module so the runtime's action binder loads
        assertHas(src, """BmlComponentInfo("like-button", null, "", listOf(), "XPage.js", false, renderRevision = "x.bml")""")
    }

    @Test
    fun `a client component state can derive its instance key from the initialized model`() {
        val src = gen(
            """<component tag="profile-card">""" +
                """<prop name="id" type="String" required/>""" +
                """<script server provides="profile">bosca.bml.sample.ProfileModel(id)</script>""" +
                """<island name="profile-view" :key="profile.id"><button @click="profile.save">save</button></island>""" +
                """</component>""" +
                """<page route="/"><profile-card id="a"/></page>""",
        ).source

        val modelInitialization = src.indexOf("val profile = run {")
        val keyInitialization = src.indexOf("val __bmlKey_profile: String")
        assertTrue(modelInitialization >= 0, src)
        assertTrue(keyInitialization > modelInitialization, src)
        assertHas(src, """val __bmlKey_profile: String = "profile-card.profile:" + (profile.id)""")
    }

    @Test
    fun `a server-scoped component model restores from the session under the instance key`() {
        val src = gen(
            """<component tag="cart-row">""" +
                """<prop name="id" type="String" required/>""" +
                """<script server provides="row" scope="server-session">bosca.bml.sample.RowModel(id)</script>""" +
                """<island name="row-view" :key="id"><span>{ row.count }</span></island>""" +
                """<button @click="row.add">+</button>""" +
                """</component>""" +
                """<page route="/"><cart-row id="a"/></page>""",
        ).source
        assertHas(src, """val row: bosca.bml.sample.RowModel = ctx.session?.get(bosca.bml.render.bmlPageSessionStateKey(ctx.requestPath, __bmlKey_row))?.let { bosca.bml.render.decodeState<bosca.bml.sample.RowModel>(it) } ?: run {""")
        assertHas(src, "ctx.session?.putIfAbsent(bosca.bml.render.bmlPageSessionStateKey(ctx.requestPath, __bmlKey_row), bosca.bml.render.encodeState(row))")
        // the info advertises server state so pages rendering this component mint a session
        assertHas(src, """BmlComponentInfo("cart-row", null, "", listOf(), "XPage.js", true, renderRevision = "x.bml")""")
        // the dispatcher loads/stores under the posted per-instance key
        assertHas(src, "val row: bosca.bml.sample.RowModel = bosca.bml.render.decodeState<bosca.bml.sample.RowModel>(ctx.session?.get(instanceKey) ?: state)")
        assertHas(src, "ctx.session?.put(instanceKey, bosca.bml.render.encodeState(row))")
    }

    @Test
    fun `site component state can sync headlessly with one stable client-local key`() {
        val result = gen(
            """<component tag="free-access" scope="site">""" +
                """<script server provides="access" scope="client-local">example.FreeAccess()</script>""" +
                """<main><script client scoped>ctx.dispatch(access.sync(ctx))</script></main>""" +
                """</component><page route="/"><free-access/></page>""",
        )
        val src = result.source
        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString())
        assertHas(src, """val __bmlKey_access: String = "free-access.access"""")
        assertHas(src, "override val siteScoped: Boolean = true")
        assertHas(src, """"sync" -> access.sync(ctx)""")
        assertHas(src, "return bosca.bml.render.IslandActionResult(bosca.bml.render.encodeClientState(access), null)")
        assertHas(src, """w.markup(" data-bml-scope=\"client-local\" data-bml-site>")""")
        assertFalse("FreeAccessAccessIsland" in src, "headless site state must not generate a view renderer:\n$src")
    }

    @Test
    fun `site component can opt browser state into sign out cleanup`() {
        val result = gen(
            """<component tag="account-draft" scope="site">""" +
                """<script server provides="draft" scope="client-local" clear-on-sign-out>example.AccountDraft()</script>""" +
                """<main><script client scoped>ctx.dispatch(draft.save(ctx))</script></main>""" +
                """</component><page route="/"><account-draft/></page>""",
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString())
        assertHas(
            result.source,
            """w.markup(" data-bml-scope=\"client-local\" data-bml-clear-on-sign-out data-bml-site>")""",
        )
    }

    @Test
    fun `headless site server state emits a dispatch marker without exposing its model`() {
        val result = gen(
            """<component tag="account-state" scope="site">""" +
                """<script server provides="account" scope="server-session">example.AccountState()</script>""" +
                """<main><script client scoped>ctx.dispatch(account.sync(ctx))</script></main>""" +
                """</component><page route="/"><account-state/></page>""",
        )
        val src = result.source
        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString())
        assertHas(src, """ctx.session?.putIfAbsent(__bmlKey_account, bosca.bml.render.encodeState(account))""")
        assertHas(src, """w.markup(" data-bml-site data-bml-server>")""")
        assertFalse("w.raw(" in src, "server state must not be embedded in the page:\n$src")
    }

    @Test
    fun `site component state requires durable storage`() {
        val result = gen(
            """<component tag="free-access" scope="site">""" +
                """<script server provides="access">example.FreeAccess()</script>""" +
                """<button @click="access.sync">Sync</button></component>""",
        )
        assertTrue(result.diagnostics.any { "must use `client-session`, `client-local`, or `server-session`" in it.message })
        assertFalse("FreeAccessAccessStateDispatcher" in result.source)
    }

    @Test
    fun `site component state has one unkeyed identity`() {
        val result = gen(
            """<component tag="free-access" scope="site">""" +
                """<script server provides="access" scope="client-local">example.FreeAccess()</script>""" +
                """<island name="usage" :key="access.id"><span>{ access.used }</span></island>""" +
                """<button @click="access.sync">Sync</button></component>""",
        )
        assertTrue(result.diagnostics.any { "one stable component identity" in it.message })
        assertFalse("FreeAccessAccessStateDispatcher" in result.source)
    }

    @Test
    fun `component scope accepts only page or site`() {
        val result = gen("""<component tag="free-access" scope="global"><div/></component>""")
        assertTrue(result.diagnostics.any { "scope must be `page` or `site`" in it.message })
    }

    @Test
    fun `a component live state without an island key degrades with a diagnostic`() {
        val result = gen(
            """<component tag="like-button">""" +
                """<script server provides="like">bosca.bml.sample.LikeModel()</script>""" +
                """<island name="like-view"><span>{ like.count }</span></island>""" +
                """<button @click="like.toggle">+</button>""" +
                """</component>""" +
                """<page route="/"><like-button/></page>""",
        )
        assertTrue(
            result.diagnostics.any { it.message.contains("needs a per-instance `:key") },
            "expected a missing-:key diagnostic: ${result.diagnostics}",
        )
        assertFalse(result.source.contains("StateDispatcher"), "no dispatcher for a keyless component state")
    }
}
