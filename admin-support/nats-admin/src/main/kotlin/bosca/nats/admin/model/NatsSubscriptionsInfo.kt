package bosca.nats.admin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subscription routing statistics from the NATS `/subsz` monitoring endpoint.
 * Provides insight into the subscription trie cache performance and fanout characteristics.
 */
@Serializable
data class NatsSubscriptionsInfo(
    @SerialName("num_subscriptions") val numSubscriptions: Long,
    @SerialName("num_cache") val numCache: Long,
    @SerialName("num_inserts") val numInserts: Long,
    @SerialName("num_removes") val numRemoves: Long,
    @SerialName("num_matches") val numMatches: Long,
    @SerialName("cache_hit_rate") val cacheHitRate: Double,
    @SerialName("max_fanout") val maxFanout: Int,
    @SerialName("avg_fanout") val avgFanout: Double,
)
