package bosca.workops.controller

import bosca.comments.model.CommentStatus
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.comment.TaskComment
import bosca.workops.model.comment.TaskCommentInput
import bosca.workops.service.ProjectService
import bosca.workops.service.TaskCommentService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService

@TypeController(type = "WorkOpsTaskComment")
class TaskCommentTypeController(
    private val service: TaskCommentService,
    private val taskService: TaskService,
    private val permissionEvaluator: TaskPermissionEvaluator,
    private val profileService: ProfileService,
    private val profilePermissions: ProfilePermissionEvaluator,
) : GraphQLController<TaskComment> {

    @Field fun id(c: TaskComment) = c.id
    @Field fun parentId(c: TaskComment) = c.parentId
    @Field fun taskId(c: TaskComment) = c.taskId
    @Field fun profileId(c: TaskComment) = c.profileId
    @Field fun impersonatorId(c: TaskComment) = c.impersonatorId

    @Field
    suspend fun profile(authentication: AuthenticationContext, c: TaskComment): Profile? {
        val profile = profileService.getById(c.profileId)
        return if (profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW)) profile else null
    }
    @Field fun visibility(c: TaskComment) = c.visibility
    @Field fun created(c: TaskComment) = c.created
    @Field fun modified(c: TaskComment) = c.modified
    @Field fun status(c: TaskComment) = c.status
    @Field fun content(c: TaskComment) = c.content
    @Field fun likes(c: TaskComment) = c.likes
    @Field fun deleted(c: TaskComment) = c.deleted

    private suspend fun resolveViewingProfileId(authentication: AuthenticationContext?): UUID? {
        val context = authentication ?: return null
        val authenticated = context.principal() ?: return null
        val principal = authenticated.asPrincipal()
        val primaryProfileId = principal.primaryProfileId
        if (primaryProfileId != null) return primaryProfileId
        val profile = profileService.getByPrincipal(principal.id).firstOrNull() ?: return null
        return profile.id
    }

    @Field
    suspend fun replies(
        comment: TaskComment,
        authentication: AuthenticationContext?,
        offset: Long,
        limit: Long,
    ): List<TaskComment> {
        val task = taskService.getById(comment.taskId)
        if (task == null || authentication == null ||
            !permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW)) {
            return emptyList()
        }
        val viewingProfileId = resolveViewingProfileId(authentication)
        val manager = permissionEvaluator.isAllowed(authentication, task, PermissionAction.MANAGE)
        return service.listReplies(
            taskId = comment.taskId,
            parentId = comment.id,
            viewingProfileId = viewingProfileId,
            manager = manager,
            offset = offset,
            limit = limit,
        )
    }
}

object WorkOpsTaskComments

@TypeController
class TaskCommentQueryController(
    private val service: TaskCommentService,
    private val taskService: TaskService,
    private val permissionEvaluator: TaskPermissionEvaluator,
    private val profileService: ProfileService,
) : GraphQLController<WorkOpsTaskComments> {

    private suspend fun resolveViewingProfileId(authentication: AuthenticationContext): UUID? {
        val authenticated = authentication.principal() ?: return null
        val principal = authenticated.asPrincipal()
        val primaryProfileId = principal.primaryProfileId
        if (primaryProfileId != null) return primaryProfileId
        val profile = profileService.getByPrincipal(principal.id).firstOrNull() ?: return null
        return profile.id
    }

    @Field
    suspend fun forTask(
        authentication: AuthenticationContext,
        taskId: UUID,
        offset: Long,
        limit: Long,
    ): List<TaskComment> {
        val task = taskService.getById(taskId) ?: return emptyList()
        if (!permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW)) return emptyList()
        val viewingProfileId = resolveViewingProfileId(authentication)
        val manager = permissionEvaluator.isAllowed(authentication, task, PermissionAction.MANAGE)
        return service.list(taskId, viewingProfileId, manager, offset, limit)
    }

    @Field
    suspend fun countForTask(authentication: AuthenticationContext, taskId: UUID): Long {
        val task = taskService.getById(taskId) ?: return 0
        if (!permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW)) return 0
        val viewingProfileId = resolveViewingProfileId(authentication)
        val manager = permissionEvaluator.isAllowed(authentication, task, PermissionAction.MANAGE)
        return service.count(taskId, viewingProfileId, manager)
    }

    @Field
    suspend fun repliesFor(
        authentication: AuthenticationContext,
        taskId: UUID,
        parentId: Long,
        offset: Long,
        limit: Long,
    ): List<TaskComment> {
        val task = taskService.getById(taskId) ?: return emptyList()
        if (!permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW)) return emptyList()
        val viewingProfileId = resolveViewingProfileId(authentication)
        val manager = permissionEvaluator.isAllowed(authentication, task, PermissionAction.MANAGE)
        return service.listReplies(taskId, parentId, viewingProfileId, manager, offset, limit)
    }

    @Field
    suspend fun comment(
        authentication: AuthenticationContext,
        taskId: UUID,
        commentId: Long,
    ): TaskComment? {
        val task = taskService.getById(taskId) ?: return null
        if (!permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW)) return null
        val viewingProfileId = resolveViewingProfileId(authentication)
        val manager = permissionEvaluator.isAllowed(authentication, task, PermissionAction.MANAGE)
        return service.get(taskId, commentId, viewingProfileId, manager)
    }
}

object WorkOpsTaskCommentsMutation

@TypeController
class TaskCommentMutationController(
    private val service: TaskCommentService,
    private val taskService: TaskService,
    private val projectService: ProjectService,
    private val permissionEvaluator: TaskPermissionEvaluator,
    private val watcherService: bosca.workops.service.TaskWatcherService,
    private val profileService: ProfileService,
    private val preferenceService: bosca.workops.service.NotificationPreferenceService,
) : GraphQLController<WorkOpsTaskCommentsMutation> {

    private suspend fun resolveProfileId(authenticated: bosca.security.model.AuthenticatedPrincipal): UUID? {
        val principal = authenticated.asPrincipal()
        val primaryProfileId = principal.primaryProfileId
        if (primaryProfileId != null) return primaryProfileId
        val profile = profileService.getByPrincipal(principal.id).firstOrNull() ?: return null
        return profile.id
    }

    @Field
    suspend fun add(
        authentication: AuthenticationContext,
        taskId: UUID,
        input: TaskCommentInput,
    ): TaskComment {
        val task = taskService.getById(taskId) ?: error("addTaskComment: task $taskId not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("addTaskComment requires an authenticated principal")
        val profileId = resolveProfileId(authenticated)
            ?: error("addTaskComment requires a profile")
        val comment = service.add(
            taskId = taskId,
            input = input,
            actingPrincipalId = authenticated.id,
            actingProfileId = profileId,
        )
        val pref = preferenceService.get(profileId)
        if (pref?.watchCommented != false) {
            watcherService.add(taskId, profileId)
        }
        val mentions = bosca.workops.service.resolveProfileMentions(input.content, profileService)
        for (mention in mentions) watcherService.add(taskId, mention)
        return comment
    }

    @Field
    suspend fun like(
        authentication: AuthenticationContext,
        taskId: UUID,
        commentId: Long,
    ): Int {
        val task = taskService.getById(taskId) ?: error("likeTaskComment: task $taskId not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.VIEW)
        val authenticated = authentication.principal()
            ?: error("likeTaskComment requires an authenticated principal")
        val profileId = resolveProfileId(authenticated)
            ?: error("likeTaskComment requires a profile")
        return service.like(taskId, commentId, profileId)
    }

    @Field
    suspend fun unlike(
        authentication: AuthenticationContext,
        taskId: UUID,
        commentId: Long,
    ): Int {
        val task = taskService.getById(taskId) ?: error("unlikeTaskComment: task $taskId not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.VIEW)
        val authenticated = authentication.principal()
            ?: error("unlikeTaskComment requires an authenticated principal")
        val profileId = resolveProfileId(authenticated)
            ?: error("unlikeTaskComment requires a profile")
        return service.unlike(taskId, commentId, profileId)
    }

    @Field
    suspend fun setStatus(
        authentication: AuthenticationContext,
        taskId: UUID,
        commentId: Long,
        status: CommentStatus,
    ): Boolean {
        val task = taskService.getById(taskId) ?: error("setTaskCommentStatus: task $taskId not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.MANAGE)
        val authenticated = authentication.principal()
            ?: error("setTaskCommentStatus requires an authenticated principal")
        service.setStatus(taskId, commentId, status, authenticated.id, resolveProfileId(authenticated))
        return true
    }

    @Field
    suspend fun delete(
        authentication: AuthenticationContext,
        taskId: UUID,
        commentId: Long,
    ): Boolean {
        val task = taskService.getById(taskId) ?: error("deleteTaskComment: task $taskId not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.MANAGE)
        val authenticated = authentication.principal()
            ?: error("deleteTaskComment requires an authenticated principal")
        service.delete(taskId, commentId, authenticated.id, resolveProfileId(authenticated))
        return true
    }
}
