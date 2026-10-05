package bosca.search.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchResultFacet(
    val count: Long,
    val field: String,
    val value: String,
)