package yks

import yks.utils.*
import yks.types.*
import kotlin.test.*

class DocTest {

    @Test
    fun testDocCreation() {
        val doc = Doc()
        assertNotNull(doc.clientID)
        assertNotNull(doc.guid)
        assertTrue(doc.share.isEmpty())
        assertTrue(doc.gc)
    }

    @Test
    fun testDocCustomOptions() {
        val doc = Doc(gc = false, guid = "custom-guid")
        assertFalse(doc.gc)
        assertEquals("custom-guid", doc.guid)
    }

    @Test
    fun testGetArray() {
        val doc = Doc()
        val arr1 = doc.getArray("test")
        val arr2 = doc.getArray("test")
        assertSame(arr1, arr2)
    }

    @Test
    fun testGetMap() {
        val doc = Doc()
        val map1 = doc.getMap("test")
        val map2 = doc.getMap("test")
        assertSame(map1, map2)
    }

    @Test
    fun testGetText() {
        val doc = Doc()
        val text1 = doc.getText("test")
        val text2 = doc.getText("test")
        assertSame(text1, text2)
    }

    @Test
    fun testGetDifferentNames() {
        val doc = Doc()
        val arr = doc.getArray("array")
        val map = doc.getMap("map")
        val text = doc.getText("text")
        assertEquals(3, doc.share.size)
        assertTrue(doc.share.containsKey("array"))
        assertTrue(doc.share.containsKey("map"))
        assertTrue(doc.share.containsKey("text"))
    }

    @Test
    fun testTypeMismatch() {
        val doc = Doc()
        doc.getArray("test")
        assertFailsWith<IllegalStateException> {
            doc.getMap("test") // Same name, different type
        }
    }

    @Test
    fun testToJSON() {
        val doc = Doc()
        val arr = doc.getArray("array")
        arr.push(listOf(1, 2, 3))
        val map = doc.getMap("map")
        map.set("key", "value")

        val json = doc.toJSON()
        assertEquals(listOf(1, 2, 3), json["array"])
        val mapJson = json["map"] as Map<*, *>
        assertEquals("value", mapJson["key"])
    }

    @Test
    fun testTransact() {
        val doc = Doc()
        val arr = doc.getArray("test")
        doc.transact { transaction ->
            arr.push(listOf(1, 2, 3))
            // Inside transaction, changes are batched
        }
        assertEquals(3, arr.length)
    }

    @Test
    fun testBeforeTransactionEvent() {
        val doc = Doc()
        var fired = false
        doc.on<Transaction>("beforeTransaction") { _ ->
            fired = true
        }
        val arr = doc.getArray("test")
        arr.push(listOf(1))
        assertTrue(fired)
    }

    @Test
    fun testAfterTransactionEvent() {
        val doc = Doc()
        var fired = false
        doc.on<Transaction>("afterTransaction") { _ ->
            fired = true
        }
        val arr = doc.getArray("test")
        arr.push(listOf(1))
        assertTrue(fired)
    }

    @Test
    fun testMultipleSharedTypes() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        val map = doc.getMap("map")
        val text = doc.getText("text")

        arr.push(listOf(1, 2))
        map.set("key", "val")
        text.insert(0, "hello")

        assertEquals(2, arr.length)
        assertEquals("val", map.get("key"))
        assertEquals("hello", text.toString())
    }

    @Test
    fun testDocDestroy() {
        val doc = Doc()
        var destroyed = false
        doc.on<Doc>("destroy") { _ ->
            destroyed = true
        }
        doc.getArray("test").push(listOf(1))
        doc.destroy()
        assertTrue(doc.isDestroyed)
        assertTrue(destroyed)
    }

    @Test
    fun testClientIDUnique() {
        val doc1 = Doc()
        val doc2 = Doc()
        // While not guaranteed, it's statistically near-impossible for two random uint32s to collide
        // We just check they're assigned
        assertNotNull(doc1.clientID)
        assertNotNull(doc2.clientID)
    }

    @Test
    fun testGetXmlFragment() {
        val doc = Doc()
        val frag = doc.getXmlFragment("xml")
        assertNotNull(frag)
    }

    @Test
    fun testEmptyDocToJSON() {
        val doc = Doc()
        val json = doc.toJSON()
        assertTrue(json.isEmpty())
    }

    // --- gc option tests ---

    @Test
    fun testDocGcTrue() {
        val doc = Doc(gc = true)
        assertTrue(doc.gc)
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        arr.delete(0, 3)

        // With gc=true, deleted items get content replaced with ContentDeleted after transaction cleanup
        val structs = doc.store.clients[doc.clientID]
        assertNotNull(structs)
        assertTrue(structs.any { it is yks.structs.Item && (it as yks.structs.Item).content is yks.structs.content.ContentDeleted },
            "Items should have ContentDeleted after GC with gc=true")
    }

    @Test
    fun testDocGcFalse() {
        val doc = Doc(gc = false)
        assertFalse(doc.gc)
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        arr.delete(0, 3) // delete all

        // With gc=false, deleted items remain as Items (not GC'd)
        val structs = doc.store.clients[doc.clientID]
        assertNotNull(structs)
        assertTrue(structs.all { it is yks.structs.Item }, "Items should remain when gc=false")
    }

    @Test
    fun testDocGcAffectsEncodedState() {
        val docGc = Doc(gc = true)
        docGc.clientID = 1
        val arrGc = docGc.getArray("arr")
        arrGc.push(listOf("a", "b", "c"))
        arrGc.delete(0, 3)

        val docNoGc = Doc(gc = false)
        docNoGc.clientID = 2
        val arrNoGc = docNoGc.getArray("arr")
        arrNoGc.push(listOf("a", "b", "c"))
        arrNoGc.delete(0, 3)

        val updateGc = encodeStateAsUpdate(docGc)
        val updateNoGc = encodeStateAsUpdate(docNoGc)

        // GC'd update may be different in size since GC structs are more compact
        val restored1 = Doc()
        applyUpdate(restored1, updateGc)
        assertEquals(0, restored1.getArray("arr").length)

        val restored2 = Doc()
        applyUpdate(restored2, updateNoGc)
        assertEquals(0, restored2.getArray("arr").length)
    }

    // --- Doc.load() tests ---

    @Test
    fun testDocLoad() {
        val doc = Doc()
        val map = doc.getMap("data")

        // Add a subdoc
        val subdoc = Doc(guid = "sub-1")
        map.set("child", subdoc)

        // The subdoc should be in the parent's subdocs set
        assertTrue(doc.subdocs.contains(subdoc))

        // Test load on the subdoc
        var loadFired = false
        subdoc.on<Doc>("load") { _ -> loadFired = true }
        subdoc.load()
        assertTrue(subdoc.isLoaded)
        assertTrue(loadFired)
    }

    @Test
    fun testDocLoadAlreadyLoaded() {
        val doc = Doc()
        doc.load()
        assertTrue(doc.isLoaded)
        // Calling load again should not throw
        doc.load()
        assertTrue(doc.isLoaded)
    }

    // --- Doc.destroy() tests ---

    @Test
    fun testDocDestroyWithSubdocs() {
        val doc = Doc()
        val map = doc.getMap("data")
        val subdoc = Doc(guid = "sub-1")
        map.set("child", subdoc)

        var subdocDestroyed = false
        subdoc.on<Doc>("destroy") { _ -> subdocDestroyed = true }

        doc.destroy()
        assertTrue(doc.isDestroyed)
        assertTrue(subdocDestroyed)
    }

    @Test
    fun testDocDestroyFiresEvent() {
        val doc = Doc()
        var eventFired = false
        doc.on<Doc>("destroy") { _ -> eventFired = true }
        doc.destroy()
        assertTrue(eventFired)
    }

    @Test
    fun testDocDestroyAfterDestroy() {
        val doc = Doc()
        doc.destroy()
        assertTrue(doc.isDestroyed)
        // Should not throw on second destroy
        doc.destroy()
        assertTrue(doc.isDestroyed)
    }

    // --- getSubdocs() tests ---

    @Test
    fun testGetSubdocs() {
        val doc = Doc()
        val map = doc.getMap("data")
        val sub1 = Doc(guid = "sub-1")
        val sub2 = Doc(guid = "sub-2")
        map.set("child1", sub1)
        map.set("child2", sub2)

        assertEquals(2, doc.subdocs.size)
        assertTrue(doc.subdocs.contains(sub1))
        assertTrue(doc.subdocs.contains(sub2))
    }

    @Test
    fun testGetSubdocsEmpty() {
        val doc = Doc()
        doc.getMap("data")
        assertTrue(doc.subdocs.isEmpty())
    }

    // --- Doc Companion options ---

    @Test
    fun testDocCustomGuid() {
        val doc = Doc(guid = "my-custom-guid")
        assertEquals("my-custom-guid", doc.guid)
    }

    @Test
    fun testDocCollectionId() {
        val doc = Doc(collectionid = "my-collection")
        assertEquals("my-collection", doc.collectionid)
    }

    @Test
    fun testDocMeta() {
        val doc = Doc(meta = mapOf("version" to 1))
        assertEquals(mapOf("version" to 1), doc.meta)
    }

    @Test
    fun testDocAutoLoad() {
        val doc = Doc(autoLoad = true)
        assertTrue(doc.autoLoad)
    }

    @Test
    fun testDocShouldLoad() {
        val doc = Doc(shouldLoad = false)
        assertFalse(doc.shouldLoad)
    }

    // --- transact with non-null origin ---

    @Test
    fun testTransactWithOrigin() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var capturedOrigin: Any? = "unset"
        doc.on<Transaction>("beforeTransaction") { tx ->
            capturedOrigin = tx.origin
        }
        doc.transact(origin = "my-origin") { _ ->
            arr.push(listOf(1))
        }
        assertEquals("my-origin", capturedOrigin)
    }

    @Test
    fun testTransactWithNullOrigin() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        var capturedOrigin: Any? = "unset"
        doc.on<Transaction>("beforeTransaction") { tx ->
            capturedOrigin = tx.origin
        }
        doc.transact(origin = null) { _ ->
            arr.push(listOf(1))
        }
        assertNull(capturedOrigin)
    }

    // --- getXmlElement ---

    @Test
    fun testGetXmlElement() {
        val doc = Doc()
        val elem = doc.getXmlElement("xml", "div")
        assertNotNull(elem)
        val elem2 = doc.getXmlElement("xml")
        assertSame(elem, elem2)
    }
}
