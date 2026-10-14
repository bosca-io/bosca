package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.workops.model.guidance.*
import bosca.workops.service.*

object WorkOpsGuidanceQuery
object WorkOpsGuidanceMutation

@TypeController(type = "WorkOpsGuidanceQuery")
class WorkOpsGuidanceQueryController(
    private val guidance: WorkOpsGuidanceService,
    private val projects: ProjectService,
    private val permissions: ProjectPermissionEvaluator,
) : GraphQLController<WorkOpsGuidanceQuery> {
    @Field suspend fun effective(authentication: AuthenticationContext, projectId: UUID?): WorkOpsGuidance {
        authorize(authentication, projectId)
        return guidance.effective(projectId)
    }
    @Field suspend fun exportSkill(authentication: AuthenticationContext, projectId: UUID?, phase: WorkOpsGuidancePhase): String {
        authorize(authentication, projectId)
        return guidance.exportSkill(projectId, phase)
    }
    @Field fun importSkill(authentication: AuthenticationContext, phase: WorkOpsGuidancePhase, skill: String): WorkOpsGuide {
        requireNotNull(authentication.principal()) { "Authentication required" }
        val guide = guidance.importSkill(phase, skill)
        return WorkOpsGuide(guide.phase, guide.name, guide.description, guide.markdown, false)
    }
    private suspend fun authorize(authentication: AuthenticationContext, projectId: UUID?) {
        requireNotNull(authentication.principal()) { "Authentication required" }
        if (projectId != null) permissions.verifyAllowed(authentication,
            projects.getById(projectId) ?: error("Project not found"), PermissionAction.VIEW)
    }
}

@TypeController(type = "WorkOpsGuidanceMutation")
class WorkOpsGuidanceMutationController(
    private val guidance: WorkOpsGuidanceService,
    private val projects: ProjectService,
    private val permissions: ProjectPermissionEvaluator,
    private val groups: GroupEvaluator,
) : GraphQLController<WorkOpsGuidanceMutation> {
    @Field suspend fun save(authentication: AuthenticationContext, projectId: UUID?, input: WorkOpsGuidanceInput, expectedRevision: String): WorkOpsGuidance {
        authorize(authentication, projectId)
        return guidance.save(projectId, input, expectedRevision)
    }
    @Field suspend fun reset(authentication: AuthenticationContext, projectId: UUID?, expectedRevision: String): WorkOpsGuidance {
        authorize(authentication, projectId)
        return guidance.reset(projectId, expectedRevision)
    }
    private suspend fun authorize(authentication: AuthenticationContext, projectId: UUID?) {
        requireNotNull(authentication.principal()) { "Authentication required" }
        if (projectId == null) groups.verifyHasAdminGroup(authentication)
        else permissions.verifyAllowed(authentication, projects.getById(projectId) ?: error("Project not found"), PermissionAction.MANAGE)
    }
}

@TypeController(type = "WorkOpsGuidance")
class WorkOpsGuidanceFieldController : GraphQLController<WorkOpsGuidance> {
    @Field fun revision(value: WorkOpsGuidance) = value.revision
    @Field fun policy(value: WorkOpsGuidance) = value.policy
    @Field fun policyInherited(value: WorkOpsGuidance) = value.policyInherited
    @Field fun guides(value: WorkOpsGuidance) = value.guides
}

@TypeController(type = "WorkOpsGuide")
class WorkOpsGuideFieldController : GraphQLController<WorkOpsGuide> {
    @Field fun phase(value: WorkOpsGuide) = value.phase
    @Field fun name(value: WorkOpsGuide) = value.name
    @Field fun description(value: WorkOpsGuide) = value.description
    @Field fun markdown(value: WorkOpsGuide) = value.markdown
    @Field fun inherited(value: WorkOpsGuide) = value.inherited
    @Field fun metadataId(value: WorkOpsGuide) = value.metadataId
    @Field fun documentVersion(value: WorkOpsGuide) = value.documentVersion
    @Field fun resourceId(value: WorkOpsGuide) = value.resourceId
}

@TypeController(type = "WorkOpsGuidancePolicy")
class WorkOpsGuidancePolicyFieldController : GraphQLController<WorkOpsGuidancePolicy> {
    @Field fun requireInterfaceReview(value: WorkOpsGuidancePolicy) = value.requireInterfaceReview
    @Field fun requireTdd(value: WorkOpsGuidancePolicy) = value.requireTdd
    @Field fun requireTestQuality(value: WorkOpsGuidancePolicy) = value.requireTestQuality
    @Field fun minimumLineCoverage(value: WorkOpsGuidancePolicy) = value.minimumLineCoverage
    @Field fun minimumBranchCoverage(value: WorkOpsGuidancePolicy) = value.minimumBranchCoverage
}
