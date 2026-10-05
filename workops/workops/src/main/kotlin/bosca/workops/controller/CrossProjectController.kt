package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.git.service.RepositoryAccessEvaluator
import bosca.git.service.RepositoryService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.project.Project
import bosca.workops.model.release.Release
import bosca.workops.model.release.ReleasePipelineInput
import bosca.workops.model.release.ReleasePipelinePlan
import bosca.workops.model.release.ReleasePipelinePlanJob
import bosca.workops.model.release.ReleasePipelinePlanStep
import bosca.workops.model.release.ReleaseProjectVersion
import bosca.workops.model.release.ReleaseRunView
import bosca.workops.model.release.TaskAffectedProject
import bosca.workops.model.version.Version
import bosca.workops.service.CrossProjectMoveService
import bosca.workops.service.EnvironmentService
import bosca.workops.service.EnvironmentPermissionEvaluator
import bosca.workops.service.MoveWorkOpsTaskInput
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.ReleasePipelineService
import bosca.workops.service.ReleaseService
import bosca.workops.service.TaskAffectedProjectService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import bosca.workops.service.VersionService
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class CreateReleaseInput(
    @Contextual val programId: UUID,
    val name: String,
    val description: String? = null,
    @Contextual val releaseDate: OffsetDateTime? = null,
    @Contextual val ownerProfileId: UUID? = null,
)

@Serializable
data class MoveTaskInput(
    @Contextual val targetProjectId: UUID,
    @Contextual val statusMapping: JsonElement,
    @Contextual val fieldMapping: JsonElement? = null,
)

@Serializable
data class UpdateReleaseInput(
    val name: String,
    val description: String? = null,
    @Contextual val releaseDate: OffsetDateTime? = null,
)

@Serializable
data class PromoteReleaseInput(
    val environment: String,
    val allowDowngrade: Boolean = false,
    val inputs: JsonElement? = null,
)

@Serializable
data class StartPatchReleaseInput(
    val projectIds: List<@Contextual UUID>,
)

data class ReleaseRepositoryAccess(val repositoryId: UUID, val canEdit: Boolean, val canExecute: Boolean)
data class ReleaseEnvironmentAccess(val key: String, val canExecute: Boolean)
data class ReleaseActionAccess(
    val canManage: Boolean,
    val repositories: List<ReleaseRepositoryAccess>,
    val environments: List<ReleaseEnvironmentAccess>,
)

@TypeController(type = "WorkOpsRelease")
class ReleaseTypeController(
    private val environmentService: EnvironmentService,
) : GraphQLController<Release> {
    @Field fun id(r: Release) = r.id
    @Field fun programId(r: Release) = r.programId
    @Field fun name(r: Release) = r.name
    @Field fun description(r: Release) = r.description
    @Field fun releaseDate(r: Release) = r.releaseDate
    @Field fun releasedAt(r: Release) = r.releasedAt
    @Field fun ownerProfileId(r: Release) = r.ownerProfileId
    @Field fun version(r: Release) = r.version

    /** The release's live "what artifact/version is in what environment" view — every environment
     *  deployment stamped with this release id by git-ci deploy and promotion actions. */
    @Field suspend fun environmentDeployments(r: Release): List<EnvironmentDeployment> =
        environmentService.deploymentsByRelease(r.id)
}

@TypeController(type = "WorkOpsReleaseProjectVersion")
class ReleaseProjectVersionTypeController(
    private val projectService: ProjectService,
    private val versionService: VersionService,
) : GraphQLController<ReleaseProjectVersion> {
    @Field fun releaseId(r: ReleaseProjectVersion) = r.releaseId
    @Field fun projectId(r: ReleaseProjectVersion) = r.projectId
    @Field fun versionId(r: ReleaseProjectVersion) = r.versionId
    @Field fun deploymentOrder(r: ReleaseProjectVersion) = r.deploymentOrder
    @Field fun deploymentStatus(r: ReleaseProjectVersion) = r.deploymentStatus
    @Field fun deployedAt(r: ReleaseProjectVersion) = r.deployedAt
    @Field fun deployedByPrincipalId(r: ReleaseProjectVersion) = r.deployedByPrincipalId
    @Field fun rollbackVersionId(r: ReleaseProjectVersion) = r.rollbackVersionId

    /** Resolved project (name/key), so callers don't have to render a raw UUID. */
    @Field suspend fun project(r: ReleaseProjectVersion): Project? = projectService.getById(r.projectId)

    /** Resolved version (name), so callers don't have to render a raw UUID. */
    @Field suspend fun version(r: ReleaseProjectVersion): Version? = versionService.getById(r.versionId)
}

@TypeController(type = "WorkOpsReleaseRun")
class ReleaseRunTypeController : GraphQLController<ReleaseRunView> {
    @Field fun runId(r: ReleaseRunView) = r.runId
    @Field fun status(r: ReleaseRunView) = r.status
    @Field fun startedAt(r: ReleaseRunView) = r.startedAt
    @Field fun finishedAt(r: ReleaseRunView) = r.finishedAt
    @Field fun durationMs(r: ReleaseRunView) = r.durationMs
}

@TypeController(type = "WorkOpsReleasePipelineInput")
class ReleasePipelineInputTypeController : GraphQLController<ReleasePipelineInput> {
    @Field fun name(input: ReleasePipelineInput) = input.name
    @Field fun type(input: ReleasePipelineInput) = input.type
    @Field fun defaultValue(input: ReleasePipelineInput) = input.defaultValue
    @Field fun description(input: ReleasePipelineInput) = input.description
    @Field fun options(input: ReleasePipelineInput) = input.options
    @Field fun required(input: ReleasePipelineInput) = input.required
}

@TypeController(type = "WorkOpsReleasePipelinePlanStep")
class ReleasePipelinePlanStepTypeController : GraphQLController<ReleasePipelinePlanStep> {
    @Field fun name(step: ReleasePipelinePlanStep) = step.name
    @Field fun action(step: ReleasePipelinePlanStep) = step.action
}

@TypeController(type = "WorkOpsReleasePipelinePlanJob")
class ReleasePipelinePlanJobTypeController : GraphQLController<ReleasePipelinePlanJob> {
    @Field fun key(job: ReleasePipelinePlanJob) = job.key
    @Field fun environment(job: ReleasePipelinePlanJob) = job.environment
    @Field fun approvalRequired(job: ReleasePipelinePlanJob) = job.approvalRequired
    @Field fun needs(job: ReleasePipelinePlanJob) = job.needs
    @Field fun requirements(job: ReleasePipelinePlanJob) = job.requirements
    @Field fun steps(job: ReleasePipelinePlanJob) = job.steps
    @Field fun depth(job: ReleasePipelinePlanJob) = job.depth
}

@TypeController(type = "WorkOpsReleasePipelinePlan")
class ReleasePipelinePlanTypeController : GraphQLController<ReleasePipelinePlan> {
    @Field fun pipelineId(plan: ReleasePipelinePlan) = plan.pipelineId
    @Field fun pipelineName(plan: ReleasePipelinePlan) = plan.pipelineName
    @Field fun repositoryId(plan: ReleasePipelinePlan) = plan.repositoryId
    @Field fun repositorySlug(plan: ReleasePipelinePlan) = plan.repositorySlug
    @Field fun triggerType(plan: ReleasePipelinePlan) = plan.triggerType
    @Field fun environment(plan: ReleasePipelinePlan) = plan.environment
    @Field fun promotesFrom(plan: ReleasePipelinePlan) = plan.promotesFrom
    @Field fun inputs(plan: ReleasePipelinePlan) = plan.inputs
    @Field fun jobs(plan: ReleasePipelinePlan) = plan.jobs
}

@TypeController(type = "WorkOpsReleaseRepositoryAccess")
class ReleaseRepositoryAccessTypeController : GraphQLController<ReleaseRepositoryAccess> {
    @Field fun repositoryId(access: ReleaseRepositoryAccess) = access.repositoryId
    @Field fun canEdit(access: ReleaseRepositoryAccess) = access.canEdit
    @Field fun canExecute(access: ReleaseRepositoryAccess) = access.canExecute
}

@TypeController(type = "WorkOpsReleaseEnvironmentAccess")
class ReleaseEnvironmentAccessTypeController : GraphQLController<ReleaseEnvironmentAccess> {
    @Field fun key(access: ReleaseEnvironmentAccess) = access.key
    @Field fun canExecute(access: ReleaseEnvironmentAccess) = access.canExecute
}

@TypeController(type = "WorkOpsReleaseActionAccess")
class ReleaseActionAccessTypeController : GraphQLController<ReleaseActionAccess> {
    @Field fun canManage(access: ReleaseActionAccess) = access.canManage
    @Field fun repositories(access: ReleaseActionAccess) = access.repositories
    @Field fun environments(access: ReleaseActionAccess) = access.environments
}

@TypeController(type = "WorkOpsTaskAffectedProject")
class TaskAffectedProjectTypeController : GraphQLController<TaskAffectedProject> {
    @Field fun taskId(a: TaskAffectedProject) = a.taskId
    @Field fun projectId(a: TaskAffectedProject) = a.projectId
    @Field fun addedAt(a: TaskAffectedProject) = a.addedAt
}

object WorkOpsCrossProjectQuery

@TypeController
class CrossProjectQueryController(
    private val releaseService: ReleaseService,
    private val releasePipelineService: ReleasePipelineService,
    private val affectedService: TaskAffectedProjectService,
    private val programService: ProgramService,
    private val programPermissions: ProgramPermissionEvaluator,
    private val environmentService: EnvironmentService,
    private val environmentPermissions: EnvironmentPermissionEvaluator,
    private val repositoryService: RepositoryService,
    private val repositoryPermissions: RepositoryAccessEvaluator,
    private val taskService: TaskService,
    private val taskPermissions: TaskPermissionEvaluator,
) : GraphQLController<WorkOpsCrossProjectQuery> {

    @Field
    suspend fun releases(authentication: AuthenticationContext, programId: UUID): List<Release> {
        val program = programService.getById(programId) ?: return emptyList()
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        return releaseService.list(programId)
    }

    @Field
    suspend fun release(authentication: AuthenticationContext, id: UUID): Release? {
        val release = releaseService.getById(id) ?: return null
        val program = programService.getById(release.programId) ?: return null
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        return release
    }

    @Field
    suspend fun versionsForRelease(authentication: AuthenticationContext, releaseId: UUID): List<ReleaseProjectVersion> {
        val release = releaseService.getById(releaseId) ?: return emptyList()
        val program = programService.getById(release.programId) ?: return emptyList()
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        return releaseService.listVersions(releaseId)
    }

    @Field
    suspend fun releaseChannelArtifactTypes(authentication: AuthenticationContext, releaseId: UUID): List<String> {
        val release = releaseService.getById(releaseId) ?: return emptyList()
        val program = programService.getById(release.programId) ?: return emptyList()
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        return releasePipelineService.releaseChannelArtifactTypes(releaseId)
    }

    @Field
    suspend fun releaseDeclaredArtifacts(authentication: AuthenticationContext, releaseId: UUID): List<bosca.workops.model.artifact.ReleaseDeclaredArtifact> {
        val release = releaseService.getById(releaseId) ?: return emptyList()
        val program = programService.getById(release.programId) ?: return emptyList()
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        return releasePipelineService.releaseDeclaredArtifacts(releaseId)
    }

    @Field
    suspend fun releaseReadiness(authentication: AuthenticationContext, releaseId: UUID): List<String> {
        val release = releaseService.getById(releaseId) ?: return emptyList()
        val program = programService.getById(release.programId) ?: return emptyList()
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        return releasePipelineService.dependencyViolations(releaseId)
    }

    @Field
    suspend fun releaseRuns(authentication: AuthenticationContext, releaseId: UUID): List<ReleaseRunView> {
        val release = releaseService.getById(releaseId) ?: return emptyList()
        val program = programService.getById(release.programId) ?: return emptyList()
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        return releasePipelineService.runsForRelease(releaseId, RELEASE_RUNS_LIMIT)
    }

    @Field
    suspend fun releasePlans(authentication: AuthenticationContext, releaseId: UUID): List<ReleasePipelinePlan> {
        val release = releaseService.getById(releaseId) ?: return emptyList()
        val program = programService.getById(release.programId) ?: return emptyList()
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        return releasePipelineService.plansForRelease(releaseId)
    }

    @Field
    suspend fun releaseActionAccess(authentication: AuthenticationContext, releaseId: UUID): ReleaseActionAccess? {
        val release = releaseService.getById(releaseId) ?: return null
        val program = programService.getById(release.programId) ?: return null
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        val repositories = releasePipelineService.repositoryIdsForRelease(releaseId).mapNotNull { repositoryId ->
            val repository = repositoryService.findById(repositoryId) ?: return@mapNotNull null
            ReleaseRepositoryAccess(
                repositoryId,
                repositoryPermissions.isAllowed(authentication, repository, PermissionAction.EDIT),
                repositoryPermissions.isAllowed(authentication, repository, PermissionAction.EXECUTE),
            )
        }
        val environments = environmentService.listByProgram(release.programId).map { environment ->
            ReleaseEnvironmentAccess(
                environment.key,
                environmentPermissions.isAllowed(authentication, environment, PermissionAction.EXECUTE),
            )
        }
        return ReleaseActionAccess(
            canManage = programPermissions.isAllowed(authentication, program, PermissionAction.MANAGE),
            repositories = repositories,
            environments = environments,
        )
    }

    @Field
    suspend fun affectedProjects(authentication: AuthenticationContext, taskId: UUID): List<TaskAffectedProject> {
        val task = taskService.getById(taskId) ?: return emptyList()
        taskPermissions.verifyAllowed(authentication, task, PermissionAction.VIEW)
        return affectedService.listForTask(taskId)
    }

    private companion object {
        /** How many of a release's native git-ci runs the dashboard lists (newest first). */
        const val RELEASE_RUNS_LIMIT = 20
    }
}

object WorkOpsCrossProjectMutation

@TypeController
class CrossProjectMutationController(
    private val releaseService: ReleaseService,
    private val releasePipelineService: ReleasePipelineService,
    private val affectedService: TaskAffectedProjectService,
    private val moveService: CrossProjectMoveService,
    private val programService: ProgramService,
    private val projectService: ProjectService,
    private val taskService: TaskService,
    private val environmentService: EnvironmentService,
    private val environmentPermissions: EnvironmentPermissionEvaluator,
    private val repositoryService: RepositoryService,
    private val repositoryPermissions: RepositoryAccessEvaluator,
    private val programPermissions: ProgramPermissionEvaluator,
    private val projectPermissions: ProjectPermissionEvaluator,
    private val taskPermissions: TaskPermissionEvaluator,
    private val json: Json,
) : GraphQLController<WorkOpsCrossProjectMutation> {

    @Field
    suspend fun createRelease(
        authentication: AuthenticationContext,
        input: CreateReleaseInput,
    ): Release {
        val program = programService.getById(input.programId)
            ?: error("Program ${input.programId} not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        return releaseService.create(
            programId = input.programId,
            name = input.name,
            description = input.description,
            releaseDate = input.releaseDate,
            ownerProfileId = input.ownerProfileId,
        )
    }

    @Field
    suspend fun updateRelease(
        authentication: AuthenticationContext,
        id: UUID,
        input: UpdateReleaseInput,
        expectedVersion: Long,
    ): Release {
        val release = releaseService.getById(id) ?: error("Release $id not found")
        val program = programService.getById(release.programId) ?: error("Program ${release.programId} not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        return releaseService.update(id, input.name, input.description, input.releaseDate, expectedVersion)
    }

    @Field
    suspend fun deleteRelease(authentication: AuthenticationContext, id: UUID): Boolean {
        val release = releaseService.getById(id) ?: return false
        val program = programService.getById(release.programId) ?: error("Program ${release.programId} not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        releaseService.delete(id)
        return true
    }

    @Field
    suspend fun bundleVersion(
        authentication: AuthenticationContext,
        releaseId: UUID,
        projectId: UUID,
        versionId: UUID,
    ): Boolean {
        val release = releaseService.getById(releaseId)
            ?: error("Release $releaseId not found")
        val program = programService.getById(release.programId)
            ?: error("Program ${release.programId} not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        releaseService.bundle(releaseId, projectId, versionId)
        return true
    }

    @Field
    suspend fun unbundleVersion(
        authentication: AuthenticationContext,
        releaseId: UUID,
        versionId: UUID,
    ): Boolean {
        val release = releaseService.getById(releaseId)
            ?: error("Release $releaseId not found")
        val program = programService.getById(release.programId)
            ?: error("Program ${release.programId} not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        releaseService.unbundle(releaseId, versionId)
        return true
    }

    @Field
    suspend fun markReleased(
        authentication: AuthenticationContext,
        releaseId: UUID,
        expectedVersion: Long,
    ): Release {
        val release = releaseService.getById(releaseId)
            ?: error("Release $releaseId not found")
        val program = programService.getById(release.programId)
            ?: error("Program ${release.programId} not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        return releaseService.release(releaseId, expectedVersion)
    }

    @Field
    suspend fun launchRelease(
        authentication: AuthenticationContext,
        releaseId: UUID,
        inputs: JsonElement? = null,
    ): Boolean {
        val release = releaseService.getById(releaseId)
            ?: error("Release $releaseId not found")
        val program = programService.getById(release.programId)
            ?: error("Program ${release.programId} not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        verifyRepositoryExecution(authentication, releaseId)
        return releasePipelineService.launch(releaseId, scalarInputs(inputs, "Release"), authentication)
    }

    @Field
    suspend fun promoteRelease(
        authentication: AuthenticationContext,
        releaseId: UUID,
        input: PromoteReleaseInput,
    ): Boolean {
        val release = releaseService.getById(releaseId)
            ?: error("Release $releaseId not found")
        val program = programService.getById(release.programId)
            ?: error("Program ${release.programId} not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        verifyRepositoryExecution(authentication, releaseId)
        val requestedTarget = input.environment.trim()
        val environment = environmentService.getByProgramAndKey(program.id, requestedTarget)
            ?: environmentService.getByProgramAndName(program.id, requestedTarget)
            ?: error("Environment '$requestedTarget' not found in program ${program.key}")
        environmentPermissions.verifyAllowed(authentication, environment, PermissionAction.EXECUTE)
        val parameters = scalarInputs(input.inputs, "Promotion")
        return releasePipelineService.promote(
            releaseId = releaseId,
            environment = environment.key,
            allowDowngrade = input.allowDowngrade,
            inputs = parameters,
            authentication = authentication,
        )
    }

    /** Release orchestration requires repository write and build execution grants in every repository. */
    private suspend fun verifyRepositoryExecution(authentication: AuthenticationContext, releaseId: UUID) {
        releasePipelineService.repositoryIdsForRelease(releaseId)
            .forEach { repositoryId ->
                val repository = repositoryService.findById(repositoryId)
                    ?: error("Repository $repositoryId in release $releaseId not found")
                if (!repositoryPermissions.isAllowed(authentication, repository, PermissionAction.EDIT)) {
                    throw SecurityException("EDIT access denied for repository ${repository.slug}")
                }
                if (!repositoryPermissions.isAllowed(authentication, repository, PermissionAction.EXECUTE)) {
                    throw SecurityException("EXECUTE access denied for repository ${repository.slug}")
                }
            }
    }

    @Field
    suspend fun startPatchRelease(
        authentication: AuthenticationContext,
        releaseId: UUID,
        input: StartPatchReleaseInput,
    ): Release {
        val release = releaseService.getById(releaseId)
            ?: error("Release $releaseId not found")
        val program = programService.getById(release.programId)
            ?: error("Program ${release.programId} not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        return releasePipelineService.startPatchRelease(releaseId, input.projectIds, authentication)
    }

    @Field
    suspend fun rollbackReleaseArtifacts(authentication: AuthenticationContext, releaseId: UUID): Boolean {
        val release = releaseService.getById(releaseId)
            ?: error("Release $releaseId not found")
        val program = programService.getById(release.programId)
            ?: error("Program ${release.programId} not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        return releasePipelineService.rollbackArtifacts(releaseId, authentication)
    }

    @Field
    suspend fun rollbackReleaseEnvironment(
        authentication: AuthenticationContext,
        releaseId: UUID,
        environmentKey: String,
        toRevision: Int,
    ): List<String> {
        val release = releaseService.getById(releaseId)
            ?: error("Release $releaseId not found")
        val program = programService.getById(release.programId)
            ?: error("Program ${release.programId} not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        return releasePipelineService.rollbackEnvironment(releaseId, environmentKey, toRevision, authentication)
    }

    private fun scalarInputs(element: JsonElement?, action: String): Map<String, String> =
        (element as? JsonObject).orEmpty().mapValues { (name, value) ->
            val primitive = value as? JsonPrimitive
                ?: error("$action input '$name' must be a scalar value")
            primitive.content
        }

    @Field
    suspend fun addAffectedProject(
        authentication: AuthenticationContext,
        taskId: UUID,
        projectId: UUID,
    ): Boolean {
        val task = taskService.getById(taskId)
            ?: error("Task $taskId not found")
        taskPermissions.verifyAllowed(authentication, task, PermissionAction.EDIT)
        affectedService.add(taskId, projectId)
        return true
    }

    @Field
    suspend fun removeAffectedProject(
        authentication: AuthenticationContext,
        taskId: UUID,
        projectId: UUID,
    ): Boolean {
        val task = taskService.getById(taskId)
            ?: error("Task $taskId not found")
        taskPermissions.verifyAllowed(authentication, task, PermissionAction.EDIT)
        affectedService.remove(taskId, projectId)
        return true
    }

    @Field
    suspend fun moveTaskToProject(
        authentication: AuthenticationContext,
        taskId: UUID,
        input: MoveTaskInput,
    ): UUID {
        val task = taskService.getById(taskId)
            ?: error("Task $taskId not found")
        taskPermissions.verifyAllowed(authentication, task, PermissionAction.MANAGE)
        val targetProject = projectService.getById(input.targetProjectId)
            ?: error("Target project ${input.targetProjectId} not found")
        projectPermissions.verifyAllowed(authentication, targetProject, PermissionAction.MANAGE)
        val authenticated = authentication.principal()
            ?: error("moveTaskToProject requires an authenticated principal")
        val statusMappingObj = input.statusMapping as? JsonObject ?: JsonObject(emptyMap())
        val typedStatusMapping = mutableMapOf<UUID, UUID>()
        for ((key, value) in statusMappingObj) {
            val source = runCatching { UUID.parse(key) }.getOrNull() ?: continue
            val primitive = value as? JsonPrimitive ?: continue
            val targetText = primitive.content
            val target = runCatching { UUID.parse(targetText) }.getOrNull() ?: continue
            typedStatusMapping[source] = target
        }
        val fieldMappingObj = input.fieldMapping as? JsonObject ?: JsonObject(emptyMap())
        val typedFieldMapping = mutableMapOf<String, String>()
        for ((key, value) in fieldMappingObj) {
            val primitive = value as? JsonPrimitive ?: continue
            typedFieldMapping[key] = primitive.content
        }
        val moved = moveService.moveTaskToProject(
            taskId = taskId,
            input = MoveWorkOpsTaskInput(
                targetProjectId = input.targetProjectId,
                statusMapping = typedStatusMapping,
                fieldMapping = typedFieldMapping,
            ),
            actingPrincipalId = authenticated.id,
        )
        return moved.id
    }
}
