package yks

import yks.types.*
import yks.utils.*
import kotlin.test.*

class YXmlTest {

    // ==================== Helper ====================

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

    // ==================== YXmlFragment ====================

    @Test
    fun testXmlFragmentTypeName() {
        val frag = YXmlFragment()
        assertEquals("XmlFragment", frag.typeName)
    }

    @Test
    fun testXmlFragmentCopy() {
        val frag = YXmlFragment()
        val copy = frag.copy()
        assertIs<YXmlFragment>(copy)
        assertNotSame(frag, copy)
    }

    @Test
    fun testXmlFragmentIntegrationViaArray() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val frag = YXmlFragment()
        arr.push(listOf(frag))
        assertNotNull(frag.doc)
        assertSame(doc, frag.doc)
    }

    @Test
    fun testXmlFragmentInsertChildren() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val frag = YXmlFragment()
        arr.push(listOf(frag))

        val child1 = YXmlElement("p")
        val child2 = YXmlElement("span")
        frag.insert(0, listOf(child1, child2))
        assertEquals(2, frag.length)
    }

    @Test
    fun testXmlFragmentGetChild() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val frag = YXmlFragment()
        arr.push(listOf(frag))

        val child = YXmlElement("div")
        frag.insert(0, listOf(child))
        val retrieved = frag.get(0)
        assertIs<YXmlElement>(retrieved)
        assertEquals("div", retrieved.tag)
    }

    @Test
    fun testXmlFragmentToArray() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val frag = YXmlFragment()
        arr.push(listOf(frag))

        val elem1 = YXmlElement("h1")
        val elem2 = YXmlElement("p")
        val elem3 = YXmlElement("footer")
        frag.insert(0, listOf(elem1, elem2, elem3))

        val children = frag.toArray()
        assertEquals(3, children.size)
        assertIs<YXmlElement>(children[0])
        assertIs<YXmlElement>(children[1])
        assertIs<YXmlElement>(children[2])
    }

    @Test
    fun testXmlFragmentDelete() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val frag = YXmlFragment()
        arr.push(listOf(frag))

        val elem1 = YXmlElement("a")
        val elem2 = YXmlElement("b")
        val elem3 = YXmlElement("c")
        frag.insert(0, listOf(elem1, elem2, elem3))
        assertEquals(3, frag.length)

        frag.delete(1) // delete "b"
        assertEquals(2, frag.length)

        val children = frag.toArray()
        assertEquals(2, children.size)
    }

    @Test
    fun testXmlFragmentDeleteMultiple() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val frag = YXmlFragment()
        arr.push(listOf(frag))

        val elems = listOf(
            YXmlElement("a"),
            YXmlElement("b"),
            YXmlElement("c"),
            YXmlElement("d")
        )
        frag.insert(0, elems)
        assertEquals(4, frag.length)

        frag.delete(1, 2) // delete "b" and "c"
        assertEquals(2, frag.length)
    }

    @Test
    fun testXmlFragmentEmptyLength() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val frag = YXmlFragment()
        arr.push(listOf(frag))
        assertEquals(0, frag.length)
        assertEquals(emptyList(), frag.toArray())
    }

    @Test
    fun testXmlFragmentToJSON() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val frag = YXmlFragment()
        arr.push(listOf(frag))

        // Empty fragment returns an empty list
        val json = frag.toJSON()
        assertIs<List<*>>(json)
    }

    @Test
    fun testXmlFragmentRootLevel() {
        val doc = Doc()
        val frag = doc.getXmlFragment("xmlRoot")
        assertNotNull(frag.doc)

        val child = YXmlElement("section")
        frag.insert(0, listOf(child))
        assertEquals(1, frag.length)
        assertIs<YXmlElement>(frag.get(0))
    }

    // ==================== YXmlElement ====================

    @Test
    fun testXmlElementTypeName() {
        val elem = YXmlElement("div")
        assertEquals("XmlElement:div", elem.typeName)
    }

    @Test
    fun testXmlElementTag() {
        val elem = YXmlElement("span")
        assertEquals("span", elem.tag)
    }

    @Test
    fun testXmlElementCopy() {
        val elem = YXmlElement("p")
        val copy = elem.copy()
        assertIs<YXmlElement>(copy)
        assertEquals("p", copy.tag)
        assertNotSame(elem, copy)
    }

    @Test
    fun testXmlElementSetAndGetAttribute() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("div")
        arr.push(listOf(elem))

        elem.setAttribute("class", "container")
        assertEquals("container", elem.getAttribute("class"))
    }

    @Test
    fun testXmlElementMultipleAttributes() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("div")
        arr.push(listOf(elem))

        elem.setAttribute("id", "main")
        elem.setAttribute("class", "wrapper")
        elem.setAttribute("data-value", "42")

        assertEquals("main", elem.getAttribute("id"))
        assertEquals("wrapper", elem.getAttribute("class"))
        assertEquals("42", elem.getAttribute("data-value"))
    }

    @Test
    fun testXmlElementOverwriteAttribute() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("div")
        arr.push(listOf(elem))

        elem.setAttribute("class", "old")
        elem.setAttribute("class", "new")
        assertEquals("new", elem.getAttribute("class"))
    }

    @Test
    fun testXmlElementRemoveAttribute() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("div")
        arr.push(listOf(elem))

        elem.setAttribute("class", "container")
        assertEquals("container", elem.getAttribute("class"))

        elem.removeAttribute("class")
        assertNull(elem.getAttribute("class"))
    }

    @Test
    fun testXmlElementGetAttributes() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("div")
        arr.push(listOf(elem))

        elem.setAttribute("id", "main")
        elem.setAttribute("class", "wrapper")

        val attrs = elem.getAttributes()
        assertEquals(2, attrs.size)
        assertEquals("main", attrs["id"])
        assertEquals("wrapper", attrs["class"])
    }

    @Test
    fun testXmlElementGetAttributesEmpty() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("div")
        arr.push(listOf(elem))

        val attrs = elem.getAttributes()
        assertTrue(attrs.isEmpty())
    }

    @Test
    fun testXmlElementGetAttributeNonexistent() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("div")
        arr.push(listOf(elem))

        assertNull(elem.getAttribute("nonexistent"))
    }

    @Test
    fun testXmlElementWithChildren() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val parent = YXmlElement("div")
        arr.push(listOf(parent))

        val child1 = YXmlElement("p")
        val child2 = YXmlElement("span")
        parent.insert(0, listOf(child1, child2))

        assertEquals(2, parent.length)
        val children = parent.toArray()
        assertEquals(2, children.size)
    }

    @Test
    fun testXmlElementChildrenAndAttributes() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("div")
        arr.push(listOf(elem))

        // Set attributes
        elem.setAttribute("class", "container")
        elem.setAttribute("id", "main")

        // Add children
        val child = YXmlElement("p")
        elem.insert(0, listOf(child))

        // Both should work independently
        assertEquals("container", elem.getAttribute("class"))
        assertEquals("main", elem.getAttribute("id"))
        assertEquals(1, elem.length)
    }

    @Test
    fun testXmlElementToJSON() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("div")
        arr.push(listOf(elem))

        elem.setAttribute("class", "box")

        val json = elem.toJSON()
        assertIs<Map<*, *>>(json)
        assertEquals("div", json["tag"])
        assertIs<Map<*, *>>(json["attributes"])
        val attrs = json["attributes"] as Map<*, *>
        assertEquals("box", attrs["class"])
        assertIs<List<*>>(json["children"])
    }

    @Test
    fun testXmlElementToJSONWithChildren() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val parent = YXmlElement("div")
        arr.push(listOf(parent))

        val child = YXmlElement("p")
        parent.insert(0, listOf(child))
        child.setAttribute("class", "text")

        val json = parent.toJSON() as Map<*, *>
        val children = json["children"] as List<*>
        assertEquals(1, children.size)

        val childJson = children[0] as Map<*, *>
        assertEquals("p", childJson["tag"])
    }

    @Test
    fun testXmlElementRootLevel() {
        val doc = Doc()
        val elem = doc.getXmlElement("elem", "root")
        assertNotNull(elem.doc)
        assertEquals("root", elem.tag)

        elem.setAttribute("version", "1.0")
        assertEquals("1.0", elem.getAttribute("version"))
    }

    @Test
    fun testXmlElementDeleteChildren() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("ul")
        arr.push(listOf(elem))

        val items = listOf(
            YXmlElement("li"),
            YXmlElement("li"),
            YXmlElement("li")
        )
        elem.insert(0, items)
        assertEquals(3, elem.length)

        elem.delete(0, 2)
        assertEquals(1, elem.length)
    }

    // ==================== YXmlText ====================

    @Test
    fun testXmlTextTypeName() {
        val xmlText = YXmlText()
        assertEquals("XmlText", xmlText.typeName)
    }

    @Test
    fun testXmlTextCopy() {
        val xmlText = YXmlText()
        val copy = xmlText.copy()
        assertIs<YXmlText>(copy)
        assertNotSame(xmlText, copy)
    }

    @Test
    fun testXmlTextInsertAndToString() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val xmlText = YXmlText()
        arr.push(listOf(xmlText))

        xmlText.insert(0, "hello")
        assertEquals("hello", xmlText.toString())
    }

    @Test
    fun testXmlTextInsertMultiple() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val xmlText = YXmlText()
        arr.push(listOf(xmlText))

        xmlText.insert(0, "world")
        xmlText.insert(0, "hello ")
        assertEquals("hello world", xmlText.toString())
    }

    @Test
    fun testXmlTextDelete() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val xmlText = YXmlText()
        arr.push(listOf(xmlText))

        xmlText.insert(0, "hello world")
        xmlText.delete(5, 6) // remove " world"
        assertEquals("hello", xmlText.toString())
    }

    @Test
    fun testXmlTextLength() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val xmlText = YXmlText()
        arr.push(listOf(xmlText))

        assertEquals(0, xmlText.length)
        xmlText.insert(0, "test")
        assertEquals(4, xmlText.length)
    }

    @Test
    fun testXmlTextEmpty() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val xmlText = YXmlText()
        arr.push(listOf(xmlText))

        assertEquals("", xmlText.toString())
        assertEquals(0, xmlText.length)
    }

    @Test
    fun testXmlTextInsideElement() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("p")
        arr.push(listOf(elem))

        val xmlText = YXmlText()
        elem.insert(0, listOf(xmlText))
        xmlText.insert(0, "paragraph content")

        assertEquals("paragraph content", xmlText.toString())
        assertEquals(1, elem.length)
    }

    // ==================== YXmlHook ====================

    @Test
    fun testXmlHookTypeName() {
        val hook = YXmlHook("myHook")
        assertEquals("XmlHook", hook.typeName)
    }

    @Test
    fun testXmlHookHookName() {
        val hook = YXmlHook("testHook")
        assertEquals("testHook", hook.hookName)
    }

    @Test
    fun testXmlHookCopy() {
        val hook = YXmlHook("myHook")
        val copy = hook.copy()
        assertIs<YXmlHook>(copy)
        assertEquals("myHook", copy.hookName)
        assertNotSame(hook, copy)
    }

    @Test
    fun testXmlHookSetAndGet() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val hook = YXmlHook("myHook")
        arr.push(listOf(hook))

        hook.set("key", "value")
        assertEquals("value", hook.get("key"))
    }

    @Test
    fun testXmlHookMultipleKeys() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val hook = YXmlHook("myHook")
        arr.push(listOf(hook))

        hook.set("a", 1)
        hook.set("b", "two")
        hook.set("c", true)

        assertEquals(1, hook.get("a"))
        assertEquals("two", hook.get("b"))
        assertEquals(true, hook.get("c"))
    }

    @Test
    fun testXmlHookDelete() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val hook = YXmlHook("myHook")
        arr.push(listOf(hook))

        hook.set("key", "value")
        assertTrue(hook.has("key"))
        hook.delete("key")
        assertFalse(hook.has("key"))
        assertNull(hook.get("key"))
    }

    @Test
    fun testXmlHookOverwrite() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val hook = YXmlHook("myHook")
        arr.push(listOf(hook))

        hook.set("key", "first")
        hook.set("key", "second")
        assertEquals("second", hook.get("key"))
    }

    @Test
    fun testXmlHookEntries() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val hook = YXmlHook("myHook")
        arr.push(listOf(hook))

        hook.set("x", 10)
        hook.set("y", 20)

        val entries = hook.entries()
        assertEquals(2, entries.size)
        assertEquals(10, entries["x"])
        assertEquals(20, entries["y"])
    }

    @Test
    fun testXmlHookSize() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val hook = YXmlHook("myHook")
        arr.push(listOf(hook))

        assertEquals(0, hook.size)
        hook.set("a", 1)
        hook.set("b", 2)
        assertEquals(2, hook.size)
    }

    // ==================== Observer Events ====================

    @Test
    fun testXmlFragmentObserve() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val frag = YXmlFragment()
        arr.push(listOf(frag))

        var eventCount = 0
        frag.observe { _, _ -> eventCount++ }

        val child = YXmlElement("div")
        frag.insert(0, listOf(child))
        assertEquals(1, eventCount)

        frag.delete(0)
        assertEquals(2, eventCount)
    }

    @Test
    fun testXmlElementObserve() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("div")
        arr.push(listOf(elem))

        var eventCount = 0
        elem.observe { _, _ -> eventCount++ }

        elem.setAttribute("class", "box")
        assertEquals(1, eventCount)

        elem.setAttribute("id", "main")
        assertEquals(2, eventCount)

        elem.removeAttribute("class")
        assertEquals(3, eventCount)
    }

    @Test
    fun testXmlTextObserve() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val xmlText = YXmlText()
        arr.push(listOf(xmlText))

        var eventCount = 0
        xmlText.observe { _, _ -> eventCount++ }

        xmlText.insert(0, "hello")
        assertEquals(1, eventCount)

        xmlText.delete(0, 3)
        assertEquals(2, eventCount)
    }

    @Test
    fun testXmlHookObserve() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val hook = YXmlHook("myHook")
        arr.push(listOf(hook))

        var eventCount = 0
        hook.observe { _, _ -> eventCount++ }

        hook.set("key", "value")
        assertEquals(1, eventCount)

        hook.delete("key")
        assertEquals(2, eventCount)
    }

    // ==================== Sync Round-Trip ====================

    @Test
    fun testXmlElementSyncRoundTrip() {
        val doc1 = Doc()
        val arr1 = doc1.getArray("root")
        val elem = YXmlElement("div")
        arr1.push(listOf(elem))
        elem.setAttribute("class", "container")
        elem.setAttribute("id", "main")

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val arr2 = doc2.getArray("root")
        val elem2 = arr2.get(0)
        assertIs<YXmlElement>(elem2)
        // Note: tag is not preserved during encoding/decoding yet (known limitation)
        assertEquals("container", elem2.getAttribute("class"))
        assertEquals("main", elem2.getAttribute("id"))
    }

    @Test
    fun testXmlElementChildrenSyncRoundTrip() {
        val doc1 = Doc()
        val arr1 = doc1.getArray("root")
        val parent = YXmlElement("ul")
        arr1.push(listOf(parent))

        val child1 = YXmlElement("li")
        val child2 = YXmlElement("li")
        parent.insert(0, listOf(child1, child2))
        child1.setAttribute("class", "first")
        child2.setAttribute("class", "second")

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val arr2 = doc2.getArray("root")
        val parent2 = arr2.get(0) as YXmlElement
        // Note: tag is not preserved during encoding/decoding yet (known limitation)
        assertEquals(2, parent2.length)

        val c1 = parent2.get(0) as YXmlElement
        val c2 = parent2.get(1) as YXmlElement
        assertEquals("first", c1.getAttribute("class"))
        assertEquals("second", c2.getAttribute("class"))
    }

    @Test
    fun testXmlTextSyncRoundTrip() {
        val doc1 = Doc()
        val arr1 = doc1.getArray("root")
        val xmlText = YXmlText()
        arr1.push(listOf(xmlText))
        xmlText.insert(0, "hello world")

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val arr2 = doc2.getArray("root")
        val xmlText2 = arr2.get(0)
        assertIs<YXmlText>(xmlText2)
        assertEquals("hello world", xmlText2.toString())
    }

    @Test
    fun testXmlHookSyncRoundTrip() {
        val doc1 = Doc()
        val arr1 = doc1.getArray("root")
        val hook = YXmlHook("myHook")
        arr1.push(listOf(hook))
        hook.set("key1", "value1")
        hook.set("key2", 42)

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val arr2 = doc2.getArray("root")
        val hook2 = arr2.get(0)
        assertIs<YXmlHook>(hook2)
        // Note: hookName is not preserved during encoding/decoding yet (known limitation)
        assertEquals("value1", hook2.get("key1"))
        assertEquals(42, hook2.get("key2"))
    }

    @Test
    fun testXmlFragmentSyncRoundTrip() {
        val doc1 = Doc()
        val arr1 = doc1.getArray("root")
        val frag = YXmlFragment()
        arr1.push(listOf(frag))

        val child1 = YXmlElement("div")
        val child2 = YXmlElement("span")
        frag.insert(0, listOf(child1, child2))
        child1.setAttribute("id", "first")

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val arr2 = doc2.getArray("root")
        val frag2 = arr2.get(0)
        assertIs<YXmlFragment>(frag2)
        assertEquals(2, frag2.length)

        val c1 = frag2.get(0) as YXmlElement
        assertEquals("first", c1.getAttribute("id"))
    }

    // ==================== Two-Doc Concurrent Sync ====================

    @Test
    fun testXmlElementConcurrentAttributeSync() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("root")
        val elem1 = YXmlElement("div")
        arr1.push(listOf(elem1))

        sync(doc1, doc2)

        val arr2 = doc2.getArray("root")
        val elem2 = arr2.get(0) as YXmlElement

        // Concurrent attribute modifications
        elem1.setAttribute("class", "from-doc1")
        elem2.setAttribute("id", "from-doc2")

        sync(doc1, doc2)

        // Both docs should converge
        assertEquals(elem1.getAttribute("class"), elem2.getAttribute("class"))
        assertEquals(elem1.getAttribute("id"), elem2.getAttribute("id"))
        assertEquals("from-doc1", elem1.getAttribute("class"))
        assertEquals("from-doc2", elem1.getAttribute("id"))
    }

    @Test
    fun testXmlElementConcurrentAttributeConflict() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("root")
        val elem1 = YXmlElement("div")
        arr1.push(listOf(elem1))

        sync(doc1, doc2)

        val arr2 = doc2.getArray("root")
        val elem2 = arr2.get(0) as YXmlElement

        // Both write to same attribute
        elem1.setAttribute("class", "value1")
        elem2.setAttribute("class", "value2")

        sync(doc1, doc2)

        // Should converge (last-writer-wins by client ID)
        assertEquals(elem1.getAttribute("class"), elem2.getAttribute("class"))
    }

    @Test
    fun testXmlTextConcurrentSync() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("root")
        val xmlText1 = YXmlText()
        arr1.push(listOf(xmlText1))
        xmlText1.insert(0, "hello")

        sync(doc1, doc2)

        val arr2 = doc2.getArray("root")
        val xmlText2 = arr2.get(0) as YXmlText
        assertEquals("hello", xmlText2.toString())

        // Concurrent edits
        xmlText1.insert(5, " world")
        xmlText2.insert(0, "Oh! ")

        sync(doc1, doc2)

        assertEquals(xmlText1.toString(), xmlText2.toString())
    }

    @Test
    fun testXmlHookConcurrentSync() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("root")
        val hook1 = YXmlHook("sharedHook")
        arr1.push(listOf(hook1))

        sync(doc1, doc2)

        val arr2 = doc2.getArray("root")
        val hook2 = arr2.get(0) as YXmlHook

        // Concurrent writes to different keys
        hook1.set("a", "from-doc1")
        hook2.set("b", "from-doc2")

        sync(doc1, doc2)

        assertEquals("from-doc1", hook1.get("a"))
        assertEquals("from-doc2", hook1.get("b"))
        assertEquals("from-doc1", hook2.get("a"))
        assertEquals("from-doc2", hook2.get("b"))
    }

    // ==================== Nested XML Structures ====================

    @Test
    fun testNestedXmlElements() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val div = YXmlElement("div")
        arr.push(listOf(div))

        val ul = YXmlElement("ul")
        div.insert(0, listOf(ul))

        val li1 = YXmlElement("li")
        val li2 = YXmlElement("li")
        ul.insert(0, listOf(li1, li2))

        assertEquals(1, div.length)
        assertEquals(2, ul.length)
    }

    @Test
    fun testNestedXmlElementsWithTextAndAttributes() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val div = YXmlElement("div")
        arr.push(listOf(div))
        div.setAttribute("class", "container")

        val p = YXmlElement("p")
        div.insert(0, listOf(p))

        val text = YXmlText()
        p.insert(0, listOf(text))
        text.insert(0, "Hello, World!")

        // Verify structure
        assertEquals("container", div.getAttribute("class"))
        assertEquals(1, div.length)

        val retrievedP = div.get(0) as YXmlElement
        assertEquals("p", retrievedP.tag)
        assertEquals(1, retrievedP.length)

        val retrievedText = retrievedP.get(0) as YXmlText
        assertEquals("Hello, World!", retrievedText.toString())
    }

    @Test
    fun testNestedXmlElementsSyncRoundTrip() {
        val doc1 = Doc()
        val arr1 = doc1.getArray("root")

        val html = YXmlElement("html")
        arr1.push(listOf(html))

        val body = YXmlElement("body")
        html.insert(0, listOf(body))
        body.setAttribute("class", "main")

        val p = YXmlElement("p")
        body.insert(0, listOf(p))

        val text = YXmlText()
        p.insert(0, listOf(text))
        text.insert(0, "content")

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val arr2 = doc2.getArray("root")
        val html2 = arr2.get(0) as YXmlElement
        // Note: tag is not preserved during encoding/decoding yet (known limitation)

        val body2 = html2.get(0) as YXmlElement
        assertEquals("main", body2.getAttribute("class"))

        val p2 = body2.get(0) as YXmlElement

        val text2 = p2.get(0) as YXmlText
        assertEquals("content", text2.toString())
    }

    // ==================== Edge Cases ====================

    @Test
    fun testXmlElementRemoveNonexistentAttribute() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("div")
        arr.push(listOf(elem))

        // Removing a nonexistent attribute should not throw
        elem.removeAttribute("nonexistent")
        assertNull(elem.getAttribute("nonexistent"))
    }

    @Test
    fun testXmlElementSetAttributeAfterRemove() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val elem = YXmlElement("div")
        arr.push(listOf(elem))

        elem.setAttribute("class", "first")
        elem.removeAttribute("class")
        elem.setAttribute("class", "second")
        assertEquals("second", elem.getAttribute("class"))
    }

    @Test
    fun testXmlFragmentInsertAtVariousPositions() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val frag = YXmlFragment()
        arr.push(listOf(frag))

        val first = YXmlElement("first")
        frag.insert(0, listOf(first)) // insert at beginning

        val third = YXmlElement("third")
        frag.insert(1, listOf(third)) // insert at end

        val second = YXmlElement("second")
        frag.insert(1, listOf(second)) // insert in middle

        assertEquals(3, frag.length)
        assertEquals("first", (frag.get(0) as YXmlElement).tag)
        assertEquals("second", (frag.get(1) as YXmlElement).tag)
        assertEquals("third", (frag.get(2) as YXmlElement).tag)
    }

    @Test
    fun testXmlHookToJSON() {
        val doc = Doc()
        val arr = doc.getArray("root")
        val hook = YXmlHook("testHook")
        arr.push(listOf(hook))

        hook.set("key", "value")
        hook.set("num", 42)

        val json = hook.toJSON()
        assertIs<Map<*, *>>(json)
        assertEquals("value", json["key"])
        assertEquals(42, json["num"])
    }

    @Test
    fun testMultipleXmlTypesInSameDoc() {
        val doc = Doc()
        val arr = doc.getArray("root")

        val elem = YXmlElement("div")
        val text = YXmlText()
        val hook = YXmlHook("hook1")
        val frag = YXmlFragment()

        arr.push(listOf(elem, text, hook, frag))

        elem.setAttribute("class", "box")
        text.insert(0, "hello")
        hook.set("config", "value")
        val child = YXmlElement("inner")
        frag.insert(0, listOf(child))

        assertEquals(4, arr.length)
        assertIs<YXmlElement>(arr.get(0))
        assertIs<YXmlText>(arr.get(1))
        assertIs<YXmlHook>(arr.get(2))
        assertIs<YXmlFragment>(arr.get(3))

        assertEquals("box", (arr.get(0) as YXmlElement).getAttribute("class"))
        assertEquals("hello", (arr.get(1) as YXmlText).toString())
        assertEquals("value", (arr.get(2) as YXmlHook).get("config"))
        assertEquals(1, (arr.get(3) as YXmlFragment).length)
    }

    @Test
    fun testMultipleXmlTypesSyncRoundTrip() {
        val doc1 = Doc()
        val arr1 = doc1.getArray("root")

        val elem = YXmlElement("section")
        val text = YXmlText()
        val hook = YXmlHook("renderer")

        arr1.push(listOf(elem, text, hook))

        elem.setAttribute("role", "main")
        text.insert(0, "content")
        hook.set("engine", "custom")

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val arr2 = doc2.getArray("root")
        assertEquals(3, arr2.length)

        val elem2 = arr2.get(0) as YXmlElement
        // Note: tag is not preserved during encoding/decoding yet (known limitation)
        assertEquals("main", elem2.getAttribute("role"))

        val text2 = arr2.get(1) as YXmlText
        assertEquals("content", text2.toString())

        val hook2 = arr2.get(2) as YXmlHook
        // Note: hookName is not preserved during encoding/decoding yet (known limitation)
        assertEquals("custom", hook2.get("engine"))
    }

    // ==================== Not Integrated Error Cases ====================

    @Test
    fun testXmlFragmentNotIntegratedThrows() {
        val frag = YXmlFragment()
        assertFailsWith<IllegalStateException> {
            frag.insert(0, listOf(YXmlElement("div")))
        }
    }

    @Test
    fun testXmlFragmentDeleteNotIntegratedThrows() {
        val frag = YXmlFragment()
        assertFailsWith<IllegalStateException> {
            frag.delete(0, 1)
        }
    }

    @Test
    fun testXmlElementSetAttributeNotIntegratedThrows() {
        val elem = YXmlElement("div")
        assertFailsWith<IllegalStateException> {
            elem.setAttribute("class", "box")
        }
    }

    @Test
    fun testXmlElementRemoveAttributeNotIntegratedThrows() {
        val elem = YXmlElement("div")
        assertFailsWith<IllegalStateException> {
            elem.removeAttribute("class")
        }
    }

    @Test
    fun testXmlHookSetNotIntegratedThrows() {
        val hook = YXmlHook("myHook")
        assertFailsWith<IllegalStateException> {
            hook.set("key", "value")
        }
    }

    @Test
    fun testXmlHookDeleteNotIntegratedThrows() {
        val hook = YXmlHook("myHook")
        assertFailsWith<IllegalStateException> {
            hook.delete("key")
        }
    }
}
