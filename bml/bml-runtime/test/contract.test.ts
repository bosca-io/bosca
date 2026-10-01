import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { bmlContractCall } from "../src/contract"
import { configureBosca } from "../src/graphql"

describe("bmlContractCall (typed <contract> client)", () => {
  beforeEach(() => {
    configureBosca({ getToken: () => "tok", getAnalyticsSessionId: () => "analytics-session", getInstallationId: () => "installation" })
    document.documentElement.lang = ""
    document.body.replaceChildren()
  })
  afterEach(() => {
    document.cookie = "bml_locale=; Max-Age=0; Path=/"
    vi.unstubAllGlobals()
  })

  it("POSTs args to /_bml/contract/<contract>/<method> with auth and returns the typed result", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ ok: 1 }), { status: 200 }))
    vi.stubGlobal("fetch", fetchMock)

    const r = await bmlContractCall<{ ok: number }>("Todos", "add", ["x", 2])
    expect(r).toEqual({ ok: 1 })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe("/_bml/contract/Todos/add")
    expect(init.method).toBe("POST")
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok")
    expect((init.headers as Record<string, string>)["X-BA-Session-ID"]).toBe("analytics-session")
    expect((init.headers as Record<string, string>)["X-Installation-ID"]).toBe("installation")
    expect(JSON.parse(String(init.body))).toEqual(["x", 2])
  })

  it("throws on a non-2xx response", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("no", { status: 500 })))
    await expect(bmlContractCall("C", "m", [])).rejects.toThrow("HTTP 500")
  })

  it("uses the shared page's locale even when its cookie and html lang differ", async () => {
    document.cookie = "bml_locale=fr; Path=/"
    document.documentElement.lang = "fr"
    document.body.innerHTML = '<script data-bml-page-canonical data-bml-page-path="/shared" data-bml-page-locale="en"></script>'
    const fetchMock = vi.fn(async () => new Response("{}", { status: 200 }))
    vi.stubGlobal("fetch", fetchMock)

    await bmlContractCall("Todos", "add", [])
    expect((fetchMock.mock.calls[0] as [string])[0]).toBe("/_bml/contract/Todos/add?lang=en")
  })
})
