/**
 * Tests for SSR (server-side rendering) scenarios where browser globals
 * like `window`, `document`, and `localStorage` are unavailable.
 * Uses vi.stubGlobal to simulate SSR by nullifying browser globals.
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { startOAuthRedirect, getExchangeTokenFromUrl, getLinkTokenFromUrl, getLinkMethodsFromUrl } from './oauth'
import { CookieStorage, LocalStorageStorage } from './storage'

describe('OAuth SSR behavior', () => {
  it('link challenges and proof methods are absent during SSR', () => {
    vi.stubGlobal('window', undefined)
    expect(getLinkTokenFromUrl()).toBeNull()
    expect(getLinkMethodsFromUrl()).toBeNull()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('startOAuthRedirect does nothing when window is falsy', () => {
    vi.stubGlobal('window', undefined)
    expect(() => startOAuthRedirect('https://api.test', {
      provider: 'GOOGLE',
      redirectUrl: 'https://app.test/',
    })).not.toThrow()
  })

  it('startOAuthRedirect uses empty string for redirectUrl when window is falsy and no redirectUrl provided', () => {
    vi.stubGlobal('window', undefined)
    expect(() => startOAuthRedirect('https://api.test', {
      provider: 'GOOGLE',
    })).not.toThrow()
  })

  it('getExchangeTokenFromUrl returns null when window is falsy', () => {
    vi.stubGlobal('window', undefined)
    expect(getExchangeTokenFromUrl()).toBeNull()
  })
})

describe('CookieStorage SSR behavior', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns null for all getters when document is falsy', () => {
    vi.stubGlobal('document', undefined)
    const storage = new CookieStorage('_bat')
    expect(storage.getToken()).toBeNull()
    expect(storage.getRefreshToken()).toBeNull()
    expect(storage.getTokenMetadata()).toBeNull()
  })

  it('setters are no-ops when document is falsy', () => {
    vi.stubGlobal('document', undefined)
    const storage = new CookieStorage('_bat')
    expect(() => storage.setToken('token')).not.toThrow()
    expect(() => storage.setRefreshToken('refresh')).not.toThrow()
    expect(() => storage.setTokenMetadata({ expiresAt: 1, issuedAt: 1 })).not.toThrow()
  })

  it('clear is a no-op when document is falsy', () => {
    vi.stubGlobal('document', undefined)
    const storage = new CookieStorage('_bat')
    expect(() => storage.clear()).not.toThrow()
  })
})

describe('LocalStorageStorage SSR behavior', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns null for all getters when localStorage is falsy', () => {
    vi.stubGlobal('localStorage', undefined)
    const storage = new LocalStorageStorage('_bat')
    expect(storage.getToken()).toBeNull()
    expect(storage.getRefreshToken()).toBeNull()
    expect(storage.getTokenMetadata()).toBeNull()
  })

  it('setters are no-ops when localStorage is falsy', () => {
    vi.stubGlobal('localStorage', undefined)
    const storage = new LocalStorageStorage('_bat')
    expect(() => storage.setToken('token')).not.toThrow()
    expect(() => storage.setRefreshToken('refresh')).not.toThrow()
    expect(() => storage.setTokenMetadata({ expiresAt: 1, issuedAt: 1 })).not.toThrow()
  })

  it('clear is a no-op when localStorage is falsy', () => {
    vi.stubGlobal('localStorage', undefined)
    const storage = new LocalStorageStorage('_bat')
    expect(() => storage.clear()).not.toThrow()
  })
})
