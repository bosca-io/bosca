// @vitest-environment happy-dom

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { FormTracker } from './form_tracker'
import * as sink from '../sink'

vi.mock('../sink', async () => {
  const actual = await vi.importActual<typeof sink>('../sink')
  return {
    ...actual,
    logEvent: vi.fn().mockImplementation(() => Promise.resolve()),
    logEventBeacon: vi.fn(),
  }
})

// Test-only helper: sets DOM body using hardcoded fixture strings (no user input)
function setTestBody(html: string) {
  document.body.innerHTML = html // eslint-disable-line
}

describe('FormTracker', () => {
  let tracker: FormTracker

  beforeEach(() => {
    vi.clearAllMocks()
    setTestBody('<form id="myform"><input name="email" type="email" /><button type="submit">Go</button></form>')
  })

  afterEach(() => {
    tracker?.stop()
  })

  it('reports transport failures for form submissions and field interactions', async () => {
    const failure = new Error('offline')
    vi.mocked(sink.logEvent).mockRejectedValue(failure)
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      setTestBody('<form><input name="email"></form>')
      tracker = new FormTracker({ trackFieldInteractions: true })
      tracker.start()
      document.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true }))
      const input = document.querySelector('input')!
      input.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))
      input.dispatchEvent(new FocusEvent('focusout', { bubbles: true }))
      await vi.waitFor(() => expect(error).toHaveBeenCalledTimes(3))
      expect(error).toHaveBeenCalledWith('failed to track form submit:', failure)
      expect(error).toHaveBeenCalledWith('failed to track field focus:', failure)
      expect(error).toHaveBeenCalledWith('failed to track field blur:', failure)
    } finally {
      vi.mocked(sink.logEvent).mockResolvedValue(undefined)
      error.mockRestore()
    }
  })

  it('should track form submissions', () => {
    tracker = new FormTracker()
    tracker.start()

    const form = document.querySelector('#myform')!
    form.dispatchEvent(new Event('submit', { bubbles: true }))

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const submitCall = calls.find((c: any[]) => c[0].element.type === 'form_submit')
    expect(submitCall).toBeDefined()
    expect(submitCall![0].element.extras.form_method).toBe('get')
  })

  it('should ignore submit events on non-form elements', () => {
    tracker = new FormTracker()
    tracker.start()

    const btn = document.querySelector('button')!
    btn.dispatchEvent(new Event('submit', { bubbles: true }))

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const submitCalls = calls.filter((c: any[]) => c[0].element.type === 'form_submit')
    expect(submitCalls.length).toBe(0)
  })

  it('should track field focus events', () => {
    tracker = new FormTracker({ trackFieldInteractions: true })
    tracker.start()

    const input = document.querySelector('input')!
    input.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const focusCall = calls.find((c: any[]) => c[0].element.type === 'field_focus')
    expect(focusCall).toBeDefined()
    expect(focusCall![0].element.extras.field_type).toBe('email')
    expect(focusCall![0].element.extras.field_name).toBe('email')
  })

  it('should track field blur events with dwell time', () => {
    tracker = new FormTracker({ trackFieldInteractions: true })
    tracker.start()

    const input = document.querySelector('input')!
    input.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))
    input.dispatchEvent(new FocusEvent('focusout', { bubbles: true }))

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const blurCall = calls.find((c: any[]) => c[0].element.type === 'field_blur')
    expect(blurCall).toBeDefined()
    expect(blurCall![0].element.extras.dwell_ms).toBeDefined()
  })

  it('should handle blur without prior focus', () => {
    tracker = new FormTracker({ trackFieldInteractions: true })
    tracker.start()

    const input = document.querySelector('input')!
    input.dispatchEvent(new FocusEvent('focusout', { bubbles: true }))

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const blurCall = calls.find((c: any[]) => c[0].element.type === 'field_blur')
    expect(blurCall).toBeDefined()
    expect(blurCall![0].element.extras.dwell_ms).toBe('0')
  })

  it('should ignore focus on non-form-field elements', () => {
    tracker = new FormTracker({ trackFieldInteractions: true })
    tracker.start()

    const btn = document.querySelector('button')!
    btn.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const focusCalls = calls.filter((c: any[]) => c[0].element.type === 'field_focus')
    expect(focusCalls.length).toBe(0)
  })

  it('should not track field interactions when disabled', () => {
    tracker = new FormTracker({ trackFieldInteractions: false })
    tracker.start()

    const input = document.querySelector('input')!
    input.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))

    const calls = (sink.logEvent as ReturnType<typeof vi.fn>).mock.calls
    const focusCalls = calls.filter((c: any[]) => c[0].element.type === 'field_focus')
    expect(focusCalls.length).toBe(0)
  })

  it('should track form abandonment on unload', () => {
    tracker = new FormTracker({ trackFieldInteractions: true, trackAbandonment: true })
    tracker.start()

    const input = document.querySelector('input')!
    input.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))

    window.dispatchEvent(new Event('beforeunload'))

    expect(sink.logEventBeacon).toHaveBeenCalled()
    const call = (sink.logEventBeacon as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(call.element.type).toBe('form_abandon')
  })

  it('should not track abandonment for submitted forms', () => {
    tracker = new FormTracker({ trackFieldInteractions: true, trackAbandonment: true })
    tracker.start()

    const input = document.querySelector('input')!
    input.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))

    const form = document.querySelector('#myform')!
    form.dispatchEvent(new Event('submit', { bubbles: true }))

    window.dispatchEvent(new Event('beforeunload'))

    const beaconCalls = (sink.logEventBeacon as ReturnType<typeof vi.fn>).mock.calls
    const abandonCalls = beaconCalls.filter((c: any[]) => c[0].element.type === 'form_abandon')
    expect(abandonCalls.length).toBe(0)
  })

  it('should not track abandonment when disabled', () => {
    tracker = new FormTracker({ trackAbandonment: false })
    tracker.start()

    window.dispatchEvent(new Event('beforeunload'))
    expect(sink.logEventBeacon).not.toHaveBeenCalled()
  })

  it('should clean up all listeners on stop', () => {
    tracker = new FormTracker({ trackFieldInteractions: true, trackAbandonment: true })
    tracker.start()
    tracker.stop()

    const form = document.querySelector('#myform')!
    form.dispatchEvent(new Event('submit', { bubbles: true }))
    expect(sink.logEvent).not.toHaveBeenCalled()
  })
})
