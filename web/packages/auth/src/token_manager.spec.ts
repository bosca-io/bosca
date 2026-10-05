import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { TokenManager } from './token_manager'
import { MemoryStorage } from './storage'
import { TokenExpiredError } from './errors'
import type { AuthResponse, BoscaToken, TokenMetadata } from './types'

// Mock the graphql module
vi.mock('./graphql', () => ({
  refreshToken: vi.fn(),
}))

import * as graphql from './graphql'
const mockRefreshToken = vi.mocked(graphql.refreshToken)
const browserLocks = navigator.locks
const locksDescriptor = Object.getOwnPropertyDescriptor(navigator, 'locks')

function makeToken(expiresInSeconds: number): BoscaToken {
  const now = Math.floor(Date.now() / 1000)
  return {
    token: `jwt-${expiresInSeconds}`,
    expiresAt: now + expiresInSeconds,
    issuedAt: now,
  }
}

function makeExpiredToken(): BoscaToken {
  const nowSec = Math.floor(Date.now() / 1000) - 100
  return {
    token: 'jwt-expired',
    expiresAt: nowSec,
    issuedAt: nowSec - 100,
  }
}

function makeAuthResponse(expiresInSeconds: number, refreshTokenStr: string | null = 'new-refresh'): AuthResponse {
  return {
    principal: { id: 'p-1', verified: true, primaryProfileId: null },
    profile: null,
    token: makeToken(expiresInSeconds),
    refreshToken: refreshTokenStr,
    accountCreated: false,
    originator: null,
  }
}

describe('TokenManager', () => {
  let storage: MemoryStorage
  let events: Array<{ event: string; data?: unknown }>
  let manager: TokenManager

  beforeEach(() => {
    vi.useFakeTimers()
    // Native Web Locks use the browser task queue, which fake timers cannot advance.
    // Exercise that queue separately with real timers below.
    Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
    storage = new MemoryStorage()
    events = []
    mockRefreshToken.mockReset()

    manager = new TokenManager(
      storage,
      'https://api.test',
      60_000, // 60s refresh buffer
      true,   // autoRefresh enabled
      1_000,  // 1s retry delay
      (event, data) => events.push({ event, data }),
    )
  })

  afterEach(() => {
    manager.destroy()
    vi.useRealTimers()
    if (locksDescriptor) Object.defineProperty(navigator, 'locks', locksDescriptor)
    else delete (navigator as { locks?: unknown }).locks
  })

  // -------------------------------------------------------------------------
  // setTokens / getToken
  // -------------------------------------------------------------------------

  describe('setTokens', () => {
    it('stores tokens in memory and storage', () => {
      const response = makeAuthResponse(3600)
      manager.setTokens(response)

      expect(manager.getToken()).toBe(response.token.token)
      expect(storage.getToken()).toBe(response.token.token)
      expect(storage.getRefreshToken()).toBe('new-refresh')
      const meta = storage.getTokenMetadata()
      expect(meta).toMatchObject({ expiresAt: response.token.expiresAt, issuedAt: response.token.issuedAt })
    })

    it('handles null refresh token', () => {
      const response = makeAuthResponse(3600, null)
      manager.setTokens(response)

      expect(manager.getToken()).toBe(response.token.token)
      expect(storage.getRefreshToken()).toBeNull()
    })
  })

  // -------------------------------------------------------------------------
  // consumeSignInResult
  // -------------------------------------------------------------------------

  describe('consumeSignInResult', () => {
    const principal = { id: 'p-1', verified: true, primaryProfileId: null }

    it('returns null when there is no marker', () => {
      manager.setTokens(makeAuthResponse(3600))
      expect(manager.consumeSignInResult(principal, null)).toBeNull()
    })

    it('pairs the marker with the restored token state and clears it once', () => {
      const response = makeAuthResponse(3600)
      manager.setTokens(response)
      vi.spyOn(storage, 'getSignInResult').mockReturnValue({ originator: 'obs-join:romans', accountCreated: true })
      const clear = vi.spyOn(storage, 'clearSignInResult')

      const result = manager.consumeSignInResult(principal, null)

      expect(result).toMatchObject({
        principal,
        profile: null,
        token: {
          token: response.token.token,
          expiresAt: response.token.expiresAt,
          issuedAt: response.token.issuedAt,
        },
        refreshToken: 'new-refresh',
        accountCreated: true,
        originator: 'obs-join:romans',
      })
      expect(clear).toHaveBeenCalledOnce()
    })

    it('returns null and leaves the marker when there is no active token', () => {
      vi.spyOn(storage, 'getSignInResult').mockReturnValue({ originator: 'obs-join:x', accountCreated: false })
      const clear = vi.spyOn(storage, 'clearSignInResult')

      expect(manager.consumeSignInResult(principal, null)).toBeNull()
      expect(clear).not.toHaveBeenCalled()
    })
  })

  // -------------------------------------------------------------------------
  // restore
  // -------------------------------------------------------------------------

  describe('restore', () => {
    it('restores tokens from storage', () => {
      const token = makeToken(3600)
      storage.setToken(token.token)
      storage.setTokenMetadata({ expiresAt: token.expiresAt, issuedAt: token.issuedAt })
      storage.setRefreshToken('stored-refresh')

      const result = manager.restore()

      expect(result).toBe(token.token)
      expect(manager.getToken()).toBe(token.token)
    })

    it('returns null when storage is empty', () => {
      expect(manager.restore()).toBeNull()
    })

    it('returns null when metadata is missing and token is not a valid JWT', () => {
      storage.setToken('orphan-token')
      expect(manager.restore()).toBeNull()
    })

    it('extracts metadata from a bare JWT when _bat_meta is missing', () => {
      const nowSec = Math.floor(Date.now() / 1000)
      const payload = { exp: nowSec + 3600, iat: nowSec }
      const fakeJwt = `eyJ0eXAiOiJKV1QifQ.${btoa(JSON.stringify(payload))}.signature`
      storage.setToken(fakeJwt)

      const result = manager.restore()

      expect(result).toBe(fakeJwt)
      expect(manager.getToken()).toBe(fakeJwt)
      expect(manager.isExpired()).toBe(false)
    })

    it('treats a bare JWT with expired exp as expired after restore', () => {
      const nowSec = Math.floor(Date.now() / 1000)
      const payload = { exp: nowSec - 100, iat: nowSec - 200 }
      const fakeJwt = `eyJ0eXAiOiJKV1QifQ.${btoa(JSON.stringify(payload))}.signature`
      storage.setToken(fakeJwt)

      const result = manager.restore()

      expect(result).toBe(fakeJwt)
      expect(manager.isExpired()).toBe(true)
    })
  })

  // -------------------------------------------------------------------------
  // isExpired
  // -------------------------------------------------------------------------

  describe('isExpired', () => {
    it('returns true when no token exists', () => {
      expect(manager.isExpired()).toBe(true)
    })

    it('returns false for a valid token', () => {
      manager.setTokens(makeAuthResponse(3600))
      expect(manager.isExpired()).toBe(false)
    })

    it('returns true for an expired token', () => {
      const response = makeAuthResponse(3600)
      response.token = makeExpiredToken()
      manager.setTokens(response)
      expect(manager.isExpired()).toBe(true)
    })
  })

  // -------------------------------------------------------------------------
  // getValidToken
  // -------------------------------------------------------------------------

  describe('getValidToken', () => {
    it('returns null when no token exists', async () => {
      expect(await manager.getValidToken()).toBeNull()
    })

    it('returns token directly when not expired', async () => {
      const response = makeAuthResponse(3600)
      manager.setTokens(response)
      expect(await manager.getValidToken()).toBe(response.token.token)
    })

    it('refreshes and returns new token when expired with refresh token', async () => {
      // Use a manager with autoRefresh disabled to avoid competing refresh
      manager.destroy()
      manager = new TokenManager(storage, 'https://api.test', 60_000, false, 1_000, (e, d) => events.push({ event: e, data: d }))

      // Set an expired token
      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)

      // Mock successful refresh
      const refreshed = makeAuthResponse(3600)
      mockRefreshToken.mockResolvedValueOnce(refreshed)

      const result = await manager.getValidToken()
      expect(result).toBe(refreshed.token.token)
      expect(mockRefreshToken).toHaveBeenCalledWith('https://api.test', 'new-refresh')
    })

    it('returns null and signs out when expired with no refresh token', async () => {
      const expired = makeAuthResponse(3600, null)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)

      const result = await manager.getValidToken()

      expect(result).toBeNull()
      expect(events.some(e => e.event === 'signedOut')).toBe(true)
      expect(events.some(e => e.event === 'error')).toBe(true)
    })

    it('returns null and signs out when refresh fails', async () => {
      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)

      // Both refresh attempts fail
      mockRefreshToken.mockRejectedValue(new Error('refresh failed'))

      const resultPromise = manager.getValidToken()
      // Advance past the 5s retry delay
      await vi.advanceTimersByTimeAsync(2_000)
      const result = await resultPromise

      expect(result).toBeNull()
      expect(events.some(e => e.event === 'signedOut')).toBe(true)
    })
  })

  // -------------------------------------------------------------------------
  // Refresh-only recovery (access token cookie gone, refresh token present)
  // -------------------------------------------------------------------------

  describe('refresh-only recovery', () => {
    it('restore() picks up the refresh token even when the access token cookie is gone', () => {
      // Simulates the scenario where the short-lived access token cookie
      // has been evicted but the long-lived refresh token cookie survives.
      storage.setRefreshToken('orphan-refresh-token')

      const result = manager.restore()
      expect(result).toBeNull() // no access token to return

      // But the refresh token was absorbed into the manager state, so
      // getValidToken() can recover the session.
      const refreshed = makeAuthResponse(3600, 'rotated-refresh')
      mockRefreshToken.mockResolvedValueOnce(refreshed)

      return manager.getValidToken().then((token) => {
        expect(token).toBe(refreshed.token.token)
        expect(mockRefreshToken).toHaveBeenCalledWith('https://api.test', 'orphan-refresh-token')
      })
    })

    it('getValidToken refreshes successfully when only the refresh token was ever loaded', async () => {
      storage.setRefreshToken('orphan-refresh-token')
      manager.restore()

      const refreshed = makeAuthResponse(3600)
      mockRefreshToken.mockResolvedValueOnce(refreshed)

      const result = await manager.getValidToken()
      expect(result).toBe(refreshed.token.token)
    })

    it('getValidToken returns null (no signedOut event) when nothing is in storage', async () => {
      const result = await manager.getValidToken()
      expect(result).toBeNull()
      // Important: we were never signed in, so do NOT emit signedOut.
      expect(events.some(e => e.event === 'signedOut')).toBe(false)
    })
  })

  // -------------------------------------------------------------------------
  // Cross-tab refresh coordination
  // -------------------------------------------------------------------------

  describe('cross-tab refresh coordination', () => {
    it('rejects a refresh if the session was cleared while waiting for the browser lock', async () => {
      let run!: () => Promise<unknown>
      Object.defineProperty(navigator, 'locks', { configurable: true, value: {
        request: (_name: string, callback: () => Promise<unknown>) => {
          run = callback
          return new Promise((resolve, reject) => { run = () => callback().then(resolve, reject) })
        },
      } })
      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)
      const result = manager.getValidToken()
      manager.clear()
      await run()
      expect(await result).toBeNull()
      expect(mockRefreshToken).not.toHaveBeenCalled()
      expect(events.some(event => event.event === 'signedOut')).toBe(true)
    })

    it('uses retained refresh credentials when storage temporarily has no token', async () => {
      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)
      storage.clear()
      mockRefreshToken.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(makeAuthResponse(3600))
      const result = manager.getValidToken()
      await vi.advanceTimersByTimeAsync(2_000)
      expect(await result).toBe('jwt-3600')
      expect(mockRefreshToken).toHaveBeenNthCalledWith(2, 'https://api.test', 'new-refresh')
    })

    it.each(['during-delay', 'after-retry'] as const)('adopts another tab token %s without automatic refresh', async timing => {
      manager.destroy()
      manager = new TokenManager(storage, 'https://api.test', 60_000, false, 1_000, (e, d) => events.push({ event: e, data: d }))
      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)
      const adopt = () => {
        storage.setToken('other-tab')
        storage.setTokenMetadata(makeToken(3600))
        storage.setRefreshToken('other-refresh')
      }
      mockRefreshToken.mockRejectedValueOnce(new Error('offline'))
      if (timing === 'after-retry') mockRefreshToken.mockImplementationOnce(async () => { adopt(); throw new Error('already rotated') })
      const result = manager.getValidToken()
      await vi.advanceTimersByTimeAsync(500)
      if (timing === 'during-delay') adopt()
      await vi.advanceTimersByTimeAsync(1_000)
      expect(await result).toBe('other-tab')
      expect(events.some(e => e.event === 'signedOut')).toBe(false)
    })

    it('stops retrying when explicit sign-out clears the session during the retry delay', async () => {
      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)
      mockRefreshToken.mockRejectedValueOnce(new Error('offline'))
      const result = manager.getValidToken()
      await vi.advanceTimersByTimeAsync(500)
      manager.clear()
      await vi.advanceTimersByTimeAsync(1_000)
      expect(await result).toBeNull()
      expect(mockRefreshToken).toHaveBeenCalledOnce()
    })

    it('preserves a typed token-expiry rejection after the retry fails', async () => {
      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)
      mockRefreshToken.mockRejectedValue(new TokenExpiredError('expired refresh'))
      const result = manager.getValidToken()
      await vi.advanceTimersByTimeAsync(2_000)
      expect(await result).toBeNull()
      expect(events.filter(e => e.event === 'signedOut')).toHaveLength(1)
    })

    it('refreshes a token whose stored metadata changed without a new token value', async () => {
      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)
      storage.setTokenMetadata(makeToken(3600))
      mockRefreshToken.mockResolvedValueOnce(makeAuthResponse(3600))
      expect(await manager.getValidToken()).toBe('jwt-3600')
      expect(mockRefreshToken).toHaveBeenCalledOnce()
    })

    it('requires a JWT expiry and uses it as issuance time when issuance is absent', () => {
      const jwt = (payload: unknown) => `header.${btoa(JSON.stringify(payload))}.signature`
      storage.setToken(jwt({ iat: 100 }))
      expect(manager.restore()).toBeNull()
      storage.setToken('header.invalid-json.signature')
      expect(manager.restore()).toBeNull()
      const exp = Math.floor(Date.now() / 1000) + 3600
      storage.setToken(jwt({ exp }))
      expect(manager.restore()).toBe(storage.getToken())
      expect(manager.consumeSignInResult({ id: 'user', verified: true, primaryProfileId: null }, null)).toBeNull()
    })

    it.runIf(typeof browserLocks?.request === 'function')('serializes two managers through native Web Locks and adopts the rotated token', async () => {
      vi.useRealTimers()
      Object.defineProperty(navigator, 'locks', { value: browserLocks, configurable: true })
      manager.destroy()
      manager = new TokenManager(storage, 'https://api.test', 60_000, false, 1_000, (e, d) => events.push({ event: e, data: d }))
      const peerEvents: string[] = []
      const peer = new TokenManager(storage, 'https://api.test', 60_000, false, 1_000, (event) => peerEvents.push(event))
      try {
        const expired = makeAuthResponse(3600)
        expired.token = makeExpiredToken()
        manager.setTokens(expired)
        peer.restore()
        let release!: (response: AuthResponse) => void
        mockRefreshToken.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
        const first = manager.getValidToken()
        await vi.waitFor(() => expect(mockRefreshToken).toHaveBeenCalledOnce())
        const second = peer.getValidToken()
        const refreshed = makeAuthResponse(3600, 'rotated-refresh')
        release(refreshed)
        expect(await Promise.all([first, second])).toEqual([refreshed.token.token, refreshed.token.token])
        expect(mockRefreshToken).toHaveBeenCalledOnce()
        expect(peerEvents).toContain('tokenAdopted')
        expect(storage.getRefreshToken()).toBe('rotated-refresh')
      } finally {
        peer.destroy()
      }
    })

    it('adopts a fresh token from storage instead of hitting the network when another tab already refreshed', async () => {
      // Simulate tab A having set an expired token.
      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)

      // Tab B wrote newer tokens to shared storage in the meantime.
      const otherTabToken = makeToken(3600)
      otherTabToken.token = 'tab-b-fresh-access-token'
      storage.setToken(otherTabToken.token)
      storage.setTokenMetadata({
        expiresAt: otherTabToken.expiresAt,
        issuedAt: otherTabToken.issuedAt,
      })
      storage.setRefreshToken('tab-b-fresh-refresh-token')

      const result = await manager.getValidToken()

      // Adopted the fresh token without calling the refresh mutation.
      expect(result).toBe('tab-b-fresh-access-token')
      expect(mockRefreshToken).not.toHaveBeenCalled()
      // No `tokenRefreshed` event — this instance didn't refresh — but
      // listeners holding the old token learn about the adopted one.
      expect(events.some(e => e.event === 'tokenRefreshed')).toBe(false)
      expect(events.filter(e => e.event === 'tokenAdopted')).toHaveLength(1)
    })

    it('calls navigator.locks.request when Web Locks API is available', async () => {
      // A deterministic lock also exercises this path under fake timers.
      const lockRequests: Array<{ name: string }> = []
      const nav = (globalThis as { navigator: object }).navigator
      const originalDescriptor = Object.getOwnPropertyDescriptor(nav, 'locks')
      Object.defineProperty(nav, 'locks', {
        value: {
          request: async (name: string, cb: () => Promise<unknown>) => {
            lockRequests.push({ name })
            return cb()
          },
        },
        configurable: true,
      })
      try {
        const expired = makeAuthResponse(3600)
        expired.token = makeExpiredToken()
        manager.setTokens(expired)

        const refreshed = makeAuthResponse(3600)
        mockRefreshToken.mockResolvedValueOnce(refreshed)

        const result = await manager.getValidToken()

        expect(result).toBe(refreshed.token.token)
        expect(lockRequests).toHaveLength(1)
        expect(lockRequests[0].name).toBe('bosca-auth-refresh:https://api.test')
      } finally {
        if (originalDescriptor) {
          Object.defineProperty(nav, 'locks', originalDescriptor)
        } else {
          delete (nav as { locks?: unknown }).locks
        }
      }
    })

    it('still handles concurrent multi-tab refreshes correctly when Web Locks API is unavailable', async () => {
      // Explicit regression test for the no-locks fallback: even without
      // cross-tab serialization, the adopt-from-storage pattern must
      // prevent the losing tab from destroying the winning tab's state.
      // (This is what happens in browsers older than Web Locks support.)
      const nav = (globalThis as { navigator: object }).navigator
      const originalDescriptor = Object.getOwnPropertyDescriptor(nav, 'locks')
      Object.defineProperty(nav, 'locks', { value: undefined, configurable: true })
      try {
        const expired = makeAuthResponse(3600)
        expired.token = makeExpiredToken()
        manager.setTokens(expired)

        // Simulate the race: our network call fails because another tab
        // consumed the single-use refresh token first, and that other
        // tab's tokens landed in shared storage before our failure returned.
        mockRefreshToken.mockImplementationOnce(async () => {
          const fresh = makeToken(3600)
          fresh.token = 'winning-tab-access-token'
          storage.setToken(fresh.token)
          storage.setTokenMetadata({
            expiresAt: fresh.expiresAt,
            issuedAt: fresh.issuedAt,
          })
          storage.setRefreshToken('winning-tab-refresh')
          throw new Error('refresh token not found')
        })

        const result = await manager.getValidToken()

        // Without locks, we still got the right answer via adoption.
        expect(result).toBe('winning-tab-access-token')
        expect(events.some(e => e.event === 'signedOut')).toBe(false)
      } finally {
        if (originalDescriptor) {
          Object.defineProperty(nav, 'locks', originalDescriptor)
        } else {
          delete (nav as { locks?: unknown }).locks
        }
      }
    })

    it('does not sign out when refresh fails but another tab has already written fresh tokens', async () => {
      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)

      // First network attempt throws "token not found" — simulating the
      // single-use refresh token having already been consumed by another tab.
      mockRefreshToken.mockImplementationOnce(async () => {
        // Between the network call and its rejection, the other tab
        // finishes and writes its fresh tokens to shared storage.
        const fresh = makeToken(3600)
        fresh.token = 'other-tab-token'
        storage.setToken(fresh.token)
        storage.setTokenMetadata({
          expiresAt: fresh.expiresAt,
          issuedAt: fresh.issuedAt,
        })
        storage.setRefreshToken('other-tab-refresh')
        throw new Error('refresh token not found')
      })

      const result = await manager.getValidToken()

      // The other tab's fresh token was adopted instead of wiping state.
      expect(result).toBe('other-tab-token')
      expect(events.some(e => e.event === 'signedOut')).toBe(false)
    })
  })

  // -------------------------------------------------------------------------
  // Automatic refresh scheduling
  // -------------------------------------------------------------------------

  describe('automatic refresh', () => {
    it('schedules refresh before token expiry', async () => {
      const refreshed = makeAuthResponse(7200)
      mockRefreshToken.mockResolvedValueOnce(refreshed)

      // Set token that expires in 120 seconds (buffer is 60s, so refresh at 60s)
      const response = makeAuthResponse(120)
      manager.setTokens(response)

      // Advance to just before the refresh should fire (59s)
      await vi.advanceTimersByTimeAsync(59_000)
      expect(mockRefreshToken).not.toHaveBeenCalled()

      // Advance past the refresh point (61s from start = expiresAt - 60s buffer)
      await vi.advanceTimersByTimeAsync(2_000)
      expect(mockRefreshToken).toHaveBeenCalledOnce()
    })

    it('does not tight-loop on long-lived tokens (setTimeout int32 overflow)', async () => {
      // 30-day token: lifetime > 2^31-1 ms. A naive setTimeout(delay)
      // clamps the delay to 0 and fires immediately, which then calls
      // doRefresh → setTokens → scheduleRefresh → overflow → immediate
      // fire → etc. until something happens to break the loop (like a
      // transient failure, which the old code "handled" by wiping the
      // cookie jar). Neither behavior is acceptable; scheduleRefresh
      // must clamp the delay and re-schedule instead.
      const thirtyDaysInSeconds = 30 * 24 * 60 * 60
      manager.setTokens(makeAuthResponse(thirtyDaysInSeconds))

      // Let all microtasks drain — if the bug were present, at least
      // one refresh would already have fired by now via the 0-delay
      // timer (or within a few ms of the 2^31 clamp).
      await vi.advanceTimersByTimeAsync(0)
      expect(mockRefreshToken).not.toHaveBeenCalled()

      // Advance by exactly the clamp ceiling (~24.8 days). The timer
      // should fire and re-schedule, still without hitting the network
      // (the token has ~5 days of life left at that point).
      await vi.advanceTimersByTimeAsync(2_147_483_647)
      expect(mockRefreshToken).not.toHaveBeenCalled()
    })

    it('skips scheduled refresh when token already sits inside the refresh buffer window', async () => {
      // Token expires in 30s but the refresh buffer is 60s — the token is
      // effectively already in the refresh window. Scheduling a timer for
      // a non-positive delay would risk tight loops (if the refreshed
      // token also happens to land in the buffer window). Lazy refresh
      // via getValidToken() still covers this once the token actually
      // expires.
      const response = makeAuthResponse(30)
      manager.setTokens(response)

      await vi.advanceTimersByTimeAsync(0)
      expect(mockRefreshToken).not.toHaveBeenCalled()

      // Still usable until real expiry.
      expect(await manager.getValidToken()).toBe(response.token.token)

      // Real expiry triggers lazy refresh.
      const refreshed = makeAuthResponse(7200)
      mockRefreshToken.mockResolvedValueOnce(refreshed)
      await vi.advanceTimersByTimeAsync(31_000)
      expect(await manager.getValidToken()).toBe(refreshed.token.token)
      expect(mockRefreshToken).toHaveBeenCalledOnce()
    })

    it('emits tokenRefreshed event on successful auto-refresh', async () => {
      const refreshed = makeAuthResponse(7200)
      mockRefreshToken.mockResolvedValueOnce(refreshed)

      manager.setTokens(makeAuthResponse(120))

      await vi.advanceTimersByTimeAsync(61_000)

      expect(events.some(e => e.event === 'tokenRefreshed')).toBe(true)
    })

    it('does not schedule refresh when autoRefresh is disabled', async () => {
      manager.destroy()
      manager = new TokenManager(storage, 'https://api.test', 60_000, false, 1_000, (e, d) => events.push({ event: e, data: d }))

      manager.setTokens(makeAuthResponse(120))

      await vi.advanceTimersByTimeAsync(120_000)
      expect(mockRefreshToken).not.toHaveBeenCalled()
    })

    it('does not schedule refresh when no refresh token exists', async () => {
      manager.setTokens(makeAuthResponse(120, null))

      await vi.advanceTimersByTimeAsync(120_000)
      expect(mockRefreshToken).not.toHaveBeenCalled()
    })
  })

  // -------------------------------------------------------------------------
  // Refresh failure and retry
  // -------------------------------------------------------------------------

  describe('refresh failure handling', () => {
    it('retries once on first failure then succeeds', async () => {
      const refreshed = makeAuthResponse(7200)
      mockRefreshToken
        .mockRejectedValueOnce(new Error('temporary failure'))
        .mockResolvedValueOnce(refreshed)

      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)

      // getValidToken triggers refresh
      const resultPromise = manager.getValidToken()

      // Advance past the retry delay (5 seconds)
      await vi.advanceTimersByTimeAsync(2_000)

      const result = await resultPromise
      expect(result).toBe(refreshed.token.token)
      expect(mockRefreshToken).toHaveBeenCalledTimes(2)
    })

    it('signs out in-memory after both retries fail but leaves storage intact', async () => {
      // Transient failures (network, 5xx, etc.) used to wipe the
      // cookie jar here, which meant a single flaky refresh call
      // permanently destroyed a potentially still-valid refresh
      // token. Storage is now only cleared on explicit signOut().
      mockRefreshToken
        .mockRejectedValueOnce(new Error('first failure'))
        .mockRejectedValueOnce(new Error('second failure'))

      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)
      const originalRefreshToken = storage.getRefreshToken()
      expect(originalRefreshToken).not.toBeNull()

      const resultPromise = manager.getValidToken()

      // Advance past retry delay
      await vi.advanceTimersByTimeAsync(2_000)

      const result = await resultPromise
      expect(result).toBeNull()
      expect(events.filter(e => e.event === 'signedOut')).toHaveLength(1)
      expect(events.filter(e => e.event === 'error').length).toBeGreaterThanOrEqual(1)
      // In-memory state dropped.
      expect(manager.getToken()).toBeNull()
      // Storage is intentionally preserved so the next page load
      // can retry. Only explicit `clear()` (called by signOut) wipes storage.
      expect(storage.getRefreshToken()).toBe(originalRefreshToken)
    })

    it('deduplicates concurrent refresh calls', async () => {
      const refreshed = makeAuthResponse(7200)
      mockRefreshToken.mockResolvedValueOnce(refreshed)

      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)

      // Trigger multiple concurrent refreshes
      const p1 = manager.getValidToken()
      const p2 = manager.getValidToken()
      const p3 = manager.getValidToken()

      const [r1, r2, r3] = await Promise.all([p1, p2, p3])

      expect(r1).toBe(refreshed.token.token)
      expect(r2).toBe(refreshed.token.token)
      expect(r3).toBe(refreshed.token.token)
      // Only one actual refresh call should have been made
      expect(mockRefreshToken).toHaveBeenCalledTimes(1)
    })
  })

  // -------------------------------------------------------------------------
  // clear / destroy
  // -------------------------------------------------------------------------

  describe('clear', () => {
    it('removes all tokens and cancels timer', () => {
      manager.setTokens(makeAuthResponse(3600))

      manager.clear()

      expect(manager.getToken()).toBeNull()
      expect(storage.getToken()).toBeNull()
      expect(storage.getRefreshToken()).toBeNull()
      expect(storage.getTokenMetadata()).toBeNull()
    })
  })

  // -------------------------------------------------------------------------
  // Full token expiration scenario
  // -------------------------------------------------------------------------

  describe('full session expiration', () => {
    it('drops in-memory state and signals signedOut when the server rejects the refresh token', async () => {
      // Even when the refresh token is genuinely dead (server returned
      // an error that we can't distinguish from a transient failure),
      // we deliberately leave storage alone. The downside is a dead
      // cookie in the jar; the upside is that a transient failure
      // misidentified as terminal can't destroy a valid session.
      // Explicit signOut() is the only path that wipes storage.
      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)

      mockRefreshToken
        .mockRejectedValueOnce(new Error('refresh token expired'))
        .mockRejectedValueOnce(new Error('refresh token expired'))

      const resultPromise = manager.getValidToken()
      await vi.advanceTimersByTimeAsync(2_000)

      const result = await resultPromise

      expect(result).toBeNull()
      expect(manager.getToken()).toBeNull()
      expect(manager.isExpired()).toBe(true)

      const signedOutEvents = events.filter(e => e.event === 'signedOut')
      const errorEvents = events.filter(e => e.event === 'error')
      expect(signedOutEvents.length).toBeGreaterThanOrEqual(1)
      expect(errorEvents.length).toBeGreaterThanOrEqual(1)

      // Storage is intentionally preserved.
      expect(storage.getRefreshToken()).not.toBeNull()
    })

    it('handles scenario where access token is valid but refresh token is missing', async () => {
      // Token still valid, no refresh token — should work fine until token expires
      manager.setTokens(makeAuthResponse(3600, null))

      const result = await manager.getValidToken()
      expect(result).not.toBeNull()

      // But after expiry, there's no way to refresh
      vi.advanceTimersByTime(3601_000)
      expect(manager.isExpired()).toBe(true)

      const afterExpiry = await manager.getValidToken()
      expect(afterExpiry).toBeNull()
      expect(events.some(e => e.event === 'signedOut')).toBe(true)
    })
  })

  // -------------------------------------------------------------------------
  // Additional edge cases
  // -------------------------------------------------------------------------

  describe('edge cases', () => {
    it('refresh token cleared between first failure and retry throws TokenExpiredError', async () => {
      manager.destroy()
      manager = new TokenManager(storage, 'https://api.test', 60_000, false, 1_000, (e, d) => events.push({ event: e, data: d }))

      const expired = makeAuthResponse(3600)
      expired.token = makeExpiredToken()
      manager.setTokens(expired)

      // First refresh fails, then clear() is called during the 5s wait
      mockRefreshToken.mockRejectedValueOnce(new Error('temporary'))
      mockRefreshToken.mockRejectedValueOnce(new Error('no refresh token'))

      const resultPromise = manager.getValidToken()

      // Clear tokens while the retry delay is pending
      await vi.advanceTimersByTimeAsync(2_000)
      manager.clear()
      await vi.advanceTimersByTimeAsync(4_000)

      const result = await resultPromise
      expect(result).toBeNull()
    })

    it('setTokens twice cancels first refresh timer', async () => {
      // First token expires in 120s (refresh at 60s)
      manager.setTokens(makeAuthResponse(120))

      // Second token expires in 300s (refresh at 240s)
      manager.setTokens(makeAuthResponse(300))

      const refreshed = makeAuthResponse(7200)
      mockRefreshToken.mockResolvedValueOnce(refreshed)

      // Advance to 61s — first timer would have fired but should be cancelled
      await vi.advanceTimersByTimeAsync(61_000)
      expect(mockRefreshToken).not.toHaveBeenCalled()

      // Advance to 241s — second timer fires
      await vi.advanceTimersByTimeAsync(180_000)
      expect(mockRefreshToken).toHaveBeenCalledOnce()
    })

    it('auto-refresh timer first attempt fails but retry succeeds', async () => {
      const refreshed = makeAuthResponse(7200)
      mockRefreshToken
        .mockRejectedValueOnce(new Error('temporary'))
        .mockResolvedValueOnce(refreshed)

      // Token expires in 120s, buffer 60s → refresh at 60s
      manager.setTokens(makeAuthResponse(120))

      // Advance to trigger auto-refresh
      await vi.advanceTimersByTimeAsync(61_000)

      // First attempt fails, wait for retry delay
      await vi.advanceTimersByTimeAsync(2_000)

      expect(mockRefreshToken).toHaveBeenCalledTimes(2)
      expect(events.some(e => e.event === 'tokenRefreshed')).toBe(true)
      expect(manager.getToken()).toBe(refreshed.token.token)
    })
  })
})
