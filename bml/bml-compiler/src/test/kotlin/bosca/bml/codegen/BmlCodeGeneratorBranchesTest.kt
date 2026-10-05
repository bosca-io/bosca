package bosca.bml.codegen

import bosca.bml.parser.BmlParser
import bosca.bml.parser.Severity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Branch coverage for [BmlCodeGenerator] beyond the happy paths in [BmlCodeGeneratorTest]: component-only
 * files, prop/slot variants, control-flow + special-tag lowering, the live-state edge detection, `@click`
 * parsing, and string escaping.
 */
class BmlCodeGeneratorBranchesTest {

    private fun gen(s: String, obj: String = "XPage") =
        BmlCodeGenerator("test", obj, "x.bml").generate(BmlParser.parse(s).document)
    private fun src(s: String) = gen(s).source
    private fun warns(s: String) = gen(s).diagnostics.filter { it.severity == Severity.Warning }.map { it.message }
    private fun errs(s: String) = gen(s).diagnostics.filter { it.severity == Severity.Error }.map { it.message }
    private fun has(hay: String, needle: String) = assertTrue(needle in hay, "missing:\n  $needle\n--- actual ---\n$hay")
    private fun lacks(hay: String, needle: String) = assertFalse(needle in hay, "should NOT contain:\n  $needle\n--- actual ---\n$hay")

    /** A live page: `m` is a constructor-typed provides, an island views it; [click] is the button's @click. */
    private fun live(click: String, island: String = "<span>{ m.n }</span>") =
        """<page route="/"><script server provides="m">M()</script><island name="i">$island</island><button @click="$click">+</button></page>"""

    // ── generate(): component-only files + page-less imports ─────────────────

    @Test fun `every generated file imports currentRenderContext for embedded server code`() {
        has(src("""<page route="/"><h1>x</h1></page>"""), "import bosca.bml.render.currentRenderContext")
        has(src("""<component tag="c"><b>y</b></component>"""), "import bosca.bml.render.currentRenderContext")
    }

    @Test fun `component-only file emits the component and no page object or page imports`() {
        val s = src("""<component tag="x"><span>hi</span></component>""")
        has(s, "public object XComponent")
        lacks(s, "XPage")
        lacks(s, "BmlGenerated")
    }

    @Test fun `page with no route attribute defaults to an empty route`() {
        has(src("<page><h1>hi</h1></page>"), """override val route: String = """"")
    }

    // ── components: tag/prop/slot diagnostics + forms ───────────────────────

    @Test fun `component without a tag is an error`() {
        assertTrue(errs("""<component><span>x</span></component>""").any { "requires a tag" in it })
    }

    @Test fun `prop with no name is skipped and a missing type defaults to nullable Any`() {
        has(src("""<component tag="c"><prop/><prop name="x"/><span>{ x }</span></component>"""), """val x: Any? = props["x"] as Any?""")
    }

    @Test fun `string prop default is quoted and a bound default is an expression`() {
        val s = src(
            """<component tag="c"><prop name="label" type="String" default="hi"/>""" +
                """<prop name="n" type="Int" :default="1 + 1"/><span>{ label }{ n }</span></component>""",
        )
        has(s, """val label: String = (props["label"] as? String) ?: ("hi")""")
        has(s, """val n: Int = (props["n"] as? Int) ?: (1 + 1)""")
    }

    @Test fun `a component with a text-only body has no fall-through root`() {
        lacks(src("""<component tag="c">just text</component>"""), "__bmlAttrs")
    }

    @Test fun `named slot warns and still renders the default slot`() {
        val r = gen("""<component tag="c"><slot name="header"/></component>""")
        assertTrue(r.diagnostics.any { it.message.contains("named slots are a follow-on") })
        has(r.source, "slot()")
    }

    // ── component instantiation prop forms ──────────────────────────────────

    @Test fun `instantiation passes boolean and interpolated attributes`() {
        val s = src(
            """<component tag="c"><prop name="x" type="String"/><span>{ x }</span></component>""" +
                """<page route="/"><c flag x="a-{ y }"/></page>""",
        )
        has(s, """"flag" to true""")
        has(s, """"x" to ("a-" + (y))""")
    }

    // ── nodes: comments, top-level style, server-script-without-provides ─────

    @Test fun `build-only comment is dropped while html comment and top-level style are emitted`() {
        val s = src("<page route=\"/\">{# secret #}<!-- shown --><style>.a {\n  color: red\n}</style></page>")
        lacks(s, "secret")
        has(s, "shown")
        has(s, "<style>")
    }

    @Test fun `server script without provides emits its body inline, not as a val`() {
        val s = src("<page route=\"/\"><script server>val q = compute()</script><h1>hi</h1></page>")
        has(s, "val q = compute()")
        lacks(s, "val q = run {")
    }

    // ── control flow + special tags ─────────────────────────────────────────

    @Test fun `if else-if else lowers to a chained when`() {
        val s = src("<page route=\"/\"><if a><p>A</p><else-if b><p>B</p><else><p>C</p></if></page>")
        has(s, "if (a) {"); has(s, "} else if (b) {"); has(s, "} else {")
    }

    @Test fun `feature flag if forms lower through the render-context facade`() {
        val s = src(
            """<page route="/"><if flag="checkout">A""" +
                """<else-if flag="layout" variation="compact">B""" +
                """<else-if flag="layout" :when='flag.degraded || flag.value.toString() == "beta"'>C</if></page>""",
        )
        lacks(s, "import bosca.bml.render.featureFlags")
        has(s, "if (ctx.featureFlags.enabled(\"checkout\")) {")
        has(s, "} else if (ctx.featureFlags.variation(\"layout\", \"compact\")) {")
        has(
            s,
            "ctx.featureFlags.evaluate(\"layout\").let { flag -> (flag.degraded || flag.value.toString() == \"beta\") }",
        )
    }

    @Test fun `featureFlags imports are only emitted when authored`() {
        val documents = listOf(
            """<page route="/"><script server>import example.flags.featureFlags</script><if flag="checkout">A</if></page>""" to
                "import example.flags.featureFlags",
            """<component tag="card"><script server>import example.flags.featureFlags</script><p>A</p></component>""" to
                "import example.flags.featureFlags",
            """<message key="notice"><script server>import example.flags.featureFlags</script><email><subject>A</subject><html>A</html></email></message>""" to
                "import example.flags.featureFlags",
            """<page route="/"><script server>import example.flags.featureFlags;</script><p>A</p></page>""" to
                "import example.flags.featureFlags;",
            """<page route="/"><script server>import example.flags.featureFlags // site helper</script><p>A</p></page>""" to
                "import example.flags.featureFlags // site helper",
            """<page route="/"><script server>import example.flags.other as featureFlags</script><p>A</p></page>""" to
                "import example.flags.other as featureFlags",
            """<page route="/"><script server>import example.flags.other /* authored */ as featureFlags</script><p>A</p></page>""" to
                "import example.flags.other /* authored */ as featureFlags",
            """<page route="/"><script server>import java.time.*; import example.flags.featureFlags</script><p>A</p></page>""" to
                "import java.time.*; import example.flags.featureFlags",
            """<page route="/"><script server>import example.flags.other /* outer /* nested */ comment */ as featureFlags</script><p>A</p></page>""" to
                "import example.flags.other /* outer /* nested */ comment */ as featureFlags",
            """<page route="/"><script server>import example.flags.featureFlags /* outer /* nested */ comment */</script><p>A</p></page>""" to
                "import example.flags.featureFlags /* outer /* nested */ comment */",
            """<page route="/"><script server>import example.flags.`helper as source` as featureFlags</script><p>A</p></page>""" to
                "import example.flags.`helper as source` as featureFlags",
        )

        for ((document, authoredImport) in documents) {
            val s = src(document)
            has(s, authoredImport)
            lacks(s, "import bosca.bml.render.featureFlags")
        }

        val aliased = src(
            """<page route="/"><script server>import example.flags.featureFlags as siteFeatureFlags</script><p>A</p></page>""",
        )
        lacks(aliased, "import bosca.bml.render.featureFlags")

        val wildcard = src(
            """<page route="/"><script server>import java.time.*\nfeatureFlags().installationId</script><p>A</p></page>""",
        )
        has(wildcard, "import java.time.*")
        lacks(wildcard, "import bosca.bml.render.featureFlags")
    }

    @Test fun `template and use tags warn as not yet implemented`() {
        assertTrue(warns("<page route=\"/\"><template>x</template></page>").any { "<template> codegen is not yet implemented" in it })
        assertTrue(warns("<page route=\"/\"><use>x</use></page>").any { "<use> codegen is not yet implemented" in it })
    }

    @Test fun `data without provides is an error`() {
        assertTrue(errs("<page route=\"/\"><data>{ x }</data></page>").any { "<data> requires provides" in it })
    }

    @Test fun `inject requires a literal name and type`() {
        assertTrue(errs("<page route=\"/\"><inject type=\"Service\"/></page>").any { "requires non-blank literal name" in it })
        assertTrue(errs("<page route=\"/\"><inject name=\"service\" :type=\"kind\"/></page>").any { "supports only literal" in it })
    }

    @Test fun `inject name must be a Kotlin identifier`() {
        assertTrue(errs("<page route=\"/\"><inject name=\"bad-name\" type=\"Service\"/></page>").any { "valid Kotlin identifier" in it })
        assertTrue(errs("<page route=\"/\"><inject name=\"class\" type=\"Service\"/></page>").any { "valid Kotlin identifier" in it })
    }

    @Test fun `inject init must be a literal Kotlin method name`() {
        assertTrue(errs("<page route=\"/\"><inject name=\"s\" type=\"Service\" init/></page>").any { "init must be a non-blank literal" in it })
        assertTrue(errs("<page route=\"/\"><inject name=\"s\" type=\"Service\" init=\"bad-name\"/></page>").any { "valid Kotlin method name" in it })
    }

    @Test fun `inject server marker and state scope are validated`() {
        assertTrue(errs("<page route=\"/\"><inject server=\"true\" name=\"s\" type=\"Service\"/></page>").any { "bare presence marker" in it })
        assertTrue(errs("<page route=\"/\"><inject scope=\"page\" name=\"s\" type=\"Service\"/></page>").any { "scope requires the bare server marker" in it })
        assertTrue(errs("<page route=\"/\"><inject server scope=\"forever\" name=\"s\" type=\"Service\"/></page>").any { "scope must be `page`, `client-session`, `client-local`, or `server-session`" in it })
    }

    @Test fun `inject must be empty and directly owned by a render unit`() {
        assertTrue(errs("<page route=\"/\"><inject name=\"s\" type=\"Service\"><span/></inject></page>").any { "must be empty" in it })
        assertTrue(errs("<page route=\"/\"><div><inject name=\"s\" type=\"Service\"/></div></page>").any { "must be a direct child" in it })
    }

    @Test fun `any event requires a live model and does not silently wire invalid actions`() {
        val r = gen("""<page route="/"><div @hover="x.y">z</div></page>""")
        assertTrue(r.diagnostics.any { it.message.contains("does not reference a live state model") })
        lacks(r.source, "data-bml-method")
    }

    @Test fun `client script inside an if still advertises the client module`() {
        has(src("""<page route="/"><if a><script client>x()</script></if></page>"""), """override val clientModule: String = "XPage.js"""")
    }

    @Test fun `dollar signs in text are escaped in the generated string literal`() {
        has(src("""<page route="/"><p>Price: $5</p></page>"""), """w.markup("Price: \$5")""")
    }

    // ── live-state edge detection ───────────────────────────────────────────

    @Test fun `click method-call syntax is stripped to the bare method name`() {
        has(src(live("m.go()")), """actionMarkers("m", "go", null, null)""")
    }

    @Test fun `click with no dot does not wire and warns`() {
        val r = gen(live("go"))
        lacks(r.source, "data-bml-method")
        assertTrue(r.diagnostics.any { it.message.contains("is not a valid model action") })
    }

    @Test fun `click with a multi-segment receiver does not wire`() {
        lacks(src(live("a.b.c")), "data-bml-method")
    }

    @Test fun `click on a non-constructor (listOf) provides is ignored with a diagnostic`() {
        val r = gen("""<page route="/"><script server provides="items">listOf(1)</script><island name="i"><span>{ items }</span></island><button @click="items.clear">x</button></page>""")
        assertTrue(r.diagnostics.any { it.message.contains("not a constructor-typed") })
    }

    @Test fun `click on a receiver that is not a provides produces no dispatcher`() {
        lacks(src("""<page route="/"><script server provides="m">M()</script><island name="i"><span>{ m.n }</span></island><button @click="ghost.go">x</button></page>"""), "StateDispatcher")
    }

    @Test fun `click inside control flow is still collected and wired`() {
        has(
            src("""<page route="/"><script server provides="m">M()</script><island name="i"><span>{ m.n }</span></island><if cond><button @click="m.go">x</button></if></page>"""),
            """actionMarkers("m", "go", null, null)""",
        )
    }

    @Test fun `island with no name defaults its data-bml-island name`() {
        val s = src("""<page route="/"><script server provides="m">M()</script><island><span>{ m.n }</span></island><button @click="m.go">x</button></page>""")
        has(s, """data-bml-island=\"island\"""")
        has(s, """w.attr("data-bml-state-key", "m")""")
    }

    @Test fun `multiple islands referencing one state warn and use the first`() {
        val r = gen("""<page route="/"><script server provides="m">M()</script><island name="a"><span>{ m.n }</span></island><island name="b"><span>{ m.n }</span></island><button @click="m.go">x</button></page>""")
        assertTrue(r.diagnostics.any { it.message.contains("referenced by multiple islands") })
    }

    @Test fun `island recognizes state referenced via for-iterable and if-condition`() {
        has(src(live("m.go", island = "<for x in m.items><span>{ x }</span></for>")), """w.attr("data-bml-state-key", "m")""")
        has(src(live("m.go", island = "<if m.on><span>y</span></if>")), """w.attr("data-bml-state-key", "m")""")
    }

    @Test fun `island recognizes state referenced via spread and interpolated attributes`() {
        has(src(live("m.go", island = "<div {...m.attrs}>x</div>")), """w.attr("data-bml-state-key", "m")""")
        has(src(live("m.go", island = """<div class="x-{ m.cls }">y</div>""")), """w.attr("data-bml-state-key", "m")""")
    }
}
