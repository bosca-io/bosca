package bosca.git.ci.graphql

import bosca.di.MissingProviderException
import bosca.di.provide
import bosca.git.ci.service.PipelineRunFinalizer
import bosca.git.service.EnvironmentActionAuthorizer
import bosca.git.model.AgentMode
import bosca.git.model.AgentStatus
import bosca.git.model.CommitStatusState
import bosca.git.model.OrchestratorConfig
import bosca.git.model.PipelineAgent
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineScheduleJob
import bosca.git.model.PipelineSecret
import bosca.git.model.PipelineTriggerType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.CommitStatusService
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.git.service.PipelineScheduleService
import bosca.git.service.PipelineLogService
import bosca.git.service.PipelineSecretService
import bosca.git.service.PipelineService
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.UUID
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobPrincipalState
import bosca.scheduler.service.SchedulerService

object GitMutation

@TypeController(type = "GitMutation")
class GitPipelineMutation(
    private val pipelineService: PipelineService,
    private val runService: PipelineRunService,
    private val jobService: PipelineJobService,
    private val agentService: PipelineAgentService,
    private val secretService: PipelineSecretService,
    private val logService: PipelineLogService,
    private val commitStatusService: CommitStatusService,
    private val repositoryService: RepositoryService,
    private val browseService: RepositoryBrowseService,
    private val writeService: bosca.git.service.RepositoryWriteService,
    private val permissionEvaluator: RepositoryPermissionEvaluator,
    private val scheduleService: PipelineScheduleService,
    private val schedulerService: SchedulerService,
    private val securityService: SecurityService,
) : GraphQLController<GitMutation> {

    private val runFinalizer by lazy {
        PipelineRunFinalizer(jobService, runService, agentService)
    }

    companion object {
        const val MAX_ERROR_MESSAGE_LENGTH = 8192
        private val TERMINAL_JOB_STATUSES = setOf(
            PipelineRunStatus.SUCCESS,
            PipelineRunStatus.FAILURE,
            PipelineRunStatus.CANCELLED,
            PipelineRunStatus.SKIPPED,
        )
    }

    @Field
    suspend fun triggerPipeline(
        authentication: AuthenticationContext,
        pipelineId: UUID,
        ref: String,
        inputs: JsonElement? = null,
    ): PipelineRun {
        val pipeline = pipelineService.findById(pipelineId)
            ?: throw NoSuchElementException("Pipeline not found: $pipelineId")
        val repository = repositoryService.findById(pipeline.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pipeline.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EXECUTE)

        // The run must record a real commit SHA, never the symbolic ref: runners export it as
        // COMMIT_SHA, where a ref name (with its slashes) corrupts derived values like registry
        // upload paths. Parsing at the SHA also pins the definition to the exact commit the run
        // records, immune to a concurrent push to the ref.
        val commitSha = browseService.resolveRef(pipeline.repositoryId, ref)
            ?: throw NoSuchElementException("Ref not found: $ref")

        val definition = pipelineService.parseDefinition(pipeline.repositoryId, commitSha, pipeline.filePath)
            ?: throw IllegalStateException("Failed to parse pipeline: ${pipeline.filePath}")

        val principalId = authentication.principal()?.id
            ?: throw SecurityException("Authentication required")
        val parameters = when (inputs) {
            null, JsonNull -> emptyMap()
            is JsonObject -> inputs.entries.associate { (name, value) ->
                val content = (value as? JsonPrimitive)?.contentOrNull
                    ?: throw IllegalArgumentException("Input '$name' must be a string, boolean, or number")
                "inputs.$name" to content
            }
            else -> throw IllegalArgumentException("Pipeline inputs must be an object")
        }
        return runService.createRun(
            pipelineId = pipelineId,
            repositoryId = pipeline.repositoryId,
            definition = definition,
            commitSha = commitSha,
            ref = ref,
            triggerType = PipelineTriggerType.MANUAL,
            triggeredBy = principalId,
            parameters = parameters,
        )
    }

    /** Assigns a server-side execution identity; YAML can never select the principal. */
    @Field
    suspend fun assignPipelineSchedule(
        authentication: AuthenticationContext,
        scheduleId: UUID,
        principalId: UUID,
    ): ScheduledJob {
        val (_, repository) = scheduleAndRepository(scheduleId)
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        val actor = authentication.principal()
            ?: throw SecurityException("An authenticated principal is required")
        verifySchedulePrincipal(principalId, repository)
        val confirmedBy = if (actor.id == principalId || actor.hasGroup("administrators")) actor.id else null
        return schedulerService.assignExecutionPrincipal(scheduleId, principalId, actor.id, confirmedBy)
            ?: throw NoSuchElementException("Pipeline schedule not found: $scheduleId")
    }

    /** The target principal or an administrator consents to a pending assignment. */
    @Field
    suspend fun confirmPipelineScheduleAssignment(
        authentication: AuthenticationContext,
        scheduleId: UUID,
    ): ScheduledJob {
        val (schedule, repository) = scheduleAndRepository(scheduleId)
        val actor = authentication.principal()
            ?: throw SecurityException("An authenticated principal is required")
        if (schedule.principalState == ScheduledJobPrincipalState.ACTIVE) return schedule
        check(schedule.principalState == ScheduledJobPrincipalState.PENDING_CONFIRMATION) {
            "Pipeline schedule $scheduleId has no pending assignment"
        }
        val assigned = schedule.executionPrincipalId
            ?: throw IllegalStateException("Pipeline schedule $scheduleId has no assigned principal")
        if (actor.id != assigned && !actor.hasGroup("administrators")) {
            throw SecurityException("Only the assigned principal or an administrator may confirm this assignment")
        }
        verifySchedulePrincipal(assigned, repository)
        return schedulerService.confirmExecutionPrincipal(scheduleId, actor.id)
            ?: throw NoSuchElementException("Pipeline schedule not found: $scheduleId")
    }

    /** Lets the assigned principal withdraw consent; repository managers can also clear it. */
    @Field
    suspend fun clearPipelineScheduleAssignment(
        authentication: AuthenticationContext,
        scheduleId: UUID,
    ): ScheduledJob {
        val (schedule, repository) = scheduleAndRepository(scheduleId)
        val actor = authentication.principal()
            ?: throw SecurityException("An authenticated principal is required")
        if (actor.id != schedule.executionPrincipalId &&
            !permissionEvaluator.isAllowed(authentication, repository, PermissionAction.MANAGE)
        ) {
            throw SecurityException("Only the assigned principal or a repository manager may clear this assignment")
        }
        return schedulerService.clearExecutionPrincipal(scheduleId)
            ?: throw NoSuchElementException("Pipeline schedule not found: $scheduleId")
    }

    private suspend fun scheduleAndRepository(scheduleId: UUID): Pair<ScheduledJob, bosca.git.model.Repository> {
        val schedule = scheduleService.findById(scheduleId)
            ?: throw NoSuchElementException("Pipeline schedule not found: $scheduleId")
        val pipelineId = runCatching {
            Json.decodeFromJsonElement(PipelineScheduleJob.serializer(), schedule.jobParameters).pipelineId
        }.getOrElse { throw IllegalStateException("Pipeline schedule $scheduleId has invalid parameters", it) }
        val pipeline = pipelineService.findById(pipelineId)
            ?: throw NoSuchElementException("Pipeline not found: $pipelineId")
        val repository = repositoryService.findById(pipeline.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${pipeline.repositoryId}")
        return schedule to repository
    }

    private suspend fun verifySchedulePrincipal(principalId: UUID, repository: bosca.git.model.Repository) {
        val principal = securityService.getPrincipalById(principalId)
            ?: throw NoSuchElementException("Principal not found: $principalId")
        check(principal.deletedAt == null) { "Principal $principalId is disabled" }
        val targetAuthentication = securityService.impersonate(principalId)
        if (!permissionEvaluator.isAllowed(targetAuthentication, repository, PermissionAction.EXECUTE)) {
            throw SecurityException("Principal $principalId cannot execute pipelines in repository ${repository.id}")
        }
    }

    @Field
    suspend fun cancelPipelineRun(authentication: AuthenticationContext, id: UUID): PipelineRun {
        val run = runService.findById(id)
            ?: throw NoSuchElementException("Pipeline run not found: $id")
        val repository = repositoryService.findById(run.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${run.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        runService.cancelRun(id)
        return runService.findById(id) ?: throw NoSuchElementException("Pipeline run not found: $id")
    }

    @Field
    suspend fun deletePipelineRun(authentication: AuthenticationContext, id: UUID): Boolean {
        val run = runService.findById(id)
            ?: throw NoSuchElementException("Pipeline run not found: $id")
        val repository = repositoryService.findById(run.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${run.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        runService.delete(id)
        return true
    }

    @Field
    suspend fun cancelPipelineJob(authentication: AuthenticationContext, jobId: UUID): PipelineJob {
        val (_, repository) = jobAndRepository(jobId)
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        return runService.cancelJob(jobId)
    }

    @Field
    suspend fun rerunPipeline(authentication: AuthenticationContext, runId: UUID): PipelineRun {
        val run = runService.findById(runId)
            ?: throw NoSuchElementException("Pipeline run not found: $runId")
        val repository = repositoryService.findById(run.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${run.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EXECUTE)
        val principalId = authentication.principal()?.id
            ?: throw SecurityException("Authentication required")
        return runService.rerun(runId, principalId)
    }

    @Field
    suspend fun rerunPipelineJob(authentication: AuthenticationContext, jobId: UUID): PipelineJob {
        val (_, repository) = jobAndRepository(jobId)
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EXECUTE)
        val principalId = authentication.principal()?.id
            ?: throw SecurityException("Authentication required")
        return runService.rerunJob(jobId, principalId)
    }

    /**
     * Opens a job's external requirement gate without weakening same-run dependencies, conditions,
     * or approvals. Terminal requirement failures are reopened in place before the gate is opened.
     */
    @Field
    suspend fun runPipelineJobAnyway(
        authentication: AuthenticationContext,
        jobId: UUID,
        reason: String?,
    ): PipelineJob {
        val (_, repository) = jobAndRepository(jobId)
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EXECUTE)
        val principalId = authentication.principal()?.id
            ?: throw SecurityException("Authentication required")
        return runService.runJobAnyway(jobId, principalId, reason)
    }

    /** Re-runs only a terminal run's FAILED/CANCELLED jobs — succeeded jobs keep their results. */
    @Field
    suspend fun rerunFailedJobs(authentication: AuthenticationContext, runId: UUID): PipelineRun {
        val run = runService.findById(runId)
            ?: throw NoSuchElementException("Pipeline run not found: $runId")
        val repository = repositoryService.findById(run.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${run.repositoryId}")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EXECUTE)
        val principalId = authentication.principal()?.id
            ?: throw SecurityException("Authentication required")
        return runService.rerunFailedJobs(runId, principalId)
    }

    /**
     * Approves an approval-gated job. Enforces approve-when-ready — dependencies and
     * requirements must already have cleared. An approved GATE (steps-less job) completes
     * server-side immediately.
     */
    @Field
    suspend fun approvePipelineJob(authentication: AuthenticationContext, jobId: UUID, comment: String?): PipelineJob {
        val (job, repository) = jobAndRepository(jobId)
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EXECUTE)
        verifyEnvironmentAllowed(authentication, repository.id, job)
        val principalId = authentication.principal()?.id
            ?: throw SecurityException("Authentication required")
        val approved = jobService.approve(jobId, principalId, comment)
        for (gate in jobService.completeGateJobs(approved.pipelineRunId)) {
            runFinalizer.finalizeJob(gate.id, PipelineRunStatus.SUCCESS, releaseAgent = false)
        }
        return jobService.findById(jobId)
            ?: throw NoSuchElementException("Job not found: $jobId")
    }

    /** Rejects an approval-gated job — the job FAILS with the rejection recorded. */
    @Field
    suspend fun rejectPipelineJob(authentication: AuthenticationContext, jobId: UUID, comment: String?): PipelineJob {
        val (job, repository) = jobAndRepository(jobId)
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EXECUTE)
        verifyEnvironmentAllowed(authentication, repository.id, job)
        jobService.rejectApproval(jobId, authentication.principal()?.id, comment)
        runFinalizer.finalizeJob(jobId, PipelineRunStatus.FAILURE, releaseAgent = false)
        return jobService.findById(jobId)
            ?: throw NoSuchElementException("Job not found: $jobId")
    }

    /**
     * The `uses: tag` action: creates a release tag in [repository] from a RUNNING job.
     * The tag rides [bosca.git.service.RepositoryWriteService.createTag]'s post-receive path with
     * the RUN'S INITIATING PRINCIPAL as the pusher, so every run the tag triggers inherits the
     * human who started the release — a run with no initiator refuses to tag (nothing executes
     * unattributed). [tag] defaults to the run's `release.version` parameter; [repository] is
     * `owner/slug` or a bare sibling slug within the run repository's owner. Idempotent per the
     * write service: same commit = no-op, different commit = loud failure. A tag targeting the run
     * repository is pinned to the run's exact commit; sibling repositories intentionally use their
     * default branches because a workspace release coordinates distinct repository commits.
     */
    @Field
    suspend fun createReleaseTag(
        authentication: AuthenticationContext,
        jobId: UUID,
        repository: String,
        tag: String?,
        message: String?,
    ): bosca.git.service.CreateTagResult {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        val (job, runRepository) = jobAndRepository(authentication, jobId)
        check(job.status == PipelineRunStatus.RUNNING) {
            "Job '${job.name}' is ${job.status} — only a running job can create release tags"
        }
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${job.pipelineRunId}")
        val initiator = run.triggeredBy
            ?: throw IllegalStateException(
                "Run #${run.number} has no initiating principal — refusing to tag, nothing executes unattributed"
            )
        val target = resolveRepositoryReference(runRepository, repository)
            ?: throw NoSuchElementException("Repository not found: $repository")
        permissionEvaluator.verifyAllowed(authentication, target, PermissionAction.EDIT)
        val tagName = tag
            ?: (run.parameters as? kotlinx.serialization.json.JsonObject)
                ?.get("release.version")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: throw IllegalArgumentException(
                "No tag name: pass with.tag or run with a release.version parameter"
            )
        return writeService.createTag(
            bosca.git.service.CreateTagInput(
                repositoryId = target.id,
                tag = tagName,
                targetRef = if (target.id == run.repositoryId) run.commitSha else "refs/heads/${target.defaultBranch}",
                message = message?.ifBlank { null } ?: "Release $tagName",
                taggerName = "Bosca Release",
                taggerEmail = "release@bosca",
                pusherPrincipalId = initiator,
                // Re-run safety: a re-run tagging the same commit again is a no-op, not an error.
                allowExisting = true,
            )
        )
    }

    /**
     * `owner/slug` resolves across namespaces; a bare slug resolves within the requesting run's
     * repository owner — sibling repositories, so release YAML never hardcodes an owner (same rule
     * as pipeline requirements).
     */
    private suspend fun resolveRepositoryReference(
        requesting: bosca.git.model.Repository,
        reference: String,
    ): bosca.git.model.Repository? {
        if ("/" in reference) {
            return repositoryService.findByOwnerAndSlug(reference.substringBefore("/"), reference.substringAfter("/"))
        }
        if (requesting.slug == reference) return requesting
        return repositoryService.findByOwner(requesting.ownerId).firstOrNull { it.slug == reference }
    }

    /**
     * The `uses: allocate-build-number` action: one durable number per app/source/version/variant.
     * The running job supplies repository, commit, and run identity; callers cannot allocate on
     * behalf of unrelated source. WorkOps owns the atomic counter through the ReleaseDeployer SPI.
     */
    @Field
    suspend fun allocateBuildNumberFromJob(
        authentication: AuthenticationContext,
        jobId: UUID,
        platform: String,
        applicationId: String,
        sourceVersion: String,
        buildKey: String?,
        minimum: String?,
    ): AppBuildNumberResult {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        val (job, repository) = jobAndRepository(authentication, jobId)
        check(job.status == PipelineRunStatus.RUNNING) {
            "Job '${job.name}' is ${job.status} — only a running job can allocate a build number"
        }
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${job.pipelineRunId}")
        val outcome = releaseDeployer().allocateBuildNumber(
            bosca.git.service.ReleaseBuildNumberRequest(
                repositoryId = repository.id,
                pipelineRunId = run.id,
                sourceCommitSha = run.commitSha,
                sourceVersion = sourceVersion,
                platform = platform,
                applicationId = applicationId,
                buildKey = buildKey?.ifBlank { null } ?: "default",
                minimum = minimum?.ifBlank { null },
            ),
        )
        return AppBuildNumberResult(outcome.number, outcome.value, outcome.reused)
    }

    /**
     * The `uses: deploy` action: deploys [environment] as declared by the target
     * repository's `.bosca/deploy.yaml`, from a RUNNING job. All mechanics — entry selection,
     * artifact-selector resolution, environment permission (evaluated against the RUN'S INITIATING
     * PRINCIPAL), adapter invocation, and EnvironmentDeployment recording — go through the
     * [ReleaseDeployer] SPI, fail-closed: no deployer in this deployment means no deploys.
     * [repository] defaults to the job's own repository; [overrides] are step-level `with:`
     * config-key overrides.
     */
    @Field
    suspend fun deployFromJob(
        authentication: AuthenticationContext,
        jobId: UUID,
        environmentKey: String,
        repository: String?,
        target: String?,
        overrides: kotlinx.serialization.json.JsonElement?,
    ): ReleaseDeployOutcomeResult {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        require(environmentKey.isNotBlank()) { "deploy requires a non-blank environment key" }
        val (job, runRepository) = jobAndRepository(authentication, jobId)
        check(job.status == PipelineRunStatus.RUNNING) {
            "Job '${job.name}' is ${job.status} — only a running job can deploy"
        }
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${job.pipelineRunId}")
        val initiator = run.triggeredBy
            ?: throw IllegalStateException(
                "Run #${run.number} has no initiating principal — refusing to deploy, nothing executes unattributed"
            )
        val targetRepository = if (repository.isNullOrBlank()) {
            runRepository
        } else {
            resolveRepositoryReference(runRepository, repository)
                ?: throw NoSuchElementException("Repository not found: $repository")
        }
        val outcome = releaseDeployer().deploy(
            bosca.git.service.ReleaseDeployRequest(
                targetRepositoryId = targetRepository.id,
                ref = run.ref,
                environmentKey = environmentKey,
                target = target?.ifBlank { null },
                overrides = jsonToStringMap(overrides),
                parameters = jsonToStringMap(run.parameters),
                initiatorPrincipalId = initiator,
            )
        )
        return ReleaseDeployOutcomeResult(outcome.reference, outcome.status)
    }

    /**
     * The `uses: rollback` action: target-honest rollback of [environmentKey] —
     * helm restores the revision AND reverts the ops-repo values; environment permission is
     * evaluated against the RUN'S INITIATING PRINCIPAL through the deployer. [toRevision] 0 means
     * "the previous revision" (helm's own convention).
     */
    @Field
    suspend fun rollbackFromJob(
        authentication: AuthenticationContext,
        jobId: UUID,
        environmentKey: String,
        repository: String?,
        target: String?,
        toRevision: Int?,
        overrides: kotlinx.serialization.json.JsonElement?,
    ): ReleaseDeployOutcomeResult {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        require(environmentKey.isNotBlank()) { "rollback requires a non-blank environment key" }
        val revision = toRevision ?: 0
        require(revision >= 0) { "toRevision must be zero or greater, got $revision" }
        val (job, runRepository) = jobAndRepository(authentication, jobId)
        check(job.status == PipelineRunStatus.RUNNING) {
            "Job '${job.name}' is ${job.status} — only a running job can roll back"
        }
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${job.pipelineRunId}")
        val initiator = run.triggeredBy
            ?: throw IllegalStateException(
                "Run #${run.number} has no initiating principal — refusing to roll back, nothing executes unattributed"
            )
        val targetRepository = repository?.takeIf { it.isNotBlank() }?.let {
            resolveRepositoryReference(runRepository, it)
                ?: throw NoSuchElementException("Repository not found: $it")
        } ?: runRepository
        val outcome = releaseDeployer().rollback(
            bosca.git.service.ReleaseRollbackRequest(
                targetRepositoryId = targetRepository.id,
                ref = run.ref,
                environmentKey = environmentKey,
                target = target?.ifBlank { null },
                toRevision = revision,
                overrides = jsonToStringMap(overrides),
                parameters = jsonToStringMap(run.parameters),
                initiatorPrincipalId = initiator,
            )
        )
        return ReleaseDeployOutcomeResult(outcome.reference, outcome.status)
    }

    /**
     * The `uses: play-rollout` action: advances the current Google Play staged rollout
     * without re-uploading the bundle. The percentage is store-neutral (0–100); the WorkOps adapter
     * maps it to Play's userFraction and evaluates environment permission against the run initiator.
     */
    @Field
    suspend fun playRolloutFromJob(
        authentication: AuthenticationContext,
        jobId: UUID,
        environmentKey: String,
        rolloutPercentage: Double,
        repository: String?,
        target: String?,
    ): ReleaseDeployOutcomeResult {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        require(environmentKey.isNotBlank()) { "play-rollout requires a non-blank environment key" }
        require(rolloutPercentage.isFinite() && rolloutPercentage in 0.0..100.0) {
            "rolloutPercentage must be between 0 and 100, got $rolloutPercentage"
        }
        val (job, runRepository) = jobAndRepository(authentication, jobId)
        check(job.status == PipelineRunStatus.RUNNING) {
            "Job '${job.name}' is ${job.status} — only a running job can advance a rollout"
        }
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${job.pipelineRunId}")
        val initiator = run.triggeredBy
            ?: throw IllegalStateException(
                "Run #${run.number} has no initiating principal — refusing to advance rollout, nothing executes unattributed"
            )
        val targetRepository = repository?.takeIf { it.isNotBlank() }?.let {
            resolveRepositoryReference(runRepository, it)
                ?: throw NoSuchElementException("Repository not found: $it")
        } ?: runRepository
        val outcome = releaseDeployer().playRollout(
            bosca.git.service.ReleasePlayRolloutRequest(
                targetRepositoryId = targetRepository.id,
                ref = run.ref,
                environmentKey = environmentKey,
                target = target?.ifBlank { null },
                rolloutPercentage = rolloutPercentage,
                parameters = jsonToStringMap(run.parameters),
                initiatorPrincipalId = initiator,
            ),
        )
        return ReleaseDeployOutcomeResult(outcome.reference, outcome.status)
    }

    /** One observation for `uses: app-store-review`; the macOS agent owns polling cadence. */
    @Field
    suspend fun appStoreReviewFromJob(
        authentication: AuthenticationContext,
        jobId: UUID,
        environmentKey: String,
        mode: bosca.git.service.ReleaseAppStoreReviewMode,
        repository: String?,
        target: String?,
    ): AppStoreReviewOutcomeResult {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        require(environmentKey.isNotBlank()) { "app-store-review requires a non-blank environment key" }
        val (job, runRepository) = jobAndRepository(authentication, jobId)
        check(job.status == PipelineRunStatus.RUNNING) {
            "Job '${job.name}' is ${job.status} — only a running job can observe App Store review"
        }
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${job.pipelineRunId}")
        val initiator = run.triggeredBy
            ?: throw IllegalStateException(
                "Run #${run.number} has no initiating principal — refusing to read review, nothing executes unattributed"
            )
        val targetRepository = repository?.takeIf { it.isNotBlank() }?.let {
            resolveRepositoryReference(runRepository, it)
                ?: throw NoSuchElementException("Repository not found: $it")
        } ?: runRepository
        val outcome = releaseDeployer().appStoreReview(
            bosca.git.service.ReleaseAppStoreReviewRequest(
                targetRepositoryId = targetRepository.id,
                ref = run.ref,
                environmentKey = environmentKey,
                target = target?.ifBlank { null },
                mode = mode,
                parameters = jsonToStringMap(run.parameters),
                initiatorPrincipalId = initiator,
            ),
        )
        return AppStoreReviewOutcomeResult(outcome.state, outcome.complete, outcome.approved)
    }

    /** One Play-vitals observation for `uses: verify-store-health`; no continuous monitoring here. */
    @Field
    suspend fun storeHealthFromJob(
        authentication: AuthenticationContext,
        jobId: UUID,
        environmentKey: String,
        maxCrashRate: Double,
        windowSeconds: Long,
        repository: String?,
        target: String?,
    ): StoreHealthOutcomeResult {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        require(environmentKey.isNotBlank()) { "verify-store-health requires a non-blank environment key" }
        require(maxCrashRate.isFinite() && maxCrashRate in 0.0..100.0) {
            "maxCrashRate must be between 0 and 100 percent, got $maxCrashRate"
        }
        require(windowSeconds > 0) { "windowSeconds must be positive, got $windowSeconds" }
        val (job, runRepository) = jobAndRepository(authentication, jobId)
        check(job.status == PipelineRunStatus.RUNNING) {
            "Job '${job.name}' is ${job.status} — only a running job can observe store health"
        }
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${job.pipelineRunId}")
        val initiator = run.triggeredBy
            ?: throw IllegalStateException(
                "Run #${run.number} has no initiating principal — refusing to read store health, nothing executes unattributed"
            )
        val targetRepository = repository?.takeIf { it.isNotBlank() }?.let {
            resolveRepositoryReference(runRepository, it)
                ?: throw NoSuchElementException("Repository not found: $it")
        } ?: runRepository
        val outcome = releaseDeployer().storeHealth(
            bosca.git.service.ReleaseStoreHealthRequest(
                targetRepositoryId = targetRepository.id,
                ref = run.ref,
                environmentKey = environmentKey,
                target = target?.ifBlank { null },
                maxCrashRate = maxCrashRate,
                window = java.time.Duration.ofSeconds(windowSeconds),
                parameters = jsonToStringMap(run.parameters),
                initiatorPrincipalId = initiator,
            ),
        )
        return StoreHealthOutcomeResult(
            outcome.crashRate, outcome.maxCrashRate, outcome.windowSeconds, outcome.healthy,
        )
    }

    /**
     * The `uses: mark-released` action: stamps the run's workops release released —
     * idempotent, program MANAGE evaluated against the run's initiating principal.
     */
    @Field
    suspend fun markReleasedFromJob(authentication: AuthenticationContext, jobId: UUID): Boolean {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        val (job, _) = jobAndRepository(authentication, jobId)
        check(job.status == PipelineRunStatus.RUNNING) {
            "Job '${job.name}' is ${job.status} — only a running job can mark the release released"
        }
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${job.pipelineRunId}")
        val initiator = run.triggeredBy
            ?: throw IllegalStateException(
                "Run #${run.number} has no initiating principal — refusing, nothing executes unattributed"
            )
        val releaseId = jsonToStringMap(run.parameters)["release.id"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Run #${run.number} carries no release.id — nothing to mark released")
        releaseDeployer().markReleased(UUID.parse(releaseId), initiator)
        return true
    }

    /**
     * The `uses: generate-release-notes` action: after tag waves, generate localized
     * Version-owned drafts from the tagged Git ranges under the run initiator's WorkOps authority.
     */
    @Field
    suspend fun generateReleaseNotesFromJob(authentication: AuthenticationContext, jobId: UUID): Boolean {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        val (job, _) = jobAndRepository(authentication, jobId)
        check(job.status == PipelineRunStatus.RUNNING) {
            "Job '${job.name}' is ${job.status} — only a running job can generate release notes"
        }
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${job.pipelineRunId}")
        val initiator = run.triggeredBy
            ?: throw IllegalStateException(
                "Run #${run.number} has no initiating principal — refusing, nothing executes unattributed"
            )
        val releaseId = jsonToStringMap(run.parameters)["release.id"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Run #${run.number} carries no release.id — no notes can be generated")
        releaseDeployer().generateReleaseNotes(UUID.parse(releaseId), initiator)
        return true
    }

    private suspend fun releaseDeployer(): bosca.git.service.ReleaseDeployer = try {
        provide<bosca.git.service.ReleaseDeployer>()
    } catch (e: MissingProviderException) {
        throw IllegalStateException(
            "No release deployer is available in this deployment — release actions cannot proceed"
        )
    }

    private fun jsonToStringMap(element: kotlinx.serialization.json.JsonElement?): Map<String, String> {
        if (element !is kotlinx.serialization.json.JsonObject) return emptyMap()
        return buildMap {
            element.forEach { (key, value) ->
                if (value is kotlinx.serialization.json.JsonPrimitive) {
                    put(key, value.content)
                }
            }
        }
    }

    private suspend fun jobAndRepository(
        authentication: AuthenticationContext,
        jobId: UUID,
    ): Pair<PipelineJob, bosca.git.model.Repository> {
        val result = jobAndRepository(jobId)
        verifyJobCredential(authentication, result.first)
        return result
    }

    private suspend fun jobAndRepository(
        jobId: UUID,
    ): Pair<PipelineJob, bosca.git.model.Repository> {
        val job = jobService.findById(jobId)
            ?: throw NoSuchElementException("Pipeline job not found: $jobId")
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${job.pipelineRunId}")
        val repository = repositoryService.findById(run.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${run.repositoryId}")
        return job to repository
    }

    /**
     * Approving/rejecting an ENVIRONMENT-BOUND job is an environment-targeting action:
     * beyond repository permission it requires EXECUTE on the linked WorkOps environment, checked
     * through the [EnvironmentActionAuthorizer] SPI. Fail-closed: a deployment that binds jobs to
     * environments but has no authorizer cannot evaluate the requirement, so it must not proceed.
     */
    private suspend fun verifyEnvironmentAllowed(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        job: PipelineJob,
    ) {
        val environment = job.environment ?: return
        val authorizer = try {
            provide<EnvironmentActionAuthorizer>()
        } catch (e: MissingProviderException) {
            throw IllegalStateException(
                "Job '${job.name}' targets environment '$environment', but no environment authorizer " +
                    "is available in this deployment — environment-bound approvals cannot proceed"
            )
        }
        authorizer.verifyAllowed(authentication, repositoryId, environment, PermissionAction.EXECUTE)
    }

    @Field
    suspend fun registerAgent(authentication: AuthenticationContext, name: String, labels: List<String>, mode: AgentMode): AgentRegistrationResult {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.MANAGE)
        val principalId = authentication.principal()?.id
            ?: throw bosca.security.service.SecurityException("Authentication required")
        val (agent, token) = agentService.register(name, labels, mode, principalId)
        return AgentRegistrationResult(agent, token)
    }

    @Field
    suspend fun registerEphemeralAgent(
        authentication: AuthenticationContext,
        jobId: UUID,
        name: String,
        labels: List<String>,
        parentAgentId: UUID,
        timeoutMinutes: Int
    ): AgentRegistrationResult {
        if (authentication.principal() is ScopedAuthenticatedPrincipal) {
            permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
            verifyAgentCredential(authentication, parentAgentId)
        } else {
            permissionEvaluator.verifyAllowed(authentication, PermissionAction.MANAGE)
        }
        val principalId = authentication.principal()?.id
            ?: throw bosca.security.service.SecurityException("Authentication required")
        val (agent, token) = agentService.registerEphemeral(jobId, name, labels, parentAgentId, timeoutMinutes, principalId)
        return AgentRegistrationResult(agent, token)
    }

    @Field
    suspend fun setAgentInstanceId(authentication: AuthenticationContext, agentId: UUID, instanceId: String): Boolean {
        val agent = agentService.findById(agentId)
            ?: throw NoSuchElementException("Agent not found: $agentId")
        if (authentication.principal() is ScopedAuthenticatedPrincipal) {
            permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
            verifyParentAgentCredential(authentication, agent)
        } else {
            permissionEvaluator.verifyAllowed(authentication, PermissionAction.MANAGE)
        }
        agentService.setInstanceId(agentId, instanceId)
        return true
    }

    @Field
    suspend fun updateAgent(authentication: AuthenticationContext, id: UUID, name: String, labels: List<String>): PipelineAgent {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.MANAGE)
        agentService.findById(id) ?: throw NoSuchElementException("Agent not found: $id")
        return agentService.updateAgent(id, name, labels)
    }

    @Field
    suspend fun deregisterAgent(authentication: AuthenticationContext, id: UUID): Boolean {
        if (authentication.principal() is ScopedAuthenticatedPrincipal) {
            permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
            verifyAgentOrParentCredential(authentication, id)
        } else {
            permissionEvaluator.verifyAllowed(authentication, PermissionAction.MANAGE)
        }
        agentService.deregister(id)
        return true
    }

    @Field
    suspend fun setPipelineSecret(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        name: String,
        value: String,
        environmentKey: String?,
    ): PipelineSecret {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return secretService.setSecret(repositoryId, name, value, environmentKey)
    }

    /** Grants a group an action on the secret itself — use = EXECUTE. */
    @Field
    suspend fun addPipelineSecretPermission(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        name: String,
        groupId: UUID,
        action: PermissionAction,
    ): Boolean {
        val (repository, secret) = repositoryAndSecret(repositoryId, name)
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        secretService.addPermission(secret.id, groupId, action)
        return true
    }

    @Field
    suspend fun removePipelineSecretPermission(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        name: String,
        groupId: UUID,
        action: PermissionAction,
    ): Boolean {
        val (repository, secret) = repositoryAndSecret(repositoryId, name)
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        secretService.removePermission(secret.id, groupId, action)
        return true
    }

    private suspend fun repositoryAndSecret(
        repositoryId: UUID,
        name: String,
    ): Pair<bosca.git.model.Repository, PipelineSecret> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        val secret = secretService.listSecrets(repositoryId).firstOrNull { it.name == name }
            ?: throw NoSuchElementException("Secret not found: $name")
        return repository to secret
    }

    @Field
    suspend fun deletePipelineSecret(authentication: AuthenticationContext, repositoryId: UUID, name: String): Boolean {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        secretService.deleteSecret(repositoryId, name)
        return true
    }
    @Field
    suspend fun claimJob(
        authentication: AuthenticationContext,
        agentId: UUID,
        labels: List<String>,
        jobId: UUID? = null,
    ): PipelineJob? {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        verifyAgentCredential(authentication, agentId)

        if (jobId != null) {
            val agent = agentService.findById(agentId)
                ?: throw NoSuchElementException("Agent not found: $agentId")
            if (!agent.ephemeral) {
                throw SecurityException("Targeted job claims require an ephemeral agent")
            }
            if (agent.jobId != jobId) {
                throw SecurityException("Ephemeral agent $agentId is not scoped to job $jobId")
            }
            val abandonedJob = jobService.findCurrentByAgent(agentId)
            agentService.updateStatus(agentId, AgentStatus.BUSY)
            val job = jobService.claimJobById(
                agentId = agentId,
                jobId = jobId,
                previousAgentId = agent.parentAgentId,
            )
            if (job == null) {
                agentService.updateStatus(agentId, AgentStatus.ONLINE)
            }
            if (abandonedJob != null && (job == null || abandonedJob.id != job.id)) {
                failAbandonedJob(abandonedJob)
            }
            return job
        }

        val abandonedJob = jobService.findCurrentByAgent(agentId)

        agentService.updateStatus(agentId, AgentStatus.BUSY)
        val job = jobService.claimJob(agentId, labels)
        if (job == null) {
            agentService.updateStatus(agentId, AgentStatus.ONLINE)
        }

        if (abandonedJob != null && (job == null || abandonedJob.id != job.id)) {
            failAbandonedJob(abandonedJob)
        }

        return job
    }

    private suspend fun failAbandonedJob(job: PipelineJob) {
        val transitioned = jobService.finishIfActive(
            job.id,
            PipelineRunStatus.FAILURE,
            "Agent abandoned job before claiming another job",
        )
        if (transitioned) {
            runFinalizer.finalizeJob(job.id, PipelineRunStatus.FAILURE, releaseAgent = false)
        }
    }

    @Field
    suspend fun updateJobStatus(authentication: AuthenticationContext, jobId: UUID, status: PipelineRunStatus, errorMessage: String?): PipelineJob {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        val current = jobService.findById(jobId)
            ?: throw NoSuchElementException("Job not found: $jobId")
        verifyJobCredential(authentication, current)
        val boundedError = errorMessage?.take(MAX_ERROR_MESSAGE_LENGTH)
        val transitioned = if (status in TERMINAL_JOB_STATUSES) {
            // Server-side cancellation and durable Kubernetes reconciliation can race the agent's
            // final report. Every terminal writer uses the same active-to-terminal compare-and-set,
            // so the loser cannot overwrite the settled outcome or repeat run/agent finalization.
            jobService.finishIfActive(jobId, status, boundedError)
        } else {
            jobService.updateStatus(jobId, status, boundedError)
            true
        }
        if (transitioned) {
            runFinalizer.finalizeJob(jobId, status)
        }
        return jobService.findById(jobId)
            ?: throw NoSuchElementException("Job not found: $jobId")
    }

    @Field
    suspend fun updateStepStatus(authentication: AuthenticationContext, stepId: UUID, status: PipelineRunStatus, exitCode: Int?, errorMessage: String?): Boolean {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        val step = jobService.findStepById(stepId)
            ?: throw NoSuchElementException("Step not found: $stepId")
        val job = jobService.findById(step.pipelineJobId)
            ?: throw NoSuchElementException("Job not found: ${step.pipelineJobId}")
        verifyJobCredential(authentication, job)
        // The agent sends a bounded summary already; the cap here protects the
        // row from an unbounded message if a different client misbehaves.
        jobService.updateStepStatus(stepId, status, exitCode, errorMessage?.take(MAX_ERROR_MESSAGE_LENGTH))
        return true
    }

    @Field
    suspend fun reportCommitStatus(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        commitSha: String,
        context: String,
        state: CommitStatusState,
        description: String?,
        targetUrl: String?,
        jobId: UUID? = null,
    ): Boolean {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        if (authentication.principal() is ScopedAuthenticatedPrincipal) {
            permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
            val scopedJobId = jobId
                ?: throw SecurityException("API tokens must identify the job reporting commit status")
            val job = jobService.findById(scopedJobId)
                ?: throw NoSuchElementException("Job not found: $scopedJobId")
            verifyJobCredential(authentication, job)
            val run = runService.findById(job.pipelineRunId)
                ?: throw NoSuchElementException("Pipeline run not found: ${job.pipelineRunId}")
            if (
                run.repositoryId != repositoryId ||
                run.commitSha != commitSha ||
                context != "ci/${job.name}"
            ) {
                throw SecurityException(
                    "Job $scopedJobId cannot report commit status for the requested target"
                )
            }
        } else {
            permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.EDIT)
        }
        commitStatusService.recordStatus(repositoryId, commitSha, context, state, description, targetUrl)
        return true
    }

    @Field
    suspend fun configureOrchestrator(authentication: AuthenticationContext, agentId: UUID, config: OrchestratorConfigInput): PipelineAgent {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.MANAGE)
        val agent = agentService.findById(agentId)
            ?: throw NoSuchElementException("Agent not found: $agentId")
        if (agent.mode != AgentMode.ORCHESTRATOR) {
            throw IllegalArgumentException("Agent $agentId is not an orchestrator")
        }
        val orchestratorConfig = OrchestratorConfig(
            provider = config.provider,
            credentials = config.credentials,
            defaults = config.defaults,
            runnerProfiles = config.runnerProfiles,
            maxConcurrentVms = config.maxConcurrentVms,
            maxJobTimeoutMinutes = config.maxJobTimeoutMinutes,
            maxVmLifetimeMinutes = config.maxVmLifetimeMinutes,
            alertSinks = config.alertSinks
        )
        val configJson = Json.encodeToString(OrchestratorConfig.serializer(), orchestratorConfig)
        return agentService.updateProviderConfig(agentId, configJson)
    }

    @Field
    suspend fun getOrchestratorConfig(authentication: AuthenticationContext, agentId: UUID): OrchestratorConfig? {
        if (authentication.principal() is ScopedAuthenticatedPrincipal) {
            permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
            verifyAgentCredential(authentication, agentId)
        } else {
            permissionEvaluator.verifyAllowed(authentication, PermissionAction.MANAGE)
        }
        val agent = agentService.findById(agentId)
            ?: throw NoSuchElementException("Agent not found: $agentId")
        val configJson = agent.providerConfig ?: return null
        return Json.decodeFromString(OrchestratorConfig.serializer(), configJson)
    }

    @Field
    suspend fun agentHeartbeat(authentication: AuthenticationContext, agentId: UUID): Boolean {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        verifyAgentCredential(authentication, agentId)
        agentService.heartbeat(agentId)
        return true
    }

    @Field
    suspend fun appendPipelineLogs(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        runId: UUID,
        jobId: UUID,
        stepId: UUID,
        lines: List<LogLineInput>
    ): Boolean {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        val job = jobService.findById(jobId)
            ?: throw NoSuchElementException("Job not found: $jobId")
        verifyJobCredential(authentication, job)
        repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        val run = runService.findById(runId)
            ?: throw NoSuchElementException("Pipeline run not found: $runId")
        val step = jobService.findStepById(stepId)
            ?: throw NoSuchElementException("Pipeline step not found: $stepId")
        if (
            job.pipelineRunId != runId ||
            run.repositoryId != repositoryId ||
            step.pipelineJobId != jobId
        ) {
            throw SecurityException("Pipeline log target does not belong to job $jobId")
        }
        val logLines = lines.map { input ->
            bosca.git.service.LogLine(
                lineNumber = input.lineNumber,
                timestamp = input.timestamp,
                content = input.content,
                stream = when (input.stream?.uppercase()) {
                    "STDERR" -> bosca.git.service.LogStream.STDERR
                    else -> bosca.git.service.LogStream.STDOUT
                }
            )
        }
        logService.appendLog(repositoryId, runId, jobId, stepId, logLines)
        return true
    }

    @Field
    suspend fun decryptPipelineSecrets(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        names: List<String>
    ): List<DecryptedSecret> {
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        permissionEvaluator.verifyAllowed(authentication, repository, PermissionAction.MANAGE)
        return secretService.decryptSecrets(repositoryId, names).map { (name, value) ->
            DecryptedSecret(name, value)
        }
    }

    /**
     * Resolves a RUNNING job's secrets for its agent: only what the pipeline declared
     * (or the legacy repository set when it declared nothing), honoring each secret's environment
     * scope, evaluated against the run's INITIATING principal. Failures name the secret — the agent
     * fails the job loudly, never runs with a silently missing value.
     */
    @Field
    suspend fun resolveJobSecrets(authentication: AuthenticationContext, jobId: UUID): List<DecryptedSecret> {
        permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE)
        val (job, _) = jobAndRepository(authentication, jobId)
        check(job.status == PipelineRunStatus.RUNNING) {
            "Job '${job.name}' is ${job.status} — only a running job resolves secrets"
        }
        val run = runService.findById(job.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${job.pipelineRunId}")
        return secretService.resolveJobSecrets(job, run).map { (name, value) ->
            DecryptedSecret(name, value)
        }
    }

    /**
     * API-token callers may act only as the agent whose credential they presented. Interactive
     * callers retain the surrounding permission checks for administration and diagnostics.
     */
    private suspend fun verifyAgentCredential(
        authentication: AuthenticationContext,
        agentId: UUID,
    ) {
        val scoped = authentication.principal() as? ScopedAuthenticatedPrincipal ?: return
        val agent = agentService.findById(agentId)
            ?: throw NoSuchElementException("Agent not found: $agentId")
        if (agent.apiTokenCredentialId != scoped.credentialId) {
            throw SecurityException("API token is not assigned to agent $agentId")
        }
    }

    /**
     * Permits an ephemeral agent to remove itself and its orchestrator to remove the child it owns.
     */
    private suspend fun verifyAgentOrParentCredential(
        authentication: AuthenticationContext,
        agentId: UUID,
    ) {
        val scoped = authentication.principal() as? ScopedAuthenticatedPrincipal ?: return
        val agent = agentService.findById(agentId)
            ?: throw NoSuchElementException("Agent not found: $agentId")
        if (agent.apiTokenCredentialId == scoped.credentialId) return
        verifyParentAgentCredential(authentication, agent)
    }

    private suspend fun verifyParentAgentCredential(
        authentication: AuthenticationContext,
        agent: PipelineAgent,
    ) {
        val parentAgentId = agent.parentAgentId
            ?: throw SecurityException("Agent ${agent.id} is not owned by an orchestrator")
        verifyAgentCredential(authentication, parentAgentId)
    }

    private suspend fun verifyJobCredential(
        authentication: AuthenticationContext,
        job: PipelineJob,
    ) {
        if (authentication.principal() !is ScopedAuthenticatedPrincipal) return
        val agentId = job.agentId
            ?: throw SecurityException("Job ${job.id} is not assigned to an agent")
        verifyAgentCredential(authentication, agentId)
    }
}

data class AgentRegistrationResult(
    val agent: PipelineAgent,
    val token: String
)

data class ReleaseDeployOutcomeResult(
    val reference: String,
    val status: String,
)

data class AppStoreReviewOutcomeResult(
    val state: String,
    val complete: Boolean,
    val approved: Boolean,
)

data class StoreHealthOutcomeResult(
    val crashRate: Double,
    val maxCrashRate: Double,
    val windowSeconds: Long,
    val healthy: Boolean,
)

data class AppBuildNumberResult(
    val number: Long,
    val value: String,
    val reused: Boolean,
)

@TypeController(type = "GitAppBuildNumber")
class GitAppBuildNumberController : GraphQLController<AppBuildNumberResult> {

    @Field
    fun number(allocation: AppBuildNumberResult): Long = allocation.number

    @Field
    fun value(allocation: AppBuildNumberResult): String = allocation.value

    @Field
    fun reused(allocation: AppBuildNumberResult): Boolean = allocation.reused
}

@TypeController(type = "GitReleaseDeployOutcome")
class GitReleaseDeployOutcomeController : GraphQLController<ReleaseDeployOutcomeResult> {

    @Field
    fun reference(outcome: ReleaseDeployOutcomeResult): String = outcome.reference

    @Field
    fun status(outcome: ReleaseDeployOutcomeResult): String = outcome.status
}

@TypeController("GitAppStoreReviewOutcome")
class GitAppStoreReviewOutcomeController : GraphQLController<AppStoreReviewOutcomeResult> {
    @Field fun state(outcome: AppStoreReviewOutcomeResult): String = outcome.state
    @Field fun complete(outcome: AppStoreReviewOutcomeResult): Boolean = outcome.complete
    @Field fun approved(outcome: AppStoreReviewOutcomeResult): Boolean = outcome.approved
}

@TypeController("GitStoreHealthOutcome")
class GitStoreHealthOutcomeController : GraphQLController<StoreHealthOutcomeResult> {
    @Field fun crashRate(outcome: StoreHealthOutcomeResult): Double = outcome.crashRate
    @Field fun maxCrashRate(outcome: StoreHealthOutcomeResult): Double = outcome.maxCrashRate
    @Field fun windowSeconds(outcome: StoreHealthOutcomeResult): Long = outcome.windowSeconds
    @Field fun healthy(outcome: StoreHealthOutcomeResult): Boolean = outcome.healthy
}

@TypeController(type = "GitPipelineAgentRegistration")
class GitPipelineAgentRegistrationController : GraphQLController<AgentRegistrationResult> {

    @Field
    fun agent(registration: AgentRegistrationResult): PipelineAgent = registration.agent

    @Field
    fun token(registration: AgentRegistrationResult): String = registration.token
}

@TypeController(type = "GitCreateTagResult")
class GitCreateTagResultController : GraphQLController<bosca.git.service.CreateTagResult> {

    @Field
    fun tag(tagged: bosca.git.service.CreateTagResult): String = tagged.tag

    @Field
    fun ref(tagged: bosca.git.service.CreateTagResult): String = tagged.ref

    @Field
    fun commitSha(tagged: bosca.git.service.CreateTagResult): String = tagged.commitSha

    @Field
    fun tagSha(tagged: bosca.git.service.CreateTagResult): String = tagged.tagSha

    @Field
    fun created(tagged: bosca.git.service.CreateTagResult): Boolean = tagged.created
}

@kotlinx.serialization.Serializable
data class OrchestratorConfigInput(
    val provider: String,
    val credentials: bosca.git.model.ProviderCredentials,
    val defaults: bosca.git.model.VmDefaults,
    val runnerProfiles: Map<String, bosca.git.model.VmProfile> = emptyMap(),
    val maxConcurrentVms: Int = 5,
    val maxJobTimeoutMinutes: Int = 60,
    val maxVmLifetimeMinutes: Int = 90,
    val alertSinks: List<bosca.git.model.AlertSink> = emptyList()
)

@kotlinx.serialization.Serializable
data class LogLineInput(
    val lineNumber: Int,
    val timestamp: String,
    val content: String,
    val stream: String? = "stdout"
)

@kotlinx.serialization.Serializable
data class DecryptedSecret(
    val name: String,
    val value: String
)
