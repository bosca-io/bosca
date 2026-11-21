package bosca.workops.ui

import kotlinx.serialization.Serializable

/** The three build steps share one draft; selecting projects does not write the release. */
@Serializable
data class WorkOpsReleaseDraft(
    var name: String = "",
    var description: String = "",
    var date: String = "",
    var step: Int = 1,
    var components: List<WorkOpsReleaseComponentChoice> = emptyList(),
)
