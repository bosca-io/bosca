import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { bosca, configureBosca } from "../src/graphql"
import {
  advanceDeferredIdentity,
  BML_IDENTITY_CHANGE_EVENT,
  enableDeferred,
  loadDeferredAll,
  refreshDeferred,
  resetDeferred,
} from "../src/deferred"
import { defineIsland, mountAll } from "../src/island"
import { resetSessionReloadRequest } from "../src/deferred-runtime"

/** jsdom keeps one document across a mocked reload; clear the per-document reload request as a real reload would. */
function simulateNewDocument(): void {
  resetSessionReloadRequest()
}

function deferred(name: string, renderer: string, props: Record<string, unknown> = {}): HTMLElement {
  const root = document.createElement("div")
  root.dataset.bmlIsland = name
  root.dataset.bmlDeferred = renderer
  root.dataset.bmlDeferredState = "pending"
  root.dataset.bmlProps = JSON.stringify(props)
  root.setAttribute("aria-busy", "true")
  root.innerHTML = "<p>Loading</p>"
  document.body.append(root)
  return root
}

describe("deferred islands", () => {
  beforeEach(() => {
    advanceDeferredIdentity()
    window.sessionStorage.clear()
    simulateNewDocument()
    document.body.replaceChildren()
    document.documentElement.lang = "fr"
    window.history.replaceState({}, "", "/accounts/42?tab=first&tab=second")
    const page = document.createElement("script")
    page.type = "application/json"
    page.dataset.bmlPage = "/accounts/{id}"
    page.dataset.bmlPageCanonical = ""
    document.body.append(page)
    configureBosca({
      endpoint: "/graphql",
      getToken: () => "current-token",
      getInstallationId: () => "installation",
      getAnalyticsSessionId: () => "analytics-session",
    })
    enableDeferred()
    vi.spyOn(window.location, "reload").mockImplementation(() => undefined)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it("posts explicit context with current auth and replaces the fallback", async () => {
    const root = deferred("account", "AccountsPage:account", { accountId: "acct-7" })
    const fetchMock = vi.fn(async () => new Response("<strong>Ada</strong>"))
    vi.stubGlobal("fetch", fetchMock)

    await loadDeferredAll()

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe("/_bml/deferred/AccountsPage%3Aaccount")
    expect(init.method).toBe("POST")
    expect(init.credentials).toBe("same-origin")
    expect(init.cache).toBe("no-store")
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer current-token")
    expect((init.headers as Record<string, string>)["X-Installation-ID"]).toBe("installation")
    expect((init.headers as Record<string, string>)["X-BA-Session-ID"]).toBe("analytics-session")
    expect(JSON.parse(String(init.body))).toEqual({
      props: { accountId: "acct-7" },
      page: "/accounts/{id}",
      path: "/accounts/42",
      query: { tab: "first" },
      locale: "fr",
    })
    expect(root.innerHTML).toBe("<strong>Ada</strong>")
    expect(root.dataset.bmlDeferredState).toBe("loaded")
    expect(root.hasAttribute("aria-busy")).toBe(false)
  })

  it("preserves query names inherited from Object.prototype", async () => {
    window.history.replaceState({}, "", "/accounts/42?constructor=ctor&toString=text&__proto__=proto")
    deferred("query-names", "AccountsPage:query-names")
    const fetchMock = vi.fn(async () => new Response("<span>ready</span>"))
    vi.stubGlobal("fetch", fetchMock)

    await loadDeferredAll()

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(String(init.body)).query).toEqual(JSON.parse(
      '{"constructor":"ctor","toString":"text","__proto__":"proto"}',
    ))
  })

  it("uses only the canonical generated page marker for request ownership", async () => {
    const misleading = document.createElement("script")
    misleading.dataset.bmlPage = "/not-the-page"
    document.body.prepend(misleading)
    deferred("canonical-page", "AccountsPage:canonical")
    const fetchMock = vi.fn(async () => new Response("<span>ready</span>"))
    vi.stubGlobal("fetch", fetchMock)

    await loadDeferredAll()

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(String(init.body)).page).toBe("/accounts/{id}")
  })

  it("uses the server-selected locale instead of the document locale", async () => {
    document.documentElement.lang = "en"
    const context = document.createElement("script")
    context.type = "application/json"
    context.dataset.bmlPageCanonical = ""
    context.dataset.bmlPagePath = "/accounts/42"
    context.dataset.bmlPageLocale = "fr"
    document.body.append(context)
    deferred("locale", "AccountsPage:locale")
    const fetchMock = vi.fn(async () => new Response("<span>ready</span>"))
    vi.stubGlobal("fetch", fetchMock)

    await loadDeferredAll()

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(String(init.body)).locale).toBe("fr")
  })

  it("uses the server-selected render path for an alternate response page", async () => {
    const path = document.createElement("script")
    path.type = "application/json"
    path.dataset.bmlPageCanonical = ""
    path.dataset.bmlPagePath = "/not-found"
    document.body.append(path)
    deferred("not-found", "NotFoundPage:private")
    const fetchMock = vi.fn(async () => new Response("<span>ready</span>"))
    vi.stubGlobal("fetch", fetchMock)

    await loadDeferredAll()

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(String(init.body)).path).toBe("/not-found")
  })

  it("loads sibling boundaries concurrently", async () => {
    deferred("first", "Page:first")
    deferred("second", "Page:second")
    let active = 0
    let maximum = 0
    const fetchMock = vi.fn(async () => {
      active++
      maximum = Math.max(maximum, active)
      await Promise.resolve()
      active--
      return new Response("<span>ready</span>")
    })
    vi.stubGlobal("fetch", fetchMock)

    await loadDeferredAll()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(maximum).toBe(2)
  })

  it("bootstraps a missing identity once before loading siblings concurrently", async () => {
    configureBosca({ getInstallationId: undefined, getAnalyticsSessionId: undefined })
    document.cookie = "bml_asid=; Max-Age=0; Path=/"
    deferred("first-bootstrap", "Page:first-bootstrap")
    deferred("second-bootstrap", "Page:second-bootstrap")
    let active = 0
    let maximum = 0
    const fetchMock = vi.fn(async (url: string) => {
      if (url === "/_bml/identity") return new Response(null, { status: 204 })
      active++
      maximum = Math.max(maximum, active)
      await Promise.resolve()
      active--
      return new Response("<span>ready</span>")
    })
    vi.stubGlobal("fetch", fetchMock)

    await loadDeferredAll()

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      "/_bml/identity",
      "/_bml/deferred/Page%3Afirst-bootstrap",
      "/_bml/deferred/Page%3Asecond-bootstrap",
    ])
    expect(maximum).toBe(2)
  })

  it("bootstraps identity for a shared interactive shell without a deferred boundary", async () => {
    configureBosca({ getInstallationId: undefined, getAnalyticsSessionId: undefined })
    document.cookie = "bml_asid=; Max-Age=0; Path=/"
    const marker = document.createElement("script")
    marker.type = "application/json"
    marker.dataset.bmlSharedIdentity = ""
    document.body.append(marker)
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal("fetch", fetchMock)

    enableDeferred()

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/_bml/identity")
  })

  it("waits for synchronous application configuration before eager identity setup", async () => {
    configureBosca({ getInstallationId: undefined, getAnalyticsSessionId: undefined })
    document.cookie = "bml_asid=; Max-Age=0; Path=/"
    const marker = document.createElement("script")
    marker.type = "application/json"
    marker.dataset.bmlSharedIdentity = ""
    document.body.append(marker)
    const fetchMock = vi.fn()
    vi.stubGlobal("fetch", fetchMock)

    enableDeferred()
    configureBosca({ getAnalyticsSessionId: () => "configured-session" })
    await Promise.resolve()

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it("does not bootstrap identity on a page without a private request boundary", async () => {
    configureBosca({ getInstallationId: undefined, getAnalyticsSessionId: undefined })
    document.cookie = "bml_asid=; Max-Age=0; Path=/"
    const fetchMock = vi.fn(async () => new Response('{"data":{"ready":true}}'))
    vi.stubGlobal("fetch", fetchMock)

    enableDeferred()
    await expect(bosca.query<{ ready: boolean }>("query { ready }")).resolves.toEqual({ ready: true })

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(["/graphql"])
  })

  it("holds BML requests until the shared identity bootstrap settles", async () => {
    configureBosca({ getInstallationId: undefined, getAnalyticsSessionId: undefined })
    document.cookie = "bml_asid=; Max-Age=0; Path=/"
    deferred("bootstrap-gate", "Page:bootstrap-gate")
    let finishIdentity!: () => void
    const identityResponse = new Promise<Response>((resolve) => {
      finishIdentity = () => resolve(new Response(null, { status: 204 }))
    })
    const fetchMock = vi.fn((url: string) => {
      if (url === "/_bml/identity") return identityResponse
      if (url === "/graphql") return Promise.resolve(new Response('{"data":{"ready":true}}'))
      return Promise.resolve(new Response("<span>ready</span>"))
    })
    vi.stubGlobal("fetch", fetchMock)

    enableDeferred()
    const query = bosca.query<{ ready: boolean }>("query { ready }")
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/_bml/identity")

    finishIdentity()
    await expect(query).resolves.toEqual({ ready: true })
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(["/_bml/identity", "/graphql"])
  })

  it("does not fan out BML requests when identity bootstrap fails", async () => {
    configureBosca({ getInstallationId: undefined, getAnalyticsSessionId: undefined })
    document.cookie = "bml_asid=; Max-Age=0; Path=/"
    deferred("bootstrap-failure", "Page:bootstrap-failure")
    const fetchMock = vi.fn(async () => new Response("unavailable", { status: 503 }))
    vi.stubGlobal("fetch", fetchMock)

    enableDeferred()
    await expect(bosca.query("query { ready }")).rejects.toThrow("BML identity bootstrap HTTP 503")

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(["/_bml/identity"])
  })

  it("does not repeat a successful bootstrap when the browser does not keep identity cookies", async () => {
    configureBosca({ getInstallationId: undefined, getAnalyticsSessionId: undefined })
    document.cookie = "bml_asid=; Max-Age=0; Path=/"
    deferred("cookieless-identity", "Page:cookieless-identity")
    const fetchMock = vi.fn(async (url: string) =>
      url === "/_bml/identity"
        ? new Response(null, { status: 204 })
        : new Response('{"data":{"ready":true}}'),
    )
    vi.stubGlobal("fetch", fetchMock)

    enableDeferred()
    await bosca.query("query { ready }")
    await bosca.query("query { ready }")

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(["/_bml/identity", "/graphql", "/graphql"])
  })

  it("bootstraps again after an identity change", async () => {
    configureBosca({ getInstallationId: undefined, getAnalyticsSessionId: undefined })
    document.cookie = "bml_asid=; Max-Age=0; Path=/"
    deferred("identity-change-bootstrap", "Page:identity-change-bootstrap")
    const fetchMock = vi.fn(async (url: string) =>
      url === "/_bml/identity"
        ? new Response(null, { status: 204 })
        : new Response('{"data":{"ready":true}}'),
    )
    vi.stubGlobal("fetch", fetchMock)

    enableDeferred()
    await bosca.query("query { ready }")
    advanceDeferredIdentity()
    await bosca.query("query { ready }")

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      "/_bml/identity",
      "/graphql",
      "/_bml/identity",
      "/graphql",
    ])
  })

  it("does not hold a cross-origin GraphQL endpoint on the same-origin identity bootstrap", async () => {
    configureBosca({
      endpoint: "https://data.example.test/graphql",
      getInstallationId: undefined,
      getAnalyticsSessionId: undefined,
    })
    document.cookie = "bml_asid=; Max-Age=0; Path=/"
    deferred("remote-graphql", "Page:remote-graphql")
    const fetchMock = vi.fn(async (url: string) =>
      url === "/_bml/identity"
        ? new Response("unavailable", { status: 503 })
        : new Response('{"data":{"ready":true}}'),
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(bosca.query<{ ready: boolean }>("query { ready }")).resolves.toEqual({ ready: true })
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(["https://data.example.test/graphql"])
  })

  it("bootstraps the new identity before any request that follows an identity change", async () => {
    configureBosca({ getInstallationId: undefined, getAnalyticsSessionId: undefined })
    document.cookie = "bml_asid=; Max-Age=0; Path=/"
    const root = deferred("identity-race", "Page:identity-race")
    const calls: string[] = []
    let identityCount = 0
    let releaseSecondIdentity!: () => void
    let deferredCount = 0
    vi.stubGlobal("fetch", vi.fn((url: string, init: RequestInit) => {
      calls.push(url)
      if (url === "/_bml/identity") {
        identityCount++
        if (identityCount === 1) return Promise.resolve(new Response(null, { status: 204 }))
        return new Promise<Response>((resolve) => {
          releaseSecondIdentity = () => resolve(new Response(null, { status: 204 }))
        })
      }
      deferredCount++
      if (deferredCount === 1) {
        // The first private request is still in flight when the identity changes.
        return new Promise<Response>((_, reject) => {
          init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")))
        })
      }
      return Promise.resolve(new Response("<span>new identity</span>"))
    }))

    void mountAll()
    await vi.waitFor(() => expect(deferredCount).toBe(1))
    document.dispatchEvent(new Event(BML_IDENTITY_CHANGE_EVENT))
    await vi.waitFor(() => expect(identityCount).toBe(2))
    await new Promise((resolve) => setTimeout(resolve, 20))
    // No private request may leave before the new identity is established.
    expect(calls.slice(2)).toEqual(["/_bml/identity"])

    releaseSecondIdentity()
    await vi.waitFor(() => expect(root.textContent).toBe("new identity"))
    expect(calls.slice(2)).toEqual(["/_bml/identity", "/_bml/deferred/Page%3Aidentity-race"])
  })

  it("reloads a boundary whose response was superseded by a direct identity change", async () => {
    const root = deferred("direct-advance", "Page:direct-advance")
    let releaseFirst!: (response: Response) => void
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { releaseFirst = resolve }))
      .mockResolvedValueOnce(new Response("<span>current identity</span>"))
    vi.stubGlobal("fetch", fetchMock)

    const load = loadDeferredAll()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    // Public API use without the refresh event that normally restores boundaries.
    advanceDeferredIdentity()
    releaseFirst(new Response("<span>old identity</span>"))
    await load

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(root.textContent).toBe("current identity")
    expect(root.dataset.bmlDeferredState).toBe("loaded")
  })

  it("restores the authored fallback nodes rather than re-parsed markup", async () => {
    const root = deferred("fallback-clone", "Page:fallback-clone")
    root.innerHTML = '<p data-note="a&amp;b">Loading <em>soon</em></p>'
    const original = root.innerHTML
    vi.stubGlobal("fetch", vi.fn(async () => new Response("<span>private</span>")))

    await loadDeferredAll()
    expect(root.textContent).toBe("private")
    resetDeferred(root)

    expect(root.innerHTML).toBe(original)
    expect(root.dataset.bmlDeferredState).toBe("pending")
  })

  it("decodes deferred map props sent as ordered pairs and in the earlier object form", async () => {
    const pairs = deferred("map-pairs", "Page:map-pairs", {
      ids: { $bml: "map", value: [["10", { $bml: "long", value: "9007199254740993" }], ["b", "bee"]] },
      legacy: { $bml: "map", value: { a: 1 } },
    })
    let props: Record<string, unknown> | undefined
    defineIsland("map-pairs", (ctx) => { props = ctx.props })
    vi.stubGlobal("fetch", vi.fn(async () => new Response("<span>ready</span>")))

    await mountAll()
    expect(pairs.dataset.bmlDeferredState).toBe("loaded")
    expect(props).toEqual({ ids: { "10": 9007199254740993n, b: "bee" }, legacy: { a: 1 } })
    // The pairs travel back to the server unchanged, so the server-side order survives.
    const body = JSON.parse(String((vi.mocked(fetch).mock.calls[0]?.[1] as RequestInit).body))
    expect(body.props.ids.value).toEqual([["10", { $bml: "long", value: "9007199254740993" }], ["b", "bee"]])
  })

  it("keeps a failed identity boundary in error until explicitly retried", async () => {
    configureBosca({ getInstallationId: undefined, getAnalyticsSessionId: undefined })
    document.cookie = "bml_asid=; Max-Age=0; Path=/"
    const root = deferred("bootstrap-error", "Page:bootstrap-error")
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("unavailable", { status: 503 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response("<span>ready</span>"))
    vi.stubGlobal("fetch", fetchMock)

    await mountAll()
    expect(root.dataset.bmlDeferredState).toBe("error")
    expect(fetchMock).toHaveBeenCalledOnce()

    document.body.append(document.createElement("div"))
    await mountAll()
    expect(root.dataset.bmlDeferredState).toBe("error")
    expect(fetchMock).toHaveBeenCalledOnce()

    refreshDeferred(root)
    await vi.waitFor(() => expect(root.dataset.bmlDeferredState).toBe("loaded"))
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it("retries a failed shared-shell identity bootstrap before a later BML request", async () => {
    configureBosca({ getInstallationId: undefined, getAnalyticsSessionId: undefined })
    document.cookie = "bml_asid=; Max-Age=0; Path=/"
    const marker = document.createElement("script")
    marker.type = "application/json"
    marker.dataset.bmlSharedIdentity = ""
    document.body.append(marker)
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("unavailable", { status: 503 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response('{"data":{"ready":true}}'))
    vi.stubGlobal("fetch", fetchMock)

    enableDeferred()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    await new Promise((resolve) => setTimeout(resolve, 0))
    await expect(bosca.query<{ ready: boolean }>("query { ready }")).resolves.toEqual({ ready: true })

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      "/_bml/identity",
      "/_bml/identity",
      "/graphql",
    ])
  })

  it("bootstraps a missing installation identity when the server supports it", async () => {
    configureBosca({ getInstallationId: undefined, getAnalyticsSessionId: () => "analytics-session" })
    const capability = document.createElement("script")
    capability.type = "application/json"
    capability.dataset.bmlInstallationIdentity = ""
    document.body.append(capability)
    deferred("installation-bootstrap", "Page:installation-bootstrap")
    const fetchMock = vi.fn(async (url: string) =>
      url === "/_bml/identity"
        ? new Response(null, { status: 204 })
        : new Response("<span>ready</span>"),
    )
    vi.stubGlobal("fetch", fetchMock)

    await loadDeferredAll()

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      "/_bml/identity",
      "/_bml/deferred/Page%3Ainstallation-bootstrap",
    ])
  })

  it("allows concurrent loads started for separate inserted roots", async () => {
    const first = deferred("inserted-first", "Page:inserted-first")
    const second = deferred("inserted-second", "Page:inserted-second")
    let active = 0
    let maximum = 0
    const fetchMock = vi.fn(async () => {
      active++
      maximum = Math.max(maximum, active)
      await Promise.resolve()
      active--
      return new Response("<span>ready</span>")
    })
    vi.stubGlobal("fetch", fetchMock)

    await Promise.all([loadDeferredAll(first), loadDeferredAll(second)])

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(maximum).toBe(2)
  })

  it("rescans a returned fragment and loads nested sibling boundaries concurrently", async () => {
    const outer = deferred("outer", "Page:outer")
    let active = 0
    let maximum = 0
    const fetchMock = vi.fn(async (url: string) => {
      active++
      maximum = Math.max(maximum, active)
      await Promise.resolve()
      const response = url.endsWith("Page%3Aouter")
        ? new Response(
            "<div data-bml-deferred=\"Page:first\" data-bml-deferred-state=\"pending\" data-bml-props=\"{}\"></div>" +
            "<div data-bml-deferred=\"Page:second\" data-bml-deferred-state=\"pending\" data-bml-props=\"{}\"></div>",
          )
        : new Response("<span>ready</span>")
      active--
      return response
    })
    vi.stubGlobal("fetch", fetchMock)

    await loadDeferredAll(outer)

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(maximum).toBe(2)
    expect(
      Array.from(outer.querySelectorAll<HTMLElement>("[data-bml-deferred]"))
        .every((root) => root.dataset.bmlDeferredState === "loaded"),
    ).toBe(true)
  })

  it("contains a failed boundary and continues loading unrelated islands", async () => {
    const failed = deferred("failed", "Page:failed")
    const ready = deferred("ready", "Page:ready")
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("unavailable", { status: 503 }))
      .mockResolvedValueOnce(new Response("<span>ready</span>"))
    vi.stubGlobal("fetch", fetchMock)

    await loadDeferredAll()

    expect(failed.dataset.bmlDeferredState).toBe("error")
    expect(failed.dataset.bmlDeferredError).toContain("503")
    expect(failed.textContent).toBe("Loading")
    expect(ready.dataset.bmlDeferredState).toBe("loaded")
    expect(ready.textContent).toBe("ready")
  })

  it("reloads when a stateful deferred boundary has no page session", async () => {
    const root = deferred("expired", "Page:expired")
    vi.stubGlobal("fetch", vi.fn(async () => new Response("session expired", { status: 410 })))

    await loadDeferredAll()

    expect(window.location.reload).toHaveBeenCalledOnce()
    expect(root.textContent).toBe("Loading")
    expect(root.dataset.bmlDeferredState).toBe("loading")
  })

  it("reports a boundary error instead of reloading again when the session cookie does not persist", async () => {
    const root = deferred("cookieless", "Page:cookieless")
    vi.stubGlobal("fetch", vi.fn(async () => new Response("session expired", { status: 410 })))

    await loadDeferredAll()
    expect(window.location.reload).toHaveBeenCalledOnce()

    // The reload produced another page whose deferred request is again missing its session.
    simulateNewDocument()
    root.dataset.bmlDeferredState = "pending"
    await loadDeferredAll()

    expect(window.location.reload).toHaveBeenCalledOnce()
    expect(root.dataset.bmlDeferredState).toBe("error")
    expect(root.dataset.bmlDeferredError).toContain("did not keep the page session")
    expect(root.textContent).toBe("Loading")
  })

  it("reloads once without reporting errors when sibling boundaries both miss the page session", async () => {
    const first = deferred("sibling-expired-first", "Page:sibling-expired-first")
    const second = deferred("sibling-expired-second", "Page:sibling-expired-second")
    const errors = vi.fn()
    document.addEventListener("bml:deferred-error", errors)
    vi.stubGlobal("fetch", vi.fn(async () => new Response("session expired", { status: 410 })))

    try {
      await loadDeferredAll()
    } finally {
      document.removeEventListener("bml:deferred-error", errors)
    }

    expect(window.location.reload).toHaveBeenCalledOnce()
    expect(errors).not.toHaveBeenCalled()
    expect(first.dataset.bmlDeferredState).toBe("loading")
    expect(second.dataset.bmlDeferredState).toBe("loading")
  })

  it("allows a later session expiry to reload after a successful private render", async () => {
    const root = deferred("renewed", "Page:renewed")
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("session expired", { status: 410 }))
      .mockResolvedValueOnce(new Response("<span>ready</span>"))
      .mockResolvedValueOnce(new Response("session expired", { status: 410 }))
    vi.stubGlobal("fetch", fetchMock)

    await loadDeferredAll()
    simulateNewDocument()
    root.dataset.bmlDeferredState = "pending"
    await loadDeferredAll()
    expect(root.dataset.bmlDeferredState).toBe("loaded")

    refreshDeferred(root)
    await vi.waitFor(() => expect(window.location.reload).toHaveBeenCalledTimes(2))
  })

  it("restores the authored fallback before reloading private content", async () => {
    const root = deferred("identity", "Page:identity")
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("<span>Alice</span>"))
      .mockResolvedValueOnce(new Response("<span>Bob</span>"))
    vi.stubGlobal("fetch", fetchMock)
    await loadDeferredAll()
    expect(root.textContent).toBe("Alice")

    resetDeferred(root)
    expect(root.textContent).toBe("Loading")
    expect(root.dataset.bmlDeferredState).toBe("pending")
    await loadDeferredAll(root)

    expect(root.textContent).toBe("Bob")
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it("tears down and remounts client setup when resetDeferred reloads a boundary", async () => {
    const root = deferred("reset-lifecycle", "Page:reset-lifecycle")
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("<span>Alice</span>"))
      .mockResolvedValueOnce(new Response("<span>Bob</span>"))
    vi.stubGlobal("fetch", fetchMock)
    let mounts = 0
    let unmounts = 0
    let events = 0
    defineIsland("reset-lifecycle", (ctx) => {
      mounts++
      ctx.on(document, "bml-reset-probe", () => { events++ })
      ctx.onUnmount(() => { unmounts++ })
    })

    await mountAll()
    expect(mounts).toBe(1)
    resetDeferred(root)
    document.dispatchEvent(new Event("bml-reset-probe"))
    expect(unmounts).toBe(1)
    expect(events).toBe(0)
    expect(root.textContent).toBe("Loading")

    await mountAll(root)
    document.dispatchEvent(new Event("bml-reset-probe"))
    expect(root.textContent).toBe("Bob")
    expect(mounts).toBe(2)
    expect(events).toBe(1)
  })

  it("restores and reloads a detached boundary before reusing it", async () => {
    const root = deferred("detached-private", "Page:detached")
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("<span>Alice</span>"))
      .mockResolvedValueOnce(new Response("<span>Bob</span>"))
    vi.stubGlobal("fetch", fetchMock)
    let unmounts = 0
    let contentAtUnmount: string | null = null
    defineIsland("detached-private", (ctx) => {
      ctx.onUnmount(() => {
        unmounts++
        contentAtUnmount = ctx.root.textContent
      })
    })
    await mountAll()
    expect(root.textContent).toBe("Alice")

    root.remove()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(unmounts).toBe(1)
    expect(contentAtUnmount).toBe("Alice")
    expect(root.textContent).toBe("Loading")
    expect(root.dataset.bmlDeferredState).toBe("pending")
    document.dispatchEvent(new CustomEvent(BML_IDENTITY_CHANGE_EVENT))

    document.body.append(root)
    await vi.waitFor(() => expect(root.textContent).toBe("Bob"))

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(root.dataset.bmlDeferredState).toBe("loaded")
  })

  it("reloads a boundary reparented during an identity change", async () => {
    const root = deferred("reparented-private", "Page:reparented")
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("<span>Alice</span>"))
      .mockResolvedValueOnce(new Response("<span>Bob</span>"))
    vi.stubGlobal("fetch", fetchMock)
    let mounts = 0
    let unmounts = 0
    defineIsland("reparented-private", (ctx) => {
      mounts++
      ctx.onUnmount(() => { unmounts++ })
    })
    await mountAll()
    expect(root.textContent).toBe("Alice")
    expect(mounts).toBe(1)

    root.remove()
    document.dispatchEvent(new Event(BML_IDENTITY_CHANGE_EVENT))
    document.body.append(root)

    await vi.waitFor(() => expect(root.textContent).toBe("Bob"))
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(mounts).toBe(2)
    expect(unmounts).toBe(1)
  })

  it("discards a response superseded by a refresh", async () => {
    const root = deferred("superseded", "Page:superseded")
    let resolveOld: ((response: Response) => void) | undefined
    const oldResponse = new Promise<Response>((resolve) => { resolveOld = resolve })
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => oldResponse)
      .mockResolvedValueOnce(new Response("<span>Current</span>"))
    vi.stubGlobal("fetch", fetchMock)

    const oldLoad = loadDeferredAll(root)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    resetDeferred(root)
    await loadDeferredAll(root)
    resolveOld!(new Response("<span>Stale</span>"))
    await oldLoad

    expect(root.textContent).toBe("Current")
    expect(root.dataset.bmlDeferredState).toBe("loaded")
  })

  it("retries a deferred render when the URL query changes in flight", async () => {
    const root = deferred("query-change", "Page:query-change")
    let releaseOld!: (response: Response) => void
    const oldResponse = new Promise<Response>((resolve) => { releaseOld = resolve })
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => oldResponse)
      .mockResolvedValueOnce(new Response("<span>Current</span>"))
    vi.stubGlobal("fetch", fetchMock)

    const loading = loadDeferredAll(root)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    window.history.replaceState({}, "", "/accounts/42?tab=current")
    releaseOld(new Response("<span>Stale</span>"))
    await loading

    expect(fetchMock).toHaveBeenCalledTimes(2)
    const firstBody = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))
    const secondBody = JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))
    expect(firstBody.query).toEqual({ tab: "first" })
    expect(secondBody.query).toEqual({ tab: "current" })
    expect(root.textContent).toBe("Current")
    expect(root.dataset.bmlDeferredState).toBe("loaded")
  })

  it.each([
    ["browser path", () => window.history.replaceState({}, "", "/accounts/84?tab=first&tab=second"), "path", "/accounts/84"],
    ["canonical page", () => {
      document.querySelector<HTMLElement>("script[data-bml-page-canonical]")!.dataset.bmlPage = "/profiles/{id}"
    }, "page", "/profiles/{id}"],
    ["canonical render path", () => {
      document.querySelector<HTMLElement>("script[data-bml-page-canonical]")!.dataset.bmlPagePath = "/profiles/84"
    }, "path", "/profiles/84"],
    ["server-selected locale", () => {
      const marker = document.querySelector<HTMLElement>("script[data-bml-page-canonical]")!
      marker.dataset.bmlPagePath = "/accounts/42"
      marker.dataset.bmlPageLocale = "de"
    }, "locale", "de"],
  ] as const)("retries a deferred render when the %s changes in flight", async (_label, changeContext, field, expected) => {
    const root = deferred("context-change", "Page:context-change")
    let releaseOld!: (response: Response) => void
    const oldResponse = new Promise<Response>((resolve) => { releaseOld = resolve })
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => oldResponse)
      .mockResolvedValueOnce(new Response("<span>Current</span>"))
    vi.stubGlobal("fetch", fetchMock)

    const loading = loadDeferredAll(root)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    changeContext()
    releaseOld(new Response("<span>Stale</span>"))
    await loading

    expect(fetchMock).toHaveBeenCalledTimes(2)
    const secondBody = JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))
    expect(secondBody[field]).toBe(expected)
    expect(root.textContent).toBe("Current")
    expect(root.dataset.bmlDeferredState).toBe("loaded")
  })

  it("discards a superseded response whose body finishes after the refresh", async () => {
    const root = deferred("streamed", "Page:streamed")
    let resolveBody: ((html: string) => void) | undefined
    const oldBody = new Promise<string>((resolve) => { resolveBody = resolve })
    const oldResponse = {
      ok: true,
      status: 200,
      headers: new Headers(),
      text: () => oldBody,
    } as Response
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(oldResponse)
      .mockResolvedValueOnce(new Response("<span>Current</span>"))
    vi.stubGlobal("fetch", fetchMock)

    const oldLoad = loadDeferredAll(root)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    resetDeferred(root)
    await loadDeferredAll(root)
    resolveBody!("<span>Stale</span>")
    await oldLoad

    expect(root.textContent).toBe("Current")
    expect(root.dataset.bmlDeferredState).toBe("loaded")
  })

  it("mounts authored island code only after the private fragment arrives", async () => {
    const root = deferred("mounted-after-load", "Page:mounted")
    const fetchMock = vi.fn(async () => new Response("<button>Ready</button>"))
    vi.stubGlobal("fetch", fetchMock)
    let mountedText: string | null = null
    defineIsland("mounted-after-load", (ctx) => {
      mountedText = ctx.root.textContent
    })

    await mountAll()

    expect(root.dataset.bmlDeferredState).toBe("loaded")
    expect(mountedText).toBe("Ready")
  })

  it("mounts eager islands while a deferred request is still in flight", async () => {
    const deferredRoot = deferred("slow-private", "Page:slow")
    deferredRoot.innerHTML = "<button data-bml-method=\"retry\">Loading</button>"
    const eagerRoot = document.createElement("div")
    eagerRoot.dataset.bmlIsland = "eager-during-deferred"
    document.body.append(eagerRoot)
    let resolveResponse: ((response: Response) => void) | undefined
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((resolve) => { resolveResponse = resolve })))
    let eagerMounted = false
    defineIsland("eager-during-deferred", () => { eagerMounted = true })

    const mounting = mountAll()
    await vi.waitFor(() => expect(eagerMounted).toBe(true))

    expect(deferredRoot.dataset.bmlDeferredState).toBe("loading")
    expect(deferredRoot.querySelector<HTMLElement>("[data-bml-method]")?.dataset.bmlActionBound).toBeUndefined()
    resolveResponse!(new Response("<span>Private</span>"))
    await mounting
    expect(deferredRoot.dataset.bmlDeferredState).toBe("loaded")
  })

  it("mounts a deferred root whose setup registers after loading starts", async () => {
    const root = deferred("late-registration", "Page:late-registration")
    let resolveResponse: ((response: Response) => void) | undefined
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((resolve) => { resolveResponse = resolve })))

    const initialLoad = loadDeferredAll(root)
    await vi.waitFor(() => expect(root.dataset.bmlDeferredState).toBe("loading"))
    let mounts = 0
    defineIsland("late-registration", () => { mounts++ })
    const laterMount = mountAll(root)

    resolveResponse!(new Response("<span>Private</span>"))
    await Promise.all([initialLoad, laterMount])

    expect(root.dataset.bmlDeferredState).toBe("loaded")
    expect(mounts).toBe(1)
  })

  it("keeps tagged props on the server wire and decodes them for client setup", async () => {
    const root = deferred("typed-deferred", "Page:typed")
    root.dataset.bmlProps = JSON.stringify({
      ids: { $bml: "list", value: [{ $bml: "long", value: "9007199254740993" }] },
      weights: { $bml: "map", value: { primary: { $bml: "float", value: "1.5" } } },
      ratios: { $bml: "list", value: [{ $bml: "double", value: "1.0" }] },
      samples: { $bml: "double-array", value: [{ $bml: "double", value: "1.0" }] },
      metadata: { $bml: "map", value: { $bml: "category", value: 1 } },
      codes: { $bml: "char-array", value: [{ $bml: "char", value: "x" }] },
    })
    const fetchMock = vi.fn(async () => new Response("<span>Typed</span>"))
    vi.stubGlobal("fetch", fetchMock)
    let mountedProps: Record<string, unknown> | undefined
    defineIsland("typed-deferred", (ctx) => { mountedProps = ctx.props })

    await mountAll()

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(String(init.body)).props.ids.value[0]).toEqual({
      $bml: "long",
      value: "9007199254740993",
    })
    expect(JSON.parse(String(init.body)).props.ratios.value[0]).toEqual({
      $bml: "double",
      value: "1.0",
    })
    expect(JSON.parse(String(init.body)).props.samples.value[0]).toEqual({
      $bml: "double",
      value: "1.0",
    })
    expect(mountedProps).toEqual({
      ids: [9007199254740993n],
      weights: { primary: 1.5 },
      ratios: [1],
      samples: [1],
      metadata: { $bml: "category", value: 1 },
      codes: ["x"],
    })
  })
})
