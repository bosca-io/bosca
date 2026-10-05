import { BoscaSink } from '../bosca'
import { addSink, removeSink } from '../sink'
import { PageTracker } from './page_tracker'
import { ClickTracker, type ClickTrackerOptions } from './click_tracker'
import { ScrollTracker, type ScrollTrackerOptions } from './scroll_tracker'
import { MouseTracker, type MouseTrackerOptions } from './mouse_tracker'
import { VisibilityTracker, type VisibilityTrackerOptions } from './visibility_tracker'
import { FormTracker, type FormTrackerOptions } from './form_tracker'
import { ErrorTracker, type ErrorTrackerOptions } from './error_tracker'

export { PageTracker, extractUtmParams } from './page_tracker'
export { ClickTracker } from './click_tracker'
export type { ClickTrackerOptions } from './click_tracker'
export { ScrollTracker } from './scroll_tracker'
export type { ScrollTrackerOptions } from './scroll_tracker'
export { MouseTracker } from './mouse_tracker'
export type { MouseTrackerOptions } from './mouse_tracker'
export { VisibilityTracker } from './visibility_tracker'
export type { VisibilityTrackerOptions } from './visibility_tracker'
export { FormTracker } from './form_tracker'
export type { FormTrackerOptions } from './form_tracker'
export { ErrorTracker } from './error_tracker'
export type { ErrorTrackerOptions } from './error_tracker'
export { extractAttributes } from './attribute_extractor'
export type { ExtractedAttributes } from './attribute_extractor'
export { getElementIdentifier, getElementType, getElementText } from './element_identifier'
export { getViewportInfo } from './viewport'
export type { ViewportInfo } from './viewport'

/**
 * Controls which auto-instrumentation features are active. Each feature can
 * be toggled independently and optionally configured with feature-specific
 * options. All features default to enabled so that a single call to
 * `autoInstrument()` gives full coverage out of the box.
 */
export interface AutoInstrumentOptions {
  /** Track page views including SPA client-side navigations */
  pageViews: boolean
  /** Track clicks with coordinates for heat map generation */
  clicks: boolean | Partial<ClickTrackerOptions>
  /** Track scroll depth milestones */
  scrollDepth: boolean | Partial<ScrollTrackerOptions>
  /** Sample mouse movement positions for movement heat maps */
  mouseMovement: boolean | Partial<MouseTrackerOptions>
  /** Track element visibility via IntersectionObserver */
  elementVisibility: boolean | Partial<VisibilityTrackerOptions>
  /** Track form submissions and field interactions */
  forms: boolean | Partial<FormTrackerOptions>
  /** Automatically capture uncaught errors and unhandled promise rejections */
  errors: boolean | Partial<ErrorTrackerOptions>
}

const DEFAULT_OPTIONS: AutoInstrumentOptions = {
  pageViews: true,
  clicks: true,
  scrollDepth: true,
  mouseMovement: false,
  elementVisibility: true,
  forms: true,
  errors: true,
}

/**
 * Manages the lifecycle of all auto-instrumentation trackers. Create an
 * instance with desired options, call `start()` to begin capturing events,
 * and `stop()` to tear down all listeners cleanly (important for SPA
 * frameworks that mount/unmount the analytics plugin on route changes).
 */
export class AutoInstrumentation {
  private options: AutoInstrumentOptions
  private pageTracker: PageTracker | null = null
  private clickTracker: ClickTracker | null = null
  private scrollTracker: ScrollTracker | null = null
  private mouseTracker: MouseTracker | null = null
  private visibilityTracker: VisibilityTracker | null = null
  private formTracker: FormTracker | null = null
  private errorTracker: ErrorTracker | null = null
  private started = false

  constructor(options: Partial<AutoInstrumentOptions> = {}) {
    this.options = { ...DEFAULT_OPTIONS, ...options }
  }

  /**
   * Initializes and starts all enabled trackers. Safe to call in SSR
   * environments — trackers will no-op if `window` or `document` are
   * unavailable.
   */
  start() {
    if (this.started) return
    this.started = true

    if (this.options.scrollDepth) {
      const scrollOpts = typeof this.options.scrollDepth === 'object' ? this.options.scrollDepth : {}
      this.scrollTracker = new ScrollTracker(scrollOpts)
      this.scrollTracker.start()
    }

    if (this.options.pageViews) {
      this.pageTracker = new PageTracker()
      if (this.scrollTracker) {
        this.pageTracker.onPageNavigate(() => this.scrollTracker?.resetForNewPage())
      }
      this.pageTracker.start()
    }

    if (this.options.clicks) {
      const clickOpts = typeof this.options.clicks === 'object' ? this.options.clicks : {}
      this.clickTracker = new ClickTracker(clickOpts)
      this.clickTracker.start()
    }

    if (this.options.mouseMovement) {
      const mouseOpts = typeof this.options.mouseMovement === 'object' ? this.options.mouseMovement : {}
      this.mouseTracker = new MouseTracker(mouseOpts)
      this.mouseTracker.start()
    }

    if (this.options.elementVisibility) {
      const visOpts = typeof this.options.elementVisibility === 'object' ? this.options.elementVisibility : {}
      this.visibilityTracker = new VisibilityTracker(visOpts)
      this.visibilityTracker.start()
    }

    if (this.options.forms) {
      const formOpts = typeof this.options.forms === 'object' ? this.options.forms : {}
      this.formTracker = new FormTracker(formOpts)
      this.formTracker.start()
    }

    if (this.options.errors) {
      const errorOpts = typeof this.options.errors === 'object' ? this.options.errors : {}
      this.errorTracker = new ErrorTracker(errorOpts)
      this.errorTracker.start()
    }
  }

  /**
   * Stops all active trackers and removes their DOM event listeners.
   * After calling stop, the instance can be started again with `start()`.
   */
  stop() {
    if (!this.started) return
    this.started = false

    this.pageTracker?.stop()
    this.clickTracker?.stop()
    this.scrollTracker?.stop()
    this.mouseTracker?.stop()
    this.visibilityTracker?.stop()
    this.formTracker?.stop()
    this.errorTracker?.stop()

    this.pageTracker = null
    this.clickTracker = null
    this.scrollTracker = null
    this.mouseTracker = null
    this.visibilityTracker = null
    this.formTracker = null
    this.errorTracker = null
  }

  /** Returns the scroll tracker so callers can reset depth on navigation. */
  getScrollTracker(): ScrollTracker | null {
    return this.scrollTracker
  }
}

/**
 * Convenience factory that creates a BoscaSink, registers it, enables
 * auto-instrumentation, and starts capturing events. This is the simplest
 * way to get full analytics coverage with a single function call.
 *
 * Once started, the SDK automatically captures clicks, impressions, form
 * submissions, scroll depth, page views (including SPA navigations with
 * UTM parameters), and errors — no manual event calls needed.
 *
 * Enrich events with `data-ba-*` HTML attributes (see
 * {@link extractAttributes} for the full convention). For example:
 *
 * ```html
 * <div data-ba-content-id="prod-42" data-ba-content-type="product"
 *      data-ba-content-index="0" data-ba-no-text>
 *   <h3>Widget Pro — $29.99</h3>
 *   <button data-ba-action="add-to-cart">Add to Cart</button>
 * </div>
 * ```
 *
 * - The `<div>` automatically gets impression tracking (has `data-ba-content-id`)
 * - Clicking the button produces `extras: { action: "add_to_cart" }` and
 *   `content: [{ id: "prod-42", type: "product", index: 0 }]` — inherited
 *   from the parent
 * - `data-ba-no-text` prevents the noisy card text from appearing in the event
 *
 * For Nuxt, use {@link setupNuxtAnalytics} from the `nuxt.ts` module instead.
 * For htmx, no special handling is needed — MutationObserver detects swapped
 * content automatically.
 *
 * @example
 * ```ts
 * import { autoInstrument } from '@bosca/analytics-client-browser'
 *
 * const { sink, stop } = autoInstrument({
 *   url: 'https://analytics.example.com',
 *   appId: 'my-app',
 *   appVersion: '1.0.0',
 *   clientId: 'web',
 * })
 * ```
 */
export function autoInstrument(config: {
  url: string
  appId: string
  appVersion: string
  clientId: string
  userId?: string
  debug?: boolean
  anonymous?: boolean
  omitCredentials?: boolean
  options?: Partial<AutoInstrumentOptions>
}): { sink: BoscaSink; instrumentation: AutoInstrumentation; stop: () => void } {
  const sink = new BoscaSink({
    url: config.url,
    appId: config.appId,
    appVersion: config.appVersion,
    clientId: config.clientId,
    debug: config.debug,
    anonymous: config.anonymous,
    omitCredentials: config.omitCredentials,
  })
  if (config.userId) {
    sink.setUserId(config.userId)
  }
  addSink(sink)

  const instrumentation = new AutoInstrumentation(config.options)
  instrumentation.start()

  return {
    sink,
    instrumentation,
    stop: () => {
      instrumentation.stop()
      removeSink(sink)
    },
  }
}
