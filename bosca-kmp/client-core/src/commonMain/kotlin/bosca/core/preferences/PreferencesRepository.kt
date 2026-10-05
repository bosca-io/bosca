package bosca.core.preferences

import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Repository for persisting and observing user navigation preferences,
 * such as the most recently viewed community and channel.
 *
 * Built on top of [Preferences], this interface provides typed accessors
 * so callers do not need to manage raw preference keys or serialization.
 */
interface PreferencesRepository {

    /**
     * A [Flow] that emits the [Uuid] of the most recently selected community,
     * or `null` if no community has been selected yet.
     */
    val lastCommunityId: Flow<Uuid?>

    /**
     * Records the given community [id] as the most recently selected community.
     *
     * @param id the community identifier to persist
     */
    suspend fun setLastCommunityId(id: Uuid)

    /**
     * Returns a [Flow] emitting the [Uuid] of the most recently selected channel
     * within the specified community, or `null` if none has been recorded.
     *
     * @param communityId the community whose last-viewed channel should be observed
     */
    fun lastChannelId(communityId: Uuid): Flow<Uuid?>

    /**
     * Records the given [channelId] as the most recently selected channel within
     * the specified [communityId].
     *
     * @param communityId the community the channel belongs to
     * @param channelId the channel identifier to persist
     */
    suspend fun setLastChannelId(communityId: Uuid, channelId: Uuid)
}
