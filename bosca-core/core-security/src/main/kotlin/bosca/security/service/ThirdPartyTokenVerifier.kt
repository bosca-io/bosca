package bosca.security.service

import bosca.security.oauth2.ThirdPartyUser
import bosca.service.Service

/**
 * Verifies a provider-issued OAuth/OIDC token and resolves the asserted third-party identity.
 *
 * Modeled as a provided interface (rather than inline network calls inside the security service) so the
 * OAuth sign-up / login / connect paths can be exercised with a mock instead of a live provider
 * round-trip. The single network-bound seam lives behind this contract. Extends [Service] so the
 * `@ServiceImplementation` on its impl is auto-registered as a DI provider.
 */
interface ThirdPartyTokenVerifier : Service {

    /**
     * Verifies [token] for provider [type] and returns the asserted user — including the
     * provider-verified-email signal ([ThirdPartyUser.emailVerified]). Throws if the token is invalid,
     * its audience does not match, or [type] is not a configured provider.
     */
    suspend fun verify(type: ThirdPartyType, token: String): ThirdPartyUser
}
