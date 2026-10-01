// @vitest-environment happy-dom

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ErrorTracker } from './error_tracker'
import * as sink from '../sink'

function dispatchErrorFixture(event: ErrorEvent | PromiseRejectionEvent) {
  // A synthetic fixture error is handled by the test even when SDK capture is disabled.
  // Register only during its synchronous dispatch so other uncaught errors still fail.
  const acknowledge = (received: Event) => { if (received === event) received.preventDefault() }
  window.addEventListener(event.type, acknowledge)
  try { window.dispatchEvent(event) }
  finally { window.removeEventListener(event.type, acknowledge) }
}

if (typeof globalThis.PromiseRejectionEvent === 'undefined') {
  (globalThis as any).PromiseRejectionEvent = class PromiseRejectionEvent extends Event {
    readonly promise: Promise<any>
    readonly reason: any
    constructor(type: string, init: { promise: Promise<any>; reason?: any }) {
      super(type)
      this.promise = init.promise
      this.reason = init.reason
    }
  }
}

vi.mock('../sink', async () => {
  const actual = await vi.importActual<typeof sink>('../sink')
  return {
    ...actual,
    logError: vi.fn().mockImplementation(() => Promise.resolve()),
  }
})

describe('ErrorTracker', () => {
  let tracker: ErrorTracker

  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    tracker?.stop()
  })

  it('keeps error capture usable when both error transports reject', async () => {
    vi.mocked(sink.logError).mockRejectedValueOnce(new Error('offline')).mockRejectedValueOnce(new Error('offline'))
    tracker = new ErrorTracker()
    tracker.start()
    dispatchErrorFixture(new ErrorEvent('error', { message: 'fixture error', error: new Error('fixture error') }))
    dispatchErrorFixture(new PromiseRejectionEvent('unhandledrejection', { promise: Promise.resolve(), reason: new Error('fixture rejection') }))
    await Promise.resolve()
    expect(sink.logError).toHaveBeenCalledTimes(2)
  })

  it('should capture uncaught errors via the error event', () => {
    tracker = new ErrorTracker()
    tracker.start()

    const error = new TypeError('Cannot read property of undefined')
    dispatchErrorFixture(
      new ErrorEvent('error', {
        message: error.message,
        error,
        filename: 'app.js',
        lineno: 42,
        colno: 10,
      }),
    )

    expect(sink.logError).toHaveBeenCalledTimes(1)
    const [errorInfo, element] = (sink.logError as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(errorInfo.message).toBe('Cannot read property of undefined')
    expect(errorInfo.type).toBe('TypeError')
    expect(errorInfo.fatal).toBe(false)
    expect(element.type).toBe('uncaught_error')
    expect(element.extras.filename).toBe('app.js')
    expect(element.extras.lineno).toBe('42')
    expect(element.extras.colno).toBe('10')
  })

  it('should capture unhandled promise rejections with Error reason', () => {
    tracker = new ErrorTracker()
    tracker.start()

    const error = new RangeError('out of bounds')
    dispatchErrorFixture(
      new PromiseRejectionEvent('unhandledrejection', {
        promise: Promise.resolve(),
        reason: error,
      }),
    )

    expect(sink.logError).toHaveBeenCalledTimes(1)
    const [errorInfo, element] = (sink.logError as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(errorInfo.message).toBe('out of bounds')
    expect(errorInfo.type).toBe('RangeError')
    expect(element.type).toBe('unhandled_rejection')
  })

  it('should capture unhandled promise rejections with string reason', () => {
    tracker = new ErrorTracker()
    tracker.start()

    dispatchErrorFixture(
      new PromiseRejectionEvent('unhandledrejection', {
        promise: Promise.resolve(),
        reason: 'something failed',
      }),
    )

    expect(sink.logError).toHaveBeenCalledTimes(1)
    const [errorInfo] = (sink.logError as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(errorInfo.message).toBe('something failed')
    expect(errorInfo.type).toBe('UnhandledRejection')
  })

  it('should capture unhandled promise rejections with non-string, non-Error reason', () => {
    tracker = new ErrorTracker()
    tracker.start()

    dispatchErrorFixture(
      new PromiseRejectionEvent('unhandledrejection', {
        promise: Promise.resolve(),
        reason: 42,
      }),
    )

    expect(sink.logError).toHaveBeenCalledTimes(1)
    const [errorInfo] = (sink.logError as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(errorInfo.message).toBe('Unhandled promise rejection')
    expect(errorInfo.type).toBe('UnhandledRejection')
  })

  it('should handle error events with no error object', () => {
    tracker = new ErrorTracker()
    tracker.start()

    dispatchErrorFixture(
      new ErrorEvent('error', {
        message: 'Script error.',
        filename: '',
        lineno: 0,
        colno: 0,
      }),
    )

    expect(sink.logError).toHaveBeenCalledTimes(1)
    const [errorInfo] = (sink.logError as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(errorInfo.message).toBe('Script error.')
    expect(errorInfo.type).toBe('Error')
    expect(errorInfo.stack_trace).toBeUndefined()
  })

  it('should throttle errors beyond maxErrorsPerMinute', () => {
    tracker = new ErrorTracker({ maxErrorsPerMinute: 3 })
    tracker.start()

    for (let i = 0; i < 5; i++) {
      dispatchErrorFixture(
        new ErrorEvent('error', { message: `Error ${i}`, error: new Error(`Error ${i}`) }),
      )
    }

    expect(sink.logError).toHaveBeenCalledTimes(3)
  })

  it('should not capture errors when captureUncaught is disabled', () => {
    tracker = new ErrorTracker({ captureUncaught: false })
    tracker.start()

    dispatchErrorFixture(
      new ErrorEvent('error', { message: 'should be ignored', error: new Error('ignored') }),
    )

    expect(sink.logError).not.toHaveBeenCalled()
  })

  it('should not capture rejections when captureUnhandledRejections is disabled', () => {
    tracker = new ErrorTracker({ captureUnhandledRejections: false })
    tracker.start()

    dispatchErrorFixture(
      new PromiseRejectionEvent('unhandledrejection', {
        promise: Promise.resolve(),
        reason: new Error('ignored'),
      }),
    )

    expect(sink.logError).not.toHaveBeenCalled()
  })

  it('should clean up all listeners on stop', () => {
    tracker = new ErrorTracker()
    tracker.start()
    tracker.stop()

    dispatchErrorFixture(
      new ErrorEvent('error', { message: 'after stop', error: new Error('after stop') }),
    )
    dispatchErrorFixture(
      new PromiseRejectionEvent('unhandledrejection', {
        promise: Promise.resolve(),
        reason: new Error('after stop'),
      }),
    )

    expect(sink.logError).not.toHaveBeenCalled()
  })
})
