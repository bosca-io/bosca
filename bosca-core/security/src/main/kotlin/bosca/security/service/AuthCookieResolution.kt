package bosca.security.service

import bosca.server.ServerCall
import java.net.URI

/**
 * Resolves the configured custom cookie prefix for this request. A configured addressed host wins;
 * an unconfigured internal proxy host may use the public application origin.
 */
fun SecurityConfiguration.resolveAuthCookiePrefix(call: ServerCall): String? {
    val addressedHost = call.request.origin.host.lowercase()
    return authCookiePrefixes.firstOrNull { addressedHost in it.domains }?.prefix
        ?: authCookieDomain(call.request.appOrigin)?.let { publicHost ->
            authCookiePrefixes.firstOrNull { publicHost in it.domains }?.prefix
        }
}

/** Extracts the normalized host used for exact cookie-prefix domain matching. */
internal fun authCookieDomain(origin: String): String? = try {
    URI(origin).host?.lowercase()
} catch (_: Exception) {
    null
}
