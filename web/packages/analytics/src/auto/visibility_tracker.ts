import { AnalyticEventType } from '../event'
import { logEvent } from '../sink'
import { extractAttributes, type ExtractedAttributes } from './attribute_extractor'
import { getElementIdentifier, getElementType, getElementText } from './element_identifier'

/**
 * Configuration for element visibility tracking, controlling what fraction
 * of an element must be visible and how long it must remain visible before
 * an impression event is recorded.
 */
export interface VisibilityTrackerOptions {
  /**
   * CSS selector identifying which elements to observe for visibility.
   * Defaults to elements with a `data-ba-track` attribute or
   * a `data-ba-content-id` attribute.
   */
  selector: string
  /**
   * Fraction of the element's area that must be within the viewport
   * (0.0–1.0) for it to count as "visible".
   */
  visibilityThreshold: number
  /**
   * Milliseconds the element must remain continuously visible before an
   * impression event fires, preventing rapid scroll-through from counting.
   */
  dwellTime: number
}

const DEFAULT_OPTIONS: VisibilityTrackerOptions = {
  selector: '[data-ba-track], [data-ba-content-id]',
  visibilityThreshold: 0.5,
  dwellTime: 1000,
}

/**
 * Uses IntersectionObserver to automatically fire impression events when
 * tracked elements become visible in the viewport. Answers the question
 * "which content did users actually see?" without requiring manual
 * `logImpression` calls. Elements opt in via the `data-ba-track`
 * attribute, `data-ba-content-id`, or a custom CSS selector. A configurable
 * dwell time prevents impressions from firing when users scroll past content
 * too quickly.
 */
export class VisibilityTracker {
  private options: VisibilityTrackerOptions
  private observer: IntersectionObserver | null = null
  private dwellTimers: Map<Element, ReturnType<typeof setTimeout>> = new Map()
  private trackedElements: Map<Element, ExtractedAttributes> = new Map()
  private mutationObserver: MutationObserver | null = null
  private pendingMutation: ReturnType<typeof setTimeout> | null = null
  private visibleElements = new Set<Element>()
  private visibilityHandler: (() => void) | null = null

  constructor(options: Partial<VisibilityTrackerOptions> = {}) {
    this.options = { ...DEFAULT_OPTIONS, ...options }
  }

  /**
   * Creates the IntersectionObserver, begins observing existing elements
   * matching the selector, and watches for new elements added to the DOM.
   */
  start() {
    if (typeof window === 'undefined' || typeof IntersectionObserver === 'undefined') return

    this.observer = new IntersectionObserver(
      (entries) => this.onIntersection(entries),
      { threshold: this.options.visibilityThreshold },
    )

    this.observeMatchingElements(document.body)
    this.visibilityHandler = () => {
      for (const timer of this.dwellTimers.values()) clearTimeout(timer)
      this.dwellTimers.clear()
      if (!document.hidden) {
        for (const element of this.visibleElements) this.startDwell(element)
      }
    }
    document.addEventListener('visibilitychange', this.visibilityHandler)

    this.mutationObserver = new MutationObserver((mutations) => {
      for (const mutation of mutations) {
        if (mutation.type !== 'attributes') continue
        for (const element of this.visibleElements) {
          if (mutation.target === element || mutation.target.contains(element)) {
            const timer = this.dwellTimers.get(element)
            if (timer) clearTimeout(timer)
            this.dwellTimers.delete(element)
            this.startDwell(element)
          }
        }
      }
      if (this.pendingMutation) return
      const added: Element[] = []
      for (const mutation of mutations) {
        for (let i = 0; i < mutation.addedNodes.length; i++) {
          const node = mutation.addedNodes[i]!
          if (node.nodeType === Node.ELEMENT_NODE) {
            added.push(node as Element)
          }
        }
      }
      if (added.length === 0) return
      this.pendingMutation = setTimeout(() => {
        this.pendingMutation = null
        this.evictDisconnected()
        for (const root of added) {
          if (root.isConnected) this.observeMatchingElements(root)
        }
      }, 100)
    })
    this.mutationObserver.observe(document.body, {
      childList: true, subtree: true, attributes: true,
      attributeFilter: ['data-ba-content-id', 'data-ba-content-type', 'data-ba-content-index'],
    })
  }

  /** Disconnects observers and clears all pending dwell timers. */
  stop() {
    if (this.observer) {
      this.observer.disconnect()
      this.observer = null
    }
    if (this.mutationObserver) {
      this.mutationObserver.disconnect()
      this.mutationObserver = null
    }
    for (const timer of this.dwellTimers.values()) {
      clearTimeout(timer)
    }
    if (this.pendingMutation) {
      clearTimeout(this.pendingMutation)
      this.pendingMutation = null
    }
    this.dwellTimers.clear()
    this.trackedElements.clear()
    this.visibleElements.clear()
    if (this.visibilityHandler) {
      document.removeEventListener('visibilitychange', this.visibilityHandler)
      this.visibilityHandler = null
    }
  }

  private evictDisconnected() {
    for (const [element, _] of this.trackedElements) {
      if (!element.isConnected) {
        this.trackedElements.delete(element)
        this.visibleElements.delete(element)
        const timer = this.dwellTimers.get(element)
        if (timer) {
          clearTimeout(timer)
          this.dwellTimers.delete(element)
        }
      }
    }
  }

  private observeMatchingElements(root: Element) {
    if (!this.observer) return

    const selector = this.options.selector
    if (root.matches(selector)) this.trackElement(root)

    const elements = root.querySelectorAll(selector)
    for (let i = 0; i < elements.length; i++) {
      this.trackElement(elements[i]!)
    }
  }

  private trackElement(element: Element) {
    if (this.trackedElements.has(element)) return
    this.trackedElements.set(element, extractAttributes(element))
    this.observer!.observe(element)
  }

  private onIntersection(entries: IntersectionObserverEntry[]) {
    for (const entry of entries) {
      if (entry.isIntersecting && entry.intersectionRatio >= this.options.visibilityThreshold) {
        this.visibleElements.add(entry.target)
        this.startDwell(entry.target)
      } else {
        this.visibleElements.delete(entry.target)
        const timer = this.dwellTimers.get(entry.target)
        if (timer) {
          clearTimeout(timer)
          this.dwellTimers.delete(entry.target)
        }
      }
    }
  }

  private startDwell(element: Element) {
    if (this.dwellTimers.has(element) || document.hidden || !element.isConnected) return
    const attributes = extractAttributes(element)
    const timer = setTimeout(() => {
      this.dwellTimers.delete(element)
      if (document.hidden || !element.isConnected || !this.visibleElements.has(element)) return
      const current = extractAttributes(element)
      // A reused card must display its current content for a full dwell interval.
      const identity = (value: ExtractedAttributes) => JSON.stringify(value.content.map(item => [item.id, item.type, item.index]))
      if (identity(current) !== identity(attributes)) {
        this.startDwell(element)
        return
      }
      this.emitImpression(element, current)
    }, this.options.dwellTime)
    this.dwellTimers.set(element, timer)
  }

  private emitImpression(element: Element, attributes: ExtractedAttributes) {
    const elementId = getElementIdentifier(element)
    const extras: { [key: string]: string } = {
      path: typeof window !== 'undefined' ? window.location.pathname : '',
    }

    const trackId = element.getAttribute('data-ba-track')
    if (trackId) extras.track_id = trackId

    const text = getElementText(element)
    if (text) extras.element_text = text

    for (const [key, value] of Object.entries(attributes.extras)) extras[key] = value
    // Only the tracker supplies qualification fields; inherited attributes cannot override them.
    extras.visible_ms = String(this.options.dwellTime)
    extras.visibility_threshold = String(this.options.visibilityThreshold)
    const content = attributes.content

    logEvent({
      type: AnalyticEventType.impression,
      element: {
        id: elementId,
        type: getElementType(element),
        extras,
        content: content.length > 0 ? content : undefined,
      },
    }).catch((e) => console.error('failed to track visibility:', e))
  }
}
