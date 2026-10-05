package bosca.security.routes.oauth2

import bosca.http.withRequestContext
import bosca.security.model.LoginResponse
import bosca.security.model.OAuth2CredentialAttributes
import bosca.security.oauth2.ThirdPartyUser
import bosca.security.service.AuthCookiePrefix
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.security.service.resolveAuthCookiePrefix
import bosca.security.session.Session
import bosca.server.Cookie
import bosca.server.routing.RoutingContext
import io.opentelemetry.api.trace.Tracer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.Logger
import java.net.URI
import java.net.URLEncoder
import java.util.*

/**
 * Processes OAuth2 callbacks after a user authenticates with a third-party provider,
 * performing the login or account creation, and then either setting a session cookie
 * for same-domain requests or generating an exchange token for cross-domain redirects.
 */
class OAuth2CallbackHandler(
    private val securityService: SecurityService,
    private val securityConfiguration: SecurityConfiguration,
    private val tracer: Tracer,
    private val log: Logger,
) {

    /**
     * Handles the OAuth2 provider callback after state has been validated and the user
     * info fetched. Logs in (or creates) the user and redirects to the stored redirect
     * URL, using either a session cookie or an exchange token depending on whether the
     * redirect is cross-domain.
     */
    suspend fun RoutingContext.handleCallback(oauth2State: OAuth2State, type: String, user: ThirdPartyUser) = withRequestContext {
        val span = tracer.spanBuilder("security.$type.oauth2").startSpan()

        val connectPrincipalId = oauth2State.connectPrincipalId
        if (connectPrincipalId != null) {
            try {
                securityService.connectThirdParty(
                    connectPrincipalId,
                    OAuth2CredentialAttributes(user.id, null, null, type),
                    user,
                )
            } finally {
                span.end()
            }
            val separator = if ("?" in oauth2State.redirect) "&" else "?"
            call.respondRedirect("${oauth2State.redirect}${separator}connected=${URLEncoder.encode(type, Charsets.UTF_8)}")
            return@withRequestContext
        }

        val loginResponse = try {
            val requestedLanguage = call.request.acceptLanguageItems().firstOrNull()
            val locale = Locale.forLanguageTag(if (requestedLanguage == null) "en-US" else requestedLanguage.value)
            // For a new, provider-unverified third-party signup the service sends a verification email; pass
            // the origin of the post-login redirect (the host the user started the flow on) so that link
            // routes back there rather than to the default app origin. Validated against the allow-list
            // downstream; a relative redirect (no host) yields null and falls back to the default.
            securityService.loginWithThirdParty(
                OAuth2CredentialAttributes(user.id, "", null, type), user, locale, true, oauth2State.tokens,
                requestOrigin = redirectOrigin(oauth2State.redirect),
                // Where this sign-in came from: stamped on the credential (first-time signup) and echoed
                // back on the login response.
                originator = oauth2State.originator,
            )
        } finally {
            span.end()
        }

        if (isCrossDomain(oauth2State.redirect)) {
            log.info("Cross-domain redirect for principal: ${loginResponse.principalId}, generating exchange token")
            val exchangeToken = securityService.createExchangeToken(
                loginResponse.principalId,
                loginResponse.accountCreated,
                loginResponse.originator,
            )
            val separator = if ("?" in oauth2State.redirect) "&" else "?"
            val encodedToken = URLEncoder.encode(exchangeToken, Charsets.UTF_8)
            call.respondRedirect("${oauth2State.redirect}${separator}exchangeToken=$encodedToken")
        } else {
            val customCookieName = securityConfiguration.resolveAuthCookiePrefix(call)
            call.sessions.clear()
            if (oauth2State.admin) {
                log.info("Setting admin session for principal: ${loginResponse.principalId}")
                val groups = securityService.getPrincipalGroups(loginResponse.principalId)
                call.sessions.set(Session(loginResponse, groups.any { it.name == "administrators" }))
            } else {
                log.info("Setting session for principal: ${loginResponse.principalId}")
                call.sessions.set(Session(loginResponse, false))
            }
            writeCompanionCookies(loginResponse, oauth2State.admin, customCookieName)
            call.respondRedirect(oauth2State.redirect)
        }
    }

    /**
     * Reduces the post-login [redirect] to its origin (`scheme://host[:port]`) — the host the user started the
     * OAuth flow on — for routing the verification email of a new, provider-unverified third-party signup.
     * Returns null when [redirect] has no parseable host (e.g. a relative path), so the caller falls back to
     * the default app origin. The result is validated against the app-origin allow-list downstream, so an
     * unexpected host can never leak into the email link.
     */
    private fun redirectOrigin(redirect: String): String? = try {
        val uri = URI(redirect)
        uri.host?.let { host ->
            val scheme = uri.scheme ?: return null
            val port = if (uri.port != -1) ":${uri.port}" else ""
            "$scheme://$host$port"
        }
    } catch (_: Exception) {
        null
    }

    private fun RoutingContext.isCrossDomain(redirect: String): Boolean {
        val redirectUri = try { URI(redirect) } catch (_: Exception) { return false }
        val redirectHost = redirectUri.host ?: return false
        val redirectScheme = redirectUri.scheme ?: "https"
        val redirectPort = if (redirectUri.port != -1) redirectUri.port else defaultPort(redirectScheme)

        val requestScheme = call.request.origin.scheme
        val requestHost = call.request.origin.host
        val requestPort = call.request.origin.port

        if (!redirectScheme.equals(requestScheme, ignoreCase = true)) return true
        if (!redirectHost.equals(requestHost, ignoreCase = true)) return true
        return redirectPort != requestPort
    }

    /**
     * Sets the refresh token and token metadata cookies alongside the session cookie
     * so that the client-side auth library can restore a full session on page load
     * without requiring an exchange token round-trip.
     */
    private fun RoutingContext.writeCompanionCookies(
        loginResponse: LoginResponse,
        admin: Boolean,
        customCookieName: String?,
    ) {
        val cookieName = customCookieName ?: AuthCookiePrefix.DEFAULT
        val domain = if (customCookieName == null) {
            if (admin) securityConfiguration.adminDomain else securityConfiguration.domain
        } else null
        val refreshToken = loginResponse.refreshToken
        if (refreshToken != null) {
            call.response.cookies.append(
                Cookie(
                    name = "${cookieName}_rt",
                    value = refreshToken,
                    maxAge = 365 * 24 * 60 * 60,
                    domain = domain,
                    path = "/",
                    secure = securityConfiguration.cookieSecure,
                    httpOnly = false,
                    sameSite = "Lax",
                )
            )
        }
        val meta = """{"expiresAt":${loginResponse.token.expiresAt},"issuedAt":${loginResponse.token.issuedAt}}"""
        call.response.cookies.append(
            Cookie(
                name = "${cookieName}_meta",
                value = URLEncoder.encode(meta, Charsets.UTF_8),
                maxAge = 365 * 24 * 60 * 60,
                domain = domain,
                path = "/",
                secure = securityConfiguration.cookieSecure,
                httpOnly = false,
                sameSite = "Lax",
            )
        )
        // Same-domain OAuth completes here via the session cookie and mints no
        // exchange token, so the client library would restore the session
        // silently with no `signedIn` event. Hand the sign-in outcome across the
        // redirect in a one-shot cookie — the echoed originator and whether the
        // account was just created — so the client can announce it exactly once.
        // The client clears this on read; the short TTL bounds an unread marker.
        val signIn = buildJsonObject {
            put("originator", loginResponse.originator)
            put("accountCreated", loginResponse.accountCreated)
        }.toString()
        call.response.cookies.append(
            Cookie(
                name = "${cookieName}_signin",
                value = URLEncoder.encode(signIn, Charsets.UTF_8),
                maxAge = 5 * 60,
                domain = domain,
                path = "/",
                secure = securityConfiguration.cookieSecure,
                httpOnly = false,
                sameSite = "Lax",
            )
        )
    }

    private fun defaultPort(scheme: String): Int = when (scheme.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }
}
