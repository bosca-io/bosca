package bosca.content.metadata.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Represents a single content item identified during a health check as having
 * an issue that requires editorial attention, such as broken relationships
 * or workflow inconsistencies.
 */
@Serializable
data class ContentHealthCheckItem(
    @Contextual
    val id: UUID,
    val name: String,
    @ColumnName("workflow_state_id")
    val workflowState: String
)
