package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.audit.TaskHistoryEntry
import kotlinx.serialization.json.JsonElement

/**
 * Append-only writer / reader of `workops.task_history` (R17).
 *
 * The repository deliberately exposes **only** [add] and read paths —
 * no UPDATE, no DELETE — so the application code path cannot mutate
 * existing history rows. Phase 10 lifts the database-level enforcement
 * by stripping UPDATE / DELETE grants from the application role.
 *
 * Multi-field updates produce one history row carrying multiple
 * [bosca.workops.model.audit.FieldChange] entries — see the R17
 * spec text. Callers compose the change set in the service layer and
 * serialize it to a JSON array before calling [add]; the JSON shape
 * is enforced at the service boundary because the partitioned-table
 * layer cannot easily own a per-element check constraint.
 *
 * The `changes` parameter is a [JsonElement] rather than a
 * `List<FieldChange>` because Bosca's repository binder lacks a path
 * for non-primitive collections — the parameter would otherwise route
 * through the Postgres `Types.ARRAY` adapter and fail the
 * `jsonb` insert with a confusing cast error.
 */
@Repository
interface TaskHistoryRepository {

    @Query(
        """
        insert into workops.task_history (
            task_id, changed_at, changed_by_principal_id, changed_by_profile_id, changes
        ) values (
            :taskId, :changedAt, :changedByPrincipalId, :changedByProfileId, :changes::jsonb
        )
        returning *
        """
    )
    suspend fun add(
        taskId: UUID,
        changedAt: OffsetDateTime,
        changedByPrincipalId: UUID,
        changedByProfileId: UUID?,
        changes: JsonElement,
    ): TaskHistoryEntry

    @Query(
        """
        select * from workops.task_history
        where task_id = :taskId
        order by changed_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listByTask(taskId: UUID, offset: Long, limit: Int): List<TaskHistoryEntry>
}
