package yks.utils

import yks.lib0.*

/**
 * Abstract base for update decoders (V1 and V2).
 * Matches yjs UpdateDecoder interface.
 */
sealed class UpdateDecoder {
    abstract val restDecoder: Decoder

    abstract fun readLeftID(): ID
    abstract fun readRightID(): ID
    abstract fun readClient(): Int
    abstract fun readInfo(): Int
    abstract fun readString(): String
    abstract fun readParentInfo(): Boolean
    abstract fun readTypeRef(): Int
    abstract fun readLen(): Int
    abstract fun readAny(): Any?
    abstract fun readBuf(): ByteArray
    abstract fun readJSON(): String
    abstract fun readKey(): String

    // Delete set decoding (overridden by V2 for delta decoding)
    open fun resetDsCurVal() {}
    open fun readDsClock(): Int = restDecoder.readVarUint()
    open fun readDsLen(): Int = restDecoder.readVarUint()
}

/**
 * V1 decoder - simple sequential decoding.
 * Matches yjs UpdateDecoderV1.
 */
class UpdateDecoderV1(data: ByteArray) : UpdateDecoder() {
    override val restDecoder = Decoder(data)

    override fun readLeftID(): ID {
        val client = restDecoder.readVarUint()
        val clock = restDecoder.readVarUint()
        return ID(client, clock)
    }

    override fun readRightID(): ID {
        val client = restDecoder.readVarUint()
        val clock = restDecoder.readVarUint()
        return ID(client, clock)
    }

    override fun readClient(): Int = restDecoder.readVarUint()
    override fun readInfo(): Int = restDecoder.readUint8()
    override fun readString(): String = restDecoder.readVarString()
    override fun readParentInfo(): Boolean = restDecoder.readVarUint() == 1
    override fun readTypeRef(): Int = restDecoder.readVarUint()
    override fun readLen(): Int = restDecoder.readVarUint()
    override fun readAny(): Any? = restDecoder.readAny()
    override fun readBuf(): ByteArray = restDecoder.readVarUint8Array()
    override fun readJSON(): String = restDecoder.readVarString()
    override fun readKey(): String = restDecoder.readVarString()
}

/**
 * V2 decoder - columnar decoding with RLE compression.
 * Matches yjs UpdateDecoderV2.
 */
class UpdateDecoderV2(data: ByteArray) : UpdateDecoder() {
    private val outerDecoder = Decoder(data)
    val keyClockDecoder: IntDiffOptRleDecoder
    val clientDecoder: UintOptRleDecoder
    val leftClockDecoder: IntDiffOptRleDecoder
    val rightClockDecoder: IntDiffOptRleDecoder
    val infoDecoder: RleDecoder
    val stringDecoder: StringDecoder
    val parentInfoDecoder: RleDecoder
    val typeRefDecoder: UintOptRleDecoder
    val lenDecoder: UintOptRleDecoder
    override val restDecoder: Decoder

    // Key table for V2 key deduplication
    private val keys = mutableListOf<String>()

    // Delete set delta decoding state
    private var dsCurrVal = 0

    init {
        outerDecoder.readVarUint() // feature flag (currently unused)
        keyClockDecoder = IntDiffOptRleDecoder(outerDecoder.readVarUint8Array())
        clientDecoder = UintOptRleDecoder(outerDecoder.readVarUint8Array())
        leftClockDecoder = IntDiffOptRleDecoder(outerDecoder.readVarUint8Array())
        rightClockDecoder = IntDiffOptRleDecoder(outerDecoder.readVarUint8Array())
        infoDecoder = RleDecoder(outerDecoder.readVarUint8Array())
        stringDecoder = StringDecoder(outerDecoder.readVarUint8Array())
        parentInfoDecoder = RleDecoder(outerDecoder.readVarUint8Array())
        typeRefDecoder = UintOptRleDecoder(outerDecoder.readVarUint8Array())
        lenDecoder = UintOptRleDecoder(outerDecoder.readVarUint8Array())
        restDecoder = Decoder(outerDecoder.readTail())
    }

    override fun readLeftID(): ID {
        return ID(clientDecoder.read(), leftClockDecoder.read())
    }

    override fun readRightID(): ID {
        return ID(clientDecoder.read(), rightClockDecoder.read())
    }

    override fun readClient(): Int = clientDecoder.read()
    override fun readInfo(): Int = infoDecoder.read()
    override fun readString(): String = stringDecoder.read()
    override fun readParentInfo(): Boolean = parentInfoDecoder.read() == 1
    override fun readTypeRef(): Int = typeRefDecoder.read()
    override fun readLen(): Int = lenDecoder.read()
    override fun readAny(): Any? = restDecoder.readAny()
    override fun readBuf(): ByteArray = restDecoder.readVarUint8Array()
    override fun readJSON(): String {
        // V2 uses readAny instead of readVarString (unlike V1)
        return restDecoder.readAny() as String
    }
    override fun readKey(): String {
        val keyClock = keyClockDecoder.read()
        if (keyClock < keys.size) {
            return keys[keyClock]
        } else {
            val key = stringDecoder.read()
            keys.add(key)
            return key
        }
    }

    override fun resetDsCurVal() {
        dsCurrVal = 0
    }

    override fun readDsClock(): Int {
        dsCurrVal += restDecoder.readVarUint()
        return dsCurrVal
    }

    override fun readDsLen(): Int {
        val diff = restDecoder.readVarUint() + 1
        dsCurrVal += diff
        return diff
    }
}
