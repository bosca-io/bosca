package bosca.workops.model.roadmap

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * R18 — a what-if overlay onto a program's epics. Scenarios are
 * a JSONB blob `{ taskId: { startDate?, dueDate?, assigneeProfileId?,
 * status?, summary? } }`. The roadmap compute path applies the
 * overlay in memory; `commitScenario` writes each entry through
 * `TaskService.update` so audit attributes the committer.
 */
@Serializable
data class RoadmapScenario(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("program_id")
    @Contextual
    val programId: UUID,
    val name: String,
    val description: String? = null,
    @Contextual
    val overrides: JsonElement = JsonObject(emptyMap()),
    @ColumnName("created_by_profile_id")
    @Contextual
    val createdByProfileId: UUID,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val version: Long = 0,
)

/**
 * Computed roadmap entry. The `progressPercent` is derived from
 * the epic's child rollups (Phase 8.3); the dates come from the
 * task itself or from the scenario overlay (when provided).
 */
@Serializable
data class RoadmapEntry(
    @Contextual
    val taskId: UUID,
    val key: String,
    val summary: String,
    @Contextual
    val startDate: OffsetDateTime?,
    @Contextual
    val dueDate: OffsetDateTime?,
    val progressPercent: Int,
    val statusCategory: String,
    @Contextual
    val assigneeProfileId: UUID?,
    val isFromScenario: Boolean,
)
