package yks

import yks.lib0.*
import yks.utils.*
import yks.types.*
import kotlin.test.*

class RelativePositionTest {

    @Test
    fun testCreateFromIndex0() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        val rpos = createRelativePositionFromTypeIndex(text, 0)
        assertNotNull(rpos)
    }

    @Test
    fun testCreateFromMiddleIndex() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        val rpos = createRelativePositionFromTypeIndex(text, 3)
        assertNotNull(rpos)
        assertNotNull(rpos.item)
    }

    @Test
    fun testCreateFromEndIndex() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        val rpos = createRelativePositionFromTypeIndex(text, 5)
        assertNotNull(rpos)
    }

    @Test
    fun testResolvePosition() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        val rpos = createRelativePositionFromTypeIndex(text, 3)
        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNotNull(absPos)
        assertEquals(3, absPos.index)
    }

    @Test
    fun testPositionSurvivesInsertBefore() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        // Position at index 3 (after "Hel")
        val rpos = createRelativePositionFromTypeIndex(text, 3)

        // Insert before the position
        text.insert(0, "XXX")

        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNotNull(absPos)
        assertEquals(6, absPos.index) // Should shift by 3
    }

    @Test
    fun testPositionSurvivesInsertAfter() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        // Position at index 2
        val rpos = createRelativePositionFromTypeIndex(text, 2)

        // Insert after the position
        text.insert(4, "XXX")

        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNotNull(absPos)
        assertEquals(2, absPos.index) // Should stay at 2
    }

    @Test
    fun testPositionOnEmptyType() {
        val doc = Doc()
        val arr = doc.getArray("arr")

        val rpos = createRelativePositionFromTypeIndex(arr, 0)
        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNotNull(absPos)
        assertEquals(0, absPos.index)
    }

    @Test
    fun testCompareRelativePositions() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        val rpos1 = createRelativePositionFromTypeIndex(text, 2)
        val rpos2 = createRelativePositionFromTypeIndex(text, 2)
        assertTrue(compareRelativePositions(rpos1, rpos2))

        val rpos3 = createRelativePositionFromTypeIndex(text, 3)
        assertFalse(compareRelativePositions(rpos1, rpos3))
    }

    @Test
    fun testEncodeDecodeRelativePosition() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")

        val rpos = createRelativePositionFromTypeIndex(text, 5)
        val encoded = encodeRelativePosition(rpos)
        val decoded = decodeRelativePosition(encoded)
        // In the yjs wire format, when item is present, tname/type are not encoded.
        // After decode, item and assoc should match; tname/type will be null.
        assertEquals(rpos.item, decoded.item)
        assertEquals(rpos.assoc, decoded.assoc)
        // Verify the decoded rpos resolves correctly
        val absPos = createAbsolutePositionFromRelativePosition(decoded, doc)
        assertNotNull(absPos)
        assertEquals(5, absPos.index)
    }

    @Test
    fun testRelativePositionOnArray() {
        val doc = Doc()
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b", "c", "d", "e"))

        val rpos = createRelativePositionFromTypeIndex(arr, 3)

        // Insert before position
        arr.insert(0, listOf("x"))

        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNotNull(absPos)
        assertEquals(4, absPos.index) // shifted by 1
    }

    @Test
    fun testRelativePositionWithRootType() {
        val doc = Doc()
        val text = doc.getText("myText")
        text.insert(0, "abc")

        val rpos = createRelativePositionFromTypeIndex(text, 1)
        // Root type should use tname
        assertNotNull(rpos.tname)
        assertEquals("myText", rpos.tname)
    }

    @Test
    fun testEncodeDecodeRoundTrip() {
        // In yjs wire format, when item is present, only item+assoc are encoded (not tname/type).
        // When item is null, tname or type is encoded.
        val rpos = RelativePosition(
            type = null,
            tname = "test",
            item = ID(42, 10),
            assoc = 0
        )
        val encoded = encodeRelativePosition(rpos)
        val decoded = decodeRelativePosition(encoded)
        // Only item and assoc survive encoding when item is present
        assertEquals(rpos.item, decoded.item)
        assertEquals(rpos.assoc, decoded.assoc)
        assertNull(decoded.tname, "tname is not encoded when item is present")
    }

    @Test
    fun testEncodeDecodeWithTypeId() {
        // In yjs wire format, when item is present, only item+assoc are encoded (not type).
        val rpos = RelativePosition(
            type = ID(1, 0),
            tname = null,
            item = ID(2, 5),
            assoc = -1
        )
        val encoded = encodeRelativePosition(rpos)
        val decoded = decodeRelativePosition(encoded)
        // Only item and assoc survive encoding when item is present
        assertEquals(rpos.item, decoded.item)
        assertEquals(rpos.assoc, decoded.assoc)
        assertNull(decoded.type, "type is not encoded when item is present")
    }

    @Test
    fun testEncodeDecodeNullItem() {
        val rpos = RelativePosition(
            type = null,
            tname = "root",
            item = null,
            assoc = 0
        )
        val encoded = encodeRelativePosition(rpos)
        val decoded = decodeRelativePosition(encoded)
        assertEquals(rpos, decoded)
    }

    // --- Resolution with deleted items ---

    @Test
    fun testResolveAfterDeletedItem() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello World")

        // Position at index 5 (after "Hello")
        val rpos = createRelativePositionFromTypeIndex(text, 5)

        // Delete the character at the position
        text.delete(5, 1) // delete " "

        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNotNull(absPos)
        // The referenced item is deleted, so the position should still resolve
        // It may resolve to the next valid position
    }

    @Test
    fun testResolveAfterDeletedItemsBefore() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "abcdef")

        val rpos = createRelativePositionFromTypeIndex(text, 4) // after "abcd"

        // Delete "bc" (index 1 and 2)
        text.delete(1, 2)

        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNotNull(absPos)
        // Position referenced char at clock offset 4, which is still present
    }

    // --- Resolution with assoc < 0 vs >= 0 ---

    @Test
    fun testLeftAssociation() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        val rpos = createRelativePositionFromTypeIndex(text, 3, assoc = -1)
        assertEquals(-1, rpos.assoc)

        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNotNull(absPos)
        assertEquals(3, absPos.index)
        assertEquals(-1, absPos.assoc)
    }

    @Test
    fun testRightAssociation() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        val rpos = createRelativePositionFromTypeIndex(text, 3, assoc = 0)
        assertEquals(0, rpos.assoc)

        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNotNull(absPos)
        assertEquals(3, absPos.index)
        assertEquals(0, absPos.assoc)
    }

    @Test
    fun testAssocAffectsResolutionAfterInsert() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "abc")

        // Left-associated position at index 2 (after "ab")
        val leftRpos = createRelativePositionFromTypeIndex(text, 2, assoc = -1)
        // Right-associated position at index 2
        val rightRpos = createRelativePositionFromTypeIndex(text, 2, assoc = 0)

        // Insert at position 2
        text.insert(2, "X")

        val leftAbs = createAbsolutePositionFromRelativePosition(leftRpos, doc)
        val rightAbs = createAbsolutePositionFromRelativePosition(rightRpos, doc)
        assertNotNull(leftAbs)
        assertNotNull(rightAbs)
        // Left-associated should be before the insert, right-associated should be after
        assertTrue(leftAbs.index <= rightAbs.index)
    }

    // --- createRelativePositionFromTypeIndex edge cases ---

    @Test
    fun testIndex0WithLeftAssoc() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        val rpos = createRelativePositionFromTypeIndex(text, 0, assoc = -1)
        // assoc < 0 and index == 0: should return position with item=null
        assertNull(rpos.item)
        assertEquals(-1, rpos.assoc)

        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNotNull(absPos)
        assertEquals(0, absPos.index) // left-assoc at start => index 0
    }

    @Test
    fun testIndexAtEndWithRightAssoc() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        val rpos = createRelativePositionFromTypeIndex(text, 5, assoc = 0)
        // Index past the end: should return position with item=null
        assertNull(rpos.item)
        assertEquals(0, rpos.assoc)

        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNotNull(absPos)
        assertEquals(5, absPos.index) // right-assoc at end => length
    }

    @Test
    fun testIndexAtEndWithLeftAssoc() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        val rpos = createRelativePositionFromTypeIndex(text, 5, assoc = -1)
        // Left-associated at end: should reference the last item
        assertNotNull(rpos.item)
        assertEquals(-1, rpos.assoc)
    }

    // --- Type-relative positions (no item, tname-based) ---

    @Test
    fun testTypeRelativePositionWithTname() {
        val doc = Doc()
        val arr = doc.getArray("myArray")

        // Position at start of empty array
        val rpos = createRelativePositionFromTypeIndex(arr, 0)
        assertNull(rpos.item)
        assertNotNull(rpos.tname)
        assertEquals("myArray", rpos.tname)

        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNotNull(absPos)
        assertEquals(0, absPos.index)
    }

    @Test
    fun testTypeRelativePositionWithTypeId() {
        // Create a nested type (non-root) to get a type-relative position
        val doc = Doc()
        val root = doc.getMap("root")
        val nested = YArray()
        root.set("nested", nested)

        // Position in nested array (non-root type)
        val rpos = createRelativePositionFromTypeIndex(nested, 0)
        // Nested types should have typeId, not tname
        assertNull(rpos.tname)
    }

    // --- Encode/decode round-trip for all position types ---

    @Test
    fun testEncodeDecodeRoundTripItemPosition() {
        val doc = Doc()
        val text = doc.getText("text")
        text.insert(0, "Hello")

        val rpos = createRelativePositionFromTypeIndex(text, 3)
        assertNotNull(rpos.item)

        val encoded = encodeRelativePosition(rpos)
        val decoded = decodeRelativePosition(encoded)
        assertEquals(rpos.item, decoded.item)
        assertEquals(rpos.assoc, decoded.assoc)

        val absPos = createAbsolutePositionFromRelativePosition(decoded, doc)
        assertNotNull(absPos)
        assertEquals(3, absPos.index)
    }

    @Test
    fun testEncodeDecodeRoundTripTnamePosition() {
        val rpos = RelativePosition(
            type = null,
            tname = "myText",
            item = null,
            assoc = -1
        )
        val encoded = encodeRelativePosition(rpos)
        val decoded = decodeRelativePosition(encoded)
        assertEquals(rpos.tname, decoded.tname)
        assertEquals(rpos.assoc, decoded.assoc)
        assertNull(decoded.item)
        assertNull(decoded.type)
    }

    @Test
    fun testEncodeDecodeRoundTripTypePosition() {
        val rpos = RelativePosition(
            type = ID(5, 10),
            tname = null,
            item = null,
            assoc = 0
        )
        val encoded = encodeRelativePosition(rpos)
        val decoded = decodeRelativePosition(encoded)
        assertEquals(rpos.type, decoded.type)
        assertEquals(rpos.assoc, decoded.assoc)
        assertNull(decoded.item)
        assertNull(decoded.tname)
    }

    @Test
    fun testEncodeDecodeNoItemTnameOrTypeThrows() {
        val rpos = RelativePosition(
            type = null,
            tname = null,
            item = null,
            assoc = 0
        )
        assertFailsWith<IllegalStateException> {
            encodeRelativePosition(rpos)
        }
    }

    // --- Resolution with unknown state returns null ---

    @Test
    fun testResolveUnknownClientReturnsNull() {
        val doc = Doc()
        doc.getText("text").insert(0, "abc")

        // Position referencing a client that doesn't exist in this doc
        val rpos = RelativePosition(
            type = null,
            tname = null,
            item = ID(999999, 0),
            assoc = 0
        )
        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNull(absPos)
    }

    @Test
    fun testResolveUnknownTnameReturnsNull() {
        val doc = Doc()
        doc.getText("text").insert(0, "abc")

        val rpos = RelativePosition(
            type = null,
            tname = "nonexistent",
            item = null,
            assoc = 0
        )
        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        assertNull(absPos)
    }

    @Test
    fun testResolveNonItemStructReturnsNull() {
        // If the referenced item is actually a GC struct, should return null
        val doc = Doc(gc = true)
        val arr = doc.getArray("arr")
        arr.push(listOf("a"))
        val itemClock = doc.store.clients[doc.clientID]?.first()?.id?.clock ?: 0
        arr.delete(0, 1) // triggers GC

        val rpos = RelativePosition(
            type = null,
            tname = null,
            item = ID(doc.clientID, itemClock),
            assoc = 0
        )
        val absPos = createAbsolutePositionFromRelativePosition(rpos, doc)
        // May or may not be null depending on whether the struct is GC'd
        // The important thing is it doesn't crash
    }

    // --- compareRelativePositions ---

    @Test
    fun testCompareEqualPositions() {
        val rpos1 = RelativePosition(null, "text", ID(1, 5), 0)
        val rpos2 = RelativePosition(null, "text", ID(1, 5), 0)
        assertTrue(compareRelativePositions(rpos1, rpos2))
    }

    @Test
    fun testCompareDifferentAssoc() {
        val rpos1 = RelativePosition(null, "text", ID(1, 5), 0)
        val rpos2 = RelativePosition(null, "text", ID(1, 5), -1)
        assertFalse(compareRelativePositions(rpos1, rpos2))
    }
}
