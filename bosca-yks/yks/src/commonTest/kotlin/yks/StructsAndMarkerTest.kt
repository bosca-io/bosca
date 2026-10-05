package yks

import yks.structs.*
import yks.structs.content.*
import yks.types.*
import yks.utils.*
import kotlin.test.*

class StructsAndMarkerTest {

    // =========================================================================
    // GC Tests
    // =========================================================================

    @Test
    fun testGCDeletedAlwaysTrue() {
        val gc = GC(ID(1, 0), 5)
        assertTrue(gc.deleted, "GC.deleted should always be true")
    }

    @Test
    fun testGCIdAndLength() {
        val gc = GC(ID(42, 10), 7)
        assertEquals(ID(42, 10), gc.id)
        assertEquals(7, gc.length)
    }

    @Test
    fun testGCLastId() {
        val gc = GC(ID(1, 5), 3)
        assertEquals(ID(1, 7), gc.lastId, "lastId should be (client, clock + length - 1)")
    }

    @Test
    fun testGCMergeWithAnotherGC() {
        val gc1 = GC(ID(1, 0), 3)
        val gc2 = GC(ID(1, 3), 5)
        val merged = gc1.mergeWith(gc2)
        assertTrue(merged, "GC should merge with another GC")
        assertEquals(8, gc1.length, "Merged GC length should be sum of both")
    }

    @Test
    fun testGCMergeWithNonGCReturnsFalse() {
        val gc = GC(ID(1, 0), 3)
        val skip = Skip(ID(1, 3), 5)
        val merged = gc.mergeWith(skip)
        assertFalse(merged, "GC should not merge with Skip")
        assertEquals(3, gc.length, "GC length should remain unchanged")
    }

    @Test
    fun testGCWriteProducesCorrectBytes() {
        val gc = GC(ID(1, 0), 10)
        val encoder = UpdateEncoderV1()
        gc.write(encoder, 0, 0)
        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV1(bytes)
        val info = decoder.readInfo()
        assertEquals(0, info, "GC info byte should be 0")
        val len = decoder.readLen()
        assertEquals(10, len, "GC length should be 10")
    }

    @Test
    fun testGCWriteWithOffset() {
        val gc = GC(ID(1, 0), 10)
        val encoder = UpdateEncoderV1()
        gc.write(encoder, 3, 0)
        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV1(bytes)
        decoder.readInfo() // consume info byte
        val len = decoder.readLen()
        assertEquals(7, len, "Written length should be 10 - 3 = 7")
    }

    @Test
    fun testGCWriteWithOffsetEnd() {
        val gc = GC(ID(1, 0), 10)
        val encoder = UpdateEncoderV1()
        gc.write(encoder, 2, 3)
        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV1(bytes)
        decoder.readInfo()
        val len = decoder.readLen()
        assertEquals(5, len, "Written length should be 10 - 2 - 3 = 5")
    }

    @Test
    fun testGCGetMissingReturnsNull() {
        val doc = Doc()
        val transaction = Transaction(doc)
        val gc = GC(ID(1, 0), 5)
        assertNull(gc.getMissing(transaction, doc.store), "GC.getMissing should always return null")
    }

    @Test
    fun testGCSplice() {
        val gc = GC(ID(1, 0), 10)
        val right = gc.splice(4)
        assertEquals(4, gc.length, "Left GC length should be the diff")
        assertEquals(ID(1, 0), gc.id, "Left GC id should be unchanged")
        assertEquals(6, right.length, "Right GC length should be original - diff")
        assertEquals(ID(1, 4), right.id, "Right GC id should start at clock + diff")
        assertTrue(right.deleted, "Spliced GC should also be deleted")
    }

    @Test
    fun testGCSpliceAtBoundary() {
        val gc = GC(ID(2, 5), 1)
        val right = gc.splice(1)
        assertEquals(1, gc.length)
        assertEquals(0, right.length)
        assertEquals(ID(2, 6), right.id)
    }

    @Test
    fun testGCMultipleMerges() {
        val gc1 = GC(ID(1, 0), 2)
        val gc2 = GC(ID(1, 2), 3)
        val gc3 = GC(ID(1, 5), 4)
        assertTrue(gc1.mergeWith(gc2))
        assertEquals(5, gc1.length)
        assertTrue(gc1.mergeWith(gc3))
        assertEquals(9, gc1.length)
    }

    // =========================================================================
    // Skip Tests
    // =========================================================================

    @Test
    fun testSkipDeletedAlwaysFalse() {
        val skip = Skip(ID(1, 0), 5)
        assertFalse(skip.deleted, "Skip.deleted should always be false")
    }

    @Test
    fun testSkipIdAndLength() {
        val skip = Skip(ID(99, 50), 20)
        assertEquals(ID(99, 50), skip.id)
        assertEquals(20, skip.length)
    }

    @Test
    fun testSkipLastId() {
        val skip = Skip(ID(1, 10), 5)
        assertEquals(ID(1, 14), skip.lastId, "lastId should be (client, clock + length - 1)")
    }

    @Test
    fun testSkipMergeWithAnotherSkip() {
        val skip1 = Skip(ID(1, 0), 3)
        val skip2 = Skip(ID(1, 3), 7)
        val merged = skip1.mergeWith(skip2)
        assertTrue(merged, "Skip should merge with another Skip")
        assertEquals(10, skip1.length, "Merged Skip length should be sum of both")
    }

    @Test
    fun testSkipMergeWithNonSkipReturnsFalse() {
        val skip = Skip(ID(1, 0), 3)
        val gc = GC(ID(1, 3), 5)
        val merged = skip.mergeWith(gc)
        assertFalse(merged, "Skip should not merge with GC")
        assertEquals(3, skip.length, "Skip length should remain unchanged")
    }

    @Test
    fun testSkipWriteProducesCorrectBytes() {
        val skip = Skip(ID(1, 0), 15)
        val encoder = UpdateEncoderV1()
        skip.write(encoder, 0, 0)
        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV1(bytes)
        val info = decoder.readInfo()
        assertEquals(10, info, "Skip info byte should be 10")
        val len = decoder.readLen()
        assertEquals(15, len, "Skip length should be 15")
    }

    @Test
    fun testSkipWriteWithOffset() {
        val skip = Skip(ID(1, 0), 20)
        val encoder = UpdateEncoderV1()
        skip.write(encoder, 5, 0)
        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV1(bytes)
        decoder.readInfo()
        val len = decoder.readLen()
        assertEquals(15, len, "Written length should be 20 - 5 = 15")
    }

    @Test
    fun testSkipGetMissingReturnsNull() {
        val doc = Doc()
        val transaction = Transaction(doc)
        val skip = Skip(ID(1, 0), 5)
        assertNull(skip.getMissing(transaction, doc.store), "Skip.getMissing should always return null")
    }

    @Test
    fun testSkipIntegrateThrows() {
        val doc = Doc()
        val transaction = Transaction(doc)
        val skip = Skip(ID(1, 0), 5)
        assertFailsWith<UnsupportedOperationException> {
            skip.integrate(transaction, 0)
        }
    }

    @Test
    fun testSkipMultipleMerges() {
        val s1 = Skip(ID(1, 0), 10)
        val s2 = Skip(ID(1, 10), 20)
        val s3 = Skip(ID(1, 30), 5)
        assertTrue(s1.mergeWith(s2))
        assertEquals(30, s1.length)
        assertTrue(s1.mergeWith(s3))
        assertEquals(35, s1.length)
    }

    // =========================================================================
    // GC and Skip cross-type merge tests
    // =========================================================================

    @Test
    fun testGCDoesNotMergeWithSkip() {
        val gc = GC(ID(1, 0), 5)
        val skip = Skip(ID(1, 5), 3)
        assertFalse(gc.mergeWith(skip))
    }

    @Test
    fun testSkipDoesNotMergeWithGC() {
        val skip = Skip(ID(1, 0), 5)
        val gc = GC(ID(1, 5), 3)
        assertFalse(skip.mergeWith(gc))
    }

    @Test
    fun testGCLengthOneWrite() {
        val gc = GC(ID(0, 0), 1)
        val encoder = UpdateEncoderV1()
        gc.write(encoder, 0, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        assertEquals(0, decoder.readInfo())
        assertEquals(1, decoder.readLen())
    }

    @Test
    fun testSkipLengthOneWrite() {
        val skip = Skip(ID(0, 0), 1)
        val encoder = UpdateEncoderV1()
        skip.write(encoder, 0, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        assertEquals(10, decoder.readInfo())
        assertEquals(1, decoder.readLen())
    }

    // =========================================================================
    // ArraySearchMarker Tests
    // =========================================================================

    @Test
    fun testArraySearchMarkerCreation() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5))
        // The searchMarker list should exist for YArray
        assertNotNull(arr.searchMarker, "YArray should have searchMarker initialized")
    }

    @Test
    fun testArraySearchMarkerMaxConstant() {
        assertEquals(80, ArraySearchMarker.MAX_SEARCH_MARKERS)
    }

    @Test
    fun testArraySearchMarkerTimestampIncreases() {
        val savedTimestamp = ArraySearchMarker.globalTimestamp
        val doc = Doc()
        val arr = doc.getArray("arr")
        // Insert enough items to potentially trigger marker creation
        val items = (0 until 200).toList()
        arr.push(items)
        // Access various indices to trigger marker updates
        for (i in listOf(50, 100, 150, 0, 199)) {
            arr.get(i)
        }
        // globalTimestamp should not have decreased
        assertTrue(
            ArraySearchMarker.globalTimestamp >= savedTimestamp,
            "globalTimestamp should not decrease"
        )
    }

    @Test
    fun testArraySearchMarkerDataClass() {
        // ArraySearchMarker is a data class, verify copy/equals work
        val doc = Doc()
        val arr = doc.getArray("test")
        arr.push(listOf("a"))
        val item = arr.start!!
        val marker1 = ArraySearchMarker(item, 0, 100L)
        val marker2 = ArraySearchMarker(item, 0, 100L)
        assertEquals(marker1, marker2, "Same data should be equal")
        val marker3 = marker1.copy(index = 5)
        assertEquals(5, marker3.index)
        assertEquals(0, marker1.index, "Original should be unchanged after copy")
    }

    // =========================================================================
    // YArray pop() Tests
    // =========================================================================

    @Test
    fun testYArrayPopReturnsLastElement() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(10, 20, 30))
        val popped = arr.pop()
        assertEquals(30, popped)
    }

    @Test
    fun testYArrayPopReducesLength() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf("x", "y", "z"))
        assertEquals(3, arr.length)
        arr.pop()
        assertEquals(2, arr.length)
        arr.pop()
        assertEquals(1, arr.length)
        arr.pop()
        assertEquals(0, arr.length)
    }

    @Test
    fun testYArrayPopMultipleTypes() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, "two", 3.0, true, null))
        assertEquals(null, arr.pop())
        assertEquals(true, arr.pop())
        assertEquals(3.0, arr.pop())
        assertEquals("two", arr.pop())
        assertEquals(1, arr.pop())
        assertEquals(0, arr.length)
    }

    @Test
    fun testYArrayPopPreservesRemainingItems() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b", "c", "d"))
        arr.pop() // remove "d"
        assertEquals(listOf("a", "b", "c"), arr.toArray())
    }

    // =========================================================================
    // YArray Large Array (Search Marker Exercise) Tests
    // =========================================================================

    @Test
    fun testYArrayLargeInsertAndAccess() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val count = 500
        val items = (0 until count).toList()
        arr.push(items)
        assertEquals(count, arr.length)

        // Access items at various positions to exercise search markers
        assertEquals(0, arr.get(0))
        assertEquals(249, arr.get(249))
        assertEquals(499, arr.get(499))

        // Random access pattern
        for (i in listOf(100, 400, 50, 450, 250, 1, 498)) {
            assertEquals(i, arr.get(i))
        }
    }

    @Test
    fun testYArrayLargeDeleteAndReindex() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push((0 until 200).toList())
        assertEquals(200, arr.length)

        // Delete from the middle
        arr.delete(50, 100)
        assertEquals(100, arr.length)

        // First 50 should be unchanged
        for (i in 0 until 50) {
            assertEquals(i, arr.get(i))
        }
        // Items after the gap should be the original 150-199
        for (i in 50 until 100) {
            assertEquals(i + 100, arr.get(i))
        }
    }

    @Test
    fun testYArrayLargeInsertInMiddle() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push((0 until 100).toList())
        // Insert 50 items at position 50
        arr.insert(50, (1000 until 1050).toList())
        assertEquals(150, arr.length)
        assertEquals(49, arr.get(49))
        assertEquals(1000, arr.get(50))
        assertEquals(1049, arr.get(99))
        assertEquals(50, arr.get(100))
    }

    @Test
    fun testYArrayLargeMultipleOperations() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        // Build up incrementally with individual pushes
        for (i in 0 until 100) {
            arr.push(listOf(i))
        }
        assertEquals(100, arr.length)

        // Delete every other item starting from the end
        for (i in 99 downTo 0 step 2) {
            arr.delete(i)
        }
        assertEquals(50, arr.length)

        // Remaining should be the even-indexed originals: 0, 2, 4, ...
        for (i in 0 until 50) {
            assertEquals(i * 2, arr.get(i))
        }
    }

    // =========================================================================
    // YText Enhanced Tests
    // =========================================================================

    @Test
    fun testYTextDeleteFromMiddle() {
        val doc = Doc()
        val text = doc.getText("t")
        text.insert(0, "Hello, World!")
        text.delete(5, 2) // delete ", "
        assertEquals("HelloWorld!", text.toString())
    }

    @Test
    fun testYTextInsertAtEnd() {
        val doc = Doc()
        val text = doc.getText("t")
        text.insert(0, "Hello")
        text.insert(5, " World")
        assertEquals("Hello World", text.toString())
    }

    @Test
    fun testYTextInsertAtBeginning() {
        val doc = Doc()
        val text = doc.getText("t")
        text.insert(0, "World")
        text.insert(0, "Hello ")
        assertEquals("Hello World", text.toString())
    }

    @Test
    fun testYTextComplexEdits() {
        val doc = Doc()
        val text = doc.getText("t")
        text.insert(0, "abcdefghij")
        text.delete(3, 4) // "abc" + "hij"
        assertEquals("abchij", text.toString())
        text.insert(3, "XYZ")
        assertEquals("abcXYZhij", text.toString())
        text.delete(0, 3) // remove "abc"
        assertEquals("XYZhij", text.toString())
        text.insert(6, "!!!")
        assertEquals("XYZhij!!!", text.toString())
    }

    @Test
    fun testYTextToStringAfterMultipleInserts() {
        val doc = Doc()
        val text = doc.getText("t")
        text.insert(0, "c")
        text.insert(0, "b")
        text.insert(0, "a")
        text.insert(3, "d")
        assertEquals("abcd", text.toString())
    }

    @Test
    fun testYTextLength() {
        val doc = Doc()
        val text = doc.getText("t")
        assertEquals(0, text.length)
        text.insert(0, "Hello")
        assertEquals(5, text.length)
        text.insert(5, " World!")
        assertEquals(12, text.length)
        text.delete(0, 5) // remove "Hello"
        assertEquals(7, text.length)
    }

    @Test
    fun testYTextToJSON() {
        val doc = Doc()
        val text = doc.getText("t")
        text.insert(0, "test string")
        assertEquals("test string", text.toJSON())
    }

    @Test
    fun testYTextToDelta() {
        val doc = Doc()
        val text = doc.getText("t")
        text.insert(0, "Hello World")
        val delta = text.toDelta()
        assertTrue(delta.isNotEmpty())
        val firstOp = delta[0]
        assertEquals("Hello World", firstOp["insert"])
    }

    @Test
    fun testYTextDeleteAll() {
        val doc = Doc()
        val text = doc.getText("t")
        text.insert(0, "Hello")
        text.delete(0, 5)
        assertEquals("", text.toString())
        assertEquals(0, text.length)
    }

    @Test
    fun testYTextEmptyString() {
        val doc = Doc()
        val text = doc.getText("t")
        assertEquals("", text.toString())
        assertEquals(0, text.length)
    }

    // =========================================================================
    // YType observe() and observeDeep() Tests
    // =========================================================================

    @Test
    fun testYArrayObserveCallbackReceivesEvent() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var receivedEvent: YEvent? = null
        arr.observe { event, _ ->
            receivedEvent = event
        }
        arr.push(listOf(1))
        assertNotNull(receivedEvent)
        assertSame(arr, receivedEvent.target)
    }

    @Test
    fun testYArrayObserveUnsubscribe() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var callCount = 0
        val unsubscribe = arr.observe { _, _ ->
            callCount++
        }
        arr.push(listOf(1))
        assertEquals(1, callCount)
        unsubscribe()
        arr.push(listOf(2))
        assertEquals(1, callCount, "Should not fire after unsubscribe")
    }

    @Test
    fun testYArrayObserveMultipleListeners() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var count1 = 0
        var count2 = 0
        arr.observe { _, _ -> count1++ }
        arr.observe { _, _ -> count2++ }
        arr.push(listOf(1))
        assertEquals(1, count1)
        assertEquals(1, count2)
    }

    @Test
    fun testYMapObserveCallbackReceivesEvent() {
        val doc = Doc()
        val map = doc.getMap("map")
        var receivedEvent: YEvent? = null
        map.observe { event, _ ->
            receivedEvent = event
        }
        map.set("key", "value")
        assertNotNull(receivedEvent)
        assertSame(map, receivedEvent.target)
    }

    @Test
    fun testYTextObserveCallbackOnInsert() {
        val doc = Doc()
        val text = doc.getText("t")
        var eventCount = 0
        text.observe { _, _ ->
            eventCount++
        }
        text.insert(0, "Hello")
        text.insert(5, " World")
        assertEquals(2, eventCount)
    }

    @Test
    fun testYTextObserveCallbackOnDelete() {
        val doc = Doc()
        val text = doc.getText("t")
        text.insert(0, "Hello World")
        var eventFired = false
        text.observe { _, _ ->
            eventFired = true
        }
        text.delete(5, 6)
        assertTrue(eventFired)
    }

    @Test
    fun testYArrayObserveDeep() {
        val doc = Doc()
        val outer = doc.getArray("outer")
        val inner = YArray()
        var deepEventFired = false
        outer.observeDeep { events, _ ->
            deepEventFired = true
            assertTrue(events.isNotEmpty())
        }
        outer.push(listOf(inner))
        // Now modify the inner array
        inner.push(listOf(1, 2, 3))
        assertTrue(deepEventFired, "Deep observer should fire when nested type changes")
    }

    @Test
    fun testYMapObserveDeep() {
        val doc = Doc()
        val root = doc.getMap("root")
        val nested = YMap()
        var deepEvents = mutableListOf<List<YEvent>>()
        root.observeDeep { events, _ ->
            deepEvents.add(events)
        }
        root.set("nested", nested)
        nested.set("key", "value")
        assertTrue(deepEvents.isNotEmpty(), "Deep observer should fire for nested changes")
    }

    @Test
    fun testObserveDeepUnsubscribe() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var callCount = 0
        val unsub = arr.observeDeep { _, _ -> callCount++ }
        arr.push(listOf(1))
        // callCount may or may not be 1 depending on whether self-changes trigger deep events
        val countAfterFirst = callCount
        unsub()
        arr.push(listOf(2))
        assertEquals(countAfterFirst, callCount, "Should not fire deep events after unsubscribe")
    }

    // =========================================================================
    // YType.length Property Tests
    // =========================================================================

    @Test
    fun testYArrayLengthAfterPush() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        assertEquals(0, arr.length)
        arr.push(listOf(1))
        assertEquals(1, arr.length)
        arr.push(listOf(2, 3))
        assertEquals(3, arr.length)
    }

    @Test
    fun testYArrayLengthAfterDelete() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5))
        assertEquals(5, arr.length)
        arr.delete(2, 2) // delete 3, 4
        assertEquals(3, arr.length)
        arr.delete(0)
        assertEquals(2, arr.length)
    }

    @Test
    fun testYArrayLengthAfterPop() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        arr.pop()
        assertEquals(2, arr.length)
    }

    @Test
    fun testYTextLengthAfterOperations() {
        val doc = Doc()
        val text = doc.getText("t")
        assertEquals(0, text.length)
        text.insert(0, "12345")
        assertEquals(5, text.length)
        text.delete(2, 2)
        assertEquals(3, text.length)
        text.insert(1, "XX")
        assertEquals(5, text.length)
    }

    // =========================================================================
    // toJSON() for Nested Structures
    // =========================================================================

    @Test
    fun testYArrayToJSONWithNestedArray() {
        val doc = Doc()
        val outer = doc.getArray("outer")
        val inner = YArray()
        outer.push(listOf(1, inner, 3))
        inner.push(listOf("a", "b"))
        val json = outer.toJSON()
        assertTrue(json is List<*>)
        val list = json
        assertEquals(1, list[0])
        assertEquals(listOf("a", "b"), list[1])
        assertEquals(3, list[2])
    }

    @Test
    fun testYArrayToJSONWithNestedMap() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val map = YMap()
        arr.push(listOf(map))
        map.set("name", "test")
        map.set("count", 42)
        val json = arr.toJSON()
        assertTrue(json is List<*>)
        val list = json
        val mapJson = list[0] as Map<*, *>
        assertEquals("test", mapJson["name"])
        assertEquals(42, mapJson["count"])
    }

    @Test
    fun testYMapToJSONWithNestedTypes() {
        val doc = Doc()
        val root = doc.getMap("root")
        val arr = YArray()
        val text = YText()
        root.set("list", arr)
        root.set("text", text)
        root.set("simple", "hello")
        arr.push(listOf(1, 2, 3))
        text.insert(0, "world")
        val json = root.toJSON() as Map<*, *>
        assertEquals(listOf(1, 2, 3), json["list"])
        assertEquals("world", json["text"])
        assertEquals("hello", json["simple"])
    }

    @Test
    fun testDocToJSONWithMultipleTypes() {
        val doc = Doc()
        val arr = doc.getArray("myArray")
        val map = doc.getMap("myMap")
        val text = doc.getText("myText")
        arr.push(listOf(10, 20))
        map.set("key", "value")
        text.insert(0, "hello")
        val json = doc.toJSON()
        assertEquals(listOf(10, 20), json["myArray"])
        assertEquals("hello", json["myText"])
        val mapJson = json["myMap"] as Map<*, *>
        assertEquals("value", mapJson["key"])
    }

    @Test
    fun testYArrayToJSONDeeplyNested() {
        val doc = Doc()
        val level1 = doc.getArray("l1")
        val level2 = YArray()
        val level3 = YMap()
        level1.push(listOf(level2))
        level2.push(listOf(level3))
        level3.set("deep", true)
        val json = level1.toJSON()
        assertTrue(json is List<*>)
        val l1 = json
        val l2 = l1[0] as List<*>
        val l3 = l2[0] as Map<*, *>
        assertEquals(true, l3["deep"])
    }

    // =========================================================================
    // GC integration via document lifecycle (GC after delete)
    // =========================================================================

    @Test
    fun testGCOccursAfterDeleteInGCEnabledDoc() {
        val doc = Doc(gc = true)
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5))
        val clientId = doc.clientID

        // Delete some items
        arr.delete(1, 3) // delete 2, 3, 4
        assertEquals(listOf(1, 5), arr.toArray())

        // After the transaction completes (with gc=true), deleted items
        // should have been GC'd to ContentDeleted (parent is not GC'd,
        // so items become ContentDeleted rather than GC structs).
        val structs = doc.store.clients[clientId]
        assertNotNull(structs)
        val hasDeletedContent = structs.any {
            it is Item && it.deleted && it.content is ContentDeleted
        }
        assertTrue(hasDeletedContent, "Store should contain items with ContentDeleted after GC")
    }

    @Test
    fun testNoGCInGCDisabledDoc() {
        val doc = Doc(gc = false)
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        arr.delete(0, 3)
        val clientId = doc.clientID
        val structs = doc.store.clients[clientId]
        assertNotNull(structs)
        // With gc=false, deleted items keep their original content type
        val hasContentDeleted = structs.any {
            it is Item && it.deleted && it.content is ContentDeleted
        }
        assertFalse(hasContentDeleted, "Store should NOT GC items when gc=false")
    }

    // =========================================================================
    // Sync round-trip with GC
    // =========================================================================

    @Test
    fun testSyncWithGCStructs() {
        val doc1 = Doc(gc = true)
        val arr1 = doc1.getArray("arr")
        arr1.push(listOf(1, 2, 3, 4, 5))
        arr1.delete(1, 3) // delete 2, 3, 4 -- triggers GC

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val arr2 = doc2.getArray("arr")
        assertEquals(listOf(1, 5), arr2.toArray())
    }

    // =========================================================================
    // YArray get() edge cases
    // =========================================================================

    @Test
    fun testYArrayGetOutOfBoundsReturnsNull() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        assertNull(arr.get(3), "get() past end should return null")
        assertNull(arr.get(100), "get() way past end should return null")
    }

    @Test
    fun testYArrayGetOnEmptyReturnsNull() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        assertNull(arr.get(0))
    }

    // =========================================================================
    // YText applyDelta
    // =========================================================================

    @Test
    fun testYTextApplyDelta() {
        val doc = Doc()
        val text = doc.getText("t")
        text.applyDelta(listOf(
            mapOf("insert" to "Hello World")
        ))
        assertEquals("Hello World", text.toString())
    }

    @Test
    fun testYTextApplyDeltaInsertAndDelete() {
        val doc = Doc()
        val text = doc.getText("t")
        text.insert(0, "Hello World")
        text.applyDelta(listOf(
            mapOf("retain" to 5),
            mapOf("delete" to 1), // delete the space
            mapOf("insert" to "_")
        ))
        assertEquals("Hello_World", text.toString())
    }

    // =========================================================================
    // Copy methods
    // =========================================================================

    @Test
    fun testYArrayCopy() {
        val arr = YArray()
        val copy = arr.copy()
        assertTrue(copy is YArray, "Copy of YArray should be YArray")
        assertNotSame(arr, copy, "Copy should be a different instance")
    }

    @Test
    fun testYTextCopy() {
        val text = YText()
        val copy = text.copy()
        assertTrue(copy is YText, "Copy of YText should be YText")
        assertNotSame(text, copy)
    }

    @Test
    fun testYMapCopy() {
        val map = YMap()
        val copy = map.copy()
        assertTrue(copy is YMap, "Copy of YMap should be YMap")
        assertNotSame(map, copy)
    }

    // =========================================================================
    // GC integration with offset > 0
    // =========================================================================

    @Test
    fun testGCIntegrateWithOffset() {
        val doc = Doc()
        val transaction = Transaction(doc)
        val gc = GC(ID(1, 10), 5)
        gc.integrate(transaction, 2)
        assertEquals(ID(1, 12), gc.id, "GC id should be offset by 2")
        assertEquals(3, gc.length, "GC length should be reduced by offset")
    }

    @Test
    fun testGCIntegrateWithZeroOffset() {
        val doc = Doc()
        val transaction = Transaction(doc)
        val gc = GC(ID(1, 0), 5)
        gc.integrate(transaction, 0)
        assertEquals(ID(1, 0), gc.id, "GC id should remain unchanged with offset 0")
        assertEquals(5, gc.length, "GC length should remain unchanged with offset 0")
    }

    // =========================================================================
    // GC write and read round-trip via encode/decode
    // =========================================================================

    @Test
    fun testGCWriteAndReadViaDocSync() {
        val doc1 = Doc(gc = true)
        val arr1 = doc1.getArray("arr")
        arr1.push(listOf("a", "b", "c", "d", "e"))
        arr1.delete(0, 5)

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val arr2 = doc2.getArray("arr")
        assertEquals(0, arr2.length, "All items should be deleted after sync")
        assertEquals(emptyList(), arr2.toArray())
    }

    // =========================================================================
    // GC splice used during snapshot operations
    // =========================================================================

    @Test
    fun testGCSpliceProducesCorrectIds() {
        val gc = GC(ID(5, 100), 20)
        val right = gc.splice(7)
        assertEquals(ID(5, 100), gc.id)
        assertEquals(7, gc.length)
        assertEquals(ID(5, 107), right.id)
        assertEquals(13, right.length)
        assertTrue(gc.deleted)
        assertTrue(right.deleted)
    }

    // =========================================================================
    // YArray insert single value overload
    // =========================================================================

    @Test
    fun testYArrayInsertSingleValueOverload() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.insert(0, 42)
        assertEquals(listOf(42), arr.toArray())
        assertEquals(1, arr.length)
    }

    // =========================================================================
    // ContentType delete and gc paths
    // =========================================================================

    @Test
    fun testContentTypeDeleteRemovesNestedChildren() {
        val doc = Doc(gc = true)
        val arr = doc.getArray("arr")
        val innerMap = YMap()
        arr.push(listOf(innerMap))
        innerMap.set("key1", "value1")
        innerMap.set("key2", "value2")

        // Verify nested content exists
        assertEquals("value1", innerMap.get("key1"))
        assertEquals("value2", innerMap.get("key2"))

        // Delete the array element containing the map
        arr.delete(0)
        assertEquals(0, arr.length)
    }

    @Test
    fun testContentTypeDeleteWithNestedArrayItems() {
        val doc = Doc(gc = true)
        val arr = doc.getArray("arr")
        val innerArr = YArray()
        arr.push(listOf(innerArr))
        innerArr.push(listOf(1, 2, 3))

        assertEquals(3, innerArr.length)
        arr.delete(0)
        assertEquals(0, arr.length)
    }

    @Test
    fun testContentTypeGCWithNestedTypes() {
        val doc = Doc(gc = true)
        val arr = doc.getArray("arr")
        val innerMap = YMap()
        arr.push(listOf(innerMap))
        innerMap.set("a", 1)
        innerMap.set("b", 2)

        arr.delete(0)

        // After GC, encode and decode to verify structural integrity
        val update = encodeStateAsUpdate(doc)
        val doc2 = Doc()
        applyUpdate(doc2, update)
        val arr2 = doc2.getArray("arr")
        assertEquals(0, arr2.length)
    }

    @Test
    fun testContentTypeDeleteWithBothListAndMapChildren() {
        val doc = Doc(gc = true)
        val arr = doc.getArray("arr")
        val innerMap = YMap()
        arr.push(listOf(innerMap))

        // Add items to both the map's map and its linked list (via a nested YArray)
        innerMap.set("mapKey", "mapValue")
        val nestedArr = YArray()
        innerMap.set("nested", nestedArr)
        nestedArr.push(listOf(1, 2, 3))

        arr.delete(0)
        assertEquals(0, arr.length)

        // Verify sync still works after deletion of nested types
        val update = encodeStateAsUpdate(doc)
        val doc2 = Doc()
        applyUpdate(doc2, update)
        assertEquals(0, doc2.getArray("arr").length)
    }

    // =========================================================================
    // ContentBinary delete and gc are no-ops
    // =========================================================================

    @Test
    fun testContentBinaryDeleteIsNoOp() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val data = byteArrayOf(1, 2, 3)
        arr.push(listOf(data))
        arr.delete(0)
        assertEquals(0, arr.length)
    }

    @Test
    fun testContentBinaryGCViaDocGC() {
        val doc = Doc(gc = true)
        val arr = doc.getArray("arr")
        arr.push(listOf(byteArrayOf(10, 20)))
        arr.delete(0)

        val update = encodeStateAsUpdate(doc)
        val doc2 = Doc()
        applyUpdate(doc2, update)
        assertEquals(0, doc2.getArray("arr").length)
    }

    // =========================================================================
    // ContentEmbed delete and gc are no-ops
    // =========================================================================

    @Test
    fun testContentEmbedDeleteIsNoOp() {
        val cb = ContentEmbed("embed-data")
        val doc = Doc()
        val transaction = Transaction(doc)
        cb.delete(transaction)
        // No exception, no-op
    }

    @Test
    fun testContentEmbedGCIsNoOp() {
        val cb = ContentEmbed("embed-data")
        val doc = Doc()
        val transaction = Transaction(doc)
        cb.gc(transaction)
        // No exception, no-op
    }

    // =========================================================================
    // ContentString splice at surrogate boundary
    // =========================================================================

    @Test
    fun testContentStringSpliceAtSurrogateBoundary() {
        // Create a string with a surrogate pair (emoji takes 2 UTF-16 code units)
        val emoji = "\uD83D\uDE00" // grinning face emoji
        val cs = ContentString("a${emoji}b")
        // The string is: a + high surrogate + low surrogate + b = 4 chars
        // Splicing at index 2 (between the two halves of the surrogate pair)
        // should move the high surrogate to the right side
        val right = cs.splice(2)
        // After the splice, the high surrogate should have been moved to the right
        assertEquals("a", cs.str, "Left side should not end with a lone high surrogate")
        assertTrue((right as ContentString).str.startsWith(emoji),
            "Right side should start with the complete surrogate pair")
    }

    @Test
    fun testContentStringSpliceNotAtSurrogate() {
        val cs = ContentString("hello")
        val right = cs.splice(3)
        assertEquals("hel", cs.str)
        assertEquals("lo", (right as ContentString).str)
    }

    // =========================================================================
    // ContentDeleted gc is no-op
    // =========================================================================

    @Test
    fun testContentDeletedGCIsNoOp() {
        val cd = ContentDeleted(5)
        val doc = Doc()
        val transaction = Transaction(doc)
        cd.gc(transaction)
        assertEquals(5, cd.getLength())
    }

    @Test
    fun testContentDeletedDeleteIsNoOp() {
        val cd = ContentDeleted(3)
        val doc = Doc()
        val transaction = Transaction(doc)
        cd.delete(transaction)
        assertEquals(3, cd.getLength())
    }

    // =========================================================================
    // ContentFormat delete and gc are no-ops
    // =========================================================================

    @Test
    fun testContentFormatDeleteIsNoOp() {
        val cf = ContentFormat("bold", true)
        val doc = Doc()
        val transaction = Transaction(doc)
        cf.delete(transaction)
        // No exception, no-op
        assertEquals("bold", cf.key)
    }

    @Test
    fun testContentFormatGCIsNoOp() {
        val cf = ContentFormat("italic", true)
        val doc = Doc()
        val transaction = Transaction(doc)
        cf.gc(transaction)
        // No exception, no-op
        assertEquals("italic", cf.key)
    }

    // =========================================================================
    // ContentJSON splice and write with offset
    // =========================================================================

    @Test
    fun testContentJSONSpliceProducesCorrectHalves() {
        val cj = ContentJSON(mutableListOf(1, 2, 3, 4, 5))
        val right = cj.splice(2)
        assertIs<ContentJSON>(right)
        assertEquals(mutableListOf<Any?>(1, 2), cj.values)
        assertEquals(mutableListOf<Any?>(3, 4, 5), right.values)
    }

    @Test
    fun testContentJSONWriteWithOffsetSkipsEarlierValues() {
        val cj = ContentJSON(mutableListOf("a", "b", "c", "d"))
        val encoder = UpdateEncoderV1()
        cj.write(encoder, 1)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentJSON.read(decoder)
        assertEquals(3, restored.getLength())
        assertEquals("b", restored.values[0])
        assertEquals("c", restored.values[1])
        assertEquals("d", restored.values[2])
    }

    @Test
    fun testContentJSONDeleteIsNoOp() {
        val cj = ContentJSON(mutableListOf(1, 2))
        val doc = Doc()
        val transaction = Transaction(doc)
        cj.delete(transaction)
        assertEquals(2, cj.getLength())
    }

    @Test
    fun testContentJSONGCIsNoOp() {
        val cj = ContentJSON(mutableListOf(1, 2))
        val doc = Doc()
        val transaction = Transaction(doc)
        cj.gc(transaction)
        assertEquals(2, cj.getLength())
    }

    // =========================================================================
    // ContentAny delete and gc are no-ops
    // =========================================================================

    @Test
    fun testContentAnyDeleteIsNoOp() {
        val ca = ContentAny(mutableListOf(1, 2, 3))
        val doc = Doc()
        val transaction = Transaction(doc)
        ca.delete(transaction)
        assertEquals(3, ca.getLength())
    }

    @Test
    fun testContentAnyGCIsNoOp() {
        val ca = ContentAny(mutableListOf(1, 2, 3))
        val doc = Doc()
        val transaction = Transaction(doc)
        ca.gc(transaction)
        assertEquals(3, ca.getLength())
    }

    // =========================================================================
    // ContentDoc gc is no-op
    // =========================================================================

    @Test
    fun testContentDocGCIsNoOp() {
        val subdoc = Doc(guid = "gc-test")
        val cd = yks.structs.content.ContentDoc(subdoc)
        val doc = Doc()
        val transaction = Transaction(doc)
        cd.gc(transaction)
        assertEquals("gc-test", cd.doc.guid)
    }

    // =========================================================================
    // ContentString delete and gc are no-ops
    // =========================================================================

    @Test
    fun testContentStringDeleteIsNoOp() {
        val cs = ContentString("hello")
        val doc = Doc()
        val transaction = Transaction(doc)
        cs.delete(transaction)
        assertEquals("hello", cs.str)
    }

    @Test
    fun testContentStringGCIsNoOp() {
        val cs = ContentString("hello")
        val doc = Doc()
        val transaction = Transaction(doc)
        cs.gc(transaction)
        assertEquals("hello", cs.str)
    }
}
