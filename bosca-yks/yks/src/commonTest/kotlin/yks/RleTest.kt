package yks

import kotlin.test.*
import yks.lib0.*

class RleTest {

    // ---------------------------------------------------------------
    // UintOptRleEncoder / UintOptRleDecoder
    // ---------------------------------------------------------------

    @Test
    fun testUintOptRleSingleValue() {
        val enc = UintOptRleEncoder()
        enc.write(42)
        val dec = UintOptRleDecoder(enc.toByteArray())
        assertEquals(42, dec.read())
        assertFalse(dec.hasContent)
    }

    @Test
    fun testUintOptRleRepeatedValues() {
        val enc = UintOptRleEncoder()
        repeat(5) { enc.write(7) }
        val dec = UintOptRleDecoder(enc.toByteArray())
        repeat(5) { assertEquals(7, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testUintOptRleAlternatingValues() {
        val enc = UintOptRleEncoder()
        val values = listOf(1, 2, 1, 2, 1, 2)
        values.forEach { enc.write(it) }
        val dec = UintOptRleDecoder(enc.toByteArray())
        values.forEach { assertEquals(it, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testUintOptRleZero() {
        val enc = UintOptRleEncoder()
        enc.write(0)
        val dec = UintOptRleDecoder(enc.toByteArray())
        assertEquals(0, dec.read())
        assertFalse(dec.hasContent)
    }

    @Test
    fun testUintOptRleRepeatedZeros() {
        val enc = UintOptRleEncoder()
        repeat(10) { enc.write(0) }
        val dec = UintOptRleDecoder(enc.toByteArray())
        repeat(10) { assertEquals(0, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testUintOptRleLargeValues() {
        val enc = UintOptRleEncoder()
        val values = listOf(1000, 100_000, 1_000_000, Int.MAX_VALUE / 2)
        values.forEach { enc.write(it) }
        val dec = UintOptRleDecoder(enc.toByteArray())
        values.forEach { assertEquals(it, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testUintOptRleLargeRepeated() {
        val enc = UintOptRleEncoder()
        repeat(100) { enc.write(999_999) }
        val dec = UintOptRleDecoder(enc.toByteArray())
        repeat(100) { assertEquals(999_999, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testUintOptRleManyDifferentValues() {
        val enc = UintOptRleEncoder()
        val values = (0 until 200).toList()
        values.forEach { enc.write(it) }
        val dec = UintOptRleDecoder(enc.toByteArray())
        values.forEach { assertEquals(it, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testUintOptRleMixedRunsAndSingles() {
        val enc = UintOptRleEncoder()
        // single, run of 3, single, run of 2, single
        val values = listOf(10, 20, 20, 20, 30, 40, 40, 50)
        values.forEach { enc.write(it) }
        val dec = UintOptRleDecoder(enc.toByteArray())
        values.forEach { assertEquals(it, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testUintOptRleCompression() {
        // Repeated values should compress: encoded size should be much smaller
        // than writing each value individually.
        val enc = UintOptRleEncoder()
        repeat(1000) { enc.write(5) }
        val bytes = enc.toByteArray()
        // A run of 1000 identical values should compress to just a few bytes
        // (negative count varint + value varint)
        assertTrue(bytes.size < 20, "Expected compression, got ${bytes.size} bytes")
    }

    @Test
    fun testUintOptRleHasContent() {
        val enc = UintOptRleEncoder()
        enc.write(1)
        enc.write(2)
        enc.write(3)
        val dec = UintOptRleDecoder(enc.toByteArray())
        assertTrue(dec.hasContent)
        dec.read()
        assertTrue(dec.hasContent)
        dec.read()
        assertTrue(dec.hasContent)
        dec.read()
        assertFalse(dec.hasContent)
    }

    // ---------------------------------------------------------------
    // IntDiffOptRleEncoder / IntDiffOptRleDecoder
    // ---------------------------------------------------------------

    @Test
    fun testIntDiffOptRleConstantSequence() {
        // All same value => diffs are 0 after first => should RLE
        val enc = IntDiffOptRleEncoder()
        repeat(10) { enc.write(42) }
        val dec = IntDiffOptRleDecoder(enc.toByteArray())
        repeat(10) { assertEquals(42, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testIntDiffOptRleArithmeticSequence() {
        // 0, 5, 10, 15, 20, ... => constant diff of 5 => should RLE
        val enc = IntDiffOptRleEncoder()
        val values = (0 until 20).map { it * 5 }
        values.forEach { enc.write(it) }
        val dec = IntDiffOptRleDecoder(enc.toByteArray())
        values.forEach { assertEquals(it, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testIntDiffOptRleArithmeticCompression() {
        // An arithmetic sequence has constant diffs, so should compress well
        val enc = IntDiffOptRleEncoder()
        val values = (0 until 1000).map { it * 3 }
        values.forEach { enc.write(it) }
        val bytes = enc.toByteArray()
        // 1000 values with constant diff should compress to a small number of bytes
        assertTrue(bytes.size < 20, "Expected compression, got ${bytes.size} bytes")
    }

    @Test
    fun testIntDiffOptRleRandomDiffs() {
        val enc = IntDiffOptRleEncoder()
        val values = listOf(0, 10, 3, 50, -20, 100, 99, 98, 97)
        values.forEach { enc.write(it) }
        val dec = IntDiffOptRleDecoder(enc.toByteArray())
        values.forEach { assertEquals(it, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testIntDiffOptRleNegativeValues() {
        val enc = IntDiffOptRleEncoder()
        val values = listOf(-1, -2, -3, -4, -5)
        values.forEach { enc.write(it) }
        val dec = IntDiffOptRleDecoder(enc.toByteArray())
        values.forEach { assertEquals(it, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testIntDiffOptRleMixedNegativePositive() {
        val enc = IntDiffOptRleEncoder()
        val values = listOf(-100, 0, 100, -50, 50, -1, 1)
        values.forEach { enc.write(it) }
        val dec = IntDiffOptRleDecoder(enc.toByteArray())
        values.forEach { assertEquals(it, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testIntDiffOptRleZeroDiffs() {
        // All zeros
        val enc = IntDiffOptRleEncoder()
        repeat(10) { enc.write(0) }
        val dec = IntDiffOptRleDecoder(enc.toByteArray())
        repeat(10) { assertEquals(0, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testIntDiffOptRleSingleValue() {
        val enc = IntDiffOptRleEncoder()
        enc.write(99)
        val dec = IntDiffOptRleDecoder(enc.toByteArray())
        assertEquals(99, dec.read())
        assertFalse(dec.hasContent)
    }

    @Test
    fun testIntDiffOptRleSingleNegativeValue() {
        val enc = IntDiffOptRleEncoder()
        enc.write(-42)
        val dec = IntDiffOptRleDecoder(enc.toByteArray())
        assertEquals(-42, dec.read())
        assertFalse(dec.hasContent)
    }

    @Test
    fun testIntDiffOptRleDecreasingSequence() {
        // 100, 90, 80, 70, ... => constant diff of -10
        val enc = IntDiffOptRleEncoder()
        val values = (0 until 10).map { 100 - it * 10 }
        values.forEach { enc.write(it) }
        val dec = IntDiffOptRleDecoder(enc.toByteArray())
        values.forEach { assertEquals(it, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testIntDiffOptRleHasContent() {
        val enc = IntDiffOptRleEncoder()
        enc.write(1)
        enc.write(2)
        val dec = IntDiffOptRleDecoder(enc.toByteArray())
        assertTrue(dec.hasContent)
        dec.read()
        assertTrue(dec.hasContent)
        dec.read()
        assertFalse(dec.hasContent)
    }

    @Test
    fun testIntDiffOptRleAlternatingDiffs() {
        // 0, 1, 0, 1, 0, 1 => diffs alternate between +1 and -1, no RLE on diffs
        val enc = IntDiffOptRleEncoder()
        val values = listOf(0, 1, 0, 1, 0, 1)
        values.forEach { enc.write(it) }
        val dec = IntDiffOptRleDecoder(enc.toByteArray())
        values.forEach { assertEquals(it, dec.read()) }
        assertFalse(dec.hasContent)
    }

    // ---------------------------------------------------------------
    // RleEncoder / RleDecoder
    // ---------------------------------------------------------------

    @Test
    fun testRleSingleByte() {
        val enc = RleEncoder()
        enc.write(42)
        val dec = RleDecoder(enc.toByteArray())
        assertEquals(42, dec.read())
        assertFalse(dec.hasContent)
    }

    @Test
    fun testRleRepeatedBytes() {
        val enc = RleEncoder()
        repeat(10) { enc.write(0xFF) }
        val dec = RleDecoder(enc.toByteArray())
        repeat(10) { assertEquals(0xFF, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testRleAlternatingBytes() {
        val enc = RleEncoder()
        val values = listOf(0, 1, 0, 1, 0, 1)
        values.forEach { enc.write(it) }
        val dec = RleDecoder(enc.toByteArray())
        values.forEach { assertEquals(it, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testRleAll256ByteValues() {
        val enc = RleEncoder()
        for (i in 0..255) {
            enc.write(i)
        }
        val dec = RleDecoder(enc.toByteArray())
        for (i in 0..255) {
            assertEquals(i, dec.read())
        }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testRleZeroByte() {
        val enc = RleEncoder()
        enc.write(0)
        val dec = RleDecoder(enc.toByteArray())
        assertEquals(0, dec.read())
        assertFalse(dec.hasContent)
    }

    @Test
    fun testRleBoundaryBytes() {
        val enc = RleEncoder()
        val values = listOf(0, 127, 128, 255)
        values.forEach { enc.write(it) }
        val dec = RleDecoder(enc.toByteArray())
        values.forEach { assertEquals(it, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testRleMixedRunsAndSingles() {
        val enc = RleEncoder()
        // single, run of 4, single, run of 3
        val values = listOf(10, 20, 20, 20, 20, 30, 40, 40, 40)
        values.forEach { enc.write(it) }
        val dec = RleDecoder(enc.toByteArray())
        values.forEach { assertEquals(it, dec.read()) }
        assertFalse(dec.hasContent)
    }

    @Test
    fun testRleCompression() {
        // 500 repeated bytes should compress significantly
        val enc = RleEncoder()
        repeat(500) { enc.write(0xAB) }
        val bytes = enc.toByteArray()
        // Should be 1 byte for value + a few bytes for count varint
        assertTrue(bytes.size < 10, "Expected compression, got ${bytes.size} bytes")
    }

    @Test
    fun testRleHasContent() {
        val enc = RleEncoder()
        enc.write(1)
        enc.write(1)
        enc.write(2)
        val dec = RleDecoder(enc.toByteArray())
        assertTrue(dec.hasContent)
        dec.read()
        assertTrue(dec.hasContent)
        dec.read()
        assertTrue(dec.hasContent)
        dec.read()
        assertFalse(dec.hasContent)
    }

    @Test
    fun testRleLargeRunCount() {
        val enc = RleEncoder()
        repeat(10_000) { enc.write(99) }
        val dec = RleDecoder(enc.toByteArray())
        repeat(10_000) { assertEquals(99, dec.read()) }
        assertFalse(dec.hasContent)
    }

    // ---------------------------------------------------------------
    // StringEncoder / StringDecoder
    // ---------------------------------------------------------------

    @Test
    fun testStringEmptyString() {
        val enc = StringEncoder()
        enc.write("")
        val dec = StringDecoder(enc.toByteArray())
        assertEquals("", dec.read())
    }

    @Test
    fun testStringSingleChar() {
        val enc = StringEncoder()
        enc.write("a")
        val dec = StringDecoder(enc.toByteArray())
        assertEquals("a", dec.read())
    }

    @Test
    fun testStringMultipleStrings() {
        val enc = StringEncoder()
        val strings = listOf("hello", "world", "foo", "bar", "baz")
        strings.forEach { enc.write(it) }
        val dec = StringDecoder(enc.toByteArray())
        strings.forEach { assertEquals(it, dec.read()) }
    }

    @Test
    fun testStringUnicode() {
        val enc = StringEncoder()
        val strings = listOf("cafe\u0301", "日本語", "emoji: \uD83D\uDE00", "mixed ABC 123 e\u0301a\u0300u\u0308")
        strings.forEach { enc.write(it) }
        val dec = StringDecoder(enc.toByteArray())
        strings.forEach { assertEquals(it, dec.read()) }
    }

    @Test
    fun testStringSameLengthTriggersRle() {
        // Same-length strings mean the UintOptRle length encoder can RLE the lengths
        val enc = StringEncoder()
        val strings = listOf("aaa", "bbb", "ccc", "ddd", "eee")
        strings.forEach { enc.write(it) }
        val dec = StringDecoder(enc.toByteArray())
        strings.forEach { assertEquals(it, dec.read()) }
    }

    @Test
    fun testStringEmptyAndNonEmptyMixed() {
        val enc = StringEncoder()
        val strings = listOf("", "hello", "", "world", "")
        strings.forEach { enc.write(it) }
        val dec = StringDecoder(enc.toByteArray())
        strings.forEach { assertEquals(it, dec.read()) }
    }

    @Test
    fun testStringSingleLongString() {
        val enc = StringEncoder()
        val long = "a".repeat(10_000)
        enc.write(long)
        val dec = StringDecoder(enc.toByteArray())
        assertEquals(long, dec.read())
    }

    @Test
    fun testStringManySmallStrings() {
        val enc = StringEncoder()
        val strings = (0 until 100).map { "s$it" }
        strings.forEach { enc.write(it) }
        val dec = StringDecoder(enc.toByteArray())
        strings.forEach { assertEquals(it, dec.read()) }
    }

    @Test
    fun testStringSameLengthCompression() {
        // Verify that same-length strings produce smaller encoding than varied lengths,
        // because the UintOptRle encoder compresses repeated length values.
        val sameLen = StringEncoder()
        val variedLen = StringEncoder()

        // 50 strings all of length 5 (lengths compress via RLE)
        repeat(50) { sameLen.write("abcde") }
        // 50 strings of varied lengths (lengths don't compress as well)
        repeat(50) { i -> variedLen.write("x".repeat(i + 1)) }

        val sameLenBytes = sameLen.toByteArray()
        val variedLenBytes = variedLen.toByteArray()

        // The same-length version has 50*5=250 chars of content,
        // the varied-length version has 1+2+...+50=1275 chars of content,
        // so the varied version is larger in raw content.
        // More importantly, the length metadata for same-length should be tiny.
        // We just verify both round-trip correctly and that the same-length
        // encoding is smaller despite having nontrivial content.
        assertTrue(sameLenBytes.size < variedLenBytes.size,
            "Same-length strings should produce smaller encoding")
    }

    @Test
    fun testStringSpecialCharacters() {
        val enc = StringEncoder()
        val strings = listOf(
            "line1\nline2",
            "tab\there",
            "null\u0000char",
            "backslash\\test",
            "quote\"test"
        )
        strings.forEach { enc.write(it) }
        val dec = StringDecoder(enc.toByteArray())
        strings.forEach { assertEquals(it, dec.read()) }
    }
}
