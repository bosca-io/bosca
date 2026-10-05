package bosca.workops.controller

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.audit.RequirementHistoryEntry
import bosca.workops.model.audit.SpecHistoryEntry
import bosca.workops.model.project.Project
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.model.spec.CreateSpecInput
import bosca.workops.model.spec.Spec
import bosca.workops.model.spec.SpecComment
import bosca.workops.model.spec.SpecContext
import bosca.workops.model.spec.SpecTaskGeneration
import bosca.workops.model.spec.UpdateSpecInput
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.WorkflowTransition
import bosca.workops.repository.SpecTaskGenerationRepository
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.RequirementService
import bosca.workops.service.SpecCommentService
import bosca.workops.service.SpecContextService
import bosca.workops.service.SpecGitSyncService
import bosca.workops.service.SpecPermissionEvaluator
import bosca.workops.service.SpecService
import bosca.workops.service.StatusService
import bosca.workops.service.WorkflowService
import bosca.workops.service.matching
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@TypeController(type = "WorkOpsSpec")
class SpecTypeFieldController(
    private val projectService: ProjectService,
    private val statusService: StatusService,
    private val specService: SpecService,
    private val requirementService: RequirementService,
    private val contextService: SpecContextService,
    private val commentService: SpecCommentService,
    private val generationRepository: SpecTaskGenerationRepository,
    private val profileService: ProfileService,
    private val profilePermissions: ProfilePermissionEvaluator,
    private val workflowService: WorkflowService,
    private val metadataService: MetadataService,
    private val metadataPermissions: MetadataPermissionEvaluator,
) : GraphQLController<Spec> {

    @Field
    fun id(s: Spec) = s.id

    @Field
    fun key(s: Spec) = s.key

    @Field
    fun metadataId(s: Spec) = s.metadataId

    @Field
    fun programId(s: Spec) = s.programId

    @Field
    fun projectId(s: Spec) = s.projectId

    @Field
    fun ownerProfileId(s: Spec) = s.ownerProfileId

    @Field
    fun parentSpecId(s: Spec) = s.parentSpecId

    @Field
    fun sortOrder(s: Spec) = s.sortOrder

    @Field
    fun childCount(s: Spec) = s.childCount

    @Field
    fun childDoneCount(s: Spec) = s.childDoneCount

    @Field
    fun gitRepositoryId(s: Spec) = s.gitRepositoryId

    @Field
    fun gitPath(s: Spec) = s.gitPath

    @Field
    fun watcherProfileIds(s: Spec) = s.watcherProfileIds

    @Field
    fun labelIds(s: Spec) = s.labelIds

    @Field
    fun deletedAt(s: Spec) = s.deletedAt

    @Field
    fun createdAt(s: Spec) = s.createdAt

    @Field
    fun modifiedAt(s: Spec) = s.modifiedAt

    @Field
    fun createdByPrincipalId(s: Spec) = s.createdByPrincipalId

    @Field
    fun modifiedByPrincipalId(s: Spec) = s.modifiedByPrincipalId

    @Field
    fun version(s: Spec) = s.version

    @Field
    suspend fun metadata(authentication: AuthenticationContext?, spec: Spec): Metadata? {
        val metadata = metadataService.getById(spec.metadataId) ?: return null
        return if (metadataPermissions.isAllowed(authentication, metadata, PermissionAction.VIEW)) metadata else null
    }

    @Field
    suspend fun parentSpec(spec: Spec): Spec? =
        spec.parentSpecId?.let { specService.getById(it) }

    @Field
    suspend fun children(spec: Spec, offset: Long, limit: Int): List<Spec> =
        specService.listChildren(spec.id, offset, limit)

    @Field
    suspend fun project(spec: Spec): Project? =
        spec.projectId?.let { projectService.getById(it) }

    @Field
    suspend fun status(spec: Spec): Status =
        statusService.getById(spec.statusId)
            ?: error("Status ${spec.statusId} missing for spec ${spec.key}")

    @Field
    suspend fun owner(authentication: AuthenticationContext, spec: Spec): Profile? {
        val profile = profileService.getById(spec.ownerProfileId)
        return if (profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW)) profile else null
    }

    @Field
    suspend fun transitions(spec: Spec, currentOnly: Boolean): List<WorkflowTransition> {
        val resolution = workflowService.resolveWorkflowForSpec(spec)
        return if (currentOnly) resolution.transitions.matching(resolution.currentState.id)
        else resolution.transitions
    }

    @Field
    suspend fun history(spec: Spec, offset: Long, limit: Int): List<SpecHistoryEntry> =
        specService.listHistory(spec.id, offset, limit)

    @Field
    suspend fun requirements(spec: Spec, offset: Long, limit: Int): List<Requirement> =
        requirementService.listByParent(RequirementParent.SPEC, spec.id, offset, limit)

    @Field
    suspend fun requirementCount(spec: Spec): Long =
        requirementService.countByParent(RequirementParent.SPEC, spec.id)

    @Field
    suspend fun contexts(spec: Spec): List<SpecContext> =
        contextService.listBySpec(spec.id)

    @Field
    suspend fun taskGenerations(spec: Spec, offset: Long, limit: Int): List<SpecTaskGeneration> =
        generationRepository.listBySpec(spec.id, offset, limit)

    private suspend fun resolveViewingProfileId(authentication: AuthenticationContext?): UUID? {
        val context = authentication ?: return null
        val authenticated = context.principal() ?: return null
        val principal = authenticated.asPrincipal()
        return principal.primaryProfileId
            ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id
    }

    @Field
    suspend fun comments(
        spec: Spec,
        authentication: AuthenticationContext?,
        offset: Long,
        limit: Long,
    ): List<SpecComment> {
        val viewingProfileId = resolveViewingProfileId(authentication)
        return if (viewingProfileId != null) {
            commentService.listForProfile(spec.id, viewingProfileId, offset, limit)
        } else {
            commentService.listPublic(spec.id, offset, limit)
        }
    }
}

object WorkOpsSpecs

@TypeController
class SpecQueryController(
    private val specService: SpecService,
    private val projectService: ProjectService,
    private val programService: bosca.workops.service.ProgramService,
    private val permissionEvaluator: SpecPermissionEvaluator,
    private val projectPermissionEvaluator: ProjectPermissionEvaluator,
    private val programPermissionEvaluator: bosca.workops.service.ProgramPermissionEvaluator,
) : GraphQLController<WorkOpsSpecs> {

    @Field
    suspend fun spec(authentication: AuthenticationContext, id: UUID): Spec? {
        val spec = specService.getById(id) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, spec, PermissionAction.VIEW)) spec else null
    }

    @Field
    suspend fun specByKey(authentication: AuthenticationContext, key: String): Spec? {
        val spec = specService.getByKey(key) ?: return null
        return if (permissionEvaluator.isAllowed(authentication, spec, PermissionAction.VIEW)) spec else null
    }

    @Field
    suspend fun byProject(authentication: AuthenticationContext, projectId: UUID, offset: Long, limit: Int): List<Spec> {
        val project = projectService.getById(projectId) ?: return emptyList()
        projectPermissionEvaluator.verifyAllowed(authentication, project, PermissionAction.VIEW)
        val specs = specService.listByProject(projectId, offset, limit)
        return permissionEvaluator.filterAllowed(authentication, specs, PermissionAction.VIEW)
    }

    @Field
    suspend fun byProgram(authentication: AuthenticationContext, programId: UUID, offset: Long, limit: Int): List<Spec> {
        val program = programService.getById(programId) ?: return emptyList()
        programPermissionEvaluator.verifyAllowed(authentication, program, PermissionAction.VIEW)
        val specs = specService.listByProgram(programId, offset, limit)
        return permissionEvaluator.filterAllowed(authentication, specs, PermissionAction.VIEW)
    }

    @Field
    suspend fun byOwner(authentication: AuthenticationContext, ownerProfileId: UUID, offset: Long, limit: Int): List<Spec> {
        val specs = specService.listByOwner(ownerProfileId, offset, limit)
        return permissionEvaluator.filterAllowed(authentication, specs, PermissionAction.VIEW)
    }

    @Field
    suspend fun children(authentication: AuthenticationContext, parentSpecId: UUID, offset: Long, limit: Int): List<Spec> {
        val parent = specService.getById(parentSpecId) ?: return emptyList()
        permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.VIEW)
        val specs = specService.listChildren(parentSpecId, offset, limit)
        return permissionEvaluator.filterAllowed(authentication, specs, PermissionAction.VIEW)
    }
}

object WorkOpsSpecsMutation

@TypeController
class SpecMutationController(
    private val specService: SpecService,
    private val contextService: SpecContextService,
    private val gitSyncService: SpecGitSyncService,
    private val projectService: ProjectService,
    private val profileService: ProfileService,
    private val permissionEvaluator: SpecPermissionEvaluator,
    private val projectPermissionEvaluator: ProjectPermissionEvaluator,
) : GraphQLController<WorkOpsSpecsMutation> {

    private suspend fun resolveProfileId(authenticated: bosca.security.model.AuthenticatedPrincipal): UUID {
        val principal = authenticated.asPrincipal()
        principal.primaryProfileId?.let { return it }
        val profiles = profileService.getByPrincipal(principal.id)
        return profiles.firstOrNull()?.id
            ?: error("principal ${principal.id} has no profiles")
    }

    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateSpecInput): Spec {
        val projectId = input.projectId
        if (projectId != null) {
            val project = projectService.getById(projectId)
                ?: error("createSpec requires an existing project $projectId")
            projectPermissionEvaluator.verifyAllowed(authentication, project, PermissionAction.EDIT)
        }
        val authenticated = authentication.principal()
            ?: error("createSpec requires an authenticated principal")
        val profileId = resolveProfileId(authenticated)
        return specService.create(
            input = input,
            actingPrincipalId = authenticated.id,
            actingProfileId = profileId,
            ownerProfileId = profileId,
        )
    }

    @Field
    suspend fun update(authentication: AuthenticationContext, id: UUID, input: UpdateSpecInput): Spec {
        val spec = specService.getById(id) ?: error("updateSpec: spec $id not found")
        permissionEvaluator.verifyAllowed(authentication, spec, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("updateSpec requires an authenticated principal")
        return specService.update(id, input, authenticated.id, resolveProfileId(authenticated))
    }

    @Field
    suspend fun softDelete(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Spec {
        val spec = specService.getById(id) ?: error("softDeleteSpec: spec $id not found")
        permissionEvaluator.verifyAllowed(authentication, spec, PermissionAction.DELETE)
        val authenticated = authentication.principal()
            ?: error("softDeleteSpec requires an authenticated principal")
        return specService.softDelete(id, expectedVersion, authenticated.id, resolveProfileId(authenticated))
    }

    @Field
    suspend fun restore(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): Spec {
        val spec = specService.getById(id) ?: error("restoreSpec: spec $id not found")
        permissionEvaluator.verifyAllowed(authentication, spec, PermissionAction.DELETE)
        val authenticated = authentication.principal()
            ?: error("restoreSpec requires an authenticated principal")
        return specService.restore(id, expectedVersion, authenticated.id, resolveProfileId(authenticated))
    }

    @Field
    suspend fun addContext(authentication: AuthenticationContext, specId: UUID, input: bosca.workops.model.spec.CreateSpecContextInput): SpecContext {
        val spec = specService.getById(specId) ?: error("addSpecContext: spec $specId not found")
        permissionEvaluator.verifyAllowed(authentication, spec, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("addSpecContext requires an authenticated principal")
        val profileId = resolveProfileId(authenticated)
        return contextService.add(specId, input, authenticated.id, profileId)
    }

    @Field
    suspend fun removeContext(authentication: AuthenticationContext, specId: UUID, contextId: UUID): Boolean {
        val spec = specService.getById(specId) ?: error("removeSpecContext: spec $specId not found")
        permissionEvaluator.verifyAllowed(authentication, spec, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("removeSpecContext requires an authenticated principal")
        contextService.remove(specId, contextId, authenticated.id, resolveProfileId(authenticated))
        return true
    }

    @Field
    suspend fun generateTasks(
        authentication: AuthenticationContext,
        specId: UUID,
        metadataVersion: Int,
        source: bosca.workops.model.spec.GenerationSource,
        agentSessionId: UUID? = null,
    ): bosca.workops.model.spec.SpecTaskGeneration {
        val spec = specService.getById(specId) ?: error("generateTasks: spec $specId not found")
        permissionEvaluator.verifyAllowed(authentication, spec, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("generateTasks requires an authenticated principal")
        return specService.generateTasks(
            specId = specId,
            metadataVersion = metadataVersion,
            source = source,
            actingPrincipalId = authenticated.id,
            actingProfileId = resolveProfileId(authenticated),
            agentSessionId = agentSessionId,
        )
    }

    @Field
    suspend fun transition(
        authentication: AuthenticationContext,
        id: UUID,
        transitionId: UUID,
        expectedVersion: Long,
        resolutionId: UUID? = null,
    ): Spec {
        val spec = specService.getById(id) ?: error("transitionSpec: spec $id not found")
        permissionEvaluator.verifyAllowed(authentication, spec, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("transitionSpec requires an authenticated principal")
        val transitioned = specService.transition(
            id = id,
            transitionId = transitionId,
            expectedVersion = expectedVersion,
            actingPrincipalId = authenticated.id,
            actingProfileId = resolveProfileId(authenticated),
            resolutionId = resolutionId,
        )
        return transitioned
    }

    @Field
    suspend fun pushToGit(
        authentication: AuthenticationContext,
        specId: UUID,
        content: String,
        authorName: String,
        authorEmail: String,
    ): String? {
        val spec = specService.getById(specId) ?: error("pushToGit: spec $specId not found")
        permissionEvaluator.verifyAllowed(authentication, spec, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("pushToGit requires an authenticated principal")
        return gitSyncService.pushToGit(spec, content, authenticated.id, authorName, authorEmail)
    }

    @Field
    suspend fun pullFromGit(authentication: AuthenticationContext, specId: UUID, commitSha: String): Spec? {
        val spec = specService.getById(specId) ?: error("pullFromGit: spec $specId not found")
        permissionEvaluator.verifyAllowed(authentication, spec, PermissionAction.EDIT)
        val authenticated = authentication.principal()
            ?: error("pullFromGit requires an authenticated principal")
        return gitSyncService.pullFromGit(specId, commitSha, authenticated.id, resolveProfileId(authenticated))
    }

}

@TypeController(type = "WorkOpsSpecContext")
class SpecContextTypeController : GraphQLController<SpecContext> {

    @Field
    fun id(c: SpecContext) = c.id

    @Field
    fun specId(c: SpecContext) = c.specId

    @Field
    fun contextType(c: SpecContext) = c.contextType

    @Field
    fun targetId(c: SpecContext) = c.targetId

    @Field
    fun label(c: SpecContext) = c.label

    @Field
    fun attributes(c: SpecContext) = c.attributes

    @Field
    fun addedByProfileId(c: SpecContext) = c.addedByProfileId

    @Field
    fun createdAt(c: SpecContext) = c.createdAt
}

@TypeController(type = "WorkOpsSpecHistoryEntry")
class SpecHistoryEntryTypeController(
    private val json: Json,
) : GraphQLController<SpecHistoryEntry> {

    @Field
    fun id(e: SpecHistoryEntry) = e.id

    @Field
    fun specId(e: SpecHistoryEntry) = e.specId

    @Field
    fun changedAt(e: SpecHistoryEntry) = e.changedAt

    @Field
    fun changedByPrincipalId(e: SpecHistoryEntry) = e.changedByPrincipalId

    @Field
    fun changedByProfileId(e: SpecHistoryEntry) = e.changedByProfileId

    @Field
    fun changes(entry: SpecHistoryEntry): List<FieldChange> =
        json.decodeFromJsonElement(ListSerializer(FieldChange.serializer()), entry.changes)
}

@TypeController(type = "WorkOpsSpecTaskGeneration")
class SpecTaskGenerationTypeController : GraphQLController<SpecTaskGeneration> {

    @Field
    fun id(g: SpecTaskGeneration) = g.id

    @Field
    fun specId(g: SpecTaskGeneration) = g.specId

    @Field
    fun metadataVersion(g: SpecTaskGeneration) = g.metadataVersion

    @Field
    fun source(g: SpecTaskGeneration) = g.source

    @Field
    fun agentSessionId(g: SpecTaskGeneration) = g.agentSessionId

    @Field
    fun generatedTaskIds(g: SpecTaskGeneration) = g.generatedTaskIds

    @Field
    fun createdAt(g: SpecTaskGeneration) = g.createdAt

    @Field
    fun createdByPrincipalId(g: SpecTaskGeneration) = g.createdByPrincipalId
}

@TypeController(type = "WorkOpsRequirementHistoryEntry")
class RequirementHistoryEntryTypeController(
    private val json: Json,
) : GraphQLController<RequirementHistoryEntry> {

    @Field
    fun id(e: RequirementHistoryEntry) = e.id

    @Field
    fun requirementId(e: RequirementHistoryEntry) = e.requirementId

    @Field
    fun changedAt(e: RequirementHistoryEntry) = e.changedAt

    @Field
    fun changedByPrincipalId(e: RequirementHistoryEntry) = e.changedByPrincipalId

    @Field
    fun changedByProfileId(e: RequirementHistoryEntry) = e.changedByProfileId

    @Field
    fun changes(entry: RequirementHistoryEntry): List<FieldChange> =
        json.decodeFromJsonElement(ListSerializer(FieldChange.serializer()), entry.changes)
}
