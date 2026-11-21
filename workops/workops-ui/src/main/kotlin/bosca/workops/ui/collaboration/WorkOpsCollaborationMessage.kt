package bosca.workops.ui.collaboration

/** Hocuspocus room prefix and the unchanged Yjs message payload, including its message type. */
internal class WorkOpsCollaborationMessage(
    val name: String,
    val room: WorkOpsCollaborationRoom,
    val type: Int,
    val payload: ByteArray,
)
