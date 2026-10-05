package yks

import yks.structs.*
import yks.structs.content.*
import yks.types.*
import yks.utils.*
import kotlin.test.*

/**
 * Additional tests targeting uncovered lines in Doc.kt, StructStore.kt,
 * Snapshot.kt, and ArraySearchMarker.kt.
 */
class DocStoreSnapshotTest {

    // =========================================================================
    // Doc.kt - Options and Properties
    // =========================================================================

    @Test
    fun testDocOptionsGuid() {
        val doc = Doc(guid = "my-custom-guid-123")
        assertEquals("my-custom-guid-123", doc.guid)
    }

    @Test
    fun testDocOptionsGcDisabled() {
        val doc = Doc(gc = false)
        assertFalse(doc.gc)
    }

    @Test
    fun testDocOptionsAutoLoad() {
        val doc = Doc(autoLoad = true)
        assertTrue(doc.autoLoad)
    }

    @Test
    fun testDocOptionsShouldLoad() {
        val doc = Doc(shouldLoad = false)
        assertFalse(doc.shouldLoad)
    }

    @Test
    fun testDocOptionsMeta() {
        val meta = mapOf("version" to 1, "name" to "test")
        val doc = Doc(meta = meta)
        assertEquals(meta, doc.meta)
    }

    @Test
    fun testDocOptionsCollectionId() {
        val doc = Doc(collectionid = "my-collection")
        assertEquals("my-collection", doc.collectionid)
    }

    @Test
    fun testDocOptionsOpts() {
        val opts = "custom-opts"
        val doc = Doc(opts = opts)
        assertEquals("custom-opts", doc.opts)
    }

    @Test
    fun testDocMetaNull() {
        val doc = Doc()
        assertNull(doc.meta)
    }

    @Test
    fun testDocCollectionIdNull() {
        val doc = Doc()
        assertNull(doc.collectionid)
    }

    @Test
    fun testDocAllOptions() {
        val doc = Doc(
            gc = false,
            guid = "all-opts",
            collectionid = "col-1",
            meta = 42,
            autoLoad = true,
            shouldLoad = false,
            opts = "extra"
        )
        assertFalse(doc.gc)
        assertEquals("all-opts", doc.guid)
        assertEquals("col-1", doc.collectionid)
        assertEquals(42, doc.meta)
        assertTrue(doc.autoLoad)
        assertFalse(doc.shouldLoad)
        assertEquals("extra", doc.opts)
    }

    // =========================================================================
    // Doc.kt - Initial State
    // =========================================================================

    @Test
    fun testDocInitialState() {
        val doc = Doc()
        assertNull(doc.transaction)
        assertTrue(doc.transactionCleanups.isEmpty())
        assertTrue(doc.subdocs.isEmpty())
        assertNull(doc.item)
        assertFalse(doc.isSuggestionDoc)
        assertTrue(doc.cleanupFormatting)
        assertFalse(doc.isDestroyed)
    }

    @Test
    fun testDocIsLoadedInitially() {
        val doc = Doc()
        // A fresh root Doc is not explicitly loaded (isLoaded starts false)
        assertFalse(doc.isLoaded)
    }

    @Test
    fun testDocIsSyncedInitially() {
        val doc = Doc()
        assertFalse(doc.isSynced)
    }

    // =========================================================================
    // Doc.kt - load()
    // =========================================================================

    @Test
    fun testDocLoadSetsIsLoaded() {
        val doc = Doc()
        assertFalse(doc.isLoaded)
        doc.load()
        assertTrue(doc.isLoaded)
    }

    @Test
    fun testDocLoadEmitsLoadEvent() {
        val doc = Doc()
        var loadFired = false
        doc.on<Doc>("load") { loadedDoc ->
            loadFired = true
            assertSame(doc, loadedDoc)
        }
        doc.load()
        assertTrue(loadFired)
    }

    @Test
    fun testDocLoadCalledMultipleTimes() {
        val doc = Doc()
        var loadCount = 0
        doc.on<Doc>("load") { _ -> loadCount++ }
        doc.load()
        doc.load()
        // load event fires each time
        assertEquals(2, loadCount)
        assertTrue(doc.isLoaded)
    }

    // =========================================================================
    // Doc.kt - destroy()
    // =========================================================================

    @Test
    fun testDocDestroySetsIsDestroyed() {
        val doc = Doc()
        assertFalse(doc.isDestroyed)
        doc.destroy()
        assertTrue(doc.isDestroyed)
    }

    @Test
    fun testDocDestroyEmitsDestroyEvent() {
        val doc = Doc()
        var destroyFired = false
        doc.on<Doc>("destroy") { destroyedDoc ->
            destroyFired = true
            assertSame(doc, destroyedDoc)
        }
        doc.destroy()
        assertTrue(destroyFired)
    }

    @Test
    fun testDocDestroyClearsListeners() {
        val doc = Doc()
        var count = 0
        doc.on<Doc>("load") { _ -> count++ }
        doc.destroy()
        // After destroy, listeners are cleared -- emit should not fire
        doc.emit("load", doc)
        assertEquals(0, count)
    }

    @Test
    fun testDocDestroyWithData() {
        val doc = Doc()
        val arr = doc.getArray("test")
        arr.push(listOf(1, 2, 3))
        doc.destroy()
        assertTrue(doc.isDestroyed)
    }

    // =========================================================================
    // Doc.kt - Sub-docs
    // =========================================================================

    @Test
    fun testSubdocAddedViaContentDoc() {
        val parentDoc = Doc()
        val arr = parentDoc.getArray("docs")
        val childDoc = Doc(guid = "child-1")

        arr.push(listOf(childDoc))

        assertTrue(parentDoc.subdocs.contains(childDoc))
        assertEquals("child-1", childDoc.guid)
    }

    @Test
    fun testSubdocEvent() {
        val parentDoc = Doc()
        val arr = parentDoc.getArray("docs")

        var addedDocs = emptySet<Doc>()
        parentDoc.on<SubdocsEvent>("subdocs") { event ->
            addedDocs = event.added
        }

        val childDoc = Doc(guid = "subdoc-event-test")
        arr.push(listOf(childDoc))

        assertTrue(addedDocs.contains(childDoc))
    }

    @Test
    fun testSubdocRemovedOnDelete() {
        val parentDoc = Doc()
        val arr = parentDoc.getArray("docs")
        val childDoc = Doc(guid = "to-be-removed")

        arr.push(listOf(childDoc))
        assertTrue(parentDoc.subdocs.contains(childDoc))

        var removedDocs = emptySet<Doc>()
        parentDoc.on<SubdocsEvent>("subdocs") { event ->
            removedDocs = event.removed
        }

        arr.delete(0)
        assertTrue(removedDocs.contains(childDoc))
    }

    @Test
    fun testMultipleSubdocs() {
        val parentDoc = Doc()
        val arr = parentDoc.getArray("docs")
        val child1 = Doc(guid = "child-1")
        val child2 = Doc(guid = "child-2")
        val child3 = Doc(guid = "child-3")

        arr.push(listOf(child1, child2, child3))

        assertEquals(3, parentDoc.subdocs.size)
        assertTrue(parentDoc.subdocs.contains(child1))
        assertTrue(parentDoc.subdocs.contains(child2))
        assertTrue(parentDoc.subdocs.contains(child3))
    }

    @Test
    fun testSubdocShouldLoadTracking() {
        val parentDoc = Doc()
        val arr = parentDoc.getArray("docs")

        var loadedDocs = emptySet<Doc>()
        parentDoc.on<SubdocsEvent>("subdocs") { event ->
            loadedDocs = event.loaded
        }

        val loadableDoc = Doc(guid = "loadable", shouldLoad = true)
        arr.push(listOf(loadableDoc))

        assertTrue(loadedDocs.contains(loadableDoc))
    }

    @Test
    fun testDestroyWithSubdocs() {
        val parentDoc = Doc()
        val arr = parentDoc.getArray("docs")
        val child = Doc(guid = "child-to-destroy")

        arr.push(listOf(child))
        assertTrue(parentDoc.subdocs.contains(child))

        parentDoc.destroy()
        assertTrue(parentDoc.isDestroyed)
        assertTrue(child.isDestroyed)
    }

    // =========================================================================
    // Doc.kt - Events
    // =========================================================================

    @Test
    fun testBeforeTransactionEvent() {
        val doc = Doc()
        val events = mutableListOf<String>()
        doc.on<Transaction>("beforeTransaction") { _ ->
            events.add("before")
        }
        val arr = doc.getArray("test")
        arr.push(listOf(1))
        assertTrue(events.contains("before"))
    }

    @Test
    fun testAfterTransactionEvent() {
        val doc = Doc()
        val events = mutableListOf<String>()
        doc.on<Transaction>("afterTransaction") { _ ->
            events.add("after")
        }
        val arr = doc.getArray("test")
        arr.push(listOf(1))
        assertTrue(events.contains("after"))
    }

    @Test
    fun testBeforeAndAfterTransactionOrder() {
        val doc = Doc()
        val events = mutableListOf<String>()
        doc.on<Transaction>("beforeTransaction") { _ ->
            events.add("before")
        }
        doc.on<Transaction>("afterTransaction") { _ ->
            events.add("after")
        }
        val arr = doc.getArray("test")
        arr.push(listOf(1))
        assertEquals(listOf("before", "after"), events)
    }

    @Test
    fun testUpdateEvent() {
        val doc = Doc()
        var receivedUpdate: ByteArray? = null
        doc.on2<ByteArray, Any?>("update") { update, origin ->
            receivedUpdate = update
        }
        val arr = doc.getArray("test")
        arr.push(listOf(1, 2, 3))
        assertNotNull(receivedUpdate)
        assertTrue(receivedUpdate.isNotEmpty())
    }

    @Test
    fun testUpdateV2Event() {
        val doc = Doc()
        var receivedUpdate: ByteArray? = null
        doc.on2<ByteArray, Any?>("updateV2") { update, _ ->
            receivedUpdate = update
        }
        val arr = doc.getArray("test")
        arr.push(listOf(1, 2, 3))
        assertNotNull(receivedUpdate)
        assertTrue(receivedUpdate.isNotEmpty())
    }

    @Test
    fun testUpdateEventAppliedToOtherDoc() {
        val doc1 = Doc()
        doc1.clientID = 1
        val doc2 = Doc()
        doc2.clientID = 2

        doc1.on2<ByteArray, Any?>("update") { update, _ ->
            applyUpdate(doc2, update)
        }

        val arr1 = doc1.getArray("arr")
        arr1.push(listOf("a", "b", "c"))

        val arr2 = doc2.getArray("arr")
        assertEquals(listOf("a", "b", "c"), arr2.toArray())
    }

    @Test
    fun testUpdateEventOrigin() {
        val doc = Doc()
        var receivedOrigin: Any? = "not-set"
        doc.on2<ByteArray, Any?>("update") { _, origin ->
            receivedOrigin = origin
        }

        doc.transact("my-origin") { transaction ->
            doc.getArray("test").push(listOf(1))
        }

        assertEquals("my-origin", receivedOrigin)
    }

    @Test
    fun testDestroyEvent() {
        val doc = Doc()
        var destroyFired = false
        doc.on<Doc>("destroy") { _ ->
            destroyFired = true
        }
        doc.destroy()
        assertTrue(destroyFired)
    }

    @Test
    fun testBeforeAllTransactionsEvent() {
        val doc = Doc()
        var fired = false
        doc.on<Doc>("beforeAllTransactions") { _ ->
            fired = true
        }
        doc.getArray("test").push(listOf(1))
        assertTrue(fired)
    }

    @Test
    fun testAfterAllTransactionsEvent() {
        val doc = Doc()
        var fired = false
        doc.on<Doc>("afterAllTransactions") { _ ->
            fired = true
        }
        doc.getArray("test").push(listOf(1))
        assertTrue(fired)
    }

    @Test
    fun testTransactionEventSequence() {
        val doc = Doc()
        val sequence = mutableListOf<String>()
        doc.on<Doc>("beforeAllTransactions") { _ -> sequence.add("beforeAll") }
        doc.on<Transaction>("beforeTransaction") { _ -> sequence.add("before") }
        doc.on<Transaction>("afterTransaction") { _ -> sequence.add("after") }
        doc.on<Doc>("afterAllTransactions") { _ -> sequence.add("afterAll") }

        doc.getArray("test").push(listOf(1))

        assertEquals("beforeAll", sequence[0])
        assertEquals("before", sequence[1])
        assertEquals("after", sequence[2])
        assertEquals("afterAll", sequence.last())
    }

    // =========================================================================
    // Doc.kt - Type retrieval edge cases
    // =========================================================================

    @Test
    fun testGetXmlElement() {
        val doc = Doc()
        val elem = doc.getXmlElement("xml", "div")
        assertNotNull(elem)
    }

    @Test
    fun testGetXmlElementSameNameReturnsExisting() {
        val doc = Doc()
        val elem1 = doc.getXmlElement("xml", "div")
        val elem2 = doc.getXmlElement("xml", "span")
        assertSame(elem1, elem2) // returns existing regardless of tag
    }

    @Test
    fun testTypeMismatchThrows() {
        val doc = Doc()
        doc.getArray("foo")
        assertFailsWith<IllegalStateException> {
            doc.getText("foo")
        }
    }

    @Test
    fun testGetDefaultNameArray() {
        val doc = Doc()
        val arr = doc.getArray()
        // Default name is empty string
        assertSame(arr, doc.getArray(""))
    }

    @Test
    fun testGetDefaultNameMap() {
        val doc = Doc()
        val map = doc.getMap()
        assertSame(map, doc.getMap(""))
    }

    @Test
    fun testGetDefaultNameText() {
        val doc = Doc()
        val text = doc.getText()
        assertSame(text, doc.getText(""))
    }

    // =========================================================================
    // Doc.kt - transact()
    // =========================================================================

    @Test
    fun testTransactWithOrigin() {
        val doc = Doc()
        var transactionOrigin: Any? = null
        doc.on<Transaction>("beforeTransaction") { tr ->
            transactionOrigin = tr.origin
        }
        doc.transact("test-origin") { _ ->
            doc.getArray("test").push(listOf(1))
        }
        assertEquals("test-origin", transactionOrigin)
    }

    @Test
    fun testNestedTransactionsUsesSameTransaction() {
        val doc = Doc()
        var outerTr: Transaction? = null
        var innerTr: Transaction? = null
        doc.transact { tr ->
            outerTr = tr
            doc.transact { tr2 ->
                innerTr = tr2
            }
        }
        assertSame(outerTr, innerTr)
    }

    // =========================================================================
    // StructStore.kt - getState
    // =========================================================================

    @Test
    fun testGetStateEmptyStore() {
        val store = StructStore()
        assertEquals(0, getState(store, 999))
    }

    @Test
    fun testGetStateAfterInsert() {
        val doc = Doc()
        doc.clientID = 100
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        val state = getState(doc.store, 100)
        assertTrue(state > 0)
    }

    @Test
    fun testGetStateMultipleClients() {
        val doc1 = Doc()
        doc1.clientID = 1
        val doc2 = Doc()
        doc2.clientID = 2

        doc1.getArray("arr").push(listOf("a", "b"))
        doc2.getArray("arr").push(listOf("x", "y", "z"))

        // Sync doc2 into doc1
        val update = encodeStateAsUpdate(doc2)
        applyUpdate(doc1, update)

        val state1 = getState(doc1.store, 1)
        val state2 = getState(doc1.store, 2)

        assertTrue(state1 > 0, "Client 1 should have state > 0")
        assertTrue(state2 > 0, "Client 2 should have state > 0")
    }

    @Test
    fun testGetStateForUnknownClient() {
        val store = StructStore()
        assertEquals(0, getState(store, 42))
    }

    // =========================================================================
    // StructStore.kt - getStateVector
    // =========================================================================

    @Test
    fun testGetStateVectorEmptyStore() {
        val store = StructStore()
        val sv = getStateVector(store)
        assertTrue(sv.isEmpty())
    }

    @Test
    fun testGetStateVectorAfterOperations() {
        val doc = Doc()
        doc.clientID = 10
        doc.getArray("arr").push(listOf(1, 2, 3))

        val sv = getStateVector(doc.store)
        assertTrue(sv.containsKey(10))
        assertTrue(sv[10]!! > 0)
    }

    @Test
    fun testGetStateVectorMultipleClients() {
        val doc1 = Doc()
        doc1.clientID = 1
        val doc2 = Doc()
        doc2.clientID = 2

        doc1.getArray("arr").push(listOf("a"))
        doc2.getMap("map").set("k", "v")

        val update = encodeStateAsUpdate(doc2)
        applyUpdate(doc1, update)

        val sv = getStateVector(doc1.store)
        assertTrue(sv.size >= 2)
        assertTrue(sv.containsKey(1))
        assertTrue(sv.containsKey(2))
    }

    // =========================================================================
    // StructStore.kt - findIndexSS
    // =========================================================================

    @Test
    fun testFindIndexSSSingleStruct() {
        val store = StructStore()
        val gc = GC(ID(1, 0), 5)
        store.clients[1] = mutableListOf(gc)

        val idx = findIndexSS(store.clients[1]!!, 0)
        assertEquals(0, idx)
    }

    @Test
    fun testFindIndexSSMiddleClock() {
        val store = StructStore()
        val gc = GC(ID(1, 0), 10)
        store.clients[1] = mutableListOf(gc)

        val idx = findIndexSS(store.clients[1]!!, 5)
        assertEquals(0, idx)
    }

    @Test
    fun testFindIndexSSMultipleStructs() {
        val store = StructStore()
        val structs = mutableListOf<AbstractStruct>(
            GC(ID(1, 0), 3),
            GC(ID(1, 3), 3),
            GC(ID(1, 6), 4)
        )
        store.clients[1] = structs

        assertEquals(0, findIndexSS(structs, 0))
        assertEquals(0, findIndexSS(structs, 2))
        assertEquals(1, findIndexSS(structs, 3))
        assertEquals(1, findIndexSS(structs, 5))
        assertEquals(2, findIndexSS(structs, 6))
        assertEquals(2, findIndexSS(structs, 9))
    }

    @Test
    fun testFindIndexSSLastPosition() {
        val structs = mutableListOf<AbstractStruct>(
            GC(ID(1, 0), 5),
            GC(ID(1, 5), 5)
        )
        val idx = findIndexSS(structs, 9) // last valid clock
        assertEquals(1, idx)
    }

    @Test
    fun testFindIndexSSFirstPosition() {
        val structs = mutableListOf<AbstractStruct>(
            GC(ID(1, 0), 5),
            GC(ID(1, 5), 5),
            GC(ID(1, 10), 5)
        )
        val idx = findIndexSS(structs, 0)
        assertEquals(0, idx)
    }

    @Test
    fun testFindIndexSSProportionalPivot() {
        // Create many structs to exercise the proportional pivot path
        val structs = mutableListOf<AbstractStruct>()
        for (i in 0 until 100) {
            structs.add(GC(ID(1, i), 1))
        }
        // Search at various positions
        assertEquals(0, findIndexSS(structs, 0))
        assertEquals(50, findIndexSS(structs, 50))
        assertEquals(99, findIndexSS(structs, 99))
    }

    @Test
    fun testFindIndexSSBinarySearchFallback() {
        // Structs with varying lengths so proportional pivot is less accurate
        val structs = mutableListOf<AbstractStruct>()
        var clock = 0
        val lengths = listOf(1, 1, 1, 100, 1, 1, 1, 1, 1, 1)
        for (len in lengths) {
            structs.add(GC(ID(1, clock), len))
            clock += len
        }
        // Search for a clock in the large struct
        assertEquals(3, findIndexSS(structs, 50))
        // Search for clock in small structs after the large one
        assertEquals(4, findIndexSS(structs, 103))
    }

    // =========================================================================
    // StructStore.kt - addStruct
    // =========================================================================

    @Test
    fun testAddStructNewClient() {
        val store = StructStore()
        val gc = GC(ID(42, 0), 5)
        addStruct(store, gc)
        assertTrue(store.clients.containsKey(42))
        assertEquals(1, store.clients[42]!!.size)
        assertSame(gc, store.clients[42]!![0])
    }

    @Test
    fun testAddStructAppend() {
        val store = StructStore()
        val gc1 = GC(ID(1, 0), 3)
        val gc2 = GC(ID(1, 3), 5)
        addStruct(store, gc1)
        addStruct(store, gc2)
        assertEquals(2, store.clients[1]!!.size)
        assertSame(gc1, store.clients[1]!![0])
        assertSame(gc2, store.clients[1]!![1])
    }

    @Test
    fun testAddStructMultipleClients() {
        val store = StructStore()
        addStruct(store, GC(ID(1, 0), 3))
        addStruct(store, GC(ID(2, 0), 5))
        addStruct(store, GC(ID(3, 0), 7))
        assertEquals(3, store.clients.size)
    }

    @Test
    fun testAddStructReplaceSkip() {
        val store = StructStore()
        val skip = Skip(ID(1, 0), 10)
        store.clients[1] = mutableListOf(skip)

        // Add a struct that overlaps with the skip's range
        val gc = GC(ID(1, 3), 4) // replaces middle of skip
        addStruct(store, gc)

        val structs = store.clients[1]!!
        // Should have skip(0,3), gc(3,4), skip(7,3)
        assertEquals(3, structs.size)
        assertTrue(structs[0] is Skip)
        assertEquals(0, structs[0].id.clock)
        assertEquals(3, structs[0].length)
        assertSame(gc, structs[1])
        assertTrue(structs[2] is Skip)
        assertEquals(7, structs[2].id.clock)
        assertEquals(3, structs[2].length)
    }

    @Test
    fun testAddStructReplaceSkipAtStart() {
        val store = StructStore()
        val skip = Skip(ID(1, 0), 10)
        store.clients[1] = mutableListOf(skip)

        val gc = GC(ID(1, 0), 3) // replaces start of skip
        addStruct(store, gc)

        val structs = store.clients[1]!!
        // Should have gc(0,3), skip(3,7)
        assertEquals(2, structs.size)
        assertSame(gc, structs[0])
        assertTrue(structs[1] is Skip)
        assertEquals(3, structs[1].id.clock)
        assertEquals(7, structs[1].length)
    }

    @Test
    fun testAddStructReplaceSkipAtEnd() {
        val store = StructStore()
        val skip = Skip(ID(1, 0), 10)
        store.clients[1] = mutableListOf(skip)

        val gc = GC(ID(1, 7), 3) // replaces end of skip
        addStruct(store, gc)

        val structs = store.clients[1]!!
        // Should have skip(0,7), gc(7,3)
        assertEquals(2, structs.size)
        assertTrue(structs[0] is Skip)
        assertEquals(0, structs[0].id.clock)
        assertEquals(7, structs[0].length)
        assertSame(gc, structs[1])
    }

    @Test
    fun testAddStructReplaceEntireSkip() {
        val store = StructStore()
        val skip = Skip(ID(1, 0), 5)
        store.clients[1] = mutableListOf(skip)

        val gc = GC(ID(1, 0), 5) // exactly replaces the skip
        addStruct(store, gc)

        val structs = store.clients[1]!!
        assertEquals(1, structs.size)
        assertSame(gc, structs[0])
    }

    // =========================================================================
    // StructStore.kt - replaceStruct
    // =========================================================================

    @Test
    fun testReplaceStruct() {
        val store = StructStore()
        val gc1 = GC(ID(1, 0), 5)
        addStruct(store, gc1)

        val gc2 = GC(ID(1, 0), 5) // replacement
        replaceStruct(store, gc1, gc2)

        assertSame(gc2, store.clients[1]!![0])
    }

    @Test
    fun testReplaceStructMiddle() {
        val store = StructStore()
        val gc1 = GC(ID(1, 0), 3)
        val gc2 = GC(ID(1, 3), 3)
        val gc3 = GC(ID(1, 6), 3)
        addStruct(store, gc1)
        addStruct(store, gc2)
        addStruct(store, gc3)

        val replacement = Skip(ID(1, 3), 3)
        replaceStruct(store, gc2, replacement)

        assertEquals(3, store.clients[1]!!.size)
        assertSame(gc1, store.clients[1]!![0])
        assertSame(replacement, store.clients[1]!![1])
        assertSame(gc3, store.clients[1]!![2])
    }

    @Test
    fun testReplaceStructNonExistentClient() {
        val store = StructStore()
        val gc = GC(ID(999, 0), 5)
        // Should not crash when client doesn't exist
        replaceStruct(store, gc, GC(ID(999, 0), 5))
    }

    // =========================================================================
    // StructStore.kt - find / getItem
    // =========================================================================

    @Test
    fun testFindStruct() {
        val doc = Doc()
        doc.clientID = 1
        doc.getArray("arr").push(listOf(10, 20, 30))

        val store = doc.store
        val struct = find(store, ID(1, 0))
        assertNotNull(struct)
        assertTrue(struct is Item)
    }

    @Test
    fun testFindStructThrowsForUnknownClient() {
        val store = StructStore()
        assertFailsWith<IllegalStateException> {
            find(store, ID(999, 0))
        }
    }

    @Test
    fun testGetItemAlias() {
        val doc = Doc()
        doc.clientID = 1
        doc.getArray("arr").push(listOf("a"))

        val item1 = find(doc.store, ID(1, 0))
        val item2 = getItem(doc.store, ID(1, 0))
        assertSame(item1, item2)
    }

    // =========================================================================
    // StructStore.kt - iterateStructs
    // =========================================================================

    @Test
    fun testIterateStructsEmptyStore() {
        val doc = Doc()
        val transaction = Transaction(doc)
        val visited = mutableListOf<AbstractStruct>()
        iterateStructs(transaction, emptyList(), 0, 10) { visited.add(it) }
        assertTrue(visited.isEmpty())
    }

    @Test
    fun testIterateStructsZeroLength() {
        val doc = Doc()
        doc.clientID = 1
        doc.getArray("arr").push(listOf(1, 2, 3))
        val structs = doc.store.clients[1]!!

        val visited = mutableListOf<AbstractStruct>()
        val transaction = Transaction(doc)
        iterateStructs(transaction, structs, 0, 0) { visited.add(it) }
        assertTrue(visited.isEmpty())
    }

    @Test
    fun testIterateStructsRange() {
        val store = StructStore()
        val structs = mutableListOf<AbstractStruct>(
            GC(ID(1, 0), 3),
            GC(ID(1, 3), 3),
            GC(ID(1, 6), 4)
        )
        store.clients[1] = structs

        val doc = Doc()
        val transaction = Transaction(doc)
        val visited = mutableListOf<AbstractStruct>()
        iterateStructs(transaction, structs, 3, 3) { visited.add(it) }
        assertEquals(1, visited.size)
        assertEquals(3, visited[0].id.clock)
    }

    @Test
    fun testIterateStructsPartialRange() {
        // When clockStart falls in the middle of a struct, skip to the next one
        val store = StructStore()
        val structs = mutableListOf<AbstractStruct>(
            GC(ID(1, 0), 5),
            GC(ID(1, 5), 5),
            GC(ID(1, 10), 5)
        )
        store.clients[1] = structs

        val doc = Doc()
        val transaction = Transaction(doc)
        val visited = mutableListOf<AbstractStruct>()
        // Start in the middle of first struct (clock=2), length=6 -> range [2,8)
        iterateStructs(transaction, structs, 2, 6) { visited.add(it) }
        // First struct contains clock 2 but starts at 0 => skipped; second struct (5-9) is in range
        assertEquals(1, visited.size)
        assertEquals(5, visited[0].id.clock)
    }

    // =========================================================================
    // StructStore.kt - PendingStructs
    // =========================================================================

    @Test
    fun testPendingStructsInitiallyNull() {
        val store = StructStore()
        assertNull(store.pendingStructs)
        assertNull(store.pendingDs)
    }

    // =========================================================================
    // StructStore.kt - getItemCleanStart
    // =========================================================================

    @Test
    fun testGetItemCleanStartNoSplit() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf("a"))

        doc.transact { transaction ->
            val item = assertNotNull(getItemCleanStart(transaction, ID(1, 0)))
            assertEquals(0, item.id.clock)
        }
    }

    @Test
    fun testGetItemCleanStartWithSplit() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3, 4, 5)) // stored as single ContentAny with length 5

        doc.transact { transaction ->
            val item = assertNotNull(getItemCleanStart(transaction, ID(1, 2)))
            assertEquals(2, item.id.clock)
            // The original item should have been split
            val structs = doc.store.clients[1]!!
            assertTrue(structs.size >= 2)
        }
    }

    // =========================================================================
    // StructStore.kt - getItemCleanEnd
    // =========================================================================

    @Test
    fun testGetItemCleanEndNoSplit() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf("x"))

        doc.transact { transaction ->
            val item = assertNotNull(getItemCleanEnd(transaction, doc.store, ID(1, 0)))
            assertEquals(0, item.id.clock)
        }
    }

    @Test
    fun testGetItemCleanEndWithSplit() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf(10, 20, 30, 40, 50)) // single ContentAny len=5

        doc.transact { transaction ->
            val item = getItemCleanEnd(transaction, doc.store, ID(1, 2))
            // After split, the left portion should end at clock 2
            val structs = doc.store.clients[1]!!
            assertTrue(structs.size >= 2)
        }
    }

    // =========================================================================
    // Snapshot.kt - createSnapshot
    // =========================================================================

    @Test
    fun testCreateSnapshotDirect() {
        val ds = IdSet()
        ds.add(1, 0, 3)
        ds.add(2, 5, 2)
        val sv = mapOf(1 to 10, 2 to 7)
        val snap = createSnapshot(ds, sv)
        assertEquals(sv, snap.sv)
        assertTrue(snap.ds.has(1, 0))
        assertTrue(snap.ds.has(1, 2))
        assertTrue(snap.ds.has(2, 5))
        assertTrue(snap.ds.has(2, 6))
        assertFalse(snap.ds.has(2, 7))
    }

    // =========================================================================
    // Snapshot.kt - emptySnapshot
    // =========================================================================

    @Test
    fun testEmptySnapshotProperties() {
        assertTrue(emptySnapshot.sv.isEmpty())
        assertTrue(emptySnapshot.ds.clients.isEmpty())
    }

    @Test
    fun testEmptySnapshotEquality() {
        assertTrue(equalSnapshots(emptySnapshot, emptySnapshot))
    }

    @Test
    fun testEmptySnapshotNotEqualToPopulated() {
        val doc = Doc()
        doc.getArray("arr").push(listOf(1))
        val snap = snapshot(doc)
        assertFalse(equalSnapshots(emptySnapshot, snap))
    }

    // =========================================================================
    // Snapshot.kt - equalSnapshots
    // =========================================================================

    @Test
    fun testEqualSnapshotsIdentical() {
        val ds = IdSet()
        ds.add(1, 0, 5)
        val sv = mapOf(1 to 5)
        val snap1 = Snapshot(ds, sv)
        val snap2 = Snapshot(ds, sv)
        assertTrue(equalSnapshots(snap1, snap2))
    }

    @Test
    fun testEqualSnapshotsDifferentSVSize() {
        val snap1 = Snapshot(IdSet(), mapOf(1 to 5))
        val snap2 = Snapshot(IdSet(), mapOf(1 to 5, 2 to 3))
        assertFalse(equalSnapshots(snap1, snap2))
    }

    @Test
    fun testEqualSnapshotsDifferentSVValues() {
        val snap1 = Snapshot(IdSet(), mapOf(1 to 5))
        val snap2 = Snapshot(IdSet(), mapOf(1 to 10))
        assertFalse(equalSnapshots(snap1, snap2))
    }

    @Test
    fun testEqualSnapshotsDifferentSVClients() {
        val snap1 = Snapshot(IdSet(), mapOf(1 to 5))
        val snap2 = Snapshot(IdSet(), mapOf(2 to 5))
        assertFalse(equalSnapshots(snap1, snap2))
    }

    @Test
    fun testEqualSnapshotsDifferentDSSize() {
        val ds1 = IdSet()
        ds1.add(1, 0, 3)
        val ds2 = IdSet()
        ds2.add(1, 0, 3)
        ds2.add(2, 0, 2)
        val sv = mapOf(1 to 5, 2 to 5)
        assertFalse(equalSnapshots(Snapshot(ds1, sv), Snapshot(ds2, sv)))
    }

    @Test
    fun testEqualSnapshotsDifferentDSClients() {
        val ds1 = IdSet()
        ds1.add(1, 0, 3)
        val ds2 = IdSet()
        ds2.add(2, 0, 3)
        val sv = mapOf(1 to 5, 2 to 5)
        assertFalse(equalSnapshots(Snapshot(ds1, sv), Snapshot(ds2, sv)))
    }

    @Test
    fun testEqualSnapshotsDifferentDSRanges() {
        val ds1 = IdSet()
        ds1.add(1, 0, 3)
        val ds2 = IdSet()
        ds2.add(1, 0, 5)
        val sv = mapOf(1 to 10)
        assertFalse(equalSnapshots(Snapshot(ds1, sv), Snapshot(ds2, sv)))
    }

    @Test
    fun testEqualSnapshotsDifferentDSClock() {
        val ds1 = IdSet()
        ds1.add(1, 0, 3)
        val ds2 = IdSet()
        ds2.add(1, 2, 3) // same len, different clock
        val sv = mapOf(1 to 10)
        assertFalse(equalSnapshots(Snapshot(ds1, sv), Snapshot(ds2, sv)))
    }

    @Test
    fun testEqualSnapshotsDifferentNumberOfRanges() {
        val ds1 = IdSet()
        ds1.add(1, 0, 3)
        val ds2 = IdSet()
        ds2.add(1, 0, 1)
        ds2.add(1, 5, 1) // same client, different number of ranges
        val sv = mapOf(1 to 10)
        assertFalse(equalSnapshots(Snapshot(ds1, sv), Snapshot(ds2, sv)))
    }

    // =========================================================================
    // Snapshot.kt - encodeSnapshot / decodeSnapshot
    // =========================================================================

    @Test
    fun testEncodeDecodeEmptySnapshot() {
        val encoded = encodeSnapshot(emptySnapshot)
        val decoded = decodeSnapshot(encoded)
        assertTrue(equalSnapshots(emptySnapshot, decoded))
    }

    @Test
    fun testEncodeDecodeSnapshotWithDS() {
        val ds = IdSet()
        ds.add(1, 0, 3)
        ds.add(1, 5, 2)
        ds.add(2, 10, 5)
        val sv = mapOf(1 to 10, 2 to 20)
        val snap = Snapshot(ds, sv)

        val encoded = encodeSnapshot(snap)
        val decoded = decodeSnapshot(encoded)

        assertTrue(equalSnapshots(snap, decoded))
        assertTrue(decoded.ds.has(1, 0))
        assertTrue(decoded.ds.has(1, 5))
        assertTrue(decoded.ds.has(2, 12))
    }

    @Test
    fun testEncodeDecodeSnapshotRoundTripMultiple() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        arr.delete(1, 1) // delete middle

        val map = doc.getMap("map")
        map.set("key", "value")

        val snap = snapshot(doc)
        val encoded = encodeSnapshot(snap)
        val decoded = decodeSnapshot(encoded)
        assertTrue(equalSnapshots(snap, decoded))

        // Double encode/decode
        val reEncoded = encodeSnapshot(decoded)
        val reDecoded = decodeSnapshot(reEncoded)
        assertTrue(equalSnapshots(snap, reDecoded))
    }

    @Test
    fun testEncodeDecodeSnapshotWithMultipleClients() {
        val doc1 = Doc()
        doc1.clientID = 1
        val doc2 = Doc()
        doc2.clientID = 2

        doc1.getArray("arr").push(listOf("a", "b"))
        doc2.getArray("arr").push(listOf("x", "y"))

        val update = encodeStateAsUpdate(doc2)
        applyUpdate(doc1, update)

        val snap = snapshot(doc1)
        val encoded = encodeSnapshot(snap)
        val decoded = decodeSnapshot(encoded)
        assertTrue(equalSnapshots(snap, decoded))
    }

    // =========================================================================
    // Snapshot.kt - isVisible
    // =========================================================================

    @Test
    fun testIsVisibleForExistingItem() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b", "c"))

        val snap = snapshot(doc)
        val structs = doc.store.clients[1]!!

        // All items should be visible in the current snapshot
        for (struct in structs) {
            if (struct is Item) {
                assertTrue(isVisible(struct, snap))
            }
        }
    }

    @Test
    fun testIsVisibleForDeletedItem() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b", "c"))

        val snap1 = snapshot(doc)
        arr.delete(1, 1) // delete "b"
        val snap2 = snapshot(doc)

        val structs = doc.store.clients[1]!!
        // All items should be visible in snap1 (before delete)
        for (struct in structs) {
            if (struct is Item && struct.id.clock < (snap1.sv[struct.id.client] ?: 0)) {
                if (!snap1.ds.has(struct.id.client, struct.id.clock)) {
                    assertTrue(isVisible(struct, snap1))
                }
            }
        }

        // In snap2, deleted items should not be visible
        val deletedItems = structs.filter { it is Item && it.deleted }
        for (item in deletedItems) {
            if (item is Item) {
                assertFalse(isVisible(item, snap2))
            }
        }
    }

    @Test
    fun testIsVisibleUnknownClient() {
        val ds = IdSet()
        val sv = mapOf(1 to 5)
        val snap = Snapshot(ds, sv)

        val doc = Doc()
        doc.clientID = 99
        doc.getArray("arr").push(listOf("x"))
        val struct = doc.store.clients[99]!!.first() as Item

        // Client 99 not in snapshot sv -> not visible
        assertFalse(isVisible(struct, snap))
    }

    @Test
    fun testIsVisibleBeyondStateVector() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf("a"))

        val snap = snapshot(doc) // capture snap with 1 item

        arr.push(listOf("b")) // add another item

        // The new item should not be visible in the earlier snapshot
        val structs = doc.store.clients[1]!!
        val lastStruct = structs.last()
        if (lastStruct is Item && lastStruct.id.clock >= (snap.sv[1] ?: 0)) {
            assertFalse(isVisible(lastStruct, snap))
        }
    }

    // =========================================================================
    // Snapshot.kt - snapshot() function
    // =========================================================================

    @Test
    fun testSnapshotCapturesCurrentState() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        val snap = snapshot(doc)
        assertFalse(snap.sv.isEmpty())
        assertTrue(snap.sv[1]!! > 0)
    }

    @Test
    fun testSnapshotWithDeletes() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        arr.delete(0, 2)

        val snap = snapshot(doc)
        assertFalse(snap.ds.isEmpty())
    }

    @Test
    fun testSnapshotProgresses() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")

        arr.push(listOf(1))
        val snap1 = snapshot(doc)

        arr.push(listOf(2))
        val snap2 = snapshot(doc)

        // snap2 should have a higher clock than snap1
        assertTrue(snap2.sv[1]!! > snap1.sv[1]!!)
        assertFalse(equalSnapshots(snap1, snap2))
    }

    // =========================================================================
    // Snapshot.kt - createDocFromSnapshot
    // =========================================================================

    @Test
    fun testCreateDocFromSnapshot() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b", "c"))

        val snap = snapshot(doc)

        arr.push(listOf("d", "e")) // add more after snapshot

        val snapDoc = createDocFromSnapshot(doc, snap)
        val snapArr = snapDoc.getArray("arr")
        // The snapshot doc should only have the state at snapshot time
        assertEquals(3, snapArr.length)
        assertEquals(listOf("a", "b", "c"), snapArr.toArray())
    }

    @Test
    fun testCreateDocFromSnapshotWithDeletes() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b", "c", "d"))

        val snapBefore = snapshot(doc)

        arr.delete(1, 2) // delete "b" and "c"

        // Restore from the snapshot before the delete
        // The snapshot's delete set is empty (no deletes at that point),
        // so the restored doc should show all items as non-deleted.
        val restoredDoc = createDocFromSnapshot(doc, snapBefore)
        val restoredArr = restoredDoc.getArray("arr")
        // createDocFromSnapshot uses the snapshot's delete set (empty)
        // and the snapshot's state vector to determine which structs to include
        assertTrue(restoredArr.length > 0)
    }

    @Test
    fun testCreateDocFromSnapshotEmpty() {
        val doc = Doc()
        doc.clientID = 1

        // Take snapshot of empty doc
        val snap = snapshot(doc)
        val snapDoc = createDocFromSnapshot(doc, snap)
        assertTrue(snapDoc.store.clients.isEmpty() || getStateVector(snapDoc.store).isEmpty())
    }

    // =========================================================================
    // ArraySearchMarker.kt - findMarker
    // =========================================================================

    @Test
    fun testFindMarkerReturnsNullForEmptyType() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        // Empty array -> start is null -> should return null
        val marker = findMarker(arr, 5)
        assertNull(marker)
    }

    @Test
    fun testFindMarkerReturnsNullForIndexZero() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        val marker = findMarker(arr, 0)
        assertNull(marker)
    }

    @Test
    fun testFindMarkerReturnsNullWhenNoMarkers() {
        val type = YMap() // YMap doesn't initialize searchMarker
        val marker = findMarker(type, 5)
        assertNull(marker)
    }

    @Test
    fun testFindMarkerWithEmptyMarkerList() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        // Clear markers to test empty marker list path
        arr.searchMarker!!.clear()
        val marker = findMarker(arr, 2)
        assertNull(marker)
    }

    @Test
    fun testFindMarkerFindsClosest() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        // Push a large number of items to trigger marker creation
        arr.push((0 until 200).toList())

        val item1 = arr.start!!
        val marker1 = ArraySearchMarker(item1, 0)
        arr.searchMarker!!.clear()
        arr.searchMarker!!.add(marker1)

        // Find marker closest to index 10
        val found = findMarker(arr, 10)
        assertNotNull(found)
        assertSame(marker1, found)
    }

    @Test
    fun testFindMarkerPicksNearest() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push((0 until 100).toList())

        // Manually add two markers at different positions
        val item = arr.start!!
        val marker1 = ArraySearchMarker(item, 10)
        val marker2 = ArraySearchMarker(item, 90)
        arr.searchMarker!!.clear()
        arr.searchMarker!!.add(marker1)
        arr.searchMarker!!.add(marker2)

        // Searching for index 15 should pick marker at index 10 (distance 5 vs 75)
        val found = findMarker(arr, 15)
        assertNotNull(found)
        assertSame(marker1, found)

        // Searching for index 85 should pick marker at index 90 (distance 5 vs 75)
        val found2 = findMarker(arr, 85)
        assertNotNull(found2)
        assertSame(marker2, found2)
    }

    // =========================================================================
    // ArraySearchMarker.kt - updateMarker
    // =========================================================================

    @Test
    fun testUpdateMarkerWalkRight() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        // Insert individually to create separate linked list items
        for (i in 0 until 20) {
            arr.push(listOf(i))
        }

        val item = arr.start!!
        val marker = ArraySearchMarker(item, 0)

        updateMarker(marker, arr, 5)
        // After walking right, marker index should be updated
        assertEquals(5, marker.index)
    }

    @Test
    fun testUpdateMarkerWalkLeft() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        // Insert items one at a time to create individual items in the linked list
        for (i in 0 until 20) {
            arr.push(listOf(i))
        }

        // Find an item deep in the list
        var item = arr.start!!
        var idx = 0
        while (item.right != null && idx < 15) {
            if (!item.right!!.deleted && item.right!!.countable) {
                idx += item.right!!.length
            }
            item = item.right!!
        }

        val marker = ArraySearchMarker(item, idx)
        updateMarker(marker, arr, 5)
        assertEquals(5, marker.index)
    }

    @Test
    fun testUpdateMarkerSameIndex() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push((0 until 10).toList())

        val item = arr.start!!
        val marker = ArraySearchMarker(item, 0)
        val oldTimestamp = marker.timestamp

        updateMarker(marker, arr, 0)
        // Timestamp should be updated even if index is unchanged
        assertTrue(marker.timestamp >= oldTimestamp)
    }

    @Test
    fun testUpdateMarkerTimestampIncreases() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        for (i in 0 until 10) {
            arr.push(listOf(i))
        }

        val item = arr.start!!
        val marker = ArraySearchMarker(item, 0, 0L)
        val timestamp1 = marker.timestamp

        updateMarker(marker, arr, 3)
        val timestamp2 = marker.timestamp
        assertTrue(timestamp2 > timestamp1)

        updateMarker(marker, arr, 5)
        val timestamp3 = marker.timestamp
        assertTrue(timestamp3 > timestamp2)
    }

    // =========================================================================
    // ArraySearchMarker.kt - refreshMarker
    // =========================================================================

    @Test
    fun testRefreshMarkerWithNonDeletedItem() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        val item = arr.start!!
        val marker = ArraySearchMarker(item, 0)

        // Item is not deleted, so refresh should keep the same item
        refreshMarker(marker, arr)
        assertSame(item, marker.item)
    }

    @Test
    fun testRefreshMarkerWithDeletedItemWalksRight() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        // Insert individually to create separate items
        for (i in 0 until 5) {
            arr.push(listOf(i))
        }

        val firstItem = arr.start!!
        val secondItem = firstItem.right!!
        val marker = ArraySearchMarker(firstItem, 0)

        // Delete the first item
        arr.delete(0)

        // Now the marker's item is deleted, refreshMarker should walk right
        refreshMarker(marker, arr)
        // marker.item should now point to a non-deleted item
        assertFalse(marker.item.deleted)
    }

    @Test
    fun testRefreshMarkerWithDeletedItemWalksLeft() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        // Insert individually to create separate items
        for (i in 0 until 5) {
            arr.push(listOf(i))
        }

        // Get the last item
        var lastItem = arr.start!!
        while (lastItem.right != null) {
            lastItem = lastItem.right!!
        }

        val marker = ArraySearchMarker(lastItem, 4)

        // Delete the last item
        arr.delete(4)

        // marker's item is deleted, and there's nothing to the right
        // refreshMarker should walk left
        refreshMarker(marker, arr)
        assertFalse(marker.item.deleted)
    }

    // =========================================================================
    // ArraySearchMarker.kt - MAX_SEARCH_MARKERS constant
    // =========================================================================

    @Test
    fun testMaxSearchMarkersValue() {
        assertEquals(80, ArraySearchMarker.MAX_SEARCH_MARKERS)
    }

    // =========================================================================
    // ArraySearchMarker.kt - globalTimestamp
    // =========================================================================

    @Test
    fun testGlobalTimestampMonotonicallyIncreases() {
        val ts1 = ArraySearchMarker.globalTimestamp
        val doc = Doc()
        val arr = doc.getArray("arr")
        val item1 = ArraySearchMarker(
            item = run {
                arr.push(listOf(1))
                arr.start!!
            },
            index = 0
        )
        val ts2 = ArraySearchMarker.globalTimestamp
        assertTrue(ts2 > ts1)
    }

    // =========================================================================
    // ArraySearchMarker.kt - Integration with YArray operations
    // =========================================================================

    @Test
    fun testSearchMarkerAfterLargeInsertAndAccess() {
        val doc = Doc()
        val arr = doc.getArray("arr")

        // Insert a large number of items to exercise search markers
        arr.push((0 until 500).toList())

        // Access various positions
        assertEquals(0, arr.get(0))
        assertEquals(250, arr.get(250))
        assertEquals(499, arr.get(499))

        // Insert in the middle
        arr.insert(250, listOf(-1))
        assertEquals(-1, arr.get(250))
        assertEquals(250, arr.get(251))

        // Delete from the middle
        arr.delete(100, 50)
        assertEquals(451, arr.length)
    }

    @Test
    fun testSearchMarkerAfterManyIndividualInserts() {
        val doc = Doc()
        val arr = doc.getArray("arr")

        // Individual inserts create separate linked list items (better marker exercise)
        for (i in 0 until 100) {
            arr.push(listOf(i))
        }

        // Access forwards
        for (i in 0 until 100) {
            assertEquals(i, arr.get(i))
        }

        // Access backwards
        for (i in 99 downTo 0) {
            assertEquals(i, arr.get(i))
        }

        // Access random pattern
        for (i in listOf(50, 10, 90, 30, 70, 5, 95, 0, 99)) {
            assertEquals(i, arr.get(i))
        }
    }

    @Test
    fun testSearchMarkerSurvivesDeleteInMiddle() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        for (i in 0 until 100) {
            arr.push(listOf(i))
        }

        // Access to create markers
        arr.get(50)
        arr.get(75)

        // Delete items around where markers might be
        arr.delete(48, 5)

        // Array should still be consistent
        assertEquals(95, arr.length)
        assertEquals(0, arr.get(0))
        assertEquals(47, arr.get(47))
        assertEquals(53, arr.get(48))
    }

    // =========================================================================
    // Cross-cutting: Doc + StructStore + Snapshot integration
    // =========================================================================

    @Test
    fun testSnapshotEncodeDecodeConsistency() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        val map = doc.getMap("map")
        val text = doc.getText("text")

        arr.push(listOf(1, 2, 3))
        map.set("name", "test")
        text.insert(0, "hello world")

        val snap1 = snapshot(doc)

        arr.delete(1)
        map.set("name", "updated")
        text.delete(5, 6)

        val snap2 = snapshot(doc)

        // Encode and decode both
        val dec1 = decodeSnapshot(encodeSnapshot(snap1))
        val dec2 = decodeSnapshot(encodeSnapshot(snap2))

        assertTrue(equalSnapshots(snap1, dec1))
        assertTrue(equalSnapshots(snap2, dec2))
        assertFalse(equalSnapshots(dec1, dec2))
    }

    @Test
    fun testStateVectorConsistentWithSnapshot() {
        val doc = Doc()
        doc.clientID = 1
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        val sv = getStateVector(doc.store)
        val snap = snapshot(doc)

        assertEquals(sv, snap.sv)
    }

    @Test
    fun testDocTransactOriginPassedToUpdateEvent() {
        val doc = Doc()
        var receivedOrigin: Any? = null
        doc.on2<ByteArray, Any?>("update") { _, origin ->
            receivedOrigin = origin
        }

        doc.transact("custom-origin") { _ ->
            doc.getArray("arr").push(listOf(1))
        }

        assertEquals("custom-origin", receivedOrigin)
    }

    @Test
    fun testDocGcFilterCustom() {
        // Custom GC filter that prevents GC on all items
        val doc = Doc(gc = true, gcFilter = { false })
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))
        arr.delete(0, 3)

        // With gcFilter = { false }, items should NOT be GC'd to ContentDeleted
        val clientId = doc.clientID
        val structs = doc.store.clients[clientId]!!
        val hasContentDeleted = structs.any {
            it is Item && it.deleted && it.content is ContentDeleted
        }
        assertFalse(hasContentDeleted, "Custom gcFilter returning false should prevent GC")
    }

    @Test
    fun testAfterTransactionCleanupEvent() {
        val doc = Doc()
        var fired = false
        doc.on<Transaction>("afterTransactionCleanup") { _ ->
            fired = true
        }
        doc.getArray("arr").push(listOf(1))
        assertTrue(fired)
    }

    @Test
    fun testBeforeObserverCallsEvent() {
        val doc = Doc()
        var fired = false
        doc.on<Transaction>("beforeObserverCalls") { _ ->
            fired = true
        }
        doc.getArray("arr").push(listOf(1))
        assertTrue(fired)
    }

    @Test
    fun testClientIdCollisionDetection() {
        val doc1 = Doc()
        doc1.clientID = 100
        val doc2 = Doc()
        doc2.clientID = 100 // same client ID

        doc1.getArray("arr").push(listOf(1))

        val update = encodeStateAsUpdate(doc1)
        // Apply update from "remote" with same client ID => collision detection
        applyUpdate(doc2, update, "remote")

        // doc2 should have changed its client ID
        assertNotEquals(100, doc2.clientID)
    }

    @Test
    fun testHasListeners() {
        val doc = Doc()
        assertFalse(doc.hasListeners("update"))

        val unsub = doc.on2<ByteArray, Any?>("update") { _, _ -> }
        assertTrue(doc.hasListeners("update"))

        unsub()
        assertFalse(doc.hasListeners("update"))
    }

    @Test
    fun testSnapshotDataClass() {
        val ds = IdSet()
        val sv = mapOf(1 to 5)
        val snap = Snapshot(ds, sv)
        assertEquals(ds, snap.ds)
        assertEquals(sv, snap.sv)
    }
}
