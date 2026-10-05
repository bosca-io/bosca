package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MetadataSupplementaryInput(
    @Contextual
    val metadataId: UUID,
    val key: String,
    val name: String,
    @Contextual
    val attributes: JsonElement? = null,
    @Contextual
    val planId: UUID? = null,
    @Contextual
    val jobId: UUID? = null,
    val contentLength: Long? = null,
    val contentType: String,
    @Contextual
    val sourceId: UUID? = null,
    val sourceIdentifier: String? = null,
)