package bosca.security.routes

import bosca.cache.RequestCache
import bosca.cache.asCoroutineContext
import bosca.core.annotations.Internal
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.di.provide
import bosca.observability.ErrorCapture
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.SimplePasswordAttributes
import bosca.security.service.ApiTokenService
import bosca.security.service.AuthCookiePrefix
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.security.service.resolveAuthCookiePrefix
import bosca.security.session.Session
import bosca.server.Cookie
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.middleware.AuthMiddleware
import bosca.server.middleware.SessionWriter
import bosca.server.routing.AuthConfig
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.Payload
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Authentication middleware that validates incoming requests using JWT tokens,
 * HTTP Basic credentials, or session cookies, populating the call's authentication
 * context with the authenticated principal.
 *
 * Also implements [SessionWriter] to persist session state as response cookies,
 * handling both the regular and admin domain cookie variants.
 */
class BoscaAuthMiddleware(
    private val securityConfiguration: SecurityConfiguration,
    private val connectionPool: ConnectionPool,
    private val securityService: SecurityService,
    private val apiTokenService: ApiTokenService,
    private val tracer: Tracer,
    private val cookieMaxAge: Long,
    private val errorCapture: ErrorCapture,
) : AuthMiddleware, SessionWriter {

    private val defaultCookieName = AuthCookiePrefix.DEFAULT

    @OptIn(Internal::class)
    override suspend fun authenticate(call: ServerCall, authConfig: AuthConfig?) {
        val authHeader = call.request.header("Authorization")
        if (authHeader != null) {
            if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
                val token = authHeader.substring("Bearer ".length)
                if (!authenticateBearerToken(call, token)) {
                    // Bearer token was explicitly provided — reject with 401 rather
                    // than silently degrading to anonymous on optional-auth routes
                    call.respond(HttpStatusCode.Unauthorized, "")
                }
                return
            }

            if (authHeader.startsWith("Basic ", ignoreCase = true)) {
                val encoded = authHeader.substring("Basic ".length)
                try {
                    val decoded = String(Base64.getDecoder().decode(encoded))
                    val parts = decoded.split(":", limit = 2)
                    if (parts.size < 2) {
                        log.debug("Malformed Basic auth header: missing colon separator")
                        call.respond(HttpStatusCode.Unauthorized, "")
                        return
                    }
                    val (username, password) = parts
                    if (username == "api_token" && password.startsWith("bsk_")) {
                        val principal = validateApiToken(password, call.request.clientIp)
                        call.authenticationContext.principal("api_token", principal)
                    } else {
                        val principal = validateCredential(SimplePasswordAttributes(username, password))
                        call.authenticationContext.principal("basic", principal)
                    }
                    return
                } catch (e: IllegalArgumentException) {
                    log.debug("Basic Verification failed", e)
                    call.respond(HttpStatusCode.Unauthorized, "")
                    return
                } catch (e: bosca.security.service.SecurityException) {
                    log.debug("Basic auth failed", e)
                    call.respond(HttpStatusCode.Unauthorized, "")
                    return
                } catch (e: SecurityException) {
                    log.debug("Basic Verification failed", e)
                    call.respond(HttpStatusCode.Unauthorized, "")
                    return
                } catch (e: Exception) {
                    log.warn("Unexpected error during Basic auth validation", e)
                    errorCapture.capture(e, call, mapOf("auth.provider" to "basic"))
                    call.respond(HttpStatusCode.Unauthorized, "")
                    return
                }
            }
        }

        val customCookieName = securityConfiguration.resolveAuthCookiePrefix(call)
        val cookieValue = call.request.cookies[customCookieName ?: defaultCookieName]
            ?.takeIf(String::isNotBlank)
        if (cookieValue != null) {
            try {
                val tokenValue = cookieValue.removeSurrounding("\"")
                val session = Session(tokenValue)
                // The "admin:::" prefix is a routing marker set during OAuth2 login
                // to distinguish admin-domain sessions from regular sessions. It controls
                // which cookie domain is used when writing/clearing the session cookie
                // (see writeSessionCookie/clearSessionCookie). The prefix is stripped
                // before JWT verification — it does not grant any additional privileges;
                // authorization is determined solely by the JWT claims.
                val actualToken = if (session.token.startsWith("admin:::")) {
                    session.token.removePrefix("admin:::")
                } else {
                    session.token
                }
                val decodedJWT = securityConfiguration.verifier.verify(actualToken)
                val principal = validateJwt(decodedJWT)
                call.authenticationContext.principal("session", principal)
                call.sessions.onLoad(session)
                return
            } catch (e: JWTVerificationException) {
                log.debug("Session JWT Verification failed", e)
            } catch (e: Exception) {
                log.warn("Unexpected error during session cookie validation", e)
                errorCapture.capture(e, call, mapOf("auth.provider" to "session"))
            }
        }

        if (authConfig != null && !authConfig.optional && call.authenticationContext.anyPrincipal() == null) {
            call.respond(HttpStatusCode.Unauthorized, "")
        }
    }

    /**
     * Validates a bearer token (`bsk_` API token or JWT) and attaches the
     * resulting principal to [call]'s authentication context.
     *
     * Shared by the `Authorization` header path in [authenticate] and the
     * WebSocket `connection_init` path ([ConnectionInitAuthenticator]) —
     * the latter runs on an established socket where responding 401 is
     * meaningless, so failure is reported via the return value and the
     * caller decides how to reject.
     *
     * API tokens are prefix-routed: a failing `bsk_` token never falls
     * through to JWT parsing, so the caller's intent stays unambiguous.
     */
    @OptIn(Internal::class)
    suspend fun authenticateBearerToken(call: ServerCall, token: String): Boolean {
        if (token.startsWith("bsk_")) {
            return try {
                val principal = validateApiToken(token, call.request.clientIp)
                call.authenticationContext.principal("api_token", principal)
                true
            } catch (e: bosca.security.service.SecurityException) {
                log.debug("API token authentication failed", e)
                false
            } catch (e: Exception) {
                log.warn("Unexpected error during API token validation", e)
                errorCapture.capture(e, call, mapOf("auth.provider" to "api_token"))
                false
            }
        }

        return try {
            val decodedJWT = securityConfiguration.verifier.verify(token)
            val principal = validateJwt(decodedJWT)
            call.authenticationContext.principal("bearer", principal)
            true
        } catch (e: JWTVerificationException) {
            log.debug("JWT Verification failed", e)
            false
        } catch (e: Exception) {
            // Route to error capture explicitly — these would not
            // reach AnalyticsMiddleware because the callers
            // reject inline rather than letting them propagate.
            log.warn("Unexpected error during JWT validation", e)
            errorCapture.capture(e, call, mapOf("auth.provider" to "bearer"))
            false
        }
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(BoscaAuthMiddleware::class.java)
    }

    override fun writeSession(call: ServerCall, session: Any?) {
        if (session is Session) {
            writeSessionCookie(call, session)
        }
    }

    override fun clearSession(call: ServerCall) {
        clearSessionCookie(call)
    }

    /**
     * Writes a session cookie to the response for the given [call] and [session].
     * Handles the admin domain distinction based on the session token prefix.
     */
    fun writeSessionCookie(call: ServerCall, session: Session) {
        var cookieValue = session.token.removeSurrounding("\"")
        val customCookieName = securityConfiguration.resolveAuthCookiePrefix(call)
        val cookieName = customCookieName ?: defaultCookieName
        var domain = if (customCookieName == null) securityConfiguration.domain else null

        if (cookieValue.startsWith("admin:::")) {
            cookieValue = cookieValue.substringAfter("admin:::")
            if (customCookieName == null) domain = securityConfiguration.adminDomain
        }

        call.response.cookies.append(
            Cookie(
                name = cookieName,
                value = cookieValue,
                maxAge = cookieMaxAge.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                domain = domain,
                path = "/",
                secure = securityConfiguration.cookieSecure,
                httpOnly = securityConfiguration.cookieHttpOnly,
                sameSite = "Lax",
            )
        )
    }

    /**
     * Clears the session cookie for the given [call]. When the admin and regular domains
     * differ, clears cookies on both domains to prevent stale cookies from lingering on
     * either domain regardless of which session type was active.
     */
    fun clearSessionCookie(call: ServerCall) {
        val customCookieName = securityConfiguration.resolveAuthCookiePrefix(call)
        if (customCookieName != null) {
            clearCookieOnDomain(call, customCookieName, null)
        } else {
            clearCookieOnDomain(call, defaultCookieName, securityConfiguration.domain)
            if (securityConfiguration.adminDomain != securityConfiguration.domain) {
                clearCookieOnDomain(call, defaultCookieName, securityConfiguration.adminDomain)
            }
        }
    }

    private fun clearCookieOnDomain(call: ServerCall, cookieName: String, domain: String?) {
        call.response.cookies.append(
            Cookie(
                name = cookieName,
                value = "",
                maxAge = 0,
                domain = domain,
                path = "/",
                secure = securityConfiguration.cookieSecure,
                httpOnly = securityConfiguration.cookieHttpOnly,
                sameSite = "Lax",
            )
        )
    }

    private suspend fun validateApiToken(rawToken: String, remoteIp: String?): AuthenticatedPrincipal {
        val span = tracer.spanBuilder("security.validate.api_token").startSpan()
        return try {
            val cache = RequestCache(provide(), provide())
            val connection = connectionPool.connection()
            try {
                withContext(connection.asCoroutineContext() + span.asContextElement() + cache.asCoroutineContext()) {
                    apiTokenService.authenticate(rawToken, remoteIp)
                }
            } finally {
                connection.release()
            }
        } finally {
            span.end()
        }
    }

    private suspend fun validateJwt(payload: Payload): AuthenticatedPrincipal {
        val span = tracer.spanBuilder("security.validate.jwt").startSpan()
        return try {
            val cache = RequestCache(provide(), provide())
            val connection = connectionPool.connection()
            try {
                withContext(connection.asCoroutineContext() + span.asContextElement() + cache.asCoroutineContext()) {
                    securityService.authenticateWithPayload(payload)
                }
            } finally {
                connection.release()
            }
        } finally {
            span.end()
        }
    }

    private suspend fun validateCredential(credential: SimplePasswordAttributes): AuthenticatedPrincipal {
        val span = tracer.spanBuilder("security.validate.credential").startSpan()
        return try {
            val cache = RequestCache(provide(), provide())
            val connection = connectionPool.connection()
            try {
                withContext(connection.asCoroutineContext() + span.asContextElement() + cache.asCoroutineContext()) {
                    securityService.authenticateWithCredential(credential)
                }
            } finally {
                connection.release()
            }
        } finally {
            span.end()
        }
    }
}
