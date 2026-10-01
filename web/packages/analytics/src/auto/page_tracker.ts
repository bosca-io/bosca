import { AnalyticEventType } from '../event'
import { logEvent } from '../sink'
import { getViewportInfo } from './viewport'

/** The standard UTM query parameters used for campaign attribution. */
const UTM_PARAMS = ['utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content'] as const

/**
 * Extracts UTM campaign parameters and the raw query-string referrer
 * (if present) from the given URL search string.
 */
export function extractUtmParams(search: string): { [key: string]: string } {
  const params = new URLSearchParams(search)
  const result: { [key: string]: string } = {}
  for (const key of UTM_PARAMS) {
    const value = params.get(key)
    if (value) {
      result[key] = value
    }
  }
  const ref = params.get('ref')
  if (ref) {
    result.ref = ref
  }
  return result
}

/**
 * Tracks page views including single-page application navigations. Hooks
 * into both the History API (pushState/replaceState) and the popstate event
 * so that client-side route changes in frameworks like Nuxt, Next, and
 * Vue Router are captured without requiring manual instrumentation.
 *
 * On the initial page view, any UTM campaign parameters present in the URL
 * are captured and included in the event extras for attribution tracking.
 */
export class PageTracker {
  private currentUrl: string = ''
  private currentTitle: string = ''
  private originalPushState: typeof history.pushState | null = null
  private originalReplaceState: typeof history.replaceState | null = null
  private pushStateWrapper: typeof history.pushState | null = null
  private replaceStateWrapper: typeof history.replaceState | null = null
  private popstateHandler: (() => void) | null = null
  private initialUtmParams: { [key: string]: string } = {}
  private onNavigateCallback: (() => void) | null = null

  /** Registers a callback invoked after each SPA navigation is tracked. */
  onPageNavigate(callback: () => void) {
    this.onNavigateCallback = callback
  }

  /**
   * Begins listening for navigation events and immediately records the
   * current page as the initial page view. UTM parameters from the landing
   * URL are captured and attached to the initial page view event.
   */
  start() {
    if (typeof window === 'undefined') return

    this.currentUrl = window.location.href
    this.currentTitle = document.title
    this.initialUtmParams = extractUtmParams(window.location.search)

    this.trackPageView()

    this.originalPushState = history.pushState.bind(history)
    this.originalReplaceState = history.replaceState.bind(history)

    const self = this
    this.pushStateWrapper = function (...args: Parameters<typeof history.pushState>) {
      self.originalPushState!(...args)
      self.onNavigate()
    }
    this.replaceStateWrapper = function (...args: Parameters<typeof history.replaceState>) {
      self.originalReplaceState!(...args)
      self.onNavigate()
    }

    history.pushState = this.pushStateWrapper
    history.replaceState = this.replaceStateWrapper

    this.popstateHandler = () => this.onNavigate()
    window.addEventListener('popstate', this.popstateHandler)
  }

  /** Removes all event listeners and restores the original History methods if still ours. */
  stop() {
    if (typeof window === 'undefined') return

    // Only restore if the current method is still our wrapper; another library
    // may have patched after us and restoring would break their integration.
    if (this.originalPushState && history.pushState === this.pushStateWrapper) {
      history.pushState = this.originalPushState
    }
    this.originalPushState = null
    this.pushStateWrapper = null

    if (this.originalReplaceState && history.replaceState === this.replaceStateWrapper) {
      history.replaceState = this.originalReplaceState
    }
    this.originalReplaceState = null
    this.replaceStateWrapper = null

    if (this.popstateHandler) {
      window.removeEventListener('popstate', this.popstateHandler)
      this.popstateHandler = null
    }
  }

  private onNavigate() {
    const newUrl = window.location.href
    if (newUrl !== this.currentUrl) {
      const previousUrl = this.currentUrl
      this.currentUrl = newUrl
      // Defer title read to allow frameworks to update document.title after pushState
      Promise.resolve().then(() => {
        this.currentTitle = document.title
        this.trackPageView(previousUrl)
        this.onNavigateCallback?.()
      })
    }
  }

  private trackPageView(referrer?: string) {
    const viewport = getViewportInfo()
    const currentUtm = extractUtmParams(window.location.search)
    // TODO: Remove legacy url/path/title extras once all downstream consumers
    // have migrated to the top-level `page` struct populated by the event
    // factory. Kept for one release cycle for backward compatibility.
    const extras: { [key: string]: string } = {
      url: this.currentUrl,
      path: window.location.pathname,
      title: this.currentTitle,
      viewport_width: String(viewport.viewportWidth),
      viewport_height: String(viewport.viewportHeight),
      document_width: String(viewport.documentWidth),
      document_height: String(viewport.documentHeight),
      ...this.initialUtmParams,
      ...currentUtm,
    }
    if (referrer) {
      extras.referrer = referrer
    }
    if (document.referrer && !referrer) {
      extras.referrer = document.referrer
    }

    logEvent({
      type: AnalyticEventType.impression,
      element: {
        id: window.location.pathname,
        type: 'page',
        extras,
      },
    }).catch((e) => console.error('failed to track page view:', e))
  }
}
