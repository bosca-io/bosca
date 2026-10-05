package yks.sync

import yks.lib0.Decoder
import yks.lib0.Encoder
import yks.utils.*

/**
 * Sync protocol matching y-protocols/sync.js.
 *
 * Defines three message types for syncing Yjs documents:
 * - SyncStep1: client sends state vector, expects SyncStep2 reply
 * - SyncStep2: reply with missing updates (also used for incremental updates)
 * - Update: incremental document update after initial sync
 */
object SyncProtocol {
    const val MESSAGE_SYNC_STEP1 = 0
    const val MESSAGE_SYNC_STEP2 = 1
    const val MESSAGE_YJS_UPDATE = 2

    /**
     * Write a SyncStep1 message: varUint(0) • varByteArray(stateVector).
     * The receiver should reply with SyncStep2.
     */
    fun writeSyncStep1(encoder: Encoder, doc: Doc) {
        encoder.writeVarUint(MESSAGE_SYNC_STEP1)
        encoder.writeVarUint8Array(encodeStateVector(doc))
    }

    /**
     * Write a SyncStep2 message: varUint(1) • varByteArray(update).
     * Contains all structs and deletes the receiver is missing.
     */
    fun writeSyncStep2(encoder: Encoder, doc: Doc, encodedStateVector: ByteArray? = null) {
        encoder.writeVarUint(MESSAGE_SYNC_STEP2)
        encoder.writeVarUint8Array(encodeStateAsUpdate(doc, encodedStateVector))
    }

    /**
     * Write an Update message: varUint(2) • varByteArray(update).
     * Used for incremental updates after initial sync.
     */
    fun writeUpdate(encoder: Encoder, update: ByteArray) {
        encoder.writeVarUint(MESSAGE_YJS_UPDATE)
        encoder.writeVarUint8Array(update)
    }

    /**
     * Read a SyncStep1 message and write a SyncStep2 reply.
     * Reads the remote state vector and responds with missing updates.
     */
    fun readSyncStep1(decoder: Decoder, encoder: Encoder, doc: Doc) {
        writeSyncStep2(encoder, doc, decoder.readVarUint8Array())
    }

    /**
     * Read a SyncStep2 message and apply the update to the document.
     */
    fun readSyncStep2(decoder: Decoder, doc: Doc, origin: Any? = null) {
        applyUpdate(doc, decoder.readVarUint8Array(), origin)
    }

    /**
     * Read a sync message, dispatch to the appropriate handler.
     * Returns the message type that was read.
     *
     * If the message is SyncStep1, a SyncStep2 reply is written to [encoder].
     */
    fun readSyncMessage(
        decoder: Decoder,
        encoder: Encoder,
        doc: Doc,
        origin: Any? = null
    ): Int {
        val messageType = decoder.readVarUint()
        when (messageType) {
            MESSAGE_SYNC_STEP1 -> readSyncStep1(decoder, encoder, doc)
            MESSAGE_SYNC_STEP2 -> readSyncStep2(decoder, doc, origin)
            MESSAGE_YJS_UPDATE -> readSyncStep2(decoder, doc, origin) // same as SyncStep2
            else -> throw IllegalStateException("Unknown sync message type: $messageType")
        }
        return messageType
    }
}
