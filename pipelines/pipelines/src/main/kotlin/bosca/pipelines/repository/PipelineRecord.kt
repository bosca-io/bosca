@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.repository

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * The persisted row for a pipeline. The node/edge graph is stored as a single `jsonb` column
 * (`graph`) — mapped to a `@Contextual JsonElement` (handled automatically at the jsonb boundary). The
 * service converts this to/from the typed `Pipeline` via the aggregated node `SerializersModule`.
 */
@Serializable
data class PipelineRecord(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String = "",
    @ColumnName("accepted_input_type")
    val acceptedInputType: String,
    /** Free-form categorization labels; stored as a Postgres `text[]` (column `tags`). */
    val tags: List<String> = emptyList(),
    val triggered: Boolean = false,
    val key: String = "",
    val api: Boolean = false,
    val public: Boolean = false,
    /** Cron expression for a scheduled run, or null when not scheduled. */
    val schedule: String? = null,
    /** Max durable runs in flight at once, or null for unlimited. */
    @ColumnName("max_concurrent_runs")
    val maxConcurrentRuns: Int? = null,
    /** Max durable runs started per rolling minute, or null for unlimited. */
    @ColumnName("max_runs_per_minute")
    val maxRunsPerMinute: Int? = null,
    @Contextual
    val graph: JsonElement,
    val version: Long = 0,
    @ColumnName("git_repository_id")
    @Contextual
    val gitRepositoryId: UUID? = null,
    @ColumnName("git_path")
    val gitPath: String? = null,
    @ColumnName("last_sync_error")
    val lastSyncError: String? = null,
    @ColumnName("deleted_at")
    @Contextual
    val deletedAt: OffsetDateTime? = null,
)
