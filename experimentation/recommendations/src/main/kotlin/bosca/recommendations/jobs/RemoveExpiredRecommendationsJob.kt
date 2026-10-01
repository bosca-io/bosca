package bosca.recommendations.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/**
 * Job payload for removing expired recommendations across all
 * strategies. Runs as a periodic cleanup task to purge entries
 * whose expiration timestamp has passed.
 */
@Serializable
class RemoveExpiredRecommendationsJob : IJobDefinition
