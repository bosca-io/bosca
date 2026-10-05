package bosca.collaboration.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Result of validating a set of mentioned profile IDs against channel membership
 * and general chat participation eligibility. Used to determine who receives
 * notifications and who may be auto-invited to a channel.
 */
@Serializable
data class MentionValidationResult(
    /** Mentioned profiles that are already members of the channel */
    val channelMembers: List<UUID>,
    /** Mentioned profiles that can participate in chat but are not yet channel members */
    val reachableNonMembers: List<UUID>,
    /** Mentioned profiles that cannot participate in chat */
    val unreachable: List<UUID>
)
