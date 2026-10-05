import { AnalyticEventType } from '../event'
import { logEvent } from '../sink'
import { getViewportInfo } from './viewport'

/**
 * Configuration for scroll depth tracking, controlling how often scroll
 * position is sampled and at which depth milestones events are emitted.
 */
export interface ScrollTrackerOptions {
  /** Percentage thresholds (0–100) at which a scroll depth event fires */
  depthMarks: number[]
  /** Minimum milliseconds between scroll position samples */
  throttleMs: number
}

const DEFAULT_OPTIONS: ScrollTrackerOptions = {
  depthMarks: [25, 50, 75, 90, 100],
  throttleMs: 250,
}

/**
 * Monitors how far users scroll down a page and emits events when they cross
 * configurable depth thresholds (e.g. 25%, 50%, 75%, 100%). This answers the
 * question "how much of the page are users actually seeing?" and helps
 * identify where users lose interest and stop scrolling. Depth marks are
 * tracked per-page and reset on navigation. They remain interaction events,
 * but analytics should use their depth as view-quality telemetry rather than
 * count every milestone as another engagement.
 */
export class ScrollTracker {
  private options: ScrollTrackerOptions
  private scrollHandler: (() => void) | null = null
  private reachedDepths: Set<number> = new Set()
  private maxScrollDepth: number = 0
  private throttleTimer: ReturnType<typeof setTimeout> | null = null
  private currentPath: string = ''

  constructor(options: Partial<ScrollTrackerOptions> = {}) {
    this.options = { ...DEFAULT_OPTIONS, ...options }
  }

  /** Attaches scroll listener and resets depth tracking for the current page. */
  start() {
    if (typeof window === 'undefined') return

    this.currentPath = window.location.pathname
    this.scrollHandler = () => this.onScroll()
    window.addEventListener('scroll', this.scrollHandler, { passive: true })
  }

  /** Removes scroll listener and emits a final max-depth event for the page. */
  stop() {
    if (typeof window === 'undefined') return

    if (this.scrollHandler) {
      window.removeEventListener('scroll', this.scrollHandler)
      this.scrollHandler = null
    }
    if (this.throttleTimer) {
      clearTimeout(this.throttleTimer)
      this.throttleTimer = null
    }

    this.emitMaxDepth()
  }

  /**
   * Resets depth tracking when the user navigates to a new page within an
   * SPA. Should be called by the auto-instrumentation orchestrator whenever
   * a page view event fires.
   */
  resetForNewPage() {
    this.emitMaxDepth()
    this.reachedDepths.clear()
    this.maxScrollDepth = 0
    this.currentPath = typeof window !== 'undefined' ? window.location.pathname : ''
  }

  private onScroll() {
    if (this.throttleTimer) return

    this.throttleTimer = setTimeout(() => {
      this.throttleTimer = null
      this.measureScroll()
    }, this.options.throttleMs)
  }

  private measureScroll() {
    const viewport = getViewportInfo()
    const scrollableHeight = viewport.documentHeight - viewport.viewportHeight
    if (scrollableHeight <= 0) return

    const scrollPercent = Math.min(
      100,
      Math.round(((viewport.scrollY + viewport.viewportHeight) / viewport.documentHeight) * 100),
    )

    if (scrollPercent > this.maxScrollDepth) {
      this.maxScrollDepth = scrollPercent
    }

    for (const mark of this.options.depthMarks) {
      if (scrollPercent >= mark && !this.reachedDepths.has(mark)) {
        this.reachedDepths.add(mark)
        this.emitDepthMark(mark, viewport.documentHeight)
      }
    }
  }

  private emitDepthMark(depth: number, documentHeight: number) {
    logEvent({
      type: AnalyticEventType.interaction,
      element: {
        id: this.currentPath || window.location.pathname,
        type: 'scroll_depth',
        extras: {
          depth_percent: String(depth),
          document_height: String(documentHeight),
          path: this.currentPath || window.location.pathname,
        },
      },
    }).catch((e) => console.error('failed to track scroll depth:', e))
  }

  private emitMaxDepth() {
    if (this.maxScrollDepth > 0 && this.currentPath) {
      logEvent({
        type: AnalyticEventType.interaction,
        element: {
          id: this.currentPath,
          type: 'scroll_max_depth',
          extras: {
            max_depth_percent: String(this.maxScrollDepth),
            path: this.currentPath,
          },
        },
      }).catch((e) => console.error('failed to track max scroll depth:', e))
    }
  }
}
