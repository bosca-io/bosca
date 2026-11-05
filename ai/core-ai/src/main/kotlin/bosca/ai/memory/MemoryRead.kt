package bosca.ai.memory

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** First memory revision delivered through tools since a native context was rebuilt. */
@Serializable
data class MemoryRead(
    @ColumnName("session_id") @Contextual val sessionId: UUID,
    @ColumnName("metadata_id") @Contextual val metadataId: UUID,
    val revision: String,
    @ColumnName("source_changed") val sourceChanged: Boolean,
)
