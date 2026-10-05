package bosca.core.security

import bosca.core.security.type.ThirdPartyType
import bosca.core.security.model.AuthEvent
import bosca.core.security.model.AuthResponse
import bosca.core.security.model.AuthStatus
import bosca.core.security.model.BoscaAuthConfig
import bosca.core.security.model.Group
import bosca.core.security.model.Identity
import bosca.core.security.model.OAuthRedirectOptions
import bosca.core.security.model.Principal
import bosca.core.security.model.Profile
import bosca.core.security.model.ProfileInput
import bosca.core.security.model.SignupOptions
import bosca.core.security.providers.ThirdPartyAuthenticationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Default [BoscaAuth] implementation. Kotlin port of `client.ts`.
 *
 * @param scope a long-lived (SupervisorJob) scope owned by this client, used for the token manager
 * @param now epoch-millis clock, injectable for deterministic tests
 */
class BoscaAuthImpl(
    storage: TokenStorage,
    identityStorage: IdentityStorage,
    private val graphql: AuthGraphql,
    private val config: BoscaAuthConfig,
    private val scope: CoroutineScope,
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    // OAuth redirect seams, injected for testability; default to the platform actuals.
    private val startRedirect: (apiUrl: String, options: OAuthRedirectOptions) -> Unit = ::startOAuthRedirect,
    readExchangeToken: () -> String? = ::getExchangeTokenFromUrl,
    private val signInUsesRedirect: Boolean = oauthSignInUsesRedirect,
) : BoscaAuth {

    /**
     * The web OAuth return-leg token, captured (and stripped from the URL)
     * eagerly at construction so it survives SPA-router URL rewrites that can
     * happen before [initialize]/[handleRedirectResult] get to run (e.g.
     * Compose `bindBackStackToBrowserHistory` replacing the location with the
     * bare route path). Single-use: consumed by [handleRedirectResult].
     * Always null off web.
     */
    private var pendingExchangeToken: String? = readExchangeToken()

    private val tokenManager = TokenManager(
        storage = storage,
        identityStorage = identityStorage,
        graphql = graphql,
        config = config,
        scope = scope,
        onEvent = { handleTokenManagerEvent(it) },
        now = now,
    )

    private val _status = MutableStateFlow<AuthStatus>(AuthStatus.Unknown)
    override val status: StateFlow<AuthStatus> = _status.asStateFlow()

    private val _currentUser = MutableStateFlow<Principal?>(null)
    override val currentUser: StateFlow<Principal?> = _currentUser.asStateFlow()

    private val _currentProfile = MutableStateFlow<Profile?>(null)
    override val currentProfile: StateFlow<Profile?> = _currentProfile.asStateFlow()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    override val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    override val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    override val token: String? get() = tokenManager.getToken()

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    override suspend fun initialize(fetchProfile: Boolean): Principal? {
        tokenManager.restore()

        // Restore the last server-verified identity before touching the network.
        // This is the authoritative offline state for a token session.
        val storedIdentity = try {
            tokenManager.getIdentity()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("[bosca-auth] Failed to restore persisted auth session", e)
            null
        }
        storedIdentity?.let(::restoreIdentity)

        // Use getValidToken() so two recovery cases work: the access token
        // expired between launches (lazy refresh), or only the refresh token
        // survived (recover by refreshing).
        val validToken = try {
            tokenManager.getValidToken()
        } catch (e: CancellationException) {
            throw e
        } catch (_: AuthenticationRejectedError) {
            rejectAuthentication()
            return null
        } catch (e: Exception) {
            // Connectivity/local-refresh failures do not revoke a previously
            // verified session. A session without persisted identity cannot be restored.
            Log.e("[bosca-auth] Token refresh failed during initialize", e)
            _currentUser.value?.let { return it }
            _status.value = AuthStatus.Unauthenticated
            return null
        }
        if (validToken == null) {
            _currentUser.value?.let { return it }

            // No stored session — but we may be returning from a web OAuth
            // redirect with a single-use exchange token in the URL (always
            // null off web). Completing it here means apps get redirect-based
            // sign-in for free from their normal startup initialize() call.
            try {
                if (handleRedirectResult() != null) return _currentUser.value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The exchange token is single-use and short-lived; a failed
                // exchange (expired, replayed) must not brick startup — resolve
                // to Unauthenticated so the user can retry the sign-in.
                Log.e("[bosca-auth] OAuth redirect exchange failed during initialize", e)
            }
            _status.value = AuthStatus.Unauthenticated
            return null
        }

        if (fetchProfile) {
            try {
                val principal = graphql.getCurrentPrincipal(validToken)
                val profiles = graphql.getCurrentProfiles(validToken)
                setCurrentUser(principal)
                setProfiles(profiles)
                persistIdentity()
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthenticationRejectedError) {
                rejectAuthentication()
                return null
            } catch (e: Exception) {
                // Connectivity/server failures preserve a restored session. Only
                // an explicit HTTP 401/403 above is allowed to clear it.
                Log.e("[bosca-auth] Profile fetch failed during initialize (session preserved)", e)
                _currentUser.value?.let { return it }
                _status.value = AuthStatus.Unauthenticated
                return null
            }
        } else if (_currentUser.value == null) {
            _status.value = AuthStatus.Unauthenticated
        }

        return _currentUser.value
    }

    // -------------------------------------------------------------------------
    // Email/password + third-party
    // -------------------------------------------------------------------------

    override suspend fun signInWithPassword(identifier: String, password: String): AuthResponse {
        val response = graphql.loginWithPassword(identifier, password)
        handleAuthResponse(response)
        return response
    }

    override suspend fun signUp(options: SignupOptions): Principal {
        val resolved = options.copy(languageTag = options.languageTag ?: config.defaultLanguageTag)
        return graphql.signupWithPassword(resolved)
    }

    override suspend fun signInWithThirdParty(type: ThirdPartyType, token: String, languageTag: String?): AuthResponse {
        val response = graphql.signupThirdParty(type, token, languageTag ?: config.defaultLanguageTag)
        handleAuthResponse(response)
        return response
    }

    override suspend fun signInWithThirdPartyNative(provider: ThirdPartyProvider, languageTag: String?): AuthResponse {
        if (signInUsesRedirect) {
            // Web has no native provider SDK: fall back to the server-redirect
            // flow. The browser navigates away, so this call never resumes —
            // the sign-in completes on the next page load when initialize()
            // exchanges the token the backend appends to the redirect URL.
            signInWithRedirect(OAuthRedirectOptions(provider = provider.toThirdPartyType()))
            awaitCancellation()
        }
        val user = ThirdPartyAuthenticationProvider.login(provider)
        val providerToken = user.token ?: throw OAuthError("Third-party provider returned no token")
        return signInWithThirdParty(user.type, providerToken, languageTag)
    }

    override suspend fun signOut() {
        // Grab the current token without triggering a refresh — we're signing out.
        val currentToken = tokenManager.getToken()
        try {
            graphql.signOut(currentToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkError) {
            // Network failure must NOT auto-log-out: the user still has a valid
            // session and should be able to retry.
            throw e
        } catch (e: Exception) {
            // Any other failure (expired/invalid token, GraphQL auth error) means
            // the session is effectively dead — fall through and clear state.
            Log.e("[bosca-auth] signOut server call failed; clearing local state", e)
        }
        clearStateAndStorage()
        emit(AuthEvent.SignedOut)
    }

    // -------------------------------------------------------------------------
    // OAuth redirect (web)
    // -------------------------------------------------------------------------

    override fun signInWithRedirect(options: OAuthRedirectOptions) {
        startRedirect(config.apiUrl, options)
    }

    override suspend fun handleRedirectResult(): AuthResponse? {
        val exchangeToken = pendingExchangeToken ?: return null
        // Consume before exchanging: the token is single-use server-side, so a
        // failed exchange can't be retried anyway.
        pendingExchangeToken = null
        return exchange(exchangeToken)
    }

    override suspend fun exchange(token: String): AuthResponse {
        val response = graphql.exchangeToken(token)
        handleAuthResponse(response)
        return response
    }

    // -------------------------------------------------------------------------
    // Password / identifier / verification
    // -------------------------------------------------------------------------

    override suspend fun forgotPassword(identifier: String) = graphql.forgotPassword(identifier)

    override suspend fun resetPassword(token: String, password: String) = graphql.resetPassword(token, password)

    override suspend fun changePassword(newPassword: String, oldPassword: String) {
        val token = getToken() ?: throw UnauthenticatedError()
        authenticatedRequest { graphql.changePassword(token, newPassword, oldPassword) }
    }

    override suspend fun changeIdentifier(identifier: String, password: String) {
        val token = getToken() ?: throw UnauthenticatedError()
        authenticatedRequest { graphql.changeIdentifier(token, identifier, password) }
    }

    override suspend fun verifyEmail(token: String) = graphql.verifyEmail(token)

    override suspend fun resendVerification(identifier: String) = graphql.resendVerification(identifier)

    // -------------------------------------------------------------------------
    // Profiles / groups
    // -------------------------------------------------------------------------

    override suspend fun getProfiles(): List<Profile> {
        val token = getToken() ?: return emptyList()
        val profiles = authenticatedRequest { graphql.getCurrentProfiles(token) }
        setProfiles(profiles)
        persistIdentity()
        return profiles
    }

    override suspend fun updateProfile(id: Uuid?, input: ProfileInput): Profile {
        val token = getToken() ?: throw UnauthenticatedError()
        val profile = authenticatedRequest { graphql.updateProfile(token, id, input) }
        // Refresh all profiles to keep local state consistent.
        getProfiles()
        return profile
    }

    override suspend fun setPrimaryProfile(profileId: Uuid, principalId: Uuid?) {
        val token = getToken() ?: throw UnauthenticatedError()
        authenticatedRequest { graphql.setPrimaryProfile(token, profileId, principalId) }

        // Refresh principal + profiles only when targeting the current user; an
        // admin setting another user's primary profile shouldn't touch local state.
        // The mutation already succeeded — a failed refresh just logs and carries on.
        if (principalId == null) {
            try {
                setCurrentUser(authenticatedRequest { graphql.getCurrentPrincipal(token) })
                persistIdentity()
                getProfiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthenticationRejectedError) {
                throw e
            } catch (e: Exception) {
                Log.e("[bosca-auth] State refresh failed after setPrimaryProfile", e)
            }
        }
    }

    override suspend fun getGroups(): List<Group> {
        val token = getToken() ?: return emptyList()
        val groups = authenticatedRequest { graphql.getCurrentGroups(token) }
        _groups.value = groups
        return groups
    }

    // -------------------------------------------------------------------------
    // Token access
    // -------------------------------------------------------------------------

    override suspend fun refresh(): AuthResponse? = tokenManager.forceRefresh()

    override suspend fun getToken(): String? = tokenManager.getValidToken()

    override suspend fun getAuthHeaders(): Map<String, String> {
        val token = getToken() ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun destroy() {
        tokenManager.cancelRefresh()
        resetInMemoryAuthState()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private suspend fun handleAuthResponse(response: AuthResponse) {
        tokenManager.setTokens(response)
        setCurrentUser(response.principal)

        val included = response.profile
        if (!included.isNullOrEmpty()) {
            setProfiles(included)
        } else {
            try {
                setProfiles(authenticatedRequest { graphql.getCurrentProfiles(response.token.token) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthenticationRejectedError) {
                throw e
            } catch (e: Exception) {
                Log.e("[bosca-auth] Profile fetch failed after sign-in", e)
            }
        }
        persistIdentity()
        emit(AuthEvent.SignedIn(response))
    }

    private fun setCurrentUser(principal: Principal?) {
        _currentUser.value = principal
        _isAuthenticated.value = principal != null
        _status.value = if (principal != null) AuthStatus.Authenticated(principal) else AuthStatus.Unauthenticated
    }

    private fun setProfiles(profiles: List<Profile>, emitEvent: Boolean = true) {
        _profiles.value = profiles
        _currentProfile.value = profiles.firstOrNull { it.isPrimary } ?: profiles.firstOrNull()
        if (emitEvent) emit(AuthEvent.ProfileUpdated(profiles))
    }

    private fun restoreIdentity(identity: Identity) {
        setCurrentUser(identity.principal)
        setProfiles(identity.profiles, emitEvent = false)
    }

    private suspend fun persistIdentity() {
        val principal = _currentUser.value ?: return
        try {
            tokenManager.setIdentity(Identity(principal, _profiles.value))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A storage failure must not invalidate a successful server login.
            Log.e("[bosca-auth] Failed to persist identity", e)
        }
    }

    private suspend fun <T> authenticatedRequest(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuthenticationRejectedError) {
        rejectAuthentication()
        throw e
    }

    private suspend fun rejectAuthentication() {
        val shouldEmitSignedOut = _status.value !is AuthStatus.Unauthenticated
        clearStateAndStorage()
        if (shouldEmitSignedOut) emit(AuthEvent.SignedOut)
    }

    /** Drops in-memory auth state WITHOUT wiping storage (transient sign-out). */
    private fun resetInMemoryAuthState() {
        _currentUser.value = null
        _currentProfile.value = null
        _profiles.value = emptyList()
        _groups.value = emptyList()
        _isAuthenticated.value = false
        _status.value = AuthStatus.Unauthenticated
    }

    /** Destructive sign-out: wipes storage AND in-memory state. */
    private suspend fun clearStateAndStorage() {
        tokenManager.clear()
        resetInMemoryAuthState()
    }

    private fun handleTokenManagerEvent(event: AuthEvent) {
        // A token-manager SignedOut comes from a failed refresh: clear in-memory
        // state but DO NOT wipe storage (the refresh token may still be valid).
        if (event is AuthEvent.SignedOut) {
            resetInMemoryAuthState()
        }
        emit(event)
    }

    private fun emit(event: AuthEvent) {
        _events.tryEmit(event)
    }
}
