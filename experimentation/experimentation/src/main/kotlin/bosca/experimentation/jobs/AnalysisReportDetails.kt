package bosca.experimentation.jobs

import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.GoalMetricType
import kotlinx.serialization.Serializable

/**
 * Typed schema for the JSON blob written to
 * `experimentation.analysis_reports.details`. Producer:
 * [ExperimentAnalysisJobExecutor]; consumers:
 * [RolloutPolicyJobExecutor] (reading the verdict + primary
 * confidence) and the admin UI (rendering goal/variation tables).
 *
 * Existed historically as a free-form `JsonObject` constructed via
 * `buildJsonObject`/`buildJsonArray`, which forced consumers into a
 * tower of `as? JsonObject` casts to read it back. Promoting it to a
 * typed `@Serializable` data class moves the cast risk into a single
 * `decodeFromJsonElement` call at the read boundary and lets every
 * downstream consumer touch the fields by name.
 *
 * `ignoreUnknownKeys` is enabled at the read sites because the admin
 * UI (and external consumers) may eventually start writing extra
 * keys we don't read here, and a hard schema would force a
 * cross-deploy migration on every additive change.
 */
@Serializable
data class AnalysisReportDetails(
    val experimentName: String = "",
    val hypothesis: String = "",
    val verdict: String = "",
    val variationCount: Int = 0,
    val goalCount: Int = 0,
    val totalImpressions: Long = 0L,
    val totalConversions: Long = 0L,
    val controlVariationKey: String? = null,
    /**
     * Monotonic analysis-definition revision read by the producer. It advances
     * when experiment inputs or conversion goals change, but not for lifecycle-only
     * status transitions, so the report that pauses or completes an experiment
     * remains the current explanation for that state.
     */
    val experimentRevision: Long? = null,
    val multipleTesting: MultipleTestingDetails? = null,
    val srm: SrmDetails? = null,
    val goals: List<GoalDetails> = emptyList(),
)

/**
 * Bonferroni correction state for the report. Always emitted by the
 * producer so the admin UI can render "1 comparison, no correction"
 * vs "5 comparisons, Bonferroni corrected to per-test alpha 0.01"
 * without re-deriving it from goal/variation counts.
 */
@Serializable
data class MultipleTestingDetails(
    val numComparisons: Int,
    val effectiveAlpha: Double,
    val perTestConfidence: Double,
    val corrected: Boolean,
)

/**
 * Sample-ratio-mismatch check result. The producer omits this field
 * entirely (the parent `srm` is nullable) when the experiment has
 * fewer than two variations or no rollout weights, so the consumer
 * must accept null without interpreting it as "SRM passed".
 */
@Serializable
data class SrmDetails(
    val failed: Boolean,
    val chiSquared: Double,
    val pValue: Double,
    val observed: Map<String, Long> = emptyMap(),
    val expected: Map<String, Double> = emptyMap(),
)

/**
 * Per-goal section of [AnalysisReportDetails.goals]. Carries the
 * goal-level metadata the controller needs to find the primary
 * winner ([role]) and the per-variation rows used by both the
 * controller (to read `confidence` for the policy gate) and the
 * admin UI (to render the metric table).
 */
@Serializable
data class GoalDetails(
    val goalId: String,
    val goalName: String,
    val metricType: GoalMetricType,
    val role: ConversionGoalRole,
    val verdict: String,
    val winnerVariationKey: String? = null,
    val unit: String? = null,
    val coverageImbalanced: Boolean = false,
    val variations: List<VariationDetails> = emptyList(),
)

/**
 * Per-variation row inside a [GoalDetails]. Optional fields are
 * `null` when the producer did not have a value to write — for
 * example, [confidence] is null on the control row of every report,
 * and [mean]/[variance] are null on `UNIQUE_CONVERSION` goals.
 *
 * The [confidence] field carries the frequentist `1 - p` for
 * frequentist analysis, and `probabilityBeatsControl` (the Bayesian
 * posterior) for Bayesian analysis. The producer-side analyzer
 * normalizes both into this single field so the controller and the
 * admin UI can compare against `policy.minConfidence` without
 * branching on the analysis method.
 */
@Serializable
data class VariationDetails(
    val variationKey: String,
    val variationName: String,
    val isControl: Boolean,
    val impressions: Long,
    val observationCount: Long = impressions,
    val coverage: Double = if (impressions > 0L) observationCount.toDouble() / impressions else 0.0,
    val conversions: Long,
    val rate: Double,
    val mean: Double? = null,
    val variance: Double? = null,
    val liftPercent: Double? = null,
    val liftCiLowerPercent: Double? = null,
    val liftCiUpperPercent: Double? = null,
    val confidence: Double? = null,
)
