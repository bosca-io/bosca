import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createApp, type App } from 'vue'
import { setupNuxtAuth, useAuth } from './nuxt'
import type { AuthResponse, BoscaToken, Profile } from './types'

// Mock the graphql module (used internally by BoscaAuth)
vi.mock('./graphql', () => ({
  loginWithPassword: vi.fn(),
  refreshToken: vi.fn(),
  exchangeToken: vi.fn(),
  forgotPassword: vi.fn(),
  resetPassword: vi.fn(),
  signupWithPassword: vi.fn(),
  signupThirdParty: vi.fn(),
  verifyEmail: vi.fn(),
  resendVerification: vi.fn(),
  getCurrentPrincipal: vi.fn(),
  getCurrentProfiles: vi.fn(),
  editProfile: vi.fn(),
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

const mockGetPrincipal = vi.mocked(graphql.getCurrentPrincipal)
const mockGetProfiles = vi.mocked(graphql.getCurrentProfiles)
const mockExchange = vi.mocked(graphql.exchangeToken)
const mockGetExchange = vi.mocked(oauth.getExchangeTokenFromUrl)

function makeToken(expiresInSeconds: number): BoscaToken {
  const now = Math.floor(Date.now() / 1000)
  return { token: `jwt-${expiresInSeconds}`, expiresAt: now + expiresInSeconds, issuedAt: now }
}

function makeProfile(): Profile {
  return {
    id: 'pr-1', name: 'Test User', type: 'GENERIC', visibility: 'USER',
    slug: null, isPrimary: true, created: '2024-01-01T00:00:00Z', attributes: [],
  }
}

function makeAuthResponse(): AuthResponse {
  return {
    principal: { id: 'p-1', verified: true, primaryProfileId: 'pr-1' },
    profile: [makeProfile()],
    token: makeToken(3600),
    refreshToken: 'refresh-token',
  }
}

function createMockNuxtApp(): { nuxtApp: { vueApp: App }; app: App } {
  const app = createApp({ render: () => null })
  return { nuxtApp: { vueApp: app }, app }
}

describe('setupNuxtAuth', () => {
  let authInstance: Awaited<ReturnType<typeof setupNuxtAuth>> | null = null
  let app: App | null = null

  beforeEach(() => {
    vi.resetAllMocks()
    localStorage.clear()
    mockGetExchange.mockReturnValue(null)
  })

  afterEach(() => {
    authInstance?.destroy()
    authInstance = null
    app = null
  })

  it('finishes initialization when reading the redirect result fails', async () => {
    mockGetExchange.mockImplementationOnce(() => { throw new Error('invalid redirect') })
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const mock = createMockNuxtApp()
      authInstance = await setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'memory' })
      mock.app.runWithContext(() => expect(useAuth().isLoading.value).toBe(false))
      expect(error).toHaveBeenCalledWith('Auth initialization failed:', expect.any(Error))
    } finally { error.mockRestore() }
  })

  it('creates auth instance and makes it available via useAuth', async () => {
    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'memory' })

    expect(authInstance).toBeDefined()
    app.runWithContext(() => {
      const state = useAuth()
      expect(state).toBeDefined()
      expect(state.auth).toBe(authInstance)
    })
  })

  it('sets isLoading to false after init', async () => {
    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'memory' })

    app.runWithContext(() => {
      expect(useAuth().isLoading.value).toBe(false)
    })
  })

  it('starts with unauthenticated state when no stored token', async () => {
    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'memory' })

    app.runWithContext(() => {
      const state = useAuth()
      expect(state.user.value).toBeNull()
      expect(state.profile.value).toBeNull()
      expect(state.isAuthenticated.value).toBe(false)
    })
  })

  it('restores session from storage and fetches principal and profiles', async () => {
    const token = makeToken(3600)
    localStorage.setItem('_bat', token.token)
    localStorage.setItem('_bat_meta', JSON.stringify(token))

    mockGetPrincipal.mockResolvedValueOnce({ id: 'p-1', verified: true, primaryProfileId: 'pr-1' })
    mockGetProfiles.mockResolvedValueOnce([makeProfile()])

    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'localStorage' })

    expect(authInstance.currentProfile?.name).toBe('Test User')
    expect(authInstance.currentUser?.id).toBe('p-1')
    app.runWithContext(() => {
      const state = useAuth()
      expect(state.profile.value?.name).toBe('Test User')
      expect(state.isAuthenticated.value).toBe(true)
    })
  })

  it('respects fetchProfileOnInit: false', async () => {
    const token = makeToken(3600)
    localStorage.setItem('_bat', token.token)
    localStorage.setItem('_bat_meta', JSON.stringify(token))

    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, {
      apiUrl: 'https://api.test',
      storage: 'localStorage',
      fetchProfileOnInit: false,
    })

    expect(mockGetProfiles).not.toHaveBeenCalled()
  })

  it('handles an OAuth redirect during setup', async () => {
    mockGetExchange.mockReturnValueOnce('exchange-token')
    mockExchange.mockResolvedValueOnce(makeAuthResponse())

    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'memory' })

    expect(mockExchange).toHaveBeenCalledWith('https://api.test', 'exchange-token')
    expect(authInstance.isAuthenticated).toBe(true)
    app.runWithContext(() => {
      const state = useAuth()
      expect(state.isAuthenticated.value).toBe(true)
      expect(state.user.value?.id).toBe('p-1')
    })
  })

  it('invokes onSignedIn with the response during the OAuth redirect handled on init', async () => {
    // The redirect's `signedIn` fires synchronously inside setupNuxtAuth, before
    // the caller regains control — so this asserts the option's listener is
    // registered early enough to observe a first-time signup's accountCreated.
    mockGetExchange.mockReturnValueOnce('exchange-token')
    mockExchange.mockResolvedValueOnce({ ...makeAuthResponse(), accountCreated: true, originator: null })

    const onSignedIn = vi.fn()
    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, {
      apiUrl: 'https://api.test',
      storage: 'memory',
      onSignedIn,
    })

    expect(onSignedIn).toHaveBeenCalledTimes(1)
    expect(onSignedIn.mock.calls[0][0].accountCreated).toBe(true)
  })

  it('keeps an existing selected session instead of exchanging a URL token', async () => {
    const token = makeToken(3600)
    localStorage.setItem('_bat', token.token)
    localStorage.setItem('_bat_meta', JSON.stringify(token))
    localStorage.setItem('_bat_rt', 'refresh')

    mockGetPrincipal.mockResolvedValueOnce({ id: 'p-1', verified: true, primaryProfileId: 'pr-1' })
    mockGetProfiles.mockResolvedValueOnce([makeProfile()])
    mockGetExchange.mockReturnValueOnce('exchange-token')
    mockExchange.mockResolvedValueOnce(makeAuthResponse())

    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'localStorage' })

    expect(authInstance.isAuthenticated).toBe(true)
    expect(mockExchange).not.toHaveBeenCalled()
    expect(mockGetPrincipal).toHaveBeenCalledWith('https://api.test', token.token)
  })

  it('does not exchange a URL token when the configured custom session is valid', async () => {
    const token = makeToken(3600)
    localStorage.setItem('_bat_preview', token.token)
    localStorage.setItem('_bat_preview_meta', JSON.stringify(token))
    localStorage.setItem('_bat_preview_rt', 'preview-refresh')

    mockGetExchange.mockReturnValueOnce('foreign-exchange-token')
    mockGetPrincipal.mockResolvedValueOnce({ id: 'p-1', verified: true, primaryProfileId: 'pr-1' })
    mockGetProfiles.mockResolvedValueOnce([makeProfile()])

    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, {
      apiUrl: 'https://api.test',
      tokenName: '_bat_preview',
      storage: 'localStorage',
    })

    expect(mockGetPrincipal).toHaveBeenCalledWith('https://api.test', token.token)
    expect(mockExchange).not.toHaveBeenCalled()
    expect(authInstance.isAuthenticated).toBe(true)
  })

  it('exchanges when the configured session cannot restore an authenticated principal', async () => {
    const token = makeToken(3600)
    localStorage.setItem('_bat_preview', token.token)
    localStorage.setItem('_bat_preview_meta', JSON.stringify(token))
    localStorage.setItem('_bat_preview_rt', 'stale-refresh')

    mockGetExchange.mockReturnValueOnce('preview-exchange-token')
    mockGetPrincipal.mockRejectedValueOnce(new Error('Session revoked'))
    mockExchange.mockResolvedValueOnce(makeAuthResponse())

    const consoleDebug = vi.spyOn(console, 'debug').mockImplementation(() => undefined)
    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, {
      apiUrl: 'https://api.test',
      tokenName: '_bat_preview',
      storage: 'localStorage',
    })

    expect(mockExchange).toHaveBeenCalledWith('https://api.test', 'preview-exchange-token')
    expect(authInstance.isAuthenticated).toBe(true)
    consoleDebug.mockRestore()
  })

  it('exchanges a custom-prefix OAuth return instead of restoring the unrelated default session', async () => {
    const defaultToken = makeToken(3600)
    localStorage.setItem('_bat', defaultToken.token)
    localStorage.setItem('_bat_meta', JSON.stringify(defaultToken))
    localStorage.setItem('_bat_rt', 'default-refresh')

    mockGetExchange.mockReturnValueOnce('preview-exchange-token')
    mockExchange.mockResolvedValueOnce(makeAuthResponse())

    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, {
      apiUrl: 'https://api.test',
      tokenName: '_bat_preview',
      storage: 'localStorage',
    })

    expect(mockExchange).toHaveBeenCalledWith('https://api.test', 'preview-exchange-token')
    expect(localStorage.getItem('_bat_preview')).toBe(makeAuthResponse().token.token)
    expect(localStorage.getItem('_bat')).toBe(defaultToken.token)
    expect(authInstance.isAuthenticated).toBe(true)
  })

  it.skipIf(Object.getOwnPropertyDescriptor(globalThis, 'window')?.configurable === false)('skips initialization on server when skipInitOnServer is true', async () => {
    const originalWindow = globalThis.window
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    // @ts-expect-error: simulating server environment
    delete globalThis.window

    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, {
      apiUrl: 'https://api.test',
      tokenName: '_bat_preview',
      storage: 'memory',
      skipInitOnServer: true,
    })

    expect(mockGetPrincipal).not.toHaveBeenCalled()
    expect(mockGetProfiles).not.toHaveBeenCalled()
    expect(mockExchange).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()

    app.runWithContext(() => {
      const state = useAuth()
      expect(state.isLoading.value).toBe(false)
      expect(state.isAuthenticated.value).toBe(false)
      expect(state.user.value).toBeNull()
    })

    globalThis.window = originalWindow
    vi.unstubAllGlobals()
  })

  it.skipIf(Object.getOwnPropertyDescriptor(globalThis, 'window')?.configurable === false)('still initializes on server when skipInitOnServer is false', async () => {
    const originalWindow = globalThis.window
    // @ts-expect-error: simulating server environment
    delete globalThis.window

    const token = makeToken(3600)
    localStorage.setItem('_bat', token.token)
    localStorage.setItem('_bat_meta', JSON.stringify(token))

    mockGetPrincipal.mockResolvedValueOnce({ id: 'p-1', verified: true, primaryProfileId: 'pr-1' })
    mockGetProfiles.mockResolvedValueOnce([makeProfile()])

    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, {
      apiUrl: 'https://api.test',
      storage: 'localStorage',
      skipInitOnServer: false,
    })

    // Without skipInitOnServer, initialization proceeds even on server
    // (though in this test localStorage is available, so it finds the token)
    app.runWithContext(() => {
      expect(useAuth().isLoading.value).toBe(false)
    })

    globalThis.window = originalWindow
  })

  it('handles initialization failure gracefully', async () => {
    const token = makeToken(3600)
    localStorage.setItem('_bat', token.token)
    localStorage.setItem('_bat_meta', JSON.stringify(token))

    mockGetPrincipal.mockRejectedValueOnce(new Error('Network error'))
    mockGetProfiles.mockRejectedValueOnce(new Error('Network error'))
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'localStorage' })

    expect(authInstance).toBeDefined()
    app.runWithContext(() => {
      expect(useAuth().isLoading.value).toBe(false)
    })
    consoleSpy.mockRestore()
  })

  it('updates reactive state on signedIn event', async () => {
    vi.mocked(graphql.loginWithPassword).mockResolvedValueOnce(makeAuthResponse())

    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'memory' })

    app.runWithContext(() => {
      const state = useAuth()
      expect(state.isAuthenticated.value).toBe(false)
    })

    await authInstance.signInWithPassword('user@test.com', 'pass')

    app.runWithContext(() => {
      const state = useAuth()
      expect(state.isAuthenticated.value).toBe(true)
      expect(state.user.value?.id).toBe('p-1')
      expect(state.profile.value?.name).toBe('Test User')
    })
  })

  it('updates reactive state on signedOut event', async () => {
    vi.mocked(graphql.loginWithPassword).mockResolvedValueOnce(makeAuthResponse())

    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'memory' })
    await authInstance.signInWithPassword('user@test.com', 'pass')

    app.runWithContext(() => {
      const state = useAuth()
      expect(state.isAuthenticated.value).toBe(true)
    })

    await authInstance.signOut()

    app.runWithContext(() => {
      const state = useAuth()
      expect(state.isAuthenticated.value).toBe(false)
      expect(state.user.value).toBeNull()
      expect(state.profile.value).toBeNull()
    })
  })

  it('updates reactive state on profileUpdated event', async () => {
    vi.mocked(graphql.loginWithPassword).mockResolvedValueOnce(makeAuthResponse())

    const mock = createMockNuxtApp()
    app = mock.app
    authInstance = await setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'memory' })
    await authInstance.signInWithPassword('user@test.com', 'pass')

    const updatedProfile = { ...makeProfile(), name: 'Updated Name' }
    mockGetProfiles.mockResolvedValueOnce([updatedProfile])

    await authInstance.getProfiles()

    app.runWithContext(() => {
      const state = useAuth()
      expect(state.profile.value?.name).toBe('Updated Name')
    })
  })
})

describe('useAuth', () => {
  it('returns state after setupNuxtAuth has been called', async () => {
    vi.resetAllMocks()
    vi.mocked(oauth.getExchangeTokenFromUrl).mockReturnValue(null)

    const mock = createMockNuxtApp()
    const auth = await setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'memory' })

    mock.app.runWithContext(() => {
      const state = useAuth()
      expect(state).toBeDefined()
      expect(state.auth).toBeDefined()
      expect(state.isLoading.value).toBe(false)
    })

    auth.destroy()
  })

  it('throws when called without setupNuxtAuth', () => {
    const app = createApp({ render: () => null })
    app.runWithContext(() => {
      expect(() => useAuth()).toThrow('Auth not initialized')
    })
  })

  it('throws when setupNuxtAuth is called twice on the same app', async () => {
    vi.resetAllMocks()
    vi.mocked(oauth.getExchangeTokenFromUrl).mockReturnValue(null)

    const mock = createMockNuxtApp()
    const auth = await setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'memory' })

    await expect(
      setupNuxtAuth(mock.nuxtApp, { apiUrl: 'https://api.test', storage: 'memory' }),
    ).rejects.toThrow('setupNuxtAuth has already been called')

    auth.destroy()
  })
})
