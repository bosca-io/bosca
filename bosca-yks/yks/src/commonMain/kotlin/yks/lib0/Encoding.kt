package yks.lib0

/**
 * Check if a Double can be exactly represented as a 32-bit float.
 * Uses Double→Float→Double bit-level round-trip.
 * On Kotlin/JS, Float.toRawBits() properly truncates via Float32Array.
 * On Kotlin/JVM and Native, toFloat() properly truncates.
 * Matches yjs lib0 isFloat32 behavior.
 */
private fun isFloat32(num: Double): Boolean {
    // Round-trip through float32 bit representation to check precision.
    // 1. Get float32 bits (truncating from float64 if needed)
    // 2. Reconstruct float64 from float32 bits
    // 3. Compare with original
    val float32Bits = num.toFloat().toRawBits()
    val asFloat64 = Float.fromBits(float32Bits).toDouble()
    // On Kotlin/JS, toDouble() is a no-op so we also need to compare the float64 bits
    // to ensure no precision was lost.
    return num.toRawBits() == asFloat64.toRawBits()
}

/**
 * Binary encoder matching lib0/encoding.
 * Uses variable-length encoding compatible with yjs wire format.
 */
class Encoder {
    private var buf = ByteArray(128)
    private var pos = 0

    private fun ensureCapacity(needed: Int) {
        if (pos + needed > buf.size) {
            val newBuf = ByteArray(maxOf(buf.size * 2, pos + needed))
            buf.copyInto(newBuf)
            buf = newBuf
        }
    }

    /** Write a single byte. */
    fun writeUint8(value: Int) {
        ensureCapacity(1)
        buf[pos++] = (value and 0xFF).toByte()
    }

    /** Write a variable-length unsigned integer (1-5 bytes). Treats value as unsigned uint32. */
    fun writeVarUint(value: Int) {
        // Use Long to handle full uint32 range (negative signed ints = large unsigned values)
        var v = value.toLong() and 0xFFFFFFFFL
        while (v > BITS7.toLong()) {
            ensureCapacity(1)
            buf[pos++] = (BIT8.toLong() or (v and BITS7.toLong())).toByte()
            v = v ushr 7
        }
        ensureCapacity(1)
        buf[pos++] = (v and BITS7.toLong()).toByte()
    }

    /** Write a variable-length unsigned long (1-9 bytes). */
    fun writeVarUint(value: Long) {
        var v = value
        while (v > BITS7.toLong()) {
            ensureCapacity(1)
            buf[pos++] = (BIT8.toLong() or (v and BITS7.toLong())).toByte()
            v = v ushr 7
        }
        ensureCapacity(1)
        buf[pos++] = (v and BITS7.toLong()).toByte()
    }

    /**
     * Write a variable-length signed integer using zigzag encoding.
     * Positive values are encoded as 2*n, negative as 2*(-n)-1.
     */
    fun writeVarInt(value: Int) {
        // Use Long internally to avoid overflow when negating Int.MIN_VALUE
        writeVarInt(value.toLong())
    }

    /** Write a variable-length signed long. */
    fun writeVarInt(value: Long) {
        val isNegative = value < 0
        var v = if (isNegative) -value else value
        // First byte: bit 7 (BIT8=128) = continuation, bit 6 (BIT7=64) = sign, bits 0-5 = data
        ensureCapacity(1)
        if (v > BITS6.toLong()) {
            buf[pos++] = ((if (isNegative) BIT7 else 0) or BIT8 or (v.toInt() and BITS6)).toByte()
            v = v ushr 6
            while (v > BITS7.toLong()) {
                ensureCapacity(1)
                buf[pos++] = (BIT8 or (v.toInt() and BITS7)).toByte()
                v = v ushr 7
            }
            ensureCapacity(1)
            buf[pos++] = (v.toInt() and BITS7).toByte()
        } else {
            buf[pos++] = ((if (isNegative) BIT7 else 0) or (v.toInt() and BITS6)).toByte()
        }
    }

    /** Write a UTF-8 encoded string prefixed by its byte length. */
    fun writeVarString(value: String) {
        val bytes = value.encodeToByteArray()
        writeVarUint(bytes.size)
        writeBytes(bytes)
    }

    /** Write raw bytes. */
    fun writeBytes(bytes: ByteArray) {
        ensureCapacity(bytes.size)
        bytes.copyInto(buf, pos)
        pos += bytes.size
    }

    /** Write a Uint8Array prefixed by its length. */
    fun writeVarUint8Array(bytes: ByteArray) {
        writeVarUint(bytes.size)
        writeBytes(bytes)
    }

    /** Write a uint16 in big-endian. */
    fun writeUint16(value: Int) {
        ensureCapacity(2)
        buf[pos++] = ((value ushr 8) and 0xFF).toByte()
        buf[pos++] = (value and 0xFF).toByte()
    }

    /** Write a uint32 in big-endian. */
    fun writeUint32(value: Int) {
        ensureCapacity(4)
        buf[pos++] = ((value ushr 24) and 0xFF).toByte()
        buf[pos++] = ((value ushr 16) and 0xFF).toByte()
        buf[pos++] = ((value ushr 8) and 0xFF).toByte()
        buf[pos++] = (value and 0xFF).toByte()
    }

    /** Write a uint32 in big-endian (from Long to handle unsigned range). */
    fun writeUint32BigEndian(value: Long) {
        ensureCapacity(4)
        buf[pos++] = ((value ushr 24) and 0xFF).toByte()
        buf[pos++] = ((value ushr 16) and 0xFF).toByte()
        buf[pos++] = ((value ushr 8) and 0xFF).toByte()
        buf[pos++] = (value and 0xFF).toByte()
    }

    /**
     * Write an arbitrary JSON-compatible value.
     * Encoding matches lib0/encoding.writeAny:
     * - undefined (127), null (126), int (125), float32 (124), float64 (123),
     *   bigint (122), false (121), true (120), string (119), Map (118),
     *   Array (117), Uint8Array (116)
     */
    fun writeAny(value: Any?) {
        when (value) {
            null -> writeUint8(126) // null
            is Boolean -> writeUint8(if (value) 120 else 121)
            is Number -> {
                // On Kotlin/JS, all numbers match `is Int`, `is Double`, and `is Float`.
                // Use explicit runtime checks to dispatch correctly.
                // yjs: number.isInteger(data) && math.abs(data) <= binary.BITS31 → tag 125
                // yjs: isFloat32(data) → tag 124, else → tag 123 (float64)
                val d = value.toDouble()
                if (d == d.toLong().toDouble() && !d.isInfinite() && !d.isNaN()
                    && kotlin.math.abs(d) <= BITS31.toDouble()) {
                    // Integer-valued number within BITS31 range — encode as VarInt (tag 125)
                    writeUint8(125)
                    writeVarInt(d.toLong().toInt())
                } else if (isFloat32(d)) {
                    // Can be exactly represented as float32 — encode as float32 (matching yjs isFloat32)
                    writeUint8(124)
                    writeFloat32(d.toFloat())
                } else {
                    // Fractional or large integer that needs full double precision — encode as float64
                    writeUint8(123)
                    writeFloat64(d)
                }
            }
            is String -> {
                writeUint8(119)
                writeVarString(value)
            }
            is Map<*, *> -> {
                writeUint8(118)
                writeVarUint(value.size)
                for ((k, v) in value) {
                    writeVarString(k.toString())
                    writeAny(v)
                }
            }
            is List<*> -> {
                writeUint8(117)
                writeVarUint(value.size)
                for (v in value) {
                    writeAny(v)
                }
            }
            is ByteArray -> {
                writeUint8(116)
                writeVarUint8Array(value)
            }
            else -> {
                // Treat as undefined
                writeUint8(127)
            }
        }
    }

    private fun writeFloat32(value: Float) {
        // Big-endian byte order (matching yjs DataView with littleEndian=false)
        val bits = value.toRawBits()
        ensureCapacity(4)
        buf[pos++] = ((bits ushr 24) and 0xFF).toByte()
        buf[pos++] = ((bits ushr 16) and 0xFF).toByte()
        buf[pos++] = ((bits ushr 8) and 0xFF).toByte()
        buf[pos++] = (bits and 0xFF).toByte()
    }

    private fun writeFloat64(value: Double) {
        // Big-endian byte order (matching yjs DataView with littleEndian=false)
        val bits = value.toRawBits()
        ensureCapacity(8)
        buf[pos++] = ((bits ushr 56) and 0xFF).toByte()
        buf[pos++] = ((bits ushr 48) and 0xFF).toByte()
        buf[pos++] = ((bits ushr 40) and 0xFF).toByte()
        buf[pos++] = ((bits ushr 32) and 0xFF).toByte()
        buf[pos++] = ((bits ushr 24) and 0xFF).toByte()
        buf[pos++] = ((bits ushr 16) and 0xFF).toByte()
        buf[pos++] = ((bits ushr 8) and 0xFF).toByte()
        buf[pos++] = (bits and 0xFF).toByte()
    }

    /** Write a signed 64-bit integer in big-endian. Matches lib0 writeBigInt64. */
    fun writeBigInt64(value: Long) {
        ensureCapacity(8)
        buf[pos++] = ((value ushr 56) and 0xFF).toByte()
        buf[pos++] = ((value ushr 48) and 0xFF).toByte()
        buf[pos++] = ((value ushr 40) and 0xFF).toByte()
        buf[pos++] = ((value ushr 32) and 0xFF).toByte()
        buf[pos++] = ((value ushr 24) and 0xFF).toByte()
        buf[pos++] = ((value ushr 16) and 0xFF).toByte()
        buf[pos++] = ((value ushr 8) and 0xFF).toByte()
        buf[pos++] = (value and 0xFF).toByte()
    }

    /** Get the current length of written data. */
    val length: Int get() = pos

    /** Return the encoded bytes as a new ByteArray. */
    fun toByteArray(): ByteArray = buf.copyOf(pos)
}

/** Create an encoder and run the block on it, returning the bytes. */
inline fun encode(block: Encoder.() -> Unit): ByteArray {
    val encoder = Encoder()
    encoder.block()
    return encoder.toByteArray()
}
