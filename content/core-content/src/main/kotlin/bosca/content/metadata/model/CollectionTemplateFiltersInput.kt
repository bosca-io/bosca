package bosca.content.metadata.model

import kotlinx.serialization.Serializable

@Serializable
class CollectionTemplateFiltersInput(
    val filters: List<CollectionTemplateFilterInput>
)

@Serializable
data class CollectionTemplateFilterInput(
    val filter: String,
    val name: String
)