package bosca.experimentation.model

import kotlinx.serialization.Serializable

/** Selects an explicit event, the default event, or no conditional stage for a variation. */
@Serializable
enum class GoalActivationMode { EVENT, INHERIT, NOT_APPLICABLE }
