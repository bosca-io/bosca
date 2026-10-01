package bosca.search.model

import bosca.search.IndexStorageSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class RawSearchResult(
    val hits: List<JsonObject>,
    val facets: List<SearchResultFacet>,
    val estimatedHits: Long,
    val system: IndexStorageSystem
)
