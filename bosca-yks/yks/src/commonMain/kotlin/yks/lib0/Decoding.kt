package yks.lib0

/**
 * Binary decoder matching lib0/decoding.
 * Reads variable-length encoded data compatible with yjs wire format.
 */
class Decoder(private val buf: ByteArray, private var pos: Int = 0) {

    /** Number of bytes remaining. */
    val hasContent: Boolean get() = pos < buf.size

    /** Current read position. */
    val position: Int get() = pos

    /** Read a single byte. */
    fun readUint8(): Int {
        return buf[pos++].toInt() and 0xFF
    }

    /** Read a variable-length unsigned integer. */
    fun readVarUint(): Int {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = readUint8()
            result = result or ((b and BITS7) shl shift)
            shift += 7
        } while (b and BIT8 != 0)
        return result
    }

    /** Read a variable-length unsigned long. */
    fun readVarUintLong(): Long {
        var result = 0L
        var shift = 0
        var b: Int
        do {
            b = readUint8()
            result = result or ((b.toLong() and BITS7.toLong()) shl shift)
            shift += 7
        } while (b and BIT8 != 0)
        return result
    }

    /**
     * Read a variable-length signed integer (zigzag encoding).
     * First byte: bit 7 = continuation, bit 6 = sign, bits 0-5 = data.
     */
    fun readVarInt(): Int {
        var b = readUint8()
        val isNegative = b and BIT7 != 0  // bit 6 = sign
        var result = b and BITS6
        var shift = 6
        if (b and BIT8 != 0) {  // bit 7 = continuation
            do {
                b = readUint8()
                result = result or ((b and BITS7) shl shift)
                shift += 7
            } while (b and BIT8 != 0)
        }
        return if (isNegative) -result else result
    }

    /**
     * Read a variable-length signed integer and also return whether the sign bit was set.
     * This is needed for UintOptRleDecoder to detect negative zero encoding.
     * Returns Pair(value, isNegative) where isNegative is true if the sign bit was set.
     */
    fun readVarIntWithSign(): Pair<Int, Boolean> {
        var b = readUint8()
        val isNegative = b and BIT7 != 0  // bit 6 = sign
        var result = b and BITS6
        var shift = 6
        if (b and BIT8 != 0) {  // bit 7 = continuation
            do {
                b = readUint8()
                result = result or ((b and BITS7) shl shift)
                shift += 7
            } while (b and BIT8 != 0)
        }
        return Pair(if (isNegative) -result else result, isNegative)
    }

    /** Read a variable-length signed long. */
    fun readVarIntLong(): Long {
        var b = readUint8()
        val isNegative = b and BIT7 != 0  // bit 6 = sign
        var result = (b and BITS6).toLong()
        var shift = 6
        if (b and BIT8 != 0) {  // bit 7 = continuation
            do {
                b = readUint8()
                result = result or ((b.toLong() and BITS7.toLong()) shl shift)
                shift += 7
            } while (b and BIT8 != 0)
        }
        return if (isNegative) -result else result
    }

    /** Read a UTF-8 string prefixed by its byte length. */
    fun readVarString(): String {
        val len = readVarUint()
        val bytes = readBytes(len)
        return bytes.decodeToString()
    }

    /** Read a byte array prefixed by its length. */
    fun readVarUint8Array(): ByteArray {
        val len = readVarUint()
        return readBytes(len)
    }

    /** Read exactly [len] bytes. */
    fun readBytes(len: Int): ByteArray {
        val result = buf.copyOfRange(pos, pos + len)
        pos += len
        return result
    }

    /** Read remaining bytes. */
    fun readTail(): ByteArray {
        val result = buf.copyOfRange(pos, buf.size)
        pos = buf.size
        return result
    }

    /** Read a uint16 in big-endian. */
    fun readUint16(): Int {
        val b0 = readUint8()
        val b1 = readUint8()
        return (b0 shl 8) or b1
    }

    /** Read a uint32 in big-endian. */
    fun readUint32(): Int {
        val b0 = readUint8()
        val b1 = readUint8()
        val b2 = readUint8()
        val b3 = readUint8()
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    /** Read a uint32 as Long (unsigned range). */
    fun readUint32BigEndian(): Long {
        return readUint32().toLong() and 0xFFFFFFFFL
    }

    /** Read a signed 64-bit integer in big-endian. Matches lib0 readBigInt64. */
    fun readBigInt64(): Long {
        val b7 = readUint8().toLong()
        val b6 = readUint8().toLong()
        val b5 = readUint8().toLong()
        val b4 = readUint8().toLong()
        val b3 = readUint8().toLong()
        val b2 = readUint8().toLong()
        val b1 = readUint8().toLong()
        val b0 = readUint8().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24) or
            (b4 shl 32) or (b5 shl 40) or (b6 shl 48) or (b7 shl 56)
    }

    /**
     * Read an arbitrary JSON-compatible value.
     * Matches lib0/decoding.readAny.
     */
    fun readAny(): Any? {
        return when (val tag = readUint8()) {
            127 -> null // undefined → null in Kotlin
            126 -> null
            125 -> readVarInt() // integer
            124 -> readFloat32().toDouble() // convert to Double to match JS semantics (all numbers are float64)
            123 -> readFloat64()
            122 -> readBigInt64() // bigint → Long
            121 -> false
            120 -> true
            119 -> readVarString()
            118 -> { // object/map
                val len = readVarUint()
                val map = LinkedHashMap<String, Any?>(len)
                repeat(len) {
                    val key = readVarString()
                    map[key] = readAny()
                }
                map
            }
            117 -> { // array
                val len = readVarUint()
                val list = ArrayList<Any?>(len)
                repeat(len) {
                    list.add(readAny())
                }
                list
            }
            116 -> readVarUint8Array() // Uint8Array
            else -> throw IllegalStateException("Unknown readAny tag: $tag")
        }
    }

    private fun readFloat32(): Float {
        // Big-endian byte order (matching yjs DataView with littleEndian=false)
        val b3 = readUint8()
        val b2 = readUint8()
        val b1 = readUint8()
        val b0 = readUint8()
        val bits = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        return Float.fromBits(bits)
    }

    private fun readFloat64(): Double {
        // Big-endian byte order (matching yjs DataView with littleEndian=false)
        val b7 = readUint8().toLong()
        val b6 = readUint8().toLong()
        val b5 = readUint8().toLong()
        val b4 = readUint8().toLong()
        val b3 = readUint8().toLong()
        val b2 = readUint8().toLong()
        val b1 = readUint8().toLong()
        val b0 = readUint8().toLong()
        val bits = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24) or
            (b4 shl 32) or (b5 shl 40) or (b6 shl 48) or (b7 shl 56)
        return Double.fromBits(bits)
    }

    /** Clone this decoder at the current position. */
    fun clone(): Decoder = Decoder(buf, pos)

    /** Skip [n] bytes. */
    fun skip(n: Int) {
        pos += n
    }
}
