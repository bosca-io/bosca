import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { configureBosca } from "../src/graphql"
import { getNote, GetNote, type GetNoteData } from "./generated/GetNote"

/**
 * end-to-end: an island calls a *generated, typed* operation (`getNote`, the verbatim output of the
 * Bosca-native TypeScript generator) over the existing `@bosca/bml` `bosca` transport — no Apollo. Proves the
 * generated client posts the right document + typed variables and decodes a typed result.
 */
describe("generated typed GraphQL operation (no Apollo)", () => {
  beforeEach(() => {
    configureBosca({ endpoint: "https://x.test/graphql", getToken: () => "tok" })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it("posts the generated document + typed variables and decodes a typed result", async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response(JSON.stringify({ data: { note: { id: "9", visibility: "PRIVATE" } } }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
    )
    vi.stubGlobal("fetch", fetchMock)

    const data: GetNoteData = await getNote({ id: "9" })
    // typed end-to-end — these accesses would not compile if the result were `unknown`
    expect(data.note?.id).toBe("9")
    expect(data.note?.visibility).toBe("PRIVATE")

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe("https://x.test/graphql")
    const body = JSON.parse(String(init.body)) as { query: string; operationName: string; variables: { id: string } }
    expect(body.operationName).toBe("GetNote")
    expect(body.query).toBe(GetNote.query)
    expect(body.variables.id).toBe("9")
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok")
  })
})
