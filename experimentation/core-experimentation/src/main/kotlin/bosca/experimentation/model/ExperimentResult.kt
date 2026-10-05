package bosca.experimentation.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Aggregated performance metrics for a specific variation and conversion goal
 * combination within an experiment.
 *
 * Results are computed by the result aggregation job, which queries the analytics
 * data warehouse for outcome-eligible subject and conversion counts, then derives rates,
 * statistical confidence, and lift over the rollout's control variation. The
 * variation referenced by [variationKey] must exist in the parent flag's variation
 * palette at the time the result was computed; if the variation is later removed,
 * the result becomes orphaned and is filtered out by the read path.
 */
@BatchKey("id")
@Serializable
data class ExperimentResult(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("experiment_id")
    @Contextual
    val experimentId: UUID,
    @ColumnName("variation_key")
    val variationKey: String,
    @ColumnName("goal_id")
    @Contextual
    val goalId: UUID,
    /** Number of subjects eligible for outcomes after applying the activation filter. */
    val impressions: Long = 0,
    /** Raw assignments used for allocation and sample-ratio diagnostics. */
    val assignments: Long = impressions,
    /** Number of independent subject-level observations used by the statistic. */
    @ColumnName("observation_count")
    val observationCount: Long = impressions,
    val conversions: Long = 0,
    @ColumnName("conversion_rate")
    val conversionRate: Double = 0.0,
    @ColumnName("confidence_level")
    val confidenceLevel: Double? = null,
    @ColumnName("lift_over_control")
    val liftOverControl: Double? = null,
    /**
     * Per-user mean of matching events for an `EVENT_COUNT` goal, or mean
     * derived session seconds for a `SESSION_DURATION` goal. `null` for
     * `UNIQUE_CONVERSION`, where the meaningful metric is [conversionRate].
     */
    val mean: Double? = null,
    /**
     * Sample variance for a continuous goal, computed with Bessel's correction
     * (`n - 1` denominator). `null` for `UNIQUE_CONVERSION` goals.
     */
    val variance: Double? = null,
    /**
     * Posterior `P(this variation's metric > control's metric)`.
     * Populated only for Bayesian runs. Ranges 0..1. Null on the
     * control row (comparing against itself is meaningless) and null
     * on every row of a frequentist run.
     */
    @ColumnName("probability_beats_control")
    val probabilityBeatsControl: Double? = null,
    /**
     * Posterior `E[max(0, control - this)]` — the expected magnitude
     * of the regret from picking this variation when the control is
     * actually better. Ranges 0..∞; small is good. Populated only
     * for Bayesian runs.
     */
    @ColumnName("expected_loss")
    val expectedLoss: Double? = null,
    /**
     * CUPED-adjusted per-user mean for `EVENT_COUNT` goals. Populated
     * only when the goal carries a `cupedCovariate` configuration and
     * the pre-period query produced a valid covariate. `adjustedMean`
     * is the residual mean after the θ × (x - x̄) adjustment and is
     * what [bosca.experimentation.jobs.computeLiftWithCi] feeds into
     * Welch's formula for the confidence. The unadjusted [mean]
     * remains the "number operators see" — only the CI width uses
     * the adjusted variance.
     */
    @ColumnName("adjusted_mean")
    val adjustedMean: Double? = null,
    /**
     * CUPED-adjusted per-user sample variance. Paired with
     * [adjustedMean]. Strictly smaller than [variance] whenever the
     * covariate explains any portion of the post-period variation,
     * which is the whole point of the CUPED adjustment.
     */
    @ColumnName("adjusted_variance")
    val adjustedVariance: Double? = null,
    @ColumnName("updated_at")
    @Contextual
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    init {
        require(assignments >= 0) { "Assignments must be non-negative, got $assignments" }
        require(impressions >= 0) { "Impressions must be non-negative, got $impressions" }
        require(observationCount >= 0) { "Observation count must be non-negative, got $observationCount" }
        require(conversions >= 0) { "Conversions must be non-negative, got $conversions" }
        // Note: `conversions <= impressions` is only meaningful for
        // UNIQUE_CONVERSION goals (where conversions counts distinct converting
        // users). For EVENT_COUNT goals, `conversions` holds the total event
        // count and may exceed the number of exposed users, so we do not
        // enforce that invariant at the model layer.
        require(confidenceLevel == null || confidenceLevel in 0.0..1.0) {
            "Confidence level must be between 0.0 and 1.0, got $confidenceLevel"
        }
        require(variance == null || variance >= 0.0) {
            "Variance must be non-negative, got $variance"
        }
        require(probabilityBeatsControl == null || probabilityBeatsControl in 0.0..1.0) {
            "probabilityBeatsControl must be in [0, 1], got $probabilityBeatsControl"
        }
        require(expectedLoss == null || expectedLoss >= 0.0) {
            "expectedLoss must be non-negative, got $expectedLoss"
        }
        require(adjustedVariance == null || adjustedVariance >= 0.0) {
            "adjustedVariance must be non-negative, got $adjustedVariance"
        }
    }
}
