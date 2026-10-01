import type { BoscaAuthConfig, SignInResult, TokenMetadata } from './types'

/**
 * Abstraction for persisting authentication tokens across page loads.
 * Implementations handle the specifics of where and how tokens are
 * stored (cookies, localStorage, or in-memory for SSR/testing).
 */
export interface TokenStorage {
  /** Retrieve the stored access token string, or null if absent */
  getToken(): string | null
  /** Persist the access token string, optionally expiring at the given Unix epoch (seconds) */
  setToken(token: string, expiresAtSec?: number): void
  /** Retrieve the stored refresh token string, or null if absent */
  getRefreshToken(): string | null
  /** Persist the refresh token string */
  setRefreshToken(token: string): void
  /** Retrieve stored token metadata (expiry times) for scheduling refresh on reload */
  getTokenMetadata(): TokenMetadata | null
  /** Persist token metadata alongside the access token */
  setTokenMetadata(metadata: TokenMetadata): void
  /**
   * Retrieve the one-shot sign-in-result marker the backend writes on a
   * same-domain OAuth callback (originator + accountCreated), or null when
   * absent. Only the cookie-backed store can ever hold one — the backend has
   * no way to write into localStorage/memory — so other implementations
   * always return null.
   */
  getSignInResult(): SignInResult | null
  /** Remove the one-shot sign-in-result marker once it has been consumed. */
  clearSignInResult(): void
  /** Remove all stored tokens and metadata */
  clear(): void
}

/**
 * Stores tokens in browser cookies, compatible with the existing
 * Bosca server middleware that reads the `_bat` cookie for SSR auth.
 * The refresh token is stored in a separate `_brt` cookie.
 */
export class CookieStorage implements TokenStorage {
  private readonly tokenName: string
  private readonly refreshTokenName: string
  private readonly metadataName: string
  private readonly signInResultName: string
  private readonly domain: string | undefined

  constructor(tokenName: string, domain?: string) {
    this.tokenName = tokenName
    this.refreshTokenName = tokenName + '_rt'
    this.metadataName = tokenName + '_meta'
    this.signInResultName = tokenName + '_signin'
    this.domain = domain
  }

  getToken(): string | null {
    return this.getCookie(this.tokenName)
  }

  setToken(token: string, expiresAtSec?: number): void {
    if (expiresAtSec) {
      const expiresAt = new Date(expiresAtSec * 1000).toUTCString()
      this.setCookieWithExpires(this.tokenName, token, expiresAt)
    } else {
      this.setCookie(this.tokenName, token, 1)
    }
  }

  getRefreshToken(): string | null {
    return this.getCookie(this.refreshTokenName)
  }

  setRefreshToken(token: string): void {
    this.setCookie(this.refreshTokenName, token, 365)
  }

  getTokenMetadata(): TokenMetadata | null {
    const raw = this.getCookie(this.metadataName)
    if (!raw) return null
    try {
      return JSON.parse(decodeURIComponent(raw)) as TokenMetadata
    } catch {
      return null
    }
  }

  setTokenMetadata(metadata: TokenMetadata): void {
    this.setCookie(this.metadataName, encodeURIComponent(JSON.stringify(metadata)), 365)
  }

  getSignInResult(): SignInResult | null {
    const raw = this.getCookie(this.signInResultName)
    if (!raw) return null
    try {
      const parsed = JSON.parse(decodeURIComponent(raw)) as Partial<SignInResult>
      return {
        originator: typeof parsed.originator === 'string' ? parsed.originator : null,
        accountCreated: parsed.accountCreated === true,
      }
    } catch {
      return null
    }
  }

  clearSignInResult(): void {
    this.deleteCookie(this.signInResultName)
  }

  clear(): void {
    this.deleteCookie(this.tokenName)
    this.deleteCookie(this.refreshTokenName)
    this.deleteCookie(this.metadataName)
    this.deleteCookie(this.signInResultName)
  }

  private getCookie(name: string): string | null {
    if (!globalThis.document) return null
    const match = document.cookie.match(new RegExp('(?:^|; )' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '=([^;]*)'))
    return match ? decodeURIComponent(match[1]!) : null
  }

  private setCookie(name: string, value: string, days: number): void {
    this.setCookieWithExpires(name, value, new Date(Date.now() + days * 86400000).toUTCString())
  }

  private setCookieWithExpires(name: string, value: string, expires: string): void {
    if (!globalThis.document) return
    let cookie = `${name}=${encodeURIComponent(value)}; expires=${expires}; path=/; SameSite=Lax`
    if (this.domain) {
      cookie += `; domain=${this.domain}`
    }
    document.cookie = cookie
  }

  private deleteCookie(name: string): void {
    if (!globalThis.document) return
    let cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; SameSite=Lax`
    if (this.domain) {
      cookie += `; domain=${this.domain}`
    }
    document.cookie = cookie
  }
}

/**
 * Stores tokens in the browser's localStorage under configurable
 * key names. Suitable for SPAs that don't need SSR cookie access.
 */
export class LocalStorageStorage implements TokenStorage {
  private readonly tokenKey: string
  private readonly refreshTokenKey: string
  private readonly metadataKey: string
  constructor(tokenName: string) {
    this.tokenKey = tokenName
    this.refreshTokenKey = tokenName + '_rt'
    this.metadataKey = tokenName + '_meta'
  }

  getToken(): string | null {
    return this.getItem(this.tokenKey)
  }

  setToken(token: string, _expiresAtSec?: number): void {
    this.setItem(this.tokenKey, token)
  }

  getRefreshToken(): string | null {
    return this.getItem(this.refreshTokenKey)
  }

  setRefreshToken(token: string): void {
    this.setItem(this.refreshTokenKey, token)
  }

  getTokenMetadata(): TokenMetadata | null {
    const raw = this.getItem(this.metadataKey)
    if (!raw) return null
    try {
      return JSON.parse(raw) as TokenMetadata
    } catch {
      return null
    }
  }

  setTokenMetadata(metadata: TokenMetadata): void {
    this.setItem(this.metadataKey, JSON.stringify(metadata))
  }

  // The sign-in-result marker is a same-domain OAuth cookie the backend writes;
  // it never reaches localStorage, so there is nothing to read or clear.
  getSignInResult(): SignInResult | null {
    return null
  }

  clearSignInResult(): void {}

  clear(): void {
    this.removeItem(this.tokenKey)
    this.removeItem(this.refreshTokenKey)
    this.removeItem(this.metadataKey)
  }

  private getItem(key: string): string | null {
    if (!globalThis.localStorage) return null
    return localStorage.getItem(key)
  }

  private setItem(key: string, value: string): void {
    if (!globalThis.localStorage) return
    localStorage.setItem(key, value)
  }

  private removeItem(key: string): void {
    if (!globalThis.localStorage) return
    localStorage.removeItem(key)
  }
}

/**
 * In-memory token storage for use during SSR or in test environments
 * where persistent browser storage is unavailable.
 */
export class MemoryStorage implements TokenStorage {
  private token: string | null = null
  private refreshToken: string | null = null
  private metadata: TokenMetadata | null = null

  getToken(): string | null {
    return this.token
  }

  setToken(token: string, _expiresAtSec?: number): void {
    this.token = token
  }

  getRefreshToken(): string | null {
    return this.refreshToken
  }

  setRefreshToken(token: string): void {
    this.refreshToken = token
  }

  getTokenMetadata(): TokenMetadata | null {
    return this.metadata
  }

  setTokenMetadata(metadata: TokenMetadata): void {
    this.metadata = metadata
  }

  // No backend can write a cookie into in-memory storage, so there is never a
  // sign-in-result marker to surface here.
  getSignInResult(): SignInResult | null {
    return null
  }

  clearSignInResult(): void {}

  clear(): void {
    this.token = null
    this.refreshToken = null
    this.metadata = null
  }
}

/**
 * Creates the appropriate storage implementation based on the
 * configured storage type, falling back to memory storage when
 * browser APIs are unavailable (SSR). When the config carries a
 * fully-constructed storage instance (e.g. a server-side cookie-backed
 * adapter prepared by an SSR plugin), that instance is used directly.
 */
export function createStorage(config: BoscaAuthConfig): TokenStorage {
  const tokenName = config.tokenName?.trim() || '_bat'
  const storageType = config.storage ?? 'cookie'

  if (typeof storageType !== 'string') {
    return storageType
  }

  switch (storageType) {
    case 'cookie':
      return new CookieStorage(tokenName, tokenName === '_bat' ? config.cookieDomain : undefined)
    case 'localStorage':
      return new LocalStorageStorage(tokenName)
    case 'memory':
      return new MemoryStorage()
    default:
      return new MemoryStorage()
  }
}
