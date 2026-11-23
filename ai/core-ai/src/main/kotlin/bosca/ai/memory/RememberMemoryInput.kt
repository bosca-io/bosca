package bosca.ai.memory

import bosca.content.metadata.model.MemoryScope
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class RememberMemoryInput(
    @Contextual val requestId: UUID,
    val statement: String,
    val scope: MemoryScope = MemoryScope.PERSONAL,
    @Contextual val collectionId: UUID? = null,
    @Contextual val sourceMetadataId: UUID? = null,
    @Contextual val conversationId: UUID? = null,
    @Contextual val expires: OffsetDateTime? = null,
    val tags: List<String> = emptyList(),
)
