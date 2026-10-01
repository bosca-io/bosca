import { AnalyticEventType, type IAnalyticEvent } from '../event'
import { getCurrentPage } from '../factory'
import { logEvent, logEventBeacon } from '../sink'
import { extractAttributes } from './attribute_extractor'
import { getElementIdentifier, getElementType, getElementText } from './element_identifier'
import { getViewportInfo } from './viewport'

/**
 * Configuration for the click tracker controlling which interactions are
 * captured and how rage clicks are detected.
 */
export interface ClickTrackerOptions {
  /** Whether to also capture touch-start events on mobile devices */
  trackTouch: boolean
  /** Whether to detect rapid repeated clicks on the same target (rage clicks) */
  detectRageClicks: boolean
  /**
   * Number of clicks within the rage click time window that qualifies
   * as a rage click
   */
  rageClickThreshold: number
  /** Duration in milliseconds for the rage click detection window */
  rageClickWindow: number
}

const DEFAULT_OPTIONS: ClickTrackerOptions = {
  trackTouch: true,
  detectRageClicks: true,
  rageClickThreshold: 3,
  rageClickWindow: 1000,
}

/**
 * Records every click (and optionally touch) event with the element's
 * position, the viewport-relative and document-relative coordinates, and a
 * stable element identifier. This data feeds heat map visualizations that
 * show where users click on each page. Also detects "rage clicks" — rapid
 * repeated clicks on the same element — which often signal user frustration.
 */
export class ClickTracker {
  private options: ClickTrackerOptions
  private clickHandler: ((e: MouseEvent) => void) | null = null
  private touchHandler: ((e: TouchEvent) => void) | null = null
  private navigationClickHandler: ((e: MouseEvent) => void) | null = null
  private navigationClicks = new WeakMap<MouseEvent, IAnalyticEvent>()
  private recentClicks: { target: string; time: number }[] = []

  constructor(options: Partial<ClickTrackerOptions> = {}) {
    this.options = { ...DEFAULT_OPTIONS, ...options }
  }

  /** Attaches click and touch listeners to the document. */
  start() {
    if (typeof document === 'undefined') return

    this.clickHandler = (e: MouseEvent) => this.onClick(e)
    document.addEventListener('click', this.clickHandler, { capture: true, passive: true })

    if (this.options.trackTouch) {
      this.touchHandler = (e: TouchEvent) => this.onTouch(e)
      document.addEventListener('touchstart', this.touchHandler, { capture: true, passive: true })
    }

    this.navigationClickHandler = (e: MouseEvent) => this.onNavigationClick(e)
    window.addEventListener('click', this.navigationClickHandler)
  }

  /** Removes all listeners. */
  stop() {
    if (typeof document === 'undefined') return

    if (this.clickHandler) {
      document.removeEventListener('click', this.clickHandler, { capture: true } as EventListenerOptions)
      this.clickHandler = null
    }
    if (this.touchHandler) {
      document.removeEventListener('touchstart', this.touchHandler, { capture: true } as EventListenerOptions)
      this.touchHandler = null
    }
    if (this.navigationClickHandler) {
      window.removeEventListener('click', this.navigationClickHandler)
      this.navigationClickHandler = null
    }
  }

  private onClick(e: MouseEvent) {
    const target = e.target as Element
    if (!target) return

    const elementId = getElementIdentifier(target)
    const viewport = getViewportInfo()
    const { extras: extracted, content } = extractAttributes(target)

    const extras: { [key: string]: string } = {
      page_x: String(e.pageX),
      page_y: String(e.pageY),
      client_x: String(e.clientX),
      client_y: String(e.clientY),
      viewport_width: String(viewport.viewportWidth),
      viewport_height: String(viewport.viewportHeight),
      document_width: String(viewport.documentWidth),
      document_height: String(viewport.documentHeight),
      element_type: getElementType(target),
      element_text: getElementText(target),
      ...extracted,
    }

    const anchor = target.closest('a[href]')
    const href = anchor?.getAttribute('href')
    if (href) {
      extras.href = href
    }

    const interaction: IAnalyticEvent = {
      type: AnalyticEventType.interaction,
      page: getCurrentPage(),
      element: {
        id: elementId,
        type: 'click',
        extras,
        content: content.length > 0 ? content : undefined,
      },
    }
    if (anchor) {
      this.navigationClicks.set(e, interaction)
      // A stopped propagation cannot reach the window listener below. Finish after dispatch as a
      // fallback; ordinary bubbling handles navigation synchronously before this microtask runs.
      queueMicrotask(() => this.onNavigationClick(e))
    } else {
      this.logInteraction(interaction)
    }

    if (this.options.detectRageClicks) {
      this.checkRageClick(elementId)
    }
  }

  private onNavigationClick(e: MouseEvent) {
    const interaction = this.navigationClicks.get(e)
    this.navigationClicks.delete(e)
    if (!interaction) return

    if (this.isDocumentNavigation(e, interaction)) {
      logEventBeacon(interaction)
    } else {
      this.logInteraction(interaction)
    }
  }

  private isDocumentNavigation(e: MouseEvent, interaction: IAnalyticEvent): boolean {
    if (e.defaultPrevented || e.button !== 0) return false
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return false

    const target = e.target as Element | null
    const anchor = target?.closest('a[href]') as HTMLAnchorElement | null
    if (!anchor || anchor.hasAttribute('download')) return false
    const browsingContext = anchor.getAttribute('target')?.trim().toLowerCase()
    if (browsingContext && browsingContext !== '_self') return false

    let source: URL
    let destination: URL
    try {
      source = new URL(interaction.page?.url ?? window.location.href)
      destination = new URL(anchor.getAttribute('href')!, source)
    } catch {
      return false
    }
    if (destination.protocol === 'javascript:') return false
    if (
      destination.origin === source.origin
      && destination.pathname === source.pathname
      && destination.search === source.search
    ) return false

    return true
  }

  private logInteraction(interaction: IAnalyticEvent) {
    logEvent(interaction).catch((e) => console.error('failed to track click:', e))
  }

  private onTouch(e: TouchEvent) {
    const touch = e.touches[0]
    if (!touch) return

    const target = e.target as Element
    if (!target) return

    const elementId = getElementIdentifier(target)
    const viewport = getViewportInfo()
    const { extras: extracted, content } = extractAttributes(target)

    logEvent({
      type: AnalyticEventType.interaction,
      element: {
        id: elementId,
        type: 'touch',
        extras: {
          page_x: String(touch.pageX),
          page_y: String(touch.pageY),
          client_x: String(touch.clientX),
          client_y: String(touch.clientY),
          viewport_width: String(viewport.viewportWidth),
          viewport_height: String(viewport.viewportHeight),
          document_width: String(viewport.documentWidth),
          document_height: String(viewport.documentHeight),
          element_type: getElementType(target),
          element_text: getElementText(target),
          is_touch: 'true',
          ...extracted,
        },
        content: content.length > 0 ? content : undefined,
      },
    }).catch((e) => console.error('failed to track touch:', e))
  }

  private checkRageClick(elementId: string) {
    const now = Date.now()
    this.recentClicks.push({ target: elementId, time: now })

    // Discard clicks outside the detection window
    this.recentClicks = this.recentClicks.filter(
      (click) => now - click.time < this.options.rageClickWindow,
    )

    const matchingClicks = this.recentClicks.filter((click) => click.target === elementId)

    if (matchingClicks.length >= this.options.rageClickThreshold) {
      logEvent({
        type: AnalyticEventType.interaction,
        element: {
          id: elementId,
          type: 'rage_click',
          extras: {
            click_count: String(matchingClicks.length),
            window_ms: String(this.options.rageClickWindow),
          },
        },
      }).catch((e) => console.error('failed to track rage click:', e))

      // Reset so the same burst doesn't fire multiple rage click events
      this.recentClicks = []
    }
  }
}
