package bosca.security.routes.oauth2

import bosca.cache.Cache
import bosca.cache.StringCacheKey
import bosca.security.routes.security.validateRedirect
import bosca.server.Parameters
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Carries the OAuth2 authorization state through the redirect flow, preserving
 * signup tokens, the target redirect URL, and the PKCE code verifier between
 * the initial login request and the provider callback.
 */
@Serializable
data class OAuth2State(
    val state: String,
    val provider: String,
    val tokens: List<bosca.security.model.SignupToken>,
    val redirect: String,
    val admin: Boolean,
    val codeVerifier: String,
    /**
     * The caller-supplied login request originator captured at the `/oauth2/{provider}/login` entry point,
     * carried through the redirect so it can be stamped on the credential (first-time signup) and echoed
     * back on the eventual [bosca.security.model.LoginResponse]. Defaulted so any state cached before this
     * field existed still deserializes.
     */
    val originator: String? = null,
    /** Authenticated principal receiving this provider when the flow is account linking, not login. */
    val connectPrincipalId: UUID? = null,
)

/**
 * Manages OAuth2 authorization state stored in the distributed cache, providing
 * creation and retrieval of state objects that carry signup tokens and redirect
 * URLs across the OAuth2 authorization flow.
 */
class OAuth2StateManager(
    private val cache: Cache<String>,
    private val json: Json,
    private val allowedRedirects: List<String>,
) {

    /**
     * Creates and caches an OAuth2 state entry from the login request's query
     * parameters, capturing any organization/community signup tokens, the
     * intended redirect URL, and a PKCE code verifier for the authorization flow.
     *
     * @return the PKCE code challenge derived from the generated code verifier,
     *         to be included in the authorization URL
     */
    suspend fun createState(
        queryParameters: Parameters,
        state: String,
        providerType: String,
        connectPrincipalId: UUID? = null,
    ): String {
        val organizationToken = queryParameters["organization"]
        val communityToken = queryParameters["community"]
        val tokens = buildList {
            if (organizationToken != null) {
                add(bosca.security.model.SignupToken(bosca.security.model.SignupTokenType.ORGANIZATION, organizationToken))
            }
            if (communityToken != null) {
                add(bosca.security.model.SignupToken(bosca.security.model.SignupTokenType.COMMUNITY_GROUP, communityToken))
            }
        }

        val redirect = queryParameters["redirect"]
        val validatedRedirect = if (!redirect.isNullOrBlank()) {
            validateRedirect(redirect, allowedRedirects)
        } else {
            "/"
        } ?: "/"

        val codeVerifier = generateCodeVerifier()
        val oauth2State = OAuth2State(
            state = state,
            provider = providerType,
            tokens = tokens,
            redirect = validatedRedirect,
            admin = queryParameters["admin"] == "true",
            codeVerifier = codeVerifier,
            originator = queryParameters["originator"],
            connectPrincipalId = connectPrincipalId,
        )
        cache.put(StringCacheKey(STATE_CACHE_KEY, state), json.encodeToString(oauth2State))

        return generateCodeChallenge(codeVerifier)
    }

    /**
     * Retrieves and atomically removes the cached OAuth2 state for the given
     * state token, returning null if the state does not exist or has expired.
     */
    suspend fun retrieveAndRemoveState(state: String): OAuth2State? {
        val value = cache.remove(StringCacheKey(STATE_CACHE_KEY, state))?.value ?: return null
        return json.decodeFromString<OAuth2State>(value)
    }

    companion object {
        /**
         * Cache key prefix for OAuth2 authorization state entries.
         *
         * State entries inherit the cache-level TTL (5 minutes as configured in
         * [OAuth2Module]), which bounds the CSRF attack window
         * and prevents indefinite accumulation. Users must complete the OAuth2 flow
         * within this window or the state will expire.
         */
        const val STATE_CACHE_KEY = "oauth2:state"

        private val secureRandom = SecureRandom()
        private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()

        /**
         * Generates a cryptographically random PKCE code verifier (86 characters)
         * per RFC 7636 Section 4.1, using base64url-encoded random bytes.
         */
        internal fun generateCodeVerifier(): String {
            val bytes = ByteArray(64)
            secureRandom.nextBytes(bytes)
            return base64UrlEncoder.encodeToString(bytes)
        }

        /**
         * Derives a PKCE code challenge from the given [codeVerifier] using S256
         * (SHA-256 hash, base64url-encoded without padding) per RFC 7636 Section 4.2.
         */
        internal fun generateCodeChallenge(codeVerifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
            return base64UrlEncoder.encodeToString(hash)
        }
    }
}
