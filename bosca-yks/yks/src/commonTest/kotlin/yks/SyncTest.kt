package yks

import yks.utils.*
import yks.types.*
import kotlin.test.*

/**
 * Tests for multi-document synchronization and CRDT convergence.
 */
class SyncTest {

    private fun newDoc(clientID: Int): Doc {
        val doc = Doc()
        doc.clientID = clientID
        return doc
    }

    private fun sync(doc1: Doc, doc2: Doc) {
        val sv1 = encodeStateVector(doc1)
        val sv2 = encodeStateVector(doc2)
        val update1to2 = encodeStateAsUpdate(doc1, sv2)
        val update2to1 = encodeStateAsUpdate(doc2, sv1)
        applyUpdate(doc2, update1to2)
        applyUpdate(doc1, update2to1)
    }

    @Test
    fun testSyncArrayInserts() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("arr")
        val arr2 = doc2.getArray("arr")

        arr1.push(listOf("a", "b"))
        arr2.push(listOf("c", "d"))

        sync(doc1, doc2)

        assertEquals(arr1.toArray().toSet(), arr2.toArray().toSet())
        assertEquals(4, arr1.length)
        assertEquals(4, arr2.length)
    }

    @Test
    fun testSyncMapConcurrent() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val map1 = doc1.getMap("map")
        val map2 = doc2.getMap("map")

        map1.set("key1", "from-doc1")
        map2.set("key2", "from-doc2")

        sync(doc1, doc2)

        assertEquals("from-doc1", map1.get("key1"))
        assertEquals("from-doc2", map1.get("key2"))
        assertEquals("from-doc1", map2.get("key1"))
        assertEquals("from-doc2", map2.get("key2"))
    }

    @Test
    fun testSyncMapConflict() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val map1 = doc1.getMap("map")
        val map2 = doc2.getMap("map")

        // Both write to same key
        map1.set("key", "value1")
        map2.set("key", "value2")

        sync(doc1, doc2)

        // After sync, both should see the same value (last-writer-wins by client ID)
        assertEquals(map1.get("key"), map2.get("key"))
    }

    @Test
    fun testSyncTextConcurrent() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val text1 = doc1.getText("text")
        val text2 = doc2.getText("text")

        text1.insert(0, "Hello")

        sync(doc1, doc2)

        assertEquals("Hello", text2.toString())

        // Concurrent inserts at different positions
        text1.insert(5, " World")
        text2.insert(0, "Oh! ")

        sync(doc1, doc2)

        assertEquals(text1.toString(), text2.toString())
    }

    @Test
    fun testSyncMultipleRounds() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("arr")
        val arr2 = doc2.getArray("arr")

        arr1.push(listOf(1))
        sync(doc1, doc2)
        assertEquals(listOf(1), arr2.toArray())

        arr2.push(listOf(2))
        sync(doc1, doc2)
        assertEquals(listOf(1, 2), arr1.toArray())

        arr1.push(listOf(3))
        arr2.push(listOf(4))
        sync(doc1, doc2)

        assertEquals(arr1.toArray().toSet(), arr2.toArray().toSet())
        assertEquals(4, arr1.length)
    }

    @Test
    fun testSyncDeleteAndInsert() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("arr")
        arr1.push(listOf("a", "b", "c", "d", "e"))

        sync(doc1, doc2)

        val arr2 = doc2.getArray("arr")
        assertEquals(5, arr2.length)

        // doc1 deletes, doc2 inserts
        arr1.delete(1, 2) // remove "b", "c"
        arr2.push(listOf("f"))

        sync(doc1, doc2)

        assertEquals(arr1.toArray(), arr2.toArray())
        assertTrue(arr1.toArray().contains("a"))
        assertTrue(arr1.toArray().contains("d"))
        assertTrue(arr1.toArray().contains("e"))
        assertTrue(arr1.toArray().contains("f"))
        assertFalse(arr1.toArray().contains("b"))
        assertFalse(arr1.toArray().contains("c"))
    }

    @Test
    fun testSyncThreeDocs() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)
        val doc3 = newDoc(3)

        val map1 = doc1.getMap("m")
        val map2 = doc2.getMap("m")
        val map3 = doc3.getMap("m")

        map1.set("a", 1)
        map2.set("b", 2)
        map3.set("c", 3)

        // Sync all pairs
        sync(doc1, doc2)
        sync(doc2, doc3)
        sync(doc1, doc3)

        assertEquals(1, map1.get("a"))
        assertEquals(2, map1.get("b"))
        assertEquals(3, map1.get("c"))
        assertEquals(map1.entries(), map2.entries())
        assertEquals(map2.entries(), map3.entries())
    }

    @Test
    fun testSyncEmptyDoc() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        // Sync with no changes should not crash
        sync(doc1, doc2)
        assertTrue(doc1.share.isEmpty())
        assertTrue(doc2.share.isEmpty())
    }

    @Test
    fun testSyncNestedTypes() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val root1 = doc1.getMap("root")
        val inner = YArray()
        root1.set("list", inner)
        inner.push(listOf(1, 2, 3))

        sync(doc1, doc2)

        val root2 = doc2.getMap("root")
        val inner2 = root2.get("list") as YArray
        assertEquals(listOf(1, 2, 3), inner2.toArray())
    }

    @Test
    fun testIdempotentApply() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("arr")
        arr1.push(listOf("x", "y"))

        val update = encodeStateAsUpdate(doc1)
        applyUpdate(doc2, update)
        applyUpdate(doc2, update) // Apply same update again

        val arr2 = doc2.getArray("arr")
        assertEquals(2, arr2.length)
        assertEquals(listOf("x", "y"), arr2.toArray())
    }

    @Test
    fun testUpdateListener() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        // Set up auto-sync via update listener
        doc1.on2<ByteArray, Any?>("update") { update, _ ->
            applyUpdate(doc2, update)
        }

        val arr1 = doc1.getArray("arr")
        arr1.push(listOf(1, 2, 3))

        val arr2 = doc2.getArray("arr")
        assertEquals(listOf(1, 2, 3), arr2.toArray())
    }

    @Test
    fun testIncrementalSync() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("arr")
        arr1.push(listOf("first"))

        // Full sync
        val fullUpdate = encodeStateAsUpdate(doc1)
        applyUpdate(doc2, fullUpdate)

        // Now make more changes
        arr1.push(listOf("second"))

        // Incremental sync using state vector
        val sv2 = encodeStateVector(doc2)
        val diffUpdate = encodeStateAsUpdate(doc1, sv2)
        applyUpdate(doc2, diffUpdate)

        val arr2 = doc2.getArray("arr")
        assertEquals(listOf("first", "second"), arr2.toArray())
    }

    @Test
    fun testSyncMapDeleteConflict() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val map1 = doc1.getMap("map")
        map1.set("key", "initial")

        sync(doc1, doc2)

        val map2 = doc2.getMap("map")
        assertEquals("initial", map2.get("key"))

        // doc1 updates, doc2 deletes
        map1.set("key", "updated")
        map2.delete("key")

        sync(doc1, doc2)

        // Both should converge
        assertEquals(map1.get("key"), map2.get("key"))
    }

    @Test
    fun testSyncVariousValueTypes() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("arr")
        arr1.push(listOf(
            42,
            "hello",
            true,
            false,
            null
        ))

        sync(doc1, doc2)

        val arr2 = doc2.getArray("arr")
        assertEquals(5, arr2.length)
        assertEquals(42, arr2.get(0))
        assertEquals("hello", arr2.get(1))
        assertEquals(true, arr2.get(2))
        assertEquals(false, arr2.get(3))
        assertNull(arr2.get(4))
    }
}
