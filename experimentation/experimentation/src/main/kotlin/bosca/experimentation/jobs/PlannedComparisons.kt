package bosca.experimentation.jobs

import bosca.experimentation.model.*
import org.apache.commons.math3.distribution.HypergeometricDistribution
import org.apache.commons.math3.distribution.TDistribution
import kotlin.math.abs
import kotlin.math.sqrt

/** Two-sided Fisher exact probability for sparse binary observations. */
internal fun fisherExactPValue(a: Long, nA: Long, b: Long, nB: Long): Double {
    require(a in 0..nA && b in 0..nB)
    val total = nA + nB
    require(total <= Int.MAX_VALUE) { "Fisher exact table exceeds supported population size" }
    if (total == 0L) return 1.0
    val distribution = HypergeometricDistribution(total.toInt(), (a + b).toInt(), nA.toInt())
    val observed = distribution.logProbability(a.toInt())
    return (distribution.supportLowerBound..distribution.supportUpperBound).sumOf {
        if (distribution.logProbability(it) <= observed + 1e-12) distribution.probability(it) else 0.0
    }.coerceIn(0.0, 1.0)
}

/**
 * Compares every pair in the involved palette under one predeclared comparison family.
 *
 * Only per-arm observation minimums gate a conclusion here; the experiment's sample
 * target withholds ship decisions in [runDeterministicAnalysis] so that safety stops
 * are not delayed. A binary arm reporting more conversions than its denominator is
 * blocked as a data-quality failure rather than tested.
 * The control estimate defines the practical-effect margin for the whole family,
 * including treatment-to-treatment comparisons, independently of variation keys.
 */
internal fun plannedComparisons(
    experiment: Experiment, goals: List<ConversionGoal>, results: List<ExperimentResult>,
    alpha: Double, thresholds: AnalysisThresholds, blockedReason: String?,
): List<PlannedComparison> = goals.flatMap { goal ->
    val rows = results.filter { it.goalId == goal.id }.sortedBy { it.variationKey }
    val control = rows.find { it.variationKey == experiment.controlVariationKey }
    val controlEstimate = if (goal.metricType == GoalMetricType.UNIQUE_CONVERSION) control?.conversionRate
        else control?.adjustedMean ?: control?.mean
    val practical = if (goal.role == ConversionGoalRole.GUARDRAIL) thresholds.guardrailMinRegressionPercent else thresholds.minPracticalLiftPercent
    val minimumDifference = controlEstimate?.let { abs(it) * practical / 100 }
    rows.indices.flatMap { i -> (i + 1 until rows.size).map { j ->
        val left = rows[i]; val right = rows[j]
        val binary = goal.metricType == GoalMetricType.UNIQUE_CONVERSION
        val nL = if (goal.metricType == GoalMetricType.SESSION_DURATION) left.observationCount else left.impressions
        val nR = if (goal.metricType == GoalMetricType.SESSION_DURATION) right.observationCount else right.impressions
        val mL = if (binary) left.conversionRate else left.adjustedMean ?: left.mean
        val mR = if (binary) right.conversionRate else right.adjustedMean ?: right.mean
        val vL = left.adjustedVariance ?: left.variance
        val vR = right.adjustedVariance ?: right.variance
        val countsExceedDenominator = binary && (left.conversions > nL || right.conversions > nR)
        val delta = if (mL != null && mR != null) mL - mR else null
        val frequentistInterval = if (!countsExceedDenominator && nL > 1 && nR > 1 && delta != null && mL != null && mR != null) {
            if (binary) {
                val l = checkNotNull(wilsonInterval(left.conversions, nL, alpha))
                val r = checkNotNull(wilsonInterval(right.conversions, nR, alpha))
                MetricInterval(delta - sqrt((mL - l.lower) * (mL - l.lower) + (r.upper - mR) * (r.upper - mR)),
                    delta + sqrt((l.upper - mL) * (l.upper - mL) + (mR - r.lower) * (mR - r.lower)))
            } else if (vL != null && vR != null) {
                val half = TDistribution(welchSatterthwaiteDf(vL, nL, vR, nR).coerceAtLeast(1.0))
                    .inverseCumulativeProbability(1 - alpha / 2) * sqrt(vL / nL + vR / nR)
                MetricInterval(delta - half, delta + half)
            } else null
        } else null
        val frequentist = if (countsExceedDenominator) null
        else if (binary) chiSquaredConfidence(nR, right.conversions, nL, left.conversions)
            ?: (1 - fisherExactPValue(left.conversions, nL, right.conversions, nR))
        else if (mR != null && mL != null && vR != null && vL != null) welchsTConfidence(mR, vR, nR, mL, vL, nL) else null
        val prior = experiment.bayesianPrior?.let { kotlinx.serialization.json.Json.decodeFromJsonElement(BayesianPrior.serializer(), it) } ?: BayesianPrior.DEFAULT
        val posterior = if (!countsExceedDenominator && experiment.analysisMethod == AnalysisMethod.BAYESIAN && nL > 1 && nR > 1 && mL != null && mR != null) {
            if (binary) {
                val l = betaPosterior(left.conversions, nL, prior)
                val r = betaPosterior(right.conversions, nR, prior)
                val (rightDraws, leftDraws) = drawPaired(r, l, 0L, BETA_MC_SAMPLES)
                val differences = leftDraws.indices.map { leftDraws[it] - rightDraws[it] }.sorted()
                val interval = MetricInterval(differences[(differences.lastIndex * alpha / 2).toInt()],
                    differences[(differences.lastIndex * (1 - alpha / 2)).toInt()])
                interval to differences.count { it > 0 }.toDouble() / differences.size
            } else if (vL != null && vR != null) {
                val l = bayesianNormalPosterior(mL, vL, nL, prior)
                val r = bayesianNormalPosterior(mR, vR, nR, prior)
                val half = org.apache.commons.math3.distribution.NormalDistribution().inverseCumulativeProbability(1 - alpha / 2) * sqrt(l.variance + r.variance)
                MetricInterval(l.mean - r.mean - half, l.mean - r.mean + half) to normalProbabilityBeatsControl(r.mean, r.variance, l.mean, l.variance)
            } else null
        } else null
        val interval = if (experiment.analysisMethod == AnalysisMethod.BAYESIAN) posterior?.first else frequentistInterval
        val evidence = if (experiment.analysisMethod == AnalysisMethod.FREQUENTIST) frequentist else posterior?.second
        val enough = nL >= WELCH_MIN_SAMPLES_PER_ARM && nR >= WELCH_MIN_SAMPLES_PER_ARM
        val blocked = blockedReason ?: if (countsExceedDenominator) {
            "Conversions exceed the outcome-eligible subjects in at least one arm."
        } else null
        val conclusive = evidence != null && delta != null && minimumDifference != null && abs(delta) >= minimumDifference &&
            (if (experiment.analysisMethod == AnalysisMethod.BAYESIAN) maxOf(evidence, 1 - evidence) >= 1 - alpha
             else evidence >= 1 - alpha && interval != null && (interval.lower > 0 || interval.upper < 0))
        val conclusion = when {
            blocked != null -> "DATA_QUALITY_BLOCKED"
            !enough || interval == null || minimumDifference == null -> "INSUFFICIENT_DATA"
            conclusive -> "CONCLUSIVE"
            interval.lower >= -minimumDifference && interval.upper <= minimumDifference -> "EQUIVALENT"
            else -> "INCONCLUSIVE"
        }
        val reason = blocked ?: when (conclusion) {
            "INSUFFICIENT_DATA" -> if (minimumDifference == null) "The control estimate is unavailable for the practical-effect margin."
                else "The per-arm observation minimum has not been met."
            "CONCLUSIVE" -> "The overall difference clears the statistical and practical-effect thresholds."
            "EQUIVALENT" -> "The entire overall interval lies within the practical-effect margin."
            else -> "The overall comparison does not clear the statistical and practical-effect thresholds."
        }
        PlannedComparison(goal.id.toString(), left.variationKey, right.variationKey, nL, nR, delta, interval,
            evidence, if (experiment.analysisMethod == AnalysisMethod.BAYESIAN) "P(win)" else "1 − p", 1 - alpha, conclusion, reason)
    } }
}
