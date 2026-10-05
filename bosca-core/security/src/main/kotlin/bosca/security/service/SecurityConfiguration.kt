package bosca.security.service

import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import kotlinx.serialization.Serializable

interface SecurityConfiguration {
    val secret: String
    val issuer: String
    val audience: String
    val domain: String
    val adminDomain: String
    val realm: String
    val expirationTimeInSeconds: Long
    val algorithm: Algorithm
    val verifier: JWTVerifier
    val allowedRedirects: List<String>
    val oauth2: List<OAuth2Provider>

    /**
     * Public origin of the Studio web app (e.g. `http://localhost:3000` in dev or
     * `https://admin.example.com` in production). Used as the fallback base for transactional auth
     * email links when the originating host can't be determined or isn't allow-listed.
     */
    val appUrl: String

    /**
     * Absolute destination linked from security-alert emails. Defaults to [appUrl] so deployments
     * without a dedicated self-service account application retain their existing destination.
     */
    val securityAlertUrl: String

    /**
     * Absolute destination used by the Welcome email's primary action. This may point at a
     * product-specific onboarding route instead of the default Studio `/welcome` page.
     */
    val welcomeUrl: String

    /**
     * Allow-list of public web origins an auth email link may point back to, for multi-host
     * deployments (one backend serving several Studio hosts). The host a request came from is only used
     * to build an email link if it exactly matches (scheme+host+port) an entry here — otherwise [appUrl]
     * is used. Kept separate from [allowedRedirects] (OAuth `redirect_uri`s) so the two can be governed
     * independently. Empty disables multi-host routing (every link uses [appUrl]).
     */
    val allowedAppOrigins: List<String>

    /** Whether session cookies should only be sent over HTTPS connections. */
    val cookieSecure: Boolean

    /** Whether session cookies should be inaccessible to client-side JavaScript. */
    val cookieHttpOnly: Boolean

    /** Additional host-only authentication cookie prefixes selected by exact request domain. */
    val authCookiePrefixes: List<AuthCookiePrefix>

    /** WebAuthn configuration for passkey registration and authentication. */
    val webauthn: WebAuthnConfiguration
}

/**
 * Configures an additional authentication cookie prefix. [prefix] is the exact access-token
 * cookie name; refresh, metadata, and sign-in cookies append their established suffixes.
 * The default `_bat` cookie is always available and is not configured here.
 */
@Serializable
data class AuthCookiePrefix(
    val prefix: String,
    val domains: List<String>,
) {
    companion object {
        const val DEFAULT = "_bat"
    }
}

/**
 * Configuration for the WebAuthn Relying Party, controlling how passkeys are
 * registered and authenticated against this server.
 */
@Serializable
data class WebAuthnConfiguration(
    /** Human-readable name displayed to the user during passkey registration (e.g., "Bosca"). */
    val rpName: String = "Bosca",
    /** Relying Party ID — the effective domain for credential scoping. Defaults to the server's domain. */
    val rpId: String? = null,
    /** Allowed origins for WebAuthn ceremonies (e.g., "https://admin.example.com"). */
    val origins: List<String> = emptyList(),
    /**
     * Origins accepted in addition to [origins] or, when [origins] is empty, to the defaults — for an app
     * served from a subdomain of [rpId], such as a Studio host. Blank entries are ignored.
     */
    val extraOrigins: List<String> = emptyList(),
) {
    /**
     * Origins accepted in a ceremony's client data: the configured [origins] (or `https://<rpId>` and
     * `https://<adminDomain>` when none are configured) followed by [extraOrigins].
     */
    fun allowedOrigins(rpId: String, adminDomain: String): List<String> =
        (origins.ifEmpty { listOf("https://$rpId", "https://$adminDomain") } + extraOrigins)
            // Browsers report an origin without a trailing slash, so `https://host/` would never match.
            .map { it.trim().trimEnd('/') }
            .filter(String::isNotEmpty)
            .distinct()
}

@Serializable
data class OAuth2Provider(
    val type: String,
    val clientId: String,
    val clientSecret: String,
    val supportedClientIds: Set<String> = emptySet(),
    val enabled: Boolean,
    val callback: String,
    val adminCallback: String,
    val scopes: List<String>,
    val userInfoUrl: String,
    val authorizeUrl: String,
    val accessTokenUrl: String,
    /** Whether this provider supports PKCE (RFC 7636). When false, code_challenge parameters are omitted from the authorization URL. */
    val pkceEnabled: Boolean = true,
) {
    init {
        if (enabled) {
            requireHttpsEndpoint(type, "accessTokenUrl", accessTokenUrl, "client credentials in transit")
            requireHttpsEndpoint(type, "userInfoUrl", userInfoUrl, "user data in transit")
            requireHttpsEndpoint(type, "authorizeUrl", authorizeUrl, "authorization flow")
        }
    }
}

private fun requireHttpsEndpoint(providerType: String, field: String, value: String, purpose: String) {
    if (!value.startsWith("https://")) {
        throw IllegalArgumentException("OAuth2 provider '$providerType': $field must use HTTPS to protect $purpose")
    }
}
