package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@Repository
interface ReportComputerRepository {

    @Query(
        """
        select tt.name as label, count(t.id) as count
        from workops.task t
        join workops.task_type tt on tt.id = t.task_type_id
        where t.deleted_at is null
              and (:projectId::uuid is null or t.project_id = :projectId)
        group by tt.name
        order by tt.name
        """
    )
    suspend fun countByTaskType(projectId: UUID?): List<CategoryDistribution>

    @Query(
        """
        select p.name as label, count(t.id) as count
        from workops.task t
        join workops.priority p on p.id = t.priority_id
        where t.deleted_at is null
              and (:projectId::uuid is null or t.project_id = :projectId)
        group by p.name, p.display_order
        order by p.display_order
        """
    )
    suspend fun countByPriority(projectId: UUID?): List<CategoryDistribution>

    @Query(
        """
        select to_char(d::date, 'YYYY-MM-DD') as day,
               coalesce(c.created_count, 0)::bigint as created,
               coalesce(r.resolved_count, 0)::bigint as resolved
        from generate_series(:from::date, :to::date, '1 day'::interval) d
        left join lateral (
            select count(*) as created_count
            from workops.task
            where created_at::date = d::date
              and (:projectId::uuid is null or project_id = :projectId)
              and deleted_at is null
        ) c on true
        left join lateral (
            select count(*) as resolved_count
            from workops.task
            where resolution_at::date = d::date
              and (:projectId::uuid is null or project_id = :projectId)
              and deleted_at is null
        ) r on true
        order by d
        """
    )
    suspend fun createdVsResolvedByDay(
        from: OffsetDateTime,
        to: OffsetDateTime,
        projectId: UUID?,
    ): List<CreatedVsResolvedDay>

    @Query(
        """
        select
            coalesce(sum(case when outcome = 'MET'           then 1 else 0 end), 0)::bigint as met,
            coalesce(sum(case when outcome = 'BREACHED'      then 1 else 0 end), 0)::bigint as breached,
            coalesce(sum(case when outcome = 'STOPPED_EARLY' then 1 else 0 end), 0)::bigint as stopped_early,
            coalesce(sum(case when outcome = 'OPEN'          then 1 else 0 end), 0)::bigint as open
        from workops.task_sla_state s
        join workops.task t on t.id = s.task_id
        where (:projectId::uuid is null or t.project_id = :projectId)
        """
    )
    suspend fun slaCompliance(projectId: UUID?): SlaComplianceCounts

    @Query(
        """
        select
            coalesce(sum(case when t.id = any(s.committed_task_ids)
                              then coalesce(t.original_estimate_seconds, 0) else 0 end), 0)::bigint as committed,
            coalesce(sum(case when t.id = any(s.committed_task_ids)
                              and t.status_id in (
                                  select id from workops.status where category = 'DONE'
                              )
                              then coalesce(t.original_estimate_seconds, 0) else 0 end), 0)::bigint as completed
        from workops.sprint s
        left join workops.task t on t.sprint_id = s.id and t.deleted_at is null
        where s.id = :sprintId
        group by s.id
        """
    )
    suspend fun sprintVelocity(sprintId: UUID): SprintVelocityCounts
}

data class CategoryDistribution(
    val label: String,
    val count: Long,
)

data class CreatedVsResolvedDay(
    val day: String,
    val created: Long,
    val resolved: Long,
)

data class SlaComplianceCounts(
    val met: Long,
    val breached: Long,
    @bosca.db.annotation.ColumnName("stopped_early")
    val stoppedEarly: Long,
    val open: Long,
)

data class SprintVelocityCounts(
    val committed: Long,
    val completed: Long,
)
