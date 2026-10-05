package bosca.recommendations.model

import kotlinx.serialization.Serializable

/**
 * A user's feedback gesture on a recommended item. The recommendation feedback mutation routes
 * each gesture to an existing primitive — [BOOST]/[LOWER]/[CARE] become a profile rating, [HIDE] becomes
 * a dismissal — so feedback tunes future recommendations without any new feedback store.
 *
 * The constant names are the serialized form, matching the uppercase GraphQL enum values; no
 * `@SerialName` is used so the GraphQL enum coercion resolves each gesture by its declared name.
 */
@Serializable
enum class RecommendationFeedback {
    /** Strong positive — "more like this." */
    BOOST,

    /** Negative — "less like this." */
    LOWER,

    /** Positive interest — "I care about this." */
    CARE,

    /** Remove from the feed — routed to a dismissal, not a rating. */
    HIDE,
}
