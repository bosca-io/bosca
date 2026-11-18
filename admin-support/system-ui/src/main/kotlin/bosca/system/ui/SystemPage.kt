package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Serializable
class SystemPage(
    val kind: String, val admin: Boolean, val offset: Long = 0, val search: String = "",
    val status: String = "", val source: String = "", var rows: List<SystemRow> = emptyList(),
    var hasNext: Boolean = false, var draft: SystemGroupDraft = SystemGroupDraft(), val feedback: SystemFeedback = SystemFeedback(), var groupType: String = "SYSTEM",
    var history: List<SystemHistoryData.Scheduler.History> = emptyList(), var totalCount: Long = 0,
    var lastAction: String = "", var lastActionId: String = "",
) {
    val historySummary get() = "$totalCount total · ${history.count { it.status == ExecutionStatus.RUNNING }} running · ${history.count { it.status == ExecutionStatus.PENDING }} pending"
    val title: String get() = when (kind) { "groups" -> "Groups"; "scheduler" -> "Scheduler"; "jobs" -> "Jobs"; else -> "Packages" }
    val href: String get() = if (kind == "groups") "/system/security/groups" else "/system/$kind"
    fun pageHref(nextOffset: Long): String = "$href?offset=$nextOffset&search=${URLEncoder.encode(search, StandardCharsets.UTF_8)}&status=$status&source=$source&groupType=$groupType"
    val previousHref: String get() = if (offset > 0) pageHref((offset - 25).coerceAtLeast(0)) else ""
    val nextHref: String get() = if (hasNext) pageHref(offset + 25) else ""
    suspend fun reload(ctx: RenderContext) {
        if (!admin) return
        when (kind) {
            "groups" -> {
                val result = ctx.gql.execute(SystemGroups, SystemGroups.Variables(offset, search, GroupType.valueOf(groupType))).security.groups.find
                hasNext = result.size > 25
                rows = result.take(25).map { SystemRow(it.id, it.name, it.description, it.type.name) }
            }
            "scheduler" -> {
                val result = ctx.gql.execute(SystemSchedules, SystemSchedules.Variables(offset)).scheduler.jobs
                hasNext = result.size > 25
                rows = result.take(25).map { SystemRow(it.id, it.name, it.description.orEmpty(), if (it.enabled) "Enabled" else "Disabled", it.enabled, it.cronExpression, it.lastRunAt, it.nextRunAt) }
            }
            "jobs" -> {
                val result = ctx.gql.execute(SystemHistory, SystemHistory.Variables(offset, status.takeIf(String::isNotBlank)?.let(ExecutionStatus::valueOf), source.takeIf(String::isNotBlank)?.let(JobHistorySource::valueOf))).scheduler
                history = result.history; totalCount = result.historyCount
                hasNext = offset + result.history.size < result.historyCount
                rows = result.history.map { SystemRow(it.id, it.name ?: it.jobId, listOfNotNull(it.source.name, it.triggeredAt, it.completedAt, it.errorMessage, it.delayedUntil?.let { time -> "Delayed: $time" }, it.parentJobId?.let { parent -> "Parent: $parent" }, if (it.wasCatchUp) "Catch-up" else null).joinToString(" · "), it.status.name, it.status in listOf(ExecutionStatus.PENDING, ExecutionStatus.RUNNING)) }
            }
        }
    }
    suspend fun createGroup(name: String, description: String, type: String, ctx: RenderContext) = feedback.perform {
        lastAction = "createGroup"; lastActionId = ""
        require(admin) { "Administrator access required." }
        draft = SystemGroupDraft(name, description, type)
        require(name.isNotBlank()) { "Enter a group name." }
        ctx.gql.execute(SystemAddGroup, SystemAddGroup.Variables(name.trim(), description, GroupType.valueOf(type)))
        groupType = type
        reload(ctx)
        draft = SystemGroupDraft()
        lastAction = ""
        feedback.message = "Group created."
    }
    suspend fun action(id: String, action: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        lastAction = action; lastActionId = id
        require(admin) { "Administrator access required." }
        require(confirmed) { "Confirm the action." }
        when (kind) {
            "groups" -> { require(action == "delete"); ctx.gql.execute(SystemDeleteGroup, SystemDeleteGroup.Variables(id)) }
            "scheduler" -> when (action) {
                "enable" -> ctx.gql.execute(SystemEnableSchedule, SystemEnableSchedule.Variables(id))
                "disable" -> ctx.gql.execute(SystemDisableSchedule, SystemDisableSchedule.Variables(id))
                "trigger" -> ctx.gql.execute(SystemTriggerSchedule, SystemTriggerSchedule.Variables(id))
                "delete" -> ctx.gql.execute(SystemDeleteSchedule, SystemDeleteSchedule.Variables(id))
                else -> error("Unknown schedule action")
            }
            "jobs" -> { require(action == "cancel"); ctx.gql.execute(SystemCancelHistory, SystemCancelHistory.Variables(id)) }
            else -> error("Unknown system action")
        }
        reload(ctx)
        lastAction = ""; lastActionId = ""
        feedback.message = "Action completed."
    }
}

suspend fun systemPage(kind: String): SystemPage {
    val ctx = currentRenderContext()
    val admin = ctx.gql.execute(SystemAccess, Unit).security.principals.current.groups.any { it.name in setOf("administrators", "sa") }
    return SystemPage(kind, admin, ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0, ctx.query["search"].orEmpty(), ctx.query["status"].orEmpty().takeIf { value -> value.isBlank() || ExecutionStatus.entries.any { it.name == value } }.orEmpty(), ctx.query["source"].orEmpty().takeIf { value -> value.isBlank() || JobHistorySource.entries.any { it.name == value } }.orEmpty()).also { it.groupType = ctx.query["groupType"].takeIf { value -> GroupType.entries.any { entry -> entry.name == value } } ?: "SYSTEM"; it.reload(ctx) }
}
