package bosca.workops.ui.collaboration

import yks.sync.SyncHandler

/** One authenticated provider attached to a document through a WebSocket connection. */
internal class WorkOpsCollaborationParticipant(
    val name: String,
    val document: WorkOpsCollaborationDocument,
    token: String,
    val handler: SyncHandler,
    val send: (ByteArray) -> Unit,
    val close: suspend (Int, String) -> Unit,
) {
    @Volatile var token: String = token
    @Volatile var refreshing: Boolean = false
}
