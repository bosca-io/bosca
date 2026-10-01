package bosca.git.ci.service

import bosca.db.connectionOrNull
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.git.ci.parser.ExpressionContext
import bosca.git.ci.parser.PipelineExpressionParser
import bosca.git.ci.parser.PipelineJobFilter
import bosca.git.ci.repository.PipelineRunRepository
import bosca.git.model.AgentStatus
import bosca.git.model.ArtifactDefinition
import bosca.git.model.JobDefinition
import bosca.git.model.PipelineConcurrency
import bosca.git.model.PipelineDefinition
import bosca.git.model.PipelineEvent
import bosca.git.model.PipelineExecutionPlan
import bosca.git.model.PipelineJob
import bosca.git.model.PipelinePlanRejectedException
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineTriggerType
import bosca.git.model.WebhookEvent
import bosca.git.model.dispatch
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineArtifactService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineLogService
import bosca.git.service.PipelineRunService
import bosca.git.service.PipelineService
import bosca.git.service.WebhookService
import bosca.kubernetes.service.KubernetesJobDispatchService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

@ServiceImplementation
class PipelineRunServiceImpl(
    private val runRepository: PipelineRunRepository,
    private val jobService: PipelineJobService,
    private val webhookService: WebhookService,
    private val pipelineService: PipelineService,
    private val json: Json,
    private val agentService: PipelineAgentService,
    private val kubernetesDispatchService: ObjectProvider<KubernetesJobDispatchService>,
    private val artifactService: PipelineArtifactService,
    private val logService: PipelineLogService,
) : PipelineRunService {

    override suspend fun createTriggeredRun(
        triggerId: UUID,
        pipelineId: UUID,
        repositoryId: UUID,
        definition: PipelineDefinition,
        commitSha: String,
        ref: String,
        triggerType: PipelineTriggerType,
        triggeredBy: UUID,
    ): PipelineRun? = transaction {
        require(triggerType == PipelineTriggerType.PUSH || triggerType == PipelineTriggerType.TAG) {
            "Only push and tag runs use automatic trigger occurrences"
        }
        if (runRepository.reserveTrigger(triggerId, pipelineId) == 0) return@transaction null
        createRun(pipelineId, repositoryId, definition, commitSha, ref, triggerType, triggeredBy, emptyMap())
    }

    override suspend fun createRun(
        pipelineId: UUID,
        repositoryId: UUID,
        definition: PipelineDefinition,
        commitSha: String,
        ref: String,
        triggerType: PipelineTriggerType,
        triggeredBy: UUID?,
        parameters: Map<String, String>
    ): PipelineRun {
        // Trigger inputs: defaults applied, unknown/invalid submissions rejected, and a
        // promotion validated against the trigger's allowed environments — all BEFORE any row exists,
        // so a bad request never leaves an orphaned run.
        val executionPlan = plan(definition, ref, triggerType, parameters)
        val effectiveParameters = executionPlan.parameters
        if (triggerType == PipelineTriggerType.PROMOTION && definition.environments.isNotEmpty()) {
            validatePromotion(pipelineId, definition, ref, effectiveParameters)
        }
        val selectedJobs = executionPlan.jobs
        val parser = PipelineExpressionParser()
        val runContext = ExpressionContext(
            ref = ref,
            branch = branchOf(ref),
            event = triggerType.name.lowercase(),
            env = definition.env + effectiveParameters,
            extra = effectiveParameters,
        )

        val concurrencyGroup = resolveConcurrencyGroup(definition.concurrency, ref)

        if (concurrencyGroup != null && definition.concurrency?.cancelInProgress == true) {
            cancelSupersededRuns(concurrencyGroup)
        }

        val number = runRepository.getMaxNumber(pipelineId) + 1
        val run = runRepository.create(
            PipelineRun(
                pipelineId = pipelineId,
                repositoryId = repositoryId,
                commitSha = commitSha,
                ref = ref,
                triggerType = triggerType,
                triggeredBy = triggeredBy,
                status = PipelineRunStatus.QUEUED,
                number = number,
                concurrencyGroup = concurrencyGroup,
                parameters = json.encodeToJsonElement(
                    MapSerializer(String.serializer(), String.serializer()),
                    effectiveParameters,
                ),
            )
        )

        // Agents only receive step-level env from the claim-job payload, so the workflow-level env
        // block AND any trigger-time parameters must be flattened into every step here or they never
        // reach the runner. Precedence low→high: workflow env, step env, trigger parameters — so
        // step env overrides the workflow default, and an injected parameter (e.g. a release version)
        // overrides both and can't be shadowed by the definition.
        //
        // Declared artifact coordinates are resolved here for the same reason: this is
        // the one point where ref, the workflow env, and the trigger parameters that carry a release
        // version are all in hand. Storing the concrete coordinate makes artifacts() a plain read.
        // Matrix values aren't known until job expansion, so coordinates resolve against run-level
        // context, not per-matrix.
        val needsStepEnv = definition.env.isNotEmpty() || effectiveParameters.isNotEmpty()
        val hasArtifacts = selectedJobs.values.any {
            it.artifacts.isNotEmpty() || it.requires.isNotEmpty() || it.pipelineRequires.isNotEmpty()
        }
        val jobs = if (!needsStepEnv && !hasArtifacts) {
            selectedJobs
        } else {
            selectedJobs.mapValues { (_, job) ->
                job.copy(
                    steps = if (needsStepEnv) {
                        job.steps.map { step -> step.copy(env = definition.env + step.env + effectiveParameters) }
                    } else {
                        job.steps
                    },
                    artifacts = if (job.artifacts.isEmpty()) {
                        job.artifacts
                    } else {
                        job.artifacts.map { it.copy(coordinate = parser.interpolate(it.coordinate, runContext)) }
                    },
                    // Required-artifact coordinates resolve at the same single point as
                    // produced ones, so the dispatch gate reads concrete coordinates without re-parsing.
                    requires = if (job.requires.isEmpty()) {
                        job.requires
                    } else {
                        job.requires.map { it.copy(coordinate = parser.interpolate(it.coordinate, runContext)) }
                    },
                    // Pipeline-requirement correlation refs resolve here too: an omitted
                    // ref becomes THIS run's ref — a consumer's tag run waits for the provider's run
                    // for the same tag — and an explicit ref template interpolates against the same
                    // context as coordinates. The persisted form is always concrete.
                    pipelineRequires = if (job.pipelineRequires.isEmpty()) {
                        job.pipelineRequires
                    } else {
                        job.pipelineRequires.map { req ->
                            req.copy(ref = req.ref?.let { parser.interpolate(it, runContext) } ?: ref)
                        }
                    },
                )
            }
        }
        jobService.createJobs(run.id, jobs)

        // Gated jobs: evaluate immediately — the required artifacts may already exist
        // and the required upstream runs may already have succeeded, in which case the job dispatches
        // without waiting for an event or the sweep.
        // Resolved lazily to keep the checker→finalizer→runService graph cycle-free at construction.
        if (jobs.values.any { it.requires.isNotEmpty() || it.pipelineRequires.isNotEmpty() }) {
            bosca.di.provide<PipelineRequirementChecker>().checkRun(run.id)
        }

        // Deferred conditions with no pending dependencies (e.g. a lone `if: always()` job, or a
        // failure-routed job whose dependency was excluded by selection) evaluate now — otherwise
        // nothing would ever trigger them. If that settles EVERY job (all skipped), finalize the run
        // here: no job event will ever arrive to do it.
        if (jobs.values.any { it.condition != null }) {
            jobService.evaluateDeferredConditions(run.id)
            val created = jobService.findByRun(run.id)
            if (created.isNotEmpty() && created.all { it.status in TERMINAL_STATUSES }) {
                updateStatus(
                    run.id,
                    when {
                        created.any { it.status == PipelineRunStatus.FAILURE } -> PipelineRunStatus.FAILURE
                        created.any { it.status == PipelineRunStatus.CANCELLED } -> PipelineRunStatus.CANCELLED
                        else -> PipelineRunStatus.SUCCESS
                    },
                )
            }
        }

        log.info("Created pipeline run #{} for pipeline {} on {}", number, pipelineId, ref)
        return run
    }

    override fun plan(
        definition: PipelineDefinition,
        ref: String,
        triggerType: PipelineTriggerType,
        parameters: Map<String, String>,
    ): PipelineExecutionPlan {
        val effectiveParameters = validateTriggerParameters(definition, triggerType, parameters)
        val parser = PipelineExpressionParser()
        val runContext = ExpressionContext(
            ref = ref,
            branch = branchOf(ref),
            event = triggerType.name.lowercase(),
            env = definition.env + effectiveParameters,
            extra = effectiveParameters,
        )
        val conditionSelected = PipelineJobFilter.filter(definition.jobs, runContext, parser)
        val environmentSelected = selectEnvironmentJobs(
            conditionSelected, definition, triggerType, effectiveParameters,
        )
        val selectedJobs = PipelineJobFilter.pruneNeeds(
            environmentSelected.mapValues { (_, job) ->
                val environmentApproval = job.environment?.let { definition.environments[it] }?.approval == true
                val withApproval = if (!job.approval && environmentApproval) job.copy(approval = true) else job
                if (definition.secrets.isEmpty()) {
                    withApproval
                } else {
                    withApproval.copy(secrets = (definition.secrets + withApproval.secrets).distinct())
                }
            }
        )
        rejectPlanUnless(selectedJobs.isNotEmpty()) {
            "Every job in '${definition.name}' was excluded by its if: condition or environment for this " +
                "${triggerType.name.lowercase()} run"
        }
        return PipelineExecutionPlan(effectiveParameters, selectedJobs)
    }

    override suspend fun findById(id: UUID): PipelineRun? {
        return runRepository.findById(id)
    }

    override suspend fun findLatestByPipelineAndRef(pipelineId: UUID, ref: String): PipelineRun? {
        return runRepository.findLatestByPipelineAndRef(pipelineId, ref)
    }

    override suspend fun findByPipeline(pipelineId: UUID, offset: Long, limit: Int): List<PipelineRun> {
        return runRepository.findByPipeline(pipelineId, offset, limit)
    }

    override suspend fun findByRepository(repositoryId: UUID, offset: Long, limit: Int): List<PipelineRun> {
        return runRepository.findByRepository(repositoryId, offset, limit)
    }

    override suspend fun findByReleaseId(releaseId: UUID, offset: Long, limit: Int): List<PipelineRun> {
        return runRepository.findByReleaseId(releaseId.toString(), offset, limit)
    }

    override suspend fun artifacts(runId: UUID): List<ArtifactDefinition> {
        // Coordinates were resolved against the run context when the run was created, so this is a plain
        // read. distinct() collapses the duplicates a matrix job produces — every expanded job row
        // carries the same declared artifacts.
        val serializer = ListSerializer(ArtifactDefinition.serializer())
        return jobService.findByRun(runId)
            .flatMap { json.decodeFromJsonElement(serializer, it.artifacts) }
            .distinct()
    }

    override suspend fun delete(runId: UUID): PipelineRun =
        if (connectionOrNull() == null) {
            deleteLocked(runId)
        } else {
            transaction { deleteLocked(runId) }
        }

    private suspend fun deleteLocked(runId: UUID): PipelineRun {
        val run = runRepository.findByIdForUpdate(runId)
            ?: throw NoSuchElementException("Pipeline run not found: $runId")
        check(run.status in TERMINAL_STATUSES) {
            "Only a terminal pipeline run can be deleted (run #${run.number} is ${run.status})"
        }

        for (job in jobService.findByRun(runId)) {
            for (step in jobService.getSteps(job.id)) {
                logService.deleteStepLog(run.repositoryId, run.id, job.id, step.id)
            }
        }
        for (artifact in artifactService.listByRun(runId)) {
            artifactService.delete(run.repositoryId, run.number, artifact.name)
        }

        val deleted = runRepository.delete(runId)
            ?: throw IllegalStateException("Pipeline run $runId could not be deleted")
        log.info("Deleted pipeline run #{} ({})", deleted.number, deleted.id)
        return deleted
    }

    override suspend fun updateStatus(runId: UUID, status: PipelineRunStatus) {
        val run = runRepository.findById(runId) ?: return
        when (status) {
            PipelineRunStatus.RUNNING -> {
                runRepository.markStarted(runId, status)
                dispatchWebhook(run, WebhookEvent.PIPELINE_RUN_STARTED, status)
            }
            PipelineRunStatus.SUCCESS, PipelineRunStatus.FAILURE,
            PipelineRunStatus.CANCELLED -> {
                runRepository.markFinished(runId, status)
                dispatchWebhook(run, WebhookEvent.PIPELINE_RUN_COMPLETED, status)
            }
            else -> runRepository.updateStatus(runId, status)
        }
        PipelineEvent(
            repositoryId = run.repositoryId,
            pipelineRunId = runId,
            pipelineId = run.pipelineId,
            status = status
        ).dispatch()
    }

    override suspend fun cancelRun(runId: UUID) {
        val run = runRepository.findById(runId) ?: return
        if (run.status != PipelineRunStatus.QUEUED && run.status != PipelineRunStatus.RUNNING) return

        val jobs = jobService.findByRun(runId)
        for (job in jobs) {
            if (job.status == PipelineRunStatus.QUEUED || job.status == PipelineRunStatus.RUNNING) {
                if (jobService.finishIfActive(job.id, PipelineRunStatus.CANCELLED, "Pipeline run cancelled")) {
                    cancelKubernetesDispatch(job)
                    releaseAssignedAgent(job)
                }
            }
        }
        updateStatus(runId, PipelineRunStatus.CANCELLED)
        log.info("Cancelled pipeline run #{} ({})", run.number, runId)
    }

    override suspend fun cancelJob(jobId: UUID): PipelineJob {
        val job = jobService.findById(jobId)
            ?: throw NoSuchElementException("Pipeline job not found: $jobId")
        check(job.status == PipelineRunStatus.QUEUED || job.status == PipelineRunStatus.RUNNING) {
            "Only a queued or running job can be cancelled (job ${job.name} is ${job.status})"
        }
        check(jobService.finishIfActive(jobId, PipelineRunStatus.CANCELLED, "Job cancelled by user")) {
            "Pipeline job $jobId could not be cancelled — its status changed concurrently"
        }
        cancelKubernetesDispatch(job)
        PipelineRunFinalizer(jobService, this, agentService)
            .finalizeJob(jobId, PipelineRunStatus.CANCELLED)
        log.info("Cancelled pipeline job {} ({})", job.name, jobId)
        return jobService.findById(jobId)
            ?: throw NoSuchElementException("Pipeline job not found after cancellation: $jobId")
    }

    override suspend fun rerun(runId: UUID, triggeredBy: UUID?): PipelineRun {
        val original = runRepository.findById(runId)
            ?: throw NoSuchElementException("Pipeline run not found: $runId")

        val pipeline = pipelineService.findById(original.pipelineId)
            ?: throw NoSuchElementException("Pipeline not found: ${original.pipelineId}")

        val definition = pipelineService.parseDefinition(original.repositoryId, original.ref, pipeline.filePath)
            ?: throw IllegalStateException("Failed to parse pipeline: ${pipeline.filePath}")

        // A re-run means THE SAME RUN again: the original trigger type and parameters select the
        // same jobs (a promotion re-run stays a promotion) — only triggered_by records the re-runner.
        val originalParameters = (original.parameters as? JsonObject)
            ?.mapNotNull { (key, value) -> value.jsonPrimitive.contentOrNull?.let { key to it } }
            ?.toMap()
            ?: emptyMap()
        return createRun(
            pipelineId = original.pipelineId,
            repositoryId = original.repositoryId,
            definition = definition,
            commitSha = original.commitSha,
            ref = original.ref,
            triggerType = original.triggerType,
            triggeredBy = triggeredBy,
            parameters = originalParameters
        )
    }

    override suspend fun rerunJob(jobId: UUID, triggeredBy: UUID?): PipelineJob {
        val previous = jobService.findById(jobId)
            ?: throw NoSuchElementException("Pipeline job not found: $jobId")
        check(previous.status == PipelineRunStatus.FAILURE || previous.status == PipelineRunStatus.CANCELLED) {
            "Only a failed or cancelled job can be re-run (job ${previous.name} is ${previous.status})"
        }
        val run = runRepository.findById(previous.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${previous.pipelineRunId}")
        check(run.status == PipelineRunStatus.FAILURE || run.status == PipelineRunStatus.CANCELLED) {
            "Only a failed or cancelled run can re-run a job (run #${run.number} is ${run.status})"
        }

        val reset = jobService.resetForRerun(previous)
            ?: throw IllegalStateException("Pipeline job $jobId could not be reset — its status changed concurrently")
        retireEphemeralAgent(previous)
        val reopened = runRepository.resetForRerun(run.id, triggeredBy)
            ?: throw IllegalStateException("Pipeline run ${run.id} could not be re-opened — its status changed concurrently")

        if (
            reset.requirementsSatisfiedAt == null &&
            (hasEntries(reset.requirements) || hasEntries(reset.pipelineRequirements))
        ) {
            bosca.di.provide<PipelineRequirementChecker>().checkRun(run.id)
        }
        if (previous.condition != null) {
            jobService.evaluateDeferredConditions(run.id)
        }

        PipelineEvent(
            repositoryId = reopened.repositoryId,
            pipelineRunId = reopened.id,
            pipelineId = reopened.pipelineId,
            status = PipelineRunStatus.QUEUED
        ).dispatch()

        log.info("Re-running pipeline job {} ({}) in run #{} ({})", previous.name, jobId, run.number, run.id)
        return reset
    }

    override suspend fun runJobAnyway(jobId: UUID, triggeredBy: UUID, reason: String?): PipelineJob {
        val previous = jobService.findById(jobId)
            ?: throw NoSuchElementException("Pipeline job not found: $jobId")
        check(
            previous.requirementsSatisfiedAt == null &&
                (hasEntries(previous.requirements) || hasEntries(previous.pipelineRequirements))
        ) {
            "Job '${previous.name}' is not waiting on external requirements"
        }
        val run = runRepository.findById(previous.pipelineRunId)
            ?: throw NoSuchElementException("Pipeline run not found: ${previous.pipelineRunId}")

        val bypassed = when (previous.status) {
            PipelineRunStatus.QUEUED -> {
                check(run.status == PipelineRunStatus.QUEUED || run.status == PipelineRunStatus.RUNNING) {
                    "Run #${run.number} is ${run.status} — it cannot dispatch a queued job"
                }
                jobService.bypassRequirements(jobId, triggeredBy, reason)
            }

            PipelineRunStatus.FAILURE, PipelineRunStatus.CANCELLED -> {
                check(run.status == PipelineRunStatus.FAILURE || run.status == PipelineRunStatus.CANCELLED) {
                    "Only a failed or cancelled run can reopen a terminal job (run #${run.number} is ${run.status})"
                }
                val reset = jobService.resetForRerun(previous)
                    ?: throw IllegalStateException(
                        "Pipeline job $jobId could not be reset — its status changed concurrently"
                    )
                retireEphemeralAgent(previous)
                val reopened = runRepository.resetForRerun(run.id, triggeredBy)
                    ?: throw IllegalStateException(
                        "Pipeline run ${run.id} could not be re-opened — its status changed concurrently"
                    )
                val result = jobService.bypassRequirements(reset.id, triggeredBy, reason)
                if (previous.condition != null) {
                    jobService.evaluateDeferredConditions(run.id)
                }
                PipelineEvent(
                    repositoryId = reopened.repositoryId,
                    pipelineRunId = reopened.id,
                    pipelineId = reopened.pipelineId,
                    status = PipelineRunStatus.QUEUED,
                ).dispatch()
                result
            }

            else -> error(
                "Job '${previous.name}' is ${previous.status} — only a queued, failed, or cancelled job can build anyway"
            )
        }

        val finalizer = PipelineRunFinalizer(jobService, this, agentService)
        for (gate in jobService.completeGateJobs(run.id)) {
            finalizer.finalizeJob(gate.id, PipelineRunStatus.SUCCESS, releaseAgent = false)
        }
        log.info(
            "External requirements bypassed for pipeline job {} ({}) by {}",
            previous.name,
            jobId,
            triggeredBy,
        )
        return jobService.findById(jobId) ?: bypassed
    }

    override suspend fun rerunFailedJobs(runId: UUID, triggeredBy: UUID?): PipelineRun {
        val run = runRepository.findById(runId)
            ?: throw NoSuchElementException("Pipeline run not found: $runId")
        check(run.status == PipelineRunStatus.FAILURE || run.status == PipelineRunStatus.CANCELLED) {
            "Only a failed or cancelled run can re-run its failed jobs (run #${run.number} is ${run.status})"
        }
        val toReset = jobService.findByRun(runId)
            .filter { it.status == PipelineRunStatus.FAILURE || it.status == PipelineRunStatus.CANCELLED }
        check(toReset.isNotEmpty()) { "Run #${run.number} has no failed or cancelled jobs to re-run" }

        val resetJobs = toReset.mapNotNull { previous ->
            jobService.resetForRerun(previous)?.let { reset -> previous to reset }
        }
        val stillGated = resetJobs.any { (_, reset) ->
            reset.requirementsSatisfiedAt == null &&
                (hasEntries(reset.requirements) || hasEntries(reset.pipelineRequirements))
        }

        // A targeted ephemeral agent is scoped to a job ID, while a failed-job rerun keeps that
        // same job row and increments its attempt. Retire the prior agent before the transaction
        // becomes visible so a delayed pod from the old attempt cannot claim the new one.
        for ((previous, _) in resetJobs) {
            retireEphemeralAgent(previous)
        }

        val reopened = runRepository.resetForRerun(runId, triggeredBy)
            ?: throw IllegalStateException("Pipeline run $runId could not be re-opened — its status changed concurrently")

        // Still-waiting requirement gates re-evaluate immediately, exactly like after run creation —
        // the world may have changed since the failed attempt.
        if (stillGated) {
            bosca.di.provide<PipelineRequirementChecker>().checkRun(runId)
        }
        if (toReset.any { it.condition != null }) {
            jobService.evaluateDeferredConditions(runId)
        }

        PipelineEvent(
            repositoryId = reopened.repositoryId,
            pipelineRunId = runId,
            pipelineId = reopened.pipelineId,
            status = PipelineRunStatus.QUEUED
        ).dispatch()

        log.info("Re-running {} failed job(s) of pipeline run #{} ({})", toReset.size, run.number, runId)
        return reopened
    }

    private suspend fun retireEphemeralAgent(job: PipelineJob) {
        cancelKubernetesDispatch(job)
        val agentId = job.agentId ?: return
        if (agentService.findById(agentId)?.ephemeral == true) {
            agentService.deregister(agentId)
        }
    }

    private suspend fun cancelKubernetesDispatch(job: PipelineJob) {
        job.kubernetesDispatchId?.let { dispatchId ->
            if (kubernetesDispatchService.exists) {
                kubernetesDispatchService.get().cancel(dispatchId)
            }
        }
    }

    private suspend fun releaseAssignedAgent(job: PipelineJob) {
        val agentId = job.agentId ?: return
        val agent = agentService.findById(agentId) ?: return
        if (agent.ephemeral) {
            agentService.deregister(agentId)
        } else {
            agentService.updateStatus(agentId, AgentStatus.ONLINE)
        }
    }

    private fun hasEntries(element: kotlinx.serialization.json.JsonElement): Boolean =
        (element as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true

    /**
     * Validates a promotion against the environment topology and run history, before
     * any row exists. The chain rule: the stage the target promotes FROM must have completed for
     * this ref — via the release run when the source deploys on release, or a promotion run
     * targeting the source. The downgrade guard: promoting an older version than the environment
     * last received is blocked unless explicitly overridden.
     */
    private suspend fun validatePromotion(
        pipelineId: UUID,
        definition: PipelineDefinition,
        ref: String,
        parameters: Map<String, String>,
    ) {
        val target = parameters.getValue(PROMOTION_ENVIRONMENT_PARAMETER)
        val environment = requireNotNull(definition.environments[target]) {
            "Promotion targets environment '$target', which is not declared in this pipeline's 'environments' block"
        }

        val source = environment.promotesFrom
        if (source != null) {
            val sourceReached =
                (definition.environments[source]?.deployOnRelease == true &&
                    runRepository.findLatestSuccessfulRelease(pipelineId, ref) != null) ||
                    runRepository.findLatestSuccessfulPromotion(pipelineId, ref, source) != null
            require(sourceReached) {
                "Promotion to '$target' requires a successful '$source' stage for $ref first"
            }
        }

        val version = parameters[RELEASE_VERSION_PARAMETER] ?: return
        val lastPromotion = runRepository.findLatestSuccessfulPromotionToEnvironment(pipelineId, target) ?: return
        val deployedVersion = (lastPromotion.parameters as? JsonObject)
            ?.get(RELEASE_VERSION_PARAMETER)?.jsonPrimitive?.contentOrNull ?: return
        if (compareVersions(version, deployedVersion) < 0 && parameters[ALLOW_DOWNGRADE_PARAMETER] != "true") {
            throw IllegalArgumentException(
                "Promotion would DOWNGRADE '$target' from $deployedVersion to $version — " +
                    "pass $ALLOW_DOWNGRADE_PARAMETER=true to override"
            )
        }
    }

    /**
     * Selects the jobs the trigger's environment semantics admit. RELEASE runs take
     * every environment-less job plus jobs bound to a deploy-on-release environment. PROMOTION runs
     * take the target environment's jobs plus the environment-less jobs they transitively `needs:`
     * (gates, notify hooks) — never the whole release phase; a pipeline with no `environments:`
     * block instead keeps every job its `if:` conditions admitted, because conditions are its only
     * selection mechanism. Every other trigger takes only environment-less jobs.
     */
    private fun selectEnvironmentJobs(
        jobs: Map<String, JobDefinition>,
        definition: PipelineDefinition,
        triggerType: PipelineTriggerType,
        parameters: Map<String, String>,
    ): Map<String, JobDefinition> = when (triggerType) {
        PipelineTriggerType.RELEASE -> jobs.filterValues { job ->
            job.environment == null || definition.environments[job.environment]?.deployOnRelease == true
        }
        PipelineTriggerType.PROMOTION -> {
            if (definition.environments.isEmpty()) return jobs
            val target = parameters[PROMOTION_ENVIRONMENT_PARAMETER]
            val included = jobs.filterValues { it.environment == target }.keys.toMutableSet()
            var changed = true
            while (changed) {
                changed = false
                for ((name, job) in jobs) {
                    if (name in included || job.environment != null) continue
                    if (included.any { jobs[it]?.needs?.contains(name) == true }) {
                        included.add(name)
                        changed = true
                    }
                }
            }
            jobs.filterKeys { it in included }
        }
        else -> jobs.filterValues { it.environment == null }
    }

    /**
     * Validates trigger-time parameters against the matching trigger's declared inputs:
     * unknown `inputs.*` submissions are rejected, declared inputs are defaulted and type-checked,
     * and a promotion must name an environment the trigger allows. Returns the effective parameters
     * (submitted + defaults), which flow into step env and the `inputs.*` expression context.
     */
    private fun validateTriggerParameters(
        definition: PipelineDefinition,
        triggerType: PipelineTriggerType,
        parameters: Map<String, String>,
    ): Map<String, String> {
        val trigger = definition.triggers.firstOrNull { it.type == triggerType }

        if (triggerType == PipelineTriggerType.PROMOTION) {
            val environment = parameters[PROMOTION_ENVIRONMENT_PARAMETER]?.takeIf { it.isNotBlank() }
                ?: throw PipelinePlanRejectedException(
                    "A promotion run requires the '$PROMOTION_ENVIRONMENT_PARAMETER' parameter"
                )
            val allowed = trigger?.environments.orEmpty()
            rejectPlanUnless(allowed.isEmpty() || environment in allowed) {
                "'${definition.name}' promotes to ${allowed.joinToString()} — not '$environment'"
            }
            rejectPlanUnless(definition.environments.isEmpty() || environment in definition.environments) {
                "Promotion targets environment '$environment', which is not declared in this pipeline's " +
                    "'environments' block"
            }
        }

        val declared = trigger?.inputs.orEmpty()
        val unknown = parameters.keys
            .filter { it.startsWith(INPUT_PARAMETER_PREFIX) }
            .map { it.removePrefix(INPUT_PARAMETER_PREFIX) }
            .filter { it !in declared }
        rejectPlanUnless(unknown.isEmpty()) {
            "Unknown input(s) ${unknown.joinToString()} — the ${triggerType.name.lowercase()} trigger declares " +
                declared.keys.joinToString().ifEmpty { "no inputs" }
        }
        if (declared.isEmpty()) return parameters

        val effective = parameters.toMutableMap()
        for ((name, input) in declared) {
            val key = INPUT_PARAMETER_PREFIX + name
            val value = effective[key] ?: input.default
                ?: throw PipelinePlanRejectedException("Missing required input '$name' (no default declared)")
            when (input.type) {
                "boolean" -> rejectPlanUnless(value == "true" || value == "false") {
                    "Input '$name' must be true or false, got '$value'"
                }
                "number" -> rejectPlanUnless(value.toDoubleOrNull() != null) {
                    "Input '$name' must be a number, got '$value'"
                }
                "choice" -> rejectPlanUnless(value in input.options) {
                    "Input '$name' must be one of ${input.options.joinToString()}, got '$value'"
                }
            }
            effective[key] = value
        }
        return effective
    }

    private inline fun rejectPlanUnless(condition: Boolean, message: () -> String) {
        if (!condition) throw PipelinePlanRejectedException(message())
    }

    private suspend fun cancelSupersededRuns(concurrencyGroup: String) {
        val activeRuns = runRepository.findActiveByConcurrencyGroup(concurrencyGroup)
        for (run in activeRuns) {
            log.info("Cancelling superseded run #{} in concurrency group '{}'", run.number, concurrencyGroup)
            cancelRun(run.id)
        }
    }

    private suspend fun dispatchWebhook(run: PipelineRun, event: WebhookEvent, status: PipelineRunStatus) {
        try {
            val payload = buildJsonObject {
                put("pipeline_run_id", run.id.toString())
                put("pipeline_id", run.pipelineId.toString())
                put("repository_id", run.repositoryId.toString())
                put("commit_sha", run.commitSha)
                put("ref", run.ref)
                put("number", run.number)
                put("status", status.name.lowercase())
            }.toString()
            webhookService.dispatch(run.repositoryId, event, payload)
        } catch (e: Exception) {
            log.warn("Failed to dispatch {} webhook for run {}: {}", event, run.id, e.message)
        }
    }

    private fun resolveConcurrencyGroup(concurrency: PipelineConcurrency?, ref: String): String? {
        if (concurrency == null) return null
        val context = ExpressionContext(ref = ref, branch = branchOf(ref))
        return PipelineExpressionParser().interpolate(concurrency.group, context)
    }

    /** The branch name for a ref, stripping the `refs/heads/` prefix; other refs (tags) pass through. */
    /**
     * Dotted-numeric version comparison ("6.1.10" > "6.1.9"; missing segments are 0, so
     * "6.1" == "6.1.0"). Non-numeric segments compare as text — release versions here are plain
     * x.y.z tags.
     */
    private fun compareVersions(a: String, b: String): Int {
        val left = a.removePrefix("v").split('.')
        val right = b.removePrefix("v").split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { "0" }
            val r = right.getOrElse(i) { "0" }
            if (l == r) continue
            val ln = l.toLongOrNull()
            val rn = r.toLongOrNull()
            return if (ln != null && rn != null) ln.compareTo(rn) else l.compareTo(r)
        }
        return 0
    }

    private fun branchOf(ref: String): String =
        if (ref.startsWith("refs/heads/")) ref.removePrefix("refs/heads/") else ref

    companion object {
        private val log = LoggerFactory.getLogger(PipelineRunServiceImpl::class.java)

        /** Trigger-input parameters travel under this prefix: `inputs.<name>`. */
        const val INPUT_PARAMETER_PREFIX = "inputs."

        /** The promotion run's target environment key. */
        const val PROMOTION_ENVIRONMENT_PARAMETER = "promotion.environment"

        /** The version a release/promotion run carries — what the downgrade guard compares. */
        const val RELEASE_VERSION_PARAMETER = "release.version"

        /** Set to "true" to promote an OLDER version over a newer one (downgrade guard override). */
        const val ALLOW_DOWNGRADE_PARAMETER = "promotion.allowDowngrade"

        private val TERMINAL_STATUSES = setOf(
            PipelineRunStatus.SUCCESS,
            PipelineRunStatus.FAILURE,
            PipelineRunStatus.CANCELLED,
            PipelineRunStatus.SKIPPED,
        )
    }
}
