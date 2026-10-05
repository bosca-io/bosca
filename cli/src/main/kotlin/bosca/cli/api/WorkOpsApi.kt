package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.*
import kotlin.uuid.Uuid

/**
 * Unified client for all workops GraphQL operations, used by both
 * the CLI commands and the MCP server tool handlers.
 */
class WorkOpsApi(network: NetworkClient) : Api(network) {

    private val gql = network.boscaGraphql

    // ── Portfolios ──────────────────────────────────────────────

    suspend fun listPortfolios(offset: Long = 0, limit: Int = 50): List<IWorkOpsPortfolioFragment> =
        gql.execute(GetWorkOpsPortfolios, GetWorkOpsPortfolios.Variables(offset, limit)).workOps.portfolios.all

    suspend fun getPortfolio(id: Uuid): IWorkOpsPortfolioFragment? =
        gql.execute(GetWorkOpsPortfolio, GetWorkOpsPortfolio.Variables(id)).workOps.portfolios.portfolio

    suspend fun getPortfolioByKey(key: String): IWorkOpsPortfolioFragment? =
        gql.execute(GetWorkOpsPortfolioByKey, GetWorkOpsPortfolioByKey.Variables(key)).workOps.portfolios.portfolioByKey

    suspend fun createPortfolio(input: WorkOpsPortfolioInput): IWorkOpsPortfolioFragment =
        gql.execute(CreateWorkOpsPortfolio, CreateWorkOpsPortfolio.Variables(input)).workOps.portfolios.create

    suspend fun updatePortfolio(id: Uuid, input: WorkOpsPortfolioInput, expectedVersion: Long): IWorkOpsPortfolioFragment =
        gql.execute(UpdateWorkOpsPortfolio, UpdateWorkOpsPortfolio.Variables(id, input, expectedVersion)).workOps.portfolios.update

    suspend fun archivePortfolio(id: Uuid, expectedVersion: Long): IWorkOpsPortfolioFragment =
        gql.execute(ArchiveWorkOpsPortfolio, ArchiveWorkOpsPortfolio.Variables(id, expectedVersion)).workOps.portfolios.archive

    // ── Programs ────────────────────────────────────────────────

    suspend fun listPrograms(): List<IWorkOpsProgramFragment> =
        gql.execute(GetWorkOpsPrograms, Unit).workOps.programs.all

    suspend fun getProgram(id: Uuid): IWorkOpsProgramFragment? =
        gql.execute(GetWorkOpsProgram, GetWorkOpsProgram.Variables(id)).workOps.programs.program

    suspend fun getProgramsByPortfolio(portfolioId: Uuid, offset: Long = 0, limit: Int = 50): List<IWorkOpsProgramFragment> =
        gql.execute(GetWorkOpsProgramsByPortfolio, GetWorkOpsProgramsByPortfolio.Variables(portfolioId, offset, limit)).workOps.programs.byPortfolio

    suspend fun createProgram(input: WorkOpsProgramInput): IWorkOpsProgramFragment =
        gql.execute(CreateWorkOpsProgram, CreateWorkOpsProgram.Variables(input)).workOps.programs.create

    suspend fun updateProgram(id: Uuid, input: WorkOpsProgramInput, expectedVersion: Long): IWorkOpsProgramFragment =
        gql.execute(UpdateWorkOpsProgram, UpdateWorkOpsProgram.Variables(id, input, expectedVersion)).workOps.programs.update

    suspend fun archiveProgram(id: Uuid, expectedVersion: Long): IWorkOpsProgramFragment =
        gql.execute(ArchiveWorkOpsProgram, ArchiveWorkOpsProgram.Variables(id, expectedVersion)).workOps.programs.archive

    // ── Projects ────────────────────────────────────────────────

    suspend fun listProjects(): List<IWorkOpsProjectFragment> =
        gql.execute(GetWorkOpsProjects, Unit).workOps.projects.all

    suspend fun getProject(id: Uuid): IWorkOpsProjectFragment? =
        gql.execute(GetWorkOpsProject, GetWorkOpsProject.Variables(id)).workOps.projects.project

    suspend fun getProjectByKey(key: String): IWorkOpsProjectFragment? =
        gql.execute(GetWorkOpsProjectByKey, GetWorkOpsProjectByKey.Variables(key)).workOps.projects.projectByKey

    suspend fun getProjectsByProgram(programId: Uuid, offset: Long = 0, limit: Int = 50): List<IWorkOpsProjectFragment> =
        gql.execute(GetWorkOpsProjectsByProgram, GetWorkOpsProjectsByProgram.Variables(programId, offset, limit)).workOps.projects.byProgram

    suspend fun createProject(input: WorkOpsProjectInput): IWorkOpsProjectFragment =
        gql.execute(CreateWorkOpsProject, CreateWorkOpsProject.Variables(input)).workOps.projects.create

    suspend fun updateProject(id: Uuid, input: WorkOpsProjectInput, expectedVersion: Long): IWorkOpsProjectFragment =
        gql.execute(UpdateWorkOpsProject, UpdateWorkOpsProject.Variables(id, input, expectedVersion)).workOps.projects.update

    suspend fun archiveProject(id: Uuid, expectedVersion: Long): IWorkOpsProjectFragment =
        gql.execute(ArchiveWorkOpsProject, ArchiveWorkOpsProject.Variables(id, expectedVersion)).workOps.projects.archive

    // ── Tasks ───────────────────────────────────────────────────

    suspend fun getTask(id: Uuid): IWorkOpsTaskFragment? =
        gql.execute(GetWorkOpsTask, GetWorkOpsTask.Variables(id)).workOps.tasks.task

    suspend fun getTaskByKey(key: String): IWorkOpsTaskFragment? =
        gql.execute(GetWorkOpsTaskByKey, GetWorkOpsTaskByKey.Variables(key)).workOps.tasks.taskByKey

    suspend fun getTasksByProject(projectId: Uuid, offset: Long = 0, limit: Int = 50): List<IWorkOpsTaskSummaryFragment> =
        gql.execute(GetWorkOpsTasksByProject, GetWorkOpsTasksByProject.Variables(projectId, offset, limit)).workOps.tasks.byProject

    suspend fun createTask(input: CreateWorkOpsTaskInput): IWorkOpsTaskFragment =
        gql.execute(CreateWorkOpsTask, CreateWorkOpsTask.Variables(input)).workOps.tasks.create

    suspend fun updateTask(id: Uuid, input: UpdateWorkOpsTaskInput): IWorkOpsTaskFragment =
        gql.execute(UpdateWorkOpsTask, UpdateWorkOpsTask.Variables(id, input)).workOps.tasks.update

    suspend fun transitionTask(id: Uuid, transitionId: Uuid, expectedVersion: Long, resolutionId: Uuid? = null, comment: String? = null): IWorkOpsTaskFragment =
        gql.execute(TransitionWorkOpsTask, TransitionWorkOpsTask.Variables(id, transitionId, expectedVersion, resolutionId, comment)).workOps.tasks.transition

    suspend fun softDeleteTask(id: Uuid, expectedVersion: Long): IWorkOpsTaskFragment =
        gql.execute(SoftDeleteWorkOpsTask, SoftDeleteWorkOpsTask.Variables(id, expectedVersion)).workOps.tasks.softDelete

    suspend fun restoreTask(id: Uuid, expectedVersion: Long): IWorkOpsTaskFragment =
        gql.execute(RestoreWorkOpsTask, RestoreWorkOpsTask.Variables(id, expectedVersion)).workOps.tasks.restore

    suspend fun addTaskAffectedProject(taskId: Uuid, projectId: Uuid): Boolean =
        gql.execute(AddWorkOpsTaskAffectedProject, AddWorkOpsTaskAffectedProject.Variables(taskId, projectId)).workOps.tasks.addAffectedProject

    suspend fun removeTaskAffectedProject(taskId: Uuid, projectId: Uuid): Boolean =
        gql.execute(RemoveWorkOpsTaskAffectedProject, RemoveWorkOpsTaskAffectedProject.Variables(taskId, projectId)).workOps.tasks.removeAffectedProject

    suspend fun getTasksByAffectedProject(projectId: Uuid, offset: Long = 0, limit: Int = 50): List<IWorkOpsTaskSummaryFragment> =
        gql.execute(GetWorkOpsTasksByAffectedProject, GetWorkOpsTasksByAffectedProject.Variables(projectId, offset, limit)).workOps.tasks.byAffectedProject

    suspend fun getTaskTransitions(taskId: Uuid, currentOnly: Boolean = true): GetWorkOpsTaskTransitionsData.WorkOps.Tasks.Task? =
        gql.execute(GetWorkOpsTaskTransitions, GetWorkOpsTaskTransitions.Variables(taskId, currentOnly)).workOps.tasks.task

    suspend fun getTaskHistory(taskId: Uuid, offset: Long = 0, limit: Int = 50): List<GetWorkOpsTaskHistoryData.WorkOps.Tasks.Task.History> =
        gql.execute(GetWorkOpsTaskHistory, GetWorkOpsTaskHistory.Variables(taskId, offset, limit)).workOps.tasks.task?.history ?: emptyList()

    // ── Reference Data ──────────────────────────────────────────

    suspend fun getTaskTypes(): List<GetWorkOpsTaskTypesData.WorkOps.Tasks.TaskTypes> =
        gql.execute(GetWorkOpsTaskTypes, Unit).workOps.tasks.taskTypes

    suspend fun getPriorities(): List<GetWorkOpsPrioritiesData.WorkOps.Tasks.Priorities> =
        gql.execute(GetWorkOpsPriorities, Unit).workOps.tasks.priorities

    suspend fun getResolutions(): List<GetWorkOpsResolutionsData.WorkOps.Tasks.Resolutions> =
        gql.execute(GetWorkOpsResolutions, Unit).workOps.tasks.resolutions

    suspend fun getStatuses(): List<GetWorkOpsStatusesData.WorkOps.Statuses.All> =
        gql.execute(GetWorkOpsStatuses, Unit).workOps.statuses.all

    // ── Boards ──────────────────────────────────────────────────

    suspend fun getBoard(id: Uuid): IWorkOpsBoardFragment? =
        gql.execute(GetWorkOpsBoard, GetWorkOpsBoard.Variables(id)).workOps.boards.board

    suspend fun getBoardsByProject(projectId: Uuid): List<IWorkOpsBoardFragment> =
        gql.execute(GetWorkOpsBoardsByProject, GetWorkOpsBoardsByProject.Variables(projectId)).workOps.boards.byProject

    suspend fun createBoard(input: CreateWorkOpsBoardInput): IWorkOpsBoardFragment =
        gql.execute(CreateWorkOpsBoard, CreateWorkOpsBoard.Variables(input)).workOps.boards.create

    suspend fun deleteBoard(boardId: Uuid): Boolean =
        gql.execute(DeleteWorkOpsBoard, DeleteWorkOpsBoard.Variables(boardId)).workOps.boards.delete

    // ── Sprints ─────────────────────────────────────────────────

    suspend fun getSprint(id: Uuid): IWorkOpsSprintFragment? =
        gql.execute(GetWorkOpsSprint, GetWorkOpsSprint.Variables(id)).workOps.sprints.sprint

    suspend fun getSprintsByBoard(boardId: Uuid, offset: Long = 0, limit: Int = 50): List<IWorkOpsSprintFragment> =
        gql.execute(GetWorkOpsSprintsByBoard, GetWorkOpsSprintsByBoard.Variables(boardId, offset, limit)).workOps.sprints.byBoard

    suspend fun createSprint(input: CreateWorkOpsSprintInput): IWorkOpsSprintFragment =
        gql.execute(CreateWorkOpsSprint, CreateWorkOpsSprint.Variables(input)).workOps.sprints.create

    suspend fun startSprint(input: StartWorkOpsSprintInput): IWorkOpsSprintFragment =
        gql.execute(StartWorkOpsSprint, StartWorkOpsSprint.Variables(input)).workOps.sprints.start

    suspend fun closeSprint(input: CloseWorkOpsSprintInput): IWorkOpsSprintFragment =
        gql.execute(CloseWorkOpsSprint, CloseWorkOpsSprint.Variables(input)).workOps.sprints.close

    // ── Worklogs ────────────────────────────────────────────────

    suspend fun getWorklogs(taskId: Uuid, offset: Long = 0, limit: Int = 50): List<IWorkOpsWorkLogFragment> =
        gql.execute(GetWorkOpsWorklogs, GetWorkOpsWorklogs.Variables(taskId, offset, limit)).workOps.worklogs.forTask

    suspend fun logWork(taskId: Uuid, input: WorkOpsWorkLogInput): IWorkOpsWorkLogFragment =
        gql.execute(LogWorkOpsWork, LogWorkOpsWork.Variables(taskId, input)).workOps.worklogs.logWork

    suspend fun deleteWorklog(id: Uuid): Boolean =
        gql.execute(DeleteWorkOpsWorklog, DeleteWorkOpsWorklog.Variables(id)).workOps.worklogs.deleteWorklog

    // ── Comments ────────────────────────────────────────────────

    suspend fun getTaskComments(taskId: Uuid, offset: Long = 0, limit: Long = 50): List<GetWorkOpsTaskCommentsData.WorkOps.TaskComments.ForTask> =
        gql.execute(GetWorkOpsTaskComments, GetWorkOpsTaskComments.Variables(taskId, offset, limit)).workOps.taskComments.forTask

    suspend fun addTaskComment(taskId: Uuid, input: WorkOpsTaskCommentInput): AddWorkOpsTaskCommentData.WorkOps.TaskComments.Add =
        gql.execute(AddWorkOpsTaskComment, AddWorkOpsTaskComment.Variables(taskId, input)).workOps.taskComments.add

    suspend fun deleteTaskComment(taskId: Uuid, commentId: Long): Boolean =
        gql.execute(DeleteWorkOpsTaskComment, DeleteWorkOpsTaskComment.Variables(taskId, commentId)).workOps.taskComments.delete

    // ── Links ───────────────────────────────────────────────────

    suspend fun getTaskLinks(taskId: Uuid): List<GetWorkOpsTaskLinksData.WorkOps.Links.ForTask> =
        gql.execute(GetWorkOpsTaskLinks, GetWorkOpsTaskLinks.Variables(taskId)).workOps.links.forTask

    suspend fun getLinkTypes(): List<GetWorkOpsLinkTypesData.WorkOps.Links.LinkTypes> =
        gql.execute(GetWorkOpsLinkTypes, Unit).workOps.links.linkTypes

    suspend fun linkTasks(input: WorkOpsTaskLinkInput): LinkWorkOpsTasksData.WorkOps.Links.Link =
        gql.execute(LinkWorkOpsTasks, LinkWorkOpsTasks.Variables(input)).workOps.links.link

    suspend fun unlinkTasks(linkId: Uuid): Boolean =
        gql.execute(UnlinkWorkOpsTasks, UnlinkWorkOpsTasks.Variables(linkId)).workOps.links.unlink

    // ── Labels ──────────────────────────────────────────────────

    suspend fun getGlobalLabels(): List<GetWorkOpsLabelsGlobalData.WorkOps.Labels.Global> =
        gql.execute(GetWorkOpsLabelsGlobal, Unit).workOps.labels.global

    suspend fun getLabelsByProject(projectId: Uuid): List<GetWorkOpsLabelsByProjectData.WorkOps.Labels.ByProject> =
        gql.execute(GetWorkOpsLabelsByProject, GetWorkOpsLabelsByProject.Variables(projectId)).workOps.labels.byProject

    suspend fun createLabel(input: CreateWorkOpsLabelInput): CreateWorkOpsLabelData.WorkOps.Labels.Create =
        gql.execute(CreateWorkOpsLabel, CreateWorkOpsLabel.Variables(input)).workOps.labels.create

    suspend fun deleteLabel(id: Uuid): Boolean =
        gql.execute(DeleteWorkOpsLabel, DeleteWorkOpsLabel.Variables(id)).workOps.labels.delete

    // ── Workflows ───────────────────────────────────────────────

    suspend fun getWorkflows(): List<GetWorkOpsWorkflowsData.WorkOps.Workflows.All> =
        gql.execute(GetWorkOpsWorkflows, Unit).workOps.workflows.all

    suspend fun getWorkflow(id: Uuid): GetWorkOpsWorkflowData.WorkOps.Workflows.Workflow? =
        gql.execute(GetWorkOpsWorkflow, GetWorkOpsWorkflow.Variables(id)).workOps.workflows.workflow

    // ── Search / BQL ────────────────────────────────────────────

    suspend fun searchTasks(bql: String, offset: Long = 0, limit: Int = 50): SearchWorkOpsTasksData.WorkOps.SavedFilters.SearchTasks =
        gql.execute(SearchWorkOpsTasks, SearchWorkOpsTasks.Variables(bql, offset, limit)).workOps.savedFilters.searchTasks

    suspend fun validateBql(source: String): ValidateWorkOpsBqlData.WorkOps.SavedFilters.ValidateBql =
        gql.execute(ValidateWorkOpsBql, ValidateWorkOpsBql.Variables(source)).workOps.savedFilters.validateBql

    // ── Milestones ──────────────────────────────────────────────

    suspend fun getMilestonesByProgram(programId: Uuid): List<GetWorkOpsMilestonesByProgramData.WorkOps.Milestones.ByProgram> =
        gql.execute(GetWorkOpsMilestonesByProgram, GetWorkOpsMilestonesByProgram.Variables(programId)).workOps.milestones.byProgram

    suspend fun createMilestone(input: CreateWorkOpsMilestoneInput): CreateWorkOpsMilestoneData.WorkOps.Milestones.Create =
        gql.execute(CreateWorkOpsMilestone, CreateWorkOpsMilestone.Variables(input)).workOps.milestones.create

    // ── Components ──────────────────────────────────────────────

    suspend fun getComponentsByProject(projectId: Uuid): List<GetWorkOpsComponentsByProjectData.WorkOps.Components.ByProject> =
        gql.execute(GetWorkOpsComponentsByProject, GetWorkOpsComponentsByProject.Variables(projectId)).workOps.components.byProject

    suspend fun createComponent(input: CreateWorkOpsComponentInput): CreateWorkOpsComponentData.WorkOps.Components.Create =
        gql.execute(CreateWorkOpsComponent, CreateWorkOpsComponent.Variables(input)).workOps.components.create

    // ── Versions ────────────────────────────────────────────────

    suspend fun getVersionsByProject(projectId: Uuid): List<GetWorkOpsVersionsByProjectData.WorkOps.Versions.ByProject> =
        gql.execute(GetWorkOpsVersionsByProject, GetWorkOpsVersionsByProject.Variables(projectId)).workOps.versions.byProject

    suspend fun createVersion(input: CreateWorkOpsVersionInput): CreateWorkOpsVersionData.WorkOps.Versions.Create =
        gql.execute(CreateWorkOpsVersion, CreateWorkOpsVersion.Variables(input)).workOps.versions.create

    suspend fun releaseVersion(id: Uuid, expectedVersion: Long): ReleaseWorkOpsVersionData.WorkOps.Versions.Release =
        gql.execute(ReleaseWorkOpsVersion, ReleaseWorkOpsVersion.Variables(id, expectedVersion)).workOps.versions.release

    // ── Notifications ───────────────────────────────────────────

    suspend fun getNotifications(offset: Long = 0, limit: Int = 50): List<GetWorkOpsNotificationsData.WorkOps.Notifications.Mine> =
        gql.execute(GetWorkOpsNotifications, GetWorkOpsNotifications.Variables(offset, limit)).workOps.notifications.mine

    suspend fun getUnreadCount(): Long =
        gql.execute(GetWorkOpsUnreadNotificationCount, Unit).workOps.notifications.unreadCount

    suspend fun markNotificationRead(id: Uuid): Boolean =
        gql.execute(MarkWorkOpsNotificationRead, MarkWorkOpsNotificationRead.Variables(id)).workOps.notifications.markRead

    suspend fun markAllNotificationsRead(): Boolean =
        gql.execute(MarkAllWorkOpsNotificationsRead, Unit).workOps.notifications.markAllRead

    suspend fun watchTask(taskId: Uuid): Boolean =
        gql.execute(WatchWorkOpsTask, WatchWorkOpsTask.Variables(taskId)).workOps.notifications.watch

    suspend fun unwatchTask(taskId: Uuid): Boolean =
        gql.execute(UnwatchWorkOpsTask, UnwatchWorkOpsTask.Variables(taskId)).workOps.notifications.unwatch

    // ── Specs ───────────────────────────────────────────────────

    suspend fun getSpec(id: Uuid): IWorkOpsSpecFragment? =
        gql.execute(GetWorkOpsSpec, GetWorkOpsSpec.Variables(id)).workOps.specs.spec

    suspend fun getSpecByKey(key: String): IWorkOpsSpecFragment? =
        gql.execute(GetWorkOpsSpecByKey, GetWorkOpsSpecByKey.Variables(key)).workOps.specs.specByKey

    suspend fun getSpecsByProject(projectId: Uuid, offset: Long = 0, limit: Int = 50): List<IWorkOpsSpecFragment> =
        gql.execute(GetWorkOpsSpecsByProject, GetWorkOpsSpecsByProject.Variables(projectId, offset, limit)).workOps.specs.byProject

    suspend fun getSpecsByProgram(programId: Uuid, offset: Long = 0, limit: Int = 50): List<IWorkOpsSpecFragment> =
        gql.execute(GetWorkOpsSpecsByProgram, GetWorkOpsSpecsByProgram.Variables(programId, offset, limit)).workOps.specs.byProgram

    suspend fun getSpecsByOwner(ownerProfileId: Uuid, offset: Long = 0, limit: Int = 50): List<IWorkOpsSpecFragment> =
        gql.execute(GetWorkOpsSpecsByOwner, GetWorkOpsSpecsByOwner.Variables(ownerProfileId, offset, limit)).workOps.specs.byOwner

    suspend fun getSpecChildren(parentSpecId: Uuid, offset: Long = 0, limit: Int = 50): List<IWorkOpsSpecFragment> =
        gql.execute(GetWorkOpsSpecChildren, GetWorkOpsSpecChildren.Variables(parentSpecId, offset, limit)).workOps.specs.children

    suspend fun createSpec(input: CreateWorkOpsSpecInput): IWorkOpsSpecFragment =
        gql.execute(CreateWorkOpsSpec, CreateWorkOpsSpec.Variables(input)).workOps.specs.create

    suspend fun updateSpec(id: Uuid, input: UpdateWorkOpsSpecInput): IWorkOpsSpecFragment =
        gql.execute(UpdateWorkOpsSpec, UpdateWorkOpsSpec.Variables(id, input)).workOps.specs.update

    suspend fun transitionSpec(id: Uuid, transitionId: Uuid, expectedVersion: Long, resolutionId: Uuid? = null): IWorkOpsSpecFragment =
        gql.execute(TransitionWorkOpsSpec, TransitionWorkOpsSpec.Variables(id, transitionId, expectedVersion, resolutionId)).workOps.specs.transition

    suspend fun softDeleteSpec(id: Uuid, expectedVersion: Long): IWorkOpsSpecFragment =
        gql.execute(SoftDeleteWorkOpsSpec, SoftDeleteWorkOpsSpec.Variables(id, expectedVersion)).workOps.specs.softDelete

    suspend fun restoreSpec(id: Uuid, expectedVersion: Long): IWorkOpsSpecFragment =
        gql.execute(RestoreWorkOpsSpec, RestoreWorkOpsSpec.Variables(id, expectedVersion)).workOps.specs.restore

    suspend fun getSpecHistory(specId: Uuid, offset: Long = 0, limit: Int = 50): List<GetWorkOpsSpecHistoryData.WorkOps.Specs.Spec.History> =
        gql.execute(GetWorkOpsSpecHistory, GetWorkOpsSpecHistory.Variables(specId, offset, limit)).workOps.specs.spec?.history ?: emptyList()

    suspend fun getSpecContexts(specId: Uuid): List<GetWorkOpsSpecContextsData.WorkOps.Specs.Spec.Contexts> =
        gql.execute(GetWorkOpsSpecContexts, GetWorkOpsSpecContexts.Variables(specId)).workOps.specs.spec?.contexts ?: emptyList()

    suspend fun addSpecContext(specId: Uuid, input: CreateWorkOpsSpecContextInput): AddWorkOpsSpecContextData.WorkOps.Specs.AddContext =
        gql.execute(AddWorkOpsSpecContext, AddWorkOpsSpecContext.Variables(specId, input)).workOps.specs.addContext

    suspend fun removeSpecContext(specId: Uuid, contextId: Uuid): Boolean =
        gql.execute(RemoveWorkOpsSpecContext, RemoveWorkOpsSpecContext.Variables(specId, contextId)).workOps.specs.removeContext

    suspend fun generateSpecTasks(specId: Uuid, metadataVersion: Int, source: WorkOpsGenerationSource, agentSessionId: Uuid? = null): GenerateWorkOpsSpecTasksData.WorkOps.Specs.GenerateTasks =
        gql.execute(GenerateWorkOpsSpecTasks, GenerateWorkOpsSpecTasks.Variables(specId, metadataVersion, source, agentSessionId)).workOps.specs.generateTasks

    suspend fun pushSpecToGit(specId: Uuid, content: String, authorName: String, authorEmail: String): String? =
        gql.execute(PushWorkOpsSpecToGit, PushWorkOpsSpecToGit.Variables(specId, content, authorName, authorEmail)).workOps.specs.pushToGit

    suspend fun pullSpecFromGit(specId: Uuid, commitSha: String): IWorkOpsSpecFragment? =
        gql.execute(PullWorkOpsSpecFromGit, PullWorkOpsSpecFromGit.Variables(specId, commitSha)).workOps.specs.pullFromGit

    // ── Spec Comments ──────────────────────────────────────────

    suspend fun getSpecComments(specId: Uuid, offset: Long = 0, limit: Long = 50): List<GetWorkOpsSpecCommentsData.WorkOps.SpecComments.ForSpec> =
        gql.execute(GetWorkOpsSpecComments, GetWorkOpsSpecComments.Variables(specId, offset, limit)).workOps.specComments.forSpec

    suspend fun addSpecComment(specId: Uuid, input: WorkOpsSpecCommentInput): AddWorkOpsSpecCommentData.WorkOps.SpecComments.Add =
        gql.execute(AddWorkOpsSpecComment, AddWorkOpsSpecComment.Variables(specId, input)).workOps.specComments.add

    suspend fun deleteSpecComment(specId: Uuid, commentId: Long): Boolean =
        gql.execute(DeleteWorkOpsSpecComment, DeleteWorkOpsSpecComment.Variables(specId, commentId)).workOps.specComments.delete

    // ── Requirements ───────────────────────────────────────────

    suspend fun getRequirement(id: Uuid): GetWorkOpsRequirementData.WorkOps.Requirements.Requirement? =
        gql.execute(GetWorkOpsRequirement, GetWorkOpsRequirement.Variables(id)).workOps.requirements.requirement

    suspend fun getRequirementByKey(key: String): GetWorkOpsRequirementByKeyData.WorkOps.Requirements.RequirementByKey? =
        gql.execute(GetWorkOpsRequirementByKey, GetWorkOpsRequirementByKey.Variables(key)).workOps.requirements.requirementByKey

    suspend fun getRequirementsBySpec(specId: Uuid, offset: Long = 0, limit: Int = 50): List<GetWorkOpsRequirementsBySpecData.WorkOps.Requirements.BySpec> =
        gql.execute(GetWorkOpsRequirementsBySpec, GetWorkOpsRequirementsBySpec.Variables(specId, offset, limit)).workOps.requirements.bySpec

    suspend fun getRequirementsByTask(taskId: Uuid, offset: Long = 0, limit: Int = 50): List<GetWorkOpsRequirementsByTaskData.WorkOps.Requirements.ByTask> =
        gql.execute(GetWorkOpsRequirementsByTask, GetWorkOpsRequirementsByTask.Variables(taskId, offset, limit)).workOps.requirements.byTask

    suspend fun createRequirement(input: CreateWorkOpsRequirementInput): CreateWorkOpsRequirementData.WorkOps.Requirements.Create =
        gql.execute(CreateWorkOpsRequirement, CreateWorkOpsRequirement.Variables(input)).workOps.requirements.create

    suspend fun updateRequirement(id: Uuid, input: UpdateWorkOpsRequirementInput): UpdateWorkOpsRequirementData.WorkOps.Requirements.Update =
        gql.execute(UpdateWorkOpsRequirement, UpdateWorkOpsRequirement.Variables(id, input)).workOps.requirements.update

    suspend fun softDeleteRequirement(id: Uuid, expectedVersion: Long): SoftDeleteWorkOpsRequirementData.WorkOps.Requirements.SoftDelete =
        gql.execute(SoftDeleteWorkOpsRequirement, SoftDeleteWorkOpsRequirement.Variables(id, expectedVersion)).workOps.requirements.softDelete

    suspend fun restoreRequirement(id: Uuid, expectedVersion: Long): RestoreWorkOpsRequirementData.WorkOps.Requirements.Restore =
        gql.execute(RestoreWorkOpsRequirement, RestoreWorkOpsRequirement.Variables(id, expectedVersion)).workOps.requirements.restore

    suspend fun moveRequirement(id: Uuid, parentType: WorkOpsRequirementParent, parentId: Uuid, expectedVersion: Long): MoveWorkOpsRequirementData.WorkOps.Requirements.MoveToParent =
        gql.execute(MoveWorkOpsRequirement, MoveWorkOpsRequirement.Variables(id, parentType, parentId, expectedVersion)).workOps.requirements.moveToParent
}
