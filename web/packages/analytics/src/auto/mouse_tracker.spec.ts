// @vitest-environment happy-dom

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { MouseTracker } from './mouse_tracker'
import * as sink from '../sink'

vi.mock('../sink', async () => {
  const actual = await vi.importActual<typeof sink>('../sink')
  return {
    ...actual,
    logEvent: vi.fn().mockImplementation(() => Promise.resolve()),
  }
})

describe('MouseTracker', () => {
  let tracker: MouseTracker

  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
  })

  afterEach(() => {
    tracker?.stop()
    vi.useRealTimers()
  })

  it('should sample mouse movements at the configured interval', () => {
    tracker = new MouseTracker({ sampleInterval: 50, batchSize: 100 })
    tracker.start()

    // Fire two moves 100ms apart (both should be sampled)
    document.dispatchEvent(new MouseEvent('mousemove', { clientX: 10, clientY: 20 }))
    vi.advanceTimersByTime(100)
    document.dispatchEvent(new MouseEvent('mousemove', { clientX: 30, clientY: 40 }))

    // Flush via 5-second timer
    vi.advanceTimersByTime(5000)

    expect(sink.logEvent).toHaveBeenCalled()
    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.type).toBe('mouse_movement')
    expect(call.element.extras.sample_count).toBe('2')
  })

  it('should skip samples that are too close together', () => {
    tracker = new MouseTracker({ sampleInterval: 100, batchSize: 100 })
    tracker.start()

    // Fire two moves with no time advancing (0ms apart)
    document.dispatchEvent(new MouseEvent('mousemove', { clientX: 1, clientY: 1 }))
    document.dispatchEvent(new MouseEvent('mousemove', { clientX: 2, clientY: 2 }))

    vi.advanceTimersByTime(5000)

    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.extras.sample_count).toBe('1')
  })

  it('should auto-flush when batch size is reached', () => {
    tracker = new MouseTracker({ sampleInterval: 0, batchSize: 3 })
    tracker.start()

    for (let i = 0; i < 3; i++) {
      vi.advanceTimersByTime(1)
      document.dispatchEvent(new MouseEvent('mousemove', { clientX: i, clientY: i }))
    }

    expect(sink.logEvent).toHaveBeenCalled()
    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.extras.sample_count).toBe('3')
  })

  it('should flush remaining samples on stop', () => {
    tracker = new MouseTracker({ sampleInterval: 0, batchSize: 100 })
    tracker.start()

    document.dispatchEvent(new MouseEvent('mousemove', { clientX: 5, clientY: 5 }))
    tracker.stop()

    expect(sink.logEvent).toHaveBeenCalled()
    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.extras.sample_count).toBe('1')
  })

  it('should not flush on stop if no samples collected', () => {
    tracker = new MouseTracker({ sampleInterval: 100, batchSize: 50 })
    tracker.start()
    tracker.stop()

    expect(sink.logEvent).not.toHaveBeenCalled()
  })

  it('should clean up listeners on stop', () => {
    tracker = new MouseTracker({ sampleInterval: 0, batchSize: 100 })
    tracker.start()
    tracker.stop()

    document.dispatchEvent(new MouseEvent('mousemove', { clientX: 1, clientY: 1 }))
    vi.advanceTimersByTime(5000)

    // Only the flush-on-stop call, no new events
    expect(sink.logEvent).not.toHaveBeenCalled()
  })

  it('should include viewport info in events', () => {
    tracker = new MouseTracker({ sampleInterval: 0, batchSize: 1 })
    tracker.start()

    vi.advanceTimersByTime(1)
    document.dispatchEvent(new MouseEvent('mousemove', { clientX: 1, clientY: 1 }))

    const call = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.extras.viewport_width).toBeDefined()
    expect(call.element.extras.viewport_height).toBeDefined()
  })
})
