package bosca.workops.ui

import kotlinx.serialization.Serializable

/** Board identity and scope; project display names come from authorized project roots. */
@Serializable
data class WorkOpsBoardChoice(
    val id: String,
    val name: String,
    val type: String,
    val projectId: String?,
    val programId: String?,
    val portfolioId: String?,
    val projectIds: List<String>,
)
