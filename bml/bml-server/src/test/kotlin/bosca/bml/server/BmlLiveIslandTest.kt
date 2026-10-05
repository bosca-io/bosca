package bosca.bml.server

import bosca.bml.render.BmlIslandActionDispatcher
import bosca.bml.render.IslandActionResult
import bosca.bml.render.RenderContext
import bosca.bml.render.bmlPageSessionStateKey
import bosca.bml.render.encodeActionResponse
import bosca.bml.render.parseActionRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BmlLiveIslandTest {

    // ── session store (scope="server-session") ──────────────────────────────

    @Test
    fun `session store round-trips state by id`() = runTest {
        val store = InMemoryBmlSessionStore()
        val s = store.create()
        s.put("counterModel", "{\"count\":1}")
        val again = store.get(s.id)
        assertNotNull(again)
        assertEquals("{\"count\":1}", again.get("counterModel"))
    }

    @Test
    fun `unknown session id returns null`() = runTest {
        assertNull(InMemoryBmlSessionStore().get("nope"))
    }

    @Test
    fun `session state is initialized once`() = runTest {
        val session = InMemoryBmlSessionStore().create()

        assertTrue(session.putIfAbsent("counterModel", "first"))
        assertFalse(session.putIfAbsent("counterModel", "second"))
        assertEquals("first", session.get("counterModel"))
    }

    @Test
    fun `idle sessions expire past the ttl`() = runTest {
        var now = 0L
        val store = InMemoryBmlSessionStore(ttlMillis = 1000, clock = { now })
        val s = store.create()
        now = 1500
        assertNull(store.get(s.id), "a session idle past the TTL must be gone")
    }

    @Test
    fun `bounded store evicts the oldest session`() = runTest {
        var now = 0L
        val store = InMemoryBmlSessionStore(maxSessions = 2, clock = { now })
        val a = store.create(); now = 1
        store.create(); now = 2
        store.create() // exceeds the cap → the oldest (a) is evicted
        assertNull(store.get(a.id), "oldest session must be evicted past the cap")
        assertEquals(2, store.size())
    }

    @Test
    fun `session bounds page state without evicting site state`() = runTest {
        var now = 0L
        val session = InMemoryBmlSessionStore(
            maxPageStatesPerSession = 2,
            pageStateTtlMillis = 60_000,
            clock = { now },
        ).create()
        session.put("account-state.account", "site")
        val first = bmlPageSessionStateKey("/first", "draft")
        val second = bmlPageSessionStateKey("/second", "draft")
        val third = bmlPageSessionStateKey("/third", "draft")
        session.put(first, "first"); now++
        session.put(second, "second"); now++
        session.put(third, "third")

        assertNull(session.get(first))
        assertEquals("second", session.get(second))
        assertEquals("third", session.get(third))
        assertEquals("site", session.get("account-state.account"))
    }

    @Test
    fun `page state expires while an active session retains site state`() = runTest {
        var now = 0L
        val session = InMemoryBmlSessionStore(pageStateTtlMillis = 1_000, clock = { now }).create()
        val page = bmlPageSessionStateKey("/article", "draft")
        session.put(page, "page")
        session.put("site.preferences", "site")
        now = 1_001

        assertNull(session.get(page))
        assertEquals("site", session.get("site.preferences"))
    }

    // ── wire format (core-bml, hand-written / native-safe) ──────────────────

    @Test
    fun `parseActionRequest reads page, stateKey, method, and state`() {
        val req = parseActionRequest(
            "{\"page\":\"/\",\"stateKey\":\"counterModel\",\"method\":\"increment\",\"state\":\"\"}",
        )
        assertEquals("/", req.page)
        assertEquals("counterModel", req.stateKey)
        assertEquals("increment", req.method)
        assertEquals("", req.state)
    }

    @Test
    fun `parseActionRequest tolerates a missing field`() {
        val req = parseActionRequest("{\"page\":\"/\",\"stateKey\":\"m\",\"method\":\"increment\"}")
        assertEquals("", req.state)
        assertEquals("increment", req.method)
    }

    @Test
    fun `encodeActionResponse encodes a null state as JSON null`() {
        assertEquals(
            "{\"state\":null,\"html\":\"<b>x</b>\"}",
            encodeActionResponse(IslandActionResult(null, "<b>x</b>")),
        )
    }

    @Test
    fun `encodeActionResponse json-escapes the client state and html`() {
        val out = encodeActionResponse(IslandActionResult("{\"count\":1}", "<span>1</span>"))
        assertTrue(out.startsWith("{\"state\":\""), out)
        assertTrue(out.contains("\"html\":\"<span>1</span>\""), out)
    }

    // ── dispatcher plumbing (both scopes) ───────────────────────────────────

    /**
     * A stand-in for the codegen-emitted dispatcher: parses the trivial `{"count":N}` by hand so this layer
     * needs no serialization plugin. The real `encodeState`/`decodeState` round-trip is exercised by the
     * bml-sample render test.
     */
    private fun counterDispatcher(serverScoped: Boolean) = object : BmlIslandActionDispatcher {
        override val stateKey = "counterModel"
        override val serverScoped = serverScoped
        override suspend fun dispatch(ctx: RenderContext, method: String, state: String, args: kotlinx.serialization.json.JsonArray, instanceKey: String): IslandActionResult {
            val json = if (serverScoped) ctx.session?.get(stateKey) ?: "{\"count\":0}" else state
            var count = Regex("\"count\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (method == "increment") count++
            val newJson = "{\"count\":$count}"
            ctx.writer.markup("<span>Count: $count</span>")
            return if (serverScoped) {
                ctx.session?.put(stateKey, newJson)
                IslandActionResult(null, ctx.writer.toString())
            } else {
                IslandActionResult(newJson, ctx.writer.toString())
            }
        }
    }

    @Test
    fun `client-scoped dispatch returns the new state and re-rendered html`() = runTest {
        val result = counterDispatcher(serverScoped = false).dispatch(RenderContext(), "increment", "{\"count\":0}")
        assertEquals("{\"count\":1}", result.state)
        assertEquals("<span>Count: 1</span>", result.html)
    }

    @Test
    fun `server-scoped dispatch persists to the session and returns no client state`() = runTest {
        val store = InMemoryBmlSessionStore()
        val session = store.create()
        session.put("counterModel", "{\"count\":0}")
        val result = counterDispatcher(serverScoped = true).dispatch(RenderContext(session = session), "increment", "")
        assertNull(result.state, "server scope must never return client state")
        assertEquals("<span>Count: 1</span>", result.html)
        assertEquals("{\"count\":1}", store.get(session.id)?.get("counterModel"))
    }
}
