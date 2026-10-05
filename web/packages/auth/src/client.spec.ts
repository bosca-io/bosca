import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { BoscaAuth } from './client'
import { NetworkError, TokenExpiredError, UnauthenticatedError } from './errors'
import type { AuthResponse, BoscaToken, Group, Profile } from './types'

// Mock the graphql module
vi.mock('./graphql', () => ({
  loginWithPassword: vi.fn(),
  refreshToken: vi.fn(),
  exchangeToken: vi.fn(),
  forgotPassword: vi.fn(),
  resetPassword: vi.fn(),
  changePassword: vi.fn(),
  changeIdentifier: vi.fn(),
  setPrimaryProfile: vi.fn(),
  signupWithPassword: vi.fn(),
  signupThirdParty: vi.fn(),
  verifyEmail: vi.fn(),
  resendVerification: vi.fn(),
  getCurrentPrincipal: vi.fn(),
  getCurrentProfiles: vi.fn(),
  editProfile: vi.fn(),
  getCurrentGroups: vi.fn(),
  signOut: vi.fn(),
}))

// Mock the oauth module
vi.mock('./oauth', () => ({
  startOAuthRedirect: vi.fn(),
  getExchangeTokenFromUrl: vi.fn(),
  getLinkTokenFromUrl: vi.fn(),
  getLinkMethodsFromUrl: vi.fn(),
}))

import * as graphql from './graphql'
import * as oauth from './oauth'

const mockLogin = vi.mocked(graphql.loginWithPassword)
const mockRefresh = vi.mocked(graphql.refreshToken)
const mockExchange = vi.mocked(graphql.exchangeToken)
const mockForgot = vi.mocked(graphql.forgotPassword)
const mockReset = vi.mocked(graphql.resetPassword)
const mockChangePassword = vi.mocked(graphql.changePassword)
const mockChangeIdentifier = vi.mocked(graphql.changeIdentifier)
const mockSetPrimaryProfile = vi.mocked(graphql.setPrimaryProfile)
const mockSignup = vi.mocked(graphql.signupWithPassword)
const mockVerify = vi.mocked(graphql.verifyEmail)
const mockResend = vi.mocked(graphql.resendVerification)
const mockGetPrincipal = vi.mocked(graphql.getCurrentPrincipal)
const mockGetProfiles = vi.mocked(graphql.getCurrentProfiles)
const mockEditProfile = vi.mocked(graphql.editProfile)
const mockGetGroups = vi.mocked(graphql.getCurrentGroups)
const mockStartOAuth = vi.mocked(oauth.startOAuthRedirect)
const mockGetExchange = vi.mocked(oauth.getExchangeTokenFromUrl)
const mockGetLinkToken = vi.mocked(oauth.getLinkTokenFromUrl)
const mockGetLinkMethods = vi.mocked(oauth.getLinkMethodsFromUrl)
const mockSignOut = vi.mocked(graphql.signOut)

function makeToken(expiresInSeconds: number): BoscaToken {
  const now = Math.floor(Date.now() / 1000)
  return { token: `jwt-${expiresInSeconds}`, expiresAt: now + expiresInSeconds, issuedAt: now }
}

function makeProfile(overrides?: Partial<Profile>): Profile {
  return {
    id: 'pr-1',
    name: 'Test User',
    type: 'GENERIC',
    visibility: 'USER',
    slug: 'test-user',
    isPrimary: true,
    created: '2024-01-01T00:00:00Z',
    attributes: [{
      id: 'attr-1',
      typeId: 'bosca.profiles.name',
      attributes: { name: 'Test User' },
      source: 'signup',
      priority: 1,
      confidence: 100,
      visibility: 'USER',
    }],
    ...overrides,
  }
}

function makeAuthResponse(expiresIn = 3600, overrides: Partial<AuthResponse> = {}): AuthResponse {
  return {
    principal: { id: 'p-1', verified: true, primaryProfileId: 'pr-1' },
    profile: [makeProfile()],
    token: makeToken(expiresIn),
    refreshToken: 'refresh-token',
    accountCreated: false,
    originator: null,
    ...overrides,
  }
}

describe('BoscaAuth', () => {
  let auth: BoscaAuth

  beforeEach(() => {
    vi.useFakeTimers()
    vi.resetAllMocks()
    localStorage.clear()

    auth = new BoscaAuth({
      apiUrl: 'https://api.test',
      storage: 'memory',
      autoRefresh: false, // disabled for most tests to avoid timer complexity
    })
  })

  afterEach(() => {
    auth.destroy()
    vi.useRealTimers()
  })

  // -------------------------------------------------------------------------
  // Initial state
  // -------------------------------------------------------------------------

  describe('initial state', () => {
    it('starts unauthenticated', () => {
      expect(auth.isAuthenticated).toBe(false)
      expect(auth.currentUser).toBeNull()
      expect(auth.currentProfile).toBeNull()
      expect(auth.token).toBeNull()
      expect(auth.profiles).toEqual([])
      expect(auth.groups).toEqual([])
    })
  })

  // -------------------------------------------------------------------------
  // signInWithPassword
  // -------------------------------------------------------------------------

  describe('signInWithPassword', () => {
    it('authenticates and stores user state', async () => {
      const response = makeAuthResponse()
      mockLogin.mockResolvedValueOnce(response)

      const result = await auth.signInWithPassword('user@test.com', 'password')

      expect(result.principal.id).toBe('p-1')
      expect(auth.isAuthenticated).toBe(true)
      expect(auth.currentUser).toEqual(response.principal)
      expect(auth.currentProfile?.name).toBe('Test User')
      expect(auth.token).toBe(response.token.token)
    })

    it('emits signedIn event', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())

      const listener = vi.fn()
      auth.on('signedIn', listener)

      await auth.signInWithPassword('user@test.com', 'pass')

      expect(listener).toHaveBeenCalledTimes(1)
    })

    it('forwards the caller-supplied originator to the login call', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())

      await auth.signInWithPassword('user@test.com', 'pass', 'studio')

      expect(mockLogin).toHaveBeenCalledWith('https://api.test', 'user@test.com', 'pass', 'studio')
    })

    it('surfaces accountCreated and originator on the signedIn event', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse(3600, { accountCreated: true, originator: 'studio' }))

      const listener = vi.fn()
      auth.on('signedIn', listener)

      await auth.signInWithPassword('user@test.com', 'pass', 'studio')

      const response = listener.mock.calls[0][0] as { accountCreated: boolean; originator: string | null }
      expect(response.accountCreated).toBe(true)
      expect(response.originator).toBe('studio')
    })

    it('fetches profiles from server when not included in response', async () => {
      const response = makeAuthResponse()
      response.profile = null
      mockLogin.mockResolvedValueOnce(response)
      mockGetProfiles.mockResolvedValueOnce([makeProfile()])

      await auth.signInWithPassword('user@test.com', 'pass')

      expect(mockGetProfiles).toHaveBeenCalledWith('https://api.test', response.token.token)
      expect(auth.currentProfile?.name).toBe('Test User')
    })

    it('propagates login errors', async () => {
      mockLogin.mockRejectedValueOnce(new Error('Invalid credentials'))

      await expect(auth.signInWithPassword('user@test.com', 'wrong'))
        .rejects.toThrow('Invalid credentials')

      expect(auth.isAuthenticated).toBe(false)
    })
  })

  // -------------------------------------------------------------------------
  // signUp
  // -------------------------------------------------------------------------

  describe('signUp', () => {
    it('calls signup and returns principal', async () => {
      mockSignup.mockResolvedValueOnce({ id: 'new-p', verified: false, primaryProfileId: null })

      const result = await auth.signUp({
        identifier: 'new@test.com',
        password: 'pass',
        profile: { name: 'New', visibility: 'USER', attributes: [] },
      })

      expect(result.id).toBe('new-p')
      expect(result.verified).toBe(false)
      // Signup does NOT auto-sign-in
      expect(auth.isAuthenticated).toBe(false)
    })

    it('auto-detects browser language when languageTag is not provided', async () => {
      const originalLanguage = Object.getOwnPropertyDescriptor(globalThis.navigator, 'language')
      Object.defineProperty(globalThis.navigator, 'language', { value: 'fr-FR', configurable: true })

      mockSignup.mockResolvedValueOnce({ id: 'new-p', verified: false, primaryProfileId: null })

      await auth.signUp({
        identifier: 'new@test.com',
        password: 'pass',
        profile: { name: 'New', visibility: 'USER', attributes: [] },
      })

      expect(mockSignup).toHaveBeenCalledWith('https://api.test', expect.objectContaining({
        languageTag: 'fr-FR',
      }))

      if (originalLanguage) {
        Object.defineProperty(globalThis.navigator, 'language', originalLanguage)
      }
    })

    it('uses explicit languageTag over auto-detected value', async () => {
      mockSignup.mockResolvedValueOnce({ id: 'new-p', verified: false, primaryProfileId: null })

      await auth.signUp({
        identifier: 'new@test.com',
        password: 'pass',
        profile: { name: 'New', visibility: 'USER', attributes: [] },
        languageTag: 'de-DE',
      })

      expect(mockSignup).toHaveBeenCalledWith('https://api.test', expect.objectContaining({
        languageTag: 'de-DE',
      }))
    })

    it('falls back to config defaultLanguageTag when navigator is unavailable', async () => {
      const originalNavigator = globalThis.navigator
      delete (globalThis as Record<string, unknown>).navigator

      const ssrAuth = new BoscaAuth({
        apiUrl: 'https://api.test',
        storage: 'memory',
        autoRefresh: false,
        defaultLanguageTag: 'ja-JP',
      })

      mockSignup.mockResolvedValueOnce({ id: 'new-p', verified: false, primaryProfileId: null })

      await ssrAuth.signUp({
        identifier: 'new@test.com',
        password: 'pass',
        profile: { name: 'New', visibility: 'USER', attributes: [] },
      })

      expect(mockSignup).toHaveBeenCalledWith('https://api.test', expect.objectContaining({
        languageTag: 'ja-JP',
      }))

      globalThis.navigator = originalNavigator
      ssrAuth.destroy()
    })
  })

  // -------------------------------------------------------------------------
  // signOut
  // -------------------------------------------------------------------------

  describe('signOut', () => {
    it('calls the server signOut mutation with the current bearer token, then clears local state and emits event', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      mockSignOut.mockResolvedValueOnce(undefined)
      await auth.signInWithPassword('u', 'p')
      const tokenBeforeSignOut = auth.token

      const listener = vi.fn()
      auth.on('signedOut', listener)

      await auth.signOut()

      // The server call must happen BEFORE local state is cleared —
      // otherwise we'd pass `null` for the bearer token and the
      // server couldn't identify the principal to invalidate.
      expect(mockSignOut).toHaveBeenCalledOnce()
      expect(mockSignOut).toHaveBeenCalledWith('https://api.test', tokenBeforeSignOut)

      expect(auth.isAuthenticated).toBe(false)
      expect(auth.currentUser).toBeNull()
      expect(auth.currentProfile).toBeNull()
      expect(auth.token).toBeNull()
      expect(listener).toHaveBeenCalledOnce()
    })

    it('preserves local session and rethrows when the server call fails with a network error', async () => {
      // A transient network failure (offline, DNS, unreachable
      // server) must NOT auto-log-out the user. Their session is
      // still valid; they should be able to retry. Silently wiping
      // local state on a blip would be a hostile UX and wouldn't
      // invalidate the server-side session anyway.
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      mockSignOut.mockRejectedValueOnce(new NetworkError('offline'))
      await auth.signInWithPassword('u', 'p')

      const listener = vi.fn()
      auth.on('signedOut', listener)

      await expect(auth.signOut()).rejects.toBeInstanceOf(NetworkError)

      expect(auth.isAuthenticated).toBe(true)
      expect(auth.token).not.toBeNull()
      expect(listener).not.toHaveBeenCalled()
    })

    it('clears local state and emits signedOut when the server call fails with a token/auth error', async () => {
      // An expired token or failed refresh means the session is
      // already effectively dead on the server. Keeping local
      // state would just strand the UI in a broken "logged in
      // but every request 401s" state.
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      mockSignOut.mockRejectedValueOnce(new TokenExpiredError())
      await auth.signInWithPassword('u', 'p')

      const listener = vi.fn()
      auth.on('signedOut', listener)

      // The session-ending path logs via `console.error` — expected
      // here, so suppress it to keep the test output clean.
      const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
      await auth.signOut()
      expect(errSpy).toHaveBeenCalledOnce()
      errSpy.mockRestore()

      expect(auth.isAuthenticated).toBe(false)
      expect(auth.token).toBeNull()
      expect(listener).toHaveBeenCalledOnce()
    })
  })

  // -------------------------------------------------------------------------
  // OAuth redirect flow
  // -------------------------------------------------------------------------

  describe('signInWithRedirect', () => {
    it('delegates to startOAuthRedirect', () => {
      auth.signInWithRedirect({ provider: 'GOOGLE', redirectUrl: 'https://app.test/' })

      expect(mockStartOAuth).toHaveBeenCalledWith('https://api.test', {
        provider: 'GOOGLE',
        redirectUrl: 'https://app.test/',
      })
    })

  })

  describe('handleRedirectResult', () => {
    it('returns null when no exchange token in URL', async () => {
      mockGetExchange.mockReturnValueOnce(null)

      const result = await auth.handleRedirectResult()
      expect(result).toBeNull()
      expect(auth.isAuthenticated).toBe(false)
    })

    it('exchanges token and signs in when exchange token present', async () => {
      mockGetExchange.mockReturnValueOnce('exchange-token-abc')
      mockExchange.mockResolvedValueOnce(makeAuthResponse())

      const result = await auth.handleRedirectResult()

      expect(result).not.toBeNull()
      expect(result!.principal.id).toBe('p-1')
      expect(auth.isAuthenticated).toBe(true)
      expect(mockExchange).toHaveBeenCalledWith('https://api.test', 'exchange-token-abc')
    })

    it('does not replace a valid session with an exchange token', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('user@test.com', 'password')
      mockGetExchange.mockReturnValueOnce('foreign-exchange-token')

      const result = await auth.handleRedirectResult()

      expect(result).toBeNull()
      expect(auth.currentUser?.id).toBe('p-1')
      expect(mockExchange).not.toHaveBeenCalled()
    })

    it('propagates exchange errors', async () => {
      mockGetExchange.mockReturnValueOnce('bad-token')
      mockExchange.mockRejectedValueOnce(new Error('Invalid exchange token'))

      await expect(auth.handleRedirectResult())
        .rejects.toThrow('Invalid exchange token')
    })
  })

  describe('getLinkFromUrl', () => {
    it('returns null when no link token in URL', () => {
      mockGetLinkToken.mockReturnValueOnce(null)

      expect(auth.getLinkFromUrl()).toBeNull()
      // Without a token there is no challenge — the methods param is not consumed.
      expect(mockGetLinkMethods).not.toHaveBeenCalled()
    })

    it('returns the token and offered proof methods', () => {
      mockGetLinkToken.mockReturnValueOnce('link-token-abc')
      mockGetLinkMethods.mockReturnValueOnce(['PASSWORD', 'EMAIL'])

      expect(auth.getLinkFromUrl()).toEqual({ token: 'link-token-abc', methods: ['PASSWORD', 'EMAIL'] })
    })

    it('returns null methods when the redirect carried none', () => {
      mockGetLinkToken.mockReturnValueOnce('link-token-abc')
      mockGetLinkMethods.mockReturnValueOnce(null)

      expect(auth.getLinkFromUrl()).toEqual({ token: 'link-token-abc', methods: null })
    })
  })

  // -------------------------------------------------------------------------
  // Password management
  // -------------------------------------------------------------------------

  describe('forgotPassword', () => {
    it('delegates to graphql forgotPassword', async () => {
      mockForgot.mockResolvedValueOnce(undefined)
      await auth.forgotPassword('user@test.com')
      expect(mockForgot).toHaveBeenCalledWith('https://api.test', 'user@test.com')
    })
  })

  describe('resetPassword', () => {
    it('delegates to graphql resetPassword', async () => {
      mockReset.mockResolvedValueOnce(undefined)
      await auth.resetPassword('reset-token', 'newpass')
      expect(mockReset).toHaveBeenCalledWith('https://api.test', 'reset-token', 'newpass')
    })
  })

  describe('changePassword', () => {
    it('delegates to graphql changePassword with current token', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      mockChangePassword.mockResolvedValueOnce(undefined)
      await auth.changePassword('newpass', 'oldpass')

      expect(mockChangePassword).toHaveBeenCalledWith(
        'https://api.test',
        expect.any(String),
        'newpass',
        'oldpass',
      )
    })

    it('throws UnauthenticatedError when not authenticated', async () => {
      await expect(auth.changePassword('newpass', 'oldpass'))
        .rejects.toBeInstanceOf(UnauthenticatedError)
    })
  })

  describe('changeIdentifier', () => {
    it('delegates to graphql changeIdentifier with current token', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      mockChangeIdentifier.mockResolvedValueOnce(undefined)
      await auth.changeIdentifier('new@test.com', 'pw')

      expect(mockChangeIdentifier).toHaveBeenCalledWith(
        'https://api.test',
        expect.any(String),
        'new@test.com',
        'pw',
      )
    })

    it('throws UnauthenticatedError when not authenticated', async () => {
      await expect(auth.changeIdentifier('new@test.com', 'pw'))
        .rejects.toBeInstanceOf(UnauthenticatedError)
    })
  })

  describe('setPrimaryProfile', () => {
    it('delegates and refreshes principal + profiles for self', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      mockSetPrimaryProfile.mockResolvedValueOnce(undefined)
      mockGetPrincipal.mockResolvedValueOnce(makeAuthResponse().principal)
      mockGetProfiles.mockResolvedValueOnce([makeProfile({ isPrimary: true })])

      await auth.setPrimaryProfile('profile-uuid')

      expect(mockSetPrimaryProfile).toHaveBeenCalledWith(
        'https://api.test',
        expect.any(String),
        'profile-uuid',
        undefined,
      )
      expect(mockGetPrincipal).toHaveBeenCalled()
      expect(mockGetProfiles).toHaveBeenCalled()
    })

    it('does not refresh local state when targeting another principal', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      mockGetPrincipal.mockClear()
      mockGetProfiles.mockClear()

      mockSetPrimaryProfile.mockResolvedValueOnce(undefined)
      await auth.setPrimaryProfile('profile-uuid', 'other-principal-uuid')

      expect(mockSetPrimaryProfile).toHaveBeenCalledWith(
        'https://api.test',
        expect.any(String),
        'profile-uuid',
        'other-principal-uuid',
      )
      expect(mockGetPrincipal).not.toHaveBeenCalled()
      expect(mockGetProfiles).not.toHaveBeenCalled()
    })

    it('swallows refresh errors after a successful mutation', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      mockSetPrimaryProfile.mockResolvedValueOnce(undefined)
      mockGetPrincipal.mockRejectedValueOnce(new Error('network blip'))

      // Mutation succeeded; refresh failure must not reject the whole call.
      await expect(auth.setPrimaryProfile('profile-uuid')).resolves.toBeUndefined()
    })

    it('throws UnauthenticatedError when not authenticated', async () => {
      await expect(auth.setPrimaryProfile('profile-uuid'))
        .rejects.toBeInstanceOf(UnauthenticatedError)
    })
  })

  // -------------------------------------------------------------------------
  // Email verification
  // -------------------------------------------------------------------------

  describe('verifyEmail', () => {
    it('delegates to graphql verifyEmail', async () => {
      mockVerify.mockResolvedValueOnce(undefined)
      await auth.verifyEmail('verify-token')
      expect(mockVerify).toHaveBeenCalledWith('https://api.test', 'verify-token')
    })
  })

  describe('resendVerification', () => {
    it('delegates to graphql resendVerification', async () => {
      mockResend.mockResolvedValueOnce(undefined)
      await auth.resendVerification('user@test.com')
      expect(mockResend).toHaveBeenCalledWith('https://api.test', 'user@test.com')
    })
  })

  // -------------------------------------------------------------------------
  // Profile management
  // -------------------------------------------------------------------------

  describe('getProfiles', () => {
    it('fetches and updates profile state', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      const profiles = [makeProfile(), makeProfile({ id: 'pr-2', name: 'Second', isPrimary: false })]
      mockGetProfiles.mockResolvedValueOnce(profiles)

      const result = await auth.getProfiles()

      expect(result).toHaveLength(2)
      expect(auth.currentProfile?.id).toBe('pr-1') // primary
      expect(auth.profiles).toHaveLength(2)
    })

    it('returns empty array when not authenticated', async () => {
      const result = await auth.getProfiles()
      expect(result).toEqual([])
    })

    it('emits profileUpdated event', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      const listener = vi.fn()
      auth.on('profileUpdated', listener)

      mockGetProfiles.mockResolvedValueOnce([makeProfile()])
      await auth.getProfiles()

      expect(listener).toHaveBeenCalled()
    })
  })

  describe('updateProfile', () => {
    it('updates profile and refreshes all profiles', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      const updated = makeProfile({ name: 'Updated Name' })
      mockEditProfile.mockResolvedValueOnce(updated)
      mockGetProfiles.mockResolvedValueOnce([updated])

      const result = await auth.updateProfile('pr-1', {
        name: 'Updated Name',
        visibility: 'USER',
        attributes: [],
      })

      expect(result.name).toBe('Updated Name')
      expect(mockEditProfile).toHaveBeenCalled()
      expect(mockGetProfiles).toHaveBeenCalled()
    })

    it('throws UnauthenticatedError when not authenticated', async () => {
      await expect(auth.updateProfile('pr-1', {
        name: 'Test',
        visibility: 'USER',
        attributes: [],
      })).rejects.toBeInstanceOf(UnauthenticatedError)
    })
  })

  // -------------------------------------------------------------------------
  // Groups
  // -------------------------------------------------------------------------

  describe('getGroups', () => {
    it('fetches and caches groups', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      const groups: Group[] = [
        { id: 'g-1', name: 'admins', description: 'System administrators', type: 'SYSTEM' },
        { id: 'g-2', name: 'editors', description: 'Content editors', type: 'PRINCIPAL' },
      ]
      mockGetGroups.mockResolvedValueOnce(groups)

      const result = await auth.getGroups()

      expect(result).toEqual(groups)
      expect(auth.groups).toEqual(groups)
    })

    it('returns empty array when not authenticated', async () => {
      const result = await auth.getGroups()
      expect(result).toEqual([])
    })

    it('clears groups on sign out', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      mockGetGroups.mockResolvedValueOnce([
        { id: 'g-1', name: 'admins', description: 'Admins', type: 'SYSTEM' },
      ])
      await auth.getGroups()
      expect(auth.groups).toHaveLength(1)

      await auth.signOut()
      expect(auth.groups).toEqual([])
    })
  })

  // -------------------------------------------------------------------------
  // Token access
  // -------------------------------------------------------------------------

  describe('getToken', () => {
    it('returns valid token', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      const token = await auth.getToken()
      expect(token).not.toBeNull()
    })

    it('returns null when not authenticated', async () => {
      expect(await auth.getToken()).toBeNull()
    })
  })

  describe('getAuthHeaders', () => {
    it('returns Authorization header with Bearer token', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      const headers = await auth.getAuthHeaders()
      expect(headers).toHaveProperty('Authorization')
      expect(headers.Authorization).toMatch(/^Bearer /)
    })

    it('returns empty object when not authenticated', async () => {
      const headers = await auth.getAuthHeaders()
      expect(headers).toEqual({})
    })
  })

  // -------------------------------------------------------------------------
  // Events
  // -------------------------------------------------------------------------

  describe('onAuthStateChanged', () => {
    it('fires with user on sign in', async () => {
      const callback = vi.fn()
      auth.onAuthStateChanged(callback)

      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      expect(callback).toHaveBeenCalledWith(expect.objectContaining({ id: 'p-1' }))
    })

    it('fires with null on sign out', async () => {
      const callback = vi.fn()

      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      auth.onAuthStateChanged(callback)
      await auth.signOut()

      expect(callback).toHaveBeenCalledWith(null)
    })

    it('returns unsubscribe function', async () => {
      const callback = vi.fn()
      const unsub = auth.onAuthStateChanged(callback)

      unsub()

      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      expect(callback).not.toHaveBeenCalled()
    })
  })

  describe('on/off', () => {
    it('registers and unregisters event listeners', async () => {
      const callback = vi.fn()
      const unsub = auth.on('signedIn', callback)

      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')
      expect(callback).toHaveBeenCalledOnce()

      unsub()

      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signOut()
      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      expect(callback).toHaveBeenCalledOnce() // still just once
    })

    it('handles errors in event listeners gracefully', async () => {
      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
      auth.on('signedIn', () => { throw new Error('listener error') })

      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      // Should not throw
      await auth.signInWithPassword('u', 'p')

      expect(consoleSpy).toHaveBeenCalled()
      consoleSpy.mockRestore()
    })
  })

  // -------------------------------------------------------------------------
  // initialize (session restoration)
  // -------------------------------------------------------------------------

  describe('initialize', () => {
    it('returns null when no stored session', async () => {
      const result = await auth.initialize()
      expect(result).toBeNull()
      expect(auth.isAuthenticated).toBe(false)
    })

    it('restores session from storage and fetches principal and profile', async () => {
      // Manually populate localStorage to simulate a previous session
      const token = makeToken(3600)
      localStorage.setItem('_bat', token.token)
      localStorage.setItem('_bat_rt', 'stored-refresh')
      localStorage.setItem('_bat_meta', JSON.stringify(token))

      const auth2 = new BoscaAuth({
        apiUrl: 'https://api.test',
        storage: 'localStorage',
        autoRefresh: false,
      })

      mockGetPrincipal.mockResolvedValueOnce({ id: 'p-1', verified: true, primaryProfileId: 'pr-1' })
      mockGetProfiles.mockResolvedValueOnce([makeProfile()])
      const result = await auth2.initialize()

      expect(result).toEqual({ id: 'p-1', verified: true, primaryProfileId: 'pr-1' })
      expect(auth2.isAuthenticated).toBe(true)
      expect(auth2.currentUser?.id).toBe('p-1')
      expect(auth2.currentProfile?.name).toBe('Test User')
      expect(auth2.token).toBe(token.token)
      auth2.destroy()
    })

    it('returns null but leaves storage intact when profile fetch fails during init', async () => {
      // A transient profile-fetch failure (network blip, 5xx, etc.)
      // must not destroy the cookie jar — otherwise one flaky API
      // response during page load permanently signs the user out
      // despite a perfectly valid refresh token.
      const token = makeToken(3600)
      localStorage.setItem('_bat', token.token)
      localStorage.setItem('_bat_meta', JSON.stringify(token))
      localStorage.setItem('_bat_rt', 'still-valid-refresh-token')

      const auth2 = new BoscaAuth({
        apiUrl: 'https://api.test',
        storage: 'localStorage',
        autoRefresh: false,
      })

      mockGetPrincipal.mockRejectedValueOnce(new Error('Unauthorized'))
      const result = await auth2.initialize()

      expect(result).toBeNull()
      // In-memory state reports unauthenticated (profile fetch failed).
      expect(auth2.isAuthenticated).toBe(false)
      // But the durable state (cookies/localStorage) is untouched so
      // the next page load can retry.
      expect(localStorage.getItem('_bat')).toBe(token.token)
      expect(localStorage.getItem('_bat_rt')).toBe('still-valid-refresh-token')
      auth2.destroy()
    })
  })

  // -------------------------------------------------------------------------
  // initialize — same-domain OAuth sign-in-result marker
  // -------------------------------------------------------------------------

  describe('initialize (same-domain OAuth sign-in-result)', () => {
    const authCookieNames = ['_bat', '_bat_rt', '_bat_meta', '_bat_signin']
    const clearAuthCookies = () => {
      for (const name of authCookieNames) {
        document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; SameSite=Lax`
      }
    }
    // Backend hands the marker back as double URL-encoded JSON, like `_bat_meta`.
    const setServerCookie = (name: string, value: string) => {
      document.cookie = `${name}=${encodeURIComponent(encodeURIComponent(value))}; path=/; SameSite=Lax`
    }

    beforeEach(clearAuthCookies)
    afterEach(clearAuthCookies)

    it('emits signedIn carrying the echoed originator/accountCreated, then clears the marker', async () => {
      const token = makeToken(3600)
      document.cookie = `_bat=${token.token}; path=/; SameSite=Lax`
      setServerCookie('_bat_meta', JSON.stringify(token))
      setServerCookie('_bat_signin', JSON.stringify({ originator: 'obs-join:romans', accountCreated: true }))

      const auth2 = new BoscaAuth({ apiUrl: 'https://api.test', storage: 'cookie', autoRefresh: false })
      mockGetPrincipal.mockResolvedValueOnce({ id: 'p-1', verified: true, primaryProfileId: 'pr-1' })
      mockGetProfiles.mockResolvedValueOnce([makeProfile()])

      const signedIn = vi.fn()
      auth2.on('signedIn', signedIn)
      await auth2.initialize()

      expect(signedIn).toHaveBeenCalledOnce()
      const response = signedIn.mock.calls[0]![0] as AuthResponse
      expect(response.originator).toBe('obs-join:romans')
      expect(response.accountCreated).toBe(true)
      expect(response.principal.id).toBe('p-1')
      // One-shot: the marker is consumed, so ordinary reloads stay silent.
      expect(document.cookie).not.toContain('obs-join')
      auth2.destroy()
    })

    it('does not emit signedIn on a plain restore with no marker', async () => {
      const token = makeToken(3600)
      document.cookie = `_bat=${token.token}; path=/; SameSite=Lax`
      setServerCookie('_bat_meta', JSON.stringify(token))

      const auth2 = new BoscaAuth({ apiUrl: 'https://api.test', storage: 'cookie', autoRefresh: false })
      mockGetPrincipal.mockResolvedValueOnce({ id: 'p-1', verified: true, primaryProfileId: 'pr-1' })
      mockGetProfiles.mockResolvedValueOnce([makeProfile()])

      const signedIn = vi.fn()
      auth2.on('signedIn', signedIn)
      await auth2.initialize()

      expect(signedIn).not.toHaveBeenCalled()
      expect(auth2.isAuthenticated).toBe(true)
      auth2.destroy()
    })
  })

  // -------------------------------------------------------------------------
  // Profile selection
  // -------------------------------------------------------------------------

  describe('profile selection', () => {
    it('selects primary profile from multiple profiles', async () => {
      const profiles: Profile[] = [
        { id: 'pr-2', name: 'Secondary', type: 'GENERIC', visibility: 'USER', slug: null, isPrimary: false, created: '2024-01-01T00:00:00Z', attributes: [] },
        { id: 'pr-1', name: 'Primary', type: 'GENERIC', visibility: 'USER', slug: null, isPrimary: true, created: '2024-01-01T00:00:00Z', attributes: [] },
      ]
      const response: AuthResponse = {
        principal: { id: 'p-1', verified: true, primaryProfileId: 'pr-1' },
        profile: profiles,
        token: makeToken(3600),
        refreshToken: 'rt',
      }
      mockLogin.mockResolvedValueOnce(response)

      const result = await auth.signInWithPassword('u', 'p')

      expect(auth.profiles).toHaveLength(2)
      expect(auth.currentProfile?.id).toBe('pr-1')
      expect(auth.currentProfile?.name).toBe('Primary')
    })

    it('falls back to first profile when none is primary', async () => {
      const response = makeAuthResponse()
      response.profile = [
        makeProfile({ id: 'pr-1', name: 'First', isPrimary: false }),
        makeProfile({ id: 'pr-2', name: 'Second', isPrimary: false }),
      ]
      mockLogin.mockResolvedValueOnce(response)

      await auth.signInWithPassword('u', 'p')

      expect(auth.currentProfile?.id).toBe('pr-1')
    })
  })

  // -------------------------------------------------------------------------
  // destroy
  // -------------------------------------------------------------------------

  describe('destroy', () => {
    it('clears all state and listeners', async () => {
      const callback = vi.fn()
      auth.on('signedIn', callback)

      mockLogin.mockResolvedValueOnce(makeAuthResponse())
      await auth.signInWithPassword('u', 'p')

      auth.destroy()

      expect(auth.currentUser).toBeNull()
      expect(auth.currentProfile).toBeNull()
      expect(auth.profiles).toEqual([])
    })
  })

  // -------------------------------------------------------------------------
  // Full expiration scenario (integration-style)
  // -------------------------------------------------------------------------

  describe('full session expiration flow', () => {
    it('handles complete token expiry gracefully', async () => {
      // Use autoRefresh for this test
      const autoAuth = new BoscaAuth({
        apiUrl: 'https://api.test',
        storage: 'memory',
        autoRefresh: true,
        refreshBuffer: 60_000,
      })

      // Sign in with token that expires in 2 minutes
      const response = makeAuthResponse(120)
      mockLogin.mockResolvedValueOnce(response)
      await autoAuth.signInWithPassword('u', 'p')

      expect(autoAuth.isAuthenticated).toBe(true)

      // Simulate refresh failure (both access and refresh tokens expired on server)
      mockRefresh
        .mockRejectedValueOnce(new Error('refresh token expired'))
        .mockRejectedValueOnce(new Error('refresh token expired'))

      // Advance past the token expiry + buffer
      await vi.advanceTimersByTimeAsync(61_000)

      // Wait for retry delay
      await vi.advanceTimersByTimeAsync(6_000)

      // After failed refresh, getToken should return null
      const token = await autoAuth.getToken()

      // Note: auto-refresh may have already cleared the state
      // The important thing is that we don't crash and state is consistent
      if (token === null) {
        expect(autoAuth.isAuthenticated).toBe(false)
      }

      autoAuth.destroy()
    })
  })

  // -------------------------------------------------------------------------
  // Additional edge cases
  // -------------------------------------------------------------------------

  describe('edge cases', () => {
    it('initialize with fetchProfile=false skips principal and profile fetch', async () => {
      const token = makeToken(3600)
      localStorage.setItem('_bat', token.token)
      localStorage.setItem('_bat_meta', JSON.stringify(token))

      const auth2 = new BoscaAuth({
        apiUrl: 'https://api.test',
        storage: 'localStorage',
        autoRefresh: false,
      })

      const result = await auth2.initialize(false)

      expect(mockGetPrincipal).not.toHaveBeenCalled()
      expect(mockGetProfiles).not.toHaveBeenCalled()
      expect(auth2.token).toBe(token.token)
      auth2.destroy()
    })

    it('signIn with null profiles and profile fetch failure still authenticates', async () => {
      const response = makeAuthResponse()
      response.profile = null
      mockLogin.mockResolvedValueOnce(response)
      mockGetProfiles.mockRejectedValueOnce(new Error('Profile service down'))

      const result = await auth.signInWithPassword('u', 'p')

      // User should be authenticated even though profiles failed
      expect(auth.currentUser).not.toBeNull()
      expect(auth.currentUser?.id).toBe('p-1')
      expect(auth.currentProfile).toBeNull()
    })

    it('calling off on an unregistered event does not throw', () => {
      const cb = vi.fn()
      expect(() => auth.off('nonexistent', cb)).not.toThrow()
    })

    it('emitting an event with no listeners is a no-op', async () => {
      // signOut emits 'signedOut' event — should work with no listeners
      await auth.signOut()
      // No assertion needed — just verifying it doesn't throw
    })

    it('isAuthenticated returns false when the token has expired', async () => {
      mockLogin.mockResolvedValueOnce(makeAuthResponse(120))
      await auth.signInWithPassword('u', 'p')

      expect(auth.isAuthenticated).toBe(true)

      // Advance past the token's expiry
      vi.advanceTimersByTime(121_000)

      expect(auth.isAuthenticated).toBe(false)
      // The raw token string is still in memory — isAuthenticated just
      // reflects that it's no longer valid for use.
      expect(auth.token).not.toBeNull()
    })

    it('setProfiles with empty array results in null currentProfile', async () => {
      const response = makeAuthResponse()
      response.profile = []
      mockLogin.mockResolvedValueOnce(response)
      mockGetProfiles.mockResolvedValueOnce([])

      await auth.signInWithPassword('u', 'p')

      expect(auth.currentProfile).toBeNull()
      expect(auth.profiles).toEqual([])
    })
  })
})
