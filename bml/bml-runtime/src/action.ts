/**
 * Declarative live-state actions. State scripts, action elements, and optional island views join by
 * `data-bml-state-key`; no global model registry is needed. State remains bound to its page render
 * root or explicit component. A page-scoped component uses the concrete pathname, while a site-scoped
 * component uses one stable key across pages and is restored only when its view is present.
 */
import { authHeaders } from "./graphql"
import { notifyIslandUpdate, replaceChildrenFromHtml } from "./update"
import {
  BML_IDENTITY_CHANGE_EVENT,
  currentPageLocale,
  currentPagePath,
  currentPageQuery,
  isInsidePendingDeferred,
  clearSessionReloadMarker,
  pendingDeferredIdentity,
  reloadOnceForSession,
} from "./deferred-runtime"
import { stringifyJson } from "./json"
import { sharedState } from "./shared-state"

/** URL bml-server serves live-state actions on (same origin as the page). */
export const ACTION_URL = "/_bml/action"

type ClientStorageScope = "client-session" | "client-local"

interface InFlightAction {
  readonly epoch: number
  readonly promise: Promise<void>
}

/** Scheduling and unload behavior shared by declarative and programmatic actions. */
export interface ActionOptions {
  readonly debounceMs?: number
  readonly throttleMs?: number
  readonly coalesce?: boolean
  readonly keepalive?: boolean
  readonly flushOnPageHide?: boolean
  /** Distinguishes independently scheduled calls to the same state method. */
  readonly scheduleKey?: string
}

/** Cleanup behavior after an explicit authentication transition. */
export interface ClearBmlClientStateOptions {
  /**
   * Reload after clearing marked state to obtain a fresh server render. Defaults to true.
   * Set to false only when the current page shell is independent of the authenticated identity.
   */
  readonly reload?: boolean
}

interface ScheduledAction {
  key: string
  method: string
  args: unknown[]
  options: ActionOptions
  epoch: number
  timer: ReturnType<typeof setTimeout> | null
  readonly promise: Promise<void>
  readonly resolve: () => void
  readonly reject: (error: unknown) => void
}

interface ActionResponse {
  readonly state: string | null
  readonly html: string | null
}

interface StateBinding {
  readonly script: HTMLScriptElement
  readonly view: HTMLElement | null | undefined
}

interface ActionRuntimeState {
  readonly restoredStateScripts: WeakSet<HTMLScriptElement>
  readonly restoredStateViews: WeakSet<HTMLElement>
  readonly restoringStateScripts: WeakMap<HTMLScriptElement, Promise<void>>
  readonly initialClientStates: WeakMap<HTMLScriptElement, string>
  readonly inFlight: Map<string, InFlightAction>
  readonly scheduled: Map<string, ScheduledAction>
  readonly coalesced: Map<string, ScheduledAction>
  readonly lastDispatchAt: Map<string, number>
  readonly cancellationListeners: Set<() => void>
  epoch: number
  controller: AbortController
  pageHideBound: boolean
}

const actionState = sharedState<ActionRuntimeState>("@bosca/bml/action-runtime", () => ({
  restoredStateScripts: new WeakSet<HTMLScriptElement>(),
  restoredStateViews: new WeakSet<HTMLElement>(),
  restoringStateScripts: new WeakMap<HTMLScriptElement, Promise<void>>(),
  initialClientStates: new WeakMap<HTMLScriptElement, string>(),
  inFlight: new Map<string, InFlightAction>(),
  scheduled: new Map<string, ScheduledAction>(),
  coalesced: new Map<string, ScheduledAction>(),
  lastDispatchAt: new Map<string, number>(),
  cancellationListeners: new Set<() => void>(),
  epoch: 0,
  controller: new AbortController(),
  pageHideBound: false,
}))

const {
  restoredStateScripts,
  restoredStateViews,
  restoringStateScripts,
  initialClientStates,
  inFlight,
  scheduled,
  coalesced,
  lastDispatchAt,
  cancellationListeners,
} = actionState

/** Bind each not-yet-bound declarative action under [scope], including [scope] itself. */
export function bindActions(scope: ParentNode = document): void {
  if (typeof document === "undefined") return
  const elements = Array.from(scope.querySelectorAll<HTMLElement>("[data-bml-method]"))
  if (scope instanceof HTMLElement && scope.matches("[data-bml-method]")) elements.unshift(scope)
  for (const el of elements) {
    if (isInsidePendingDeferred(el)) continue
    if (el.dataset.bmlActionBound) continue
    el.dataset.bmlActionBound = "true"
    el.addEventListener(el.dataset.bmlEvent ?? "click", (event) => {
      const key = el.dataset.bmlStateKey
      const method = el.dataset.bmlMethod
      if (!key || !method) return
      if (event.type === "click" || event.type === "submit") event.preventDefault()
      const options = actionOptions(el)
      if (activeInFlight(key, actionState.epoch) && !options.coalesce) return
      const wasDisabled = el instanceof HTMLButtonElement ? el.disabled : undefined
      el.dataset.bmlActionPending = "true"
      el.setAttribute("aria-busy", "true")
      if (el instanceof HTMLButtonElement) el.disabled = true
      dispatchAction(key, method, resolveArgs(el), options)
        .catch((error) => console.error(`bml: action '${method}' on '${key}' failed`, error))
        .finally(() => {
          if (!el.isConnected || el.dataset.bmlActionPending !== "true") return
          delete el.dataset.bmlActionPending
          el.removeAttribute("aria-busy")
          if (el instanceof HTMLButtonElement) el.disabled = wasDisabled ?? false
        })
    })
  }
}

function actionOptions(el: HTMLElement): ActionOptions {
  return {
    debounceMs: numberData(el.dataset.bmlDebounceMs),
    throttleMs: numberData(el.dataset.bmlThrottleMs),
    coalesce: el.hasAttribute("data-bml-coalesce"),
    keepalive: el.hasAttribute("data-bml-keepalive"),
    flushOnPageHide: el.hasAttribute("data-bml-flush-pagehide"),
    scheduleKey: el.dataset.bmlActionId,
  }
}

function numberData(raw: string | undefined): number | undefined {
  if (raw == null) return undefined
  const value = Number(raw)
  return Number.isFinite(value) && value >= 0 ? value : undefined
}

/** Resolve render-time values and `form.<field>` sentinels into action arguments. */
function resolveArgs(el: HTMLElement): unknown[] {
  const raw = el.dataset.bmlArgs
  if (!raw) return []
  let parsed: unknown[]
  try {
    parsed = JSON.parse(raw) as unknown[]
  } catch {
    return []
  }
  return parsed.map((arg) => {
    if (arg && typeof arg === "object" && "__bmlField" in (arg as Record<string, unknown>)) {
      const name = String((arg as Record<string, unknown>).__bmlField)
      const form = el instanceof HTMLFormElement ? el : el.closest("form")
      const field = form?.elements.namedItem(name)
      if (field instanceof HTMLInputElement && field.type === "checkbox") return field.checked
      const value = (field as { value?: unknown } | null | undefined)?.value
      return value === undefined ? "" : String(value)
    }
    return arg
  })
}

/** Dispatch one model method. Calls for the same state key are serialized. */
export function dispatchAction(
  key: string,
  method: string,
  args: unknown[] = [],
  options: ActionOptions = {},
): Promise<void> {
  const id = actionId(key, method, options.scheduleKey)
  const epoch = actionState.epoch
  if (options.flushOnPageHide) bindPageHide()

  const debounceMs = normalizedDelay(options.debounceMs)
  const throttleMs = normalizedDelay(options.throttleMs)
  if (debounceMs != null) return scheduleDebounced(id, key, method, args, options, epoch, debounceMs)
  if (throttleMs != null) {
    const elapsed = Date.now() - (lastDispatchAt.get(id) ?? Number.NEGATIVE_INFINITY)
    if (elapsed < throttleMs) return scheduleAt(id, key, method, args, options, epoch, throttleMs - elapsed)
    lastDispatchAt.set(id, Date.now())
  }

  const delayed = scheduled.get(id)
  if (delayed) {
    if (delayed.timer != null) clearTimeout(delayed.timer)
    scheduled.delete(id)
  }
  const result = dispatchQueued(key, method, args, options, epoch)
  if (delayed) result.then(delayed.resolve, delayed.reject)
  return result
}

function dispatchQueued(
  key: string,
  method: string,
  args: unknown[],
  options: ActionOptions,
  epoch: number,
): Promise<void> {
  const active = activeInFlight(key, epoch)
  if (options.coalesce && active) {
    const id = actionId(key, method, options.scheduleKey)
    const existing = coalesced.get(id)
    if (existing) {
      existing.args = args
      existing.options = options
      return existing.promise
    }
    const pending = scheduledAction(key, method, args, options, epoch)
    coalesced.set(id, pending)
    const runPending = () => {
      if (coalesced.get(id) !== pending) return
      coalesced.delete(id)
      dispatchQueued(pending.key, pending.method, pending.args, { ...pending.options, coalesce: false }, pending.epoch)
        .then(pending.resolve, pending.reject)
    }
    active.promise.then(runPending, runPending)
    return pending.promise
  }
  let viewSync: { script: HTMLScriptElement; view: HTMLElement } | null = null
  const action = enqueueStateOperation(key, epoch, () => runAction(
    key,
    method,
    args,
    options,
    epoch,
    (script, view) => { viewSync = { script, view } },
  ))
  return action.then(async () => {
    if (epoch !== actionState.epoch || viewSync == null) return
    const { script, view } = viewSync
    await restoreStateScript(script, view, true)
  })
}

function enqueueStateOperation(key: string, epoch: number, operation: () => Promise<void>): Promise<void> {
  const previous = activeInFlight(key, epoch)
  let cancel!: () => void
  const canceled = new Promise<void>((resolve) => { cancel = resolve })
  cancellationListeners.add(cancel)
  const run = (previous ? previous.promise.catch(() => {}) : Promise.resolve()).then(async () => {
    if (epoch !== actionState.epoch) return
    await operation()
  }).catch((error) => {
    if (epoch !== actionState.epoch && isAbortError(error)) return
    throw error
  })
  let entry!: InFlightAction
  const tracked = Promise.race([run, canceled]).finally(() => {
    cancellationListeners.delete(cancel)
    if (inFlight.get(key) === entry) inFlight.delete(key)
  })
  entry = { epoch, promise: tracked }
  inFlight.set(key, entry)
  return tracked
}

function activeInFlight(key: string, epoch: number): InFlightAction | undefined {
  const active = inFlight.get(key)
  return active?.epoch === epoch ? active : undefined
}

function scheduleDebounced(
  id: string,
  key: string,
  method: string,
  args: unknown[],
  options: ActionOptions,
  epoch: number,
  delay: number,
): Promise<void> {
  const pending = scheduled.get(id) ?? scheduledAction(key, method, args, options, epoch)
  if (pending.timer != null) clearTimeout(pending.timer)
  pending.key = key
  pending.method = method
  pending.args = args
  pending.options = options
  pending.epoch = epoch
  pending.timer = setTimeout(() => runScheduled(id, pending), delay)
  scheduled.set(id, pending)
  return pending.promise
}

function scheduleAt(
  id: string,
  key: string,
  method: string,
  args: unknown[],
  options: ActionOptions,
  epoch: number,
  delay: number,
): Promise<void> {
  const existing = scheduled.get(id)
  if (existing) {
    existing.args = args
    existing.options = options
    return existing.promise
  }
  const pending = scheduledAction(key, method, args, options, epoch)
  pending.timer = setTimeout(() => runScheduled(id, pending), delay)
  scheduled.set(id, pending)
  return pending.promise
}

function runScheduled(id: string, pending: ScheduledAction): void {
  if (scheduled.get(id) !== pending) return
  scheduled.delete(id)
  pending.timer = null
  if (pending.epoch !== actionState.epoch) {
    pending.resolve()
    return
  }
  lastDispatchAt.set(id, Date.now())
  dispatchQueued(pending.key, pending.method, pending.args, pending.options, pending.epoch)
    .then(pending.resolve, pending.reject)
}

function scheduledAction(
  key: string,
  method: string,
  args: unknown[],
  options: ActionOptions,
  epoch: number,
): ScheduledAction {
  let resolve!: () => void
  let reject!: (error: unknown) => void
  const promise = new Promise<void>((ok, fail) => { resolve = ok; reject = fail })
  return { key, method, args, options, epoch, timer: null, promise, resolve, reject }
}

function actionId(key: string, method: string, scheduleKey?: string): string {
  return `${key}\u0000${method}\u0000${scheduleKey ?? ""}`
}

function normalizedDelay(value: number | undefined): number | undefined {
  return value != null && Number.isFinite(value) && value >= 0 ? value : undefined
}

function bindPageHide(): void {
  if (actionState.pageHideBound || typeof window === "undefined") return
  actionState.pageHideBound = true
  window.addEventListener("pagehide", flushPageHideActions)
}

function flushPageHideActions(): void {
  for (const [id, pending] of scheduled) {
    if (!pending.options.flushOnPageHide) continue
    if (pending.timer != null) clearTimeout(pending.timer)
    scheduled.delete(id)
    pending.timer = null
    dispatchQueued(
      pending.key,
      pending.method,
      pending.args,
      {
        ...pending.options,
        debounceMs: undefined,
        throttleMs: undefined,
        coalesce: true,
        keepalive: true,
      },
      pending.epoch,
    ).then(pending.resolve, pending.reject)
  }
}

async function runAction(
  key: string,
  method: string,
  args: unknown[],
  options: ActionOptions,
  epoch: number,
  requestViewSync: (script: HTMLScriptElement, view: HTMLElement) => void,
): Promise<void> {
  const script = currentStateScript(key)
  const scope = clientStorageScope(script)
  const site = isSiteScoped(script)
  const clearOnSignOut = isClearOnSignOut(script)
  if (scope) removeStoredState(null, key, site, !clearOnSignOut)
  const defaultState = initialClientState(script)
  const currentState = script?.textContent ?? ""
  const saved = scope ? validStoredState(scope, key, site, clearOnSignOut) : null
  const state = saved ?? currentState
  const page = currentPage()
  const browserPath = window.location.pathname
  const browserQuery = window.location.search
  const path = currentPagePath()
  const query = currentPageQuery()
  const locale = currentPageLocale()
  const view = currentIslandView(key)
  const response = await postAction(
    page,
    path,
    query,
    locale,
    key,
    method,
    state,
    args,
    view != null,
    options.keepalive === true,
  )
  if (epoch !== actionState.epoch) return
  const sameRenderContext = () =>
    browserPath === window.location.pathname &&
    browserQuery === window.location.search &&
    page === currentPage() &&
    path === currentPagePath() &&
    locale === currentPageLocale()
  const responseApplicable = () => site || (
    sameRenderContext() &&
    currentStateScript(key) === script &&
    currentIslandView(key) === view &&
    clientStorageScope(script) === scope &&
    !isSiteScoped(script) &&
    isClearOnSignOut(script) === clearOnSignOut
  )
  if (!responseApplicable()) return
  if (response.status === 410) {
    // Reload once for a fresh session; a repeat means the browser is not keeping the session cookie.
    if (reloadOnceForSession()) return
    throw new Error("BML action HTTP 410: the browser did not keep the page session")
  }
  if (response.status === 422) {
    if (saved != null && scope && getStoredState(scope, key, site, clearOnSignOut) === saved) {
      removeStoredState(scope, key, site, clearOnSignOut)
    }
    throw new InvalidClientStateError()
  }
  if (!response.ok) throw new Error(`BML action HTTP ${response.status}`)
  const data = await readActionResponse(response)
  clearSessionReloadMarker()
  if (epoch !== actionState.epoch || !responseApplicable()) return

  applyClientState(key, data.state, script, scope, site, clearOnSignOut, defaultState)
  if (script) restoredStateScripts.add(script)
  const currentScript = site ? currentStateScript(key) : script
  const currentView = site
    ? currentIslandView(key)
    : currentStateScript(key) === script && currentIslandView(key) === view ? view : null
  if (currentView && data.html != null && (!site || sameRenderContext())) {
    applyView(currentView, data.html)
    restoredStateViews.add(currentView)
  } else if (site && currentScript && currentView) {
    requestViewSync(currentScript, currentView)
  }
}

/** Restore persistent state; site state waits until its component's view is present. */
export async function restoreClientState(scope?: ParentNode): Promise<void> {
  if (typeof document === "undefined") return
  for (const binding of stateBindings(scope ?? document)) await restoreStateScript(binding.script, binding.view)
}

async function restoreStateScript(
  script: HTMLScriptElement,
  knownView?: HTMLElement | null,
  forceViewRender = false,
): Promise<void> {
  const key = script.dataset.bmlStateKey
  if (!key) {
    restoredStateScripts.add(script)
    return
  }
  const storageScope = clientStorageScope(script)
  const site = isSiteScoped(script)
  const clearOnSignOut = isClearOnSignOut(script)
  const view = knownView === undefined ? currentIslandView(key) : knownView
  const needsViewSync = forceViewRender || (
    site && view != null && restoredStateScripts.has(script) && !restoredStateViews.has(view)
  )
  if (restoredStateScripts.has(script) && !needsViewSync) return
  const activeRestore = restoringStateScripts.get(script)
  if (activeRestore) {
    await activeRestore
    await restoreStateScript(script, knownView, forceViewRender)
    return
  }
  initialClientState(script)
  if (storageScope) removeStoredState(null, key, site, !clearOnSignOut)
  if (!storageScope && !forceViewRender) {
    removeStoredState(null, key, site, clearOnSignOut)
    restoredStateScripts.add(script)
    if (view) restoredStateViews.add(view)
    return
  }
  if (storageScope) removeStoredState(otherClientStorageScope(storageScope), key, site, clearOnSignOut)
  if (site && view == null) return
  if (storageScope && !needsViewSync &&
    storedStateToRestore(storageScope, key, script.textContent ?? "", site, clearOnSignOut) == null) {
    restoredStateScripts.add(script)
    if (view) restoredStateViews.add(view)
    return
  }

  const epoch = actionState.epoch
  const restore = (async () => {
    let complete = false
    try {
      await enqueueStateOperation(key, epoch, async () => {
        complete = await performRestore(script, key, storageScope, site, clearOnSignOut, needsViewSync, epoch)
      })
      if (complete && epoch === actionState.epoch) restoredStateScripts.add(script)
    } catch {
      // Network, auth, dependency, and render failures leave valid stored state retryable.
    }
  })()
  let tracked!: Promise<void>
  tracked = restore.finally(() => {
    if (restoringStateScripts.get(script) === tracked) restoringStateScripts.delete(script)
  })
  restoringStateScripts.set(script, tracked)
  await tracked
}

async function performRestore(
  script: HTMLScriptElement,
  key: string,
  storageScope: ClientStorageScope | null,
  site: boolean,
  clearOnSignOut: boolean,
  forceViewRender: boolean,
  epoch: number,
): Promise<boolean> {
  if (currentStateScript(key) !== script || clientStorageScope(script) !== storageScope || isSiteScoped(script) !== site) {
    return false
  }
  const view = currentIslandView(key)
  if (site && view == null) return false
  const initialState = script.textContent ?? ""
  const stored = storageScope ? validStoredState(storageScope, key, site, clearOnSignOut) : null
  const saved = storageScope && stored != null && stored !== initialState
    ? stored
    : forceViewRender ? stored ?? initialState : null
  if (saved == null) return true
  const page = currentPage()
  const browserPath = window.location.pathname
  const browserQuery = window.location.search
  const path = currentPagePath()
  const query = currentPageQuery()
  const locale = currentPageLocale()
  const response = await postAction(
    page,
    path,
    query,
    locale,
    key,
    "",
    saved,
    [],
    view != null,
    false,
  )
  if (epoch !== actionState.epoch) return false
  const responseApplicable = () =>
    browserPath === window.location.pathname &&
    browserQuery === window.location.search &&
    page === currentPage() &&
    path === currentPagePath() &&
    locale === currentPageLocale() &&
    currentStateScript(key) === script &&
    (script.textContent ?? "") === initialState &&
    clientStorageScope(script) === storageScope &&
    isSiteScoped(script) === site &&
    currentIslandView(key) === view &&
    (storageScope == null || getStoredState(storageScope, key, site, clearOnSignOut) === stored)
  if (!responseApplicable()) return false
  if (response.status === 410) {
    if (reloadOnceForSession()) return false
    throw new Error("BML action HTTP 410: the browser did not keep the page session")
  }
  if (response.status === 422) {
    if (storageScope && stored != null && getStoredState(storageScope, key, site, clearOnSignOut) === stored) {
      removeStoredState(storageScope, key, site, clearOnSignOut)
    }
    return true
  }
  if (!response.ok) throw new Error(`BML action HTTP ${response.status}`)
  const data = await readActionResponse(response)
  clearSessionReloadMarker()
  if (epoch !== actionState.epoch || !responseApplicable()) return false
  applyClientState(key, data.state, script, storageScope, site, clearOnSignOut, initialClientState(script))
  if (view && data.html != null) {
    applyView(view, data.html)
    restoredStateViews.add(view)
  }
  return true
}

async function postAction(
  page: string,
  path: string,
  query: Record<string, string>,
  locale: string | undefined,
  key: string,
  method: string,
  state: string,
  args: unknown[],
  renderView: boolean,
  keepalive: boolean,
): Promise<Response> {
  const signal = actionState.controller.signal
  // A keepalive action is flushed while the page unloads, which leaves no time to wait for an identity
  // bootstrap; sending it without the new cookies is better than never sending it.
  const identity = keepalive ? null : pendingDeferredIdentity()
  if (identity != null) await identity
  if (signal.aborted) throw new DOMException("The operation was aborted.", "AbortError")
  return fetch(ACTION_URL, {
    method: "POST",
    headers: authHeaders(),
    credentials: "same-origin",
    keepalive,
    signal,
    body: stringifyJson({
      page,
      path,
      query,
      locale: locale ?? null,
      stateKey: key,
      method,
      state,
      args,
      renderView,
    }),
  })
}

async function readActionResponse(response: Response): Promise<ActionResponse> {
  return (await response.json()) as ActionResponse
}

function applyClientState(
  key: string,
  state: string | null,
  originalScript: HTMLScriptElement | null,
  scope: ClientStorageScope | null,
  site: boolean,
  clearOnSignOut: boolean,
  defaultState: string,
): void {
  if (state == null) return
  const script = site ? currentStateScript(key) : originalScript
  if (script) script.textContent = state
  if (scope) {
    removeStoredState(otherClientStorageScope(scope), key, site, clearOnSignOut)
    if (site && state === defaultState) removeStoredState(scope, key, site, clearOnSignOut)
    else setStoredState(scope, key, state, site, clearOnSignOut)
  } else {
    removeStoredState(null, key, site, clearOnSignOut)
  }
}

function applyView(view: HTMLElement, html: string, preserve = true): void {
  const update = replaceChildrenFromHtml(view, html, preserve)
  bindActions(view)
  notifyIslandUpdate(update)
}

class InvalidClientStateError extends Error {}

function stateBindings(scope: ParentNode): StateBinding[] {
  const scripts = new Set(Array.from(scope.querySelectorAll<HTMLScriptElement>("script[data-bml-state-key]"))
    .filter((script) => !isInsidePendingDeferred(script)))
  if (scope instanceof HTMLScriptElement && scope.matches("script[data-bml-state-key]") && !isInsidePendingDeferred(scope)) {
    scripts.add(scope)
  }
  const views = Array.from(scope.querySelectorAll<HTMLElement>("[data-bml-island][data-bml-state-key]"))
    .filter((view) => !isInsidePendingDeferred(view))
  if (scope instanceof HTMLElement && scope.matches("[data-bml-island][data-bml-state-key]") && !isInsidePendingDeferred(scope)) {
    views.unshift(scope)
  }
  const scriptsByKey = new Map<string, HTMLScriptElement>()
  scripts.forEach((script) => {
    const key = script.dataset.bmlStateKey
    if (key) scriptsByKey.set(key, script)
  })
  if (views.some((view) => {
    const key = view.dataset.bmlStateKey
    return key != null && !scriptsByKey.has(key)
  })) {
    document.querySelectorAll<HTMLScriptElement>("script[data-bml-state-key]").forEach((script) => {
      if (isInsidePendingDeferred(script)) return
      const key = script.dataset.bmlStateKey
      if (key && !scriptsByKey.has(key)) scriptsByKey.set(key, script)
    })
  }
  const viewsByKey = new Map<string, HTMLElement>()
  for (const view of views) {
    const key = view.dataset.bmlStateKey
    const script = key ? scriptsByKey.get(key) : null
    if (script) scripts.add(script)
    if (!key) continue
    if (viewsByKey.has(key)) throw new Error(`BML state '${key}' has multiple live views`)
    viewsByKey.set(key, view)
  }
  return Array.from(scripts, (script) => {
    const key = script.dataset.bmlStateKey
    return {
      script,
      view: key && viewsByKey.has(key) ? viewsByKey.get(key) : scope === document ? null : undefined,
    }
  })
}

function currentStateScript(key: string): HTMLScriptElement | null {
  return Array.from(document.querySelectorAll<HTMLScriptElement>(
    `script[data-bml-state-key="${cssEscape(key)}"]`,
  )).find((script) => !isInsidePendingDeferred(script)) ?? null
}

function currentIslandView(key: string): HTMLElement | null {
  const views = islandViews(key)
  if (views.length > 1) throw new Error(`BML state '${key}' has multiple live views`)
  return views[0] ?? null
}

function islandViews(key: string): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>(
    `[data-bml-island][data-bml-state-key="${cssEscape(key)}"]`,
  )).filter((view) => !isInsidePendingDeferred(view))
}

function isSiteScoped(script: HTMLScriptElement | null): boolean {
  return script?.hasAttribute("data-bml-site") === true
}

function isClearOnSignOut(script: HTMLScriptElement | null): boolean {
  return script?.hasAttribute("data-bml-clear-on-sign-out") === true
}

function initialClientState(script: HTMLScriptElement | null): string {
  if (!script) return ""
  const existing = initialClientStates.get(script)
  if (existing != null) return existing
  const initial = script.textContent ?? ""
  initialClientStates.set(script, initial)
  return initial
}

function clientStorageScope(script: HTMLScriptElement | null): ClientStorageScope | null {
  const scope = script?.dataset.bmlScope
  return scope === "client-session" || scope === "client-local" ? scope : null
}

function otherClientStorageScope(scope: ClientStorageScope): ClientStorageScope {
  return scope === "client-session" ? "client-local" : "client-session"
}

function validStoredState(scope: ClientStorageScope, key: string, site: boolean, clearOnSignOut: boolean): string | null {
  const saved = getStoredState(scope, key, site, clearOnSignOut)
  if (saved == null) return null
  if (isSerializedClientState(saved)) return saved
  removeStoredState(scope, key, site, clearOnSignOut)
  return null
}

function storedStateToRestore(
  scope: ClientStorageScope,
  key: string,
  currentState: string,
  site: boolean,
  clearOnSignOut: boolean,
): string | null {
  const saved = validStoredState(scope, key, site, clearOnSignOut)
  return saved == null || saved === currentState ? null : saved
}

function isSerializedClientState(state: string): boolean {
  try {
    JSON.parse(state)
    return true
  } catch {
    return false
  }
}

/** The current page's route pattern baked into the generated page marker. */
function currentPage(): string {
  return document.querySelector("script[data-bml-page-canonical]")?.getAttribute("data-bml-page")
    ?? document.querySelector("script[data-bml-page]")?.getAttribute("data-bml-page")
    ?? window.location.pathname
}

function cssEscape(value: string): string {
  if (typeof CSS !== "undefined" && typeof CSS.escape === "function") return CSS.escape(value)
  return value.replace(/["\\]/g, "\\$&")
}

/** Site state has one key; page state remains scoped to the concrete pathname. */
function storageKey(key: string, site: boolean, clearOnSignOut: boolean): string {
  const prefix = clearOnSignOut ? "bml:clear:" : "bml:"
  return site ? `${prefix}site:${key}` : `${prefix}${window.location.pathname}:${key}`
}

function storage(scope: ClientStorageScope): Storage | null {
  try {
    if (typeof window === "undefined") return null
    return scope === "client-local" ? window.localStorage : window.sessionStorage
  } catch {
    return null
  }
}

function getStoredState(scope: ClientStorageScope, key: string, site: boolean, clearOnSignOut: boolean): string | null {
  try {
    return storage(scope)?.getItem(storageKey(key, site, clearOnSignOut)) ?? null
  } catch {
    return null
  }
}

function setStoredState(
  scope: ClientStorageScope,
  key: string,
  value: string,
  site: boolean,
  clearOnSignOut: boolean,
): boolean {
  const store = storage(scope)
  if (!store) return false
  const storedKey = storageKey(key, site, clearOnSignOut)
  try {
    store.setItem(storedKey, value)
    return true
  } catch (error) {
    console.warn(`bml: ${scope} state '${key}' could not be persisted`, error)
    try {
      store.removeItem(storedKey)
    } catch {
      // Storage cleanup is best-effort in browser privacy modes.
    }
    return false
  }
}

/** Null removes the selected state from both browser stores. */
function removeStoredState(
  scope: ClientStorageScope | null,
  key: string,
  site: boolean,
  clearOnSignOut: boolean,
): void {
  const scopes: ClientStorageScope[] = scope ? [scope] : ["client-session", "client-local"]
  const storedKey = storageKey(key, site, clearOnSignOut)
  for (const candidate of scopes) {
    try {
      storage(candidate)?.removeItem(storedKey)
    } catch {
      // Storage cleanup is best-effort in browser privacy modes.
    }
  }
}

/** Cancel current actions and clear client state explicitly marked as identity-bound. */
export function clearBmlClientState(options: ClearBmlClientStateOptions = {}): void {
  invalidateClientActions()
  for (const scope of ["client-session", "client-local"] as const) {
    const store = storage(scope)
    if (!store) continue
    try {
      const keys: string[] = []
      for (let index = 0; index < store.length; index++) {
        const key = store.key(index)
        if (key?.startsWith("bml:clear:")) keys.push(key)
      }
      keys.forEach((key) => store.removeItem(key))
    } catch {
      // Storage cleanup is best-effort in browser privacy modes.
    }
  }
  if (typeof document === "undefined") return
  // The server-session cookie is HttpOnly, so mark it for replacement on the next stateful page
  // render. Writing this synchronously also makes the default reload race-free.
  document.cookie = `bml_session_reset=1; Path=/; SameSite=Lax${window.location.protocol === "https:" ? "; Secure" : ""}`
  const markedScripts = Array.from(document.querySelectorAll<HTMLScriptElement>(
    "script[data-bml-state-key][data-bml-clear-on-sign-out]",
  ))
  for (const script of markedScripts) {
    const key = script.dataset.bmlStateKey
    if (!key) continue
    script.textContent = ""
    restoredStateScripts.delete(script)
    for (const view of islandViews(key)) {
      view.replaceChildren()
      restoredStateViews.delete(view)
    }
  }
  if (options.reload !== false) {
    window.location.reload()
    return
  }
  document.dispatchEvent(new Event(BML_IDENTITY_CHANGE_EVENT))
}

/** Cancel work created before client-state cleanup so it cannot recreate signed-out state. */
function invalidateClientActions(): void {
  actionState.epoch++
  cancellationListeners.forEach((cancel) => cancel())
  cancellationListeners.clear()
  actionState.controller.abort()
  actionState.controller = new AbortController()
  inFlight.clear()
  for (const pending of scheduled.values()) {
    if (pending.timer != null) clearTimeout(pending.timer)
    pending.resolve()
  }
  scheduled.clear()
  for (const pending of coalesced.values()) pending.resolve()
  coalesced.clear()
  lastDispatchAt.clear()
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError"
}
