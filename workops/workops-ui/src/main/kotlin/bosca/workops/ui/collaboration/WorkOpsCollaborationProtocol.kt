package bosca.workops.ui.collaboration

import yks.lib0.Decoder
import yks.lib0.encode

/** Adapts the existing provider's room/auth envelope to YKS's Yjs synchronization protocol. */
internal object WorkOpsCollaborationProtocol {
    const val SYNC = 0
    const val AWARENESS = 1
    const val AUTH = 2
    const val QUERY_AWARENESS = 3
    const val CLOSE = 7
    private const val SYNC_STATUS = 8

    fun decode(bytes: ByteArray): WorkOpsCollaborationMessage {
        val decoder = Decoder(bytes)
        val name = decoder.readVarString()
        val room = WorkOpsCollaborationRoom.parse(name)
        val payload = bytes.copyOfRange(decoder.position, bytes.size)
        return WorkOpsCollaborationMessage(name, room, Decoder(payload).readVarUint(), payload)
    }

    fun token(message: WorkOpsCollaborationMessage): String {
        require(message.type == AUTH) { "Expected collaboration authentication" }
        val decoder = Decoder(message.payload)
        decoder.readVarUint()
        require(decoder.readVarUint() == 0) { "Expected collaboration token" }
        val token = decoder.readVarString()
        require(token.isNotBlank() && !decoder.hasContent) { "Invalid collaboration token message" }
        return token
    }

    fun syncType(message: WorkOpsCollaborationMessage): Int {
        require(message.type == SYNC) { "Expected collaboration sync" }
        return Decoder(message.payload).run { readVarUint(); readVarUint() }
    }

    fun frame(name: String, payload: ByteArray): ByteArray = encode {
        writeVarString(name)
        writeBytes(payload)
    }

    fun authenticated(name: String): ByteArray = frame(name, encode {
        writeVarUint(AUTH)
        writeVarUint(2)
        writeVarString("read-write")
    })

    fun denied(name: String): ByteArray = frame(name, encode {
        writeVarUint(AUTH)
        writeVarUint(1)
        writeVarString("permission-denied")
    })

    fun acknowledged(name: String): ByteArray = frame(name, encode {
        writeVarUint(SYNC_STATUS)
        writeVarInt(1)
    })
}
