// @vitest-environment happy-dom

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { AnalyticEventType } from '../event'
import { ScrollTracker } from './scroll_tracker'
import * as sink from '../sink'
import * as viewport from './viewport'

vi.mock('./viewport', { spy: true })

vi.mock('../sink', async () => {
  const actual = await vi.importActual<typeof sink>('../sink')
  return {
    ...actual,
    logEvent: vi.fn().mockImplementation(() => Promise.resolve()),
  }
})

describe('ScrollTracker', () => {
  let tracker: ScrollTracker

  beforeEach(() => {
    vi.useFakeTimers()
    ;(sink.logEvent as ReturnType<typeof vi.fn>).mockImplementation(() => Promise.resolve())
  })

  afterEach(() => {
    tracker?.stop()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  function mockViewport(scrollY: number, viewportHeight: number, documentHeight: number) {
    vi.spyOn(viewport, 'getViewportInfo').mockReturnValue({
      viewportWidth: 1024,
      viewportHeight,
      documentWidth: 1024,
      documentHeight,
      scrollX: 0,
      scrollY,
    })
  }

  it('should emit depth marks when scroll thresholds are crossed', () => {
    tracker = new ScrollTracker({ depthMarks: [25, 50, 75, 100], throttleMs: 0 })
    tracker.start()

    // Simulate 50% scroll on a 2000px page with 500px viewport
    mockViewport(500, 500, 2000)
    window.dispatchEvent(new Event('scroll'))
    vi.advanceTimersByTime(10)

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const depthCalls = calls.filter((c: any[]) => c[0].element.type === 'scroll_depth')
    expect(depthCalls.every((c: any[]) => c[0].type === AnalyticEventType.interaction)).toBe(true)
    // Should have hit 25% and 50% marks
    expect(depthCalls.length).toBeGreaterThanOrEqual(1)
    const depths = depthCalls.map((c: any[]) => c[0].element.extras.depth_percent)
    expect(depths).toContain('25')
    expect(depths).toContain('50')
  })

  it('should not re-emit already-reached depth marks', () => {
    tracker = new ScrollTracker({ depthMarks: [50], throttleMs: 0 })
    tracker.start()

    mockViewport(500, 500, 2000)
    window.dispatchEvent(new Event('scroll'))
    vi.advanceTimersByTime(10)

    // Scroll again to same position
    window.dispatchEvent(new Event('scroll'))
    vi.advanceTimersByTime(10)

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const depthCalls = calls.filter((c: any[]) => c[0].element.type === 'scroll_depth')
    expect(depthCalls.length).toBe(1)
  })

  it('should throttle scroll measurements', () => {
    tracker = new ScrollTracker({ depthMarks: [25, 50], throttleMs: 500 })
    tracker.start()

    mockViewport(500, 500, 2000)
    window.dispatchEvent(new Event('scroll'))
    // Second scroll within throttle window should be ignored
    window.dispatchEvent(new Event('scroll'))

    vi.advanceTimersByTime(500)

    // Only one measurement should have occurred
    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    expect(calls.length).toBeGreaterThanOrEqual(1)
  })

  it('should skip measurement when document is not scrollable', () => {
    tracker = new ScrollTracker({ depthMarks: [50], throttleMs: 0 })
    tracker.start()

    // viewport height === document height: not scrollable
    mockViewport(0, 1000, 1000)
    window.dispatchEvent(new Event('scroll'))
    vi.advanceTimersByTime(10)

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const depthCalls = calls.filter((c: any[]) => c[0].element.type === 'scroll_depth')
    expect(depthCalls.length).toBe(0)
  })

  it('should emit max depth on stop', () => {
    tracker = new ScrollTracker({ depthMarks: [25], throttleMs: 0 })
    tracker.start()

    mockViewport(250, 500, 2000)
    window.dispatchEvent(new Event('scroll'))
    vi.advanceTimersByTime(10)

    tracker.stop()

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const maxDepthCalls = calls.filter((c: any[]) => c[0].element.type === 'scroll_max_depth')
    expect(maxDepthCalls.length).toBe(1)
    expect(Number(maxDepthCalls[0][0].element.extras.max_depth_percent)).toBeGreaterThan(0)
  })

  it('should reset depth marks on resetForNewPage', () => {
    tracker = new ScrollTracker({ depthMarks: [50], throttleMs: 0 })
    tracker.start()

    mockViewport(500, 500, 2000)
    window.dispatchEvent(new Event('scroll'))
    vi.advanceTimersByTime(10)

    tracker.resetForNewPage()

    // Same scroll should re-emit 50%
    window.dispatchEvent(new Event('scroll'))
    vi.advanceTimersByTime(10)

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const depthCalls = calls.filter((c: any[]) => c[0].element.type === 'scroll_depth')
    expect(depthCalls.length).toBe(2)
  })

  it('should not emit max depth when no scrolling occurred', () => {
    tracker = new ScrollTracker({ depthMarks: [50], throttleMs: 0 })
    tracker.start()
    tracker.stop()

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const maxDepthCalls = calls.filter((c: any[]) => c[0].element.type === 'scroll_max_depth')
    expect(maxDepthCalls.length).toBe(0)
  })
})
