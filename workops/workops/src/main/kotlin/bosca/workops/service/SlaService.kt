package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.task.TaskSlaAtRisk
import bosca.workops.model.task.TaskSlaBreached
import bosca.workops.model.task.dispatch
import bosca.workops.model.sla.SlaGoal
import bosca.workops.model.sla.SlaOutcome
import bosca.workops.model.sla.SlaPolicy
import bosca.workops.model.sla.TaskSlaState
import bosca.workops.model.sla.WorkingCalendar
import bosca.workops.model.task.Task
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.SlaGoalRepository
import bosca.workops.repository.SlaPolicyRepository
import bosca.workops.repository.TaskRepository
import bosca.workops.repository.TaskSlaStateRepository
import bosca.workops.repository.WorkingCalendarRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@ServiceImplementation
class WorkingCalendarServiceImpl(
    private val repository: WorkingCalendarRepository,
) : WorkingCalendarService {
    override suspend fun getById(id: UUID) = repository.getById(id)
    override suspend fun list() = repository.listAll()
    override suspend fun create(input: WorkingCalendarInput): WorkingCalendar {
        runCatching { java.time.ZoneId.of(input.timeZone) }.getOrElse {
            throw bosca.workops.model.WorkOpsValidationException("timeZone", "invalid timezone: ${input.timeZone}")
        }
        return repository.add(
            input.name, input.description, input.timeZone, input.weeklyHours, input.holidays,
        )
    }
}

@ServiceImplementation
class SlaPolicyServiceImpl(
    private val policyRepo: SlaPolicyRepository,
    private val goalRepo: SlaGoalRepository,
) : SlaPolicyService {
    override suspend fun getById(id: UUID) = policyRepo.getById(id)
    override suspend fun list() = policyRepo.listAll()
    override suspend fun create(name: String, description: String?) = policyRepo.add(name, description)
    override suspend fun goalsFor(policyId: UUID) = goalRepo.listForPolicy(policyId)
    override suspend fun addGoal(goal: SlaGoal) = goalRepo.add(goal)
}

/**
 * R13's `WorkingCalendar` arithmetic. `addBusinessMinutes` walks
 * forward from a start instant counting only minutes that fall
 * inside the calendar's working windows; the function is the
 * single dependency the [SlaEvaluator] has on the calendar shape.
 *
 * The implementation is deliberately simple — it iterates day by
 * day in the calendar's local zone. Suitable for typical SLAs
 * (minutes / hours / days). Long horizons (weeks+) work but pay
 * O(days) per call.
 */
object WorkingCalendarMath {

    private val log = LoggerFactory.getLogger(WorkingCalendarMath::class.java)

    fun addBusinessMinutes(
        calendar: WorkingCalendar,
        from: OffsetDateTime,
        minutes: Int,
    ): OffsetDateTime {
        if (minutes <= 0) return from
        val zone = runCatching { ZoneId.of(calendar.timeZone) }.getOrElse { ex ->
            error("Working calendar ${calendar.id} has invalid timezone '${calendar.timeZone}': ${ex.message}")
        }
        val weekly = parseWeekly(calendar)
        val holidays = parseHolidays(calendar)

        // 24x7 calendar — short-circuit to the wall-clock answer.
        if (isContinuous(weekly)) return from.plusMinutes(minutes.toLong())

        var remaining = minutes.toLong()
        var cursor: ZonedDateTime = from.toInstant().atZone(zone)
        // Cap iterations as a safety net.
        repeat(370) {
            if (remaining <= 0L) return cursor.toOffsetDateTime()
            val day = cursor.toLocalDate()
            if (day in holidays) {
                cursor = day.plusDays(1).atStartOfDay(zone)
                return@repeat
            }
            val ranges = weekly[day.dayOfWeek] ?: emptyList()
            for (range in ranges) {
                val rangeStart = day.atTime(range.start).atZone(zone)
                val rangeEnd = day.atTime(range.endExclusiveOrEod()).atZone(zone)
                if (cursor.isBefore(rangeStart)) cursor = rangeStart
                if (cursor.isAfter(rangeEnd) || cursor.isEqual(rangeEnd)) continue
                val available = Duration.between(cursor, rangeEnd).toMinutes()
                if (available >= remaining) {
                    return cursor.plusMinutes(remaining).toOffsetDateTime()
                }
                remaining -= available
                cursor = rangeEnd
            }
            cursor = day.plusDays(1).atStartOfDay(zone)
        }
        return cursor.toOffsetDateTime()
    }

    private fun isContinuous(weekly: Map<DayOfWeek, List<TimeRange>>): Boolean {
        if (weekly.size < 7) return false
        return DayOfWeek.entries.all { day ->
            val ranges = weekly[day] ?: return@all false
            ranges.any { it.start == LocalTime.MIDNIGHT && it.endExclusiveOrEod() == LocalTime.MAX.minusNanos(0) }
                || ranges.any { it.start == LocalTime.MIDNIGHT && it.endRaw == "24:00" }
        }
    }

    private fun parseWeekly(calendar: WorkingCalendar): Map<DayOfWeek, List<TimeRange>> {
        val obj = calendar.weeklyHours as? JsonObject ?: return emptyMap()
        val out = mutableMapOf<DayOfWeek, List<TimeRange>>()
        for ((dayName, value) in obj) {
            val day = runCatching { DayOfWeek.valueOf(dayName.uppercase()) }.getOrElse {
                log.warn("Working calendar {}: unrecognized day name '{}', skipping", calendar.id, dayName)
                null
            } ?: continue
            val ranges = (value as? JsonArray) ?: continue
            out[day] = ranges.mapNotNull { entry ->
                val o = entry as? JsonObject ?: return@mapNotNull null
                val start = o["startLocal"] as? JsonPrimitive ?: return@mapNotNull null
                val end = o["endLocal"] as? JsonPrimitive ?: return@mapNotNull null
                val s = start.content
                val e = end.content
                runCatching { TimeRange(LocalTime.parse(s), e) }.getOrElse { ex ->
                    log.warn("Working calendar {}: invalid time range '{}-{}': {}", calendar.id, s, e, ex.message)
                    null
                }
            }
        }
        return out
    }

    private fun parseHolidays(calendar: WorkingCalendar): Set<LocalDate> {
        val arr = calendar.holidays as? JsonArray ?: return emptySet()
        return arr.mapNotNull { entry ->
            val primitive = entry as? JsonPrimitive ?: return@mapNotNull null
            val text = primitive.content
            runCatching { LocalDate.parse(text) }.getOrElse { ex ->
                log.warn("Working calendar {}: invalid holiday date '{}': {}", calendar.id, text, ex.message)
                null
            }
        }.toSet()
    }

    data class TimeRange(val start: LocalTime, val endRaw: String) {
        fun endExclusiveOrEod(): LocalTime =
            if (endRaw == "24:00") LocalTime.MAX else LocalTime.parse(endRaw)
    }
}

/**
 * Decides start / pause / resume / stop for each goal given a task
 * change. The implementation is intentionally lean for v1 —
 * goal predicates are evaluated by checking the task's status
 * category against the rule's keyword (`open`, `paused`,
 * `done`/`resolved`/`closed`). Phase 8.2 (automation) replaces
 * these literal predicates with a full BQL evaluator.
 */
class SlaEvaluator(
    private val taskSlaStateRepo: TaskSlaStateRepository,
    private val goalRepo: SlaGoalRepository,
    private val calendarService: WorkingCalendarService,
    private val projectRepository: ProjectRepository,
) {

    private val slaLog = LoggerFactory.getLogger(SlaEvaluator::class.java)

    enum class Edge { START, PAUSE, RESUME, STOP_MET }

    /**
     * Re-evaluate every goal for [task] given the latest snapshot.
     * Returns the edges applied so callers can audit / react.
     */
    suspend fun evaluate(task: Task, statusCategory: StatusCategory, policyId: UUID): List<Edge> {
        val goals = goalRepo.listForPolicy(policyId)
        val applied = mutableListOf<Edge>()
        for (goal in goals) {
            val state = taskSlaStateRepo.get(task.id, goal.id)
            val edge = decide(goal, state, statusCategory) ?: continue
            apply(task, goal, state, edge)
            applied.add(edge)
        }
        return applied
    }

    private fun decide(
        goal: SlaGoal,
        state: TaskSlaState?,
        category: StatusCategory,
    ): Edge? {
        val open = state != null && state.outcome == SlaOutcome.OPEN
        return when {
            state == null && matches(goal.startConditions, category) -> Edge.START
            open && goal.pauseConditions != null && matches(goal.pauseConditions!!, category) && state.pausedAt == null -> Edge.PAUSE
            open && goal.pauseConditions != null && !matches(goal.pauseConditions!!, category) && state.pausedAt != null -> Edge.RESUME
            open && matches(goal.stopConditions, category) -> Edge.STOP_MET
            else -> null
        }
    }

    private fun matches(predicate: String, category: StatusCategory): Boolean {
        // Tiny rule language for v1: `open`, `paused`, `done`,
        // `resolved`, or `closed` literals matched against the
        // workflow status category. AND/OR are reserved for the
        // BQL upgrade in Phase 8.2.
        val lowered = predicate.lowercase().trim()
        return when (category) {
            StatusCategory.TODO, StatusCategory.IN_PROGRESS -> lowered == "open"
            StatusCategory.DONE -> lowered in setOf("done", "resolved", "closed")
            StatusCategory.CANCELLED -> lowered == "closed"
        }
    }

    private suspend fun apply(task: Task, goal: SlaGoal, state: TaskSlaState?, edge: Edge) {
        val now = OffsetDateTime.now()
        when (edge) {
            Edge.START -> {
                val cal = goal.calendarId?.let { calendarService.getById(it) }
                    ?: calendarService.getById(UUID.parse("c0000000-0000-0000-0000-000000000001"))
                if (cal == null) {
                    slaLog.warn("SLA goal {} for task {}: no working calendar found (goalCalendarId={}, fallback missing), skipping", goal.id, task.id, goal.calendarId)
                    return
                }
                val due = WorkingCalendarMath.addBusinessMinutes(cal, now, goal.targetMinutes)
                taskSlaStateRepo.start(task.id, goal.id, now, due)
            }
            Edge.PAUSE -> taskSlaStateRepo.pause(task.id, goal.id)
            Edge.RESUME -> taskSlaStateRepo.resume(task.id, goal.id)
            Edge.STOP_MET -> taskSlaStateRepo.stop(task.id, goal.id, SlaOutcome.MET.name)
        }
    }
}

/**
 * Status-category enum the evaluator consults. The status table
 * stores the same set as a varchar; this projection keeps the
 * evaluator independent from the raw SQL.
 */
enum class StatusCategory { TODO, IN_PROGRESS, DONE, CANCELLED }

@ServiceImplementation
class SlaTickServiceImpl(
    private val stateRepo: TaskSlaStateRepository,
    private val goalRepo: SlaGoalRepository,
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val json: Json,
) : SlaTickService {

    private val log = LoggerFactory.getLogger(SlaTickServiceImpl::class.java)

    override suspend fun tick(now: OffsetDateTime, atRiskWindowMinutes: Int, limit: Int): Int {
        val candidates = stateRepo.pendingBoundaries(now, atRiskWindowMinutes, limit)
        var emitted = 0
        for (state in candidates) {
            val task = taskRepository.getActiveById(state.taskId) ?: run {
                log.warn("SLA tick: task {} not found for SLA state (goalId={}), skipping evaluation — possible orphaned SLA state row", state.taskId, state.goalId)
                continue
            }
            val project = projectRepository.getById(task.projectId) ?: run {
                log.warn("SLA tick: project {} not found for task {} (goalId={}), skipping evaluation — possible orphaned SLA state row", task.projectId, task.id, state.goalId)
                continue
            }
            // Breach first: if the deadline has passed and we
            // haven't emitted, fire the breach + flip outcome.
            if (!state.breachEmitted && !state.dueAt.isAfter(now)) {
                stateRepo.markBreachEmitted(state.taskId, state.goalId)
                TaskSlaBreached(taskId = task.id, projectId = project.id, goalId = state.goalId).dispatch()
                emitted++
                continue
            }
            // At-risk: emit if we're inside `atRiskAtPercent` of
            // the goal's target minutes.
            val goal = goalRepo.getById(state.goalId) ?: run {
                log.warn("SLA tick: goal {} not found for task {} (taskId={}), skipping at-risk evaluation — possible orphaned SLA state row", state.goalId, task.key, state.taskId)
                continue
            }
            val totalMinutes = goal.targetMinutes.toLong()
            val elapsed = Duration.between(state.startedAt.toInstant(), now.toInstant()).toMinutes()
            val threshold = totalMinutes * goal.atRiskAtPercent / 100
            if (!state.atRiskEmitted && elapsed >= threshold) {
                stateRepo.markAtRiskEmitted(state.taskId, state.goalId)
                TaskSlaAtRisk(taskId = task.id, projectId = project.id, goalId = state.goalId).dispatch()
                emitted++
            }
        }
        return emitted
    }
}
