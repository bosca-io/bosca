package bosca.workops.ui

import kotlinx.serialization.Serializable

/** Columns use the existing nonversioned API and retain the complete inspected payload. */
@Serializable
data class WorkOpsBoardColumnDraft(
    val id: String = "",
    val name: String = "",
    val order: Int = 1,
    val statusIds: List<String> = emptyList(),
    val limit: String = "",
    val originalStatusIds: List<String> = emptyList(),
)
