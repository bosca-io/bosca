package bosca.workops.ui

import kotlinx.serialization.Serializable

/** Board settings retain the membership, strategy and optimistic version inspected at edit time. */
@Serializable
data class WorkOpsBoardSettingsDraft(
    val name: String,
    val type: String,
    val swimlane: String,
    val projectIds: List<String>,
    val originalProjectIds: List<String>,
    val originalSwimlane: String,
    val version: Long,
    val multiProject: Boolean,
)
