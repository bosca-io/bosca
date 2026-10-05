package yks.lib0

/**
 * Run-length decoder for unsigned integers.
 * Matches lib0 UintOptRleDecoder.
 */
class UintOptRleDecoder(data: ByteArray) {
    private val decoder = Decoder(data)
    private var state = 0
    private var count = 0

    fun read(): Int {
        if (count == 0) {
            val (value, isNegative) = decoder.readVarIntWithSign()
            if (isNegative) {
                // Sign bit set (negative value or negative zero) means multiple occurrences
                state = -value // negate back to get the original state (handles negative zero: -0 = 0)
                count = decoder.readVarUint() + 2
            } else {
                // Positive or zero: single occurrence, value is the state directly
                count = 1
                state = value
            }
        }
        count--
        return state
    }

    val hasContent: Boolean get() = count > 0 || decoder.hasContent
}

/**
 * Integer difference run-length decoder.
 * Matches lib0 IntDiffOptRleDecoder.
 */
class IntDiffOptRleDecoder(data: ByteArray) {
    private val decoder = Decoder(data)
    private var state = 0
    private var count = 0
    private var diff = 0

    fun read(): Int {
        if (count == 0) {
            val encodedDiff = decoder.readVarInt()
            val hasCount = encodedDiff and 1 != 0
            diff = encodedDiff shr 1
            if (hasCount) {
                count = decoder.readVarUint() + 2 // stored as count-2
            } else {
                count = 1
            }
        }
        state += diff
        count--
        return state
    }

    val hasContent: Boolean get() = count > 0 || decoder.hasContent
}

/**
 * Generic run-length decoder for bytes.
 * Matches lib0 RleDecoder for uint8 values.
 *
 * Encoding: value is written first, then count-1 follows if there are more bytes.
 * If value is the last byte in the buffer (no count follows), it repeats forever.
 */
class RleDecoder(data: ByteArray) {
    private val decoder = Decoder(data)
    private var state = 0
    private var count = 0

    fun read(): Int {
        if (count == 0) {
            state = decoder.readUint8()
            if (decoder.hasContent) {
                count = decoder.readVarUint() + 1 // stored as count-1
            } else {
                count = -1 // read forever (last value in buffer)
            }
        }
        count--
        return state
    }

    val hasContent: Boolean get() = count > 0 || decoder.hasContent
}

/**
 * String decoder.
 * Matches lib0 StringDecoder.
 *
 * Input format: [VarString(concatenated)] [raw bytes from UintOptRleEncoder(lengths)]
 * Note: The lengths are raw bytes (not length-prefixed), matching yjs.
 */
class StringDecoder(data: ByteArray) {
    private val masterString: String
    private val lenDecoder: UintOptRleDecoder
    private var pos = 0

    init {
        val decoder = Decoder(data)
        masterString = decoder.readVarString()
        // Remaining bytes are the raw UintOptRle-encoded lengths (no length prefix)
        val lenBytes = decoder.readTail()
        lenDecoder = UintOptRleDecoder(lenBytes)
    }

    fun read(): String {
        val len = lenDecoder.read()
        val result = masterString.substring(pos, pos + len)
        pos += len
        return result
    }
}
