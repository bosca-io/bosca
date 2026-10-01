package bosca.security.service

import java.net.URI

/**
 * Resolves the public web origin a transactional auth email should link back to, for Bosca's
 * multi-host deployments (one backend serving several Studio hosts).
 *
 * The chosen origin is always validated against an allow-list before use: an unvalidated host taken
 * from a request header or client field would let an attacker point an email's link at their own
 * site (a phishing open-redirect). Anything not on the list falls back to the configured default.
 */
object AppOrigins {

    /**
     * Picks the origin to link back to: an [explicit] client-supplied value if present, otherwise the
     * request's own [requestOrigin] ("where they came from"). The pick must exactly match
     * (scheme + host + port) an entry in [allowed]; if it doesn't — or nothing was supplied — [default]
     * (the `app.url` fallback) is returned. The returned value is normalized (no trailing slash/path).
     */
    fun resolve(explicit: String?, requestOrigin: String?, allowed: List<String>, default: String): String {
        val candidate = explicit?.takeIf { it.isNotBlank() } ?: requestOrigin
        return candidate?.let { normalizeIfAllowed(it, allowed) } ?: default
    }

    /** Returns [origin] normalized to `scheme://host[:port]` iff it matches an allow-listed origin, else null. */
    private fun normalizeIfAllowed(origin: String, allowed: List<String>): String? {
        val normalized = normalize(origin) ?: return null
        val isAllowed = allowed.any { normalize(it) == normalized }
        return if (isAllowed) normalized else null
    }

    /** Reduces a URL to its origin (`scheme://host[:port]`), dropping any path/query, or null if unparseable. */
    private fun normalize(value: String): String? = try {
        val uri = URI(value)
        uri.host?.let { host ->
            val scheme = uri.scheme ?: "https"
            val port = if (uri.port != -1) ":${uri.port}" else ""
            "$scheme://$host$port"
        }
    } catch (_: Exception) {
        null
    }
}
