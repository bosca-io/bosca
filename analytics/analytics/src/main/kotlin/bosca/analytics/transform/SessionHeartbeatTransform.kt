package bosca.analytics.transform

import bosca.analytics.livesessions.LiveSessionsService
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.model.LiveSession
import bosca.counter.Counter
import bosca.di.ObjectProvider

/**
 * The single pipeline stage that consumes ephemeral [EventType.Heartbeat] events. It derives two
 * independent views of "an active session" from each heartbeat, then strips the heartbeats so they never
 * reach storage. Both derivations run *before* the strip — folding them into one transform makes that
 * ordering a local invariant rather than a contract spread across two pipeline positions.
 *
 * **1. Live map (per-session location).** Each geo-tagged heartbeat is published to the
 * [LiveSessionsService] stream that backs the live-sessions map as a [LiveSession] — session id + coarse,
 * IP-derived lat/lon + app version. A batch carries one session context, so at most one point is published
 * per batch. A heartbeat with no Cloudflare-derived location is skipped here (it simply never appears on
 * the map) but is still counted below.
 *
 * **2. Active-session count (aggregate).** Each heartbeat increments the distributed [Counter] bucket for
 * the batch's app — `sessions.<appId>.<15-min index>`. Because a session emits exactly one heartbeat per
 * window, a bucket's value **is** the count of distinct active sessions for that window, so no dedup is
 * needed; a session that stops heartbeating simply falls out of the next bucket (the 15-minute bucket size
 * is the session timeout). Reading active sessions = `max(current, previous)`.
 *
 * Heartbeats carry no analytical value, so this transform removes them from the returned batch — they never
 * reach the event store (Iceberg / DB).
 *
 * **Ordering.** Runs AFTER [bosca.analytics.transform.geo.CloudflareGeoTransform] so the batch's geo is
 * populated before the live-map publish reads it.
 *
 * **[liveSessions] is resolved lazily.** It is `.get()`-ed only when a heartbeat is actually published.
 * The collector normally strips heartbeats before enqueueing the batch, but processor composition roots
 * retain the binding so retained or redelivered heartbeats from an earlier collector attempt remain
 * processable. A heartbeat older than the active-session window is still counted into its historical
 * bucket and stripped, but is not republished as a currently-live session.
 *
 * **Not idempotent — by design.** [EventPipelineTransform] asks transforms to be idempotent because a
 * downstream failure NAKs the batch and the whole chain re-runs on redelivery. Re-publishing a session's
 * point is a harmless set-overwrite on the client, but this transform re-counts the same heartbeats on each
 * replay. That over-count is accepted rather than deduped: active-session telemetry is ephemeral and
 * approximate — buckets roll over every [BUCKET_SECONDS] and reads take `max(current, previous)` — so a
 * transient inflation from a redelivered batch washes out within one window and never affects stored data.
 */
class SessionHeartbeatTransform(
    private val counter: Counter,
    private val liveSessions: ObjectProvider<LiveSessionsService>,
    private val clock: () -> Long = System::currentTimeMillis,
) : EventPipelineTransform {

    override suspend fun transform(context: EventPipelineContext, events: Events): Events {
        val heartbeats = events.events.count { it.type == EventType.Heartbeat }
        if (heartbeats == 0) return events

        // Live map first (it reads geo the strip would later discard), then meter, then strip.
        publishLiveSession(events)

        val eventContext = events.context
        val appId = if (eventContext == null) UNKNOWN_APP else eventContext.appId
        val key = key(appId, bucketOf(events.received))
        repeat(heartbeats) { counter.increment(key) }

        return events.copy(events = events.events.filterNot { it.type == EventType.Heartbeat })
    }

    /**
     * Publishes the batch's session to the live map if it carries a coarse location — one point per batch
     * (a batch is one session context). Absent context or geo → not published (the session just does not
     * appear on the map); the caller still counts it.
     */
    private suspend fun publishLiveSession(events: Events) {
        val received = events.received
        if (received != null && clock() - received >= BUCKET_MILLIS) return
        val ctx = events.context ?: return
        val geo = ctx.geo ?: return
        val latitude = geo.latitude ?: return
        val longitude = geo.longitude ?: return
        liveSessions.get().publish(
            ctx.appId,
            LiveSession(
                sessionId = ctx.sessionId,
                latitude = latitude,
                longitude = longitude,
                appVersion = ctx.appVersion,
            ),
        )
    }

    /** The 15-minute bucket index for a received-at millis (server time; falls back to now if unset). */
    private fun bucketOf(receivedMillis: Long?): Long =
        (receivedMillis ?: clock()) / 1000 / BUCKET_SECONDS

    companion object {
        /** Session-timeout window; also the counter bucket size. */
        const val BUCKET_SECONDS = 15L * 60
        const val BUCKET_MILLIS = BUCKET_SECONDS * 1000
        const val UNKNOWN_APP = "unknown"

        /** Counter key for an app's active-session bucket at a 15-minute index. */
        fun key(appId: String, bucket: Long): String = "sessions.$appId.$bucket"
    }
}
