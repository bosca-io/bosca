package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.sprint.Sprint
import bosca.workops.model.sprint.SprintState

/**
 * Persists [Sprint] rows. The single-active-sprint invariant per
 * board (R8) is enforced at the database layer by the partial
 * unique index `sprint_active_singleton`; the service layer also
 * pre-checks so the typed [bosca.workops.model.SprintAlreadyActiveException]
 * surfaces before the SQL would.
 */
@Repository
interface SprintRepository {

    @Query("select * from workops.sprint where id = :id")
    suspend fun getById(id: UUID): Sprint?

    @Query("select * from workops.sprint where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Sprint>

    @Query(
        """
        select * from workops.sprint
        where board_id = :boardId
        order by created_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listByBoard(boardId: UUID, offset: Long, limit: Int): List<Sprint>

    @Query("select * from workops.sprint where board_id = :boardId and state = (:state)::workops.sprint_state limit 1")
    suspend fun findActive(boardId: UUID, state: SprintState = SprintState.ACTIVE): Sprint?

    @Query("insert into workops.sprint (board_id, name, goal) values (:boardId, :name, :goal) returning *")
    suspend fun add(boardId: UUID, name: String, goal: String?): Sprint

    /**
     * Flip a [Sprint] from `FUTURE` to `ACTIVE`. The caller passes
     * the entity with the desired startDate / endDate / committed
     * task ids set; the entity's `version` doubles as the
     * optimistic-lock token. The model-flatten path is the only
     * Bosca KSP shape that supports a `uuid[]` bind in one call.
     */
    @Query(
        """
        update workops.sprint
        set state = ('active')::workops.sprint_state,
            start_date = coalesce(:startDate, now()),
            end_date = :endDate,
            committed_task_ids = :committedTaskIds,
            modified_at = now(),
            version = version + 1
        where id = :id and version = :version and state = 'future'
        returning *
        """
    )
    suspend fun start(sprint: Sprint): Sprint?

    @Query(
        """
        update workops.sprint
        set committed_task_ids = array_append(committed_task_ids, :taskId),
            modified_at = now(),
            version = version + 1
        where id = :sprintId and state = 'future'
          and not (:taskId = any(committed_task_ids))
        returning *
        """
    )
    suspend fun addCommittedTask(sprintId: UUID, taskId: UUID): Sprint?

    @Query(
        """
        update workops.sprint
        set added_during_sprint_task_ids = array_append(added_during_sprint_task_ids, :taskId),
            modified_at = now(),
            version = version + 1
        where id = :sprintId and state = 'active'
          and not (:taskId = any(added_during_sprint_task_ids))
          and not (:taskId = any(committed_task_ids))
        returning *
        """
    )
    suspend fun addDuringSprintTask(sprintId: UUID, taskId: UUID): Sprint?

    @Query(
        """
        update workops.sprint
        set committed_task_ids = array_remove(committed_task_ids, :taskId),
            added_during_sprint_task_ids = array_remove(added_during_sprint_task_ids, :taskId),
            modified_at = now(),
            version = version + 1
        where id = :sprintId
          and (:taskId = any(committed_task_ids) or :taskId = any(added_during_sprint_task_ids))
        returning *
        """
    )
    suspend fun removeTask(sprintId: UUID, taskId: UUID): Sprint?

    @Query(
        """
        update workops.sprint
        set state = ('closed')::workops.sprint_state,
            complete_date = now(),
            velocity_points = :velocityPoints,
            modified_at = now(),
            version = version + 1
        where id = :id and version = :expectedVersion and state = 'active'
        returning *
        """
    )
    suspend fun close(id: UUID, velocityPoints: Double?, expectedVersion: Long): Sprint?
}
