package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable data class SearchSnippetPart(val text: String, val matched: Boolean = false)
