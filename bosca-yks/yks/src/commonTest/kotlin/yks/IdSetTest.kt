package yks

import yks.lib0.*
import yks.utils.*
import kotlin.test.*

class IdSetTest {

    @Test
    fun testAddAndHas() {
        val set = IdSet()
        set.add(1, 0, 5)
        assertTrue(set.has(1, 0))
        assertTrue(set.has(1, 4))
        assertFalse(set.has(1, 5))
        assertFalse(set.has(2, 0))
    }

    @Test
    fun testMultipleClients() {
        val set = IdSet()
        set.add(1, 0, 3)
        set.add(2, 10, 5)
        assertTrue(set.has(1, 0))
        assertTrue(set.has(1, 2))
        assertTrue(set.has(2, 10))
        assertTrue(set.has(2, 14))
        assertFalse(set.has(1, 3))
        assertFalse(set.has(2, 15))
    }

    @Test
    fun testAdjacentRangesMerge() {
        val set = IdSet()
        set.add(1, 0, 3)
        set.add(1, 3, 2) // Adjacent — should extend
        assertTrue(set.has(1, 0))
        assertTrue(set.has(1, 4))
        val ranges = set.clients[1]!!.getIds()
        assertEquals(1, ranges.size) // Should be merged into one range
        assertEquals(0, ranges[0].clock)
        assertEquals(5, ranges[0].len)
    }

    @Test
    fun testOverlappingRanges() {
        val set = IdSet()
        set.add(1, 0, 5)
        set.add(1, 3, 5) // Overlapping
        assertTrue(set.has(1, 0))
        assertTrue(set.has(1, 7))
        val ranges = set.clients[1]!!.getIds()
        assertEquals(1, ranges.size)
        assertEquals(0, ranges[0].clock)
        assertEquals(8, ranges[0].len)
    }

    @Test
    fun testDisjointRanges() {
        val set = IdSet()
        set.add(1, 0, 3)
        set.add(1, 10, 3)
        assertTrue(set.has(1, 0))
        assertTrue(set.has(1, 2))
        assertFalse(set.has(1, 3))
        assertTrue(set.has(1, 10))
        assertTrue(set.has(1, 12))
        assertFalse(set.has(1, 13))
    }

    @Test
    fun testEmpty() {
        val set = IdSet()
        assertTrue(set.isEmpty())
        assertFalse(set.has(0, 0))
    }

    @Test
    fun testHasId() {
        val set = IdSet()
        set.add(1, 5, 10)
        assertTrue(set.hasId(ID(1, 5)))
        assertTrue(set.hasId(ID(1, 14)))
        assertFalse(set.hasId(ID(1, 15)))
        assertFalse(set.hasId(ID(1, 4)))
    }

    @Test
    fun testWriteReadIdSet() {
        val set = IdSet()
        set.add(1, 0, 5)
        set.add(2, 10, 3)
        set.add(1, 8, 2)

        val encoder = Encoder()
        writeIdSet(encoder, set)
        val decoder = Decoder(encoder.toByteArray())
        val decoded = readIdSet(decoder)

        assertTrue(decoded.has(1, 0))
        assertTrue(decoded.has(1, 4))
        assertTrue(decoded.has(1, 8))
        assertTrue(decoded.has(1, 9))
        assertTrue(decoded.has(2, 10))
        assertTrue(decoded.has(2, 12))
        assertFalse(decoded.has(1, 5))
        assertFalse(decoded.has(2, 13))
    }

    @Test
    fun testMergeIdSets() {
        val set1 = IdSet()
        set1.add(1, 0, 5)
        val set2 = IdSet()
        set2.add(1, 5, 5)
        set2.add(2, 0, 3)

        val merged = mergeIdSets(listOf(set1, set2))
        assertTrue(merged.has(1, 0))
        assertTrue(merged.has(1, 9))
        assertTrue(merged.has(2, 0))
        assertTrue(merged.has(2, 2))
    }

    @Test
    fun testCreateDeleteSetFromStore() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5))
        arr.delete(1, 2) // Delete items at index 1 and 2

        val ds = createDeleteSetFromStructStore(doc.store)
        assertFalse(ds.isEmpty())
    }
}
