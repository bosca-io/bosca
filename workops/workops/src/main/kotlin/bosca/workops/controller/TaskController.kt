package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.workops.model.audit.TaskHistoryEntry
import bosca.workops.model.links.TaskLink
import bosca.workops.model.milestone.Milestone
import bosca.workops.model.project.Project
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.task.Priority
import bosca.workops.model.task.Resolution
import bosca.workops.model.task.Task
import bosca.workops.model.task.TaskType
import bosca.workops.model.task.TaskTypeScheme
import bosca.workops.model.task.UpdateTaskInput
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.WorkflowTransition
import bosca.workops.repository.TaskPermissionRepository
import bosca.workops.service.MilestoneService
import bosca.workops.service.PriorityService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.RequirementService
import bosca.workops.service.ResolutionService
import bosca.workops.service.StatusService
import bosca.workops.service.TaskAffectedProjectService
import bosca.workops.service.TaskCommentService
import bosca.workops.service.TaskCustomFieldService
import bosca.workops.service.TaskLinkService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import bosca.workops.service.TaskTypeSchemeService
import bosca.workops.service.TaskTypeService
import bosca.workops.service.WorkflowService
import bosca.workops.service.matching

@TypeController(type = "WorkOpsTask")
class TaskTypeFieldController(
    private val projectService: ProjectService,
    private val taskTypeService: TaskTypeService,
    private val statusService: StatusService,
    private val priorityService: PriorityService,
    private val resolutionService: ResolutionService,
    private val taskService: TaskService,
    private val workflowService: WorkflowService,
    private val taskLinkService: TaskLinkService,
    private val taskCommentService: TaskCommentService,
    private val customFieldService: TaskCustomFieldService,
    private val profileService: ProfileService,
    private val profilePermissions: ProfilePermissionEvaluator,
    private val permissionEvaluator: TaskPermissionEvaluator,
    private val milestoneService: MilestoneService,
    private val affectedProjectService: TaskAffectedProjectService,
    private val requirementService: RequirementService,
) : GraphQLController<Task> {

    @Field fun id(t: Task) = t.id
    @Field fun key(t: Task) = t.key
    @Field fun summary(t: Task) = t.summary
    @Field fun descriptionMarkdown(t: Task) = t.descriptionMarkdown
    @Field fun descriptionHtml(t: Task) = t.descriptionHtml
    @Field fun reporterProfileId(t: Task) = t.reporterProfileId
    @Field fun assigneeProfileId(t: Task) = t.assigneeProfileId
    @Field fun sprintId(t: Task) = t.sprintId
    @Field fun milestoneId(t: Task) = t.milestoneId

    @Field
    suspend fun milestone(task: Task): Milestone? {
        val id = task.milestoneId ?: return null
        return milestoneService.getById(id)
    }

    @Field fun affectsVersionIds(t: Task) = t.affectsVersionIds
    @Field fun fixVersionIds(t: Task) = t.fixVersionIds
    @Field fun componentIds(t: Task) = t.componentIds
    @Field fun labelIds(t: Task) = t.labelIds
    @Field fun originalEstimateSeconds(t: Task) = t.originalEstimateSeconds
    @Field fun remainingEstimateSeconds(t: Task) = t.remainingEstimateSeconds
    @Field fun timeSpentSeconds(t: Task) = t.timeSpentSeconds
    @Field fun dueDate(t: Task) = t.dueDate
    @Field fun startDate(t: Task) = t.startDate
    @Field fun resolutionAt(t: Task) = t.resolutionAt
    @Field fun slaDueAt(t: Task) = t.slaDueAt
    @Field fun metadataId(t: Task) = t.metadataId
    @Field fun contentItemId(t: Task) = t.contentItemId
    @Field fun collectionId(t: Task) = t.collectionId
    @Field fun watcherProfileIds(t: Task) = t.watcherProfileIds
    @Field fun voteCount(t: Task) = t.voteCount
    @Field fun deletedAt(t: Task) = t.deletedAt
    @Field fun createdAt(t: Task) = t.createdAt
    @Field fun modifiedAt(t: Task) = t.modifiedAt
    @Field fun createdByPrincipalId(t: Task) = t.createdByPrincipalId
    @Field fun modifiedByPrincipalId(t: Task) = t.modifiedByPrincipalId
    @Field fun version(t: Task) = t.version
    @Field fun epicTotalEstimateSeconds(t: Task) = t.epicTotalEstimateSeconds
    @Field fun epicTotalRemainingSeconds(t: Task) = t.epicTotalRemainingSeconds
    @Field fun epicTotalSpentSeconds(t: Task) = t.epicTotalSpentSeconds
    @Field fun epicChildCount(t: Task) = t.epicChildCount
    @Field fun epicChildDoneCount(t: Task) = t.epicChildDoneCount

    @Field
    suspend fun reporter(authentication: AuthenticationContext, task: Task): Profile? {
        val profile = profileService.getById(task.reporterProfileId)
        return if (profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW)) profile else null
    }

    @Field
    suspend fun assignee(authentication: AuthenticationContext, task: Task): Profile? {
        val id = task.assigneeProfileId ?: return null
        val profile = profileService.getById(id)
        return if (profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW)) profile else null
    }

    @Field
    suspend fun project(task: Task): Project =
        projectService.getById(task.projectId)
            ?: error("Project ${task.projectId} missing for task ${task.key}")

    @Field
    suspend fun taskType(task: Task): TaskType =
        taskTypeService.getById(task.taskTypeId)
            ?: error("TaskType ${task.taskTypeId} missing for task ${task.key}")

    @Field
    suspend fun status(task: Task): Status =
        statusService.getById(task.statusId)
            ?: error("Status ${task.statusId} missing for task ${task.key}")

    @Field
    suspend fun priority(task: Task): Priority =
        priorityService.getById(task.priorityId)
            ?: error("Priority ${task.priorityId} missing for task ${task.key}")

    @Field
    suspend fun resolution(task: Task): Resolution? =
        task.resolutionId?.let { resolutionService.getById(it) }

    @Field
    suspend fun parentTask(task: Task): Task? =
        task.parentTaskId?.let { taskService.getById(it) }

    @Field
    suspend fun epicTask(task: Task): Task? =
        task.epicTaskId?.let { taskService.getById(it) }

    @Field
    suspend fun history(task: Task, offset: Long, limit: Int): List<TaskHistoryEntry> =
        taskService.listHistory(task.id, offset, limit)

    @Field
    suspend fun transitions(task: Task, currentOnly: Boolean): List<WorkflowTransition> {
        val resolution = workflowService.resolveWorkflowForTask(task)
        return if (currentOnly) resolution.transitions.matching(resolution.currentState.id)
        else resolution.transitions
    }

    @Field
    suspend fun links(task: Task): List<TaskLink> = taskLinkService.listForTask(task.id)

    @Field
    suspend fun comments(
        task: Task,
        authentication: AuthenticationContext?,
        offset: Long,
        limit: Long,
    ): List<bosca.workops.model.comment.TaskComment> {
        val authenticated = if (authentication == null) null else authentication.principal()
        val viewingProfileId = if (authenticated == null) {
            null
        } else {
            val principal = authenticated.asPrincipal()
            val primaryProfileId = principal.primaryProfileId
            if (primaryProfileId != null) {
                primaryProfileId
            } else {
                val profile = profileService.getByPrincipal(principal.id).firstOrNull()
                if (profile == null) null else profile.id
            }
        }
        val manager = authentication != null && permissionEvaluator.isAllowed(authentication, task, PermissionAction.MANAGE)
        return taskCommentService.list(task.id, viewingProfileId, manager, offset, limit)
    }

    @Field
    suspend fun customFields(
        task: Task,
        authentication: AuthenticationContext?,
    ): kotlinx.serialization.json.JsonElement {
        val project = projectService.getById(task.projectId) ?: return task.customFieldValues
        val manager = authentication != null && permissionEvaluator.isAllowed(authentication, task, PermissionAction.MANAGE)
        return customFieldService.filterForRead(project, task, manager)
    }

    @Field
    suspend fun permissions(authentication: AuthenticationContext, task: Task): List<EntityPermission> {
        if (!permissionEvaluator.isAllowed(authentication, task, PermissionAction.MANAGE)) return emptyList()
        return taskService.getPermissions(task)
    }

    @Field
    suspend fun affectedProjects(task: Task): List<Project> {
        val affected = affectedProjectService.listForTask(task.id)
        if (affected.isEmpty()) return emptyList()
        return affected.mapNotNull { projectService.getById(it.projectId) }
    }

    @Field
    suspend fun customFieldValuesRaw(
        task: Task,
        authentication: AuthenticationContext,
    ): kotlinx.serialization.json.JsonElement {
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.MANAGE)
        return task.customFieldValues
    }

    @Field
    suspend fun requirement(task: Task): Requirement? = requirementService.getByTaskId(task.id)
}

@TypeController(type = "WorkOpsTaskTypeScheme")
class TaskTypeSchemeFieldController(
    private val taskTypeService: TaskTypeService,
) : GraphQLController<TaskTypeScheme> {

    @Field fun id(s: TaskTypeScheme) = s.id
    @Field fun name(s: TaskTypeScheme) = s.name
    @Field fun description(s: TaskTypeScheme) = s.description
    @Field fun taskTypeIds(s: TaskTypeScheme) = s.taskTypeIds
    @Field fun defaultTaskTypeId(s: TaskTypeScheme) = s.defaultTaskTypeId
    @Field fun version(s: TaskTypeScheme) = s.version

    @Field
    suspend fun defaultTaskType(scheme: TaskTypeScheme): TaskType =
        taskTypeService.getById(scheme.defaultTaskTypeId)
            ?: error("Default TaskType ${scheme.defaultTaskTypeId} missing for scheme ${scheme.id}")

    @Field
    suspend fun taskTypes(scheme: TaskTypeScheme): List<TaskType> =
        taskTypeService.getByIds(scheme.taskTypeIds)
}

object WorkOpsTasks

@TypeController
class TaskQueryController(
    private val taskService: TaskService,
    private val taskTypeService: TaskTypeService,
    private val taskTypeSchemeService: TaskTypeSchemeService,
    private val priorityService: PriorityService,
    private val resolutionService: ResolutionService,
    private val projectService: ProjectService,
    private val permissionEvaluator: TaskPermissionEvaluator,
    private val projectPermissionEvaluator: ProjectPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<WorkOpsTasks> {

    @Field
    suspend fun task(authentication: AuthenticationContext, id: UUID): Task? {
        val task = taskService.getById(id) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW)) task else null
    }

    @Field
    suspend fun taskByKey(authentication: AuthenticationContext, key: String): Task? {
        val task = taskService.getByKey(key) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW)) task else null
    }

    @Field
    suspend fun byProject(
        authentication: AuthenticationContext,
        projectId: UUID,
        offset: Long,
        limit: Int,
    ): List<Task> {
        val project = projectService.getById(projectId) ?: return emptyList()
        projectPermissionEvaluator.verifyAllowed(authentication, project, PermissionAction.VIEW)
        return taskService.listByProject(projectId, offset, limit)
    }

    @Field
    suspend fun byAffectedProject(
        authentication: AuthenticationContext,
        projectId: UUID,
        offset: Long,
        limit: Int,
    ): List<Task> {
        val project = projectService.getById(projectId) ?: return emptyList()
        projectPermissionEvaluator.verifyAllowed(authentication, project, PermissionAction.VIEW)
        return taskService.listByAffectedProject(projectId, offset, limit)
    }

    @Field
    suspend fun taskTypes(): List<TaskType> = taskTypeService.list()

    @Field
    suspend fun taskTypeSchemes(): List<TaskTypeScheme> = taskTypeSchemeService.list()

    @Field
    suspend fun priorities(): List<Priority> = priorityService.list()

    @Field
    suspend fun resolutions(): List<Resolution> = resolutionService.list()
}

object WorkOpsTasksMutation

@TypeController
class TaskMutationController(
    private val service: TaskService,
    private val projectService: ProjectService,
    private val profileService: ProfileService,
    private val permissionEvaluator: TaskPermissionEvaluator,
    private val projectPermissionEvaluator: ProjectPermissionEvaluator,
    private val watcherService: bosca.workops.service.TaskWatcherService,
    private val permissionRepo: TaskPermissionRepository,
    private val affectedProjectService: TaskAffectedProjectService,
) : GraphQLController<WorkOpsTasksMutation> {

    private suspend fun resolveProfileId(authenticated: bosca.security.model.AuthenticatedPrincipal): UUID? {
        val principal = authenticated.asPrincipal()
        return principal.primaryProfileId
            ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id
    }

    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateTaskInput): Task {
        val project = projectService.getById(input.projectId)
            ?: error("createTask requires an existing project ${input.projectId}")
        projectPermissionEvaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("createTask requires an authenticated principal")
        val principalId = authenticated.id
        val profileId = resolveProfileId(authenticated)
            ?: error("createTask requires the principal to have at least one profile for reporter attribution")
        val reporterProfileId = profileId
        val task = service.create(
            input = input,
            actingPrincipalId = principalId,
            actingProfileId = profileId,
            reporterProfileId = reporterProfileId,
        )
        watcherService.add(task.id, reporterProfileId)
        task.assigneeProfileId?.let { watcherService.add(task.id, it) }
        return task
    }

    @Field
    suspend fun update(authentication: AuthenticationContext, id: UUID, input: UpdateTaskInput): Task {
        val task = service.getById(id) ?: error("updateTask: task $id not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("updateTask requires an authenticated principal")
        val updated = service.update(id, input, authenticated.id, resolveProfileId(authenticated))
        updated.assigneeProfileId
            ?.takeIf { it != task.assigneeProfileId }
            ?.let { watcherService.add(updated.id, it) }
        return updated
    }

    @Field
    suspend fun softDelete(
        authentication: AuthenticationContext,
        id: UUID,
        expectedVersion: Long,
    ): Task {
        val task = service.getById(id) ?: error("softDeleteTask: task $id not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.DELETE)
        val authenticated = authentication.principal()
            ?: error("softDeleteTask requires an authenticated principal")
        return service.softDelete(id, expectedVersion, authenticated.id, resolveProfileId(authenticated))
    }

    @Field
    suspend fun restore(
        authentication: AuthenticationContext,
        id: UUID,
        expectedVersion: Long,
    ): Task {
        val task = service.getById(id) ?: error("restoreTask: task $id not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.DELETE)
        val authenticated = authentication.principal()
            ?: error("restoreTask requires an authenticated principal")
        return service.restore(id, expectedVersion, authenticated.id, resolveProfileId(authenticated))
    }

    @Field
    suspend fun transition(
        authentication: AuthenticationContext,
        id: UUID,
        transitionId: UUID,
        expectedVersion: Long,
        resolutionId: UUID? = null,
        comment: String? = null,
    ): Task {
        val task = service.getById(id) ?: error("transitionTask: task $id not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("transitionTask requires an authenticated principal")
        val profileId = resolveProfileId(authenticated)
        val permissions = PermissionAction.entries
            .filter { permissionEvaluator.isAllowed(authentication, task, it) }
            .toSet()
        val transitioned = service.transition(
            id = id,
            transitionId = transitionId,
            expectedVersion = expectedVersion,
            actingPrincipalId = authenticated.id,
            actingProfileId = profileId,
            principalPermissions = permissions,
            resolutionId = resolutionId,
            comment = comment,
        )
        return transitioned
    }

    @Field
    suspend fun createDocument(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Task {
        val task = service.getById(id) ?: error("createDocument: task $id not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("createDocument requires an authenticated principal")
        return service.createDocument(id, expectedVersion, authenticated.id, resolveProfileId(authenticated))
    }

    @Field
    suspend fun addPermission(authentication: AuthenticationContext, id: UUID, groupId: UUID, action: PermissionAction): Boolean {
        val task = service.getById(id) ?: error("Task $id not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.MANAGE)
        permissionRepo.add(id, groupId, action)
        return true
    }

    @Field
    suspend fun removePermission(authentication: AuthenticationContext, id: UUID, groupId: UUID, action: PermissionAction): Boolean {
        val task = service.getById(id) ?: error("Task $id not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.MANAGE)
        permissionRepo.delete(id, groupId, action)
        return true
    }

    @Field
    suspend fun addAffectedProject(authentication: AuthenticationContext, id: UUID, projectId: UUID): Boolean {
        val task = service.getById(id) ?: error("Task $id not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT)
        affectedProjectService.add(id, projectId)
        return true
    }

    @Field
    suspend fun removeAffectedProject(authentication: AuthenticationContext, id: UUID, projectId: UUID): Boolean {
        val task = service.getById(id) ?: error("Task $id not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT)
        affectedProjectService.remove(id, projectId)
        return true
    }
}
