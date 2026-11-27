package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.SchemaFormOption
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** WorkOps owns rule identity, immutable scope and exact drafts; shared controls own editing. */
@Serializable
class WorkOpsAutomationModel(
    val scope: String = "GLOBAL",
    val scopeId: String = "",
    var offset: Long = 0,
    val targetId: String = "",
    var rows: List<WorkOpsAutomationRuleRecord> = emptyList(),
    var loaded: Boolean = false,
    var editing: Boolean = false,
    var draft: WorkOpsAutomationRuleRecord = WorkOpsAutomationRuleRecord(),
    var draftRevision: Long = 0,
    var profileName: String = "",
    var deleting: WorkOpsAutomationRuleRecord? = null,
    var resultId: String = "",
    var resultScope: String = scope,
    var resultScopeId: String = scopeId,
    var historyTarget: WorkOpsAutomationRuleRecord? = null,
    var historyOffset: Long = 0,
    var historyRows: List<WorkOpsAutomationLogRecord> = emptyList(),
    var historyLoaded: Boolean = false,
    var historyHasNext: Boolean = false,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    val profileFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val historyFeedback: WorkOpsFeedback = WorkOpsFeedback(),
    val scopeChoices: WorkOpsPermissionsModel = WorkOpsPermissionsModel(kind = scope.lowercase()),
) {
    val scopes get() = listOf("GLOBAL", "PORTFOLIO", "PROGRAM", "PROJECT")
    val failureModes get() = listOf("STOP_ON_ERROR", "CONTINUE")
    val triggerChoices get() = listOf("TaskCreated" to "Task created", "TaskUpdated" to "Task updated", "TaskTransitioned" to "Task transitioned",
        "TaskCommented" to "Task commented", "TaskDeleted" to "Task deleted", "SlaBreached" to "SLA breached", "SlaAtRisk" to "SLA at risk")
        .map { SchemaFormOption(it.first, it.second) }.let { choices ->
            if (draft.triggerType.isNotBlank() && choices.none { it.value == draft.triggerType }) choices + SchemaFormOption(draft.triggerType, draft.triggerType) else choices
        }
    val pageRows get() = rows.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val previousHref get() = if (offset > 0) href((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (rows.size > offset + 25) href(offset + 25) else ""
    val resultHref get() = if (resultId.isBlank()) "" else href(0, resultScope, resultScopeId) + "&item=${workOpsEncode(resultId)}#workops-rule-${workOpsEncode(resultId)}"
    fun label(value: String) = value.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
    private fun href(page: Long, scopeValue: String = scope, id: String = scopeId) = "/workops/settings/automation?scope=${workOpsEncode(scopeValue)}&scopeId=${workOpsEncode(id)}&offset=$page"
    private fun requireScope(value: String, id: String) {
        require(value in scopes) { "Choose a rule scope." }
        require(if (value == "GLOBAL") id.isBlank() else id.isNotBlank()) { if (value == "GLOBAL") "Leave scope ID blank for global rules." else "Enter the ${value.lowercase()} scope ID." }
    }
    suspend fun reload(ctx: RenderContext) {
        loaded = false; requireScope(scope, scopeId)
        rows = ctx.gql.execute(WorkOpsAutomationRules, WorkOpsAutomationRules.Variables(scope, scopeId.takeIf(String::isNotBlank))).workOps.automation.rules.map {
            WorkOpsAutomationRuleRecord(it.id, it.name, it.description.orEmpty(), it.enabled, it.scope, it.scopeId.orEmpty(),
                it.trigger.toString(), it.conditions.toString(), it.actions.toString(), it.failureMode,
                it.maxFiresPerTaskPerHour.toString(), it.executionLogRetentionDays.toString(), it.runAsProfileId, it.version)
        }
        if (targetId.isNotBlank()) rows.indexOfFirst { it.id == targetId }.takeIf { it >= 0 }?.let { offset = it.toLong() / 25 * 25 }
        loaded = true
    }
    /** Refreshes authorized scope choices and rules while retaining the rule editor's draft. */
    suspend fun refresh(ctx: RenderContext) {
        if (scope != "GLOBAL") scopeChoices.retryChoices(ctx)
        retry(ctx)
    }

    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    suspend fun open(id: String, ctx: RenderContext) = feedback.perform {
        require(loaded) { "Reload rules first." }
        draft = if (id.isBlank()) WorkOpsAutomationRuleRecord(scope = scope, scopeId = scopeId)
        else requireNotNull(rows.firstOrNull { it.id == id }) { "Reload this rule." }
        editing = true; draftRevision++; profileName = ""; resultId = ""; reloadProfile(ctx)
    }
    fun cancel() { editing = false }
    private fun capture(name: String, description: String, enabled: Boolean, scope: String, scopeId: String, trigger: String, conditions: String, actions: String, failureMode: String, fires: String, retention: String, runAsId: String) {
        if (draft.runAsId != runAsId) profileName = ""
        draft = draft.copy(name = name, description = description, enabled = enabled,
            scope = if (draft.id.isBlank()) scope else draft.scope, scopeId = if (draft.id.isBlank()) scopeId else draft.scopeId,
            trigger = trigger, conditions = conditions, actions = actions, failureMode = failureMode, fires = fires, retention = retention, runAsId = runAsId)
    }
    private suspend fun reloadProfile(ctx: RenderContext) = profileFeedback.perform {
        profileName = if (draft.runAsId.isBlank()) "" else ctx.gql.execute(WorkOpsTaskProfile, WorkOpsTaskProfile.Variables(draft.runAsId)).profiles.profile?.name ?: "Profile unavailable"
    }
    suspend fun reloadDraft(name: String, description: String, enabled: Boolean, scope: String, scopeId: String, trigger: String, conditions: String, actions: String, failureMode: String, fires: String, retention: String, runAsId: String, ctx: RenderContext) = feedback.perform {
        capture(name, description, enabled, scope, scopeId, trigger, conditions, actions, failureMode, fires, retention, runAsId)
        reloadProfile(ctx); reload(ctx)
    }
    suspend fun chooseTrigger(type: String, name: String, description: String, enabled: Boolean, scope: String, scopeId: String, trigger: String, conditions: String, actions: String, failureMode: String, fires: String, retention: String, runAsId: String, ctx: RenderContext) = feedback.perform {
        capture(name, description, enabled, scope, scopeId, trigger, conditions, actions, failureMode, fires, retention, runAsId)
        reloadProfile(ctx)
        require(type.isNotBlank() && triggerChoices.any { it.value == type }) { "Choose a trigger." }
        val value = runCatching { Json.parseToJsonElement(trigger) as? JsonObject }.getOrNull()
        requireNotNull(value) { "Fix the trigger JSON before changing its type." }
        draft = draft.copy(trigger = JsonObject(value + ("type" to JsonPrimitive(type))).toString()); draftRevision++
    }
    suspend fun save(name: String, description: String, enabled: Boolean, scope: String, scopeId: String, trigger: String, conditions: String, actions: String, failureMode: String, fires: String, retention: String, runAsId: String, ctx: RenderContext) = feedback.perform {
        capture(name, description, enabled, scope, scopeId, trigger, conditions, actions, failureMode, fires, retention, runAsId)
        require(editing) { "Open the rule editor first." }; require(name.isNotBlank()) { "Enter a name." }
        requireScope(draft.scope, draft.scopeId.trim()); require(runAsId.isNotBlank()) { "Choose a run-as profile." }
        require(failureMode in failureModes) { "Choose an action failure mode." }
        val count = requireNotNull(fires.toIntOrNull()?.takeIf { it > 0 }) { "Enter a positive fire limit." }
        val days = requireNotNull(retention.toIntOrNull()?.takeIf { it > 0 }) { "Enter a positive log retention period." }
        fun parsed(text: String, field: String): JsonElement = runCatching { Json.parseToJsonElement(text) }.getOrElse { throw IllegalArgumentException("$field must be valid JSON.", it) }
        val triggerValue = parsed(trigger, "Trigger"); require(triggerValue is JsonObject) { "Trigger must be a JSON object." }
        val conditionsValue = parsed(conditions, "Conditions"); require(conditionsValue is JsonArray) { "Conditions must be a JSON array." }
        val actionsValue = parsed(actions, "Actions"); require(actionsValue is JsonArray) { "Actions must be a JSON array." }
        val input = WorkOpsAutomationRuleInput(scope = draft.scope, scopeId = draft.scopeId.trim().takeIf(String::isNotBlank), name = name.trim(),
            description = description.trim().takeIf(String::isNotBlank), enabled = enabled, trigger = triggerValue, conditions = conditionsValue,
            actions = actionsValue, runAsProfileId = runAsId.trim(), failureMode = failureMode, executionLogRetentionDays = days, maxFiresPerTaskPerHour = count)
        resultId = if (draft.id.isBlank()) ctx.gql.execute(WorkOpsAutomationCreate, WorkOpsAutomationCreate.Variables(input)).workOps.automation.create.id
        else ctx.gql.execute(WorkOpsAutomationUpdate, WorkOpsAutomationUpdate.Variables(draft.id, input, draft.version)).workOps.automation.update.id
        resultScope = draft.scope; resultScopeId = draft.scopeId.trim(); editing = false; reload(ctx); feedback.message = "Automation rule saved."
    }
    suspend fun toggle(id: String, enabled: Boolean, version: Long, ctx: RenderContext) = feedback.perform {
        require(loaded) { "Reload rules first." }
        ctx.gql.execute(WorkOpsAutomationSetEnabled, WorkOpsAutomationSetEnabled.Variables(id, enabled, version))
        reload(ctx); feedback.message = if (enabled) "Rule enabled." else "Rule disabled."
    }
    fun beginDelete(id: String) {
        require(loaded) { "Reload rules first." }; deleting = requireNotNull(rows.firstOrNull { it.id == id }) { "Reload this rule." }
        feedback.message = ""; feedback.failed = false
    }
    fun cancelDelete() { deleting = null }
    suspend fun delete(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(deleting) { "Choose a rule first." }; require(confirmed) { "Confirm deletion." }
        check(ctx.gql.execute(WorkOpsAutomationDelete, WorkOpsAutomationDelete.Variables(target.id)).workOps.automation.delete) { "The rule was not deleted." }
        deleting = null; if (resultId == target.id) resultId = ""; reload(ctx); feedback.message = "Rule deleted."
    }
    suspend fun openHistory(id: String, ctx: RenderContext) {
        require(loaded) { "Reload rules first." }; historyTarget = requireNotNull(rows.firstOrNull { it.id == id }) { "Reload this rule." }
        historyOffset = 0; historyRows = emptyList(); retryHistory(ctx)
    }
    fun closeHistory() { historyTarget = null }
    suspend fun retryHistory(ctx: RenderContext) = historyFeedback.perform {
        historyLoaded = false; val target = requireNotNull(historyTarget) { "Choose a rule first." }
        val values = ctx.gql.execute(WorkOpsAutomationHistory, WorkOpsAutomationHistory.Variables(target.id, historyOffset)).workOps.automation.executionLogs
        historyRows = values.take(50).map { WorkOpsAutomationLogRecord(it.id, it.outcome, it.startedAt, it.finishedAt.orEmpty(), it.durationMs, it.errorMessage.orEmpty(), it.taskId.orEmpty()) }
        historyHasNext = values.size > 50; historyLoaded = true
    }
    suspend fun historyPage(next: Boolean, ctx: RenderContext) { historyOffset = if (next) historyOffset + 50 else (historyOffset - 50).coerceAtLeast(0); retryHistory(ctx) }
}

suspend fun workOpsAutomation(): WorkOpsAutomationModel {
    val ctx = currentRenderContext()
    return WorkOpsAutomationModel(ctx.query["scope"] ?: "GLOBAL", ctx.query["scopeId"].orEmpty(),
        ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0, ctx.query["item"].orEmpty()).also { it.refresh(ctx) }
}
