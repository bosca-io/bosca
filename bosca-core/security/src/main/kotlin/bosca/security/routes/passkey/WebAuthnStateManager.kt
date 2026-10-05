package bosca.security.routes.passkey

import bosca.cache.Cache
import bosca.cache.StringCacheKey
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64

/**
 * Carries WebAuthn ceremony state through the challenge-response flow, preserving
 * the randomly-generated challenge and associated principal metadata between the
 * "begin" and "complete" phases of both registration and authentication ceremonies.
 */
@Serializable
data class WebAuthnChallengeState(
    val challenge: String,
    val principalId: UUID? = null,
)

/**
 * Manages WebAuthn challenge state stored in the distributed cache. Challenges are
 * short-lived (5-minute TTL from cache configuration) and atomically removed on
 * retrieval to prevent replay attacks.
 */
class WebAuthnStateManager(
    private val cache: Cache<String>,
    private val json: Json,
) {

    /**
     * Generates a cryptographically random challenge, caches it with the associated
     * principal and admin context, and returns the state keyed by a unique token.
     *
     * @return a pair of (state key for client round-trip, the challenge state)
     */
    suspend fun createChallenge(principalId: UUID? = null): Pair<String, WebAuthnChallengeState> {
        val stateKey = generateStateKey()
        val challenge = generateChallenge()
        val state = WebAuthnChallengeState(
            challenge = challenge,
            principalId = principalId,
        )
        cache.put(StringCacheKey(CHALLENGE_CACHE_KEY, stateKey), json.encodeToString(state))
        return stateKey to state
    }

    /**
     * Retrieves and atomically removes the cached challenge state for the given
     * state key, returning null if the state does not exist or has expired.
     */
    suspend fun retrieveAndRemoveChallenge(stateKey: String): WebAuthnChallengeState? {
        val value = cache.remove(StringCacheKey(CHALLENGE_CACHE_KEY, stateKey))?.value ?: return null
        return json.decodeFromString<WebAuthnChallengeState>(value)
    }

    companion object {
        const val CHALLENGE_CACHE_KEY = "webauthn:challenge"

        private val secureRandom = SecureRandom()
        private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()

        internal fun generateStateKey(): String {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            return base64UrlEncoder.encodeToString(bytes)
        }

        internal fun generateChallenge(): String {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            return base64UrlEncoder.encodeToString(bytes)
        }
    }
}
