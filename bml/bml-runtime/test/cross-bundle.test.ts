import { mkdtempSync, readFileSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { afterEach, describe, expect, it, vi } from "vitest"
// @ts-expect-error - plain .mjs build helper, no types
import { bundleIslands } from "../tools/bundle.mjs"

interface GlobalBundleApi {
  clear(): void
}

interface PageBundleApi {
  readonly endpoint: string
  readonly authorization?: string
  dispatch(): Promise<void>
}

describe("separately bundled runtime copies", () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    delete (globalThis as { __bmlGlobalBundle?: unknown }).__bmlGlobalBundle
    delete (globalThis as { __bmlPageBundle?: unknown }).__bmlPageBundle
    const host = globalThis as unknown as Record<symbol, unknown>
    delete host[Symbol.for("@bosca/bml/config")]
    delete host[Symbol.for("@bosca/bml/action-runtime")]
  })

  it("shares configuration and action cancellation", async () => {
    const dir = mkdtempSync(join(tmpdir(), "bml-cross-bundle-"))
    const runtime = join(process.cwd(), "src", "index.ts")
    writeFileSync(
      join(dir, "global.ts"),
      `import { clearBmlClientState, configureBosca } from ${JSON.stringify(runtime)}\n` +
        `configureBosca({ endpoint: "/configured", getToken: () => "shared-token" })\n` +
        `globalThis.__bmlGlobalBundle = { clear: () => clearBmlClientState({ reload: false }) }\n`,
    )
    writeFileSync(
      join(dir, "page.ts"),
      `import { authHeaders, boscaEndpoint, dispatchAction } from ${JSON.stringify(runtime)}\n` +
        `const headers = authHeaders()\n` +
        `globalThis.__bmlPageBundle = {\n` +
        `  endpoint: boscaEndpoint(),\n` +
        `  authorization: headers.Authorization,\n` +
        `  dispatch: () => dispatchAction("counter", "increment"),\n` +
        `}\n`,
    )
    const out = join(dir, "out")
    await bundleIslands({
      entryPoints: [join(dir, "global.ts"), join(dir, "page.ts")],
      outdir: out,
    })

    document.body.innerHTML =
      '<script type="application/json" data-bml-page="/"></script>' +
      '<script type="application/json" data-bml-state-key="counter">{"count":0}</script>'
    vi.spyOn(window.location, "reload").mockImplementation(() => undefined)
    Function(readFileSync(join(out, "global.js"), "utf8"))()
    Function(readFileSync(join(out, "page.js"), "utf8"))()
    const globalApi = (globalThis as { __bmlGlobalBundle: GlobalBundleApi }).__bmlGlobalBundle
    const pageApi = (globalThis as { __bmlPageBundle: PageBundleApi }).__bmlPageBundle

    expect(pageApi.endpoint).toBe("/configured")
    expect(pageApi.authorization).toBe("Bearer shared-token")

    let signal: AbortSignal | null = null
    vi.stubGlobal("fetch", vi.fn((_url: string, init?: RequestInit) => {
      const requestSignal = init?.signal as AbortSignal
      signal = requestSignal
      return new Promise<Response>((_resolve, reject) => {
        requestSignal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")), { once: true })
      })
    }))
    const pending = pageApi.dispatch()
    await vi.waitFor(() => expect(signal).not.toBeNull())

    globalApi.clear()

    expect(signal?.aborted).toBe(true)
    await pending
  })
})
