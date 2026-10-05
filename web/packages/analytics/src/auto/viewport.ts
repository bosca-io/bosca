/**
 * Captures the current viewport and document dimensions. These measurements
 * are attached to positional events (clicks, mouse moves) so that coordinates
 * can be normalized to relative positions when building heat maps across
 * different screen sizes and responsive layouts.
 */
export interface ViewportInfo {
  /** Visible viewport width in CSS pixels */
  viewportWidth: number
  /** Visible viewport height in CSS pixels */
  viewportHeight: number
  /** Total scrollable document width in CSS pixels */
  documentWidth: number
  /** Total scrollable document height in CSS pixels */
  documentHeight: number
  /** Current horizontal scroll offset */
  scrollX: number
  /** Current vertical scroll offset */
  scrollY: number
}

/**
 * Reads the current viewport and document dimensions from the browser. Falls
 * back to zero values in non-browser environments (SSR).
 */
export function getViewportInfo(): ViewportInfo {
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return { viewportWidth: 0, viewportHeight: 0, documentWidth: 0, documentHeight: 0, scrollX: 0, scrollY: 0 }
  }
  return {
    viewportWidth: window.innerWidth,
    viewportHeight: window.innerHeight,
    documentWidth: document.documentElement.scrollWidth,
    documentHeight: document.documentElement.scrollHeight,
    scrollX: window.scrollX,
    scrollY: window.scrollY,
  }
}
