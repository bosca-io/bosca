package yks.sync

import yks.lib0.Decoder
import yks.lib0.Encoder
import yks.lib0.encode
import yks.utils.Doc

/**
 * Top-level message framing matching y-websocket message types.
 *
 * Each message starts with a varUint message type:
 * - 0 = sync (wraps SyncProtocol sub-messages)
 * - 1 = awareness (wraps encoded awareness update)
 * - 2 = auth (permission denied)
 * - 3 = query awareness (request all awareness states)
 */
object MessageProtocol {
    const val MESSAGE_SYNC = 0
    const val MESSAGE_AWARENESS = 1
    const val MESSAGE_AUTH = 2
    const val MESSAGE_QUERY_AWARENESS = 3

    /** Encode a top-level SyncStep1 message: varUint(0) • syncStep1(...) */
    fun encodeSyncStep1(doc: Doc): ByteArray = encode {
        writeVarUint(MESSAGE_SYNC)
        SyncProtocol.writeSyncStep1(this, doc)
    }

    /** Encode a top-level SyncStep2 message: varUint(0) • syncStep2(...) */
    fun encodeSyncStep2(doc: Doc, encodedStateVector: ByteArray? = null): ByteArray = encode {
        writeVarUint(MESSAGE_SYNC)
        SyncProtocol.writeSyncStep2(this, doc, encodedStateVector)
    }

    /** Encode a top-level Update message: varUint(0) • update(...) */
    fun encodeUpdate(update: ByteArray): ByteArray = encode {
        writeVarUint(MESSAGE_SYNC)
        SyncProtocol.writeUpdate(this, update)
    }

    /** Encode a top-level awareness message: varUint(1) • varByteArray(awarenessUpdate) */
    fun encodeAwareness(awareness: Awareness, clients: List<Int>): ByteArray = encode {
        writeVarUint(MESSAGE_AWARENESS)
        writeVarUint8Array(encodeAwarenessUpdate(awareness, clients))
    }

    /** Encode a query awareness message: varUint(3) */
    fun encodeQueryAwareness(): ByteArray = encode {
        writeVarUint(MESSAGE_QUERY_AWARENESS)
    }

    /**
     * Read and dispatch a complete message.
     * Returns reply bytes (empty if no reply needed).
     */
    fun readMessage(
        data: ByteArray,
        doc: Doc,
        awareness: Awareness?,
        origin: Any? = null
    ): ByteArray {
        val decoder = Decoder(data)
        val encoder = Encoder()
        val messageType = decoder.readVarUint()

        when (messageType) {
            MESSAGE_SYNC -> {
                encoder.writeVarUint(MESSAGE_SYNC)
                val syncType = SyncProtocol.readSyncMessage(decoder, encoder, doc, origin)
                // Only return reply if encoder has more than the message type byte
                if (encoder.length <= 1) return ByteArray(0)
                // SyncStep1 produces a reply (SyncStep2); others don't
                if (syncType != SyncProtocol.MESSAGE_SYNC_STEP1) return ByteArray(0)
            }
            MESSAGE_AWARENESS -> {
                if (awareness != null) {
                    applyAwarenessUpdate(awareness, decoder.readVarUint8Array(), origin)
                }
                return ByteArray(0)
            }
            MESSAGE_QUERY_AWARENESS -> {
                if (awareness != null) {
                    return encodeAwareness(awareness, awareness.getStates().keys.toList())
                }
                return ByteArray(0)
            }
            MESSAGE_AUTH -> {
                // Auth messages (permission denied) — read but don't act on them
                // The transport layer can handle auth separately
                return ByteArray(0)
            }
            else -> throw IllegalStateException("Unknown message type: $messageType")
        }
        return encoder.toByteArray()
    }
}
