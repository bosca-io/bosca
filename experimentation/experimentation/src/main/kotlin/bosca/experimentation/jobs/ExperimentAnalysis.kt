package bosca.experimentation.jobs

import bosca.experimentation.model.AnalysisMethod
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentResult
import bosca.experimentation.model.GoalMetricType
import bosca.experimentation.model.Variation
import org.apache.commons.math3.distribution.TDistribution
import org.apache.commons.math3.stat.inference.ChiSquareTest
import java.math.BigInteger
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Structured deterministic analysis of an experiment, computed from the
 * `experiment_results` rows alone (no statistical handwaving in natural
 * language). The [ExperimentAnalysisJobExecutor] populates one of these
 * and renders it into the `AnalysisReport` summary/recommendation/details,
 * and the AI step (when enabled) receives it as part of its prompt to
 * write a more nuanced summary on top.
 *
 * The structured form exists because the previous executor was a single
 * decision tree of strings — it picked one variation by the highest
 * confidence (regardless of lift sign), one goal at a time, with no SRM
 * check, no per-goal practical significance threshold, and no
 * confidence intervals. This struct captures the things that need to be
 * true *before* writing any "ship the treatment" recommendation.
 */
data class DeterministicAnalysis(
    val experimentName: String,
    val hypothesis: String,
    val controlKey: String?,
    val variationCount: Int,
    val goalCount: Int,
    val totalImpressions: Long,
    val totalConversions: Long,

    /** SRM check result; null when there's no rollout to compare against. */
    val srm: SrmResult?,

    /** Per-goal verdicts; the recommendation aggregates across these. */
    val goalAnalyses: List<GoalAnalysis>,

    /** The overall verdict combining SRM, all goals, and lift directions. */
    val verdict: Verdict,

    /** The natural-language summary written by the deterministic step. */
    val summary: String,

    /** The natural-language recommendation written by the deterministic step. */
    val recommendation: String,

    /** Confidence used by the report row (0.0–1.0). */
    val confidence: Double,

    /**
     * Bonferroni multiple-testing correction applied to per-test alphas.
     *
     * `numComparisons` is the number of independent (variation, goal)
     * treatment-vs-control comparisons in this experiment, i.e.
     * `goalCount * (variationCount - 1)`. When it exceeds 1, the per-test
     * alpha is divided by it (and the per-test confidence threshold raised
     * to `1 - α/n`) so the family-wise false-positive rate stays at
     * [BASE_ALPHA] across the whole experiment, not per individual test.
     *
     * `effectiveAlpha` is the per-test alpha actually used by `analyzeGoal`
     * and `computeLiftWithCi`; the lift CI's t critical value is taken at
     * `1 - effectiveAlpha/2` so the CI bounds widen consistently with the
     * decision threshold.
     */
    val numComparisons: Int,
    val effectiveAlpha: Double,

    /**
     * True when different goal rows reported different per-variation impression
     * counts — indicates a partial aggregation or concurrent update produced
     * inconsistent data. The analysis proceeds with first-seen counts, but
     * consumers should surface this as a data-quality warning.
     */
    val srmImpressionInconsistency: Boolean = false,
)

/**
 * Sample Ratio Mismatch test. Compares observed per-variation impression
 * counts against the expected counts derived from the rollout's weight
 * configuration via a chi-squared goodness-of-fit test.
 *
 * SRM is the canonical "is bucketing actually doing what you think it's
 * doing?" sanity check. A failing SRM means there's a bucketing or
 * tracking bug, and any conclusions drawn from the experiment are
 * suspect — the analyzer surfaces this loudly and refuses to make a
 * "ship" recommendation when SRM is broken.
 */
data class SrmResult(
    val observed: Map<String, Long>,
    val expected: Map<String, Double>,
    val chiSquared: Double,
    val pValue: Double,
    /** True when p-value < threshold (default 0.001) — bucketing looks broken. */
    val failed: Boolean,
)

/**
 * Per-goal analysis: the per-variation comparison plus a goal-level verdict.
 *
 * The [role] is carried here (instead of re-looking up the parent
 * [ConversionGoal]) because the overall verdict logic needs to treat
 * losers on primary goals, secondary goals, and guardrails differently
 * without joining back to the goal list. See
 * [Verdict] and the severity ordering in [runDeterministicAnalysis] for
 * how the role participates in the decision.
 */
data class GoalAnalysis(
    val goalId: String,
    val goalName: String,
    val metricType: GoalMetricType,
    val role: ConversionGoalRole,
    /** Per-variation rows including the control. */
    val variationRows: List<VariationRow>,
    /** The variation with the largest *positive* lift, if any. */
    val winner: VariationRow?,
    /** Goal-level verdict considering only this goal's data. */
    val verdict: GoalVerdict,
    /** True when session-observation coverage differs materially between arms. */
    val coverageImbalanced: Boolean = false,
)

/**
 * Configurable thresholds threaded through [runDeterministicAnalysis].
 *
 * Pulling these out of module constants and onto a parameter lets a
 * rollout policy carry per-experiment overrides (e.g. a looser
 * [guardrailThreshold] to catch regressions earlier than wins, or a
 * tighter [guardrailMinRegressionPercent] for a variance-heavy
 * guardrail). Omitting the parameter reuses the module defaults so
 * every pre-policy call site keeps its current behavior.
 *
 * @property baseAlpha family-wise base alpha for the frequentist pipeline.
 *           Overridden by the experiment's policy only in experiments
 *           that want non-standard confidence bars; defaults to
 *           [BASE_ALPHA].
 * @property minPracticalLiftPercent minimum positive lift required to
 *           call a treatment a winner (on primary and secondary goals)
 *           and minimum magnitude of negative lift required to call a
 *           treatment a loser on primary / secondary goals.
 * @property guardrailMinRegressionPercent minimum magnitude of negative
 *           lift that counts as a guardrail regression and triggers a
 *           [Verdict.HALT] at the experiment level. Defaults to
 *           `0.5%` — stricter than the primary-metric loser threshold
 *           because guardrails are "must not break" metrics.
 */
data class AnalysisThresholds(
    val baseAlpha: Double = BASE_ALPHA,
    val minPracticalLiftPercent: Double = MIN_PRACTICAL_LIFT_PERCENT,
    val guardrailMinRegressionPercent: Double = GUARDRAIL_MIN_REGRESSION_PERCENT_DEFAULT,
) {
    init {
        require(baseAlpha in 0.0..1.0) { "baseAlpha must be in [0, 1], got $baseAlpha" }
        require(minPracticalLiftPercent >= 0.0) {
            "minPracticalLiftPercent must be non-negative, got $minPracticalLiftPercent"
        }
        require(guardrailMinRegressionPercent >= 0.0) {
            "guardrailMinRegressionPercent must be non-negative, got $guardrailMinRegressionPercent"
        }
    }
}

/**
 * Per-variation aggregated metric for one goal, with confidence intervals
 * on the lift estimate. The CI is what lets the analyzer say "+8.3% lift
 * (95% CI: +1.2% to +15.4%)" instead of just dropping a single number.
 */
data class VariationRow(
    val variationKey: String,
    val variationName: String,
    val isControl: Boolean,
    val impressions: Long,
    val observationCount: Long,
    val coverage: Double,
    val conversions: Long,
    val rate: Double,
    val mean: Double?,
    val variance: Double?,
    val liftPercent: Double?,
    /** Lower bound of the 95% CI on lift, in percent. Null for control. */
    val liftCiLowerPercent: Double?,
    /** Upper bound of the 95% CI on lift, in percent. Null for control. */
    val liftCiUpperPercent: Double?,
    val confidence: Double?,
)

enum class GoalVerdict {
    /** Treatment positively outperformed control with significance and meaningful lift. */
    WINNER,
    /** Significance reached but lift is below the practical threshold (or negative). */
    INCONCLUSIVE_LIFT,
    /** Approaching significance — keep collecting. */
    NEEDS_MORE_DATA,
    /** Significance reached and the best treatment is *worse* than control. */
    LOSER,
    /** Not enough data to compute confidence at all. */
    INSUFFICIENT_DATA,
}

enum class Verdict {
    /**
     * Bucketing or tracking is broken (SRM p-value below threshold).
     * Distinct from metric-based verdicts because none of the numbers
     * can be trusted — the rollout controller treats this as "hold"
     * rather than "halt" because halting implies acting on the data.
     */
    SRM_FAILED,
    /**
     * A guardrail goal regressed significantly. The rollout controller
     * SHALL immediately set the treatment weight to 0% and pause the
     * experiment when the attached rollout policy has
     * `haltOnGuardrail = true`. Strictly more severe than
     * [DO_NOT_SHIP]: `DO_NOT_SHIP` says "don't advance"; `HALT` says
     * "revert now".
     */
    HALT,
    /**
     * A **primary** goal regressed significantly. No guardrail has
     * tripped, but the treatment is demonstrably worse on what the
     * experiment was trying to improve. The controller holds; the
     * operator decides what to do next.
     */
    DO_NOT_SHIP,
    /**
     * At least one primary goal is a clear winner, no primary goal is
     * a loser, no secondary goal regressed significantly, and no
     * guardrail tripped. Adaptive rollout modes advance on this verdict.
     */
    SHIP,
    /** At least one goal is approaching significance and none have failed. */
    KEEP_RUNNING,
    /**
     * Mixed signal: some goals improved but none enough to recommend,
     * a secondary regressed (downgrading an otherwise-SHIP to
     * INCONCLUSIVE per the secondary-goal rule), or the experiment
     * produced data on every goal without a clear direction.
     */
    INCONCLUSIVE,
    /** Not enough data anywhere yet. */
    NO_DATA,
}

/**
 * Computes a [DeterministicAnalysis] from the raw inputs. Pure function:
 * no I/O, no service calls, no logging — fully testable by passing in
 * fake results. The executor handles all the data loading and persists
 * the resulting natural-language summary into `analysis_reports`.
 *
 * [thresholds] carries the per-experiment configurable cutoffs (base
 * alpha, practical-significance floor, guardrail regression floor).
 * It defaults to the module constants so every pre-policy test and
 * caller keeps its current behavior.
 */
fun runDeterministicAnalysis(
    experiment: Experiment,
    variations: List<Variation>,
    expectedRolloutWeights: Map<String, Double>,
    goals: List<ConversionGoal>,
    results: List<ExperimentResult>,
    thresholds: AnalysisThresholds = AnalysisThresholds(),
): DeterministicAnalysis {
    val analysisMethod = experiment.analysisMethod
    val controlKey = experiment.controlVariationKey
    require(variations.any { it.key == controlKey }) {
        "Experiment ${experiment.id} control variation '$controlKey' is not involved in analysis"
    }
    val involvedVariationKeys = variations.mapTo(hashSetOf()) { it.key }
    val goalIds = goals.mapTo(hashSetOf()) { it.id }
    val currentResults = results.filter {
        it.variationKey in involvedVariationKeys && it.goalId in goalIds
    }
    // Deduplicate outcome-eligible impressions and raw assignments across goals.
    // Activation may make these counts differ; assignment counts remain the SRM input.
    val impressionsByVariation = mutableMapOf<String, Long>()
    val assignmentsByVariation = mutableMapOf<String, Long>()
    var impressionInconsistencyDetected = false
    for (r in currentResults) {
        val existing = impressionsByVariation[r.variationKey]
        if (existing == null) {
            impressionsByVariation[r.variationKey] = r.impressions
        } else if (existing != r.impressions) {
            impressionInconsistencyDetected = true
        }
        val existingAssignments = assignmentsByVariation[r.variationKey]
        if (existingAssignments == null) {
            assignmentsByVariation[r.variationKey] = r.assignments
        } else if (existingAssignments != r.assignments) {
            impressionInconsistencyDetected = true
        }
    }
    val totalImpressions = impressionsByVariation.values.sum()
    val totalAssignments = assignmentsByVariation.values.sum()
    val totalConversions = currentResults.sumOf { it.conversions }

    val srm = if (assignmentsByVariation.size >= 2 && expectedRolloutWeights.isNotEmpty()) {
        computeSrm(assignmentsByVariation, expectedRolloutWeights)
    } else null

    // Bonferroni correction. Family = all (variation - control) × goal
    // comparisons in this experiment. When the family is empty (no goals,
    // single variation) or has only one test, no correction applies and
    // we use the base alpha directly. Otherwise the per-test alpha is
    // divided by the number of comparisons so the family-wise error rate
    // stays at BASE_ALPHA. The lift CI's t critical value is taken at
    // `1 - effectiveAlpha/2`, so the CI bounds widen alongside the
    // decision threshold rather than stating a tighter precision than the
    // significance call actually used.
    val numComparisons = goals.size * maxOf(0, variations.size - 1)
    // Bonferroni is a frequentist family-wise error control. Under a
    // Bayesian analysis the per-test "confidence" is a posterior
    // probability, which has no family-wise error rate to adjust —
    // applying Bonferroni there would over-discount perfectly
    // calibrated posteriors. See requirements.md R4.
    val effectiveAlpha = when (analysisMethod) {
        AnalysisMethod.FREQUENTIST -> if (numComparisons > 1) thresholds.baseAlpha / numComparisons else thresholds.baseAlpha
        AnalysisMethod.BAYESIAN -> thresholds.baseAlpha
    }

    if (currentResults.isEmpty() || totalImpressions == 0L) {
        return DeterministicAnalysis(
            experimentName = experiment.name,
            hypothesis = experiment.hypothesis,
            controlKey = controlKey,
            variationCount = variations.size,
            goalCount = goals.size,
            totalImpressions = 0L,
            totalConversions = 0L,
            srm = srm,
            goalAnalyses = emptyList(),
            verdict = Verdict.NO_DATA,
            summary = if (totalAssignments > 0L && experiment.activationFilter != null) {
                "No data to analyze yet — $totalAssignments assigned subjects have not matched the activation filter."
            } else {
                "No data to analyze yet — the experiment has no outcome-eligible subjects."
            },
            recommendation = if (totalAssignments > 0L && experiment.activationFilter != null) {
                "Verify that the activation filter matches the first qualifying page impression, then re-aggregate."
            } else {
                "Start the experiment if it's not running, drive traffic to its targeting rule, then re-aggregate."
            },
            confidence = 0.0,
            numComparisons = numComparisons,
            effectiveAlpha = effectiveAlpha,
        )
    }

    // Per-goal analyses. Each goal sees the same per-test alpha so the
    // family-wise correction is consistent across the report. The
    // per-goal practical-lift threshold varies by role: guardrails use
    // the (potentially tighter) guardrailMinRegressionPercent so
    // variance-heavy safety metrics can trip HALT on smaller movements,
    // while primary/secondary goals use the standard practical floor.
    val goalAnalyses = goals.map { goal ->
        val practical = when (goal.role) {
            ConversionGoalRole.GUARDRAIL -> thresholds.guardrailMinRegressionPercent
            ConversionGoalRole.PRIMARY,
            ConversionGoalRole.SECONDARY -> thresholds.minPracticalLiftPercent
        }
        analyzeGoal(
            goal = goal,
            variations = variations,
            controlKey = controlKey,
            goalResults = currentResults.filter { it.goalId == goal.id },
            alpha = effectiveAlpha,
            practicalLiftPercent = practical,
            analysisMethod = analysisMethod,
        )
    }

    // Overall verdict severity order:
    //   1. SRM_FAILED   — bucketing broken, a distinct branch.
    //   2. HALT         — any guardrail loser; the controller reverts.
    //   3. DO_NOT_SHIP  — any PRIMARY loser; controller holds.
    //   4. INCONCLUSIVE — any SECONDARY loser (downgrades SHIP, per R1).
    //   5. SHIP         — a PRIMARY winner with no loser of any role.
    //   6. KEEP_RUNNING — something approaching significance.
    //   7. NO_DATA      — every goal is INSUFFICIENT_DATA.
    //   8. INCONCLUSIVE — everything else.
    val anyGuardrailLoser = goalAnalyses.any {
        it.role == ConversionGoalRole.GUARDRAIL && it.verdict == GoalVerdict.LOSER
    }
    val anyPrimaryLoser = goalAnalyses.any {
        it.role == ConversionGoalRole.PRIMARY && it.verdict == GoalVerdict.LOSER
    }
    val anySecondaryLoser = goalAnalyses.any {
        it.role == ConversionGoalRole.SECONDARY && it.verdict == GoalVerdict.LOSER
    }
    val anyPrimaryWinner = goalAnalyses.any {
        it.role == ConversionGoalRole.PRIMARY && it.verdict == GoalVerdict.WINNER
    }
    val verdict = when {
        srm?.failed == true -> Verdict.SRM_FAILED
        anyGuardrailLoser -> Verdict.HALT
        anyPrimaryLoser -> Verdict.DO_NOT_SHIP
        anyPrimaryWinner && !anySecondaryLoser -> Verdict.SHIP
        anyPrimaryWinner && anySecondaryLoser -> Verdict.INCONCLUSIVE
        goalAnalyses.any { it.verdict == GoalVerdict.NEEDS_MORE_DATA } -> Verdict.KEEP_RUNNING
        goalAnalyses.all { it.verdict == GoalVerdict.INSUFFICIENT_DATA } -> Verdict.NO_DATA
        else -> Verdict.INCONCLUSIVE
    }

    val (summary, recommendation, confidence) = renderVerdict(
        experiment, verdict, goalAnalyses, srm, totalImpressions, effectiveAlpha, numComparisons,
    )

    return DeterministicAnalysis(
        experimentName = experiment.name,
        hypothesis = experiment.hypothesis,
        controlKey = controlKey,
        variationCount = variations.size,
        goalCount = goals.size,
        totalImpressions = totalImpressions,
        totalConversions = totalConversions,
        srm = srm,
        goalAnalyses = goalAnalyses,
        verdict = verdict,
        summary = summary,
        recommendation = recommendation,
        confidence = confidence,
        numComparisons = numComparisons,
        effectiveAlpha = effectiveAlpha,
        srmImpressionInconsistency = impressionInconsistencyDetected,
    )
}

private fun analyzeGoal(
    goal: ConversionGoal,
    variations: List<Variation>,
    controlKey: String,
    goalResults: List<ExperimentResult>,
    alpha: Double,
    practicalLiftPercent: Double,
    analysisMethod: AnalysisMethod = AnalysisMethod.FREQUENTIST,
): GoalAnalysis {
    // Per-test confidence threshold and "approaching" threshold derived
    // from the (already-Bonferroni-corrected) alpha. Doing the derivation
    // here keeps the lift CI critical value, the WINNER/LOSER cutoff, and
    // the NEEDS_MORE_DATA cutoff all referencing the same number — drift
    // between them is the kind of inconsistency that produced the original
    // "the result says ship but the CI crosses zero" class of bugs.
    val confidenceThreshold = 1.0 - alpha
    val nearSignificanceThreshold = 1.0 - 2.0 * alpha
    if (goalResults.isEmpty()) {
        return GoalAnalysis(
            goalId = goal.id.toString(),
            goalName = goal.name,
            metricType = goal.metricType,
            role = goal.role,
            variationRows = emptyList(),
            winner = null,
            verdict = GoalVerdict.INSUFFICIENT_DATA,
        )
    }

    val controlResult = goalResults.find { it.variationKey == controlKey }
    // Note: the control base (conversionRate / mean) is intentionally
    // not pre-computed here. The delta-method lift CI in
    // [computeLiftWithCi] reads control.conversionRate / control.mean
    // directly off the ExperimentResult, and folding it through a
    // local would just hide the dependency.

    val rows = goalResults.map { r ->
        // runDeterministicAnalysis filters currentResults against the current
        // variation palette before calling this function.
        val variation = variations.first { it.key == r.variationKey }
        val isControl = r.variationKey == controlKey
        val (lift, ciLower, ciUpper) = if (isControl || controlResult == null) {
            Triple(null, null, null)
        } else {
            computeLiftWithCi(goal.metricType, controlResult, r, alpha)
        }
        // Under Bayesian analysis, the controller/promotion gate
        // reads `probabilityBeatsControl` out of the "confidence"
        // field on VariationRow so downstream consumers (UI,
        // rollout controller) can stay method-agnostic. The
        // frequentist path still reports `confidenceLevel`.
        val reportedConfidence = when (analysisMethod) {
            AnalysisMethod.FREQUENTIST -> r.confidenceLevel
            AnalysisMethod.BAYESIAN -> r.probabilityBeatsControl
        }
        VariationRow(
            variationKey = r.variationKey,
            variationName = variation.name,
            isControl = isControl,
            impressions = r.impressions,
            observationCount = r.observationCount,
            coverage = if (r.impressions > 0L) r.observationCount.toDouble() / r.impressions else 0.0,
            conversions = r.conversions,
            rate = r.conversionRate,
            mean = r.mean,
            variance = r.variance,
            liftPercent = lift,
            liftCiLowerPercent = ciLower,
            liftCiUpperPercent = ciUpper,
            confidence = reportedConfidence,
        )
    }

    // Winner = the non-control row with the highest *positive* lift that
    // also clears the confidence threshold and the practical-significance
    // threshold. If no such row exists, no winner.
    val candidate = rows
        .filter { !it.isControl }
        .filter { (it.liftPercent ?: 0.0) > 0.0 }
        .filter { (it.confidence ?: 0.0) >= confidenceThreshold }
        .filter { (it.liftPercent ?: 0.0) >= practicalLiftPercent }
        .maxByOrNull { it.liftPercent ?: 0.0 }

    // The "loser" check has to consider variations that
    // significantly REGRESS against the control. Under the
    // frequentist path this is `confidence >= threshold AND lift <=
    // -practical` — a one-sided chi-squared / Welch's t confidence
    // is symmetric about the lift sign, so the same threshold
    // catches winners and losers. Under Bayesian analysis the
    // confidence column carries `probabilityBeatsControl`, which is
    // HIGH (close to 1) for a winning treatment and LOW (close to 0)
    // for a losing one — the symmetric check is `(1 -
    // probabilityBeatsControl) >= threshold`, i.e. the posterior
    // probability that control beats treatment clears the bar.
    // For guardrails the practical threshold is typically tighter
    // than for primaries — the caller passes in the role-appropriate
    // cutoff.
    val significantLoser = rows
        .filter { !it.isControl }
        .filter { r ->
            val conf = r.confidence ?: 0.0
            when (analysisMethod) {
                AnalysisMethod.FREQUENTIST -> conf >= confidenceThreshold
                AnalysisMethod.BAYESIAN -> (1.0 - conf) >= confidenceThreshold
            }
        }
        .any { (it.liftPercent ?: 0.0) <= -practicalLiftPercent }

    val coverageImbalanced = goal.metricType == GoalMetricType.SESSION_DURATION &&
        rows.indices.any { left ->
            (left + 1 until rows.size).any { right ->
                coverageDifferenceExceeds(
                    rows[left].observationCount,
                    rows[left].impressions,
                    rows[right].observationCount,
                    rows[right].impressions,
                )
            }
        }
    val verdict = when {
        coverageImbalanced -> GoalVerdict.INCONCLUSIVE_LIFT
        candidate != null -> GoalVerdict.WINNER
        significantLoser -> GoalVerdict.LOSER
        rows.any { r ->
            if (r.isControl) return@any false
            val conf = r.confidence ?: 0.0
            // Under Bayesian analysis, an "approaching loser" has low pBC
            // (e.g. 0.12, where 1-0.12=0.88 is near the significance
            // threshold). Check both directions so NEEDS_MORE_DATA is
            // symmetric: approaching-winner (high conf) OR approaching-
            // loser (low conf, high inverse).
            conf >= nearSignificanceThreshold ||
                (analysisMethod == AnalysisMethod.BAYESIAN && (1.0 - conf) >= nearSignificanceThreshold)
        } ->
            GoalVerdict.NEEDS_MORE_DATA
        rows.any { !it.isControl && it.confidence != null } ->
            // Confidence was computed but didn't reach the bar AND there's
            // no negative-lift loser — this is the "tested and got nothing"
            // case, which is meaningfully different from "still warming up".
            GoalVerdict.INCONCLUSIVE_LIFT
        else -> GoalVerdict.INSUFFICIENT_DATA
    }

    return GoalAnalysis(
        goalId = goal.id.toString(),
        goalName = goal.name,
        metricType = goal.metricType,
        role = goal.role,
        variationRows = rows,
        winner = if (coverageImbalanced) null else candidate,
        verdict = verdict,
        coverageImbalanced = coverageImbalanced,
    )
}

/** Exact, overflow-safe comparison for a strict ten-percentage-point coverage gap. */
internal fun coverageDifferenceExceeds(
    firstObservations: Long,
    firstImpressions: Long,
    secondObservations: Long,
    secondImpressions: Long,
): Boolean {
    val firstNumerator = if (firstImpressions > 0L) firstObservations else 0L
    val firstDenominator = if (firstImpressions > 0L) firstImpressions else 1L
    val secondNumerator = if (secondImpressions > 0L) secondObservations else 0L
    val secondDenominator = if (secondImpressions > 0L) secondImpressions else 1L
    val a = BigInteger.valueOf(firstNumerator)
    val b = BigInteger.valueOf(firstDenominator)
    val c = BigInteger.valueOf(secondNumerator)
    val d = BigInteger.valueOf(secondDenominator)
    val difference = a.multiply(d).subtract(c.multiply(b)).abs().multiply(BigInteger.TEN)
    return difference > b.multiply(d)
}

/**
 * Welch–Satterthwaite degrees of freedom for the difference of two
 * means with unequal variances. Used both for the EVENT_COUNT lift CI
 * (where it is the textbook df) and as the t critical value source for
 * the proportion-difference CI (which strictly speaking should use a
 * normal, but using a t with `nC + nT - 2` df is a strict superset:
 * for large samples it collapses onto the normal, and for small samples
 * it produces wider, more conservative bounds — preferable to silently
 * over-stating precision).
 *
 * Not in commons-math directly — math3 only exposes the test, not the
 * df it computes internally — so we replicate the textbook formula
 * here. Numerator/denominator are guarded against zero/non-finite
 * inputs (which the call sites already filter, but defensively).
 */
internal fun welchSatterthwaiteDf(vC: Double, nC: Long, vT: Double, nT: Long): Double {
    if (nC < 2 || nT < 2) return (nC + nT - 2).coerceAtLeast(1L).toDouble()
    val a = vC / nC
    val b = vT / nT
    val numerator = (a + b) * (a + b)
    val denominator = (a * a) / (nC - 1) + (b * b) / (nT - 1)
    if (denominator <= 0.0 || !numerator.isFinite() || !denominator.isFinite()) {
        return (nC + nT - 2).toDouble()
    }
    return numerator / denominator
}

/**
 * Computes percent lift `(treatment - control) / control * 100` and a
 * `(1 - alpha)` confidence interval around it. The CI uses different math
 * depending on the goal's metric type:
 *
 *  - `UNIQUE_CONVERSION` is a difference of two proportions. The CI
 *    uses the Wald standard error with a Student-t critical value at
 *    `nC + nT - 2` df, then rescales to a percent-of-control lift CI
 *    by dividing by the control rate.
 *  - `EVENT_COUNT` is a difference of two means. We use Welch's
 *    standard error and a t critical value at the Welch–Satterthwaite
 *    df, then rescale.
 *
 * The t critical value comes directly from commons-math's
 * [TDistribution.inverseCumulativeProbability] — there is no Bosca
 * wrapper because there is nothing to abstract beyond the call itself,
 * and parameterizing `alpha` matters precisely so the multiple-testing
 * correction in [runDeterministicAnalysis] can widen the CI in step
 * with its own decision threshold.
 *
 * For both types, when the control base is zero (no conversions on the
 * control side) the percent-lift is undefined and we return null bounds.
 */
internal fun computeLiftWithCi(
    metricType: GoalMetricType,
    control: ExperimentResult,
    treatment: ExperimentResult,
    alpha: Double,
): Triple<Double?, Double?, Double?> {
    val twoSidedQuantile = 1.0 - alpha / 2.0
    return when (metricType) {
        GoalMetricType.UNIQUE_CONVERSION -> {
            val pC = control.conversionRate
            val pT = treatment.conversionRate
            if (pC <= 0.0) return Triple(null, null, null)
            val nC = control.impressions
            val nT = treatment.impressions
            val pointLift = (pT / pC - 1.0) * 100.0
            if (nC < 2 || nT < 2 || pT <= 0.0) {
                return Triple(pointLift, null, null)
            }
            // Delta-method SE for the *ratio* (pT/pC), not the difference.
            // Var(pT/pC - 1) ≈ (pT/pC)^2 · ( Var(pT)/pT^2 + Var(pC)/pC^2 )
            // with Var(p) = p(1-p)/n. The previous (diff ± t·seDiff)/pC
            // formula treated pC as a constant and silently understated the
            // CI width — narrow enough to flip a SHIP/DO_NOT_SHIP call on
            // small or low-rate experiments.
            val varPC = pC * (1.0 - pC) / nC
            val varPT = pT * (1.0 - pT) / nT
            val ratio = pT / pC
            val varRatio = ratio * ratio * (varPT / (pT * pT) + varPC / (pC * pC))
            val seRatio = sqrt(varRatio)
            if (!seRatio.isFinite()) return Triple(pointLift, null, null)
            val df = (nC + nT - 2).coerceAtLeast(1L).toDouble()
            val tCrit = TDistribution(df).inverseCumulativeProbability(twoSidedQuantile)
            val halfWidth = tCrit * seRatio * 100.0
            Triple(pointLift, pointLift - halfWidth, pointLift + halfWidth)
        }
        GoalMetricType.EVENT_COUNT,
        GoalMetricType.SESSION_DURATION -> {
            // Prefer CUPED-adjusted variance when the goal has a
            // covariate and the aggregator populated the adjusted
            // columns. The point lift stays tied to the RAW control
            // mean so operators see the same business number
            // regardless of whether CUPED ran — only the CI width
            // (computed from the adjusted variance) tightens.
            val mC = control.mean ?: return Triple(null, null, null)
            val mT = treatment.mean ?: return Triple(null, null, null)
            val vC = control.adjustedVariance ?: control.variance ?: 0.0
            val vT = treatment.adjustedVariance ?: treatment.variance ?: 0.0
            val nC = if (metricType == GoalMetricType.SESSION_DURATION) control.observationCount else control.impressions
            val nT = if (metricType == GoalMetricType.SESSION_DURATION) treatment.observationCount else treatment.impressions
            if (mC <= 0.0) return Triple(null, null, null)
            val pointLift = (mT / mC - 1.0) * 100.0
            // Match the Welch significance test gate so the lift CI and the
            // confidence column are consistent: under WELCH_MIN_SAMPLES_PER_ARM
            // we report the point lift but null bounds.
            if (nC < WELCH_MIN_SAMPLES_PER_ARM || nT < WELCH_MIN_SAMPLES_PER_ARM || mT <= 0.0) {
                return Triple(pointLift, null, null)
            }
            // Delta-method SE for the ratio of means. Var(mean) = variance/n.
            // Var(mT/mC - 1) ≈ (mT/mC)^2 · ( Var(mT)/mT^2 + Var(mC)/mC^2 )
            //              = (mT/mC)^2 · ( vT/(nT·mT^2) + vC/(nC·mC^2) )
            // Same fix as the proportion path: the previous formula
            // ignored the variance contribution from the denominator.
            val ratio = mT / mC
            val varRatio =
                ratio * ratio * ((vT / (nT.toDouble() * mT * mT)) + (vC / (nC.toDouble() * mC * mC)))
            val seRatio = sqrt(varRatio)
            if (!seRatio.isFinite()) return Triple(pointLift, null, null)
            val df = welchSatterthwaiteDf(vC, nC, vT, nT).coerceAtLeast(1.0)
            val tCrit = TDistribution(df).inverseCumulativeProbability(twoSidedQuantile)
            val halfWidth = tCrit * seRatio * 100.0
            Triple(pointLift, pointLift - halfWidth, pointLift + halfWidth)
        }
    }
}

/**
 * Shared [ChiSquareTest] instance used by [computeSrm]. Commons Math's
 * test classes are stateless and thread-safe, so a single instance is
 * reused across all analysis calls.
 */
private val SRM_CHI_SQUARE_TEST = ChiSquareTest()

/**
 * Chi-squared goodness-of-fit test for sample ratio mismatch (SRM),
 * built on Apache Commons Math's [ChiSquareTest.chiSquareTest]
 * expected/observed overload.
 *
 * Given observed per-variation impression counts and expected
 * proportions (normalized rollout weights), this returns the chi-
 * squared statistic and its upper-tail p-value. A p-value below
 * [SRM_P_VALUE_THRESHOLD] means the observed split is statistically
 * inconsistent with the configured weights — bucketing or tracking is
 * broken and the experiment's results should not be trusted.
 *
 * Buckets with a zero expected count are excluded because Commons
 * Math's implementation rejects non-positive expected values; they
 * contribute nothing to a well-formed chi-squared statistic anyway.
 */
private fun computeSrm(
    observed: Map<String, Long>,
    expectedWeights: Map<String, Double>,
): SrmResult {
    val totalObserved = observed.values.sum()
    val totalWeight = expectedWeights.values.sum()
    if (totalObserved == 0L || totalWeight <= 0.0) {
        return SrmResult(observed, emptyMap(), 0.0, 1.0, false)
    }
    val expectedByKey = mutableMapOf<String, Double>()
    val activeKeys = mutableListOf<String>()
    val expectedForTest = mutableListOf<Double>()
    val observedForTest = mutableListOf<Long>()
    for ((key, weight) in expectedWeights) {
        val expectedCount = (weight / totalWeight) * totalObserved
        expectedByKey[key] = expectedCount
        if (expectedCount <= 0.0) continue
        activeKeys.add(key)
        expectedForTest.add(expectedCount)
        observedForTest.add(observed[key] ?: 0L)
    }
    if (activeKeys.size < 2) {
        return SrmResult(observed, expectedByKey, 0.0, 1.0, false)
    }
    val expectedArray = expectedForTest.toDoubleArray()
    val observedArray = observedForTest.toLongArray()
    val chiSquared = SRM_CHI_SQUARE_TEST.chiSquare(expectedArray, observedArray)
    val pValue = SRM_CHI_SQUARE_TEST.chiSquareTest(expectedArray, observedArray)
    return SrmResult(
        observed = observed,
        expected = expectedByKey,
        chiSquared = chiSquared,
        pValue = pValue,
        failed = pValue < SRM_P_VALUE_THRESHOLD,
    )
}

private fun renderVerdict(
    experiment: Experiment,
    verdict: Verdict,
    goalAnalyses: List<GoalAnalysis>,
    srm: SrmResult?,
    totalImpressions: Long,
    effectiveAlpha: Double,
    numComparisons: Int,
): Triple<String, String, Double> {
    val maxConfidence = goalAnalyses
        .flatMap { it.variationRows }
        .mapNotNull { it.confidence }
        .maxOrNull() ?: 0.0

    val perTestConfidencePct = (1.0 - effectiveAlpha) * 100.0
    val correctionNote = if (numComparisons > 1) {
        " (Bonferroni-corrected across $numComparisons comparisons → per-test threshold ${"%.2f".format(perTestConfidencePct)}%)"
    } else ""

    return when (verdict) {
        Verdict.SRM_FAILED -> {
            val obs = srm?.observed?.entries?.joinToString(", ") { "${it.key}=${it.value}" } ?: ""
            Triple(
                "Sample ratio mismatch detected (chi-squared p=${"%.4f".format(srm?.pValue ?: 0.0)}). " +
                    "Observed split: $obs. Bucketing or tracking is misbehaving — the experiment's results " +
                    "should not be trusted until this is investigated.",
                "Stop the experiment, audit your bucketing logic and event ingestion, and re-run with fresh assignments.",
                0.0,
            )
        }
        Verdict.HALT -> {
            // Guardrail HALT dominates every other signal except SRM.
            // The text names the offending guardrails explicitly so
            // operators reading a rollout event log can see exactly
            // why the controller reverted.
            val tripped = goalAnalyses.filter {
                it.role == ConversionGoalRole.GUARDRAIL && it.verdict == GoalVerdict.LOSER
            }
            val tripText = tripped.joinToString(", ") { ga ->
                val worst = ga.variationRows
                    .filter { !it.isControl }
                    .minByOrNull { it.liftPercent ?: 0.0 }
                val liftPart = worst?.liftPercent?.let { " (${"%+.1f".format(it)}%)" } ?: ""
                "${ga.goalName}$liftPart"
            }
            Triple(
                "Guardrail regression detected on: $tripText. The rollout controller will halt the " +
                    "treatment (weight → 0) and pause the experiment when a halt-on-guardrail policy " +
                    "is attached. Any positive movement on other goals is not enough to override a " +
                    "guardrail trip.",
                "Halt the treatment immediately, investigate the guardrail regression, and only resume after " +
                    "a fix is in place and validated.",
                maxConfidence,
            )
        }
        Verdict.DO_NOT_SHIP -> {
            // DO_NOT_SHIP is now scoped to PRIMARY goal losers (guardrail
            // losers route through HALT above, secondary losers through
            // INCONCLUSIVE below). The prose is intentionally narrower
            // than the old pre-roles version.
            val losingGoals = goalAnalyses.filter {
                it.role == ConversionGoalRole.PRIMARY && it.verdict == GoalVerdict.LOSER
            }
            val losersText = losingGoals.joinToString(", ") { it.goalName }
            Triple(
                "At least one primary goal regressed significantly: $losersText. Shipping any " +
                    "treatment from this experiment would make those metrics worse.",
                "Do not roll out. Investigate why the treatment is regressing on $losersText, then revise the hypothesis.",
                maxConfidence,
            )
        }
        Verdict.SHIP -> {
            val winningGoals = goalAnalyses.filter { it.verdict == GoalVerdict.WINNER }
            val winnerNames = winningGoals.mapNotNull { it.winner?.variationName }.distinct()
            val variationName = winnerNames.firstOrNull() ?: "the treatment"
            val liftSummary = winningGoals.joinToString("; ") { ga ->
                val w = ga.winner!!
                val ci = if (w.liftCiLowerPercent != null && w.liftCiUpperPercent != null) {
                    " (95% CI: ${"%+.1f".format(w.liftCiLowerPercent)}% to ${"%+.1f".format(w.liftCiUpperPercent)}%)"
                } else ""
                "${ga.goalName}: ${"%+.1f".format(w.liftPercent ?: 0.0)}%$ci"
            }
            Triple(
                "$variationName beat the control on ${winningGoals.size} goal(s) at ≥${"%.2f".format(perTestConfidencePct)}% confidence$correctionNote with practically significant lift. $liftSummary",
                "Roll out '$variationName' to 100% of traffic on the attached rule, monitor for a few more days, then archive the experiment.",
                maxConfidence,
            )
        }
        Verdict.KEEP_RUNNING -> {
            val approaching = goalAnalyses.filter { it.verdict == GoalVerdict.NEEDS_MORE_DATA }
            Triple(
                "Results are approaching significance on ${approaching.size} goal(s) but no goal has reached " +
                    "the ${"%.2f".format(perTestConfidencePct)}% threshold$correctionNote yet (max: ${"%.1f".format(maxConfidence * 100)}%).",
                "Keep the experiment running until significance is reached, then re-aggregate.",
                maxConfidence,
            )
        }
        Verdict.INCONCLUSIVE -> Triple(
            "No clear winner. The experiment has data but neither significant lift nor a clear regression on any goal " +
                "(max confidence: ${"%.1f".format(maxConfidence * 100)}%).",
            "Consider stopping the experiment, revising the hypothesis, or testing a more dramatic variation.",
            maxConfidence,
        )
        Verdict.NO_DATA -> Triple(
            "Experiment has $totalImpressions outcome-eligible subjects but no goal has enough data to compute a confidence yet.",
            if (totalImpressions == 0L) {
                "Drive traffic to the experiment's targeting rule, then re-aggregate."
            } else {
                "Wait for more conversions to land, then re-aggregate."
            },
            0.0,
        )
    }
}

/**
 * Family-wise base alpha for hypothesis tests. 0.05 is the conventional
 * A/B-testing default. The per-test alpha is this value divided by the
 * number of (variation, goal) treatment-vs-control comparisons in the
 * experiment (Bonferroni); see [DeterministicAnalysis.effectiveAlpha].
 *
 * Making this per-experiment is intentionally deferred until there's
 * evidence operators want different settings on different experiments.
 */
internal const val BASE_ALPHA = 0.05

/**
 * Minimum percent lift over control required to call a treatment a winner,
 * and (by symmetry) the minimum regression magnitude required to call it a
 * loser on primary/secondary goals. `1%` is the conservative default;
 * operators can override per-experiment via [AnalysisThresholds].
 */
internal const val MIN_PRACTICAL_LIFT_PERCENT = 1.0

/**
 * Default minimum regression magnitude for a guardrail goal to trip
 * [Verdict.HALT]. Tighter than [MIN_PRACTICAL_LIFT_PERCENT] because
 * guardrails are "must not break" metrics and operators generally want
 * to catch small regressions on page load, error rate, refund rate, etc.
 * earlier than they would catch small regressions on primary metrics.
 * Overridden per-experiment via the rollout policy's
 * `guardrailMinRegressionPercent`.
 */
internal const val GUARDRAIL_MIN_REGRESSION_PERCENT_DEFAULT = 0.5

/**
 * SRM p-value threshold. Below this, the bucket distribution is
 * statistically inconsistent with the configured rollout. The
 * conventional value is 1e-3 (looser than 1e-2 to avoid noise during
 * normal operation, tighter than 1e-4 to actually catch regressions).
 */
internal const val SRM_P_VALUE_THRESHOLD = 0.001
