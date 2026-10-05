package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class MetadataSourceInput(
    @Contextual
    val id: UUID? = null,
    val identifier: String? = null,
    val sourceUrl: String? = null
)