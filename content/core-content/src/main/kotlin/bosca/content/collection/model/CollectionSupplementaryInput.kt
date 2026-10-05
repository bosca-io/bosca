package bosca.content.collection.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CollectionSupplementaryInput(
    @Contextual
    val collectionId: UUID,
    val key: String,
    val name: String,
    @Contextual
    val planId: UUID? = null,
    @Contextual
    val jobId: UUID? = null,
    val contentType: String,
    val contentLength: Int? = null,
    val sourceId: String? = null,
    val sourceIdentifier: String? = null,
    @Contextual
    val attributes: JsonElement? = null
)