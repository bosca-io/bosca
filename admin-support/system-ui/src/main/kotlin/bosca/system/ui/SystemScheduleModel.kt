package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
class SystemScheduleModel(
    val id: String, val admin: Boolean, var name: String = "", var description: String = "",
    var jobName: String = "", var parameters: String = "{}", var cron: String = "",
    var enabled: Boolean = true, var concurrent: Boolean = false, var catchUp: Boolean = false,
    var maxCatchUp: String = "", var principalState: String = "", var principalId: String = "",
    var definitions: List<SystemDefinitionsData.Scheduler.AvailableJobDefinitions> = emptyList(),
    val feedback: SystemFeedback = SystemFeedback(),
    var cronCheck: SystemCronData.Scheduler.ValidateCronExpression? = null,
    var lastRunAt: String? = null, var nextRunAt: String? = null,
    var createdBy: String = "", var assignedBy: String? = null, var confirmedBy: String? = null,
    var history: List<SystemScheduleHistoryData.Scheduler.Job.History> = emptyList(),
    var historyCount: Long = 0, var historyOffset: Long = 0,
    var historyStatus: String = "", var historySource: String = "",
    var principalDraftId: String? = null, var clearPrincipalDraft: Boolean = false,
    var formRevision: Long = 0, var failedAction: String = "",
) {
    val selectedDefinition get() = definitions.firstOrNull { it.name == jobName }

    suspend fun reload(ctx: RenderContext) {
        if (!admin) return
        definitions = ctx.gql.execute(SystemDefinitions, Unit).scheduler.availableJobDefinitions
        if (id == "new") return
        val job = ctx.gql.execute(SystemSchedule, SystemSchedule.Variables(id)).scheduler.job
        if (job == null) { ctx.notFound = true; return }
        name = job.name; description = job.description.orEmpty(); jobName = job.jobName
        parameters = job.jobParameters.toString(); cron = job.cronExpression.orEmpty()
        enabled = job.enabled; concurrent = job.allowConcurrent; catchUp = job.catchUp; maxCatchUp = job.maxCatchUp.toString()
        principalState = job.principalState.name; principalId = job.executionPrincipalId.orEmpty()
        principalDraftId = null; clearPrincipalDraft = false
        createdBy = job.createdBy; assignedBy = job.principalAssignedBy; confirmedBy = job.principalConfirmedBy
        lastRunAt = job.lastRunAt; nextRunAt = job.nextRunAt
        reloadHistory(ctx)
    }

    private suspend fun reloadHistory(ctx: RenderContext) {
        val job = ctx.gql.execute(SystemScheduleHistory, SystemScheduleHistory.Variables(id, historyOffset,
            historyStatus.takeIf(String::isNotBlank)?.let(ExecutionStatus::valueOf),
            historySource.takeIf(String::isNotBlank)?.let(JobHistorySource::valueOf))).scheduler.job
        history = job?.history.orEmpty(); historyCount = job?.historyCount ?: 0
    }

    suspend fun loadHistory(status: String, source: String, offset: Long, ctx: RenderContext) = feedback.perform {
        failedAction = ""
        require(admin && id != "new") { "Administrator access required." }
        historyStatus = status; historySource = source; historyOffset = offset.coerceAtLeast(0)
        reloadHistory(ctx)
    }

    private fun retain(name: String, description: String, jobName: String, parameters: String, cron: String, enabled: Boolean, concurrent: Boolean, catchUp: Boolean, maximum: String) {
        if (this.cron != cron) cronCheck = null
        this.name = name; this.description = description; this.jobName = jobName; this.parameters = parameters; this.cron = cron
        this.enabled = enabled; this.concurrent = concurrent; this.catchUp = catchUp; maxCatchUp = maximum
    }

    suspend fun inspect(name: String, description: String, jobName: String, parameters: String, cron: String, enabled: Boolean, concurrent: Boolean, catchUp: Boolean, maximum: String, preview: Boolean, ctx: RenderContext) = feedback.perform {
        failedAction = ""
        require(admin) { "Administrator access required." }
        retain(name, description, jobName, parameters, cron, enabled, concurrent, catchUp, maximum)
        formRevision++
        if (preview) {
            require(cron.isNotBlank()) { "Cron expression is empty." }
            cronCheck = ctx.gql.execute(SystemCron, SystemCron.Variables(cron.trim())).scheduler.validateCronExpression
        }
    }

    suspend fun save(name: String, description: String, jobName: String, parameters: String, cron: String, enabled: Boolean, concurrent: Boolean, catchUp: Boolean, maxCatchUp: String, ctx: RenderContext) = feedback.perform {
        failedAction = ""
        require(admin) { "Administrator access required." }
        retain(name, description, jobName, parameters, cron, enabled, concurrent, catchUp, maxCatchUp)
        require(name.isNotBlank() && definitions.any { it.name == jobName }) { "Enter a name and choose a registered job definition." }
        val maximum = if (maxCatchUp.isBlank()) null else maxCatchUp.toIntOrNull()?.also { require(it > 0) { "Maximum catch-up must be positive." } } ?: error("Maximum catch-up must be a whole number.")
        if (cron.isNotBlank()) {
            val validation = ctx.gql.execute(SystemCron, SystemCron.Variables(cron.trim())).scheduler.validateCronExpression
            cronCheck = validation
            require(validation.valid) { validation.error ?: "Invalid cron expression." }
        }
        val input = ScheduledJobInput(name = name.trim(), description = description.trim().takeIf(String::isNotBlank), jobName = jobName,
            jobParameters = Json.parseToJsonElement(parameters.ifBlank { "{}" }), cronExpression = cron.trim().takeIf(String::isNotBlank),
            enabled = enabled, allowConcurrent = concurrent, catchUp = catchUp, maxCatchUp = maximum)
        if (id == "new") {
            val created = ctx.gql.execute(SystemCreateSchedule, SystemCreateSchedule.Variables(input)).scheduler.create
            feedback.nextHref = "/system/scheduler/${created.id}"; feedback.message = "Scheduled job created."
        } else {
            ctx.gql.execute(SystemEditSchedule, SystemEditSchedule.Variables(id, input))
            reload(ctx); feedback.message = "Scheduled job saved."
        }
        formRevision++
    }

    suspend fun assign(principalId: String, clear: Boolean, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        failedAction = ""; principalDraftId = principalId; clearPrincipalDraft = clear
        require(admin && confirmed) { "Confirm the execution principal change." }
        if (clear) ctx.gql.execute(SystemClearSchedulePrincipal, SystemClearSchedulePrincipal.Variables(id))
        else ctx.gql.execute(SystemAssignSchedule, SystemAssignSchedule.Variables(id, principalId))
        reload(ctx); feedback.message = "Execution principal updated."
    }

    suspend fun action(action: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        failedAction = action
        require(admin && id != "new" && confirmed) { "Confirm the scheduled job action." }
        when (action) {
            "trigger" -> ctx.gql.execute(SystemTriggerSchedule, SystemTriggerSchedule.Variables(id))
            "delete" -> ctx.gql.execute(SystemDeleteSchedule, SystemDeleteSchedule.Variables(id))
            "confirmPrincipal" -> ctx.gql.execute(SystemConfirmSchedulePrincipal, SystemConfirmSchedulePrincipal.Variables(id))
            else -> error("Unknown scheduled job action.")
        }
        if (action == "delete") feedback.nextHref = "/system/scheduler" else reload(ctx)
        failedAction = ""
        feedback.message = if (action == "trigger") "Scheduled job triggered." else "Scheduled job updated."
    }

    suspend fun cancel(historyId: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        failedAction = "cancel:$historyId"
        require(admin && confirmed) { "Confirm execution cancellation." }
        ctx.gql.execute(SystemCancelHistory, SystemCancelHistory.Variables(historyId))
        reloadHistory(ctx); failedAction = ""; feedback.message = "Execution cancelled."
    }
}

suspend fun systemSchedule(): SystemScheduleModel {
    val ctx = currentRenderContext()
    return SystemScheduleModel(ctx.params["id"].orEmpty(), systemAdministrator(ctx)).also { model -> model.feedback.perform { model.reload(ctx) } }
}
