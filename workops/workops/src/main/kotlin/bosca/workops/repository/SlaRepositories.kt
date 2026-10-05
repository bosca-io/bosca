package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.sla.SlaGoal
import bosca.workops.model.sla.SlaPolicy
import bosca.workops.model.sla.TaskSlaState
import bosca.workops.model.sla.WorkingCalendar

@Repository
interface WorkingCalendarRepository {

    @Query("select * from workops.working_calendar where id = :id")
    suspend fun getById(id: UUID): WorkingCalendar?

    @Query("select * from workops.working_calendar order by name")
    suspend fun listAll(): List<WorkingCalendar>

    @Query(
        """
        insert into workops.working_calendar
            (name, description, time_zone, weekly_hours, holidays)
        values
            (:name, :description, :timeZone,
             cast(:weeklyHours as jsonb), cast(:holidays as jsonb))
        returning *
        """
    )
    suspend fun add(
        name: String,
        description: String?,
        timeZone: String,
        weeklyHours: String,
        holidays: String,
    ): WorkingCalendar
}

@Repository
interface SlaPolicyRepository {

    @Query("select * from workops.sla_policy where id = :id")
    suspend fun getById(id: UUID): SlaPolicy?

    @Query("select * from workops.sla_policy order by name")
    suspend fun listAll(): List<SlaPolicy>

    @Query(
        """
        insert into workops.sla_policy (name, description)
        values (:name, :description)
        returning *
        """
    )
    suspend fun add(name: String, description: String?): SlaPolicy

    @Query("select * from workops.sla_policy where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<SlaPolicy>
}

@Repository
interface SlaGoalRepository {

    @Query("select * from workops.sla_goal where id = :id")
    suspend fun getById(id: UUID): SlaGoal?

    @Query("select * from workops.sla_goal where policy_id = :policyId order by display_order, name")
    suspend fun listForPolicy(policyId: UUID): List<SlaGoal>

    @Query(
        """
        insert into workops.sla_goal
            (policy_id, name, start_conditions, pause_conditions,
             stop_conditions, target_minutes, at_risk_at_percent,
             calendar_id, display_order)
        values
            (:policyId, :name, :startConditions, :pauseConditions,
             :stopConditions, :targetMinutes, :atRiskAtPercent,
             :calendarId, :displayOrder)
        returning *
        """
    )
    suspend fun add(goal: SlaGoal): SlaGoal

    @Query("delete from workops.sla_goal where id = :id")
    suspend fun delete(id: UUID)
}

@Repository
interface TaskSlaStateRepository {

    @Query(
        """
        select * from workops.task_sla_state
        where task_id = :taskId
        order by goal_id
        """
    )
    suspend fun listForTask(taskId: UUID): List<TaskSlaState>

    @Query(
        """
        select * from workops.task_sla_state
        where task_id = :taskId and goal_id = :goalId
        """
    )
    suspend fun get(taskId: UUID, goalId: UUID): TaskSlaState?

    @Query(
        """
        insert into workops.task_sla_state
            (task_id, goal_id, started_at, due_at)
        values
            (:taskId, :goalId, :startedAt, :dueAt)
        on conflict (task_id, goal_id) do nothing
        returning *
        """
    )
    suspend fun start(taskId: UUID, goalId: UUID, startedAt: OffsetDateTime, dueAt: OffsetDateTime): TaskSlaState?

    @Query(
        """
        update workops.task_sla_state
        set paused_at = now(), version = version + 1
        where task_id = :taskId and goal_id = :goalId
              and outcome = 'OPEN' and paused_at is null
        returning *
        """
    )
    suspend fun pause(taskId: UUID, goalId: UUID): TaskSlaState?

    @Query(
        """
        update workops.task_sla_state
        set paused_total_seconds = paused_total_seconds + extract(epoch from (now() - paused_at))::bigint,
            paused_at = null,
            due_at = due_at + (now() - paused_at),
            version = version + 1
        where task_id = :taskId and goal_id = :goalId
              and outcome = 'OPEN' and paused_at is not null
        returning *
        """
    )
    suspend fun resume(taskId: UUID, goalId: UUID): TaskSlaState?

    @Query(
        """
        update workops.task_sla_state
        set outcome = :outcome, version = version + 1
        where task_id = :taskId and goal_id = :goalId and outcome = 'OPEN'
        returning *
        """
    )
    suspend fun stop(taskId: UUID, goalId: UUID, outcome: String): TaskSlaState?

    @Query(
        """
        update workops.task_sla_state
        set at_risk_emitted = true, version = version + 1
        where task_id = :taskId and goal_id = :goalId
        """
    )
    suspend fun markAtRiskEmitted(taskId: UUID, goalId: UUID)

    @Query(
        """
        update workops.task_sla_state
        set breach_emitted = true, outcome = 'BREACHED', version = version + 1
        where task_id = :taskId and goal_id = :goalId
        """
    )
    suspend fun markBreachEmitted(taskId: UUID, goalId: UUID)

    @Query(
        """
        select * from workops.task_sla_state
        where outcome = 'OPEN'
          and (
              (breach_emitted = false and due_at <= :upTo)
              or (at_risk_emitted = false and due_at <= :upTo + (interval '1 minute' * :atRiskWindowMinutes))
          )
        order by due_at
        limit :limit
        """
    )
    suspend fun pendingBoundaries(upTo: OffsetDateTime, atRiskWindowMinutes: Int, limit: Int): List<TaskSlaState>
}
