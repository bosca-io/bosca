package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class PipelineArtifact(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    @Contextual @ColumnName("pipeline_run_id") val pipelineRunId: UUID,
    @ColumnName("run_number") val runNumber: Int,
    val name: String,
    @ColumnName("size_bytes") val sizeBytes: Long = 0,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now()
)
