import { logError } from '../sink'

/**
 * Configuration for automatic error capturing.
 */
export interface ErrorTrackerOptions {
  /** Capture uncaught synchronous errors via the global error event */
  captureUncaught: boolean
  /** Capture unhandled promise rejections */
  captureUnhandledRejections: boolean
  /** Maximum number of errors to capture per minute before throttling (prevents flood from error loops) */
  maxErrorsPerMinute: number
}

const DEFAULT_OPTIONS: ErrorTrackerOptions = {
  captureUncaught: true,
  captureUnhandledRejections: true,
  maxErrorsPerMinute: 30,
}

/**
 * Automatically captures uncaught JavaScript errors and unhandled promise
 * rejections, forwarding them through the analytics error pipeline. Includes
 * per-minute throttling to prevent error loops from flooding the collector.
 */
export class ErrorTracker {
  private options: ErrorTrackerOptions
  private errorHandler: ((event: ErrorEvent) => void) | null = null
  private rejectionHandler: ((event: PromiseRejectionEvent) => void) | null = null
  private errorTimestamps: number[] = []

  constructor(options: Partial<ErrorTrackerOptions> = {}) {
    this.options = { ...DEFAULT_OPTIONS, ...options }
  }

  /** Attaches global error and unhandled rejection listeners. */
  start() {
    if (typeof window === 'undefined') return

    if (this.options.captureUncaught) {
      this.errorHandler = (event: ErrorEvent) => this.onError(event)
      window.addEventListener('error', this.errorHandler)
    }

    if (this.options.captureUnhandledRejections) {
      this.rejectionHandler = (event: PromiseRejectionEvent) => this.onUnhandledRejection(event)
      window.addEventListener('unhandledrejection', this.rejectionHandler)
    }
  }

  /** Removes all listeners. */
  stop() {
    if (typeof window === 'undefined') return

    if (this.errorHandler) {
      window.removeEventListener('error', this.errorHandler)
      this.errorHandler = null
    }
    if (this.rejectionHandler) {
      window.removeEventListener('unhandledrejection', this.rejectionHandler)
      this.rejectionHandler = null
    }
  }

  private isThrottled(): boolean {
    const now = Date.now()
    this.errorTimestamps = this.errorTimestamps.filter((t) => now - t < 60_000)
    if (this.errorTimestamps.length >= this.options.maxErrorsPerMinute) {
      return true
    }
    this.errorTimestamps.push(now)
    return false
  }

  private onError(event: ErrorEvent) {
    if (this.isThrottled()) return

    const error = event.error
    const stack = error instanceof Error ? error.stack : undefined

    logError(
      {
        message: event.message || 'Unknown error',
        type: error instanceof Error ? error.constructor.name : 'Error',
        stack_trace: stack,
        fatal: false,
      },
      {
        id: event.filename || '',
        type: 'uncaught_error',
        extras: {
          filename: event.filename || '',
          lineno: String(event.lineno ?? ''),
          colno: String(event.colno ?? ''),
        },
      },
    ).catch(() => {})
  }

  private onUnhandledRejection(event: PromiseRejectionEvent) {
    if (this.isThrottled()) return

    const reason = event.reason
    let message: string
    let type: string
    let stack: string | undefined

    if (reason instanceof Error) {
      message = reason.message
      type = reason.constructor.name
      stack = reason.stack
    } else if (typeof reason === 'string') {
      message = reason
      type = 'UnhandledRejection'
    } else {
      message = 'Unhandled promise rejection'
      type = 'UnhandledRejection'
    }

    logError(
      {
        message,
        type,
        stack_trace: stack,
        fatal: false,
      },
      {
        id: '',
        type: 'unhandled_rejection',
      },
    ).catch(() => {})
  }
}
