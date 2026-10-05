package bosca.analytics.livesessions.nats

import bosca.analytics.livesessions.LiveSessionsService
import bosca.analytics.model.LiveSession
import bosca.nats.NatsConnectionPool
import io.nats.client.PushSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import io.nats.client.api.StreamConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Duration as JavaDuration

/**
 * NATS-backed [LiveSessionsService]. Heartbeats are published to `sessions.geo.<appId>.<encodedVersion>` on one
 * JetStream stream whose `maxAge` (15 min) is the active-session window — old heartbeats fall out on
 * their own, so the retained set is the current active set. [subscribe] creates an ephemeral push
 * consumer with `DeliverPolicy.All`, which replays the retained snapshot and then tails new heartbeats
 * in a single stream; `AckPolicy.None` because this is a fan-out read, not a work queue.
 */
class NatsLiveSessions(
    private val pool: NatsConnectionPool,
    private val json: Json,
) : LiveSessionsService {

    private val streamMutex = Mutex()
    @Volatile
    private var streamReady = false

    override suspend fun publish(appId: String, session: LiveSession) = withContext(Dispatchers.IO) {
        ensureStream()
        // Stamp the app into the payload so it always agrees with the subject — an all-apps
        // subscriber only sees the payload.
        val stamped = session.copy(appId = appId)
        val payload = json.encodeToString(LiveSession.serializer(), stamped).toByteArray(Charsets.UTF_8)
        pool.systemConnection().jetStream().publish(subject(appId, session.appVersion), payload)
        Unit
    }

    override fun subscribe(appId: String?, appVersion: String?): Flow<LiveSession> = flow {
        ensureStream()
        val filter = subjectFilter(appId, appVersion)
        val consumerConfig = ConsumerConfiguration.builder()
            .deliverPolicy(DeliverPolicy.All) // replay the retained snapshot, then tail new heartbeats
            .ackPolicy(AckPolicy.None)
            .filterSubject(filter)
            .build()
        val subscription = pool.systemConnection().jetStream().subscribe(
            filter,
            PushSubscribeOptions.builder().configuration(consumerConfig).build(),
        )
        try {
            while (currentCoroutineContext().isActive) {
                val message = subscription.nextMessage(POLL_TIMEOUT) ?: continue
                emit(json.decodeFromString(LiveSession.serializer(), message.data.toString(Charsets.UTF_8)))
            }
        } finally {
            runCatching { subscription.unsubscribe() }
        }
    }.flowOn(Dispatchers.IO)

    /** Create the retention-bounded stream once per process (idempotent under a startup race). */
    private suspend fun ensureStream() {
        if (streamReady) return
        streamMutex.withLock {
            if (streamReady) return
            val management = pool.systemConnection().jetStreamManagement()
            val exists = management.streamNames.contains(STREAM)
            if (!exists) {
                val config = StreamConfiguration.builder()
                    .name(STREAM)
                    .subjects("$PREFIX.>")
                    .maxAge(MAX_AGE)
                    .build()
                try {
                    management.addStream(config)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (creationFailure: Exception) {
                    // A concurrent creator may have won after our lookup. Only suppress the failure
                    // when a second lookup proves the required stream now exists.
                    if (!management.streamNames.contains(STREAM)) throw creationFailure
                }
            }
            streamReady = true
        }
    }

    companion object {
        internal const val STREAM = "BOSCA_LIVE_SESSIONS"
        internal const val PREFIX = "sessions.geo"
        internal const val NO_VERSION = "_none"
        private val MAX_AGE: JavaDuration = JavaDuration.ofMinutes(15)
        private val POLL_TIMEOUT: JavaDuration = JavaDuration.ofMillis(500)

        /** Token-safe encoding of an app version for a NATS subject token (`.` is the token separator). */
        internal fun encodeVersion(appVersion: String?): String =
            if (appVersion.isNullOrEmpty()) NO_VERSION else appVersion.replace('.', '_')

        internal fun subject(appId: String, appVersion: String?): String =
            "$PREFIX.$appId.${encodeVersion(appVersion)}"

        /**
         * Subject filter for a subscription. `null` [appId] spans every application (`>` catches all
         * remaining tokens, or `*` holds the app position when a version narrows the last token);
         * `null` [appVersion] spans all versions of the app.
         */
        internal fun subjectFilter(appId: String?, appVersion: String?): String = when {
            appId == null && appVersion == null -> "$PREFIX.>"
            appId == null -> "$PREFIX.*.${encodeVersion(appVersion)}"
            appVersion == null -> "$PREFIX.$appId.*"
            else -> "$PREFIX.$appId.${encodeVersion(appVersion)}"
        }
    }
}
