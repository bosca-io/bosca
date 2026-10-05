package bosca.git.ci.service

import bosca.di.MissingProviderException
import bosca.di.provide
import bosca.git.model.ArtifactRequirement
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRequirement
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.Repository
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.git.service.RequiredArtifactVerifier
import bosca.serialization.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

/**
 * Evaluates queued jobs' requirements and flips the dispatch gate. A gated job (`requires:` in its
 * YAML) is created queued but unclaimable; this checker stamps `requirements_satisfied_at` once
 * every requirement resolves — from four triggers that converge on the same idempotent transition:
 *
 *  1. immediately after run creation (the requirements may already be met),
 *  2. the registry's version-published event (releases an artifact waiter within seconds),
 *  3. the pipeline status event (`bosca.git.pipeline`) — releases a pipeline waiter the moment its
 *     upstream run finishes,
 *  4. the scheduled sweep (the backstop for missed events, and the deadline enforcer).
 *
 * Two requirement kinds share the gate. An ARTIFACT requirement is satisfied when the
 * registry holds the coordinate, verified through the [RequiredArtifactVerifier] SPI. A PIPELINE
 * requirement is satisfied when the upstream repository's named pipeline has a run for
 * the correlation ref (latest by number) that finished SUCCESS — evaluated in-module against run
 * history, because runs are as queryable as artifacts. An upstream run that finished FAILURE or
 * CANCELLED fails the requiring job IMMEDIATELY, naming the dependency — waiting out the deadline
 * would only delay a certain failure.
 *
 * A tag or commit marker may explicitly declare a later patch compatible with an earlier patch in
 * the same major/minor line. Exact current-version requirements are always evaluated first; only a
 * missing pipeline run or artifact falls back to the marker's named ancestor version.
 *
 * A job still unsatisfied past its deadline fails LOUDLY, naming every unmet dependency — a gated
 * job never hangs silently. A transient verification error is logged and retried on the next
 * trigger (the deadline bounds the retrying); a MISSING artifact verifier fails artifact-gated jobs
 * immediately, because a deployment that parses artifact `requires:` but cannot check them has no
 * honest way to dispatch (pipeline-only gates are unaffected — their check needs no SPI).
 */
class PipelineRequirementChecker(
    private val jobService: PipelineJobService,
    private val runService: PipelineRunService,
    private val agentService: PipelineAgentService,
    private val pipelineService: bosca.git.service.PipelineService,
    private val repositoryService: RepositoryService,
    repositoryBrowseService: RepositoryBrowseService,
    private val json: Json,
) {

    private val finalizer = PipelineRunFinalizer(jobService, runService, agentService)
    private val patchLineageResolver = PatchReleaseLineageResolver(repositoryBrowseService)

    /** One evaluation pass over every queued job with unverified requirements. */
    suspend fun checkAwaiting() {
        for (job in jobService.findAwaitingRequirements()) {
            checkJob(job)
        }
    }

    /** Evaluates one run's gated jobs — called right after run creation. */
    suspend fun checkRun(pipelineRunId: UUID) {
        for (job in jobService.findAwaitingRequirementsByRun(pipelineRunId)) {
            checkJob(job)
        }
    }

    private suspend fun checkJob(job: PipelineJob) {
        val artifactRequirements = decode(job.requirements, ArtifactRequirement.serializer())
        val pipelineRequirements = decode(job.pipelineRequirements, PipelineRequirement.serializer())
        if (artifactRequirements.isEmpty() && pipelineRequirements.isEmpty()) return
        val patchLineage = try {
            runService.findById(job.pipelineRunId)?.let { patchLineageResolver.resolve(it) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Patch compatibility is optional. A transient Git read failure must not suppress the
            // ordinary exact requirement check; the next event/sweep can discover the marker.
            log.warn("Patch-release marker lookup failed for job {}: {}", job.id, e.message)
            null
        }

        val pendingPipelines = mutableListOf<String>()
        for (requirement in pipelineRequirements) {
            val state = try {
                evaluatePipelineRequirement(job, requirement, patchLineage)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Transient — retried on the next event/sweep; the deadline bounds how long.
                log.error("Pipeline-requirement evaluation errored for job {}: {}", job.id, e.message, e)
                return
            }
            when (state) {
                is UpstreamState.Succeeded -> Unit
                is UpstreamState.Failed -> {
                    fail(job, state.message)
                    return
                }
                is UpstreamState.Pending -> pendingPipelines.add(state.description)
            }
        }

        val unsatisfiedArtifacts = if (artifactRequirements.isEmpty()) {
            emptyList()
        } else {
            try {
                val verifier = provide<RequiredArtifactVerifier>()
                val exactUnsatisfied = verifier.unsatisfied(artifactRequirements)
                val fallbackByOriginal = if (patchLineage == null) {
                    emptyMap()
                } else {
                    exactUnsatisfied.mapNotNull { requirement ->
                        val coordinate = patchLineage.rewrite(requirement.coordinate)
                        if (coordinate == requirement.coordinate) null
                        else requirement to requirement.copy(coordinate = coordinate)
                    }.toMap()
                }
                if (fallbackByOriginal.isEmpty()) {
                    exactUnsatisfied
                } else {
                    val fallbackUnsatisfied = verifier.unsatisfied(fallbackByOriginal.values.toList()).toSet()
                    val stillUnsatisfied = exactUnsatisfied.filter { original ->
                        fallbackByOriginal[original]?.let { it in fallbackUnsatisfied } ?: true
                    }
                    if (stillUnsatisfied.size < exactUnsatisfied.size) {
                        patchLineage?.let { lineage ->
                            log.info(
                                "Job {} ({}) accepted patch-base artifacts from {} for {}",
                                job.id,
                                job.name,
                                lineage.baseVersion,
                                lineage.currentVersion,
                            )
                        }
                    }
                    stillUnsatisfied
                }
            } catch (e: MissingProviderException) {
                fail(job, "required artifacts cannot be verified — no artifact verifier is available in this deployment")
                return
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Transient — retried on the next event/sweep; the deadline bounds how long.
                log.error("Requirement verification errored for job {}: {}", job.id, e.message, e)
                return
            }
        }

        if (pendingPipelines.isEmpty() && unsatisfiedArtifacts.isEmpty()) {
            jobService.markRequirementsSatisfied(job.id)
            log.info("Job {} ({}) requirements satisfied — dispatchable", job.id, job.name)
            // A stamped GATE job (steps-less) completes server-side right now — the
            // requirement event was the last thing it was waiting on.
            for (gate in jobService.completeGateJobs(job.pipelineRunId)) {
                finalizer.finalizeJob(gate.id, PipelineRunStatus.SUCCESS, releaseAgent = false)
            }
            return
        }
        val deadline = job.requirementsDeadline
        if (deadline != null && deadline.isBefore(OffsetDateTime.now())) {
            val unmet = unsatisfiedArtifacts.map { "${it.namespace}/${it.coordinate} (${it.type})" } + pendingPipelines
            fail(job, "requirements were not satisfied before the deadline: ${unmet.joinToString()}")
        }
    }

    /**
     * Resolves one pipeline requirement against upstream run history. Every lookup miss is PENDING,
     * not an error: the upstream repository may not be tagged yet, its pipeline may not have synced
     * yet, and its run may not exist yet — all normal orderings the deadline bounds.
     */
    private suspend fun evaluatePipelineRequirement(
        job: PipelineJob,
        requirement: PipelineRequirement,
        patchLineage: PatchReleaseLineage?,
    ): UpstreamState {
        val repository = resolveUpstreamRepository(job, requirement.repository)
            ?: return UpstreamState.Pending("pipeline '${requirement.pipeline}' — repository '${requirement.repository}' not found")
        val pipeline = pipelineService.findByRepositoryAndName(repository.id, requirement.pipeline)
            ?: return UpstreamState.Pending("pipeline '${requirement.pipeline}' not found in '${requirement.repository}'")
        val ref = requirement.ref
            ?: runService.findById(job.pipelineRunId)?.ref
            ?: return UpstreamState.Pending("pipeline '${requirement.pipeline}' ('${requirement.repository}') — correlation ref unresolved")
        var resolvedRef = ref
        var run: PipelineRun? = runService.findLatestByPipelineAndRef(pipeline.id, resolvedRef)
        if (run == null && patchLineage != null) {
            val patchRef = patchLineage.rewrite(ref)
            if (patchRef != ref) {
                resolvedRef = patchRef
                run = runService.findLatestByPipelineAndRef(pipeline.id, resolvedRef)
                if (run != null) {
                    log.info(
                        "Job {} ({}) accepted patch-base pipeline {} at {} for {}",
                        job.id,
                        job.name,
                        requirement.pipeline,
                        resolvedRef,
                        ref,
                    )
                }
            }
        }
        if (run == null) {
            val fallback = if (resolvedRef == ref) "" else " or patch base $resolvedRef"
            return UpstreamState.Pending(
                "pipeline '${requirement.pipeline}' ('${requirement.repository}') has no run for $ref$fallback"
            )
        }
        return when (run.status) {
            PipelineRunStatus.SUCCESS -> UpstreamState.Succeeded
            PipelineRunStatus.FAILURE, PipelineRunStatus.CANCELLED, PipelineRunStatus.SKIPPED ->
                UpstreamState.Failed(
                    "required pipeline '${requirement.pipeline}' ('${requirement.repository}') " +
                        "run #${run.number} for $resolvedRef finished ${run.status}"
                )
            else -> UpstreamState.Pending(
                "pipeline '${requirement.pipeline}' ('${requirement.repository}') " +
                    "run #${run.number} for $resolvedRef is ${run.status}"
            )
        }
    }

    /**
     * Resolves a requirement's repository reference: `owner/slug` looks up across namespaces; a bare
     * `slug` resolves within the REQUIRING repository's owner namespace — sibling repositories, the
     * common case, so platform YAML never hardcodes an owner.
     */
    private suspend fun resolveUpstreamRepository(job: PipelineJob, reference: String): Repository? {
        if ("/" in reference) {
            return repositoryService.findByOwnerAndSlug(reference.substringBefore("/"), reference.substringAfter("/"))
        }
        val run = runService.findById(job.pipelineRunId) ?: return null
        val requiring = repositoryService.findById(run.repositoryId) ?: return null
        if (requiring.slug == reference) return requiring
        return repositoryService.findByOwner(requiring.ownerId).firstOrNull { it.slug == reference }
    }

    private fun <T> decode(element: kotlinx.serialization.json.JsonElement, serializer: KSerializer<T>): List<T> =
        (element as? JsonArray)?.takeIf { it.isNotEmpty() }
            ?.let { json.decodeFromJsonElement(ListSerializer(serializer), it) }
            ?: emptyList()

    private suspend fun fail(job: PipelineJob, message: String) {
        log.warn("Failing job {} ({}) — {}", job.id, job.name, message)
        jobService.updateStatus(job.id, PipelineRunStatus.FAILURE, message)
        finalizer.finalizeJob(job.id, PipelineRunStatus.FAILURE, releaseAgent = false)
    }

    private sealed interface UpstreamState {
        data object Succeeded : UpstreamState
        data class Failed(val message: String) : UpstreamState
        data class Pending(val description: String) : UpstreamState
    }

    companion object {
        private val log = LoggerFactory.getLogger(PipelineRequirementChecker::class.java)
    }
}
