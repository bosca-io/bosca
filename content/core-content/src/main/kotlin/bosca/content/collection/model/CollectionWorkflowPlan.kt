package bosca.content.collection.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CollectionWorkflowPlan(
    val id: UUID,
    @ColumnName("plan_id")
    val planId: UUID,
    val queue: String,
    val created: OffsetDateTime
)