package bosca.analytics.livesessions.redis

import bosca.analytics.livesessions.LiveSessionsService
import bosca.analytics.model.LiveSession
import bosca.redis.RedisConnectionPool
import bosca.redis.SharedConnection
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.KeyScanCursor
import io.lettuce.core.Range
import io.lettuce.core.ScanArgs
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Redis-backed [LiveSessionsService]. Each app keeps a sorted set of heartbeats scored by publish time: a
 * heartbeat is `ZADD`ed and the window is trimmed with `ZREMRANGEBYSCORE`, so the members within the
 * TTL window are the current active set (the snapshot). Live tailing rides a pub/sub channel per app,
 * using the same ref-counted subscription pattern as [bosca.pubsub.RedisPubSubServiceImpl] so
 * concurrent subscribers to one app do not unsubscribe each other. Version filtering is applied to the
 * decoded [LiveSession.appVersion].
 *
 * An all-applications subscribe (`appId = null`) SCANs the per-app sorted sets for the snapshot and
 * tails a pattern subscription (`PSUBSCRIBE`) across every app channel — same ref-counting, keyed by
 * the pattern.
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisLiveSessions(
    private val connections: RedisConnectionPool,
    private val json: Json,
    private val ttl: Duration = DEFAULT_TTL,
) : LiveSessionsService {

    private val sharedConnection = SharedConnection(connections)
    private val subscriptions = mutableMapOf<String, Int>()
    private val mutex = Mutex()

    override suspend fun publish(appId: String, session: LiveSession) {
        // Stamp the app into the payload so it always agrees with the key/channel — an all-apps
        // subscriber only sees the payload.
        val member = json.encodeToString(LiveSession.serializer(), session.copy(appId = appId))
        val now = clock()
        val connection = connections.connection()
        try {
            val commands = connection.coroutines()
            commands.zadd(key(appId), now.toDouble(), member)
            commands.zremrangebyscore(key(appId), staleRange(now))
            commands.expire(key(appId), ttl.inWholeSeconds * 2)
        } finally {
            connections.release(connection)
        }
        // Tail: notify live subscribers immediately.
        sharedConnection.pubSubConnection().coroutines().publish(channel(appId), member)
    }

    override fun subscribe(appId: String?, appVersion: String?): Flow<LiveSession> = flow {
        // Snapshot: the members still inside the TTL window (trim stale first) — one app's sorted
        // set, or every app's when unfiltered.
        val now = clock()
        val connection = connections.connection()
        val snapshot: List<String> = try {
            val commands = connection.coroutines()
            val keys = if (appId != null) listOf(key(appId)) else scanKeys(commands)
            keys.flatMap { k ->
                commands.zremrangebyscore(k, staleRange(now))
                commands.zrangebyscore(k, freshRange(now)).toList()
            }
        } finally {
            connections.release(connection)
        }
        for (member in snapshot) {
            val session = decode(member) ?: continue
            if (matches(session, appVersion)) emit(session)
        }

        // Tail: live heartbeats, ref-counted across concurrent subscribers. A single-app subscribe
        // rides the app's channel; all-apps rides a PSUBSCRIBE across every app channel, ref-counted
        // under the pattern itself.
        val pubSub = sharedConnection.pubSubConnection().reactive()
        val target = if (appId != null) channel(appId) else CHANNEL_PATTERN
        val shouldSubscribe = acquireSubscription(target)
        try {
            if (shouldSubscribe) {
                if (appId != null) pubSub.subscribe(target).block()
                else pubSub.psubscribe(target).block()
            }
            val tail = if (appId != null) {
                pubSub.observeChannels().asFlow().filter { it.channel == target }.map { it.message }
            } else {
                pubSub.observePatterns().asFlow().filter { it.pattern == target }.map { it.message }
            }
            tail.collect { member ->
                val session = decode(member) ?: return@collect
                if (matches(session, appVersion)) emit(session)
            }
        } finally {
            val shouldUnsubscribe = releaseSubscription(target)
            if (shouldUnsubscribe) {
                if (appId != null) pubSub.unsubscribe(target).block()
                else pubSub.punsubscribe(target).block()
            }
        }
    }.flowOn(Dispatchers.IO)

    internal suspend fun acquireSubscription(target: String): Boolean = mutex.withLock {
        val previous = subscriptions[target] ?: 0
        subscriptions[target] = previous + 1
        previous == 0
    }

    internal suspend fun releaseSubscription(target: String): Boolean = mutex.withLock {
        val remaining = subscriptions.getValue(target) - 1
        if (remaining == 0) {
            subscriptions.remove(target)
            true
        } else {
            subscriptions[target] = remaining
            false
        }
    }

    /** Every app's sorted-set key, via cursored SCAN so an all-apps snapshot never blocks Redis. */
    internal suspend fun scanKeys(commands: RedisCoroutinesCommands<String, String>): List<String> {
        val keys = mutableListOf<String>()
        val args = ScanArgs.Builder.matches("$KEY_PREFIX*").limit(SCAN_BATCH)
        var cursor: KeyScanCursor<String>? = commands.scan(args)
        while (cursor != null) {
            keys += cursor.keys
            if (cursor.isFinished) break
            cursor = commands.scan(cursor, args)
        }
        return keys
    }

    private fun clock(): Long = System.currentTimeMillis()

    /** Scores strictly inside the TTL window: `[now - ttl, +inf)`. */
    private fun freshRange(now: Long): Range<Double> =
        Range.create((now - ttl.inWholeMilliseconds).toDouble(), Double.MAX_VALUE)

    /** Scores that have aged out: `[0, now - ttl)`. */
    private fun staleRange(now: Long): Range<Double> =
        Range.create(0.0, (now - ttl.inWholeMilliseconds).toDouble())

    internal fun decode(member: String): LiveSession? =
        runCatching { json.decodeFromString(LiveSession.serializer(), member) }.getOrNull()

    companion object {
        val DEFAULT_TTL: Duration = 15.minutes

        internal const val KEY_PREFIX = "sessions.geo:"
        internal const val CHANNEL_PATTERN = "sessions.geo.ch:*"
        private const val SCAN_BATCH = 200L

        internal fun key(appId: String): String = "$KEY_PREFIX$appId"
        internal fun channel(appId: String): String = "sessions.geo.ch:$appId"

        /** A session matches when no version filter is set, or its version equals the requested one. */
        internal fun matches(session: LiveSession, appVersion: String?): Boolean =
            appVersion == null || session.appVersion == appVersion
    }
}
