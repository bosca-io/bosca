package bosca.ai.memory

import bosca.content.metadata.model.MemoryAttributes
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** A current, authorized projection of a content-backed memory. */
@Serializable
data class Memory(
    @Contextual val id: UUID,
    val version: Int,
    val revision: String,
    val statement: String,
    val provenance: MemoryAttributes,
    val sourceChanged: Boolean,
    val tags: List<String>,
    @Contextual val created: OffsetDateTime,
    @Contextual val modified: OffsetDateTime?,
)
