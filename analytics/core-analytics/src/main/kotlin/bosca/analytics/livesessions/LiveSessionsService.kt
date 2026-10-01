package bosca.analytics.livesessions

import bosca.analytics.model.LiveSession
import bosca.service.Service
import kotlinx.coroutines.flow.Flow

/**
 * A short-retention stream of geo-tagged session heartbeats backing the live sessions map. Backed by
 * NATS (a JetStream stream with a 15-minute `maxAge`) or Redis (a timestamp-scored sorted set plus a
 * pub/sub tail), selected by the `liveSessions.type` config — the same "NATS or Redis, by config"
 * pattern as [bosca.counter.Counter] and [bosca.pubsub.PubSubService], so domain code never binds to a
 * concrete product.
 *
 * There is no de-duplication and no last-write-wins: a session emits ~one heartbeat per window and the
 * retention window is the session-active window, so the retained contents already ARE the active set.
 * A session that stops heartbeating ages out on its own.
 */
interface LiveSessionsService : Service {

    /**
     * Append a heartbeat for [appId]. The session's [LiveSession.appVersion] selects the segment.
     * The published payload carries [LiveSession.appId] = [appId] (stamped here, not by the caller)
     * so a cross-application subscriber can attribute every point to its app.
     */
    suspend fun publish(appId: String, session: LiveSession)

    /**
     * Stream the currently-active sessions — first the retained snapshot, then live heartbeats as
     * they arrive. Pass [appId] to restrict to one application; pass `null` to stream every
     * application. Pass [appVersion] to restrict to one version; omit it for all versions.
     * The returned flow is cold and begins on collection; cancelling it releases the subscription.
     */
    fun subscribe(appId: String? = null, appVersion: String? = null): Flow<LiveSession>
}
