package bosca.content.metadata.model

import bosca.content.transition.model.JobHistory
import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
class MetadataJobHistory(
    @Contextual
    override val id: UUID,
    val version: Int,
    @ColumnName("job_name")
    override val jobName: String,
    @Contextual
    @ColumnName("job_id")
    override val jobId: UUID,
    override val status: String,
    override val principal: UUID?,
    override val created: OffsetDateTime = OffsetDateTime.now(),
    override val complete: OffsetDateTime? = null,
    override val success: Boolean = false,
    @ColumnName("delayed_until")
    override val delayedUntil: OffsetDateTime? = null
) : JobHistory {
}
