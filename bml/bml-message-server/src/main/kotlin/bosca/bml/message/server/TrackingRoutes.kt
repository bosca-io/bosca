package bosca.bml.message.server

import bosca.analytics.model.Element
import bosca.analytics.model.Event
import bosca.analytics.model.EventType
import bosca.analytics.server.ServerAnalyticsClient
import bosca.bml.message.client.EmailLinkClicked
import bosca.bml.message.client.EmailOpened
import bosca.pubsub.PubSubService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * The PUBLIC engagement routes — they join `/assets` on the public host:
 *
 * - `GET /c/{token}` — verify the signed token, publish [EmailLinkClicked], 302 to the original
 *   URL. The redirect is NEVER blocked by event emission: a publish failure logs and the user
 *   still lands where they clicked.
 * - `GET /o/{token}` — verify, publish [EmailOpened], respond a 1×1 transparent GIF.
 *
 * A token that fails verification answers 404 and emits nothing (fail closed — a forged token
 * can neither phish a redirect nor pollute engagement data).
 */
object TrackingRoutes {

    private val log = LoggerFactory.getLogger(TrackingRoutes::class.java)

    private val PIXEL: ByteArray = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")

    /** Engagement as an analytics [Event]: the kind rides Element.type, the detail its extras. */
    private fun engagementEvent(type: EventType, kind: String, payload: LinkTracking.Payload): Event = Event(
        created = System.currentTimeMillis(),
        type = type,
        element = Element(
            type = kind,
            extras = buildJsonObject {
                put("messageId", payload.m)
                payload.r?.let { put("recipientId", it) }
                if (payload.u.isNotBlank()) put("url", payload.u)
            },
        ),
    )

    fun install(
        application: BoscaApplication,
        tracking: LinkTracking,
        pubSub: PubSubService?,
        analytics: ServerAnalyticsClient?,
    ) {
        val router = application.router

        router.get("/c/{token}") {
            val payload = tracking.verify(call.pathParameters["token"].orEmpty())
            if (payload == null || payload.u.isBlank()) {
                call.respond(HttpStatusCode.NotFound, "404 Not Found")
                return@get
            }
            if (pubSub != null) {
                runCatching {
                    pubSub.publish(
                        EmailLinkClicked.CHANNEL,
                        EmailLinkClicked.serializer(),
                        EmailLinkClicked(
                            messageId = payload.m,
                            recipientId = payload.r,
                            url = payload.u,
                            id = engagementId(),
                        ),
                    )
                }.onFailure { log.warn("bml-message: click event publish failed for message {}: {}", payload.m, it.message) }
            }
            // Click-through RATES live in analytics (Iceberg/Trino); the capture contract
            // logs-and-drops on pipeline failures, so it can never block the redirect.
            analytics?.capture(engagementEvent(EventType.Interaction, "email-click", payload))
            call.respondRedirect(payload.u)
        }

        router.get("/o/{token}") {
            val payload = tracking.verify(call.pathParameters["token"].orEmpty())
            if (payload == null) {
                call.respond(HttpStatusCode.NotFound, "404 Not Found")
                return@get
            }
            if (pubSub != null) {
                runCatching {
                    pubSub.publish(
                        EmailOpened.CHANNEL,
                        EmailOpened.serializer(),
                        EmailOpened(
                            messageId = payload.m,
                            recipientId = payload.r,
                            id = engagementId(),
                        ),
                    )
                }.onFailure { log.warn("bml-message: open event publish failed for message {}: {}", payload.m, it.message) }
            }
            analytics?.capture(engagementEvent(EventType.Impression, "email-open", payload))
            // Never cache: every render of the pixel is a distinct open signal.
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondBytes(PIXEL, ContentType("image", "gif"))
        }
    }

    private fun engagementId(): String = "bml-${java.util.UUID.randomUUID()}"
}
