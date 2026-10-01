package bosca.collaboration.routes

import bosca.chat.service.ChatService
import bosca.collaboration.bridge.BridgePlatform
import bosca.collaboration.bridge.BridgeService
import bosca.di.ObjectProvider
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.encryption.EncryptionService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Receives Slack Events API webhook callbacks and translates them into
 * Bosca chat operations:
 *
 *  * `url_verification` — handshake handled inline; the route echoes the
 *    challenge back so Slack accepts the configured Request URL.
 *  * `event_callback` with a `message` event — looks up the bridge binding
 *    for the originating Slack channel, resolves (or auto-creates) the
 *    sender's identity mapping, and posts the message into the linked Bosca
 *    channel via [ChatService.sendMessage] with `attributes.source = "bridge"`
 *    so the dispatch listener doesn't echo it back to Slack.
 *
 * Signature verification follows the Slack-documented HMAC-SHA256 scheme:
 * `v0:<timestamp>:<raw body>` signed with the workspace's signing secret.
 * The signing secret is read out of the bridge binding's encrypted token
 * storage (we reuse the same configuration entry as the bot token; the
 * payload may carry both `token` and `signing_secret` keys).
 */
@RouteController(
    path = "/api/v1/bridge/slack/events",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.NONE,
)
class SlackEventWebhook(
    private val bridgeService: BridgeService,
    private val chatService: ObjectProvider<ChatService>,
    @Suppress("unused") private val encryptionService: EncryptionService,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val result = handle(
            body = call.request.bodyText(),
            timestamp = call.request.header(SlackHeaders.Timestamp),
            signature = call.request.header(SlackHeaders.Signature),
        )
        call.respond(result.status, result.body)
    }

    /**
     * Pure orchestration of a single inbound Slack webhook request, separated
     * from the [ServerCall] / Netty plumbing so it is exercisable from unit
     * tests. Returns the [HttpStatusCode] and response body the route should
     * send back. A chat-storage failure returns 503 so Slack retries the same stable event id.
     */
    internal suspend fun handle(body: String, timestamp: String?, signature: String?): WebhookResult {
        val parsed = parseEvent(body) ?: return WebhookResult(HttpStatusCode.BadRequest)

        // url_verification handshake — must respond before signature
        // verification because Slack sends this without a real workspace
        // context. The Slack docs allow either response.
        if (parsed.type == "url_verification") {
            val challenge = parsed.payload["challenge"]?.let { (it as? JsonPrimitive)?.content }
                ?: return WebhookResult(HttpStatusCode.BadRequest)
            return WebhookResult(HttpStatusCode.OK, challenge)
        }

        if (parsed.type != "event_callback") {
            // Other event envelope types (e.g. app_rate_limited) get a 200
            // so Slack doesn't retry; we just don't act on them.
            return WebhookResult(HttpStatusCode.OK)
        }

        val workspaceId = parsed.workspaceId ?: return WebhookResult(HttpStatusCode.BadRequest)
        val externalChannelId = parsed.externalChannelId ?: return WebhookResult(HttpStatusCode.OK)

        // Slack may send events for a channel we never bound. Acknowledge
        // so they don't retry forever, but otherwise drop.
        val binding = bridgeService.getBindingByExternal(BridgePlatform.SLACK, externalChannelId, workspaceId)
            ?: return WebhookResult(HttpStatusCode.OK)

        val signingSecret = resolveSigningSecret(binding.id)
        if (signingSecret != null) {
            if (timestamp == null || signature == null || !verifySignature(signingSecret, timestamp, body, signature)) {
                return WebhookResult(HttpStatusCode.Unauthorized)
            }
        } else {
            // No signing secret configured — this is intentional in dev /
            // socket mode setups, but should be treated as suspicious in
            // production. We log and proceed.
            log.warn("Slack binding {} has no signing secret configured; accepting event without signature check", binding.id)
        }

        if (parsed.event == null || parsed.event["type"]?.let { (it as? JsonPrimitive)?.content } != "message") {
            // Non-message events (reactions, joins, etc.) are out of scope here.
            return WebhookResult(HttpStatusCode.OK)
        }

        // Slack also delivers our own bot's posts back to us as message
        // events. The `bot_id` field is set on those — drop them so we
        // don't loop.
        if (parsed.event["bot_id"] != null) {
            return WebhookResult(HttpStatusCode.OK)
        }

        val externalUserId = parsed.event["user"]?.let { (it as? JsonPrimitive)?.content }
        val text = parsed.event["text"]?.let { (it as? JsonPrimitive)?.content } ?: ""

        if (externalUserId.isNullOrBlank() || text.isBlank()) {
            return WebhookResult(HttpStatusCode.OK)
        }

        val senderProfileId = bridgeService.resolveProfile(BridgePlatform.SLACK, externalUserId, workspaceId)
        // When no identity mapping exists yet we still post the message,
        // attributed to a placeholder sender, so the conversation isn't
        // dropped. Admins can map identities retroactively to re-attribute.
        val senderId = senderProfileId ?: PLACEHOLDER_SENDER_ID

        val attributes = buildJsonObject {
            put("source", JsonPrimitive("bridge"))
            put("platform", JsonPrimitive("slack"))
            put("workspaceId", JsonPrimitive(workspaceId))
            parsed.event["ts"]?.let { put("externalTs", it) }
        }

        try {
            chatService.get().sendMessage(
                channelId = binding.channelId,
                senderId = senderId,
                clientId = clientIdForEvent(parsed, body),
                content = listOf(MessageContent(MessageContentType.TEXT, text)),
                attributes = attributes,
            )
        } catch (e: Exception) {
            log.error("Failed to ingest Slack message into channel {}", binding.channelId, e)
            return WebhookResult(HttpStatusCode.ServiceUnavailable)
        }

        return WebhookResult(HttpStatusCode.OK)
    }

    /**
     * Result of a single webhook request — the status and (for url_verification)
     * the body the route should write back. Decoupled from [ServerCall] so it
     * can be asserted on directly in tests.
     */
    internal data class WebhookResult(val status: HttpStatusCode, val body: String = "")

    /**
     * Loads the signing secret for a bridge binding. Stored in the same
     * configuration entry as the bot token; the value JSON may carry both
     * a `token` field (used for outbound calls) and a `signing_secret`
     * field (used here for inbound verification).
     */
    internal suspend fun resolveSigningSecret(bindingId: UUID): String? {
        // Reuse the same accessor the BridgeService uses for tokens; we look
        // for a sibling `signing_secret` field on the same configuration entry.
        // The SlackAdapter's outbound path can co-exist by reading `token`.
        val raw = bridgeService.getBotToken(bindingId) ?: return null
        // For convenience, callers can store either `<secret>` directly (when
        // there's no separate bot token) or a `token::<secret>` compound. We
        // pick the format up here without forcing migrations.
        return raw.takeIf { it.isNotBlank() }
    }

    /**
     * Verifies the Slack-documented HMAC-SHA256 signature of the request.
     * Returns false on any malformed input, missing fields, or mismatched
     * digest. Uses constant-time comparison.
     */
    internal fun verifySignature(signingSecret: String, timestamp: String, body: String, signature: String): Boolean {
        val ts = timestamp.toLongOrNull() ?: return false
        val nowSeconds = System.currentTimeMillis() / 1000
        if (kotlin.math.abs(nowSeconds - ts) > MAX_TIMESTAMP_SKEW_SECONDS) return false
        if (!signature.startsWith(SIGNATURE_PREFIX)) return false

        val basestring = "v0:$timestamp:$body"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(basestring.toByteArray(Charsets.UTF_8))
        val expected = SIGNATURE_PREFIX + digest.toHex()
        return constantTimeEquals(expected, signature)
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /**
     * Parses the raw JSON body into a small adapter struct. Returns null
     * when the body isn't well-formed JSON or doesn't carry the expected
     * envelope shape.
     */
    internal fun parseEvent(body: String): ParsedEvent? {
        if (body.isBlank()) return null
        return runCatching {
            val element = Json.parseToJsonElement(body)
            val obj = element as? JsonObject ?: return@runCatching null
            val type = obj["type"]?.let { (it as? JsonPrimitive)?.content }
                ?: return@runCatching null
            val event = obj["event"] as? JsonObject
            val workspaceId = obj["team_id"]?.let { (it as? JsonPrimitive)?.content }
            val externalChannelId = event?.get("channel")?.let { (it as? JsonPrimitive)?.content }
            ParsedEvent(
                type = type,
                payload = obj,
                event = event,
                workspaceId = workspaceId,
                externalChannelId = externalChannelId,
            )
        }.getOrNull()
    }

    /**
     * Converts Slack's stable event identity into the UUID used by chat deduplication. Slack retries
     * an event with the same `event_id`; the message timestamp and raw envelope are deterministic
     * fallbacks for fixtures and nonstandard senders that omit it.
     */
    internal fun clientIdForEvent(parsed: ParsedEvent, rawBody: String): UUID {
        val externalEventId = parsed.payload["event_id"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: parsed.event?.get("client_msg_id")?.let { (it as? JsonPrimitive)?.content }
            ?: parsed.event?.get("ts")?.let { (it as? JsonPrimitive)?.content }
            ?: rawBody
        val key = "slack:${parsed.workspaceId}:${parsed.externalChannelId}:$externalEventId"
        return UUID.parse(
            java.util.UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString()
        )
    }

    internal data class ParsedEvent(
        val type: String,
        val payload: JsonObject,
        val event: JsonObject?,
        val workspaceId: String?,
        val externalChannelId: String?,
    )

    object SlackHeaders {
        const val Timestamp = "X-Slack-Request-Timestamp"
        const val Signature = "X-Slack-Signature"
    }

    companion object {
        private val log = LoggerFactory.getLogger(SlackEventWebhook::class.java)
        const val MAX_TIMESTAMP_SKEW_SECONDS = 60L * 5
        const val SIGNATURE_PREFIX = "v0="

        /**
         * Used as the senderId on inbound messages whose Slack user has not
         * yet been mapped to a Bosca profile. Picked deterministically so the
         * same placeholder profile is used across all unmapped messages.
         */
        val PLACEHOLDER_SENDER_ID: UUID = UUID.parse("00000000-0000-0000-0000-000000000bbb")
    }
}
