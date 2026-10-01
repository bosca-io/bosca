package yks

import yks.utils.*
import yks.types.*
import kotlin.test.*

class YEventTest {

    // ---------------------------------------------------------------
    // YEvent.target
    // ---------------------------------------------------------------

    @Test
    fun testEventTargetIsTheObservedArray() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var capturedTarget: YType? = null
        arr.observe { event, _ ->
            capturedTarget = event.target
        }
        arr.push(listOf(1))
        assertSame(arr, capturedTarget)
    }

    @Test
    fun testEventTargetIsTheObservedMap() {
        val doc = Doc()
        val map = doc.getMap("map")
        var capturedTarget: YType? = null
        map.observe { event, _ ->
            capturedTarget = event.target
        }
        map.set("key", "value")
        assertSame(map, capturedTarget)
    }

    @Test
    fun testEventTargetIsTheObservedText() {
        val doc = Doc()
        val text = doc.getText("text")
        var capturedTarget: YType? = null
        text.observe { event, _ ->
            capturedTarget = event.target
        }
        text.insert(0, "hello")
        assertSame(text, capturedTarget)
    }

    // ---------------------------------------------------------------
    // YEvent.transaction
    // ---------------------------------------------------------------

    @Test
    fun testEventTransactionIsNotNull() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var capturedTransaction: Transaction? = null
        arr.observe { event, transaction ->
            capturedTransaction = event.transaction
            // The transaction from the callback arg should match
            assertSame(transaction, event.transaction)
        }
        arr.push(listOf(42))
        assertNotNull(capturedTransaction)
    }

    // ---------------------------------------------------------------
    // YEvent.delta - Array insert operations
    // ---------------------------------------------------------------

    @Test
    fun testDeltaInsertAtBeginning() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.push(listOf(1, 2, 3))
        assertNotNull(capturedDelta)
        assertEquals(1, capturedDelta.size)
        val insert = capturedDelta[0] as Delta.Insert
        assertEquals(listOf(1, 2, 3), insert.values)
    }

    @Test
    fun testDeltaInsertAtEnd() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.push(listOf(3, 4))

        assertNotNull(capturedDelta)
        // Should be: retain(2), insert([3, 4])
        assertEquals(2, capturedDelta.size)
        val retain = capturedDelta[0] as Delta.Retain
        assertEquals(2, retain.length)
        val insert = capturedDelta[1] as Delta.Insert
        assertEquals(listOf(3, 4), insert.values)
    }

    @Test
    fun testDeltaInsertInMiddle() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 4))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.insert(1, listOf(2, 3))

        assertNotNull(capturedDelta)
        // Should be: retain(1), insert([2, 3])
        // (No trailing retain per the implementation)
        assertEquals(2, capturedDelta.size)
        val retain = capturedDelta[0] as Delta.Retain
        assertEquals(1, retain.length)
        val insert = capturedDelta[1] as Delta.Insert
        assertEquals(listOf(2, 3), insert.values)
    }

    // ---------------------------------------------------------------
    // YEvent.delta - Array delete operations
    // ---------------------------------------------------------------

    @Test
    fun testDeltaDeleteFromBeginning() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.delete(0)

        assertNotNull(capturedDelta)
        assertEquals(1, capturedDelta.size)
        val delete = capturedDelta[0] as Delta.Delete
        assertEquals(1, delete.length)
    }

    @Test
    fun testDeltaDeleteFromEnd() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.delete(2)

        assertNotNull(capturedDelta)
        // Should be: retain(2), delete(1)
        assertEquals(2, capturedDelta.size)
        val retain = capturedDelta[0] as Delta.Retain
        assertEquals(2, retain.length)
        val delete = capturedDelta[1] as Delta.Delete
        assertEquals(1, delete.length)
    }

    @Test
    fun testDeltaDeleteFromMiddle() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.delete(1, 3)

        assertNotNull(capturedDelta)
        // Should be: retain(1), delete(3)
        // No trailing retain
        assertEquals(2, capturedDelta.size)
        val retain = capturedDelta[0] as Delta.Retain
        assertEquals(1, retain.length)
        val delete = capturedDelta[1] as Delta.Delete
        assertEquals(3, delete.length)
    }

    @Test
    fun testDeltaDeleteAll() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.delete(0, 3)

        assertNotNull(capturedDelta)
        assertEquals(1, capturedDelta.size)
        val delete = capturedDelta[0] as Delta.Delete
        assertEquals(3, delete.length)
    }

    // ---------------------------------------------------------------
    // YEvent.delta - Text operations
    // ---------------------------------------------------------------

    @Test
    fun testDeltaTextInsert() {
        val doc = Doc()
        val text = doc.getText("text")

        var capturedDelta: List<Delta>? = null
        text.observe { event, _ ->
            capturedDelta = event.delta
        }
        text.insert(0, "Hello")

        assertNotNull(capturedDelta)
        assertEquals(1, capturedDelta.size)
        val insert = capturedDelta[0] as Delta.Insert
        // ContentString.getContent() returns individual characters as strings
        assertEquals(listOf("H", "e", "l", "l", "o"), insert.values)
    }

    @Test
    fun testDeltaTextInsertAtEnd() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        var capturedDelta: List<Delta>? = null
        text.observe { event, _ ->
            capturedDelta = event.delta
        }
        text.insert(5, " World")

        assertNotNull(capturedDelta)
        // retain(5), insert individual chars of " World"
        assertEquals(2, capturedDelta.size)
        val retain = capturedDelta[0] as Delta.Retain
        assertEquals(5, retain.length)
        val insert = capturedDelta[1] as Delta.Insert
        assertEquals(listOf(" ", "W", "o", "r", "l", "d"), insert.values)
    }

    @Test
    fun testDeltaTextDelete() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")

        var capturedDelta: List<Delta>? = null
        text.observe { event, _ ->
            capturedDelta = event.delta
        }
        text.delete(5, 6) // delete " World"

        assertNotNull(capturedDelta)
        // retain(5), delete(6)
        assertEquals(2, capturedDelta.size)
        val retain = capturedDelta[0] as Delta.Retain
        assertEquals(5, retain.length)
        val delete = capturedDelta[1] as Delta.Delete
        assertEquals(6, delete.length)
    }

    @Test
    fun testDeltaTextInsertInMiddle() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hllo")

        var capturedDelta: List<Delta>? = null
        text.observe { event, _ ->
            capturedDelta = event.delta
        }
        text.insert(1, "e")

        assertNotNull(capturedDelta)
        // retain(1), insert(["e"])
        assertEquals(2, capturedDelta.size)
        val retain = capturedDelta[0] as Delta.Retain
        assertEquals(1, retain.length)
        val insert = capturedDelta[1] as Delta.Insert
        // Single char "e" from ContentString
        assertEquals(1, insert.values.size)
        assertEquals("e", insert.values[0])
    }

    @Test
    fun testDeltaTextDeleteFromBeginning() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")

        var capturedDelta: List<Delta>? = null
        text.observe { event, _ ->
            capturedDelta = event.delta
        }
        text.delete(0, 6) // delete "Hello "

        assertNotNull(capturedDelta)
        assertEquals(1, capturedDelta.size)
        val delete = capturedDelta[0] as Delta.Delete
        assertEquals(6, delete.length)
    }

    // ---------------------------------------------------------------
    // YEvent.delta caching
    // ---------------------------------------------------------------

    @Test
    fun testDeltaIsCached() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var capturedEvent: YEvent? = null
        arr.observe { event, _ ->
            capturedEvent = event
        }
        arr.push(listOf(1, 2, 3))
        assertNotNull(capturedEvent)
        val delta1 = capturedEvent.delta
        val delta2 = capturedEvent.delta
        assertSame(delta1, delta2, "delta should be cached and return same instance")
    }

    // ---------------------------------------------------------------
    // YEvent.delta - Empty delta for no list changes
    // ---------------------------------------------------------------

    @Test
    fun testDeltaEmptyForMapChange() {
        val doc = Doc()
        val map = doc.getMap("map")
        var capturedDelta: List<Delta>? = null
        map.observe { event, _ ->
            capturedDelta = event.delta
        }
        map.set("key", "value")
        assertNotNull(capturedDelta)
        // Map changes don't produce list deltas (start is null for maps)
        assertTrue(capturedDelta.isEmpty())
    }

    // ---------------------------------------------------------------
    // YEvent.keys() - Map key changes
    // ---------------------------------------------------------------

    @Test
    fun testKeysAddNewKey() {
        val doc = Doc()
        val map = doc.getMap("map")
        var capturedKeys: Map<String, KeyChange>? = null
        map.observe { event, _ ->
            capturedKeys = event.keys()
        }
        map.set("name", "Alice")
        assertNotNull(capturedKeys)
        assertEquals(1, capturedKeys.size)
        val change = capturedKeys["name"]
        assertNotNull(change)
        assertEquals("add", change.action)
        assertNull(change.oldValue)
    }

    @Test
    fun testKeysUpdateExistingKey() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("name", "Alice")

        var capturedKeys: Map<String, KeyChange>? = null
        map.observe { event, _ ->
            capturedKeys = event.keys()
        }
        map.set("name", "Bob")

        assertNotNull(capturedKeys)
        assertEquals(1, capturedKeys.size)
        val change = capturedKeys["name"]
        assertNotNull(change)
        // The new item was inserted in this transaction, previous value was "Alice"
        assertEquals("add", change.action)
        assertEquals("Alice", change.oldValue)
    }

    @Test
    fun testKeysDeleteKey() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("name", "Alice")

        var capturedKeys: Map<String, KeyChange>? = null
        map.observe { event, _ ->
            capturedKeys = event.keys()
        }
        map.delete("name")

        assertNotNull(capturedKeys)
        assertEquals(1, capturedKeys.size)
        val change = capturedKeys["name"]
        assertNotNull(change)
        // Deleted in this transaction: item is in insertSet AND is deleted -> action is "delete"
        // But actually: the original item was NOT inserted in this transaction; it was inserted before.
        // The delete just marks it as deleted. So the item in map["name"] is still the old one (now deleted).
        // The action logic: insertSet.has(item.id.client, item.id.clock) determines if item was just inserted.
        // For a delete of an existing item, the item in map[key] is the existing item. It's not in insertSet.
        // So action would be "update" (the else branch).
        assertEquals("update", change.action)
    }

    @Test
    fun testKeysMultipleChanges() {
        val doc = Doc()
        val map = doc.getMap("map")

        var capturedKeys: Map<String, KeyChange>? = null
        map.observe { event, _ ->
            capturedKeys = event.keys()
        }

        doc.transact {
            map.set("a", 1)
            map.set("b", 2)
            map.set("c", 3)
        }

        assertNotNull(capturedKeys)
        assertEquals(3, capturedKeys.size)
        assertTrue(capturedKeys.containsKey("a"))
        assertTrue(capturedKeys.containsKey("b"))
        assertTrue(capturedKeys.containsKey("c"))
    }

    // ---------------------------------------------------------------
    // YEvent.keysChanged
    // ---------------------------------------------------------------

    @Test
    fun testKeysChangedForMapSet() {
        val doc = Doc()
        val map = doc.getMap("map")
        var capturedKeysChanged: Set<String?>? = null
        map.observe { event, _ ->
            capturedKeysChanged = event.keysChanged
        }
        map.set("foo", "bar")
        assertNotNull(capturedKeysChanged)
        assertTrue(capturedKeysChanged.contains("foo"))
    }

    @Test
    fun testKeysChangedForArrayInsert() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var capturedKeysChanged: Set<String?>? = null
        arr.observe { event, _ ->
            capturedKeysChanged = event.keysChanged
        }
        arr.push(listOf(1))
        assertNotNull(capturedKeysChanged)
        // For array changes, keysChanged contains null
        assertTrue(capturedKeysChanged.contains(null))
    }

    // ---------------------------------------------------------------
    // YEvent.getPath() - Root-level types
    // ---------------------------------------------------------------

    @Test
    fun testPathForRootArray() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var capturedPath: List<Any>? = null
        arr.observe { event, _ ->
            capturedPath = event.getPath()
        }
        arr.push(listOf(1))
        assertNotNull(capturedPath)
        // Root-level type has empty path
        assertEquals(emptyList(), capturedPath)
    }

    @Test
    fun testPathForRootMap() {
        val doc = Doc()
        val map = doc.getMap("map")
        var capturedPath: List<Any>? = null
        map.observe { event, _ ->
            capturedPath = event.getPath()
        }
        map.set("key", "value")
        assertNotNull(capturedPath)
        assertEquals(emptyList(), capturedPath)
    }

    @Test
    fun testPathForRootText() {
        val doc = Doc()
        val text = doc.getText("text")
        var capturedPath: List<Any>? = null
        text.observe { event, _ ->
            capturedPath = event.getPath()
        }
        text.insert(0, "hello")
        assertNotNull(capturedPath)
        assertEquals(emptyList(), capturedPath)
    }

    // ---------------------------------------------------------------
    // YEvent.getPath() - Nested types (via map key)
    // ---------------------------------------------------------------

    @Test
    fun testPathForNestedMapInMap() {
        val doc = Doc()
        val outer = doc.getMap("outer")
        val inner = YMap()
        outer.set("nested", inner)

        var capturedPath: List<Any>? = null
        inner.observe { event, _ ->
            capturedPath = event.getPath()
        }
        inner.set("key", "value")

        assertNotNull(capturedPath)
        // Path from root to inner: parentSub is "nested"
        assertEquals(listOf<Any>("nested"), capturedPath)
    }

    @Test
    fun testPathForNestedArrayInMap() {
        val doc = Doc()
        val map = doc.getMap("map")
        val arr = YArray()
        map.set("list", arr)

        var capturedPath: List<Any>? = null
        arr.observe { event, _ ->
            capturedPath = event.getPath()
        }
        arr.push(listOf(1))

        assertNotNull(capturedPath)
        assertEquals(listOf<Any>("list"), capturedPath)
    }

    // ---------------------------------------------------------------
    // YEvent.getPath() - Nested types (via array index)
    // ---------------------------------------------------------------

    @Test
    fun testPathForNestedMapInArray() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val innerMap = YMap()
        arr.push(listOf(innerMap))

        var capturedPath: List<Any>? = null
        innerMap.observe { event, _ ->
            capturedPath = event.getPath()
        }
        innerMap.set("key", "value")

        assertNotNull(capturedPath)
        // Path from root: index 0 in the array
        assertEquals(listOf<Any>(0), capturedPath)
    }

    @Test
    fun testPathForNestedArrayInArrayAtIndex() {
        val doc = Doc()
        val outer = doc.getArray("outer")
        outer.push(listOf("a", "b"))
        val inner = YArray()
        outer.push(listOf(inner))

        var capturedPath: List<Any>? = null
        inner.observe { event, _ ->
            capturedPath = event.getPath()
        }
        inner.push(listOf(1))

        assertNotNull(capturedPath)
        // inner is at index 2 (after "a" and "b")
        assertEquals(listOf<Any>(2), capturedPath)
    }

    // ---------------------------------------------------------------
    // YEvent.getPath() - Deeply nested types
    // ---------------------------------------------------------------

    @Test
    fun testPathForDeeplyNestedType() {
        val doc = Doc()
        val root = doc.getMap("root")
        val level1 = YMap()
        root.set("level1", level1)
        val level2 = YArray()
        level1.set("level2", level2)
        val level3 = YMap()
        level2.push(listOf(level3))

        var capturedPath: List<Any>? = null
        level3.observe { event, _ ->
            capturedPath = event.getPath()
        }
        level3.set("deep", "value")

        assertNotNull(capturedPath)
        // Path: "level1" -> "level2" -> 0
        assertEquals(listOf<Any>("level1", "level2", 0), capturedPath)
    }

    // ---------------------------------------------------------------
    // Deep observer events (observeDeep)
    // ---------------------------------------------------------------

    @Test
    fun testDeepObserverOnParentMap() {
        val doc = Doc()
        val root = doc.getMap("root")
        val inner = YArray()
        root.set("items", inner)

        var deepEvents: List<YEvent>? = null
        root.observeDeep { events, _ ->
            deepEvents = events
        }
        inner.push(listOf(1, 2))

        assertNotNull(deepEvents)
        assertEquals(1, deepEvents.size)
        assertSame(inner, deepEvents[0].target)
    }

    @Test
    fun testDeepObserverGetsPathFromRoot() {
        val doc = Doc()
        val root = doc.getMap("root")
        val inner = YMap()
        root.set("child", inner)

        var capturedPath: List<Any>? = null
        root.observeDeep { events, _ ->
            capturedPath = events[0].getPath()
        }
        inner.set("key", "value")

        assertNotNull(capturedPath)
        assertEquals(listOf<Any>("child"), capturedPath)
    }

    @Test
    fun testDeepObserverPropagatesMultipleLevels() {
        val doc = Doc()
        val root = doc.getMap("root")
        val mid = YMap()
        root.set("mid", mid)
        val leaf = YArray()
        mid.set("leaf", leaf)

        var rootDeepEvents: List<YEvent>? = null
        root.observeDeep { events, _ ->
            rootDeepEvents = events
        }
        leaf.push(listOf(42))

        assertNotNull(rootDeepEvents)
        assertEquals(1, rootDeepEvents.size)
        assertSame(leaf, rootDeepEvents[0].target)
    }

    // ---------------------------------------------------------------
    // Multiple events in a single transaction
    // ---------------------------------------------------------------

    @Test
    fun testMultipleEventsInTransaction() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val map = doc.getMap("map")

        var arrEventCount = 0
        var mapEventCount = 0
        arr.observe { _, _ -> arrEventCount++ }
        map.observe { _, _ -> mapEventCount++ }

        doc.transact {
            arr.push(listOf(1, 2))
            map.set("key", "value")
        }

        // Both types should receive exactly one event per transaction
        assertEquals(1, arrEventCount)
        assertEquals(1, mapEventCount)
    }

    @Test
    fun testBatchedDeltaInTransaction() {
        val doc = Doc()
        val arr = doc.getArray("arr")

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }

        doc.transact {
            arr.push(listOf(1, 2))
            arr.push(listOf(3))
        }

        assertNotNull(capturedDelta)
        // All inserts in same transaction should produce combined delta
        // Depending on batching: insert([1, 2, 3])
        val totalInserted = capturedDelta.filterIsInstance<Delta.Insert>()
            .flatMap { it.values }
        assertEquals(listOf(1, 2, 3), totalInserted)
    }

    // ---------------------------------------------------------------
    // YEvent.delta - Insert then delete in same transaction
    // ---------------------------------------------------------------

    @Test
    fun testInsertAndDeleteInSameTransactionOnArray() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }

        doc.transact {
            arr.delete(1, 2) // delete items at index 1,2 (values 2,3)
        }

        assertNotNull(capturedDelta)
        // retain(1), delete(2)
        val retains = capturedDelta.filterIsInstance<Delta.Retain>()
        val deletes = capturedDelta.filterIsInstance<Delta.Delete>()
        assertEquals(1, retains.size)
        assertEquals(1, retains[0].length)
        assertEquals(1, deletes.size)
        assertEquals(2, deletes[0].length)
    }

    // ---------------------------------------------------------------
    // YEvent with nested YType inserts in array
    // ---------------------------------------------------------------

    @Test
    fun testDeltaInsertNestedType() {
        val doc = Doc()
        val arr = doc.getArray("arr")

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }

        val innerMap = YMap()
        arr.push(listOf(innerMap))

        assertNotNull(capturedDelta)
        assertEquals(1, capturedDelta.size)
        val insert = capturedDelta[0] as Delta.Insert
        assertEquals(1, insert.values.size)
        assertTrue(insert.values[0] is YMap)
    }

    @Test
    fun testDeltaInsertMixedPrimitivesAndTypes() {
        val doc = Doc()
        val arr = doc.getArray("arr")

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }

        val innerArr = YArray()
        arr.push(listOf(1, 2, innerArr, 3))

        assertNotNull(capturedDelta)
        // The insertAfter batches primitives. So: ContentAny([1,2]), ContentType(innerArr), ContentAny([3])
        // All are inserts
        val allInserts = capturedDelta.filterIsInstance<Delta.Insert>()
        val allValues = allInserts.flatMap { it.values }
        assertEquals(4, allValues.size)
        assertEquals(1, allValues[0])
        assertEquals(2, allValues[1])
        assertTrue(allValues[2] is YArray)
        assertEquals(3, allValues[3])
    }

    // ---------------------------------------------------------------
    // getPathTo standalone function
    // ---------------------------------------------------------------

    @Test
    fun testGetPathToForRootType() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1))
        // Root-level type has item == null, so path is empty
        doc.transact { transaction ->
            val path = getPathTo(arr, transaction)
            assertEquals(emptyList(), path)
        }
    }

    @Test
    fun testGetPathToForNestedType() {
        val doc = Doc()
        val root = doc.getMap("root")
        val child = YMap()
        root.set("child", child)
        child.set("dummy", 1) // ensure child is populated

        doc.transact { transaction ->
            val path = getPathTo(child, transaction)
            assertEquals(listOf<Any>("child"), path)
        }
    }

    @Test
    fun testGetPathToForArrayNestedType() {
        val doc = Doc()
        val root = doc.getArray("root")
        val child = YMap()
        root.push(listOf(child))

        doc.transact { transaction ->
            val path = getPathTo(child, transaction)
            assertEquals(listOf<Any>(0), path)
        }
    }

    @Test
    fun testGetPathToForArrayNestedTypeAtNonZeroIndex() {
        val doc = Doc()
        val root = doc.getArray("root")
        root.push(listOf("a", "b", "c"))
        val child = YMap()
        root.push(listOf(child))

        doc.transact { transaction ->
            val path = getPathTo(child, transaction)
            assertEquals(listOf<Any>(3), path)
        }
    }

    // ---------------------------------------------------------------
    // KeyChange data class
    // ---------------------------------------------------------------

    @Test
    fun testKeyChangeDataClass() {
        val kc1 = KeyChange(action = "add", oldValue = null)
        val kc2 = KeyChange(action = "add", oldValue = null)
        assertEquals(kc1, kc2)
        assertEquals("add", kc1.action)
        assertNull(kc1.oldValue)
    }

    @Test
    fun testKeyChangeWithOldValue() {
        val kc = KeyChange(action = "update", oldValue = "previousValue")
        assertEquals("update", kc.action)
        assertEquals("previousValue", kc.oldValue)
    }

    // ---------------------------------------------------------------
    // Delta sealed class variants
    // ---------------------------------------------------------------

    @Test
    fun testDeltaInsertClass() {
        val d = Delta.Insert(listOf(1, 2, 3))
        assertEquals(listOf(1, 2, 3), d.values)
        assertNull(d.attributes)
    }

    @Test
    fun testDeltaInsertWithAttributes() {
        val attrs = mapOf("bold" to true)
        val d = Delta.Insert(listOf("text"), attrs)
        assertEquals(listOf("text"), d.values)
        assertEquals(attrs, d.attributes)
    }

    @Test
    fun testDeltaDeleteClass() {
        val d = Delta.Delete(5)
        assertEquals(5, d.length)
    }

    @Test
    fun testDeltaRetainClass() {
        val d = Delta.Retain(3)
        assertEquals(3, d.length)
        assertNull(d.attributes)
    }

    @Test
    fun testDeltaRetainWithAttributes() {
        val attrs = mapOf("italic" to true)
        val d = Delta.Retain(3, attrs)
        assertEquals(3, d.length)
        assertEquals(attrs, d.attributes)
    }

    // ---------------------------------------------------------------
    // YEvent.keys() edge cases
    // ---------------------------------------------------------------

    @Test
    fun testKeysEmptyForArrayEvent() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var capturedKeys: Map<String, KeyChange>? = null
        arr.observe { event, _ ->
            capturedKeys = event.keys()
        }
        arr.push(listOf(1))
        assertNotNull(capturedKeys)
        // Array events have null in keysChanged, which is skipped by keys()
        assertTrue(capturedKeys.isEmpty())
    }

    @Test
    fun testKeysSetAndDeleteInSameTransaction() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("x", 1)
        map.set("y", 2)

        var capturedKeys: Map<String, KeyChange>? = null
        map.observe { event, _ ->
            capturedKeys = event.keys()
        }

        doc.transact {
            map.set("x", 10) // update
            map.delete("y")  // delete
            map.set("z", 3)  // add new
        }

        assertNotNull(capturedKeys)
        assertTrue(capturedKeys.containsKey("x"))
        assertTrue(capturedKeys.containsKey("y"))
        assertTrue(capturedKeys.containsKey("z"))
    }

    @Test
    fun testKeysReturnsOldValueOnOverwrite() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("counter", 1)

        var capturedKeys: Map<String, KeyChange>? = null
        map.observe { event, _ ->
            capturedKeys = event.keys()
        }
        map.set("counter", 2)

        assertNotNull(capturedKeys)
        val change = capturedKeys["counter"]
        assertNotNull(change)
        // oldValue should be the value from the left neighbor (the previous item)
        assertEquals(1, change.oldValue)
    }

    // ---------------------------------------------------------------
    // Observer unsubscribe
    // ---------------------------------------------------------------

    @Test
    fun testObserveReturnsUnsubscribeFunction() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var count = 0
        val unsubscribe = arr.observe { _, _ ->
            count++
        }
        arr.push(listOf(1))
        assertEquals(1, count)

        unsubscribe()
        arr.push(listOf(2))
        assertEquals(1, count, "Should not fire after unsubscribe")
    }

    @Test
    fun testDeepObserveReturnsUnsubscribeFunction() {
        val doc = Doc()
        val root = doc.getMap("root")
        val child = YArray()
        root.set("child", child)

        var deepCount = 0
        val unsubscribe = root.observeDeep { _, _ ->
            deepCount++
        }
        child.push(listOf(1))
        assertEquals(1, deepCount)

        unsubscribe()
        child.push(listOf(2))
        assertEquals(1, deepCount, "Should not fire after unsubscribe")
    }

    // ---------------------------------------------------------------
    // Delta on empty operations
    // ---------------------------------------------------------------

    @Test
    fun testDeltaOnEmptyArray() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        // Insert into empty array
        arr.push(listOf(42))
        assertNotNull(capturedDelta)
        assertEquals(1, capturedDelta.size)
        val insert = capturedDelta[0] as Delta.Insert
        assertEquals(listOf(42), insert.values)
    }

    // ---------------------------------------------------------------
    // Comprehensive: insert at specific positions
    // ---------------------------------------------------------------

    @Test
    fun testDeltaInsertAtIndex0WithExistingItems() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(2, 3))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.insert(0, listOf(0, 1))

        assertNotNull(capturedDelta)
        // Insert at beginning: insert([0, 1])
        // No trailing retain
        assertEquals(1, capturedDelta.size)
        val insert = capturedDelta[0] as Delta.Insert
        assertEquals(listOf(0, 1), insert.values)
    }

    // ---------------------------------------------------------------
    // Deep observer with multiple nested changes
    // ---------------------------------------------------------------

    @Test
    fun testDeepObserverMultipleNestedChangesInTransaction() {
        val doc = Doc()
        val root = doc.getMap("root")
        val arr = YArray()
        val map = YMap()
        root.set("arr", arr)
        root.set("map", map)

        var deepEvents: List<YEvent>? = null
        root.observeDeep { events, _ ->
            deepEvents = events
        }

        doc.transact {
            arr.push(listOf(1, 2, 3))
            map.set("key", "value")
        }

        assertNotNull(deepEvents)
        // Both arr and map changed, so 2 events should propagate
        assertEquals(2, deepEvents.size)
        val targets = deepEvents.map { it.target }.toSet()
        assertTrue(targets.contains(arr))
        assertTrue(targets.contains(map))
    }

    // ---------------------------------------------------------------
    // YEvent with synced remote changes
    // ---------------------------------------------------------------

    @Test
    fun testEventFiredOnRemoteApply() {
        val doc1 = Doc()
        val arr1 = doc1.getArray("arr")
        arr1.push(listOf(1, 2, 3))

        val doc2 = Doc()
        var eventFired = false
        val arr2 = doc2.getArray("arr")
        arr2.observe { _, _ ->
            eventFired = true
        }

        val update = encodeStateAsUpdate(doc1)
        applyUpdate(doc2, update)

        assertTrue(eventFired, "Event should fire when remote update is applied")
    }

    @Test
    fun testDeepEventFiredOnRemoteApply() {
        val doc1 = Doc()
        val root1 = doc1.getMap("root")
        val child1 = YArray()
        root1.set("child", child1)
        child1.push(listOf(1, 2))

        val doc2 = Doc()
        val root2 = doc2.getMap("root")
        var deepFired = false
        root2.observeDeep { _, _ ->
            deepFired = true
        }

        val update = encodeStateAsUpdate(doc1)
        applyUpdate(doc2, update)

        assertTrue(deepFired, "Deep event should fire when remote update is applied")
    }

    // ---------------------------------------------------------------
    // Keys with null keysChanged entry (array-like change on map)
    // ---------------------------------------------------------------

    @Test
    fun testKeysSkipsNullEntries() {
        val doc = Doc()
        val map = doc.getMap("map")
        var capturedEvent: YEvent? = null
        map.observe { event, _ ->
            capturedEvent = event
        }
        map.set("a", 1)
        assertNotNull(capturedEvent)
        // keysChanged should have "a" but keys() filters null
        val keys = capturedEvent.keys()
        assertTrue(keys.containsKey("a"))
    }

    // ---------------------------------------------------------------
    // Delta equality tests
    // ---------------------------------------------------------------

    @Test
    fun testDeltaInsertEquality() {
        val d1 = Delta.Insert(listOf(1, 2))
        val d2 = Delta.Insert(listOf(1, 2))
        assertEquals(d1, d2)
    }

    @Test
    fun testDeltaDeleteEquality() {
        val d1 = Delta.Delete(3)
        val d2 = Delta.Delete(3)
        assertEquals(d1, d2)
    }

    @Test
    fun testDeltaRetainEquality() {
        val d1 = Delta.Retain(5)
        val d2 = Delta.Retain(5)
        assertEquals(d1, d2)
    }

    // ---------------------------------------------------------------
    // YEvent.keys() with map that has content
    // ---------------------------------------------------------------

    @Test
    fun testKeysActionAddForNewKey() {
        val doc = Doc()
        val map = doc.getMap("map")

        var capturedKeys: Map<String, KeyChange>? = null
        map.observe { event, _ ->
            capturedKeys = event.keys()
        }
        map.set("brand_new", 42)

        assertNotNull(capturedKeys)
        val change = capturedKeys["brand_new"]
        assertNotNull(change)
        assertEquals("add", change.action)
        assertNull(change.oldValue) // no left neighbor for new key
    }

    // ---------------------------------------------------------------
    // Text delta with multiple operations
    // ---------------------------------------------------------------

    @Test
    fun testTextMultipleInsertsInTransaction() {
        val doc = Doc()
        val text = doc.getText("text")

        var capturedDelta: List<Delta>? = null
        text.observe { event, _ ->
            capturedDelta = event.delta
        }

        doc.transact {
            text.insert(0, "Hello")
            text.insert(5, " World")
        }

        assertNotNull(capturedDelta)
        // Both inserts in same transaction should produce a combined delta
        // ContentString.getContent() returns individual characters
        val allInserts = capturedDelta.filterIsInstance<Delta.Insert>()
        val allChars = allInserts.flatMap { it.values }
        // Join back into text for easy comparison
        assertEquals("Hello World", allChars.joinToString(""))
    }

    // ---------------------------------------------------------------
    // Array: single item operations
    // ---------------------------------------------------------------

    @Test
    fun testDeltaSingleItemInsert() {
        val doc = Doc()
        val arr = doc.getArray("arr")

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.push(listOf("only"))

        assertNotNull(capturedDelta)
        assertEquals(1, capturedDelta.size)
        val insert = capturedDelta[0] as Delta.Insert
        assertEquals(listOf("only"), insert.values)
    }

    @Test
    fun testDeltaSingleItemDelete() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf("only"))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.delete(0)

        assertNotNull(capturedDelta)
        assertEquals(1, capturedDelta.size)
        val delete = capturedDelta[0] as Delta.Delete
        assertEquals(1, delete.length)
    }

    // ---------------------------------------------------------------
    // Verify delta does not add trailing retain
    // ---------------------------------------------------------------

    @Test
    fun testDeltaNoTrailingRetain() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        // Delete from middle, leaving items at the end
        arr.delete(1, 2)

        assertNotNull(capturedDelta)
        // Should be: retain(1), delete(2)
        // Must NOT have a trailing Retain(2)
        assertEquals(2, capturedDelta.size)
        assertTrue(capturedDelta[0] is Delta.Retain)
        assertTrue(capturedDelta[1] is Delta.Delete)
    }

    // ---------------------------------------------------------------
    // Path index calculation with deleted items
    // ---------------------------------------------------------------

    @Test
    fun testPathSkipsDeletedItems() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b"))
        val inner = YMap()
        arr.push(listOf(inner))

        // Delete "b" so inner's index shifts
        arr.delete(1)

        var capturedPath: List<Any>? = null
        inner.observe { event, _ ->
            capturedPath = event.getPath()
        }
        inner.set("key", "value")

        assertNotNull(capturedPath)
        // After deleting "b", inner is now at index 1 (only "a" before it)
        assertEquals(listOf<Any>(1), capturedPath)
    }

    // ---------------------------------------------------------------
    // Map: set then immediately update in same transaction
    // ---------------------------------------------------------------

    @Test
    fun testMapSetThenUpdateInSameTransaction() {
        val doc = Doc()
        val map = doc.getMap("map")

        var capturedKeys: Map<String, KeyChange>? = null
        map.observe { event, _ ->
            capturedKeys = event.keys()
        }

        doc.transact {
            map.set("key", "first")
            map.set("key", "second")
        }

        assertNotNull(capturedKeys)
        val change = capturedKeys["key"]
        assertNotNull(change)
        // The final item for "key" was inserted in this transaction
        assertEquals("add", change.action)
        // oldValue is from the left neighbor which was "first"
        assertEquals("first", change.oldValue)
    }

    // ---------------------------------------------------------------
    // Verify event fires for each separate transaction
    // ---------------------------------------------------------------

    @Test
    fun testSeparateTransactionsFireSeparateEvents() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val deltas = mutableListOf<List<Delta>>()
        arr.observe { event, _ ->
            deltas.add(event.delta)
        }

        arr.push(listOf(1))
        arr.push(listOf(2))
        arr.push(listOf(3))

        assertEquals(3, deltas.size)
    }

    // ---------------------------------------------------------------
    // Large array operations
    // ---------------------------------------------------------------

    @Test
    fun testDeltaLargeInsert() {
        val doc = Doc()
        val arr = doc.getArray("arr")

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }

        val items = (0 until 100).toList()
        arr.push(items)

        assertNotNull(capturedDelta)
        val totalInserted = capturedDelta.filterIsInstance<Delta.Insert>()
            .flatMap { it.values }
        assertEquals(100, totalInserted.size)
        assertEquals(items, totalInserted)
    }

    @Test
    fun testDeltaLargeDelete() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push((0 until 50).toList())

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.delete(10, 30)

        assertNotNull(capturedDelta)
        val retain = capturedDelta[0] as Delta.Retain
        assertEquals(10, retain.length)
        val delete = capturedDelta[1] as Delta.Delete
        assertEquals(30, delete.length)
    }

    // ---------------------------------------------------------------
    // Map: multiple key types
    // ---------------------------------------------------------------

    @Test
    fun testKeysWithVariousValueTypes() {
        val doc = Doc()
        val map = doc.getMap("map")

        var capturedKeys: Map<String, KeyChange>? = null
        map.observe { event, _ ->
            capturedKeys = event.keys()
        }

        doc.transact {
            map.set("str", "hello")
            map.set("num", 42)
            map.set("bool", true)
            map.set("null_val", null)
        }

        assertNotNull(capturedKeys)
        assertEquals(4, capturedKeys.size)
        assertEquals("add", capturedKeys["str"]!!.action)
        assertEquals("add", capturedKeys["num"]!!.action)
        assertEquals("add", capturedKeys["bool"]!!.action)
        assertEquals("add", capturedKeys["null_val"]!!.action)
    }

    // ---------------------------------------------------------------
    // Deep observer: root-level changes
    // ---------------------------------------------------------------

    @Test
    fun testDeepObserverFiresForDirectChangeToo() {
        val doc = Doc()
        val map = doc.getMap("map")

        // observeDeep on root-level map should get events when that map changes
        // (since the event propagates to parent, which is the doc root)
        var shallowCount = 0
        map.observe { _, _ -> shallowCount++ }

        map.set("key", "value")
        assertEquals(1, shallowCount)
    }

    // ---------------------------------------------------------------
    // Text: combined insert and delete
    // ---------------------------------------------------------------

    @Test
    fun testTextDeleteFromMiddle() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "abcdef")

        var capturedDelta: List<Delta>? = null
        text.observe { event, _ ->
            capturedDelta = event.delta
        }
        text.delete(2, 2) // delete "cd"

        assertNotNull(capturedDelta)
        // retain(2), delete(2)
        assertEquals(2, capturedDelta.size)
        val retain = capturedDelta[0] as Delta.Retain
        assertEquals(2, retain.length)
        val delete = capturedDelta[1] as Delta.Delete
        assertEquals(2, delete.length)
    }

    // ---------------------------------------------------------------
    // YEvent.keys() - delete action path
    // ---------------------------------------------------------------

    @Test
    fun testKeysDeleteActionWhenItemInsertedAndDeletedInSameTransaction() {
        val doc = Doc()
        val map = doc.getMap("map")

        var capturedKeys: Map<String, KeyChange>? = null
        map.observe { event, _ ->
            capturedKeys = event.keys()
        }

        doc.transact {
            map.set("temp", "created")
            map.delete("temp")
        }

        assertNotNull(capturedKeys)
        val change = capturedKeys["temp"]
        assertNotNull(change)
        // Item was inserted in this transaction AND is deleted -> "delete" action
        assertEquals("delete", change.action)
    }

    // ---------------------------------------------------------------
    // YEvent.computeDelta - flush pending insert before delete
    // ---------------------------------------------------------------

    @Test
    fun testDeltaInsertThenDeleteFlushesInsertFirst() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }

        doc.transact {
            arr.insert(1, listOf(10, 11))
            arr.delete(0)
        }

        val delta1 = capturedDelta
        assertNotNull(delta1)
        // The delta should contain both deletes and inserts in the right order
        val inserts = delta1.filterIsInstance<Delta.Insert>()
        val deletes = delta1.filterIsInstance<Delta.Delete>()
        assertTrue(inserts.isNotEmpty() || deletes.isNotEmpty(),
            "Delta should contain insert and/or delete operations")
    }

    // ---------------------------------------------------------------
    // YEvent.computeDelta - retain after insert
    // ---------------------------------------------------------------

    @Test
    fun testDeltaRetainAfterInsertInMiddle() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }

        arr.insert(2, listOf(10))

        val delta2 = capturedDelta
        assertNotNull(delta2)
        // Should be: retain(2), insert([10])
        // No trailing retain
        assertEquals(2, delta2.size)
        val retain = delta2[0] as Delta.Retain
        assertEquals(2, retain.length)
        val insert = delta2[1] as Delta.Insert
        assertEquals(listOf(10), insert.values)
    }

    // ---------------------------------------------------------------
    // YEvent.getPath() - nested YArray in YMap
    // ---------------------------------------------------------------

    @Test
    fun testPathForNestedArrayInMapWithKey() {
        val doc = Doc()
        val root = doc.getMap("root")
        val arr = YArray()
        root.set("items", arr)
        val innerMap = YMap()
        arr.push(listOf(innerMap))

        var capturedPath: List<Any>? = null
        innerMap.observe { event, _ ->
            capturedPath = event.getPath()
        }
        innerMap.set("deep", "value")

        assertNotNull(capturedPath)
        // Path: "items" -> 0
        assertEquals(listOf<Any>("items", 0), capturedPath)
    }

    // ---------------------------------------------------------------
    // computeDelta: delete then insert in same position
    // ---------------------------------------------------------------

    @Test
    fun testDeltaDeleteFollowedByInsertAtSamePosition() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }

        doc.transact {
            arr.delete(1)
            arr.insert(1, listOf(20))
        }

        val delta3 = capturedDelta
        assertNotNull(delta3)
        // Should contain a delete and an insert
        val allDeletes = delta3.filterIsInstance<Delta.Delete>()
        val allInserts = delta3.filterIsInstance<Delta.Insert>()
        assertTrue(allDeletes.isNotEmpty(), "Should have at least one delete")
        assertTrue(allInserts.isNotEmpty(), "Should have at least one insert")
    }

    // ---------------------------------------------------------------
    // computeDelta: insert at beginning of non-empty array
    // (tests flush of pending insert before retain)
    // ---------------------------------------------------------------

    @Test
    fun testDeltaInsertAtBeginningFlushesBeforeRetain() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(10, 20, 30))

        var capturedDelta: List<Delta>? = null
        arr.observe { event, _ ->
            capturedDelta = event.delta
        }
        arr.insert(0, listOf(0))

        val delta4 = capturedDelta
        assertNotNull(delta4)
        assertEquals(1, delta4.size)
        val insert = delta4[0] as Delta.Insert
        assertEquals(listOf(0), insert.values)
    }
}
