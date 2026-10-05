package bosca.workops.model.automation

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.workflow.Condition
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class AutomationScope { GLOBAL, PORTFOLIO, PROGRAM, PROJECT }

@Serializable
enum class FailureMode { STOP_ON_ERROR, CONTINUE }

/**
 * The terminal outcome the executor records for every rule run.
 * Successful runs carry `OK`; condition-driven skips are
 * `SKIPPED`. Hard failures land in one of the three error
 * outcomes so dashboards can break them out.
 */
@Serializable
enum class AutomationOutcome {
    OK,
    SKIPPED,
    LOOP_GUARD_TRIPPED,
    PERMISSION_DENIED,
    ACTION_FAILED,
    NOT_IMPLEMENTED,
    INTERNAL_ERROR,
}

/**
 * R14 — every Trigger kind the dispatcher routes events into.
 * The acceptance criteria mention exactly these variants.
 */
@Serializable
sealed class Trigger {
    @Serializable @SerialName("TaskCreated")
    data class TaskCreated(val filter: String? = null) : Trigger()

    @Serializable @SerialName("TaskUpdated")
    data class TaskUpdated(
        val filter: String? = null,
        val fieldKeys: List<String> = emptyList(),
    ) : Trigger()

    @Serializable @SerialName("TaskTransitioned")
    data class TaskTransitioned(
        val fromStatusIds: List<@Contextual UUID> = emptyList(),
        val toStatusIds: List<@Contextual UUID> = emptyList(),
        val filter: String? = null,
    ) : Trigger()

    @Serializable @SerialName("TaskCommented")
    data class TaskCommented(val filter: String? = null) : Trigger()

    @Serializable @SerialName("TaskDeleted")
    data class TaskDeleted(val filter: String? = null) : Trigger()

    @Serializable @SerialName("Scheduled")
    data class Scheduled(val cron: String, val filter: String? = null) : Trigger()

    @Serializable @SerialName("SlaBreached")
    data class SlaBreached(val slaGoalIds: List<@Contextual UUID> = emptyList()) : Trigger()

    @Serializable @SerialName("SlaAtRisk")
    data class SlaAtRisk(val slaGoalIds: List<@Contextual UUID> = emptyList()) : Trigger()

    @Serializable @SerialName("IncomingWebhook")
    data class IncomingWebhook(val secret: String) : Trigger()

    @Serializable @SerialName("ManualButton")
    data class ManualButton(
        val label: String,
        @Contextual val screenId: UUID? = null,
    ) : Trigger()

    @Serializable @SerialName("ExperimentVerdict")
    data class ExperimentVerdict(
        @Contextual val experimentId: UUID? = null,
        val verdicts: List<String> = emptyList(),
    ) : Trigger()

    @Serializable @SerialName("ArtifactPublished")
    data class ArtifactPublished(
        @Contextual val projectId: UUID? = null,
        val artifactTypes: List<String> = emptyList(),
    ) : Trigger()

    @Serializable @SerialName("PipelineFailed")
    data class PipelineFailed(
        @Contextual val projectId: UUID? = null,
        val pipelineIds: List<String> = emptyList(),
    ) : Trigger()

    @Serializable @SerialName("PipelineCompleted")
    data class PipelineCompleted(
        @Contextual val projectId: UUID? = null,
        val pipelineIds: List<String> = emptyList(),
    ) : Trigger()

    @Serializable @SerialName("DependencyOutdated")
    data class DependencyOutdated(
        @Contextual val consumerProjectId: UUID? = null,
    ) : Trigger()

    @Serializable @SerialName("EnvironmentDeploymentFailed")
    data class EnvironmentDeploymentFailed(
        @Contextual val environmentId: UUID? = null,
    ) : Trigger()

    @Serializable @SerialName("EnvironmentUnhealthy")
    data class EnvironmentUnhealthy(
        @Contextual val environmentId: UUID? = null,
    ) : Trigger()

    @Serializable @SerialName("EnvironmentPromotionReady")
    data class EnvironmentPromotionReady(
        @Contextual val environmentId: UUID? = null,
    ) : Trigger()
}

/**
 * R14 — every Action kind the executor knows. Variants whose
 * dependencies haven't shipped (RunScript, Sleep, Branch, ForEach,
 * Lookup, AssignToOnCallRotation, CreateSubtask, CreateTask) still
 * round-trip through persistence; the executor raises
 * `PendingPhaseImplementationException` for those at run time so
 * admins can author the rule today.
 */
@Serializable
sealed class Action {
    @Serializable @SerialName("EditTask")
    data class EditTask(
        @Contextual val fields: JsonElement = JsonObject(emptyMap()),
    ) : Action()

    @Serializable @SerialName("TransitionTask")
    data class TransitionTask(@Contextual val transitionId: UUID) : Action()

    @Serializable @SerialName("AddComment")
    data class AddComment(val template: String) : Action()

    @Serializable @SerialName("CreateTask")
    data class CreateTask(
        @Contextual val projectId: UUID,
        @Contextual val fields: JsonElement = JsonObject(emptyMap()),
    ) : Action()

    @Serializable @SerialName("CreateSubtask")
    data class CreateSubtask(
        @Contextual val template: JsonElement = JsonObject(emptyMap()),
    ) : Action()

    @Serializable @SerialName("LinkTasks")
    data class LinkTasks(
        @Contextual val linkTypeId: UUID,
        val targetExpression: String,
    ) : Action()

    @Serializable @SerialName("AssignToOnCallRotation")
    data class AssignToOnCallRotation(@Contextual val rotationId: UUID) : Action()

    @Serializable @SerialName("AssignTo")
    data class AssignTo(val profileExpression: String) : Action()

    @Serializable @SerialName("SetFieldValue")
    data class SetFieldValue(val fieldKey: String, val expression: String) : Action()

    @Serializable @SerialName("SendWebhook")
    data class SendWebhook(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val bodyTemplate: String,
    ) : Action()

    @Serializable @SerialName("SendEmail")
    data class SendEmail(
        val toExpression: String,
        val subject: String,
        val body: String,
    ) : Action()

    @Serializable @SerialName("SendSlackMessage")
    data class SendSlackMessage(val channel: String, val body: String) : Action()

    @Serializable @SerialName("RunScript")
    data class RunScript(val scriptKey: String) : Action()

    @Serializable @SerialName("Branch")
    data class Branch(
        val condition: Condition,
        val ifActions: List<Action> = emptyList(),
        val elseActions: List<Action> = emptyList(),
    ) : Action()

    @Serializable @SerialName("ForEach")
    data class ForEach(
        val collectionExpression: String,
        val actions: List<Action> = emptyList(),
    ) : Action()

    @Serializable @SerialName("Sleep")
    data class Sleep(val durationSeconds: Long) : Action() {
        init {
            require(durationSeconds in 1..86400) { "sleep duration must be 1–86400 seconds" }
        }
    }

    @Serializable @SerialName("Lookup")
    data class Lookup(val query: String, val bindAs: String) : Action()
}

/**
 * Persisted rule. Trigger, conditions, and actions ride as JSONB
 * blobs; the executor decodes them lazily.
 */
@Serializable
data class AutomationRule(
    @Contextual
    val id: UUID = UUID.NIL,
    val scope: AutomationScope,
    @ColumnName("scope_id")
    @Contextual
    val scopeId: UUID? = null,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    @Contextual
    val trigger: JsonElement,
    @Contextual
    val conditions: JsonElement = JsonObject(emptyMap()),
    @Contextual
    val actions: JsonElement = JsonObject(emptyMap()),
    @ColumnName("run_as_profile_id")
    @Contextual
    val runAsProfileId: UUID,
    @ColumnName("failure_mode")
    val failureMode: FailureMode = FailureMode.STOP_ON_ERROR,
    @ColumnName("execution_log_retention_days")
    val executionLogRetentionDays: Int = 30,
    @ColumnName("max_fires_per_task_per_hour")
    val maxFiresPerTaskPerHour: Int = 5,
    val version: Long = 0,
) {
    init {
        require(name.isNotBlank()) { "automation rule name must not be blank" }
        require(maxFiresPerTaskPerHour > 0) { "maxFiresPerTaskPerHour must be positive" }
        require(executionLogRetentionDays > 0) { "executionLogRetentionDays must be positive" }
        require(scope == AutomationScope.GLOBAL || scopeId != null) { "$scope scope requires a non-null scopeId" }
        require(scope != AutomationScope.GLOBAL || scopeId == null) { "GLOBAL scope must have null scopeId" }
    }
}

/**
 * One row per execution attempt. Successful runs carry
 * `outcome = OK`; loop-guard skips and permission denials carry
 * the matching `AutomationOutcome` value.
 */
@Serializable
data class AutomationExecutionLog(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("rule_id")
    @Contextual
    val ruleId: UUID,
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID? = null,
    val outcome: AutomationOutcome,
    @ColumnName("started_at")
    @Contextual
    val startedAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("finished_at")
    @Contextual
    val finishedAt: OffsetDateTime? = null,
    @ColumnName("duration_ms")
    val durationMs: Long? = null,
    @ColumnName("error_message")
    val errorMessage: String? = null,
)
