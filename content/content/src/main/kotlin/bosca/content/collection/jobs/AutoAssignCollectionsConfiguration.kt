package bosca.content.collection.jobs

import kotlinx.serialization.Serializable

@Serializable
data class AutoAssignCollectionsConfiguration(
    /**
     * List of item attribute keys/values that contain a collection slug that they should be assigned to
     */
    val attributes: List<AttributeValue>,
)

@Serializable
data class AttributeValue(val key: String, val value: String, val slug: String)
