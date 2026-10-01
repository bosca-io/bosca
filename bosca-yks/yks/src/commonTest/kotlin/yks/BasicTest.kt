package yks

import yks.utils.*
import yks.types.*
import kotlin.test.*

class BasicTest {

    @Test
    fun testDocCreation() {
        val doc = Doc()
        assertNotNull(doc.clientID)
        assertNotNull(doc.guid)
        assertTrue(doc.share.isEmpty())
    }

    @Test
    fun testYArrayInsertAndGet() {
        val doc = Doc()
        val arr = doc.getArray("test")
        arr.insert(0, listOf(1, 2, 3))
        assertEquals(3, arr.length)
        assertEquals(1, arr.get(0))
        assertEquals(2, arr.get(1))
        assertEquals(3, arr.get(2))
    }

    @Test
    fun testYArrayPush() {
        val doc = Doc()
        val arr = doc.getArray("test")
        arr.push(listOf("hello", "world"))
        assertEquals(2, arr.length)
        assertEquals("hello", arr.get(0))
        assertEquals("world", arr.get(1))
    }

    @Test
    fun testYArrayDelete() {
        val doc = Doc()
        val arr = doc.getArray("test")
        arr.insert(0, listOf(1, 2, 3, 4, 5))
        arr.delete(1, 2)
        assertEquals(3, arr.length)
        assertEquals(1, arr.get(0))
        assertEquals(4, arr.get(1))
        assertEquals(5, arr.get(2))
    }

    @Test
    fun testYArrayToArray() {
        val doc = Doc()
        val arr = doc.getArray("test")
        arr.insert(0, listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), arr.toArray())
    }

    @Test
    fun testYMapSetAndGet() {
        val doc = Doc()
        val map = doc.getMap("test")
        map.set("key1", "value1")
        map.set("key2", 42)
        assertEquals("value1", map.get("key1"))
        assertEquals(42, map.get("key2"))
        assertNull(map.get("nonexistent"))
    }

    @Test
    fun testYMapDelete() {
        val doc = Doc()
        val map = doc.getMap("test")
        map.set("key", "value")
        assertTrue(map.has("key"))
        map.delete("key")
        assertFalse(map.has("key"))
        assertNull(map.get("key"))
    }

    @Test
    fun testYMapOverwrite() {
        val doc = Doc()
        val map = doc.getMap("test")
        map.set("key", "first")
        map.set("key", "second")
        assertEquals("second", map.get("key"))
    }

    @Test
    fun testYTextInsertAndToString() {
        val doc = Doc()
        val text = doc.getText("test")
        text.insert(0, "Hello, World!")
        assertEquals("Hello, World!", text.toString())
    }

    @Test
    fun testYTextInsertMultiple() {
        val doc = Doc()
        val text = doc.getText("test")
        text.insert(0, "World!")
        text.insert(0, "Hello, ")
        assertEquals("Hello, World!", text.toString())
    }

    @Test
    fun testYTextDelete() {
        val doc = Doc()
        val text = doc.getText("test")
        text.insert(0, "Hello, World!")
        text.delete(5, 8) // delete ", World!"
        assertEquals("Hello", text.toString())
    }

    @Test
    fun testDocToJSON() {
        val doc = Doc()
        val arr = doc.getArray("array")
        arr.push(listOf(1, 2, 3))
        val map = doc.getMap("map")
        map.set("key", "value")

        val json = doc.toJSON()
        assertEquals(listOf(1, 2, 3), json["array"])
    }

    @Test
    fun testStateVector() {
        val doc = Doc()
        val arr = doc.getArray("test")
        arr.push(listOf(1, 2, 3))
        val sv = encodeStateVector(doc)
        val decoded = decodeStateVector(sv)
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.values.any { it > 0 })
    }

    @Test
    fun testEncodeDecodeUpdate() {
        val doc1 = Doc()
        val arr1 = doc1.getArray("test")
        arr1.push(listOf("hello", "world"))

        // Verify doc1 state
        assertEquals(2, arr1.length)
        assertEquals("hello", arr1.get(0))
        assertEquals("world", arr1.get(1))

        // Verify store state
        val sv1 = getStateVector(doc1.store)
        assertTrue(sv1.isNotEmpty(), "State vector should not be empty")
        val clientState = sv1[doc1.clientID] ?: 0
        assertTrue(clientState >= 2, "Client state should be >= 2, got $clientState")

        // Check store directly
        val structs = doc1.store.clients[doc1.clientID]
        assertNotNull(structs, "Structs should exist for client")
        assertTrue(structs.size >= 1, "Should have at least 1 struct, got ${structs.size}")

        // Encode doc1 state
        val update = encodeStateAsUpdate(doc1)
        assertTrue(update.isNotEmpty(), "Update should not be empty")

        // Apply to doc2
        val doc2 = Doc()
        applyUpdate(doc2, update)

        // Check doc2 store
        val sv2 = getStateVector(doc2.store)
        val doc2Structs = doc2.store.clients.values.flatten()

        val arr2 = doc2.getArray("test")
        assertEquals(2, arr2.length, "Expected 2 items in array, got ${arr2.length}. Doc2 store has ${doc2Structs.size} structs total. SV2=$sv2")
        assertEquals("hello", arr2.get(0))
        assertEquals("world", arr2.get(1))
    }

    @Test
    fun testSyncTwoDocs() {
        val doc1 = Doc()
        val doc2 = Doc()

        // Make changes on doc1
        val map1 = doc1.getMap("shared")
        map1.set("from", "doc1")

        // Sync doc1 → doc2
        val sv2 = encodeStateVector(doc2)
        val update1to2 = encodeStateAsUpdate(doc1, sv2)
        applyUpdate(doc2, update1to2)

        // Verify doc2 has the changes
        val map2 = doc2.getMap("shared")
        assertEquals("doc1", map2.get("from"))

        // Make changes on doc2
        map2.set("from2", "doc2")

        // Sync doc2 → doc1
        val sv1 = encodeStateVector(doc1)
        val update2to1 = encodeStateAsUpdate(doc2, sv1)
        applyUpdate(doc1, update2to1)

        // Verify bidirectional sync
        assertEquals("doc2", map1.get("from2"))
    }

    @Test
    fun testObserveArray() {
        val doc = Doc()
        val arr = doc.getArray("test")
        var eventFired = false
        arr.observe { _, _ ->
            eventFired = true
        }
        arr.push(listOf(1))
        assertTrue(eventFired)
    }

    @Test
    fun testObserveMap() {
        val doc = Doc()
        val map = doc.getMap("test")
        var eventFired = false
        map.observe { _, _ ->
            eventFired = true
        }
        map.set("key", "value")
        assertTrue(eventFired)
    }

    @Test
    fun testNestedTypes() {
        val doc = Doc()
        val root = doc.getMap("root")
        val innerArray = YArray()
        root.set("list", innerArray)
        innerArray.push(listOf(1, 2, 3))
        assertEquals(3, (root.get("list") as YArray).length)
    }

    @Test
    fun testEncodingRoundTrip() {
        // Test that encoding basic values round-trips correctly
        val encoder = yks.lib0.Encoder()
        encoder.writeVarUint(0)
        encoder.writeVarUint(127)
        encoder.writeVarUint(128)
        encoder.writeVarUint(16383)
        encoder.writeVarUint(16384)
        encoder.writeVarInt(0)
        encoder.writeVarInt(-1)
        encoder.writeVarInt(63)
        encoder.writeVarInt(-64)
        encoder.writeVarString("hello")
        encoder.writeVarString("")
        encoder.writeAny(42)
        encoder.writeAny("test")
        encoder.writeAny(null)
        encoder.writeAny(true)
        encoder.writeAny(false)

        val decoder = yks.lib0.Decoder(encoder.toByteArray())
        assertEquals(0, decoder.readVarUint())
        assertEquals(127, decoder.readVarUint())
        assertEquals(128, decoder.readVarUint())
        assertEquals(16383, decoder.readVarUint())
        assertEquals(16384, decoder.readVarUint())
        assertEquals(0, decoder.readVarInt())
        assertEquals(-1, decoder.readVarInt())
        assertEquals(63, decoder.readVarInt())
        assertEquals(-64, decoder.readVarInt())
        assertEquals("hello", decoder.readVarString())
        assertEquals("", decoder.readVarString())
        assertEquals(42, decoder.readAny())
        assertEquals("test", decoder.readAny())
        assertNull(decoder.readAny())
        assertEquals(true, decoder.readAny())
        assertEquals(false, decoder.readAny())
    }
}
