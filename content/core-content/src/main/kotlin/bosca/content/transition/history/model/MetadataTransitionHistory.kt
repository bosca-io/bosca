package bosca.content.transition.history.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class MetadataTransitionHistory(
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID,
    @ColumnName("from_state_id")
    val fromStateId: String,
    @ColumnName("to_state_id")
    val toStateId: String,
    @Contextual
    val principal: UUID?,
    val status: String,
    val success: Boolean,
    val complete: Boolean,
)