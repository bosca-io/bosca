package bosca.content.metadata.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID



data class MetadataWorkflowPlan(
    val id: UUID,
    val planId: UUID,
    val queue: String,
    val created: OffsetDateTime
)