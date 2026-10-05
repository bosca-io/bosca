package yks

import yks.lib0.*
import yks.utils.*
import kotlin.test.*

class EncodingTest {

    @Test
    fun testVarUintSmall() {
        for (v in listOf(0, 1, 63, 64, 127)) {
            val enc = Encoder()
            enc.writeVarUint(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readVarUint())
        }
    }

    @Test
    fun testVarUintMedium() {
        for (v in listOf(128, 255, 256, 16383, 16384)) {
            val enc = Encoder()
            enc.writeVarUint(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readVarUint())
        }
    }

    @Test
    fun testVarUintLarge() {
        for (v in listOf(65535, 100000, 1000000, Int.MAX_VALUE)) {
            val enc = Encoder()
            enc.writeVarUint(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readVarUint())
        }
    }

    @Test
    fun testVarIntPositive() {
        for (v in listOf(0, 1, 63, 64, 127, 128, 1000, 100000)) {
            val enc = Encoder()
            enc.writeVarInt(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readVarInt())
        }
    }

    @Test
    fun testVarIntNegative() {
        for (v in listOf(-1, -2, -63, -64, -128, -1000, -100000)) {
            val enc = Encoder()
            enc.writeVarInt(v)
            val dec = Decoder(enc.toByteArray())
            assertEquals(v, dec.readVarInt())
        }
    }

    @Test
    fun testVarString() {
        for (s in listOf("", "a", "hello", "Hello, World!", "foo bar baz")) {
            val enc = Encoder()
            enc.writeVarString(s)
            val dec = Decoder(enc.toByteArray())
            assertEquals(s, dec.readVarString())
        }
    }

    @Test
    fun testVarStringUnicode() {
        val strings = listOf("café", "日本語", "emoji: 😀", "mixed ABC 123 éàü")
        for (s in strings) {
            val enc = Encoder()
            enc.writeVarString(s)
            val dec = Decoder(enc.toByteArray())
            assertEquals(s, dec.readVarString(), "Failed for string: $s")
        }
    }

    @Test
    fun testWriteAnyInt() {
        val enc = Encoder()
        enc.writeAny(42)
        val dec = Decoder(enc.toByteArray())
        assertEquals(42, dec.readAny())
    }

    @Test
    fun testWriteAnyString() {
        val enc = Encoder()
        enc.writeAny("hello")
        val dec = Decoder(enc.toByteArray())
        assertEquals("hello", dec.readAny())
    }

    @Test
    fun testWriteAnyNull() {
        val enc = Encoder()
        enc.writeAny(null)
        val dec = Decoder(enc.toByteArray())
        assertNull(dec.readAny())
    }

    @Test
    fun testWriteAnyBoolean() {
        val enc = Encoder()
        enc.writeAny(true)
        enc.writeAny(false)
        val dec = Decoder(enc.toByteArray())
        assertEquals(true, dec.readAny())
        assertEquals(false, dec.readAny())
    }

    @Test
    fun testWriteAnyDouble() {
        val enc = Encoder()
        val value: Any = 3.14 // explicit Any to test writeAny dispatch
        enc.writeAny(value)
        val dec = Decoder(enc.toByteArray())
        val tag = dec.readUint8()
        // Should be encoded as either float64 (123) or float32 (124)
        assertTrue(tag == 123 || tag == 124 || tag == 125, "Unexpected tag: $tag")
    }

    @Test
    fun testWriteAnyList() {
        val enc = Encoder()
        enc.writeAny(listOf(1, "two", true))
        val dec = Decoder(enc.toByteArray())
        val result = dec.readAny() as List<*>
        assertEquals(3, result.size)
        assertEquals(1, result[0])
        assertEquals("two", result[1])
        assertEquals(true, result[2])
    }

    @Test
    fun testWriteAnyMap() {
        val enc = Encoder()
        enc.writeAny(mapOf("a" to 1, "b" to "two"))
        val dec = Decoder(enc.toByteArray())
        val result = dec.readAny() as Map<*, *>
        assertEquals(1, result["a"])
        assertEquals("two", result["b"])
    }

    @Test
    fun testMultipleValues() {
        val enc = Encoder()
        enc.writeVarUint(42)
        enc.writeVarInt(-10)
        enc.writeVarString("test")
        enc.writeAny(true)
        enc.writeAny(listOf(1, 2))

        val dec = Decoder(enc.toByteArray())
        assertEquals(42, dec.readVarUint())
        assertEquals(-10, dec.readVarInt())
        assertEquals("test", dec.readVarString())
        assertEquals(true, dec.readAny())
        val list = dec.readAny() as List<*>
        assertEquals(listOf(1, 2), list)
    }

    @Test
    fun testStateVectorRoundTrip() {
        val doc = Doc()
        val arr = doc.getArray("test")
        arr.push(listOf(1, 2, 3, 4, 5))

        val sv = encodeStateVector(doc)
        val decoded = decodeStateVector(sv)
        val expected = getStateVector(doc.store)
        assertEquals(expected, decoded)
    }

    @Test
    fun testUint8() {
        val enc = Encoder()
        for (v in listOf(0, 1, 127, 128, 255)) {
            enc.writeUint8(v)
        }
        val dec = Decoder(enc.toByteArray())
        assertEquals(0, dec.readUint8())
        assertEquals(1, dec.readUint8())
        assertEquals(127, dec.readUint8())
        assertEquals(128, dec.readUint8())
        assertEquals(255, dec.readUint8())
    }

    @Test
    fun testByteArrayContent() {
        val enc = Encoder()
        val data = byteArrayOf(1, 2, 3, 4, 5)
        enc.writeVarUint8Array(data)
        val dec = Decoder(enc.toByteArray())
        assertContentEquals(data, dec.readVarUint8Array())
    }
}
