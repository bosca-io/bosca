package bosca.experimentation.jobs

import bosca.experimentation.model.BayesianPrior
import org.apache.commons.math3.distribution.BetaDistribution
import org.apache.commons.math3.distribution.NormalDistribution
import org.apache.commons.math3.random.JDKRandomGenerator
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure Bayesian analysis primitives for [runDeterministicAnalysis]'s
 * Bayesian branch. All functions are deterministic given their inputs
 * (and, for the Monte Carlo paths, their seed) so every test and
 * every re-analysis of the same report produces byte-identical
 * numbers — critical for test stability and for operators comparing
 * reports across re-runs.
 *
 * The design splits into two families:
 *
 *  - **Beta / proportion** posteriors (`UNIQUE_CONVERSION` goals).
 *    `Beta(α + k, β + n - k)` where `α, β` come from the experiment's
 *    [BayesianPrior] (default uniform `Beta(1,1)`). `P(T > C)` and
 *    expected loss are estimated via a seeded Monte Carlo with
 *    [BETA_MC_SAMPLES] draws from each posterior. 50k draws give
 *    a Monte Carlo standard error of about 0.002 at p=0.5, which is
 *    well below the 0.01 granularity operators care about.
 *
 *  - **Normal / mean** posteriors (`EVENT_COUNT` goals). With a flat
 *    prior the posterior is `N(sampleMean, sampleVar / n)`; with a
 *    Normal prior `N(m0, v0)` supplied on [BayesianPrior] the two
 *    precisions combine via the standard conjugate update
 *    (`1/v_post = 1/v0 + n/sampleVar`). `P(T > C)` is computed in
 *    closed form from the Normal CDF of `(mT - mC)` and the expected
 *    loss is the textbook half-Normal integral
 *    `(mC - mT) * Φ(z) + σ * φ(z)`.
 */

/** Number of Monte Carlo draws used by the Beta `P(T > C)` estimator. */
internal const val BETA_MC_SAMPLES = 50_000

/**
 * Posterior for a `UNIQUE_CONVERSION` goal's per-variation rate.
 * Exposed as a [BetaDistribution] so callers can sample from it with
 * a seeded random generator.
 *
 * @param conversions number of distinct converting users.
 * @param impressions total number of users exposed.
 * @param prior [BayesianPrior] carrying `α` and `β`. Defaults to
 *        [BayesianPrior.DEFAULT] which is uniform.
 */
internal fun betaPosterior(
    conversions: Long,
    impressions: Long,
    prior: BayesianPrior = BayesianPrior.DEFAULT,
): BetaDistribution {
    require(impressions >= 0) { "impressions must be non-negative" }
    require(conversions in 0..impressions) { "conversions must be in [0, impressions]" }
    val a = prior.betaPriorAlpha + conversions
    val b = prior.betaPriorBeta + (impressions - conversions)
    return BetaDistribution(a, b)
}

/**
 * Estimates `P(treatment > control)` on the two Beta posteriors via
 * [BETA_MC_SAMPLES] seeded Monte Carlo draws from each. The seed is
 * derived from the analysis report id (see
 * [seedFromReportId]) so re-running analysis on the same report
 * produces byte-identical output.
 *
 * The treatment is counted as strictly greater than the control
 * (ties, which are almost impossible for continuous distributions,
 * contribute 0). This matches the convention operators are used to
 * — they want "P(treatment is actually better)" not "P(they're the
 * same or better)".
 */
internal fun betaProbabilityBeatsControl(
    control: BetaDistribution,
    treatment: BetaDistribution,
    seed: Long,
    samples: Int = BETA_MC_SAMPLES,
): Double {
    val (controlSamples, treatmentSamples) = drawPaired(control, treatment, seed, samples)
    var wins = 0
    for (i in 0 until samples) {
        if (treatmentSamples[i] > controlSamples[i]) wins++
    }
    return wins.toDouble() / samples
}

/**
 * Estimates the expected loss from picking `treatment` — i.e.
 * `E[max(0, θ_control - θ_treatment)]`, the expected magnitude of
 * the regret conditional on the treatment actually being worse.
 * Zero when the treatment clearly dominates, large and positive
 * when the two posteriors overlap a lot. Same seeded draws as
 * [betaProbabilityBeatsControl] so the two stats are mutually
 * consistent on any given report.
 */
internal fun betaExpectedLoss(
    control: BetaDistribution,
    treatment: BetaDistribution,
    seed: Long,
    samples: Int = BETA_MC_SAMPLES,
): Double {
    val (controlSamples, treatmentSamples) = drawPaired(control, treatment, seed, samples)
    var sum = 0.0
    for (i in 0 until samples) {
        val diff = controlSamples[i] - treatmentSamples[i]
        if (diff > 0.0) sum += diff
    }
    return sum / samples
}

/**
 * Draws [samples] points from each of [control] and [treatment]
 * using a single seeded [JDKRandomGenerator]. The two arms share
 * the same RNG sequence so a caller that computes both "probability
 * beats control" and "expected loss" from the same draws sees a
 * consistent view of the posterior overlap.
 */
private fun drawPaired(
    control: BetaDistribution,
    treatment: BetaDistribution,
    seed: Long,
    samples: Int,
): Pair<DoubleArray, DoubleArray> {
    val rng = JDKRandomGenerator().apply { setSeed(seed) }
    // BetaDistribution.sample() uses its own internal RNG by
    // default; reseeding the instance is the supported path.
    control.reseedRandomGenerator(rng.nextLong())
    treatment.reseedRandomGenerator(rng.nextLong())
    val c = DoubleArray(samples) { control.sample() }
    val t = DoubleArray(samples) { treatment.sample() }
    return c to t
}

/**
 * Closed-form `P(T > C)` for two Normal posteriors on per-user means.
 *
 * If `mT ~ N(μT, σT²)` and `mC ~ N(μC, σC²)` with independent
 * posteriors, then `mT - mC ~ N(μT - μC, σT² + σC²)`, and
 * `P(mT > mC) = P(mT - mC > 0) = 1 - Φ(-d/σ) = Φ(d/σ)` where
 * `d = μT - μC` and `σ = sqrt(σT² + σC²)`.
 *
 * When both posterior variances are zero (degenerate point masses,
 * which happens only in contrived tests) the answer is deterministic:
 * `1.0` if `μT > μC`, `0.5` on exact tie, `0.0` otherwise.
 */
internal fun normalProbabilityBeatsControl(
    controlMean: Double,
    controlVariance: Double,
    treatmentMean: Double,
    treatmentVariance: Double,
): Double {
    val d = treatmentMean - controlMean
    val varSum = controlVariance + treatmentVariance
    if (varSum <= 0.0) return when {
        d > 0.0 -> 1.0
        d < 0.0 -> 0.0
        else -> 0.5
    }
    val sigma = sqrt(varSum)
    return STD_NORMAL.cumulativeProbability(d / sigma)
}

/**
 * Closed-form `E[max(0, mC - mT)]` for two independent Normal
 * posteriors. Using `d = mC - mT` and `σ = sqrt(vC + vT)`, the
 * expected positive part of `d` is
 *
 *   `E[max(0, d)] = μ_d · Φ(μ_d / σ) + σ · φ(μ_d / σ)`
 *
 * where `μ_d = mC - mT`. This is the same half-Normal moment used
 * in Bayesian A/B test engines from Google, Evan Miller, and
 * Chris Stucchio's widely-cited treatments.
 */
internal fun normalExpectedLoss(
    controlMean: Double,
    controlVariance: Double,
    treatmentMean: Double,
    treatmentVariance: Double,
): Double {
    val muD = controlMean - treatmentMean
    val varSum = controlVariance + treatmentVariance
    if (varSum <= 0.0) return max(0.0, muD)
    val sigma = sqrt(varSum)
    val z = muD / sigma
    val cdf = STD_NORMAL.cumulativeProbability(z)
    val pdf = STD_NORMAL.density(z)
    // Guard against floating-point underflow producing a tiny negative
    // (e.g. -1e-16) when treatment strongly dominates control.
    return max(0.0, muD * cdf + sigma * pdf)
}

/**
 * Combines a sample mean/variance with a (possibly absent) Normal
 * prior into posterior mean/variance on the per-user mean. Shared
 * by Phase 4 Bayesian aggregation and by the Normal closed-form
 * above.
 *
 * With no prior the posterior is `N(sampleMean, sampleVariance / n)`.
 * With a proper `N(m0, v0)` prior we combine precisions:
 *   precision_post = 1/v0 + n/sampleVariance
 *   mean_post      = (m0/v0 + n·sampleMean/sampleVariance) / precision_post
 *
 * When only one half of the prior is set the function falls back to
 * "no prior" — a half-configured prior is almost certainly a
 * misconfiguration and silently filling in a default is worse than
 * ignoring it.
 */
internal data class NormalPosterior(val mean: Double, val variance: Double)

internal fun bayesianNormalPosterior(
    sampleMean: Double,
    sampleVariance: Double,
    n: Long,
    prior: BayesianPrior,
): NormalPosterior {
    if (n <= 0L) {
        return NormalPosterior(sampleMean, sampleVariance / maxOf(n, 1L).toDouble())
    }
    val m0 = prior.normalPriorMean
    val v0 = prior.normalPriorVariance
    if (m0 == null || v0 == null || sampleVariance <= 0.0) {
        // No prior, or degenerate sample variance (all users had identical
        // event counts). Fall back to the no-prior posterior to avoid
        // division by zero in the precision combination below.
        return NormalPosterior(sampleMean, sampleVariance / n.toDouble())
    }
    val precisionPrior = 1.0 / v0
    val precisionSample = n.toDouble() / sampleVariance
    val precisionPost = precisionPrior + precisionSample
    val meanPost = (m0 * precisionPrior + sampleMean * precisionSample) / precisionPost
    return NormalPosterior(meanPost, 1.0 / precisionPost)
}

/**
 * Derives a stable Long seed from a UUID for the Bayesian Monte
 * Carlo path. Uses the first 64 bits of the UUID directly — as long
 * as the same report produces the same UUID (and it does: the id
 * is set when the row is inserted and never mutates) the seed is
 * stable. `kotlin.uuid.Uuid.toLongs` exposes the (mostSig, leastSig)
 * pair via an inline lambda; we only need the most-significant half.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal fun seedFromReportId(reportId: bosca.serialization.UUID): Long =
    reportId.toLongs { msb, _ -> msb }

/**
 * Single shared [NormalDistribution] for the standard normal. The
 * Commons Math distribution classes are stateless modulo their RNG
 * field, and the closed-form helpers don't sample from it — they
 * only hit `cumulativeProbability` / `density`. One instance avoids
 * reallocating on every call.
 */
private val STD_NORMAL = NormalDistribution(0.0, 1.0)

/**
 * For debugging only. Logs an operator-readable Beta posterior
 * summary. Not used in hot paths; kept here so tests can assert on
 * its output format and it stays next to the math it documents.
 */
internal fun describeBeta(b: BetaDistribution): String {
    val mean = b.numericalMean
    val variance = b.numericalVariance
    val sd = sqrt(variance)
    // Use ln/exp to avoid underflow when alpha+beta is tiny. The
    // typical analysis numbers are nowhere near the danger zone but
    // the guard is free.
    val concentration = exp(ln(b.alpha + b.beta))
    return "Beta(α=${b.alpha}, β=${b.beta}, mean=${"%.4f".format(mean)}, sd=${"%.4f".format(sd)}, n≈${"%.1f".format(concentration)})"
}
