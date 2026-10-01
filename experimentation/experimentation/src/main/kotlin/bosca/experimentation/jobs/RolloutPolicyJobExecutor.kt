package bosca.experimentation.jobs

import bosca.di.provide
import bosca.db.transaction
import bosca.observability.ErrorCapture
import bosca.experimentation.configuration.JobQueueNames
import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FeatureFlagInput
import bosca.experimentation.model.RolloutPolicy
import bosca.experimentation.model.RolloutPolicyEvent
import bosca.experimentation.model.RolloutPolicyMode
import bosca.experimentation.model.Rollout
import bosca.experimentation.model.TargetingRule
import bosca.experimentation.model.Variation
import bosca.experimentation.model.VariationWeight
import bosca.experimentation.repository.AnalysisReportRepository
import bosca.experimentation.repository.ExperimentRepository
import bosca.experimentation.repository.FeatureFlagRepository
import bosca.experimentation.repository.RolloutPolicyEventRepository
import bosca.experimentation.service.ExperimentService
import bosca.experimentation.service.FeatureFlagService
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.enqueueLater
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import kotlin.time.Duration as KDuration
import java.time.Duration as JDuration

/**
 * Applies rollout policy decisions to a running experiment.
 *
 * Chained from [ExperimentAnalysisJobExecutor] at the end of every
 * analysis run (when the experiment has an attached policy and is
 * RUNNING), and separately enqueued on a delay for
 * [RolloutPolicyMode.SCHEDULED_STEPS] experiments by
 * [bosca.experimentation.service.ExperimentServiceImpl.setStatus] on
 * the DRAFT → RUNNING transition (each successful step also
 * enqueues the next one).
 *
 * This executor is deliberately thin: the interesting logic — the
 * "given this state, what do we do?" decision — lives in
 * [decide] / [RolloutPolicyDecider]. The executor only:
 *
 *   1. Loads experiment, policy, flag, attached rule, latest
 *      analysis report (for the verdict).
 *   2. Derives the current weight map from the attached rule.
 *   3. Calls [decide].
 *   4. For non-Hold actions, builds a new [FeatureFlagInput] with
 *      the replacement rollout and calls
 *      [FeatureFlagService.edit]. For Halt and Complete actions,
 *      also calls [ExperimentService.setStatus].
 *   5. Writes a [RolloutPolicyEvent] row with the before/after
 *      weight snapshots.
 *   6. For scheduled wakes, enqueues the next scheduled step.
 *
 * The transaction-scoped experiment lock serializes traffic changes with
 * definition edits, and the transaction commits flag, status, and audit writes
 * together.
 */
@JobDefinition(RolloutPolicyJob::class, JobQueueNames.experimentationJobQueue, "rollout-policy")
class RolloutPolicyJobExecutor : AbstractJobExecutor<RolloutPolicyJob>(
    RolloutPolicyJob.serializer()
) {
    override suspend fun execute() {
        val job = getJobDefinition()
        transaction {
            runRolloutPolicy(
                job = job,
                experimentRepository = provide(),
                experimentService = provide(),
                flagService = provide(),
                flagRepository = provide(),
                eventRepository = provide(),
                analysisReportRepository = provide(),
                jobQueue = provide(JobQueueNames.experimentationJobQueue),
                json = provide(),
                errorCapture = provide(),
            )
        }
    }

    companion object {
        internal val log = LoggerFactory.getLogger(RolloutPolicyJobExecutor::class.java)
    }
}

// =====================================================================
// Pure orchestrator + helpers, lifted out of the executor class so
// they are reachable from unit tests without an `AbstractJobExecutor`
// instance or DI plumbing. The executor's `execute()` method is the
// thinnest possible shim that calls `runRolloutPolicy` with collaborators
// resolved from `provide<>()`; everything else lives here.
// =====================================================================

private val WEIGHT_MAP_SERIALIZER = MapSerializer(String.serializer(), Int.serializer())

/**
 * Executes a rollout-controller decision against the supplied collaborators.
 * Production callers must keep the surrounding transaction open so
 * [ExperimentRepository.getByIdForUpdate] protects every resulting side effect.
 * Dependencies remain explicit so tests can assert the I/O behavior directly.
 */
suspend fun runRolloutPolicy(
    job: RolloutPolicyJob,
    experimentRepository: ExperimentRepository,
    experimentService: ExperimentService,
    flagService: FeatureFlagService,
    flagRepository: FeatureFlagRepository,
    eventRepository: RolloutPolicyEventRepository,
    analysisReportRepository: AnalysisReportRepository,
    jobQueue: JobQueue,
    json: Json,
    errorCapture: ErrorCapture = ErrorCapture.Noop,
) {
    val log = RolloutPolicyJobExecutor.log

    // execute() keeps this row lock through every flag/status/audit write. A
    // concurrent definition edit therefore commits either entirely before this
    // fresh read or entirely after the rollout action; an obsolete report can
    // never act across the edit boundary.
    val experiment = experimentRepository.getByIdForUpdate(job.experimentId)
    if (experiment == null) {
        log.warn("Experiment not found: {} — skipping rollout policy", job.experimentId)
        return
    }
    if (experiment.status != ExperimentStatus.RUNNING) {
        // Wake-ups fired on a paused/completed experiment are
        // no-ops. The decider would HOLD on most non-RUNNING states
        // anyway, but bailing out early avoids the cost of loading
        // the rest of the state and is the documented behavior the
        // executor tests assert against.
        log.info(
            "Experiment {} is {} — rollout policy controller wake ignored",
            experiment.id, experiment.status,
        )
        return
    }

    val policy = experiment.rolloutPolicy?.let { parseRolloutPolicy(json, it) }
    if (policy == null) {
        log.info("Experiment {} has no rollout policy; controller wake ignored", experiment.id)
        return
    }

    val flag = flagRepository.getById(experiment.featureFlagId) ?: run {
        log.warn("Flag {} not found for experiment {}", experiment.featureFlagId, experiment.id)
        return
    }
    val rules = parseTargetingRulesList(json, flag.targetingRules)
    val attachedRule = experiment.targetingRuleId?.let { id -> rules.find { it.id == id } }
    if (attachedRule == null) {
        log.warn(
            "Experiment {} is attached to rule {} which no longer exists on flag {}",
            experiment.id, experiment.targetingRuleId, flag.id,
        )
        return
    }

    val currentWeights = attachedRule.rollout.variationWeights.associate { it.variationKey to it.weight }
    require(policy.treatmentVariationKey != experiment.controlVariationKey) {
        "Experiment ${experiment.id} rollout policy treatment must differ from its control"
    }
    require(policy.treatmentVariationKey in currentWeights) {
        "Experiment ${experiment.id} rollout treatment '${policy.treatmentVariationKey}' is absent from the attached rule"
    }
    require(experiment.controlVariationKey in currentWeights) {
        "Experiment ${experiment.id} control '${experiment.controlVariationKey}' is absent from the attached rule"
    }
    // Decode the latest analysis report once and feed both
    // verdict + confidence helpers from the same record. The
    // alternative — calling each helper independently — would
    // double-decode and could produce inconsistent state if a
    // background job wrote a new report between the two calls.
    val latestDetails = readLatestAnalysisDetails(analysisReportRepository, experiment)
    val analysisDetails = latestDetails?.takeIf { isCurrentAnalysisDetails(it, experiment) }
    val verdict = readLatestVerdictFromDetails(analysisDetails, experiment.id)
    val primaryConfidence = readLatestPrimaryConfidenceFromDetails(
        analysisDetails, policy.treatmentVariationKey,
    )

    val decision = decide(
        RolloutDecisionInput(
            policy = policy,
            verdict = verdict,
            primaryConfidence = primaryConfidence,
            currentWeights = currentWeights,
            controlVariationKey = experiment.controlVariationKey,
            scheduledStepIndex = job.scheduledStepIndex,
        )
    )

    when (decision) {
        is Action.Advance -> {
            applyRolloutFlagEdit(flagService, flag, rules, attachedRule, decision.newWeights, json)
            writeRolloutEvent(eventRepository, experiment.id, decision, currentWeights, decision.newWeights, json, errorCapture)
            // For scheduled mode, enqueue the next wake. Adaptive
            // modes rely on the next analysis run to trigger the
            // next controller wake.
            if (policy.mode == RolloutPolicyMode.SCHEDULED_STEPS && job.scheduledStepIndex != null) {
                enqueueNextScheduledStep(jobQueue, experiment.id, policy, job.scheduledStepIndex, errorCapture)
            }
        }
        is Action.Complete -> {
            applyRolloutFlagEdit(flagService, flag, rules, attachedRule, decision.newWeights, json)
            experimentService.setStatus(experiment.id, ExperimentStatus.COMPLETED)
            writeRolloutEvent(eventRepository, experiment.id, decision, currentWeights, decision.newWeights, json, errorCapture)
        }
        is Action.Halt -> {
            applyRolloutFlagEdit(flagService, flag, rules, attachedRule, decision.newWeights, json)
            // PAUSED not COMPLETED: the operator can resume the
            // experiment after investigating the guardrail.
            experimentService.setStatus(experiment.id, ExperimentStatus.PAUSED)
            writeRolloutEvent(eventRepository, experiment.id, decision, currentWeights, decision.newWeights, json, errorCapture)
        }
        is Action.Hold -> {
            writeRolloutEvent(eventRepository, experiment.id, decision, currentWeights, currentWeights, json, errorCapture)
        }
    }

    log.info(
        "Rollout policy controller: experiment={} verdict={} action={} reason={}",
        experiment.id, verdict, decision.type, decision.reason,
    )
}

/**
 * Decodes the rollout policy JSONB column into its typed form.
 * Throws on any deserialization failure — a malformed policy is a
 * data-integrity bug that must not be silently swallowed, because
 * swallowing it would leave the controller operating on a
 * phantom-null policy while the operator believes they have
 * automated rollout configured.
 */
internal fun parseRolloutPolicy(json: Json, element: JsonElement): RolloutPolicy =
    json.decodeFromJsonElement(RolloutPolicy.serializer(), element)

/**
 * Decodes the feature flag's `targetingRules` JSONB column. A
 * null column means "no rules attached" and is a valid state;
 * anything else must deserialize or the experiment cannot be
 * analyzed safely. No catch — failures bubble up to the job
 * runner, which logs and retries per its failure policy.
 */
internal fun parseTargetingRulesList(json: Json, rulesJson: JsonElement?): List<TargetingRule> {
    if (rulesJson == null) return emptyList()
    return json.decodeFromJsonElement(ListSerializer(TargetingRule.serializer()), rulesJson)
}

/**
 * `kotlinx.serialization` instance configured for the analysis-
 * report details schema. `ignoreUnknownKeys` lets the consumer
 * accept additive changes from the producer (or external writers)
 * without a coordinated deploy.
 */
private val ANALYSIS_DETAILS_JSON: Json = Json { ignoreUnknownKeys = true }

/** A report is current only for the exact experiment definition that produced it. */
internal fun isCurrentAnalysisDetails(
    details: AnalysisReportDetails,
    experiment: Experiment,
): Boolean =
    details.controlVariationKey == experiment.controlVariationKey &&
        details.experimentRevision == experiment.analysisRevision

/**
 * Decodes the latest analysis report's `details` column into the
 * typed [AnalysisReportDetails] shape, or `null` when no report
 * exists yet. A present-but-malformed report throws — silently
 * treating it as "no report" would make the controller advance
 * rollouts against stale data, which is the opposite of safe.
 */
internal suspend fun readLatestAnalysisDetails(
    reports: AnalysisReportRepository,
    experiment: Experiment,
): AnalysisReportDetails? {
    val latest = reports.getLatestForRevision(
        experiment.id,
        experiment.controlVariationKey,
        experiment.analysisRevision,
    ).firstOrNull() ?: return null
    return ANALYSIS_DETAILS_JSON.decodeFromJsonElement(
        AnalysisReportDetails.serializer(),
        latest.details,
    )
}

internal fun readLatestVerdictFromDetails(
    details: AnalysisReportDetails?,
    experimentId: UUID,
): Verdict {
    val name = details?.verdict?.takeIf { it.isNotEmpty() } ?: return Verdict.NO_DATA
    return try {
        Verdict.valueOf(name)
    } catch (_: IllegalArgumentException) {
        RolloutPolicyJobExecutor.log.warn(
            "Unknown verdict '{}' on analysis report for experiment {}", name, experimentId,
        )
        Verdict.NO_DATA
    }
}

/**
 * Reads the primary goal's winning variation confidence from the
 * latest analysis report. Used by adaptive modes to compare against
 * the policy's `minConfidence`. Returns the max confidence across
 * primary goal winners on the named treatment variation; null when
 * no primary goal produced a winner.
 *
 * Bayesian runs expose `probabilityBeatsControl` via the same
 * [VariationDetails.confidence] field because the analyzer
 * normalizes both methods into one column on the producer side
 * (see Phase 4 in the experiments-v2 spec). Consumers therefore
 * never need to branch on the analysis method.
 */
internal fun readLatestPrimaryConfidenceFromDetails(
    details: AnalysisReportDetails?,
    treatmentVariationKey: String,
): Double? {
    if (details == null) return null
    var best: Double? = null
    for (goal in details.goals) {
        if (goal.role != ConversionGoalRole.PRIMARY) continue
        for (row in goal.variations) {
            if (row.variationKey != treatmentVariationKey) continue
            val conf = row.confidence ?: continue
            val current = best
            if (current == null || conf > current) best = conf
        }
    }
    return best
}

/**
 * Rewrites the attached rule's rollout to [newWeights] and calls
 * [FeatureFlagService.edit] with a fresh [FeatureFlagInput]
 * mirroring the current flag's state otherwise. Going through
 * `edit` (not a repository-level update) ensures the existing
 * `FlagUpdated` publish and the exposure-prune side-effects
 * fire exactly as they would for a human edit.
 *
 * Zero weights are retained. They serve no traffic, but preserving the entries keeps the
 * experiment's analysis topology available after a 100% rollout or guardrail halt.
 */
internal suspend fun applyRolloutFlagEdit(
    flagService: FeatureFlagService,
    flag: FeatureFlag,
    rules: List<TargetingRule>,
    attachedRule: TargetingRule,
    newWeights: Map<String, Int>,
    json: Json,
) {
    val newRule = attachedRule.copy(
        rollout = Rollout(
            variationWeights = newWeights
                .map { (k, w) -> VariationWeight(variationKey = k, weight = w) }
        )
    )
    val updatedRules = rules.map { if (it.id == attachedRule.id) newRule else it }
    val updatedRulesJson = json.encodeToJsonElement(
        ListSerializer(TargetingRule.serializer()),
        updatedRules,
    )
    val input = FeatureFlagInput(
        key = flag.key,
        name = flag.name,
        description = flag.description,
        type = flag.type,
        variations = flag.variations,
        defaultVariationKey = flag.defaultVariationKey,
        targetingRules = updatedRulesJson,
        status = flag.status,
    )
    flagService.edit(flag.id, input)
}

internal suspend fun writeRolloutEvent(
    repository: RolloutPolicyEventRepository,
    experimentId: UUID,
    action: Action,
    oldWeights: Map<String, Int>,
    newWeights: Map<String, Int>,
    json: Json,
    errorCapture: ErrorCapture = ErrorCapture.Noop,
) {
    runCatching {
        repository.add(
            RolloutPolicyEvent(
                experimentId = experimentId,
                action = action.type,
                reason = action.reason,
                oldWeights = json.encodeToJsonElement(WEIGHT_MAP_SERIALIZER, oldWeights),
                newWeights = json.encodeToJsonElement(WEIGHT_MAP_SERIALIZER, newWeights),
            )
        )
    }.onFailure { e ->
        RolloutPolicyJobExecutor.log.error(
            "Failed to write rollout policy event for experiment {}", experimentId, e,
        )
        errorCapture.capture(e, null, mapOf("experimentId" to experimentId.toString(), "location" to "writeRolloutEvent"))
    }
}

/**
 * After a scheduled-step Advance, enqueue the subsequent step's
 * wake at `now + steps[next].afterDuration`. Called only for
 * the Advance path; Complete terminates the chain, Halt pauses
 * the experiment (subsequent wakes will no-op on status check),
 * Hold keeps the existing schedule intact.
 */
internal suspend fun enqueueNextScheduledStep(
    jobQueue: JobQueue,
    experimentId: UUID,
    policy: RolloutPolicy,
    currentIndex: Int,
    errorCapture: ErrorCapture = ErrorCapture.Noop,
) {
    val steps = policy.steps ?: return
    val nextIndex = currentIndex + 1
    if (nextIndex >= steps.size) return
    val afterIso = steps[nextIndex].afterDuration ?: run {
        RolloutPolicyJobExecutor.log.warn(
            "Scheduled step {} on experiment {} missing afterDuration; next wake will not be scheduled",
            nextIndex, experimentId,
        )
        return
    }
    val delay = try {
        val jd = JDuration.parse(afterIso)
        KDuration.parse("${jd.toMillis()}ms")
    } catch (e: Exception) {
        RolloutPolicyJobExecutor.log.error(
            "Invalid afterDuration '{}' on experiment {} step {}: {}",
            afterIso, experimentId, nextIndex, e.message,
        )
        errorCapture.capture(e, null, mapOf("experimentId" to experimentId.toString(), "stepIndex" to nextIndex, "afterDuration" to afterIso))
        return
    }
    runCatching {
        RolloutPolicyJob(experimentId = experimentId, scheduledStepIndex = nextIndex)
            .enqueueLater(
                queue = jobQueue,
                executor = RolloutPolicyJobExecutor::class,
                timeout = delay,
            )
    }.onFailure { e ->
        RolloutPolicyJobExecutor.log.error(
            "Failed to enqueue next scheduled rollout step {} for experiment {}",
            nextIndex, experimentId, e,
        )
        errorCapture.capture(e, null, mapOf("experimentId" to experimentId.toString(), "stepIndex" to nextIndex))
    }
}
