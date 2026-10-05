package bosca.content.find

import bosca.content.collection.model.CollectionType
import bosca.content.ordering.OrderingInput
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class FindQueryInput(
    val attributes: List<FindAttributesInput>? = null,
    val categoryIds: List<UUID>? = null,
    val collectionType: CollectionType? = null,
    val contentTypes: List<String>? = null,
    val languageTags: List<String>? = null,
    val extensionFilter: ExtensionFilterType? = null,
    val offset: Long? = null,
    val limit: Int? = null,
    val ordering: List<OrderingInput>? = null,
    val traitIds: List<String>? = null
)