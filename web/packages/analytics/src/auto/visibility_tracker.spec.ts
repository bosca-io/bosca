// @vitest-environment happy-dom

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { VisibilityTracker } from './visibility_tracker'
import * as sink from '../sink'

vi.mock('../sink', async () => {
  const actual = await vi.importActual<typeof sink>('../sink')
  return {
    ...actual,
    logEvent: vi.fn().mockImplementation(() => Promise.resolve()),
  }
})

// Capture IntersectionObserver callbacks so we can simulate intersections
let observerCallback: IntersectionObserverCallback
let observedElements: Element[] = []

class MockIntersectionObserver {
  constructor(callback: IntersectionObserverCallback, public options?: IntersectionObserverInit) {
    observerCallback = callback
  }
  observe(el: Element) { observedElements.push(el) }
  unobserve(_el: Element) {}
  disconnect() { observedElements = [] }
}

// Capture MutationObserver callback so we can trigger it manually
let mutationCallback: MutationCallback | null = null

class MockMutationObserver {
  constructor(callback: MutationCallback) {
    mutationCallback = callback
  }
  observe(_target: Node, _options?: MutationObserverInit) {}
  disconnect() { mutationCallback = null }
  takeRecords(): MutationRecord[] { return [] }
}

// Test-only DOM setup helper using hardcoded fixture strings
function setTestBody(content: string) {
  document.body.innerHTML = content // eslint-disable-line
}

describe('VisibilityTracker', () => {
  let tracker: VisibilityTracker

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(sink.logEvent).mockResolvedValue(undefined)
    vi.useFakeTimers()
    observedElements = []
    mutationCallback = null
    ;(globalThis as any).IntersectionObserver = MockIntersectionObserver
    ;(globalThis as any).MutationObserver = MockMutationObserver
    setTestBody('<div data-ba-track="hero">Hero</div><div data-ba-track="cta">CTA</div>')
  })

  afterEach(() => {
    tracker?.stop()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('requires the configured visible fraction throughout the dwell interval', () => {
    tracker = new VisibilityTracker()
    tracker.start()
    const target = observedElements[0]!
    const enter = (ratio: number) => observerCallback(
      [{ target, isIntersecting: true, intersectionRatio: ratio } as IntersectionObserverEntry], {} as IntersectionObserver,
    )
    enter(.1)
    vi.advanceTimersByTime(1100)
    expect(sink.logEvent).not.toHaveBeenCalled()
    enter(.8)
    vi.advanceTimersByTime(700)
    enter(.4)
    vi.advanceTimersByTime(1100)
    expect(sink.logEvent).not.toHaveBeenCalled()
    enter(.5)
    vi.advanceTimersByTime(1000)
    expect(sink.logEvent).toHaveBeenCalledTimes(1)
    const extras = vi.mocked(sink.logEvent).mock.calls[0]![0].element.extras
    expect(extras).toMatchObject({ visible_ms: '1000', visibility_threshold: '0.5' })
  })

  it('restarts continuous visibility after the document is hidden', () => {
    let hidden = false
    vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden)
    tracker = new VisibilityTracker()
    tracker.start()
    observerCallback([{ target: observedElements[0]!, isIntersecting: true, intersectionRatio: 1 } as IntersectionObserverEntry], {} as IntersectionObserver)
    vi.advanceTimersByTime(700)
    hidden = true
    document.dispatchEvent(new Event('visibilitychange'))
    vi.advanceTimersByTime(2000)
    expect(sink.logEvent).not.toHaveBeenCalled()
    hidden = false
    document.dispatchEvent(new Event('visibilitychange'))
    vi.advanceTimersByTime(999)
    expect(sink.logEvent).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(sink.logEvent).toHaveBeenCalledTimes(1)
  })

  it('requires a fresh dwell interval when a card changes content', () => {
    setTestBody('<article data-ba-content-id="old" data-ba-visible-ms="30000" data-ba-visibility-threshold="1"></article>')
    tracker = new VisibilityTracker()
    tracker.start()
    const target = observedElements[0]!
    observerCallback([{ target, isIntersecting: true, intersectionRatio: 1 } as IntersectionObserverEntry], {} as IntersectionObserver)
    vi.advanceTimersByTime(500)
    target.setAttribute('data-ba-content-id', 'new')
    mutationCallback?.([{ type: 'attributes', target, addedNodes: [] } as unknown as MutationRecord], {} as MutationObserver)
    vi.advanceTimersByTime(999)
    expect(sink.logEvent).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    const event = vi.mocked(sink.logEvent).mock.calls[0]![0]
    expect(event.element.content?.[0]?.id).toBe('new')
    expect(event.element.extras).toMatchObject({ visible_ms: '1000', visibility_threshold: '0.5' })
  })

  it('does not emit an impression for a disconnected card', () => {
    tracker = new VisibilityTracker()
    tracker.start()
    const target = observedElements[0]!
    observerCallback([{ target, isIntersecting: true, intersectionRatio: 1 } as IntersectionObserverEntry], {} as IntersectionObserver)
    target.remove()
    vi.advanceTimersByTime(1100)
    expect(sink.logEvent).not.toHaveBeenCalled()
  })

  it('should observe elements matching the selector', () => {
    tracker = new VisibilityTracker()
    tracker.start()

    expect(observedElements.length).toBe(2)
  })

  it('should emit impression after dwell time', () => {
    tracker = new VisibilityTracker({ dwellTime: 500 })
    tracker.start()

    const el = document.querySelector('[data-ba-track="hero"]')!

    // Simulate element becoming visible
    observerCallback(
      [{ target: el, isIntersecting: true, intersectionRatio: 1 } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    )

    // Before dwell time — no event
    vi.advanceTimersByTime(400)
    expect(sink.logEvent).not.toHaveBeenCalled()

    // After dwell time — event fires
    vi.advanceTimersByTime(200)
    expect(sink.logEvent).toHaveBeenCalledTimes(1)
    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.type).toBe('impression')
    expect(call.element.extras.track_id).toBe('hero')
  })

  it('should cancel impression if element leaves viewport before dwell time', () => {
    tracker = new VisibilityTracker({ dwellTime: 500 })
    tracker.start()

    const el = document.querySelector('[data-ba-track="cta"]')!

    // Element enters viewport
    observerCallback(
      [{ target: el, isIntersecting: true, intersectionRatio: 1 } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    )

    vi.advanceTimersByTime(300)

    // Element leaves viewport before dwell completes
    observerCallback(
      [{ target: el, isIntersecting: false } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    )

    vi.advanceTimersByTime(500)
    expect(sink.logEvent).not.toHaveBeenCalled()
  })

  it('should not start duplicate dwell timers for same element', () => {
    tracker = new VisibilityTracker({ dwellTime: 500 })
    tracker.start()

    const el = document.querySelector('[data-ba-track="hero"]')!

    observerCallback(
      [{ target: el, isIntersecting: true, intersectionRatio: 1 } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    )
    // Second intersection entry for same element
    observerCallback(
      [{ target: el, isIntersecting: true, intersectionRatio: 1 } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    )

    vi.advanceTimersByTime(600)
    expect(sink.logEvent).toHaveBeenCalledTimes(1)
  })

  it('should observe new elements added to DOM via MutationObserver', () => {
    tracker = new VisibilityTracker()
    tracker.start()

    const initialCount = observedElements.length

    // Add a new element to the DOM
    const newEl = document.createElement('div')
    newEl.setAttribute('data-ba-track', 'new-section')
    newEl.textContent = 'New'
    document.body.appendChild(newEl)

    // Trigger the MutationObserver callback with the added node
    if (mutationCallback) {
      const record = {
        addedNodes: [newEl] as unknown as NodeList,
        removedNodes: [] as unknown as NodeList,
      } as MutationRecord
      mutationCallback([record], {} as MutationObserver)
    }

    // The callback is debounced with setTimeout(100)
    vi.advanceTimersByTime(150)

    expect(observedElements.length).toBeGreaterThan(initialCount)
  })

  it('should use custom selector', () => {
    setTestBody('<section class="track-me">Section</section>')
    tracker = new VisibilityTracker({ selector: '.track-me' })
    tracker.start()

    expect(observedElements.length).toBe(1)
  })

  it('should clean up on stop', () => {
    tracker = new VisibilityTracker({ dwellTime: 500 })
    tracker.start()

    const el = document.querySelector('[data-ba-track="hero"]')!
    observerCallback(
      [{ target: el, isIntersecting: true, intersectionRatio: 1 } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    )

    tracker.stop()

    // Dwell timer should have been cleared
    vi.advanceTimersByTime(1000)
    expect(sink.logEvent).not.toHaveBeenCalled()
  })

  it('should include data-ba-* extras and content in impression events', () => {
    setTestBody('<div data-ba-track="promo" data-ba-element-type="recommendation_item" data-ba-campaign="spring" data-ba-content-id="offer-1" data-ba-content-type="offer" data-ba-experiment-source="recommendations">Promo</div>')
    tracker = new VisibilityTracker({ dwellTime: 100 })
    tracker.start()

    const el = document.querySelector('[data-ba-track="promo"]')!
    observerCallback(
      [{ target: el, isIntersecting: true, intersectionRatio: 1 } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    )
    vi.advanceTimersByTime(200)

    expect(sink.logEvent).toHaveBeenCalledTimes(1)
    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.extras.campaign).toBe('spring')
    expect(call.element.extras.track_id).toBe('promo')
    expect(call.element.extras.experiment_source).toBe('recommendations')
    expect(call.element.type).toBe('recommendation_item')
    expect(call.element.content).toEqual([{ id: 'offer-1', type: 'offer' }])
  })

  it('should auto-observe elements with data-ba-content-id', () => {
    setTestBody('<div data-ba-content-id="product-1" data-ba-content-type="product">Product</div>')
    tracker = new VisibilityTracker()
    tracker.start()

    expect(observedElements.length).toBe(1)
  })

  it('should handle element leaving viewport that has no dwell timer', () => {
    tracker = new VisibilityTracker()
    tracker.start()

    const el = document.querySelector('[data-ba-track="hero"]')!

    // Element leaves viewport without ever entering (no dwell timer set)
    expect(() => {
      observerCallback(
        [{ target: el, isIntersecting: false } as IntersectionObserverEntry],
        {} as IntersectionObserver,
      )
    }).not.toThrow()
  })
})
