package bosca.workops.service

import bosca.db.transaction
import bosca.git.model.PipelineDefinition
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineTriggerType
import bosca.git.model.TriggerInput
import bosca.git.service.PipelineRunService
import bosca.git.service.ReleaseDeployer
import bosca.git.service.ReleaseRollbackRequest
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.dependency.DependencyType
import bosca.workops.model.release.Release
import bosca.workops.model.release.ReleasePipelineInput
import bosca.workops.model.release.ReleasePipelinePlan
import bosca.workops.model.release.ReleasePipelinePlanJob
import bosca.workops.model.release.ReleasePipelinePlanStep
import bosca.workops.model.release.ReleaseRunView
import bosca.workops.model.version.CreateVersionInput
import bosca.workops.deploy.DeployConfigService
import java.time.Duration

/**
 * Starts and promotes a WorkOps release by creating native git-ci runs from the YAML owned by its
 * bundled projects. Run correlation is explicit through the persisted `release.id` parameter.
 */
@ServiceImplementation
class ReleasePipelineServiceImpl(
    private val runService: PipelineRunService,
    private val releaseService: ReleaseService,
    private val dependencyService: DependencyDeclarationService,
    private val projectService: ProjectService,
    private val versionService: VersionService,
    private val publicationService: ArtifactPublicationService,
    private val projectRepositories: ProjectRepositoryService,
    private val repositoryWrite: bosca.git.service.RepositoryWriteService,
    private val gitPipelines: bosca.git.service.PipelineService,
    private val repositoryService: RepositoryService,
    private val repositoryBrowse: RepositoryBrowseService,
    private val environmentService: EnvironmentService,
    private val deployConfigService: DeployConfigService,
    private val releaseDeployer: ReleaseDeployer,
) : ReleasePipelineService {

    override suspend fun launch(
        releaseId: UUID,
        inputs: Map<String, String>,
        authentication: AuthenticationContext,
    ): Boolean {
        val release = releaseService.getById(releaseId) ?: return false
        verifyDependenciesComplete(releaseId)
        val actor = authentication.principal()
            ?: throw SecurityException("Starting a release requires an authenticated principal")
        val actorId = actor.id
        val context = launchContext(release)
        val parameters = inputs.mapKeys { (key, _) ->
            if (key.startsWith(INPUT_PARAMETER_PREFIX)) key else INPUT_PARAMETER_PREFIX + key
        }
        check(createTriggeredRuns(context, PipelineTriggerType.RELEASE, actorId, parameters) > 0) {
            "No RELEASE-triggered pipeline is attached to the projects in release ${release.name}"
        }
        return true
    }

    override suspend fun promote(
        releaseId: UUID,
        environment: String,
        allowDowngrade: Boolean,
        inputs: Map<String, String>,
        authentication: AuthenticationContext,
    ): Boolean {
        val release = releaseService.getById(releaseId) ?: return false
        val target = environment.trim()
        require(target.isNotEmpty()) { "A promotion target environment is required" }
        val actor = authentication.principal()
            ?: throw SecurityException("Promoting a release requires an authenticated principal")
        val actorId = actor.id
        val parameters = inputs.mapKeys { (key, _) ->
            if (key.startsWith(INPUT_PARAMETER_PREFIX)) key else INPUT_PARAMETER_PREFIX + key
        } + mapOf(
            PROMOTION_ENVIRONMENT_PARAMETER to target,
            ALLOW_DOWNGRADE_PARAMETER to allowDowngrade.toString(),
        )
        val context = launchContext(release)
        check(createTriggeredRuns(context, PipelineTriggerType.PROMOTION, actorId, parameters) > 0) {
            "No PROMOTION-triggered pipeline for '$target' is attached to release ${release.name}"
        }
        return true
    }

    override suspend fun startPatchRelease(
        releaseId: UUID,
        projectIds: List<UUID>,
        authentication: AuthenticationContext,
    ): Release {
        authentication.principal()
            ?: throw SecurityException("Starting a patch release requires an authenticated principal")
        val source = releaseService.getById(releaseId)
            ?: throw NoSuchElementException("Release $releaseId not found")
        val selectedIds = projectIds.distinct()
        require(selectedIds.isNotEmpty()) { "A patch release requires at least one affected project" }
        val sourceItems = releaseService.listVersions(releaseId).associateBy { it.projectId }
        val missing = selectedIds.filterNot { it in sourceItems }
        require(missing.isEmpty()) { "Projects ${missing.joinToString()} are not part of release ${source.name}" }

        return transaction {
            val patchVersions = selectedIds.map { projectId ->
                val sourceItem = sourceItems.getValue(projectId)
                val sourceVersion = versionService.getById(sourceItem.versionId)
                    ?: error("Version ${sourceItem.versionId} not found")
                val patchName = bumpPatch(sourceVersion.name)
                check(versionService.listByProject(projectId).none { it.name == patchName }) {
                    "Project $projectId already has version $patchName"
                }
                projectId to versionService.create(
                    CreateVersionInput(
                        projectId = projectId,
                        name = patchName,
                        description = "Patch of ${sourceVersion.name} for ${source.name}",
                    )
                )
            }
            val names = patchVersions.map { it.second.name }.distinct()
            val patch = releaseService.create(
                programId = source.programId,
                name = names.singleOrNull() ?: "${source.name} patch",
                description = "Roll-forward patch release for ${source.name}",
                releaseDate = null,
                ownerProfileId = source.ownerProfileId,
            )
            for ((projectId, version) in patchVersions) {
                releaseService.bundle(patch.id, projectId, version.id)
            }
            patch
        }
    }

    private suspend fun launchContext(release: Release): ReleaseLaunchContext {
        val items = releaseService.listVersions(release.id)
        check(items.isNotEmpty()) { "Release ${release.name} has no bundled project versions" }

        val versions = items.associateWith { item ->
            versionService.getById(item.versionId)
                ?: error("Version ${item.versionId} in release ${release.name} no longer exists")
        }
        val versionByRepository = mutableMapOf<UUID, String>()
        val repositories = items.flatMap { item ->
            projectRepositories.list(item.projectId).mapNotNull { link ->
                val repository = repositoryService.findById(link.repositoryId) ?: return@mapNotNull null
                val versionName = versions.getValue(item).name
                val prior = versionByRepository.putIfAbsent(repository.id, versionName)
                check(prior == null || prior == versionName) {
                    "Repository ${repository.slug} is linked to project versions $prior and $versionName in ${release.name}"
                }
                repository
            }
        }.distinctBy { it.id }
        check(repositories.isNotEmpty()) { "Release ${release.name} has no attached git repositories" }

        return ReleaseLaunchContext(
            release = release,
            repositoryIds = repositories.map { it.id },
            versionByRepository = versionByRepository,
            projectScope = repositories.map { it.slug }.distinct().sorted().joinToString(","),
        )
    }

    private suspend fun createTriggeredRuns(
        context: ReleaseLaunchContext,
        triggerType: PipelineTriggerType,
        actorId: UUID,
        additionalParameters: Map<String, String> = emptyMap(),
    ): Int {
        val prepared = mutableListOf<PreparedRun>()
        for (repositoryId in context.repositoryIds) {
            val repository = repositoryService.findById(repositoryId) ?: continue
            val ref = if (triggerType == PipelineTriggerType.PROMOTION) {
                "refs/tags/${context.versionByRepository.getValue(repositoryId)}"
            } else {
                "refs/heads/${repository.defaultBranch}"
            }
            val commitSha = repositoryBrowse.resolveRef(repository.id, ref)
                ?: error("Release ref '$ref' cannot be resolved in ${repository.slug}")
            for (pipeline in gitPipelines.findByRepository(repository.id)) {
                val definition = gitPipelines.parseDefinition(repository.id, commitSha, pipeline.filePath)
                    ?: error("Pipeline ${pipeline.filePath} cannot be parsed at $commitSha")
                if (!accepts(definition, triggerType, additionalParameters[PROMOTION_ENVIRONMENT_PARAMETER])) continue
                val trigger = definition.triggers.first { it.type == triggerType }
                val acceptedParameters = additionalParameters.filterKeys { key ->
                    !key.startsWith(INPUT_PARAMETER_PREFIX) || key.removePrefix(INPUT_PARAMETER_PREFIX) in trigger.inputs
                }
                val parameters = releaseParameters(context, repositoryId) + acceptedParameters
                // Validate every selected run before persisting any of them. This catches malformed
                // inputs and empty conditional plans in a later repository without leaving earlier
                // repositories dispatched; the transaction below covers persistence-time failures.
                runService.plan(definition, ref, triggerType, parameters)
                prepared += PreparedRun(
                    pipeline.id, repository.id, definition, commitSha, ref, parameters,
                )
            }
        }
        transaction {
            for (run in prepared) {
                runService.createRun(
                    pipelineId = run.pipelineId,
                    repositoryId = run.repositoryId,
                    definition = run.definition,
                    commitSha = run.commitSha,
                    ref = run.ref,
                    triggerType = triggerType,
                    triggeredBy = actorId,
                    parameters = run.parameters,
                )
            }
        }
        return prepared.size
    }

    override suspend fun plansForRelease(releaseId: UUID): List<ReleasePipelinePlan> {
        val release = releaseService.getById(releaseId) ?: return emptyList()
        val context = launchContext(release)
        val result = mutableListOf<ReleasePipelinePlan>()
        for (repositoryId in context.repositoryIds) {
            val repository = repositoryService.findById(repositoryId) ?: continue
            val ref = "refs/heads/${repository.defaultBranch}"
            val commitSha = repositoryBrowse.resolveRef(repository.id, ref)
                ?: error("Default branch '${repository.defaultBranch}' cannot be resolved in ${repository.slug}")
            for (pipeline in gitPipelines.findByRepository(repository.id)) {
                val definition = gitPipelines.parseDefinition(repository.id, commitSha, pipeline.filePath)
                    ?: error("Pipeline ${pipeline.filePath} cannot be parsed at $commitSha")
                for (triggerType in listOf(PipelineTriggerType.RELEASE, PipelineTriggerType.PROMOTION)) {
                    val trigger = definition.triggers.firstOrNull { it.type == triggerType } ?: continue
                    val environments = when (triggerType) {
                        PipelineTriggerType.RELEASE -> listOf<String?>(null)
                        PipelineTriggerType.PROMOTION ->
                            (trigger.environments.ifEmpty { definition.environments.keys.toList() })
                                .distinct()
                                .map { it }
                        else -> emptyList()
                    }
                    for (environment in environments) {
                        val parameters = releaseParameters(context, repositoryId) +
                            planningInputs(trigger.inputs) +
                            if (environment == null) emptyMap() else mapOf(
                                PROMOTION_ENVIRONMENT_PARAMETER to environment,
                                ALLOW_DOWNGRADE_PARAMETER to "false",
                            )
                        val execution = runService.plan(definition, ref, triggerType, parameters)
                        val depths = jobDepths(execution.jobs)
                        val jobs = execution.jobs.entries
                            .mapIndexed { index, (key, job) -> Triple(index, key, job) }
                            .sortedWith(compareBy<Triple<Int, String, bosca.git.model.JobDefinition>> {
                                depths.getValue(it.second)
                            }.thenBy { it.first })
                            .map { (_, key, job) ->
                                ReleasePipelinePlanJob(
                                    key = key,
                                    environment = job.environment,
                                    approvalRequired = job.approval,
                                    needs = job.needs,
                                    requirements = job.requires.map {
                                        "artifact ${it.type}:${it.namespace}:${it.coordinate}"
                                    } + job.pipelineRequires.map {
                                        "pipeline ${it.repository}/${it.pipeline}@${it.ref ?: "this ref"}"
                                    },
                                    steps = job.steps.map {
                                        ReleasePipelinePlanStep(it.name, it.uses ?: "run")
                                    },
                                    depth = depths.getValue(key),
                                )
                            }
                        result += ReleasePipelinePlan(
                            pipelineId = pipeline.id,
                            pipelineName = definition.name,
                            repositoryId = repository.id,
                            repositorySlug = repository.slug,
                            triggerType = triggerType.name,
                            environment = environment,
                            promotesFrom = environment?.let { definition.environments[it]?.promotesFrom },
                            inputs = trigger.inputs.map { (name, input) -> input.toReleaseInput(name) },
                            jobs = jobs,
                        )
                    }
                }
            }
        }
        return result.sortedWith(
            compareBy<ReleasePipelinePlan> { if (it.triggerType == PipelineTriggerType.RELEASE.name) 0 else 1 }
                .thenBy { it.environment ?: "" }
                .thenBy { it.repositorySlug }
                .thenBy { it.pipelineName }
        )
    }

    override suspend fun repositoryIdsForRelease(releaseId: UUID): List<UUID> {
        val release = releaseService.getById(releaseId) ?: return emptyList()
        return launchContext(release).repositoryIds
    }

    private fun releaseParameters(context: ReleaseLaunchContext, repositoryId: UUID): Map<String, String> = mapOf(
        RELEASE_ID_PARAMETER to context.release.id.toString(),
        RELEASE_VERSION_PARAMETER to context.versionByRepository.getValue(repositoryId),
        RELEASE_PROJECTS_PARAMETER to context.projectScope,
    )

    private fun planningInputs(inputs: Map<String, TriggerInput>): Map<String, String> = inputs.mapValues { (_, input) ->
        input.default ?: when (input.type) {
            "boolean" -> "false"
            "number" -> "0"
            "choice" -> input.options.firstOrNull()
                ?: throw IllegalArgumentException("A choice input must declare at least one option")
            else -> ""
        }
    }.mapKeys { (name, _) -> INPUT_PARAMETER_PREFIX + name }

    private fun TriggerInput.toReleaseInput(name: String) = ReleasePipelineInput(
        name = name,
        type = type,
        defaultValue = default,
        description = description,
        options = options,
        required = default == null,
    )

    private fun jobDepths(jobs: Map<String, bosca.git.model.JobDefinition>): Map<String, Int> {
        val depths = mutableMapOf<String, Int>()
        fun depth(key: String, visiting: Set<String>): Int {
            depths[key]?.let { return it }
            check(key !in visiting) { "Pipeline job dependency cycle includes '$key'" }
            val value = jobs[key]?.needs.orEmpty().maxOfOrNull { dependency ->
                if (dependency in jobs) depth(dependency, visiting + key) + 1 else 0
            } ?: 0
            depths[key] = value
            return value
        }
        jobs.keys.forEach { depth(it, emptySet()) }
        return depths
    }

    private fun accepts(definition: PipelineDefinition, triggerType: PipelineTriggerType, environment: String?): Boolean {
        val trigger = definition.triggers.firstOrNull { it.type == triggerType } ?: return false
        return triggerType != PipelineTriggerType.PROMOTION ||
            trigger.environments.isEmpty() || environment in trigger.environments
    }

    private fun bumpPatch(version: String): String {
        val match = SEMANTIC_VERSION.matchEntire(version)
            ?: throw IllegalArgumentException("Version '$version' is not a major.minor.patch version")
        val prefix = match.groupValues[1]
        val major = match.groupValues[2]
        val minor = match.groupValues[3]
        val patch = match.groupValues[4].toLong()
        check(patch < Long.MAX_VALUE) { "Version '$version' cannot be patch-bumped" }
        return "$prefix$major.$minor.${patch + 1}"
    }

    override suspend fun rollbackArtifacts(releaseId: UUID, authentication: AuthenticationContext): Boolean {
        val release = releaseService.getById(releaseId) ?: return false
        check(release.releasedAt == null) {
            "the release is already marked released — un-releasing is not supported"
        }
        check(runsForRelease(releaseId, ROLLBACK_RUN_SCAN).none { it.status == "QUEUED" || it.status == "RUNNING" }) {
            "a git-ci run for this release is still live — cancel it before rolling the attempt back"
        }
        for (item in releaseService.listVersions(releaseId)) {
            val version = versionService.getById(item.versionId) ?: continue
            val versionName = version.name
            // Tags: best-effort per repository — an attempt that failed early never tagged some repos.
            // The relay tags the version name verbatim; the v-prefixed form is also swept for attempts
            // tagged before the prefix was dropped.
            for (link in projectRepositories.list(item.projectId)) {
                for (tag in listOf(versionName, "v$versionName")) {
                    try {
                        repositoryWrite.deleteTag(link.repositoryId, tag)
                        log.info("Release rollback {}: deleted tag {} in repository {}", releaseId, tag, link.repositoryId)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        log.info("Release rollback {}: tag {} not deleted in {} ({})", releaseId, tag, link.repositoryId, e.message)
                    }
                }
            }
            for (publication in publicationService.listByVersion(item.versionId)) {
                publicationService.remove(publication.id)
                log.info("Release rollback {}: removed publication {}", releaseId, publication.coordinates)
            }
        }
        releaseService.resetDeployments(releaseId)
        return true
    }

    override suspend fun rollbackEnvironment(
        releaseId: UUID,
        environment: String,
        toRevision: Int,
        authentication: AuthenticationContext,
    ): List<String> {
        require(toRevision >= 0) { "Rollback revision must be zero or greater" }
        val release = releaseService.getById(releaseId)
            ?: throw NoSuchElementException("Release $releaseId not found")
        val key = environment.trim()
        require(key.isNotEmpty()) { "A rollback environment is required" }
        val targetEnvironment = environmentService.getByProgramAndKey(release.programId, key)
            ?: throw NoSuchElementException("Environment '$key' is not part of release program ${release.programId}")
        val actor = authentication.principal()
            ?: throw SecurityException("Rolling back an environment requires an authenticated principal")
        val actorId = actor.id
        val deployments = environmentService.currentState(targetEnvironment.id)
            .filter { it.releaseId == releaseId && it.status == bosca.workops.model.environment.EnvironmentDeploymentStatus.DEPLOYED }
        check(deployments.isNotEmpty()) {
            "Environment '$key' has no current deployments from release ${release.name} to roll back"
        }

        val outcomes = mutableListOf<String>()
        for (deployment in deployments) {
            val version = versionService.getById(deployment.versionId)
                ?: error("Version ${deployment.versionId} for deployment ${deployment.id} no longer exists")
            val ref = "refs/tags/${version.name}"
            val config = deployConfigService.forEnvironment(
                deployment.projectId,
                key,
                listOf(ref, "refs/tags/v${version.name}", "refs/heads/main"),
            ) ?: error("Project ${deployment.projectId} has no deploy configuration for environment '$key'")
            val sourceRepositoryId = config.sourceRepositoryId
                ?: error("The deploy configuration for project ${deployment.projectId} did not record its source repository")
            val repositoryId = UUID.parse(sourceRepositoryId)
            val entries = config.effectiveTargets().filter { it.targetKind() == deployment.targetKind }
            check(entries.isNotEmpty()) {
                "The deploy configuration for project ${deployment.projectId} no longer declares ${deployment.targetKind} in '$key'"
            }
            for (entry in entries) {
                val outcome = releaseDeployer.rollback(
                    ReleaseRollbackRequest(
                        targetRepositoryId = repositoryId,
                        ref = ref,
                        environmentKey = key,
                        target = if (config.effectiveTargets().size == 1) null else entry.target,
                        toRevision = toRevision,
                        overrides = emptyMap(),
                        parameters = mapOf(
                            RELEASE_ID_PARAMETER to releaseId.toString(),
                            RELEASE_VERSION_PARAMETER to version.name,
                        ),
                        initiatorPrincipalId = actorId,
                    )
                )
                outcomes += outcome.reference
            }
        }
        return outcomes
    }

    override suspend fun releaseChannelArtifactTypes(releaseId: UUID): List<String> =
        releaseDeclaredArtifacts(releaseId).mapTo(mutableSetOf()) { it.type.name }.sorted()

    override suspend fun releaseDeclaredArtifacts(releaseId: UUID): List<bosca.workops.model.artifact.ReleaseDeclaredArtifact> {
        val declared = mutableListOf<bosca.workops.model.artifact.ReleaseDeclaredArtifact>()
        for (item in releaseService.listVersions(releaseId)) {
            val versionName = versionService.getById(item.versionId)?.name
            for (link in projectRepositories.list(item.projectId)) {
                for (pipeline in gitPipelines.findByRepository(link.repositoryId)) {
                    for (artifact in gitPipelines.declaredArtifacts(pipeline.id)) {
                        declared += bosca.workops.model.artifact.ReleaseDeclaredArtifact(
                            projectId = item.projectId,
                            type = bosca.workops.model.artifact.artifactTypeOfString(artifact.type),
                            namespace = artifact.namespace,
                            coordinate = resolveCoordinate(artifact.coordinate, versionName),
                            environments = artifact.environments,
                        )
                    }
                }
            }
        }
        return declared
    }

    /** Resolves the coordinate's version/tag tokens against the release's version name — the same
     *  values the CI run resolves them to (the relay tags the version name verbatim, so both
     *  `version` and `tag` resolve to it). */
    private fun resolveCoordinate(coordinate: String, versionName: String?): String {
        if (versionName == null) return coordinate
        return coordinate
            .replace(Regex("\\$\\{\\{\\s*version\\s*}}"), versionName)
            .replace(Regex("\\$\\{\\{\\s*tag\\s*}}"), versionName)
    }

    /**
     * A BUILD dependency is satisfied one of two ways: the provider ships IN THIS RELEASE (with a
     * satisfying version), or a RELEASED provider version already satisfies the declared constraint
     * (nothing to co-release). A consumer whose BUILD dependency is satisfied by NEITHER fails the
     * launch here — naming every violation — before anything is tagged. RUNTIME/CONTRACT declarations
     * never gate a launch.
     *
     * The `match` constraint pins the provider to THE CONSUMER'S OWN version in this release: releasing
     * Server 10.2.3 with `Workspace: match` requires Workspace 10.2.3 — in this release, or already
     * released. Other constraints: `*`/blank (any), a trailing `*` prefix (`6.0.*`), or an exact name.
     */
    override suspend fun dependencyViolations(releaseId: UUID): List<String> {
        val items = releaseService.listVersions(releaseId)
        if (items.isEmpty()) return emptyList()
        val versionNameByProject = mutableMapOf<UUID, String>()
        for (item in items) {
            versionService.getById(item.versionId)?.let { versionNameByProject[item.projectId] = it.name }
        }
        val projectIds = versionNameByProject.keys + items.map { it.projectId }
        val violations = mutableListOf<String>()
        for (item in items) {
            // A bundled project with no git repositories can't be built — the relay would "succeed"
            // having tagged, built, and deployed NOTHING. Loud here, before anything starts.
            if (projectRepositories.list(item.projectId).isEmpty()) {
                violations += "${projectName(item.projectId)} has no git repositories attached — nothing can be built or deployed for it"
            }
            val consumer = item.projectId
            for (dep in dependencyService.listByConsumer(consumer)) {
                if (dep.dependencyType != DependencyType.BUILD) continue
                val constraint = dep.providerVersionConstraint.trim()
                // `match` resolves to the consumer's own version in this release.
                val effective = if (constraint.equals(MATCH_CONSTRAINT, ignoreCase = true)) {
                    versionNameByProject[consumer] ?: continue
                } else {
                    constraint
                }
                val exact = constraint.equals(MATCH_CONSTRAINT, ignoreCase = true)
                val inReleaseSatisfies = dep.providerProjectId in projectIds &&
                    (!exact || versionNameByProject[dep.providerProjectId] == effective)
                if (inReleaseSatisfies) continue
                if (hasSatisfyingReleasedVersion(dep.providerProjectId, effective, exactOnly = exact)) continue
                val required = if (exact) "version $effective" else "constraint '$constraint'"
                violations += "${projectName(consumer)} depends on ${projectName(dep.providerProjectId)} " +
                    "($required) and neither this release nor a released version satisfies it"
            }
        }
        return violations
    }

    private suspend fun verifyDependenciesComplete(releaseId: UUID) {
        val violations = dependencyViolations(releaseId)
        check(violations.isEmpty()) {
            "the release is missing required projects — ${violations.joinToString("; ")}. " +
                "Add them to the release, or release a satisfying provider version first."
        }
    }

    /** Whether any RELEASED version of [projectId] satisfies [constraint]. */
    private suspend fun hasSatisfyingReleasedVersion(projectId: UUID, constraint: String, exactOnly: Boolean = false): Boolean =
        versionService.listByProject(projectId).any {
            it.released && if (exactOnly) it.name == constraint else satisfies(it.name, constraint)
        }

    /**
     * Constraint semantics kept deliberately small and honest: `*`/blank matches any version; a
     * trailing `*` is a prefix match (`6.0.*` → any `6.0.x`); anything else is an exact name match.
     * (`match` is resolved to the consumer's version BEFORE this is consulted.)
     */
    private fun satisfies(versionName: String, constraint: String): Boolean {
        val c = constraint.trim()
        return when {
            c.isEmpty() || c == "*" -> true
            c.endsWith("*") -> versionName.startsWith(c.dropLast(1).removeSuffix("."))
            else -> versionName == c
        }
    }

    private suspend fun projectName(projectId: UUID): String {
        val project = projectService.getById(projectId) ?: return projectId.toString()
        return "${project.key} (${project.name})"
    }

    override suspend fun runsForRelease(releaseId: UUID, limit: Int): List<ReleaseRunView> {
        releaseService.getById(releaseId) ?: return emptyList()
        return runService.findByReleaseId(releaseId, 0, limit.coerceIn(1, MAX_RELEASE_RUNS))
            .map { it.toReleaseRunView() }
    }

    private fun PipelineRun.toReleaseRunView(): ReleaseRunView {
        val began = started ?: created
        val ended = finished
        return ReleaseRunView(
            runId = id,
            status = status.name,
            startedAt = began,
            finishedAt = ended,
            durationMs = ended?.let {
                Duration.between(began, it).toMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            },
        )
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(ReleasePipelineServiceImpl::class.java)

        private data class ReleaseLaunchContext(
            val release: Release,
            val repositoryIds: List<UUID>,
            val versionByRepository: Map<UUID, String>,
            val projectScope: String,
        )

        private data class PreparedRun(
            val pipelineId: UUID,
            val repositoryId: UUID,
            val definition: PipelineDefinition,
            val commitSha: String,
            val ref: String,
            val parameters: Map<String, String>,
        )

        /** Recent git-ci runs checked for liveness before a rollback. */
        private const val ROLLBACK_RUN_SCAN = 20

        private const val MAX_RELEASE_RUNS = 100

        /** The constraint that pins a provider to the CONSUMER'S own release version. */
        const val MATCH_CONSTRAINT = "match"

        const val RELEASE_ID_PARAMETER = "release.id"
        const val RELEASE_VERSION_PARAMETER = "release.version"
        const val RELEASE_PROJECTS_PARAMETER = "release.projects"
        const val PROMOTION_ENVIRONMENT_PARAMETER = "promotion.environment"
        const val ALLOW_DOWNGRADE_PARAMETER = "promotion.allowDowngrade"
        const val INPUT_PARAMETER_PREFIX = "inputs."

        private val SEMANTIC_VERSION = Regex("^(v?)(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$")
    }
}
