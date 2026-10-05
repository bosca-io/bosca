package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A CI/CD pipeline definition parsed from a YAML file in a repository.
 * One record per file per repository, unique on (repository_id, file_path).
 * The triggers and concurrency are stored as JSONB for flexible querying.
 */
@Serializable
data class Pipeline(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    @ColumnName("file_path") val filePath: String,
    val name: String,
    val triggers: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonArray(emptyList()),
    val concurrency: kotlinx.serialization.json.JsonElement? = null,
    @ColumnName("config_hash") val configHash: String,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val updated: OffsetDateTime = OffsetDateTime.now(),
    /** Removed from the live catalog; retained for run history and automatic trigger deduplication. */
    @Contextual @ColumnName("deleted_at") val deletedAt: OffsetDateTime? = null,
)
