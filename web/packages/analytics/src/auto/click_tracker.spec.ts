// @vitest-environment happy-dom

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ClickTracker } from './click_tracker'
import * as sink from '../sink'

vi.mock('../sink', async () => {
  const actual = await vi.importActual<typeof sink>('../sink')
  return {
    ...actual,
    logEvent: vi.fn().mockImplementation(() => Promise.resolve()),
    logEventBeacon: vi.fn(),
  }
})

function setBody(html: string) {
  // Using innerHTML in tests only — all values are hardcoded test fixtures, not user input
  document.body.innerHTML = html // eslint-disable-line no-unsanitized/property
}

function clickLink(selector: string, init: MouseEventInit = {}) {
  // Cancel the browser's default navigation after the tracker's window listener
  // has classified the event, keeping the test iframe and its runner connected.
  const preventNavigation = (event: Event) => event.preventDefault()
  window.addEventListener('click', preventNavigation, { once: true })
  try {
    document.querySelector(selector)!.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, ...init }))
  } finally {
    window.removeEventListener('click', preventNavigation)
  }
}

// Provide a minimal Touch polyfill if the test environment doesn't implement it
if (typeof globalThis.Touch === 'undefined') {
  (globalThis as any).Touch = class Touch {
    identifier: number
    target: EventTarget
    clientX: number
    clientY: number
    pageX: number
    pageY: number
    constructor(init: { identifier: number; target: EventTarget; clientX?: number; clientY?: number; pageX?: number; pageY?: number }) {
      this.identifier = init.identifier
      this.target = init.target
      this.clientX = init.clientX ?? 0
      this.clientY = init.clientY ?? 0
      this.pageX = init.pageX ?? 0
      this.pageY = init.pageY ?? 0
    }
  }
}

describe('ClickTracker', () => {
  let tracker: ClickTracker

  beforeEach(() => {
    vi.clearAllMocks()
    history.replaceState({}, '', '/')
    setBody('<button id="btn">Click</button><a href="/page">Link</a>')
  })

  afterEach(() => {
    tracker?.stop()
  })

  it('reports transport failures for ordinary clicks, touch, and rage clicks', async () => {
    const failure = new Error('transport unavailable')
    vi.mocked(sink.logEvent).mockRejectedValue(failure)
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      tracker = new ClickTracker({ trackTouch: true, detectRageClicks: true, rageClickThreshold: 1 })
      tracker.start()
      const button = document.querySelector('#btn')!
      button.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      button.dispatchEvent(new TouchEvent('touchstart', { bubbles: true, touches: [new Touch({ identifier: 1, target: button })] }))
      await vi.waitFor(() => expect(error).toHaveBeenCalledTimes(3))
      expect(error).toHaveBeenCalledWith('failed to track click:', failure)
      expect(error).toHaveBeenCalledWith('failed to track touch:', failure)
      expect(error).toHaveBeenCalledWith('failed to track rage click:', failure)
    } finally {
      vi.mocked(sink.logEvent).mockResolvedValue(undefined)
      error.mockRestore()
    }
  })

  it('should track clicks with coordinates and element info', () => {
    tracker = new ClickTracker()
    tracker.start()

    const btn = document.querySelector('#btn')!
    btn.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: 50, clientY: 100 }))

    expect(sink.logEvent).toHaveBeenCalledTimes(1)
    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.type).toBe('interaction')
    expect(call.element.type).toBe('click')
    expect(call.element.extras.client_x).toBe('50')
    expect(call.element.extras.client_y).toBe('100')
  })

  it('should include href for links', () => {
    tracker = new ClickTracker()
    tracker.start()

    clickLink('a')

    const call = (sink.logEventBeacon as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.extras.href).toBe('/page')
    expect(call.page).toMatchObject({ path: '/', url: expect.stringMatching(/\/$/) })
  })

  it('should beacon same-window document navigation clicks', () => {
    tracker = new ClickTracker()
    tracker.start()

    clickLink('a')

    expect(sink.logEvent).not.toHaveBeenCalled()
    expect(sink.logEventBeacon).toHaveBeenCalledTimes(1)
  })

  it('should not beacon link clicks that do not unload the current document', () => {
    setBody(`
      <a id="cancelled" href="/cancelled">Cancelled</a>
      <a id="fragment" href="#section">Fragment</a>
      <a id="new-tab" href="/new-tab" target="_blank">New tab</a>
      <a id="download" href="/file.pdf" download>Download</a>
      <a id="modified" href="/modified">Modified</a>
    `)
    document.querySelector('#cancelled')!.addEventListener('click', (event) => event.preventDefault())
    tracker = new ClickTracker()
    tracker.start()

    clickLink('#cancelled')
    clickLink('#fragment')
    clickLink('#new-tab')
    clickLink('#download')
    clickLink('#modified', { ctrlKey: true })

    expect(sink.logEvent).toHaveBeenCalledTimes(5)
    expect(sink.logEventBeacon).not.toHaveBeenCalled()
  })

  it('should track touch events when enabled', () => {
    tracker = new ClickTracker({ trackTouch: true })
    tracker.start()

    const btn = document.querySelector('#btn')!
    const touch = new Touch({ identifier: 1, target: btn, clientX: 10, clientY: 20, pageX: 10, pageY: 20 })
    btn.dispatchEvent(new TouchEvent('touchstart', { bubbles: true, touches: [touch] }))

    expect(sink.logEvent).toHaveBeenCalledTimes(1)
    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.type).toBe('touch')
    expect(call.element.extras.is_touch).toBe('true')
  })

  it('should not track touch events when disabled', () => {
    tracker = new ClickTracker({ trackTouch: false })
    tracker.start()

    const btn = document.querySelector('#btn')!
    const touch = new Touch({ identifier: 1, target: btn, clientX: 10, clientY: 20, pageX: 10, pageY: 20 })
    btn.dispatchEvent(new TouchEvent('touchstart', { bubbles: true, touches: [touch] }))

    expect(sink.logEvent).not.toHaveBeenCalled()
  })

  it('should detect rage clicks and reset so subsequent clicks do not re-trigger', () => {
    tracker = new ClickTracker({ detectRageClicks: true, rageClickThreshold: 3, rageClickWindow: 5000 })
    tracker.start()

    const btn = document.querySelector('#btn')!
    // 3 clicks triggers rage detection
    for (let i = 0; i < 3; i++) {
      btn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    }

    let calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    let rageCalls = calls.filter((c: any[]) => c[0].element.type === 'rage_click')
    expect(rageCalls.length).toBe(1)
    expect(rageCalls[0][0].element.extras.click_count).toBe('3')

    // A 4th click should NOT fire another rage event (recentClicks was cleared)
    btn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    rageCalls = calls.filter((c: any[]) => c[0].element.type === 'rage_click')
    expect(rageCalls.length).toBe(1) // still just 1
  })

  it('should not detect rage clicks when disabled', () => {
    tracker = new ClickTracker({ detectRageClicks: false })
    tracker.start()

    const btn = document.querySelector('#btn')!
    for (let i = 0; i < 5; i++) {
      btn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    }

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const rageCalls = calls.filter((c: any[]) => c[0].element.type === 'rage_click')
    expect(rageCalls.length).toBe(0)
  })

  it('should handle touch with no touches array gracefully', () => {
    tracker = new ClickTracker({ trackTouch: true })
    tracker.start()

    const btn = document.querySelector('#btn')!
    btn.dispatchEvent(new TouchEvent('touchstart', { bubbles: true, touches: [] }))

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const touchCalls = calls.filter((c: any[]) => c[0].element?.type === 'touch')
    expect(touchCalls.length).toBe(0)
  })

  it('should include data-ba-* extras and content in click events', () => {
    setBody(`
      <div data-ba-section="hero" data-ba-element-type="recommendation_item" data-ba-content-id="cat-1" data-ba-content-type="category" data-ba-experiment-source="recommendations">
        <button id="buy" data-ba-action="purchase" data-ba-content-id="prod-5" data-ba-content-type="product">Buy</button>
      </div>
    `)
    tracker = new ClickTracker()
    tracker.start()

    document.querySelector('#buy')!.dispatchEvent(new MouseEvent('click', { bubbles: true }))

    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.extras.action).toBe('purchase')
    expect(call.element.extras.section).toBe('hero')
    expect(call.element.extras.experiment_source).toBe('recommendations')
    expect(call.element.type).toBe('click')
    expect(call.element.extras.element_type).toBe('recommendation_item')
    expect(call.element.content).toEqual([
      { id: 'prod-5', type: 'product' },
      { id: 'cat-1', type: 'category' },
    ])
  })

  it('should clean up listeners on stop', () => {
    tracker = new ClickTracker({ trackTouch: true })
    tracker.start()
    tracker.stop()

    const btn = document.querySelector('#btn')!
    btn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    clickLink('a')
    expect(sink.logEvent).not.toHaveBeenCalled()
    expect(sink.logEventBeacon).not.toHaveBeenCalled()
  })
})
