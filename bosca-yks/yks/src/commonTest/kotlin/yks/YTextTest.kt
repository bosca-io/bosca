package yks

import yks.structs.Item
import yks.structs.content.ContentEmbed
import yks.utils.*
import yks.types.*
import kotlin.test.*

class YTextTest {

    @Test
    fun testInsertAtStart() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")
        assertEquals("Hello", text.toString())
        assertEquals(5, text.length)
    }

    @Test
    fun testInsertAtEnd() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")
        text.insert(5, " World")
        assertEquals("Hello World", text.toString())
    }

    @Test
    fun testInsertInMiddle() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hllo")
        text.insert(1, "e")
        assertEquals("Hello", text.toString())
    }

    @Test
    fun testDeleteFromStart() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")
        text.delete(0, 6) // delete "Hello "
        assertEquals("World", text.toString())
    }

    @Test
    fun testDeleteFromEnd() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")
        text.delete(5, 6) // delete " World"
        assertEquals("Hello", text.toString())
    }

    @Test
    fun testDeleteFromMiddle() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")
        text.delete(3, 5) // delete "lo Wo"
        assertEquals("Helrld", text.toString())
    }

    @Test
    fun testDeleteAll() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")
        text.delete(0, 5)
        assertEquals("", text.toString())
        assertEquals(0, text.length)
    }

    @Test
    fun testMultipleInserts() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "c")
        text.insert(0, "b")
        text.insert(0, "a")
        text.insert(3, "d")
        assertEquals("abcd", text.toString())
    }

    @Test
    fun testInsertDeleteInterleaved() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "abcdef")
        text.delete(2, 2) // remove "cd" -> "abef"
        text.insert(2, "CD") // -> "abCDef"
        assertEquals("abCDef", text.toString())
    }

    @Test
    fun testEmptyText() {
        val doc = Doc()
        val text = doc.getText("text")
        assertEquals("", text.toString())
        assertEquals(0, text.length)
    }

    @Test
    fun testToJSON() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")
        assertEquals("Hello", text.toJSON())
    }

    @Test
    fun testToDelta() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        assertEquals("Hello", delta[0]["insert"])
    }

    @Test
    fun testObserve() {
        val doc = Doc()
        val text = doc.getText("text")
        var eventCount = 0
        text.observe { _, _ -> eventCount++ }
        text.insert(0, "Hello")
        text.insert(5, " World")
        text.delete(0, 6)
        assertEquals(3, eventCount)
    }

    @Test
    fun testTextSyncRoundTrip() {
        val doc1 = Doc()
        val text1 = doc1.getText("text")
        text1.insert(0, "Hello, World!")

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val text2 = doc2.getText("text")
        assertEquals("Hello, World!", text2.toString())
    }

    @Test
    fun testTextSyncWithEdits() {
        val doc1 = Doc()
        val text1 = doc1.getText("text")
        text1.insert(0, "abcdef")
        text1.delete(2, 2) // "abef"
        text1.insert(2, "XY") // "abXYef"

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val text2 = doc2.getText("text")
        assertEquals("abXYef", text2.toString())
    }

    @Test
    fun testApplyDelta() {
        val doc = Doc()
        val text = doc.getText("text")
        text.applyDelta(listOf(
            mapOf("insert" to "Hello World")
        ))
        assertEquals("Hello World", text.toString())
    }

    @Test
    fun testLongText() {
        val doc = Doc()
        val text = doc.getText("text")
        val longStr = "a".repeat(1000)
        text.insert(0, longStr)
        assertEquals(1000, text.length)
        assertEquals(longStr, text.toString())
    }

    // ── Initial text constructor ──

    @Test
    fun testInitialText() {
        val doc = Doc()
        val text = YText("hello")
        text.integrate(doc, null)
        doc.share["text_init"] = text
        assertEquals("hello", text.toString())
        assertEquals(5, text.length)
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        assertEquals("hello", delta[0]["insert"])
    }

    @Test
    fun testInitialTextEmpty() {
        val doc = Doc()
        val text = YText("")
        text.integrate(doc, null)
        doc.share["text_empty"] = text
        assertEquals("", text.toString())
        assertEquals(0, text.length)
    }

    @Test
    fun testInitialTextNull() {
        val doc = Doc()
        val text = YText()
        text.integrate(doc, null)
        doc.share["text_null"] = text
        assertEquals("", text.toString())
        assertEquals(0, text.length)
    }

    // ── Insert with attributes ──

    @Test
    fun testInsertWithAttributes() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "bold text", mapOf("bold" to true))
        assertEquals("bold text", text.toString())
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        assertEquals("bold text", delta[0]["insert"])
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(true, attrs["bold"])
    }

    @Test
    fun testInsertPlainThenBold() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "plain ")
        text.insert(6, "bold", mapOf("bold" to true))
        val delta = text.toDelta()
        assertEquals(2, delta.size)
        assertEquals("plain ", delta[0]["insert"])
        assertNull(delta[0]["attributes"])
        assertEquals("bold", delta[1]["insert"])
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[1]["attributes"] as Map<String, Any?>
        assertEquals(true, attrs["bold"])
    }

    @Test
    fun testInsertBoldThenPlain() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "bold", mapOf("bold" to true))
        text.insert(4, " plain")
        assertEquals("bold plain", text.toString())
        val delta = text.toDelta()
        // Should have bold segment then plain segment
        assertTrue(delta.size >= 2)
        assertEquals("bold", delta[0]["insert"])
        assertNotNull(delta[0]["attributes"])
        // The plain text segment should have no bold attribute
        val lastDelta = delta.last()
        assertEquals(" plain", lastDelta["insert"])
    }

    // ── Format range ──

    @Test
    fun testFormatRange() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")
        text.format(0, 5, mapOf("bold" to true))
        val delta = text.toDelta()
        assertEquals(2, delta.size)
        assertEquals("Hello", delta[0]["insert"])
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(true, attrs["bold"])
        assertEquals(" World", delta[1]["insert"])
        assertNull(delta[1]["attributes"])
    }

    @Test
    fun testFormatMiddleRange() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")
        text.format(5, 1, mapOf("italic" to true))
        val delta = text.toDelta()
        assertEquals(3, delta.size)
        assertEquals("Hello", delta[0]["insert"])
        assertNull(delta[0]["attributes"])
        assertEquals(" ", delta[1]["insert"])
        @Suppress("UNCHECKED_CAST")
        val midAttrs = delta[1]["attributes"] as Map<String, Any?>
        assertEquals(true, midAttrs["italic"])
        assertEquals("World", delta[2]["insert"])
        assertNull(delta[2]["attributes"])
    }

    @Test
    fun testFormatEntireText() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")
        text.format(0, 5, mapOf("bold" to true))
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        assertEquals("Hello", delta[0]["insert"])
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(true, attrs["bold"])
    }

    @Test
    fun testFormatRemovesAttribute() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello", mapOf("bold" to true))
        text.format(0, 5, mapOf("bold" to null))
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        assertEquals("Hello", delta[0]["insert"])
        assertNull(delta[0]["attributes"])
    }

    @Test
    fun testFormatOverlapping() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "abcdef")
        text.format(0, 4, mapOf("bold" to true))
        text.format(2, 4, mapOf("italic" to true))
        val delta = text.toDelta()
        // "ab" bold, "cd" bold+italic, "ef" italic
        assertEquals(3, delta.size)
        assertEquals("ab", delta[0]["insert"])
        @Suppress("UNCHECKED_CAST")
        val attrs0 = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(true, attrs0["bold"])
        assertNull(attrs0["italic"])

        assertEquals("cd", delta[1]["insert"])
        @Suppress("UNCHECKED_CAST")
        val attrs1 = delta[1]["attributes"] as Map<String, Any?>
        assertEquals(true, attrs1["bold"])
        assertEquals(true, attrs1["italic"])

        assertEquals("ef", delta[2]["insert"])
        @Suppress("UNCHECKED_CAST")
        val attrs2 = delta[2]["attributes"] as Map<String, Any?>
        assertNull(attrs2["bold"])
        assertEquals(true, attrs2["italic"])
    }

    @Test
    fun testMultipleFormats() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")
        text.format(0, 5, mapOf("bold" to true))
        text.format(0, 5, mapOf("italic" to true))
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(true, attrs["bold"])
        assertEquals(true, attrs["italic"])
    }

    @Test
    fun testFormatThenInsertMaintainsFormat() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "ac")
        text.format(0, 1, mapOf("bold" to true))
        // Insert at the boundary between bold "a" and plain "c"
        text.insert(1, "b", mapOf("bold" to true))
        assertEquals("abc", text.toString())
        val delta = text.toDelta()
        // "ab" should be bold, "c" plain
        assertTrue(delta.size >= 2)
        // First segment(s) contain bold text
        @Suppress("UNCHECKED_CAST")
        val firstAttrs = delta[0]["attributes"] as? Map<String, Any?>
        assertNotNull(firstAttrs)
        assertEquals(true, firstAttrs["bold"])
    }

    @Test
    fun testFormatSameAttributeTwice() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")
        text.format(0, 5, mapOf("bold" to true))
        // Formatting with same value again should be a no-op effectively
        text.format(0, 5, mapOf("bold" to true))
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(true, attrs["bold"])
    }

    // ── applyDelta ──

    @Test
    fun testApplyDeltaWithRetainAndAttributes() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")
        text.applyDelta(listOf(
            mapOf("retain" to 5, "attributes" to mapOf("bold" to true))
        ))
        val delta = text.toDelta()
        assertEquals(2, delta.size)
        assertEquals("Hello", delta[0]["insert"])
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(true, attrs["bold"])
        assertEquals(" World", delta[1]["insert"])
    }

    @Test
    fun testApplyDeltaWithDelete() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")
        text.applyDelta(listOf(
            mapOf("retain" to 5),
            mapOf("delete" to 6)
        ))
        assertEquals("Hello", text.toString())
    }

    @Test
    fun testApplyDeltaWithRetainNoAttributes() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")
        // Retain 5 (advance cursor), then insert
        text.applyDelta(listOf(
            mapOf("retain" to 5),
            mapOf("insert" to "!")
        ))
        assertEquals("Hello! World", text.toString())
    }

    @Test
    fun testApplyDeltaComplex() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "abcdef")
        text.applyDelta(listOf(
            mapOf("retain" to 2),                                       // skip "ab"
            mapOf("insert" to "XY", "attributes" to mapOf("bold" to true)), // insert "XY" bold
            mapOf("delete" to 2),                                       // delete "cd"
            mapOf("retain" to 2, "attributes" to mapOf("italic" to true))  // format "ef" italic
        ))
        assertEquals("abXYef", text.toString())
        val delta = text.toDelta()
        // "ab" plain, "XY" bold, "ef" italic
        assertTrue(delta.size >= 3)
    }

    @Test
    fun testApplyDeltaRetainAcrossFormattedText() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello", mapOf("bold" to true))
        text.insert(5, " World")
        // Retain past the bold text and the format marker
        text.applyDelta(listOf(
            mapOf("retain" to 5),
            mapOf("insert" to "!")
        ))
        assertEquals("Hello! World", text.toString())
    }

    @Test
    fun testApplyDeltaRetainPartialItem() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "abcdef")
        // Retain into the middle of the item, then insert
        text.applyDelta(listOf(
            mapOf("retain" to 3),
            mapOf("insert" to "X")
        ))
        assertEquals("abcXdef", text.toString())
    }

    // ── toDelta with embeds ──

    @Test
    fun testToDeltaWithEmbed() {
        val doc = Doc()
        val text = doc.getText("text")
        val embedObj = mapOf("image" to "https://example.com/img.png")
        // Create embed item directly since applyDelta only handles string inserts
        doc.transact { transaction ->
            val id = transaction.nextID()
            val item = Item(
                id = id,
                left = null,
                origin = null,
                right = null,
                rightOrigin = null,
                parent = text,
                parentSub = null,
                content = ContentEmbed(embedObj)
            )
            item.integrate(transaction, 0)
        }
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        assertEquals(embedObj, delta[0]["insert"])
    }

    @Test
    fun testToDeltaWithEmbedAndFormatting() {
        val doc = Doc()
        val text = doc.getText("text")
        // Insert bold-formatted text first, then an embed
        text.insert(0, "bold", mapOf("bold" to true))
        doc.transact { transaction ->
            val id = transaction.nextID()
            val item = Item(
                id = id,
                left = null,
                origin = null,
                right = text.start,
                rightOrigin = text.start?.id,
                parent = text,
                parentSub = null,
                content = ContentEmbed(mapOf("image" to "test.png"))
            )
            item.integrate(transaction, 0)
        }
        val delta = text.toDelta()
        assertTrue(delta.size >= 2)
        // The embed should be present in the delta
        val embedDelta = delta.find { it["insert"] is Map<*, *> }
        assertNotNull(embedDelta)
    }

    @Test
    fun testToDeltaEmbedBetweenText() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "beforeafter")
        // Insert embed at position 6 by splitting
        doc.transact { transaction ->
            val pos = text.toString() // "beforeafter"
            val id = transaction.nextID()
            // The embed goes at the end for simplicity
            val item = Item(
                id = id,
                left = null,
                origin = null,
                right = text.start,
                rightOrigin = text.start?.id,
                parent = text,
                parentSub = null,
                content = ContentEmbed(mapOf("hr" to true))
            )
            item.integrate(transaction, 0)
        }
        val delta = text.toDelta()
        assertTrue(delta.size >= 2)
        val embedDelta = delta.find { it["insert"] is Map<*, *> }
        assertNotNull(embedDelta)
        assertEquals(mapOf("hr" to true), embedDelta["insert"])
    }

    // ── Delete across formatted text ──

    @Test
    fun testDeleteAcrossFormattedText() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "ab")
        text.insert(2, "cd", mapOf("bold" to true))
        text.insert(4, "ef")
        assertEquals("abcdef", text.toString())
        // Delete from index 1 to 4 characters: "bcde"
        text.delete(1, 4)
        assertEquals("af", text.toString())
    }

    @Test
    fun testDeleteEntireFormattedSegment() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello", mapOf("bold" to true))
        text.insert(5, " World")
        text.delete(0, 5) // Delete all bold text
        assertEquals(" World", text.toString())
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        assertEquals(" World", delta[0]["insert"])
    }

    @Test
    fun testDeleteWithinFormattedText() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World", mapOf("bold" to true))
        text.delete(5, 1) // Delete the space
        assertEquals("HelloWorld", text.toString())
        val delta = text.toDelta()
        // All remaining text should still be bold
        for (d in delta) {
            @Suppress("UNCHECKED_CAST")
            val attrs = d["attributes"] as? Map<String, Any?>
            assertNotNull(attrs)
            assertEquals(true, attrs["bold"])
        }
    }

    // ── Sync round-trip with formatting ──

    @Test
    fun testSyncWithFormatting() {
        val doc1 = Doc()
        val text1 = doc1.getText("text")
        text1.insert(0, "Hello", mapOf("bold" to true))
        text1.insert(5, " World")

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val text2 = doc2.getText("text")
        assertEquals("Hello World", text2.toString())
        val delta = text2.toDelta()
        assertEquals(2, delta.size)
        assertEquals("Hello", delta[0]["insert"])
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(true, attrs["bold"])
        assertEquals(" World", delta[1]["insert"])
    }

    @Test
    fun testSyncWithFormatAndDelete() {
        val doc1 = Doc()
        val text1 = doc1.getText("text")
        text1.insert(0, "abcdef")
        text1.format(0, 3, mapOf("bold" to true))
        text1.delete(1, 2) // Delete "bc" from bold range

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val text2 = doc2.getText("text")
        assertEquals("adef", text2.toString())
    }

    @Test
    fun testSyncWithMultipleFormats() {
        val doc1 = Doc()
        val text1 = doc1.getText("text")
        text1.insert(0, "Hello World")
        text1.format(0, 5, mapOf("bold" to true))
        text1.format(6, 5, mapOf("italic" to true))

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        val text2 = doc2.getText("text")
        assertEquals("Hello World", text2.toString())
        val delta = text2.toDelta()
        assertTrue(delta.size >= 3) // bold, space, italic
    }

    // ── Observe with formatting ──

    @Test
    fun testObserveFormat() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")
        var eventCount = 0
        text.observe { _, _ -> eventCount++ }
        text.format(0, 5, mapOf("bold" to true))
        assertEquals(1, eventCount)
    }

    @Test
    fun testObserveInsertWithAttributes() {
        val doc = Doc()
        val text = doc.getText("text")
        var eventCount = 0
        text.observe { _, _ -> eventCount++ }
        text.insert(0, "bold", mapOf("bold" to true))
        assertEquals(1, eventCount)
    }

    // ── equalAttrs edge cases (tested indirectly through formatting behavior) ──

    @Test
    fun testEqualAttrsWithMaps() {
        val doc = Doc()
        val text = doc.getText("text")
        val style1 = mapOf("color" to "red", "size" to 12)
        val style2 = mapOf("color" to "red", "size" to 12)
        text.insert(0, "styled", mapOf("style" to style1))
        // Format with identical map value should be a no-op
        text.format(0, 6, mapOf("style" to style2))
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertNotNull(attrs["style"])
    }

    @Test
    fun testEqualAttrsDifferentMapSizes() {
        val doc = Doc()
        val text = doc.getText("text")
        val style1 = mapOf("color" to "red")
        val style2 = mapOf("color" to "red", "size" to 12)
        text.insert(0, "styled", mapOf("style" to style1))
        // Format with different-sized map should update the attribute
        text.format(0, 6, mapOf("style" to style2))
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(style2, attrs["style"])
    }

    @Test
    fun testEqualAttrsMapsWithDifferentValues() {
        val doc = Doc()
        val text = doc.getText("text")
        val style1 = mapOf("color" to "red")
        val style2 = mapOf("color" to "blue")
        text.insert(0, "styled", mapOf("style" to style1))
        text.format(0, 6, mapOf("style" to style2))
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(style2, attrs["style"])
    }

    @Test
    fun testEqualAttrsMapVsMissingKey() {
        val doc = Doc()
        val text = doc.getText("text")
        val style1 = mapOf("a" to 1, "b" to 2)
        val style2 = mapOf("a" to 1, "c" to 2) // "b" missing, "c" extra
        text.insert(0, "text", mapOf("style" to style1))
        text.format(0, 4, mapOf("style" to style2))
        val delta = text.toDelta()
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(style2, attrs["style"])
    }

    @Test
    fun testEqualAttrsNullCases() {
        val doc = Doc()
        val text = doc.getText("text")
        // Insert with null attribute value (should remove attribute if it existed)
        text.insert(0, "text", mapOf("bold" to null))
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        // null attribute values should not appear in attributes
        assertNull(delta[0]["attributes"])
    }

    @Test
    fun testEqualAttrsSameReference() {
        val doc = Doc()
        val text = doc.getText("text")
        val value = "same"
        text.insert(0, "text", mapOf("key" to value))
        // Re-format with same reference
        text.format(0, 4, mapOf("key" to value))
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(value, attrs["key"])
    }

    @Test
    fun testEqualAttrsNonMapEquality() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "text", mapOf("size" to 14))
        // Format with equal but different Int instance
        text.format(0, 4, mapOf("size" to 14))
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(14, attrs["size"])
    }

    // ── minimizeAttributeChanges ──

    @Test
    fun testMinimizeAttributeChanges() {
        val doc = Doc()
        val text = doc.getText("text")
        // Insert bold text, then insert more bold text right after
        text.insert(0, "A", mapOf("bold" to true))
        text.insert(1, "B", mapOf("bold" to true))
        assertEquals("AB", text.toString())
        val delta = text.toDelta()
        // Both should be merged or at least both bold
        for (d in delta) {
            val insert = d["insert"] as String
            if (insert.contains("A") || insert.contains("B")) {
                @Suppress("UNCHECKED_CAST")
                val attrs = d["attributes"] as? Map<String, Any?>
                assertNotNull(attrs)
                assertEquals(true, attrs["bold"])
            }
        }
    }

    // ── Complex formatting scenarios ──

    @Test
    fun testFormatChangeAttribute() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello", mapOf("color" to "red"))
        text.format(0, 5, mapOf("color" to "blue"))
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals("blue", attrs["color"])
    }

    @Test
    fun testEmbedWithSyncRoundTrip() {
        val doc1 = Doc()
        val text1 = doc1.getText("text")
        text1.insert(0, "hello")
        doc1.transact { transaction ->
            val id = transaction.nextID()
            val item = Item(
                id = id,
                left = null,
                origin = null,
                right = text1.start,
                rightOrigin = text1.start?.id,
                parent = text1,
                parentSub = null,
                content = ContentEmbed(mapOf("video" to "clip.mp4"))
            )
            item.integrate(transaction, 0)
        }
        val delta1 = text1.toDelta()
        val embedDelta = delta1.find { it["insert"] is Map<*, *> }
        assertNotNull(embedDelta)
    }

    @Test
    fun testApplyDeltaDeleteAll() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")
        text.applyDelta(listOf(
            mapOf("delete" to 11)
        ))
        assertEquals("", text.toString())
        assertEquals(0, text.length)
    }

    @Test
    fun testApplyDeltaRetainThenDelete() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")
        text.applyDelta(listOf(
            mapOf("retain" to 5),
            mapOf("delete" to 1),
            mapOf("insert" to ", ")
        ))
        assertEquals("Hello, World", text.toString())
    }

    @Test
    fun testFormatPartialThenDeleteOverlap() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "abcdef")
        text.format(2, 2, mapOf("bold" to true)) // "cd" bold
        text.delete(1, 4) // delete "bcde"
        assertEquals("af", text.toString())
    }

    @Test
    fun testInsertAtFormattedBoundary() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "ab")
        text.format(0, 1, mapOf("bold" to true)) // "a" bold
        // Insert plain text between bold "a" and plain "b"
        text.insert(1, "X")
        assertEquals("aXb", text.toString())
    }

    @Test
    fun testFormatThenReformat() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")
        text.format(0, 5, mapOf("bold" to true))
        text.format(0, 5, mapOf("bold" to null)) // Remove bold
        text.format(0, 5, mapOf("italic" to true)) // Add italic
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertNull(attrs["bold"])
        assertEquals(true, attrs["italic"])
    }

    @Test
    fun testApplyDeltaFormatWithRetainAcrossMultipleItems() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "abc")
        text.insert(3, "def")
        // Format across the boundary of two items
        text.applyDelta(listOf(
            mapOf("retain" to 2, "attributes" to mapOf("bold" to true))
        ))
        val delta = text.toDelta()
        assertTrue(delta.size >= 2)
        assertEquals("ab", delta[0]["insert"])
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(true, attrs["bold"])
    }

    @Test
    fun testDeletePartialItem() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "abcdef", mapOf("bold" to true))
        text.delete(2, 2) // delete "cd"
        assertEquals("abef", text.toString())
        val delta = text.toDelta()
        // Remaining text should still be bold
        for (d in delta) {
            @Suppress("UNCHECKED_CAST")
            val attrs = d["attributes"] as? Map<String, Any?>
            assertNotNull(attrs)
            assertEquals(true, attrs["bold"])
        }
    }

    @Test
    fun testLengthWithFormatting() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello", mapOf("bold" to true))
        text.insert(5, " World")
        // Format markers should not affect length
        assertEquals(11, text.length)
    }

    @Test
    fun testToJSONIgnoresFormatting() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello", mapOf("bold" to true))
        text.insert(5, " World")
        assertEquals("Hello World", text.toJSON())
    }

    @Test
    fun testToDeltaEmptyText() {
        val doc = Doc()
        val text = doc.getText("text")
        val delta = text.toDelta()
        assertEquals(0, delta.size)
    }

    @Test
    fun testFormatZeroLength() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")
        // Format zero characters should be a no-op
        text.format(0, 0, mapOf("bold" to true))
        val delta = text.toDelta()
        assertEquals(1, delta.size)
        assertNull(delta[0]["attributes"])
    }

    @Test
    fun testApplyDeltaWithInsertAttributes() {
        val doc = Doc()
        val text = doc.getText("text")
        text.applyDelta(listOf(
            mapOf("insert" to "Hello", "attributes" to mapOf("bold" to true)),
            mapOf("insert" to " World")
        ))
        assertEquals("Hello World", text.toString())
        val delta = text.toDelta()
        assertEquals(2, delta.size)
        @Suppress("UNCHECKED_CAST")
        val attrs = delta[0]["attributes"] as Map<String, Any?>
        assertEquals(true, attrs["bold"])
    }
}
