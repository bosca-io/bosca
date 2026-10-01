package bosca.git.ci.service

import bosca.di.ObjectProvider
import bosca.git.ci.configuration.KubernetesCiDispatchConfiguration
import bosca.git.ci.repository.PipelineJobRepository
import bosca.git.ci.repository.PipelineStepRepository
import bosca.git.model.ArtifactDefinition
import bosca.git.model.JobDefinition
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineStep
import bosca.git.service.LogLine
import bosca.git.service.LogStream
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineLogService
import bosca.kubernetes.service.KubernetesJobDispatchService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private fun Map<String, String>.toJsonObject(): JsonObject =
    JsonObject(mapValues { (_, v) -> JsonPrimitive(v) })

@ServiceImplementation
class PipelineJobServiceImpl(
    private val jobRepository: PipelineJobRepository,
    private val stepRepository: PipelineStepRepository,
    private val logService: PipelineLogService,
    private val json: Json,
    private val kubernetesDispatch: KubernetesCiDispatchConfiguration,
    private val kubernetesDispatchService: ObjectProvider<KubernetesJobDispatchService>,
) : PipelineJobService {

    override suspend fun createJobs(pipelineRunId: UUID, jobDefinitions: Map<String, JobDefinition>): List<PipelineJob> {
        val jobs = mutableListOf<PipelineJob>()

        for ((jobName, definition) in jobDefinitions) {
            val matrixCombinations = expandMatrix(definition.matrix)

            for (matrixValues in matrixCombinations) {
                val displayName = if (matrixValues.isNotEmpty()) {
                    "$jobName (${matrixValues.values.joinToString(", ")})"
                } else {
                    jobName
                }

                val matrixJson: kotlinx.serialization.json.JsonElement = if (matrixValues.isNotEmpty()) {
                    kotlinx.serialization.json.buildJsonObject {
                        matrixValues.forEach { (k, v) -> put(k, v) }
                    }
                } else kotlinx.serialization.json.JsonObject(emptyMap())

                // The gate's failure clock: the earliest timeout expiry across BOTH requirement
                // kinds. The requirement checker fails a still-unsatisfied job past this, naming
                // the unmet dependencies — a gated job never hangs silently.
                val requirementTimeouts = definition.requires.map { it.timeout } +
                    definition.pipelineRequires.map { it.timeout }

                val job = jobRepository.create(
                    PipelineJob(
                        pipelineRunId = pipelineRunId,
                        name = displayName,
                        runnerLabel = definition.runner,
                        matrixValues = matrixJson,
                        artifacts = json.encodeToJsonElement(ListSerializer(ArtifactDefinition.serializer()), definition.artifacts),
                        requirements = json.encodeToJsonElement(ListSerializer(bosca.git.model.ArtifactRequirement.serializer()), definition.requires),
                        pipelineRequirements = json.encodeToJsonElement(ListSerializer(bosca.git.model.PipelineRequirement.serializer()), definition.pipelineRequires),
                        requirementsDeadline = if (requirementTimeouts.isEmpty()) {
                            null
                        } else {
                            java.time.OffsetDateTime.now()
                                .plusSeconds(requirementTimeouts.min().inWholeSeconds)
                        },
                        // Only DEFERRED conditions survive creation-time selection (PipelineJobFilter
                        // consumes the rest), so a stored condition always means "evaluate when the
                        // dependencies settle".
                        condition = definition.condition,
                        environment = definition.environment,
                        // Resolved by createRun: the job's own approval flag or its environment's policy.
                        approvalRequired = definition.approval,
                        dependsOn = definition.needs,
                        // The declared secret names (pipeline-level flattened in by createRun) — the
                        // server resolves ONLY these for the job.
                        secretNames = definition.secrets,
                        timeoutMinutes = definition.timeout?.inWholeMinutes?.toInt()
                    )
                )

                for ((ordinal, stepDef) in definition.steps.withIndex()) {
                    stepRepository.create(
                        PipelineStep(
                            pipelineJobId = job.id,
                            name = stepDef.name,
                            ordinal = ordinal,
                            uses = stepDef.uses,
                            run = stepDef.run,
                            image = stepDef.image,
                            condition = stepDef.condition,
                            workingDirectory = stepDef.workingDirectory,
                            with = stepDef.with.toJsonObject(),
                            env = stepDef.env.toJsonObject()
                        )
                    )
                }

                jobs.add(job)
            }
        }

        log.info("Created {} jobs for pipeline run {}", jobs.size, pipelineRunId)
        return jobs
    }

    override suspend fun findByRun(pipelineRunId: UUID): List<PipelineJob> {
        return jobRepository.findByRun(pipelineRunId)
    }

    override suspend fun findById(id: UUID): PipelineJob? {
        return jobRepository.findById(id)
    }

    override suspend fun findCurrentByAgent(agentId: UUID): PipelineJob? {
        return jobRepository.findCurrentByAgent(agentId)
    }

    override suspend fun findByAgent(agentId: UUID, limit: Int): List<PipelineJob> {
        return jobRepository.findByAgent(agentId, limit)
    }

    override suspend fun claimJob(agentId: UUID, labels: List<String>): PipelineJob? {
        val abandoned = jobRepository.findCurrentByAgent(agentId)
        if (abandoned != null) {
            log.warn("Agent {} still has running job {} — failing it as abandoned", agentId, abandoned.id)
            val reason = "Agent reconnected without finishing this job; marked as abandoned"
            jobRepository.markFinished(abandoned.id, PipelineRunStatus.FAILURE, reason)
            cascadeStepsToTerminal(abandoned.id, PipelineRunStatus.FAILURE, reason)
        }

        val pollingLabels = if (kubernetesDispatchService.exists) {
            labels.filterNot(kubernetesDispatch.profiles::contains)
        } else {
            labels
        }
        if (pollingLabels.isEmpty()) return null

        val job = jobRepository.findNextAvailable(pollingLabels) ?: return null
        val claimed = jobRepository.claimJobById(
            job.id,
            agentId,
            previousAgentId = null,
            status = PipelineRunStatus.RUNNING,
        )
        if (claimed != null) {
            log.info("Agent {} claimed job {} ({})", agentId, claimed.id, claimed.name)
        }
        return claimed
    }

    override suspend fun claimJobById(
        agentId: UUID,
        jobId: UUID,
        previousAgentId: UUID?,
    ): PipelineJob? {
        val current = jobRepository.findCurrentByAgent(agentId)
        if (current?.id == jobId) return current

        val claimed = jobRepository.claimJobById(
            jobId,
            agentId,
            previousAgentId,
            PipelineRunStatus.RUNNING,
        )
        if (claimed != null) {
            log.info("Agent {} claimed requested job {} ({})", agentId, claimed.id, claimed.name)
        }
        return claimed
    }

    override suspend fun findNextKubernetesDispatchCandidate(
        profiles: List<String>,
    ): PipelineJob? = jobRepository.findNextKubernetesDispatchCandidate(profiles)

    override suspend fun markKubernetesDispatched(
        jobId: UUID,
        dispatchId: UUID,
        agentId: UUID,
    ): PipelineJob? = jobRepository.markKubernetesDispatched(jobId, dispatchId, agentId)

    override suspend fun findUnfinalizedKubernetesDispatched(
        limit: Int,
    ): List<PipelineJob> = jobRepository.findUnfinalizedKubernetesDispatched(limit)

    override suspend fun claimKubernetesFinalization(
        jobId: UUID,
    ): PipelineJob? = jobRepository.claimKubernetesFinalization(jobId)

    override suspend fun updateStatus(jobId: UUID, status: PipelineRunStatus, errorMessage: String?) {
        when (status) {
            PipelineRunStatus.SUCCESS, PipelineRunStatus.FAILURE,
            PipelineRunStatus.CANCELLED, PipelineRunStatus.SKIPPED -> {
                jobRepository.markFinished(jobId, status, errorMessage)
                if (status != PipelineRunStatus.SUCCESS) {
                    val reason = when (status) {
                        PipelineRunStatus.CANCELLED -> "Step terminated because the job was cancelled"
                        PipelineRunStatus.SKIPPED -> "Step skipped because the job's condition evaluated to false"
                        else -> errorMessage ?: "Step terminated because the job failed"
                    }
                    cascadeStepsToTerminal(jobId, status, reason)
                }
            }
            else -> jobRepository.updateStatus(jobId, status)
        }
    }

    override suspend fun finishIfActive(
        jobId: UUID,
        status: PipelineRunStatus,
        errorMessage: String?,
    ): Boolean {
        require(status in TERMINAL_STATUSES) { "Pipeline job terminal status required: $status" }
        if (jobRepository.markFinishedIfActive(jobId, status, errorMessage) == null) return false
        if (status != PipelineRunStatus.SUCCESS) {
            val reason = when (status) {
                PipelineRunStatus.CANCELLED -> "Step terminated because the job was cancelled"
                PipelineRunStatus.SKIPPED -> "Step skipped because the job's condition evaluated to false"
                else -> errorMessage ?: "Step terminated because the job failed"
            }
            cascadeStepsToTerminal(jobId, status, reason)
        }
        return true
    }

    override suspend fun evaluateDeferredConditions(pipelineRunId: UUID) {
        val jobs = jobRepository.findByRun(pipelineRunId)
        val deferred = jobs.filter { it.status == PipelineRunStatus.QUEUED && it.condition != null && it.conditionSatisfiedAt == null }
        if (deferred.isEmpty()) return

        // Cross-aggregate read goes through the run SERVICE; resolved lazily because the run
        // service constructor-injects this job service.
        val run = bosca.di.provide<bosca.git.service.PipelineRunService>().findById(pipelineRunId)
        val byName = jobs.associateBy { it.name }
        val parser = bosca.git.ci.parser.PipelineExpressionParser()

        for (job in deferred) {
            val condition = job.condition ?: continue
            val dependencies = job.dependsOn.mapNotNull { byName[it] }
            if (dependencies.any { it.status !in TERMINAL_STATUSES }) continue

            // Reconstruct the creation-time context (ref/event from the run; env/params from the
            // job's flattened step env, whose dotted keys double as extras) and add the settled
            // dependency outcomes: needs.<dep>.result, plus a dependency-status summary so
            // failure() = any dep failed and cancelled() = any dep cancelled (none failed).
            val stepEnv = stepEnvOf(job.id)
            val needsResults = dependencies.associate { "needs.${it.name}.result" to it.status.name.lowercase() }
            val context = bosca.git.ci.parser.ExpressionContext(
                ref = run?.ref ?: "",
                branch = run?.ref?.removePrefix("refs/heads/") ?: "",
                event = run?.triggerType?.name?.lowercase() ?: "",
                jobStatus = when {
                    dependencies.any { it.status == PipelineRunStatus.FAILURE } -> "failure"
                    dependencies.any { it.status == PipelineRunStatus.CANCELLED } -> "cancelled"
                    else -> "success"
                },
                env = stepEnv,
                extra = stepEnv + needsResults,
            )
            val satisfied = try {
                parser.evaluateBoolean(condition, context)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Deferred condition '{}' on job {} failed to evaluate — skipping the job: {}", condition, job.id, e.message)
                false
            }
            if (satisfied) {
                jobRepository.markConditionSatisfied(job.id)
                log.info("Deferred condition satisfied — job {} ({}) is dispatchable", job.id, job.name)
            } else {
                updateStatus(job.id, PipelineRunStatus.SKIPPED)
                log.info("Deferred condition false — job {} ({}) skipped", job.id, job.name)
            }
        }
    }

    /** The job's first step's flattened env (workflow env + step env + trigger parameters). */
    private suspend fun stepEnvOf(jobId: UUID): Map<String, String> {
        val env = stepRepository.findByJob(jobId).firstOrNull()?.env as? JsonObject ?: return emptyMap()
        return env.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
    }

    override suspend fun findAwaitingRequirements(): List<PipelineJob> =
        jobRepository.findAwaitingRequirements()

    override suspend fun findAwaitingRequirementsByRun(pipelineRunId: UUID): List<PipelineJob> =
        jobRepository.findAwaitingRequirementsByRun(pipelineRunId)

    override suspend fun markRequirementsSatisfied(jobId: UUID) =
        jobRepository.markRequirementsSatisfied(jobId)

    override suspend fun bypassRequirements(jobId: UUID, requestedBy: UUID, reason: String?): PipelineJob {
        val job = jobRepository.findById(jobId)
            ?: throw NoSuchElementException("Pipeline job not found: $jobId")
        check(job.status == PipelineRunStatus.QUEUED) {
            "Job '${job.name}' is ${job.status} — only a queued job can bypass requirements"
        }
        check(hasEntries(job.requirements) || hasEntries(job.pipelineRequirements)) {
            "Job '${job.name}' has no external requirements to bypass"
        }
        check(job.requirementsSatisfiedAt == null) {
            "Job '${job.name}' is no longer waiting on external requirements"
        }
        val normalizedReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        require(normalizedReason == null || normalizedReason.length <= MAX_REQUIREMENT_BYPASS_REASON_LENGTH) {
            "Requirement override reason must be $MAX_REQUIREMENT_BYPASS_REASON_LENGTH characters or fewer"
        }
        return jobRepository.bypassRequirements(jobId, requestedBy, normalizedReason)
            ?: throw IllegalStateException(
                "Job '${job.name}' could not bypass requirements — its status or requirement gate changed concurrently"
            )
    }

    override suspend fun completeGateJobs(pipelineRunId: UUID): List<PipelineJob> {
        val jobs = jobRepository.findByRun(pipelineRunId)
        val byName = jobs.associateBy { it.name }
        val completed = mutableListOf<PipelineJob>()
        for (job in jobs) {
            if (job.status != PipelineRunStatus.QUEUED) continue
            if (stepRepository.findByJob(job.id).isNotEmpty()) continue
            if (!gatesCleared(job, byName)) continue
            if (job.approvalRequired && job.approvedAt == null) continue
            updateStatus(job.id, PipelineRunStatus.SUCCESS)
            log.info("Gate job {} ({}) completed — all gates cleared", job.id, job.name)
            completed.add(job)
        }
        return completed
    }

    override suspend fun approve(jobId: UUID, approvedBy: UUID?, comment: String?): PipelineJob {
        val job = jobRepository.findById(jobId)
            ?: throw NoSuchElementException("Pipeline job not found: $jobId")
        check(job.status == PipelineRunStatus.QUEUED && job.approvalRequired && job.approvedAt == null) {
            "Job '${job.name}' is not awaiting approval"
        }
        // Approve-when-ready: the approval gate engages only once everything else has
        // cleared, so the approver approves the actual, current state — never a pre-authorization
        // that fires later.
        val siblings = jobRepository.findByRun(job.pipelineRunId).associateBy { it.name }
        check(gatesCleared(job, siblings)) {
            "Job '${job.name}' is not ready for approval — its dependencies or requirements are still pending"
        }
        return jobRepository.approve(jobId, approvedBy, comment)
            ?: throw IllegalStateException("Job '${job.name}' was approved or dispatched concurrently")
    }

    override suspend fun rejectApproval(jobId: UUID, rejectedBy: UUID?, comment: String?) {
        val job = jobRepository.findById(jobId)
            ?: throw NoSuchElementException("Pipeline job not found: $jobId")
        check(job.status == PipelineRunStatus.QUEUED && job.approvalRequired && job.approvedAt == null) {
            "Job '${job.name}' is not awaiting approval"
        }
        val reason = buildString {
            append("Approval rejected")
            if (rejectedBy != null) append(" by $rejectedBy")
            if (!comment.isNullOrBlank()) append(": $comment")
        }
        updateStatus(jobId, PipelineRunStatus.FAILURE, reason)
        log.info("Approval rejected for job {} ({})", jobId, job.name)
    }

    override suspend fun isAwaitingApproval(job: PipelineJob): Boolean {
        if (job.status != PipelineRunStatus.QUEUED || !job.approvalRequired || job.approvedAt != null) return false
        val siblings = jobRepository.findByRun(job.pipelineRunId).associateBy { it.name }
        return gatesCleared(job, siblings)
    }

    /**
     * Whether everything BEFORE the approval gate has cleared (gate order: needs →
     * requires → approval): dependencies succeeded (or the deferred condition already evaluated
     * true, which implies the dependencies settled) and any artifact/pipeline requirements are
     * stamped satisfied.
     */
    private fun gatesCleared(job: PipelineJob, siblings: Map<String, PipelineJob>): Boolean {
        val depsCleared = if (job.condition != null) {
            job.conditionSatisfiedAt != null
        } else {
            job.dependsOn.all { siblings[it]?.status == PipelineRunStatus.SUCCESS }
        }
        if (!depsCleared) return false
        val gated = hasEntries(job.requirements) || hasEntries(job.pipelineRequirements)
        return !gated || job.requirementsSatisfiedAt != null
    }

    private fun hasEntries(element: kotlinx.serialization.json.JsonElement): Boolean =
        (element as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true

    /**
     * Drives every non-terminal step of [jobId] to [status]. The agent is
     * the only writer of agent-observed step failures, so server-initiated
     * terminations (abandoned job, reaper, timeout, cancellation) must
     * record [reason] themselves — otherwise the UI shows a red step with
     * no context. For FAILURE, steps that were actually running usually
     * have partial logs in object storage (the agent flushes during
     * execution), so the reason is augmented with the persisted log tail.
     */
    private suspend fun cascadeStepsToTerminal(jobId: UUID, status: PipelineRunStatus, reason: String) {
        val steps = stepRepository.findByJob(jobId).filter {
            it.status != PipelineRunStatus.SUCCESS &&
                it.status != PipelineRunStatus.FAILURE &&
                it.status != PipelineRunStatus.CANCELLED &&
                it.status != PipelineRunStatus.SKIPPED
        }
        if (steps.isEmpty()) return

        val logContext = if (status == PipelineRunStatus.FAILURE &&
            steps.any { it.status == PipelineRunStatus.RUNNING }
        ) {
            resolveLogContext(jobId)
        } else {
            null
        }

        for (step in steps) {
            // Only a step that was running has log output of its own;
            // queued steps get the reason alone.
            val message = if (status == PipelineRunStatus.FAILURE && step.status == PipelineRunStatus.RUNNING) {
                failureMessageWithLogTail(logContext, step.id, reason)
            } else {
                reason
            }
            stepRepository.markFinished(step.id, status, null, message)
        }
    }

    /**
     * Best-effort: composes "<reason>\n\nLast log output:\n<tail>" from the
     * step's persisted log tail, mirroring the summary format the CLI agent
     * writes for agent-observed failures. Falls back to the reason alone on
     * any read failure — deriving a summary must never break the
     * reaper/cancel/cascade flow.
     */
    private suspend fun failureMessageWithLogTail(context: StepLogContext?, stepId: UUID, reason: String): String {
        if (context == null) return reason
        val tail = try {
            summarizeLogTail(
                logService.getLogs(
                    repositoryId = context.repositoryId,
                    runId = context.runId,
                    jobId = context.jobId,
                    stepId = stepId,
                    limit = ERROR_TAIL_LINES,
                    tail = true
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to read log tail for step {} while deriving its error summary: {}", stepId, e.message)
            null
        }
        return if (tail.isNullOrBlank()) reason else "$reason\n\nLast log output:\n$tail"
    }

    /**
     * Mirrors the CLI's StepErrorSummary conventions: prefer stderr —
     * compilers and build tools emit their failure reports there — fall
     * back to the combined tail, join the last lines, and on overflow keep
     * the end (build tools print the decisive line last).
     */
    private fun summarizeLogTail(tail: List<LogLine>): String? {
        val stderr = tail.filter { it.stream == LogStream.STDERR && it.content.isNotBlank() }
        val source = stderr.ifEmpty { tail.filter { it.content.isNotBlank() } }
        if (source.isEmpty()) return null
        val joined = source.takeLast(ERROR_TAIL_LINES).joinToString("\n") { it.content }
        return if (joined.length > ERROR_TAIL_CHARS) joined.takeLast(ERROR_TAIL_CHARS) else joined
    }

    private suspend fun resolveLogContext(jobId: UUID): StepLogContext? {
        return try {
            val job = jobRepository.findById(jobId) ?: return null
            val run = bosca.di.provide<bosca.git.service.PipelineRunService>().findById(job.pipelineRunId) ?: return null
            StepLogContext(repositoryId = run.repositoryId, runId = run.id, jobId = jobId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to resolve log location for job {} while deriving step error summaries: {}", jobId, e.message)
            null
        }
    }

    private data class StepLogContext(val repositoryId: UUID, val runId: UUID, val jobId: UUID)

    override suspend fun resetForRerun(job: PipelineJob): PipelineJob? {
        // A job that failed WAITING (requirements never satisfied) gets a fresh window: the deadline
        // is recomputed from the persisted requirement timeouts, exactly as at creation. Irrelevant
        // when the stamp is already set — the checker never revisits a stamped job.
        val timeouts =
            decodeRequirements(job.requirements, bosca.git.model.ArtifactRequirement.serializer()).map { it.timeout } +
                decodeRequirements(job.pipelineRequirements, bosca.git.model.PipelineRequirement.serializer()).map { it.timeout }
        val deadline = if (timeouts.isEmpty()) {
            null
        } else {
            java.time.OffsetDateTime.now().plusSeconds(timeouts.min().inWholeSeconds)
        }
        val reset = jobRepository.resetForRerun(job.id, deadline) ?: return null
        stepRepository.resetForRerun(job.id)
        log.info("Reset job {} ({}) for re-run — attempt {}", reset.id, reset.name, reset.attempt)
        return reset
    }

    private fun <T> decodeRequirements(
        element: kotlinx.serialization.json.JsonElement,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): List<T> =
        (element as? kotlinx.serialization.json.JsonArray)?.takeIf { it.isNotEmpty() }
            ?.let { json.decodeFromJsonElement(ListSerializer(serializer), it) }
            ?: emptyList()

    override suspend fun findStepById(id: UUID): PipelineStep? {
        return stepRepository.findById(id)
    }

    override suspend fun getSteps(jobId: UUID): List<PipelineStep> {
        return stepRepository.findByJob(jobId)
    }

    override suspend fun updateStepStatus(stepId: UUID, status: PipelineRunStatus, exitCode: Int?, errorMessage: String?) {
        when (status) {
            PipelineRunStatus.RUNNING -> stepRepository.markStarted(stepId, status, exitCode)
            PipelineRunStatus.SUCCESS, PipelineRunStatus.FAILURE,
            PipelineRunStatus.CANCELLED -> stepRepository.markFinished(stepId, status, exitCode, errorMessage)
            else -> stepRepository.updateStatus(stepId, status)
        }
    }


    override suspend fun reapStaleRunningJobs(): List<PipelineJob> {
        val orphaned = jobRepository.findOrphanedRunning()
        val timedOut = jobRepository.findTimedOutRunning()
        val toFail = (orphaned + timedOut).distinctBy { it.id }
        val orphanedIds = orphaned.map { it.id }.toSet()
        for (job in toFail) {
            val reason = if (job.id in orphanedIds) {
                "Agent stopped heartbeating while the job was running"
            } else {
                "Job exceeded its ${job.timeoutMinutes ?: 60} minute timeout"
            }
            cancelKubernetesDispatch(job)
            jobRepository.markFinished(job.id, PipelineRunStatus.FAILURE, reason)
            cascadeStepsToTerminal(job.id, PipelineRunStatus.FAILURE, reason)
        }
        return toFail
    }

    override suspend fun cancelKubernetesDispatch(job: PipelineJob) {
        val dispatchId = job.kubernetesDispatchId ?: return
        if (kubernetesDispatchService.exists) {
            kubernetesDispatchService.get().cancel(dispatchId)
        }
    }


    override suspend fun cancelBlockedJobs(pipelineRunId: UUID): List<PipelineJob> {
        val cancelled = mutableListOf<PipelineJob>()
        while (true) {
            val batch = jobRepository.cancelBlockedJobs(pipelineRunId)
            if (batch.isEmpty()) break
            for (job in batch) {
                cancelKubernetesDispatch(job)
                cascadeStepsToTerminal(
                    job.id, PipelineRunStatus.CANCELLED,
                    "Job was cancelled before this step ran"
                )
            }
            cancelled.addAll(batch)
        }
        if (cancelled.isNotEmpty()) {
            log.info("Cancelled {} blocked jobs for run {}", cancelled.size, pipelineRunId)
        }
        return cancelled
    }

    private fun expandMatrix(matrix: Map<String, List<String>>?): List<Map<String, String>> {
        if (matrix.isNullOrEmpty()) return listOf(emptyMap())

        val keys = matrix.keys.toList()
        val values = keys.map { matrix[it] ?: emptyList() }

        return cartesianProduct(values).map { combination ->
            keys.zip(combination).toMap()
        }
    }

    private fun cartesianProduct(lists: List<List<String>>): List<List<String>> {
        if (lists.isEmpty()) return listOf(emptyList())
        val first = lists.first()
        val rest = cartesianProduct(lists.drop(1))
        return first.flatMap { item -> rest.map { listOf(item) + it } }
    }

    companion object {
        const val MAX_REQUIREMENT_BYPASS_REASON_LENGTH = 1000
        private val log = LoggerFactory.getLogger(PipelineJobServiceImpl::class.java)

        // Mirror the CLI agent's StepErrorSummary limits so server-derived
        // summaries read the same as agent-derived ones in the UI.
        private const val ERROR_TAIL_LINES = 50
        private const val ERROR_TAIL_CHARS = 4000

        private val TERMINAL_STATUSES = setOf(
            PipelineRunStatus.SUCCESS,
            PipelineRunStatus.FAILURE,
            PipelineRunStatus.CANCELLED,
            PipelineRunStatus.SKIPPED,
        )
    }
}
