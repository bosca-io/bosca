@file:Suppress("USELESS_CAST")

package yks

import yks.structs.content.*
import yks.utils.UpdateEncoderV1
import yks.utils.UpdateDecoderV1
import yks.utils.Doc
import yks.types.*
import kotlin.test.*

class ContentTest {

    // ==================== ContentDeleted ====================

    @Test
    fun testContentDeletedGetLength() {
        val cd = ContentDeleted(5)
        assertEquals(5, cd.getLength())
    }

    @Test
    fun testContentDeletedGetLengthZero() {
        val cd = ContentDeleted(0)
        assertEquals(0, cd.getLength())
    }

    @Test
    fun testContentDeletedGetContent() {
        val cd = ContentDeleted(5)
        assertEquals(emptyList(), cd.getContent())
    }

    @Test
    fun testContentDeletedIsNotCountable() {
        val cd = ContentDeleted(3)
        assertFalse(cd.isCountable())
    }

    @Test
    fun testContentDeletedGetRef() {
        val cd = ContentDeleted(1)
        assertEquals(1, cd.getRef())
    }

    @Test
    fun testContentDeletedCopy() {
        val cd = ContentDeleted(7)
        val copy = cd.copy()
        assertTrue(copy is ContentDeleted)
        assertEquals(7, copy.getLength())
        // Verify independence: mutating copy does not affect original
        (copy as ContentDeleted).len = 10
        assertEquals(7, cd.getLength())
    }

    @Test
    fun testContentDeletedSplice() {
        val cd = ContentDeleted(10)
        val right = cd.splice(4)
        assertTrue(right is ContentDeleted)
        assertEquals(4, cd.getLength())
        assertEquals(6, right.getLength())
    }

    @Test
    fun testContentDeletedSpliceAtOne() {
        val cd = ContentDeleted(5)
        val right = cd.splice(1)
        assertEquals(1, cd.getLength())
        assertEquals(4, right.getLength())
    }

    @Test
    fun testContentDeletedMergeWithSameType() {
        val left = ContentDeleted(3)
        val right = ContentDeleted(5)
        assertTrue(left.mergeWith(right))
        assertEquals(8, left.getLength())
    }

    @Test
    fun testContentDeletedMergeWithDifferentType() {
        val left = ContentDeleted(3)
        val right = ContentString("abc")
        assertFalse(left.mergeWith(right))
        assertEquals(3, left.getLength())
    }

    @Test
    fun testContentDeletedWriteReadRoundTrip() {
        val original = ContentDeleted(42)
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentDeleted.read(decoder)
        assertEquals(42, restored.getLength())
    }

    @Test
    fun testContentDeletedWriteWithOffset() {
        val original = ContentDeleted(10)
        val encoder = UpdateEncoderV1()
        original.write(encoder, 3)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentDeleted.read(decoder)
        assertEquals(7, restored.getLength())
    }

    // ==================== ContentBinary ====================

    @Test
    fun testContentBinaryGetLength() {
        val cb = ContentBinary(byteArrayOf(1, 2, 3))
        assertEquals(1, cb.getLength())
    }

    @Test
    fun testContentBinaryGetContent() {
        val data = byteArrayOf(10, 20, 30)
        val cb = ContentBinary(data)
        val content = cb.getContent()
        assertEquals(1, content.size)
        assertContentEquals(data, content[0] as ByteArray)
    }

    @Test
    fun testContentBinaryIsCountable() {
        val cb = ContentBinary(byteArrayOf())
        assertTrue(cb.isCountable())
    }

    @Test
    fun testContentBinaryGetRef() {
        val cb = ContentBinary(byteArrayOf())
        assertEquals(3, cb.getRef())
    }

    @Test
    fun testContentBinaryCopy() {
        val data = byteArrayOf(1, 2, 3)
        val cb = ContentBinary(data)
        val copy = cb.copy()
        assertTrue(copy is ContentBinary)
        assertContentEquals(data, (copy as ContentBinary).data)
        // Verify deep copy: modifying copy's data should not affect original
        copy.data[0] = 99
        assertEquals(1, cb.data[0])
    }

    @Test
    fun testContentBinarySpliceThrows() {
        val cb = ContentBinary(byteArrayOf(1, 2, 3))
        assertFailsWith<UnsupportedOperationException> {
            cb.splice(0)
        }
    }

    @Test
    fun testContentBinaryMergeReturnsFalse() {
        val left = ContentBinary(byteArrayOf(1))
        val right = ContentBinary(byteArrayOf(2))
        assertFalse(left.mergeWith(right))
    }

    @Test
    fun testContentBinaryWriteReadRoundTrip() {
        val data = byteArrayOf(0, 127, -128, 1, -1)
        val original = ContentBinary(data)
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentBinary.read(decoder)
        assertContentEquals(data, restored.data)
    }

    @Test
    fun testContentBinaryWriteReadEmptyArray() {
        val original = ContentBinary(byteArrayOf())
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentBinary.read(decoder)
        assertContentEquals(byteArrayOf(), restored.data)
    }

    // ==================== ContentJSON ====================

    @Test
    fun testContentJSONGetLength() {
        val cj = ContentJSON(mutableListOf(1, "two", null))
        assertEquals(3, cj.getLength())
    }

    @Test
    fun testContentJSONGetLengthEmpty() {
        val cj = ContentJSON(mutableListOf())
        assertEquals(0, cj.getLength())
    }

    @Test
    fun testContentJSONGetContent() {
        val values = mutableListOf<Any?>(1, "hello", null)
        val cj = ContentJSON(values)
        assertEquals(values, cj.getContent())
    }

    @Test
    fun testContentJSONIsCountable() {
        val cj = ContentJSON(mutableListOf(1))
        assertTrue(cj.isCountable())
    }

    @Test
    fun testContentJSONGetRef() {
        val cj = ContentJSON(mutableListOf())
        assertEquals(2, cj.getRef())
    }

    @Test
    fun testContentJSONCopy() {
        val cj = ContentJSON(mutableListOf(1, 2, 3))
        val copy = cj.copy()
        assertTrue(copy is ContentJSON)
        assertEquals(cj.getContent(), copy.getContent())
        // Verify independence
        (copy as ContentJSON).values.add(4)
        assertEquals(3, cj.getLength())
    }

    @Test
    fun testContentJSONSplice() {
        val cj = ContentJSON(mutableListOf("a", "b", "c", "d", "e"))
        val right = cj.splice(2)
        assertTrue(right is ContentJSON)
        assertEquals(mutableListOf<Any?>("a", "b"), cj.values)
        assertEquals(mutableListOf<Any?>("c", "d", "e"), (right as ContentJSON).values)
    }

    @Test
    fun testContentJSONMergeReturnsFalse() {
        val left = ContentJSON(mutableListOf(1))
        val right = ContentJSON(mutableListOf(2))
        assertFalse(left.mergeWith(right))
    }

    @Test
    fun testContentJSONWriteReadRoundTrip() {
        val original = ContentJSON(mutableListOf(42, "hello", true))
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentJSON.read(decoder)
        assertEquals(3, restored.getLength())
        assertEquals(42, restored.values[0])
        assertEquals("hello", restored.values[1])
        assertEquals(true, restored.values[2])
    }

    @Test
    fun testContentJSONWriteWithOffset() {
        val original = ContentJSON(mutableListOf(1, 2, 3, 4))
        val encoder = UpdateEncoderV1()
        original.write(encoder, 2)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentJSON.read(decoder)
        assertEquals(2, restored.getLength())
        assertEquals(3, restored.values[0])
        assertEquals(4, restored.values[1])
    }

    @Test
    fun testContentJSONWriteReadNull() {
        val original = ContentJSON(mutableListOf(null))
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentJSON.read(decoder)
        assertEquals(1, restored.getLength())
        assertNull(restored.values[0])
    }

    // ==================== ContentString ====================

    @Test
    fun testContentStringGetLength() {
        val cs = ContentString("hello")
        assertEquals(5, cs.getLength())
    }

    @Test
    fun testContentStringGetLengthEmpty() {
        val cs = ContentString("")
        assertEquals(0, cs.getLength())
    }

    @Test
    fun testContentStringGetContent() {
        val cs = ContentString("abc")
        val content = cs.getContent()
        assertEquals(listOf("a", "b", "c"), content)
    }

    @Test
    fun testContentStringGetContentEmpty() {
        val cs = ContentString("")
        assertEquals(emptyList(), cs.getContent())
    }

    @Test
    fun testContentStringIsCountable() {
        val cs = ContentString("x")
        assertTrue(cs.isCountable())
    }

    @Test
    fun testContentStringGetRef() {
        val cs = ContentString("x")
        assertEquals(4, cs.getRef())
    }

    @Test
    fun testContentStringCopy() {
        val cs = ContentString("hello")
        val copy = cs.copy()
        assertTrue(copy is ContentString)
        assertEquals("hello", (copy as ContentString).str)
        // Verify independence
        copy.str = "world"
        assertEquals("hello", cs.str)
    }

    @Test
    fun testContentStringSplice() {
        val cs = ContentString("hello world")
        val right = cs.splice(5)
        assertTrue(right is ContentString)
        assertEquals("hello", cs.str)
        assertEquals(" world", (right as ContentString).str)
    }

    @Test
    fun testContentStringSpliceAtOne() {
        val cs = ContentString("abc")
        val right = cs.splice(1)
        assertEquals("a", cs.str)
        assertEquals("bc", (right as ContentString).str)
    }

    @Test
    fun testContentStringMergeWithSameType() {
        val left = ContentString("hello")
        val right = ContentString(" world")
        assertTrue(left.mergeWith(right))
        assertEquals("hello world", left.str)
    }

    @Test
    fun testContentStringMergeWithDifferentType() {
        val left = ContentString("hello")
        val right = ContentDeleted(1)
        assertFalse(left.mergeWith(right))
        assertEquals("hello", left.str)
    }

    @Test
    fun testContentStringWriteReadRoundTrip() {
        val original = ContentString("Hello, World!")
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentString.read(decoder)
        assertEquals("Hello, World!", restored.str)
    }

    @Test
    fun testContentStringWriteWithOffset() {
        val original = ContentString("Hello, World!")
        val encoder = UpdateEncoderV1()
        original.write(encoder, 7)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentString.read(decoder)
        assertEquals("World!", restored.str)
    }

    @Test
    fun testContentStringWriteReadUnicode() {
        val original = ContentString("Hello \u00e9\u00e8\u00ea \u00fc\u00f6\u00e4")
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentString.read(decoder)
        assertEquals("Hello \u00e9\u00e8\u00ea \u00fc\u00f6\u00e4", restored.str)
    }

    // ==================== ContentAny ====================

    @Test
    fun testContentAnyGetLength() {
        val ca = ContentAny(mutableListOf(1, "two", null, true))
        assertEquals(4, ca.getLength())
    }

    @Test
    fun testContentAnyGetLengthEmpty() {
        val ca = ContentAny(mutableListOf())
        assertEquals(0, ca.getLength())
    }

    @Test
    fun testContentAnyGetContent() {
        val values = mutableListOf<Any?>(1, "two", null)
        val ca = ContentAny(values)
        assertEquals(values, ca.getContent())
    }

    @Test
    fun testContentAnyIsCountable() {
        val ca = ContentAny(mutableListOf(1))
        assertTrue(ca.isCountable())
    }

    @Test
    fun testContentAnyGetRef() {
        val ca = ContentAny(mutableListOf())
        assertEquals(8, ca.getRef())
    }

    @Test
    fun testContentAnyCopy() {
        val ca = ContentAny(mutableListOf(1, 2, 3))
        val copy = ca.copy()
        assertTrue(copy is ContentAny)
        assertEquals(ca.getContent(), copy.getContent())
        // Verify independence
        (copy as ContentAny).values.add(4)
        assertEquals(3, ca.getLength())
    }

    @Test
    fun testContentAnySplice() {
        val ca = ContentAny(mutableListOf(10, 20, 30, 40, 50))
        val right = ca.splice(2)
        assertTrue(right is ContentAny)
        assertEquals(mutableListOf<Any?>(10, 20), ca.values)
        assertEquals(mutableListOf<Any?>(30, 40, 50), (right as ContentAny).values)
    }

    @Test
    fun testContentAnySpliceAtOne() {
        val ca = ContentAny(mutableListOf("a", "b", "c"))
        val right = ca.splice(1)
        assertEquals(mutableListOf<Any?>("a"), ca.values)
        assertEquals(mutableListOf<Any?>("b", "c"), (right as ContentAny).values)
    }

    @Test
    fun testContentAnyMergeWithSameType() {
        val left = ContentAny(mutableListOf(1, 2))
        val right = ContentAny(mutableListOf(3, 4))
        assertTrue(left.mergeWith(right))
        assertEquals(mutableListOf<Any?>(1, 2, 3, 4), left.values)
    }

    @Test
    fun testContentAnyMergeWithDifferentType() {
        val left = ContentAny(mutableListOf(1))
        val right = ContentString("hello")
        assertFalse(left.mergeWith(right))
    }

    @Test
    fun testContentAnyWriteReadRoundTrip() {
        val original = ContentAny(mutableListOf(42, "hello", null, true, false))
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentAny.read(decoder)
        assertEquals(5, restored.getLength())
        assertEquals(42, restored.values[0])
        assertEquals("hello", restored.values[1])
        assertNull(restored.values[2])
        assertEquals(true, restored.values[3])
        assertEquals(false, restored.values[4])
    }

    @Test
    fun testContentAnyWriteWithOffset() {
        val original = ContentAny(mutableListOf(1, 2, 3, 4))
        val encoder = UpdateEncoderV1()
        original.write(encoder, 2)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentAny.read(decoder)
        assertEquals(2, restored.getLength())
        assertEquals(3, restored.values[0])
        assertEquals(4, restored.values[1])
    }

    @Test
    fun testContentAnyWriteReadEmptyList() {
        val original = ContentAny(mutableListOf())
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentAny.read(decoder)
        assertEquals(0, restored.getLength())
        assertTrue(restored.values.isEmpty())
    }

    // ==================== ContentEmbed ====================

    @Test
    fun testContentEmbedGetLength() {
        val ce = ContentEmbed("image-data")
        assertEquals(1, ce.getLength())
    }

    @Test
    fun testContentEmbedGetContent() {
        val embedObj = mapOf("src" to "image.png")
        val ce = ContentEmbed(embedObj)
        val content = ce.getContent()
        assertEquals(1, content.size)
        assertEquals(embedObj, content[0])
    }

    @Test
    fun testContentEmbedGetContentNull() {
        val ce = ContentEmbed(null)
        assertEquals(listOf(null), ce.getContent())
    }

    @Test
    fun testContentEmbedIsCountable() {
        val ce = ContentEmbed("data")
        assertTrue(ce.isCountable())
    }

    @Test
    fun testContentEmbedGetRef() {
        val ce = ContentEmbed("data")
        assertEquals(5, ce.getRef())
    }

    @Test
    fun testContentEmbedCopy() {
        val ce = ContentEmbed("embed-data")
        val copy = ce.copy()
        assertTrue(copy is ContentEmbed)
        assertEquals("embed-data", (copy as ContentEmbed).embed)
    }

    @Test
    fun testContentEmbedSpliceThrows() {
        val ce = ContentEmbed("data")
        assertFailsWith<UnsupportedOperationException> {
            ce.splice(0)
        }
    }

    @Test
    fun testContentEmbedMergeReturnsFalse() {
        val left = ContentEmbed("a")
        val right = ContentEmbed("b")
        assertFalse(left.mergeWith(right))
    }

    @Test
    fun testContentEmbedWriteReadRoundTripString() {
        val original = ContentEmbed("some-embed-data")
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentEmbed.read(decoder)
        assertEquals("some-embed-data", restored.embed)
    }

    @Test
    fun testContentEmbedWriteReadRoundTripNumber() {
        val original = ContentEmbed(42)
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentEmbed.read(decoder)
        // Embed goes through jsonStringify -> jsonParse, so numeric string "42" parses back to 42
        assertEquals(42, restored.embed)
    }

    // ==================== ContentFormat ====================

    @Test
    fun testContentFormatGetLength() {
        val cf = ContentFormat("bold", true)
        assertEquals(1, cf.getLength())
    }

    @Test
    fun testContentFormatGetContent() {
        val cf = ContentFormat("bold", true)
        assertEquals(emptyList(), cf.getContent())
    }

    @Test
    fun testContentFormatIsNotCountable() {
        val cf = ContentFormat("italic", true)
        assertFalse(cf.isCountable())
    }

    @Test
    fun testContentFormatGetRef() {
        val cf = ContentFormat("bold", true)
        assertEquals(6, cf.getRef())
    }

    @Test
    fun testContentFormatCopy() {
        val cf = ContentFormat("bold", true)
        val copy = cf.copy()
        assertTrue(copy is ContentFormat)
        assertEquals("bold", (copy as ContentFormat).key)
        assertEquals(true, copy.value)
    }

    @Test
    fun testContentFormatSpliceThrows() {
        val cf = ContentFormat("bold", true)
        assertFailsWith<UnsupportedOperationException> {
            cf.splice(0)
        }
    }

    @Test
    fun testContentFormatMergeReturnsFalse() {
        val left = ContentFormat("bold", true)
        val right = ContentFormat("italic", true)
        assertFalse(left.mergeWith(right))
    }

    @Test
    fun testContentFormatWriteReadRoundTrip() {
        val original = ContentFormat("bold", true)
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentFormat.read(decoder)
        assertEquals("bold", restored.key)
        assertEquals(true, restored.value)
    }

    @Test
    fun testContentFormatWriteReadNullValue() {
        val original = ContentFormat("color", null)
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentFormat.read(decoder)
        assertEquals("color", restored.key)
        assertNull(restored.value)
    }

    @Test
    fun testContentFormatWriteReadFalseValue() {
        val original = ContentFormat("bold", false)
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentFormat.read(decoder)
        assertEquals("bold", restored.key)
        assertEquals(false, restored.value)
    }

    // ==================== ContentType ====================

    @Test
    fun testContentTypeGetLength() {
        val ct = ContentType(YArray())
        assertEquals(1, ct.getLength())
    }

    @Test
    fun testContentTypeGetContent() {
        val arr = YArray()
        val ct = ContentType(arr)
        val content = ct.getContent()
        assertEquals(1, content.size)
        assertSame(arr, content[0])
    }

    @Test
    fun testContentTypeIsCountable() {
        val ct = ContentType(YMap())
        assertTrue(ct.isCountable())
    }

    @Test
    fun testContentTypeGetRef() {
        val ct = ContentType(YText())
        assertEquals(7, ct.getRef())
    }

    @Test
    fun testContentTypeCopy() {
        val ct = ContentType(YArray())
        val copy = ct.copy()
        assertTrue(copy is ContentType)
        assertTrue((copy as ContentType).type is YArray)
        // copy() should create a new YType instance
        assertNotSame(ct.type, copy.type)
    }

    @Test
    fun testContentTypeCopyPreservesTypeKind() {
        val types = listOf(YArray(), YMap(), YText(), YXmlFragment(), YXmlText())
        for (type in types) {
            val ct = ContentType(type)
            val copy = ct.copy() as ContentType
            assertEquals(type::class, copy.type::class,
                "Copy of ContentType wrapping ${type::class.simpleName} should preserve type")
        }
    }

    @Test
    fun testContentTypeSpliceThrows() {
        val ct = ContentType(YArray())
        assertFailsWith<UnsupportedOperationException> {
            ct.splice(0)
        }
    }

    @Test
    fun testContentTypeMergeReturnsFalse() {
        val left = ContentType(YArray())
        val right = ContentType(YMap())
        assertFalse(left.mergeWith(right))
    }

    @Test
    fun testContentTypeWriteReadRoundTripYArray() {
        val original = ContentType(YArray())
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentType.read(decoder)
        assertTrue(restored.type is YArray)
    }

    @Test
    fun testContentTypeWriteReadRoundTripYMap() {
        val original = ContentType(YMap())
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentType.read(decoder)
        assertTrue(restored.type is YMap)
    }

    @Test
    fun testContentTypeWriteReadRoundTripYText() {
        val original = ContentType(YText())
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentType.read(decoder)
        assertTrue(restored.type is YText)
    }

    @Test
    fun testContentTypeWriteReadRoundTripYXmlFragment() {
        val original = ContentType(YXmlFragment())
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentType.read(decoder)
        assertTrue(restored.type is YXmlFragment)
    }

    @Test
    fun testContentTypeWriteReadRoundTripYXmlText() {
        val original = ContentType(YXmlText())
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentType.read(decoder)
        assertTrue(restored.type is YXmlText)
    }

    @Test
    fun testContentTypeWriteReadRoundTripYXmlElement() {
        val original = ContentType(YXmlElement("paragraph"))
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentType.read(decoder)
        assertTrue(restored.type is YXmlElement)
        assertEquals("paragraph", (restored.type as YXmlElement).tag)
    }

    @Test
    fun testContentTypeWriteReadRoundTripYXmlHook() {
        val original = ContentType(YXmlHook("my-hook"))
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentType.read(decoder)
        assertTrue(restored.type is YXmlHook)
        assertEquals("my-hook", (restored.type as YXmlHook).hookName)
    }

    // ==================== ContentDoc ====================

    @Test
    fun testContentDocGetLength() {
        val doc = Doc(guid = "test-guid")
        val cd = ContentDoc(doc)
        assertEquals(1, cd.getLength())
    }

    @Test
    fun testContentDocGetContent() {
        val doc = Doc(guid = "test-guid")
        val cd = ContentDoc(doc)
        val content = cd.getContent()
        assertEquals(1, content.size)
        assertSame(doc, content[0])
    }

    @Test
    fun testContentDocIsCountable() {
        val cd = ContentDoc(Doc())
        assertTrue(cd.isCountable())
    }

    @Test
    fun testContentDocGetRef() {
        val cd = ContentDoc(Doc())
        assertEquals(9, cd.getRef())
    }

    @Test
    fun testContentDocCopy() {
        val doc = Doc(guid = "original-guid")
        val cd = ContentDoc(doc)
        val copy = cd.copy()
        assertTrue(copy is ContentDoc)
        // ContentDoc.copy() returns the same doc reference
        assertSame(doc, (copy as ContentDoc).doc)
    }

    @Test
    fun testContentDocSpliceThrows() {
        val cd = ContentDoc(Doc())
        assertFailsWith<UnsupportedOperationException> {
            cd.splice(0)
        }
    }

    @Test
    fun testContentDocMergeReturnsFalse() {
        val left = ContentDoc(Doc())
        val right = ContentDoc(Doc())
        assertFalse(left.mergeWith(right))
    }

    @Test
    fun testContentDocWriteReadRoundTrip() {
        val doc = Doc(guid = "my-subdoc-guid")
        val original = ContentDoc(doc)
        val encoder = UpdateEncoderV1()
        original.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentDoc.read(decoder)
        assertEquals("my-subdoc-guid", restored.doc.guid)
    }

    // ==================== readContent dispatcher ====================

    @Test
    fun testReadContentRef1ReturnsContentDeleted() {
        val encoder = UpdateEncoderV1()
        ContentDeleted(5).write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val result = readContent(1, decoder)
        assertTrue(result is ContentDeleted)
        assertEquals(5, result.getLength())
    }

    @Test
    fun testReadContentRef2ReturnsContentJSON() {
        val encoder = UpdateEncoderV1()
        ContentJSON(mutableListOf(42, "test")).write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val result = readContent(2, decoder)
        assertTrue(result is ContentJSON)
        assertEquals(2, result.getLength())
    }

    @Test
    fun testReadContentRef3ReturnsContentBinary() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val encoder = UpdateEncoderV1()
        ContentBinary(data).write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val result = readContent(3, decoder)
        assertTrue(result is ContentBinary)
        assertContentEquals(data, (result as ContentBinary).data)
    }

    @Test
    fun testReadContentRef4ReturnsContentString() {
        val encoder = UpdateEncoderV1()
        ContentString("hello").write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val result = readContent(4, decoder)
        assertTrue(result is ContentString)
        assertEquals("hello", (result as ContentString).str)
    }

    @Test
    fun testReadContentRef5ReturnsContentEmbed() {
        val encoder = UpdateEncoderV1()
        ContentEmbed("embed-val").write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val result = readContent(5, decoder)
        assertTrue(result is ContentEmbed)
        assertEquals("embed-val", (result as ContentEmbed).embed)
    }

    @Test
    fun testReadContentRef6ReturnsContentFormat() {
        val encoder = UpdateEncoderV1()
        ContentFormat("bold", true).write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val result = readContent(6, decoder)
        assertTrue(result is ContentFormat)
        assertEquals("bold", (result as ContentFormat).key)
        assertEquals(true, result.value)
    }

    @Test
    fun testReadContentRef7ReturnsContentType() {
        val encoder = UpdateEncoderV1()
        ContentType(YArray()).write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val result = readContent(7, decoder)
        assertTrue(result is ContentType)
        assertTrue((result as ContentType).type is YArray)
    }

    @Test
    fun testReadContentRef8ReturnsContentAny() {
        val encoder = UpdateEncoderV1()
        ContentAny(mutableListOf(1, null, "x")).write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val result = readContent(8, decoder)
        assertTrue(result is ContentAny)
        assertEquals(3, result.getLength())
    }

    @Test
    fun testReadContentRef9ReturnsContentDoc() {
        val encoder = UpdateEncoderV1()
        ContentDoc(Doc(guid = "subdoc")).write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val result = readContent(9, decoder)
        assertTrue(result is ContentDoc)
        assertEquals("subdoc", (result as ContentDoc).doc.guid)
    }

    @Test
    fun testReadContentUnknownRefThrows() {
        // Ref 0 and ref 10+ are unknown
        val encoder = UpdateEncoderV1()
        encoder.restEncoder.writeVarUint(0) // some dummy data
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        assertFailsWith<IllegalStateException> {
            readContent(0, decoder)
        }
    }

    @Test
    fun testReadContentRefTooHighThrows() {
        val encoder = UpdateEncoderV1()
        encoder.restEncoder.writeVarUint(0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        assertFailsWith<IllegalStateException> {
            readContent(99, decoder)
        }
    }

    // ==================== Cross-type merge rejection ====================

    @Test
    fun testMergeAcrossTypesAlwaysFails() {
        // Build a representative instance of each content type
        val contents: List<AbstractContent> = listOf(
            ContentDeleted(1),
            ContentJSON(mutableListOf(1)),
            ContentBinary(byteArrayOf(1)),
            ContentString("a"),
            ContentEmbed("x"),
            ContentFormat("k", "v"),
            ContentType(YArray()),
            ContentAny(mutableListOf(1)),
            ContentDoc(Doc())
        )
        for (left in contents) {
            for (right in contents) {
                if (left::class != right::class) {
                    assertFalse(left.mergeWith(right),
                        "${left::class.simpleName} should not merge with ${right::class.simpleName}")
                }
            }
        }
    }

    // ==================== Splice edge cases ====================

    @Test
    fun testContentDeletedSpliceLeavesLeftWithOffset() {
        val cd = ContentDeleted(100)
        val right = cd.splice(99)
        assertEquals(99, cd.len)
        assertEquals(1, (right as ContentDeleted).len)
    }

    @Test
    fun testContentStringSpliceEntireLeft() {
        // Splice at 0 should leave empty left, full right
        val cs = ContentString("abc")
        val right = cs.splice(0)
        assertEquals("", cs.str)
        assertEquals("abc", (right as ContentString).str)
    }

    @Test
    fun testContentAnySpliceAtEnd() {
        val ca = ContentAny(mutableListOf(1, 2, 3))
        val right = ca.splice(3)
        assertEquals(3, ca.getLength())
        assertEquals(0, right.getLength())
    }

    @Test
    fun testContentJSONSpliceAtOne() {
        val cj = ContentJSON(mutableListOf("a", "b", "c"))
        val right = cj.splice(1)
        assertEquals(mutableListOf<Any?>("a"), cj.values)
        assertEquals(mutableListOf<Any?>("b", "c"), (right as ContentJSON).values)
    }

    // ==================== Ref values are correct and unique ====================

    @Test
    fun testAllRefValuesAreUnique() {
        val contents: List<AbstractContent> = listOf(
            ContentDeleted(1),
            ContentJSON(mutableListOf()),
            ContentBinary(byteArrayOf()),
            ContentString(""),
            ContentEmbed(null),
            ContentFormat("k", "v"),
            ContentType(YArray()),
            ContentAny(mutableListOf()),
            ContentDoc(Doc())
        )
        val refs = contents.map { it.getRef() }
        assertEquals(refs.size, refs.toSet().size, "All ref values should be unique: $refs")
    }

    @Test
    fun testRefValuesMatchExpected() {
        assertEquals(1, ContentDeleted(1).getRef())
        assertEquals(2, ContentJSON(mutableListOf()).getRef())
        assertEquals(3, ContentBinary(byteArrayOf()).getRef())
        assertEquals(4, ContentString("").getRef())
        assertEquals(5, ContentEmbed(null).getRef())
        assertEquals(6, ContentFormat("k", "v").getRef())
        assertEquals(7, ContentType(YArray()).getRef())
        assertEquals(8, ContentAny(mutableListOf()).getRef())
        assertEquals(9, ContentDoc(Doc()).getRef())
    }

    // ==================== Countable correctness ====================

    @Test
    fun testCountableTypes() {
        // These should be countable
        assertTrue(ContentBinary(byteArrayOf()).isCountable())
        assertTrue(ContentJSON(mutableListOf()).isCountable())
        assertTrue(ContentString("").isCountable())
        assertTrue(ContentAny(mutableListOf()).isCountable())
        assertTrue(ContentEmbed(null).isCountable())
        assertTrue(ContentType(YArray()).isCountable())
        assertTrue(ContentDoc(Doc()).isCountable())
    }

    @Test
    fun testNonCountableTypes() {
        // These should NOT be countable
        assertFalse(ContentDeleted(1).isCountable())
        assertFalse(ContentFormat("k", "v").isCountable())
    }

    // ==================== ContentType delete and gc via doc operations ====================

    @Test
    fun testContentTypeDeleteTraversesLinkedListAndMap() {
        val doc = Doc(gc = true)
        val arr = doc.getArray("arr")
        val innerMap = YMap()
        arr.push(listOf(innerMap))

        // Populate both the map's key-value store and add a nested array
        innerMap.set("key1", "val1")
        innerMap.set("key2", "val2")
        val nestedArr = YArray()
        innerMap.set("list", nestedArr)
        nestedArr.push(listOf(10, 20, 30))

        // Deleting the array element triggers ContentType.delete on the map
        arr.delete(0)
        assertEquals(0, arr.length)

        // Verify round-trip integrity
        val update = yks.utils.encodeStateAsUpdate(doc)
        val doc2 = Doc()
        yks.utils.applyUpdate(doc2, update)
        assertEquals(0, doc2.getArray("arr").length)
    }

    @Test
    fun testContentTypeGCTraversesLinkedListAndMap() {
        val doc = Doc(gc = true)
        val arr = doc.getArray("arr")
        val innerMap = YMap()
        arr.push(listOf(innerMap))
        innerMap.set("a", 1)
        innerMap.set("b", 2)

        // Delete triggers gc chain
        arr.delete(0)

        // Encode and decode the document with GC'd content
        val update = yks.utils.encodeStateAsUpdate(doc)
        val doc2 = Doc()
        yks.utils.applyUpdate(doc2, update)
        assertEquals(0, doc2.getArray("arr").length)
    }

    // ==================== ContentBinary delete and gc are no-ops ====================

    @Test
    fun testContentBinaryDeleteAndGCNoOps() {
        val doc = Doc()
        val transaction = yks.utils.Transaction(doc)
        val cb = ContentBinary(byteArrayOf(1, 2, 3))
        cb.delete(transaction)
        cb.gc(transaction)
        // Neither throws, both are no-ops
        assertContentEquals(byteArrayOf(1, 2, 3), cb.data)
    }

    // ==================== ContentEmbed delete and gc are no-ops ====================

    @Test
    fun testContentEmbedDeleteAndGCNoOps() {
        val doc = Doc()
        val transaction = yks.utils.Transaction(doc)
        val ce = ContentEmbed("embed-object")
        ce.delete(transaction)
        ce.gc(transaction)
        // Neither throws, both are no-ops
        assertEquals("embed-object", ce.embed)
    }

    // ==================== ContentString splice surrogate boundary ====================

    @Test
    fun testContentStringSpliceSurrogatePairMovesHighSurrogateToRight() {
        // Build a string with a surrogate pair in the middle
        val highSurrogate = '\uD83D'
        val lowSurrogate = '\uDE00'
        val str = "X${highSurrogate}${lowSurrogate}Y"
        // str = "X" + high + low + "Y", length 4
        val cs = ContentString(str)
        // Split between the high and low surrogate (offset 2)
        val right = cs.splice(2) as ContentString
        // The high surrogate should be moved to the right side
        assertEquals("X", cs.str)
        assertEquals("${highSurrogate}${lowSurrogate}Y", right.str)
    }

    @Test
    fun testContentStringSpliceNoSurrogateAtBoundary() {
        val cs = ContentString("abcdef")
        val right = cs.splice(3) as ContentString
        assertEquals("abc", cs.str)
        assertEquals("def", right.str)
    }

    // ==================== ContentDeleted gc is no-op ====================

    @Test
    fun testContentDeletedGCDoesNothing() {
        val doc = Doc()
        val transaction = yks.utils.Transaction(doc)
        val cd = ContentDeleted(10)
        cd.gc(transaction)
        assertEquals(10, cd.getLength())
    }

    // ==================== ContentFormat delete and gc are no-ops ====================

    @Test
    fun testContentFormatDeleteAndGCNoOps() {
        val doc = Doc()
        val transaction = yks.utils.Transaction(doc)
        val cf = ContentFormat("bold", true)
        cf.delete(transaction)
        cf.gc(transaction)
        // Neither throws, both are no-ops
        assertEquals("bold", cf.key)
        assertEquals(true, cf.value)
    }

    // ==================== ContentJSON splice and write offset paths ====================

    @Test
    fun testContentJSONSpliceAtBoundaries() {
        // Splice at 0: empty left, full right
        val cj1 = ContentJSON(mutableListOf(1, 2, 3))
        val right1 = cj1.splice(0) as ContentJSON
        assertEquals(mutableListOf<Any?>(), cj1.values)
        assertEquals(mutableListOf<Any?>(1, 2, 3), right1.values)

        // Splice at end: full left, empty right
        val cj2 = ContentJSON(mutableListOf(1, 2, 3))
        val right2 = cj2.splice(3) as ContentJSON
        assertEquals(mutableListOf<Any?>(1, 2, 3), cj2.values)
        assertEquals(mutableListOf<Any?>(), right2.values)
    }

    @Test
    fun testContentJSONWriteWithMaxOffset() {
        val cj = ContentJSON(mutableListOf(10, 20, 30))
        val encoder = UpdateEncoderV1()
        cj.write(encoder, 2) // offset = 2, only last value
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentJSON.read(decoder)
        assertEquals(1, restored.getLength())
        assertEquals(30, restored.values[0])
    }

    @Test
    fun testContentJSONWriteWithZeroOffset() {
        val cj = ContentJSON(mutableListOf(100, 200))
        val encoder = UpdateEncoderV1()
        cj.write(encoder, 0)
        val decoder = UpdateDecoderV1(encoder.toByteArray())
        val restored = ContentJSON.read(decoder)
        assertEquals(2, restored.getLength())
        assertEquals(100, restored.values[0])
        assertEquals(200, restored.values[1])
    }

    // ==================== ContentDoc delete tracks subdoc removal ====================

    @Test
    fun testContentDocDeleteAddsToSubdocsRemoved() {
        val doc = Doc()
        val subdoc = Doc(guid = "removed-subdoc")
        val cd = yks.structs.content.ContentDoc(subdoc)
        val transaction = yks.utils.Transaction(doc)
        cd.delete(transaction)
        assertTrue(transaction.subdocsRemoved.contains(subdoc),
            "ContentDoc.delete should add the subdoc to subdocsRemoved")
    }
}
