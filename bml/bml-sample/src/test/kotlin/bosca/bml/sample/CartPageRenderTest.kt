package bosca.bml.sample

import bosca.bml.render.RenderContext
import bosca.bml.render.bmlPageSessionStateKey
import bosca.bml.server.InMemoryBmlSessionStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end test for a `scope="server-session"` live island: the [Cart] is held in the server session and never
 * serialized to the client (only an opaque session id is). Generated in-memory from `src/main/bml/cart.bml`
 * by the BML K2 plugin. Contrast with [HomePageRenderTest], whose counterModel is client-scoped.
 */
class CartPageRenderTest {
    private val sessionKey = bmlPageSessionStateKey("/cart", "cart")

    @Test
    fun `the cart page declares server state and a page-scoped server dispatcher`() {
        assertTrue(bml.generated.CartPage.hasServerState, "cart page must declare server state")
        // Dispatchers are scoped by route then state key: the cart lives under /cart, the counter under /.
        val cart = bml.generated.BmlIslands.dispatchers.getValue("/cart").getValue("cart")
        assertTrue(cart.serverScoped, """cart is scope="server-session"""")
        assertTrue("counterModel" in bml.generated.BmlIslands.dispatchers.getValue("/"), "home still has counterModel")
        assertFalse("cart" in bml.generated.BmlIslands.dispatchers.getValue("/"), "cart must NOT be under the home route")
    }

    @Test
    fun `first paint stores the model in the session and emits no client state`() = runBlocking {
        val session = InMemoryBmlSessionStore().create()
        val ctx = RenderContext(session = session).apply { requestPath = "/cart" }
        bml.generated.CartPage.render(ctx)
        val html = ctx.toString()
        // server scope: NO client state script (the cart contents never reach the client)
        assertFalse(
            """<script type="application/json" data-bml-state-key="cart">""" in html,
            "server state must not be serialized into a client script:\n$html",
        )
        // identity is the HttpOnly bml_session cookie — no in-page session id script
        assertFalse("data-bml-session" in html, "server identity is a cookie now, not an in-page script:\n$html")
        // the model is stored server-side under the state key
        assertEquals("""{"itemCount":0}""", session.get(sessionKey), "cart must be stored in the session")
        // page identity marker for action routing
        assertTrue("""data-bml-page="/cart"""" in html, "page marker missing:\n$html")
    }

    @Test
    fun `a render with an existing session restores the stored model (survives refresh)`() = runBlocking {
        // The durability guarantee: a reused (cookie-identified) session keeps its model across page loads,
        // rather than re-running the Cart() initializer and resetting to 0.
        val session = InMemoryBmlSessionStore().create()
        session.put(sessionKey, """{"itemCount":7}""")
        val ctx = RenderContext(session = session).apply { requestPath = "/cart" }
        bml.generated.CartPage.render(ctx)
        assertTrue("7 item(s)" in ctx.toString(), "render must restore the stored server state, not reset it:\n$ctx")
        assertEquals("""{"itemCount":7}""", session.get(sessionKey), "the restored model is re-persisted unchanged")
    }

    @Test
    fun `dispatch loads from and persists to the session, returning no client state`() = runBlocking {
        val session = InMemoryBmlSessionStore().create()
        bml.generated.CartPage.render(RenderContext(session = session).apply { requestPath = "/cart" }) // first paint seeds the session
        val d = bml.generated.BmlIslands.dispatchers.getValue("/cart").getValue("cart")
        // server scope: the posted client state is empty; the model is loaded from the session
        val result = d.dispatch(RenderContext(session = session, inlineStyles = false), "addItem", "", instanceKey = sessionKey)
        assertNull(result.state, "server scope returns no client state")
        val html = assertNotNull(result.html, "dispatch must return a rendered fragment")
        assertTrue("1 item(s)" in html, "view re-rendered with the new count:\n$html")
        assertEquals("""{"itemCount":1}""", session.get(sessionKey), "the new state is persisted server-side")
    }

    @Test
    fun `multiple @click methods on one model are each dispatchable`() = runBlocking {
        val session = InMemoryBmlSessionStore().create()
        session.put(sessionKey, """{"itemCount":5}""")
        val d = bml.generated.BmlIslands.dispatchers.getValue("/cart").getValue("cart")
        val result = d.dispatch(RenderContext(session = session, inlineStyles = false), "clear", "", instanceKey = sessionKey)
        assertEquals("""{"itemCount":0}""", session.get(sessionKey), "clear() resets the count")
        val html = assertNotNull(result.html, "dispatch must return a rendered fragment")
        assertTrue("0 item(s)" in html, html)
    }

    @Test
    fun `dispatching an unknown method leaves the server state unchanged`() = runBlocking {
        // the dispatcher's `when` else arm for a server-scoped state: re-render, persist unchanged
        val session = InMemoryBmlSessionStore().create()
        session.put(sessionKey, """{"itemCount":4}""")
        val d = bml.generated.BmlIslands.dispatchers.getValue("/cart").getValue("cart")
        val result = d.dispatch(RenderContext(session = session, inlineStyles = false), "frobnicate", "", instanceKey = sessionKey)
        val html = assertNotNull(result.html, "dispatch must return a rendered fragment")
        assertTrue("4 item(s)" in html, html)
        assertEquals("""{"itemCount":4}""", session.get(sessionKey), "unknown method must not change the cart")
    }
}
