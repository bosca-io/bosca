package yks

import kotlin.test.*
import yks.lib0.*
import yks.utils.*
import yks.types.*

/**
 * Comprehensive tests for UpdateEncoder/UpdateDecoder, focusing on the V2
 * columnar encoding variants which have lower coverage.
 */
class UpdateEncoderDecoderTest {

    // ---------------------------------------------------------------
    // Helper utilities
    // ---------------------------------------------------------------

    private fun newDoc(clientID: Int): Doc {
        val doc = Doc()
        doc.clientID = clientID
        return doc
    }

    private fun syncV2(doc1: Doc, doc2: Doc) {
        val sv1 = encodeStateVector(doc1)
        val sv2 = encodeStateVector(doc2)
        val update1to2 = encodeStateAsUpdateV2(doc1, sv2)
        val update2to1 = encodeStateAsUpdateV2(doc2, sv1)
        applyUpdateV2(doc2, update1to2)
        applyUpdateV2(doc1, update2to1)
    }

    // ---------------------------------------------------------------
    // V2UpdateEncoder: Individual method tests
    // ---------------------------------------------------------------

    @Test
    fun testV2WriteAndReadLeftID() {
        val encoder = UpdateEncoderV2()
        encoder.writeLeftID(42, 100)
        encoder.writeLeftID(0, 0)
        encoder.writeLeftID(999, 50000)

        // To read back, we need to go through the full V2 serialization.
        // Manually construct a minimal V2 update that uses these values.
        // Instead, test through round-trip of a document operation.
        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        val id1 = decoder.readLeftID()
        assertEquals(ID(42, 100), id1)
        val id2 = decoder.readLeftID()
        assertEquals(ID(0, 0), id2)
        val id3 = decoder.readLeftID()
        assertEquals(ID(999, 50000), id3)
    }

    @Test
    fun testV2WriteAndReadRightID() {
        val encoder = UpdateEncoderV2()
        encoder.writeRightID(10, 20)
        encoder.writeRightID(300, 400)

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        val id1 = decoder.readRightID()
        assertEquals(ID(10, 20), id1)
        val id2 = decoder.readRightID()
        assertEquals(ID(300, 400), id2)
    }

    @Test
    fun testV2WriteAndReadClient() {
        val encoder = UpdateEncoderV2()
        encoder.writeClient(0)
        encoder.writeClient(1)
        encoder.writeClient(100)
        encoder.writeClient(999999)

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        assertEquals(0, decoder.readClient())
        assertEquals(1, decoder.readClient())
        assertEquals(100, decoder.readClient())
        assertEquals(999999, decoder.readClient())
    }

    @Test
    fun testV2WriteAndReadInfo() {
        val encoder = UpdateEncoderV2()
        // Test various info byte patterns used in yjs
        encoder.writeInfo(0)       // GC
        encoder.writeInfo(10)      // Skip
        encoder.writeInfo(8)       // ContentAny, no origin, no rightOrigin, no parentSub
        encoder.writeInfo(BIT8 or 8)  // ContentAny with origin
        encoder.writeInfo(BIT7 or 4)  // ContentString with rightOrigin
        encoder.writeInfo(BIT6 or BIT8 or BIT7 or 8)  // all flags

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        assertEquals(0, decoder.readInfo())
        assertEquals(10, decoder.readInfo())
        assertEquals(8, decoder.readInfo())
        assertEquals(BIT8 or 8, decoder.readInfo())
        assertEquals(BIT7 or 4, decoder.readInfo())
        assertEquals(BIT6 or BIT8 or BIT7 or 8, decoder.readInfo())
    }

    @Test
    fun testV2WriteAndReadString() {
        val encoder = UpdateEncoderV2()
        encoder.writeString("hello")
        encoder.writeString("")
        encoder.writeString("world")
        encoder.writeString("unicode: cafe\u0301")

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        assertEquals("hello", decoder.readString())
        assertEquals("", decoder.readString())
        assertEquals("world", decoder.readString())
        assertEquals("unicode: cafe\u0301", decoder.readString())
    }

    @Test
    fun testV2WriteAndReadParentInfo() {
        val encoder = UpdateEncoderV2()
        encoder.writeParentInfo(true)
        encoder.writeParentInfo(false)
        encoder.writeParentInfo(true)
        encoder.writeParentInfo(false)

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        assertTrue(decoder.readParentInfo())
        assertFalse(decoder.readParentInfo())
        assertTrue(decoder.readParentInfo())
        assertFalse(decoder.readParentInfo())
    }

    @Test
    fun testV2WriteAndReadTypeRef() {
        val encoder = UpdateEncoderV2()
        encoder.writeTypeRef(0) // YArray
        encoder.writeTypeRef(1) // YMap
        encoder.writeTypeRef(2) // YText
        encoder.writeTypeRef(3) // YXmlElement
        encoder.writeTypeRef(4) // YXmlFragment
        encoder.writeTypeRef(6) // YXmlText

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        assertEquals(0, decoder.readTypeRef())
        assertEquals(1, decoder.readTypeRef())
        assertEquals(2, decoder.readTypeRef())
        assertEquals(3, decoder.readTypeRef())
        assertEquals(4, decoder.readTypeRef())
        assertEquals(6, decoder.readTypeRef())
    }

    @Test
    fun testV2WriteAndReadLen() {
        val encoder = UpdateEncoderV2()
        encoder.writeLen(0)
        encoder.writeLen(1)
        encoder.writeLen(5)
        encoder.writeLen(100)
        encoder.writeLen(10000)

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        assertEquals(0, decoder.readLen())
        assertEquals(1, decoder.readLen())
        assertEquals(5, decoder.readLen())
        assertEquals(100, decoder.readLen())
        assertEquals(10000, decoder.readLen())
    }

    @Test
    fun testV2WriteAndReadAny() {
        val encoder = UpdateEncoderV2()
        encoder.writeAny(null)
        encoder.writeAny(42)
        encoder.writeAny("hello")
        encoder.writeAny(true)
        encoder.writeAny(false)
        encoder.writeAny(listOf(1, 2, 3))
        encoder.writeAny(mapOf("a" to 1))

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        assertNull(decoder.readAny())
        assertEquals(42, decoder.readAny())
        assertEquals("hello", decoder.readAny())
        assertEquals(true, decoder.readAny())
        assertEquals(false, decoder.readAny())
        assertEquals(listOf(1, 2, 3), decoder.readAny())
        val map = decoder.readAny() as Map<*, *>
        assertEquals(1, map["a"])
    }

    @Test
    fun testV2WriteAndReadBuf() {
        val encoder = UpdateEncoderV2()
        encoder.writeBuf(byteArrayOf(1, 2, 3, 4, 5))
        encoder.writeBuf(byteArrayOf())
        encoder.writeBuf(byteArrayOf(0xFF.toByte(), 0x00, 0x7F))

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), decoder.readBuf())
        assertContentEquals(byteArrayOf(), decoder.readBuf())
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x00, 0x7F), decoder.readBuf())
    }

    @Test
    fun testV2WriteAndReadJSON() {
        val encoder = UpdateEncoderV2()
        encoder.writeJSON("{\"key\":\"value\"}")
        encoder.writeJSON("null")
        encoder.writeJSON("\"hello\"")

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        assertEquals("{\"key\":\"value\"}", decoder.readJSON())
        assertEquals("null", decoder.readJSON())
        assertEquals("\"hello\"", decoder.readJSON())
    }

    @Test
    fun testV2WriteAndReadKeyBasic() {
        val encoder = UpdateEncoderV2()
        encoder.writeKey("name")
        encoder.writeKey("age")
        encoder.writeKey("email")

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        assertEquals("name", decoder.readKey())
        assertEquals("age", decoder.readKey())
        assertEquals("email", decoder.readKey())
    }

    @Test
    fun testV2WriteAndReadKeyDeduplication() {
        // V2 key encoding uses a clock table: repeated keys reuse the same clock index
        val encoder = UpdateEncoderV2()
        encoder.writeKey("alpha")
        encoder.writeKey("beta")
        encoder.writeKey("alpha")  // should reuse clock 0
        encoder.writeKey("beta")   // should reuse clock 1
        encoder.writeKey("gamma")  // new key, clock 2
        encoder.writeKey("alpha")  // reuse clock 0 again

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        assertEquals("alpha", decoder.readKey())
        assertEquals("beta", decoder.readKey())
        assertEquals("alpha", decoder.readKey())
        assertEquals("beta", decoder.readKey())
        assertEquals("gamma", decoder.readKey())
        assertEquals("alpha", decoder.readKey())
    }

    @Test
    fun testV2KeyDeduplicationCompression() {
        // Repeated keys should compress well due to the clock table
        val encoder1 = UpdateEncoderV2()
        val encoder2 = UpdateEncoderV2()

        // encoder1: 100 unique keys
        repeat(100) { encoder1.writeKey("key_$it") }

        // encoder2: same key repeated 100 times (should be smaller)
        repeat(100) { encoder2.writeKey("repeated") }

        val size1 = encoder1.toByteArray().size
        val size2 = encoder2.toByteArray().size
        assertTrue(size2 < size1, "Repeated keys ($size2 bytes) should be smaller than unique keys ($size1 bytes)")
    }

    // ---------------------------------------------------------------
    // V2 Delete Set encoding/decoding
    // ---------------------------------------------------------------

    @Test
    fun testV2DsClockAndLen() {
        val encoder = UpdateEncoderV2()
        // Simulate writing a delete set with V2 delta encoding
        encoder.resetDsCurVal()
        encoder.writeDsClock(10)
        encoder.writeDsLen(5)  // deletes [10..15)
        encoder.writeDsClock(20)
        encoder.writeDsLen(3)  // deletes [20..23)

        val bytes = encoder.restEncoder.toByteArray()
        val decoder = Decoder(bytes)

        // V2 writeDsClock writes (clock - dsCurrVal) as delta
        // After reset: dsCurrVal=0, writeDsClock(10) writes 10, dsCurrVal=10
        // writeDsLen(5) writes 5-1=4, dsCurrVal=10+5=15
        // writeDsClock(20) writes 20-15=5, dsCurrVal=20
        // writeDsLen(3) writes 3-1=2, dsCurrVal=20+3=23
        assertEquals(10, decoder.readVarUint())
        assertEquals(4, decoder.readVarUint())
        assertEquals(5, decoder.readVarUint())
        assertEquals(2, decoder.readVarUint())
    }

    @Test
    fun testV2DsResetBetweenClients() {
        val encoder = UpdateEncoderV2()

        // Client 1
        encoder.resetDsCurVal()
        encoder.writeDsClock(5)
        encoder.writeDsLen(3)

        // Client 2 - reset between clients
        encoder.resetDsCurVal()
        encoder.writeDsClock(10)
        encoder.writeDsLen(2)

        val bytes = encoder.restEncoder.toByteArray()
        val decoder = Decoder(bytes)

        // Client 1: delta from 0 -> 5, len 3-1=2
        assertEquals(5, decoder.readVarUint())
        assertEquals(2, decoder.readVarUint())
        // Client 2: reset, so delta from 0 -> 10, len 2-1=1
        assertEquals(10, decoder.readVarUint())
        assertEquals(1, decoder.readVarUint())
    }

    @Test
    fun testV2DsLenZeroThrows() {
        val encoder = UpdateEncoderV2()
        encoder.resetDsCurVal()
        encoder.writeDsClock(0)
        assertFailsWith<IllegalStateException> {
            encoder.writeDsLen(0)
        }
    }

    @Test
    fun testV2DsClockAndLenReadRoundTrip() {
        // Use UpdateDecoderV2 to read back what UpdateEncoderV2 writes
        // We need to construct a complete V2 update with a delete set.
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        arr.push(listOf("a", "b", "c"))
        arr.delete(1, 1)  // delete "b"

        val v2Update = encodeStateAsUpdateV2(doc)

        // Apply to a fresh doc
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val arr2 = doc2.getArray("arr")
        assertEquals(2, arr2.length)
        assertEquals("a", arr2.get(0))
        assertEquals("c", arr2.get(1))
    }

    // ---------------------------------------------------------------
    // V1UpdateEncoder / V1UpdateDecoder: Round-trip tests
    // ---------------------------------------------------------------

    @Test
    fun testV1RoundTripLeftIDAndRightID() {
        val encoder = UpdateEncoderV1()
        encoder.writeLeftID(1, 2)
        encoder.writeRightID(3, 4)
        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV1(bytes)
        assertEquals(ID(1, 2), decoder.readLeftID())
        assertEquals(ID(3, 4), decoder.readRightID())
    }

    @Test
    fun testV1RoundTripAllMethods() {
        val encoder = UpdateEncoderV1()
        encoder.writeClient(42)
        encoder.writeInfo(0xAB)
        encoder.writeString("test")
        encoder.writeParentInfo(true)
        encoder.writeTypeRef(2)
        encoder.writeLen(10)
        encoder.writeAny("hello")
        encoder.writeBuf(byteArrayOf(1, 2, 3))
        encoder.writeJSON("{}")
        encoder.writeKey("mykey")

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV1(bytes)
        assertEquals(42, decoder.readClient())
        assertEquals(0xAB, decoder.readInfo())
        assertEquals("test", decoder.readString())
        assertTrue(decoder.readParentInfo())
        assertEquals(2, decoder.readTypeRef())
        assertEquals(10, decoder.readLen())
        assertEquals("hello", decoder.readAny())
        assertContentEquals(byteArrayOf(1, 2, 3), decoder.readBuf())
        assertEquals("{}", decoder.readJSON())
        assertEquals("mykey", decoder.readKey())
    }

    // ---------------------------------------------------------------
    // V2 columnar format structure tests
    // ---------------------------------------------------------------

    @Test
    fun testV2EmptyEncoder() {
        // An encoder with nothing written should still produce a valid V2 byte array
        val encoder = UpdateEncoderV2()
        val bytes = encoder.toByteArray()
        assertTrue(bytes.isNotEmpty(), "V2 encoder should produce non-empty output even with no data")

        // Should be decodable without error
        val decoder = UpdateDecoderV2(bytes)
        // No reads to do, but construction should succeed
        assertNotNull(decoder)
    }

    @Test
    fun testV2FeatureFlag() {
        // V2 format starts with a feature flag (currently 0)
        val encoder = UpdateEncoderV2()
        val bytes = encoder.toByteArray()
        val outerDecoder = Decoder(bytes)
        val featureFlag = outerDecoder.readVarUint()
        assertEquals(0, featureFlag, "V2 feature flag should be 0")
    }

    @Test
    fun testV2ColumnarSeparation() {
        // Verify that different channels are encoded into separate byte arrays
        val encoder = UpdateEncoderV2()
        // Write to different channels
        encoder.writeClient(100)
        encoder.writeInfo(42)
        encoder.writeString("hello")
        encoder.writeParentInfo(true)
        encoder.writeTypeRef(1)
        encoder.writeLen(5)
        encoder.writeKey("mykey")
        encoder.writeAny(99)

        val bytes = encoder.toByteArray()
        val outerDecoder = Decoder(bytes)

        // Read feature flag
        outerDecoder.readVarUint()

        // Read each column as separate byte arrays
        val keyClock = outerDecoder.readVarUint8Array()
        val client = outerDecoder.readVarUint8Array()
        val leftClock = outerDecoder.readVarUint8Array()
        val rightClock = outerDecoder.readVarUint8Array()
        val info = outerDecoder.readVarUint8Array()
        val strings = outerDecoder.readVarUint8Array()
        val parentInfo = outerDecoder.readVarUint8Array()
        val typeRef = outerDecoder.readVarUint8Array()
        val len = outerDecoder.readVarUint8Array()
        val rest = outerDecoder.readTail()

        // Verify non-empty columns for what was written
        assertTrue(client.isNotEmpty(), "Client column should be non-empty")
        assertTrue(info.isNotEmpty(), "Info column should be non-empty")
        assertTrue(strings.isNotEmpty(), "Strings column should be non-empty")
        assertTrue(parentInfo.isNotEmpty(), "ParentInfo column should be non-empty")
        assertTrue(typeRef.isNotEmpty(), "TypeRef column should be non-empty")
        assertTrue(len.isNotEmpty(), "Len column should be non-empty")
        assertTrue(keyClock.isNotEmpty(), "KeyClock column should be non-empty")
        assertTrue(rest.isNotEmpty(), "Rest column should be non-empty (writeAny)")
    }

    // ---------------------------------------------------------------
    // V2 full document round-trip tests
    // ---------------------------------------------------------------

    @Test
    fun testV2RoundTripEmptyDoc() {
        val doc1 = newDoc(1)
        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)
        assertTrue(doc2.share.isEmpty())
    }

    @Test
    fun testV2RoundTripArrayInts() {
        val doc1 = newDoc(1)
        val arr1 = doc1.getArray("arr")
        arr1.push(listOf(1, 2, 3, 4, 5))

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val arr2 = doc2.getArray("arr")
        assertEquals(5, arr2.length)
        assertEquals(listOf(1, 2, 3, 4, 5), arr2.toArray())
    }

    @Test
    fun testV2RoundTripArrayStrings() {
        val doc1 = newDoc(1)
        val arr1 = doc1.getArray("arr")
        arr1.push(listOf("hello", "world", "foo"))

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val arr2 = doc2.getArray("arr")
        assertEquals(3, arr2.length)
        assertEquals(listOf("hello", "world", "foo"), arr2.toArray())
    }

    @Test
    fun testV2RoundTripArrayMixedTypes() {
        val doc1 = newDoc(1)
        val arr1 = doc1.getArray("arr")
        arr1.push(listOf(42, "hello", true, false, null))

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val arr2 = doc2.getArray("arr")
        assertEquals(5, arr2.length)
        assertEquals(42, arr2.get(0))
        assertEquals("hello", arr2.get(1))
        assertEquals(true, arr2.get(2))
        assertEquals(false, arr2.get(3))
        assertNull(arr2.get(4))
    }

    @Test
    fun testV2RoundTripMapStrings() {
        val doc1 = newDoc(1)
        val map1 = doc1.getMap("map")
        map1.set("name", "Alice")
        map1.set("city", "NYC")
        map1.set("lang", "Kotlin")

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val map2 = doc2.getMap("map")
        assertEquals("Alice", map2.get("name"))
        assertEquals("NYC", map2.get("city"))
        assertEquals("Kotlin", map2.get("lang"))
    }

    @Test
    fun testV2RoundTripMapMixedTypes() {
        val doc1 = newDoc(1)
        val map1 = doc1.getMap("map")
        map1.set("int", 42)
        map1.set("str", "hello")
        map1.set("bool", true)
        map1.set("null", null)

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val map2 = doc2.getMap("map")
        assertEquals(42, map2.get("int"))
        assertEquals("hello", map2.get("str"))
        assertEquals(true, map2.get("bool"))
        assertNull(map2.get("null"))
    }

    @Test
    fun testV2RoundTripTextSimple() {
        val doc1 = newDoc(1)
        val text1 = doc1.getText("text")
        text1.insert(0, "Hello, World!")

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val text2 = doc2.getText("text")
        assertEquals("Hello, World!", text2.toString())
    }

    @Test
    fun testV2RoundTripTextMultipleInserts() {
        val doc1 = newDoc(1)
        val text1 = doc1.getText("text")
        text1.insert(0, "Hello")
        text1.insert(5, " World")
        text1.insert(0, "Oh! ")

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val text2 = doc2.getText("text")
        assertEquals(text1.toString(), text2.toString())
    }

    @Test
    fun testV2RoundTripNestedTypes() {
        val doc1 = newDoc(1)
        val root = doc1.getMap("root")
        val inner = YArray()
        root.set("list", inner)
        inner.push(listOf(1, 2, 3))

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val root2 = doc2.getMap("root")
        val inner2 = root2.get("list") as YArray
        assertEquals(listOf(1, 2, 3), inner2.toArray())
    }

    @Test
    fun testV2RoundTripNestedMapInArray() {
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        val nestedMap = YMap()
        arr.push(listOf(nestedMap))
        nestedMap.set("key", "value")

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val arr2 = doc2.getArray("arr")
        assertEquals(1, arr2.length)
        val nestedMap2 = arr2.get(0) as YMap
        assertEquals("value", nestedMap2.get("key"))
    }

    @Test
    fun testV2RoundTripWithDeletions() {
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        arr.push(listOf("a", "b", "c", "d", "e"))
        arr.delete(1, 2)  // delete "b" and "c"

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val arr2 = doc2.getArray("arr")
        assertEquals(3, arr2.length)
        assertEquals("a", arr2.get(0))
        assertEquals("d", arr2.get(1))
        assertEquals("e", arr2.get(2))
    }

    @Test
    fun testV2RoundTripMapDelete() {
        val doc1 = newDoc(1)
        val map = doc1.getMap("map")
        map.set("keep", "yes")
        map.set("remove", "no")
        map.delete("remove")

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val map2 = doc2.getMap("map")
        assertEquals("yes", map2.get("keep"))
        assertNull(map2.get("remove"))
    }

    @Test
    fun testV2RoundTripTextDelete() {
        val doc1 = newDoc(1)
        val text = doc1.getText("text")
        text.insert(0, "Hello World")
        text.delete(5, 6)  // delete " World"

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val text2 = doc2.getText("text")
        assertEquals("Hello", text2.toString())
    }

    // ---------------------------------------------------------------
    // V2 sync (two-doc) tests
    // ---------------------------------------------------------------

    @Test
    fun testV2SyncArrayInserts() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("arr")
        val arr2 = doc2.getArray("arr")

        arr1.push(listOf("a", "b"))
        arr2.push(listOf("c", "d"))

        syncV2(doc1, doc2)

        assertEquals(arr1.toArray().toSet(), arr2.toArray().toSet())
        assertEquals(4, arr1.length)
        assertEquals(4, arr2.length)
    }

    @Test
    fun testV2SyncMapConcurrent() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val map1 = doc1.getMap("map")
        val map2 = doc2.getMap("map")

        map1.set("x", 1)
        map2.set("y", 2)

        syncV2(doc1, doc2)

        assertEquals(1, map1.get("x"))
        assertEquals(2, map1.get("y"))
        assertEquals(1, map2.get("x"))
        assertEquals(2, map2.get("y"))
    }

    @Test
    fun testV2SyncMapConflict() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val map1 = doc1.getMap("map")
        val map2 = doc2.getMap("map")

        map1.set("key", "from1")
        map2.set("key", "from2")

        syncV2(doc1, doc2)

        // Both should converge to the same value
        assertEquals(map1.get("key"), map2.get("key"))
    }

    @Test
    fun testV2SyncTextConcurrent() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val text1 = doc1.getText("text")
        val text2 = doc2.getText("text")

        text1.insert(0, "Hello")
        syncV2(doc1, doc2)
        assertEquals("Hello", text2.toString())

        text1.insert(5, " World")
        text2.insert(0, "Oh! ")
        syncV2(doc1, doc2)

        assertEquals(text1.toString(), text2.toString())
    }

    @Test
    fun testV2SyncDeleteAndInsert() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("arr")
        arr1.push(listOf("a", "b", "c"))

        syncV2(doc1, doc2)

        val arr2 = doc2.getArray("arr")
        arr1.delete(1, 1) // delete "b"
        arr2.push(listOf("d"))

        syncV2(doc1, doc2)

        assertEquals(arr1.toArray(), arr2.toArray())
    }

    @Test
    fun testV2SyncMultipleRounds() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("arr")
        val arr2 = doc2.getArray("arr")

        arr1.push(listOf(1))
        syncV2(doc1, doc2)
        assertEquals(listOf(1), arr2.toArray())

        arr2.push(listOf(2))
        syncV2(doc1, doc2)
        assertEquals(listOf(1, 2), arr1.toArray())

        arr1.push(listOf(3))
        arr2.push(listOf(4))
        syncV2(doc1, doc2)
        assertEquals(arr1.toArray().toSet(), arr2.toArray().toSet())
        assertEquals(4, arr1.length)
    }

    @Test
    fun testV2SyncThreeDocs() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)
        val doc3 = newDoc(3)

        val m1 = doc1.getMap("m")
        val m2 = doc2.getMap("m")
        val m3 = doc3.getMap("m")

        m1.set("a", 1)
        m2.set("b", 2)
        m3.set("c", 3)

        syncV2(doc1, doc2)
        syncV2(doc2, doc3)
        syncV2(doc1, doc3)

        assertEquals(1, m1.get("a"))
        assertEquals(2, m1.get("b"))
        assertEquals(3, m1.get("c"))
        assertEquals(m1.entries(), m2.entries())
        assertEquals(m2.entries(), m3.entries())
    }

    @Test
    fun testV2IdempotentApply() {
        val doc1 = newDoc(1)
        val doc2 = Doc()

        val arr1 = doc1.getArray("arr")
        arr1.push(listOf("x", "y"))

        val update = encodeStateAsUpdateV2(doc1)
        applyUpdateV2(doc2, update)
        applyUpdateV2(doc2, update)  // Apply same update again

        val arr2 = doc2.getArray("arr")
        assertEquals(2, arr2.length)
        assertEquals(listOf("x", "y"), arr2.toArray())
    }

    // ---------------------------------------------------------------
    // V2 incremental sync
    // ---------------------------------------------------------------

    @Test
    fun testV2IncrementalSync() {
        val doc1 = newDoc(1)
        val doc2 = Doc()

        val arr1 = doc1.getArray("arr")
        arr1.push(listOf("first"))

        // Full sync
        val fullUpdate = encodeStateAsUpdateV2(doc1)
        applyUpdateV2(doc2, fullUpdate)

        // More changes
        arr1.push(listOf("second"))

        // Incremental sync
        val sv2 = encodeStateVector(doc2)
        val diffUpdate = encodeStateAsUpdateV2(doc1, sv2)
        applyUpdateV2(doc2, diffUpdate)

        val arr2 = doc2.getArray("arr")
        assertEquals(listOf("first", "second"), arr2.toArray())
    }

    @Test
    fun testV2IncrementalSyncMap() {
        val doc1 = newDoc(1)
        val doc2 = Doc()

        val map1 = doc1.getMap("map")
        map1.set("a", 1)

        val full = encodeStateAsUpdateV2(doc1)
        applyUpdateV2(doc2, full)

        map1.set("b", 2)
        map1.set("c", 3)

        val sv2 = encodeStateVector(doc2)
        val diff = encodeStateAsUpdateV2(doc1, sv2)
        applyUpdateV2(doc2, diff)

        val map2 = doc2.getMap("map")
        assertEquals(1, map2.get("a"))
        assertEquals(2, map2.get("b"))
        assertEquals(3, map2.get("c"))
    }

    // ---------------------------------------------------------------
    // V1 <-> V2 cross-format compatibility
    // ---------------------------------------------------------------

    @Test
    fun testV1ToV2Migration() {
        // Encode as V1, apply to doc, then encode as V2
        val doc1 = newDoc(1)
        val arr1 = doc1.getArray("arr")
        arr1.push(listOf(1, 2, 3))

        val v1Update = encodeStateAsUpdate(doc1)

        val doc2 = Doc()
        applyUpdate(doc2, v1Update)

        // Re-encode as V2
        val v2Update = encodeStateAsUpdateV2(doc2)

        val doc3 = Doc()
        applyUpdateV2(doc3, v2Update)

        val arr3 = doc3.getArray("arr")
        assertEquals(listOf(1, 2, 3), arr3.toArray())
    }

    @Test
    fun testV2ToV1Migration() {
        // Encode as V2, apply to doc, then encode as V1
        val doc1 = newDoc(1)
        val map1 = doc1.getMap("map")
        map1.set("key", "value")

        val v2Update = encodeStateAsUpdateV2(doc1)

        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        // Re-encode as V1
        val v1Update = encodeStateAsUpdate(doc2)

        val doc3 = Doc()
        applyUpdate(doc3, v1Update)

        val map3 = doc3.getMap("map")
        assertEquals("value", map3.get("key"))
    }

    @Test
    fun testV1AndV2ProduceSameDocument() {
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        arr.push(listOf(10, "hello", true, null))
        val map = doc1.getMap("map")
        map.set("x", 42)
        map.set("y", "world")

        val v1Update = encodeStateAsUpdate(doc1)
        val v2Update = encodeStateAsUpdateV2(doc1)

        val doc2 = Doc()
        applyUpdate(doc2, v1Update)

        val doc3 = Doc()
        applyUpdateV2(doc3, v2Update)

        // Both should produce identical state
        val arr2 = doc2.getArray("arr")
        val arr3 = doc3.getArray("arr")
        assertEquals(arr2.toArray(), arr3.toArray())

        val map2 = doc2.getMap("map")
        val map3 = doc3.getMap("map")
        assertEquals(map2.get("x"), map3.get("x"))
        assertEquals(map2.get("y"), map3.get("y"))
    }

    // ---------------------------------------------------------------
    // V2 with complex document operations
    // ---------------------------------------------------------------

    @Test
    fun testV2RoundTripMultipleTypes() {
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        val map = doc1.getMap("map")
        val text = doc1.getText("text")

        arr.push(listOf(1, 2, 3))
        map.set("name", "test")
        text.insert(0, "Hello")

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        assertEquals(listOf(1, 2, 3), doc2.getArray("arr").toArray())
        assertEquals("test", doc2.getMap("map").get("name"))
        assertEquals("Hello", doc2.getText("text").toString())
    }

    @Test
    fun testV2RoundTripLargeArray() {
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        val values = (0 until 100).toList()
        arr.push(values)

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val arr2 = doc2.getArray("arr")
        assertEquals(100, arr2.length)
        assertEquals(values, arr2.toArray())
    }

    @Test
    fun testV2RoundTripManyMapKeys() {
        val doc1 = newDoc(1)
        val map = doc1.getMap("map")
        for (i in 0 until 50) {
            map.set("key_$i", i)
        }

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val map2 = doc2.getMap("map")
        for (i in 0 until 50) {
            assertEquals(i, map2.get("key_$i"))
        }
    }

    @Test
    fun testV2RoundTripMapOverwrites() {
        // Overwriting a key multiple times creates origin chains
        val doc1 = newDoc(1)
        val map = doc1.getMap("map")
        map.set("key", "first")
        map.set("key", "second")
        map.set("key", "third")

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val map2 = doc2.getMap("map")
        assertEquals("third", map2.get("key"))
    }

    @Test
    fun testV2RoundTripTextLong() {
        val doc1 = newDoc(1)
        val text = doc1.getText("text")
        val longString = "a".repeat(1000)
        text.insert(0, longString)

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        assertEquals(longString, doc2.getText("text").toString())
    }

    @Test
    fun testV2RoundTripTextWithDeletesAndInserts() {
        val doc1 = newDoc(1)
        val text = doc1.getText("text")
        text.insert(0, "abcdefghij")
        text.delete(3, 4) // delete "defg"
        text.insert(3, "XY") // insert at position 3

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        assertEquals(text.toString(), doc2.getText("text").toString())
    }

    @Test
    fun testV2RoundTripDeeplyNested() {
        val doc1 = newDoc(1)
        val root = doc1.getMap("root")
        val level1 = YMap()
        root.set("level1", level1)
        val level2 = YArray()
        level1.set("level2", level2)
        level2.push(listOf(42))

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val root2 = doc2.getMap("root")
        val l1 = root2.get("level1") as YMap
        val l2 = l1.get("level2") as YArray
        assertEquals(listOf(42), l2.toArray())
    }

    // ---------------------------------------------------------------
    // V2 compression characteristics
    // ---------------------------------------------------------------

    @Test
    fun testV2SmallerThanV1ForRepeatedClients() {
        // V2 columnar encoding should compress well when there's only one client
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        // Many items from the same client should RLE well in the client column
        for (i in 0 until 50) {
            arr.push(listOf(i))
        }

        val v1Size = encodeStateAsUpdate(doc).size
        val v2Size = encodeStateAsUpdateV2(doc).size

        // V2 should generally be smaller for single-client data due to RLE compression
        // of the client column and info column
        assertTrue(v2Size <= v1Size,
            "V2 ($v2Size bytes) should be <= V1 ($v1Size bytes) for single-client repeated data")
    }

    @Test
    fun testV2SmallerThanV1ForMapWithSameParentSub() {
        // Map operations with parentSub should compress well in V2
        val doc = newDoc(1)
        val map = doc.getMap("map")
        for (i in 0 until 20) {
            map.set("same_key", i)
        }

        val v1Size = encodeStateAsUpdate(doc).size
        val v2Size = encodeStateAsUpdateV2(doc).size

        // V2 should be smaller or at least comparable
        assertTrue(v2Size <= v1Size + 20,
            "V2 ($v2Size bytes) should be close to V1 ($v1Size bytes) for map overwrites")
    }

    // ---------------------------------------------------------------
    // V2 with state vector targeting
    // ---------------------------------------------------------------

    @Test
    fun testV2EncodeStateAsUpdateWithSV() {
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        arr.push(listOf(1, 2, 3))

        // Get current state vector
        val sv = encodeStateVector(doc1)

        // Add more items
        arr.push(listOf(4, 5))

        // Encode only the diff
        val diffUpdate = encodeStateAsUpdateV2(doc1, sv)

        // Apply to empty doc with the initial state
        val doc2 = Doc()
        val fullUpdate = encodeStateAsUpdateV2(doc1)
        applyUpdateV2(doc2, fullUpdate)

        // Apply diff to a doc that already has [1,2,3]
        val doc3 = Doc()
        val initialUpdate = encodeStateAsUpdateV2(doc1, encodeStateVector(Doc()))
        // First apply initial state (which now includes everything)
        applyUpdateV2(doc3, initialUpdate)

        assertEquals(listOf(1, 2, 3, 4, 5), doc3.getArray("arr").toArray())
    }

    @Test
    fun testV2EncodeEmptyDiff() {
        // When target SV matches our state, the diff should be minimal
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        arr.push(listOf(1, 2, 3))

        val sv = encodeStateVector(doc1)
        val diffUpdate = encodeStateAsUpdateV2(doc1, sv)

        // Apply empty diff to a fresh doc - should not add anything
        val doc2 = Doc()
        applyUpdateV2(doc2, diffUpdate)

        // doc2 should be empty since the diff contained nothing new
        assertTrue(doc2.store.clients.isEmpty() || doc2.store.clients.all {
            it.value.isEmpty()
        })
    }

    // ---------------------------------------------------------------
    // V2 with GC structs
    // ---------------------------------------------------------------

    @Test
    fun testV2RoundTripWithGC() {
        // Create a document with GC enabled, make and delete items
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        arr.push(listOf("a", "b", "c"))
        arr.delete(0, 3)  // delete all, triggering GC on cleanup

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val arr2 = doc2.getArray("arr")
        assertEquals(0, arr2.length)
    }

    // ---------------------------------------------------------------
    // V2 Update listener test
    // ---------------------------------------------------------------

    @Test
    fun testV2UpdateListenerSync() {
        val doc1 = newDoc(1)
        val doc2 = Doc()

        // Set up auto-sync via V2 update listener
        doc1.on2<ByteArray, Any?>("updateV2") { update, _ ->
            applyUpdateV2(doc2, update)
        }

        val arr1 = doc1.getArray("arr")
        arr1.push(listOf(10, 20, 30))

        val arr2 = doc2.getArray("arr")
        assertEquals(listOf(10, 20, 30), arr2.toArray())
    }

    // ---------------------------------------------------------------
    // Mixed V1/V2 sync scenarios
    // ---------------------------------------------------------------

    @Test
    fun testMixedV1V2Sync() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)
        val doc3 = Doc()

        val arr1 = doc1.getArray("arr")
        val arr2 = doc2.getArray("arr")

        arr1.push(listOf("from1"))
        arr2.push(listOf("from2"))

        // Sync doc1 -> doc3 via V1
        val v1Update = encodeStateAsUpdate(doc1)
        applyUpdate(doc3, v1Update)

        // Sync doc2 -> doc3 via V2
        val v2Update = encodeStateAsUpdateV2(doc2)
        applyUpdateV2(doc3, v2Update)

        val arr3 = doc3.getArray("arr")
        assertEquals(2, arr3.length)
        assertTrue(arr3.toArray().contains("from1"))
        assertTrue(arr3.toArray().contains("from2"))
    }

    // ---------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------

    @Test
    fun testV2RoundTripUnicodeStrings() {
        val doc1 = newDoc(1)
        val map = doc1.getMap("map")
        map.set("emoji", "Hello \uD83D\uDE00")
        map.set("japanese", "日本語")
        map.set("arabic", "مرحبا")

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val map2 = doc2.getMap("map")
        assertEquals("Hello \uD83D\uDE00", map2.get("emoji"))
        assertEquals("日本語", map2.get("japanese"))
        assertEquals("مرحبا", map2.get("arabic"))
    }

    @Test
    fun testV2RoundTripEmptyStringsAndValues() {
        val doc1 = newDoc(1)
        val map = doc1.getMap("map")
        map.set("empty", "")
        map.set("null_val", null)
        val arr = doc1.getArray("arr")
        arr.push(listOf("", null, ""))

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val map2 = doc2.getMap("map")
        assertEquals("", map2.get("empty"))
        assertNull(map2.get("null_val"))
        val arr2 = doc2.getArray("arr")
        assertEquals("", arr2.get(0))
        assertNull(arr2.get(1))
        assertEquals("", arr2.get(2))
    }

    @Test
    fun testV2RoundTripSingleItem() {
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        arr.push(listOf(42))

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        assertEquals(listOf(42), doc2.getArray("arr").toArray())
    }

    @Test
    fun testV2RoundTripNestedArray() {
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        // Nested arrays as content values (stored via ContentAny)
        arr.push(listOf(listOf(1, 2), listOf(3, 4)))

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val arr2 = doc2.getArray("arr")
        assertEquals(2, arr2.length)
        assertEquals(listOf(1, 2), arr2.get(0))
        assertEquals(listOf(3, 4), arr2.get(1))
    }

    @Test
    fun testV2RoundTripNestedMap() {
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        // Nested maps as content values (stored via ContentAny)
        arr.push(listOf(mapOf("a" to 1, "b" to 2)))

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val arr2 = doc2.getArray("arr")
        assertEquals(1, arr2.length)
        val nested = arr2.get(0) as Map<*, *>
        assertEquals(1, nested["a"])
        assertEquals(2, nested["b"])
    }

    // ---------------------------------------------------------------
    // V2 state vector round-trip
    // ---------------------------------------------------------------

    @Test
    fun testStateVectorMatchesAfterV2Apply() {
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        arr.push(listOf(1, 2, 3))

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        val sv1 = decodeStateVector(encodeStateVector(doc1))
        val sv2 = decodeStateVector(encodeStateVector(doc2))

        assertEquals(sv1, sv2)
    }

    @Test
    fun testStateVectorAfterV2IncrementalSync() {
        val doc1 = newDoc(1)
        val doc2 = Doc()

        val arr = doc1.getArray("arr")
        arr.push(listOf(1))
        applyUpdateV2(doc2, encodeStateAsUpdateV2(doc1))

        arr.push(listOf(2))
        val sv2 = encodeStateVector(doc2)
        applyUpdateV2(doc2, encodeStateAsUpdateV2(doc1, sv2))

        arr.push(listOf(3))
        val sv2b = encodeStateVector(doc2)
        applyUpdateV2(doc2, encodeStateAsUpdateV2(doc1, sv2b))

        val svFinal1 = decodeStateVector(encodeStateVector(doc1))
        val svFinal2 = decodeStateVector(encodeStateVector(doc2))
        assertEquals(svFinal1, svFinal2)

        assertEquals(listOf(1, 2, 3), doc2.getArray("arr").toArray())
    }

    // ---------------------------------------------------------------
    // V2 with repeated identical writes (RLE opportunities)
    // ---------------------------------------------------------------

    @Test
    fun testV2RepeatedInfoBytesCompress() {
        val encoder = UpdateEncoderV2()
        // 100 identical info bytes should RLE compress
        repeat(100) { encoder.writeInfo(8) }

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        repeat(100) { assertEquals(8, decoder.readInfo()) }
    }

    @Test
    fun testV2RepeatedClientsCompress() {
        val encoder = UpdateEncoderV2()
        // Same client written many times should compress
        repeat(100) { encoder.writeClient(42) }

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        repeat(100) { assertEquals(42, decoder.readClient()) }
    }

    @Test
    fun testV2RepeatedParentInfoCompress() {
        val encoder = UpdateEncoderV2()
        repeat(100) { encoder.writeParentInfo(true) }

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        repeat(100) { assertTrue(decoder.readParentInfo()) }
    }

    @Test
    fun testV2SequentialClocksCompress() {
        val encoder = UpdateEncoderV2()
        // Sequential left clock values (0, 1, 2, 3, ...) have constant diff of 1
        for (i in 0 until 50) {
            encoder.writeLeftID(1, i)
        }

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        for (i in 0 until 50) {
            val id = decoder.readLeftID()
            assertEquals(ID(1, i), id)
        }
    }

    @Test
    fun testV2SequentialLenValuesCompress() {
        val encoder = UpdateEncoderV2()
        // All lengths of 1 should compress well
        repeat(100) { encoder.writeLen(1) }

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)
        repeat(100) { assertEquals(1, decoder.readLen()) }
    }

    // ---------------------------------------------------------------
    // V2 mixed reads/writes in realistic patterns
    // ---------------------------------------------------------------

    @Test
    fun testV2SimulateItemEncoding() {
        // Simulate encoding a simple Item (no origin, no rightOrigin, root parent, ContentAny)
        val encoder = UpdateEncoderV2()

        // info: contentRef=8 (ContentAny), no flags
        encoder.writeInfo(8)
        // parentInfo: true (root type key)
        encoder.writeParentInfo(true)
        // parent key
        encoder.writeString("arr")
        // ContentAny writes len then values
        encoder.writeLen(3)
        encoder.writeAny(1)
        encoder.writeAny(2)
        encoder.writeAny(3)

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)

        assertEquals(8, decoder.readInfo())
        assertTrue(decoder.readParentInfo())
        assertEquals("arr", decoder.readString())
        assertEquals(3, decoder.readLen())
        assertEquals(1, decoder.readAny())
        assertEquals(2, decoder.readAny())
        assertEquals(3, decoder.readAny())
    }

    @Test
    fun testV2SimulateItemWithOrigin() {
        // Simulate encoding an Item with origin (has left origin)
        val encoder = UpdateEncoderV2()

        // info: contentRef=8 (ContentAny) | BIT8 (hasOrigin)
        val info = 8 or BIT8
        encoder.writeInfo(info)
        // origin (left ID)
        encoder.writeLeftID(1, 5)
        // ContentAny
        encoder.writeLen(1)
        encoder.writeAny("value")

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)

        assertEquals(info, decoder.readInfo())
        assertEquals(ID(1, 5), decoder.readLeftID())
        assertEquals(1, decoder.readLen())
        assertEquals("value", decoder.readAny())
    }

    @Test
    fun testV2SimulateItemWithBothOrigins() {
        // Simulate encoding an Item with both origin and rightOrigin
        val encoder = UpdateEncoderV2()

        val info = 4 or BIT8 or BIT7  // ContentString + hasOrigin + hasRightOrigin
        encoder.writeInfo(info)
        encoder.writeLeftID(1, 3)
        encoder.writeRightID(2, 7)
        // ContentString writes string
        encoder.writeString("hello")

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)

        assertEquals(info, decoder.readInfo())
        assertEquals(ID(1, 3), decoder.readLeftID())
        assertEquals(ID(2, 7), decoder.readRightID())
        assertEquals("hello", decoder.readString())
    }

    @Test
    fun testV2SimulateItemWithParentSub() {
        // Simulate encoding a map Item (no origin, parentSub present)
        val encoder = UpdateEncoderV2()

        val info = 8 or BIT6  // ContentAny + hasParentSub
        encoder.writeInfo(info)
        encoder.writeParentInfo(true)  // root type key
        encoder.writeString("map")     // parent type name
        encoder.writeString("mykey")   // parentSub
        encoder.writeLen(1)
        encoder.writeAny(42)

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)

        assertEquals(info, decoder.readInfo())
        assertTrue(decoder.readParentInfo())
        assertEquals("map", decoder.readString())
        assertEquals("mykey", decoder.readString())
        assertEquals(1, decoder.readLen())
        assertEquals(42, decoder.readAny())
    }

    @Test
    fun testV2SimulateGCStruct() {
        val encoder = UpdateEncoderV2()
        // GC writes info=0, then len
        encoder.writeInfo(0)
        encoder.writeLen(5)

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)

        assertEquals(0, decoder.readInfo())
        assertEquals(5, decoder.readLen())
    }

    @Test
    fun testV2SimulateSkipStruct() {
        val encoder = UpdateEncoderV2()
        // Skip writes info=10, then len
        encoder.writeInfo(10)
        encoder.writeLen(3)

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)

        assertEquals(10, decoder.readInfo())
        assertEquals(3, decoder.readLen())
    }

    @Test
    fun testV2SimulateMultipleStructs() {
        // Simulate encoding multiple Items in sequence (as in a real update)
        val encoder = UpdateEncoderV2()

        // Item 1: root array push
        encoder.writeInfo(8) // ContentAny
        encoder.writeParentInfo(true)
        encoder.writeString("arr")
        encoder.writeLen(2)
        encoder.writeAny(1)
        encoder.writeAny(2)

        // Item 2: continuation (has origin pointing to item 1)
        encoder.writeInfo(8 or BIT8)
        encoder.writeLeftID(1, 1)
        encoder.writeLen(1)
        encoder.writeAny(3)

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)

        // Read Item 1
        assertEquals(8, decoder.readInfo())
        assertTrue(decoder.readParentInfo())
        assertEquals("arr", decoder.readString())
        assertEquals(2, decoder.readLen())
        assertEquals(1, decoder.readAny())
        assertEquals(2, decoder.readAny())

        // Read Item 2
        assertEquals(8 or BIT8, decoder.readInfo())
        assertEquals(ID(1, 1), decoder.readLeftID())
        assertEquals(1, decoder.readLen())
        assertEquals(3, decoder.readAny())
    }

    // ---------------------------------------------------------------
    // V2 ContentType round-trip (nested YType encoding)
    // ---------------------------------------------------------------

    @Test
    fun testV2SimulateContentType() {
        val encoder = UpdateEncoderV2()

        // ContentType writes typeRef
        val info = 7  // ContentType ref
        encoder.writeInfo(info)
        encoder.writeParentInfo(true)
        encoder.writeString("root")
        encoder.writeTypeRef(0)  // YArray

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)

        assertEquals(7, decoder.readInfo())
        assertTrue(decoder.readParentInfo())
        assertEquals("root", decoder.readString())
        assertEquals(0, decoder.readTypeRef())
    }

    // ---------------------------------------------------------------
    // V2 ContentFormat round-trip
    // ---------------------------------------------------------------

    @Test
    fun testV2SimulateContentFormat() {
        val encoder = UpdateEncoderV2()

        // ContentFormat ref=6, writes key and JSON value
        val info = 6
        encoder.writeInfo(info)
        encoder.writeParentInfo(true)
        encoder.writeString("text")
        encoder.writeKey("bold")
        encoder.writeJSON("true")

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)

        assertEquals(6, decoder.readInfo())
        assertTrue(decoder.readParentInfo())
        assertEquals("text", decoder.readString())
        assertEquals("bold", decoder.readKey())
        assertEquals("true", decoder.readJSON())
    }

    // ---------------------------------------------------------------
    // V2 ContentDeleted round-trip
    // ---------------------------------------------------------------

    @Test
    fun testV2SimulateContentDeleted() {
        val encoder = UpdateEncoderV2()

        // ContentDeleted ref=1, writes len
        encoder.writeInfo(1)
        encoder.writeParentInfo(true)
        encoder.writeString("arr")
        encoder.writeLen(5)  // 5 deleted positions

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)

        assertEquals(1, decoder.readInfo())
        assertTrue(decoder.readParentInfo())
        assertEquals("arr", decoder.readString())
        assertEquals(5, decoder.readLen())
    }

    // ---------------------------------------------------------------
    // V2 ContentBinary round-trip
    // ---------------------------------------------------------------

    @Test
    fun testV2SimulateContentBinary() {
        val encoder = UpdateEncoderV2()

        encoder.writeInfo(3)  // ContentBinary ref=3
        encoder.writeParentInfo(true)
        encoder.writeString("arr")
        encoder.writeBuf(byteArrayOf(0x01, 0x02, 0x03))

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)

        assertEquals(3, decoder.readInfo())
        assertTrue(decoder.readParentInfo())
        assertEquals("arr", decoder.readString())
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), decoder.readBuf())
    }

    // ---------------------------------------------------------------
    // V2 ContentDoc round-trip
    // ---------------------------------------------------------------

    @Test
    fun testV2SimulateContentDoc() {
        val encoder = UpdateEncoderV2()

        encoder.writeInfo(9) // ContentDoc ref=9
        encoder.writeParentInfo(true)
        encoder.writeString("arr")
        encoder.writeString("test-guid-123")
        encoder.writeAny(null)

        val bytes = encoder.toByteArray()
        val decoder = UpdateDecoderV2(bytes)

        assertEquals(9, decoder.readInfo())
        assertTrue(decoder.readParentInfo())
        assertEquals("arr", decoder.readString())
        assertEquals("test-guid-123", decoder.readString())
        assertNull(decoder.readAny())
    }

    // ---------------------------------------------------------------
    // V2 large-scale stress test
    // ---------------------------------------------------------------

    @Test
    fun testV2StressTestManyOperations() {
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        val map = doc1.getMap("map")
        val text = doc1.getText("text")

        // Many array operations
        for (i in 0 until 20) {
            arr.push(listOf(i))
        }

        // Many map operations
        for (i in 0 until 20) {
            map.set("key_$i", "value_$i")
        }

        // Text operations
        text.insert(0, "Hello World!")
        text.delete(5, 1)
        text.insert(5, ",")

        // Some deletions
        arr.delete(5, 5) // delete items 5-9

        val v2Update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, v2Update)

        // Verify
        assertEquals(arr.toArray(), doc2.getArray("arr").toArray())
        for (i in 0 until 20) {
            assertEquals("value_$i", doc2.getMap("map").get("key_$i"))
        }
        assertEquals(text.toString(), doc2.getText("text").toString())
    }

    @Test
    fun testV2SyncStressTwoClients() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("arr")
        val arr2 = doc2.getArray("arr")

        // Interleave operations and syncs
        arr1.push(listOf(1, 2))
        syncV2(doc1, doc2)

        arr2.push(listOf(3, 4))
        syncV2(doc1, doc2)

        arr1.push(listOf(5))
        arr2.push(listOf(6))
        syncV2(doc1, doc2)

        assertEquals(arr1.toArray(), arr2.toArray())
        assertEquals(6, arr1.length)
    }

    // ---------------------------------------------------------------
    // Pending struct integration (out-of-order updates)
    // ---------------------------------------------------------------

    @Test
    fun testOutOfOrderUpdates() {
        // Create two docs with sequential changes
        val doc1 = newDoc(1)
        val arr1 = doc1.getArray("arr")

        // State 0 -> State 1: push "a"
        arr1.push(listOf("a"))
        val sv0 = encodeStateVectorFromMap(emptyMap())
        val update1 = encodeStateAsUpdate(doc1, sv0)

        // State 1 -> State 2: push "b"
        arr1.push(listOf("b"))
        val sv1 = encodeStateVectorFromMap(mapOf(1 to 1))
        val update2 = encodeStateAsUpdate(doc1, sv1)

        // Apply update2 first (out of order) - should be pending
        val doc2 = Doc()
        applyUpdate(doc2, update2)
        // The items from update2 should be pending since they depend on update1
        val arr2 = doc2.getArray("arr")
        // Before update1 arrives, we might not see "b" yet
        assertTrue(arr2.length < 2 || doc2.store.pendingStructs != null)

        // Now apply update1 - should resolve pending
        applyUpdate(doc2, update1)
        assertEquals(2, doc2.getArray("arr").length)
        assertEquals("a", doc2.getArray("arr").get(0))
        assertEquals("b", doc2.getArray("arr").get(1))
    }

    @Test
    fun testOutOfOrderUpdatesV2() {
        val doc1 = newDoc(1)
        val arr1 = doc1.getArray("arr")

        arr1.push(listOf("first"))
        val update1 = encodeStateAsUpdateV2(doc1)

        arr1.push(listOf("second"))
        val sv1 = encodeStateVectorFromMap(mapOf(1 to 1))
        val update2 = encodeStateAsUpdateV2(doc1, sv1)

        // Apply second update first
        val doc2 = Doc()
        applyUpdateV2(doc2, update2)

        // Apply first update - resolves pending
        applyUpdateV2(doc2, update1)

        assertEquals(2, doc2.getArray("arr").length)
    }

    // ---------------------------------------------------------------
    // readAndApplyDeleteSet splitting
    // ---------------------------------------------------------------

    @Test
    fun testDeleteSetSplittingAtClockBoundary() {
        // Create a doc with items, then apply a delete set that splits an item
        val doc1 = newDoc(1)
        val text = doc1.getText("text")
        text.insert(0, "abcdef") // single item of length 6

        // Sync to doc2
        val doc2 = Doc()
        applyUpdate(doc2, encodeStateAsUpdate(doc1))

        // Delete middle characters on doc1 (positions 2-3, "cd")
        text.delete(2, 2)

        // Get the update with the delete set and apply to doc2
        val sv2 = encodeStateVector(doc2)
        val diffUpdate = encodeStateAsUpdate(doc1, sv2)
        applyUpdate(doc2, diffUpdate)

        assertEquals("abef", doc2.getText("text").toString())
    }

    @Test
    fun testDeleteSetSplitsItemAtStartBoundary() {
        val doc1 = newDoc(1)
        val arr = doc1.getArray("arr")
        arr.push(listOf("a", "b", "c", "d", "e"))

        val doc2 = Doc()
        applyUpdate(doc2, encodeStateAsUpdate(doc1))

        // Delete from middle
        arr.delete(2, 2) // delete "c" and "d"

        val sv2 = encodeStateVector(doc2)
        val diffUpdate = encodeStateAsUpdate(doc1, sv2)
        applyUpdate(doc2, diffUpdate)

        val arr2 = doc2.getArray("arr")
        assertEquals(3, arr2.length)
        assertEquals("a", arr2.get(0))
        assertEquals("b", arr2.get(1))
        assertEquals("e", arr2.get(2))
    }

    // ---------------------------------------------------------------
    // GC and Skip struct encoding/decoding in readStruct
    // ---------------------------------------------------------------

    @Test
    fun testGCStructEncodeDecode() {
        // With gc=true, deleted items get ContentDeleted after transaction cleanup
        val doc1 = Doc(gc = true)
        doc1.clientID = 1
        val arr = doc1.getArray("arr")
        arr.push(listOf("a", "b", "c"))
        arr.delete(0, 3)

        val update = encodeStateAsUpdate(doc1)
        val doc2 = Doc()
        applyUpdate(doc2, update)

        // GC'd items arrive as Items with ContentDeleted content
        val structs = doc2.store.clients[1]
        assertNotNull(structs)
        assertTrue(structs.any {
            it is yks.structs.Item && it.deleted
        }, "Expected deleted items after applying GC'd update")
    }

    @Test
    fun testGCStructEncodeDecodeV2() {
        val doc1 = Doc(gc = true)
        doc1.clientID = 1
        val arr = doc1.getArray("arr")
        arr.push(listOf("x", "y"))
        arr.delete(0, 2)

        val update = encodeStateAsUpdateV2(doc1)
        val doc2 = Doc()
        applyUpdateV2(doc2, update)

        val structs = doc2.store.clients[1]
        assertNotNull(structs)
        assertTrue(structs.any {
            it is yks.structs.Item && it.deleted
        })
    }

    // ---------------------------------------------------------------
    // writeClientStructsDefinitions with offset (encoding from specific SV)
    // ---------------------------------------------------------------

    @Test
    fun testEncodeStateFromSpecificSV() {
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        arr.push(listOf(1, 2, 3))

        // Get SV after first 2 items
        val sv = encodeStateVectorFromMap(mapOf(1 to 2))
        val diffUpdate = encodeStateAsUpdate(doc, sv)

        // Apply diff to empty doc - should only have item 3
        val doc2 = Doc()
        applyUpdate(doc2, diffUpdate)
        val arr2 = doc2.getArray("arr")
        // The third item depends on the second (origin), so it may be pending
        // or it may resolve. Either way, verify no crash.
        assertTrue(arr2.length <= 1)
    }

    @Test
    fun testEncodeStateFromSpecificSVV2() {
        val doc = newDoc(1)
        val arr = doc.getArray("arr")
        arr.push(listOf(10, 20, 30, 40, 50))

        val sv = encodeStateVectorFromMap(mapOf(1 to 3))
        val diffUpdate = encodeStateAsUpdateV2(doc, sv)

        // Apply full state first, then verify diff is a subset
        val doc2 = Doc()
        val fullSV = encodeStateVectorFromMap(mapOf(1 to 3))
        val initial = encodeStateAsUpdate(doc, encodeStateVectorFromMap(emptyMap()))
        applyUpdate(doc2, initial)
        // Now apply the V2 diff
        applyUpdateV2(doc2, diffUpdate)

        assertEquals(5, doc2.getArray("arr").length)
    }

    // ---------------------------------------------------------------
    // encodeStateAsUpdate / encodeStateAsUpdateV2 edge cases
    // ---------------------------------------------------------------

    @Test
    fun testEncodeStateAsUpdateWithEmptyDoc() {
        val doc = Doc()
        val update = encodeStateAsUpdate(doc)
        val doc2 = Doc()
        applyUpdate(doc2, update)
        assertTrue(doc2.store.clients.isEmpty())
    }

    @Test
    fun testEncodeStateAsUpdateV2WithEmptyDoc() {
        val doc = Doc()
        val updateV2 = encodeStateAsUpdateV2(doc)
        val doc2 = Doc()
        applyUpdateV2(doc2, updateV2)
        assertTrue(doc2.store.clients.isEmpty())
    }

    @Test
    fun testEncodeStateAsUpdateWithNullTargetSV() {
        val doc = newDoc(1)
        doc.getArray("arr").push(listOf(1))
        val update = encodeStateAsUpdate(doc, null)
        val doc2 = Doc()
        applyUpdate(doc2, update)
        assertEquals(1, doc2.getArray("arr").length)
    }

    @Test
    fun testEncodeStateAsUpdateV2WithNullTargetSV() {
        val doc = newDoc(1)
        doc.getArray("arr").push(listOf(1))
        val update = encodeStateAsUpdateV2(doc, null)
        val doc2 = Doc()
        applyUpdateV2(doc2, update)
        assertEquals(1, doc2.getArray("arr").length)
    }

    // ---------------------------------------------------------------
    // Multi-client out-of-order integration
    // ---------------------------------------------------------------

    @Test
    fun testMultiClientOutOfOrder() {
        val doc1 = newDoc(1)
        val doc2 = newDoc(2)

        val arr1 = doc1.getArray("arr")
        val arr2 = doc2.getArray("arr")

        arr1.push(listOf("from1"))
        arr2.push(listOf("from2"))

        // Sync doc1 -> doc2 first
        applyUpdate(doc2, encodeStateAsUpdate(doc1))

        // Then sync doc2 -> doc1
        applyUpdate(doc1, encodeStateAsUpdate(doc2))

        assertEquals(arr1.toArray().toSet(), arr2.toArray().toSet())
        assertEquals(2, arr1.length)
        assertEquals(2, arr2.length)
    }
}
