import type { TokenStorage } from './storage'
import type { AuthResponse, Principal, Profile, TokenMetadata } from './types'
import { TokenExpiredError } from './errors'
import * as graphql from './graphql'

/** Callback signature for token manager events */
export type TokenManagerEventHandler = (event: string, data?: unknown) => void

/**
 * Manages the lifecycle of Bosca authentication tokens, including
 * persistence across page reloads, automatic background refresh
 * before expiry, and transparent token renewal for API callers.
 *
 * Expiry decisions are made against the server-reported `expiresAt`
 * directly. Small client/server clock drift is absorbed by the
 * `refreshBuffer` (default 60s), and the server is the source of
 * truth for actual token validity on every API call anyway.
 */
export class TokenManager {
  private readonly storage: TokenStorage
  private readonly apiUrl: string
  private readonly refreshBuffer: number
  private readonly autoRefresh: boolean
  private readonly retryDelay: number
  private readonly onEvent: TokenManagerEventHandler

  private currentToken: TokenMetadata | null = null
  private currentTokenValue: string | null = null
  private currentRefreshToken: string | null = null
  private refreshTimer: ReturnType<typeof setTimeout> | null = null
  private refreshPromise: Promise<AuthResponse> | null = null

  constructor(
    storage: TokenStorage,
    apiUrl: string,
    refreshBuffer: number,
    autoRefresh: boolean,
    retryDelay: number,
    onEvent: TokenManagerEventHandler,
  ) {
    this.storage = storage
    this.apiUrl = apiUrl
    this.refreshBuffer = refreshBuffer
    this.autoRefresh = autoRefresh
    this.retryDelay = retryDelay
    this.onEvent = onEvent
  }

  /**
   * Restores token state from storage after a page reload. If tokens
   * exist and auto-refresh is enabled, schedules the next refresh.
   *
   * This loads whatever is available — including the refresh token on
   * its own — so that a subsequent call to `getValidToken()` can
   * recover the session by refreshing when only the refresh token
   * survived (e.g. the short-lived access token cookie was evicted
   * between page loads but the long-lived refresh token cookie was
   * not).
   *
   * @returns The stored access token string if present, or null
   */
  restore(): string | null {
    const storedToken = this.storage.getToken()
    const storedRefreshToken = this.storage.getRefreshToken()
    const metadata = this.storage.getTokenMetadata() ?? TokenManager.extractMetadataFromJwt(storedToken)

    if (storedRefreshToken) {
      this.currentRefreshToken = storedRefreshToken
    }

    if (storedToken && metadata) {
      this.currentTokenValue = storedToken
      this.currentToken = metadata
      if (this.autoRefresh) {
        this.scheduleRefresh()
      }
      return storedToken
    }

    return null
  }

  /**
   * Stores tokens from an authentication response and schedules
   * automatic refresh if enabled.
   */
  setTokens(response: AuthResponse): void {
    const metadata: TokenMetadata = {
      expiresAt: response.token.expiresAt,
      issuedAt: response.token.issuedAt,
    }

    this.currentTokenValue = response.token.token
    this.currentToken = metadata
    this.currentRefreshToken = response.refreshToken ?? null

    this.storage.setToken(response.token.token, response.token.expiresAt)
    this.storage.setTokenMetadata(metadata)
    if (response.refreshToken) {
      this.storage.setRefreshToken(response.refreshToken)
    }

    if (this.autoRefresh) {
      this.scheduleRefresh()
    }
  }

  /**
   * Returns the current access token if it has not expired. If the
   * token is expired (or missing entirely) but a refresh token
   * exists, triggers a refresh and returns the new token. When both
   * tokens are expired or refresh fails, clears the session and
   * emits `signedOut`.
   *
   * @returns A valid access token string, or null if no session exists
   *          or the session has fully expired
   */
  async getValidToken(): Promise<string | null> {
    // Fast path: in-memory access token still valid.
    if (this.currentToken && !this.isExpired()) {
      return this.currentTokenValue
    }

    // If we have a refresh token — either because the access token
    // expired, or because the access-token cookie was evicted and
    // only the refresh token survived — try to recover the session.
    if (this.currentRefreshToken) {
      try {
        const response = await this.doRefresh()
        return response.token.token
      } catch {
        // doRefresh already handled clearing state / emitting signedOut
        // for terminal failures. A non-terminal failure (another tab
        // refreshed under us) may have repopulated currentTokenValue.
        return this.currentTokenValue
      }
    }

    if (!this.currentToken) {
      // Nothing to restore — never signed in on this device.
      return null
    }

    // Had an access token, it's expired, and there's no refresh token.
    // Leave storage intact — the next explicit signIn/signOut handles
    // cookies. We only drop in-memory state and signal the UI to update.
    this.onEvent('error', new TokenExpiredError('Session expired: no refresh token available'))
    this.resetInMemoryState()
    this.onEvent('signedOut')
    return null
  }

  /**
   * Returns the raw access token string without checking expiry.
   * Use `getValidToken()` for automatic expiry handling.
   */
  getToken(): string | null {
    return this.currentTokenValue
  }

  /**
   * Consumes the one-shot sign-in-result marker written by the backend on a
   * same-domain OAuth callback. Returns an {@link AuthResponse} that pairs the
   * marker's echoed `originator` / `accountCreated` with the just-restored
   * token state, so the caller can emit a proper `signedIn` event for a flow
   * that otherwise completes silently through cookie restore. Returns null when
   * there is no marker or no active token. The marker is cleared on read, so a
   * later plain reload does not re-fire the event.
   */
  consumeSignInResult(principal: Principal, profile: Profile[] | null): AuthResponse | null {
    const result = this.storage.getSignInResult()
    if (!result || !this.currentTokenValue || !this.currentToken) return null
    this.storage.clearSignInResult()
    return {
      principal,
      profile,
      token: {
        token: this.currentTokenValue,
        expiresAt: this.currentToken.expiresAt,
        issuedAt: this.currentToken.issuedAt,
      },
      refreshToken: this.currentRefreshToken,
      accountCreated: result.accountCreated,
      originator: result.originator,
    }
  }

  /** Whether the current access token has passed its expiration time */
  isExpired(): boolean {
    if (!this.currentToken) return true
    return Date.now() >= this.currentToken.expiresAt * 1000
  }

  /**
   * Removes all stored tokens and cancels any pending refresh timer.
   *
   * This is destructive and wipes the cookie jar — intended only for
   * explicit sign-out. Internal failure paths should use
   * {@link resetInMemoryState} instead so that a transient error
   * doesn't permanently destroy a still-valid refresh token.
   */
  clear(): void {
    this.cancelRefresh()
    this.resetInMemoryState()
    this.storage.clear()
  }

  /**
   * Drops all in-memory token state without touching storage.
   * Used on transient failure paths so a network blip or 5xx
   * doesn't destroy cookies that might still be valid — the next
   * `initialize()` or `getValidToken()` call can re-read them and
   * try again.
   */
  private resetInMemoryState(): void {
    this.currentToken = null
    this.currentTokenValue = null
    this.currentRefreshToken = null
    this.refreshPromise = null
  }

  /** Cancels the automatic refresh timer without clearing tokens */
  cancelRefresh(): void {
    if (this.refreshTimer !== null) {
      clearTimeout(this.refreshTimer)
      this.refreshTimer = null
    }
  }

  /** Tears down the manager, releasing all timers and state */
  destroy(): void {
    this.clear()
  }

  private scheduleRefresh(): void {
    this.cancelRefresh()

    if (!this.currentToken || !this.currentRefreshToken) return

    const delay = this.currentToken.expiresAt * 1000 - Date.now() - this.refreshBuffer

    // If we're already inside the refresh window, skip the timer and
    // rely on lazy refresh via `getValidToken()` on the next call.
    // Scheduling a 0-delay timer here would risk tight loops when a
    // refreshed token also lands inside the buffer window.
    if (delay <= 0) return

    // setTimeout delays are stored in a signed 32-bit integer.
    // Any value above ~24.8 days silently overflows to 0 and the
    // callback fires on the next tick — which with a 30-day JWT
    // means we'd refresh immediately after every login and then
    // tight-loop on the freshly refreshed token. Clamp to the
    // maximum; when the clamped timer fires early we re-schedule
    // instead of unconditionally refreshing, so long-lived tokens
    // only actually refresh once, near real expiry.
    const MAX_TIMEOUT = 2_147_483_647
    if (delay > MAX_TIMEOUT) {
      this.refreshTimer = setTimeout(() => {
        this.scheduleRefresh()
      }, MAX_TIMEOUT)
      return
    }

    this.refreshTimer = setTimeout(() => {
      this.doRefresh().catch(() => {
        // Error handling is done inside doRefresh
      })
    }, delay)
  }

  /**
   * Decodes the payload of a JWT to extract `exp` and `iat` claims,
   * allowing a bare `_bat` cookie (without companion `_bat_meta`) to
   * be used for session restoration.
   */
  private static extractMetadataFromJwt(token: string | null): TokenMetadata | null {
    if (!token) return null
    try {
      const parts = token.split('.')
      if (parts.length !== 3) return null
      const payload = JSON.parse(atob(parts[1]!.replace(/-/g, '+').replace(/_/g, '/'))) as { exp?: number; iat?: number }
      if (typeof payload.exp !== 'number') return null
      return {
        expiresAt: payload.exp,
        issuedAt: payload.iat ?? payload.exp,
      }
    } catch {
      return null
    }
  }

  private async doRefresh(): Promise<AuthResponse> {
    // In-process deduplication: coalesce concurrent calls within this
    // TokenManager instance.
    if (this.refreshPromise) {
      return this.refreshPromise
    }

    this.refreshPromise = this.refreshWithCrossTabLock()

    try {
      return await this.refreshPromise
    } finally {
      this.refreshPromise = null
    }
  }

  /**
   * Serializes refresh across browser tabs/windows using the Web
   * Locks API when available. Multiple tabs sharing the same
   * `_bat_rt` cookie will otherwise race on the single-use refresh
   * token: the first tab consumes it and the second tab's request
   * fails, which previously wiped the shared cookies and signed the
   * user out of every tab.
   *
   * Inside the lock we re-read storage — if another tab already
   * refreshed while we were waiting, we adopt its tokens and skip
   * the network call entirely.
   */
  private async refreshWithCrossTabLock(): Promise<AuthResponse> {
    const locks = (globalThis as { navigator?: { locks?: { request: (name: string, cb: () => Promise<AuthResponse>) => Promise<AuthResponse> } } }).navigator?.locks
    const run = () => this.refreshOrAdoptFromStorage()
    if (locks && typeof locks.request === 'function') {
      // Name-scope by API URL so two apps hitting different backends
      // from the same origin don't block each other.
      return locks.request(`bosca-auth-refresh:${this.apiUrl}`, run)
    }
    return run()
  }

  /**
   * Executed inside the cross-tab lock. Before issuing a refresh,
   * re-read storage: if another tab's refresh result is already
   * there (new access token, still-valid metadata), adopt it
   * instead of consuming our stale refresh token.
   */
  private async refreshOrAdoptFromStorage(): Promise<AuthResponse> {
    const adopted = this.tryAdoptFreshTokensFromStorage()
    if (adopted) {
      return adopted
    }

    // Re-read the refresh token from storage — another tab may have
    // rotated it while we were waiting for the lock.
    const freshRefreshToken = this.storage.getRefreshToken() ?? this.currentRefreshToken
    if (!freshRefreshToken) {
      // Leave storage alone; the next explicit signIn/signOut decides
      // what to do with any lingering cookies.
      this.onEvent('error', new TokenExpiredError('No refresh token available'))
      this.resetInMemoryState()
      this.onEvent('signedOut')
      throw new TokenExpiredError('No refresh token available')
    }
    this.currentRefreshToken = freshRefreshToken

    return this.attemptRefresh()
  }

  /**
   * If storage contains a valid (non-expired) access token newer
   * than what this instance has in memory — indicating another tab
   * refreshed under us — load it into local state and return a
   * synthetic AuthResponse. Returns null when no adoption is
   * possible.
   *
   * This deliberately does not emit `tokenRefreshed` because no
   * network refresh happened from this instance's perspective. It
   * emits `tokenAdopted` so holders of long-lived connections can
   * present the new token.
   */
  private tryAdoptFreshTokensFromStorage(): AuthResponse | null {
    const storedToken = this.storage.getToken()
    const storedMetadata = this.storage.getTokenMetadata() ?? TokenManager.extractMetadataFromJwt(storedToken)
    const storedRefreshToken = this.storage.getRefreshToken()
    if (!storedToken || !storedMetadata) return null

    // Storage is also expired / inside the refresh window — no help.
    if (Date.now() >= storedMetadata.expiresAt * 1000 - this.refreshBuffer) return null

    // Only "adopt" if the stored token is actually different from
    // what we tried to refresh; otherwise we'd loop adopting our
    // own stale state.
    if (storedToken === this.currentTokenValue) return null

    this.currentTokenValue = storedToken
    this.currentToken = storedMetadata
    this.currentRefreshToken = storedRefreshToken
    if (this.autoRefresh) {
      this.scheduleRefresh()
    }
    const response: AuthResponse = {
      principal: { id: '', verified: false, primaryProfileId: null },
      profile: null,
      token: {
        token: storedToken,
        expiresAt: storedMetadata.expiresAt,
        issuedAt: storedMetadata.issuedAt,
      },
      refreshToken: storedRefreshToken,
      // Reconstructed from stored tokens, not a fresh sign-in: no account creation, no request originator.
      accountCreated: false,
      originator: null,
    }
    this.onEvent('tokenAdopted', response)
    return response
  }

  private async attemptRefresh(): Promise<AuthResponse> {
    // All callers verify currentRefreshToken is non-null before calling.
    try {
      const response = await graphql.refreshToken(this.apiUrl, this.currentRefreshToken!)
      this.setTokens(response)
      this.onEvent('tokenRefreshed', response)
      return response
    } catch (firstError) {
      // Before clearing state on failure, check whether another tab
      // managed to complete a refresh during our attempt. If storage
      // now has a fresh token, adopt it instead of signing out.
      const adopted = this.tryAdoptFreshTokensFromStorage()
      if (adopted) return adopted

      // Retry once after a short delay — handles transient network errors.
      await new Promise(resolve => setTimeout(resolve, this.retryDelay))

      // Re-read storage before retry in case another tab refreshed
      // during the retry delay.
      const adoptedAfterDelay = this.tryAdoptFreshTokensFromStorage()
      if (adoptedAfterDelay) return adoptedAfterDelay
      const latestRefreshToken = this.storage.getRefreshToken() ?? this.currentRefreshToken
      if (!latestRefreshToken) {
        this.onEvent('error', firstError)
        this.resetInMemoryState()
        this.onEvent('signedOut')
        throw new TokenExpiredError('No refresh token available after retry')
      }
      this.currentRefreshToken = latestRefreshToken

      try {
        const response = await graphql.refreshToken(this.apiUrl, this.currentRefreshToken)
        this.setTokens(response)
        this.onEvent('tokenRefreshed', response)
        return response
      } catch (retryError) {
        // One last chance: maybe another tab finished in the retry window.
        const finalAdoption = this.tryAdoptFreshTokensFromStorage()
        if (finalAdoption) return finalAdoption

        // Transient failures (network blip, server 5xx) must NOT wipe
        // the cookie jar — the refresh token may still be perfectly
        // valid and the next attempt could succeed. Only drop in-memory
        // state and emit signedOut so the UI can prompt re-login;
        // storage stays intact until the user explicitly signs out.
        this.onEvent('error', retryError)
        this.resetInMemoryState()
        this.onEvent('signedOut')
        throw retryError instanceof TokenExpiredError
          ? retryError
          : new TokenExpiredError('Token refresh failed after retry')
      }
    }
  }
}
