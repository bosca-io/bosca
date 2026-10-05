import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { resetSessionReloadRequest } from "../src/deferred-runtime"
import { ACTION_URL, bindActions, clearBmlClientState, dispatchAction, restoreClientState } from "../src/action"
import { advanceDeferredIdentity, enableDeferred } from "../src/deferred"
import { configureBosca } from "../src/graphql"
import { mountAll } from "../src/island"

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>()
  failWrites = false

  get length(): number { return this.values.size }
  clear(): void { this.values.clear() }
  getItem(key: string): string | null { return this.values.get(key) ?? null }
  key(index: number): string | null { return Array.from(this.values.keys())[index] ?? null }
  removeItem(key: string): void { this.values.delete(key) }
  setItem(key: string, value: string): void {
    if (this.failWrites) throw new DOMException("quota", "QuotaExceededError")
    this.values.set(key, value)
  }
}

function memoryStorage(): Storage {
  return new MemoryStorage()
}

function appendCounterComponent(
  scope: "client-session" | "client-local" | null,
  label: string,
): { script: HTMLScriptElement; view: HTMLElement } {
  const script = document.createElement("script")
  script.type = "application/json"
  script.dataset.bmlStateKey = "counterModel"
  if (scope) script.dataset.bmlScope = scope
  script.textContent = '{"count":0}'
  document.body.append(script)

  return { script, view: appendCounterView(label) }
}

function appendCounterView(label: string): HTMLElement {
  const view = document.createElement("div")
  view.dataset.bmlIsland = "counter"
  view.dataset.bmlStateKey = "counterModel"
  view.innerHTML = `<span>${label}</span>`
  document.body.append(view)
  return view
}

const restoreFailureCases: Array<[
  "client-session" | "client-local",
  string,
  () => Promise<Response>,
]> = [
  ["client-session", "network rejection", () => Promise.reject(new TypeError("offline"))],
  ["client-session", "HTTP 503", () => Promise.resolve(new Response("unavailable", { status: 503 }))],
  ["client-local", "network rejection", () => Promise.reject(new TypeError("offline"))],
  ["client-local", "HTTP 503", () => Promise.resolve(new Response("unavailable", { status: 503 }))],
]

/** Build the SSR shape the compiler emits for a live island + its (outside) action button. */
function setup(opts: {
  client?: boolean
  clientScope?: "client-session" | "client-local"
  page?: string
  clearOnSignOut?: boolean
}): void {
  document.body.replaceChildren()
  // Page identity marker (carries the route pattern so the action routes to this page's dispatcher).
  const pageMarker = document.createElement("script")
  pageMarker.type = "application/json"
  pageMarker.setAttribute("data-bml-page", opts.page ?? "/")
  document.body.append(pageMarker)
  // Client scope emits a state script; server scope emits none (the model lives in the cookie session).
  if (opts.client) {
    const script = document.createElement("script")
    script.type = "application/json"
    script.dataset.bmlStateKey = "counterModel"
    if (opts.clientScope) script.dataset.bmlScope = opts.clientScope
    if (opts.clearOnSignOut) script.setAttribute("data-bml-clear-on-sign-out", "")
    script.textContent = '{"count":0}'
    document.body.append(script)
  }
  const island = document.createElement("div")
  island.dataset.bmlIsland = "counter"
  island.dataset.bmlStateKey = "counterModel"
  const span = document.createElement("span")
  span.textContent = "Count: 0"
  island.append(span)
  document.body.append(island)

  const button = document.createElement("button")
  button.dataset.bmlStateKey = "counterModel"
  button.dataset.bmlMethod = "increment"
  button.textContent = "+"
  document.body.append(button) // OUTSIDE the island, on purpose
}

describe("live-island actions", () => {
  beforeEach(() => {
    configureBosca({ getToken: () => "tok", getAnalyticsSessionId: () => "analytics-session", getInstallationId: () => "installation" })
    Object.defineProperty(window, "localStorage", { configurable: true, value: memoryStorage() })
    window.sessionStorage.clear()
    // A mocked reload leaves this jsdom document in place; a real reload would start with a fresh flag.
    resetSessionReloadRequest()
    window.history.replaceState({}, "", "/")
    document.documentElement.removeAttribute("lang")
    document.cookie = "bml_session_reset=; Max-Age=0; Path=/"
    vi.spyOn(window.location, "reload").mockImplementation(() => undefined)
  })
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it("client scope: posts page+state (no session id on the wire), writes state back, swaps the view", async () => {
    setup({ client: true })
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)

    await dispatchAction("counterModel", "increment")

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(ACTION_URL)
    expect(init.method).toBe("POST")
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok")
    expect((init.headers as Record<string, string>)["X-BA-Session-ID"]).toBe("analytics-session")
    expect((init.headers as Record<string, string>)["X-Installation-ID"]).toBe("installation")
    // the bml_session cookie is sent automatically for server scope
    expect(init.credentials).toBe("same-origin")
    // page + stateKey disambiguate which page's dispatcher; NO sessionId on the wire (cookie carries it).
    expect(JSON.parse(String(init.body))).toEqual({
      page: "/",
      path: "/",
      query: {},
      locale: null,
      stateKey: "counterModel",
      method: "increment",
      state: '{"count":0}',
      args: [],
      renderView: true,
    })

    // returned client state is written back into the script (the source of truth)
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')
    expect(script?.textContent).toBe('{"count":1}')
    // the view island's children were swapped
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 1")
  })

  it("posts bigint action arguments as exact JSON numbers", async () => {
    setup({ client: true })
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)

    await dispatchAction("counterModel", "select", [9007199254740993n])

    const body = String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body)
    expect(body).toContain('"args":[9007199254740993]')
  })

  it("scopes the action to the current page's route", async () => {
    setup({ client: true, page: "/lists/{id}" })
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>x</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)

    await dispatchAction("counterModel", "increment")

    expect(JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body)).page).toBe("/lists/{id}")
  })

  it("posts the server-selected path for an alternate response page", async () => {
    setup({ client: true, page: "/not-found" })
    const marker = document.querySelector<HTMLScriptElement>("script[data-bml-page]")!
    marker.dataset.bmlPageCanonical = ""
    marker.dataset.bmlPagePath = "/not-found"
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)

    await dispatchAction("counterModel", "increment")

    const body = JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body))
    expect(body.path).toBe("/not-found")
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 1")
  })

  it("posts the current query and server-selected locale", async () => {
    window.history.replaceState({}, "", "/items?filter=open&filter=closed")
    setup({ client: true, page: "/items" })
    const marker = document.querySelector<HTMLScriptElement>("script[data-bml-page]")!
    marker.dataset.bmlPageCanonical = ""
    marker.dataset.bmlPagePath = "/items"
    marker.dataset.bmlPageLocale = "fr"
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)

    await dispatchAction("counterModel", "increment")

    const body = JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body))
    expect(body.query).toEqual({ filter: "open" })
    expect(body.locale).toBe("fr")
  })

  it("does not apply an action response rendered for an obsolete query", async () => {
    window.history.replaceState({}, "", "/items?filter=open")
    setup({ client: true, page: "/items" })
    let release!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { release = resolve }))
    vi.stubGlobal("fetch", fetchMock)

    const action = dispatchAction("counterModel", "increment")
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    window.history.replaceState({}, "", "/items?filter=closed")
    release(new Response(
      JSON.stringify({ state: '{"count":1}', html: "<span>Count: stale</span>" }),
      { status: 200 },
    ))
    await action

    const body = JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body))
    expect(body.query).toEqual({ filter: "open" })
    expect(document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')?.textContent)
      .toBe('{"count":0}')
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 0")
  })

  it("re-renders a site-scoped view for the current path after navigation", async () => {
    window.history.replaceState({}, "", "/first")
    setup({ client: true, clientScope: "client-local", page: "/first" })
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    script.setAttribute("data-bml-site", "")
    let release!: (response: Response) => void
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { release = resolve }))
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ state: '{"count":1}', html: "<span>Count: current</span>" }),
        { status: 200 },
      ))
    vi.stubGlobal("fetch", fetchMock)

    const action = dispatchAction("counterModel", "increment")
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    window.history.replaceState({}, "", "/second")
    document.querySelector<HTMLScriptElement>("script[data-bml-page]")!.dataset.bmlPage = "/second"
    release(new Response(
      JSON.stringify({ state: '{"count":1}', html: "<span>Count: stale</span>" }),
      { status: 200 },
    ))
    await action

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(JSON.parse(String((fetchMock.mock.calls[1] as [string, RequestInit])[1].body))).toMatchObject({
      page: "/second",
      path: "/second",
      method: "",
    })
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: current")
  })

  it("server scope: no client state script, posts empty state, swaps the view", async () => {
    setup({}) // no client state script → server scope (model held in the cookie session)
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: null, html: "<span>Count: 1</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)

    await dispatchAction("counterModel", "increment")

    const body = JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body))
    expect(body).toEqual({
      page: "/", path: "/", query: {}, locale: null,
      stateKey: "counterModel", method: "increment", state: "", args: [], renderView: true,
    })
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 1")
  })

  it("bindActions binds a real click on the data-bml-method element", async () => {
    setup({ client: true })
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)
    bindActions()

    document.querySelector<HTMLButtonElement>("button[data-bml-method]")!.click()

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect((fetchMock.mock.calls[0] as [string])[0]).toBe(ACTION_URL)
  })

  it("an action element inside the swapped view is re-bound and clickable", async () => {
    setup({ client: true })
    // the re-rendered view carries its own action button (the common live-island shape)
    const swappedHtml = '<button data-bml-state-key="counterModel" data-bml-method="increment">+</button>'
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":1}', html: swappedHtml }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)
    bindActions()

    document.querySelector<HTMLButtonElement>("button[data-bml-method]")!.click()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    await vi.waitFor(() =>
      expect(document.querySelector("[data-bml-island] button[data-bml-method]")).not.toBeNull(),
    )

    // the button that arrived with the swap must itself be live
    document.querySelector<HTMLButtonElement>("[data-bml-island] button[data-bml-method]")!.click()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
  })

  it("retains keyed DOM across an action update while refreshing root attributes", async () => {
    setup({ client: true })
    const view = document.querySelector<HTMLElement>("[data-bml-island]")!
    const card = document.createElement("article")
    card.setAttribute("data-bml-preserve", "item-1")
    card.setAttribute("data-version", "1")
    const image = document.createElement("img")
    image.src = "/one.jpg"
    card.append(image)
    view.replaceChildren(card)

    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        new Response(
          JSON.stringify({
            state: '{"count":1}',
            html: '<article data-bml-preserve="item-1" data-version="2"><img src="/changed.jpg"></article><article data-bml-preserve="item-2">new</article>',
          }),
          { status: 200 },
        ),
      ),
    )

    await dispatchAction("counterModel", "increment")

    expect(view.querySelector('[data-bml-preserve="item-1"]')).toBe(card)
    expect(card.querySelector("img")).toBe(image)
    expect(image.getAttribute("src")).toBe("/one.jpg")
    expect(card.dataset.version).toBe("2")
    expect(card.hasAttribute("data-bml-retained")).toBe(true)
    const added = view.querySelector('[data-bml-preserve="item-2"]')
    expect(added?.textContent).toBe("new")
    expect(added?.hasAttribute("data-bml-added")).toBe(true)
  })

  it("keeps focus on a preserved control without scrolling the update", async () => {
    setup({ client: true })
    const view = document.querySelector<HTMLElement>("[data-bml-island]")!
    const control = document.createElement("button")
    control.setAttribute("data-bml-preserve", "load-more-control")
    control.dataset.offset = "0"
    view.replaceChildren(control)
    control.focus()

    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        new Response(
          JSON.stringify({
            state: '{"count":1}',
            html: '<p>new row</p><button data-bml-preserve="load-more-control" data-offset="1">more</button>',
          }),
          { status: 200 },
        ),
      ),
    )

    await dispatchAction("counterModel", "increment")

    expect(view.querySelector('[data-bml-preserve="load-more-control"]')).toBe(control)
    expect(control.dataset.offset).toBe("1")
    expect(document.activeElement).toBe(control)
  })

  it("marks a declarative action button busy and disables it while the action is pending", async () => {
    setup({ client: true })
    let release!: (response: Response) => void
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((resolve) => { release = resolve })))
    bindActions()

    const button = document.querySelector<HTMLButtonElement>("button[data-bml-method]")!
    button.click()
    expect(button.disabled).toBe(true)
    expect(button.getAttribute("aria-busy")).toBe("true")

    await vi.waitFor(() => expect(release).toBeTypeOf("function"))
    release(new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }))
    await vi.waitFor(() => expect(button.disabled).toBe(false))
    expect(button.hasAttribute("aria-busy")).toBe(false)
  })

  it("throws on a non-2xx response and leaves state + sessionStorage untouched", async () => {
    setup({ client: true })
    vi.stubGlobal("fetch", vi.fn(async () => new Response("nope", { status: 500 })))
    await expect(dispatchAction("counterModel", "increment")).rejects.toThrow("HTTP 500")
    expect(document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')?.textContent).toBe('{"count":0}')
    expect(sessionStorage.getItem("bml:/:counterModel")).toBeNull()
  })

  it("reloads the page when the server replaces an expired session", async () => {
    setup({})
    vi.stubGlobal("fetch", vi.fn(async () => new Response("session expired", { status: 410 })))

    await dispatchAction("counterModel", "increment")

    expect(window.location.reload).toHaveBeenCalledOnce()
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 0")
  })

  it("reports an action error instead of reloading again when the session cookie does not persist", async () => {
    setup({})
    vi.stubGlobal("fetch", vi.fn(async () => new Response("session expired", { status: 410 })))

    await dispatchAction("counterModel", "increment")
    expect(window.location.reload).toHaveBeenCalledOnce()

    // The reload produced a new page whose action again finds no session.
    resetSessionReloadRequest()
    await expect(dispatchAction("counterModel", "increment")).rejects.toThrow("did not keep the page session")
    expect(window.location.reload).toHaveBeenCalledOnce()
  })

  it("sends a keepalive action without waiting for a pending identity bootstrap", async () => {
    setup({ client: true })
    configureBosca({ getInstallationId: undefined, getAnalyticsSessionId: undefined })
    document.cookie = "bml_asid=; Max-Age=0; Path=/"
    const marker = document.createElement("script")
    marker.type = "application/json"
    marker.dataset.bmlSharedIdentity = ""
    document.body.append(marker)
    const calls: string[] = []
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      calls.push(url)
      // The identity bootstrap never settles while the page unloads.
      if (url === "/_bml/identity") return new Promise<Response>(() => {})
      return Promise.resolve(new Response(JSON.stringify({ state: '{"count":1}', html: null }), { status: 200 }))
    }))
    enableDeferred()
    try {
      await vi.waitFor(() => expect(calls).toContain("/_bml/identity"))

      await dispatchAction("counterModel", "save", [1], { keepalive: true })

      expect(calls).toContain("/_bml/action")
    } finally {
      // Drop the never-settling bootstrap so later tests do not wait on it.
      marker.remove()
      advanceDeferredIdentity()
    }
  })

  it("drops clicks for a key while its action is still in flight", async () => {
    setup({ client: true })
    let release!: (r: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { release = resolve }))
    vi.stubGlobal("fetch", fetchMock)
    bindActions()

    const button = document.querySelector<HTMLButtonElement>("button[data-bml-method]")!
    button.click()
    button.click()
    button.click()

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    release(new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }))
    await vi.waitFor(() =>
      expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 1"),
    )
    // the two rapid re-clicks were dropped, not queued or raced
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it("serializes direct dispatchAction calls per key (second POST waits for the first response)", async () => {
    setup({ client: true })
    const releases: Array<(r: Response) => void> = []
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { releases.push(resolve) }))
    vi.stubGlobal("fetch", fetchMock)

    const first = dispatchAction("counterModel", "increment")
    const second = dispatchAction("counterModel", "increment")
    await Promise.resolve()
    expect(fetchMock).toHaveBeenCalledTimes(1)

    releases[0]!(new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }))
    await first
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    // the queued call reads the state the first call wrote — ordered, not raced
    expect(JSON.parse(String((fetchMock.mock.calls[1] as unknown as [string, RequestInit])[1].body)).state).toBe('{"count":1}')

    releases[1]!(new Response(JSON.stringify({ state: '{"count":2}', html: "<span>Count: 2</span>" }), { status: 200 }))
    await second
    expect(document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')?.textContent).toBe('{"count":2}')
  })

  it("a failed action does not block the next one for the same key", async () => {
    setup({ client: true })
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response("nope", { status: 500 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }))
    vi.stubGlobal("fetch", fetchMock)

    await expect(dispatchAction("counterModel", "increment")).rejects.toThrow("HTTP 500")
    await dispatchAction("counterModel", "increment")
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 1")
  })

  it("client-session scope mirrors state to sessionStorage", async () => {
    setup({ client: true, clientScope: "client-session" })
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 })),
    )
    await dispatchAction("counterModel", "increment")
    expect(sessionStorage.getItem("bml:/:counterModel")).toBe('{"count":1}')
    expect(window.localStorage.getItem("bml:/:counterModel")).toBeNull()
  })

  it("client-local scope mirrors state to localStorage", async () => {
    setup({ client: true, clientScope: "client-local" })
    sessionStorage.setItem("bml:/:counterModel", '{"count":99}')
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 })),
    )
    await dispatchAction("counterModel", "increment")
    expect(window.localStorage.getItem("bml:/:counterModel")).toBe('{"count":1}')
    expect(sessionStorage.getItem("bml:/:counterModel")).toBeNull()
  })

  it("removes an older local snapshot when replacing it exceeds browser quota", async () => {
    setup({ client: true, clientScope: "client-local" })
    const local = new MemoryStorage()
    local.setItem("bml:/:counterModel", '{"count":7}')
    local.failWrites = true
    Object.defineProperty(window, "localStorage", { configurable: true, value: local })
    const warning = vi.spyOn(console, "warn").mockImplementation(() => undefined)
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ state: '{"count":8}', html: "<span>Count: 8</span>" }), { status: 200 })),
    )

    await dispatchAction("counterModel", "increment")

    expect(local.getItem("bml:/:counterModel")).toBeNull()
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 8")
    expect(warning).toHaveBeenCalledOnce()
  })

  it("page scope does not persist client state", async () => {
    setup({ client: true })
    sessionStorage.setItem("bml:/:counterModel", '{"count":99}')
    window.localStorage.setItem("bml:/:counterModel", '{"count":98}')
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 })),
    )

    await dispatchAction("counterModel", "increment")

    expect(sessionStorage.getItem("bml:/:counterModel")).toBeNull()
    expect(window.localStorage.getItem("bml:/:counterModel")).toBeNull()
  })

  it("storage keys use the concrete pathname, never the route pattern", async () => {
    // Every step of every guide shares the pattern `/read/{id}/step/{step}` — a pattern-scoped
    // key would restore one step's saved state (e.g. completed) onto every other step's island.
    setup({ client: true, clientScope: "client-session", page: "/read/{id}/step/{step}" })
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>x</span>" }), { status: 200 })),
    )
    await dispatchAction("counterModel", "increment")
    // the POST still routes by the pattern; storage is keyed by location.pathname ("/" in tests)
    expect(sessionStorage.getItem("bml:/:counterModel")).toBe('{"count":1}')
    expect(sessionStorage.getItem("bml:/read/{id}/step/{step}:counterModel")).toBeNull()
  })

  it("@submit binds the form's submit event and resolves form-field sentinels into args", async () => {
    setup({ client: true })
    // The compiler lowers `@submit="wall.share(form.title, 42, ctx)"` to these markers.
    const form = document.createElement("form")
    form.dataset.bmlStateKey = "counterModel"
    form.dataset.bmlMethod = "share"
    form.dataset.bmlEvent = "submit"
    form.dataset.bmlArgs = '[{"__bmlField":"title"},42]'
    const input = document.createElement("input")
    input.name = "title"
    input.value = "hello"
    const check = document.createElement("input")
    check.type = "checkbox"
    check.name = "notify"
    check.checked = true
    form.append(input, check)
    document.body.append(form)

    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)
    bindActions()
    form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }))
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled())

    const body = JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body))
    // the sentinel resolved to the field's value; the render-time literal passed through verbatim
    expect(body.args).toEqual(["hello", 42])
    expect(body.method).toBe("share")
  })

  it("binds arbitrary DOM events emitted by the compiler", async () => {
    setup({ client: true })
    const input = document.createElement("input")
    input.dataset.bmlStateKey = "counterModel"
    input.dataset.bmlMethod = "change"
    input.dataset.bmlEvent = "input"
    document.body.append(input)
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>x</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)
    bindActions()

    input.dispatchEvent(new Event("input", { bubbles: true }))

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect(JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body)).method).toBe("change")
  })

  it("debounces an action and sends only its latest arguments", async () => {
    setup({ client: true })
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>x</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)

    const first = dispatchAction("counterModel", "save", [1], { debounceMs: 20, scheduleKey: "debounce-test" })
    const latest = dispatchAction("counterModel", "save", [2], { debounceMs: 20, scheduleKey: "debounce-test" })
    await Promise.all([first, latest])

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body)).args).toEqual([2])
  })

  it("coalesces calls made during a request into one follow-up with the latest arguments", async () => {
    setup({ client: true })
    const releases: Array<(response: Response) => void> = []
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { releases.push(resolve) }))
    vi.stubGlobal("fetch", fetchMock)

    const active = dispatchAction("counterModel", "save", [0])
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    const pending = dispatchAction("counterModel", "save", [1], { coalesce: true, scheduleKey: "coalesce-test" })
    const latest = dispatchAction("counterModel", "save", [2], { coalesce: true, scheduleKey: "coalesce-test" })
    releases[0]!(new Response(JSON.stringify({ state: '{"count":1}', html: "<span>x</span>" }), { status: 200 }))
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(JSON.parse(String((fetchMock.mock.calls[1] as unknown as [string, RequestInit])[1].body)).args).toEqual([2])
    releases[1]!(new Response(JSON.stringify({ state: '{"count":2}', html: "<span>x</span>" }), { status: 200 }))

    await Promise.all([active, pending, latest])
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it("flushes a delayed action with fetch keepalive on pagehide", async () => {
    setup({ client: true })
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>x</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)

    const pending = dispatchAction("counterModel", "save", [42], {
      debounceMs: 60_000,
      flushOnPageHide: true,
      scheduleKey: "pagehide-test",
    })
    window.dispatchEvent(new Event("pagehide"))
    await pending

    const init = (fetchMock.mock.calls[0] as [string, RequestInit])[1]
    expect(init.keepalive).toBe(true)
    expect(JSON.parse(String(init.body)).args).toEqual([42])
  })

  it("checkbox form-field sentinels resolve to booleans", async () => {
    setup({ client: true })
    const form = document.createElement("form")
    form.dataset.bmlStateKey = "counterModel"
    form.dataset.bmlMethod = "share"
    form.dataset.bmlEvent = "submit"
    form.dataset.bmlArgs = '[{"__bmlField":"notify"}]'
    const check = document.createElement("input")
    check.type = "checkbox"
    check.name = "notify"
    form.append(check)
    document.body.append(form)

    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":1}', html: "<span>x</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)
    bindActions()
    form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }))
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled())

    const body = JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body))
    expect(body.args).toEqual([false]) // unchecked checkbox → false, not ""
  })

  it("restoreClientState re-applies saved state and re-renders the view (empty method, no mutation)", async () => {
    setup({ client: true, clientScope: "client-session" })
    sessionStorage.setItem("bml:/:counterModel", '{"count":9}') // as if persisted before a refresh
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":9}', html: "<span>Count: 9</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)

    await restoreClientState()

    // re-rendered via the action path with an EMPTY method (no mutation) carrying the restored state
    const body = JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body))
    expect(body).toEqual({
      page: "/", path: "/", query: {}, locale: null,
      stateKey: "counterModel", method: "", state: '{"count":9}', args: [], renderView: true,
    })
    // the state script holds the restored value and the view reflects it
    expect(document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')?.textContent).toBe('{"count":9}')
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 9")
  })

  it("restores state through the server-selected alternate-page path", async () => {
    setup({ client: true, clientScope: "client-session", page: "/not-found" })
    const marker = document.querySelector<HTMLScriptElement>("script[data-bml-page]")!
    marker.dataset.bmlPageCanonical = ""
    marker.dataset.bmlPagePath = "/not-found"
    sessionStorage.setItem("bml:/:counterModel", '{"count":9}')
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":9}', html: "<span>Count: 9</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)

    await restoreClientState()

    const body = JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body))
    expect(body.path).toBe("/not-found")
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 9")
  })

  it("restoreClientState restores persistent local state after a new page render", async () => {
    setup({ client: true, clientScope: "client-local" })
    window.localStorage.setItem("bml:/:counterModel", '{"count":12}')
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":12}', html: "<span>Count: 12</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)

    await restoreClientState()

    const body = JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body))
    expect(body.state).toBe('{"count":12}')
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 12")
  })

  it.each(restoreFailureCases)(
    "restoreClientState retains valid %s state after %s",
    async (clientScope, _name, request) => {
      setup({ client: true, clientScope })
      const store = clientScope === "client-local" ? window.localStorage : window.sessionStorage
      const otherStore = clientScope === "client-local" ? window.sessionStorage : window.localStorage
      store.setItem("bml:/:counterModel", '{"count":12}')
      otherStore.setItem("bml:/:unrelated", "keep")
      vi.stubGlobal("fetch", vi.fn(request))

      await restoreClientState()

      expect(store.getItem("bml:/:counterModel")).toBe('{"count":12}')
      expect(otherStore.getItem("bml:/:unrelated")).toBe("keep")
      expect(document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')?.textContent)
        .toBe('{"count":0}')
    },
  )

  it.each(["client-session", "client-local"] as const)(
    "an action uses retained %s state after a transient restore failure",
    async (clientScope) => {
      setup({ client: true, clientScope })
      const store = clientScope === "client-local" ? window.localStorage : window.sessionStorage
      store.setItem("bml:/:counterModel", '{"count":12}')
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(new Response("unavailable", { status: 503 }))
        .mockResolvedValueOnce(
          new Response(JSON.stringify({ state: '{"count":13}', html: "<span>Count: 13</span>" }), { status: 200 }),
        )
      vi.stubGlobal("fetch", fetchMock)

      await restoreClientState()
      await dispatchAction("counterModel", "increment")

      const actionBody = JSON.parse(String((fetchMock.mock.calls[1] as [string, RequestInit])[1].body))
      expect(actionBody).toMatchObject({ method: "increment", state: '{"count":12}' })
      expect(store.getItem("bml:/:counterModel")).toBe('{"count":13}')
    },
  )

  it.each(["client-session", "client-local"] as const)(
    "restoreClientState retries transient %s failure and becomes idempotent after success",
    async (clientScope) => {
      setup({ client: true, clientScope })
      const store = clientScope === "client-local" ? window.localStorage : window.sessionStorage
      store.setItem("bml:/:counterModel", '{"count":12}')
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(new Response("unavailable", { status: 503 }))
        .mockResolvedValueOnce(
          new Response(JSON.stringify({ state: '{"count":12}', html: "<span>Count: 12</span>" }), { status: 200 }),
        )
      vi.stubGlobal("fetch", fetchMock)

      await restoreClientState()
      expect(fetchMock).toHaveBeenCalledOnce()
      expect(store.getItem("bml:/:counterModel")).toBe('{"count":12}')
      expect(document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')?.textContent)
        .toBe('{"count":0}')

      await restoreClientState()
      expect(fetchMock).toHaveBeenCalledTimes(2)
      expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 12")

      await restoreClientState()
      expect(fetchMock).toHaveBeenCalledTimes(2)
    },
  )

  it("restoreClientState sends no request when there is no persisted state", async () => {
    setup({ client: true, clientScope: "client-local" })
    const fetchMock = vi.fn()
    vi.stubGlobal("fetch", fetchMock)

    await restoreClientState()

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it("site state waits for a view, then restores from one cross-page storage key", async () => {
    setup({ client: true, clientScope: "client-local" })
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    script.setAttribute("data-bml-site", "")
    document.querySelector("[data-bml-island]")!.remove()
    window.localStorage.setItem("bml:site:counterModel", '{"count":3}')
    const fetchMock = vi.fn(async () => new Response(
      JSON.stringify({ state: '{"count":3}', html: "<span>Count: 3</span>" }),
      { status: 200 },
    ))
    vi.stubGlobal("fetch", fetchMock)

    await restoreClientState()
    expect(fetchMock).not.toHaveBeenCalled()

    const view = appendCounterView("Count: 0")
    await restoreClientState(view)

    expect(fetchMock).toHaveBeenCalledOnce()
    const body = JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body))
    expect(body).toMatchObject({ state: '{"count":3}', renderView: true })
    expect(view.textContent).toBe("Count: 3")
  })

  it("a headless site sync consumes saved state and clears storage when the model resets", async () => {
    setup({ client: true, clientScope: "client-local" })
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    script.setAttribute("data-bml-site", "")
    document.querySelector("[data-bml-island]")!.remove()
    window.localStorage.setItem("bml:site:counterModel", '{"count":3}')
    const fetchMock = vi.fn(async () => new Response(
      JSON.stringify({ state: '{"count":0}', html: null }),
      { status: 200 },
    ))
    vi.stubGlobal("fetch", fetchMock)

    await dispatchAction("counterModel", "sync")

    const body = JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body))
    expect(body).toMatchObject({ method: "sync", state: '{"count":3}', renderView: false })
    expect(window.localStorage.getItem("bml:site:counterModel")).toBeNull()
    expect(script.textContent).toBe('{"count":0}')
  })

  it("site state marked for sign-out uses the selectively cleared storage key", async () => {
    setup({ client: true, clientScope: "client-local", clearOnSignOut: true })
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    script.setAttribute("data-bml-site", "")
    vi.stubGlobal("fetch", vi.fn(async () => new Response(
      JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }),
      { status: 200 },
    )))

    await dispatchAction("counterModel", "increment")
    expect(window.localStorage.getItem("bml:clear:site:counterModel")).toBe('{"count":1}')

    clearBmlClientState({ reload: false })
    expect(window.localStorage.getItem("bml:clear:site:counterModel")).toBeNull()
    expect(script.textContent).toBe("")
    expect(document.querySelector("[data-bml-island]")?.textContent).toBe("")
    expect(window.location.reload).not.toHaveBeenCalled()
  })

  it("site state renders each replacement view from the canonical state", async () => {
    setup({ client: true, clientScope: "client-local" })
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    script.setAttribute("data-bml-site", "")
    window.localStorage.setItem("bml:site:counterModel", '{"count":3}')
    const fetchMock = vi.fn(async () => new Response(
      JSON.stringify({ state: '{"count":3}', html: "<span>Count: 3</span>" }),
      { status: 200 },
    ))
    vi.stubGlobal("fetch", fetchMock)

    await restoreClientState()
    document.querySelector<HTMLElement>("[data-bml-island]")!.remove()
    const replacement = appendCounterView("Count: 0")
    await restoreClientState(replacement)

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(replacement.textContent).toBe("Count: 3")
  })

  it("a view mounted after a headless site action renders the action state", async () => {
    setup({ client: true, clientScope: "client-local" })
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    script.setAttribute("data-bml-site", "")
    document.querySelector("[data-bml-island]")!.remove()
    window.localStorage.setItem("bml:site:counterModel", '{"count":3}')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ state: '{"count":4}', html: null }), { status: 200 }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ state: '{"count":4}', html: "<span>Count: 4</span>" }), { status: 200 }),
      )
    vi.stubGlobal("fetch", fetchMock)

    await dispatchAction("counterModel", "increment")
    const view = appendCounterView("Count: 0")
    await restoreClientState(view)

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(JSON.parse(String((fetchMock.mock.calls[1] as [string, RequestInit])[1].body))).toMatchObject({
      method: "",
      state: '{"count":4}',
      renderView: true,
    })
    expect(view.textContent).toBe("Count: 4")
  })

  it("a view mounted while a headless site action is in flight renders the action state", async () => {
    setup({ client: true, clientScope: "client-local" })
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    script.setAttribute("data-bml-site", "")
    document.querySelector("[data-bml-island]")!.remove()
    window.localStorage.setItem("bml:site:counterModel", '{"count":0}')
    let releaseAction!: (response: Response) => void
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { releaseAction = resolve }))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ state: '{"count":4}', html: "<span>Count: 4</span>" }), { status: 200 }),
      )
    vi.stubGlobal("fetch", fetchMock)

    const action = dispatchAction("counterModel", "increment")
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    expect(JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body))).toMatchObject({
      renderView: false,
    })

    const view = appendCounterView("Count: 0")
    const restore = restoreClientState(view)
    releaseAction(new Response(JSON.stringify({ state: '{"count":4}', html: null }), { status: 200 }))
    await Promise.all([action, restore])

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(JSON.parse(String((fetchMock.mock.calls[1] as [string, RequestInit])[1].body))).toMatchObject({
      method: "",
      state: '{"count":4}',
      renderView: true,
    })
    expect(script.textContent).toBe('{"count":4}')
    expect(view.textContent).toBe("Count: 4")
  })

  it("a server-session site view mounted during a headless action renders the stored state", async () => {
    setup({ client: true })
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    script.setAttribute("data-bml-site", "")
    script.setAttribute("data-bml-server", "")
    script.textContent = ""
    document.querySelector("[data-bml-island]")!.remove()
    let releaseAction!: (response: Response) => void
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { releaseAction = resolve }))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ state: null, html: "<span>Count: 4</span>" }), { status: 200 }),
      )
    vi.stubGlobal("fetch", fetchMock)

    const action = dispatchAction("counterModel", "increment")
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    const view = appendCounterView("Count: 0")
    const restore = restoreClientState(view)
    releaseAction(new Response(JSON.stringify({ state: null, html: null }), { status: 200 }))
    await Promise.all([action, restore])

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(JSON.parse(String((fetchMock.mock.calls[1] as [string, RequestInit])[1].body))).toMatchObject({
      method: "",
      state: "",
      renderView: true,
    })
    expect(view.textContent).toBe("Count: 4")
  })

  it("reloads when the current server-session site view sync returns 410", async () => {
    setup({ client: true })
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    script.setAttribute("data-bml-site", "")
    script.setAttribute("data-bml-server", "")
    script.textContent = ""
    document.querySelector("[data-bml-island]")!.remove()
    let releaseAction!: (response: Response) => void
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { releaseAction = resolve }))
      .mockResolvedValueOnce(new Response("session expired", { status: 410 }))
    vi.stubGlobal("fetch", fetchMock)
    const reload = vi.spyOn(window.location, "reload").mockImplementation(() => undefined)

    const action = dispatchAction("counterModel", "increment")
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    const view = appendCounterView("Count: 0")
    const restore = restoreClientState(view)
    releaseAction(new Response(JSON.stringify({ state: null, html: null }), { status: 200 }))
    await Promise.all([action, restore])

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(reload).toHaveBeenCalledOnce()
    expect(view.textContent).toBe("Count: 0")
  })

  it("rejects duplicate connected views for one state key", async () => {
    setup({ client: true, clientScope: "client-local" })
    appendCounterView("duplicate")

    await expect(restoreClientState()).rejects.toThrow("multiple live views")
  })

  it.each(["client-session", "client-local"] as const)(
    "restoreClientState immediately skips equal %s state without joining the action queue",
    async (clientScope) => {
      setup({ client: true, clientScope })
      const store = clientScope === "client-local" ? window.localStorage : window.sessionStorage
      store.setItem("bml:/:counterModel", '{"count":0}')
      let releaseAction!: (response: Response) => void
      const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { releaseAction = resolve }))
      vi.stubGlobal("fetch", fetchMock)

      const action = dispatchAction("counterModel", "increment")
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
      let restoreSettled = false
      const restore = restoreClientState().then(() => { restoreSettled = true })
      await new Promise((resolve) => setTimeout(resolve, 0))

      expect(restoreSettled).toBe(true)
      expect(fetchMock).toHaveBeenCalledOnce()

      releaseAction(new Response(
        JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }),
        { status: 200 },
      ))
      await Promise.all([action, restore])
    },
  )

  it.each(["client-session", "client-local"] as const)(
    "an obsolete queued %s restore skips its request and a later action uses canonical state",
    async (clientScope) => {
      setup({ client: true, clientScope })
      const store = clientScope === "client-local" ? window.localStorage : window.sessionStorage
      store.setItem("bml:/:counterModel", '{"count":12}')
      let requestCount = 0
      let releaseAction!: (response: Response) => void
      let releaseLater!: (response: Response) => void
      const fetchMock = vi.fn(() => {
        requestCount++
        if (requestCount === 1) return Promise.resolve(new Response("unavailable", { status: 503 }))
        if (requestCount === 2) {
          return new Promise<Response>((resolve) => { releaseAction = resolve })
        }
        return new Promise<Response>((resolve) => { releaseLater = resolve })
      })
      vi.stubGlobal("fetch", fetchMock)

      await restoreClientState() // transient failure leaves saved state 12 retryable
      const action = dispatchAction("counterModel", "increment")
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
      const retry = restoreClientState() // queues behind the active action
      const later = dispatchAction("counterModel", "increment") // queues behind the obsolete retry
      expect(fetchMock).toHaveBeenCalledTimes(2)

      releaseAction(new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }))
      await action
      await retry
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))

      const laterBody = JSON.parse(String((fetchMock.mock.calls[2] as [string, RequestInit])[1].body))
      expect(laterBody).toMatchObject({ method: "increment", state: '{"count":1}' })
      expect(store.getItem("bml:/:counterModel")).toBe('{"count":1}')

      releaseLater(new Response(JSON.stringify({ state: '{"count":2}', html: "<span>Count: 2</span>" }), { status: 200 }))
      await later
      expect(store.getItem("bml:/:counterModel")).toBe('{"count":2}')
      expect(document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')?.textContent)
        .toBe('{"count":2}')
      expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 2")
    },
  )

  it.each(["client-session", "client-local"] as const)(
    "a queued %s restore skips when its script is replaced and the replacement restores current storage",
    async (clientScope) => {
      setup({ client: true, clientScope })
      const store = clientScope === "client-local" ? window.localStorage : window.sessionStorage
      store.setItem("bml:/:counterModel", '{"count":12}')
      let requestCount = 0
      let releaseAction!: (response: Response) => void
      const fetchMock = vi.fn((_url: string | URL | Request, init?: RequestInit) => {
        requestCount++
        if (requestCount === 1) return Promise.resolve(new Response("unavailable", { status: 503 }))
        if (requestCount === 2) {
          return new Promise<Response>((resolve) => { releaseAction = resolve })
        }
        const requestState = JSON.parse(String(init?.body)).state as string
        return Promise.resolve(
          new Response(JSON.stringify({ state: requestState, html: "<span>Count: 1</span>" }), { status: 200 }),
        )
      })
      vi.stubGlobal("fetch", fetchMock)

      await restoreClientState()
      const originalScript = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
      const originalView = document.querySelector<HTMLElement>('[data-bml-island][data-bml-state-key="counterModel"]')!
      const action = dispatchAction("counterModel", "increment")
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
      const retry = restoreClientState()

      originalScript.remove()
      originalView.remove()
      const replacementScript = document.createElement("script")
      replacementScript.type = "application/json"
      replacementScript.dataset.bmlStateKey = "counterModel"
      replacementScript.dataset.bmlScope = clientScope
      replacementScript.textContent = '{"count":0}'
      document.body.append(replacementScript)
      const replacementView = document.createElement("div")
      replacementView.dataset.bmlIsland = "counter"
      replacementView.dataset.bmlStateKey = "counterModel"
      replacementView.innerHTML = "<span>Count: 0</span>"
      document.body.append(replacementView)
      const replacementRestore = restoreClientState(replacementScript)
      expect(fetchMock).toHaveBeenCalledTimes(2)

      releaseAction(new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }))
      await action
      await retry
      await replacementRestore

      expect(fetchMock).toHaveBeenCalledTimes(3)
      const replacementBody = JSON.parse(String((fetchMock.mock.calls[2] as [string, RequestInit])[1].body))
      expect(replacementBody).toMatchObject({ method: "", state: '{"count":12}' })
      expect(originalScript.textContent).toBe('{"count":0}')
      expect(store.getItem("bml:/:counterModel")).toBe('{"count":12}')
      expect(replacementScript.textContent).toBe('{"count":12}')
      expect(replacementView.textContent).toBe("Count: 1")
    },
  )

  it.each(["client-session", "client-local"] as const)(
    "an in-flight %s restore cannot update a replacement component",
    async (clientScope) => {
      setup({ client: true, clientScope })
      const store = clientScope === "client-local" ? window.localStorage : window.sessionStorage
      store.setItem("bml:/:counterModel", '{"count":12}')
      const releases: Array<(response: Response) => void> = []
      const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { releases.push(resolve) }))
      vi.stubGlobal("fetch", fetchMock)

      const originalScript = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
      const originalView = document.querySelector<HTMLElement>('[data-bml-island][data-bml-state-key="counterModel"]')!
      const originalRestore = restoreClientState(originalScript)
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())

      originalScript.remove()
      originalView.remove()
      const replacement = appendCounterComponent(clientScope, "Count: replacement")
      let replacementUpdates = 0
      replacement.view.addEventListener("bml:update", () => { replacementUpdates++ })
      const replacementRestore = restoreClientState(replacement.script)

      releases[0]!(new Response(
        JSON.stringify({ state: '{"count":13}', html: "<span>Count: stale restore</span>" }),
        { status: 200 },
      ))
      await originalRestore
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))

      expect(replacement.script.textContent).toBe('{"count":0}')
      expect(replacement.view.textContent).toBe("Count: replacement")
      expect(replacementUpdates).toBe(0)
      expect(store.getItem("bml:/:counterModel")).toBe('{"count":12}')
      expect(JSON.parse(String((fetchMock.mock.calls[1] as [string, RequestInit])[1].body)).state)
        .toBe('{"count":12}')

      releases[1]!(new Response(
        JSON.stringify({ state: '{"count":12}', html: "<span>Count: 12</span>" }),
        { status: 200 },
      ))
      await replacementRestore
      expect(replacement.script.textContent).toBe('{"count":12}')
      expect(replacement.view.textContent).toBe("Count: 12")
      expect(replacementUpdates).toBe(1)
    },
  )

  it.each(["client-session", "client-local"] as const)(
    "an in-flight %s restore cannot update a replacement view that retains its state script",
    async (clientScope) => {
      setup({ client: true, clientScope })
      const store = clientScope === "client-local" ? window.localStorage : window.sessionStorage
      store.setItem("bml:/:counterModel", '{"count":12}')
      const releases: Array<(response: Response) => void> = []
      const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { releases.push(resolve) }))
      vi.stubGlobal("fetch", fetchMock)

      const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
      const originalView = document.querySelector<HTMLElement>(
        '[data-bml-island][data-bml-state-key="counterModel"]',
      )!
      const originalRestore = restoreClientState(script)
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())

      originalView.remove()
      const replacementView = appendCounterView("Count: replacement")
      let replacementUpdates = 0
      replacementView.addEventListener("bml:update", () => { replacementUpdates++ })
      const replacementRestore = restoreClientState(replacementView)
      releases[0]!(new Response(
        JSON.stringify({ state: '{"count":13}', html: "<span>Count: obsolete</span>" }),
        { status: 200 },
      ))
      await originalRestore
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
      expect(JSON.parse(String((fetchMock.mock.calls[1] as [string, RequestInit])[1].body)).state)
        .toBe('{"count":12}')
      releases[1]!(new Response(
        JSON.stringify({ state: '{"count":12}', html: "<span>Count: 12</span>" }),
        { status: 200 },
      ))
      await replacementRestore

      expect(script.textContent).toBe('{"count":12}')
      expect(replacementView.textContent).toBe("Count: 12")
      expect(replacementUpdates).toBe(1)
    },
  )

  it("an in-flight restore remains retryable when its view is removed without a replacement", async () => {
    setup({ client: true, clientScope: "client-local" })
    window.localStorage.setItem("bml:/:counterModel", '{"count":12}')
    const releases: Array<(response: Response) => void> = []
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { releases.push(resolve) }))
    vi.stubGlobal("fetch", fetchMock)

    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    const originalView = document.querySelector<HTMLElement>(
      '[data-bml-island][data-bml-state-key="counterModel"]',
    )!
    const originalRestore = restoreClientState(script)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    originalView.remove()

    releases[0]!(new Response(
      JSON.stringify({ state: '{"count":13}', html: "<span>Count: obsolete</span>" }),
      { status: 200 },
    ))
    await originalRestore
    expect(script.textContent).toBe('{"count":0}')
    expect(window.localStorage.getItem("bml:/:counterModel")).toBe('{"count":12}')
    expect(document.querySelector('[data-bml-island][data-bml-state-key="counterModel"]')).toBeNull()

    const replacementView = appendCounterView("Count: replacement")
    const retry = restoreClientState(script)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    releases[1]!(new Response(
      JSON.stringify({ state: '{"count":12}', html: "<span>Count: 12</span>" }),
      { status: 200 },
    ))
    await retry
    expect(replacementView.textContent).toBe("Count: 12")
  })

  it.each(["client-session", "client-local"] as const)(
    "an in-flight %s restore cannot overwrite a newer persisted value",
    async (clientScope) => {
      setup({ client: true, clientScope })
      const store = clientScope === "client-local" ? window.localStorage : window.sessionStorage
      store.setItem("bml:/:counterModel", '{"count":12}')
      const releases: Array<(response: Response) => void> = []
      const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { releases.push(resolve) }))
      vi.stubGlobal("fetch", fetchMock)

      const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
      const restore = restoreClientState(script)
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
      store.setItem("bml:/:counterModel", '{"count":14}')
      releases[0]!(new Response(
        JSON.stringify({ state: '{"count":12}', html: "<span>Count: obsolete</span>" }),
        { status: 200 },
      ))
      await restore

      expect(script.textContent).toBe('{"count":0}')
      expect(store.getItem("bml:/:counterModel")).toBe('{"count":14}')
      expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 0")

      const retry = restoreClientState(script)
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
      expect(JSON.parse(String((fetchMock.mock.calls[1] as [string, RequestInit])[1].body)).state)
        .toBe('{"count":14}')
      releases[1]!(new Response(
        JSON.stringify({ state: '{"count":14}', html: "<span>Count: 14</span>" }),
        { status: 200 },
      ))
      await retry
      expect(script.textContent).toBe('{"count":14}')
      expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 14")
    },
  )

  it("an in-flight restore cannot cross into another concrete page path", async () => {
    setup({ client: true, clientScope: "client-local" })
    window.localStorage.setItem("bml:/:counterModel", '{"count":12}')
    let release!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { release = resolve }))
    vi.stubGlobal("fetch", fetchMock)

    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    const restore = restoreClientState(script)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())

    try {
      window.history.pushState({}, "", "/another-page")
      window.localStorage.setItem("bml:/another-page:counterModel", '{"count":12}')
      release(new Response(
        JSON.stringify({ state: '{"count":13}', html: "<span>Count: obsolete</span>" }),
        { status: 200 },
      ))
      await restore

      expect(script.textContent).toBe('{"count":0}')
      expect(window.localStorage.getItem("bml:/:counterModel")).toBe('{"count":12}')
      expect(window.localStorage.getItem("bml:/another-page:counterModel")).toBe('{"count":12}')
      expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 0")
    } finally {
      window.history.pushState({}, "", "/")
    }
  })

  it("a site-scoped restore cannot install markup rendered for another path", async () => {
    window.history.replaceState({}, "", "/first")
    setup({ client: true, clientScope: "client-local", page: "/first" })
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    script.setAttribute("data-bml-site", "")
    window.localStorage.setItem("bml:site:counterModel", '{"count":12}')
    let release!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { release = resolve }))
    vi.stubGlobal("fetch", fetchMock)

    const restore = restoreClientState(script)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    window.history.replaceState({}, "", "/second")
    document.querySelector<HTMLScriptElement>("script[data-bml-page]")!.dataset.bmlPage = "/second"
    release(new Response(
      JSON.stringify({ state: '{"count":12}', html: "<span>Count: stale</span>" }),
      { status: 200 },
    ))
    await restore

    expect(script.textContent).toBe('{"count":0}')
    expect(window.localStorage.getItem("bml:site:counterModel")).toBe('{"count":12}')
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 0")
  })

  it("an in-flight restore cannot cross into another URL query", async () => {
    window.history.replaceState({}, "", "/?filter=open")
    setup({ client: true, clientScope: "client-local" })
    window.localStorage.setItem("bml:/:counterModel", '{"count":12}')
    let release!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { release = resolve }))
    vi.stubGlobal("fetch", fetchMock)

    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    const restore = restoreClientState(script)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    window.history.replaceState({}, "", "/?filter=closed")
    release(new Response(
      JSON.stringify({ state: '{"count":13}', html: "<span>Count: stale</span>" }),
      { status: 200 },
    ))
    await restore

    expect(script.textContent).toBe('{"count":0}')
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 0")
  })

  it("an obsolete restore cannot reload the replacement view's page on a 410", async () => {
    setup({ client: true, clientScope: "client-local" })
    window.localStorage.setItem("bml:/:counterModel", '{"count":12}')
    let release!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { release = resolve }))
    vi.stubGlobal("fetch", fetchMock)
    const reload = vi.spyOn(window.location, "reload").mockImplementation(() => undefined)

    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    const restore = restoreClientState(script)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    document.querySelector<HTMLElement>("[data-bml-island]")!.remove()
    const replacementView = appendCounterView("Count: replacement")

    release(new Response("session expired", { status: 410 }))
    await restore

    expect(reload).not.toHaveBeenCalled()
    expect(script.textContent).toBe('{"count":0}')
    expect(window.localStorage.getItem("bml:/:counterModel")).toBe('{"count":12}')
    expect(replacementView.textContent).toBe("Count: replacement")
  })

  it("an obsolete restore cannot delete persisted state on a 422", async () => {
    setup({ client: true, clientScope: "client-local" })
    window.localStorage.setItem("bml:/:counterModel", '{"count":12}')
    let release!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { release = resolve }))
    vi.stubGlobal("fetch", fetchMock)

    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    const restore = restoreClientState(script)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    document.querySelector<HTMLElement>("[data-bml-island]")!.remove()
    const replacementView = appendCounterView("Count: replacement")

    release(new Response('{"error":"invalid client state"}', { status: 422 }))
    await restore

    expect(script.textContent).toBe('{"count":0}')
    expect(window.localStorage.getItem("bml:/:counterModel")).toBe('{"count":12}')
    expect(replacementView.textContent).toBe("Count: replacement")
  })

  it("a restore that becomes obsolete during JSON decoding cannot update the replacement view", async () => {
    setup({ client: true, clientScope: "client-local" })
    window.localStorage.setItem("bml:/:counterModel", '{"count":12}')
    let releaseJson!: (data: { state: string; html: string }) => void
    const response = {
      status: 200,
      ok: true,
      json: () => new Promise<{ state: string; html: string }>((resolve) => { releaseJson = resolve }),
    } as unknown as Response
    vi.stubGlobal("fetch", vi.fn(async () => response))

    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    const restore = restoreClientState(script)
    await vi.waitFor(() => expect(releaseJson).toBeTypeOf("function"))
    document.querySelector<HTMLElement>("[data-bml-island]")!.remove()
    const replacementView = appendCounterView("Count: replacement")
    let replacementUpdates = 0
    replacementView.addEventListener("bml:update", () => { replacementUpdates++ })

    releaseJson({ state: '{"count":13}', html: "<span>Count: obsolete</span>" })
    await restore

    expect(script.textContent).toBe('{"count":0}')
    expect(window.localStorage.getItem("bml:/:counterModel")).toBe('{"count":12}')
    expect(replacementView.textContent).toBe("Count: replacement")
    expect(replacementUpdates).toBe(0)
  })

  it("an ordinary 422 action deletes the persisted state it submitted", async () => {
    setup({ client: true, clientScope: "client-local" })
    window.localStorage.setItem("bml:/:counterModel", '{"count":12}')
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response('{"error":"invalid client state"}', { status: 422 })),
    )

    await expect(dispatchAction("counterModel", "increment")).rejects.toThrow()

    expect(window.localStorage.getItem("bml:/:counterModel")).toBeNull()
    expect(document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')?.textContent)
      .toBe('{"count":0}')
  })

  it("an obsolete action cannot persist over a same-page replacement", async () => {
    setup({ client: true, clientScope: "client-local" })
    window.localStorage.setItem("bml:/:counterModel", '{"count":1}')
    let release!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { release = resolve }))
    vi.stubGlobal("fetch", fetchMock)

    const originalScript = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    const originalView = document.querySelector<HTMLElement>('[data-bml-island][data-bml-state-key="counterModel"]')!
    const action = dispatchAction("counterModel", "increment")
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())

    originalScript.remove()
    originalView.remove()
    const replacement = appendCounterComponent("client-local", "Count: 2")
    replacement.script.textContent = '{"count":2}'
    window.localStorage.setItem("bml:/:counterModel", '{"count":2}')
    release(new Response(
      JSON.stringify({ state: '{"count":99}', html: "<span>Count: 99</span>" }),
      { status: 200 },
    ))
    await action

    expect(window.localStorage.getItem("bml:/:counterModel")).toBe('{"count":2}')
    expect(replacement.script.textContent).toBe('{"count":2}')
    expect(replacement.view.textContent).toBe("Count: 2")
  })

  it.each([410, 422])("an obsolete %i action response cannot affect the replacement page", async (status) => {
    setup({ client: true, clientScope: "client-local" })
    window.localStorage.setItem("bml:/:counterModel", '{"count":12}')
    let release!: (response: Response) => void
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((resolve) => { release = resolve })))

    const action = dispatchAction("counterModel", "increment")
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledOnce())
    try {
      window.history.pushState({}, "", "/replacement")
      window.localStorage.setItem("bml:/replacement:counterModel", '{"count":12}')
      release(new Response('{"error":"obsolete"}', { status }))
      await expect(action).resolves.toBeUndefined()

      expect(window.location.reload).not.toHaveBeenCalled()
      expect(window.localStorage.getItem("bml:/:counterModel")).toBe('{"count":12}')
      expect(window.localStorage.getItem("bml:/replacement:counterModel")).toBe('{"count":12}')
    } finally {
      window.history.pushState({}, "", "/")
    }
  })

  it("an in-flight persistent restore cannot recreate state for a page-scoped replacement", async () => {
    setup({ client: true, clientScope: "client-local" })
    window.localStorage.setItem("bml:/:counterModel", '{"count":12}')
    let release!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { release = resolve }))
    vi.stubGlobal("fetch", fetchMock)

    const originalScript = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    const originalView = document.querySelector<HTMLElement>('[data-bml-island][data-bml-state-key="counterModel"]')!
    const originalRestore = restoreClientState(originalScript)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())

    originalScript.remove()
    originalView.remove()
    const replacement = appendCounterComponent(null, "Count: page replacement")
    let replacementUpdates = 0
    replacement.view.addEventListener("bml:update", () => { replacementUpdates++ })
    await restoreClientState(replacement.script)
    expect(window.localStorage.getItem("bml:/:counterModel")).toBeNull()

    release(new Response(
      JSON.stringify({ state: '{"count":13}', html: "<span>Count: stale restore</span>" }),
      { status: 200 },
    ))
    await originalRestore

    expect(fetchMock).toHaveBeenCalledOnce()
    expect(replacement.script.textContent).toBe('{"count":0}')
    expect(replacement.view.textContent).toBe("Count: page replacement")
    expect(replacementUpdates).toBe(0)
    expect(window.localStorage.getItem("bml:/:counterModel")).toBeNull()
  })

  it("an action queued behind restore uses the canonical restored response", async () => {
    setup({ client: true, clientScope: "client-local" })
    window.localStorage.setItem("bml:/:counterModel", '{"count":12}')
    const releases: Array<(response: Response) => void> = []
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { releases.push(resolve) }))
    vi.stubGlobal("fetch", fetchMock)

    const restore = restoreClientState()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    const action = dispatchAction("counterModel", "increment")
    expect(fetchMock).toHaveBeenCalledOnce()

    releases[0]!(new Response(JSON.stringify({ state: '{"count":13}', html: "<span>Count: 13</span>" }), { status: 200 }))
    await restore
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const actionBody = JSON.parse(String((fetchMock.mock.calls[1] as unknown as [string, RequestInit])[1].body))
    expect(actionBody).toMatchObject({ method: "increment", state: '{"count":13}' })

    releases[1]!(new Response(JSON.stringify({ state: '{"count":14}', html: "<span>Count: 14</span>" }), { status: 200 }))
    await action
    expect(window.localStorage.getItem("bml:/:counterModel")).toBe('{"count":14}')
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 14")
  })

  it.each(["client-session", "client-local"] as const)(
    "restoreClientState discards incompatible %s JSON rejected by the current model",
    async (clientScope) => {
      setup({ client: true, clientScope })
      const store = clientScope === "client-local" ? window.localStorage : window.sessionStorage
      const otherStore = clientScope === "client-local" ? window.sessionStorage : window.localStorage
      store.setItem("bml:/:counterModel", '{"count":"from-an-older-model"}')
      otherStore.setItem("app:keep", "unrelated")
      const fetchMock = vi.fn(async () =>
        new Response('{"error":"invalid client state"}', { status: 422 }),
      )
      vi.stubGlobal("fetch", fetchMock)

      await restoreClientState()

      expect(fetchMock).toHaveBeenCalledOnce()
      expect(JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body)).state)
        .toBe('{"count":"from-an-older-model"}')
      expect(store.getItem("bml:/:counterModel")).toBeNull()
      expect(otherStore.getItem("app:keep")).toBe("unrelated")
      expect(document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')?.textContent)
        .toBe('{"count":0}')
      expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 0")
    },
  )

  it.each(["client-session", "client-local"] as const)(
    "a 422 from an older %s restore does not delete a newer stored value",
    async (clientScope) => {
      setup({ client: true, clientScope })
      const store = clientScope === "client-local" ? window.localStorage : window.sessionStorage
      const otherStore = clientScope === "client-local" ? window.sessionStorage : window.localStorage
      store.setItem("bml:/:counterModel", '{"count":"from-an-older-model"}')
      otherStore.setItem("app:keep", "unrelated")
      let release!: (response: Response) => void
      const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { release = resolve }))
      vi.stubGlobal("fetch", fetchMock)

      const restore = restoreClientState()
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
      expect(JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body)).state)
        .toBe('{"count":"from-an-older-model"}')
      store.setItem("bml:/:counterModel", '{"count":13}')
      release(new Response('{"error":"invalid client state"}', { status: 422 }))
      await restore

      expect(store.getItem("bml:/:counterModel")).toBe('{"count":13}')
      expect(otherStore.getItem("app:keep")).toBe("unrelated")
      expect(document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')?.textContent)
        .toBe('{"count":0}')
    },
  )

  it.each(["client-session", "client-local"] as const)(
    "restoreClientState discards syntactically invalid %s JSON without a request",
    async (clientScope) => {
      setup({ client: true, clientScope })
      const store = clientScope === "client-local" ? window.localStorage : window.sessionStorage
      const otherStore = clientScope === "client-local" ? window.sessionStorage : window.localStorage
      store.setItem("bml:/:counterModel", "not-json")
      otherStore.setItem("app:keep", "unrelated")
      const fetchMock = vi.fn()
      vi.stubGlobal("fetch", fetchMock)

      await restoreClientState()

      expect(store.getItem("bml:/:counterModel")).toBeNull()
      expect(otherStore.getItem("app:keep")).toBe("unrelated")
      expect(fetchMock).not.toHaveBeenCalled()
    },
  )

  it("restoreClientState includes a state script passed as the scope root", async () => {
    setup({ client: true, clientScope: "client-local" })
    window.localStorage.setItem("bml:/:counterModel", '{"count":12}')
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ state: '{"count":12}', html: "<span>Count: 12</span>" }), { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!

    await restoreClientState(script)

    expect(fetchMock).toHaveBeenCalledOnce()
    expect(JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body)).state).toBe('{"count":12}')
  })

  it("restoreClientState is safe when imported in a DOM-less environment", async () => {
    vi.stubGlobal("document", undefined)

    await expect(restoreClientState()).resolves.toBeUndefined()
  })

  it("clearBmlClientState removes only state marked for sign-out cleanup", () => {
    sessionStorage.setItem("bml:/:draft", "session")
    sessionStorage.setItem("bml:clear:/:account", "account-session")
    sessionStorage.setItem("app:keep", "session")
    window.localStorage.setItem("bml:/:draft", "local")
    window.localStorage.setItem("bml:clear:site:account", "account-local")
    window.localStorage.setItem("app:keep", "local")

    clearBmlClientState({ reload: false })

    expect(sessionStorage.getItem("bml:/:draft")).toBe("session")
    expect(window.localStorage.getItem("bml:/:draft")).toBe("local")
    expect(sessionStorage.getItem("bml:clear:/:account")).toBeNull()
    expect(window.localStorage.getItem("bml:clear:site:account")).toBeNull()
    expect(sessionStorage.getItem("app:keep")).toBe("session")
    expect(window.localStorage.getItem("app:keep")).toBe("local")
    expect(document.cookie).toContain("bml_session_reset=1")
    expect(window.location.reload).not.toHaveBeenCalled()
  })

  it("clearBmlClientState removes marked live state without reloading when explicitly requested", async () => {
    setup({ client: true, clientScope: "client-local", clearOnSignOut: true })
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    const view = document.querySelector<HTMLElement>("[data-bml-island]")!
    script.textContent = '{"owner":"account-a","count":12}'
    view.innerHTML = '<button data-bml-state-key="counterModel" data-bml-method="save">Account A</button>'
    window.localStorage.setItem("bml:clear:/:counterModel", '{"count":12}')

    clearBmlClientState({ reload: false })

    expect(window.localStorage.getItem("bml:clear:/:counterModel")).toBeNull()
    expect(script.textContent).toBe("")
    expect(view.textContent).toBe("")
    expect(window.location.reload).not.toHaveBeenCalled()
  })

  it("clearBmlClientState reloads by default after a completed identity change", async () => {
    setup({ client: true, clientScope: "client-local", clearOnSignOut: true })

    clearBmlClientState()

    expect(window.location.reload).toHaveBeenCalledOnce()
  })

  it("clearBmlClientState honors an explicit reload when no marked state is mounted", () => {
    document.body.replaceChildren()
    window.localStorage.setItem("bml:clear:site:account", "account-local")

    clearBmlClientState({ reload: true })

    expect(window.localStorage.getItem("bml:clear:site:account")).toBeNull()
    expect(window.location.reload).toHaveBeenCalledOnce()
  })

  it("clearBmlClientState leaves unmarked connected state intact", async () => {
    setup({ client: true, clientScope: "client-local" })
    await restoreClientState()
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    const view = document.querySelector<HTMLElement>("[data-bml-island]")!
    script.textContent = '{"count":12}'
    view.innerHTML = "<span>Count: 12</span>"
    window.localStorage.setItem("bml:/:counterModel", '{"count":12}')

    clearBmlClientState({ reload: false })

    expect(window.localStorage.getItem("bml:/:counterModel")).toBe('{"count":12}')
    expect(script.textContent).toBe('{"count":12}')
    expect(view.textContent).toBe("Count: 12")
    expect(window.location.reload).not.toHaveBeenCalled()
  })

  it("clearBmlClientState settles a deep action backlog without recursive cancellation", async () => {
    setup({ client: true, clientScope: "client-local" })
    sessionStorage.setItem("bml:clear:/:session", "session")
    window.localStorage.setItem("bml:clear:/:local", "local")
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => new Promise<Response>(() => undefined))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }),
      )
    vi.stubGlobal("fetch", fetchMock)

    const pending = Array.from(
      { length: 20_000 },
      (_, index) => dispatchAction("counterModel", "save", [index]),
    )
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())

    expect(() => clearBmlClientState({ reload: false })).not.toThrow()
    await Promise.all(pending)
    expect(fetchMock).toHaveBeenCalledOnce()
    expect(sessionStorage.getItem("bml:clear:/:session")).toBeNull()
    expect(window.localStorage.getItem("bml:clear:/:local")).toBeNull()

    await dispatchAction("counterModel", "save")
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(window.localStorage.getItem("bml:/:counterModel")).toBe('{"count":1}')
  }, 10_000)

  it("clearBmlClientState prevents an older response from recreating signed-out state", async () => {
    setup({ client: true, clientScope: "client-local" })
    const releases: Array<(response: Response) => void> = []
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { releases.push(resolve) }))
    vi.stubGlobal("fetch", fetchMock)

    const oldAction = dispatchAction("counterModel", "save")
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    clearBmlClientState({ reload: false })
    await expect(oldAction).resolves.toBeUndefined()

    const newAction = dispatchAction("counterModel", "save")
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    releases[1]!(new Response(JSON.stringify({ state: '{"count":2}', html: "<span>Count: 2</span>" }), { status: 200 }))
    await newAction

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(window.localStorage.getItem("bml:/:counterModel")).toBe('{"count":2}')
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 2")

    // Even a transport that ignores AbortSignal cannot let its eventual old response cross the
    // cleanup boundary or overwrite the new generation.
    releases[0]!(new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(window.localStorage.getItem("bml:/:counterModel")).toBe('{"count":2}')
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 2")
  })

  it("clearBmlClientState releases a pending declarative control and permits a new action", async () => {
    setup({ client: true, clientScope: "client-local" })
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => new Promise<Response>(() => undefined))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ state: '{"count":2}', html: "<span>Count: 2</span>" }), { status: 200 }),
      )
    vi.stubGlobal("fetch", fetchMock)
    bindActions()
    const button = document.querySelector<HTMLButtonElement>("button[data-bml-method]")!

    button.click()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    expect(button.disabled).toBe(true)

    clearBmlClientState({ reload: false })
    await vi.waitFor(() => expect(button.disabled).toBe(false))
    expect(button.hasAttribute("aria-busy")).toBe(false)

    button.click()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 2"))
  })

  it("clearBmlClientState rolls back a restore that began before cleanup", async () => {
    setup({ client: true, clientScope: "client-local", clearOnSignOut: true })
    window.localStorage.setItem("bml:clear:/:counterModel", '{"count":12}')
    let release!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { release = resolve }))
    vi.stubGlobal("fetch", fetchMock)

    const restore = restoreClientState()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    clearBmlClientState({ reload: false })
    release(new Response(JSON.stringify({ state: '{"count":12}', html: "<span>Count: 12</span>" }), { status: 200 }))
    await restore

    expect(window.localStorage.getItem("bml:clear:/:counterModel")).toBeNull()
    expect(document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')?.textContent)
      .toBe("")
    expect(document.querySelector("[data-bml-island]")?.textContent).toBe("")
    expect(window.location.reload).not.toHaveBeenCalled()
  })

  it("an immediate post-cleanup action cannot read state from a canceled restore", async () => {
    setup({ client: true, clientScope: "client-local", clearOnSignOut: true })
    window.localStorage.setItem("bml:clear:/:counterModel", '{"count":12}')
    const releases: Array<(response: Response) => void> = []
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { releases.push(resolve) }))
    vi.stubGlobal("fetch", fetchMock)

    const restore = restoreClientState()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())

    clearBmlClientState({ reload: false })
    const newAction = dispatchAction("counterModel", "increment")
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(JSON.parse(String((fetchMock.mock.calls[1] as unknown as [string, RequestInit])[1].body)).state)
      .toBe("")

    releases[1]!(new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }))
    await newAction
    await restore

    // A transport may ignore AbortSignal and complete the old restore later; generation checks must
    // still keep that response from replacing the new action's state, storage, or DOM.
    releases[0]!(new Response(JSON.stringify({ state: '{"count":12}', html: "<span>Count: 12</span>" }), { status: 200 }))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(window.localStorage.getItem("bml:clear:/:counterModel")).toBe('{"count":1}')
    expect(document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')?.textContent)
      .toBe('{"count":1}')
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 1")
  })

  it("a canceled restore response cannot mutate state before the current response", async () => {
    setup({ client: true, clientScope: "client-local", clearOnSignOut: true })
    window.localStorage.setItem("bml:clear:/:counterModel", '{"count":12}')
    const releases: Array<(response: Response) => void> = []
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { releases.push(resolve) }))
    vi.stubGlobal("fetch", fetchMock)

    const restore = restoreClientState()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    clearBmlClientState({ reload: false })
    const currentAction = dispatchAction("counterModel", "increment")
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))

    releases[0]!(new Response(JSON.stringify({ state: '{"count":12}', html: "<span>Count: 12</span>" }), { status: 200 }))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(window.localStorage.getItem("bml:clear:/:counterModel")).toBeNull()
    expect(document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')?.textContent)
      .toBe("")
    expect(document.querySelector("[data-bml-island]")?.textContent).toBe("")

    releases[1]!(new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }))
    await currentAction
    await restore
    expect(window.localStorage.getItem("bml:clear:/:counterModel")).toBe('{"count":1}')
    expect(document.querySelector("[data-bml-island] span")?.textContent).toBe("Count: 1")
  })

  it("clearBmlClientState cancels and settles delayed work from the old generation", async () => {
    setup({ client: true, clientScope: "client-local" })
    const fetchMock = vi.fn()
    vi.stubGlobal("fetch", fetchMock)

    const delayed = dispatchAction("counterModel", "save", [], { debounceMs: 60_000, scheduleKey: "draft-save" })
    clearBmlClientState({ reload: false })

    await expect(delayed).resolves.toBeUndefined()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it("clearBmlClientState aborts the active transport and gives new work a fresh signal", async () => {
    setup({ client: true, clientScope: "client-local" })
    const signals: AbortSignal[] = []
    const fetchMock = vi.fn((_url: string | URL | Request, init?: RequestInit) => {
      signals.push(init?.signal as AbortSignal)
      if (signals.length === 1) return new Promise<Response>(() => undefined)
      return Promise.resolve(
        new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }),
      )
    })
    vi.stubGlobal("fetch", fetchMock)

    const oldAction = dispatchAction("counterModel", "save")
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    expect(signals[0]?.aborted).toBe(false)

    clearBmlClientState({ reload: false })
    await expect(oldAction).resolves.toBeUndefined()
    expect(signals[0]?.aborted).toBe(true)

    await dispatchAction("counterModel", "save")
    expect(signals).toHaveLength(2)
    expect(signals[1]).not.toBe(signals[0])
    expect(signals[1]?.aborted).toBe(false)
  })

  it("clearBmlClientState cancels an action waiting for identity bootstrap", async () => {
    setup({ client: true, clientScope: "client-local" })
    configureBosca({ getAnalyticsSessionId: undefined, getInstallationId: undefined })
    const identityMarker = document.createElement("script")
    identityMarker.dataset.bmlSharedIdentity = ""
    document.body.append(identityMarker)
    let releaseIdentity!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { releaseIdentity = resolve }))
    vi.stubGlobal("fetch", fetchMock)
    enableDeferred()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())

    const oldAction = dispatchAction("counterModel", "save")
    clearBmlClientState({ reload: false })
    await expect(oldAction).resolves.toBeUndefined()
    releaseIdentity(new Response(null, { status: 204 }))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(fetchMock).toHaveBeenCalledOnce()
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/_bml/identity")
  })

  it("clearBmlClientState settles coalesced callers from the old generation", async () => {
    setup({ client: true, clientScope: "client-local" })
    const fetchMock = vi.fn(() => new Promise<Response>(() => undefined))
    vi.stubGlobal("fetch", fetchMock)

    const active = dispatchAction("counterModel", "save", [0])
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    const pending = dispatchAction(
      "counterModel",
      "save",
      [1],
      { coalesce: true, scheduleKey: "cleanup-coalesce" },
    )
    let pendingSettled = false
    void pending.then(() => { pendingSettled = true })

    clearBmlClientState({ reload: false })
    await Promise.all([active, pending])

    expect(pendingSettled).toBe(true)
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it("clearBmlClientState clears throttle history before new-generation work", async () => {
    setup({ client: true, clientScope: "client-local" })
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => new Promise<Response>(() => undefined))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ state: '{"count":1}', html: "<span>Count: 1</span>" }), { status: 200 }),
      )
    vi.stubGlobal("fetch", fetchMock)
    const options = { throttleMs: 60_000, scheduleKey: "cleanup-throttle" }

    const oldAction = dispatchAction("counterModel", "save", [0], options)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    clearBmlClientState({ reload: false })
    await oldAction

    const currentAction = dispatchAction("counterModel", "save", [1], options)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await currentAction

    expect(JSON.parse(String((fetchMock.mock.calls[1] as [string, RequestInit])[1].body)).args).toEqual([1])
  })

  it("mountAll limits incremental restore scans to the supplied subtree", async () => {
    setup({ client: true, clientScope: "client-local" })
    const documentQueries = vi.spyOn(document, "querySelectorAll")

    await mountAll()
    await mountAll(document.createElement("section"))

    expect(
      documentQueries.mock.calls.filter(([selector]) => selector === "script[data-bml-state-key]"),
    ).toHaveLength(1)
  })

  it("mountAll restores a site view added while its prior restore is in flight", async () => {
    setup({ client: true, clientScope: "client-local" })
    const script = document.querySelector<HTMLScriptElement>('script[data-bml-state-key="counterModel"]')!
    const originalView = document.querySelector<HTMLElement>("[data-bml-island]")!
    script.setAttribute("data-bml-site", "")
    window.localStorage.setItem("bml:site:counterModel", '{"count":12}')
    const releases: Array<(response: Response) => void> = []
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { releases.push(resolve) }))
    vi.stubGlobal("fetch", fetchMock)

    await mountAll()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    originalView.remove()
    const replacementView = appendCounterView("Count: replacement")

    releases[0]!(new Response(
      JSON.stringify({ state: '{"count":13}', html: "<span>Count: obsolete</span>" }),
      { status: 200 },
    ))
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    releases[1]!(new Response(
      JSON.stringify({ state: '{"count":12}', html: "<span>Count: 12</span>" }),
      { status: 200 },
    ))

    await vi.waitFor(() => expect(replacementView.textContent).toBe("Count: 12"))
  })

  it("restoreClientState maps a large document's scripts and views in two scans", async () => {
    setup({ client: true, clientScope: "client-local" })
    for (let index = 0; index < 100; index++) {
      const key = `state-${index}`
      const script = document.createElement("script")
      script.dataset.bmlStateKey = key
      script.dataset.bmlScope = "client-local"
      script.textContent = "{}"
      document.body.append(script)
      const view = document.createElement("div")
      view.dataset.bmlIsland = key
      view.dataset.bmlStateKey = key
      document.body.append(view)
    }
    // Finish automatic mounts from the fixture before measuring the explicit restore.
    await new Promise((resolve) => setTimeout(resolve, 0))
    const queries = vi.spyOn(document, "querySelectorAll")
    queries.mockClear()

    await restoreClientState()

    expect(queries.mock.calls.filter(([selector]) => selector === "script[data-bml-state-key]")).toHaveLength(1)
    expect(queries.mock.calls.filter(([selector]) => selector === "[data-bml-island][data-bml-state-key]")).toHaveLength(1)
  })
})
