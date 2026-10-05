package bosca.experimentation.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * How [bosca.experimentation.jobs.runDeterministicAnalysis] should
 * compute per-goal confidence.
 *
 * [FREQUENTIST] is the default and matches the system's behavior
 * before Phase 4 — chi-squared for `UNIQUE_CONVERSION`, Welch's t
 * for `EVENT_COUNT`, Bonferroni-corrected across the
 * `goalCount × (variationCount - 1)` family, fixed-horizon p-values.
 *
 * [BAYESIAN] uses posterior distributions instead. For
 * `UNIQUE_CONVERSION` the per-variation posterior is
 * `Beta(α + conversions, β + impressions - conversions)` where the
 * prior hyperparameters come from the experiment's [BayesianPrior]
 * (default uniform Beta(1,1)), and `probabilityBeatsControl` /
 * `expectedLoss` are estimated via seeded Monte Carlo. For
 * `EVENT_COUNT`, the per-variation posterior is Normal on the
 * per-user mean; with the default improper flat prior this collapses
 * to `N(sampleMean, sampleVar/n)`, and with a configured Normal
 * prior it combines precisions.
 *
 * The main operational difference operators see is that Bayesian
 * posteriors are interpretable at any sample size — there is no
 * peeking penalty — and the Bonferroni correction does NOT apply
 * (posterior probabilities don't have a family-wise error rate to
 * control).
 */
@DbMapper(AnalysisMethodMapper::class)
@Serializable
enum class AnalysisMethod {
    FREQUENTIST,
    BAYESIAN,
}

/**
 * Persists [AnalysisMethod] as the lowercase Postgres enum declared
 * by the V3 migration (`experimentation.analysis_method`).
 */
object AnalysisMethodMapper :
    EnumMapper<AnalysisMethod>({ AnalysisMethod.valueOf(it.uppercase()) })
