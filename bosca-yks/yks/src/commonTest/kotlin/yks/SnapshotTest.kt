package yks

import yks.utils.*
import yks.types.*
import kotlin.test.*

class SnapshotTest {

    @Test
    fun testSnapshotCapture() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        val snap = snapshot(doc)
        assertFalse(snap.sv.isEmpty())
        assertTrue(snap.sv.values.any { it > 0 })
    }

    @Test
    fun testEmptySnapshot() {
        assertEquals(IdSet().clients.size, emptySnapshot.ds.clients.size)
        assertTrue(emptySnapshot.sv.isEmpty())
    }

    @Test
    fun testEqualSnapshots() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        val snap1 = snapshot(doc)
        val snap2 = snapshot(doc)
        assertTrue(equalSnapshots(snap1, snap2))
    }

    @Test
    fun testDifferentSnapshots() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2))

        val snap1 = snapshot(doc)

        arr.push(listOf(3))

        val snap2 = snapshot(doc)
        assertFalse(equalSnapshots(snap1, snap2))
    }

    @Test
    fun testSnapshotEncodeDecodeRoundTrip() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        arr.delete(1)

        val snap = snapshot(doc)
        val encoded = encodeSnapshot(snap)
        val decoded = decodeSnapshot(encoded)

        assertTrue(equalSnapshots(snap, decoded))
    }

    @Test
    fun testSnapshotWithMultipleTypes() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b"))
        val map = doc.getMap("map")
        map.set("key", "value")
        val text = doc.getText("text")
        text.insert(0, "hello")

        val snap = snapshot(doc)
        val encoded = encodeSnapshot(snap)
        val decoded = decodeSnapshot(encoded)
        assertTrue(equalSnapshots(snap, decoded))
    }

    @Test
    fun testIsVisible() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        val snap1 = snapshot(doc)

        arr.delete(1) // delete item at index 1

        // Items created before snap1 and not deleted in snap1 should be visible
        val store = doc.store
        for ((_, structs) in store.clients) {
            for (struct in structs) {
                if (struct is yks.structs.Item) {
                    // All items that existed at snap1 should be visible in snap1
                    val visible = isVisible(struct, snap1)
                    if (struct.id.clock < (snap1.sv[struct.id.client] ?: 0)) {
                        // This item existed at snapshot time
                        if (!snap1.ds.has(struct.id.client, struct.id.clock)) {
                            assertTrue(visible)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun testCreateSnapshot() {
        val ds = IdSet()
        ds.add(1, 0, 3)
        val sv = mapOf(1 to 5, 2 to 3)
        val snap = createSnapshot(ds, sv)
        assertEquals(sv, snap.sv)
        assertTrue(snap.ds.has(1, 0))
        assertTrue(snap.ds.has(1, 2))
    }

    // --- Snapshot encoding V2 and createDocFromSnapshot ---

    @Test
    fun testCreateDocFromSnapshotBasic() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        val snap = snapshot(doc)

        arr.push(listOf(4, 5))

        val restored = createDocFromSnapshot(doc, snap)
        val restoredArr = restored.getArray("arr")
        assertEquals(3, restoredArr.length)
        assertEquals(listOf(1, 2, 3), restoredArr.toArray())
    }

    @Test
    fun testCreateDocFromSnapshotWithDeletions() {
        val doc = Doc(gc = false)
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b", "c", "d"))

        val snap1 = snapshot(doc)

        arr.delete(1, 2) // delete "b" and "c"

        // Restore to snap1: all structs from before deletion are included
        val restored1 = createDocFromSnapshot(doc, snap1)
        val restoredArr = restored1.getArray("arr")
        assertEquals(4, restoredArr.length)
    }

    @Test
    fun testCreateDocFromSnapshotWithMap() {
        val doc = Doc(gc = false)
        val map = doc.getMap("map")
        map.set("key1", "value1")
        map.set("key2", "value2")

        val snap = snapshot(doc)

        map.set("key1", "changed")
        map.delete("key2")
        map.set("key3", "new")

        // Restore to snap: only structs within snap's state vector
        val restored = createDocFromSnapshot(doc, snap)
        val restoredMap = restored.getMap("map")
        assertEquals("value1", restoredMap.get("key1"))
        assertEquals("value2", restoredMap.get("key2"))
        assertNull(restoredMap.get("key3"))
    }

    @Test
    fun testCreateDocFromSnapshotWithText() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        val snap = snapshot(doc)

        text.insert(5, " World")

        val restored = createDocFromSnapshot(doc, snap)
        assertEquals("Hello", restored.getText("text").toString())
    }

    @Test
    fun testCreateDocFromSnapshotGcFalse() {
        val doc = Doc(gc = false)
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        val snap = snapshot(doc)

        arr.delete(0, 2)
        arr.push(listOf(4))

        val restored = createDocFromSnapshot(doc, snap)
        val restoredArr = restored.getArray("arr")
        assertEquals(3, restoredArr.length)
        assertEquals(listOf(1, 2, 3), restoredArr.toArray())
    }

    @Test
    fun testCreateDocFromSnapshotEmpty() {
        val doc = Doc()
        doc.getArray("arr") // create empty shared type

        val snap = snapshot(doc)

        val restored = createDocFromSnapshot(doc, snap)
        assertTrue(restored.store.clients.isEmpty() || restored.store.clients.all { it.value.isEmpty() })
    }

    @Test
    fun testSnapshotEncodeDecodeWithDeletions() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5))
        arr.delete(1, 2)

        val snap = snapshot(doc)
        val encoded = encodeSnapshot(snap)
        val decoded = decodeSnapshot(encoded)

        assertTrue(equalSnapshots(snap, decoded))
        // Verify the delete set is preserved
        assertTrue(decoded.ds.clients.isNotEmpty())
    }

    @Test
    fun testIsVisibleDeletedItem() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        // Take snapshot before deletion
        val snap1 = snapshot(doc)

        arr.delete(1) // delete item at index 1

        // Take snapshot after deletion
        val snap2 = snapshot(doc)

        // Iterate items and check visibility in both snapshots
        val store = doc.store
        for ((_, structs) in store.clients) {
            for (struct in structs) {
                if (struct is yks.structs.Item) {
                    val visSnap1 = isVisible(struct, snap1)
                    val visSnap2 = isVisible(struct, snap2)
                    if (struct.deleted) {
                        // In snap1 the item was not deleted, so should be visible
                        assertTrue(visSnap1, "Deleted item should be visible in pre-delete snapshot")
                        // In snap2 the item is in the delete set, so should not be visible
                        assertFalse(visSnap2, "Deleted item should not be visible in post-delete snapshot")
                    }
                }
            }
        }
    }

    @Test
    fun testIsVisibleUnknownClient() {
        val doc = Doc()
        doc.getArray("arr").push(listOf(1))
        val snap = snapshot(doc)
        // Create an item with a client not in the snapshot
        val fakeItem = yks.structs.Item(
            id = ID(999999, 0),
            left = null,
            origin = null,
            right = null,
            rightOrigin = null,
            parent = null,
            parentSub = null,
            content = yks.structs.content.ContentDeleted(1)
        )
        assertFalse(isVisible(fakeItem, snap))
    }
}
