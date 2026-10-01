package bosca.git.ci.graphql

import bosca.git.model.AgentStatus
import bosca.git.model.ArtifactDefinition
import bosca.git.model.Pipeline
import bosca.git.model.PipelineAgent
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineSecret
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineLogService
import bosca.git.service.PipelineRunService
import bosca.git.service.PipelineSecretService
import bosca.git.service.PipelineService
import bosca.git.service.LogLine
import bosca.git.service.RepositoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.serialization.UUID

@TypeController(type = "Git")
class GitPipelineQuery(
    private val pipelineService: PipelineService,
    private val runService: PipelineRunService,
    private val jobService: PipelineJobService,
    private val agentService: PipelineAgentService,
    private val secretService: PipelineSecretService,
    private val logService: PipelineLogService,
    private val repositoryService: RepositoryService,
    private val permissionEvaluator: RepositoryPermissionEvaluator
) : GraphQLController<bosca.git.graphql.Git> {

    @Field
    suspend fun pipelines(authentication: AuthenticationContext?, repositoryId: UUID): List<Pipeline> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return pipelineService.findByRepository(repositoryId)
    }

    @Field
    suspend fun allPipelines(authentication: AuthenticationContext?): List<Pipeline> {
        // Author-time picker for release relays; lists pipelines across all repositories.
        return pipelineService.all()
    }

    @Field
    suspend fun declaredArtifacts(authentication: AuthenticationContext?, pipelineId: UUID): List<ArtifactDefinition> {
        val pipeline = pipelineService.findById(pipelineId) ?: return emptyList()
        val repository = repositoryService.findById(pipeline.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pipeline.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return pipelineService.declaredArtifacts(pipelineId)
    }

    @Field
    suspend fun pipelineRun(authentication: AuthenticationContext?, id: UUID): PipelineRun? {
        val run = runService.findById(id) ?: return null
        val repository = repositoryService.findById(run.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${run.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return run
    }

    /**
     * Reports the current state of a job assigned to an agent.
     *
     * This is deliberately separate from [pipelineRun]: an agent must be able to observe job-level
     * cancellation without receiving repository-wide view access. Ephemeral agents are scoped by
     * their target job; persistent agents are scoped by the job's current assignment.
     */
    @Field
    suspend fun targetedJobStatus(
        authentication: AuthenticationContext,
        agentId: UUID,
        jobId: UUID,
        attempt: Int? = null,
    ): PipelineRunStatus? {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        val agent = agentService.findById(agentId)
            ?: throw NoSuchElementException("Agent not found: $agentId")
        val scoped = authentication.principal() as? ScopedAuthenticatedPrincipal
        if (scoped != null && agent.apiTokenCredentialId != scoped.credentialId) {
            throw SecurityException("API token is not assigned to agent $agentId")
        }
        if (agent.ephemeral && agent.jobId != jobId) {
            throw SecurityException("Ephemeral agent $agentId is not scoped to job $jobId")
        }
        val job = jobService.findById(jobId) ?: return null
        if (attempt != null && attempt != job.attempt) {
            if (attempt < job.attempt) return PipelineRunStatus.CANCELLED
            throw SecurityException("Pipeline job $jobId has not reached attempt $attempt")
        }
        if (!agent.ephemeral && job.agentId != agentId) {
            throw SecurityException("Agent $agentId is not assigned to job $jobId")
        }
        return job.status
    }

    @Field
    suspend fun pipelineRuns(authentication: AuthenticationContext?, repositoryId: UUID, offset: Long?, limit: Int?): List<PipelineRun> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return runService.findByRepository(repositoryId, offset ?: 0, limit?.coerceIn(1, 100) ?: 25)
    }

    @Field
    suspend fun pipelineLogs(authentication: AuthenticationContext?, stepId: UUID, offset: Int?, limit: Int?, tail: Boolean?, beforeLine: Int?): List<LogLine> {
        val step = jobService.findStepById(stepId)
            ?: throw NoSuchElementException("Step not found: $stepId")
        val job = jobService.findById(step.pipelineJobId)
            ?: throw NoSuchElementException("Job not found: ${step.pipelineJobId}")
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Run not found: ${job.pipelineRunId}")
        val repository = repositoryService.findById(run.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${run.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.VIEW)
        return logService.getLogs(
            repositoryId = run.repositoryId,
            runId = run.id,
            jobId = job.id,
            stepId = stepId,
            offset = offset ?: 0,
            limit = limit?.coerceIn(1, 10000) ?: 1000,
            tail = tail ?: false,
            beforeLine = beforeLine
        )
    }

    @Field
    suspend fun pipelineAgents(authentication: AuthenticationContext, status: AgentStatus?): List<PipelineAgent> {
        val scoped = authentication.principal() as? ScopedAuthenticatedPrincipal
        val hasManagementScope = scoped == null || scoped.scopes == null ||
            scoped.hasScope("ci:manage") || scoped.hasScope("git:manage")
        if (hasManagementScope) {
            permissionEvaluator.verifyAllowed(authentication, PermissionAction.MANAGE)
            return agentService.listAgents(status)
        }

        // Agent credentials intentionally do not carry ci:manage. They still need to read their
        // own record to refresh labels, and orchestrators need to observe the ephemeral agents
        // they created. Keep the global list admin-only while constraining machine credentials to
        // the agent row identified by their API credential and its direct children.
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        val agents = agentService.listAgents()
        val caller = agents.firstOrNull { it.apiTokenCredentialId == scoped.credentialId }
            ?: throw SecurityException("API token is not assigned to a pipeline agent")
        return agents.filter {
            (it.id == caller.id || it.parentAgentId == caller.id) &&
                (status == null || it.status == status)
        }
    }

    @Field
    suspend fun pipelineSecrets(authentication: AuthenticationContext, repositoryId: UUID): List<PipelineSecret> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return secretService.listSecrets(repositoryId)
    }

    /**
     * The `uses: verify-deployment` gate's observation: the current deployment's
     * health for [environmentKey] in the project owning the target repository — probed once through
     * the same path the wait-healthy job uses, so both observe identically. `NONE` when nothing is
     * deployed there. The agent polls this until HEALTHY or its timeout.
     */
    @Field
    suspend fun jobDeploymentHealth(
        authentication: AuthenticationContext,
        jobId: UUID,
        environmentKey: String,
        repository: String?,
    ): String {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EDIT)
        require(environmentKey.isNotBlank()) { "verify-deployment requires a non-blank environment key" }
        val job = jobService.findById(jobId)
            ?: throw NoSuchElementException("Pipeline job not found: $jobId")
        check(job.status == bosca.git.model.PipelineRunStatus.RUNNING) {
            "Job '${job.name}' is ${job.status} — only a running job observes deployment health"
        }
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${job.pipelineRunId}")
        val initiator = run.triggeredBy
            ?: throw IllegalStateException(
                "Run #${run.number} has no initiating principal — refusing to observe deployment health, " +
                    "nothing executes unattributed"
            )
        val runRepository = repositoryService.findById(run.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${run.repositoryId}")
        val target = if (repository.isNullOrBlank()) {
            runRepository
        } else {
            val reference = repository
            val resolved = if ("/" in reference) {
                repositoryService.findByOwnerAndSlug(reference.substringBefore("/"), reference.substringAfter("/"))
            } else if (runRepository.slug == reference) {
                runRepository
            } else {
                repositoryService.findByOwner(runRepository.ownerId).firstOrNull { it.slug == reference }
            }
            resolved ?: throw NoSuchElementException("Repository not found: $reference")
        }
        val deployer = try {
            bosca.di.provide<bosca.git.service.ReleaseDeployer>()
        } catch (e: bosca.di.MissingProviderException) {
            throw IllegalStateException(
                "No release deployer is available in this deployment — verify-deployment cannot observe health"
            )
        }
        return deployer.deploymentHealth(target.id, environmentKey, initiator)
    }
}

/** Field resolver for the GraphQL `GitArtifactDefinition` type — a declared artifact projection. */
@TypeController(type = "GitArtifactDefinition")
class GitArtifactDefinitionController : GraphQLController<ArtifactDefinition> {
    @Field fun type(a: ArtifactDefinition) = a.type
    @Field fun namespace(a: ArtifactDefinition) = a.namespace
    @Field fun coordinate(a: ArtifactDefinition) = a.coordinate
}
