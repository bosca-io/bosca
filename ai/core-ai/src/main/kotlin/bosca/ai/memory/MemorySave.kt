package bosca.ai.memory

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Durable retry receipt; no memory body or credentials are stored here. */
@Serializable
data class MemorySave(
    @ColumnName("principal_id") @Contextual val principalId: UUID,
    @ColumnName("request_id") @Contextual val requestId: UUID,
    @ColumnName("input_digest") val inputDigest: String,
    @ColumnName("metadata_id") @Contextual val metadataId: UUID? = null,
)
