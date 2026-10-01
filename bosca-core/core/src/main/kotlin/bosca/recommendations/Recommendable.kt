package bosca.recommendations

/**
 * Indicates that an entity can be included in content recommendation candidates.
 *
 * Implementations expose their saved recommendation eligibility through [isRecommendable].
 * Recommendation engines exclude entities for which this value is `false`.
 */
interface Recommendable {

    /** Whether this entity may be returned as a recommendation candidate. */
    val isRecommendable: Boolean
}
