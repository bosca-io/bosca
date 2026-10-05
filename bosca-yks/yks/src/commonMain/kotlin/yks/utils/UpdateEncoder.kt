package yks.utils

import yks.lib0.*

/**
 * Abstract base for update encoders (V1 and V2).
 * Matches yjs UpdateEncoder interface.
 */
sealed class UpdateEncoder {
    abstract val restEncoder: Encoder

    abstract fun toByteArray(): ByteArray
    abstract fun writeLeftID(client: Int, clock: Int)
    abstract fun writeRightID(client: Int, clock: Int)
    abstract fun writeClient(client: Int)
    abstract fun writeInfo(info: Int)
    abstract fun writeString(value: String)
    abstract fun writeParentInfo(isYKey: Boolean)
    abstract fun writeTypeRef(ref: Int)
    abstract fun writeLen(len: Int)
    abstract fun writeAny(any: Any?)
    abstract fun writeBuf(buf: ByteArray)
    abstract fun writeJSON(str: String)
    abstract fun writeKey(key: String)

    // Delete set encoding (overridden by V2 for delta encoding)
    open fun resetDsCurVal() {}
    open fun writeDsClock(clock: Int) { restEncoder.writeVarUint(clock) }
    open fun writeDsLen(len: Int) { restEncoder.writeVarUint(len) }
}

/**
 * V1 encoder - simple sequential encoding.
 * Matches yjs UpdateEncoderV1.
 */
class UpdateEncoderV1 : UpdateEncoder() {
    override val restEncoder = Encoder()

    override fun toByteArray(): ByteArray = restEncoder.toByteArray()

    override fun writeLeftID(client: Int, clock: Int) {
        restEncoder.writeVarUint(client)
        restEncoder.writeVarUint(clock)
    }

    override fun writeRightID(client: Int, clock: Int) {
        restEncoder.writeVarUint(client)
        restEncoder.writeVarUint(clock)
    }

    override fun writeClient(client: Int) {
        restEncoder.writeVarUint(client)
    }

    override fun writeInfo(info: Int) {
        restEncoder.writeUint8(info)
    }

    override fun writeString(value: String) {
        restEncoder.writeVarString(value)
    }

    override fun writeParentInfo(isYKey: Boolean) {
        restEncoder.writeVarUint(if (isYKey) 1 else 0)
    }

    override fun writeTypeRef(ref: Int) {
        restEncoder.writeVarUint(ref)
    }

    override fun writeLen(len: Int) {
        restEncoder.writeVarUint(len)
    }

    override fun writeAny(any: Any?) {
        restEncoder.writeAny(any)
    }

    override fun writeBuf(buf: ByteArray) {
        restEncoder.writeVarUint8Array(buf)
    }

    override fun writeJSON(str: String) {
        restEncoder.writeVarString(str)
    }

    override fun writeKey(key: String) {
        restEncoder.writeVarString(key)
    }
}

/**
 * V2 encoder - columnar encoding with RLE compression.
 * Matches yjs UpdateEncoderV2.
 */
class UpdateEncoderV2 : UpdateEncoder() {
    val keyClockEncoder = IntDiffOptRleEncoder()
    val clientEncoder = UintOptRleEncoder()
    val leftClockEncoder = IntDiffOptRleEncoder()
    val rightClockEncoder = IntDiffOptRleEncoder()
    val infoEncoder = RleEncoder()
    val stringEncoder = StringEncoder()
    val parentInfoEncoder = RleEncoder()
    val typeRefEncoder = UintOptRleEncoder()
    val lenEncoder = UintOptRleEncoder()
    override val restEncoder = Encoder()

    // Key table for V2 key deduplication
    private val keyMap = mutableMapOf<String, Int>()
    private var keyClock = 0

    // Delete set delta encoding state
    private var dsCurrVal = 0

    override fun toByteArray(): ByteArray {
        val encoder = Encoder()
        encoder.writeVarUint(0) // feature flag
        encoder.writeVarUint8Array(keyClockEncoder.toByteArray())
        encoder.writeVarUint8Array(clientEncoder.toByteArray())
        encoder.writeVarUint8Array(leftClockEncoder.toByteArray())
        encoder.writeVarUint8Array(rightClockEncoder.toByteArray())
        encoder.writeVarUint8Array(infoEncoder.toByteArray())
        encoder.writeVarUint8Array(stringEncoder.toByteArray())
        encoder.writeVarUint8Array(parentInfoEncoder.toByteArray())
        encoder.writeVarUint8Array(typeRefEncoder.toByteArray())
        encoder.writeVarUint8Array(lenEncoder.toByteArray())
        encoder.writeBytes(restEncoder.toByteArray())
        return encoder.toByteArray()
    }

    override fun writeLeftID(client: Int, clock: Int) {
        clientEncoder.write(client)
        leftClockEncoder.write(clock)
    }

    override fun writeRightID(client: Int, clock: Int) {
        clientEncoder.write(client)
        rightClockEncoder.write(clock)
    }

    override fun writeClient(client: Int) {
        clientEncoder.write(client)
    }

    override fun writeInfo(info: Int) {
        infoEncoder.write(info)
    }

    override fun writeString(value: String) {
        stringEncoder.write(value)
    }

    override fun writeParentInfo(isYKey: Boolean) {
        parentInfoEncoder.write(if (isYKey) 1 else 0)
    }

    override fun writeTypeRef(ref: Int) {
        typeRefEncoder.write(ref)
    }

    override fun writeLen(len: Int) {
        lenEncoder.write(len)
    }

    override fun writeAny(any: Any?) {
        restEncoder.writeAny(any)
    }

    override fun writeBuf(buf: ByteArray) {
        restEncoder.writeVarUint8Array(buf)
    }

    override fun writeJSON(str: String) {
        // V2 uses writeAny instead of writeVarString (unlike V1)
        restEncoder.writeAny(str)
    }

    override fun writeKey(key: String) {
        val existingClock = keyMap[key]
        if (existingClock != null) {
            keyClockEncoder.write(existingClock)
        } else {
            keyClockEncoder.write(keyClock)
            keyMap[key] = keyClock++
            stringEncoder.write(key)
        }
    }

    override fun resetDsCurVal() {
        dsCurrVal = 0
    }

    override fun writeDsClock(clock: Int) {
        val diff = clock - dsCurrVal
        dsCurrVal = clock
        restEncoder.writeVarUint(diff)
    }

    override fun writeDsLen(len: Int) {
        if (len == 0) throw IllegalStateException("Unexpected zero-length delete")
        restEncoder.writeVarUint(len - 1)
        dsCurrVal += len
    }
}
