import { describe, it, expect, beforeEach } from 'vitest'
import { MemoryStorage, LocalStorageStorage, CookieStorage, createStorage } from './storage'
import type { BoscaToken } from './types'

const sampleToken: BoscaToken = {
  token: 'jwt-token-string',
  expiresAt: Math.floor(Date.now() / 1000) + 3600,
  issuedAt: Math.floor(Date.now() / 1000),
}

it('stores an expiring token and keeps custom cookie prefixes host scoped', () => {
  const storage = createStorage({ apiUrl: 'https://api.test', storage: 'cookie', tokenName: '_bat_review', cookieDomain: 'another.test' })
  storage.setToken('expiring-session', Math.floor(Date.now() / 1000) + 3600)
  expect(storage.getToken()).toBe('expiring-session')
  storage.clear()
})

// ---------------------------------------------------------------------------
// MemoryStorage
// ---------------------------------------------------------------------------

describe('MemoryStorage', () => {
  let storage: MemoryStorage

  beforeEach(() => {
    storage = new MemoryStorage()
  })

  it('returns null for all fields when empty', () => {
    expect(storage.getToken()).toBeNull()
    expect(storage.getRefreshToken()).toBeNull()
    expect(storage.getTokenMetadata()).toBeNull()
  })

  it('stores and retrieves access token', () => {
    storage.setToken('access-token')
    expect(storage.getToken()).toBe('access-token')
  })

  it('stores and retrieves refresh token', () => {
    storage.setRefreshToken('refresh-token')
    expect(storage.getRefreshToken()).toBe('refresh-token')
  })

  it('stores and retrieves token metadata', () => {
    storage.setTokenMetadata(sampleToken)
    expect(storage.getTokenMetadata()).toEqual(sampleToken)
  })

  it('clears all stored values', () => {
    storage.setToken('t')
    storage.setRefreshToken('rt')
    storage.setTokenMetadata(sampleToken)

    storage.clear()

    expect(storage.getToken()).toBeNull()
    expect(storage.getRefreshToken()).toBeNull()
    expect(storage.getTokenMetadata()).toBeNull()
  })

  it('overwrites existing values', () => {
    storage.setToken('first')
    storage.setToken('second')
    expect(storage.getToken()).toBe('second')
  })

  it('never surfaces a sign-in-result marker (backend cannot write here)', () => {
    expect(storage.getSignInResult()).toBeNull()
    expect(() => storage.clearSignInResult()).not.toThrow()
  })
})

// ---------------------------------------------------------------------------
// LocalStorageStorage
// ---------------------------------------------------------------------------

describe('LocalStorageStorage', () => {
  let storage: LocalStorageStorage

  beforeEach(() => {
    localStorage.clear()
    storage = new LocalStorageStorage('_bat')
  })

  it('returns null for all fields when empty', () => {
    expect(storage.getToken()).toBeNull()
    expect(storage.getRefreshToken()).toBeNull()
    expect(storage.getTokenMetadata()).toBeNull()
  })

  it('stores and retrieves access token in localStorage', () => {
    storage.setToken('access-token')
    expect(storage.getToken()).toBe('access-token')
    expect(localStorage.getItem('_bat')).toBe('access-token')
  })

  it('stores refresh token with _rt suffix', () => {
    storage.setRefreshToken('refresh-token')
    expect(storage.getRefreshToken()).toBe('refresh-token')
    expect(localStorage.getItem('_bat_rt')).toBe('refresh-token')
  })

  it('stores and retrieves token metadata as JSON', () => {
    storage.setTokenMetadata(sampleToken)
    const retrieved = storage.getTokenMetadata()
    expect(retrieved).toEqual(sampleToken)
  })

  it('returns null for corrupted metadata', () => {
    localStorage.setItem('_bat_meta', 'not-json')
    expect(storage.getTokenMetadata()).toBeNull()
  })

  it('clears all keys', () => {
    storage.setToken('t')
    storage.setRefreshToken('rt')
    storage.setTokenMetadata(sampleToken)

    storage.clear()

    expect(localStorage.getItem('_bat')).toBeNull()
    expect(localStorage.getItem('_bat_rt')).toBeNull()
    expect(localStorage.getItem('_bat_meta')).toBeNull()
  })

  it('uses custom token name as key prefix', () => {
    const custom = new LocalStorageStorage('mytoken')
    custom.setToken('val')
    expect(localStorage.getItem('mytoken')).toBe('val')
  })

  it('uses only the configured custom key', () => {
    localStorage.setItem('_bat', 'default')
    const custom = new LocalStorageStorage('_bat_preview')
    expect(custom.getToken()).toBeNull()

    localStorage.setItem('_bat_preview', 'preview')
    expect(custom.getToken()).toBe('preview')
  })

  it('uses the configured prefix for refresh tokens', () => {
    localStorage.setItem('_bat', 'default-access')
    localStorage.setItem('_bat_rt', 'default-refresh')
    localStorage.setItem('_bat_preview_rt', 'preview-refresh')
    const custom = new LocalStorageStorage('_bat_preview')

    expect(custom.getToken()).toBeNull()
    expect(custom.getRefreshToken()).toBe('preview-refresh')
  })

  it('does not expose the default token after custom storage is cleared', () => {
    localStorage.setItem('_bat', 'default-access')
    const custom = new LocalStorageStorage('_bat_preview')
    custom.setToken('preview-access')

    custom.clear()

    expect(custom.getToken()).toBeNull()
  })

  it('never surfaces a sign-in-result marker (backend cannot write here)', () => {
    expect(storage.getSignInResult()).toBeNull()
    expect(() => storage.clearSignInResult()).not.toThrow()
  })
})

// ---------------------------------------------------------------------------
// CookieStorage
// ---------------------------------------------------------------------------

describe('CookieStorage', () => {
  let storage: CookieStorage

  beforeEach(() => {
    // Clear all cookies
    document.cookie.split(';').forEach(c => {
      const name = c.trim().split('=')[0]
      document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; SameSite=Lax`
    })
    storage = new CookieStorage('_bat')
  })

  it('returns null when no cookie exists', () => {
    expect(storage.getToken()).toBeNull()
    expect(storage.getRefreshToken()).toBeNull()
    expect(storage.getTokenMetadata()).toBeNull()
  })

  it('stores and retrieves access token cookie', () => {
    storage.setToken('cookie-token')
    expect(storage.getToken()).toBe('cookie-token')
  })

  it('stores and retrieves refresh token cookie', () => {
    storage.setRefreshToken('cookie-refresh')
    expect(storage.getRefreshToken()).toBe('cookie-refresh')
  })

  it('stores and retrieves token metadata cookie', () => {
    storage.setTokenMetadata(sampleToken)
    const retrieved = storage.getTokenMetadata()
    expect(retrieved).toEqual(sampleToken)
  })

  it('returns null for corrupted metadata cookie', () => {
    document.cookie = '_bat_meta=not-json; path=/; SameSite=Lax'
    expect(storage.getTokenMetadata()).toBeNull()
  })

  it('clears all cookies', () => {
    storage.setToken('t')
    storage.setRefreshToken('rt')
    storage.setTokenMetadata(sampleToken)

    storage.clear()

    expect(storage.getToken()).toBeNull()
    expect(storage.getRefreshToken()).toBeNull()
    expect(storage.getTokenMetadata()).toBeNull()
  })

  it('handles tokens with special characters', () => {
    const specialToken = 'eyJ0eXAi.payload.sig+nal/end='
    storage.setToken(specialToken)
    expect(storage.getToken()).toBe(specialToken)
  })

  it('creates CookieStorage with domain parameter without error', () => {
    // jsdom doesn't persist cookies with non-localhost domains,
    // so we verify the constructor accepts the domain and operations don't throw
    const domainStorage = new CookieStorage('_bat', '.example.com')
    expect(() => domainStorage.setToken('test-token')).not.toThrow()
    expect(() => domainStorage.setRefreshToken('refresh')).not.toThrow()
    expect(() => domainStorage.clear()).not.toThrow()
  })

  it('uses only the configured custom cookie', () => {
    document.cookie = '_bat=default; path=/; SameSite=Lax'
    const custom = new CookieStorage('_bat_preview')
    expect(custom.getToken()).toBeNull()

    document.cookie = '_bat_preview=preview; path=/; SameSite=Lax'
    expect(custom.getToken()).toBe('preview')
  })

  it('uses the configured cookie prefix for refresh tokens', () => {
    document.cookie = '_bat=default-access; path=/; SameSite=Lax'
    document.cookie = '_bat_rt=default-refresh; path=/; SameSite=Lax'
    document.cookie = '_bat_preview_rt=preview-refresh; path=/; SameSite=Lax'
    const custom = new CookieStorage('_bat_preview')

    expect(custom.getToken()).toBeNull()
    expect(custom.getRefreshToken()).toBe('preview-refresh')
  })

  it('does not expose the default cookies after a custom client is cleared', () => {
    document.cookie = '_bat=default-access; path=/; SameSite=Lax'
    const custom = new CookieStorage('_bat_preview')
    custom.setToken('preview-access')

    custom.clear()

    expect(custom.getToken()).toBeNull()
  })

  it('round-trips metadata with special characters in token', () => {
    const meta: BoscaToken = {
      token: 'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWV9.sig+nature/end==',
      expiresAt: 1700000000,
      issuedAt: 1699999000,
    }
    storage.setTokenMetadata(meta)
    const retrieved = storage.getTokenMetadata()
    expect(retrieved).toEqual(meta)
    expect(retrieved).toHaveProperty('token', meta.token)
  })

  // The backend hands the marker back as double URL-encoded JSON, exactly like
  // `_bat_meta` (URLEncoder.encode server-side + one more encode on the wire,
  // mirrored by the double-decode in getSignInResult).
  const writeServerSignInCookie = (payload: unknown) => {
    document.cookie = `_bat_signin=${encodeURIComponent(encodeURIComponent(JSON.stringify(payload)))}; path=/; SameSite=Lax`
  }

  it('reads the one-shot sign-in-result marker cookie', () => {
    writeServerSignInCookie({ originator: 'obs-join:romans', accountCreated: true })
    expect(storage.getSignInResult()).toEqual({ originator: 'obs-join:romans', accountCreated: true })
  })

  it('normalizes a null originator and a missing accountCreated', () => {
    writeServerSignInCookie({ originator: null })
    expect(storage.getSignInResult()).toEqual({ originator: null, accountCreated: false })
  })

  it('returns null for a corrupted sign-in-result cookie', () => {
    document.cookie = '_bat_signin=not-json; path=/; SameSite=Lax'
    expect(storage.getSignInResult()).toBeNull()
  })

  it('clearSignInResult removes the marker', () => {
    writeServerSignInCookie({ originator: 'obs-join:x', accountCreated: false })
    storage.clearSignInResult()
    expect(storage.getSignInResult()).toBeNull()
  })

  it('clear() also removes the sign-in-result marker', () => {
    writeServerSignInCookie({ originator: 'obs-join:x', accountCreated: false })
    storage.clear()
    expect(storage.getSignInResult()).toBeNull()
  })
})

// ---------------------------------------------------------------------------
// createStorage factory
// ---------------------------------------------------------------------------

describe('createStorage', () => {
  it('uses the configured cookie prefix directly', () => {
    const storage = createStorage({ apiUrl: '', tokenName: '_bat_preview', storage: 'localStorage' })
    storage.setToken('preview-token')
    expect(localStorage.getItem('_bat_preview')).toBe('preview-token')
  })

  it('creates CookieStorage by default', () => {
    const s = createStorage({ apiUrl: '' })
    expect(s).toBeInstanceOf(CookieStorage)
  })

  it('creates CookieStorage when explicitly requested', () => {
    const s = createStorage({ apiUrl: '', storage: 'cookie' })
    expect(s).toBeInstanceOf(CookieStorage)
  })

  it('creates LocalStorageStorage', () => {
    const s = createStorage({ apiUrl: '', storage: 'localStorage' })
    expect(s).toBeInstanceOf(LocalStorageStorage)
  })

  it('creates MemoryStorage', () => {
    const s = createStorage({ apiUrl: '', storage: 'memory' })
    expect(s).toBeInstanceOf(MemoryStorage)
  })

  it('falls back to MemoryStorage for unknown type', () => {
    const s = createStorage({ apiUrl: '', storage: 'unknown' as any })
    expect(s).toBeInstanceOf(MemoryStorage)
  })

  it('returns a pre-constructed TokenStorage instance unchanged', () => {
    const instance = new MemoryStorage()
    instance.setToken('preloaded')
    const s = createStorage({ apiUrl: '', storage: instance })
    expect(s).toBe(instance)
    expect(s.getToken()).toBe('preloaded')
  })
})
