package bosca.server.auth

import bosca.core.annotations.Internal
import bosca.security.model.AuthenticatedPrincipal

/**
 * Holds the authentication state for a single HTTP request, storing principals
 * authenticated by various providers (JWT, Basic, Session, OAuth2).
 *
 * Middleware populates this during request processing, and route handlers read it
 * to determine the caller's identity and permissions.
 */
class CallAuthenticationContext {
    private val principals = java.util.concurrent.ConcurrentHashMap<String, AuthenticatedPrincipal>()
    /** Tracks insertion order so [anyPrincipal] returns a deterministic result. */
    private val providerOrder = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Stores an authenticated principal under the given [provider] name. */
    @Internal
    fun principal(provider: String, principal: AuthenticatedPrincipal) {
        if (principals.putIfAbsent(provider, principal) == null) {
            providerOrder.add(provider)
        } else {
            principals[provider] = principal
        }
    }

    /**
     * Retrieves the principal authenticated by the given [provider].
     * When [provider] is null, falls back to the first available principal from any provider.
     */
    fun principal(provider: String? = null): AuthenticatedPrincipal? =
        if (provider != null) principals[provider] else anyPrincipal()

    /** Returns all stored principals across all providers as an immutable snapshot. */
    fun allPrincipals(): Map<String, AuthenticatedPrincipal> = principals.toMap()

    /** Returns the first registered principal in insertion order, or null if empty. */
    fun anyPrincipal(): AuthenticatedPrincipal? {
        for (provider in providerOrder) {
            principals[provider]?.let { return it }
        }
        return null
    }
}
