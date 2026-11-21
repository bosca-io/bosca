package bosca.workops.ui

import kotlinx.serialization.Serializable

/** Native form values survive rejected writes and independent choice or grant refreshes. */
@Serializable
data class WorkOpsEnvironmentDraft(
    var name: String = "",
    var key: String = "",
    var description: String = "",
    var typeId: String = "",
    var autoPromote: Boolean = false,
    var manualKey: Boolean = false,
)
