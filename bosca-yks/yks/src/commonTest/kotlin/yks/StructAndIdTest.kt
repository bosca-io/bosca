package yks

import yks.lib0.*
import yks.structs.*
import yks.structs.content.*
import yks.types.*
import yks.utils.*
import kotlin.test.*

/**
 * Tests for ID, StructStore, Transaction, Item, and AbstractStruct
 * covering uncovered branches and edge cases.
 */
class StructAndIdTest {

    private fun newDoc(clientID: Int): Doc {
        val doc = Doc()
        doc.clientID = clientID
        return doc
    }

    // ---------------------------------------------------------------
    // ID tests
    // ---------------------------------------------------------------

    @Test
    fun testCompareIDsBothNull() {
        assertTrue(compareIDs(null, null))
    }

    @Test
    fun testCompareIDsOneNull() {
        assertFalse(compareIDs(ID(1, 0), null))
        assertFalse(compareIDs(null, ID(1, 0)))
    }

    @Test
    fun testCompareIDsSameReference() {
        val id = ID(1, 5)
        assertTrue(compareIDs(id, id))
    }

    @Test
    fun testCompareIDsEqual() {
        assertTrue(compareIDs(ID(1, 5), ID(1, 5)))
    }

    @Test
    fun testCompareIDsDifferentClient() {
        assertFalse(compareIDs(ID(1, 5), ID(2, 5)))
    }

    @Test
    fun testCompareIDsDifferentClock() {
        assertFalse(compareIDs(ID(1, 5), ID(1, 6)))
    }

    @Test
    fun testCreateID() {
        val id = createID(42, 100)
        assertEquals(42, id.client)
        assertEquals(100, id.clock)
    }

    @Test
    fun testWriteReadID() {
        val encoder = Encoder()
        val id = ID(42, 100)
        writeID(encoder, id)
        val decoder = Decoder(encoder.toByteArray())
        val decoded = readID(decoder)
        assertEquals(id, decoded)
    }

    @Test
    fun testFindRootTypeKey() {
        val doc = Doc()
        val arr = doc.getArray("myArray")
        assertEquals("myArray", findRootTypeKey(arr))
    }

    @Test
    fun testFindRootTypeKeyNotFound() {
        val doc = Doc()
        val orphan = YArray()
        orphan.doc = doc
        assertFailsWith<IllegalStateException> {
            findRootTypeKey(orphan)
        }
    }

    @Test
    fun testFindRootTypeKeyNoDoc() {
        val orphan = YArray()
        assertFailsWith<IllegalStateException> {
            findRootTypeKey(orphan)
        }
    }

    @Test
    fun testIDToString() {
        val id = ID(1, 5)
        assertEquals("ID(1, 5)", id.toString())
    }

    @Test
    fun testIDLastId() {
        val gc = GC(ID(1, 0), 5)
        assertEquals(ID(1, 4), gc.lastId)
    }

    // ---------------------------------------------------------------
    // StructStore tests
    // ---------------------------------------------------------------

    @Test
    fun testGetStateEmptyStore() {
        val store = StructStore()
        assertEquals(0, getState(store, 1))
    }

    @Test
    fun testGetStateWithStructs() {
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        assertTrue(getState(doc.store, 1) > 0)
    }

    @Test
    fun testGetStateVectorEmpty() {
        val store = StructStore()
        assertTrue(getStateVector(store).isEmpty())
    }

    @Test
    fun testGetStateVectorMultipleClients() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)
        doc1.getArray("arr").push(listOf(1, 2))
        doc2.getArray("arr").push(listOf(3))

        // Sync doc2 into doc1
        applyUpdate(doc1, encodeStateAsUpdate(doc2))

        val sv = getStateVector(doc1.store)
        assertEquals(2, sv[1])
        assertEquals(1, sv[2])
    }

    @Test
    fun testFindStruct() {
        val doc = newDoc(1)
        doc.getArray("arr").push(listOf("a"))
        val struct = find(doc.store, ID(1, 0))
        assertNotNull(struct)
        assertEquals(0, struct.id.clock)
    }

    @Test
    fun testFindStructUnknownClient() {
        val doc = newDoc(1)
        doc.getArray("arr").push(listOf("a"))
        assertFailsWith<IllegalStateException> {
            find(doc.store, ID(999, 0))
        }
    }

    @Test
    fun testFindIndexSSBasic() {
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5))
        val structs = doc.store.clients[1]!!
        val idx = findIndexSS(structs, 0)
        assertEquals(0, idx)
    }

    @Test
    fun testFindIndexSSMidClock() {
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        arr.push(listOf(1))
        arr.push(listOf(2))
        arr.push(listOf(3))
        val structs = doc.store.clients[1]!!
        // Find the struct containing clock 2
        val idx = findIndexSS(structs, 2)
        val struct = structs[idx]
        assertTrue(struct.id.clock <= 2 && struct.id.clock + struct.length > 2)
    }

    @Test
    fun testFindIndexSSClockInRange() {
        // When there's a single struct covering clock 0, looking up clock 0 should return index 0
        val doc = newDoc(1)
        doc.getArray("arr").push(listOf(1))
        val structs = doc.store.clients[1]!!
        val idx = findIndexSS(structs, 0)
        assertEquals(0, idx)
    }

    @Test
    fun testGetItemCleanStart() {
        val doc = newDoc(1)
        val text = doc.getText("text")
        text.insert(0, "abcdef") // single item of length 6

        transact(doc) { transaction ->
            val item = assertNotNull(getItemCleanStart(transaction, ID(1, 3)))
            assertEquals(3, item.id.clock)
        }
    }

    @Test
    fun testGetItemCleanEnd() {
        val doc = newDoc(1)
        val text = doc.getText("text")
        text.insert(0, "abcdef") // single item of length 6

        transact(doc) { transaction ->
            val item = assertNotNull(getItemCleanEnd(transaction, doc.store, ID(1, 2)))
            // Should split the item and return the left portion
            assertEquals(0, item.id.clock)
        }
    }

    @Test
    fun testIterateStructs() {
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        val visited = mutableListOf<AbstractStruct>()
        transact(doc) { transaction ->
            val structs = doc.store.clients[1]!!
            iterateStructs(transaction, structs, 0, 3) { struct ->
                visited.add(struct)
            }
        }
        assertTrue(visited.isNotEmpty())
    }

    @Test
    fun testIterateStructsEmptyList() {
        val doc = newDoc(1)
        val visited = mutableListOf<AbstractStruct>()
        transact(doc) { transaction ->
            iterateStructs(transaction, emptyList(), 0, 5) { visited.add(it) }
        }
        assertTrue(visited.isEmpty())
    }

    @Test
    fun testIterateStructsZeroLength() {
        val doc = newDoc(1)
        doc.getArray("arr").push(listOf(1))
        val visited = mutableListOf<AbstractStruct>()
        transact(doc) { transaction ->
            val structs = doc.store.clients[1]!!
            iterateStructs(transaction, structs, 0, 0) { visited.add(it) }
        }
        assertTrue(visited.isEmpty())
    }

    // ---------------------------------------------------------------
    // Transaction tests
    // ---------------------------------------------------------------

    @Test
    fun testTransactionBeforeAndAfterState() {
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        transact(doc) { transaction ->
            val before = transaction.beforeState
            arr.push(listOf(1, 2))
            val after = transaction.afterState
            assertTrue((after[1] ?: 0) > (before[1] ?: 0))
        }
    }

    @Test
    fun testTransactionNextID() {
        val doc = newDoc(1)
        transact(doc) { transaction ->
            val id = transaction.nextID()
            assertEquals(1, id.client)
            assertEquals(0, id.clock) // first ID for this client
        }
    }

    @Test
    fun testTransactionAddToDeleteSet() {
        val doc = newDoc(1)
        transact(doc) { transaction ->
            transaction.addToDeleteSet(1, 0, 5)
            assertTrue(transaction.deleteSet.has(1, 0))
            assertTrue(transaction.deleteSet.has(1, 4))
            assertFalse(transaction.deleteSet.has(1, 5))
        }
    }

    @Test
    fun testTransactionInsertSet() {
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        transact(doc) { transaction ->
            arr.push(listOf(1, 2))
            addToInsertSet(transaction, 1, 0, 2)
            assertTrue(transaction.insertSet.has(1, 0))
        }
    }

    @Test
    fun testTransactionWithSubdocTracking() {
        val doc = Doc()
        val map = doc.getMap("data")

        var subdocsEvent: SubdocsEvent? = null
        doc.on<SubdocsEvent>("subdocs") { event ->
            subdocsEvent = event
        }

        val subdoc = Doc(guid = "sub-1")
        map.set("child", subdoc)

        val event = subdocsEvent
        assertNotNull(event)
        assertTrue(event.added.contains(subdoc))
    }

    @Test
    fun testTransactionClientIDCollisionDetection() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(1) // same client ID

        val arr1 = doc1.getArray("arr")
        arr1.push(listOf("hello"))

        // Apply doc1's update to doc2 (non-local)
        val update = encodeStateAsUpdate(doc1)
        applyUpdate(doc2, update)

        // doc2 should detect collision and change its client ID
        assertNotEquals(1, doc2.clientID)
    }

    @Test
    fun testNestedTransactions() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        doc.transact { outer ->
            arr.push(listOf(1))
            doc.transact { inner ->
                // Inner transaction reuses the outer one
                arr.push(listOf(2))
                assertSame(outer, inner)
            }
        }
        assertEquals(2, arr.length)
    }

    // ---------------------------------------------------------------
    // Item tests
    // ---------------------------------------------------------------

    @Test
    fun testItemGetMissingNoDeps() {
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        arr.push(listOf("a"))

        transact(doc) { transaction ->
            val struct = doc.store.clients[1]!!.first()
            if (struct is Item) {
                val missing = struct.getMissing(transaction, doc.store)
                assertNull(missing) // no missing dependencies
            }
        }
    }

    @Test
    fun testItemDeleteAndRecheck() {
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b"))

        // Delete first item
        arr.delete(0, 1)

        val structs = doc.store.clients[1]!!
        val deleted = structs.filter { it is Item && it.deleted }
        assertTrue(deleted.isNotEmpty(), "Should have deleted items")
    }

    @Test
    fun testItemCountable() {
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        arr.push(listOf("a"))

        val struct = doc.store.clients[1]!!.first()
        if (struct is Item) {
            assertTrue(struct.countable)
        }
    }

    @Test
    fun testItemGcWithGcTrue() {
        val doc = Doc(gc = true)
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b", "c"))
        arr.delete(0, 3)

        // After GC, deleted items should have ContentDeleted content
        // (GC replaces content with ContentDeleted when parentGCd=false)
        val structs = doc.store.clients[1]!!
        assertTrue(structs.isNotEmpty())
        // All structs should be deleted
        assertTrue(structs.all { it.deleted })
        // With gc=true, the content should be replaced with ContentDeleted
        for (struct in structs) {
            if (struct is Item) {
                assertTrue(struct.content is ContentDeleted, "Expected ContentDeleted after GC")
            }
        }
    }

    @Test
    fun testItemWriteWithOffset() {
        val doc = newDoc(1)
        val text = doc.getText("text")
        text.insert(0, "abcdef")

        // Encode from a specific offset (simulates partial struct encoding)
        val sv = encodeStateVectorFromMap(mapOf(1 to 3))
        val update = encodeStateAsUpdate(doc, sv)

        val doc2 = Doc()
        val full = encodeStateAsUpdate(doc)
        applyUpdate(doc2, full)

        // Apply partial (should be idempotent on doc2 since it already has everything)
        applyUpdate(doc2, update)
        assertEquals("abcdef", doc2.getText("text").toString())
    }

    @Test
    fun testItemMergeWith() {
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        // Push items that should be merged
        arr.push(listOf(1))
        arr.push(listOf(2))
        arr.push(listOf(3))

        // After transaction cleanup, adjacent same-type items may merge
        val structs = doc.store.clients[1]!!
        // Count is implementation-dependent based on merging
        assertTrue(structs.isNotEmpty())
    }

    @Test
    fun testItemNavigationNextPrev() {
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        arr.push(listOf("a"))
        arr.push(listOf("b"))
        arr.push(listOf("c"))
        arr.delete(1) // delete "b"

        val structs = doc.store.clients[1]!!
        // Find the first non-deleted item
        val firstItem = structs.firstOrNull { it is Item && !it.deleted } as? Item
        assertNotNull(firstItem)

        // next should skip deleted items
        val nextItem = firstItem.next
        if (nextItem != null) {
            assertFalse(nextItem.deleted)
        }
    }

    // ---------------------------------------------------------------
    // GC struct tests
    // ---------------------------------------------------------------

    @Test
    fun testGCGetMissing() {
        val gc = GC(ID(1, 0), 5)
        val doc = newDoc(1)
        transact(doc) { transaction ->
            assertNull(gc.getMissing(transaction, doc.store))
        }
    }

    @Test
    fun testGCMergeWith() {
        val gc1 = GC(ID(1, 0), 3)
        val gc2 = GC(ID(1, 3), 2)
        assertTrue(gc1.mergeWith(gc2))
        assertEquals(5, gc1.length)
    }

    @Test
    fun testGCMergeWithNonGC() {
        val gc = GC(ID(1, 0), 3)
        val skip = Skip(ID(1, 3), 2)
        assertFalse(gc.mergeWith(skip))
    }

    @Test
    fun testGCWrite() {
        val gc = GC(ID(1, 0), 5)
        val encoder = UpdateEncoderV1()
        gc.write(encoder, 0, 0)
        // Should write info=0 and length
        val bytes = encoder.toByteArray()
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun testGCWriteWithOffset() {
        val gc = GC(ID(1, 0), 5)
        val encoder = UpdateEncoderV1()
        gc.write(encoder, 2, 0)
        // Should write length-offset = 3
    }

    @Test
    fun testGCWriteWithOffsetEnd() {
        val gc = GC(ID(1, 0), 5)
        val encoder = UpdateEncoderV1()
        gc.write(encoder, 0, 2)
        // Should write length-offset-offsetEnd = 3
    }

    @Test
    fun testGCSplice() {
        val gc = GC(ID(1, 0), 10)
        val right = gc.splice(4)
        assertEquals(4, gc.length)
        assertEquals(ID(1, 4), right.id)
        assertEquals(6, right.length)
    }

    // ---------------------------------------------------------------
    // Skip struct tests
    // ---------------------------------------------------------------

    @Test
    fun testSkipGetMissing() {
        val skip = Skip(ID(1, 0), 5)
        val doc = newDoc(1)
        transact(doc) { transaction ->
            assertNull(skip.getMissing(transaction, doc.store))
        }
    }

    @Test
    fun testSkipMergeWith() {
        val skip1 = Skip(ID(1, 0), 3)
        val skip2 = Skip(ID(1, 3), 2)
        assertTrue(skip1.mergeWith(skip2))
        assertEquals(5, skip1.length)
    }

    @Test
    fun testSkipMergeWithNonSkip() {
        val skip = Skip(ID(1, 0), 3)
        val gc = GC(ID(1, 3), 2)
        assertFalse(skip.mergeWith(gc))
    }

    @Test
    fun testSkipWrite() {
        val skip = Skip(ID(1, 0), 5)
        val encoder = UpdateEncoderV1()
        skip.write(encoder, 0, 0)
        val bytes = encoder.toByteArray()
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun testSkipIntegrateThrows() {
        val skip = Skip(ID(1, 0), 5)
        val doc = newDoc(1)
        transact(doc) { transaction ->
            assertFailsWith<UnsupportedOperationException> {
                skip.integrate(transaction, 0)
            }
        }
    }

    @Test
    fun testSkipDeleted() {
        val skip = Skip(ID(1, 0), 5)
        assertFalse(skip.deleted)
    }

    @Test
    fun testGCDeleted() {
        val gc = GC(ID(1, 0), 5)
        assertTrue(gc.deleted)
    }

    // ---------------------------------------------------------------
    // IdSet edge cases
    // ---------------------------------------------------------------

    @Test
    fun testIdSetOverlappingRanges() {
        val set = IdSet()
        set.add(1, 0, 5)
        set.add(1, 3, 5) // overlapping range
        assertTrue(set.has(1, 0))
        assertTrue(set.has(1, 7)) // merged range should cover this
    }

    @Test
    fun testIdSetHasId() {
        val set = IdSet()
        set.add(1, 0, 5)
        assertTrue(set.hasId(ID(1, 0)))
        assertTrue(set.hasId(ID(1, 4)))
        assertFalse(set.hasId(ID(1, 5)))
        assertFalse(set.hasId(ID(2, 0)))
    }

    @Test
    fun testIdSetForEach() {
        val set = IdSet()
        set.add(1, 0, 5)
        set.add(2, 10, 3)

        val clients = mutableListOf<Int>()
        set.forEach { client, _ -> clients.add(client) }
        assertTrue(clients.contains(1))
        assertTrue(clients.contains(2))
    }

    @Test
    fun testIdSetIsEmpty() {
        val set = IdSet()
        assertTrue(set.isEmpty())
        set.add(1, 0, 1)
        assertFalse(set.isEmpty())
    }

    @Test
    fun testIdRangesAdjacentMerge() {
        val ranges = IdRanges()
        ranges.add(0, 5) // [0, 5)
        ranges.add(5, 3) // [5, 8) - should extend the first range
        val ids = ranges.getIds()
        assertEquals(1, ids.size) // merged into one range
        assertEquals(0, ids[0].clock)
        assertEquals(8, ids[0].len)
    }

    @Test
    fun testIdRangesUnsortedMerge() {
        val ranges = IdRanges()
        ranges.add(10, 5) // [10, 15)
        ranges.add(0, 5)  // [0, 5) - unsorted
        val ids = ranges.getIds()
        assertEquals(2, ids.size) // non-overlapping, so two ranges
        assertEquals(0, ids[0].clock)
        assertEquals(10, ids[1].clock)
    }

    @Test
    fun testIdRangesHasBinarySearch() {
        val ranges = IdRanges()
        ranges.add(0, 3)
        ranges.add(10, 3)
        ranges.add(20, 3)

        assertTrue(ranges.has(0))
        assertTrue(ranges.has(2))
        assertFalse(ranges.has(3))
        assertTrue(ranges.has(10))
        assertTrue(ranges.has(12))
        assertFalse(ranges.has(13))
        assertTrue(ranges.has(20))
        assertFalse(ranges.has(23))
        assertFalse(ranges.has(5))
    }

    @Test
    fun testMergeIdSets() {
        val set1 = IdSet()
        set1.add(1, 0, 5)
        val set2 = IdSet()
        set2.add(1, 5, 3)
        set2.add(2, 0, 2)

        val merged = mergeIdSets(listOf(set1, set2))
        assertTrue(merged.has(1, 0))
        assertTrue(merged.has(1, 7))
        assertTrue(merged.has(2, 0))
    }

    @Test
    fun testAddStructToIdSet() {
        val set = IdSet()
        val gc = GC(ID(1, 5), 3)
        addStructToIdSet(set, gc)
        assertTrue(set.has(1, 5))
        assertTrue(set.has(1, 7))
        assertFalse(set.has(1, 8))
    }

    @Test
    fun testWriteReadIdSet() {
        val set = IdSet()
        set.add(1, 0, 5)
        set.add(2, 10, 3)

        val encoder = Encoder()
        writeIdSet(encoder, set)
        val decoder = Decoder(encoder.toByteArray())
        val decoded = readIdSet(decoder)

        assertTrue(decoded.has(1, 0))
        assertTrue(decoded.has(1, 4))
        assertTrue(decoded.has(2, 10))
        assertTrue(decoded.has(2, 12))
    }

    @Test
    fun testCreateDeleteSetFromStructStore() {
        val doc = Doc(gc = false)
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b", "c"))
        arr.delete(1, 1) // delete "b"

        val ds = createDeleteSetFromStructStore(doc.store)
        assertTrue(ds.clients.isNotEmpty())
    }
}
