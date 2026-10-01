import { afterEach, describe, expect, it, vi } from "vitest"
import { bosca, boscaEndpoint, configureBosca } from "../src/graphql"

describe("bosca graphql client — branches", () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    configureBosca({ endpoint: "/graphql" })
  })

  it("boscaEndpoint reflects configuration", () => {
    configureBosca({ endpoint: "/custom-graphql" })
    expect(boscaEndpoint()).toBe("/custom-graphql")
  })

  it("query sends a string query with variables and returns data", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ data: { x: 1 } }), { status: 200 }))
    vi.stubGlobal("fetch", fetchMock)

    expect(await bosca.query<{ x: number }>("query { x }", { a: 1 })).toEqual({ x: 1 })
    expect(JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body)))
      .toEqual({ query: "query { x }", variables: { a: 1 } })
  })

  it("mutate sends an operation object, preferring its own variables and operationName", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ data: { ok: true } }), { status: 200 }))
    vi.stubGlobal("fetch", fetchMock)

    await bosca.mutate({ query: "mutation { go }", variables: { v: 9 }, operationName: "Go" }, { v: 0 })
    expect(JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body)))
      .toEqual({ query: "mutation { go }", variables: { v: 9 }, operationName: "Go" })
  })

  it("throws on a non-2xx HTTP status", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("nope", { status: 503 })))
    await expect(bosca.query("query { x }")).rejects.toThrow("HTTP 503")
  })

  it("throws on GraphQL errors, joining the messages", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ errors: [{ message: "a" }, { message: "b" }] }), { status: 200 })))
    await expect(bosca.query("query { x }")).rejects.toThrow("a; b")
  })
})
