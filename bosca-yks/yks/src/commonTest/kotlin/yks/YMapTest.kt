package yks

import yks.utils.*
import yks.types.*
import kotlin.test.*

class YMapTest {

    @Test
    fun testSetAndGet() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("key", "value")
        assertEquals("value", map.get("key"))
    }

    @Test
    fun testMultipleKeys() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("a", 1)
        map.set("b", 2)
        map.set("c", 3)
        assertEquals(1, map.get("a"))
        assertEquals(2, map.get("b"))
        assertEquals(3, map.get("c"))
        assertEquals(3, map.size)
    }

    @Test
    fun testOverwrite() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("key", "first")
        map.set("key", "second")
        map.set("key", "third")
        assertEquals("third", map.get("key"))
        assertEquals(1, map.size)
    }

    @Test
    fun testDelete() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("key", "value")
        map.delete("key")
        assertNull(map.get("key"))
        assertFalse(map.has("key"))
        assertEquals(0, map.size)
    }

    @Test
    fun testHas() {
        val doc = Doc()
        val map = doc.getMap("map")
        assertFalse(map.has("key"))
        map.set("key", "value")
        assertTrue(map.has("key"))
        map.delete("key")
        assertFalse(map.has("key"))
    }

    @Test
    fun testEntries() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("x", 10)
        map.set("y", 20)
        map.set("z", 30)
        val entries = map.entries()
        assertEquals(3, entries.size)
        assertEquals(10, entries["x"])
        assertEquals(20, entries["y"])
        assertEquals(30, entries["z"])
    }

    @Test
    fun testKeys() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("a", 1)
        map.set("b", 2)
        assertEquals(setOf("a", "b"), map.keys())
    }

    @Test
    fun testValues() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("a", 1)
        map.set("b", 2)
        assertTrue(map.values().containsAll(listOf(1, 2)))
    }

    @Test
    fun testSetNull() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("key", null)
        assertTrue(map.has("key"))
        assertNull(map.get("key"))
    }

    @Test
    fun testNestedYArray() {
        val doc = Doc()
        val map = doc.getMap("map")
        val arr = YArray()
        map.set("list", arr)
        arr.push(listOf(1, 2, 3))
        val retrieved = map.get("list") as YArray
        assertEquals(listOf(1, 2, 3), retrieved.toArray())
    }

    @Test
    fun testNestedYMap() {
        val doc = Doc()
        val outer = doc.getMap("outer")
        val inner = YMap()
        outer.set("nested", inner)
        inner.set("deep", "value")
        val retrieved = outer.get("nested") as YMap
        assertEquals("value", retrieved.get("deep"))
    }

    @Test
    fun testToJSON() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("str", "hello")
        map.set("num", 42)
        map.set("bool", true)
        val json = map.toJSON() as Map<*, *>
        assertEquals("hello", json["str"])
        assertEquals(42, json["num"])
        assertEquals(true, json["bool"])
    }

    @Test
    fun testForEach() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("a", 1)
        map.set("b", 2)
        val collected = mutableMapOf<String, Any?>()
        map.forEach { value, key -> collected[key] = value }
        assertEquals(mapOf<String, Any?>("a" to 1, "b" to 2), collected)
    }

    @Test
    fun testObserve() {
        val doc = Doc()
        val map = doc.getMap("map")
        var eventCount = 0
        map.observe { _, _ -> eventCount++ }
        map.set("a", 1)
        map.set("b", 2)
        map.delete("a")
        assertEquals(3, eventCount)
    }

    @Test
    fun testMapSyncRoundTrip() {
        val doc1 = Doc()
        val map1 = doc1.getMap("map")
        map1.set("key1", "val1")
        map1.set("key2", 42)
        map1.set("key3", true)
        map1.delete("key2")

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val map2 = doc2.getMap("map")
        assertEquals("val1", map2.get("key1"))
        assertFalse(map2.has("key2"))
        assertEquals(true, map2.get("key3"))
    }

    @Test
    fun testSetAfterDelete() {
        val doc = Doc()
        val map = doc.getMap("map")
        map.set("key", "first")
        map.delete("key")
        map.set("key", "second")
        assertEquals("second", map.get("key"))
        assertTrue(map.has("key"))
    }
}
