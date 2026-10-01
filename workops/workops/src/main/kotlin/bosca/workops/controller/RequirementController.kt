package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.audit.RequirementHistoryEntry
import bosca.workops.model.requirement.CreateRequirementInput
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.model.requirement.UpdateRequirementInput
import bosca.workops.model.task.Priority
import bosca.workops.model.task.Task
import bosca.workops.model.workflow.Status
import bosca.workops.service.PriorityService
import bosca.workops.service.RequirementPermissionEvaluator
import bosca.workops.service.RequirementService
import bosca.workops.service.SpecService
import bosca.workops.service.StatusService
import bosca.workops.service.TaskService

@TypeController(type = "WorkOpsRequirement")
class RequirementTypeFieldController(
    private val statusService: StatusService,
    private val priorityService: PriorityService,
    private val requirementService: RequirementService,
    private val taskService: TaskService,
    private val taskPermissionEvaluator: bosca.workops.service.TaskPermissionEvaluator,
    private val profileService: ProfileService,
    private val profilePermissions: ProfilePermissionEvaluator,
) : GraphQLController<Requirement> {

    @Field fun id(r: Requirement) = r.id
    @Field fun key(r: Requirement) = r.key
    @Field fun metadataId(r: Requirement) = r.metadataId
    @Field fun parentType(r: Requirement) = r.parentType
    @Field fun parentId(r: Requirement) = r.parentId
    @Field fun assigneeProfileId(r: Requirement) = r.assigneeProfileId
    @Field fun taskId(r: Requirement) = r.taskId
    @Field fun sortOrder(r: Requirement) = r.sortOrder
    @Field fun labelIds(r: Requirement) = r.labelIds
    @Field fun deletedAt(r: Requirement) = r.deletedAt
    @Field fun createdAt(r: Requirement) = r.createdAt
    @Field fun modifiedAt(r: Requirement) = r.modifiedAt
    @Field fun createdByPrincipalId(r: Requirement) = r.createdByPrincipalId
    @Field fun modifiedByPrincipalId(r: Requirement) = r.modifiedByPrincipalId
    @Field fun version(r: Requirement) = r.version

    @Field
    suspend fun status(r: Requirement): Status =
        statusService.getById(r.statusId)
            ?: error("Status ${r.statusId} missing for requirement ${r.key}")

    @Field
    suspend fun priority(r: Requirement): Priority =
        priorityService.getById(r.priorityId)
            ?: error("Priority ${r.priorityId} missing for requirement ${r.key}")

    @Field
    suspend fun task(authentication: AuthenticationContext, r: Requirement): Task? {
        val id = r.taskId ?: return null
        val task = taskService.getById(id) ?: return null
        return if (taskPermissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW)) task else null
    }

    @Field
    suspend fun assignee(authentication: AuthenticationContext, r: Requirement): Profile? {
        val id = r.assigneeProfileId ?: return null
        val profile = profileService.getById(id)
        return if (profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW)) profile else null
    }

    @Field
    suspend fun history(r: Requirement, offset: Long, limit: Int): List<RequirementHistoryEntry> =
        requirementService.listHistory(r.id, offset, limit)
}

object WorkOpsRequirements

@TypeController
class RequirementQueryController(
    private val requirementService: RequirementService,
    private val permissionEvaluator: RequirementPermissionEvaluator,
    private val specService: SpecService,
    private val taskService: TaskService,
    private val specPermissionEvaluator: bosca.workops.service.SpecPermissionEvaluator,
    private val taskPermissionEvaluator: bosca.workops.service.TaskPermissionEvaluator,
) : GraphQLController<WorkOpsRequirements> {

    @Field
    suspend fun requirement(authentication: AuthenticationContext, id: UUID): Requirement? {
        val req = requirementService.getById(id) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, req, PermissionAction.VIEW)) req else null
    }

    @Field
    suspend fun requirementByKey(authentication: AuthenticationContext, key: String): Requirement? {
        val req = requirementService.getByKey(key) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, req, PermissionAction.VIEW)) req else null
    }

    @Field
    suspend fun bySpec(authentication: AuthenticationContext, specId: UUID, offset: Long, limit: Int): List<Requirement> {
        val spec = specService.getById(specId) ?: return emptyList()
        specPermissionEvaluator.verifyAllowed(authentication, spec, PermissionAction.VIEW)
        return requirementService.listByParent(RequirementParent.SPEC, specId, offset, limit)
    }

    @Field
    suspend fun byTask(authentication: AuthenticationContext, taskId: UUID, offset: Long, limit: Int): List<Requirement> {
        val task = taskService.getById(taskId) ?: return emptyList()
        taskPermissionEvaluator.verifyAllowed(authentication, task, PermissionAction.VIEW)
        return requirementService.listByParent(RequirementParent.TASK, taskId, offset, limit)
    }
}

object WorkOpsRequirementsMutation

@TypeController
class RequirementMutationController(
    private val requirementService: RequirementService,
    private val permissionEvaluator: RequirementPermissionEvaluator,
    private val specService: SpecService,
    private val taskService: TaskService,
    private val specPermissionEvaluator: bosca.workops.service.SpecPermissionEvaluator,
    private val taskPermissionEvaluator: bosca.workops.service.TaskPermissionEvaluator,
    private val profileService: ProfileService,
) : GraphQLController<WorkOpsRequirementsMutation> {

    private suspend fun resolveProfileId(authenticated: bosca.security.model.AuthenticatedPrincipal): UUID? {
        val principal = authenticated.asPrincipal()
        return principal.primaryProfileId
            ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id
    }

    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateRequirementInput): Requirement {
        when (input.parentType) {
            RequirementParent.SPEC -> {
                val spec = specService.getById(input.parentId) ?: error("createRequirement: spec ${input.parentId} not found")
                specPermissionEvaluator.verifyAllowed(authentication, spec, PermissionAction.EDIT)
            }
            RequirementParent.TASK -> {
                val task = taskService.getById(input.parentId) ?: error("createRequirement: task ${input.parentId} not found")
                taskPermissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT)
            }
        }
        val authenticated = authentication.principal()
            ?: error("createRequirement requires an authenticated principal")
        return requirementService.create(
            input = input,
            actingPrincipalId = authenticated.id,
            actingProfileId = resolveProfileId(authenticated),
        )
    }

    @Field
    suspend fun update(authentication: AuthenticationContext, id: UUID, input: UpdateRequirementInput): Requirement {
        val req = requirementService.getById(id) ?: error("updateRequirement: requirement $id not found")
        permissionEvaluator.verifyAllowed(authentication, req, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("updateRequirement requires an authenticated principal")
        return requirementService.update(id, input, authenticated.id, resolveProfileId(authenticated))
    }

    @Field
    suspend fun softDelete(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Requirement {
        val req = requirementService.getById(id) ?: error("softDeleteRequirement: requirement $id not found")
        permissionEvaluator.verifyAllowed(authentication, req, PermissionAction.DELETE)
        val authenticated = authentication.principal()
            ?: error("softDeleteRequirement requires an authenticated principal")
        return requirementService.softDelete(id, expectedVersion, authenticated.id, resolveProfileId(authenticated))
    }

    @Field
    suspend fun restore(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Requirement {
        val req = requirementService.getById(id) ?: error("restoreRequirement: requirement $id not found")
        permissionEvaluator.verifyAllowed(authentication, req, PermissionAction.DELETE)
        val authenticated = authentication.principal()
            ?: error("restoreRequirement requires an authenticated principal")
        return requirementService.restore(id, expectedVersion, authenticated.id, resolveProfileId(authenticated))
    }

    @Field
    suspend fun moveToParent(
        authentication: AuthenticationContext,
        id: UUID,
        parentType: RequirementParent,
        parentId: UUID,
        expectedVersion: Long,
    ): Requirement {
        val req = requirementService.getById(id) ?: error("moveToParent: requirement $id not found")
        permissionEvaluator.verifyAllowed(authentication, req, PermissionAction.EDIT)
        when (parentType) {
            RequirementParent.SPEC -> {
                val spec = specService.getById(parentId) ?: error("moveToParent: spec $parentId not found")
                specPermissionEvaluator.verifyAllowed(authentication, spec, PermissionAction.EDIT)
            }
            RequirementParent.TASK -> {
                val task = taskService.getById(parentId) ?: error("moveToParent: task $parentId not found")
                taskPermissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT)
            }
        }
        val authenticated = authentication.principal()
            ?: error("moveToParent requires an authenticated principal")
        return requirementService.moveToParent(id, parentType, parentId, expectedVersion, authenticated.id, resolveProfileId(authenticated))
    }
}
