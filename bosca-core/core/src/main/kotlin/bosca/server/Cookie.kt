package bosca.server

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Represents an HTTP cookie with its associated attributes for both reading from requests
 * and writing to responses.
 *
 * Supports standard cookie attributes including domain, path, max-age, secure, and httpOnly flags
 * as well as optional extensions for custom cookie properties.
 */
data class Cookie(
    val name: String,
    val value: String,
    val maxAge: Int? = null,
    val expires: Long? = null,
    val domain: String? = null,
    val path: String? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val sameSite: String? = null,
    val extensions: Map<String, String?> = emptyMap()
) {
    /** Serializes this cookie to a Set-Cookie header value for inclusion in HTTP responses. */
    fun toSetCookieString(): String = buildString {
        append(encodeCookieName(name))
        append("=")
        append(encodeCookieValue(value))
        domain?.let { append("; Domain=${sanitizeDomain(it)}") }
        path?.let { append("; Path=${sanitizeAttributeValue(it)}") }
        maxAge?.let { append("; Max-Age=$it") }
        expires?.let { append("; Expires=${formatExpires(it)}") }
        if (secure) append("; Secure")
        if (httpOnly) append("; HttpOnly")
        sameSite?.let { append("; SameSite=${sanitizeAttributeValue(it)}") }
        extensions.forEach { (k, v) ->
            val sanitizedKey = sanitizeAttributeValue(k)
            if (v != null) append("; $sanitizedKey=${sanitizeAttributeValue(v)}") else append("; $sanitizedKey")
        }
    }

    companion object {
        private val EXPIRES_FORMAT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC)

        private fun formatExpires(epochMillis: Long): String =
            EXPIRES_FORMAT.format(Instant.ofEpochMilli(epochMillis))

        /** Validates a cookie domain per RFC 6265 — strips invalid characters and leading dots. */
        private fun sanitizeDomain(domain: String): String {
            val filtered = domain.filter { it.isLetterOrDigit() || it == '-' || it == '.' }
            // Strip leading dots — the browser applies domain-matching rules automatically
            val trimmed = filtered.trimStart('.')
            // Enforce DNS label length limits (max 63 chars per label, max 253 total)
            require(trimmed.length <= 253) { "Cookie domain exceeds maximum length: $trimmed" }
            return trimmed
        }

        /** Strips control characters, semicolons, and newlines from an attribute value to prevent header injection. */
        private fun sanitizeAttributeValue(value: String): String =
            value.filter { it.code >= 0x20 && it != ';' && it != '\n' && it != '\r' }

        /** Validates a cookie name per RFC 6265 — rejects names containing control chars or delimiters. */
        private fun encodeCookieName(name: String): String {
            require(name.isNotEmpty()) { "Cookie name must not be empty" }
            val invalidChar = name.firstOrNull { it.code !in 33..126 || it in "()<>@,;:\\\"/[]?={} \t" }
            require(invalidChar == null) { "Cookie name '$name' contains invalid character: '$invalidChar'" }
            return name
        }

        /** Encodes a cookie value per RFC 6265 — wraps in quotes if it contains special chars. */
        private fun encodeCookieValue(value: String): String {
            if (value.isEmpty()) return value
            val needsQuoting = value.any { c ->
                c.code < 0x21 || c.code > 0x7E || c in ",;\"\\"
            }
            return if (needsQuoting) {
                "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            } else {
                value
            }
        }
    }
}

/**
 * Manages cookies associated with an HTTP response, providing methods to append
 * new cookies that will be sent back to the client as Set-Cookie headers.
 */
class ResponseCookies {
    private val _cookies = java.util.concurrent.CopyOnWriteArrayList<Cookie>()
    val cookies: List<Cookie> get() = _cookies

    /** Appends a cookie to be sent in the response's Set-Cookie headers. */
    fun append(cookie: Cookie) {
        _cookies.add(cookie)
    }

    /** Creates and appends a cookie with the given name and value plus optional attributes. */
    fun append(
        name: String,
        value: String,
        maxAge: Int? = null,
        expires: Long? = null,
        domain: String? = null,
        path: String? = null,
        secure: Boolean = false,
        httpOnly: Boolean = false,
        sameSite: String? = null,
        extensions: Map<String, String?> = emptyMap()
    ) {
        append(Cookie(name, value, maxAge, expires, domain, path, secure, httpOnly, sameSite, extensions))
    }
}

/**
 * Provides read access to cookies from an HTTP request by parsing the Cookie header.
 */
class RequestCookies(private val cookieHeaders: List<String>) {

    constructor(singleHeader: String?) : this(if (singleHeader != null) listOf(singleHeader) else emptyList())

    private val parsed: Map<String, String> by lazy {
        if (cookieHeaders.isEmpty()) return@lazy emptyMap()
        cookieHeaders.flatMap { header ->
            header.split(";").map { part ->
                part.trim().split("=", limit = 2).let {
                    if (it.size == 2) it[0].trim() to it[1].trim().removeSurrounding("\"") else it[0].trim() to ""
                }
            }
        }.toMap()
    }

    /** Returns the cookie value for the given [name], or null if not present. */
    operator fun get(name: String): String? = parsed[name]

    /** Returns all parsed cookies as a map. */
    val all: Map<String, String> get() = parsed
}
