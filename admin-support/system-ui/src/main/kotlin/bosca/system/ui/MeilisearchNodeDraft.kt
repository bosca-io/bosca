package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class MeilisearchNodeDraft(
    val name: String = "", val description: String = "", val url: String = "", val key: String = "",
    val types: List<String> = listOf("SEARCH"),
)
