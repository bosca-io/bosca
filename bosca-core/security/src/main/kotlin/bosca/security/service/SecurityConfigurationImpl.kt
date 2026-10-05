package bosca.security.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.config.ConfigValue

private fun <T> ApplicationConfig.getOrDefault(
    path: String,
    default: T,
    read: (ConfigValue) -> T,
): T {
    val value = propertyOrNull(path) ?: return default
    return read(value)
}

class SecurityConfigurationImpl(application: BoscaApplication) : SecurityConfiguration {

    override val secret: String
    override val issuer: String
    override val audience: String
    override val domain: String
    override val adminDomain: String
    override val realm: String
    override val expirationTimeInSeconds: Long
    override val algorithm: Algorithm
    override val verifier: JWTVerifier
    override val allowedRedirects: List<String>
    override val oauth2: List<OAuth2Provider>
    override val appUrl: String
    override val securityAlertUrl: String
    override val welcomeUrl: String
    override val allowedAppOrigins: List<String>
    override val cookieSecure: Boolean
    override val cookieHttpOnly: Boolean
    override val authCookiePrefixes: List<AuthCookiePrefix>
    override val webauthn: WebAuthnConfiguration

    init {
        val cfg = application.environment.config
        secret = cfg.property("jwt.secret").getString()
        issuer = cfg.property("jwt.issuer").getString()
        audience = cfg.property("jwt.audience").getString()
        domain = cfg.property("jwt.domain").getString()
        adminDomain = cfg.property("jwt.admin-domain").getString()
        realm = cfg.property("jwt.realm").getString()
        expirationTimeInSeconds = cfg.property("jwt.expiration-time").getString().toLongOrNull() ?: 3600
        algorithm = Algorithm.HMAC256(secret)
        verifier = JWT.require(algorithm).withIssuer(issuer).withAudience(audience).build()
        allowedRedirects = cfg.getOrDefault("oauth2.redirects", emptyList<String>()) { it.getList() }
        oauth2 = cfg.getOrDefault("oauth2.providers", emptyList<OAuth2Provider>()) {
            it.getAs<List<OAuth2Provider>>()
        }
        appUrl = cfg.getOrDefault("app.url", "http://localhost:3000") { it.getString() }
        securityAlertUrl = cfg.propertyOrNull("app.security-alert-url")
            ?.getString()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: appUrl
        welcomeUrl = cfg.propertyOrNull("app.welcome-url")
            ?.getString()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: (appUrl.trimEnd('/') + "/welcome")
        allowedAppOrigins = cfg.getOrDefault("app.allowed-origins", emptyList<String>()) { it.getList() }
        cookieSecure = cfg.getOrDefault("jwt.cookie-secure", false) {
            it.getString().toBooleanStrictOrNull() ?: false
        }
        cookieHttpOnly = cfg.getOrDefault("jwt.cookie-http-only", false) {
            it.getString().toBooleanStrictOrNull() ?: false
        }
        authCookiePrefixes = cfg.readAuthCookiePrefixes()
        webauthn = cfg.getOrDefault("webauthn", WebAuthnConfiguration(rpId = domain)) {
            it.getAs<WebAuthnConfiguration>()
        }.let { configured ->
            // An unset `$WEBAUTHN_EXTRA_ORIGIN` renders as a blank entry; drop it so the defaults stay unchanged.
            configured.copy(extraOrigins = configured.extraOrigins.map { it.trim().trimEnd('/') }.filter(String::isNotEmpty))
        }
    }
}

private fun ApplicationConfig.readAuthCookiePrefixes(): List<AuthCookiePrefix> {
    val configured = propertyOrNull("jwt.cookie-prefixes") ?: return emptyList()
    val normalized = configured.getAs<List<AuthCookiePrefix>>().map { it.validateAndNormalize() }
    return normalized.requireNoConflicts()
}

private fun AuthCookiePrefix.validateAndNormalize(): AuthCookiePrefix {
    val normalizedPrefix = prefix.trim()
    require(normalizedPrefix.matches(AUTH_COOKIE_PREFIX_PATTERN)) {
        "Auth cookie prefix '$normalizedPrefix' contains unsupported characters"
    }
    require(AUTH_COOKIE_RESERVED_PREFIXES.none { normalizedPrefix.startsWith(it) }) {
        "Auth cookie prefix '$normalizedPrefix' uses a reserved browser cookie prefix"
    }
    require(normalizedPrefix !in RESERVED_DEFAULT_AUTH_COOKIE_NAMES) {
        "Auth cookie prefix '$normalizedPrefix' is reserved for the default auth cookie"
    }
    val normalizedDomains = domains.map { domain ->
        domain.trim().removePrefix(".").lowercase().also {
            require(it.matches(AUTH_COOKIE_DOMAIN_PATTERN)) { "Invalid auth cookie domain '$domain'" }
        }
    }
    require(normalizedDomains.isNotEmpty()) {
        "Auth cookie prefix '$normalizedPrefix' must have at least one domain"
    }
    require(normalizedDomains.size == normalizedDomains.toSet().size) {
        "Auth cookie prefix '$normalizedPrefix' contains duplicate domains"
    }
    return AuthCookiePrefix(normalizedPrefix, normalizedDomains)
}

private fun List<AuthCookiePrefix>.requireNoConflicts(): List<AuthCookiePrefix> {
    val prefixes = mutableSetOf<String>()
    val domains = mutableSetOf<String>()
    for (configured in this) {
        require(prefixes.add(configured.prefix)) {
            "Auth cookie prefix '${configured.prefix}' is configured more than once"
        }
        for (domain in configured.domains) {
            require(domains.add(domain)) {
                "Auth cookie domain '$domain' belongs to multiple prefixes"
            }
        }
    }
    return this
}

private val AUTH_COOKIE_DOMAIN_PATTERN = Regex(
    "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$"
)

private val AUTH_COOKIE_PREFIX_PATTERN = Regex("^[A-Za-z0-9_-]+$")

private val AUTH_COOKIE_RESERVED_PREFIXES = listOf("__Host-", "__Secure-")

private val RESERVED_DEFAULT_AUTH_COOKIE_NAMES = setOf("_bat", "_bat_rt", "_bat_meta", "_bat_signin")
