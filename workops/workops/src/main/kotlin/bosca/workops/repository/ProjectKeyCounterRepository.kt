package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * Owns the per-project task-key counter rows (`workops.project_key_counter`).
 *
 * Per the Implementation Decisions section, task keys are minted from
 * a row in this table rather than a Postgres SEQUENCE so project-key
 * renames remain atomic with the counter and the value survives
 * dump / restore cycles cleanly. Numbers are never reused — deleted
 * task keys leave gaps; the counter only ever advances.
 *
 * [reserveNext] uses an atomic `UPDATE … RETURNING` to claim the next
 * number, so two concurrent `createTask` calls against the same
 * project cannot mint colliding keys. The caller is responsible for
 * inserting the counter row alongside project create — [initialize].
 */
@Repository
interface ProjectKeyCounterRepository {

    @Query("insert into workops.project_key_counter (project_id, last_used) values (:projectId, 0)")
    suspend fun initialize(projectId: UUID)

    @Query("select last_used from workops.project_key_counter where project_id = :projectId")
    suspend fun lastUsed(projectId: UUID): Long?

    @Query(
        """
        update workops.project_key_counter
        set last_used = last_used + 1
        where project_id = :projectId
        returning last_used
        """
    )
    suspend fun reserveNext(projectId: UUID): Long?
}
