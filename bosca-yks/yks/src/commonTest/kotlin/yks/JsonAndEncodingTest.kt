package yks

import yks.lib0.*
import kotlin.test.*

class JsonAndEncodingTest {

    // ============================================================
    // Part 1: JSON utilities — jsonStringify
    // ============================================================

    @Test
    fun testStringifyNull() {
        assertEquals("null", jsonStringify(null))
    }

    @Test
    fun testStringifyBooleans() {
        assertEquals("true", jsonStringify(true))
        assertEquals("false", jsonStringify(false))
    }

    @Test
    fun testStringifyInt() {
        assertEquals("0", jsonStringify(0))
        assertEquals("42", jsonStringify(42))
        assertEquals("-5", jsonStringify(-5))
        assertEquals("${Int.MAX_VALUE}", jsonStringify(Int.MAX_VALUE))
        assertEquals("${Int.MIN_VALUE}", jsonStringify(Int.MIN_VALUE))
    }

    @Test
    fun testStringifyLong() {
        val large = 9_000_000_000L
        assertEquals("9000000000", jsonStringify(large))
        assertEquals("${Long.MAX_VALUE}", jsonStringify(Long.MAX_VALUE))
    }

    @Test
    fun testStringifyDouble() {
        assertEquals("3.14", jsonStringify(3.14))
        // Double that equals a long value should print as long
        assertEquals("5", jsonStringify(5.0))
        assertEquals("0", jsonStringify(0.0))
        assertEquals("-3", jsonStringify(-3.0))
        // Non-integer double
        assertEquals("0.5", jsonStringify(0.5))
    }

    @Test
    fun testStringifyDoubleInfinity() {
        // Infinity cannot equal toLong().toDouble(), so it uses toString()
        val posInf = jsonStringify(Double.POSITIVE_INFINITY)
        assertTrue(posInf == "Infinity" || posInf == "null", "Unexpected: $posInf")
    }

    @Test
    fun testStringifyFloat() {
        // Float 5.0f should become Double 5.0 which equals toLong, so "5"
        assertEquals("5", jsonStringify(5.0f))
        // Float with fractional part
        val result = jsonStringify(3.14f)
        // Float precision may differ, just check it parses back close
        val parsed = result.toDouble()
        assertTrue(kotlin.math.abs(parsed - 3.14) < 0.01, "Float stringify: $result")
    }

    @Test
    fun testStringifySimpleString() {
        assertEquals("\"hello\"", jsonStringify("hello"))
        assertEquals("\"\"", jsonStringify(""))
    }

    @Test
    fun testStringifyStringEscapes() {
        // Quotes
        assertEquals("\"say \\\"hi\\\"\"", jsonStringify("say \"hi\""))
        // Backslash
        assertEquals("\"a\\\\b\"", jsonStringify("a\\b"))
        // Newline
        assertEquals("\"line1\\nline2\"", jsonStringify("line1\nline2"))
        // Tab
        assertEquals("\"col1\\tcol2\"", jsonStringify("col1\tcol2"))
        // Carriage return
        assertEquals("\"cr\\r\"", jsonStringify("cr\r"))
        // Backspace
        assertEquals("\"bs\\b\"", jsonStringify("bs\b"))
        // Form feed
        assertEquals("\"ff\\f\"", jsonStringify("ff\u000C"))
    }

    @Test
    fun testStringifyStringControlChars() {
        // Control char below 0x20 that is not one of the named escapes
        // e.g. \u0001
        val result = jsonStringify("\u0001")
        assertEquals("\"\\u0001\"", result)
        // \u001F
        val result2 = jsonStringify("\u001F")
        assertEquals("\"\\u001f\"", result2)
    }

    @Test
    fun testStringifyStringUnicode() {
        // Regular unicode characters (above 0x20) are NOT escaped — they appear literally
        assertEquals("\"caf\u00E9\"", jsonStringify("caf\u00E9"))
        assertEquals("\"\u65E5\u672C\u8A9E\"", jsonStringify("\u65E5\u672C\u8A9E"))
    }

    @Test
    fun testStringifyList() {
        assertEquals("[1,2,3]", jsonStringify(listOf(1, 2, 3)))
        assertEquals("[]", jsonStringify(emptyList<Any>()))
    }

    @Test
    fun testStringifyNestedList() {
        assertEquals("[[1,2],[3]]", jsonStringify(listOf(listOf(1, 2), listOf(3))))
    }

    @Test
    fun testStringifyListMixedTypes() {
        assertEquals("[1,\"two\",true,null]", jsonStringify(listOf(1, "two", true, null)))
    }

    @Test
    fun testStringifyMap() {
        assertEquals("{\"a\":1}", jsonStringify(mapOf("a" to 1)))
        assertEquals("{}", jsonStringify(emptyMap<String, Any>()))
    }

    @Test
    fun testStringifyMapMultipleKeys() {
        // LinkedHashMap preserves insertion order
        val map = linkedMapOf("x" to 10, "y" to 20)
        assertEquals("{\"x\":10,\"y\":20}", jsonStringify(map))
    }

    @Test
    fun testStringifyNestedMap() {
        val nested = mapOf("outer" to mapOf("inner" to 42))
        assertEquals("{\"outer\":{\"inner\":42}}", jsonStringify(nested))
    }

    @Test
    fun testStringifyUnknownType() {
        // An object of an unknown type should produce "null"
        class Weird
        assertEquals("null", jsonStringify(Weird()))
    }

    // ============================================================
    // Part 1: JSON utilities — jsonParse
    // ============================================================

    @Test
    fun testParseNull() {
        assertNull(jsonParse("null"))
    }

    @Test
    fun testParseBooleans() {
        assertEquals(true, jsonParse("true"))
        assertEquals(false, jsonParse("false"))
    }

    @Test
    fun testParseIntPositive() {
        assertEquals(42, jsonParse("42"))
    }

    @Test
    fun testParseIntNegative() {
        assertEquals(-5, jsonParse("-5"))
    }

    @Test
    fun testParseIntZero() {
        assertEquals(0, jsonParse("0"))
    }

    @Test
    fun testParseDouble() {
        assertEquals(3.14, jsonParse("3.14"))
    }

    @Test
    fun testParseScientificNotation() {
        val result = jsonParse("1e5")
        assertTrue(result is Double, "1e5 should parse as Double, got ${result!!::class}")
        assertEquals(100000.0, result)
    }

    @Test
    fun testParseScientificNotationNegativeExponent() {
        val result = jsonParse("5E-2")
        assertTrue(result is Double, "5E-2 should parse as Double")
        assertEquals(0.05, result, 1e-10)
    }

    @Test
    fun testParseLargeIntAsLong() {
        // Number too large for Int should become Long
        val result = jsonParse("9000000000")
        assertTrue(result is Long, "Large int should parse as Long, got ${result!!::class}")
        assertEquals(9_000_000_000L, result)
    }

    @Test
    fun testParseString() {
        assertEquals("hello", jsonParse("\"hello\""))
    }

    @Test
    fun testParseEmptyString() {
        assertEquals("", jsonParse("\"\""))
    }

    @Test
    fun testParseStringEscapes() {
        assertEquals("say \"hi\"", jsonParse("\"say \\\"hi\\\"\""))
        assertEquals("a\\b", jsonParse("\"a\\\\b\""))
        assertEquals("line1\nline2", jsonParse("\"line1\\nline2\""))
        assertEquals("col1\tcol2", jsonParse("\"col1\\tcol2\""))
        assertEquals("cr\r", jsonParse("\"cr\\r\""))
        assertEquals("bs\b", jsonParse("\"bs\\b\""))
        assertEquals("ff\u000C", jsonParse("\"ff\\f\""))
        assertEquals("a/b", jsonParse("\"a\\/b\""))
    }

    @Test
    fun testParseStringUnicodeEscape() {
        assertEquals("\u0041", jsonParse("\"\\u0041\"")) // 'A'
        assertEquals("\u00E9", jsonParse("\"\\u00e9\"")) // e-acute
    }

    @Test
    fun testParseArray() {
        val result = jsonParse("[1,2,3]") as List<*>
        assertEquals(listOf(1, 2, 3), result)
    }

    @Test
    fun testParseEmptyArray() {
        val result = jsonParse("[]") as List<*>
        assertTrue(result.isEmpty())
    }

    @Test
    fun testParseNestedArray() {
        val result = jsonParse("[[1,2],[3]]") as List<*>
        assertEquals(2, result.size)
        assertEquals(listOf(1, 2), result[0])
        assertEquals(listOf(3), result[1])
    }

    @Test
    fun testParseObject() {
        val result = jsonParse("{\"a\":1}") as Map<*, *>
        assertEquals(1, result["a"])
    }

    @Test
    fun testParseEmptyObject() {
        val result = jsonParse("{}") as Map<*, *>
        assertTrue(result.isEmpty())
    }

    @Test
    fun testParseObjectMultipleKeys() {
        val result = jsonParse("{\"x\":10,\"y\":20}") as Map<*, *>
        assertEquals(10, result["x"])
        assertEquals(20, result["y"])
    }

    @Test
    fun testParseNestedStructure() {
        val json = "{\"name\":\"alice\",\"scores\":[100,95],\"meta\":{\"active\":true}}"
        val result = jsonParse(json) as Map<*, *>
        assertEquals("alice", result["name"])
        assertEquals(listOf(100, 95), result["scores"])
        val meta = result["meta"] as Map<*, *>
        assertEquals(true, meta["active"])
    }

    @Test
    fun testParseWithWhitespace() {
        val json = "  { \"a\" : 1 , \"b\" : [ 2 , 3 ] }  "
        val result = jsonParse(json) as Map<*, *>
        assertEquals(1, result["a"])
        assertEquals(listOf(2, 3), result["b"])
    }

    // ============================================================
    // Part 1: JSON round-trips
    // ============================================================

    @Test
    fun testRoundTripNull() {
        assertNull(jsonParse(jsonStringify(null)))
    }

    @Test
    fun testRoundTripBooleans() {
        assertEquals(true, jsonParse(jsonStringify(true)))
        assertEquals(false, jsonParse(jsonStringify(false)))
    }

    @Test
    fun testRoundTripIntegers() {
        for (v in listOf(0, 1, -1, 42, -42, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(v, jsonParse(jsonStringify(v)), "Round trip failed for $v")
        }
    }

    @Test
    fun testRoundTripDouble() {
        // Doubles that are integer-valued round-trip as Int (since stringify produces "5", parse returns Int 5)
        assertEquals(5, jsonParse(jsonStringify(5.0)))
        // Non-integer doubles round-trip as Double
        assertEquals(3.14, jsonParse(jsonStringify(3.14)))
    }

    @Test
    fun testRoundTripStrings() {
        for (s in listOf("", "hello", "say \"hi\"", "a\\b", "line\nnewline", "\t\r\b\u000C")) {
            assertEquals(s, jsonParse(jsonStringify(s)), "Round trip failed for: ${s.map { it.code }}")
        }
    }

    @Test
    fun testRoundTripList() {
        val list = listOf(1, "two", true, null, listOf(3, 4))
        assertEquals(list, jsonParse(jsonStringify(list)))
    }

    @Test
    fun testRoundTripMap() {
        val map = mapOf("a" to 1, "b" to "two", "c" to listOf(true, null))
        assertEquals(map, jsonParse(jsonStringify(map)))
    }

    @Test
    fun testRoundTripNested() {
        val data = mapOf(
            "users" to listOf(
                mapOf("name" to "alice", "age" to 30),
                mapOf("name" to "bob", "age" to 25)
            ),
            "count" to 2
        )
        assertEquals(data, jsonParse(jsonStringify(data)))
    }

    // ============================================================
    // Part 2: Encoder/Decoder — writeAny/readAny all type tags
    // ============================================================

    @Test
    fun testWriteReadAnyNull() {
        // null writes tag 126
        val enc = Encoder()
        enc.writeAny(null)
        val bytes = enc.toByteArray()
        assertEquals(126, bytes[0].toInt() and 0xFF)
        val dec = Decoder(bytes)
        assertNull(dec.readAny())
    }

    @Test
    fun testWriteReadAnyUndefined() {
        // Unknown type writes tag 127 (undefined) which reads back as null
        val enc = Encoder()
        enc.writeUint8(127) // manually write undefined tag
        val dec = Decoder(enc.toByteArray())
        assertNull(dec.readAny())
    }

    @Test
    fun testWriteReadAnyUnknownTypeProducesUndefined() {
        // An object of unknown type → tag 127
        class Unknown
        val enc = Encoder()
        enc.writeAny(Unknown())
        val bytes = enc.toByteArray()
        assertEquals(127, bytes[0].toInt() and 0xFF)
        val dec = Decoder(bytes)
        assertNull(dec.readAny())
    }

    @Test
    fun testWriteReadAnyInt() {
        // Int writes tag 125
        // Note: Int.MIN_VALUE is excluded because negation overflows in writeVarInt
        for (v in listOf(0, 1, -1, 42, -42, 127, 128, -128, Int.MAX_VALUE, Int.MIN_VALUE + 1)) {
            val enc = Encoder()
            enc.writeAny(v)
            val bytes = enc.toByteArray()
            assertEquals(125, bytes[0].toInt() and 0xFF, "Tag mismatch for Int $v")
            val dec = Decoder(bytes)
            assertEquals(v, dec.readAny(), "readAny mismatch for Int $v")
        }
    }

    @Test
    fun testWriteReadAnyLong() {
        // Long values within BITS31 range use tag 125 (integer)
        for (v in listOf(0L, 1L, -1L)) {
            val enc = Encoder()
            enc.writeAny(v)
            val bytes = enc.toByteArray()
            assertEquals(125, bytes[0].toInt() and 0xFF, "Tag mismatch for Long $v")
            val dec = Decoder(bytes)
            val result = dec.readAny()
            assertEquals(v.toInt(), result, "readAny mismatch for Long $v")
        }
        // Large Longs outside BITS31 range are encoded as float64 (tag 123),
        // matching yjs behavior where large JS numbers use float64.
        // Note: precision may be lost for very large Longs.
        for (v in listOf(9_000_000_000L, -9_000_000_000L)) {
            val enc = Encoder()
            enc.writeAny(v)
            val bytes = enc.toByteArray()
            assertEquals(123, bytes[0].toInt() and 0xFF, "Tag mismatch for Long $v (should be float64)")
            val dec = Decoder(bytes)
            val result = dec.readAny()
            assertEquals(v.toDouble(), (result as Number).toDouble(), 0.0, "readAny mismatch for Long $v")
        }
    }

    @Test
    fun testWriteReadAnyFloat32() {
        // writeAny uses unified Number dispatch: integer-valued → tag 125, fractional → tag 123
        // Float32 (tag 124) is no longer written by writeAny
        for (v in listOf(0.0f, 1.5f, -1.5f, 3.14f)) {
            val enc = Encoder()
            enc.writeAny(v)
            val dec = Decoder(enc.toByteArray())
            val result = dec.readAny()
            assertTrue(result is Number, "readAny for Float should return Number, got ${result!!::class}")
            assertEquals(v.toDouble(), result.toDouble(), 1e-5, "readAny mismatch for Float $v")
        }
    }

    @Test
    fun testWriteReadAnyFloat64() {
        // Fractional doubles write tag 123 (float64), integer-valued doubles write tag 125 (int)
        for (v in listOf(1.5, -1.5, 3.141592653589793)) {
            val enc = Encoder()
            enc.writeAny(v)
            val dec = Decoder(enc.toByteArray())
            val result = dec.readAny()
            assertTrue(result is Double, "readAny for Double should return Double, got ${result!!::class}")
            assertEquals(v, result, "readAny mismatch for Double $v")
        }
        // Integer-valued doubles are stored as integers and read back as Int
        for (v in listOf(0.0, 1.0, -1.0)) {
            val enc = Encoder()
            enc.writeAny(v)
            val dec = Decoder(enc.toByteArray())
            val result = dec.readAny()
            assertTrue(result is Number, "readAny for integer Double should return Number")
            assertEquals(v, result.toDouble(), "readAny mismatch for integer Double $v")
        }
    }

    @Test
    fun testWriteReadAnyBigInt() {
        // Tag 122 is "bigint" → readBigInt64 returns Long (8 bytes big-endian)
        val enc = Encoder()
        enc.writeUint8(122)
        enc.writeBigInt64(42L)
        val dec = Decoder(enc.toByteArray())
        val result = dec.readAny()
        assertTrue(result is Long, "Tag 122 should produce Long, got ${result!!::class}")
        assertEquals(42L, result)
    }

    @Test
    fun testWriteReadAnyBigIntNegative() {
        val enc = Encoder()
        enc.writeUint8(122)
        enc.writeBigInt64(-999L)
        val dec = Decoder(enc.toByteArray())
        val result = dec.readAny()
        assertTrue(result is Long, "Tag 122 should produce Long")
        assertEquals(-999L, result)
    }

    @Test
    fun testWriteReadAnyFalse() {
        // false writes tag 121
        val enc = Encoder()
        enc.writeAny(false)
        val bytes = enc.toByteArray()
        assertEquals(121, bytes[0].toInt() and 0xFF)
        assertEquals(1, bytes.size)
        val dec = Decoder(bytes)
        assertEquals(false, dec.readAny())
    }

    @Test
    fun testWriteReadAnyTrue() {
        // true writes tag 120
        val enc = Encoder()
        enc.writeAny(true)
        val bytes = enc.toByteArray()
        assertEquals(120, bytes[0].toInt() and 0xFF)
        assertEquals(1, bytes.size)
        val dec = Decoder(bytes)
        assertEquals(true, dec.readAny())
    }

    @Test
    fun testWriteReadAnyString() {
        // String writes tag 119
        for (s in listOf("", "hello", "cafe\u0301", "\u65E5\u672C\u8A9E")) {
            val enc = Encoder()
            enc.writeAny(s)
            val bytes = enc.toByteArray()
            assertEquals(119, bytes[0].toInt() and 0xFF, "Tag mismatch for String '$s'")
            val dec = Decoder(bytes)
            assertEquals(s, dec.readAny(), "readAny mismatch for String '$s'")
        }
    }

    @Test
    fun testWriteReadAnyMap() {
        // Map writes tag 118
        val map = mapOf("key1" to 42, "key2" to "val", "key3" to true)
        val enc = Encoder()
        enc.writeAny(map)
        val bytes = enc.toByteArray()
        assertEquals(118, bytes[0].toInt() and 0xFF)
        val dec = Decoder(bytes)
        val result = dec.readAny() as Map<*, *>
        assertEquals(42, result["key1"])
        assertEquals("val", result["key2"])
        assertEquals(true, result["key3"])
    }

    @Test
    fun testWriteReadAnyEmptyMap() {
        val enc = Encoder()
        enc.writeAny(emptyMap<String, Any>())
        val bytes = enc.toByteArray()
        assertEquals(118, bytes[0].toInt() and 0xFF)
        val dec = Decoder(bytes)
        val result = dec.readAny() as Map<*, *>
        assertTrue(result.isEmpty())
    }

    @Test
    fun testWriteReadAnyArray() {
        // List writes tag 117
        val list = listOf(1, "two", null, false)
        val enc = Encoder()
        enc.writeAny(list)
        val bytes = enc.toByteArray()
        assertEquals(117, bytes[0].toInt() and 0xFF)
        val dec = Decoder(bytes)
        val result = dec.readAny() as List<*>
        assertEquals(4, result.size)
        assertEquals(1, result[0])
        assertEquals("two", result[1])
        assertNull(result[2])
        assertEquals(false, result[3])
    }

    @Test
    fun testWriteReadAnyEmptyArray() {
        val enc = Encoder()
        enc.writeAny(emptyList<Any>())
        val bytes = enc.toByteArray()
        assertEquals(117, bytes[0].toInt() and 0xFF)
        val dec = Decoder(bytes)
        val result = dec.readAny() as List<*>
        assertTrue(result.isEmpty())
    }

    @Test
    fun testWriteReadAnyUint8Array() {
        // ByteArray writes tag 116
        val data = byteArrayOf(0, 1, 127, -128, -1)
        val enc = Encoder()
        enc.writeAny(data)
        val bytes = enc.toByteArray()
        assertEquals(116, bytes[0].toInt() and 0xFF)
        val dec = Decoder(bytes)
        val result = dec.readAny()
        assertTrue(result is ByteArray, "Tag 116 should produce ByteArray, got ${result!!::class}")
        assertContentEquals(data, result)
    }

    @Test
    fun testWriteReadAnyUint8ArrayEmpty() {
        val enc = Encoder()
        enc.writeAny(byteArrayOf())
        val bytes = enc.toByteArray()
        assertEquals(116, bytes[0].toInt() and 0xFF)
        val dec = Decoder(bytes)
        val result = dec.readAny()
        assertTrue(result is ByteArray)
        assertEquals(0, result.size)
    }

    @Test
    fun testWriteReadAnyNestedStructure() {
        val value: Any = mapOf(
            "list" to listOf(1, 2, 3),
            "nested" to mapOf("a" to true),
            "bytes" to byteArrayOf(10, 20)
        )
        val enc = Encoder()
        enc.writeAny(value)
        val dec = Decoder(enc.toByteArray())
        val result = dec.readAny() as Map<*, *>
        assertEquals(listOf(1, 2, 3), result["list"])
        assertEquals(mapOf("a" to true), result["nested"])
        assertContentEquals(byteArrayOf(10, 20), result["bytes"] as ByteArray)
    }

    // ============================================================
    // Part 2: writeVarInt / readVarInt coverage
    // ============================================================

    @Test
    fun testVarIntZero() {
        val enc = Encoder()
        enc.writeVarInt(0)
        val dec = Decoder(enc.toByteArray())
        assertEquals(0, dec.readVarInt())
    }

    @Test
    fun testVarIntNegativeOne() {
        val enc = Encoder()
        enc.writeVarInt(-1)
        val dec = Decoder(enc.toByteArray())
        assertEquals(-1, dec.readVarInt())
    }

    @Test
    fun testVarIntEdgeCaseBits6Boundary() {
        // BITS6 = 63, so 63 fits in first byte, 64 needs continuation
        for (v in listOf(63, 64, -63, -64)) {
            val enc = Encoder()
            enc.writeVarInt(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readVarInt(), "VarInt edge case failed for $v")
        }
    }

    @Test
    fun testVarIntLargePositive() {
        for (v in listOf(1000, 100_000, 10_000_000, Int.MAX_VALUE)) {
            val enc = Encoder()
            enc.writeVarInt(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readVarInt(), "VarInt large positive failed for $v")
        }
    }

    @Test
    fun testVarIntLargeNegative() {
        for (v in listOf(-1000, -100_000, -10_000_000, Int.MIN_VALUE + 1)) {
            val enc = Encoder()
            enc.writeVarInt(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readVarInt(), "VarInt large negative failed for $v")
        }
    }

    @Test
    fun testVarIntLongRoundTrip() {
        for (v in listOf(0L, 1L, -1L, 63L, 64L, -63L, -64L, 100_000L, -100_000L)) {
            val enc = Encoder()
            enc.writeVarInt(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readVarIntLong(), "VarInt Long failed for $v")
        }
    }

    // ============================================================
    // Part 2: writeVarUint(Long) / readVarUintLong
    // ============================================================

    @Test
    fun testVarUintLongSmall() {
        for (v in listOf(0L, 1L, 127L, 128L)) {
            val enc = Encoder()
            enc.writeVarUint(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readVarUintLong(), "VarUint Long failed for $v")
        }
    }

    @Test
    fun testVarUintLongLarge() {
        for (v in listOf(256L, 65535L, 1_000_000L, 4_294_967_295L, 9_000_000_000L)) {
            val enc = Encoder()
            enc.writeVarUint(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readVarUintLong(), "VarUint Long large failed for $v")
        }
    }

    // ============================================================
    // Part 2: writeUint16 / readUint16
    // ============================================================

    @Test
    fun testUint16() {
        for (v in listOf(0, 1, 255, 256, 1000, 65535)) {
            val enc = Encoder()
            enc.writeUint16(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readUint16(), "Uint16 failed for $v")
        }
    }

    @Test
    fun testUint16ByteOrder() {
        // 0x1234 → bytes should be [0x12, 0x34] (big-endian)
        val enc = Encoder()
        enc.writeUint16(0x1234)
        val bytes = enc.toByteArray()
        assertEquals(2, bytes.size)
        assertEquals(0x12, bytes[0].toInt() and 0xFF)
        assertEquals(0x34, bytes[1].toInt() and 0xFF)
    }

    // ============================================================
    // Part 2: writeUint32 / readUint32
    // ============================================================

    @Test
    fun testUint32() {
        for (v in listOf(0, 1, 255, 256, 65535, 65536, 1_000_000, Int.MAX_VALUE)) {
            val enc = Encoder()
            enc.writeUint32(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readUint32(), "Uint32 failed for $v")
        }
    }

    @Test
    fun testUint32ByteOrder() {
        // 0x12345678 → bytes [0x12, 0x34, 0x56, 0x78]
        val enc = Encoder()
        enc.writeUint32(0x12345678)
        val bytes = enc.toByteArray()
        assertEquals(4, bytes.size)
        assertEquals(0x12, bytes[0].toInt() and 0xFF)
        assertEquals(0x34, bytes[1].toInt() and 0xFF)
        assertEquals(0x56, bytes[2].toInt() and 0xFF)
        assertEquals(0x78, bytes[3].toInt() and 0xFF)
    }

    // ============================================================
    // Part 2: writeUint32BigEndian / readUint32BigEndian
    // ============================================================

    @Test
    fun testUint32BigEndian() {
        for (v in listOf(0L, 1L, 255L, 65535L, 1_000_000L, 0xFFFFFFFFL)) {
            val enc = Encoder()
            enc.writeUint32BigEndian(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readUint32BigEndian(), "Uint32BigEndian failed for $v")
        }
    }

    @Test
    fun testUint32BigEndianFullRange() {
        // 0xFFFFFFFF (4294967295) should round-trip correctly through Long
        val enc = Encoder()
        enc.writeUint32BigEndian(0xFFFFFFFFL)
        val dec = Decoder(enc.toByteArray())
        assertEquals(4_294_967_295L, dec.readUint32BigEndian())
    }

    // ============================================================
    // Part 2: writeFloat32 / readFloat32 via writeAny/readAny
    // ============================================================

    @Test
    fun testFloat32ViaAny() {
        // writeAny dispatches all Number types via unified path
        // Fractional → float64, integer-valued → int
        val enc = Encoder()
        enc.writeAny(3.14f)
        val dec = Decoder(enc.toByteArray())
        val result = dec.readAny()
        assertTrue(result is Number, "Expected Number, got ${result!!::class}")
        assertEquals(3.14f.toDouble(), result.toDouble(), 1e-5)
    }

    @Test
    fun testFloat32SpecialValues() {
        // Infinity and NaN are encoded as float64
        for (v in listOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val enc = Encoder()
            enc.writeAny(v)
            val dec = Decoder(enc.toByteArray())
            val result = dec.readAny()
            assertTrue(result is Double, "Expected Double for $v")
            assertEquals(v.toDouble(), result)
        }
        // NaN
        val enc = Encoder()
        enc.writeAny(Float.NaN)
        val dec = Decoder(enc.toByteArray())
        val result = dec.readAny() as Double
        assertTrue(result.isNaN(), "Expected NaN")
    }

    // ============================================================
    // Part 2: writeFloat64 / readFloat64 via writeAny/readAny
    // ============================================================

    @Test
    fun testFloat64ViaAny() {
        // Fractional doubles round-trip through float64
        for (v in listOf(3.141592653589793, -2.718281828)) {
            val enc = Encoder()
            enc.writeAny(v)
            val dec = Decoder(enc.toByteArray())
            val result = dec.readAny()
            assertTrue(result is Double, "Expected Double for $v, got ${result!!::class}")
            assertEquals(v, result, "Float64 via Any failed for $v")
        }
        // Integer-valued doubles round-trip through int encoding
        for (v in listOf(0.0, 1.0, -1.0, 42.0)) {
            val enc = Encoder()
            enc.writeAny(v)
            val dec = Decoder(enc.toByteArray())
            val result = dec.readAny()
            assertTrue(result is Number)
            assertEquals(v, result.toDouble())
        }
    }

    @Test
    fun testFloat64SpecialValues() {
        for (v in listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val enc = Encoder()
            enc.writeAny(v)
            val dec = Decoder(enc.toByteArray())
            val result = dec.readAny()
            assertTrue(result is Double, "Expected Double for $v")
            assertEquals(v, result)
        }
        // NaN
        val enc = Encoder()
        enc.writeAny(Double.NaN)
        val dec = Decoder(enc.toByteArray())
        val result = dec.readAny() as Double
        assertTrue(result.isNaN(), "Expected NaN")
    }

    // ============================================================
    // Part 2: writeVarString / readVarString
    // ============================================================

    @Test
    fun testVarStringEmpty() {
        val enc = Encoder()
        enc.writeVarString("")
        val dec = Decoder(enc.toByteArray())
        assertEquals("", dec.readVarString())
    }

    @Test
    fun testVarStringAscii() {
        val s = "Hello, World!"
        val enc = Encoder()
        enc.writeVarString(s)
        val dec = Decoder(enc.toByteArray())
        assertEquals(s, dec.readVarString())
    }

    @Test
    fun testVarStringUnicode() {
        val strings = listOf(
            "cafe\u0301",           // combining accent
            "\u65E5\u672C\u8A9E",   // Japanese
            "\uD83D\uDE00",         // emoji (surrogate pair in UTF-16)
            "mixed ABC 123 \u00E9\u00E0\u00FC"
        )
        for (s in strings) {
            val enc = Encoder()
            enc.writeVarString(s)
            val dec = Decoder(enc.toByteArray())
            assertEquals(s, dec.readVarString(), "VarString unicode failed for: $s")
        }
    }

    @Test
    fun testVarStringLong() {
        val s = "a".repeat(10_000)
        val enc = Encoder()
        enc.writeVarString(s)
        val dec = Decoder(enc.toByteArray())
        assertEquals(s, dec.readVarString())
    }

    // ============================================================
    // Part 2: writeVarUint8Array / readVarUint8Array
    // ============================================================

    @Test
    fun testVarUint8ArrayEmpty() {
        val enc = Encoder()
        enc.writeVarUint8Array(byteArrayOf())
        val dec = Decoder(enc.toByteArray())
        assertContentEquals(byteArrayOf(), dec.readVarUint8Array())
    }

    @Test
    fun testVarUint8ArrayAllByteValues() {
        val data = ByteArray(256) { it.toByte() }
        val enc = Encoder()
        enc.writeVarUint8Array(data)
        val dec = Decoder(enc.toByteArray())
        assertContentEquals(data, dec.readVarUint8Array())
    }

    @Test
    fun testVarUint8ArrayLarge() {
        val data = ByteArray(1000) { (it % 256).toByte() }
        val enc = Encoder()
        enc.writeVarUint8Array(data)
        val dec = Decoder(enc.toByteArray())
        assertContentEquals(data, dec.readVarUint8Array())
    }

    // ============================================================
    // Part 2: readTail
    // ============================================================

    @Test
    fun testReadTail() {
        val enc = Encoder()
        enc.writeVarUint(42)
        enc.writeBytes(byteArrayOf(10, 20, 30))
        val dec = Decoder(enc.toByteArray())
        dec.readVarUint() // consume the varuint
        val tail = dec.readTail()
        assertContentEquals(byteArrayOf(10, 20, 30), tail)
        assertFalse(dec.hasContent, "Should have no content after readTail")
    }

    @Test
    fun testReadTailEmpty() {
        val enc = Encoder()
        enc.writeVarUint(1)
        val dec = Decoder(enc.toByteArray())
        dec.readVarUint()
        val tail = dec.readTail()
        assertEquals(0, tail.size, "Tail should be empty")
    }

    @Test
    fun testReadTailFromStart() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val dec = Decoder(data)
        val tail = dec.readTail()
        assertContentEquals(data, tail)
    }

    // ============================================================
    // Part 2: clone
    // ============================================================

    @Test
    fun testClone() {
        val enc = Encoder()
        enc.writeVarUint(42)
        enc.writeVarString("hello")
        val dec = Decoder(enc.toByteArray())
        dec.readVarUint() // advance past the uint

        val cloned = dec.clone()
        // Both should read the same string
        assertEquals("hello", dec.readVarString())
        assertEquals("hello", cloned.readVarString())
    }

    @Test
    fun testCloneIndependentPosition() {
        val enc = Encoder()
        enc.writeVarUint(1)
        enc.writeVarUint(2)
        enc.writeVarUint(3)
        val dec = Decoder(enc.toByteArray())
        dec.readVarUint() // read 1

        val cloned = dec.clone()
        // Original reads 2, clone reads 2 independently
        assertEquals(2, dec.readVarUint())
        assertEquals(2, cloned.readVarUint())
        // Now original reads 3, clone also reads 3
        assertEquals(3, dec.readVarUint())
        assertEquals(3, cloned.readVarUint())
    }

    // ============================================================
    // Part 2: skip
    // ============================================================

    @Test
    fun testSkip() {
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val dec = Decoder(data)
        dec.skip(2)
        assertEquals(30, dec.readUint8())
    }

    @Test
    fun testSkipZero() {
        val data = byteArrayOf(10, 20, 30)
        val dec = Decoder(data)
        dec.skip(0)
        assertEquals(10, dec.readUint8())
    }

    @Test
    fun testSkipThenReadTail() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val dec = Decoder(data)
        dec.skip(3)
        val tail = dec.readTail()
        assertContentEquals(byteArrayOf(4, 5), tail)
    }

    // ============================================================
    // Part 2: position and hasContent
    // ============================================================

    @Test
    fun testPositionTracking() {
        val enc = Encoder()
        enc.writeUint8(1)
        enc.writeUint8(2)
        enc.writeUint8(3)
        val dec = Decoder(enc.toByteArray())

        assertEquals(0, dec.position)
        assertTrue(dec.hasContent)

        dec.readUint8()
        assertEquals(1, dec.position)

        dec.readUint8()
        assertEquals(2, dec.position)

        dec.readUint8()
        assertEquals(3, dec.position)
        assertFalse(dec.hasContent)
    }

    // ============================================================
    // Part 2: encode {} helper function
    // ============================================================

    @Test
    fun testEncodeHelper() {
        val bytes = encode {
            writeVarUint(42)
            writeVarString("test")
        }
        val dec = Decoder(bytes)
        assertEquals(42, dec.readVarUint())
        assertEquals("test", dec.readVarString())
        assertFalse(dec.hasContent)
    }

    @Test
    fun testEncodeHelperEmpty() {
        val bytes = encode {}
        assertEquals(0, bytes.size)
    }

    @Test
    fun testEncodeHelperComplex() {
        val bytes = encode {
            writeAny(mapOf("key" to listOf(1, 2, 3)))
            writeUint16(0xBEEF)
            writeUint32BigEndian(0xDEADBEEFL)
        }
        val dec = Decoder(bytes)
        val map = dec.readAny() as Map<*, *>
        assertEquals(listOf(1, 2, 3), map["key"])
        assertEquals(0xBEEF, dec.readUint16())
        assertEquals(0xDEADBEEFL, dec.readUint32BigEndian())
        assertFalse(dec.hasContent)
    }

    // ============================================================
    // Part 2: Encoder.length
    // ============================================================

    @Test
    fun testEncoderLength() {
        val enc = Encoder()
        assertEquals(0, enc.length)
        enc.writeUint8(1)
        assertEquals(1, enc.length)
        enc.writeUint16(0x1234)
        assertEquals(3, enc.length)
        enc.writeUint32(0x12345678)
        assertEquals(7, enc.length)
    }

    // ============================================================
    // Part 2: Mixed sequential encoding/decoding
    // ============================================================

    @Test
    fun testMixedSequentialTypes() {
        val bytes = encode {
            writeAny(null)           // tag 126
            writeAny(true)           // tag 120
            writeAny(false)          // tag 121
            writeAny(42)             // tag 125 + varint
            writeAny(3.14)           // tag 123 + float64
            writeAny("hello")        // tag 119 + string
            writeAny(listOf(1, 2))   // tag 117 + array
            writeAny(mapOf("a" to 1))// tag 118 + map
            writeAny(byteArrayOf(9)) // tag 116 + bytes
        }
        val dec = Decoder(bytes)
        assertNull(dec.readAny())
        assertEquals(true, dec.readAny())
        assertEquals(false, dec.readAny())
        assertEquals(42, dec.readAny())
        assertEquals(3.14, dec.readAny())
        assertEquals("hello", dec.readAny())
        assertEquals(listOf(1, 2), dec.readAny())
        assertEquals(mapOf("a" to 1), dec.readAny())
        assertContentEquals(byteArrayOf(9), dec.readAny() as ByteArray)
        assertFalse(dec.hasContent)
    }

    @Test
    fun testUnknownTagThrows() {
        val enc = Encoder()
        enc.writeUint8(99) // not a valid readAny tag
        val dec = Decoder(enc.toByteArray())
        assertFailsWith<IllegalStateException> {
            dec.readAny()
        }
    }
}
