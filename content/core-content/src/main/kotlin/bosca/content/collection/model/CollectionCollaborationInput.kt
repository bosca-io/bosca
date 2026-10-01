package bosca.content.collection.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
class CollectionCollaborationInput(
    @Contextual
    val collectionId: UUID,
    val languageTag: String,
    val content: ByteArray
)