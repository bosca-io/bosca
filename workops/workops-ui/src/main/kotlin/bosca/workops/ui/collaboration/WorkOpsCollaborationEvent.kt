package bosca.workops.ui.collaboration

import kotlinx.serialization.Serializable

/** Instance notifications carry small presence messages; document state stays in Content. */
@Serializable
internal data class WorkOpsCollaborationEvent(
    val source: String,
    val kind: String,
    val presence: ByteArray = ByteArray(0),
)
