package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.NavigationItem
import bosca.workops.model.worklog.DurationShorthand
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Task actions run through the owning APIs with the caller's credentials and optimistic-lock token. */
@Serializable
class WorkOpsTaskDetailModel(
    var id: String,
    val tab: String = "comments",
    val activityOffset: Long = 0,
    var data: WorkOpsTaskDetailData? = null,
    var editing: Boolean = false,
    var draft: WorkOpsTaskDraft = WorkOpsTaskDraft(),
    var sprints: List<WorkOpsSprintChoice> = emptyList(),
    var sprintName: String = "",
    var profileNames: Map<String, String> = emptyMap(),
    var pendingTransition: String = "",
    var commentDraft: String = "",
    var commentRevision: Long = 0,
    var loggingWork: Boolean = false,
    var workTime: String = "1h",
    var workStarted: String = "",
    var workComment: String = "",
    var estimateAdjustment: String = "",
    var linking: Boolean = false,
    var linkTarget: String = "",
    var linkType: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    var document: WorkOpsDocumentModel? = null,
    val requirementsOffset: Long = 0,
) {
    val task get() = data?.workOps?.tasks?.task
    val assigneeLabel get() = workOpsAssigneeLabel(task?.assigneeProfileId, task?.assignee?.name)
    private val projectVisible get() = data?.workOps?.projects?.all.orEmpty().any { it.id == task?.project?.id }
    val href get() = "/workops/tasks/${workOpsEncode(id)}"
    val requirementsHref get() = "$href?tab=$tab&offset=$activityOffset"
    private val requirementsQuery get() = if (requirementsOffset > 0) "&requirementsOffset=$requirementsOffset" else ""
    val title get() = task?.let { workOpsContentLabel(it.summary, it.id, "Task") } ?: if (feedback.failed) "Could not load task" else "Task not found"
    val watching get() = data?.workOps?.notifications?.watchers.orEmpty().any { watcher ->
        data?.profiles?.current.orEmpty().any { it.id == watcher.profileId }
    }
    val watcherCount get() = data?.workOps?.notifications?.watchers.orEmpty().size
    val statusTone get() = when (task?.status?.category) {
        WorkOpsStatusCategory.IN_PROGRESS -> "purple"
        WorkOpsStatusCategory.DONE -> "success"
        else -> "neutral"
    }
    val activityTabs get() = listOf("comments" to "Comments", "history" to "History", "worklog" to "Work log")
        .map { (value, label) -> NavigationItem(label, "$href?tab=$value$requirementsQuery", value == tab) }
    val hasNext get() = when (tab) {
        "history" -> task?.history.orEmpty().size > 25
        "worklog" -> data?.workOps?.worklogs?.forTask.orEmpty().size > 25
        else -> task?.comments.orEmpty().size > 25
    }
    val previousHref get() = if (activityOffset > 0) "$href?tab=$tab&offset=${(activityOffset - 25).coerceAtLeast(0)}$requirementsQuery" else ""
    val nextHref get() = if (hasNext) "$href?tab=$tab&offset=${activityOffset + 25}$requirementsQuery" else ""
    val availableProjects get() = data?.workOps?.projects?.all.orEmpty().filter { project ->
        project.id != task?.project?.id && task?.affectedProjects.orEmpty().none { it.id == project.id }
    }

    fun profileName(id: String?) = if (id == null) "System" else profileNames[id] ?: "Profile unavailable"
    fun duration(seconds: Long?) = seconds?.let(DurationShorthand::renderShorthand) ?: "—"

    fun dueDateOverdue(today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Boolean =
        task?.dueDate?.let { LocalDate.parse(it.take(10)).isBefore(today) } ?: false

    fun historyValue(field: String, value: JsonElement?): String {
        if (value == null) return "Unset"
        val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return if (field == "summary" && name != null) workOpsContentLabel(name, id, "Task") else value.toString()
    }

    suspend fun reload(ctx: RenderContext) {
        val loaded = ctx.gql.execute(WorkOpsTaskDetail, WorkOpsTaskDetail.Variables(id, activityOffset))
        data = loaded
        val task = loaded.workOps.tasks.task ?: run { ctx.notFound = true; return }
        val names = profileNames.toMutableMap()
        loaded.profiles.current.orEmpty().forEach { names[it.id] = it.name }
        task.assignee?.let { names[it.id] = it.name }
        task.comments.mapNotNull { it.profile }.forEach { names[it.id] = it.name }
        val ids = listOf(task.reporterProfileId) + task.history.mapNotNull { it.changedByProfileId } + loaded.workOps.worklogs.forTask.map { it.profileId }
        for (profileId in ids.distinct().filterNot(names::containsKey)) {
            ctx.gql.execute(WorkOpsTaskProfile, WorkOpsTaskProfile.Variables(profileId)).profiles.profile?.let { names[it.id] = it.name }
        }
        profileNames = names
        sprintName = task.sprintId?.let { sprintId ->
            if (projectVisible) ctx.gql.execute(WorkOpsTaskSprint, WorkOpsTaskSprint.Variables(sprintId)).workOps.sprints.sprint?.name ?: "Sprint unavailable"
            else "Sprint unavailable"
        }.orEmpty()
        document = task.metadataId?.let { metadataId ->
            (document?.takeIf { it.metadataId == metadataId } ?: WorkOpsDocumentModel(metadataId)).also { it.reload(ctx) }
        }
    }

    suspend fun createDocument(ctx: RenderContext) = feedback.perform {
        val task = requireNotNull(task) { "Reload the task before adding a document." }
        ctx.gql.execute(WorkOpsCreateTaskDocument, WorkOpsCreateTaskDocument.Variables(task.id, task.version))
        reload(ctx)
        feedback.message = "Document added."
    }

    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }

    suspend fun edit(ctx: RenderContext) = feedback.perform {
        val task = requireNotNull(task) { "Reload the task before editing." }
        draft = WorkOpsTaskDraft(workOpsContentLabel(task.summary, task.id, "Task"), task.descriptionMarkdown.orEmpty(), task.priority.id,
            task.assigneeProfileId.orEmpty(), task.sprintId.orEmpty(), task.dueDate?.take(10).orEmpty(),
            task.originalEstimateSeconds?.let(DurationShorthand::renderShorthand).orEmpty(),
            task.remainingEstimateSeconds?.let(DurationShorthand::renderShorthand).orEmpty(), task.version)
        val choices = mutableListOf<WorkOpsSprintChoice>()
        val boards = if (projectVisible) ctx.gql.execute(WorkOpsTaskBoards, WorkOpsTaskBoards.Variables(task.project.id)).workOps.boards.byProject else emptyList()
        for (board in boards) {
            choices += ctx.gql.execute(WorkOpsTaskSprints, WorkOpsTaskSprints.Variables(board.id)).workOps.sprints.byBoard
                .filter { it.state != WorkOpsSprintState.CLOSED }.map { WorkOpsSprintChoice(it.id, it.name) }
        }
        if (task.sprintId != null && choices.none { it.id == task.sprintId }) choices += WorkOpsSprintChoice(task.sprintId, sprintName.ifBlank { "Current sprint" })
        sprints = choices.distinctBy { it.id }
        editing = true
    }

    fun cancelEdit() { editing = false }

    suspend fun save(summary: String, description: String, priorityId: String, assigneeId: String, sprintId: String,
        dueDate: String, originalEstimate: String, remainingEstimate: String, ctx: RenderContext) = feedback.perform {
        draft = WorkOpsTaskDraft(summary, description, priorityId, assigneeId, sprintId, dueDate, originalEstimate, remainingEstimate, draft.expectedVersion)
        require(editing && task != null) { "Open the task editor before saving." }
        require(summary.isNotBlank()) { "Enter a task summary." }
        val current = requireNotNull(task)
        val storedSummary = if (summary.trim() == workOpsContentLabel(current.summary, current.id, "Task")) current.summary else summary.trim()
        if (assigneeId.isNotBlank() && assigneeId != task?.assigneeProfileId && assigneeId !in profileNames) {
            val profile = requireNotNull(ctx.gql.execute(WorkOpsTaskProfile, WorkOpsTaskProfile.Variables(assigneeId)).profiles.profile) { "The selected assignee is unavailable." }
            profileNames = profileNames + (profile.id to profile.name)
        }
        fun estimate(value: String): Long? = if (value.isBlank()) null else if (value.trim() in setOf("0", "0m", "0s")) 0 else DurationShorthand.parseToSeconds(value)
        ctx.gql.execute(WorkOpsUpdateTask, WorkOpsUpdateTask.Variables(id, UpdateWorkOpsTaskInput(
            summary = storedSummary, descriptionMarkdown = description, priorityId = priorityId,
            assigneeProfileId = assigneeId.takeIf(String::isNotBlank), clearAssignee = assigneeId.isBlank(),
            sprintId = sprintId.takeIf(String::isNotBlank), clearSprintId = sprintId.isBlank(),
            dueDate = dueDate.takeIf(String::isNotBlank)?.let { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toString() },
            clearDueDate = dueDate.isBlank(), originalEstimateSeconds = estimate(originalEstimate), clearOriginalEstimate = originalEstimate.isBlank(),
            remainingEstimateSeconds = estimate(remainingEstimate), clearRemainingEstimate = remainingEstimate.isBlank(), expectedVersion = draft.expectedVersion,
        )))
        editing = false
        reload(ctx)
        feedback.message = "Task saved."
    }

    suspend fun beginTransition(transitionId: String, ctx: RenderContext) = feedback.perform {
        val transition = requireNotNull(task?.transitions?.firstOrNull { it.id == transitionId }) { "Reload the available transitions." }
        if (transition.toState.status.category in listOf(WorkOpsStatusCategory.DONE, WorkOpsStatusCategory.CANCELLED)) pendingTransition = transitionId
        else transition(transitionId, "", ctx)
    }

    fun cancelTransition() { pendingTransition = "" }

    suspend fun completeTransition(resolutionId: String, ctx: RenderContext) = feedback.perform {
        require(pendingTransition.isNotBlank()) { "Choose a transition first." }
        require(data?.workOps?.tasks?.resolutions.orEmpty().any { it.id == resolutionId }) { "Choose a resolution." }
        transition(pendingTransition, resolutionId, ctx)
        pendingTransition = ""
    }

    private suspend fun transition(transitionId: String, resolutionId: String, ctx: RenderContext) {
        ctx.gql.execute(WorkOpsTransitionTask, WorkOpsTransitionTask.Variables(id, transitionId, requireNotNull(task).version, resolutionId.takeIf(String::isNotBlank)))
        reload(ctx)
        feedback.message = "Task status updated."
    }

    suspend fun toggleWatch(ctx: RenderContext) = feedback.perform {
        if (watching) ctx.gql.execute(WorkOpsUnwatchTask, WorkOpsUnwatchTask.Variables(id))
        else ctx.gql.execute(WorkOpsWatchTask, WorkOpsWatchTask.Variables(id))
        reload(ctx)
        feedback.message = if (watching) "Now watching this task." else "Stopped watching this task."
    }

    suspend fun comment(content: String, ctx: RenderContext) = feedback.perform {
        commentDraft = content
        require(content.isNotBlank()) { "Enter a comment." }
        ctx.gql.execute(WorkOpsCommentOnTask, WorkOpsCommentOnTask.Variables(id, WorkOpsTaskCommentInput(content = content.trim(), visibility = ProfileVisibility.USER)))
        commentDraft = ""
        commentRevision++
        reload(ctx)
        feedback.message = "Comment added."
    }

    fun beginWork() {
        loggingWork = true
        if (workStarted.isBlank()) workStarted = LocalDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0).toString()
    }
    fun cancelWork() { loggingWork = false }

    suspend fun logWork(time: String, started: String, comment: String, adjustment: String, ctx: RenderContext) = feedback.perform {
        workTime = time; workStarted = started; workComment = comment; estimateAdjustment = adjustment
        require(time.isNotBlank()) { "Enter time spent, such as 1h 30m." }
        ctx.gql.execute(WorkOpsLogTaskWork, WorkOpsLogTaskWork.Variables(id, WorkOpsWorkLogInput(timeSpent = time,
            startedAt = LocalDateTime.parse(started).toInstant(ZoneOffset.UTC).toString(), comment = comment.takeIf(String::isNotBlank), visibility = WorklogVisibility.ALL, estimateAdjustment = adjustment.takeIf(String::isNotBlank))))
        loggingWork = false; workTime = "1h"; workStarted = ""; workComment = ""; estimateAdjustment = ""
        reload(ctx)
        feedback.message = "Work logged."
    }

    suspend fun deleteWork(id: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm work log deletion." }
        require(data?.workOps?.worklogs?.forTask.orEmpty().any { it.id == id }) { "Reload this task's work log." }
        check(ctx.gql.execute(WorkOpsDeleteTaskWork, WorkOpsDeleteTaskWork.Variables(id)).workOps.worklogs.deleteWorklog) {
            "The work log was not deleted. Reload the task before trying again."
        }
        reload(ctx)
        feedback.message = "Work log deleted."
    }

    fun beginLink() { linking = true; if (linkType.isBlank()) linkType = data?.workOps?.links?.linkTypes?.firstOrNull()?.id.orEmpty() }
    fun cancelLink() { linking = false }

    suspend fun link(targetKey: String, typeId: String, ctx: RenderContext) = feedback.perform {
        linkTarget = targetKey; linkType = typeId
        require(targetKey.isNotBlank()) { "Enter a task key." }
        require(data?.workOps?.links?.linkTypes.orEmpty().any { it.id == typeId }) { "Choose a link type." }
        val target = requireNotNull(ctx.gql.execute(WorkOpsTaskIdByKey, WorkOpsTaskIdByKey.Variables(targetKey.trim().uppercase())).workOps.tasks.taskByKey) { "Task not found." }
        ctx.gql.execute(WorkOpsLinkTask, WorkOpsLinkTask.Variables(WorkOpsTaskLinkInput(sourceTaskId = id, targetTaskId = target.id, linkTypeId = typeId)))
        linking = false; linkTarget = ""
        reload(ctx)
        feedback.message = "Task linked."
    }

    suspend fun addProject(projectId: String, ctx: RenderContext) = feedback.perform {
        require(availableProjects.any { it.id == projectId }) { "Choose an available project." }
        ctx.gql.execute(WorkOpsAddTaskProject, WorkOpsAddTaskProject.Variables(id, projectId))
        reload(ctx)
        feedback.message = "Affected project added."
    }
    suspend fun removeProject(projectId: String, ctx: RenderContext) = feedback.perform {
        ctx.gql.execute(WorkOpsRemoveTaskProject, WorkOpsRemoveTaskProject.Variables(id, projectId))
        reload(ctx)
        feedback.message = "Affected project removed."
    }
}

suspend fun workOpsTaskDetail(): WorkOpsTaskDetailModel {
    val ctx = currentRenderContext()
    val raw = ctx.params["id"].orEmpty()
    val tab = ctx.query["tab"].takeIf { it in setOf("comments", "history", "worklog") } ?: "comments"
    return WorkOpsTaskDetailModel(raw, tab, ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        requirementsOffset = ctx.query["requirementsOffset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0).also { model ->
        model.feedback.perform {
            if (!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(raw)) {
                val resolved = ctx.gql.execute(WorkOpsTaskIdByKey, WorkOpsTaskIdByKey.Variables(raw.uppercase())).workOps.tasks.taskByKey
                if (resolved == null) { ctx.notFound = true; return@perform }
                model.id = resolved.id
            }
            model.reload(ctx)
        }
    }
}
