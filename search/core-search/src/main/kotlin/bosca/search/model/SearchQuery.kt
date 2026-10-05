package bosca.search.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class SearchQuery(
    val query: String = "",
    val offset: Int?,
    val limit: Int?,
    val facets: List<String>? = null,
    val filter: List<String>? = null,
    val sort: List<String>? = null,
    val semanticRatio: Double? = null,
    val storageSystemId: UUID? = null,
    val storageSystemName: String? = null,
    val vector: List<Double>? = null,
)