package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.automation.Action
import bosca.workops.model.automation.AutomationExecutionLog
import bosca.workops.model.automation.AutomationOutcome
import bosca.workops.model.automation.AutomationRule
import bosca.workops.model.automation.AutomationScope
import bosca.workops.model.automation.FailureMode
import bosca.workops.model.automation.Trigger
import bosca.workops.model.task.Task
import bosca.workops.model.workflow.Condition

/**
 * R14 — admin CRUD for automation rules. Encoded JSONB blobs are
 * decoded on read by [AutomationExecutor].
 */
interface AutomationRuleService : Service {
    suspend fun getById(id: UUID): AutomationRule?
    suspend fun listInScope(scope: AutomationScope, scopeId: UUID?): List<AutomationRule>
    suspend fun listEnabledForScope(scope: AutomationScope, scopeId: UUID?): List<AutomationRule>
    suspend fun create(input: AutomationRuleInput): AutomationRule
    suspend fun update(id: UUID, input: AutomationRuleInput, expectedVersion: Long): AutomationRule
    suspend fun delete(id: UUID)
}

data class AutomationRuleInput(
    val scope: AutomationScope,
    val scopeId: UUID?,
    val name: String,
    val description: String?,
    val enabled: Boolean,
    val trigger: Trigger,
    val conditions: List<Condition>,
    val actions: List<Action>,
    val runAsProfileId: UUID,
    val failureMode: FailureMode,
    val executionLogRetentionDays: Int = 30,
    val maxFiresPerTaskPerHour: Int = 5,
)

/**
 * The dispatcher consumes domain events fired from controller
 * post-mutation hooks. For each event it loads the in-scope
 * enabled rules whose trigger matches and hands them to the
 * executor.
 */
interface AutomationDispatcher : Service {
    /** Routes a `TaskCreated` event to every matching rule. */
    suspend fun fireTaskCreated(task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?)
    suspend fun fireTaskUpdated(task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?, changedKeys: Set<String>)
    suspend fun fireTaskTransitioned(
        task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?,
        fromStatusId: UUID?, toStatusId: UUID,
    )
    suspend fun fireTaskCommented(task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?)
    suspend fun fireTaskDeleted(task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?)
    suspend fun fireSlaBreached(task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?, slaGoalId: UUID)
    suspend fun fireSlaAtRisk(task: Task, projectId: UUID, programId: UUID?, portfolioId: UUID?, slaGoalId: UUID)
    suspend fun fireArtifactPublished(projectId: UUID, programId: UUID?, portfolioId: UUID?, artifactType: String, coordinates: String)
    suspend fun firePipelineFailed(projectId: UUID, programId: UUID?, portfolioId: UUID?, pipelineId: String)
    suspend fun firePipelineCompleted(projectId: UUID, programId: UUID?, portfolioId: UUID?, pipelineId: String)
    suspend fun fireDependencyOutdated(consumerProjectId: UUID, programId: UUID?, portfolioId: UUID?)
    suspend fun fireEnvironmentDeploymentFailed(projectId: UUID, programId: UUID?, portfolioId: UUID?, environmentId: UUID)
    suspend fun fireEnvironmentUnhealthy(projectId: UUID, programId: UUID?, portfolioId: UUID?, environmentId: UUID)
    suspend fun fireEnvironmentPromotionReady(projectId: UUID, programId: UUID?, portfolioId: UUID?, environmentId: UUID)
}

/** Carries the triggering payload through the executor pipeline. */
data class AutomationContext(
    val triggeringTask: Task?,
    val projectId: UUID,
    val changedKeys: Set<String> = emptySet(),
    val eventMetadata: Map<String, String> = emptyMap(),
)

/**
 * Runs a single rule. Variants whose backing service hasn't
 * shipped raise [PendingPhaseImplementationException]; the
 * executor catches that and writes a `LOOP_GUARD_TRIPPED` /
 * `ACTION_FAILED` log row depending on the failure mode.
 */
interface AutomationExecutor : Service {
    suspend fun run(rule: AutomationRule, context: AutomationContext): AutomationOutcome
}

/** Read surface for the audit / observability UI. */
interface AutomationExecutionLogService : Service {
    suspend fun listForRule(ruleId: UUID, offset: Long, limit: Int): List<AutomationExecutionLog>
}
