package bosca.bml.codegen

import bosca.bml.parser.BmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Branch coverage for [BmlClientCodeGenerator]: control-flow collection, combined output, dedent, mount. */
class BmlClientCodeGeneratorBranchesTest {

    private fun gen(src: String): String? = BmlClientCodeGenerator().generate(BmlParser.parse(src).document)
    private fun assertHas(ts: String, needle: String) =
        assertTrue(ts.contains(needle), "expected TS to contain:\n  $needle\n--- actual ---\n$ts")

    @Test fun `island client script inside a for is collected`() {
        val ts = gen("""<page route="/"><for x in xs><island name="row"><script client>const r = 1</script></island></for></page>""")!!
        assertHas(ts, """defineIsland<Record<string, never>>("row", (ctx) => {""")
        assertHas(ts, "const r = 1")
    }

    @Test fun `island client scripts inside if and else are both collected`() {
        val ts = gen(
            """<page route="/"><if a>""" +
                """<island name="y"><script client>let y = 1</script></island>""" +
                """<else><island name="n"><script client>let n = 2</script></island></if></page>""",
        )!!
        assertHas(ts, """defineIsland<Record<string, never>>("y"""")
        assertHas(ts, """defineIsland<Record<string, never>>("n"""")
    }

    @Test fun `page script and island emit together with a single mountAll`() {
        val ts = gen(
            """<page route="/"><island name="c"><script client>let a = 1</script></island>""" +
                """<script client>console.log(1)</script></page>""",
        )!!
        assertHas(ts, "defineIsland")
        assertHas(ts, "console.log(1)")
        assertEquals(1, Regex("""mountAll\(\)""").findAll(ts).count(), "exactly one mountAll():\n$ts")
    }

    @Test fun `at-click inside control flow with no client script emits the minimal mount module`() {
        val ts = gen(
            """<page route="/"><script server provides="m">M()</script>""" +
                """<island name="c"><span>{ m.n }</span></island>""" +
                """<if cond><button @click="m.go">x</button></if></page>""",
        )!!
        assertHas(ts, """import { mountAll } from "@bosca/bml"""")
        assertHas(ts, "mountAll()")
        assertFalse(ts.contains("defineIsland"), "no island client setup here:\n$ts")
    }

    @Test fun `a ragged, blank-padded client body is dedented to a common indent`() {
        val ts = gen(
            "<page route=\"/\"><script client>\n\n        const a = 1\n          const b = 2\n\n</script></page>",
        )!!
        // leading/trailing blank lines dropped; common 8-space indent removed (relative nesting kept)
        assertHas(ts, "\nconst a = 1\n")
        assertHas(ts, "\n  const b = 2\n")
    }

    @Test fun `a page with neither client scripts nor events yields null`() {
        assertEquals(null, gen("""<page route="/"><h1>{ x }</h1></page>"""))
    }
}
