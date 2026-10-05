/** Deferred island loading: cache-safe shell marker -> private server render -> normal BML mount. */
import { authHeaders } from "./graphql"
import { unmountAll } from "./island"
import { stringifyJson } from "./json"
import { notifyIslandUpdate, replaceChildrenFromHtml } from "./update"
import {
  advanceDeferredIdentityBootstrap,
  enableDeferredIdentity,
  ensureDeferredIdentity,
} from "./identity"
import {
  BML_DEFERRED_REFRESH_EVENT,
  BML_IDENTITY_CHANGE_EVENT,
  currentPageLocale,
  currentPagePath,
  currentPageQuery,
  clearSessionReloadMarker,
  installDeferredRuntime,
  reloadOnceForSession,
  resetSessionReloadRequest,
} from "./deferred-runtime"
import { sharedState } from "./shared-state"

export const DEFERRED_PREFIX = "/_bml/deferred/"
export { DEFERRED_IDENTITY_URL } from "./identity"
export { BML_DEFERRED_REFRESH_EVENT, BML_IDENTITY_CHANGE_EVENT, isInsidePendingDeferred } from "./deferred-runtime"

interface DeferredState {
  /** Detached clones of each boundary's authored fallback nodes, restored without re-parsing HTML. */
  readonly fallbackNodes: WeakMap<HTMLElement, Node[]>
  readonly inFlight: WeakMap<HTMLElement, Promise<void>>
  readonly controllers: WeakMap<HTMLElement, AbortController>
  readonly generations: WeakMap<HTMLElement, number>
  readonly identityRevisions: WeakMap<HTMLElement, number>
  retryBound: boolean
  pageShowBound: boolean
  identityRevision: number
}

const state = sharedState<DeferredState>("@bosca/bml/deferred-state", () => ({
  fallbackNodes: new WeakMap<HTMLElement, Node[]>(),
  inFlight: new WeakMap<HTMLElement, Promise<void>>(),
  controllers: new WeakMap<HTMLElement, AbortController>(),
  generations: new WeakMap<HTMLElement, number>(),
  identityRevisions: new WeakMap<HTMLElement, number>(),
  retryBound: false,
  pageShowBound: false,
  identityRevision: 0,
}))

const { fallbackNodes, inFlight, controllers, generations, identityRevisions } = state

/** Enables deferred loading and shared-shell identity setup. Safe to repeat across bundles. */
export function enableDeferred(): void {
  enableDeferredIdentity()
  installDeferredRuntime({
    loadAll: loadDeferredAll,
    abort: abortDeferred,
    advanceIdentity: advanceDeferredIdentity,
    isIdentityStale: isDeferredIdentityStale,
    propsForClient: deferredPropsForClient,
  })
}

/** Loads every pending deferred boundary, including boundaries returned by another boundary. */
export async function loadDeferredAll(scope: ParentNode = document): Promise<void> {
  if (typeof document === "undefined") return Promise.resolve()
  enableDeferred()
  bindRetry()
  bindPageShow()

  // The identity revision this loop last established. An identity change during the loop (for example
  // clearBmlClientState({ reload: false })) must bootstrap the new identity before any further request.
  let identityReadyRevision: number | null = null
  while (true) {
    const roots = elementsIncluding(scope, "[data-bml-deferred]").filter((candidate) =>
      candidate.isConnected &&
      candidate.dataset.bmlDeferredState !== "loaded" &&
      candidate.dataset.bmlDeferredState !== "error" &&
      (candidate.dataset.bmlDeferredState !== "loading" || inFlight.has(candidate)),
    )
    if (roots.length === 0) return
    if (identityReadyRevision !== state.identityRevision) {
      const identityRevision: number = state.identityRevision
      try {
        await ensureDeferredIdentity()
      } catch (error) {
        if (identityRevision !== state.identityRevision) continue
        for (const root of roots) {
          if (!root.isConnected || root.dataset.bmlDeferredState !== "pending") continue
          // Keep this failure terminal until an explicit retry or identity change.
          identityRevisions.set(root, identityRevision)
          root.dataset.bmlDeferredState = "error"
          root.dataset.bmlDeferredError = error instanceof Error ? error.message : String(error)
          root.removeAttribute("aria-busy")
          root.dispatchEvent(new CustomEvent("bml:deferred-error", { bubbles: true, detail: error }))
        }
        return
      }
      if (identityRevision !== state.identityRevision) continue
      identityReadyRevision = identityRevision
    }
    await Promise.all(roots.map((root) => {
      const load = loadDeferredRoot(root)
      const controller = controllers.get(root)
      if (!controller) return load
      // A refresh can replace a request even if a custom fetch implementation is slow to reject
      // after abort. The generation check still discards any response that arrives later.
      return Promise.race([
        load,
        new Promise<void>((resolve) => controller.signal.addEventListener("abort", () => resolve(), { once: true })),
      ])
    }))
  }
}

/** Requests a fresh private render for one boundary, or every boundary when omitted. */
export function refreshDeferred(root?: HTMLElement): void {
  if (typeof document === "undefined") return
  document.dispatchEvent(new CustomEvent(BML_DEFERRED_REFRESH_EVENT, { detail: { root } }))
}

/** Advances the caller identity used to validate loaded and in-flight private fragments. */
export function advanceDeferredIdentity(): void {
  state.identityRevision++
  advanceDeferredIdentityBootstrap()
}

/** True when a boundary contains private content rendered for an earlier caller identity. */
export function isDeferredIdentityStale(root: HTMLElement): boolean {
  return root.dataset.bmlDeferredState !== "pending" &&
    identityRevisions.get(root) !== state.identityRevision
}

/** Aborts deferred requests and removes private content from boundaries being detached. */
export function abortDeferred(scope: ParentNode): void {
  for (const root of elementsIncluding(scope, "[data-bml-deferred]")) {
    restoreDeferredFallback(root)
  }
}

/** Tears down client scopes, restores authored fallbacks, and invalidates in-flight responses. */
export function resetDeferred(root?: HTMLElement): HTMLElement[] {
  const roots = root ? [root] : elementsIncluding(document, "[data-bml-deferred]")
  const outermost = root ? roots : roots.filter((candidate) => !candidate.parentElement?.closest("[data-bml-deferred]"))
  outermost.forEach(unmountAll)
  return roots
}

/** Decodes the type-preserving deferred-prop wire format for a mounted client scope. */
export function deferredPropsForClient(raw: string | undefined): Record<string, unknown> {
  const props = parseProps(raw)
  return decodeDeferredValue(props) as Record<string, unknown>
}

async function loadDeferredRoot(root: HTMLElement): Promise<void> {
  const existing = inFlight.get(root)
  if (existing) return existing
  if (!fallbackNodes.has(root)) fallbackNodes.set(root, Array.from(root.childNodes, (node) => node.cloneNode(true)))
  const startedAt = generation(root)
  const startedIdentity = state.identityRevision
  const startedContext = {
    browserPath: window.location.pathname,
    browserQuery: window.location.search,
    page: currentPageRoute(),
    path: currentPagePath(),
    query: currentPageQuery(),
    locale: currentPageLocale(),
  }
  const contextIsCurrent = (): boolean =>
    startedContext.browserPath === window.location.pathname &&
    startedContext.browserQuery === window.location.search &&
    startedContext.page === currentPageRoute() &&
    startedContext.path === currentPagePath() &&
    startedContext.locale === currentPageLocale()
  const controller = new AbortController()
  controllers.set(root, controller)
  identityRevisions.set(root, startedIdentity)
  root.dataset.bmlDeferredState = "loading"
  root.setAttribute("aria-busy", "true")
  delete root.dataset.bmlDeferredError

  // True when this response is obsolete. If only the identity moved on (advanceDeferredIdentity()
  // called directly, without the refresh event that restores boundaries), put the fallback back so
  // the boundary is reloaded for the new identity instead of staying "loading".
  const superseded = (): boolean => {
    if (startedAt !== generation(root) || !root.isConnected) return true
    if (startedIdentity === state.identityRevision) return false
    restoreDeferredFallback(root)
    return true
  }

  const promise = (async () => {
    const renderer = root.dataset.bmlDeferred
    try {
      if (!renderer) throw new Error("BML deferred boundary has no renderer")
      const response = await fetch(`${DEFERRED_PREFIX}${encodeURIComponent(renderer)}`, {
        method: "POST",
        headers: authHeaders({ Accept: "text/html" }),
        credentials: "same-origin",
        cache: "no-store",
        signal: controller.signal,
        body: stringifyJson({
          props: parseProps(root.dataset.bmlProps),
          page: startedContext.page,
          path: startedContext.path,
          query: startedContext.query,
          locale: startedContext.locale,
        }),
      })
      if (superseded()) return
      if (!contextIsCurrent()) {
        root.dataset.bmlDeferredState = "pending"
        return
      }
      if (response.status === 410) {
        if (reloadOnceForSession()) return
        throw new Error("BML deferred render HTTP 410: the browser did not keep the page session")
      }
      const redirect = response.headers.get("X-BML-Redirect")
      if (redirect) {
        window.location.assign(redirect)
        return
      }
      if (!response.ok) throw new Error(`BML deferred render HTTP ${response.status}`)
      const html = await response.text()
      if (superseded()) return
      if (!contextIsCurrent()) {
        root.dataset.bmlDeferredState = "pending"
        return
      }
      clearSessionReloadMarker()
      const update = replaceChildrenFromHtml(root, html, false)
      root.dataset.bmlDeferredState = "loaded"
      root.removeAttribute("aria-busy")
      notifyIslandUpdate(update)
    } catch (error) {
      if (controller.signal.aborted || superseded()) return
      if (!contextIsCurrent()) {
        root.dataset.bmlDeferredState = "pending"
        return
      }
      root.dataset.bmlDeferredState = "error"
      root.dataset.bmlDeferredError = error instanceof Error ? error.message : String(error)
      root.removeAttribute("aria-busy")
      root.dispatchEvent(new CustomEvent("bml:deferred-error", { bubbles: true, detail: error }))
    } finally {
      if (controllers.get(root) === controller) {
        controllers.delete(root)
        inFlight.delete(root)
      }
    }
  })()
  inFlight.set(root, promise)
  return promise
}

function generation(root: HTMLElement): number {
  return generations.get(root) ?? 0
}

function invalidate(root: HTMLElement): void {
  generations.set(root, generation(root) + 1)
}

function restoreDeferredFallback(root: HTMLElement): void {
  invalidate(root)
  controllers.get(root)?.abort()
  controllers.delete(root)
  inFlight.delete(root)
  identityRevisions.delete(root)
  const fallback = fallbackNodes.get(root)
  if (fallback != null) root.replaceChildren(...fallback.map((node) => node.cloneNode(true)))
  root.dataset.bmlDeferredState = "pending"
  root.setAttribute("aria-busy", "true")
  delete root.dataset.bmlDeferredError
}

function bindRetry(): void {
  if (state.retryBound) return
  state.retryBound = true
  document.addEventListener("click", (event) => {
    const target = event.target instanceof Element ? event.target.closest("[data-bml-deferred-retry]") : null
    const root = target?.closest<HTMLElement>("[data-bml-deferred]")
    if (!root) return
    event.preventDefault()
    refreshDeferred(root)
  })
}

function bindPageShow(): void {
  if (state.pageShowBound || typeof window === "undefined") return
  state.pageShowBound = true
  window.addEventListener("pageshow", (event) => {
    if (!event.persisted) return
    resetSessionReloadRequest()
    refreshDeferred()
  })
}

function currentPageRoute(): string {
  return document.querySelector("script[data-bml-page-canonical]")?.getAttribute("data-bml-page")
    ?? document.querySelector("script[data-bml-page]")?.getAttribute("data-bml-page")
    ?? window.location.pathname
}

function parseProps(raw: string | undefined): Record<string, unknown> {
  if (!raw) return {}
  try {
    const value = JSON.parse(raw) as unknown
    return value != null && typeof value === "object" && !Array.isArray(value)
      ? value as Record<string, unknown>
      : {}
  } catch {
    return {}
  }
}

function decodeDeferredValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(decodeDeferredValue)
  if (value == null || typeof value !== "object") return value
  const object = value as Record<string, unknown>
  const type = object.$bml
  if (typeof type !== "string" || !("value" in object)) {
    return Object.fromEntries(Object.entries(object).map(([key, entry]) => [key, decodeDeferredValue(entry)]))
  }
  const tagged = object.value
  switch (type) {
    case "long":
      return BigInt(String(tagged))
    case "byte":
    case "short":
    case "float":
    case "double":
      return Number(tagged)
    case "char":
      return String(tagged)
    case "map":
      // Maps travel as [[key, value], …] pairs so the server keeps their order; client scripts receive
      // a plain object, whose integer-like keys follow JavaScript's own ordering. The object form is
      // what shells cached before the pair encoding still carry.
      if (Array.isArray(tagged)) {
        return Object.fromEntries(tagged
          .filter((pair): pair is [string, unknown] => Array.isArray(pair) && pair.length === 2 && typeof pair[0] === "string")
          .map(([key, entry]) => [key, decodeDeferredValue(entry)]))
      }
      return tagged != null && typeof tagged === "object"
        ? Object.fromEntries(Object.entries(tagged as Record<string, unknown>)
          .map(([key, entry]) => [key, decodeDeferredValue(entry)]))
        : {}
    case "list":
    case "boolean-array":
    case "byte-array":
    case "short-array":
    case "int-array":
    case "long-array":
    case "float-array":
    case "double-array":
    case "char-array":
      return Array.isArray(tagged) ? tagged.map(decodeDeferredValue) : []
    default:
      return Object.fromEntries(Object.entries(object).map(([key, entry]) => [key, decodeDeferredValue(entry)]))
  }
}

function elementsIncluding(scope: ParentNode, selector: string): HTMLElement[] {
  const elements = Array.from(scope.querySelectorAll<HTMLElement>(selector))
  if (scope instanceof HTMLElement && scope.matches(selector)) elements.unshift(scope)
  return elements
}
