import { sharedState } from "./shared-state"

export const BML_DEFERRED_REFRESH_EVENT = "bml:deferred-refresh"
export const BML_IDENTITY_CHANGE_EVENT = "bml:identity-change"

export interface DeferredRuntime {
  loadAll(scope: ParentNode): Promise<void>
  abort(scope: ParentNode): void
  advanceIdentity(): void
  isIdentityStale(root: HTMLElement): boolean
  propsForClient(raw: string | undefined): Record<string, unknown>
}

interface DeferredRuntimeState {
  runtime?: DeferredRuntime
  identityProvider?: () => Promise<void> | null
  /** This document already asked the browser to reload for a missing server session. */
  sessionReloadRequested: boolean
}

const state = sharedState<DeferredRuntimeState>("@bosca/bml/deferred-runtime", () => ({
  sessionReloadRequested: false,
}))

const SESSION_RELOAD_KEY = "bml:deferred-session-reload"

/**
 * Reloads once so the page render can establish a fresh server session after a `410 Gone`. A
 * second 410 for the same URL in this tab means the browser is not keeping the session cookie;
 * reloading again would loop, so this returns false and the caller reports an error instead.
 * Without session storage the loop cannot be bounded, so this also returns false.
 *
 * Deferred boundaries and actions on one page can all receive 410 from the same page load. Once
 * this document has requested the reload, later callers wait for that navigation (return true)
 * instead of misreading the marker the first caller just wrote as a repeated failure.
 *
 * Browsers report no signal when a `beforeunload` prompt cancels the reload. Callers waiting on
 * it then stay pending until the page is reloaded or restored from the back/forward cache, which
 * calls [resetSessionReloadRequest].
 */
export function reloadOnceForSession(): boolean {
  if (state.sessionReloadRequested) return true
  const marker = window.location.pathname + window.location.search
  try {
    if (window.sessionStorage.getItem(SESSION_RELOAD_KEY) === marker) return false
    window.sessionStorage.setItem(SESSION_RELOAD_KEY, marker)
  } catch {
    return false
  }
  state.sessionReloadRequested = true
  window.location.reload()
  return true
}

/** A successful stateful request proves the session works, so a later expiry may reload again. */
export function clearSessionReloadMarker(): void {
  try {
    window.sessionStorage.removeItem(SESSION_RELOAD_KEY)
  } catch {
    // Unavailable storage never recorded a reload marker.
  }
}

/** A document restored from the back/forward cache is live again; its earlier reload did not replace it. */
export function resetSessionReloadRequest(): void {
  state.sessionReloadRequested = false
}

export function installDeferredRuntime(runtime: DeferredRuntime): void {
  state.runtime = runtime
}

export function currentDeferredRuntime(): DeferredRuntime | undefined {
  return state.runtime
}

export function installDeferredIdentityProvider(provider: () => Promise<void> | null): void {
  state.identityProvider = provider
}

/** Returns an identity bootstrap already required by the current page, if one exists. */
export function pendingDeferredIdentity(): Promise<void> | null {
  return state.identityProvider?.() ?? null
}

/** The concrete path selected by the server for the page currently rendered in this document. */
export function currentPagePath(): string {
  return document.querySelector("script[data-bml-page-canonical][data-bml-page-path]")
    ?.getAttribute("data-bml-page-path")
    ?? window.location.pathname
}

/** The locale selected by the server for the current page. */
export function currentPageLocale(): string | undefined {
  return document.querySelector("script[data-bml-page-canonical][data-bml-page-path][data-bml-page-locale]")
    ?.getAttribute("data-bml-page-locale")?.trim()
    || document.documentElement.lang.trim()
    || undefined
}

/** The current URL query using the same first-value semantics as RenderContext. */
export function currentPageQuery(): Record<string, string> {
  const result = Object.create(null) as Record<string, string>
  const parameters = new URLSearchParams(window.location.search)
  parameters.forEach((value, key) => {
    if (!Object.prototype.hasOwnProperty.call(result, key)) result[key] = value
  })
  return result
}

/** True while an element belongs to a deferred boundary whose private body is unavailable. */
export function isInsidePendingDeferred(element: Element): boolean {
  const boundary = element.closest<HTMLElement>("[data-bml-deferred]")
  return boundary != null && boundary.dataset.bmlDeferredState !== "loaded"
}
