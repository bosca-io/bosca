package bosca.cli.harness.egress

import java.util.Base64
import kotlin.uuid.Uuid

private const val MAX_TOKEN_LENGTH = 512

/**
 * Parses `Proxy-Authorization: Basic base64(<session>:<egress token>)`. The session must be a
 * canonical UUID and the token printable ASCII; anything else is treated as missing credentials.
 */
internal fun parseProxyAuthorization(values: List<String>): EgressCredentials? {
    val value = values.singleOrNull()?.trim() ?: return null
    val separator = value.indexOf(' ')
    if (separator <= 0 || !value.substring(0, separator).equals("Basic", ignoreCase = true)) return null
    val decoded = try {
        Base64.getDecoder().decode(value.substring(separator + 1).trim()).toString(Charsets.UTF_8)
    } catch (_: IllegalArgumentException) { return null }
    val colon = decoded.indexOf(':')
    if (colon <= 0) return null
    val session = decoded.substring(0, colon)
    val token = decoded.substring(colon + 1)
    val canonical = try { Uuid.parse(session).toString() == session } catch (_: IllegalArgumentException) { false }
    if (!canonical) return null
    if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH || token.any { it.code !in 0x21..0x7e }) return null
    return EgressCredentials(session, token)
}
