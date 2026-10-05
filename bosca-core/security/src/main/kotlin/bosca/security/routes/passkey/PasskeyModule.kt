package bosca.security.routes.passkey

import bosca.cache.CacheManager
import bosca.cache.serializers.StringKeySerializer
import bosca.di.provide
import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

/**
 * Machine-readable error response returned by passkey endpoints when a
 * ceremony step fails. The [error] field contains a stable code that
 * monitoring and client code can key on (e.g. "rate_limited",
 * "credential_compromised", "verification_failed").
 */
@Serializable
data class PasskeyErrorResponse(val error: String)

/**
 * Installs the WebAuthn state manager into DI, making it available for injection
 * into the KSP-discovered passkey route controllers. The challenge cache uses the
 * same distributed cache infrastructure as OAuth2 state management.
 */
class PasskeyModule : BoscaApplicationModule {

    override suspend fun install(application: BoscaApplication) = with(application) {
        val json = provide<Json>()
        val cacheManager = provide<CacheManager>()
        val cache = cacheManager.maybeAddCache(WebAuthnStateManager.CHALLENGE_CACHE_KEY, StringKeySerializer, 5.minutes)
        val stateManager = WebAuthnStateManager(cache, json)
        provides(singleton = true) { stateManager }
    }
}
