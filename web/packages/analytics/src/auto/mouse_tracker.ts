import { AnalyticEventType } from '../event'
import { logEvent } from '../sink'
import { getViewportInfo } from './viewport'

/**
 * Configuration for mouse movement sampling, balancing heat map accuracy
 * against event volume and performance overhead.
 */
export interface MouseTrackerOptions {
  /** Minimum milliseconds between recorded mouse position samples */
  sampleInterval: number
  /**
   * Number of samples to buffer before flushing as a single batched event.
   * Batching reduces the total number of analytics events while preserving
   * the movement path for heat map rendering.
   */
  batchSize: number
}

const DEFAULT_OPTIONS: MouseTrackerOptions = {
  sampleInterval: 100,
  batchSize: 50,
}

interface MouseSample {
  x: number
  y: number
  pageX: number
  pageY: number
  timestamp: number
}

/**
 * Periodically samples mouse cursor positions to build movement heat maps.
 * Unlike click tracking which captures discrete interactions, mouse movement
 * reveals where users look and hover — areas of attention that never result
 * in a click. Samples are batched to reduce event volume: a batch of 50
 * samples at 100ms intervals covers ~5 seconds of movement in a single event.
 */
export class MouseTracker {
  private options: MouseTrackerOptions
  private moveHandler: ((e: MouseEvent) => void) | null = null
  private lastSampleTime: number = 0
  private samples: MouseSample[] = []
  private flushTimer: ReturnType<typeof setTimeout> | null = null

  constructor(options: Partial<MouseTrackerOptions> = {}) {
    this.options = { ...DEFAULT_OPTIONS, ...options }
  }

  /** Starts listening for mouse movement across the document. */
  start() {
    if (typeof document === 'undefined') return

    this.moveHandler = (e: MouseEvent) => this.onMove(e)
    document.addEventListener('mousemove', this.moveHandler, { passive: true })
  }

  /** Stops listening, flushes any remaining buffered samples. */
  stop() {
    if (typeof document === 'undefined') return

    if (this.moveHandler) {
      document.removeEventListener('mousemove', this.moveHandler)
      this.moveHandler = null
    }
    if (this.flushTimer) {
      clearTimeout(this.flushTimer)
      this.flushTimer = null
    }
    this.flushSamples()
  }

  private onMove(e: MouseEvent) {
    const now = Date.now()
    if (now - this.lastSampleTime < this.options.sampleInterval) return
    this.lastSampleTime = now

    this.samples.push({
      x: e.clientX,
      y: e.clientY,
      pageX: e.pageX,
      pageY: e.pageY,
      timestamp: now,
    })

    if (this.samples.length >= this.options.batchSize) {
      this.flushSamples()
    } else if (!this.flushTimer) {
      this.flushTimer = setTimeout(() => {
        this.flushTimer = null
        this.flushSamples()
      }, 5000)
    }
  }

  private flushSamples() {
    if (this.samples.length === 0) return

    const batch = this.samples
    this.samples = []
    const viewport = getViewportInfo()

    // Encode samples as compact comma-separated coordinate pairs.
    // Format per sample: "clientX,clientY,pageX,pageY,timestamp"
    // Samples separated by semicolons within a single extras field to
    // minimize event overhead.
    const points = batch
      .map((s) => `${s.x},${s.y},${s.pageX},${s.pageY},${s.timestamp}`)
      .join(';')

    logEvent({
      type: AnalyticEventType.interaction,
      element: {
        id: typeof window !== 'undefined' ? window.location.pathname : '',
        type: 'mouse_movement',
        extras: {
          points,
          sample_count: String(batch.length),
          viewport_width: String(viewport.viewportWidth),
          viewport_height: String(viewport.viewportHeight),
          document_width: String(viewport.documentWidth),
          document_height: String(viewport.documentHeight),
          path: typeof window !== 'undefined' ? window.location.pathname : '',
        },
      },
    }).catch((e) => console.error('failed to track mouse movement:', e))
  }
}
