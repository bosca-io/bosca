package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.links.TaskLink
import bosca.workops.model.links.TaskLinkInput
import bosca.workops.model.links.TaskLinkType
import bosca.workops.model.task.Task
import bosca.workops.service.TaskLinkService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService

@TypeController(type = "WorkOpsTaskLink")
class TaskLinkTypeController(
    private val taskLinkService: TaskLinkService,
    private val taskService: TaskService,
    private val taskPermissions: TaskPermissionEvaluator,
) : GraphQLController<TaskLink> {

    @Field fun id(l: TaskLink) = l.id
    @Field fun createdAt(l: TaskLink) = l.createdAt
    @Field fun createdByPrincipalId(l: TaskLink) = l.createdByPrincipalId

    @Field
    suspend fun linkType(link: TaskLink): TaskLinkType =
        taskLinkService.getLinkType(link.linkTypeId)
            ?: error("link type ${link.linkTypeId} missing for link ${link.id}")

    @Field
    suspend fun sourceTask(authentication: AuthenticationContext, link: TaskLink): Task? {
        val task = taskService.getById(link.sourceTaskId) ?: return null
        return if (taskPermissions.isAllowed(authentication, task, PermissionAction.VIEW)) task else null
    }

    @Field
    suspend fun targetTask(authentication: AuthenticationContext, link: TaskLink): Task? {
        val task = taskService.getById(link.targetTaskId) ?: return null
        return if (taskPermissions.isAllowed(authentication, task, PermissionAction.VIEW)) task else null
    }
}

@TypeController(type = "WorkOpsTaskLinkType")
class TaskLinkTypeFieldsController : GraphQLController<TaskLinkType> {
    @Field fun id(t: TaskLinkType) = t.id
    @Field fun name(t: TaskLinkType) = t.name
    @Field fun inwardLabel(t: TaskLinkType) = t.inwardLabel
    @Field fun inwardName(t: TaskLinkType) = t.inwardLabel
    @Field fun outwardLabel(t: TaskLinkType) = t.outwardLabel
    @Field fun outwardName(t: TaskLinkType) = t.outwardLabel
    @Field fun category(t: TaskLinkType) = t.category
    @Field fun version(t: TaskLinkType) = t.version
}

object WorkOpsTaskLinks

@TypeController
class TaskLinkQueryController(
    private val service: TaskLinkService,
    private val taskService: TaskService,
    private val taskPermissions: TaskPermissionEvaluator,
) : GraphQLController<WorkOpsTaskLinks> {

    @Field
    suspend fun linkTypes(authentication: AuthenticationContext): List<TaskLinkType> {
        return service.listLinkTypes()
    }

    @Field
    suspend fun forTask(authentication: AuthenticationContext, taskId: UUID): List<TaskLink> {
        val task = taskService.getById(taskId) ?: return emptyList()
        if (!taskPermissions.isAllowed(authentication, task, PermissionAction.VIEW)) return emptyList()
        return service.listForTask(taskId)
    }
}

object WorkOpsTaskLinksMutation

@TypeController
class TaskLinkMutationController(
    private val service: TaskLinkService,
    private val taskService: TaskService,
    private val taskPermissions: TaskPermissionEvaluator,
) : GraphQLController<WorkOpsTaskLinksMutation> {

    @Field
    suspend fun link(authentication: AuthenticationContext, input: TaskLinkInput): TaskLink {
        val sourceTask = taskService.getById(input.sourceTaskId)
            ?: error("Source task ${input.sourceTaskId} not found")
        taskPermissions.verifyAllowed(authentication, sourceTask, PermissionAction.EDIT)
        val targetTask = taskService.getById(input.targetTaskId)
            ?: error("Target task ${input.targetTaskId} not found")
        taskPermissions.verifyAllowed(authentication, targetTask, PermissionAction.VIEW)
        val authenticated = authentication.principal()
            ?: error("linkTasks requires an authenticated principal")
        return service.link(input, authenticated.id)
    }

    @Field
    suspend fun unlink(authentication: AuthenticationContext, linkId: UUID): Boolean {
        val link = service.getById(linkId)
            ?: error("TaskLink $linkId not found")
        val task = taskService.getById(link.sourceTaskId)
            ?: error("Source task ${link.sourceTaskId} not found")
        taskPermissions.verifyAllowed(authentication, task, PermissionAction.EDIT)
        service.unlink(linkId)
        return true
    }
}
