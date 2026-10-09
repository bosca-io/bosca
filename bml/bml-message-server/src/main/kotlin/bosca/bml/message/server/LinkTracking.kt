package bosca.bml.message.server

import com.fleeksoft.ksoup.parser.Parser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * First-party engagement tracking: rewrites a rendered email's content links
 * to `<public base>/c/<token>` and injects an open pixel, using STATELESS signed tokens —
 * `base64url(payload).base64url(hmacSha256(payload))` — so the public redirect route verifies
 * without any storage and tampering fails closed.
 *
 * Deliverability rules: the unsubscribe/preferences URLs are NEVER rewritten (unsubscribing must
 * not depend on the tracker), nor are `mailto:`/`tel:`/fragment links. The plain-text alternative
 * keeps its original links.
 */
class LinkTracking(
    secret: String,
    publicBaseUrl: String,
) {
    private val base = publicBaseUrl.trimEnd('/')
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
    private val json = Json { ignoreUnknownKeys = true }

    /** The signed token payload — compact field names keep rewritten URLs short. */
    @Serializable
    data class Payload(
        /** The original destination URL ("" for the open pixel). */
        val u: String,
        /** The message id. */
        val m: String,
        /** The recipient profile id, when the send was single-recipient. */
        val r: String? = null,
    )

    fun clickUrl(url: String, messageId: String, recipientId: String?): String =
        "$base/c/${token(Payload(u = url, m = messageId, r = recipientId))}"

    fun openPixelUrl(messageId: String, recipientId: String?): String =
        "$base/o/${token(Payload(u = "", m = messageId, r = recipientId))}"

    /** Verify and decode [token]; null on any tampering/garbage — callers fail closed (404). */
    fun verify(token: String): Payload? {
        val dot = token.indexOf('.')
        if (dot <= 0 || dot == token.length - 1) return null
        return runCatching {
            val payload = Base64.getUrlDecoder().decode(token.substring(0, dot))
            val signature = Base64.getUrlDecoder().decode(token.substring(dot + 1))
            if (!java.security.MessageDigest.isEqual(signature, sign(payload))) return null
            json.decodeFromString(Payload.serializer(), payload.decodeToString())
        }.getOrNull()
    }

    /**
     * Rewrite every `href="http(s)://…"` in [html] to a tracked redirect and inject the open
     * pixel before `</body>`. [excludeUrls] (unsubscribe/preferences) pass through untouched.
     * HTML attribute entities are decoded once before comparing or signing destinations.
     */
    fun rewrite(html: String, messageId: String, recipientId: String?, excludeUrls: Set<String>): String {
        val rewritten = HREF.replace(html) { match ->
            val url = Parser.unescapeEntities(match.groupValues[1], inAttribute = true)
            if (url in excludeUrls) match.value else "href=\"${clickUrl(url, messageId, recipientId)}\""
        }
        val pixel = "<img src=\"${openPixelUrl(messageId, recipientId)}\" width=\"1\" height=\"1\" alt=\"\" style=\"display:none;\"/>"
        val bodyEnd = rewritten.lastIndexOf("</body>", ignoreCase = true)
        return if (bodyEnd >= 0) {
            rewritten.substring(0, bodyEnd) + pixel + rewritten.substring(bodyEnd)
        } else {
            rewritten + pixel
        }
    }

    private fun token(payload: Payload): String {
        val bytes = json.encodeToString(Payload.serializer(), payload).toByteArray(Charsets.UTF_8)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "${encoder.encodeToString(bytes)}.${encoder.encodeToString(sign(bytes))}"
    }

    private fun sign(payload: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(payload)

    private companion object {
        // Only absolute http(s) links are content links; mailto:/tel:/#fragment pass through.
        val HREF = Regex("""href="(https?://[^"]+)"""")
    }
}
