package bosca.experimentation.model

import bosca.analytics.model.EventType
import kotlinx.serialization.Serializable

/**
 * CUPED (Controlled experiment Using Pre-Experiment Data) covariate
 * configuration attached to a [ConversionGoal].
 *
 * When set, the aggregator runs a *second* query over the analytics
 * events table for the window immediately preceding each user's
 * assignment, extracts a per-user covariate value matching the same
 * event/element/page filters as [eventType] / [elementType] /
 * [elementId] / [pagePath], and uses ordinary least squares to
 * subtract the part of the post-period outcome that the pre-period
 * value already "explained". The adjusted metric has strictly lower
 * variance whenever the covariate correlates at all with the
 * outcome, which translates into tighter confidence intervals and
 * faster time-to-significance — typically a 30-50% reduction in
 * required sample size on per-user count metrics.
 *
 * Sane defaults:
 *
 *  - The filter fields default to `null`, matching the goal's own
 *    filters — if the goal counts "interactions on /checkout" then
 *    the covariate should too. Operators can override any field to
 *    use a different pre-period signal (e.g. "total sessions in
 *    the two weeks before the experiment").
 *  - [lookbackWindow] defaults to the 14-day ISO-8601 string
 *    `"P14D"`. Operators can tune this per experiment — shorter
 *    windows reduce noise from unrelated historical events but
 *    lose predictive power; longer windows do the opposite.
 *
 * The covariate is NOT recomputed per analysis cycle — it is a
 * per-user constant defined over a fixed pre-period window ending
 * at assignment time — but the first-pass implementation recomputes
 * on every aggregation run for simplicity. A future optimization
 * can cache per-user covariate values on a sibling table keyed by
 * (experiment_id, client_id). See requirements.md D7.
 */
@Serializable
data class CupedCovariate(
    val eventType: EventType? = null,
    val elementType: String? = null,
    val elementId: String? = null,
    /**
     * Overrides the goal's entire page filter with this exact path. Null inherits both
     * [ConversionGoal.pagePath] and [ConversionGoal.pagePathPrefixes] as alternatives.
     */
    val pagePath: String? = null,
    /**
     * Pre-period window as an ISO-8601 duration string
     * (`"P14D"`, `"P30D"`, `"PT168H"`, etc.). Parsed with
     * `java.time.Duration.parse` at aggregation time. Invalid
     * strings fall back to the 14-day default and log a warning.
     */
    val lookbackWindow: String = DEFAULT_LOOKBACK_WINDOW,
) {
    companion object {
        const val DEFAULT_LOOKBACK_WINDOW = "P14D"
    }
}
