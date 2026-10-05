// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { AutoInstrumentation, autoInstrument } from './index'
import * as sink from '../sink'

vi.mock('../sink', async () => {
  const actual = await vi.importActual<typeof sink>('../sink')
  return {
    ...actual,
    logEvent: vi.fn().mockImplementation(() => Promise.resolve()),
    logImpression: vi.fn().mockImplementation(() => Promise.resolve()),
    logInteraction: vi.fn().mockImplementation(() => Promise.resolve()),
  }
})

// Test-only DOM helper — all content is hardcoded test fixtures
function setTestBody(html: string) {
  document.body.innerHTML = html // eslint-disable-line
}

describe('AutoInstrumentation', () => {
  let instrumentation: AutoInstrumentation

  beforeEach(() => {
    vi.clearAllMocks()
    ;(sink.logEvent as ReturnType<typeof vi.fn>).mockImplementation(() => Promise.resolve())
    setTestBody('')
  })

  afterEach(() => {
    if (instrumentation) {
      instrumentation.stop()
    }
  })

  it('should be idempotent: double-start is a no-op, stop cleans up', () => {
    instrumentation = new AutoInstrumentation()
    instrumentation.start()
    const callsAfterFirstStart = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls.length
    instrumentation.start() // no-op
    // No additional page view fired
    expect((sink.logEvent as ReturnType<typeof vi.fn>).mock.calls.length).toBe(callsAfterFirstStart)
  })

  it('should only fire events for enabled features', () => {
    instrumentation = new AutoInstrumentation({
      pageViews: true,
      clicks: false,
      scrollDepth: false,
      mouseMovement: false,
      elementVisibility: false,
      forms: false,
    })
    instrumentation.start()

    // Only page view should fire
    expect(sink.logEvent).toHaveBeenCalled()
    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const pageviewCall = calls.find((c: any[]) => c[0].type === 'impression' && c[0].element?.type === 'page')
    expect(pageviewCall).toBeDefined()

    // Click on a button should not trigger anything
    setTestBody('<button id="btn">Click</button>')
    document.querySelector('#btn')!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    const clickCalls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls.filter(
      (c: any[]) => c[0].element?.type === 'click',
    )
    expect(clickCalls).toHaveLength(0)
  })

  it('should accept object-form options to configure individual trackers', () => {
    setTestBody('<button id="btn">Click</button>')
    instrumentation = new AutoInstrumentation({
      pageViews: false,
      clicks: { trackTouch: false, detectRageClicks: false },
      scrollDepth: { throttleMs: 500 },
      mouseMovement: { sampleInterval: 200 },
      elementVisibility: { dwellTime: 2000 },
      forms: { trackAbandonment: false },
    })
    instrumentation.start()

    // Clicks enabled but rage detection disabled — clicking 5x should produce only click events
    const btn = document.querySelector('#btn')!
    for (let i = 0; i < 5; i++) {
      btn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    }
    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    expect(calls.filter((c: any[]) => c[0].element?.type === 'rage_click')).toHaveLength(0)
    expect(calls.filter((c: any[]) => c[0].element?.type === 'click')).toHaveLength(5)
  })

  it('should reset scroll tracker on SPA navigation', async () => {
    instrumentation = new AutoInstrumentation({
      pageViews: true,
      clicks: false,
      scrollDepth: true,
      mouseMovement: false,
      elementVisibility: false,
      forms: false,
      errors: false,
    })
    instrumentation.start()

    const scrollTracker = instrumentation.getScrollTracker()!
    const resetSpy = vi.spyOn(scrollTracker, 'resetForNewPage')

    history.pushState({}, '', '/scroll-reset-test')
    await Promise.resolve()

    expect(resetSpy).toHaveBeenCalledTimes(1)
  })

  it('should expose scroll tracker only when scrollDepth is enabled', () => {
    instrumentation = new AutoInstrumentation({ scrollDepth: false })
    instrumentation.start()
    expect(instrumentation.getScrollTracker()).toBeNull()

    instrumentation.stop()

    instrumentation = new AutoInstrumentation({
      pageViews: false, clicks: false, scrollDepth: true,
      mouseMovement: false, elementVisibility: false, forms: false,
    })
    instrumentation.start()
    expect(instrumentation.getScrollTracker()).not.toBeNull()
  })
})

describe('autoInstrument', () => {
  beforeEach(() => {
    ;(sink.logEvent as ReturnType<typeof vi.fn>).mockImplementation(() => Promise.resolve())
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: string | URL | Request) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
      if (url.endsWith('/installation')) {
        return new Response(JSON.stringify({ id: 'test-iid' }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response('', { status: 202 })
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('should create a working sink+instrumentation pair and clean up on stop', () => {
    const result = autoInstrument({
      url: 'http://localhost:8081',
      appId: 'test',
      appVersion: '1.0',
      clientId: 'web',
      userId: 'user-1',
    })

    expect(result.instrumentation).toBeInstanceOf(AutoInstrumentation)
    expect((result.sink as unknown as { context: { user_id?: string } }).context.user_id).toBe('user-1')
    result.stop()
  })

  it('should disable all features when all options are false', () => {
    const result = autoInstrument({
      url: 'http://localhost:8081',
      appId: 'test',
      appVersion: '1.0',
      clientId: 'web',
      options: { pageViews: false, clicks: false, scrollDepth: false, mouseMovement: false, elementVisibility: false, forms: false },
    })

    // No events fired since everything is disabled
    expect(sink.logEvent).not.toHaveBeenCalled()
    result.stop()
  })
})
