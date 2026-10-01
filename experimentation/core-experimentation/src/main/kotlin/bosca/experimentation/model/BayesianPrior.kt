package bosca.experimentation.model

import kotlinx.serialization.Serializable

/**
 * Configurable prior distributions for Bayesian analysis, persisted
 * as the `experiments.bayesian_prior` JSONB column.
 *
 * Null on an experiment means "sane defaults everywhere": a uniform
 * Beta(1, 1) for proportions and an improper flat Normal prior for
 * means. Operators who want to inject domain knowledge can set one
 * or more of these fields; everything else continues to use the
 * defaults.
 *
 * Conjugacy details:
 *
 *  - **Beta / UNIQUE_CONVERSION**: the binomial likelihood's conjugate
 *    prior is `Beta(α, β)`, and the posterior after observing
 *    `k / n` is `Beta(α + k, β + n - k)`. With `α = β = 1` this
 *    is a uniform prior, which means the posterior collapses to the
 *    likelihood — operators who don't set anything get exactly
 *    "what the data says".
 *  - **Normal / EVENT_COUNT**: the posterior on the per-user mean
 *    with a flat prior is `N(sampleMean, sampleVar / n)`. With a
 *    proper Normal prior `N(m0, v0)` and known sample variance the
 *    posterior precision is `1/v0 + n/sampleVar`, the posterior
 *    mean is the precision-weighted average. Both fields must be
 *    provided together; a half-configured Normal prior falls back
 *    to the flat default and logs a warning (see
 *    [bosca.experimentation.jobs.bayesianNormalPosterior]).
 *
 * @property betaPriorAlpha α hyperparameter on the Beta prior.
 *           Default `1.0` (uniform).
 * @property betaPriorBeta β hyperparameter on the Beta prior.
 *           Default `1.0` (uniform).
 * @property normalPriorMean Normal prior mean for per-user means.
 *           Null means "no prior" — use the improper flat posterior.
 * @property normalPriorVariance Normal prior variance. Null means
 *           "no prior" — see [normalPriorMean].
 */
@Serializable
data class BayesianPrior(
    val betaPriorAlpha: Double = 1.0,
    val betaPriorBeta: Double = 1.0,
    val normalPriorMean: Double? = null,
    val normalPriorVariance: Double? = null,
) {
    init {
        require(betaPriorAlpha > 0.0) { "betaPriorAlpha must be positive, got $betaPriorAlpha" }
        require(betaPriorBeta > 0.0) { "betaPriorBeta must be positive, got $betaPriorBeta" }
        require(normalPriorVariance == null || normalPriorVariance > 0.0) {
            "normalPriorVariance must be positive when set, got $normalPriorVariance"
        }
    }

    companion object {
        /** Sane defaults: uniform Beta(1,1) and flat Normal. */
        val DEFAULT = BayesianPrior()
    }
}
