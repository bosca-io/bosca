// @vitest-environment happy-dom

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { PageTracker, extractUtmParams } from './page_tracker'
import * as sink from '../sink'

vi.mock('../sink', async () => {
  const actual = await vi.importActual<typeof sink>('../sink')
  return {
    ...actual,
    logEvent: vi.fn().mockResolvedValue(undefined),
  }
})

describe('PageTracker', () => {
  let tracker: PageTracker
  let originalPushState: typeof history.pushState
  let originalReplaceState: typeof history.replaceState

  beforeEach(() => {
    vi.clearAllMocks()
    originalPushState = history.pushState.bind(history)
    originalReplaceState = history.replaceState.bind(history)
  })

  afterEach(() => {
    tracker?.stop()
    // Restore in case stop() didn't (e.g. another library patched after us)
    if (history.pushState !== originalPushState) {
      history.pushState = originalPushState
    }
    if (history.replaceState !== originalReplaceState) {
      history.replaceState = originalReplaceState
    }
  })

  it('should track initial page view on start', () => {
    tracker = new PageTracker()
    tracker.start()

    expect(sink.logEvent).toHaveBeenCalledTimes(1)
    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.type).toBe('impression')
    expect(call.element.type).toBe('page')
    expect(call.element.extras.url).toBeDefined()
  })

  it('should track pushState navigations with referrer chain', async () => {
    tracker = new PageTracker()
    tracker.start()
    const initialUrl = window.location.href

    history.pushState({}, '', '/new-page')
    await Promise.resolve()

    expect(sink.logEvent).toHaveBeenCalledTimes(2)
    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[1][0]
    expect(call.element.extras.path).toBe('/new-page')
    // The referrer should be the URL from before the navigation
    expect(call.element.extras.referrer).toBe(initialUrl)
  })

  it('should track replaceState navigations', async () => {
    tracker = new PageTracker()
    tracker.start()

    history.replaceState({}, '', '/replaced')
    await Promise.resolve()

    expect(sink.logEvent).toHaveBeenCalledTimes(2)
    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[1][0]
    expect(call.element.extras.path).toBe('/replaced')
  })

  it('should track popstate events', async () => {
    tracker = new PageTracker()
    tracker.start()

    // First navigate somewhere
    history.pushState({}, '', '/page-a')
    await Promise.resolve()

    // Simulate back navigation
    history.pushState({}, '', '/page-b')
    await Promise.resolve()

    window.dispatchEvent(new PopStateEvent('popstate'))
    await Promise.resolve()

    expect(sink.logEvent).toHaveBeenCalled()
  })

  it('should not track navigation to same URL', async () => {
    tracker = new PageTracker()
    tracker.start()

    const callsBefore = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls.length

    // pushState to same URL
    history.pushState({}, '', window.location.pathname)
    await Promise.resolve()

    expect((sink.logEvent as ReturnType<typeof vi.fn>).mock.calls.length).toBe(callsBefore)
  })

  it('should include document.referrer for initial page view', () => {
    Object.defineProperty(document, 'referrer', { value: 'https://google.com', configurable: true })

    tracker = new PageTracker()
    tracker.start()

    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.extras.referrer).toBe('https://google.com')

    Object.defineProperty(document, 'referrer', { value: '', configurable: true })
  })

  it('should restore original history methods on stop', () => {
    tracker = new PageTracker()
    tracker.start()

    expect(history.pushState).not.toBe(originalPushState)

    tracker.stop()

    // After stop, pushState should not trigger our handler
    const callsBefore = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls.length
    history.pushState({}, '', '/after-stop')
    expect((sink.logEvent as ReturnType<typeof vi.fn>).mock.calls.length).toBe(callsBefore)
  })

  it('should not restore history methods if another library patched after us', () => {
    tracker = new PageTracker()
    tracker.start()

    // Simulate another library patching pushState after us
    const otherLibPushState = (..._args: any[]) => {}
    history.pushState = otherLibPushState as any

    tracker.stop()

    // Should NOT have restored, since another library patched after us
    expect(history.pushState).toBe(otherLibPushState)
  })

  it('should include UTM parameters on the initial page view', () => {
    // Navigate to a URL with UTM params before starting the tracker
    history.pushState({}, '', '/?utm_source=google&utm_medium=cpc&utm_campaign=spring')

    tracker = new PageTracker()
    tracker.start()

    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.extras.utm_source).toBe('google')
    expect(call.element.extras.utm_medium).toBe('cpc')
    expect(call.element.extras.utm_campaign).toBe('spring')
  })

  it('should carry UTM parameters to subsequent SPA navigations', async () => {
    history.pushState({}, '', '/?utm_source=newsletter&utm_campaign=launch')

    tracker = new PageTracker()
    tracker.start()

    history.pushState({}, '', '/about')
    await Promise.resolve()

    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[1][0]
    expect(call.element.extras.utm_source).toBe('newsletter')
    expect(call.element.extras.utm_campaign).toBe('launch')
    expect(call.element.extras.path).toBe('/about')
  })

  it('should pick up new UTM parameters on SPA navigations', async () => {
    history.pushState({}, '', '/landing')

    tracker = new PageTracker()
    tracker.start()

    history.pushState({}, '', '/promo?utm_source=email&utm_campaign=summer')
    await Promise.resolve()

    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[1][0]
    expect(call.element.extras.utm_source).toBe('email')
    expect(call.element.extras.utm_campaign).toBe('summer')
  })

  it('should override initial UTMs with current page UTMs', async () => {
    history.pushState({}, '', '/?utm_source=google')

    tracker = new PageTracker()
    tracker.start()

    history.pushState({}, '', '/page?utm_source=facebook')
    await Promise.resolve()

    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[1][0]
    expect(call.element.extras.utm_source).toBe('facebook')
  })

  it('should not include UTM keys when none are present in the URL', () => {
    history.pushState({}, '', '/clean-page')

    tracker = new PageTracker()
    tracker.start()

    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.extras.utm_source).toBeUndefined()
    expect(call.element.extras.utm_medium).toBeUndefined()
  })

  it('should include ref parameter when present', () => {
    history.pushState({}, '', '/?ref=partner-site')

    tracker = new PageTracker()
    tracker.start()

    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.extras.ref).toBe('partner-site')
  })

  it('should invoke onPageNavigate callback on SPA navigation', async () => {
    const callback = vi.fn()
    tracker = new PageTracker()
    tracker.onPageNavigate(callback)
    tracker.start()

    expect(callback).not.toHaveBeenCalled()

    history.pushState({}, '', '/callback-test')
    await Promise.resolve()

    expect(callback).toHaveBeenCalledTimes(1)
  })
})

describe('extractUtmParams', () => {
  it('should extract all standard UTM parameters', () => {
    const result = extractUtmParams(
      '?utm_source=google&utm_medium=cpc&utm_campaign=spring&utm_term=shoes&utm_content=banner',
    )
    expect(result).toEqual({
      utm_source: 'google',
      utm_medium: 'cpc',
      utm_campaign: 'spring',
      utm_term: 'shoes',
      utm_content: 'banner',
    })
  })

  it('should return only present parameters', () => {
    const result = extractUtmParams('?utm_source=twitter')
    expect(result).toEqual({ utm_source: 'twitter' })
  })

  it('should return empty object when no UTM params exist', () => {
    const result = extractUtmParams('?page=1&sort=name')
    expect(result).toEqual({})
  })

  it('should return empty object for empty search string', () => {
    const result = extractUtmParams('')
    expect(result).toEqual({})
  })

  it('should extract ref parameter', () => {
    const result = extractUtmParams('?ref=partner&utm_source=email')
    expect(result).toEqual({ utm_source: 'email', ref: 'partner' })
  })
})
