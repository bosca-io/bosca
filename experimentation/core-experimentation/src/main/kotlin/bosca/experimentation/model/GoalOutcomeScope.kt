package bosca.experimentation.model

import kotlinx.serialization.Serializable

/** Outcome scope relative to eligibility (or goal activation for the descriptive exposed view). */
@Serializable
data class GoalOutcomeScope(
    val applicationIds: List<String> = emptyList(),
    val sameSession: Boolean = false,
    val maxDelaySeconds: Int? = null,
    /** Require a recommendation card identifier and a selected content UUID. */
    val recommendationCard: Boolean = false,
    /** Also measure exact same-session target destinations within five minutes of card selection. */
    val attributeRecommendationDestination: Boolean = false,
)
