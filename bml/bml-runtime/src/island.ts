/**
 * Client scopes for BML islands and components. Each occurrence receives a root-local context with
 * generated refs, programmatic server actions, event-listener cleanup, partial updates, and
 * mount/unmount lifecycle. No scoped handler is published on `window`.
 */
import { bindActions, dispatchAction, restoreClientState, type ActionOptions } from "./action"
import {
  BML_UPDATE_EVENT,
  notifyIslandUpdate,
  replaceChildrenFromHtml,
  type IslandUpdate,
} from "./update"
import {
  BML_DEFERRED_REFRESH_EVENT,
  BML_IDENTITY_CHANGE_EVENT,
  currentDeferredRuntime,
  isInsidePendingDeferred,
} from "./deferred-runtime"
import { sharedState } from "./shared-state"

export interface ClientContext<Refs extends object = Record<string, Element>> {
  /** This instance's real root element; BML adds no wrapper for components. */
  readonly root: HTMLElement
  /** Unique per-mounted instance. */
  readonly id: string
  /** Server-passed island props (`:prop="expr"`). */
  readonly props: Record<string, unknown>
  /** Lazily resolved, compiler-typed elements authored with `ref="name"`. */
  readonly refs: Refs
  /** Nullable/dynamic ref lookup for names that cannot be statically generated. */
  ref<T extends Element = HTMLElement>(name: string): T | null
  /** Every matching ref, useful for refs emitted from loops. */
  refsAll<T extends Element = HTMLElement>(name: string): readonly T[]
  /** Namespace an otherwise-global key (storage, third-party groups, custom events). */
  scoped(name: string): string
  /** Add a listener that BML removes automatically when this instance unmounts. */
  on<E extends Event>(
    target: EventTarget,
    type: string,
    listener: (event: E) => void,
    options?: boolean | AddEventListenerOptions,
  ): void
  /** Replace a region with trusted server HTML and mount any client scopes it introduces. */
  replace(target: Element | string, html: string): void
  /** Run after each declarative-action or `replace` update. */
  onUpdate(cb: (update: IslandUpdate) => void): void
  /** Run when the instance leaves the document. */
  onUnmount(cb: () => void): void
}

export interface IslandContext<Refs extends object = Record<string, Element>> extends ClientContext<Refs> {
  /** Dispatch a method on this island's server model. */
  dispatch(method: string, args?: unknown[], options?: ActionOptions): Promise<void>
  /** Dispatch a method on a named model; generated component code uses this when multiple models exist. */
  dispatch(state: string, method: string, args?: unknown[], options?: ActionOptions): Promise<void>
}

export type ClientSetup<Refs extends object = Record<string, Element>> =
  (ctx: IslandContext<Refs>) => void | Promise<void>

interface IslandRuntimeState {
  readonly islandRegistry: Map<string, ClientSetup<object>>
  readonly componentRegistry: Map<string, ClientSetup<object>>
  readonly unmounters: WeakMap<Element, Array<() => void>>
  readonly mountedRoles: WeakMap<Element, Set<"bmlComponent" | "bmlIsland">>
  observer: MutationObserver | null
  deferredEventsBound: boolean
  instanceCounter: number
}

const runtimeState = sharedState<IslandRuntimeState>("@bosca/bml/island-runtime", () => ({
  islandRegistry: new Map<string, ClientSetup<object>>(),
  componentRegistry: new Map<string, ClientSetup<object>>(),
  unmounters: new WeakMap<Element, Array<() => void>>(),
  mountedRoles: new WeakMap<Element, Set<"bmlComponent" | "bmlIsland">>(),
  observer: null,
  deferredEventsBound: false,
  instanceCounter: 0,
}))

const { islandRegistry, componentRegistry, unmounters, mountedRoles } = runtimeState

/** Register an island's client setup, keyed by its `name`. */
export function defineIsland<Refs extends object = Record<string, Element>>(
  name: string,
  setup: ClientSetup<Refs>,
): void {
  islandRegistry.set(name, setup as ClientSetup<object>)
}

/** Register a wrapper-free component setup, keyed by its component tag. */
export function defineComponent<Refs extends object = Record<string, Element>>(
  tag: string,
  setup: ClientSetup<Refs>,
): void {
  componentRegistry.set(tag, setup as ClientSetup<object>)
}

/** Mount every not-yet-mounted action, component, and island under [scope]. Safe to repeat. */
export async function mountAll(scope: ParentNode = document): Promise<void> {
  if (typeof document === "undefined") return
  observeDocument()
  bindDeferredEvents()
  const deferred = currentDeferredRuntime()
  const deferredRoots = deferred ? elementsIncluding(scope, "[data-bml-deferred]") : []
  deferredRoots.filter(deferred?.isIdentityStale ?? (() => false)).forEach(unmountAll)
  const hasPendingDeferred = deferredRoots.some((root) =>
    root.dataset.bmlDeferredState !== "loaded" && root.dataset.bmlDeferredState !== "error",
  )
  const deferredLoad = hasPendingDeferred ? deferred?.loadAll(scope) : undefined
  bindActions(scope)
  void restoreClientState(scope).catch(reportMountError)
  await mountSelector(scope, "[data-bml-component]", componentRegistry, "bmlComponent")
  await mountSelector(scope, "[data-bml-island]", islandRegistry, "bmlIsland")
  if (!deferredLoad) return
  await deferredLoad
  bindActions(scope)
  void restoreClientState(scope).catch(reportMountError)
  await mountSelector(scope, "[data-bml-component]", componentRegistry, "bmlComponent")
  await mountSelector(scope, "[data-bml-island]", islandRegistry, "bmlIsland")
}

/** Unmount client scopes under [scope], running their teardown callbacks exactly once. */
export function unmountAll(scope: ParentNode = document): void {
  const roots = new Set<Element>([
    ...elementsIncluding(scope, "[data-bml-component]"),
    ...elementsIncluding(scope, "[data-bml-island]"),
  ])
  roots.forEach(unmount)
  currentDeferredRuntime()?.abort(scope)
}

async function mountSelector(
  scope: ParentNode,
  selector: string,
  registry: Map<string, ClientSetup<object>>,
  dataKey: "bmlComponent" | "bmlIsland",
): Promise<void> {
  for (const root of elementsIncluding(scope, selector)) {
    const roles = mountedRoles.get(root) ?? new Set<"bmlComponent" | "bmlIsland">()
    if (roles.has(dataKey)) continue
    const name = root.dataset[dataKey]
    if (!name) continue
    if (isInsidePendingDeferred(root)) continue
    const setup = registry.get(name)
    if (!setup) continue
    roles.add(dataKey)
    mountedRoles.set(root, roles)
    const id = root.dataset.bmlId ?? `${name}-${runtimeState.instanceCounter++}`
    try {
      const props = root.dataset.bmlDeferred
        ? currentDeferredRuntime()?.propsForClient?.(root.dataset.bmlProps) ?? parseProps(root.dataset.bmlProps)
        : parseProps(root.dataset.bmlProps)
      await setup(makeContext(root, id, props))
    } catch (error) {
      unmount(root)
      throw error
    }
  }
}

function makeContext(root: HTMLElement, id: string, props: Record<string, unknown>): IslandContext<object> {
  const updateCbs: Array<(update: IslandUpdate) => void> = []
  const unmountCbs = unmounters.get(root) ?? []
  unmounters.set(root, unmountCbs)
  const onDomUpdate = (event: Event) => {
    if (!(event instanceof CustomEvent)) return
    const update = event.detail as IslandUpdate
    updateCbs.forEach((cb) => cb(update))
  }
  root.addEventListener(BML_UPDATE_EVENT, onDomUpdate)
  unmountCbs.push(() => root.removeEventListener(BML_UPDATE_EVENT, onDomUpdate))

  const context: IslandContext<object> = {
    root,
    id,
    props,
    refs: new Proxy({}, {
      get: (_target, property) => typeof property === "string" ? findRef(root, property) : undefined,
    }),
    ref: <T extends Element = HTMLElement>(name: string) => findRef(root, name) as T | null,
    refsAll: <T extends Element = HTMLElement>(name: string) => findRefs(root, name) as unknown as readonly T[],
    scoped: (name) => `${id}:${name}`,
    on<E extends Event>(
      target: EventTarget,
      type: string,
      listener: (event: E) => void,
      options?: boolean | AddEventListenerOptions,
    ) {
      const handler = listener as EventListener
      target.addEventListener(type, handler, options)
      unmountCbs.push(() => target.removeEventListener(type, handler, options))
    },
    replace(target, html) {
      const element = typeof target === "string" ? root.querySelector(target) : target
      if (!element) return
      const update = replaceChildrenFromHtml(element, html)
      bindActions(element)
      void mountAll(element)
      notifyIslandUpdate(update)
    },
    onUpdate(cb) {
      updateCbs.push(cb)
    },
    onUnmount(cb) {
      unmountCbs.push(cb)
    },
    dispatch(
      stateOrMethod: string,
      methodOrArgs: string | unknown[] = [],
      argsOrOptions: unknown[] | ActionOptions = [],
      explicitOptions: ActionOptions = {},
    ) {
      const namedState = typeof methodOrArgs === "string" ? stateOrMethod : null
      const method = typeof methodOrArgs === "string" ? methodOrArgs : stateOrMethod
      const args = typeof methodOrArgs === "string"
        ? (Array.isArray(argsOrOptions) ? argsOrOptions : [])
        : methodOrArgs
      const options = typeof methodOrArgs === "string"
        ? explicitOptions
        : (Array.isArray(argsOrOptions) ? {} : argsOrOptions)
      const key = resolveStateKey(root, namedState)
      if (!key) return Promise.reject(new Error(`BML client scope '${id}' has no live server state`))
      return dispatchAction(key, method, args, { ...options, scheduleKey: options.scheduleKey ?? id })
    },
  }
  return context
}

function resolveStateKey(root: HTMLElement, state: string | null): string | undefined {
  if (state == null) {
    const stateRoot = root.hasAttribute("data-bml-state-key")
      ? root
      : root.querySelector<HTMLElement>("[data-bml-island][data-bml-state-key]")
    return stateRoot?.dataset.bmlStateKey
  }
  const ownKey = root.dataset.bmlStateKey
  const ownRegisteredKey = ownKey?.split(":", 1)[0]
  if (ownKey && (ownRegisteredKey === state || ownRegisteredKey?.endsWith(`.${state}`))) return ownKey
  if (ownKey && ownRegisteredKey) {
    const componentSeparator = ownRegisteredKey.lastIndexOf(".")
    if (componentSeparator > 0) {
      const instanceSuffix = ownKey.slice(ownRegisteredKey.length)
      const siblingKey = `${ownRegisteredKey.slice(0, componentSeparator)}.${state}${instanceSuffix}`
      const selector = `[data-bml-state-key="${cssEscape(siblingKey)}"]`
      return document.querySelector(selector) ? siblingKey : undefined
    }
  }
  const componentRoot = root.closest<HTMLElement>("[data-bml-component]")
  const component = componentRoot?.dataset.bmlComponent
  const registered = component ? `${component}.${state}` : state
  const candidates = elementsIncluding(componentRoot ?? root, "[data-bml-island][data-bml-state-key]")
  const mounted = candidates.find((candidate) => {
    const key = candidate.dataset.bmlStateKey
    return key === registered || key?.startsWith(`${registered}:`) === true
  })?.dataset.bmlStateKey
  if (mounted) return mounted
  return document.querySelector(`script[data-bml-state-key="${cssEscape(registered)}"]`) ? registered : undefined
}

function observeDocument(): void {
  if (runtimeState.observer || typeof MutationObserver === "undefined" || !document.documentElement) return
  runtimeState.observer = new MutationObserver((records) => {
    for (const record of records) {
      record.addedNodes.forEach((node) => {
        if (node instanceof Element) void mountAll(node).catch(reportMountError)
      })
      record.removedNodes.forEach((node) => {
        if (!(node instanceof Element)) return
        queueMicrotask(() => { if (!node.isConnected) unmountAll(node) })
      })
    }
  })
  runtimeState.observer.observe(document.documentElement, { childList: true, subtree: true })
}

function bindDeferredEvents(): void {
  if (runtimeState.deferredEventsBound || !currentDeferredRuntime()) return
  runtimeState.deferredEventsBound = true
  document.addEventListener(BML_DEFERRED_REFRESH_EVENT, onDeferredRefresh)
  document.addEventListener(BML_IDENTITY_CHANGE_EVENT, onDeferredRefresh)
}

function onDeferredRefresh(event: Event): void {
  const deferred = currentDeferredRuntime()
  if (!deferred) return
  if (event.type === BML_IDENTITY_CHANGE_EVENT) deferred.advanceIdentity()
  const requested = event instanceof CustomEvent
    ? (event.detail as { root?: HTMLElement } | undefined)?.root
    : undefined
  const roots = requested ? [requested] : elementsIncluding(document, "[data-bml-deferred]")
  if (roots.length === 0) return
  roots.forEach(unmountAll)
  void deferred.loadAll(requested ?? document)
    .then(() => mountAll(requested ?? document))
    .catch(reportMountError)
}

function reportMountError(error: unknown): void {
  console.error("bml: client scope failed to mount", error)
}

function unmount(root: Element): void {
  unmounters.get(root)?.forEach((cb) => cb())
  unmounters.delete(root)
  mountedRoles.delete(root)
}

function elementsIncluding(scope: ParentNode, selector: string): HTMLElement[] {
  const elements = Array.from(scope.querySelectorAll<HTMLElement>(selector))
  if (scope instanceof HTMLElement && scope.matches(selector)) elements.unshift(scope)
  return elements
}

function findRef(root: HTMLElement, name: string): Element | null {
  if (root.getAttribute("data-bml-ref") === name) return root
  return root.querySelector(`[data-bml-ref="${cssEscape(name)}"]`)
}

function findRefs(root: HTMLElement, name: string): Element[] {
  const matches = Array.from(root.querySelectorAll(`[data-bml-ref="${cssEscape(name)}"]`))
  if (root.getAttribute("data-bml-ref") === name) matches.unshift(root)
  return matches
}

function cssEscape(value: string): string {
  if (typeof CSS !== "undefined" && typeof CSS.escape === "function") return CSS.escape(value)
  return value.replace(/["\\]/g, "\\$&")
}

function parseProps(raw: string | undefined): Record<string, unknown> {
  if (!raw) return {}
  try {
    return JSON.parse(raw) as Record<string, unknown>
  } catch {
    return {}
  }
}
