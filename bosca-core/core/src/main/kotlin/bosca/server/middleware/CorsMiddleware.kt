package bosca.server.middleware

import bosca.server.HttpHeaders
import bosca.server.HttpMethod
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.Serializable

/**
 * CORS middleware that handles cross-origin request validation and preflight responses.
 *
 * Supports configurable allowed origins, methods, headers, and credentials handling,
 * including wildcard origins and per-origin predicate matching.
 */
class CorsMiddleware(private val config: CorsConfig) : CallMiddleware {

    override suspend fun beforeCall(call: ServerCall) {
        if (call.response.isCommitted) return

        // Always add Vary: Origin to prevent CDN/shared cache poisoning —
        // responses may differ based on the Origin header's presence and value.
        call.response.header(HttpHeaders.Vary, HttpHeaders.Origin)

        val origin = call.request.headers[HttpHeaders.Origin] ?: return

        // Return 403 for disallowed origins to provide a consistent rejection response
        // regardless of the rejection reason (origin, method, or header mismatch)
        if (!isOriginAllowed(origin)) {
            call.respond(HttpStatusCode.Forbidden, "")
            return
        }

        // Handle preflight — per CORS spec, a preflight requires both OPTIONS method
        // and the Access-Control-Request-Method header
        if (call.request.httpMethod == HttpMethod.Options &&
            call.request.headers[HttpHeaders.AccessControlRequestMethod] != null) {
            handlePreflight(call, origin)
            return
        }

        // Set CORS response headers
        setOriginHeader(call, origin)
        if (config.allowCredentials) {
            call.response.header(HttpHeaders.AccessControlAllowCredentials, "true")
        }
        if (config.exposedHeaders.isNotEmpty()) {
            call.response.header(HttpHeaders.AccessControlExposeHeaders, config.exposedHeaders.joinToString(", "))
        }
    }

    private fun isOriginAllowed(origin: String): Boolean {
        if (config.allowAnyHost) return true
        if (origin in config.allowedHosts) return true
        return config.originPredicates.any { it(origin) }
    }

    private fun setOriginHeader(call: ServerCall, origin: String) {
        if (config.allowAnyHost && !config.allowCredentials) {
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        } else {
            call.response.header(HttpHeaders.AccessControlAllowOrigin, origin)
        }
    }

    private suspend fun handlePreflight(call: ServerCall, origin: String) {
        val effectiveMethods = config.allowedMethods.ifEmpty { DEFAULT_METHODS }
        val effectiveHeaders = config.allowedHeaders.ifEmpty { DEFAULT_HEADERS }

        // Validate that the requested method is in the allowed set
        val requestedMethod = call.request.headers[HttpHeaders.AccessControlRequestMethod]
        if (requestedMethod != null) {
            val allowed = effectiveMethods.any { it.value.equals(requestedMethod, ignoreCase = true) }
            if (!allowed) {
                call.respond(HttpStatusCode.Forbidden, "")
                return
            }
        }

        // Validate that the requested headers are all in the allowed set
        val requestedHeaders = call.request.headers[HttpHeaders.AccessControlRequestHeaders]
        if (requestedHeaders != null) {
            val requested = requestedHeaders.split(",").map { it.trim().lowercase() }
            val allowed = effectiveHeaders.map { it.lowercase() }.toSet()
            if (!allowed.containsAll(requested)) {
                call.respond(HttpStatusCode.Forbidden, "")
                return
            }
        }

        setOriginHeader(call, origin)
        if (config.allowCredentials) {
            call.response.header(HttpHeaders.AccessControlAllowCredentials, "true")
        }
        call.response.header(HttpHeaders.AccessControlAllowMethods, effectiveMethods.joinToString(", ") { it.value })
        call.response.header(HttpHeaders.AccessControlAllowHeaders, effectiveHeaders.joinToString(", "))
        if (config.maxAgeSeconds > 0) {
            call.response.header(HttpHeaders.AccessControlMaxAge, config.maxAgeSeconds.toString())
            call.response.header(HttpHeaders.CacheControl, "public, max-age=${config.maxAgeSeconds}")
        }
        call.respond(HttpStatusCode.NoContent, "")
    }

    companion object {
        /** Default allowed methods when none are explicitly configured. */
        private val DEFAULT_METHODS = setOf(
            HttpMethod.Get, HttpMethod.Head, HttpMethod.Post, HttpMethod.Options
        )

        /** Default allowed headers when none are explicitly configured (CORS-safelisted headers). */
        private val DEFAULT_HEADERS = setOf(
            "Accept", "Accept-Language", "Content-Language", "Content-Type"
        )
    }
}

/**
 * YAML-serializable CORS configuration that maps to the `cors` section of application.yaml.
 *
 * Environment variable substitution is handled by [ApplicationConfig] before deserialization,
 * so quoted env patterns like `"$CORS_ALLOW_ANY_HOST:true"` resolve to native types via lenient JSON parsing.
 * Use [toCorsConfig] to convert this into the runtime [CorsConfig] used by [CorsMiddleware].
 */
@Serializable
data class CorsConfiguration(
    val allowAnyHost: Boolean = false,
    val allowCredentials: Boolean = false,
    val anyMethod: Boolean = false,
    val maxAgeSeconds: Long = 86400,
    val allowedHosts: List<String> = emptyList(),
    val allowedMethods: List<String> = emptyList(),
    val allowedHeaders: List<String> = emptyList(),
    val exposedHeaders: List<String> = emptyList(),
) {
    /**
     * Converts this YAML-driven configuration into a runtime [CorsConfig], applying
     * the [developmentMode] flag from the application environment.
     */
    fun toCorsConfig(developmentMode: Boolean): CorsConfig {
        val yaml = this
        return CorsConfig().apply {
            this.developmentMode = developmentMode
            this.allowAnyHost = yaml.allowAnyHost
            this.allowCredentials = yaml.allowCredentials
            this.maxAgeSeconds = yaml.maxAgeSeconds
            if (yaml.anyMethod) anyMethod()
            allowedMethods.addAll(yaml.allowedMethods.map { HttpMethod.parse(it) })
            allowedHosts.addAll(yaml.allowedHosts)
            allowedHeaders.addAll(yaml.allowedHeaders)
            exposedHeaders.addAll(yaml.exposedHeaders)
        }
    }
}

/**
 * Configuration for the CORS middleware, specifying allowed origins, methods, headers,
 * and credential handling policy.
 */
class CorsConfig {
    var allowAnyHost = false
    var allowCredentials = false
    var developmentMode = false
    var maxAgeSeconds = 86400L
    val allowedHosts = mutableSetOf<String>()
    val allowedMethods = mutableSetOf<HttpMethod>()
    val allowedHeaders = mutableSetOf<String>()
    val exposedHeaders = mutableSetOf<String>()
    val originPredicates = mutableListOf<(String) -> Boolean>()

    /** Allows all HTTP methods. */
    fun anyMethod() {
        allowedMethods.addAll(listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Patch, HttpMethod.Head, HttpMethod.Options))
    }

    /** Allows all hosts as origins. */
    fun anyHost() {
        allowAnyHost = true
    }

    /** Adds the given header name to the allowed headers list. */
    fun allowHeader(header: String) {
        allowedHeaders.add(header)
    }
}
