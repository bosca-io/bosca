package bosca.core.security

import bosca.core.security.model.TokenMetadata

/**
 * Platform-specific secure storage for authentication tokens.
 *
 * Implementations persist the access token, refresh token, token expiry
 * [TokenMetadata] across application sessions (Android
 * EncryptedSharedPreferences, iOS Keychain, browser localStorage, desktop
 * java.util.prefs). The metadata lets the token manager schedule a refresh
 * after a cold start without re-parsing the JWT.
 * Default platform implementations also implement [IdentityStorage].
 *
 * Ports the `TokenStorage` interface from `@bosca/auth-client-browser`
 * (storage.ts). The TS `setToken(token, expiresAtSec?)` expiry argument is
 * intentionally omitted: it only drives cookie auto-eviction on the web, and
 * native stores do not auto-evict — expiry is tracked solely via
 * [saveTokenMetadata]/[getTokenMetadata].
 */
interface TokenStorage {

    /** The stored access token, or `null` if absent. */
    suspend fun getToken(): String?

    /** Persist the access token, replacing any previous value. */
    suspend fun saveToken(token: String)

    /** The stored refresh token used to mint new access tokens, or `null`. */
    suspend fun getRefreshToken(): String?

    /** Persist the refresh token, replacing any previous value. */
    suspend fun saveRefreshToken(refreshToken: String)

    /**
     * The stored token expiry metadata, or `null` when absent (e.g. a legacy
     * session persisted before metadata was introduced — the token manager
     * falls back to parsing the JWT in that case).
     */
    suspend fun getTokenMetadata(): TokenMetadata?

    /** Persist the token expiry metadata alongside the access token. */
    suspend fun saveTokenMetadata(metadata: TokenMetadata)

    /** Remove the stored tokens and their metadata. */
    suspend fun clear()
}
