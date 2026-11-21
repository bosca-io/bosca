package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsSavedFilterRow(
    val id: String,
    val name: String,
    val description: String,
    val source: String,
    val modified: String,
    val version: Long,
) {
    val href get() = "/workops/tasks?bql=${workOpsEncode(source)}"
    val editId get() = "workops-filter-edit-$id"
    val previewId get() = "workops-filter-preview-$id"
}
