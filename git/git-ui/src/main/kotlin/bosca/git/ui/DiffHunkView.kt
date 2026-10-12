package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class DiffHunkView(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val totalLineCount: Int,
    val lines: List<DiffLineView>,
)
