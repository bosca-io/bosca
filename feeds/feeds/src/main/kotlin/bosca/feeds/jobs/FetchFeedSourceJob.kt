package bosca.feeds.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Payload for a single feed-source fetch: which source to fetch, and whether to [force] (skip the
 * conditional-GET cache). [force] defaults false — only the manual "fetch now" trigger sets it true.
 */
@Serializable
class FetchFeedSourceJob(
    @Contextual val feedSourceId: UUID,
    val force: Boolean = false,
) : IJobDefinition
