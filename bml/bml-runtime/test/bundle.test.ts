import { execFileSync } from "node:child_process"
// @vitest-environment node
import { existsSync, mkdtempSync, readFileSync, readdirSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { describe, expect, it } from "vitest"
// @ts-expect-error - plain .mjs build helper, no types
import { bundleGlobalAssets, bundleIslands, bundleProduction, routeSlug } from "../tools/bundle.mjs"

describe("esbuild island bundler", () => {
  it("bundles an island entry (with the runtime inlined) into a browser module", async () => {
    const dir = mkdtempSync(join(tmpdir(), "bml-bundle-"))
    const runtime = join(process.cwd(), "src", "index.ts")
    const entry = join(dir, "sortable.island.ts")
    writeFileSync(
      entry,
      `import { defineIsland } from ${JSON.stringify(runtime)}\n` +
        `defineIsland("sortable", (ctx) => { ctx.root.setAttribute("data-ok", "1") })\n`,
    )
    const out = join(dir, "out")

    await bundleIslands({ entryPoints: [entry], outdir: out })

    const files = readdirSync(out).filter((f) => f.endsWith(".js"))
    expect(files.length).toBe(1)
    const js = readFileSync(join(out, files[0]), "utf8")
    // the island's own code is bundled in (literals survive minification);
    // unused runtime (mountAll) is tree-shaken away — which is the point.
    expect(js).toContain("sortable")
    expect(js).toContain("data-ok")
    expect(js).not.toContain("BmlEvaluateFeatureFlag")
    expect(js).not.toContain("bml_iid")
    // bundled — no remaining bare import statements
    expect(js).not.toMatch(/(^|\n)\s*import\s/)
  })

  it("keeps deferred loading out of ordinary interactive bundles", async () => {
    const dir = mkdtempSync(join(tmpdir(), "bml-bundle-no-deferred-"))
    const runtime = join(process.cwd(), "src", "index.ts")
    const entry = join(dir, "page.ts")
    writeFileSync(entry, `import { mountAll } from ${JSON.stringify(runtime)}\nmountAll()\n`)
    const out = join(dir, "out")

    await bundleIslands({ entryPoints: [entry], outdir: out, minify: true })

    const js = readFileSync(join(out, "page.js"), "utf8")
    expect(js).not.toContain("/_bml/deferred/")
    expect(js).not.toContain("/_bml/identity")
  })

})

describe("production bundler", () => {
  it("slugs routes the same way the server does", () => {
    expect(routeSlug("/")).toBe("index")
    expect(routeSlug("/cart")).toBe("cart")
    expect(routeSlug("/lists/{id}")).toBe("lists-id")
  })

  it("merges a page's module and its closure components' into one bundle per page", async () => {
    const dir = mkdtempSync(join(tmpdir(), "bml-prod-"))
    writeFileSync(
      join(dir, "HomePage.ts"),
      `const descriptiveInternalValue = "home-page-code"\n` +
        `globalThis.bmlPublicApi = { publicMethod: () => descriptiveInternalValue }\n`,
    )
    writeFileSync(join(dir, "CardPage.ts"), `console.log("card-component-code")\n`)
    writeFileSync(join(dir, "CartPage.ts"), `console.log("cart-page-code")\n`)
    const manifest = join(dir, "manifest.tsv")
    writeFileSync(
      manifest,
      "bml-metrics\t1\n" +
        "P\tHomePage\t/\tHomePage.js\tbadge\tCardPage.js\n" +
        "P\tCartPage\t/cart\tCartPage.js\t-\t-\n" +
        "P\tDashboardPage\t/dashboard\t-\t-\t-\n",
    )
    const out = join(dir, "js-prod")

    const written = await bundleProduction({ manifestPath: manifest, inDir: dir, outdir: out })

    // One bundle per page WITH client code; the js-less dashboard produces nothing.
    expect(written.sort()).toEqual(["cart.page.js", "index.page.js"])
    const home = readFileSync(join(out, "index.page.js"), "utf8")
    expect(home).toContain("home-page-code")
    expect(home).toContain("card-component-code")
    expect(home).not.toContain("cart-page-code")
    expect(home).toContain("bmlPublicApi")
    expect(home).toContain("publicMethod")
    expect(home).not.toContain("descriptiveInternalValue")
    expect(existsSync(join(out, "index.page.js.map"))).toBe(false)
    const cart = readFileSync(join(out, "cart.page.js"), "utf8")
    expect(cart).toContain("cart-page-code")
    expect(cart).not.toMatch(/(^|\n)\s*import\s/)
  })

  it("reserves a colliding slug for a CSS-only page before naming a client bundle", async () => {
    const dir = mkdtempSync(join(tmpdir(), "bml-prod-css-slug-"))
    writeFileSync(join(dir, "ClientPage.ts"), 'console.log("client page")')
    const manifest = join(dir, "manifest.tsv")
    writeFileSync(
      manifest,
      "bml-metrics\t1\n" +
        "P\tStylePage\t/a-b\t-\tbadge\t-\n" +
        "P\tClientPage\t/a/b\tClientPage.js\t-\t-\n",
    )
    const out = join(dir, "js-prod")

    expect(await bundleProduction({ manifestPath: manifest, inDir: dir, outdir: out }))
      .toEqual(["a-b-2.page.js"])
    expect(readFileSync(join(out, "a-b-2.page.js"), "utf8")).toContain("client page")
    expect(existsSync(join(out, "a-b.page.js"))).toBe(false)
  })

  it("writes an empty bundle when a page's declared client modules produced no TypeScript", async () => {
    const dir = mkdtempSync(join(tmpdir(), "bml-prod-empty-"))
    const manifest = join(dir, "manifest.tsv")
    writeFileSync(
      manifest,
      "bml-metrics\t1\n" +
        "P\tQuietPage\t/quiet\tQuietPage.js\t-\t-\n",
    )
    const out = join(dir, "js-prod")

    expect(await bundleProduction({ manifestPath: manifest, inDir: dir, outdir: out }))
      .toEqual(["quiet.page.js"])
    // bml-server links /_bml/js/quiet.page.js for this page, so the file must exist.
    expect(readFileSync(join(out, "quiet.page.js"), "utf8")).toBe("")
  })

  it("writes empty page bundles from the command line when a site has no TypeScript at all", () => {
    const dir = mkdtempSync(join(tmpdir(), "bml-prod-cli-empty-"))
    const manifest = join(dir, "manifest.tsv")
    writeFileSync(manifest, "bml-metrics\t1\nP\tQuietPage\t/quiet\tQuietPage.js\t-\t-\n")
    const prod = join(dir, "js-prod")

    execFileSync(process.execPath, [
      join(__dirname, "..", "tools", "bundle.mjs"),
      "--in", join(dir, "ts-missing"),
      "--out", join(dir, "js"),
      "--manifest", manifest,
      "--prod-out", prod,
    ])

    // bml-server links /_bml/js/quiet.page.js for this page, so the file must exist.
    expect(readFileSync(join(prod, "quiet.page.js"), "utf8")).toBe("")
  })

  it("does not reserve a slug when a page's CSS is entirely shared", async () => {
    const dir = mkdtempSync(join(tmpdir(), "bml-prod-shared-css-slug-"))
    writeFileSync(join(dir, "ClientPage.ts"), 'console.log("client page")')
    const manifest = join(dir, "manifest.tsv")
    writeFileSync(
      manifest,
      "bml-metrics\t1\n" +
        "P\tStylePage\t/a-b\t-\tbadge\t-\n" +
        "P\tClientPage\t/a/b\tClientPage.js\tbadge\t-\n",
    )
    const out = join(dir, "js-prod")

    expect(await bundleProduction({ manifestPath: manifest, inDir: dir, outdir: out }))
      .toEqual(["a-b.page.js"])
    expect(existsSync(join(out, "a-b-2.page.js"))).toBe(false)
  })
})

describe("global production assets", () => {
  it("minifies CSS and safely mangles local JS identifiers while preserving public properties", async () => {
    const dir = mkdtempSync(join(tmpdir(), "bml-global-"))
    writeFileSync(
      join(dir, "site.ts"),
      `const deliberatelyLongInternalName = 42\n` +
        `const identityRuntime = globalThis[Symbol.for("@bosca/bml/deferred-runtime")]\n` +
        `if (typeof identityRuntime?.identityProvider !== "function") throw new Error("missing identity bootstrap")\n` +
        `globalThis.stablePublicName = { publicMethod: () => deliberatelyLongInternalName }\n`,
    )
    const authoredCss = `/* remove me */\n.site { color: red; margin: 0 0 0 0; background: url("/fonts/site.woff2"); }\n`
    writeFileSync(join(dir, "site.css"), authoredCss)
    const out = join(dir, "out")

    const written = await bundleGlobalAssets({ inDir: dir, outdir: out })

    expect(written).toEqual(["site.css", "site.js"])
    const js = readFileSync(join(out, "site.js"), "utf8")
    expect(js).toContain("stablePublicName")
    expect(js).toContain("publicMethod")
    expect(js).not.toContain("deliberatelyLongInternalName")
    expect(js).toContain("/_bml/identity")
    expect(js).not.toContain("/_bml/deferred/")
    expect(existsSync(join(out, "site.js.map"))).toBe(false)
    expect(() => Function(js)()).not.toThrow()
    const css = readFileSync(join(out, "site.css"), "utf8")
    expect(css).not.toContain("remove me")
    expect(css).toMatch(/url\(["']?\/fonts\/site\.woff2["']?\)/)
    expect(css.length).toBeLessThan(authoredCss.length)
  })
})
