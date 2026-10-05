package bosca.core.security

import bosca.core.security.type.ThirdPartyType
import bosca.core.security.model.AuthEvent
import bosca.core.security.model.AuthResponse
import bosca.core.security.model.AuthStatus
import bosca.core.security.model.Group
import bosca.core.security.model.OAuthRedirectOptions
import bosca.core.security.model.Principal
import bosca.core.security.model.Profile
import bosca.core.security.model.ProfileInput
import bosca.core.security.model.SignupOptions
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

/**
 * Central authentication client for Bosca apps. Kotlin port of the TypeScript
 * `BoscaAuth` class. Manages the full lifecycle: email/password sign-in, OAuth
 * (native token flow on mobile, server-redirect on web), automatic token
 * refresh, account management, and profile access.
 *
 * The TS getters become reactive [StateFlow]s and its event emitter becomes the
 * [events] [SharedFlow]. State is owned here (a DI singleton), so it survives
 * UI/ViewModel recreation.
 */
interface BoscaAuth {

    /** Coarse auth state for navigation: [AuthStatus.Unknown] until [initialize] resolves. */
    val status: StateFlow<AuthStatus>

    /** The authenticated principal, or null when signed out. */
    val currentUser: StateFlow<Principal?>

    /** The primary profile (or first) of the authenticated user, or null. */
    val currentProfile: StateFlow<Profile?>

    /** All profiles owned by the authenticated user. */
    val profiles: StateFlow<List<Profile>>

    /** Security groups the authenticated user belongs to. */
    val groups: StateFlow<List<Group>>

    /** Whether a user is currently authenticated. */
    val isAuthenticated: StateFlow<Boolean>

    /** One-shot auth events (signed in/out, token refreshed, profile updated, error). */
    val events: SharedFlow<AuthEvent>

    /** The raw access token without an expiry check (use [getToken] for a valid one). */
    val token: String?

    /**
     * Restores a persisted session on startup. When no session is stored and
     * the page URL carries a web OAuth `exchangeToken` (the return leg of
     * [signInWithRedirect]), completes that sign-in instead. Returns the
     * principal if a session was restored (and [fetchProfile] succeeded),
     * else null.
     */
    suspend fun initialize(fetchProfile: Boolean = true): Principal?

    suspend fun signInWithPassword(identifier: String, password: String): AuthResponse

    /** Registers a new account. Does not sign in (email verification may be required). */
    suspend fun signUp(options: SignupOptions): Principal

    /** Signs in/up with a provider-issued OAuth token (token obtained by the caller). */
    suspend fun signInWithThirdParty(type: ThirdPartyType, token: String, languageTag: String? = null): AuthResponse

    /**
     * Platform-appropriate third-party sign-in. On mobile/desktop, obtains a
     * provider token via the platform
     * [bosca.core.security.providers.ThirdPartyAuthenticationProvider], then
     * exchanges it via [signInWithThirdParty]. On web, falls back to the
     * server-redirect flow ([signInWithRedirect]): the browser navigates away
     * and this call never resumes — the sign-in completes on the next page
     * load via [initialize].
     */
    suspend fun signInWithThirdPartyNative(provider: ThirdPartyProvider, languageTag: String? = null): AuthResponse

    /** Server-redirect OAuth (web). Navigates the browser away; complete via [handleRedirectResult]. */
    fun signInWithRedirect(options: OAuthRedirectOptions)

    /** Completes a web OAuth redirect by exchanging the returned token, or null if none present. */
    suspend fun handleRedirectResult(): AuthResponse?

    /**
     * Exchanges a single-use OAuth `exchangeToken` for a session, signing the
     * user in. Unlike [handleRedirectResult] (which reads the token from the web
     * redirect URL), the caller supplies the token directly — used by native
     * loopback OAuth flows such as the CLI's browser sign-in.
     */
    suspend fun exchange(token: String): AuthResponse

    suspend fun signOut()

    suspend fun forgotPassword(identifier: String)

    suspend fun resetPassword(token: String, password: String)

    suspend fun changePassword(newPassword: String, oldPassword: String)

    suspend fun changeIdentifier(identifier: String, password: String)

    suspend fun verifyEmail(token: String)

    suspend fun resendVerification(identifier: String)

    suspend fun getProfiles(): List<Profile>

    suspend fun updateProfile(id: Uuid?, input: ProfileInput): Profile

    suspend fun setPrimaryProfile(profileId: Uuid, principalId: Uuid? = null)

    suspend fun getGroups(): List<Group>

    /**
     * Forces a token refresh using the stored refresh token, regardless of the
     * current token's local expiry, persisting the rotated session. Returns the
     * new session, or null when no refresh token is available. Unlike [getToken]
     * (which only refreshes a *locally* expired token), this is the explicit
     * "refresh now" used to recover from a server-side rejection (401) of a token
     * that has not yet expired locally.
     */
    suspend fun refresh(): AuthResponse?

    /** A valid access token, auto-refreshing if needed; null when signed out. */
    suspend fun getToken(): String?

    /** `{ Authorization: Bearer <token> }`, or empty when signed out. */
    suspend fun getAuthHeaders(): Map<String, String>

    /** Tears down timers and state. */
    fun destroy()
}
