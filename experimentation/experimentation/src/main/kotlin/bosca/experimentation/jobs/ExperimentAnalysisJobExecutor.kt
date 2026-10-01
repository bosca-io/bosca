package bosca.experimentation.jobs

import bosca.di.provide
import bosca.experimentation.configuration.JobQueueNames
import bosca.experimentation.model.AiInsights
import bosca.experimentation.model.AnalysisReport
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.TargetingRule
import bosca.experimentation.model.Variation
import bosca.experimentation.repository.AnalysisReportRepository
import bosca.experimentation.repository.FeatureFlagRepository
import bosca.experimentation.service.ExperimentService
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.enqueue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

/**
 * Analyzes experiment results in the variation-based model. The variations being
 * compared come from the parent flag's variation palette, restricted to the ones
 * the experiment's attached rule rollout splits across.
 *
 * Delegates the statistical reasoning to [runDeterministicAnalysis] in
 * `ExperimentAnalysis.kt`, which produces a structured [DeterministicAnalysis]
 * containing the SRM check, per-goal verdicts, and lift confidence intervals.
 * This executor's job is to load the inputs, persist the deterministic
 * prose as the authoritative report, and — when an [ExperimentAiAnalyzer]
 * is wired — additionally call out to the LLM for *insights on top of*
 * the deterministic verdict (hypothesis reconciliation, cross-goal
 * patterns, follow-up suggestions). The AI output is persisted to a
 * separate `aiInsights` column on the report and is strictly additive:
 * the AI never overrides `summary` or `recommendation`, and any AI
 * payload that fails the verdict-consistency check inside
 * [GenAiExperimentAnalyzer.ensureConsistentWithVerdict] is dropped.
 *
 * The control variation is the experiment's persisted
 * [bosca.experimentation.model.Experiment.controlVariationKey]. The executor
 * restricts the flag palette to the experiment's involved variations before
 * analysis, so an invalid or stale control fails loudly.
 */
@JobDefinition(ExperimentAnalysisJob::class, JobQueueNames.experimentationJobQueue, "analyze-experiment")
class ExperimentAnalysisJobExecutor : AbstractJobExecutor<ExperimentAnalysisJob>(
    ExperimentAnalysisJob.serializer()
) {
    override suspend fun execute() {
        val job = getJobDefinition()
        val aiAnalyzer: ExperimentAiAnalyzer? = runCatching { provide<ExperimentAiAnalyzer>() }
            .onFailure { log.warn("No ExperimentAiAnalyzer registered in DI; skipping AI step: {}", it.message) }
            .getOrNull()
        runExperimentAnalysis(
            job = job,
            experimentService = provide(),
            analysisReportRepository = provide(),
            flagRepository = provide(),
            json = provide(),
            aiAnalyzer = aiAnalyzer,
            jobQueue = provide(JobQueueNames.experimentationJobQueue),
            aggregator = { experimentId -> aggregateExperimentResults(experimentId) },
        )
    }

    /**
     * Builds the typed [AnalysisReportDetails] for the supplied
     * deterministic analysis. The repository serializes the result
     * to a JSONB column via the report's `details: JsonElement`
     * field; the consumers ([RolloutPolicyJobExecutor] and the
     * admin UI) decode the same typed shape on read, so the JSON
     * column is an implementation detail rather than a structural
     * boundary.
     */
    companion object {
        internal val log = LoggerFactory.getLogger(ExperimentAnalysisJobExecutor::class.java)
    }
}

// =====================================================================
// Pure orchestrator, extracted from the executor class so the full
// `execute()` path can be unit-tested without `AbstractJobExecutor` /
// `provide<>()` plumbing. The class above is now a thin shell that
// resolves collaborators from DI and delegates here.
// =====================================================================

/**
 * Runs the analysis pipeline end-to-end: aggregate results, load
 * flag + goals + results, call [runDeterministicAnalysis], attach
 * optional AI commentary, persist the report, and chain the rollout
 * controller when the experiment has an attached policy.
 *
 * [aggregator] is a suspend function reference so tests can inject
 * a no-op that seeds results directly via the repository instead of
 * running the real [aggregateExperimentResults], which requires a
 * live Trino connection.
 */
suspend fun runExperimentAnalysis(
    job: ExperimentAnalysisJob,
    experimentService: ExperimentService,
    analysisReportRepository: AnalysisReportRepository,
    flagRepository: FeatureFlagRepository,
    json: Json,
    aiAnalyzer: ExperimentAiAnalyzer?,
    jobQueue: JobQueue,
    aggregator: suspend (UUID) -> Unit,
) {
    val log = ExperimentAnalysisJobExecutor.log

    val experiment = experimentService.getById(job.experimentId)
    if (experiment == null) {
        log.warn("Experiment not found: {} — skipping analysis.", job.experimentId)
        return
    }

    log.info("Aggregating results before analysis for experiment: {}", job.experimentId)
    aggregator(job.experimentId)

    log.info("Analyzing experiment: {}", job.experimentId)
    val flag = flagRepository.getById(experiment.featureFlagId)
    if (flag == null) {
        log.warn("Flag not found for experiment {}", job.experimentId)
        return
    }
    val allVariations = parseVariationsForAnalysis(json, flag.variations)
    val rules = parseRulesForAnalysis(json, flag.targetingRules)
    val attachedRule = experiment.targetingRuleId?.let { id -> rules.find { it.id == id } }
    val involvedKeys = if (attachedRule != null) {
        attachedRule.rollout.variationWeights.map { it.variationKey }.distinct()
    } else {
        allVariations.map { it.key }
    }
    val variations = allVariations.filter { it.key in involvedKeys }.sortedBy { it.key }

    // Expected rollout weights drive the SRM check. When the experiment
    // is on the default-variation path (no attached rule), every user
    // matching the default lands on the same variation, so there is no
    // distribution to check and SRM is skipped.
    val expectedRolloutWeights: Map<String, Double> = if (attachedRule != null) {
        attachedRule.rollout.variationWeights.associate { it.variationKey to it.weight.toDouble() }
    } else {
        emptyMap()
    }

    val goals = experimentService.getConversionGoals(job.experimentId)
    val results = experimentService.getResults(job.experimentId)
    if (results.isEmpty()) {
        log.info("No results to analyze for experiment: {}", job.experimentId)
        return
    }

    // Structured deterministic pipeline. Pure function on the inputs;
    // produces verdict, per-goal/per-variation rows with lift CIs, and
    // a fallback natural-language summary.
    val analysis = runDeterministicAnalysis(
        experiment = experiment,
        variations = variations,
        expectedRolloutWeights = expectedRolloutWeights,
        goals = goals,
        results = results,
    )

    // Persisted summary and recommendation are ALWAYS the
    // deterministic prose. The AI step is additive and goes into
    // the separate `aiInsights` column — see the executor KDoc.
    val deterministicSummary = analysis.summary
    val deterministicRecommendation = analysis.recommendation

    // Optional AI insights on top of the deterministic verdict.
    // Absent when no analyzer is registered (e.g. no credentials).
    // Any failure or rejected payload simply leaves `aiInsights = null`
    // on the persisted report — the deterministic prose stands on
    // its own.
    val aiInsights: AiInsights? = if (aiAnalyzer != null) {
        runCatching { aiAnalyzer.summarize(analysis) }
            .onFailure { log.warn("AI experiment analyzer failed; persisting report without aiInsights", it) }
            .getOrNull()
    } else null
    val aiInsightsPayload: JsonElement? = aiInsights?.let {
        json.encodeToJsonElement(AiInsights.serializer(), it)
    }

    // Build the typed report shape, then serialize once at the
    // repository boundary. The consumer side
    // ([RolloutPolicyJobExecutor.runRolloutPolicy]) decodes
    // the same typed shape on read.
    val detailsTyped = buildAnalysisDetailsFor(analysis, experiment.analysisRevision)
    val detailsJson: JsonElement = json.encodeToJsonElement(
        AnalysisReportDetails.serializer(),
        detailsTyped,
    )

    val report = AnalysisReport(
        experimentId = job.experimentId,
        summary = deterministicSummary,
        recommendation = deterministicRecommendation,
        details = detailsJson,
        confidence = analysis.confidence,
        aiInsights = aiInsightsPayload,
    )
    analysisReportRepository.add(report)

    log.info(
        "Analysis complete for experiment: {} verdict={} aiInsights={} - {}",
        job.experimentId, analysis.verdict, aiInsightsPayload != null, deterministicRecommendation,
    )

    // Chain the rollout controller if the experiment has an
    // attached policy and is still RUNNING. The controller reads
    // the report we just wrote, decides whether to advance /
    // halt / hold / complete, and records its decision. Scheduled
    // mode treats analysis-chained wakes as no-ops (the step
    // chain drives advancement in that mode), so this enqueue is
    // safe for every mode.
    if (experiment.rolloutPolicy != null && experiment.status == ExperimentStatus.RUNNING) {
        runCatching {
            RolloutPolicyJob(experimentId = job.experimentId, scheduledStepIndex = null)
                .enqueue(jobQueue, RolloutPolicyJobExecutor::class)
        }.onFailure { e ->
            log.error(
                "Failed to chain rollout policy job after analysis for experiment {}",
                job.experimentId, e,
            )
        }
    }
}

/**
 * Decodes the flag's `variations` JSONB column. Throws on a
 * malformed blob — an unparseable variation palette is a data-
 * integrity bug that silently returning `emptyList()` would hide
 * behind a "no results to analyze" no-op on every subsequent run.
 */
private fun parseVariationsForAnalysis(json: Json, variationsJson: JsonElement): List<Variation> =
    json.decodeFromJsonElement(ListSerializer(Variation.serializer()), variationsJson)

/**
 * Decodes the flag's `targetingRules` JSONB column. Null means
 * "no rules attached" and is a valid state for a flag on its
 * default-variation path; anything else must parse.
 */
private fun parseRulesForAnalysis(json: Json, rulesJson: JsonElement?): List<TargetingRule> {
    if (rulesJson == null) return emptyList()
    return json.decodeFromJsonElement(ListSerializer(TargetingRule.serializer()), rulesJson)
}

/**
 * Builds the typed [AnalysisReportDetails] for the supplied
 * deterministic analysis. Producer-side counterpart to
 * [bosca.experimentation.jobs.readLatestAnalysisDetails] in the
 * rollout controller — the same typed shape round-trips through
 * the JSONB column, so consumers never touch raw JSON.
 */
internal fun buildAnalysisDetailsFor(
    analysis: DeterministicAnalysis,
    experimentRevision: Long,
): AnalysisReportDetails =
        AnalysisReportDetails(
            experimentName = analysis.experimentName,
            hypothesis = analysis.hypothesis,
            verdict = analysis.verdict.name,
            variationCount = analysis.variationCount,
            goalCount = analysis.goalCount,
            totalImpressions = analysis.totalImpressions,
            totalConversions = analysis.totalConversions,
            controlVariationKey = analysis.controlKey,
            experimentRevision = experimentRevision,
            // Always emitted so the admin UI can show "1 comparison,
            // no correction" vs "N comparisons, Bonferroni corrected
            // to per-test alpha α/N" without re-deriving the state.
            multipleTesting = MultipleTestingDetails(
                numComparisons = analysis.numComparisons,
                effectiveAlpha = analysis.effectiveAlpha,
                perTestConfidence = 1.0 - analysis.effectiveAlpha,
                corrected = analysis.numComparisons > 1,
            ),
            srm = analysis.srm?.let { srm ->
                SrmDetails(
                    failed = srm.failed,
                    chiSquared = srm.chiSquared,
                    pValue = srm.pValue,
                    observed = srm.observed,
                    expected = srm.expected,
                )
            },
            goals = analysis.goalAnalyses.map { goal ->
                GoalDetails(
                    goalId = goal.goalId,
                    goalName = goal.goalName,
                    metricType = goal.metricType,
                    role = goal.role,
                    verdict = goal.verdict.name,
                    winnerVariationKey = goal.winner?.variationKey,
                    unit = if (goal.metricType == bosca.experimentation.model.GoalMetricType.SESSION_DURATION) "seconds" else null,
                    coverageImbalanced = goal.coverageImbalanced,
                    variations = goal.variationRows.map { row ->
                        VariationDetails(
                            variationKey = row.variationKey,
                            variationName = row.variationName,
                            isControl = row.isControl,
                            impressions = row.impressions,
                            observationCount = row.observationCount,
                            coverage = row.coverage,
                            conversions = row.conversions,
                            rate = row.rate,
                            mean = row.mean,
                            variance = row.variance,
                            liftPercent = row.liftPercent,
                            liftCiLowerPercent = row.liftCiLowerPercent,
                            liftCiUpperPercent = row.liftCiUpperPercent,
                            confidence = row.confidence,
                        )
                    },
                )
            },
        )
