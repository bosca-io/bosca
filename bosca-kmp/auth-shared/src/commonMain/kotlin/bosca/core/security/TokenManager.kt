package bosca.core.security

import bosca.core.security.model.AuthEvent
import bosca.core.security.model.AuthResponse
import bosca.core.security.model.BoscaAuthConfig
import bosca.core.security.model.BoscaToken
import bosca.core.security.model.Identity
import bosca.core.security.model.Principal
import bosca.core.security.model.TokenMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The credential a [TokenManager] currently holds. The two kinds the client
 * supports are modeled as distinct types so the *type* — not a flag — says
 * whether the token expires and can be refreshed.
 */
private sealed interface StoredToken {
    /** The bearer token string sent on requests. */
    val token: String
}

/** A short-lived JWT access token with its server-reported expiry [metadata]. */
private data class JwtSavedToken(override val token: String, val metadata: TokenMetadata) : StoredToken

/** A long-lived, opaque API token: it has no expiry and is never refreshed. */
private data class ApiSavedToken(override val token: String) : StoredToken

/** Rotate active sessions comfortably inside the server's 30-day refresh-token lifetime. */
internal const val MAX_SESSION_ROTATION_INTERVAL_MILLIS = 7L * 24L * 60L * 60L * 1_000L

/**
 * Manages the lifecycle of authentication tokens: persistence across cold
 * starts, automatic background refresh before expiry, and transparent renewal
 * for callers. Kotlin port of `token_manager.ts`, extended to also recognize a
 * long-lived opaque API token (no JWT expiry, no refresh) as a valid session.
 *
 * Expiry is judged against the server-reported `expiresAt`; small clock drift
 * is absorbed by [BoscaAuthConfig.refreshBufferMillis], and the server remains
 * the source of truth on every request. Automatic refresh also bounds the
 * rotation interval so a long-lived access token is refreshed before its
 * refresh token expires. All time arithmetic is done in `Long` milliseconds —
 * the schema's `expiresAt` is an `Int` (seconds) and `Int * 1000` would overflow.
 *
 * @param scope a long-lived scope (SupervisorJob) owned by the caller, used for the refresh timer and refresh coalescing
 * @param onEvent receives [AuthEvent.TokenRefreshed]/[AuthEvent.SignedOut]/[AuthEvent.Error]
 * @param now current time in epoch millis; injectable so tests can drive it from virtual time
 * @param identityStorage identity persistence selected by the caller
 */
class TokenManager(
    private val storage: TokenStorage,
    private val identityStorage: IdentityStorage,
    private val graphql: AuthGraphql,
    private val config: BoscaAuthConfig,
    private val scope: CoroutineScope,
    private val onEvent: (AuthEvent) -> Unit,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    // The currently-held access credential (a JWT or an API token), or null when
    // there is none. The refresh token is tracked separately because it has an
    // independent lifecycle — it can outlive the access token (the "lone refresh
    // token survived a cold start" recovery case).
    private var current: StoredToken? = null
    private var currentRefreshToken: String? = null
    private var refreshTimer: Job? = null

    // Coalesces concurrent refreshes onto a single in-flight request, mirroring
    // the TS `refreshPromise` (a plain Mutex would serialize them and burn the
    // rotated single-use refresh token on the second caller). The leader runs
    // the refresh in its OWN coroutine and completes this deferred; followers
    // await it. Deliberately NOT a child coroutine of `scope` — a terminal
    // refresh failure must surface to the caller (which catches it), never
    // cancel the shared scope.
    private val refreshMutex = Mutex()
    private var inFlight: CompletableDeferred<AuthResponse>? = null

    /**
     * Restores token state from storage after a cold start, classifying the
     * stored access token: a JWT (has expiry [metadata]) becomes a
     * [JwtSavedToken]; an opaque token with no refresh token becomes a long-lived
     * [ApiSavedToken]. A lone refresh token (no access token) is still loaded so
     * a later [getValidToken] can recover the session from it.
     */
    suspend fun restore(): String? {
        val storedToken = storage.getToken()
        val storedRefreshToken = storage.getRefreshToken()
        if (storedRefreshToken != null) currentRefreshToken = storedRefreshToken
        if (storedToken == null) return null

        val metadata = storage.getTokenMetadata() ?: extractMetadataFromJwt(storedToken)
        current = when {
            metadata != null -> JwtSavedToken(storedToken, metadata)
            // Opaque token with no refresh token => a long-lived API token.
            storedRefreshToken == null -> ApiSavedToken(storedToken)
            // Opaque token but a refresh token exists: nothing usable now —
            // recover via getValidToken()'s refresh path.
            else -> null
        }
        if (current is JwtSavedToken && config.autoRefresh) scheduleRefresh()
        return current?.token
    }

    internal suspend fun getIdentity(): Identity? = identityStorage.getIdentity()

    internal suspend fun setIdentity(identity: Identity?) {
        identityStorage.setIdentity(identity)
    }

    /** Stores tokens from an auth response (always a JWT session) and (re)schedules automatic refresh. */
    suspend fun setTokens(response: AuthResponse) {
        val metadata = TokenMetadata(expiresAt = response.token.expiresAt, issuedAt = response.token.issuedAt)
        current = JwtSavedToken(response.token.token, metadata)
        currentRefreshToken = response.refreshToken

        storage.saveToken(response.token.token)
        storage.saveTokenMetadata(metadata)
        response.refreshToken?.let { storage.saveRefreshToken(it) }

        if (config.autoRefresh) scheduleRefresh()
    }

    /**
     * Returns a valid access token, refreshing if the current one is expired (or
     * missing) but a refresh token exists. An [ApiSavedToken] is always valid.
     * Returns null when there is no currently usable token. Connectivity and
     * local-expiry failures never emit [AuthEvent.SignedOut]; only an explicit
     * HTTP 401/403 rejection does.
     */
    suspend fun getValidToken(): String? {
        // A valid JWT — or an API token, which never expires — is returned directly.
        val held = current
        if (held != null && !isExpired()) return held.token

        if (currentRefreshToken != null) {
            return try {
                doRefresh().token.token
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthenticationRejectedError) {
                // attemptRefresh already cleared the rejected session and emitted SignedOut.
                throw e
            } catch (e: NetworkError) {
                // Let BoscaAuth preserve its restored identity while offline.
                throw e
            } catch (_: Exception) {
                // A non-auth server failure must not turn into a local sign-out.
                current?.takeUnless { isExpired() }?.token
            }
        }

        if (held == null) return null

        // Local expiry alone cannot prove that the server rejected the session.
        // Keep state/storage so the SDK can continue exposing its offline identity.
        onEvent(AuthEvent.Error(TokenExpiredError("Session expired: no refresh token available")))
        return null
    }

    /**
     * Forces a refresh using the stored refresh token, regardless of the current
     * token's local expiry, and persists the rotated session. Returns the new
     * session, or null if no refresh token is available.
     *
     * Use this for an explicit "refresh now" — e.g. recovering from a server-side
     * 401 on a token that is not yet *locally* expired (which [getValidToken]
     * would not refresh). [restore] should have run first so the current token is
     * loaded; otherwise the cross-tab adopt path could return the stored token
     * without a network refresh.
     */
    suspend fun forceRefresh(): AuthResponse? {
        if (currentRefreshToken == null && storage.getRefreshToken() == null) return null
        return doRefresh()
    }

    /** The raw access token without an expiry check. Use [getValidToken] for auto-refresh. */
    fun getToken(): String? = current?.token

    /** Whether the current access token is past its expiration. An API token never expires. */
    fun isExpired(): Boolean = when (val held = current) {
        null -> true
        is ApiSavedToken -> false
        is JwtSavedToken -> now() >= held.metadata.expiresAt.toLong() * 1000L
    }

    /**
     * Destructive: cancels the refresh timer and wipes storage. Only for
     * explicit sign-out — internal failure paths use [resetInMemoryState] so a
     * transient error never destroys a still-valid refresh token.
     */
    suspend fun clear() {
        cancelRefresh()
        resetInMemoryState()
        clearPersistentSession()
    }

    /** Cancels the automatic refresh timer without touching tokens. */
    fun cancelRefresh() {
        refreshTimer?.cancel()
        refreshTimer = null
    }

    private fun resetInMemoryState() {
        current = null
        currentRefreshToken = null
    }

    private suspend fun clearPersistentSession() {
        try {
            identityStorage.setIdentity(null)
        } finally {
            storage.clear()
        }
    }

    private fun scheduleRefresh() {
        cancelRefresh()
        // Only a JWT session is refreshed; an API token never is.
        val held = current as? JwtSavedToken ?: return
        if (currentRefreshToken == null) return

        val expiryDelayMillis = held.metadata.expiresAt.toLong() * 1000L - now() - config.refreshBufferMillis
        // Already inside the refresh window — rely on lazy refresh via
        // getValidToken() instead of scheduling a 0/negative-delay timer.
        if (expiryDelayMillis <= 0) return

        val delayMillis = minOf(expiryDelayMillis, MAX_SESSION_ROTATION_INTERVAL_MILLIS)

        refreshTimer = scope.launch {
            delay(delayMillis)
            try {
                doRefresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A proactive rotation failure must not strand a still-valid
                // session. attemptRefresh retains it, and this schedules the
                // next bounded attempt without a tight retry loop.
                if (!isExpired()) scheduleRefresh()
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun extractMetadataFromJwt(token: String?): TokenMetadata? {
        if (token == null) return null
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return null
            val payloadJson = Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(parts[1])
                .decodeToString()
            val payload = Json.parseToJsonElement(payloadJson).jsonObject
            val exp = payload["exp"]?.jsonPrimitive?.intOrNull ?: return null
            val iat = payload["iat"]?.jsonPrimitive?.intOrNull ?: exp
            TokenMetadata(expiresAt = exp, issuedAt = iat)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun doRefresh(): AuthResponse {
        val (deferred, isLeader) = refreshMutex.withLock {
            val existing = inFlight
            if (existing != null) {
                existing to false
            } else {
                val created = CompletableDeferred<AuthResponse>()
                inFlight = created
                created to true
            }
        }
        if (!isLeader) return deferred.await()

        try {
            val result = refreshWithCrossTabLock()
            deferred.complete(result)
            return result
        } catch (e: CancellationException) {
            // Cancel followers (no cause arg: avoids the kotlin vs kotlinx
            // CancellationException type split in common metadata) then rethrow.
            deferred.cancel()
            throw e
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            refreshMutex.withLock { if (inFlight === deferred) inFlight = null }
        }
    }

    private suspend fun refreshWithCrossTabLock(): AuthResponse =
        withAuthRefreshLock("bosca-auth-refresh:${config.apiUrl}") { refreshOrAdoptFromStorage() }

    private suspend fun refreshOrAdoptFromStorage(): AuthResponse {
        tryAdoptFreshTokensFromStorage()?.let { return it }

        val freshRefreshToken = storage.getRefreshToken() ?: currentRefreshToken
        if (freshRefreshToken == null) {
            onEvent(AuthEvent.Error(TokenExpiredError("No refresh token available")))
            throw TokenExpiredError("No refresh token available")
        }
        currentRefreshToken = freshRefreshToken
        return attemptRefresh()
    }

    /**
     * If storage holds a still-valid access token newer than this instance's
     * (another tab refreshed under us), adopt it and return a synthetic response
     * instead of consuming our stale refresh token. Returns null when no
     * adoption is possible. Deliberately emits no `tokenRefreshed` — no network
     * refresh happened from this instance's perspective.
     */
    private suspend fun tryAdoptFreshTokensFromStorage(): AuthResponse? {
        val storedToken = storage.getToken() ?: return null
        val storedMetadata = storage.getTokenMetadata() ?: extractMetadataFromJwt(storedToken) ?: return null
        val storedRefreshToken = storage.getRefreshToken()

        // Storage is also expired / inside the refresh window — no help.
        if (now() >= storedMetadata.expiresAt.toLong() * 1000L - config.refreshBufferMillis) return null
        // Only adopt if genuinely different, else we'd loop adopting our own state.
        if (storedToken == current?.token) return null

        current = JwtSavedToken(storedToken, storedMetadata)
        currentRefreshToken = storedRefreshToken
        if (config.autoRefresh) scheduleRefresh()

        return AuthResponse(
            principal = Principal(id = SYNTHETIC_PRINCIPAL_ID, verified = false, primaryProfileId = null),
            profile = null,
            token = BoscaToken(token = storedToken, expiresAt = storedMetadata.expiresAt, issuedAt = storedMetadata.issuedAt),
            refreshToken = storedRefreshToken,
        )
    }

    private suspend fun attemptRefresh(): AuthResponse {
        val refreshToken = currentRefreshToken ?: throw TokenExpiredError("No refresh token available")
        try {
            val response = graphql.refreshToken(refreshToken)
            setTokens(response)
            onEvent(AuthEvent.TokenRefreshed(response))
            return response
        } catch (firstError: CancellationException) {
            throw firstError
        } catch (firstError: Exception) {
            // Another tab may have completed a refresh during our attempt.
            tryAdoptFreshTokensFromStorage()?.let { return it }

            if (firstError is AuthenticationRejectedError) {
                rejectAuthentication(firstError)
            }

            // Retry once after a short delay (transient network errors).
            delay(config.retryDelayMillis)
            tryAdoptFreshTokensFromStorage()?.let { return it }

            val latestRefreshToken = storage.getRefreshToken() ?: currentRefreshToken
            if (latestRefreshToken == null) {
                onEvent(AuthEvent.Error(firstError))
                throw TokenExpiredError("No refresh token available after retry")
            }
            currentRefreshToken = latestRefreshToken

            try {
                val response = graphql.refreshToken(latestRefreshToken)
                setTokens(response)
                onEvent(AuthEvent.TokenRefreshed(response))
                return response
            } catch (retryError: CancellationException) {
                throw retryError
            } catch (retryError: Exception) {
                tryAdoptFreshTokensFromStorage()?.let { return it }

                if (retryError is AuthenticationRejectedError) {
                    rejectAuthentication(retryError)
                }

                // Connectivity and non-auth server failures preserve the session,
                // even when the local access token has expired. The persisted
                // identity remains useful for offline content and can retry later.
                onEvent(AuthEvent.Error(retryError))
                throw retryError
            }
        }
    }

    private suspend fun rejectAuthentication(error: AuthenticationRejectedError): Nothing {
        // HTTP 401/403 is the sole implicit sign-out path. Clear the complete
        // logical session, including BoscaAuth's persisted identity.
        clearPersistentSession()
        cancelRefresh()
        resetInMemoryState()
        onEvent(AuthEvent.Error(error))
        onEvent(AuthEvent.SignedOut)
        throw error
    }

    private companion object {
        // Placeholder id on the synthetic adopt response; its principal is never surfaced.
        val SYNTHETIC_PRINCIPAL_ID: Uuid = Uuid.fromLongs(0L, 0L)
    }
}
