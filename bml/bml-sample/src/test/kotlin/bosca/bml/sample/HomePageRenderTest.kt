package bosca.bml.sample

import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.RenderContext
import bosca.bml.render.propsFromJson
import bosca.bml.server.BmlStyleAssets
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end runtime test for the BML K2 compiler plugin: `bml.generated.HomePage` is generated
 * in-memory from `src/main/bml/home.bml` (there is no `HomePage.kt` on disk). Rendering it exercises
 * the embedded server Kotlin — the `<script server provides=...>` values, `{ greeting }` interpolation,
 * and — the focus here — passing real Kotlin values into components: a server-provided `List<String>`
 * (`:items="fruits"`) and a computed value (`:label="fruits.size.toString()"`).
 */
class HomePageRenderTest {

    @Test
    fun `generated page renders its embedded Kotlin and passes Kotlin values into components`() = runBlocking {
        val ctx = RenderContext()
        bml.generated.HomePage.render(ctx)
        val html = ctx.toString()

        assertTrue("Hello from BML" in html, "server <script provides> output missing:\n$html")
        // <item-list :items="fruits"> received the Kotlin List and iterated it (one <li> per element).
        // fruits = listOf("alpha", "beta", "gamma", greeting) → the greeting is the 4th element.
        assertTrue("alpha" in html && "beta" in html && "gamma" in html, "component List iteration missing:\n$html")
        // The component read a member of the passed Kotlin value: `{ items.size }` -> "(4)".
        assertTrue("Fruits (4)" in html, "component reading items.size on the passed List missing:\n$html")
        assertTrue("<h1>" in html && "<li" in html, "expected HTML structure missing:\n$html")
        // Island SSR wrapper (a live view, so it also carries the state key it reflects).
        assertTrue("""data-bml-island="counter" data-bml-state-key="counterModel"""" in html, "island wrapper missing:\n$html")
        // <badge :label="fruits.size.toString()" tone="hot"/>: a computed Kotlin value passed in as a prop,
        // on an element now carrying the component's scope marker.
        assertTrue("""<span data-bml-c="badge" class="badge badge-hot">4</span>""" in html, "computed Kotlin value / scope marker missing:\n$html")
        // Scoped CSS (Nuxt-style): each component's <style scoped> is inlined once with selectors
        // rewritten to match only elements stamped with that component's marker.
        assertTrue("""data-bml-c="item-list"""" in html, "item-list scope marker missing:\n$html")
        assertTrue(""".badge[data-bml-c="badge"]""" in html, "scoped badge selector missing:\n$html")
        assertTrue(""".item-list h2[data-bml-c="item-list"]""" in html, "scoped descendant selector missing:\n$html")
    }

    @Test
    fun `generated page implements BmlPageRenderer with route and client module`() {
        val page: BmlPageRenderer = bml.generated.HomePage
        assertEquals("/", page.route)
        // The page has <script client>, so it advertises its bundle (served by bml-server).
        assertEquals("HomePage.js", page.clientModule)
    }

    @Test
    fun `BmlPages registry enumerates the generated pages for server wiring`() {
        val routes = bml.generated.BmlPages.all.map { it.route }
        assertTrue("/" in routes, "BmlPages.all should include HomePage (route '/'): $routes")
    }

    @Test
    fun `BmlComponents registry exposes each component's scoped CSS and the page lists what it renders`() {
        val byTag = bml.generated.BmlComponents.byTag
        assertTrue("badge" in byTag && "item-list" in byTag, "registry tags: ${byTag.keys}")
        assertTrue(byTag.getValue("badge").styles.contains("""[data-bml-c="badge"]"""), "badge CSS should be scoped")
        assertTrue("badge" in bml.generated.HomePage.componentTags, "page manifest: ${bml.generated.HomePage.componentTags}")
    }

    @Test
    fun `a component re-renders as a server fragment from posted JSON props`() = runBlocking {
        // This is the sliver re-render path: client POSTs state -> server renders one component -> HTML.
        val renderer = bml.generated.BmlComponents.renderers.getValue("counter-view")
        val ctx = RenderContext(inlineStyles = false) // a sliver is pure markup; page already has the CSS
        renderer.render(ctx, propsFromJson("""{"count": 7}"""), {})
        val html = ctx.toString()
        assertTrue("Count: 7" in html, "posted Int prop should render: $html")
        assertTrue("""data-bml-c="counter-view"""" in html, "scope marker should remain on the fragment: $html")
        assertFalse("<style>" in html, "a sliver must not re-inject the component <style>:\n$html")
    }

    @Test
    fun `live island first paint emits a client state script, view island, and outside button markers`() = runBlocking {
        // home.bml's counterModel is explicitly client-session scoped: the @Serializable
        // model is serialized into a JSON script, round-tripped, and restored after a refresh.
        val ctx = RenderContext()
        bml.generated.HomePage.render(ctx)
        val html = ctx.toString()
        // client scope: the model is serialized into a JSON state script (the round-tripped source of truth)
        assertTrue(
            """<script type="application/json" data-bml-state-key="counterModel" data-bml-scope="client-session">{"count":0}</script>""" in html,
            "client state script missing:\n$html",
        )
        // The island is a pure view carrying the state key (the join), not the state itself.
        assertTrue(
            """data-bml-island="counter" data-bml-state-key="counterModel"""" in html,
            "view island state key missing:\n$html",
        )
        // The button lives OUTSIDE the island, wired purely by state key + method. `<button>` is the
        // `button` component here, so the markers FALL THROUGH onto its real <html:button> root — no wrapper.
        assertTrue(
            """<button data-bml-c="button" data-bml-state-key="counterModel" data-bml-method="increment">""" in html,
            "outside-island action markers missing from the button:\n$html",
        )
        assertFalse("@click" in html, "the @click attribute must be dropped:\n$html")
        assertFalse("display:contents" in html, "fall-through must not wrap the button:\n$html")
        // Page identity marker, so an action POST routes to THIS page's dispatcher (state keys are per-page).
        assertTrue(
            """<script type="application/json" data-bml-page="/"></script>""" in html,
            "page identity marker missing:\n$html",
        )
    }

    @Test
    fun `the page registers a client-scoped live dispatcher under its route`() {
        // Dispatchers are scoped by page route then state key (two pages may both define `counterModel`).
        val dispatchers = bml.generated.BmlIslands.dispatchers.getValue("/")
        assertTrue("counterModel" in dispatchers, "dispatcher not registered: ${dispatchers.keys}")
        val d = dispatchers.getValue("counterModel")
        assertEquals("counterModel", d.stateKey)
        assertFalse(d.serverScoped, "home.bml counterModel uses client scope")
        assertFalse(bml.generated.HomePage.hasServerState, "the home page has no server state")
    }

    @Test
    fun `dispatching increment runs the model method, re-renders the view, and returns the new client state`() = runBlocking {
        // Real encodeState/decodeState round-trip through the @Serializable CounterModel (client-scoped).
        val d = bml.generated.BmlIslands.dispatchers.getValue("/").getValue("counterModel")
        // client scope: the model round-trips through the posted state; the new state is returned to the client
        val result = d.dispatch(RenderContext(inlineStyles = false), "increment", """{"count":0}""")
        assertEquals("""{"count":1}""", result.state, "client scope returns the new serialized model")
        val html = assertNotNull(result.html, "dispatch must return a rendered fragment")
        assertTrue("Count: 1" in html, "view re-rendered with the incremented count:\n$html")
        assertTrue("""data-bml-c="counter-view"""" in html, "scope marker preserved on the fragment:\n$html")
        assertFalse("<style>" in html, "a re-render must not re-inject the component <style>:\n$html")
    }

    @Test
    fun `dispatching an unknown method re-renders without mutating the state`() = runBlocking {
        // exercises the dispatcher's `when` else arm: an unrecognized method runs no model method
        val d = bml.generated.BmlIslands.dispatchers.getValue("/").getValue("counterModel")
        val result = d.dispatch(RenderContext(inlineStyles = false), "frobnicate", """{"count":7}""")
        assertEquals("""{"count":7}""", result.state, "unknown method must leave the state unchanged")
        val html = assertNotNull(result.html, "dispatch must return a rendered fragment")
        assertTrue("Count: 7" in html, html)
    }

    @Test
    fun `linked asset mode suppresses inline component styles and links per-page chunks`() = runBlocking {
        // Linked mode: the server serves+links component CSS instead of inlining it.
        val ctx = RenderContext(inlineStyles = false)
        bml.generated.HomePage.render(ctx)
        val html = ctx.toString()
        assertFalse("<style>" in html, "linked mode must not inline component <style>:\n$html")
        // …but the scope markers stay, so the linked stylesheet still matches.
        assertTrue("""data-bml-c="badge"""" in html && """data-bml-c="item-list"""" in html, html)

        // The server's link assembly for THIS page: global app.css + only the components it renders.
        val byTag = bml.generated.BmlComponents.byTag
        val closure = BmlStyleAssets.closure(bml.generated.HomePage.componentTags, byTag)
        assertTrue("badge" in closure && "item-list" in closure, "render closure: $closure")
        val links = BmlStyleAssets.cssLinks(hasGlobalCss = true, closure, byTag)
        assertTrue("/_bml/app.css" in links, "global stylesheet link missing: $links")
        assertTrue("/_bml/css/badge.css" in links && "/_bml/css/item-list.css" in links, "component links missing: $links")
    }
}
