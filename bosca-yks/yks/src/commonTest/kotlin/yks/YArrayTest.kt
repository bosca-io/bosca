package yks

import yks.utils.*
import yks.types.*
import kotlin.test.*

class YArrayTest {

    @Test
    fun testInsertAtBeginning() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(2, 3))
        arr.insert(0, listOf(0, 1))
        assertEquals(listOf(0, 1, 2, 3), arr.toArray())
    }

    @Test
    fun testInsertInMiddle() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 4))
        arr.insert(1, listOf(2, 3))
        assertEquals(listOf(1, 2, 3, 4), arr.toArray())
    }

    @Test
    fun testInsertAtEnd() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2))
        arr.insert(2, listOf(3, 4))
        assertEquals(listOf(1, 2, 3, 4), arr.toArray())
    }

    @Test
    fun testDeleteFirst() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        arr.delete(0)
        assertEquals(listOf(2, 3), arr.toArray())
    }

    @Test
    fun testDeleteLast() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        arr.delete(2)
        assertEquals(listOf(1, 2), arr.toArray())
    }

    @Test
    fun testDeleteAll() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        arr.delete(0, 3)
        assertEquals(0, arr.length)
        assertEquals(emptyList(), arr.toArray())
    }

    @Test
    fun testPop() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b", "c"))
        val popped = arr.pop()
        assertEquals("c", popped)
        assertEquals(2, arr.length)
        assertEquals(listOf("a", "b"), arr.toArray())
    }

    @Test
    fun testLargeArray() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val items = (0 until 100).toList()
        arr.push(items)
        assertEquals(100, arr.length)
        for (i in 0 until 100) {
            assertEquals(i, arr.get(i))
        }
    }

    @Test
    fun testMixedTypes() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, "two", 3.0, true, null))
        assertEquals(1, arr.get(0))
        assertEquals("two", arr.get(1))
        assertEquals(3.0, arr.get(2))
        assertEquals(true, arr.get(3))
        assertNull(arr.get(4))
    }

    @Test
    fun testNestedArray() {
        val doc = Doc()
        val outer = doc.getArray("outer")
        val inner = YArray()
        outer.push(listOf(inner))
        inner.push(listOf(1, 2, 3))
        val retrieved = outer.get(0) as YArray
        assertEquals(listOf(1, 2, 3), retrieved.toArray())
    }

    @Test
    fun testNestedMap() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val map = YMap()
        arr.push(listOf(map))
        map.set("key", "value")
        val retrieved = arr.get(0) as YMap
        assertEquals("value", retrieved.get("key"))
    }

    @Test
    fun testObserveInsert() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var eventCount = 0
        arr.observe { _, _ ->
            eventCount++
        }
        arr.push(listOf(1))
        arr.push(listOf(2))
        assertEquals(2, eventCount)
    }

    @Test
    fun testObserveDelete() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        var eventFired = false
        arr.observe { _, _ ->
            eventFired = true
        }
        arr.delete(1)
        assertTrue(eventFired)
    }

    @Test
    fun testInsertAndDeleteInterleaved() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5))
        arr.delete(2, 1) // remove 3
        arr.insert(2, listOf(30))
        assertEquals(listOf(1, 2, 30, 4, 5), arr.toArray())
    }

    @Test
    fun testEmptyArray() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        assertEquals(0, arr.length)
        assertEquals(emptyList(), arr.toArray())
        assertNull(arr.get(0))
    }

    @Test
    fun testToJSON() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, "two", true))
        val json = arr.toJSON()
        assertEquals(listOf(1, "two", true), json)
    }

    @Test
    fun testArraySyncRoundTrip() {
        val doc1 = Doc()
        val arr1 = doc1.getArray("arr")
        arr1.push(listOf(10, 20, 30))
        arr1.delete(1) // remove 20
        arr1.insert(1, listOf(25))

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val arr2 = doc2.getArray("arr")
        assertEquals(listOf(10, 25, 30), arr2.toArray())
    }

    // ---------------------------------------------------------------
    // insert(index, value) single-value overload
    // ---------------------------------------------------------------

    @Test
    fun testInsertSingleValue() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 3))
        arr.insert(1, 2)
        assertEquals(listOf(1, 2, 3), arr.toArray())
    }

    @Test
    fun testInsertSingleValueAtBeginning() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(2, 3))
        arr.insert(0, 1)
        assertEquals(listOf(1, 2, 3), arr.toArray())
    }

    @Test
    fun testInsertSingleValueAtEnd() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2))
        arr.insert(2, 3)
        assertEquals(listOf(1, 2, 3), arr.toArray())
    }

    @Test
    fun testInsertSingleNullValue() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1))
        arr.insert(0, null)
        assertEquals(listOf(null, 1), arr.toArray())
    }

    // ---------------------------------------------------------------
    // callObserver and delta verification
    // ---------------------------------------------------------------

    @Test
    fun testObserveDeltaOnInsert() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.push(listOf(10, 20, 30))
        val delta = capturedDelta
        assertNotNull(delta)
        assertEquals(1, delta.size)
        val insert = delta[0] as Delta.Insert
        assertEquals(listOf(10, 20, 30), insert.values)
    }

    @Test
    fun testObserveDeltaOnDelete() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5))
        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.delete(1, 2)
        val delta = capturedDelta
        assertNotNull(delta)
        assertEquals(2, delta.size)
        val retain = delta[0] as Delta.Retain
        assertEquals(1, retain.length)
        val delete = delta[1] as Delta.Delete
        assertEquals(2, delete.length)
    }

    // ---------------------------------------------------------------
    // deleteAt edge cases
    // ---------------------------------------------------------------

    @Test
    fun testDeleteZeroLength() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        arr.delete(1, 0)
        assertEquals(listOf(1, 2, 3), arr.toArray())
    }

    @Test
    fun testDeleteSpanningMultipleItems() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        // Push items in separate batches to create multiple linked-list items
        arr.push(listOf(1, 2))
        arr.push(listOf(3, 4))
        arr.push(listOf(5, 6))
        // Delete from middle of first batch through middle of last batch
        arr.delete(1, 4)
        assertEquals(listOf(1, 6), arr.toArray())
    }

    @Test
    fun testDeleteRequiringSplitAtStartAndEnd() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5))
        // Delete from index 1 to 3 (values 2, 3, 4) which requires splitting the single item
        arr.delete(1, 3)
        assertEquals(listOf(1, 5), arr.toArray())
    }

    // ---------------------------------------------------------------
    // insertAfter with Doc value (subdocument)
    // ---------------------------------------------------------------

    @Test
    fun testInsertSubdocumentIntoArray() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val subdoc = Doc(guid = "sub-123")
        arr.push(listOf(subdoc))
        val retrieved = arr.get(0)
        assertIs<Doc>(retrieved)
        assertEquals("sub-123", retrieved.guid)
    }

    // ---------------------------------------------------------------
    // insertAfter with ByteArray value (binary data)
    // ---------------------------------------------------------------

    @Test
    fun testInsertBinaryDataIntoArray() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val data = byteArrayOf(1, 2, 3, 4, 5)
        arr.push(listOf(data))
        val retrieved = arr.get(0)
        assertIs<ByteArray>(retrieved)
        assertContentEquals(data, retrieved)
    }

    @Test
    fun testInsertMixedPrimitivesDocAndBinary() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val subdoc = Doc(guid = "mixed-doc")
        val binary = byteArrayOf(99)
        val nested = YMap()
        arr.push(listOf(1, subdoc, binary, nested, "end"))
        assertEquals(5, arr.length)
        assertEquals(1, arr.get(0))
        assertTrue(arr.get(1) is Doc)
        assertTrue(arr.get(2) is ByteArray)
        assertTrue(arr.get(3) is YMap)
        assertEquals("end", arr.get(4))
    }

    // ---------------------------------------------------------------
    // propagateDeepEvent
    // ---------------------------------------------------------------

    @Test
    fun testPropagateDeepEventOnNestedMapModification() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val map = YMap()
        arr.push(listOf(map))

        var deepEvents: List<YEvent>? = null
        arr.observeDeep { events, _ ->
            deepEvents = events
        }

        map.set("key", "value")

        val events = deepEvents
        assertNotNull(events)
        assertEquals(1, events.size)
        assertSame(map, events[0].target)
    }

    @Test
    fun testPropagateDeepEventPath() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val map = YMap()
        arr.push(listOf(map))

        var capturedPath: List<Any>? = null
        arr.observeDeep { events, _ ->
            capturedPath = events[0].getPath()
        }

        map.set("hello", "world")

        assertNotNull(capturedPath)
        assertEquals(listOf<Any>(0), capturedPath)
    }

    @Test
    fun testPropagateDeepEventMultipleLevels() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val innerArr = YArray()
        val innerMap = YMap()
        arr.push(listOf(innerArr))
        innerArr.push(listOf(innerMap))

        var rootDeepEvents: List<YEvent>? = null
        arr.observeDeep { events, _ ->
            rootDeepEvents = events
        }

        innerMap.set("deep", true)

        val rootEvents = rootDeepEvents
        assertNotNull(rootEvents)
        assertEquals(1, rootEvents.size)
        assertSame(innerMap, rootEvents[0].target)
    }

    // ---------------------------------------------------------------
    // Sync round-trip with binary and subdoc values
    // ---------------------------------------------------------------

    @Test
    fun testBinaryDataSyncRoundTrip() {
        val doc1 = Doc()
        val arr1 = doc1.getArray("arr")
        val data = byteArrayOf(10, 20, 30)
        arr1.push(listOf(data))

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val arr2 = doc2.getArray("arr")
        assertEquals(1, arr2.length)
        assertContentEquals(data, arr2.get(0) as ByteArray)
    }
}
