import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { renderFragment, RENDER_PREFIX } from "../src/fragment"
import { configureBosca } from "../src/graphql"

describe("renderFragment (sliver re-render)", () => {
  beforeEach(() => {
    configureBosca({ getToken: () => "tok", getAnalyticsSessionId: () => "analytics-session", getInstallationId: () => "installation" })
    document.documentElement.lang = ""
    document.body.replaceChildren()
  })
  afterEach(() => {
    document.cookie = "bml_locale=; Max-Age=0; Path=/"
    vi.unstubAllGlobals()
  })

  it("POSTs props to /_bml/render/<component> with the auth header and returns the HTML", async () => {
    const fetchMock = vi.fn(
      async () => new Response("<strong>Count: 7</strong>", { status: 200 }),
    )
    vi.stubGlobal("fetch", fetchMock)

    const html = await renderFragment("counter-view", { count: 7 })
    expect(html).toBe("<strong>Count: 7</strong>")

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(`${RENDER_PREFIX}counter-view`)
    expect(init.method).toBe("POST")
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok")
    expect((init.headers as Record<string, string>)["X-BA-Session-ID"]).toBe("analytics-session")
    expect((init.headers as Record<string, string>)["X-Installation-ID"]).toBe("installation")
    expect(String(init.body)).toBe(JSON.stringify({ count: 7 }))
  })

  it("encodes the component name in the URL", async () => {
    const fetchMock = vi.fn(async () => new Response("", { status: 200 }))
    vi.stubGlobal("fetch", fetchMock)
    await renderFragment("a/b")
    expect((fetchMock.mock.calls[0] as [string])[0]).toBe(`${RENDER_PREFIX}a%2Fb`)
  })

  it("uses the shared page's locale even when its cookie and html lang differ", async () => {
    document.cookie = "bml_locale=fr; Path=/"
    document.documentElement.lang = "fr"
    document.body.innerHTML = '<script data-bml-page-canonical data-bml-page-path="/shared" data-bml-page-locale="en"></script>'
    const fetchMock = vi.fn(async () => new Response("", { status: 200 }))
    vi.stubGlobal("fetch", fetchMock)

    await renderFragment("counter-view")
    expect((fetchMock.mock.calls[0] as [string])[0]).toBe(`${RENDER_PREFIX}counter-view?lang=en`)
  })

  it("throws on a non-2xx response", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("nope", { status: 500 })))
    await expect(renderFragment("counter-view", {})).rejects.toThrow("HTTP 500")
  })
})
