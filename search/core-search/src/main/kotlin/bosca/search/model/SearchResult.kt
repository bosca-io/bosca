package bosca.search.model

import bosca.search.IndexStorageSystem
import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(
    val documents: List<SearchDocument>,
    val facets: List<SearchResultFacet>,
    val estimatedHits: Long,
    val system: IndexStorageSystem
)