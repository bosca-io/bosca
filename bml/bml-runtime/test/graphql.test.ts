import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { authHeaders, bosca, configureBosca } from "../src/graphql"

describe("bosca graphql client", () => {
  beforeEach(() => {
    configureBosca({ endpoint: "https://x.test/graphql", getToken: () => "tok", getAnalyticsSessionId: undefined, getInstallationId: undefined })
  })
  afterEach(() => {
    configureBosca({ getAnalyticsSessionId: undefined, getInstallationId: undefined })
    vi.unstubAllGlobals()
  })

  it("posts the query with the auth header and parses data", async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response(JSON.stringify({ data: { hello: "world" } }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
    )
    vi.stubGlobal("fetch", fetchMock)

    const data = await bosca.query<{ hello: string }>("query { hello }")
    expect(data.hello).toBe("world")

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe("https://x.test/graphql")
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok")
    expect(String(init.body)).toContain("query { hello }")
  })

  it("throws on a graphql errors payload", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ errors: [{ message: "boom" }] }), { status: 200 })),
    )
    await expect(bosca.query("query { x }")).rejects.toThrow("boom")
  })

  it("reads the current analytics session on each query and mutation", async () => {
    let session: string | null = "session-a"
    configureBosca({ getAnalyticsSessionId: () => session })
    const fetchMock = vi.fn(async () => new Response('{"data":{}}'))
    vi.stubGlobal("fetch", fetchMock)
    await bosca.query("{ ok }")
    session = "session-b"
    await bosca.mutate("mutation { ok }")
    session = null
    await bosca.query("{ ok }")
    expect(fetchMock.mock.calls.map((call) => (call as unknown as [string, RequestInit])[1].headers))
      .toEqual([
        expect.objectContaining({ "X-BA-Session-ID": "session-a" }),
        expect.objectContaining({ "X-BA-Session-ID": "session-b" }),
        expect.not.objectContaining({ "X-BA-Session-ID": expect.anything() }),
      ])
  })

  it("reads the installation provider on each query and mutation", async () => {
    let installation: string | null = "installation-a"
    configureBosca({ getInstallationId: () => installation })
    const fetchMock = vi.fn(async () => new Response('{"data":{}}'))
    vi.stubGlobal("fetch", fetchMock)
    await bosca.query("{ ok }")
    installation = "installation-b"
    await bosca.mutate("mutation { ok }")
    installation = null
    await bosca.query("{ ok }")
    expect(fetchMock.mock.calls.map((call) => (call as unknown as [string, RequestInit])[1].headers))
      .toEqual([
        expect.objectContaining({ "X-Installation-ID": "installation-a" }),
        expect.objectContaining({ "X-Installation-ID": "installation-b" }),
        expect.not.objectContaining({ "X-Installation-ID": expect.anything() }),
      ])
  })

  it("omits missing installation IDs and replaces explicit headers case insensitively", () => {
    expect(authHeaders()["X-Installation-ID"]).toBeUndefined()
    for (const installation of [null, undefined, "", " "]) {
      configureBosca({ getInstallationId: () => installation })
      expect(authHeaders()["X-Installation-ID"]).toBeUndefined()
    }
    configureBosca({ getInstallationId: () => "current" })
    const headers = authHeaders({ "x-installation-id": "stale", "X-Trace": "trace" })
    expect(headers["x-installation-id"]).toBeUndefined()
    expect(headers["X-Installation-ID"]).toBe("current")
    expect(headers["X-Trace"]).toBe("trace")
  })

  it("omits absent and blank session identities and replaces stale header casing", () => {
    expect(authHeaders()["X-BA-Session-ID"]).toBeUndefined()
    for (const session of [null, undefined, "", " "]) {
      configureBosca({ getAnalyticsSessionId: () => session })
      expect(authHeaders()["X-BA-Session-ID"]).toBeUndefined()
    }
    configureBosca({ getAnalyticsSessionId: () => "current" })
    const headers = authHeaders({ "x-ba-session-id": "stale", "X-Trace": "trace" })
    expect(headers["x-ba-session-id"]).toBeUndefined()
    expect(headers["X-BA-Session-ID"]).toBe("current")
    expect(headers["X-Trace"]).toBe("trace")
  })
})
