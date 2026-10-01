package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.worklog.WorkLog

@Repository
interface WorkLogRepository {

    @Query("select * from workops.worklog where id = :id and deleted_at is null")
    suspend fun getById(id: UUID): WorkLog?

    @Query(
        """
        select * from workops.worklog
        where task_id = :taskId and deleted_at is null
        order by started_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listForTask(taskId: UUID, offset: Long, limit: Int): List<WorkLog>

    @Query(
        """
        insert into workops.worklog
            (task_id, profile_id, time_spent_seconds, started_at,
             comment, worklog_visibility)
        values
            (:taskId, :profileId, :timeSpentSeconds, :startedAt,
             :comment, :worklogVisibility)
        returning *
        """
    )
    suspend fun add(input: WorkLogInsertParams): WorkLog

    @Query(
        """
        update workops.worklog
        set time_spent_seconds = :timeSpentSeconds,
            started_at = :startedAt,
            comment = :comment,
            worklog_visibility = :worklogVisibility,
            modified_at = now()
        where id = :id and deleted_at is null
        returning *
        """
    )
    suspend fun update(input: WorkLogUpdateParams): WorkLog?

    @Query(
        """
        update workops.worklog set deleted_at = now(), modified_at = now()
        where id = :id and deleted_at is null
        returning *
        """
    )
    suspend fun softDelete(id: UUID): WorkLog?

    @Query(
        """
        select coalesce(sum(time_spent_seconds), 0)::bigint
        from workops.worklog
        where task_id = :taskId and deleted_at is null
        """
    )
    suspend fun sumForTask(taskId: UUID): Long

    @Query(
        """
        select count(*) from workops.worklog
        where profile_id = :profileId and deleted_at is null
        """
    )
    suspend fun countForProfile(profileId: UUID): Long
}

data class WorkLogInsertParams(
    val taskId: UUID,
    val profileId: UUID,
    val timeSpentSeconds: Long,
    val startedAt: OffsetDateTime,
    val comment: String?,
    val worklogVisibility: String,
)

data class WorkLogUpdateParams(
    val id: UUID,
    val timeSpentSeconds: Long,
    val startedAt: OffsetDateTime,
    val comment: String?,
    val worklogVisibility: String,
)

@Repository
interface TaskTimeRepository {

    @Query(
        """
        update workops.task
        set time_spent_seconds = :timeSpentSeconds,
            remaining_estimate_seconds = :remainingEstimateSeconds,
            modified_at = now(),
            version = version + 1
        where id = :id
        """
    )
    suspend fun setTotals(id: UUID, timeSpentSeconds: Long, remainingEstimateSeconds: Long?)

    @Query(
        """
        update workops.task
        set epic_total_estimate_seconds  = :totalEstimate,
            epic_total_remaining_seconds = :totalRemaining,
            epic_total_spent_seconds     = :totalSpent,
            epic_child_count             = :childCount,
            epic_child_done_count        = :childDoneCount,
            modified_at = now(),
            version = version + 1
        where id = :id
        """
    )
    suspend fun setEpicRollup(
        id: UUID,
        totalEstimate: Long,
        totalRemaining: Long,
        totalSpent: Long,
        childCount: Int,
        childDoneCount: Int,
    )

    @Query(
        """
        select coalesce(sum(coalesce(original_estimate_seconds,0)),0)::bigint as total_estimate,
               coalesce(sum(coalesce(remaining_estimate_seconds,0)),0)::bigint as total_remaining,
               coalesce(sum(time_spent_seconds),0)::bigint as total_spent,
               count(*)::int as child_count,
               sum(case when status_id in (
                   select id from workops.status where category in ('DONE','CANCELLED')
               ) then 1 else 0 end)::int as child_done_count
        from workops.task
        where epic_task_id = :epicId and deleted_at is null
        """
    )
    suspend fun aggregateForEpic(epicId: UUID): EpicRollup?
}

data class EpicRollup(
    @bosca.db.annotation.ColumnName("total_estimate")
    val totalEstimate: Long,
    @bosca.db.annotation.ColumnName("total_remaining")
    val totalRemaining: Long,
    @bosca.db.annotation.ColumnName("total_spent")
    val totalSpent: Long,
    @bosca.db.annotation.ColumnName("child_count")
    val childCount: Int,
    @bosca.db.annotation.ColumnName("child_done_count")
    val childDoneCount: Int,
)
