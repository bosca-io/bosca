package bosca.experimentation.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Categorizes the type of change that occurred to a feature flag,
 * allowing subscribers to react accordingly (e.g., invalidate caches
 * or re-evaluate flag values).
 */
@Serializable
enum class FlagUpdateAction {
    UPDATED,
    DELETED,
}

/**
 * Event published via PubSub when a feature flag is modified, enabling
 * real-time propagation of flag changes to connected clients via
 * GraphQL subscriptions and cache invalidation across server instances.
 */
@Serializable
data class FlagUpdated(
    val flagKey: String,
    @Contextual
    val flagId: UUID,
    val action: FlagUpdateAction
)

/** PubSub channel name for feature flag update events. */
const val FLAG_UPDATED_CHANNEL = "experimentation.flags.updated"
