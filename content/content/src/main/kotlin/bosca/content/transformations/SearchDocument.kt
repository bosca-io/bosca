package bosca.content.transformations

import bosca.search.model.SearchDocumentItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SearchDocument(
    val id: String,
    val contentId: String,
    val slug: String,
    val languageTag: String,
    val name: String,
    val description: String? = null,
    val labels: List<String> = emptyList(),
    @SerialName("_type")
    val documentType: String,
    val contentType: String,
    val published: Long = 0,
    val created: Long = 0,
    val modified: Long = 0,
    val categories: List<SearchDocumentItem> = emptyList(),
    val content: String? = null
)