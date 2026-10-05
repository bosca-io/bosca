import { describe, it, expect, vi, beforeEach } from 'vitest'
import { startOAuthRedirect, getExchangeTokenFromUrl, getLinkTokenFromUrl, getLinkMethodsFromUrl } from './oauth'

describe('startOAuthRedirect', () => {
  let originalLocation: Location

  beforeEach(() => {
    originalLocation = window.location
    // @ts-expect-error: overriding location for testing
    delete window.location
    window.location = { ...originalLocation, href: 'https://app.test/login' } as Location
  })

  it('navigates to OAuth endpoint with provider and redirect URL', () => {
    startOAuthRedirect('https://api.test', {
      provider: 'GOOGLE',
      redirectUrl: 'https://app.test/callback',
    })

    expect(window.location.href).toContain('https://api.test/oauth2/google/login?')
    expect(window.location.href).toContain('redirect=https%3A%2F%2Fapp.test%2Fcallback')
  })

  it('uses current page URL as default redirect', () => {
    window.location.href = 'https://app.test/current-page'

    startOAuthRedirect('https://api.test', {
      provider: 'FACEBOOK',
    })

    expect(window.location.href).toContain('oauth2/facebook/login')
    expect(window.location.href).toContain('redirect=https%3A%2F%2Fapp.test%2Fcurrent-page')
  })

  it('lowercases the provider name in the URL', () => {
    startOAuthRedirect('https://api.test', { provider: 'APPLE' })
    expect(window.location.href).toContain('/oauth2/apple/login')
  })

  it('includes organization parameter when provided', () => {
    startOAuthRedirect('https://api.test', {
      provider: 'GOOGLE',
      organization: 'org-token-123',
    })

    expect(window.location.href).toContain('organization=org-token-123')
  })

  it('includes community parameter when provided', () => {
    startOAuthRedirect('https://api.test', {
      provider: 'GOOGLE',
      community: 'comm-token-456',
    })

    expect(window.location.href).toContain('community=comm-token-456')
  })

  it('includes originator parameter when provided', () => {
    startOAuthRedirect('https://api.test', {
      provider: 'GOOGLE',
      originator: 'studio',
    })

    expect(window.location.href).toContain('originator=studio')
  })

  it('includes both organization and community parameters', () => {
    startOAuthRedirect('https://api.test', {
      provider: 'GOOGLE',
      organization: 'org',
      community: 'comm',
    })

    const url = window.location.href
    expect(url).toContain('organization=org')
    expect(url).toContain('community=comm')
  })
})

describe('getExchangeTokenFromUrl', () => {
  let replaceStateSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    replaceStateSpy = vi.fn()
    // @ts-expect-error: overriding for test
    delete window.location
    window.location = {
      href: 'https://app.test/',
      search: '',
      origin: 'https://app.test',
      pathname: '/',
    } as Location
    window.history.replaceState = replaceStateSpy
  })

  it('returns null when no token parameter in URL', () => {
    window.location.search = ''
    window.location.href = 'https://app.test/'

    expect(getExchangeTokenFromUrl()).toBeNull()
  })

  it('returns token when present in URL', () => {
    window.location.search = '?exchangeToken=exchange-abc-123'
    window.location.href = 'https://app.test/?exchangeToken=exchange-abc-123'

    const result = getExchangeTokenFromUrl()
    expect(result).toBe('exchange-abc-123')
  })

  it('removes token from URL after reading', () => {
    window.location.search = '?exchangeToken=exchange-abc&other=keep'
    window.location.href = 'https://app.test/?exchangeToken=exchange-abc&other=keep'

    getExchangeTokenFromUrl()

    expect(replaceStateSpy).toHaveBeenCalledTimes(1)
    const cleanedUrl = replaceStateSpy.mock.calls[0][2]
    expect(cleanedUrl).not.toContain('exchangeToken=exchange-abc')
    expect(cleanedUrl).toContain('other=keep')
  })

  it('does not call replaceState when no token found', () => {
    window.location.search = '?other=value'
    window.location.href = 'https://app.test/?other=value'

    getExchangeTokenFromUrl()

    expect(replaceStateSpy).not.toHaveBeenCalled()
  })

  it('returns null on second call after token was already consumed', () => {
    window.location.search = '?exchangeToken=once-only'
    window.location.href = 'https://app.test/?exchangeToken=once-only'

    const first = getExchangeTokenFromUrl()
    expect(first).toBe('once-only')

    // After first call, the token is removed from URL via replaceState.
    // Simulate the cleaned URL state for the second call.
    window.location.search = ''
    window.location.href = 'https://app.test/'

    const second = getExchangeTokenFromUrl()
    expect(second).toBeNull()
  })
})

describe('getLinkTokenFromUrl', () => {
  let replaceStateSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    replaceStateSpy = vi.fn()
    // @ts-expect-error: overriding for test
    delete window.location
    window.location = {
      href: 'https://app.test/',
      search: '',
      origin: 'https://app.test',
      pathname: '/',
    } as Location
    window.history.replaceState = replaceStateSpy
  })

  it('returns null when no link parameter in URL', () => {
    window.location.search = ''
    window.location.href = 'https://app.test/'

    expect(getLinkTokenFromUrl()).toBeNull()
  })

  it('returns the link token when present in URL', () => {
    window.location.search = '?link=link-abc-123'
    window.location.href = 'https://app.test/auth/login?link=link-abc-123'

    expect(getLinkTokenFromUrl()).toBe('link-abc-123')
  })

  it('removes the link token from the URL after reading, preserving other params', () => {
    window.location.search = '?link=link-abc&other=keep'
    window.location.href = 'https://app.test/auth/login?link=link-abc&other=keep'

    getLinkTokenFromUrl()

    expect(replaceStateSpy).toHaveBeenCalledTimes(1)
    const cleanedUrl = replaceStateSpy.mock.calls[0][2]
    expect(cleanedUrl).not.toContain('link=link-abc')
    expect(cleanedUrl).toContain('other=keep')
  })

  it('does not call replaceState when no link token found', () => {
    window.location.search = '?other=value'
    window.location.href = 'https://app.test/?other=value'

    getLinkTokenFromUrl()

    expect(replaceStateSpy).not.toHaveBeenCalled()
  })
})

describe('getLinkMethodsFromUrl', () => {
  let replaceStateSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    replaceStateSpy = vi.fn()
    // @ts-expect-error: overriding for test
    delete window.location
    window.location = {
      href: 'https://app.test/',
      search: '',
      origin: 'https://app.test',
      pathname: '/',
    } as Location
    window.history.replaceState = replaceStateSpy
  })

  it('returns null when no methods parameter in URL', () => {
    window.location.search = ''
    window.location.href = 'https://app.test/'

    expect(getLinkMethodsFromUrl()).toBeNull()
  })

  it('parses both proof methods when the account has a password', () => {
    window.location.search = '?methods=PASSWORD,EMAIL'
    window.location.href = 'https://app.test/auth/login?methods=PASSWORD,EMAIL'

    expect(getLinkMethodsFromUrl()).toEqual(['PASSWORD', 'EMAIL'])
  })

  it('parses email-only for an OAuth-only account', () => {
    window.location.search = '?methods=EMAIL'
    window.location.href = 'https://app.test/auth/login?methods=EMAIL'

    expect(getLinkMethodsFromUrl()).toEqual(['EMAIL'])
  })

  it('drops unknown method tokens rather than trusting the URL blindly', () => {
    window.location.search = '?methods=EMAIL,SMS,bogus'
    window.location.href = 'https://app.test/auth/login?methods=EMAIL,SMS,bogus'

    expect(getLinkMethodsFromUrl()).toEqual(['EMAIL'])
  })

  it('strips only the methods param after reading, preserving other params', () => {
    window.location.search = '?methods=EMAIL&link=tok'
    window.location.href = 'https://app.test/auth/login?methods=EMAIL&link=tok'

    getLinkMethodsFromUrl()

    expect(replaceStateSpy).toHaveBeenCalledTimes(1)
    const cleanedUrl = replaceStateSpy.mock.calls[0][2]
    expect(cleanedUrl).not.toContain('methods=EMAIL')
    expect(cleanedUrl).toContain('link=tok')
  })

  it('does not call replaceState when no methods param is found', () => {
    window.location.search = '?link=tok'
    window.location.href = 'https://app.test/?link=tok'

    getLinkMethodsFromUrl()

    expect(replaceStateSpy).not.toHaveBeenCalled()
  })
})
