package bosca.workops.service

import bosca.security.model.PermissionAction
import bosca.serialization.OffsetDateTime
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkflowEvaluatorTest {

    private val evaluator = WorkflowEvaluator()
    private val reporterId = UUID.random()
    private val assigneeId = UUID.random()
    private val statusId = UUID.random()
    private val priorityId = UUID.random()
    private val due = OffsetDateTime.parse("2026-09-02T12:00:00Z")
    private val start = OffsetDateTime.parse("2026-08-20T12:00:00Z")
    private val resolutionId = UUID.random()

    private fun task(
        assignee: UUID? = assigneeId,
        description: String? = "Description",
        resolution: UUID? = null,
        resolutionAt: OffsetDateTime? = null,
        dueDate: OffsetDateTime? = due,
        startDate: OffsetDateTime? = start,
    ) = Task(
        id = UUID.random(),
        key = "WORK-1",
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = statusId,
        priorityId = priorityId,
        summary = "Ship release",
        descriptionMarkdown = description,
        reporterProfileId = reporterId,
        assigneeProfileId = assignee,
        dueDate = dueDate,
        startDate = startDate,
        resolutionId = resolution,
        resolutionAt = resolutionAt,
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
    )

    private fun context(
        task: Task = task(),
        actingProfileId: UUID? = assigneeId,
        comment: String? = "Looks good",
        resolutionId: UUID? = null,
        unresolvedSubtaskCount: Int = 0,
        permissions: Set<PermissionAction> = setOf(PermissionAction.EDIT),
    ) = WorkflowContext(
        task = task,
        actingPrincipalId = UUID.random(),
        actingProfileId = actingProfileId,
        comment = comment,
        resolutionId = resolutionId,
        unresolvedSubtaskCount = unresolvedSubtaskCount,
        principalPermissions = permissions,
    )

    private fun transition() = WorkflowTransition(
        workflowId = UUID.random(),
        name = "Complete",
        fromStateIds = listOf(UUID.random().toString()),
        toStateId = UUID.random(),
    )

    @Test
    fun `identity and boolean composition conditions use short circuit semantics`() {
        assertTrue(evaluator.conditionsHold(listOf(Condition.Always), context()))
        assertEquals(false, evaluator.conditionsHold(listOf(Condition.Never), context()))
        assertTrue(evaluator.conditionsHold(listOf(Condition.IsAssignee), context()))
        assertEquals(false, evaluator.conditionsHold(listOf(Condition.IsAssignee), context(actingProfileId = null)))
        assertEquals(false, evaluator.conditionsHold(listOf(Condition.IsAssignee), context(actingProfileId = UUID.random())))
        assertTrue(evaluator.conditionsHold(listOf(Condition.IsReporter), context(actingProfileId = reporterId)))
        assertEquals(false, evaluator.conditionsHold(listOf(Condition.IsReporter), context(actingProfileId = null)))
        assertEquals(false, evaluator.conditionsHold(listOf(Condition.IsReporter), context(actingProfileId = UUID.random())))

        assertTrue(evaluator.conditionsHold(listOf(Condition.AllOf(emptyList())), context()))
        assertTrue(evaluator.conditionsHold(listOf(Condition.AllOf(listOf(Condition.Always, Condition.Always))), context()))
        assertEquals(false, evaluator.conditionsHold(listOf(Condition.AllOf(listOf(Condition.Always, Condition.Never))), context()))
        assertEquals(false, evaluator.conditionsHold(listOf(Condition.AnyOf(emptyList())), context()))
        assertTrue(evaluator.conditionsHold(listOf(Condition.AnyOf(listOf(Condition.Never, Condition.Always))), context()))
        assertTrue(evaluator.conditionsHold(listOf(Condition.Not(Condition.Never)), context()))
        assertEquals(false, evaluator.conditionsHold(listOf(Condition.Not(Condition.Always)), context()))
    }

    @Test
    fun `role and permission conditions deny unsupported roles and resolve known permissions`() {
        assertEquals(
            false,
            evaluator.conditionsHold(listOf(Condition.HasProjectRole(UUID.random())), context()),
        )
        assertEquals(
            false,
            evaluator.conditionsHold(listOf(Condition.IsInProjectRoles(listOf(UUID.random()))), context()),
        )
        assertTrue(
            evaluator.conditionsHold(
                listOf(Condition.HasGlobalPermission("edit")),
                context(permissions = setOf(PermissionAction.EDIT)),
            ),
        )
        assertEquals(
            false,
            evaluator.conditionsHold(
                listOf(Condition.HasGlobalPermission("manage")),
                context(permissions = setOf(PermissionAction.EDIT)),
            ),
        )
        assertEquals(
            false,
            evaluator.conditionsHold(listOf(Condition.HasGlobalPermission("not-a-permission")), context()),
        )
    }

    @Test
    fun `field conditions expose every supported task field and null semantics`() {
        val resolvedTask = task(resolution = resolutionId, resolutionAt = due)
        val expected = mapOf(
            "summary" to JsonPrimitive("Ship release"),
            "description_markdown" to JsonPrimitive("Description"),
            "assignee_profile_id" to JsonPrimitive(assigneeId.toString()),
            "reporter_profile_id" to JsonPrimitive(reporterId.toString()),
            "priority_id" to JsonPrimitive(priorityId.toString()),
            "status_id" to JsonPrimitive(statusId.toString()),
            "resolution_id" to JsonPrimitive(resolutionId.toString()),
            "due_date" to JsonPrimitive(due.toString()),
            "start_date" to JsonPrimitive(start.toString()),
        )
        expected.forEach { (field, value) ->
            assertTrue(
                evaluator.conditionsHold(
                    listOf(Condition.HasFieldValue(field, ComparisonOperator.EQ, value)),
                    context(task = resolvedTask),
                ),
                field,
            )
            assertTrue(
                evaluator.conditionsHold(
                    listOf(Condition.HasFieldValue(field, ComparisonOperator.NEQ, JsonPrimitive("different"))),
                    context(task = resolvedTask),
                ),
                field,
            )
            assertTrue(
                evaluator.conditionsHold(
                    listOf(Condition.HasFieldValue(field, ComparisonOperator.EXISTS, JsonNull)),
                    context(task = resolvedTask),
                ),
                field,
            )
        }

        val sparse = task(assignee = null, description = null, dueDate = null, startDate = null)
        assertEquals(
            false,
            evaluator.conditionsHold(
                listOf(Condition.HasFieldValue("assignee_profile_id", ComparisonOperator.EXISTS, JsonNull)),
                context(task = sparse),
            ),
        )
        assertEquals(
            false,
            evaluator.conditionsHold(
                listOf(Condition.HasFieldValue("description_markdown", ComparisonOperator.EXISTS, JsonNull)),
                context(task = sparse),
            ),
        )
        assertTrue(
            evaluator.conditionsHold(
                listOf(Condition.HasFieldValue("description_markdown", ComparisonOperator.EQ, JsonNull)),
                context(task = sparse),
            ),
        )
        listOf("resolution_id", "due_date", "start_date").forEach { field ->
            assertEquals(
                false,
                evaluator.conditionsHold(
                    listOf(Condition.HasFieldValue(field, ComparisonOperator.EXISTS, JsonNull)),
                    context(task = sparse),
                ),
                field,
            )
        }
        assertEquals(
            false,
            evaluator.conditionsHold(
                listOf(Condition.HasFieldValue("summary", ComparisonOperator.NEQ, JsonPrimitive("Ship release"))),
                context(task = sparse),
            ),
        )
    }

    @Test
    fun `unsupported field and comparison operators fail explicitly`() {
        assertFailsWith<PendingPhaseImplementationException> {
            evaluator.conditionsHold(
                listOf(Condition.HasFieldValue("custom_field", ComparisonOperator.EQ, JsonPrimitive("x"))),
                context(),
            )
        }
        listOf(
            ComparisonOperator.IN,
            ComparisonOperator.NOT_IN,
            ComparisonOperator.CONTAINS,
            ComparisonOperator.LT,
            ComparisonOperator.LTE,
            ComparisonOperator.GT,
            ComparisonOperator.GTE,
        ).forEach { operator ->
            val failure = assertFailsWith<WorkOpsValidationException> {
                evaluator.conditionsHold(
                    listOf(Condition.HasFieldValue("summary", operator, JsonPrimitive("Ship release"))),
                    context(),
                )
            }
            assertTrue(failure.message.orEmpty().contains(operator.name))
        }
    }

    @Test
    fun `field and transition validators return observable failure reasons`() {
        val tx = transition()
        fun evaluate(validator: Validator, ctx: WorkflowContext = context()) =
            evaluator.evaluate(tx, emptyList(), listOf(validator), emptyList(), ctx)

        assertIs<TransitionEvaluation.Plan>(evaluate(Validator.RequireField("assignee_profile_id")))
        val missingField = assertIs<TransitionEvaluation.ValidatorFailed>(
            evaluate(Validator.RequireField("assignee_profile_id"), context(task = task(assignee = null))),
        )
        assertEquals("assignee_profile_id is required", missingField.reason)

        assertIs<TransitionEvaluation.Plan>(
            evaluate(Validator.RequireFieldValue("summary", ComparisonOperator.EQ, JsonPrimitive("Ship release"))),
        )
        val wrongValue = assertIs<TransitionEvaluation.ValidatorFailed>(
            evaluate(Validator.RequireFieldValue("summary", ComparisonOperator.EQ, JsonPrimitive("Other"))),
        )
        assertTrue(wrongValue.reason.contains("did not satisfy EQ"))
        val absentValue = assertIs<TransitionEvaluation.ValidatorFailed>(
            evaluate(
                Validator.RequireFieldValue("assignee_profile_id", ComparisonOperator.EXISTS, JsonNull),
                context(task = task(assignee = null)),
            ),
        )
        assertTrue(absentValue.reason.contains("assignee_profile_id"))

        assertIs<TransitionEvaluation.ValidatorFailed>(evaluate(Validator.RequireResolution))
        assertIs<TransitionEvaluation.Plan>(evaluate(Validator.RequireResolution, context(resolutionId = resolutionId)))
        assertIs<TransitionEvaluation.Plan>(
            evaluate(
                Validator.RequireResolution,
                context(task = task(resolution = resolutionId, resolutionAt = due)),
            ),
        )
        assertIs<TransitionEvaluation.ValidatorFailed>(evaluate(Validator.RequireComment, context(comment = null)))
        assertIs<TransitionEvaluation.ValidatorFailed>(evaluate(Validator.RequireComment, context(comment = "  ")))
        assertIs<TransitionEvaluation.Plan>(evaluate(Validator.RequireComment))
        assertIs<TransitionEvaluation.ValidatorFailed>(
            evaluate(Validator.RequireSubtasksResolved, context(unresolvedSubtaskCount = 2)),
        )
        assertIs<TransitionEvaluation.Plan>(evaluate(Validator.RequireSubtasksResolved))
        assertFailsWith<PendingPhaseImplementationException> {
            evaluate(Validator.Custom("validate-release"))
        }
    }

    @Test
    fun `evaluation stops on conditions then validators and returns the authored post functions`() {
        val tx = transition()
        val conditionFailure = assertIs<TransitionEvaluation.ConditionFailed>(
            evaluator.evaluate(
                tx,
                listOf(Condition.Never),
                listOf(Validator.Custom("must-not-run")),
                emptyList(),
                context(),
            ),
        )
        assertSame(tx, conditionFailure.transition)
        assertEquals("Never denied", conditionFailure.reason)

        val validatorFailure = assertIs<TransitionEvaluation.ValidatorFailed>(
            evaluator.evaluate(
                tx,
                listOf(Condition.Always),
                listOf(Validator.RequireComment),
                emptyList(),
                context(comment = null),
            ),
        )
        assertSame(tx, validatorFailure.transition)

        val functions = listOf<PostFunction>(PostFunction.AssignToReporter, PostFunction.ClearResolution)
        val plan = assertIs<TransitionEvaluation.Plan>(
            evaluator.evaluate(tx, listOf(Condition.Always), emptyList(), functions, context()),
        )
        assertSame(tx, plan.transition)
        assertEquals(functions, plan.postFunctions)
    }

    @Test
    fun `approved evaluation unwraps while denied evaluations throw domain errors`() {
        val tx = transition()
        val plan = TransitionEvaluation.Plan(tx, emptyList())
        assertSame(plan, plan.requireApprovedOrThrow())
        assertFailsWith<WorkflowConditionFailedException> {
            TransitionEvaluation.ConditionFailed(tx, "not assignee").requireApprovedOrThrow()
        }
        assertFailsWith<WorkflowValidatorFailedException> {
            TransitionEvaluation.ValidatorFailed(tx, "comment required").requireApprovedOrThrow()
        }
    }
}
