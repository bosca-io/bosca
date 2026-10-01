package bosca.bml.server

import bosca.bml.render.BML_ANALYTICS_SESSION_COOKIE
import bosca.bml.render.BML_ANALYTICS_SESSION_EXPIRY_COOKIE
import bosca.server.HttpHeaders
import bosca.server.ServerCall
import bosca.server.middleware.CallMiddleware
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom

/** Writes a browser analytics session only when a route explicitly bootstraps one. */
class AnalyticsSessionIdGenerator : CallMiddleware {
    override fun onBeforeWrite(call: ServerCall) {
        if (call.attributes[SUPPRESS_ANALYTICS_COOKIE] == true) return
        val session = call.attributes[ANALYTICS_SESSION_ATTRIBUTE] as? AnalyticsSession ?: return
        val expiresAt = session.initialExpiresAt ?: return
        call.response.cookies.append(
            name = BML_ANALYTICS_SESSION_COOKIE,
            value = URLEncoder.encode(session.id, UTF_8),
            path = "/",
            secure = call.request.origin.scheme.equals("https", ignoreCase = true),
            httpOnly = false,
            sameSite = "Lax",
        )
        call.response.cookies.append(
            name = BML_ANALYTICS_SESSION_EXPIRY_COOKIE,
            value = expiresAt.toString(),
            path = "/",
            secure = call.request.origin.scheme.equals("https", ignoreCase = true),
            httpOnly = false,
            sameSite = "Lax",
        )
    }
}

/** Prevents a shared-cache response from carrying a per-browser analytics identity. */
internal fun ServerCall.suppressAnalyticsSessionCookie() {
    attributes[SUPPRESS_ANALYTICS_COOKIE] = true
}

/** Allows a response that changed from shared to private to persist its analytics identity. */
internal fun ServerCall.allowAnalyticsSessionCookie() {
    attributes.remove(SUPPRESS_ANALYTICS_COOKIE)
}

/** The request's resolved analytics session; an explicit header takes precedence over the cookie. */
internal val ServerCall.analyticsSessionId: String
    get() = analyticsSession.id

/** Resolves an identity supplied by the browser without creating one for this request. */
internal val ServerCall.existingAnalyticsSessionId: String?
    get() = (attributes[ANALYTICS_SESSION_ATTRIBUTE] as? AnalyticsSession)?.id
        ?: suppliedAnalyticsSessionId()?.also {
            attributes[ANALYTICS_SESSION_ATTRIBUTE] = AnalyticsSession(it)
        }

private data class AnalyticsSession(val id: String, val initialExpiresAt: Long? = null)

private const val SESSION_TIMEOUT_MILLIS = 300_000L
private const val SUPPRESS_ANALYTICS_COOKIE = "bosca.bml.analytics.suppress-cookie"
private const val ANALYTICS_SESSION_ATTRIBUTE = "bosca.bml.analytics.session"

private val ServerCall.analyticsSession: AnalyticsSession
    get() = attributes.getOrPut(ANALYTICS_SESSION_ATTRIBUTE) {
        val id = suppliedAnalyticsSessionId()
        if (id != null) AnalyticsSession(id)
        else AnalyticsSession(AnalyticsSessionUlid.generate(), System.currentTimeMillis() + SESSION_TIMEOUT_MILLIS)
    } as AnalyticsSession

private fun ServerCall.suppliedAnalyticsSessionId(): String? =
    request.headers[HttpHeaders.XSessionID]?.takeIf(String::isNotBlank)
        ?: request.cookies[BML_ANALYTICS_SESSION_COOKIE]?.let { cookie ->
            try {
                URLDecoder.decode(cookie, UTF_8).takeIf(String::isNotBlank)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

/** ULID: a 48-bit millisecond timestamp followed by 80 random bits, encoded as Crockford base32. */
internal object AnalyticsSessionUlid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun generate(): String {
        val result = CharArray(26)
        var timestamp = System.currentTimeMillis()
        for (index in 9 downTo 0) {
            result[index] = ALPHABET[(timestamp and 31).toInt()]
            timestamp = timestamp ushr 5
        }
        val entropy = ByteArray(10).also(random::nextBytes)
        var buffer = 0
        var bits = 0
        var index = 10
        for (byte in entropy) {
            buffer = (buffer shl 8) or (byte.toInt() and 255)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                result[index++] = ALPHABET[(buffer ushr bits) and 31]
            }
        }
        return result.concatToString()
    }
}
