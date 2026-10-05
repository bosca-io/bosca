package bosca.bml.codegen

import bosca.bml.parser.BmlParser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BmlClientCodeGeneratorTest {

    private fun gen(src: String): String? {
        val parsed = BmlParser.parse(src)
        assertFalse(parsed.hasErrors, "parse errors: ${parsed.diagnostics}")
        return BmlClientCodeGenerator().generate(parsed.document)
    }

    private fun assertHas(haystack: String, needle: String) =
        assertTrue(haystack.contains(needle), "expected TS to contain:\n  $needle\n--- actual ---\n$haystack")

    @Test
    fun `page with no client script yields null`() {
        assertNull(gen("""<page route="/"><h1>{ greeting }</h1></page>"""))
    }

    @Test
    fun `a live click with no client script emits a minimal mount module`() {
        val ts = gen(
            """<page route="/"><script server provides="m">M()</script>""" +
                """<island name="c"><span>{ m.count }</span></island>""" +
                """<button @click="m.increment">+</button></page>""",
        )!!
        // Even with no author <script client>, the @click needs the runtime to mount + bind actions.
        assertHas(ts, """import { mountAll } from "@bosca/bml"""")
        assertFalse(ts.contains("enableDeferred"), ts)
        assertHas(ts, "mountAll()")
        assertFalse(ts.contains("defineIsland"), "no islands defined here — just the mount call:\n$ts")
    }

    @Test
    fun `shared interactive page starts identity before mounting`() {
        val ts = gen(
            """<page route="/" cache="shared" maxAge="60"><script server provides="m">M()</script>""" +
                """<button @click="m.increment">+</button></page>""",
        ) ?: error("shared interactive page did not generate client startup")

        assertHas(ts, """import { enableDeferred, mountAll } from "@bosca/bml"""")
        assertTrue(
            ts.indexOf("enableDeferred()") < ts.indexOf("mountAll()"),
            "shared identity preparation must begin before actions are mounted:\n$ts",
        )
    }

    @Test
    fun `shared page with transitive client runtime emits identity bootstrap`() {
        val parsed = BmlParser.parse("""<page route="/" cache="shared" maxAge="60"><account-shell/></page>""")
        assertFalse(parsed.hasErrors, "parse errors: ${parsed.diagnostics}")

        val ts = BmlClientCodeGenerator().generate(parsed.document, hasTransitiveClientRuntime = true)
            ?: error("shared page did not generate transitive client startup")

        assertHas(ts, "enableDeferred()")
        assertHas(ts, "mountAll()")
    }

    @Test
    fun `static shared page still yields null`() {
        assertNull(gen("""<page route="/" cache="shared" maxAge="60"><h1>News</h1></page>"""))
    }

    @Test
    fun `a deferred-only page emits runtime startup`() {
        val ts = gen(
            """<page route="/"><island name="account" render="deferred"><p>Account</p></island></page>""",
        ) ?: error("deferred-only page did not generate client startup")
        assertHas(ts, """import { enableDeferred, mountAll } from "@bosca/bml"""")
        assertHas(ts, "enableDeferred()")
        assertHas(ts, "mountAll()")
    }

    @Test
    fun `deferred fallback client code and refs are not mounted after replacement`() {
        val ts = gen(
            """
            <page route="/">
              <island name="account" render="deferred">
                <fallback>
                  <button ref="retry">Retry</button>
                  <script client>fallbackOnly()</script>
                </fallback>
                <button ref="ready">Ready</button>
                <script client>ctx.refs.ready.focus()</script>
              </island>
            </page>
            """.trimIndent(),
        ) ?: error("deferred island did not generate a client module")

        assertHas(ts, "readonly ready: HTMLButtonElement")
        assertHas(ts, "ctx.refs.ready.focus()")
        assertHas(ts, """import { defineComponent, defineIsland, enableDeferred, mountAll, renderFragment } from "@bosca/bml"""")
        assertHas(ts, "enableDeferred()")
        assertTrue(
            ts.indexOf("enableDeferred()") < ts.indexOf("defineIsland<"),
            "deferred identity preparation must begin before authored client setup is registered:\n$ts",
        )
        assertFalse(ts.contains("fallbackOnly"), ts)
        assertFalse(ts.contains("readonly retry"), ts)
    }

    @Test
    fun `page-level client script is emitted at top level with runtime import`() {
        val ts = gen("""<page route="/"><script client>console.log("hi")</script></page>""")!!
        assertHas(ts, """import { defineComponent, defineIsland, mountAll, renderFragment } from "@bosca/bml"""")
        assertFalse(ts.contains("enableDeferred"), ts)
        assertHas(ts, """console.log("hi")""")
        assertHas(ts, "mountAll()")
    }

    @Test
    fun `standard on-event attributes publish their page-script functions to window`() {
        // `onsubmit="createAccount(event)"` is plain HTML, but the page script bundles as an ES
        // module — its functions are module-scoped, so the generator publishes the referenced ones.
        val ts = gen(
            """<page route="/">""" +
                """<form onsubmit="createAccount(event)"><button type="submit">Go</button></form>""" +
                """<button onclick="signOut(event)">Sign out</button>""" +
                """<script client>function createAccount(e) {} function signOut(e) {}</script>""" +
                """</page>""",
        )!!
        assertHas(ts, "Object.assign(window, { createAccount, signOut })")
        assertHas(ts, "mountAll()")
    }

    @Test
    fun `legacy component inline handlers remain reachable from module bundles`() {
        val ts = gen(
            """<component tag="bookmark-button">""" +
                """<button onclick="peBookmarkAuth()">Save</button>""" +
                """<script client>function peBookmarkAuth() { window.showAuthPrompt?.() }</script>""" +
                """</component>""",
        )!!
        assertHas(ts, "Object.assign(window, { peBookmarkAuth })")
        assertHas(ts, "mountAll()")
    }

    @Test
    fun `island client script is wrapped in defineIsland with ctx in scope`() {
        val ts = gen(
            """
            <page route="/">
              <island name="counter">
                <button>0</button>
                <script client>
                  let n = 0
                  ctx.root.querySelector("button")
                </script>
              </island>
            </page>
            """.trimIndent(),
        )!!
        assertHas(ts, """defineIsland<Record<string, never>>("counter", (ctx) => {""")
        assertHas(ts, "let n = 0")
        assertHas(ts, """ctx.root.querySelector("button")""")
        // The captured block is dedented to a single common indent (no ragged leading whitespace).
        assertFalse(ts.contains("\n          let n"), "client body was not dedented:\n$ts")
    }

    @Test
    fun `component island client registrations include their owning component`() {
        val ts = gen(
            """
            <component tag="account-shell">
              <island name="private"><script client>account()</script></island>
            </component>
            <component tag="profile-shell">
              <island name="private" render="deferred"><script client>profile()</script></island>
            </component>
            """.trimIndent(),
        )!!

        assertHas(ts, """defineIsland<Record<string, never>>("component:account-shell:private", (ctx) => {""")
        assertHas(ts, """defineIsland<Record<string, never>>("component:profile-shell:private", (ctx) => {""")
    }

    @Test
    fun `scoped component scripts get typed refs and programmatic actions without window globals`() {
        val ts = gen(
            """
            <component tag="player-controls">
              <script server provides="progress">ProgressModel()</script>
              <island name="progress"><span>{ progress.saved }</span></island>
              <button ref="save">Save</button>
              <script client scoped>
                import type { Player } from "./player"
                let player: Player | null = null
                ctx.on(ctx.refs.save, "click", () => ctx.dispatch(progress.save(ctx, [1, 2], { label: "a,b" })))
              </script>
            </component>
            """.trimIndent(),
        )!!
        assertTrue(ts.indexOf("""import type { Player } from "./player"""") < ts.indexOf("defineComponent<"), ts)
        assertHas(ts, """defineComponent<{ readonly save: HTMLButtonElement }>("player-controls", (ctx) => {""")
        assertHas(ts, "let player: Player | null = null")
        assertHas(ts, """ctx.on(ctx.refs.save, "click", () => ctx.dispatch("progress", "save", [[1, 2], { label: "a,b" }]))""")
        assertFalse(ts.contains("Object.assign(window"), "scoped handlers must stay instance-local:\n$ts")
    }
}
