package bosca.workops.ui

import kotlinx.serialization.Serializable

/** Presentation values for an authorized specification search result. */
@Serializable
data class WorkOpsSpecRow(
    val id: String,
    val title: String,
    val status: String,
    val category: String,
    val owner: String,
    val project: String,
    val childCount: Int,
    val childDoneCount: Int,
    val requirementCount: Long,
    val gitLinked: Boolean,
    val modified: String,
) {
    val href get() = "/workops/specs/${workOpsEncode(id)}"
    val statusTone get() = when (category) { "DONE" -> "success"; "IN_PROGRESS" -> "purple"; else -> "neutral" }
}
