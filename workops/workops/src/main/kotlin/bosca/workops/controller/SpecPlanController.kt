package bosca.workops.controller

import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.metadata.service.MetadataService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.spec.CreateSpecPlanInput
import bosca.workops.model.spec.RequirementContentInput
import bosca.workops.model.spec.SetRequirementContentInput
import bosca.workops.model.spec.Spec
import bosca.workops.service.*

@TypeController(type = "WorkOpsSpecsMutation")
class SpecPlanMutationController(
    private val planService: SpecPlanService,
    private val specService: SpecService,
    private val projectService: ProjectService,
    private val projectPermissions: ProjectPermissionEvaluator,
    private val specPermissions: SpecPermissionEvaluator,
    private val requirementPermissions: RequirementPermissionEvaluator,
    private val profileService: ProfileService,
) : GraphQLController<WorkOpsSpecsMutation> {
    @Field
    suspend fun createPlan(authentication: AuthenticationContext, input: CreateSpecPlanInput): Spec {
        val project = projectService.getById(input.projectId) ?: error("Project not found")
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.EDIT)
        val principal = authentication.principal() ?: error("Authentication required")
        val result = planService.create(input, principal.id, profileId(principal, profileService))
        specPermissions.verifyAllowed(authentication, result, PermissionAction.EDIT)
        return result
    }

    @Field
    suspend fun addPlanRequirement(authentication: AuthenticationContext, specId: UUID, requestId: UUID, input: RequirementContentInput): Requirement {
        val spec = specService.getById(specId) ?: error("Spec not found")
        specPermissions.verifyAllowed(authentication, spec, PermissionAction.EDIT)
        val principal = authentication.principal() ?: error("Authentication required")
        val result = planService.addRequirement(specId, requestId, input, principal.id, profileId(principal, profileService))
        requirementPermissions.verifyAllowed(authentication, result, PermissionAction.EDIT)
        return result
    }
}

@TypeController(type = "WorkOpsRequirementsMutation")
class RequirementContentMutationController(
    private val requirementService: RequirementService,
    private val taskService: TaskService,
    private val metadataService: MetadataService,
    private val requirementPermissions: RequirementPermissionEvaluator,
    private val taskPermissions: TaskPermissionEvaluator,
    private val metadataPermissions: MetadataPermissionEvaluator,
    private val profileService: ProfileService,
) : GraphQLController<WorkOpsRequirementsMutation> {
    @Field
    suspend fun setContent(authentication: AuthenticationContext, id: UUID, input: SetRequirementContentInput): Requirement {
        val requirement = requirementService.getById(id) ?: error("Requirement not found")
        requirementPermissions.verifyAllowed(authentication, requirement, PermissionAction.EDIT)
        val task = taskService.getById(requirement.taskId ?: error("Linked task is missing")) ?: error("Linked task is missing")
        taskPermissions.verifyAllowed(authentication, task, PermissionAction.EDIT)
        val metadata = metadataService.getById(requirement.metadataId) ?: error("Requirement document is missing")
        metadataPermissions.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        val principal = authentication.principal() ?: error("Authentication required")
        val profileId = principal.asPrincipal().primaryProfileId ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id
        return requirementService.setContent(id, input, principal.id, profileId)
    }
}

private suspend fun profileId(principal: AuthenticatedPrincipal, profiles: ProfileService): UUID =
    principal.asPrincipal().primaryProfileId ?: profiles.getByPrincipal(principal.id).firstOrNull()?.id ?: error("Principal has no profile")
