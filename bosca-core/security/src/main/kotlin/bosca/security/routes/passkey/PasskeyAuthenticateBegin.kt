package bosca.security.routes.passkey

import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityConfiguration
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class PasskeyAuthenticateBeginResponse(
    val stateKey: String,
    val challenge: String,
    val rpId: String,
    val timeout: Long = 300000,
)

/**
 * Initiates a WebAuthn authentication ceremony. This endpoint is unauthenticated
 * since the user is proving their identity via the passkey itself. Returns a
 * challenge and RP ID needed for navigator.credentials.get(). The allowCredentials
 * list is intentionally omitted to support discoverable credentials (resident keys),
 * allowing the authenticator to present all passkeys for this RP.
 *
 * Applies IP-based rate limiting (10 requests per minute per IP) to mitigate
 * challenge-flooding denial-of-service attacks against this unauthenticated endpoint.
 */
@RouteController("/api/v1/security/passkeys/authenticate/begin", method = RouteMethod.POST, authentication = RouteAuthentication.NONE)
class PasskeyAuthenticateBegin(
    private val securityConfiguration: SecurityConfiguration,
    private val stateManager: WebAuthnStateManager,
) : Route<PasskeyAuthenticateBeginResponse>() {

    override fun serializer(): KSerializer<PasskeyAuthenticateBeginResponse> = PasskeyAuthenticateBeginResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): PasskeyAuthenticateBeginResponse? {
        val clientIp = call.request.clientIp ?: "unknown"
        if (rateLimiter.isRateLimited(clientIp)) {
            log.warn("WebAuthn authenticate/begin rate limited for IP {}", clientIp)
            call.respond(HttpStatusCode.TooManyRequests, PasskeyErrorResponse("rate_limited"))
            return null
        }

        val webauthnConfig = securityConfiguration.webauthn
        val rpId = webauthnConfig.rpId ?: securityConfiguration.domain

        val (stateKey, state) = stateManager.createChallenge(principalId = null)

        return PasskeyAuthenticateBeginResponse(
            stateKey = stateKey,
            challenge = state.challenge,
            rpId = rpId,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(PasskeyAuthenticateBegin::class.java)
        private val rateLimiter = IpRateLimiter(maxRequests = 10, windowSeconds = 60)
    }
}

/**
 * Sliding-window rate limiter that tracks request timestamps per IP address in memory.
 * Designed for lightweight abuse prevention on unauthenticated endpoints. Stale entries
 * are purged opportunistically when the map exceeds a size threshold to bound memory use.
 *
 * @param maxRequests the maximum number of requests allowed within the time window
 * @param windowSeconds the duration of the sliding window in seconds
 */
class IpRateLimiter(
    private val maxRequests: Int,
    private val windowSeconds: Long,
) {
    private val requestLog = ConcurrentHashMap<String, MutableList<Instant>>()

    /**
     * Returns true if the given [ip] has exceeded the rate limit within the current
     * sliding window, false otherwise. Each invocation that returns false is recorded
     * as a request against the window.
     *
     * All list mutation for the target IP happens inside [ConcurrentHashMap.compute],
     * which holds the bin lock for that key, eliminating races between concurrent
     * callers for the same IP. When the map exceeds 1 000 entries, a full purge pass
     * removes empty entries — also performed inside per-key `compute` calls so that
     * no external synchronization is needed.
     */
    fun isRateLimited(ip: String): Boolean {
        val now = Instant.now()
        val cutoff = now.minusSeconds(windowSeconds)

        val needsPurge = requestLog.size > 1000

        var limited = false
        requestLog.compute(ip) { _, existing ->
            val list = existing ?: mutableListOf()
            list.removeAll { it.isBefore(cutoff) }
            if (list.size >= maxRequests) {
                limited = true
            } else {
                list.add(now)
            }
            if (list.isEmpty()) null else list
        }

        if (needsPurge) {
            for (key in requestLog.keys()) {
                requestLog.computeIfPresent(key) { _, existing ->
                    existing.removeAll { it.isBefore(cutoff) }
                    if (existing.isEmpty()) null else existing
                }
            }
        }

        return limited
    }
}
