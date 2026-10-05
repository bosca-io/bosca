package bosca.segmentation.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Lightweight projection of a banner campaign containing only its
 * identifier and random selection weight. Returned by placement
 * queries so that clients can cache the weight distribution
 * separately from full campaign data.
 *
 * The [weight] value is not a priority — it represents relative
 * probability in a weighted random selection. For example, weights
 * of 10, 10, and 80 yield selection probabilities of 10%, 10%, and 80%.
 */
@Serializable
data class BannerWeight(
    @Contextual
    val id: UUID,
    val weight: Int
)
