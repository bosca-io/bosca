package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/** A durable automatic-trigger decision. Null run IDs mean the file was intentionally skipped. */
@Serializable
data class PipelineEvaluation(
    val id: UUID,
    @ColumnName("repository_id") val repositoryId: UUID,
    @ColumnName("commit_sha") val commitSha: String,
    val ref: String,
    val completed: Boolean = false,
    val error: String? = null,
    @property:DbMapper(JsonbMapper::class) val selections: List<PipelineSelection> = emptyList(),
    val modified: OffsetDateTime = OffsetDateTime.now(),
    val created: OffsetDateTime = OffsetDateTime.now(),
)
