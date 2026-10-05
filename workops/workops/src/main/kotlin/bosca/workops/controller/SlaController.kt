package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.workops.model.sla.SlaGoal
import bosca.workops.model.sla.SlaPolicy
import bosca.workops.model.sla.TaskSlaState
import bosca.workops.model.sla.WorkingCalendar
import bosca.workops.repository.TaskSlaStateRepository
import bosca.workops.service.SlaPolicyService
import bosca.workops.service.WorkingCalendarInput
import bosca.workops.service.WorkingCalendarService
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Wire-side input for `createWorkingCalendar` — the JSON wire
 * shape gets re-encoded into the strings the repository expects.
 */
@Serializable
data class CreateWorkingCalendarInput(
    val name: String,
    val description: String? = null,
    val timeZone: String,
    @Contextual
    val weeklyHours: JsonElement,
    @Contextual
    val holidays: JsonElement,
)

/** GraphQL projection of [TaskSlaState] with derived flags. */
data class SlaGoalStateView(
    val taskId: UUID,
    val goalId: UUID,
    val startedAt: java.time.OffsetDateTime,
    val pausedAt: java.time.OffsetDateTime?,
    val pausedTotalSeconds: Long,
    val dueAt: java.time.OffsetDateTime,
    val outcome: String,
    val atRisk: Boolean,
    val breached: Boolean,
)

@TypeController(type = "WorkOpsWorkingCalendar")
class WorkingCalendarTypeController : GraphQLController<WorkingCalendar> {
    @Field fun id(cal: WorkingCalendar) = cal.id
    @Field fun name(cal: WorkingCalendar) = cal.name
    @Field fun description(cal: WorkingCalendar) = cal.description
    @Field fun timeZone(cal: WorkingCalendar) = cal.timeZone
    @Field fun weeklyHours(cal: WorkingCalendar): JsonElement = cal.weeklyHours
    @Field fun holidays(cal: WorkingCalendar): JsonElement = cal.holidays
    @Field fun version(cal: WorkingCalendar) = cal.version
}

@TypeController(type = "WorkOpsSlaPolicy")
class SlaPolicyTypeController(
    private val policyService: SlaPolicyService,
) : GraphQLController<SlaPolicy> {
    @Field fun id(p: SlaPolicy) = p.id
    @Field fun name(p: SlaPolicy) = p.name
    @Field fun description(p: SlaPolicy) = p.description
    @Field fun version(p: SlaPolicy) = p.version
    @Field suspend fun goals(p: SlaPolicy): List<SlaGoal> = policyService.goalsFor(p.id)
}

@TypeController(type = "WorkOpsSlaGoal")
class SlaGoalTypeController : GraphQLController<SlaGoal> {
    @Field fun id(g: SlaGoal) = g.id
    @Field fun policyId(g: SlaGoal) = g.policyId
    @Field fun name(g: SlaGoal) = g.name
    @Field fun startConditions(g: SlaGoal) = g.startConditions
    @Field fun pauseConditions(g: SlaGoal) = g.pauseConditions
    @Field fun stopConditions(g: SlaGoal) = g.stopConditions
    @Field fun targetMinutes(g: SlaGoal) = g.targetMinutes
    @Field fun atRiskAtPercent(g: SlaGoal) = g.atRiskAtPercent
    @Field fun calendarId(g: SlaGoal) = g.calendarId
    @Field fun displayOrder(g: SlaGoal) = g.displayOrder
}

@TypeController(type = "WorkOpsSlaGoalState")
class SlaGoalStateTypeController : GraphQLController<SlaGoalStateView> {
    @Field fun taskId(v: SlaGoalStateView) = v.taskId
    @Field fun goalId(v: SlaGoalStateView) = v.goalId
    @Field fun startedAt(v: SlaGoalStateView) = v.startedAt
    @Field fun pausedAt(v: SlaGoalStateView) = v.pausedAt
    @Field fun pausedTotalSeconds(v: SlaGoalStateView) = v.pausedTotalSeconds
    @Field fun dueAt(v: SlaGoalStateView) = v.dueAt
    @Field fun outcome(v: SlaGoalStateView) = v.outcome
    @Field fun atRisk(v: SlaGoalStateView) = v.atRisk
    @Field fun breached(v: SlaGoalStateView) = v.breached
}

object WorkOpsSlaQuery

@TypeController
class SlaQueryController(
    private val calendarService: WorkingCalendarService,
    private val policyService: SlaPolicyService,
    private val stateRepository: TaskSlaStateRepository,
    private val groupEvaluator: GroupEvaluator,
    private val taskService: bosca.workops.service.TaskService,
    private val taskPermissions: bosca.workops.service.TaskPermissionEvaluator,
) : GraphQLController<WorkOpsSlaQuery> {

    @Field
    suspend fun workingCalendars(authentication: AuthenticationContext): List<WorkingCalendar> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return calendarService.list()
    }

    @Field
    suspend fun workingCalendar(authentication: AuthenticationContext, id: UUID): WorkingCalendar? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return calendarService.getById(id)
    }

    @Field
    suspend fun slaPolicies(authentication: AuthenticationContext): List<SlaPolicy> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return policyService.list()
    }

    @Field
    suspend fun slaPolicy(authentication: AuthenticationContext, id: UUID): SlaPolicy? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return policyService.getById(id)
    }

    @Field
    suspend fun taskSlaStates(authentication: AuthenticationContext, taskId: UUID): List<SlaGoalStateView> {
        val task = taskService.getById(taskId) ?: error("Task $taskId not found")
        taskPermissions.verifyAllowed(authentication, task, bosca.security.model.PermissionAction.VIEW)
        return stateRepository.listForTask(taskId).map { it.toView() }
    }

    private fun TaskSlaState.toView() = SlaGoalStateView(
        taskId = taskId, goalId = goalId,
        startedAt = startedAt, pausedAt = pausedAt,
        pausedTotalSeconds = pausedTotalSeconds, dueAt = dueAt,
        outcome = outcome.name,
        atRisk = atRiskEmitted,
        breached = breachEmitted,
    )
}

object WorkOpsSlaMutation

@TypeController
class SlaMutationController(
    private val calendarService: WorkingCalendarService,
    private val policyService: SlaPolicyService,
    private val groupEvaluator: GroupEvaluator,
    private val json: Json,
) : GraphQLController<WorkOpsSlaMutation> {

    @Field
    suspend fun createWorkingCalendar(
        authentication: AuthenticationContext,
        input: CreateWorkingCalendarInput,
    ): WorkingCalendar {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return calendarService.create(
            WorkingCalendarInput(
                name = input.name,
                description = input.description,
                timeZone = input.timeZone,
                weeklyHours = json.encodeToString(JsonElement.serializer(), input.weeklyHours),
                holidays = json.encodeToString(JsonElement.serializer(), input.holidays),
            )
        )
    }

    @Field
    suspend fun createSlaPolicy(
        authentication: AuthenticationContext,
        name: String,
        description: String?,
    ): SlaPolicy {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return policyService.create(name, description)
    }
}
