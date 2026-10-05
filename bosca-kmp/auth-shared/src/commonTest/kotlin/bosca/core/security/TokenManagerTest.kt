package bosca.core.security

import bosca.core.security.model.AuthEvent
import bosca.core.security.model.AuthResponse
import bosca.core.security.model.Identity
import bosca.core.security.model.BoscaAuthConfig
import bosca.core.security.model.BoscaToken
import bosca.core.security.model.Principal
import bosca.core.security.model.TokenMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Kotlin port of token_manager.spec.ts. Uses [runTest] virtual time; the
 * manager's `now` is wired to the test scheduler so expiry checks and the
 * refresh timer share one clock. The refresh timer and refresh coalescing run
 * on [TestScope.backgroundScope] so a parked long-lived timer never hangs the
 * test (the TS analogue is afterEach `manager.destroy()`).
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class TokenManagerTest {

    private val testPrincipalId: Uuid = Uuid.fromLongs(0L, 1L)

    private fun TestScope.config(autoRefresh: Boolean = true) = BoscaAuthConfig(
        apiUrl = "https://api.test",
        refreshBufferMillis = 60_000L,
        autoRefresh = autoRefresh,
        retryDelayMillis = 1_000L,
    )

    private fun TestScope.newManager(
        storage: FakeTokenStorage,
        graphql: AuthGraphql,
        events: MutableList<AuthEvent>,
        autoRefresh: Boolean = true,
        identityStorage: IdentityStorage = storage,
    ): TokenManager = TokenManager(
        storage = storage,
        identityStorage = identityStorage,
        graphql = graphql,
        config = config(autoRefresh),
        scope = backgroundScope,
        onEvent = { events.add(it) },
        now = { testScheduler.currentTime },
    )

    private fun TestScope.nowSec(): Int = (testScheduler.currentTime / 1000L).toInt()

    private fun TestScope.makeToken(expiresInSeconds: Int, value: String = "jwt-$expiresInSeconds"): BoscaToken =
        BoscaToken(token = value, expiresAt = nowSec() + expiresInSeconds, issuedAt = nowSec())

    private fun TestScope.makeExpiredToken(): BoscaToken =
        BoscaToken(token = "jwt-expired", expiresAt = nowSec() - 100, issuedAt = nowSec() - 200)

    private fun TestScope.makeAuthResponse(
        expiresInSeconds: Int,
        refreshToken: String? = "new-refresh",
        token: BoscaToken = makeToken(expiresInSeconds),
    ): AuthResponse = AuthResponse(
        principal = Principal(id = testPrincipalId, verified = true, primaryProfileId = null),
        profile = null,
        token = token,
        refreshToken = refreshToken,
    )

    private fun makeJwt(exp: Int, iat: Int): String {
        val payload = """{"exp":$exp,"iat":$iat}"""
        val encoded = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(payload.encodeToByteArray())
        return "header.$encoded.signature"
    }

    // --- setTokens ------------------------------------------------------------

    @Test
    fun setTokens_storesInMemoryAndStorage() = runTest {
        val storage = FakeTokenStorage()
        val manager = newManager(storage, FakeAuthGraphql(), mutableListOf())
        val response = makeAuthResponse(3600)

        manager.setTokens(response)

        assertEquals(response.token.token, manager.getToken())
        assertEquals(response.token.token, storage.getToken())
        assertEquals("new-refresh", storage.getRefreshToken())
        assertEquals(TokenMetadata(response.token.expiresAt, response.token.issuedAt), storage.getTokenMetadata())
    }

    @Test
    fun setTokens_handlesNullRefreshToken() = runTest {
        val storage = FakeTokenStorage()
        val manager = newManager(storage, FakeAuthGraphql(), mutableListOf())

        manager.setTokens(makeAuthResponse(3600, refreshToken = null))

        assertNotNull(manager.getToken())
        assertNull(storage.getRefreshToken())
    }

    // --- restore --------------------------------------------------------------

    @Test
    fun restore_restoresTokensFromStorage() = runTest {
        val storage = FakeTokenStorage()
        val token = makeToken(3600)
        storage.saveToken(token.token)
        storage.saveTokenMetadata(TokenMetadata(token.expiresAt, token.issuedAt))
        storage.saveRefreshToken("stored-refresh")
        val manager = newManager(storage, FakeAuthGraphql(), mutableListOf())

        assertEquals(token.token, manager.restore())
        assertEquals(token.token, manager.getToken())
    }

    @Test
    fun restore_returnsNullWhenStorageEmpty() = runTest {
        val manager = newManager(FakeTokenStorage(), FakeAuthGraphql(), mutableListOf())
        assertNull(manager.restore())
    }

    // NOTE: a stored non-JWT token with no refresh token is no longer "no
    // session" — it is treated as an opaque API token. See
    // restore_opaqueApiToken_isNonExpiringAndNeverRefreshed below.

    @Test
    fun restore_extractsMetadataFromBareJwt() = runTest {
        val storage = FakeTokenStorage()
        val jwt = makeJwt(exp = nowSec() + 3600, iat = nowSec())
        storage.saveToken(jwt)
        val manager = newManager(storage, FakeAuthGraphql(), mutableListOf())

        assertEquals(jwt, manager.restore())
        assertEquals(jwt, manager.getToken())
        assertFalse(manager.isExpired())
    }

    @Test
    fun restore_treatsExpiredBareJwtAsExpired() = runTest {
        val storage = FakeTokenStorage()
        val jwt = makeJwt(exp = nowSec() - 100, iat = nowSec() - 200)
        storage.saveToken(jwt)
        val manager = newManager(storage, FakeAuthGraphql(), mutableListOf())

        assertEquals(jwt, manager.restore())
        assertTrue(manager.isExpired())
    }

    // --- isExpired ------------------------------------------------------------

    @Test
    fun isExpired_trueWhenNoToken() = runTest {
        assertTrue(newManager(FakeTokenStorage(), FakeAuthGraphql(), mutableListOf()).isExpired())
    }

    @Test
    fun isExpired_falseForValidToken() = runTest {
        val manager = newManager(FakeTokenStorage(), FakeAuthGraphql(), mutableListOf())
        manager.setTokens(makeAuthResponse(3600))
        assertFalse(manager.isExpired())
    }

    @Test
    fun isExpired_trueForExpiredToken() = runTest {
        val manager = newManager(FakeTokenStorage(), FakeAuthGraphql(), mutableListOf())
        manager.setTokens(makeAuthResponse(3600, token = makeExpiredToken()))
        assertTrue(manager.isExpired())
    }

    // --- getValidToken --------------------------------------------------------

    @Test
    fun getValidToken_nullWhenNoToken() = runTest {
        assertNull(newManager(FakeTokenStorage(), FakeAuthGraphql(), mutableListOf()).getValidToken())
    }

    @Test
    fun getValidToken_returnsTokenDirectlyWhenNotExpired() = runTest {
        val manager = newManager(FakeTokenStorage(), FakeAuthGraphql(), mutableListOf())
        val response = makeAuthResponse(3600)
        manager.setTokens(response)
        assertEquals(response.token.token, manager.getValidToken())
    }

    @Test
    fun getValidToken_refreshesWhenExpiredWithRefreshToken() = runTest {
        val storage = FakeTokenStorage()
        val graphql = FakeAuthGraphql()
        val manager = newManager(storage, graphql, mutableListOf(), autoRefresh = false)
        manager.setTokens(makeAuthResponse(3600, token = makeExpiredToken()))

        val refreshed = makeAuthResponse(3600)
        graphql.enqueueRefreshSuccess(refreshed)

        assertEquals(refreshed.token.token, manager.getValidToken())
        assertEquals(listOf("new-refresh"), graphql.refreshCalls)
    }

    @Test
    fun getValidToken_preservesSessionWhenExpiredWithNoRefreshToken() = runTest {
        val events = mutableListOf<AuthEvent>()
        val manager = newManager(FakeTokenStorage(), FakeAuthGraphql(), events)
        manager.setTokens(makeAuthResponse(3600, refreshToken = null, token = makeExpiredToken()))

        assertNull(manager.getValidToken())
        assertFalse(events.any { it is AuthEvent.SignedOut })
        assertTrue(events.any { it is AuthEvent.Error })
    }

    @Test
    fun getValidToken_preservesSessionWhenRefreshFails() = runTest {
        val events = mutableListOf<AuthEvent>()
        val graphql = FakeAuthGraphql().apply { refreshDefault = { throw RuntimeException("refresh failed") } }
        val manager = newManager(FakeTokenStorage(), graphql, events)
        manager.setTokens(makeAuthResponse(3600, token = makeExpiredToken()))

        val deferred = async { manager.getValidToken() }
        advanceTimeBy(2_000)
        runCurrent()

        assertNull(deferred.await())
        assertFalse(events.any { it is AuthEvent.SignedOut })
    }

    // --- refresh-only recovery ------------------------------------------------

    @Test
    fun restore_picksUpRefreshTokenWhenAccessTokenGone_andGetValidTokenRecovers() = runTest {
        val storage = FakeTokenStorage()
        storage.saveRefreshToken("orphan-refresh-token")
        val graphql = FakeAuthGraphql()
        val manager = newManager(storage, graphql, mutableListOf())

        assertNull(manager.restore())

        val refreshed = makeAuthResponse(3600, refreshToken = "rotated-refresh")
        graphql.enqueueRefreshSuccess(refreshed)

        assertEquals(refreshed.token.token, manager.getValidToken())
        assertEquals("orphan-refresh-token", graphql.refreshCalls.first())
    }

    @Test
    fun getValidToken_nullAndNoSignedOutWhenNothingInStorage() = runTest {
        val events = mutableListOf<AuthEvent>()
        val manager = newManager(FakeTokenStorage(), FakeAuthGraphql(), events)

        assertNull(manager.getValidToken())
        assertFalse(events.any { it is AuthEvent.SignedOut })
    }

    // --- cross-tab coordination ----------------------------------------------

    @Test
    fun getValidToken_adoptsFreshTokenFromStorageInsteadOfNetwork() = runTest {
        val storage = FakeTokenStorage()
        val graphql = FakeAuthGraphql()
        val events = mutableListOf<AuthEvent>()
        val manager = newManager(storage, graphql, events, autoRefresh = false)
        manager.setTokens(makeAuthResponse(3600, token = makeExpiredToken()))

        // Another tab wrote newer tokens into shared storage.
        storage.saveToken("tab-b-fresh-access-token")
        storage.saveTokenMetadata(TokenMetadata(expiresAt = nowSec() + 3600, issuedAt = nowSec()))
        storage.saveRefreshToken("tab-b-fresh-refresh-token")

        assertEquals("tab-b-fresh-access-token", manager.getValidToken())
        assertTrue(graphql.refreshCalls.isEmpty())
        assertFalse(events.any { it is AuthEvent.TokenRefreshed })
    }

    @Test
    fun getValidToken_adoptsFreshTokenWhenRefreshFailsButAnotherTabWrote() = runTest {
        val storage = FakeTokenStorage()
        val events = mutableListOf<AuthEvent>()
        val graphql = FakeAuthGraphql()
        graphql.enqueueRefresh { _ ->
            // The winning tab's tokens land in shared storage just before our failure.
            storage.saveToken("other-tab-token")
            storage.saveTokenMetadata(TokenMetadata(expiresAt = nowSec() + 3600, issuedAt = nowSec()))
            storage.saveRefreshToken("other-tab-refresh")
            throw RuntimeException("refresh token not found")
        }
        val manager = newManager(storage, graphql, events, autoRefresh = false)
        manager.setTokens(makeAuthResponse(3600, token = makeExpiredToken()))

        assertEquals("other-tab-token", manager.getValidToken())
        assertFalse(events.any { it is AuthEvent.SignedOut })
    }

    // --- automatic refresh scheduling ----------------------------------------

    @Test
    fun autoRefresh_schedulesRefreshBeforeExpiry() = runTest {
        val graphql = FakeAuthGraphql()
        val manager = newManager(FakeTokenStorage(), graphql, mutableListOf())
        manager.setTokens(makeAuthResponse(120)) // refresh buffer 60s → fires at 60s

        advanceTimeBy(59_000)
        runCurrent()
        assertTrue(graphql.refreshCalls.isEmpty())

        graphql.enqueueRefreshSuccess(makeAuthResponse(7200))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, graphql.refreshCalls.size)
    }

    @Test
    fun autoRefresh_rotatesLongLivedSessionsEverySevenDays() = runTest {
        val graphql = FakeAuthGraphql()
        val manager = newManager(FakeTokenStorage(), graphql, mutableListOf())
        graphql.enqueueRefreshSuccess(makeAuthResponse(30 * 24 * 60 * 60, refreshToken = "refresh-2"))
        manager.setTokens(makeAuthResponse(30 * 24 * 60 * 60)) // 30 days

        advanceTimeBy(MAX_SESSION_ROTATION_INTERVAL_MILLIS - 1_000L)
        runCurrent()
        assertTrue(graphql.refreshCalls.isEmpty())

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, graphql.refreshCalls.size)
    }

    @Test
    fun autoRefresh_repeatsSevenDayRotationAfterTokensChange() = runTest {
        val graphql = FakeAuthGraphql()
        val manager = newManager(FakeTokenStorage(), graphql, mutableListOf())
        graphql.enqueueRefreshSuccess(makeAuthResponse(30 * 24 * 60 * 60, refreshToken = "refresh-2"))
        graphql.enqueueRefreshSuccess(makeAuthResponse(30 * 24 * 60 * 60, refreshToken = "refresh-3"))
        manager.setTokens(makeAuthResponse(30 * 24 * 60 * 60, refreshToken = "refresh-1"))

        advanceTimeBy(MAX_SESSION_ROTATION_INTERVAL_MILLIS + 1_000L)
        runCurrent()
        assertEquals(1, graphql.refreshCalls.size)

        advanceTimeBy(MAX_SESSION_ROTATION_INTERVAL_MILLIS + 1_000L)
        runCurrent()
        assertEquals(2, graphql.refreshCalls.size)
    }

    @Test
    fun autoRefresh_skipsScheduleWhenInsideBufferWindow_lazyRefreshStillWorks() = runTest {
        val graphql = FakeAuthGraphql()
        val manager = newManager(FakeTokenStorage(), graphql, mutableListOf())
        val response = makeAuthResponse(30) // expires in 30s, buffer 60s → no timer
        manager.setTokens(response)

        runCurrent()
        assertTrue(graphql.refreshCalls.isEmpty())
        assertEquals(response.token.token, manager.getValidToken())

        graphql.enqueueRefreshSuccess(makeAuthResponse(7200))
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals("jwt-7200", manager.getValidToken())
        assertEquals(1, graphql.refreshCalls.size)
    }

    @Test
    fun autoRefresh_emitsTokenRefreshedEvent() = runTest {
        val graphql = FakeAuthGraphql()
        val events = mutableListOf<AuthEvent>()
        val manager = newManager(FakeTokenStorage(), graphql, events)
        graphql.enqueueRefreshSuccess(makeAuthResponse(7200))
        manager.setTokens(makeAuthResponse(120))

        advanceTimeBy(61_000)
        runCurrent()
        assertTrue(events.any { it is AuthEvent.TokenRefreshed })
    }

    @Test
    fun autoRefresh_disabled_doesNotSchedule() = runTest {
        val graphql = FakeAuthGraphql()
        val manager = newManager(FakeTokenStorage(), graphql, mutableListOf(), autoRefresh = false)
        manager.setTokens(makeAuthResponse(120))

        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(graphql.refreshCalls.isEmpty())
    }

    @Test
    fun autoRefresh_noRefreshToken_doesNotSchedule() = runTest {
        val graphql = FakeAuthGraphql()
        val manager = newManager(FakeTokenStorage(), graphql, mutableListOf())
        manager.setTokens(makeAuthResponse(120, refreshToken = null))

        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(graphql.refreshCalls.isEmpty())
    }

    // --- refresh failure / retry ---------------------------------------------

    @Test
    fun refresh_retriesOnceThenSucceeds() = runTest {
        val graphql = FakeAuthGraphql()
        val manager = newManager(FakeTokenStorage(), graphql, mutableListOf())
        val refreshed = makeAuthResponse(7200)
        graphql.enqueueRefreshError(RuntimeException("temporary"))
        graphql.enqueueRefreshSuccess(refreshed)
        manager.setTokens(makeAuthResponse(3600, token = makeExpiredToken()))

        val deferred = async { manager.getValidToken() }
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(refreshed.token.token, deferred.await())
        assertEquals(2, graphql.refreshCalls.size)
    }

    @Test
    fun refresh_preservesSessionAfterBothNonAuthFailures() = runTest {
        val storage = FakeTokenStorage()
        val events = mutableListOf<AuthEvent>()
        val graphql = FakeAuthGraphql().apply { refreshDefault = { throw RuntimeException("failure") } }
        val manager = newManager(storage, graphql, events)
        manager.setTokens(makeAuthResponse(3600, token = makeExpiredToken()))
        val originalRefresh = storage.getRefreshToken()
        assertNotNull(originalRefresh)

        val deferred = async { manager.getValidToken() }
        advanceTimeBy(2_000)
        runCurrent()

        assertNull(deferred.await())
        assertEquals(0, events.count { it is AuthEvent.SignedOut })
        assertTrue(events.any { it is AuthEvent.Error })
        assertEquals("jwt-expired", manager.getToken())
        assertEquals(originalRefresh, storage.getRefreshToken()) // storage preserved
    }

    @Test
    fun refresh_httpAuthenticationRejectionClearsCompleteSessionAndSignsOut() = runTest {
        val storage = FakeTokenStorage()
        val events = mutableListOf<AuthEvent>()
        val graphql = FakeAuthGraphql().apply {
            refreshDefault = { throw AuthenticationRejectedError(401) }
        }
        val manager = newManager(storage, graphql, events)
        manager.setTokens(makeAuthResponse(3600, token = makeExpiredToken()))
        storage.setIdentity(
            Identity(Principal(testPrincipalId, true, null), emptyList()),
        )

        assertFailsWith<AuthenticationRejectedError> { manager.getValidToken() }

        assertEquals(1, events.count { it is AuthEvent.SignedOut })
        assertNull(manager.getToken())
        assertNull(storage.getToken())
        assertNull(storage.getRefreshToken())
        assertNull(storage.getIdentity())
    }

    @Test
    fun refresh_deduplicatesConcurrentCalls() = runTest {
        val graphql = FakeAuthGraphql()
        val manager = newManager(FakeTokenStorage(), graphql, mutableListOf())
        val refreshed = makeAuthResponse(7200)
        graphql.enqueueRefreshSuccess(refreshed)
        manager.setTokens(makeAuthResponse(3600, token = makeExpiredToken()))

        val p1 = async { manager.getValidToken() }
        val p2 = async { manager.getValidToken() }
        val p3 = async { manager.getValidToken() }
        runCurrent()

        assertEquals(refreshed.token.token, p1.await())
        assertEquals(refreshed.token.token, p2.await())
        assertEquals(refreshed.token.token, p3.await())
        assertEquals(1, graphql.refreshCalls.size)
    }

    // --- clear ----------------------------------------------------------------

    @Test
    fun identityOperationsUseExplicitIdentityStorage() = runTest {
        val tokenStorage = FakeTokenStorage()
        val identityStorage = FakeTokenStorage()
        val manager = newManager(
            storage = tokenStorage,
            graphql = FakeAuthGraphql(),
            events = mutableListOf(),
            identityStorage = identityStorage,
        )
        val identity = Identity(Principal(testPrincipalId, true, null), emptyList())

        manager.setIdentity(identity)

        assertNull(tokenStorage.getIdentity())
        assertEquals(identity, manager.getIdentity())
        assertEquals(identity, identityStorage.getIdentity())

        manager.clear()

        assertNull(identityStorage.getIdentity())
    }

    @Test
    fun clear_removesTokensAndIdentityAndCancelsTimer() = runTest {
        val storage = FakeTokenStorage()
        val manager = newManager(storage, FakeAuthGraphql(), mutableListOf())
        manager.setTokens(makeAuthResponse(3600))
        storage.setIdentity(
            Identity(Principal(testPrincipalId, true, null), emptyList()),
        )

        manager.clear()

        assertNull(manager.getToken())
        assertNull(storage.getToken())
        assertNull(storage.getRefreshToken())
        assertNull(storage.getTokenMetadata())
        assertNull(storage.getIdentity())
    }

    // --- full session expiration ---------------------------------------------

    @Test
    fun fullExpiration_preservesInMemoryStateAndStorageOnNonAuthFailure() = runTest {
        val storage = FakeTokenStorage()
        val events = mutableListOf<AuthEvent>()
        val graphql = FakeAuthGraphql().apply { refreshDefault = { throw RuntimeException("refresh token expired") } }
        val manager = newManager(storage, graphql, events)
        manager.setTokens(makeAuthResponse(3600, token = makeExpiredToken()))

        val deferred = async { manager.getValidToken() }
        advanceTimeBy(2_000)
        runCurrent()

        assertNull(deferred.await())
        assertEquals("jwt-expired", manager.getToken())
        assertTrue(manager.isExpired())
        assertEquals(0, events.count { it is AuthEvent.SignedOut })
        assertTrue(events.count { it is AuthEvent.Error } >= 1)
        assertNotNull(storage.getRefreshToken()) // intentionally preserved
    }

    @Test
    fun fullExpiration_validAccessTokenWithMissingRefreshToken() = runTest {
        val events = mutableListOf<AuthEvent>()
        val manager = newManager(FakeTokenStorage(), FakeAuthGraphql(), events, autoRefresh = false)
        manager.setTokens(makeAuthResponse(3600, refreshToken = null))

        assertNotNull(manager.getValidToken())

        advanceTimeBy(3_601_000)
        runCurrent()
        assertTrue(manager.isExpired())
        assertNull(manager.getValidToken())
        assertFalse(events.any { it is AuthEvent.SignedOut })
    }

    // --- edge cases -----------------------------------------------------------

    @Test
    fun setTokensTwice_cancelsFirstRefreshTimer() = runTest {
        val graphql = FakeAuthGraphql()
        val manager = newManager(FakeTokenStorage(), graphql, mutableListOf())
        manager.setTokens(makeAuthResponse(120)) // timer at 60s
        manager.setTokens(makeAuthResponse(300)) // timer at 240s, first cancelled
        graphql.enqueueRefreshSuccess(makeAuthResponse(7200))

        advanceTimeBy(61_000)
        runCurrent()
        assertTrue(graphql.refreshCalls.isEmpty())

        advanceTimeBy(180_000)
        runCurrent()
        assertEquals(1, graphql.refreshCalls.size)
    }

    @Test
    fun autoRefreshTimer_firstAttemptFailsButRetrySucceeds() = runTest {
        val graphql = FakeAuthGraphql()
        val events = mutableListOf<AuthEvent>()
        val manager = newManager(FakeTokenStorage(), graphql, events)
        val refreshed = makeAuthResponse(7200)
        graphql.enqueueRefreshError(RuntimeException("temporary"))
        graphql.enqueueRefreshSuccess(refreshed)
        manager.setTokens(makeAuthResponse(120))

        advanceTimeBy(61_000)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(2, graphql.refreshCalls.size)
        assertTrue(events.any { it is AuthEvent.TokenRefreshed })
        assertEquals(refreshed.token.token, manager.getToken())
    }

    @Test
    fun autoRefreshTimer_bothAttemptsFailKeepsValidSessionAndReschedules() = runTest {
        val graphql = FakeAuthGraphql()
        val events = mutableListOf<AuthEvent>()
        val manager = newManager(FakeTokenStorage(), graphql, events)
        val lifetimeSeconds = 30 * 24 * 60 * 60
        graphql.enqueueRefreshError(RuntimeException("network down"))
        graphql.enqueueRefreshError(RuntimeException("still down"))
        graphql.enqueueRefreshSuccess(makeAuthResponse(lifetimeSeconds, refreshToken = "refresh-2"))
        manager.setTokens(makeAuthResponse(lifetimeSeconds, refreshToken = "refresh-1"))

        advanceTimeBy(MAX_SESSION_ROTATION_INTERVAL_MILLIS + 2_000L)
        runCurrent()

        assertEquals(2, graphql.refreshCalls.size)
        assertEquals("jwt-$lifetimeSeconds", manager.getToken())
        assertTrue(events.none { it is AuthEvent.SignedOut })

        advanceTimeBy(MAX_SESSION_ROTATION_INTERVAL_MILLIS + 1_000L)
        runCurrent()

        assertEquals(3, graphql.refreshCalls.size)
        assertEquals("jwt-$lifetimeSeconds", manager.getToken())
        assertTrue(events.any { it is AuthEvent.TokenRefreshed })
    }

    // --- API token (opaque, long-lived) --------------------------------------

    @Test
    fun restore_opaqueApiToken_isNonExpiringAndNeverRefreshed() = runTest {
        // An opaque API token: present in storage, but not a JWT and with no
        // refresh token. The manager must treat it as a valid, non-expiring
        // session — not "no session" — and never attempt a refresh.
        val storage = FakeTokenStorage()
        storage.store["token"] = "bsk_opaque_api_token"
        val events = mutableListOf<AuthEvent>()
        val graphql = FakeAuthGraphql() // refreshToken throws if ever called
        val manager = newManager(storage, graphql, events)

        assertEquals("bsk_opaque_api_token", manager.restore())
        assertFalse(manager.isExpired())

        // Advance far beyond any JWT lifetime; an API token never expires.
        advanceTimeBy(60 * 60 * 1000L)
        runCurrent()

        assertEquals("bsk_opaque_api_token", manager.getValidToken())
        assertEquals(0, graphql.refreshCalls.size)
        assertTrue(events.none { it is AuthEvent.SignedOut })
    }

    // --- forced refresh -------------------------------------------------------

    @Test
    fun forceRefresh_refreshesEvenWhenTokenNotLocallyExpired() = runTest {
        // The `bosca login --refresh-token` case: the access token is still valid
        // locally (so getValidToken would NOT refresh) but the server rejected it.
        // forceRefresh must mint a new token from the refresh token regardless.
        val storage = FakeTokenStorage()
        val graphql = FakeAuthGraphql()
        val manager = newManager(storage, graphql, mutableListOf())
        manager.setTokens(makeAuthResponse(3600)) // valid, not expired (token "jwt-3600")
        val refreshed = makeAuthResponse(7200) // token "jwt-7200"
        graphql.enqueueRefreshSuccess(refreshed)

        val result = manager.forceRefresh()

        assertEquals(refreshed.token.token, result?.token?.token)
        assertEquals(1, graphql.refreshCalls.size) // a real network refresh happened
        assertEquals(refreshed.token.token, manager.getToken()) // and was persisted in-memory
        assertEquals(refreshed.token.token, storage.getToken()) // and to storage
    }

    @Test
    fun forceRefresh_returnsNullWhenNoRefreshTokenAvailable() = runTest {
        val storage = FakeTokenStorage()
        storage.store["token"] = "bsk_opaque_api_token" // API token, no refresh
        val graphql = FakeAuthGraphql()
        val manager = newManager(storage, graphql, mutableListOf())
        manager.restore()

        assertNull(manager.forceRefresh())
        assertEquals(0, graphql.refreshCalls.size)
    }
}
