package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class DiffLineView(
    val type: String,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val content: String,
)
