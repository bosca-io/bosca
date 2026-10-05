package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.automation.AutomationExecutionLog
import bosca.workops.model.automation.AutomationRule

@Repository
interface AutomationRuleRepository {

    @Query("select * from workops.automation_rule where id = :id")
    suspend fun getById(id: UUID): AutomationRule?

    @Query(
        """
        select * from workops.automation_rule
        where scope = :scope and (:scopeId::uuid is null or scope_id = :scopeId)
          and enabled = true
        order by name
        """
    )
    suspend fun listEnabled(scope: String, scopeId: UUID?): List<AutomationRule>

    @Query(
        """
        select * from workops.automation_rule
        where scope = :scope and (:scopeId::uuid is null or scope_id = :scopeId)
        order by name
        """
    )
    suspend fun listInScope(scope: String, scopeId: UUID?): List<AutomationRule>

    @Query(
        """
        insert into workops.automation_rule
            (scope, scope_id, name, description, enabled,
             trigger, conditions, actions, run_as_profile_id,
             failure_mode, execution_log_retention_days,
             max_fires_per_task_per_hour)
        values
            (:scope, :scopeId, :name, :description, :enabled,
             cast(:trigger as jsonb), cast(:conditions as jsonb),
             cast(:actions as jsonb), :runAsProfileId,
             :failureMode, :executionLogRetentionDays,
             :maxFiresPerTaskPerHour)
        returning *
        """
    )
    suspend fun add(input: AutomationRuleInsertParams): AutomationRule

    @Query(
        """
        update workops.automation_rule
        set name = :name,
            description = :description,
            enabled = :enabled,
            trigger = cast(:trigger as jsonb),
            conditions = cast(:conditions as jsonb),
            actions = cast(:actions as jsonb),
            run_as_profile_id = :runAsProfileId,
            failure_mode = :failureMode,
            execution_log_retention_days = :executionLogRetentionDays,
            max_fires_per_task_per_hour = :maxFiresPerTaskPerHour,
            version = version + 1
        where id = :id and version = :expectedVersion
        returning *
        """
    )
    suspend fun update(input: AutomationRuleUpdateParams): AutomationRule?

    @Query("delete from workops.automation_rule where id = :id")
    suspend fun delete(id: UUID)
}

data class AutomationRuleInsertParams(
    val scope: String,
    val scopeId: UUID?,
    val name: String,
    val description: String?,
    val enabled: Boolean,
    val trigger: String,
    val conditions: String,
    val actions: String,
    val runAsProfileId: UUID,
    val failureMode: String,
    val executionLogRetentionDays: Int,
    val maxFiresPerTaskPerHour: Int,
)

data class AutomationRuleUpdateParams(
    val id: UUID,
    val name: String,
    val description: String?,
    val enabled: Boolean,
    val trigger: String,
    val conditions: String,
    val actions: String,
    val runAsProfileId: UUID,
    val failureMode: String,
    val executionLogRetentionDays: Int,
    val maxFiresPerTaskPerHour: Int,
    val expectedVersion: Long,
)

@Repository
interface AutomationExecutionLogRepository {

    @Query(
        """
        insert into workops.automation_execution_log
            (rule_id, task_id, outcome, started_at, finished_at, duration_ms, error_message)
        values (:ruleId, :taskId, :outcome, :startedAt, :finishedAt, :durationMs, :errorMessage)
        returning *
        """
    )
    suspend fun add(input: AutomationExecutionLogParams): AutomationExecutionLog

    @Query(
        """
        select * from workops.automation_execution_log
        where rule_id = :ruleId
        order by started_at desc
        limit :limit offset :offset
        """
    )
    suspend fun listForRule(ruleId: UUID, offset: Long, limit: Int): List<AutomationExecutionLog>

    @Query(
        """
        delete from workops.automation_execution_log
        where started_at < :cutoff
        """
    )
    suspend fun pruneOlderThan(cutoff: OffsetDateTime)
}

data class AutomationExecutionLogParams(
    val ruleId: UUID,
    val taskId: UUID?,
    val outcome: String,
    val startedAt: OffsetDateTime,
    val finishedAt: OffsetDateTime?,
    val durationMs: Long?,
    val errorMessage: String?,
)

@Repository
interface AutomationLoopGuardRepository {

    @Query(
        """
        insert into workops.automation_loop_guard (rule_id, task_id, fires_in_last_hour, last_fired_at)
        values (:ruleId, :taskId, 1, now())
        on conflict (rule_id, task_id) do update set
            fires_in_last_hour = case
                when workops.automation_loop_guard.last_fired_at < now() - interval '1 hour' then 1
                else workops.automation_loop_guard.fires_in_last_hour + 1
            end,
            last_fired_at = now()
        returning fires_in_last_hour
        """
    )
    suspend fun bump(ruleId: UUID, taskId: UUID): Long

    @Query(
        """
        select fires_in_last_hour from workops.automation_loop_guard
        where rule_id = :ruleId and task_id = :taskId
              and last_fired_at >= now() - interval '1 hour'
        """
    )
    suspend fun currentCount(ruleId: UUID, taskId: UUID): Long?
}
