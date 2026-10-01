package yks.lib0

/**
 * Run-length encoder for unsigned integers.
 * Matches lib0 UintOptRleEncoder.
 *
 * Encoding:
 * - count==1: write state as VarInt (positive or zero)
 * - count>1: write -state as VarInt (negative or negative zero), then count-2 as VarUint
 */
class UintOptRleEncoder {
    private val encoder = Encoder()
    private var state = 0
    private var count = 0
    private var started = false

    fun write(value: Int) {
        if (started) {
            if (state == value) {
                count++
            } else {
                flush()
                state = value
                count = 1
            }
        } else {
            state = value
            count = 1
            started = true
        }
    }

    private fun flush() {
        if (count == 0) return
        if (count == 1) {
            // Single occurrence: write state as-is (positive or zero)
            encoder.writeVarInt(state)
        } else {
            // Multiple occurrences: write -state (negative or negative zero), then count-2
            if (state == 0) {
                // writeVarInt(0) writes positive zero, but we need negative zero
                // to signal "has count". Write a byte with just the sign bit set (0x40).
                encoder.writeUint8(BIT7) // 0x40 = negative zero in VarInt encoding
            } else {
                encoder.writeVarInt(-state)
            }
            encoder.writeVarUint(count - 2) // count >= 2
        }
    }

    fun toByteArray(): ByteArray {
        flush()
        count = 0
        return encoder.toByteArray()
    }
}

/**
 * Integer difference run-length encoder.
 * Matches lib0 IntDiffOptRleEncoder.
 *
 * Encodes differences between consecutive values with optional RLE.
 * The diff is encoded with the hasCount flag in bit 0 (diff << 1 | hasCount).
 */
class IntDiffOptRleEncoder {
    private val encoder = Encoder()
    private var state = 0
    private var count = 0
    private var diff = 0
    private var started = false

    fun write(value: Int) {
        if (started) {
            val newDiff = value - state
            if (count == 0) {
                diff = newDiff
                count = 1
                state = value
            } else if (diff == newDiff) {
                count++
                state = value
            } else {
                flush()
                diff = newDiff
                count = 1
                state = value
            }
        } else {
            diff = value
            count = 1
            state = value
            started = true
        }
    }

    private fun flush() {
        if (count == 0) return
        // Encode diff with hasCount flag in bit 0
        val encodedDiff = if (count == 1) {
            diff shl 1 // no count needed
        } else {
            (diff shl 1) or 1 // has count
        }
        encoder.writeVarInt(encodedDiff)
        if (count > 1) {
            encoder.writeVarUint(count - 2) // count-2 because count>=2
        }
    }

    fun toByteArray(): ByteArray {
        flush()
        count = 0
        return encoder.toByteArray()
    }
}

/**
 * Generic run-length encoder for bytes.
 * Matches lib0 RleEncoder for uint8 values.
 *
 * Encoding: writes value, then count-1 ONLY when a new value follows.
 * The last run does NOT have a count written (the decoder reads it forever).
 * This matches yjs lib0 RleEncoder behavior.
 */
class RleEncoder {
    private val encoder = Encoder()
    private var state: Int? = null
    private var count = 0

    fun write(value: Int) {
        if (state == value) {
            count++
        } else {
            if (count > 0) {
                // Flush the count for the previous run before writing the new value
                encoder.writeVarUint(count - 1)
            }
            count = 1
            // Write the value first
            encoder.writeUint8(value)
            state = value
        }
    }

    fun toByteArray(): ByteArray {
        // Do NOT flush the last count - the decoder handles this by reading the
        // last value forever when no count follows.
        // But we do need to write it if there are going to be more values after toByteArray...
        // Actually, in yjs the RleEncoder.toUint8Array is just the base Encoder.toUint8Array,
        // which doesn't flush. So we match that behavior.
        return encoder.toByteArray()
    }
}

/**
 * String encoder that accumulates all strings and encodes lengths with UintOptRle.
 * Matches lib0 StringEncoder.
 *
 * Output format: [VarString(concatenated)] [raw bytes from UintOptRleEncoder(lengths)]
 * Note: The lengths are written as raw bytes (not length-prefixed), matching yjs.
 */
class StringEncoder {
    private val sb = StringBuilder()
    private val lenEncoder = UintOptRleEncoder()

    fun write(value: String) {
        lenEncoder.write(value.length)
        sb.append(value)
    }

    fun toByteArray(): ByteArray {
        val encoder = Encoder()
        encoder.writeVarString(sb.toString())
        // Write lens as raw bytes (no length prefix), matching yjs writeUint8Array
        encoder.writeBytes(lenEncoder.toByteArray())
        return encoder.toByteArray()
    }
}
