package bosca.workops.service

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.WorkflowConditionFailedException
import bosca.workops.model.WorkflowValidatorFailedException
import bosca.workops.model.task.Task
import bosca.workops.model.workflow.ComparisonOperator
import bosca.workops.model.workflow.Condition
import bosca.workops.model.workflow.PostFunction
import bosca.workops.model.workflow.Validator
import bosca.workops.model.workflow.WorkflowTransition
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Inputs the workflow engine needs to evaluate a transition that
 * aren't in the [Task] row itself: the acting principal / profile,
 * the optional comment supplied with the transition, and the
 * pre-resolved status category map (so the engine can answer
 * `RequireSubtasksResolved` without a per-call SQL round-trip).
 */
data class WorkflowContext(
    val task: Task,
    val actingPrincipalId: UUID,
    val actingProfileId: UUID?,
    val comment: String?,
    val resolutionId: UUID?,
    val unresolvedSubtaskCount: Int,
    /**
     * The standard permission actions the principal holds against the task,
     * pre-resolved through the [TaskPermissionEvaluator]. Used by
     * [Condition.HasGlobalPermission] without round-tripping back to
     * the evaluator.
     */
    val principalPermissions: Set<PermissionAction> = emptySet(),
)

@Repository
interface WorkflowQueryRepository {

    @Query(
        """
        select count(*)
        from workops.task t
        join workops.status s on s.id = t.status_id
        where t.parent_task_id = :taskId
          and t.deleted_at is null
          and s.category not in ('DONE', 'CANCELLED')
        """
    )
    suspend fun countUnresolvedSubtasks(taskId: UUID): Long
}

sealed class TransitionEvaluation {
    data class Plan(
        val transition: WorkflowTransition,
        val postFunctions: List<PostFunction>,
    ) : TransitionEvaluation()

    data class ConditionFailed(val transition: WorkflowTransition, val reason: String) : TransitionEvaluation()

    data class ValidatorFailed(val transition: WorkflowTransition, val reason: String) : TransitionEvaluation()
}

/**
 * Pure predicate / planner over the workflow's
 * Condition / Validator / PostFunction trees. The evaluator never
 * mutates the task — that's the service's job, inside a single
 * transaction so post-function failures roll back the status flip.
 */
class WorkflowEvaluator {

    /**
     * Evaluates a bare list of [conditions] against [context], returning true only if every one
     * holds. Used by the automation engine to gate a rule's actions (the workflow transition
     * machinery isn't involved there), reusing the exact same condition semantics as transitions.
     */
    fun conditionsHold(conditions: List<Condition>, context: WorkflowContext): Boolean =
        conditions.all { evaluateCondition(it, context) }

    fun evaluate(
        transition: WorkflowTransition,
        conditions: List<Condition>,
        validators: List<Validator>,
        postFunctions: List<PostFunction>,
        context: WorkflowContext,
    ): TransitionEvaluation {
        for (condition in conditions) {
            if (!evaluateCondition(condition, context)) {
                return TransitionEvaluation.ConditionFailed(
                    transition = transition,
                    reason = "${condition::class.simpleName} denied",
                )
            }
        }
        for (validator in validators) {
            val failure = evaluateValidator(validator, context)
            if (failure != null) {
                return TransitionEvaluation.ValidatorFailed(
                    transition = transition,
                    reason = failure,
                )
            }
        }
        return TransitionEvaluation.Plan(transition, postFunctions)
    }

    private fun evaluateCondition(condition: Condition, context: WorkflowContext): Boolean = when (condition) {
        is Condition.Always -> true
        is Condition.Never -> false
        is Condition.IsAssignee -> context.actingProfileId != null && context.actingProfileId == context.task.assigneeProfileId
        is Condition.IsReporter -> context.actingProfileId != null && context.actingProfileId == context.task.reporterProfileId
        is Condition.AllOf -> condition.conditions.all { evaluateCondition(it, context) }
        is Condition.AnyOf -> condition.conditions.any { evaluateCondition(it, context) }
        is Condition.Not -> !evaluateCondition(condition.condition, context)
        is Condition.HasProjectRole -> false
        is Condition.IsInProjectRoles -> false
        is Condition.HasGlobalPermission -> {
            val action = runCatching { PermissionAction.valueOf(condition.permission.uppercase()) }.getOrNull()
            action != null && action in context.principalPermissions
        }
        is Condition.HasFieldValue -> {
            val value = readBuiltinFieldValue(condition.fieldKey, context.task)
                ?: return false
            applyOperator(condition.operator, value, condition.value)
        }
    }

    private fun evaluateValidator(validator: Validator, context: WorkflowContext): String? = when (validator) {
        is Validator.RequireField -> if (readBuiltinFieldValue(validator.fieldKey, context.task) == null) {
            "${validator.fieldKey} is required"
        } else null

        is Validator.RequireFieldValue -> {
            val value = readBuiltinFieldValue(validator.fieldKey, context.task)
            if (value == null || !applyOperator(validator.operator, value, validator.value)) {
                "${validator.fieldKey} did not satisfy ${validator.operator}"
            } else null
        }

        is Validator.RequireResolution -> if (context.resolutionId == null && context.task.resolutionId == null) {
            "resolution is required"
        } else null

        is Validator.RequireComment -> if (context.comment.isNullOrBlank()) {
            "a comment is required for this transition"
        } else null

        is Validator.RequireSubtasksResolved -> if (context.unresolvedSubtaskCount > 0) {
            "${context.unresolvedSubtaskCount} sub-tasks remain unresolved"
        } else null

        is Validator.Custom -> throw PendingPhaseImplementationException(
            variant = "Validator.Custom(${validator.scriptKey})",
            owningPhase = 11,
        )
    }

    private fun readBuiltinFieldValue(fieldKey: String, task: Task): JsonElement? {
        return when (fieldKey) {
            "summary" -> JsonPrimitive(task.summary)
            "description_markdown" -> task.descriptionMarkdown?.let(::JsonPrimitive) ?: JsonNull
            "assignee_profile_id" -> task.assigneeProfileId?.let { JsonPrimitive(it.toString()) }
            "reporter_profile_id" -> JsonPrimitive(task.reporterProfileId.toString())
            "priority_id" -> JsonPrimitive(task.priorityId.toString())
            "status_id" -> JsonPrimitive(task.statusId.toString())
            "resolution_id" -> task.resolutionId?.let { JsonPrimitive(it.toString()) }
            "due_date" -> task.dueDate?.let { JsonPrimitive(it.toString()) }
            "start_date" -> task.startDate?.let { JsonPrimitive(it.toString()) }
            else -> throw PendingPhaseImplementationException(
                variant = "field key '$fieldKey'",
                owningPhase = 4,
            )
        }
    }

    private fun applyOperator(op: ComparisonOperator, actual: JsonElement, expected: JsonElement): Boolean {
        return when (op) {
            ComparisonOperator.EQ -> actual == expected
            ComparisonOperator.NEQ -> actual != expected
            ComparisonOperator.EXISTS -> actual !is JsonNull
            ComparisonOperator.IN, ComparisonOperator.NOT_IN, ComparisonOperator.CONTAINS,
            ComparisonOperator.LT, ComparisonOperator.LTE, ComparisonOperator.GT, ComparisonOperator.GTE ->
                throw WorkOpsValidationException("operator", "$op is not supported on Phase 3 built-in fields")
        }
    }
}

fun TransitionEvaluation.requireApprovedOrThrow(): TransitionEvaluation.Plan = when (this) {
    is TransitionEvaluation.Plan -> this
    is TransitionEvaluation.ConditionFailed ->
        throw WorkflowConditionFailedException(transition.name, reason)
    is TransitionEvaluation.ValidatorFailed ->
        throw WorkflowValidatorFailedException(transition.name, reason)
}
