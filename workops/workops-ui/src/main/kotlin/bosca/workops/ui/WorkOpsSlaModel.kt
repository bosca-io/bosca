package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.NavigationItem
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
class WorkOpsSlaModel(
    val kind: String = "policies", var offset: Long = 0, val targetId: String = "",
    var policies: List<WorkOpsSlaPolicyRecord> = emptyList(), var calendars: List<WorkOpsWorkingCalendarRecord> = emptyList(),
    var loaded: Boolean = false, var editing: Boolean = false, var draft: WorkOpsSlaDraft = WorkOpsSlaDraft(), var draftRevision: Long = 0,
    var policyDetail: WorkOpsSlaPolicyRecord? = null, var calendarDetail: WorkOpsWorkingCalendarRecord? = null,
    var resultId: String = "", val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val showingCalendars get() = kind == "calendars"
    val title get() = if (showingCalendars) "Working calendars" else "SLA policies"
    val entityLabel get() = if (showingCalendars) "working calendar" else "SLA policy"
    val tabs get() = listOf(NavigationItem("SLA policies", href("policies", 0), !showingCalendars), NavigationItem("Working calendars", href("calendars", 0), showingCalendars))
    private val count get() = if (showingCalendars) calendars.size else policies.size
    private val pageOffset get() = offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val pagePolicies get() = policies.drop(pageOffset).take(25)
    val pageCalendars get() = calendars.drop(pageOffset).take(25)
    val previousHref get() = if (offset > 0) href(kind, (offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (count > offset + 25) href(kind, offset + 25) else ""
    val resultHref get() = if (resultId.isBlank()) "" else href(kind, 0) + "&item=${workOpsEncode(resultId)}#workops-sla-${workOpsEncode(resultId)}"
    private fun href(section: String, page: Long) = "/workops/settings/sla?tab=$section&offset=$page"
    suspend fun reload(ctx: RenderContext) {
        loaded = false
        val data = ctx.gql.execute(WorkOpsSlaCatalog, Unit).workOps.sla
        policies = data.slaPolicies.map { policy -> WorkOpsSlaPolicyRecord(policy.id, policy.name, policy.description.orEmpty(), policy.goals.map { WorkOpsSlaGoalRecord(it.id, it.name, it.targetMinutes, it.atRiskAtPercent, it.startConditions, it.stopConditions, it.pauseConditions.orEmpty(), it.displayOrder) }) }
        calendars = data.workingCalendars.map { WorkOpsWorkingCalendarRecord(it.id, it.name, it.description.orEmpty(), it.timeZone, it.weeklyHours, it.holidays) }
        if (targetId.isNotBlank()) (if (showingCalendars) calendars.map { it.id } else policies.map { it.id }).indexOf(targetId).takeIf { it >= 0 }?.let { offset = it.toLong() / 25 * 25 }
        loaded = true
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun open() {
        require(loaded) { "Reload SLA settings first." }
        draft = WorkOpsSlaDraft(); draftRevision++; editing = true; policyDetail = null; calendarDetail = null; resultId = ""; feedback.message = ""; feedback.failed = false
    }
    fun cancel() { editing = false }
    fun view(id: String) {
        require(loaded) { "Reload SLA settings first." }
        if (showingCalendars) calendarDetail = requireNotNull(calendars.firstOrNull { it.id == id }) { "Reload this calendar." }
        else policyDetail = requireNotNull(policies.firstOrNull { it.id == id }) { "Reload this policy." }
    }
    fun closeDetail() { policyDetail = null; calendarDetail = null }
    private fun capture(name: String, description: String, timeZone: String, weeklyHours: String, holidays: String) {
        draft = WorkOpsSlaDraft(name, description, timeZone, weeklyHours, holidays)
    }
    suspend fun reloadDraft(name: String, description: String, timeZone: String, weeklyHours: String, holidays: String, ctx: RenderContext) = feedback.perform {
        capture(name, description, timeZone, weeklyHours, holidays); reload(ctx)
    }
    suspend fun save(name: String, description: String, timeZone: String, weeklyHours: String, holidays: String, ctx: RenderContext) = feedback.perform {
        capture(name, description, timeZone, weeklyHours, holidays)
        require(editing && loaded) { "Reload SLA settings before saving." }
        require(name.isNotBlank()) { "Enter a name." }
        val notes = description.trim().takeIf(String::isNotBlank)
        resultId = if (showingCalendars) {
            require(timeZone.isNotBlank()) { "Enter a time zone." }
            val hours = Json.parseToJsonElement(weeklyHours)
            val dates = Json.parseToJsonElement(holidays)
            require(hours is JsonObject) { "Weekly hours must be a JSON object." }
            require(dates is JsonArray) { "Holidays must be a JSON array." }
            ctx.gql.execute(WorkOpsSlaCreateCalendar, WorkOpsSlaCreateCalendar.Variables(WorkOpsWorkingCalendarInput(name.trim(), notes, timeZone.trim(), hours, dates))).workOps.sla.createWorkingCalendar.id
        } else ctx.gql.execute(WorkOpsSlaCreatePolicy, WorkOpsSlaCreatePolicy.Variables(name.trim(), notes)).workOps.sla.createSlaPolicy.id
        editing = false; reload(ctx); feedback.message = "${entityLabel.replaceFirstChar(Char::uppercase)} created."
    }
}

suspend fun workOpsSla(): WorkOpsSlaModel {
    val ctx = currentRenderContext()
    return WorkOpsSlaModel(if (ctx.query["tab"] == "calendars") "calendars" else "policies", ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0, ctx.query["item"].orEmpty()).also { it.retry(ctx) }
}
