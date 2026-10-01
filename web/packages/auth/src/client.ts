import type {
  AuthEvent,
  AuthResponse,
  BoscaAuthConfig,
  Group,
  OAuthRedirectOptions,
  Principal,
  Profile,
  ProfileInput,
  SignupOptions,
  ThirdPartyType,
} from './types'
import { createStorage, type TokenStorage } from './storage'
import { TokenManager } from './token_manager'
import { startOAuthRedirect, getExchangeTokenFromUrl, getLinkTokenFromUrl, getLinkMethodsFromUrl } from './oauth'
import { NetworkError, UnauthenticatedError, type LinkProofMethod } from './errors'
import * as graphql from './graphql'

type EventCallback = (...args: unknown[]) => void

/**
 * A pending account-link challenge read off the OAuth redirect URL by
 * `BoscaAuth.getLinkFromUrl()`: the token identifying the link, and the
 * proof methods the backend offered for the existing account (null when
 * the redirect carried no `methods` parameter).
 */
export interface PendingLink {
  token: string
  methods: LinkProofMethod[] | null
}

/**
 * Central authentication client for Bosca web applications. Manages
 * the full authentication lifecycle including email/password sign-in,
 * OAuth redirect flows, automatic token refresh, and profile access.
 *
 * Designed to be framework-agnostic at its core, with optional Nuxt
 * integration available via the companion `setupNuxtAuth` function.
 *
 * @example
 * ```ts
 * const auth = new BoscaAuth({ apiUrl: 'https://api.example.com' })
 *
 * // Sign in with email/password
 * const response = await auth.signInWithPassword('user@example.com', 'password')
 * console.log(response.principal.id)
 *
 * // Get a valid token for API calls (auto-refreshes if needed)
 * const headers = await auth.getAuthHeaders()
 * ```
 */
export class BoscaAuth {
  private readonly config: BoscaAuthConfig
  private readonly storage: TokenStorage
  private readonly tokenManager: TokenManager
  private readonly listeners = new Map<string, Set<EventCallback>>()

  private _currentUser: Principal | null = null
  private _currentProfile: Profile | null = null
  private _allProfiles: Profile[] = []
  private _groups: Group[] = []

  constructor(config: BoscaAuthConfig) {
    this.config = {
      tokenName: '_bat',
      storage: 'cookie',
      refreshBuffer: 60_000,
      autoRefresh: true,
      retryDelay: 1_000,
      ...config,
    }

    this.storage = createStorage(this.config)
    this.tokenManager = new TokenManager(
      this.storage,
      this.config.apiUrl,
      this.config.refreshBuffer!,
      this.config.autoRefresh!,
      this.config.retryDelay!,
      (event, data) => this.emit(event as AuthEvent, data),
    )
  }

  // ---------------------------------------------------------------------------
  // Auth State
  // ---------------------------------------------------------------------------

  /** The currently authenticated user principal, or null when signed out */
  get currentUser(): Principal | null {
    return this._currentUser
  }

  /** The primary profile of the authenticated user, or null when signed out */
  get currentProfile(): Profile | null {
    return this._currentProfile
  }

  /** All profiles owned by the authenticated user */
  get profiles(): Profile[] {
    return this._allProfiles
  }

  /** Security groups the authenticated user belongs to */
  get groups(): Group[] {
    return this._groups
  }

  /** Whether a user is currently authenticated with a non-expired token */
  get isAuthenticated(): boolean {
    return this._currentUser !== null && this.tokenManager.getToken() !== null && !this.tokenManager.isExpired()
  }

  /** The raw JWT access token string, or null when signed out */
  get token(): string | null {
    return this.tokenManager.getToken()
  }

  // ---------------------------------------------------------------------------
  // Initialization
  // ---------------------------------------------------------------------------

  /**
   * Restores authentication state from persisted storage (cookies or
   * localStorage). Call this on application startup to resume a previous
   * session. Optionally fetches the user's profile if a token exists.
   *
   * @param fetchProfile - Whether to fetch the user profile from the backend (default: true)
   * @returns The authenticated user principal if a session was restored, or null
   */
  async initialize(fetchProfile = true): Promise<Principal | null> {
    this.tokenManager.restore()

    // Use `getValidToken()` rather than the raw return of `restore()`
    // so that two recovery cases work transparently:
    //   1. The access token expired between page loads — lazy-refresh.
    //   2. Only the refresh token cookie survived (e.g. the short-lived
    //      access token cookie was evicted) — recover the session by
    //      refreshing directly.
    // Without this, users with a valid refresh token but a missing or
    // expired access token were signed out on page load.
    const validToken = await this.tokenManager.getValidToken()
    if (!validToken) return null

    if (fetchProfile) {
      try {
        const [principal, profiles] = await Promise.all([
          graphql.getCurrentPrincipal(this.config.apiUrl, validToken),
          graphql.getCurrentProfiles(this.config.apiUrl, validToken),
        ])
        this._currentUser = principal
        this.setProfiles(profiles)
      } catch (err) {
        // Do NOT wipe cookies on a failed profile fetch. Transient
        // failures (network blip, API cold start, 5xx, CORS) used to
        // destroy a perfectly valid session here because `clearState()`
        // calls `tokenManager.clear()` which drops the cookie jar.
        // Leave storage intact; the next `getValidToken()` / page
        // load can retry. The caller sees `null` and can prompt
        // re-auth if they want, but the underlying session is
        // preserved until the user explicitly signs out.
        console.debug('[bosca-auth] Profile fetch failed during initialize (leaving session intact):', err)
        return null
      }
    }

    // A same-domain OAuth sign-in completes via cookie restore (above), not the
    // exchange-token path, so it emits no `signedIn` on its own. The backend
    // leaves a one-shot sign-in-result marker for exactly this case: when
    // present, surface the just-restored session as a `signedIn` carrying the
    // echoed originator / accountCreated. Gated on a resolved principal so the
    // emitted response is well-formed; the marker self-clears so ordinary
    // reloads stay silent.
    if (this._currentUser) {
      const signInResult = this.tokenManager.consumeSignInResult(this._currentUser, this._allProfiles)
      if (signInResult) {
        this.emit('signedIn', signInResult)
      }
    }

    return this._currentUser
  }

  // ---------------------------------------------------------------------------
  // Email/Password Authentication
  // ---------------------------------------------------------------------------

  /**
   * Authenticates a user with their email (or username) and password.
   * On success, stores the tokens, fetches the user profile, and emits
   * a `signedIn` event. The optional `originator` identifies the calling
   * app/client and is echoed back on the response (`AuthResponse.originator`).
   */
  async signInWithPassword(identifier: string, password: string, originator?: string): Promise<AuthResponse> {
    const response = await graphql.loginWithPassword(this.config.apiUrl, identifier, password, originator)
    await this.handleAuthResponse(response)
    return response
  }

  /**
   * Registers a new user account with email, password, and profile.
   * Note: This does not automatically sign in the user — they may
   * need to verify their email first depending on server configuration.
   */
  async signUp(options: SignupOptions): Promise<Principal> {
    const resolved: SignupOptions = {
      ...options,
      languageTag: options.languageTag ?? detectBrowserLanguage() ?? this.config.defaultLanguageTag,
    }
    return graphql.signupWithPassword(this.config.apiUrl, resolved)
  }

  /**
   * Registers or signs in a user using a third-party OAuth provider
   * token (e.g. a Google ID token obtained client-side). On success,
   * stores the tokens, fetches the user profile, and emits `signedIn`.
   *
   * @param type - The OAuth provider that issued the token
   * @param token - The provider-issued access token or ID token
   * @param languageTag - Optional IETF BCP 47 language tag for the user
   */
  async signInWithThirdParty(type: ThirdPartyType, token: string, languageTag?: string): Promise<AuthResponse> {
    const response = await graphql.signupThirdParty(
      this.config.apiUrl,
      type,
      token,
      languageTag ?? detectBrowserLanguage() ?? this.config.defaultLanguageTag,
    )
    await this.handleAuthResponse(response)
    return response
  }

  /**
   * Signs the current user out end-to-end.
   *
   * Contacts the server first so the backend can:
   *   1. Bump the principal's token version, invalidating every
   *      outstanding JWT for this account on its next request.
   *   2. Delete every refresh token row for the principal so a
   *      leaked refresh token cannot mint a new session.
   *   3. Emit `Set-Cookie: _bat=; Max-Age=0` on both the primary
   *      and admin domains. This is the **only** way to remove the
   *      HTTP-only session cookie — the browser will not let JS
   *      delete it, so a purely local sign-out leaves the cookie
   *      in the jar and the server still sees an authenticated
   *      request on the next page load.
   *
   * Error handling splits by failure kind:
   *   - **Network errors** (offline, DNS, unreachable server) are
   *     rethrown *without* clearing local state. The user still
   *     holds a valid session and should be allowed to retry —
   *     silently logging them out on a transient blip would be a
   *     hostile UX and, worse, would leave the server-side session
   *     un-invalidated anyway.
   *   - **Token / auth failures** (expired token, failed refresh,
   *     GraphQL auth rejection) are treated as session-ending:
   *     local state is cleared and `signedOut` is emitted. The
   *     session is already effectively dead on the server, so
   *     keeping local state would just strand the UI.
   *
   * When local state is cleared, a stale HTTP-only cookie may
   * remain in the browser jar until the next request invalidates
   * it via the `tver` check against the DB.
   */
  async signOut(): Promise<void> {
    // Grab the current access token (without triggering a
    // refresh — we're signing out, we don't want to mint a fresh
    // token just to immediately invalidate it). If there's no
    // token at all the call still goes out; the server will use
    // the cookie, and on a failed auth it still clears the
    // cookie unconditionally.
    const token = this.tokenManager.getToken()
    try {
      await graphql.signOut(this.config.apiUrl, token)
    } catch (err) {
      // Network errors must NOT auto-log-out the user: they still
      // have a valid session and should be able to retry. Rethrow
      // so the UI can surface "couldn't reach server, try again".
      if (err instanceof NetworkError) {
        throw err
      }
      console.error('failed to sign out', err)
      // Any other failure (expired/invalid token, GraphQL auth
      // error, etc.) means the session is effectively dead — fall
      // through to clear local state below.
    }
    this.clearState()
    this.emit('signedOut')
  }

  // ---------------------------------------------------------------------------
  // OAuth (Redirect)
  // ---------------------------------------------------------------------------

  /**
   * Initiates an OAuth sign-in by redirecting the browser to the
   * Bosca backend's OAuth endpoint for the specified provider.
   * The page will navigate away — use `handleRedirectResult()` on
   * return to complete the flow.
   */
  signInWithRedirect(options: OAuthRedirectOptions): void {
    startOAuthRedirect(this.config.apiUrl, options)
  }

  /**
   * Completes an OAuth redirect flow by checking the URL for an
   * exchange token and converting it into a full JWT session. Call
   * this after `initialize()` on application startup. A valid selected
   * session is retained instead of being replaced by a transferable
   * exchange URL.
   *
   * @returns The authentication response if an exchange token was found, or null
   */
  async handleRedirectResult(): Promise<AuthResponse | null> {
    const exchangeToken = getExchangeTokenFromUrl()
    if (!exchangeToken) return null
    if (this.isAuthenticated) return null

    const response = await graphql.exchangeToken(this.config.apiUrl, exchangeToken)
    await this.handleAuthResponse(response)
    return response
  }

  /**
   * Checks the URL for a pending account-link challenge. Instead of an
   * exchange token, the backend redirects back with `?link=<token>` (plus
   * `?methods=…`) when an OAuth sign-in's verified email already belongs to
   * an existing account. Reads and strips both parameters so a refresh
   * doesn't replay them; route the user into the proof flow and complete it
   * with `linkConfirmPassword()` / `linkConfirmEmail()`.
   *
   * @returns The pending link if present, or null when this is not a link bounce
   */
  getLinkFromUrl(): PendingLink | null {
    const token = getLinkTokenFromUrl()
    if (!token) return null
    return { token, methods: getLinkMethodsFromUrl() }
  }

  // ---------------------------------------------------------------------------
  // Password Management
  // ---------------------------------------------------------------------------

  /**
   * Initiates a forgot-password flow by requesting a reset email
   * be sent to the provided identifier. Does not reveal whether
   * the account exists.
   */
  async forgotPassword(identifier: string): Promise<void> {
    await graphql.forgotPassword(this.config.apiUrl, identifier)
  }

  /**
   * Resets a user's password using a token received via the
   * forgot-password email.
   */
  async resetPassword(token: string, password: string): Promise<void> {
    await graphql.resetPassword(this.config.apiUrl, token, password)
  }

  /**
   * Completes a pending account link by proving ownership of the existing
   * account with its password. On success the user is signed in to that
   * account (tokens are stored and the `signedIn` event is emitted), now
   * carrying the newly-linked sign-in method.
   */
  async linkConfirmPassword(token: string, password: string): Promise<AuthResponse> {
    const response = await graphql.linkConfirmPassword(this.config.apiUrl, token, password)
    await this.handleAuthResponse(response)
    return response
  }

  /**
   * Requests a one-time magic-link to the existing account's verified email so
   * the user can prove ownership without a password. Does not change the
   * current session.
   */
  async linkRequestEmailProof(token: string): Promise<void> {
    await graphql.linkRequestEmailProof(this.config.apiUrl, token)
  }

  /**
   * Completes a pending account link using the one-time token from the email
   * magic-link. On success the user is signed in to the existing account.
   */
  async linkConfirmEmail(proofToken: string): Promise<AuthResponse> {
    const response = await graphql.linkConfirmEmail(this.config.apiUrl, proofToken)
    await this.handleAuthResponse(response)
    return response
  }

  /**
   * Connects an additional third-party (OAuth) login to the currently
   * authenticated account. Requires a provider-issued token; the active
   * session is the proof of ownership, so this does not change who is signed in.
   */
  async connectThirdParty(type: ThirdPartyType, token: string): Promise<void> {
    await graphql.connectThirdParty(this.config.apiUrl, type, token)
  }

  /**
   * Changes the currently authenticated user's password. The old
   * password is required and re-verified server-side before the
   * change is applied.
   *
   * Note: the server does not currently invalidate existing refresh
   * tokens when a password changes, so the caller's existing session
   * remains valid. Callers that want "change password ⇒ sign out
   * everywhere" semantics must implement that policy themselves
   * (e.g. by calling `signOut()` afterward).
   *
   * To change the login identifier, use {@link BoscaAuth.changeIdentifier}
   * instead — this method intentionally does not expose the server's
   * combined password+identifier side-channel.
   *
   * @param newPassword - The new password to set
   * @param oldPassword - The user's current password
   */
  async changePassword(newPassword: string, oldPassword: string): Promise<void> {
    const token = await this.getToken()
    if (!token) throw new UnauthenticatedError()

    await graphql.changePassword(this.config.apiUrl, token, newPassword, oldPassword)
  }

  /**
   * Changes the currently authenticated user's login identifier
   * (email or username). The user's current password is required
   * for re-verification.
   *
   * Note: existing refresh tokens remain valid after this change —
   * the current session is not signed out.
   *
   * @param identifier - The new login identifier to set
   * @param password - The user's current password
   */
  async changeIdentifier(identifier: string, password: string): Promise<void> {
    const token = await this.getToken()
    if (!token) throw new UnauthenticatedError()

    await graphql.changeIdentifier(this.config.apiUrl, token, identifier, password)
  }

  // ---------------------------------------------------------------------------
  // Email Verification
  // ---------------------------------------------------------------------------

  /**
   * Verifies a new account's email address using the token sent
   * during the signup flow.
   */
  async verifyEmail(token: string): Promise<void> {
    await graphql.verifyEmail(this.config.apiUrl, token)
  }

  /**
   * Resends the email verification message for an account that
   * has not yet confirmed their email address.
   */
  async resendVerification(identifier: string): Promise<void> {
    await graphql.resendVerification(this.config.apiUrl, identifier)
  }

  // ---------------------------------------------------------------------------
  // Profile
  // ---------------------------------------------------------------------------

  /**
   * Fetches the profiles for the currently authenticated user.
   * Updates the local profile state and emits a `profileUpdated` event.
   */
  async getProfiles(): Promise<Profile[]> {
    const token = await this.getToken()
    if (!token) return []

    const profiles = await graphql.getCurrentProfiles(this.config.apiUrl, token)
    this.setProfiles(profiles)
    return profiles
  }

  /**
   * Updates an existing profile's name, attributes, and visibility.
   * Refreshes the local profile cache after the update.
   *
   * @param id - The profile UUID to update, or null for the primary profile
   * @param input - The updated profile fields
   */
  async updateProfile(id: string | null, input: ProfileInput): Promise<Profile> {
    const token = await this.getToken()
    if (!token) throw new UnauthenticatedError()

    const profile = await graphql.editProfile(this.config.apiUrl, token, id, input)

    // Refresh all profiles to keep local state consistent
    await this.getProfiles()

    return profile
  }

  /**
   * Sets the primary profile for the currently authenticated user.
   * Refreshes the local principal and profile cache after the change
   * so that `primaryProfileId` and each profile's `isPrimary` flag
   * stay consistent.
   *
   * @param profileId - The profile UUID to mark as primary
   * @param principalId - Optional target principal UUID; requires admin privileges server-side
   */
  async setPrimaryProfile(profileId: string, principalId?: string): Promise<void> {
    const token = await this.getToken()
    if (!token) throw new UnauthenticatedError()

    await graphql.setPrimaryProfile(this.config.apiUrl, token, profileId, principalId)

    // Refresh principal + profiles to keep primaryProfileId / isPrimary
    // consistent. Only refresh when targeting the current user; an admin
    // setting another user's primary profile shouldn't touch local state.
    //
    // The mutation has already succeeded at this point — if the subsequent
    // refresh fails (network blip, rate limit), log and carry on rather
    // than rejecting the whole operation and misleading the caller into
    // thinking the primary-profile change didn't take effect.
    if (!principalId) {
      try {
        this._currentUser = await graphql.getCurrentPrincipal(this.config.apiUrl, token)
        await this.getProfiles()
      } catch (err) {
        console.debug('[bosca-auth] State refresh failed after setPrimaryProfile:', err)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Groups
  // ---------------------------------------------------------------------------

  /**
   * Fetches the security groups for the currently authenticated user.
   * Updates the local groups state. Returns an empty array when not
   * authenticated.
   */
  async getGroups(): Promise<Group[]> {
    const token = await this.getToken()
    if (!token) return []

    const groups = await graphql.getCurrentGroups(this.config.apiUrl, token)
    this._groups = groups
    return groups
  }

  // ---------------------------------------------------------------------------
  // Token Access
  // ---------------------------------------------------------------------------

  /**
   * Returns a valid access token, automatically refreshing if the
   * current token is expired or about to expire. Returns null if
   * no authenticated session exists.
   */
  async getToken(): Promise<string | null> {
    return this.tokenManager.getValidToken()
  }

  /**
   * Returns an object suitable for spreading into a `fetch` headers
   * object, containing the Authorization header with a valid Bearer token.
   * Returns an empty object if no session exists.
   */
  async getAuthHeaders(): Promise<Record<string, string>> {
    const token = await this.getToken()
    if (!token) return {}
    return { Authorization: `Bearer ${token}` }
  }

  // ---------------------------------------------------------------------------
  // Events
  // ---------------------------------------------------------------------------

  /**
   * Registers a callback to be invoked whenever the authentication
   * state changes (sign in, sign out, token refresh). Returns an
   * unsubscribe function.
   */
  onAuthStateChanged(callback: (user: Principal | null) => void): () => void {
    const wrappedSignIn = () => callback(this._currentUser)
    const wrappedSignOut = () => callback(null)

    this.on('signedIn', wrappedSignIn)
    this.on('signedOut', wrappedSignOut)

    return () => {
      this.off('signedIn', wrappedSignIn)
      this.off('signedOut', wrappedSignOut)
    }
  }

  /**
   * Registers a callback for a specific auth event. Returns an
   * unsubscribe function.
   */
  on(event: AuthEvent | string, callback: EventCallback): () => void {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set())
    }
    this.listeners.get(event)!.add(callback)
    return () => this.off(event, callback)
  }

  /**
   * Removes a previously registered event callback.
   */
  off(event: AuthEvent | string, callback: EventCallback): void {
    this.listeners.get(event)?.delete(callback)
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Tears down the auth client, clearing all timers, state, and
   * event listeners. Call this when the auth client is no longer needed.
   */
  destroy(): void {
    this.tokenManager.destroy()
    this.listeners.clear()
    this._currentUser = null
    this._currentProfile = null
    this._allProfiles = []
    this._groups = []
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private async handleAuthResponse(response: AuthResponse): Promise<void> {
    this.tokenManager.setTokens(response)
    this._currentUser = response.principal

    if (response.profile && response.profile.length > 0) {
      this.setProfiles(response.profile)
    } else {
      // Fetch profiles if not included in the response
      try {
        const profiles = await graphql.getCurrentProfiles(
          this.config.apiUrl,
          response.token.token,
        )
        this.setProfiles(profiles)
      } catch (err) {
        console.debug('[bosca-auth] Profile fetch failed after sign-in:', err)
      }
    }

    this.emit('signedIn', response)
  }

  private setProfiles(profiles: Profile[]): void {
    this._allProfiles = profiles
    this._currentProfile = profiles.find(p => p.isPrimary) ?? profiles[0] ?? null

    this.emit('profileUpdated', profiles)
  }

  private clearState(): void {
    this.tokenManager.clear()
    this._currentUser = null
    this._currentProfile = null
    this._allProfiles = []
    this._groups = []
  }

  private emit(event: string, ...args: unknown[]): void {
    const callbacks = this.listeners.get(event)
    if (callbacks) {
      for (const cb of callbacks) {
        try {
          cb(...args)
        } catch (err) {
          console.error(`Error in auth event listener for "${event}":`, err)
        }
      }
    }
  }
}

/**
 * Returns the browser's preferred IETF BCP 47 language tag
 * (e.g. "en-US", "fr") using `navigator.language`, or undefined
 * when running in a non-browser environment (SSR).
 */
function detectBrowserLanguage(): string | undefined {
  if (typeof globalThis.navigator !== 'undefined') {
    return globalThis.navigator.language
  }
  return undefined
}
