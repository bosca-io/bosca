package bosca.core.security

import bosca.core.security.type.ProfileType
import bosca.core.security.type.ProfileVisibility
import bosca.core.security.type.ThirdPartyType
import bosca.core.security.model.AuthEvent
import bosca.core.security.model.AuthResponse
import bosca.core.security.model.AuthStatus
import bosca.core.security.model.BoscaAuthConfig
import bosca.core.security.model.BoscaToken
import bosca.core.security.model.Group
import bosca.core.security.model.OAuthRedirectOptions
import bosca.core.security.model.Principal
import bosca.core.security.model.Profile
import bosca.core.security.model.ProfileInput
import bosca.core.security.model.SignupOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Kotlin port of client.spec.ts. State is asserted via the StateFlows' `.value`; events via a collector. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoscaAuthTest {

    private val principalId: Uuid = Uuid.fromLongs(0L, 1L)
    private val profileId: Uuid = Uuid.fromLongs(0L, 10L)

    private fun TestScope.config(autoRefresh: Boolean = false, defaultLanguageTag: String? = null) =
        BoscaAuthConfig(apiUrl = "https://api.test", autoRefresh = autoRefresh, defaultLanguageTag = defaultLanguageTag)

    private fun TestScope.newAuth(
        graphql: FakeAuthGraphql,
        storage: FakeTokenStorage = FakeTokenStorage(),
        autoRefresh: Boolean = false,
        defaultLanguageTag: String? = null,
        startRedirect: (String, OAuthRedirectOptions) -> Unit = { _, _ -> },
        readExchangeToken: () -> String? = { null },
        signInUsesRedirect: Boolean = false,
        identityStorage: IdentityStorage = storage,
    ): BoscaAuthImpl = BoscaAuthImpl(
        storage = storage,
        identityStorage = identityStorage,
        graphql = graphql,
        config = config(autoRefresh, defaultLanguageTag),
        scope = backgroundScope,
        now = { testScheduler.currentTime },
        startRedirect = startRedirect,
        readExchangeToken = readExchangeToken,
        signInUsesRedirect = signInUsesRedirect,
    )

    private fun TestScope.collectEvents(auth: BoscaAuth): MutableList<AuthEvent> {
        val events = mutableListOf<AuthEvent>()
        backgroundScope.launch { auth.events.collect { events.add(it) } }
        runCurrent()
        return events
    }

    private fun TestScope.makeToken(expiresInSeconds: Int = 3600): BoscaToken {
        val nowSec = (testScheduler.currentTime / 1000L).toInt()
        return BoscaToken(token = "jwt-$expiresInSeconds", expiresAt = nowSec + expiresInSeconds, issuedAt = nowSec)
    }

    private fun makeProfile(
        id: Uuid = profileId,
        name: String = "Test User",
        isPrimary: Boolean = true,
    ): Profile = Profile(
        id = id,
        name = name,
        type = ProfileType.GENERIC,
        visibility = ProfileVisibility.USER,
        slug = "test-user",
        isPrimary = isPrimary,
        attributes = emptyList(),
    )

    private fun TestScope.makeAuthResponse(
        expiresInSeconds: Int = 3600,
        profile: List<Profile>? = listOf(makeProfile()),
        refreshToken: String? = "refresh-token",
    ): AuthResponse = AuthResponse(
        principal = Principal(id = principalId, verified = true, primaryProfileId = profileId),
        profile = profile,
        token = makeToken(expiresInSeconds),
        refreshToken = refreshToken,
    )

    // --- initial state --------------------------------------------------------

    @Test
    fun initialState_isUnauthenticated() = runTest {
        val auth = newAuth(FakeAuthGraphql())
        assertFalse(auth.isAuthenticated.value)
        assertNull(auth.currentUser.value)
        assertNull(auth.currentProfile.value)
        assertNull(auth.token)
        assertTrue(auth.profiles.value.isEmpty())
        assertTrue(auth.groups.value.isEmpty())
    }

    // --- signInWithPassword ---------------------------------------------------

    @Test
    fun signInWithPassword_authenticatesAndStoresState() = runTest {
        val graphql = FakeAuthGraphql()
        val response = makeAuthResponse()
        graphql.loginWithPasswordHandler = { _, _ -> response }
        val auth = newAuth(graphql)

        val result = auth.signInWithPassword("user@test.com", "password")

        assertEquals(principalId, result.principal.id)
        assertTrue(auth.isAuthenticated.value)
        assertEquals(response.principal, auth.currentUser.value)
        assertEquals("Test User", auth.currentProfile.value?.name)
        assertEquals(response.token.token, auth.token)
    }

    @Test
    fun signInWithPassword_emitsSignedIn() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        val auth = newAuth(graphql)
        val events = collectEvents(auth)

        auth.signInWithPassword("u", "p")
        runCurrent()

        assertTrue(events.any { it is AuthEvent.SignedIn })
    }

    @Test
    fun signInWithPassword_fetchesProfilesWhenNotIncluded() = runTest {
        val graphql = FakeAuthGraphql()
        graphql.loginWithPasswordHandler = { _, _ -> makeAuthResponse(profile = null) }
        var fetched = 0
        graphql.getCurrentProfilesHandler = { fetched++; listOf(makeProfile()) }
        val auth = newAuth(graphql)

        auth.signInWithPassword("u", "p")

        assertEquals(1, fetched)
        assertEquals("Test User", auth.currentProfile.value?.name)
    }

    @Test
    fun signInWithPassword_propagatesLoginErrors() = runTest {
        val graphql = FakeAuthGraphql().apply {
            loginWithPasswordHandler = { _, _ -> throw InvalidCredentialsError() }
        }
        val auth = newAuth(graphql)

        assertFailsWith<InvalidCredentialsError> { auth.signInWithPassword("u", "wrong") }
        assertFalse(auth.isAuthenticated.value)
    }

    // --- signUp ---------------------------------------------------------------

    @Test
    fun signUp_callsSignupAndReturnsPrincipalWithoutSigningIn() = runTest {
        val graphql = FakeAuthGraphql().apply {
            signupWithPasswordHandler = { Principal(id = Uuid.fromLongs(0, 99), verified = false, primaryProfileId = null) }
        }
        val auth = newAuth(graphql)

        val result = auth.signUp(SignupOptions("new@test.com", "pass", ProfileInput("New", visibility = ProfileVisibility.USER)))

        assertFalse(result.verified)
        assertFalse(auth.isAuthenticated.value)
    }

    @Test
    fun signUp_usesExplicitLanguageTag() = runTest {
        var captured: String? = "unset"
        val graphql = FakeAuthGraphql().apply {
            signupWithPasswordHandler = { captured = it.languageTag; Principal(Uuid.fromLongs(0, 99), false, null) }
        }
        val auth = newAuth(graphql, defaultLanguageTag = "en-US")

        auth.signUp(SignupOptions("new@test.com", "pass", ProfileInput("New", visibility = ProfileVisibility.USER), languageTag = "de-DE"))

        assertEquals("de-DE", captured)
    }

    @Test
    fun signUp_fallsBackToConfigDefaultLanguageTag() = runTest {
        var captured: String? = "unset"
        val graphql = FakeAuthGraphql().apply {
            signupWithPasswordHandler = { captured = it.languageTag; Principal(Uuid.fromLongs(0, 99), false, null) }
        }
        val auth = newAuth(graphql, defaultLanguageTag = "ja-JP")

        auth.signUp(SignupOptions("new@test.com", "pass", ProfileInput("New", visibility = ProfileVisibility.USER)))

        assertEquals("ja-JP", captured)
    }

    // --- signOut --------------------------------------------------------------

    @Test
    fun signOut_callsServerWithTokenThenClearsAndEmits() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        var signOutToken: String? = "unset"
        graphql.signOutHandler = { signOutToken = it }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")
        val tokenBeforeSignOut = auth.token
        val events = collectEvents(auth)

        auth.signOut()
        runCurrent()

        assertEquals(tokenBeforeSignOut, signOutToken)
        assertFalse(auth.isAuthenticated.value)
        assertNull(auth.currentUser.value)
        assertNull(auth.currentProfile.value)
        assertNull(auth.token)
        assertTrue(events.any { it is AuthEvent.SignedOut })
    }

    @Test
    fun signOut_networkError_preservesSessionAndRethrows() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        graphql.signOutHandler = { throw NetworkError("offline") }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")
        val events = collectEvents(auth)

        assertFailsWith<NetworkError> { auth.signOut() }
        runCurrent()

        assertTrue(auth.isAuthenticated.value)
        assertTrue(auth.token != null)
        assertFalse(events.any { it is AuthEvent.SignedOut })
    }

    @Test
    fun signOut_tokenError_clearsAndEmits() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        graphql.signOutHandler = { throw TokenExpiredError() }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")
        val events = collectEvents(auth)

        auth.signOut()
        runCurrent()

        assertFalse(auth.isAuthenticated.value)
        assertNull(auth.token)
        assertTrue(events.any { it is AuthEvent.SignedOut })
    }

    // --- OAuth redirect -------------------------------------------------------

    @Test
    fun signInWithRedirect_delegatesToStartRedirect() = runTest {
        var capturedUrl: String? = null
        var capturedOptions: OAuthRedirectOptions? = null
        val auth = newAuth(FakeAuthGraphql(), startRedirect = { url, opts -> capturedUrl = url; capturedOptions = opts })

        auth.signInWithRedirect(OAuthRedirectOptions(provider = ThirdPartyType.GOOGLE, redirectUrl = "https://app.test/"))

        assertEquals("https://api.test", capturedUrl)
        assertEquals(ThirdPartyType.GOOGLE, capturedOptions?.provider)
    }

    @Test
    fun handleRedirectResult_returnsNullWhenNoExchangeToken() = runTest {
        val auth = newAuth(FakeAuthGraphql(), readExchangeToken = { null })
        assertNull(auth.handleRedirectResult())
        assertFalse(auth.isAuthenticated.value)
    }

    @Test
    fun handleRedirectResult_exchangesTokenAndSignsIn() = runTest {
        val graphql = FakeAuthGraphql()
        var exchanged: String? = null
        graphql.exchangeTokenHandler = { exchanged = it; makeAuthResponse() }
        val auth = newAuth(graphql, readExchangeToken = { "exchange-token-abc" })

        val result = auth.handleRedirectResult()

        assertEquals(principalId, result?.principal?.id)
        assertTrue(auth.isAuthenticated.value)
        assertEquals("exchange-token-abc", exchanged)
    }

    @Test
    fun handleRedirectResult_consumesExchangeTokenOnce() = runTest {
        // The token is captured at construction and single-use: a second call
        // (e.g. a re-initialize) must not replay the exchange.
        val graphql = FakeAuthGraphql()
        var exchanges = 0
        graphql.exchangeTokenHandler = { exchanges++; makeAuthResponse() }
        val auth = newAuth(graphql, readExchangeToken = { "exchange-token-abc" })

        assertEquals(principalId, auth.handleRedirectResult()?.principal?.id)
        assertNull(auth.handleRedirectResult())
        assertEquals(1, exchanges)
    }

    @Test
    fun handleRedirectResult_propagatesExchangeErrors() = runTest {
        val graphql = FakeAuthGraphql().apply { exchangeTokenHandler = { throw GraphQLAuthError("Invalid exchange token") } }
        val auth = newAuth(graphql, readExchangeToken = { "bad-token" })
        assertFailsWith<GraphQLAuthError> { auth.handleRedirectResult() }
    }

    @Test
    fun signInWithThirdPartyNative_onRedirectPlatform_startsRedirectAndNeverResumes() = runTest {
        var capturedOptions: OAuthRedirectOptions? = null
        val auth = newAuth(
            FakeAuthGraphql(),
            startRedirect = { _, opts -> capturedOptions = opts },
            signInUsesRedirect = true,
        )

        val job = launch { auth.signInWithThirdPartyNative(ThirdPartyProvider.GOOGLE) }
        runCurrent()

        assertEquals(ThirdPartyType.GOOGLE, capturedOptions?.provider)
        // The browser is navigating away — the call must stay suspended rather
        // than resume with a result (or hit the native authenticator).
        assertTrue(job.isActive)
        job.cancel()
    }

    @Test
    fun thirdPartyProvider_mapsToWireType() {
        assertEquals(ThirdPartyType.GOOGLE, ThirdPartyProvider.GOOGLE.toThirdPartyType())
        assertEquals(ThirdPartyType.FACEBOOK, ThirdPartyProvider.FACEBOOK.toThirdPartyType())
        assertEquals(ThirdPartyType.APPLE, ThirdPartyProvider.APPLE.toThirdPartyType())
    }

    @Test
    fun exchange_signsInAndPersistsSessionToStorage() = runTest {
        // The path the CLI's loopback OAuth flow uses: it captures the
        // exchangeToken from the browser redirect and calls exchange() directly
        // (handleRedirectResult is web-URL-only). The session must persist so a
        // later CLI command finds it in config.json.
        val storage = FakeTokenStorage()
        val graphql = FakeAuthGraphql()
        var exchanged: String? = null
        graphql.exchangeTokenHandler = { exchanged = it; makeAuthResponse() }
        val auth = newAuth(graphql, storage = storage)

        val result = auth.exchange("cli-loopback-token")

        assertEquals("cli-loopback-token", exchanged)
        assertEquals(principalId, result.principal.id)
        assertTrue(auth.isAuthenticated.value)
        assertEquals("jwt-3600", storage.getToken())
        assertEquals("refresh-token", storage.getRefreshToken())
    }

    // --- password management --------------------------------------------------

    @Test
    fun changePassword_delegatesWithCurrentToken() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        var captured: Triple<String, String, String>? = null
        graphql.changePasswordHandler = { t, n, o -> captured = Triple(t, n, o) }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")

        auth.changePassword("newpass", "oldpass")

        assertEquals("newpass", captured?.second)
        assertEquals("oldpass", captured?.third)
        assertTrue(!captured?.first.isNullOrBlank())
    }

    @Test
    fun changePassword_throwsUnauthenticatedWhenNotSignedIn() = runTest {
        val auth = newAuth(FakeAuthGraphql())
        assertFailsWith<UnauthenticatedError> { auth.changePassword("n", "o") }
    }

    @Test
    fun changeIdentifier_delegatesWithCurrentToken() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        var captured: Triple<String, String, String>? = null
        graphql.changeIdentifierHandler = { t, i, p -> captured = Triple(t, i, p) }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")

        auth.changeIdentifier("new@test.com", "pw")

        assertEquals("new@test.com", captured?.second)
        assertEquals("pw", captured?.third)
    }

    @Test
    fun changeIdentifier_throwsUnauthenticatedWhenNotSignedIn() = runTest {
        val auth = newAuth(FakeAuthGraphql())
        assertFailsWith<UnauthenticatedError> { auth.changeIdentifier("new@test.com", "pw") }
    }

    // --- setPrimaryProfile ----------------------------------------------------

    @Test
    fun setPrimaryProfile_delegatesAndRefreshesForSelf() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        var capturedPrincipalId: Uuid? = profileId // sentinel
        graphql.setPrimaryProfileHandler = { _, _, pid -> capturedPrincipalId = pid }
        var principalFetched = 0
        var profilesFetched = 0
        graphql.getCurrentPrincipalHandler = { principalFetched++; Principal(principalId, true, profileId) }
        graphql.getCurrentProfilesHandler = { profilesFetched++; listOf(makeProfile()) }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")

        auth.setPrimaryProfile(profileId)

        assertNull(capturedPrincipalId)
        assertEquals(1, principalFetched)
        assertEquals(1, profilesFetched)
    }

    @Test
    fun setPrimaryProfile_doesNotRefreshWhenTargetingAnotherPrincipal() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        graphql.setPrimaryProfileHandler = { _, _, _ -> }
        var principalFetched = 0
        graphql.getCurrentPrincipalHandler = { principalFetched++; Principal(principalId, true, profileId) }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")

        auth.setPrimaryProfile(profileId, Uuid.fromLongs(0, 77))

        assertEquals(0, principalFetched)
    }

    @Test
    fun setPrimaryProfile_swallowsRefreshErrorsAfterSuccessfulMutation() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        graphql.setPrimaryProfileHandler = { _, _, _ -> }
        graphql.getCurrentPrincipalHandler = { throw NetworkError("blip") }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")

        // Mutation succeeded; the refresh failure must not reject the call.
        auth.setPrimaryProfile(profileId)
    }

    @Test
    fun setPrimaryProfile_throwsUnauthenticatedWhenNotSignedIn() = runTest {
        val auth = newAuth(FakeAuthGraphql())
        assertFailsWith<UnauthenticatedError> { auth.setPrimaryProfile(profileId) }
    }

    // --- profiles / groups ----------------------------------------------------

    @Test
    fun getProfiles_fetchesUpdatesAndEmits() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        graphql.getCurrentProfilesHandler = {
            listOf(makeProfile(), makeProfile(id = Uuid.fromLongs(0, 11), name = "Second", isPrimary = false))
        }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")
        val events = collectEvents(auth)

        val result = auth.getProfiles()
        runCurrent()

        assertEquals(2, result.size)
        assertEquals(profileId, auth.currentProfile.value?.id)
        assertEquals(2, auth.profiles.value.size)
        assertTrue(events.any { it is AuthEvent.ProfileUpdated })
    }

    @Test
    fun getProfiles_returnsEmptyWhenNotAuthenticated() = runTest {
        assertTrue(newAuth(FakeAuthGraphql()).getProfiles().isEmpty())
    }

    @Test
    fun updateProfile_updatesAndRefreshes() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        val updated = makeProfile(name = "Updated Name")
        var editCalled = 0
        var profilesRefreshed = 0
        graphql.updateProfileHandler = { _, _, _ -> editCalled++; updated }
        graphql.getCurrentProfilesHandler = { profilesRefreshed++; listOf(updated) }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")

        val result = auth.updateProfile(profileId, ProfileInput("Updated Name", visibility = ProfileVisibility.USER))

        assertEquals("Updated Name", result.name)
        assertEquals(1, editCalled)
        assertEquals(1, profilesRefreshed)
    }

    @Test
    fun updateProfile_throwsUnauthenticatedWhenNotSignedIn() = runTest {
        val auth = newAuth(FakeAuthGraphql())
        assertFailsWith<UnauthenticatedError> {
            auth.updateProfile(profileId, ProfileInput("Test", visibility = ProfileVisibility.USER))
        }
    }

    @Test
    fun getGroups_fetchesAndCaches() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        val groups = listOf(
            Group(Uuid.fromLongs(0, 1), "admins", "System administrators", bosca.core.security.type.GroupType.SYSTEM),
            Group(Uuid.fromLongs(0, 2), "editors", "Content editors", bosca.core.security.type.GroupType.PRINCIPAL),
        )
        graphql.getCurrentGroupsHandler = { groups }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")

        val result = auth.getGroups()

        assertEquals(groups, result)
        assertEquals(groups, auth.groups.value)
    }

    @Test
    fun getGroups_returnsEmptyWhenNotAuthenticated() = runTest {
        assertTrue(newAuth(FakeAuthGraphql()).getGroups().isEmpty())
    }

    @Test
    fun getGroups_clearedOnSignOut() = runTest {
        val graphql = FakeAuthGraphql().apply {
            loginWithPasswordHandler = { _, _ -> makeAuthResponse() }
            signOutHandler = { }
            getCurrentGroupsHandler = { listOf(Group(Uuid.fromLongs(0, 1), "admins", "Admins", bosca.core.security.type.GroupType.SYSTEM)) }
        }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")
        auth.getGroups()
        assertEquals(1, auth.groups.value.size)

        auth.signOut()
        assertTrue(auth.groups.value.isEmpty())
    }

    // --- token access ---------------------------------------------------------

    @Test
    fun getToken_returnsValidTokenWhenAuthenticated() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")
        assertTrue(auth.getToken() != null)
    }

    @Test
    fun getToken_returnsNullWhenNotAuthenticated() = runTest {
        assertNull(newAuth(FakeAuthGraphql()).getToken())
    }

    @Test
    fun getAuthHeaders_returnsBearerWhenAuthenticated() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")

        val headers = auth.getAuthHeaders()
        assertTrue(headers["Authorization"]?.startsWith("Bearer ") == true)
    }

    @Test
    fun getAuthHeaders_emptyWhenNotAuthenticated() = runTest {
        assertTrue(newAuth(FakeAuthGraphql()).getAuthHeaders().isEmpty())
    }

    // --- currentUser flow (replaces onAuthStateChanged) -----------------------

    @Test
    fun currentUser_emitsPrincipalOnSignInAndNullOnSignOut() = runTest {
        val graphql = FakeAuthGraphql().apply {
            loginWithPasswordHandler = { _, _ -> makeAuthResponse() }
            signOutHandler = { }
        }
        val auth = newAuth(graphql)

        auth.signInWithPassword("u", "p")
        assertEquals(principalId, auth.currentUser.value?.id)

        auth.signOut()
        assertNull(auth.currentUser.value)
    }

    // --- initialize -----------------------------------------------------------

    @Test
    fun initialize_returnsNullWhenNoStoredSession() = runTest {
        val auth = newAuth(FakeAuthGraphql())
        assertNull(auth.initialize())
        assertFalse(auth.isAuthenticated.value)
    }

    @Test
    fun initialize_completesOAuthRedirectWhenNoStoredSession() = runTest {
        // Returning from a web OAuth redirect on a fresh browser session: no
        // stored tokens, but the URL carries the single-use exchange token.
        val graphql = FakeAuthGraphql()
        var exchanged: String? = null
        graphql.exchangeTokenHandler = { exchanged = it; makeAuthResponse() }
        val auth = newAuth(graphql, readExchangeToken = { "redirect-exchange-token" })

        val result = auth.initialize()

        assertEquals("redirect-exchange-token", exchanged)
        assertEquals(principalId, result?.id)
        assertTrue(auth.isAuthenticated.value)
        assertEquals("Test User", auth.currentProfile.value?.name)
    }

    @Test
    fun initialize_resolvesUnauthenticatedWhenRedirectExchangeFails() = runTest {
        // An expired/replayed exchange token must not brick startup: status must
        // resolve to Unauthenticated so the user can retry the sign-in.
        val graphql = FakeAuthGraphql().apply { exchangeTokenHandler = { throw GraphQLAuthError("expired") } }
        val auth = newAuth(graphql, readExchangeToken = { "stale-token" })

        assertNull(auth.initialize())

        assertFalse(auth.isAuthenticated.value)
        assertEquals(AuthStatus.Unauthenticated, auth.status.value)
    }

    @Test
    fun initialize_prefersStoredSessionOverExchangeToken() = runTest {
        // A live session wins: a leftover exchange token in the URL must not be
        // exchanged (it would replace the session the user already has).
        val storage = FakeTokenStorage()
        val token = makeToken(3600)
        storage.saveToken(token.token)
        storage.saveTokenMetadata(bosca.core.security.model.TokenMetadata(token.expiresAt, token.issuedAt))
        val graphql = FakeAuthGraphql().apply {
            getCurrentPrincipalHandler = { Principal(principalId, true, profileId) }
            getCurrentProfilesHandler = { listOf(makeProfile()) }
            exchangeTokenHandler = { error("exchange token must not be exchanged when a session restores") }
        }
        val auth = newAuth(graphql, storage = storage, readExchangeToken = { "leftover-token" })

        val result = auth.initialize()

        assertEquals(principalId, result?.id)
        assertEquals(token.token, auth.token)
    }

    @Test
    fun initialize_restoresSessionAndFetchesPrincipalAndProfile() = runTest {
        val storage = FakeTokenStorage()
        val token = makeToken(3600)
        storage.saveToken(token.token)
        storage.saveRefreshToken("stored-refresh")
        storage.saveTokenMetadata(bosca.core.security.model.TokenMetadata(token.expiresAt, token.issuedAt))
        val graphql = FakeAuthGraphql().apply {
            getCurrentPrincipalHandler = { Principal(principalId, true, profileId) }
            getCurrentProfilesHandler = { listOf(makeProfile()) }
        }
        val auth = newAuth(graphql, storage = storage)

        val result = auth.initialize()

        assertEquals(principalId, result?.id)
        assertTrue(auth.isAuthenticated.value)
        assertEquals("Test User", auth.currentProfile.value?.name)
        assertEquals(token.token, auth.token)
        assertEquals(principalId, storage.getIdentity()?.principal?.id)
        assertEquals("Test User", storage.getIdentity()?.profiles?.single()?.name)
    }

    @Test
    fun initialize_offlineRestoresPersistedPrincipalAndProfile() = runTest {
        val storage = FakeTokenStorage()
        val response = makeAuthResponse()
        val online = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> response } }
        newAuth(online, storage = storage).signInWithPassword("u", "p")

        val offline = FakeAuthGraphql().apply {
            getCurrentPrincipalHandler = { throw NetworkError("offline") }
        }
        val restored = newAuth(offline, storage = storage)

        val result = restored.initialize()

        assertEquals(principalId, result?.id)
        assertTrue(restored.isAuthenticated.value)
        assertEquals(AuthStatus.Authenticated(response.principal), restored.status.value)
        assertEquals("Test User", restored.currentProfile.value?.name)
    }

    @Test
    fun initialize_offlineRestoresIdentityWhenAccessTokenNeedsRefresh() = runTest {
        val storage = FakeTokenStorage()
        val response = makeAuthResponse(expiresInSeconds = -1)
        val online = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> response } }
        newAuth(online, storage = storage).signInWithPassword("u", "p")

        val offline = FakeAuthGraphql().apply {
            refreshDefault = { throw NetworkError("offline") }
        }
        val restored = newAuth(offline, storage = storage)

        val result = restored.initialize()

        assertEquals(principalId, result?.id)
        assertTrue(restored.isAuthenticated.value)
        assertEquals("Test User", restored.currentProfile.value?.name)
    }

    @Test
    fun initialize_networkFailurePreservesExistingInMemorySessionWithoutStoredIdentity() = runTest {
        val storage = FakeTokenStorage()
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        val auth = newAuth(graphql, storage = storage)
        auth.signInWithPassword("u", "p")
        storage.store.remove("identity")
        graphql.getCurrentPrincipalHandler = { throw NetworkError("offline") }

        val result = auth.initialize()

        assertEquals(principalId, result?.id)
        assertTrue(auth.isAuthenticated.value)
        assertEquals("Test User", auth.currentProfile.value?.name)
    }

    @Test
    fun initialize_httpAuthenticationRejectionClearsPersistedSession() = runTest {
        val storage = FakeTokenStorage()
        val online = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        newAuth(online, storage = storage).signInWithPassword("u", "p")

        val rejected = FakeAuthGraphql().apply {
            getCurrentPrincipalHandler = { throw AuthenticationRejectedError(401) }
        }
        val restored = newAuth(rejected, storage = storage)

        assertNull(restored.initialize())
        assertFalse(restored.isAuthenticated.value)
        assertEquals(AuthStatus.Unauthenticated, restored.status.value)
        assertNull(storage.getToken())
        assertNull(storage.getRefreshToken())
        assertNull(storage.getIdentity())
    }

    @Test
    fun initialize_returnsNullButPreservesStorageWhenProfileFetchFails() = runTest {
        val storage = FakeTokenStorage()
        val token = makeToken(3600)
        storage.saveToken(token.token)
        storage.saveTokenMetadata(bosca.core.security.model.TokenMetadata(token.expiresAt, token.issuedAt))
        storage.saveRefreshToken("still-valid-refresh-token")
        val graphql = FakeAuthGraphql().apply { getCurrentPrincipalHandler = { throw NetworkError("Unauthorized") } }
        val auth = newAuth(graphql, storage = storage)

        assertNull(auth.initialize())
        assertFalse(auth.isAuthenticated.value)
        assertEquals(token.token, storage.getToken())
        assertEquals("still-valid-refresh-token", storage.getRefreshToken())
    }

    @Test
    fun initialize_fetchProfileFalse_skipsFetch() = runTest {
        val storage = FakeTokenStorage()
        val token = makeToken(3600)
        storage.saveToken(token.token)
        storage.saveTokenMetadata(bosca.core.security.model.TokenMetadata(token.expiresAt, token.issuedAt))
        var principalFetched = 0
        val graphql = FakeAuthGraphql().apply { getCurrentPrincipalHandler = { principalFetched++; Principal(principalId, true, null) } }
        val auth = newAuth(graphql, storage = storage)

        auth.initialize(fetchProfile = false)

        assertEquals(0, principalFetched)
        assertEquals(token.token, auth.token)
    }

    // --- profile selection ----------------------------------------------------

    @Test
    fun profileSelection_selectsPrimaryFromMultiple() = runTest {
        val profiles = listOf(
            makeProfile(id = Uuid.fromLongs(0, 11), name = "Secondary", isPrimary = false),
            makeProfile(id = profileId, name = "Primary", isPrimary = true),
        )
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse(profile = profiles) } }
        val auth = newAuth(graphql)

        auth.signInWithPassword("u", "p")

        assertEquals(2, auth.profiles.value.size)
        assertEquals(profileId, auth.currentProfile.value?.id)
        assertEquals("Primary", auth.currentProfile.value?.name)
    }

    @Test
    fun profileSelection_fallsBackToFirstWhenNonePrimary() = runTest {
        val first = makeProfile(id = Uuid.fromLongs(0, 21), name = "First", isPrimary = false)
        val profiles = listOf(first, makeProfile(id = Uuid.fromLongs(0, 22), name = "Second", isPrimary = false))
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse(profile = profiles) } }
        val auth = newAuth(graphql)

        auth.signInWithPassword("u", "p")

        assertEquals(first.id, auth.currentProfile.value?.id)
    }

    // --- destroy & edge cases -------------------------------------------------

    @Test
    fun destroy_clearsState() = runTest {
        val graphql = FakeAuthGraphql().apply { loginWithPasswordHandler = { _, _ -> makeAuthResponse() } }
        val auth = newAuth(graphql)
        auth.signInWithPassword("u", "p")

        auth.destroy()

        assertNull(auth.currentUser.value)
        assertNull(auth.currentProfile.value)
        assertTrue(auth.profiles.value.isEmpty())
    }

    @Test
    fun signIn_withNullProfilesAndFetchFailure_stillAuthenticates() = runTest {
        val graphql = FakeAuthGraphql().apply {
            loginWithPasswordHandler = { _, _ -> makeAuthResponse(profile = null) }
            getCurrentProfilesHandler = { throw NetworkError("Profile service down") }
        }
        val auth = newAuth(graphql)

        auth.signInWithPassword("u", "p")

        assertEquals(principalId, auth.currentUser.value?.id)
        assertNull(auth.currentProfile.value)
    }

    @Test
    fun signIn_withEmptyProfiles_resultsInNullCurrentProfile() = runTest {
        val graphql = FakeAuthGraphql().apply {
            loginWithPasswordHandler = { _, _ -> makeAuthResponse(profile = emptyList()) }
            getCurrentProfilesHandler = { emptyList() }
        }
        val auth = newAuth(graphql)

        auth.signInWithPassword("u", "p")

        assertNull(auth.currentProfile.value)
        assertTrue(auth.profiles.value.isEmpty())
    }
}
