package bosca.content.metadata.model

import kotlinx.serialization.Serializable

@Serializable
class CollectionTemplateFilters(
    val filters: List<CollectionTemplateFilter>
)

@Serializable
data class CollectionTemplateFilter(
    val filter: String,
    val name: String
)