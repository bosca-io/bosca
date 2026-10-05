package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
class DocumentCollaborationInput(
    @Contextual
    val metadataId: UUID,
    val version: Int,
    val content: ByteArray? = null,
)