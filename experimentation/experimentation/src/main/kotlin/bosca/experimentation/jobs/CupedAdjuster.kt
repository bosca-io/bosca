package bosca.experimentation.jobs

import kotlin.math.max

/**
 * Pure CUPED (Controlled experiment Using Pre-Experiment Data)
 * variance reduction. Given per-user post-period outcomes `y_i` and
 * pre-period covariate values `x_i` across **all** users in the
 * experiment (both arms, to keep the covariate independent of
 * treatment — the whole point of CUPED is that the covariate is
 * chosen BEFORE assignment), [computeCupedTheta] fits the OLS
 * coefficient `θ = Cov(y, x) / Var(x)` and [adjustOutcomes]
 * produces the residual metric `y'_i = y_i - θ · (x_i - x̄)`.
 *
 * The adjusted metric has strictly lower variance than the raw
 * outcome whenever the covariate correlates at all with the
 * outcome, which tightens the confidence interval on the lift
 * estimate. Centering preserves the overall mean across the experiment;
 * individual arm means can change when their covariate means differ.
 *
 * The single-covariate regression uses centered sums. Assigned subjects with
 * neither outcome nor covariate are included by count, without allocating a
 * separate array entry for every inactive subject.
 *
 * All functions are pure. The aggregator fetches per-user values
 * from Trino and hands them to these helpers; there is no I/O in
 * this file.
 */

/**
 * Output of the CUPED adjustment step. Carries the per-arm
 * adjusted mean and variance, plus a flag telling the caller
 * whether the adjustment actually applied (false means the
 * adjuster fell back to unadjusted values because the covariate
 * had zero variance).
 *
 * @property adjustedMean residual mean `mean(y - θ·(x - x̄))`.
 *           Uses the global covariate mean, so an individual arm mean can shift.
 * @property adjustedVariance residual variance, using Bessel's
 *           correction. Variance reduction is fitted globally; an individual arm
 *           is not guaranteed to have lower variance.
 * @property theta fitted regression coefficient. Exposed for
 *           logging / debugging — operators often want to know
 *           "how predictive was the covariate?" and `θ` near zero
 *           answers that.
 * @property applied true when the adjuster produced a real
 *           adjustment; false when it fell back to raw values
 *           (caller gets `adjustedMean == rawMean`,
 *           `adjustedVariance == rawVariance`).
 */
data class CupedResult(
    val adjustedMean: Double,
    val adjustedVariance: Double,
    val theta: Double,
    val applied: Boolean,
)

/**
 * Computes θ across all users in the experiment using OLS.
 * Returns `null` when the covariate has zero variance (all users
 * have identical pre-period values, e.g. everyone has 0) — in
 * that case there is nothing for CUPED to regress against and
 * the caller should skip the adjustment. Returns 0.0 when the
 * sample is too small for OLS (< 2 rows) because the coefficient
 * is undefined but the caller still needs a safe numeric answer.
 *
 * Centering both variables includes an intercept. [implicitZeroCount] counts
 * assigned subjects with zero outcome and zero covariate.
 */
internal fun computeCupedTheta(
    outcomes: DoubleArray,
    covariates: DoubleArray,
    implicitZeroCount: Long = 0L,
): Double? {
    require(outcomes.size == covariates.size) {
        "outcomes and covariates must have the same size, got ${outcomes.size} vs ${covariates.size}"
    }
    require(implicitZeroCount >= 0L)
    val sampleSize = outcomes.size.toLong() + implicitZeroCount
    if (sampleSize < 2) return 0.0
    // The regression slope is undefined when every covariate is identical.
    val xMean = covariates.sum() / sampleSize
    val yMean = outcomes.sum() / sampleSize
    var xSs = implicitZeroCount * xMean * xMean
    var xySs = implicitZeroCount * xMean * yMean
    for (i in covariates.indices) {
        val xDelta = covariates[i] - xMean
        xSs += xDelta * xDelta
        xySs += xDelta * (outcomes[i] - yMean)
    }
    if (xSs == 0.0) return null

    return xySs / xSs
}

/**
 * Applies the CUPED adjustment to [outcomes] using [theta] and
 * the covariate mean `x̄` derived from [covariates], returning
 * per-user adjusted values `y'_i = y_i - θ · (x_i - x̄)`.
 *
 * Centering on `x̄` (and NOT on the per-arm covariate mean) is
 * the property that preserves the overall mean of `y'` equal to
 * the mean of `y`, so lift estimates on the adjusted metric stay
 * on the same scale as the raw metric.
 */
internal fun adjustOutcomes(
    outcomes: DoubleArray,
    covariates: DoubleArray,
    theta: Double,
): DoubleArray {
    require(outcomes.size == covariates.size)
    val xMean = covariates.average()
    return DoubleArray(outcomes.size) { i ->
        outcomes[i] - theta * (covariates[i] - xMean)
    }
}

/**
 * Computes the mean and Bessel-corrected sample variance of a
 * single arm's adjusted values. Mirrors
 * [perUserVariance]'s contract so the aggregator can use either
 * the raw-path or the CUPED-path output interchangeably.
 */
internal fun meanAndVariance(values: DoubleArray): Pair<Double, Double> {
    if (values.isEmpty()) return 0.0 to 0.0
    if (values.size == 1) return values[0] to 0.0
    var sum = 0.0
    for (v in values) sum += v
    val mean = sum / values.size
    var ss = 0.0
    for (v in values) {
        val d = v - mean
        ss += d * d
    }
    val variance = max(0.0, ss / (values.size - 1))
    return mean to variance
}

/**
 * End-to-end CUPED adjustment for a single arm's per-user data.
 *
 * [outcomes] / [covariates] are the per-user values in this arm;
 * [globalTheta] and [globalCovariateMean] come from OLS over the
 * WHOLE experiment (both arms) — they are computed once per goal
 * by the aggregator and reused for each arm's adjustment. When
 * `globalTheta` is null (covariate had zero variance across the
 * experiment), this function falls back to the raw mean/variance
 * and returns `applied = false`. [implicitZeroCount] adds inactive assigned
 * subjects with zero outcome and covariate to this arm.
 */
fun adjustArm(
    outcomes: DoubleArray,
    covariates: DoubleArray,
    globalTheta: Double?,
    globalCovariateMean: Double,
    implicitZeroCount: Long = 0L,
): CupedResult {
    require(outcomes.size == covariates.size)
    require(implicitZeroCount >= 0L)
    if (globalTheta == null) {
        val (m, v) = meanAndVarianceWithRepeated(outcomes, 0.0, implicitZeroCount)
        return CupedResult(
            adjustedMean = m,
            adjustedVariance = v,
            theta = 0.0,
            applied = false,
        )
    }
    val adjusted = DoubleArray(outcomes.size) { i ->
        outcomes[i] - globalTheta * (covariates[i] - globalCovariateMean)
    }
    val (m, v) = meanAndVarianceWithRepeated(
        adjusted, globalTheta * globalCovariateMean, implicitZeroCount,
    )
    return CupedResult(
        adjustedMean = m,
        adjustedVariance = v,
        theta = globalTheta,
        applied = true,
    )
}

/** Includes repeated values in the sample without materializing inactive subjects. */
private fun meanAndVarianceWithRepeated(values: DoubleArray, repeatedValue: Double, repeatedCount: Long): Pair<Double, Double> {
    if (repeatedCount == 0L) return meanAndVariance(values)
    val sampleSize = values.size.toLong() + repeatedCount
    val mean = (values.sum() + repeatedValue * repeatedCount) / sampleSize
    val repeatedDelta = repeatedValue - mean
    var ss = repeatedDelta * repeatedDelta * repeatedCount
    for (value in values) {
        val delta = value - mean
        ss += delta * delta
    }
    return mean to if (sampleSize > 1L) max(0.0, ss / (sampleSize - 1)) else 0.0
}

/**
 * Computes `θ` and `x̄` from the full experiment's per-user data,
 * then adjusts each arm. Callers pass per-arm
 * `Map<clientId, outcome>` and `Map<clientId, covariate>` maps;
 * the implementation aligns their union, filling absent values with zero.
 * [implicitZeroCounts] includes assigned subjects absent from both maps, per arm.
 *
 * Returns a per-arm [CupedResult] keyed by arm label. When the
 * covariate has zero variance across the whole experiment, every
 * arm's result has `applied = false` and the numbers match the
 * raw mean/variance.
 */
fun adjustAllArms(
    armsOutcomes: Map<String, Map<String, Double>>,
    armsCovariates: Map<String, Map<String, Double>>,
    implicitZeroCounts: Map<String, Long> = emptyMap(),
): Map<String, CupedResult> {
    // Build the full-experiment OLS sample by unioning every
    // user across arms. Users present in one arm but not the
    // other don't exist (one user = one assignment = one arm),
    // so this is really a concatenation across arms.
    //
    // Include ALL assigned users — not just those with post-period
    // outcomes. Users who were exposed but had zero events are the
    // bulk of the population in typical experiments; excluding them
    // biases theta by fitting only on active users.
    val allOutcomes = ArrayList<Double>()
    val allCovariates = ArrayList<Double>()
    for (arm in armsOutcomes.keys.union(armsCovariates.keys)) {
        val outcomes = armsOutcomes[arm] ?: emptyMap()
        val covariates = armsCovariates[arm] ?: emptyMap()
        val allUsers = outcomes.keys.union(covariates.keys)
        for (userId in allUsers) {
            allOutcomes.add(outcomes[userId] ?: 0.0)
            allCovariates.add(covariates[userId] ?: 0.0)
        }
    }
    val theta = computeCupedTheta(
        outcomes = allOutcomes.toDoubleArray(),
        covariates = allCovariates.toDoubleArray(),
        implicitZeroCount = implicitZeroCounts.values.sum(),
    )
    val sampleSize = allCovariates.size.toLong() + implicitZeroCounts.values.sum()
    val xMean = if (sampleSize == 0L) 0.0 else allCovariates.sum() / sampleSize

    val allArms = armsOutcomes.keys.union(armsCovariates.keys)
    return allArms.associateWith { arm ->
        val outcomes = armsOutcomes[arm] ?: emptyMap()
        val covariates = armsCovariates[arm] ?: emptyMap()
        val allUsers = outcomes.keys.union(covariates.keys)
        val yArr = DoubleArray(allUsers.size)
        val xArr = DoubleArray(allUsers.size)
        var i = 0
        for (userId in allUsers) {
            yArr[i] = outcomes[userId] ?: 0.0
            xArr[i] = covariates[userId] ?: 0.0
            i++
        }
        adjustArm(yArr, xArr, theta, xMean, implicitZeroCounts[arm] ?: 0L)
    }
}
