package yks.sync

import yks.WireCompatFixtures
import yks.lib0.Decoder
import yks.utils.Doc
import kotlin.test.*

class AwarenessTest {

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun toHex(bytes: ByteArray): String {
        return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    // ── Basic state management ──

    @Test
    fun testInitialLocalState() {
        val doc = Doc()
        val awareness = Awareness(doc)
        val state = awareness.getLocalState()
        assertNotNull(state)
        assertTrue(state.isEmpty()) // initialized with emptyMap()
    }

    @Test
    fun testSetGetLocalState() {
        val doc = Doc()
        val awareness = Awareness(doc)
        awareness.setLocalState(mapOf("user" to "Alice", "cursor" to 5))
        val state = awareness.getLocalState()
        assertNotNull(state)
        assertEquals("Alice", state["user"])
        assertEquals(5, state["cursor"])
    }

    @Test
    fun testSetLocalStateField() {
        val doc = Doc()
        val awareness = Awareness(doc)
        awareness.setLocalState(mapOf("user" to "Alice"))
        awareness.setLocalStateField("color", "#ff0000")
        val state = awareness.getLocalState()!!
        assertEquals("Alice", state["user"])
        assertEquals("#ff0000", state["color"])
    }

    @Test
    fun testSetLocalStateNull() {
        val doc = Doc()
        val awareness = Awareness(doc)
        awareness.setLocalState(mapOf("user" to "Alice"))
        awareness.setLocalState(null)
        assertNull(awareness.getLocalState())
        assertFalse(awareness.states.containsKey(awareness.clientID))
    }

    @Test
    fun testGetStates() {
        val doc = Doc()
        val awareness = Awareness(doc)
        awareness.setLocalState(mapOf("user" to "Alice"))
        val states = awareness.getStates()
        assertEquals(1, states.size)
        assertTrue(states.containsKey(awareness.clientID))
    }

    // ── Events ──

    @Test
    fun testUpdateEventFired() {
        val doc = Doc()
        val awareness = Awareness(doc)
        val events = mutableListOf<AwarenessChange>()
        awareness.on2<AwarenessChange, Any?>("update") { change, _ -> events.add(change) }

        awareness.setLocalState(mapOf("x" to 1))
        // Initial setLocalState in constructor + our call
        assertTrue(events.isNotEmpty())
        val last = events.last()
        assertTrue(last.updated.contains(awareness.clientID) || last.added.contains(awareness.clientID))
    }

    @Test
    fun testChangeEventOnlyFiredOnDeepDiff() {
        val doc = Doc()
        val awareness = Awareness(doc)
        val changes = mutableListOf<AwarenessChange>()
        awareness.on2<AwarenessChange, Any?>("change") { change, _ -> changes.add(change) }

        // Set initial state (different from empty → fires change with added)
        val state = mapOf("user" to "Alice")
        awareness.setLocalState(state)
        val addedCount = changes.size

        // Set same state again → should NOT fire change (deep equal)
        awareness.setLocalState(mapOf("user" to "Alice"))
        assertEquals(addedCount, changes.size, "Setting same state should not fire change")

        // Set different state → should fire change
        awareness.setLocalState(mapOf("user" to "Bob"))
        assertTrue(changes.size > addedCount, "Setting different state should fire change")
    }

    @Test
    fun testRemoveEventFired() {
        val doc = Doc()
        val awareness = Awareness(doc)
        val changes = mutableListOf<AwarenessChange>()
        awareness.on2<AwarenessChange, Any?>("change") { change, _ -> changes.add(change) }

        awareness.setLocalState(null)
        val lastChange = changes.last()
        assertTrue(lastChange.removed.contains(awareness.clientID))
    }

    // ── Encode/decode ──

    @Test
    fun testEncodeDecodeRoundTrip() {
        val doc1 = Doc()
        val aw1 = Awareness(doc1)
        aw1.setLocalState(mapOf("user" to "Alice", "cursor" to 42))

        val encoded = encodeAwarenessUpdate(aw1, listOf(aw1.clientID))

        val doc2 = Doc()
        val aw2 = Awareness(doc2)
        applyAwarenessUpdate(aw2, encoded, "remote")

        val state = aw2.states[aw1.clientID]
        assertNotNull(state)
        assertEquals("Alice", state["user"])
        assertEquals(42, state["cursor"])
    }

    @Test
    fun testEncodeMultipleClients() {
        val doc = Doc()
        val aw = Awareness(doc)
        aw.setLocalState(mapOf("user" to "Alice"))

        // Simulate a remote client
        val remoteState = mapOf("user" to "Bob")
        aw.states[999] = remoteState
        aw.meta[999] = MetaClientState(1, yks.lib0.currentTimeMillis())

        val encoded = encodeAwarenessUpdate(aw, listOf(aw.clientID, 999))

        val doc2 = Doc()
        val aw2 = Awareness(doc2)
        applyAwarenessUpdate(aw2, encoded, "remote")

        assertEquals("Alice", aw2.states[aw.clientID]?.get("user"))
        assertEquals("Bob", aw2.states[999]?.get("user"))
    }

    // ── Clock-based conflict resolution ──

    @Test
    fun testNewerClockWins() {
        val doc = Doc()
        val aw = Awareness(doc)

        // Set up a remote client with clock=5
        aw.states[42] = mapOf("name" to "old")
        aw.meta[42] = MetaClientState(5, yks.lib0.currentTimeMillis())

        // Apply update with clock=10 → should update
        val remoteDoc = Doc()
        val remoteAw = Awareness(remoteDoc)
        remoteAw.states[42] = mapOf("name" to "new")
        remoteAw.meta[42] = MetaClientState(10, yks.lib0.currentTimeMillis())
        val update = encodeAwarenessUpdate(remoteAw, listOf(42))
        applyAwarenessUpdate(aw, update, "remote")

        assertEquals("new", aw.states[42]?.get("name"))
    }

    @Test
    fun testOlderClockIgnored() {
        val doc = Doc()
        val aw = Awareness(doc)

        // Set up a remote client with clock=10
        aw.states[42] = mapOf("name" to "current")
        aw.meta[42] = MetaClientState(10, yks.lib0.currentTimeMillis())

        // Apply update with clock=5 → should be ignored
        val remoteDoc = Doc()
        val remoteAw = Awareness(remoteDoc)
        remoteAw.states[42] = mapOf("name" to "old")
        remoteAw.meta[42] = MetaClientState(5, yks.lib0.currentTimeMillis())
        val update = encodeAwarenessUpdate(remoteAw, listOf(42))
        applyAwarenessUpdate(aw, update, "remote")

        assertEquals("current", aw.states[42]?.get("name"))
    }

    // ── Remove awareness states ──

    @Test
    fun testRemoveAwarenessStates() {
        val doc = Doc()
        val aw = Awareness(doc)
        aw.states[42] = mapOf("name" to "Remote")
        aw.meta[42] = MetaClientState(1, yks.lib0.currentTimeMillis())

        val changes = mutableListOf<AwarenessChange>()
        aw.on2<AwarenessChange, Any?>("change") { change, _ -> changes.add(change) }

        removeAwarenessStates(aw, listOf(42), "disconnect")

        assertFalse(aw.states.containsKey(42))
        assertTrue(changes.last().removed.contains(42))
    }

    @Test
    fun testRemoveLocalStateBumpsClock() {
        val doc = Doc()
        val aw = Awareness(doc)
        val clockBefore = aw.meta[aw.clientID]?.clock ?: 0

        removeAwarenessStates(aw, listOf(aw.clientID), "test")

        val clockAfter = aw.meta[aw.clientID]?.clock ?: 0
        assertTrue(clockAfter > clockBefore)
        assertNull(aw.getLocalState())
    }

    // ── Remote cannot null local state ──

    @Test
    fun testRemoteCannotNullLocalState() {
        val doc = Doc()
        val aw = Awareness(doc)
        aw.setLocalState(mapOf("active" to true))
        val myClock = aw.meta[aw.clientID]!!.clock

        // Remote sends null state for our clientID with higher clock
        val remoteDoc = Doc()
        val remoteAw = Awareness(remoteDoc)
        remoteAw.states.remove(aw.clientID) // null state
        remoteAw.meta[aw.clientID] = MetaClientState(myClock + 1, yks.lib0.currentTimeMillis())

        val update = encodeAwarenessUpdate(remoteAw, listOf(aw.clientID), emptyMap())
        applyAwarenessUpdate(aw, update, "remote")

        // Our local state should NOT be removed
        assertNotNull(aw.getLocalState())
        assertEquals(true, aw.getLocalState()?.get("active"))
    }

    // ── Timeout ──

    @Test
    fun testCheckTimeoutsRemovesStaleClients() {
        val doc = Doc()
        val aw = Awareness(doc)

        // Add a stale remote client (last updated 60 seconds ago)
        val staleTime = yks.lib0.currentTimeMillis() - 60_000
        aw.states[999] = mapOf("name" to "Stale")
        aw.meta[999] = MetaClientState(1, staleTime)

        aw.checkTimeouts()

        assertFalse(aw.states.containsKey(999))
    }

    @Test
    fun testCheckTimeoutsKeepsFreshClients() {
        val doc = Doc()
        val aw = Awareness(doc)

        // Add a fresh remote client
        aw.states[999] = mapOf("name" to "Fresh")
        aw.meta[999] = MetaClientState(1, yks.lib0.currentTimeMillis())

        aw.checkTimeouts()

        assertTrue(aw.states.containsKey(999))
    }

    // ── Destroy ──

    @Test
    fun testDestroy() {
        val doc = Doc()
        val aw = Awareness(doc)
        aw.setLocalState(mapOf("active" to true))

        var destroyed = false
        aw.on<Awareness>("destroy") { destroyed = true }
        aw.destroy()

        assertTrue(destroyed)
        assertNull(aw.getLocalState())
    }

    // ── Wire compatibility with JS y-protocols ──

    @Test
    fun testWireCompat_decodeAwarenessUpdate() {
        val bytes = hexToBytes(WireCompatFixtures.PROTOCOL_MESSAGES_AWARENESS_AWARENESS_UPDATE)

        val doc = Doc()
        val aw = Awareness(doc)
        applyAwarenessUpdate(aw, bytes, "test")

        val state = aw.states[WireCompatFixtures.PROTOCOL_MESSAGES_AWARENESS_CLIENT_ID]
        assertNotNull(state)
        @Suppress("UNCHECKED_CAST")
        val user = state["user"] as Map<String, Any?>
        assertEquals("Alice", user["name"])
        assertEquals("#ff0000", user["color"])
        @Suppress("UNCHECKED_CAST")
        val cursor = state["cursor"] as Map<String, Any?>
        assertEquals(5, cursor["index"])
    }

    @Test
    fun testWireCompat_decodeAwarenessNullState() {
        val bytes = hexToBytes(WireCompatFixtures.PROTOCOL_MESSAGES_AWARENESS_NULL_AWARENESS_UPDATE)

        val doc = Doc()
        val aw = Awareness(doc)
        // First add the client so we can verify removal
        aw.states[WireCompatFixtures.PROTOCOL_MESSAGES_AWARENESS_NULL_CLIENT_ID] = mapOf("active" to true)
        aw.meta[WireCompatFixtures.PROTOCOL_MESSAGES_AWARENESS_NULL_CLIENT_ID] = MetaClientState(0, yks.lib0.currentTimeMillis())

        applyAwarenessUpdate(aw, bytes, "test")

        // Client should be removed (null state with higher clock)
        assertFalse(aw.states.containsKey(WireCompatFixtures.PROTOCOL_MESSAGES_AWARENESS_NULL_CLIENT_ID))
    }

    @Test
    fun testWireCompat_decodeWrappedAwarenessMessage() {
        val bytes = hexToBytes(WireCompatFixtures.PROTOCOL_MESSAGES_AWARENESS_MESSAGE)

        val doc = Doc()
        val aw = Awareness(doc)
        val reply = MessageProtocol.readMessage(bytes, doc, aw, "test")

        assertEquals(0, reply.size, "Awareness message should not produce a reply")
        val state = aw.states[WireCompatFixtures.PROTOCOL_MESSAGES_AWARENESS_CLIENT_ID]
        assertNotNull(state)
    }

    @Test
    fun testWireCompat_encodeMatchesJsFormat() {
        // Verify our encoding produces bytes that match the JS fixture
        val doc = Doc()
        doc.clientID = WireCompatFixtures.PROTOCOL_MESSAGES_AWARENESS_CLIENT_ID
        val aw = Awareness(doc)

        // Set the same state as the fixture
        aw.setLocalState(mapOf(
            "user" to mapOf("name" to "Alice", "color" to "#ff0000"),
            "cursor" to mapOf("index" to 5)
        ))
        // Set the clock to match the fixture
        aw.meta[aw.clientID] = MetaClientState(
            WireCompatFixtures.PROTOCOL_MESSAGES_AWARENESS_CLOCK,
            yks.lib0.currentTimeMillis()
        )

        val encoded = encodeAwarenessUpdate(aw, listOf(aw.clientID))
        val expected = WireCompatFixtures.PROTOCOL_MESSAGES_AWARENESS_AWARENESS_UPDATE

        // Parse both to verify they decode the same way (JSON key order may differ)
        val doc2 = Doc()
        val aw2 = Awareness(doc2)
        applyAwarenessUpdate(aw2, hexToBytes(expected), "js")

        val doc3 = Doc()
        val aw3 = Awareness(doc3)
        applyAwarenessUpdate(aw3, encoded, "kt")

        assertEquals(aw2.states[aw.clientID], aw3.states[aw.clientID])
    }

    @Test
    fun testWireCompat_queryAwarenessProducesReply() {
        val doc = Doc()
        val aw = Awareness(doc)
        aw.setLocalState(mapOf("user" to "Alice"))

        val queryBytes = hexToBytes(WireCompatFixtures.PROTOCOL_MESSAGES_QUERY_AWARENESS_MESSAGE)
        val reply = MessageProtocol.readMessage(queryBytes, doc, aw)

        assertTrue(reply.isNotEmpty(), "QueryAwareness should produce a reply")
        // Reply should be an awareness message containing our state
        val decoder = Decoder(reply)
        assertEquals(MessageProtocol.MESSAGE_AWARENESS, decoder.readVarUint())
    }
}
