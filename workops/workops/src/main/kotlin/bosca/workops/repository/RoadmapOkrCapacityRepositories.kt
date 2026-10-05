package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.capacity.Capacity
import bosca.workops.model.okr.KeyResult
import bosca.workops.model.okr.Objective
import bosca.workops.model.roadmap.RoadmapScenario

@Repository
interface RoadmapScenarioRepository {

    @Query("select * from workops.roadmap_scenario where id = :id")
    suspend fun getById(id: UUID): RoadmapScenario?

    @Query("select * from workops.roadmap_scenario where program_id = :programId order by name")
    suspend fun listForProgram(programId: UUID): List<RoadmapScenario>

    @Query(
        """
        insert into workops.roadmap_scenario
            (program_id, name, description, overrides, created_by_profile_id)
        values (:programId, :name, :description, cast(:overrides as jsonb), :createdByProfileId)
        returning *
        """
    )
    suspend fun add(
        programId: UUID,
        name: String,
        description: String?,
        overrides: String,
        createdByProfileId: UUID,
    ): RoadmapScenario

    @Query("delete from workops.roadmap_scenario where id = :id")
    suspend fun delete(id: UUID)
}

@Repository
interface ObjectiveRepository {

    @Query("select * from workops.objective where id = :id")
    suspend fun getById(id: UUID): Objective?

    @Query("select * from workops.objective where program_id = :programId order by period_start desc")
    suspend fun listForProgram(programId: UUID): List<Objective>

    @Query("select * from workops.objective where project_id = :projectId order by period_start desc")
    suspend fun listForProject(projectId: UUID): List<Objective>

    @Query("select * from workops.objective where portfolio_id = :portfolioId order by period_start desc")
    suspend fun listForPortfolio(portfolioId: UUID): List<Objective>

    @Query(
        """
        insert into workops.objective
            (portfolio_id, program_id, project_id, title, description, state,
             period_start, period_end, period_name, owner_profile_id, confidence)
        values
            (:portfolioId, :programId, :projectId, :title, :description, :state,
             :periodStart, :periodEnd, :periodName, :ownerProfileId, :confidence)
        returning *
        """
    )
    suspend fun add(input: Objective): Objective
}

@Repository
interface KeyResultRepository {

    @Query("select * from workops.key_result where objective_id = :objectiveId order by title")
    suspend fun listForObjective(objectiveId: UUID): List<KeyResult>

    @Query("select * from workops.key_result where id = :id")
    suspend fun getById(id: UUID): KeyResult?

    @Query(
        """
        insert into workops.key_result
            (objective_id, title, description, metric_type, metric, confidence)
        values
            (:objectiveId, :title, :description, :metricType, cast(:metric as jsonb), :confidence)
        returning *
        """
    )
    suspend fun add(
        objectiveId: UUID,
        title: String,
        description: String?,
        metricType: String,
        metric: String,
        confidence: String,
    ): KeyResult

    @Query(
        """
        update workops.key_result
        set current_value = :value, computed_at = now(), version = version + 1
        where id = :id
        returning *
        """
    )
    suspend fun setComputedValue(id: UUID, value: Double): KeyResult?
}

@Repository
interface CapacityRepository {

    @Query("select * from workops.capacity where sprint_id = :sprintId order by profile_id")
    suspend fun listForSprint(sprintId: UUID): List<Capacity>

    @Query(
        """
        insert into workops.capacity (sprint_id, profile_id, committed_seconds, notes)
        values (:sprintId, :profileId, :committedSeconds, :notes)
        on conflict (sprint_id, profile_id) do update set
            committed_seconds = excluded.committed_seconds,
            notes = excluded.notes
        returning *
        """
    )
    suspend fun upsert(sprintId: UUID, profileId: UUID, committedSeconds: Long, notes: String?): Capacity

    @Query("delete from workops.capacity where sprint_id = :sprintId and profile_id = :profileId")
    suspend fun delete(sprintId: UUID, profileId: UUID)
}

/**
 * Aggregation surface for the capacity report — sums original
 * estimates / time-spent per assignee within a sprint. Only
 * non-deleted tasks count.
 */
@Repository
interface SprintWorkRepository {

    @Query(
        """
        select t.assignee_profile_id          as profile_id,
               coalesce(sum(coalesce(t.original_estimate_seconds, 0)), 0)::bigint as planned_seconds,
               coalesce(sum(t.time_spent_seconds), 0)::bigint                     as completed_seconds
        from workops.task t
        where t.sprint_id = :sprintId
              and t.assignee_profile_id is not null
              and t.deleted_at is null
        group by t.assignee_profile_id
        order by t.assignee_profile_id
        """
    )
    suspend fun aggregateForSprint(sprintId: UUID): List<SprintAssigneeAggregate>
}

data class SprintAssigneeAggregate(
    @bosca.db.annotation.ColumnName("profile_id")
    val profileId: UUID,
    @bosca.db.annotation.ColumnName("planned_seconds")
    val plannedSeconds: Long,
    @bosca.db.annotation.ColumnName("completed_seconds")
    val completedSeconds: Long,
)

@Repository
interface RoadmapTaskRepository {

    @Query(
        """
        select t.* from workops.task t
        join workops.task_type tt on tt.id = t.task_type_id
        where t.project_id in (
            select id from workops.project where program_id = :programId
        )
          and tt.name in ('Epic', 'Initiative')
          and t.deleted_at is null
        order by t.start_date nulls last, t.due_date nulls last
        """
    )
    suspend fun listEpicsForProgram(programId: UUID): List<bosca.workops.model.task.Task>
}
