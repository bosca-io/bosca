package bosca.workops.ui

import kotlinx.serialization.Serializable

/** Inline version fields retain their own rejected draft and optional UTC dates. */
@Serializable
data class WorkOpsReleaseVersionDraft(
    var name: String = "",
    var description: String = "",
    var startDate: String = "",
    var releaseDate: String = "",
)
