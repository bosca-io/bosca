package bosca.collaboration.bridge.slack

import bosca.chat.model.ChatMessage
import bosca.collaboration.bridge.BridgeBinding
import bosca.collaboration.bridge.BridgePlatform
import bosca.collaboration.bridge.BridgePlatformAdapter
import bosca.communications.model.MessageContentType
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Slack Web API adapter for bidirectional message bridging. Uses the
 * `chat.postMessage`, `chat.update`, `chat.delete`, and
 * `reactions.add`/`reactions.remove` endpoints. Bot tokens are decrypted
 * from the binding at call time via [tokenProvider] (sourced by the
 * service from the encrypted configuration store).
 *
 * Slack returns HTTP 200 even on application-level failures; the actual
 * outcome is in the JSON `ok` field. This adapter checks `ok` on every
 * call and throws [SlackApiException] when it is `false` so the calling
 * job executor can log/retry instead of silently dropping the
 * `external_message_id` mapping.
 */
class SlackAdapter(
    private val httpClient: HttpClient,
    private val json: Json,
    private val tokenProvider: suspend (BridgeBinding) -> String,
) : BridgePlatformAdapter {

    override val platform = BridgePlatform.SLACK

    override suspend fun sendMessage(binding: BridgeBinding, message: ChatMessage, senderName: String): String {
        val payload = buildJsonObject {
            put("channel", JsonPrimitive(binding.externalChannelId))
            put("text", JsonPrimitive(formatMessageText(message)))
            put("username", JsonPrimitive(senderName))
        }
        val response = postJson(binding, "https://slack.com/api/chat.postMessage", payload)
        val body = decodeOk(response, "chat.postMessage")
        return body["ts"]?.jsonPrimitive?.content
            ?: throw SlackApiException("chat.postMessage", "missing_ts", "response had ok=true but no ts: $body")
    }

    override suspend fun editMessage(binding: BridgeBinding, externalMessageId: String, message: ChatMessage, senderName: String) {
        val payload = buildJsonObject {
            put("channel", JsonPrimitive(binding.externalChannelId))
            put("ts", JsonPrimitive(externalMessageId))
            put("text", JsonPrimitive(formatMessageText(message)))
        }
        val response = postJson(binding, "https://slack.com/api/chat.update", payload)
        decodeOk(response, "chat.update")
    }

    override suspend fun deleteMessage(binding: BridgeBinding, externalMessageId: String) {
        val payload = buildJsonObject {
            put("channel", JsonPrimitive(binding.externalChannelId))
            put("ts", JsonPrimitive(externalMessageId))
        }
        val response = postJson(binding, "https://slack.com/api/chat.delete", payload)
        decodeOk(response, "chat.delete")
    }

    override suspend fun addReaction(binding: BridgeBinding, externalMessageId: String, emoji: String) {
        val payload = buildJsonObject {
            put("channel", JsonPrimitive(binding.externalChannelId))
            put("timestamp", JsonPrimitive(externalMessageId))
            put("name", JsonPrimitive(toSlackEmojiName(emoji)))
        }
        val response = postJson(binding, "https://slack.com/api/reactions.add", payload)
        decodeOk(response, "reactions.add")
    }

    override suspend fun removeReaction(binding: BridgeBinding, externalMessageId: String, emoji: String) {
        val payload = buildJsonObject {
            put("channel", JsonPrimitive(binding.externalChannelId))
            put("timestamp", JsonPrimitive(externalMessageId))
            put("name", JsonPrimitive(toSlackEmojiName(emoji)))
        }
        val response = postJson(binding, "https://slack.com/api/reactions.remove", payload)
        decodeOk(response, "reactions.remove")
    }

    override fun verifyWebhookSignature(headers: Map<String, String>, body: ByteArray, signingSecret: String): Boolean {
        val timestamp = headers["x-slack-request-timestamp"] ?: return false
        val signature = headers["x-slack-signature"] ?: return false
        if (!signature.startsWith(SIGNATURE_PREFIX)) return false

        val baseString = "v0:$timestamp:${body.toString(Charsets.UTF_8)}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(baseString.toByteArray(Charsets.UTF_8))
        val computed = SIGNATURE_PREFIX + hash.joinToString("") { "%02x".format(it) }
        return constantTimeEquals(computed, signature)
    }

    /**
     * Posts a JSON body to a Slack Web API endpoint, attaching the
     * binding's bot token as a Bearer credential. The body is built from
     * a [JsonObject] (not a pre-serialized String) so Slack receives a
     * JSON object rather than a quoted JSON string literal — that was
     * the bug the previous version of this adapter shipped with.
     */
    internal suspend fun postJson(binding: BridgeBinding, url: String, payload: JsonObject): HttpResponse {
        val token = tokenProvider(binding)
        return httpClient.post(url) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
    }

    /**
     * Verifies the Slack response. Slack always returns HTTP 200 even on
     * failure; the actual success is in the `ok` field. Throws
     * [SlackApiException] when `ok` is false (or absent).
     */
    internal suspend fun decodeOk(response: HttpResponse, operation: String): JsonObject {
        val text = runCatching { response.bodyAsText() }
            .getOrElse {
                log.warn("Slack {} returned no body", operation)
                return JsonObject(emptyMap())
            }
        val body = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw SlackApiException(operation, "invalid_response", "non-JSON body: $text")
        val ok = body["ok"]?.jsonPrimitive?.booleanOrNullSafe() ?: false
        if (!ok) {
            val error = body["error"]?.jsonPrimitive?.contentOrNullSafe() ?: "unknown_error"
            throw SlackApiException(operation, error, "Slack returned ok=false (response: $text)")
        }
        return body
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()
    private fun JsonPrimitive.booleanOrNullSafe(): Boolean? = runCatching { content == "true" }.getOrNull()

    /**
     * Returns a Slack-acceptable text rendition of the message. Picks
     * TEXT blocks; HTML blocks are decoded into a coarse mrkdwn-ish
     * approximation (strips angle brackets and known entities) so they
     * don't render as literal HTML in Slack.
     */
    internal fun formatMessageText(message: ChatMessage): String {
        val parts = mutableListOf<String>()
        for (block in message.content) {
            when (block.type) {
                MessageContentType.TEXT -> parts.add(block.content)
                MessageContentType.HTML -> parts.add(htmlToPlain(block.content))
                else -> {}
            }
        }
        return parts.joinToString("\n").ifEmpty { "(empty message)" }
    }

    /**
     * Strips HTML to a plain-text rendition for Slack delivery. This
     * is a deliberately small surface — enough to keep simple `<p>`,
     * `<br>`, `<strong>`, `<em>` content readable — not a full HTML
     * sanitizer. Tag attributes are dropped; common entities are
     * decoded.
     */
    internal fun htmlToPlain(html: String): String {
        val noTags = HTML_TAG.replace(html) { match ->
            val tag = match.groupValues[1].lowercase()
            when (tag) {
                "br", "br/" -> "\n"
                "/p", "/div" -> "\n"
                else -> ""
            }
        }
        return noTags
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }

    /**
     * Maps a unicode emoji to Slack's `name` form (without the colons).
     * For shortcodes already in Slack form (`thumbsup`, `:thumbsup:`)
     * just returns the bare name. Unknown unicode emoji fall through as
     * the original string — Slack will reject with `invalid_name`,
     * which the [decodeOk] check surfaces as a thrown exception so the
     * executor can log it.
     */
    internal fun toSlackEmojiName(emoji: String): String {
        if (emoji.isBlank()) return emoji
        // Already a shortcode? (`:thumbsup:` or `thumbsup`)
        val trimmed = emoji.trim().trim(':')
        if (trimmed.matches(SHORTCODE_PATTERN)) return trimmed
        return EMOJI_TO_NAME[emoji.trim()] ?: emoji
    }

    companion object {
        private val log = LoggerFactory.getLogger(SlackAdapter::class.java)
        private val HTML_TAG = Regex("<(/?[a-zA-Z][a-zA-Z0-9]*)(?:\\s[^>]*)?/?>")
        private val SHORTCODE_PATTERN = Regex("[a-z0-9_+\\-]+")
        const val SIGNATURE_PREFIX = "v0="

        /**
         * A small lookup table mapping the most-common unicode emoji to
         * their Slack canonical names. Not exhaustive — administrators
         * can write `:custom_emoji:` directly or extend this map as
         * usage demands.
         */
        internal val EMOJI_TO_NAME = mapOf(
            "👍" to "thumbsup",
            "👎" to "thumbsdown",
            "❤️" to "heart",
            "❤" to "heart",
            "😂" to "joy",
            "🎉" to "tada",
            "🚀" to "rocket",
            "👀" to "eyes",
            "🙏" to "pray",
            "✅" to "white_check_mark",
            "🔥" to "fire",
            "🤔" to "thinking_face",
            "😢" to "cry",
            "👏" to "clap",
        )

        internal fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}

/**
 * Thrown by [SlackAdapter] when Slack returns `ok=false` on a
 * Web API call, or when the response body cannot be parsed as JSON.
 * Carries the Slack `error` code so callers can distinguish recoverable
 * problems (e.g. `rate_limited`) from terminal ones (`invalid_auth`,
 * `channel_not_found`).
 */
class SlackApiException(
    val operation: String,
    val errorCode: String,
    message: String,
) : RuntimeException("[$operation] Slack error '$errorCode': $message")
