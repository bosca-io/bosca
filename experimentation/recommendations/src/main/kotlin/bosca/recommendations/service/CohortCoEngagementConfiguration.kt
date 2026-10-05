package bosca.recommendations.service

import kotlinx.serialization.Serializable

/**
 * Tuning knobs for the COHORT_CO_ENGAGEMENT ("people like you") strategy, stored in the strategy's
 * `configuration` JSON so they can be changed via the API without re-seeding the analytics query. Both bound
 * the nightly co-occurrence batch; neither affects request latency (the serve path reads an indexed table).
 *
 * The defaults (200/200) are sized so the serve path is unaffected: `recommended`/`coEngaged` clamp their
 * `limit` to 50 and the assembler fetches `limit × 3`, so a request reads at most 150 cohort edges per source
 * — well under [perSourceCap], making that cap lossless. [perUserItemCap] only truncates genuine power-users'
 * low-signal tail before the self-join.
 */
@Serializable
data class CohortCoEngagementConfiguration(
    /**
     * (Cap A) Keep only each user's top-N most-recently-engaged items before the co-occurrence self-join, so
     * a single high-activity user can't blow it up (the join cost is ~Σ per-user-items², dominated by the
     * heaviest users). Higher = more long-tail coverage at a higher nightly batch cost.
     */
    val perUserItemCap: Int = 200,
    /**
     * (Cap B) Materialize only the top-N highest-scoring co-engaged items per source per cohort. Keep this at
     * or above the serve path's max fetch (`limit`-cap × assembler multiplier = 50 × 3 = 150) so it never
     * clips a result a request could read; it only trims a tail nothing consumes.
     */
    val perSourceCap: Int = 200,
)
