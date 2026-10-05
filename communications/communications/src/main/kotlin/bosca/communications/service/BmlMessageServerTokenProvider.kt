package bosca.communications.service

import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/**
 * Supplies the short-lived service-account JWT forwarded through the BML Message Server to Bosca
 * GraphQL. Tokens are single-flight minted and reused until five minutes before expiration, so
 * bulk sends avoid repeated signing while no render starts with a nearly expired credential.
 */
class BmlMessageServerTokenProvider(
    private val securityService: SecurityService,
    private val now: () -> Instant = Instant::now,
    private val refreshBeforeExpiry: Duration = Duration.ofMinutes(5),
) {
    private data class CachedToken(val value: String, val refreshAt: Instant)

    @Volatile
    private var cached: CachedToken? = null
    private val refreshMutex = Mutex()

    /** Returns a reusable service-account JWT, refreshing it conservatively before expiration. */
    suspend fun token(): String {
        val current = validCachedToken()
        if (current != null) return current.value
        return refreshMutex.withLock {
            val refreshed = validCachedToken()
            if (refreshed != null) refreshed.value else mint()
        }
    }

    private fun validCachedToken(): CachedToken? =
        cached?.takeIf { now().isBefore(it.refreshAt) }

    private suspend fun mint(): String {
        val serviceAccount = securityService.impersonate(SERVICE_ACCOUNT).principal().asPrincipal()
        val jwt = securityService.createJwtToken(serviceAccount, emptyMap())
        val expiration = requireNotNull(jwt.expiresAtAsInstant) {
            "BML message service-account JWT has no expiration"
        }
        return jwt.token.also {
            cached = CachedToken(it, expiration.minus(refreshBeforeExpiry))
        }
    }

    private companion object {
        const val SERVICE_ACCOUNT = "sa"
    }
}
