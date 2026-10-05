package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.worklog.DurationShorthand
import bosca.workops.model.worklog.WorkLog
import bosca.workops.model.worklog.WorkLogInput
import bosca.workops.model.worklog.WorklogVisibility
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import bosca.workops.service.WorkLogService

@TypeController(type = "WorkOpsWorkLog")
class WorkLogTypeController : GraphQLController<WorkLog> {
    @Field fun id(log: WorkLog) = log.id
    @Field fun taskId(log: WorkLog) = log.taskId
    @Field fun profileId(log: WorkLog) = log.profileId
    @Field fun timeSpentSeconds(log: WorkLog) = log.timeSpentSeconds
    @Field fun timeSpentShort(log: WorkLog): String = DurationShorthand.renderShorthand(log.timeSpentSeconds)
    @Field fun startedAt(log: WorkLog) = log.startedAt
    @Field fun comment(log: WorkLog) = log.comment
    @Field fun visibility(log: WorkLog): WorklogVisibility = log.worklogVisibility
    @Field fun createdAt(log: WorkLog) = log.createdAt
    @Field fun modifiedAt(log: WorkLog) = log.modifiedAt
}

object WorkOpsWorklogQuery

@TypeController
class WorklogQueryController(
    private val service: WorkLogService,
    private val taskService: TaskService,
    private val permissionEvaluator: TaskPermissionEvaluator,
) : GraphQLController<WorkOpsWorklogQuery> {

    @Field
    suspend fun forTask(
        authentication: AuthenticationContext,
        taskId: UUID,
        offset: Long,
        limit: Int,
    ): List<WorkLog> {
        val task = taskService.getById(taskId) ?: return emptyList()
        if (!permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW)) return emptyList()
        return service.listForTask(taskId, offset, limit)
    }

    @Field
    suspend fun worklog(authentication: AuthenticationContext, id: UUID): WorkLog? {
        val worklog = service.get(id) ?: return null
        val task = taskService.getById(worklog.taskId) ?: return null
        if (!permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW)) return null
        return worklog
    }
}

object WorkOpsWorklogMutation

@TypeController
class WorklogMutationController(
    private val service: WorkLogService,
    private val taskService: TaskService,
    private val permissionEvaluator: TaskPermissionEvaluator,
    private val profileService: ProfileService,
) : GraphQLController<WorkOpsWorklogMutation> {

    @Field
    suspend fun logWork(
        authentication: AuthenticationContext,
        taskId: UUID,
        input: WorkLogInput,
    ): WorkLog {
        val task = taskService.getById(taskId) ?: error("logWork: task $taskId not found")
        permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("logWork requires an authenticated principal")
        val profileId = authenticated.asPrincipal().primaryProfileId
            ?: profileService.getByPrincipal(authenticated.id).firstOrNull()?.id
            ?: error("logWork requires a profile")
        return service.log(taskId, profileId, input)
    }

    @Field
    suspend fun updateWorklog(
        authentication: AuthenticationContext,
        id: UUID,
        input: WorkLogInput,
    ): WorkLog {
        val existing = service.get(id) ?: error("updateWorklog: worklog $id not found")
        val task = taskService.getById(existing.taskId)
            ?: error("updateWorklog: task ${existing.taskId} not found")
        val authenticated = authentication.principal()
            ?: error("updateWorklog requires an authenticated principal")
        val profileId = authenticated.asPrincipal().primaryProfileId
            ?: profileService.getByPrincipal(authenticated.id).firstOrNull()?.id
            ?: error("updateWorklog requires a profile")
        if (existing.profileId != profileId) {
            permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.MANAGE)
        } else {
            permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT)
        }
        return service.update(id, input)
    }

    @Field
    suspend fun deleteWorklog(authentication: AuthenticationContext, id: UUID): Boolean {
        val existing = service.get(id) ?: return false
        val task = taskService.getById(existing.taskId) ?: return false
        val authenticated = authentication.principal()
            ?: error("deleteWorklog requires an authenticated principal")
        val profileId = authenticated.asPrincipal().primaryProfileId
            ?: profileService.getByPrincipal(authenticated.id).firstOrNull()?.id
            ?: error("deleteWorklog requires a profile")
        if (existing.profileId != profileId) {
            permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.MANAGE)
        } else {
            permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT)
        }
        return service.delete(id)
    }
}
