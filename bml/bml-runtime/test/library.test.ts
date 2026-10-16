// @vitest-environment node
import { createHash } from "node:crypto"
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, renameSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { dirname, join } from "node:path"
import { afterEach, describe, expect, it } from "vitest"
// @ts-expect-error - plain .mjs build helper
import { bundleLibraryAssets, bundleLibraryStyles, libraryAssetNamespace } from "../tools/library.mjs"
// @ts-expect-error - plain .mjs build helper
import { bundleIslands } from "../tools/bundle.mjs"

const directories: string[] = []
afterEach(() => directories.splice(0).forEach((dir) => rmSync(dir, { recursive: true, force: true })))
function fixture(projectDir = mkdtempSync(join(tmpdir(), "bml-library-assets-"))) {
  directories.push(projectDir)
  const sourceRoot = join(projectDir, "src/main/bml"), clientDir = join(projectDir, "src/main/client")
  const publicDir = join(projectDir, "public"), inDir = join(projectDir, "build/ts")
  const stylesDir = join(projectDir, "build/styles"), outdir = join(projectDir, "build/assets")
  const sourcesOut = join(projectDir, "build/sources"), manifestPath = join(projectDir, "build/manifest.json")
  const write = (path: string, value: string) => { mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, value) }
  write(manifestPath, JSON.stringify({ library: "example:design", version: "1.0", components: [{ clientModule: "ButtonPage.js" }] }))
  write(join(inDir, "bml-origins.json"), JSON.stringify({ "ButtonPage.ts": "controls/button.bml" }))
  write(join(sourceRoot, "controls/button.bml"), "<component tag=\"button\"/>")
  write(join(inDir, "ButtonPage.ts"), `import { defineComponent } from '@bosca/bml'; import { label } from './button'; defineComponent('button', () => label);`)
  write(join(sourceRoot, "controls/button.ts"), `import { suffix } from 'private-label'; import './button.css'; export const label = 'Button' + suffix;`)
  write(join(sourceRoot, "controls/button.css"), `.button { background: url('./icon.svg'); }`)
  write(join(sourceRoot, "controls/icon.svg"), '<svg xmlns="http://www.w3.org/2000/svg"><text>icon</text></svg>')
  write(join(projectDir, "node_modules/private-label/package.json"), JSON.stringify({ name: "private-label", main: "index.js" }))
  write(join(projectDir, "node_modules/private-label/index.js"), `export const suffix = '-private-dependency';`)
  return { projectDir, sourceRoot, clientDir, publicDir, inDir, stylesDir, outdir, sourcesOut, manifestPath, write }
}

describe("published library assets", () => {
  it.each(["external modules", "local modules", "scope"])("preserves package ownership under linked %s directories", async (layout) => {
    const f = fixture(), shared = mkdtempSync(join(tmpdir(), "bml-installed-packages-"))
    directories.push(shared)
    const store = layout === "local modules" ? join(f.projectDir, "installed-packages") : join(shared, "packages")
    if (layout !== "scope") renameSync(join(f.projectDir, "node_modules"), store)
    const dependency = join(store, "@design/token")
    f.write(join(dependency, "package.json"), JSON.stringify({ name: "@design/token", main: "index.js" }))
    f.write(join(dependency, "index.js"), `import './theme.css'; export const suffix = '-linked-directory';`)
    f.write(join(dependency, "theme.css"), `.linked { background: url('./icon.svg'); }`)
    f.write(join(dependency, "icon.svg"), '<svg xmlns="http://www.w3.org/2000/svg"><text>dependency-icon</text></svg>')
    symlinkSync(layout === "scope" ? join(store, "@design") : store,
      join(f.projectDir, layout === "scope" ? "node_modules/@design" : "node_modules"), "junction")
    f.write(join(f.sourceRoot, "controls/button.ts"), `import { suffix } from 'private-label'; import { suffix as linked } from '@design/token'; export const label = 'Button' + suffix + linked;`)
    const input = join(f.projectDir, "styles-input.json")
    f.write(input, JSON.stringify([{ key: "button:1", source: "controls/button.bml", css: `@import '@design/token/theme.css';`, digest: "original" }]))
    await bundleLibraryStyles({ ...f, input, outdir: f.stylesDir, library: "example:design", version: "1.0" })
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    expect(metadata.sources.map((source: { path: string }) => source.path).sort()).toEqual([
      "bml/controls/button.bml", "bml/controls/button.css", "bml/controls/button.ts", "bml/controls/icon.svg",
    ])
    expect(metadata.files.some((file: { path: string }) => file.path.endsWith(".svg"))).toBe(true)
    expect(readFileSync(join(f.stylesDir, "styles.json"), "utf8")).toContain(".linked")
    const module = metadata.modules["ButtonPage.js"]
    expect(module.styles).toHaveLength(1)
    expect(readFileSync(join(f.outdir, metadata.resourcePrefix, module.styles[0]), "utf8")).toContain(".linked")
    rmSync(join(f.projectDir, "src"), { recursive: true })
    rmSync(join(f.projectDir, "node_modules"), { recursive: true })
    rmSync(store, { recursive: true })
    const runtime = join(f.projectDir, "consumer/runtime.ts"), consumer = join(f.projectDir, "consumer/js")
    f.write(runtime, `export function defineComponent(tag, setup) { globalThis.linkedDirectoryLabel = setup(); }`)
    await bundleIslands({ entryPoints: [join(f.outdir, metadata.resourcePrefix, module.path)], outdir: consumer, runtime })
    new Function(readFileSync(join(consumer, "ButtonPage.js"), "utf8"))()
    expect((globalThis as any).linkedDirectoryLabel).toBe("Button-private-dependency-linked-directory")
    delete (globalThis as any).linkedDirectoryLabel
  })

  it("bundles inline CSS dependencies before scoping and preserves URL suffixes and remote references", async () => {
    const f = fixture(), input = join(f.projectDir, "styles-input.json")
    const css = `@import './theme.css'; .inline { background: url('/logo.svg#mark'); mask: url('https://example.test/mask.svg'); }`
    f.write(join(f.sourceRoot, "controls/theme.css"), `@font-face { font-family: Design; src: url('./fonts/design.woff2'); } .theme { color: red; }`)
    f.write(join(f.sourceRoot, "controls/fonts/design.woff2"), "font-bytes")
    f.write(join(f.publicDir, "logo.svg"), '<svg xmlns="http://www.w3.org/2000/svg"><g id="mark"/></svg>')
    f.write(input, JSON.stringify([{ key: "controls/button.bml:5", source: "controls/button.bml", css, digest: "original" }]))
    await bundleLibraryStyles({ ...f, input, outdir: f.stylesDir, library: "example:design", version: "1.0" })
    const prepared = JSON.parse(readFileSync(join(f.stylesDir, "styles.json"), "utf8"))["controls/button.bml:5"]
    expect(prepared.digest).toBe("original")
    expect(prepared.css).toContain(".theme")
    expect(prepared.css).not.toContain("@import")
    expect(prepared.css).toContain("/_bml/libraries/")
    expect(prepared.css).toContain(".svg#mark")
    expect(prepared.css).toContain("https://example.test/mask.svg")
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    expect(metadata.files.some((file: { path: string }) => file.path.endsWith(".woff2"))).toBe(true)
    for (const file of metadata.files) {
      const bytes = readFileSync(join(f.outdir, metadata.resourcePrefix, file.path))
      expect(file.digest).toBe(`sha256:${createHash("sha256").update(bytes).digest("hex")}`)
    }
  })

  it("packages inline and nested standalone code and consumes ESM without the producer or its npm dependencies", async () => {
    const f = fixture()
    f.write(join(f.clientDir, "theme.css"), `@import './nested/tokens.css';`)
    f.write(join(f.clientDir, "nested/tokens.css"), `:root { --design-accent: #123456; }`)
    f.write(join(f.clientDir, "startup.ts"), `import { value } from './nested/helper'; globalThis.libraryValue = value;`)
    f.write(join(f.clientDir, "nested/helper.ts"), `export const value = 'nested-helper';`)
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    const module = join(f.outdir, metadata.resourcePrefix, metadata.modules["ButtonPage.js"].path)
    const js = readFileSync(module, "utf8")
    expect(js).toContain('"@bosca/bml"')
    expect(js).toContain("-private-dependency")
    expect(js).not.toMatch(/from ["']private-label/)
    expect(metadata.modules["ButtonPage.js"].styles).toHaveLength(1)
    expect(readFileSync(join(f.outdir, metadata.resourcePrefix, "styles/theme.css"), "utf8")).toContain("--design-accent")
    expect(existsSync(join(f.sourcesOut, "bml/controls/button.ts"))).toBe(true)
    expect(existsSync(join(f.sourcesOut, "client/nested/helper.ts"))).toBe(true)
    rmSync(join(f.projectDir, "src"), { recursive: true })
    rmSync(join(f.projectDir, "node_modules"), { recursive: true })
    const runtime = join(f.projectDir, "consumer/runtime.ts")
    f.write(runtime, `export function defineComponent(tag, setup) { globalThis.result = [tag, setup()]; }`)
    const consumer = join(f.projectDir, "consumer/js")
    await bundleIslands({ entryPoints: [module], outdir: consumer, runtime })
    const browserCode = readFileSync(join(consumer, "ButtonPage.js"), "utf8")
    expect(browserCode).not.toMatch(/from ["']@bosca/)
    expect(browserCode).toContain("-private-dependency")
    new Function(browserCode)()
    expect((globalThis as any).result).toEqual(["button", "Button-private-dependency"])
    delete (globalThis as any).result
  })

  it.each(["", "nested"])("bundles only imported npm code from client dependencies under '%s'", async (directory) => {
    const f = fixture(), client = join(f.clientDir, directory)
    const dependency = join(client, "node_modules/@design/token")
    f.write(join(dependency, "package.json"), JSON.stringify({ name: "@design/token", exports: "./index.js" }))
    f.write(join(dependency, "index.js"), `import './theme.css'; export const label = 'browser-dependency';`)
    f.write(join(dependency, "theme.css"), `.dependency { background: url('./icon.svg'); }`)
    f.write(join(dependency, "icon.svg"), '<svg xmlns="http://www.w3.org/2000/svg"/>')
    f.write(join(dependency, "server-tool.js"), `import { readFileSync } from 'node:fs'; console.log(readFileSync('package.json'));`)
    f.write(join(dependency, "unused.css"), `.unused { background: url('./not-shipped.png'); }`)
    f.write(join(dependency, "internal-source.ts"), `export const internal = 'unused-private-source';`)
    f.write(join(client, "startup.ts"), `import { label } from '@design/token'; globalThis.npmLabel = label;`)
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    const relative = directory ? `${directory}/startup` : "startup"
    expect(metadata.sources.map((source: { path: string }) => source.path)).toContain(`client/${relative}.ts`)
    expect(metadata.sources.some((source: { path: string }) => source.path.includes("node_modules"))).toBe(false)
    expect(metadata.files.some((file: { path: string }) => file.path.includes("node_modules"))).toBe(false)
    expect(metadata.files.some((file: { path: string }) => file.path.endsWith(".svg"))).toBe(true)
    expect(readFileSync(join(f.outdir, metadata.resourcePrefix, `client/${relative}.css`), "utf8")).toContain(".dependency")
    rmSync(join(f.projectDir, "src"), { recursive: true })
    rmSync(join(f.projectDir, "node_modules"), { recursive: true })
    const consumer = join(f.projectDir, "consumer")
    await bundleIslands({ entryPoints: [join(f.outdir, metadata.resourcePrefix, `client/${relative}.js`)], outdir: consumer })
    const js = readFileSync(join(consumer, "startup.js"), "utf8")
    expect(js).not.toContain("unused-private-source")
    new Function(js)()
    expect((globalThis as any).npmLabel).toBe("browser-dependency")
    delete (globalThis as any).npmLabel
  })

  it("packages browser-disabled JavaScript and CSS dependencies without treating them as source files", async () => {
    const f = fixture(), dependency = join(f.projectDir, "node_modules/private-label")
    f.write(join(dependency, "package.json"), JSON.stringify({ name: "private-label", main: "index.js",
      browser: { "./optional.js": false, "./missing.js": false, fs: false, "./server.css": false },
    }))
    f.write(join(dependency, "optional.js"), `throw new Error('disabled-server-code');`)
    f.write(join(dependency, "index.js"), `import optional from './optional.js'; import missing from './missing.js'; import fs from 'fs'; import './theme.css'; export const suffix = typeof optional.nodeOnly === 'undefined' && typeof missing.nodeOnly === 'undefined' && typeof fs.readFile === 'undefined' ? '-browser' : '-server';`)
    f.write(join(dependency, "theme.css"), `@import './server.css'; .browser-theme { color: red; }`)
    f.write(join(dependency, "server.css"), `.disabled-server-style { color: blue; }`)
    const input = join(f.projectDir, "styles-input.json")
    f.write(input, JSON.stringify([{ key: "button:1", source: "controls/button.bml", css: `@import 'private-label/theme.css';`, digest: "original" }]))
    await bundleLibraryStyles({ ...f, input, outdir: f.stylesDir, library: "example:design", version: "1.0" })
    const prepared = JSON.parse(readFileSync(join(f.stylesDir, "styles.json"), "utf8"))["button:1"]
    expect(prepared.css).toContain(".browser-theme")
    expect(prepared.css).not.toContain(".disabled-server-style")
    const inputs: string[] = JSON.parse(readFileSync(join(f.stylesDir, "inputs.json"), "utf8"))
    expect(inputs.map((path) => realpathSync(path))).toEqual([realpathSync(join(dependency, "theme.css"))])
    expect(inputs.some((path) => path.includes("(disabled):"))).toBe(false)
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    expect(metadata.sources.map((file: { path: string }) => file.path)).toContain("bml/controls/button.ts")
    expect(metadata.sources.some((file: { path: string }) => /\(disabled\)|node_modules/.test(file.path))).toBe(false)
    const module = metadata.modules["ButtonPage.js"]
    const css = module.styles.map((path: string) => readFileSync(join(f.outdir, metadata.resourcePrefix, path), "utf8")).join("\n")
    expect(css).toContain(".browser-theme")
    expect(css).not.toContain(".disabled-server-style")
    rmSync(join(f.projectDir, "src"), { recursive: true })
    rmSync(join(f.projectDir, "node_modules"), { recursive: true })
    const runtime = join(f.projectDir, "runtime.ts"), consumer = join(f.projectDir, "consumer")
    f.write(runtime, `export function defineComponent(tag, setup) { globalThis.browserLabel = setup(); }`)
    await bundleIslands({ entryPoints: [join(f.outdir, metadata.resourcePrefix, module.path)], outdir: consumer, runtime })
    const js = readFileSync(join(consumer, "ButtonPage.js"), "utf8")
    expect(js).not.toContain("disabled-server-code")
    new Function(js)()
    expect((globalThis as any).browserLabel).toBe("Button-browser")
    delete (globalThis as any).browserLabel
  })

  it("rejects a real CSS dependency removed after style preparation", async () => {
    const f = fixture(), input = join(f.projectDir, "styles-input.json")
    const theme = join(f.sourceRoot, "controls/theme.css")
    f.write(theme, `.theme { color: red; }`)
    f.write(input, JSON.stringify([{ key: "button:1", source: "controls/button.bml", css: `@import './theme.css';`, digest: "original" }]))
    await bundleLibraryStyles({ ...f, input, outdir: f.stylesDir, library: "example:design", version: "1.0" })
    const canonical = realpathSync(theme)
    rmSync(theme)
    await expect(bundleLibraryAssets(f)).rejects.toThrow(`Missing library asset source ${canonical}`)
  })

  it("bundles data URL modules and styles while inventorying their physical importers", async () => {
    const f = fixture(), input = join(f.projectDir, "styles-input.json")
    f.write(join(f.sourceRoot, "controls/button.ts"), `import value from 'data:text/javascript,export default 42'; export const label = value;`)
    const theme = join(f.projectDir, "theme.css")
    f.write(theme, `@import 'data:text/css,.embedded%7Bcolor:red%7D'; .theme { color: blue; }`)
    f.write(input, JSON.stringify([{ key: "button:1", source: "controls/button.bml", css: `@import '../../../../theme.css?theme';`, digest: "original" }]))
    await bundleLibraryStyles({ ...f, input, outdir: f.stylesDir, library: "example:design", version: "1.0" })
    const prepared = JSON.parse(readFileSync(join(f.stylesDir, "styles.json"), "utf8"))["button:1"]
    expect(prepared.css).toContain(".embedded")
    const inputs: string[] = JSON.parse(readFileSync(join(f.stylesDir, "inputs.json"), "utf8"))
    expect(inputs.map((path) => realpathSync(path))).toEqual([realpathSync(theme)])
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    expect(metadata.sources.map((file: { path: string }) => file.path)).toContain("assets/theme.css")
    rmSync(join(f.projectDir, "src"), { recursive: true })
    rmSync(theme)
    const runtime = join(f.projectDir, "runtime.ts"), consumer = join(f.projectDir, "consumer")
    f.write(runtime, `export function defineComponent(tag, setup) { globalThis.dataLabel = setup(); }`)
    await bundleIslands({ entryPoints: [join(f.outdir, metadata.resourcePrefix, metadata.modules["ButtonPage.js"].path)], outdir: consumer, runtime })
    new Function(readFileSync(join(consumer, "ButtonPage.js"), "utf8"))()
    expect((globalThis as any).dataLabel).toBe(42)
    delete (globalThis as any).dataLabel
  })

  it("archives symlinked public files and directories at every authored path", async () => {
    const f = fixture(), shared = mkdtempSync(join(tmpdir(), "bml-shared-public-"))
    directories.push(shared)
    f.write(join(shared, "logo.svg"), '<svg xmlns="http://www.w3.org/2000/svg"/>')
    f.write(join(shared, "fonts/design.woff2"), "font bytes")
    mkdirSync(f.publicDir, { recursive: true })
    symlinkSync(join(shared, "logo.svg"), join(f.publicDir, "logo.svg"))
    symlinkSync(join(shared, "fonts"), join(f.publicDir, "fonts"), "junction")
    symlinkSync(join(shared, "fonts"), join(f.publicDir, "font-alias"), "junction")
    const input = join(f.projectDir, "styles-input.json")
    f.write(input, JSON.stringify([{ key: "button:1", source: "controls/button.bml", css: `.logo { background: url('/logo.svg#mark'); }`, digest: "original" }]))
    await bundleLibraryStyles({ ...f, input, outdir: f.stylesDir, library: "example:design", version: "1.0" })
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    rmSync(shared, { recursive: true })
    for (const path of ["public/logo.svg", "public/fonts/design.woff2", "public/font-alias/design.woff2"]) {
      const bytes = readFileSync(join(f.outdir, metadata.resourcePrefix, path))
      expect(readFileSync(join(f.sourcesOut, path))).toEqual(bytes)
      const digest = `sha256:${createHash("sha256").update(bytes).digest("hex")}`
      expect(metadata.files).toContainEqual({ path, digest })
      expect(metadata.sources).toContainEqual({ path, digest })
    }
  })

  it("preserves source paths under a symlinked BML root while bundling canonical inputs", async () => {
    const f = fixture(), shared = mkdtempSync(join(tmpdir(), "bml-shared-source-"))
    directories.push(shared)
    const markup = join(shared, "markup")
    renameSync(f.sourceRoot, markup)
    symlinkSync(markup, f.sourceRoot, "junction")
    f.write(join(f.sourceRoot, "controls/button.ts"), `import './button.css'; export const label = 'linked-source';`)
    const input = join(f.projectDir, "styles-input.json")
    f.write(input, JSON.stringify([{ key: "button:1", source: "controls/button.bml", css: `@import './button.css';`, digest: "original" }]))
    await bundleLibraryStyles({ ...f, input, outdir: f.stylesDir, library: "example:design", version: "1.0" })
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    const paths = metadata.sources.map((file: { path: string }) => file.path)
    expect(paths).toEqual(["bml/controls/button.bml", "bml/controls/button.css", "bml/controls/button.ts", "bml/controls/icon.svg"])
    rmSync(shared, { recursive: true })
    rmSync(join(f.projectDir, "src"), { recursive: true })
    const runtime = join(f.projectDir, "runtime.ts"), consumer = join(f.projectDir, "consumer")
    f.write(runtime, `export function defineComponent(tag, setup) { globalThis.linkedSourceLabel = setup(); }`)
    await bundleIslands({ entryPoints: [join(f.outdir, metadata.resourcePrefix, metadata.modules["ButtonPage.js"].path)], outdir: consumer, runtime })
    new Function(readFileSync(join(consumer, "ButtonPage.js"), "utf8"))()
    expect((globalThis as any).linkedSourceLabel).toBe("linked-source")
    delete (globalThis as any).linkedSourceLabel
  })

  it.each([false, true])("resolves inline npm and package imports from their BML origin (producer package: %s)", async (shadowed) => {
    const f = fixture(), shared = mkdtempSync(join(tmpdir(), "bml-inline-origin-"))
    directories.push(shared)
    const markup = join(shared, "markup")
    renameSync(f.sourceRoot, markup)
    symlinkSync(markup, f.sourceRoot, "junction")
    if (!shadowed) rmSync(join(f.projectDir, "node_modules"), { recursive: true })
    f.write(join(shared, "package.json"), JSON.stringify({ imports: { "#label": "private-label" } }))
    f.write(join(shared, "node_modules/private-label/package.json"), JSON.stringify({ name: "private-label", main: "index.js" }))
    f.write(join(shared, "node_modules/private-label/index.js"), `export const suffix = '-source-package';`)
    f.write(join(f.inDir, "contracts.ts"), `export const contract = '-generated';`)
    f.write(join(f.inDir, "ButtonPage.ts"), `
      import { defineComponent } from '@bosca/bml';
      import { suffix } from 'private-label';
      import { suffix as mapped } from '#label';
      import { label } from './button';
      import { contract } from './contracts';
      defineComponent('button', () => [suffix, mapped, label, contract]);
    `)
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    const module = join(f.outdir, metadata.resourcePrefix, metadata.modules["ButtonPage.js"].path)
    const js = readFileSync(module, "utf8")
    expect(js).toContain('"@bosca/bml"')
    expect(js).not.toContain("-private-dependency")
    rmSync(shared, { recursive: true })
    rmSync(join(f.projectDir, "src"), { recursive: true })
    rmSync(join(f.projectDir, "node_modules"), { recursive: true, force: true })
    const runtime = join(f.projectDir, "runtime.ts"), consumer = join(f.projectDir, "consumer")
    f.write(runtime, `export function defineComponent(tag, setup) { globalThis.inlineOrigin = setup(); }`)
    await bundleIslands({ entryPoints: [module], outdir: consumer, runtime })
    new Function(readFileSync(join(consumer, "ButtonPage.js"), "utf8"))()
    expect((globalThis as any).inlineOrigin).toEqual(["-source-package", "-source-package", "Button-source-package", "-generated"])
    delete (globalThis as any).inlineOrigin
  })

  it.each([false, true])("resolves inline CSS from the canonical BML file (producer package: %s)", async (shadowed) => {
    const f = fixture(), shared = mkdtempSync(join(tmpdir(), "bml-inline-css-origin-"))
    directories.push(shared)
    const original = join(shared, "button.bml")
    renameSync(join(f.sourceRoot, "controls/button.bml"), original)
    symlinkSync(original, join(f.sourceRoot, "controls/button.bml"))
    const dependency = "node_modules/design-token"
    f.write(join(shared, dependency, "package.json"), JSON.stringify({ name: "design-token" }))
    f.write(join(shared, dependency, "theme.css"), `.source-theme { color: #654321; }`)
    if (shadowed) {
      f.write(join(f.projectDir, dependency, "package.json"), JSON.stringify({ name: "design-token" }))
      f.write(join(f.projectDir, dependency, "theme.css"), `.producer-theme { color: #111111; }`)
    }
    const input = join(f.projectDir, "styles-input.json")
    f.write(input, JSON.stringify([{ key: "button:1", source: "controls/button.bml", css: `@import 'design-token/theme.css';`, digest: "original" }]))
    await bundleLibraryStyles({ ...f, input, outdir: f.stylesDir, library: "example:design", version: "1.0" })
    const css = JSON.parse(readFileSync(join(f.stylesDir, "styles.json"), "utf8"))["button:1"].css
    expect(css).toContain("#654321")
    expect(css).not.toContain("#111111")
    expect(JSON.parse(readFileSync(join(f.stylesDir, "inputs.json"), "utf8")))
      .toEqual([realpathSync(join(shared, dependency, "theme.css"))])
  })

  it.each([false, true])("uses each BML source's TypeScript configuration for inline code (linked root: %s)", async (linked) => {
    const f = fixture(), shared = mkdtempSync(join(tmpdir(), "bml-inline-tsconfig-"))
    directories.push(shared)
    if (linked) {
      const markup = join(shared, "markup")
      renameSync(f.sourceRoot, markup)
      symlinkSync(markup, f.sourceRoot, "junction")
      const target = join(markup, "controls/template.txt")
      renameSync(join(markup, "controls/button.bml"), target)
      symlinkSync(target, join(markup, "controls/button.bml"))
    }
    f.write(join(f.projectDir, "tsconfig.json"), JSON.stringify({ compilerOptions: { useDefineForClassFields: false } }))
    f.write(join(f.sourceRoot, "base.json"), JSON.stringify({ compilerOptions: { useDefineForClassFields: true } }))
    f.write(join(f.sourceRoot, "tsconfig.json"), JSON.stringify({ extends: "./base.json" }))
    f.write(join(f.sourceRoot, "assignment/tsconfig.json"), JSON.stringify({ compilerOptions: { useDefineForClassFields: false } }))
    const label = `class Base {}
      Object.defineProperty(Base.prototype, 'label', {
        set(value) { this.stored = value; }, get() { return 'setter:' + this.stored; },
      });
      class Label extends Base { label = 'raw'; }`
    f.write(join(f.sourceRoot, "controls/button.ts"), `${label}; export const label = new Label().label;`)
    f.write(join(f.sourceRoot, "assignment/button.ts"), `${label}; export const label = new Label().label;`)
    f.write(join(f.sourceRoot, "assignment/button.bml"), '<component tag="assigned"/>')
    symlinkSync(join(f.sourceRoot, "controls/button.bml"), join(f.sourceRoot, "alias.bml"))
    const origins = { "ButtonPage.ts": "controls/button.bml", "AliasPage.ts": "alias.bml", "AssignedPage.ts": "assignment/button.bml" }
    f.write(join(f.inDir, "bml-origins.json"), JSON.stringify(origins))
    f.write(f.manifestPath, JSON.stringify({ library: "example:design", version: "1.0", components:
      Object.keys(origins).map((file) => ({ clientModule: file.replace(/\.ts$/, ".js") })) }))
    f.write(join(f.inDir, "contracts.ts"), `export const contract = 'generated';`)
    for (const module of Object.keys(origins)) f.write(join(f.inDir, module), `
      import { defineComponent } from '@bosca/bml';
      import { label as helper } from './button';
      import { contract } from './contracts';
      ${label};
      defineComponent('${module}', () => [new Label().label, helper, contract]);
    `)
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    const paths = metadata.sources.map((source: { path: string }) => source.path)
    expect(paths).toContain("bml/controls/button.bml")
    expect(paths).toContain("bml/alias.bml")
    const entry = join(f.projectDir, "consumer.ts"), runtime = join(f.projectDir, "runtime.ts")
    f.write(entry, Object.keys(origins).map((module) =>
      `import ${JSON.stringify(join(f.outdir, metadata.resourcePrefix, metadata.modules[module.replace(/\.ts$/, ".js")].path))};`
    ).join("\n"))
    f.write(runtime, `export function defineComponent(tag, setup) { (globalThis.inlineConfigs ??= {})[tag] = setup(); }`)
    rmSync(shared, { recursive: true })
    rmSync(join(f.projectDir, "src"), { recursive: true })
    rmSync(join(f.projectDir, "node_modules"), { recursive: true })
    await bundleIslands({ entryPoints: [entry], outdir: join(f.projectDir, "consumer"), runtime })
    new Function(readFileSync(join(f.projectDir, "consumer/consumer.js"), "utf8"))()
    expect((globalThis as any).inlineConfigs).toEqual({
      "ButtonPage.ts": ["raw", "raw", "generated"],
      "AliasPage.ts": ["raw", "raw", "generated"],
      "AssignedPage.ts": ["setter:raw", "setter:raw", "generated"],
    })
    delete (globalThis as any).inlineConfigs
  })

  it.each(["cyclic", "dangling"])("rejects %s asset links", async (kind) => {
    const f = fixture()
    mkdirSync(f.publicDir, { recursive: true })
    symlinkSync(kind === "cyclic" ? f.publicDir : join(f.projectDir, "missing"), join(f.publicDir, "linked"), "junction")
    await expect(bundleLibraryAssets(f)).rejects.toThrow(kind === "cyclic" ? "Cyclic library asset directory link" : "ENOENT")
  })

  it("shares private dependencies across component modules and keeps published chunks rebundleable", async () => {
    const f = fixture()
    f.write(f.manifestPath, JSON.stringify({ library: "example:design", version: "1.0", components: [
      { clientModule: "ButtonPage.js" }, { clientModule: "BadgePage.js" },
    ] }))
    f.write(join(f.inDir, "bml-origins.json"), JSON.stringify({ "ButtonPage.ts": "controls/button.bml", "BadgePage.ts": "controls/badge.bml" }))
    f.write(join(f.sourceRoot, "controls/badge.bml"), '<component tag="badge"/>')
    f.write(join(f.inDir, "BadgePage.ts"), `import { label } from './button'; globalThis.badge = label;`)
    f.write(join(f.projectDir, "node_modules/private-label/index.js"), `globalThis.privateRuns = (globalThis.privateRuns ?? 0) + 1; export const suffix = '-private-dependency';`)
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    expect(metadata.files.some((it: { path: string }) => it.path.startsWith("chunks/"))).toBe(true)
    const entry = join(f.projectDir, "consumer.ts"), runtime = join(f.projectDir, "runtime.ts")
    f.write(entry, `import ${JSON.stringify(join(f.outdir, metadata.resourcePrefix, metadata.modules["ButtonPage.js"].path))}; import ${JSON.stringify(join(f.outdir, metadata.resourcePrefix, metadata.modules["BadgePage.js"].path))};`)
    f.write(runtime, `export function defineComponent(tag, setup) { globalThis.button = setup(); }`)
    rmSync(join(f.projectDir, "src"), { recursive: true })
    rmSync(join(f.projectDir, "node_modules"), { recursive: true })
    await bundleIslands({ entryPoints: [entry], outdir: join(f.projectDir, "consumer"), runtime })
    new Function(readFileSync(join(f.projectDir, "consumer/consumer.js"), "utf8"))()
    expect((globalThis as any).privateRuns).toBe(1)
    expect((globalThis as any).badge).toEqual((globalThis as any).button)
    for (const key of ["privateRuns", "badge", "button"]) delete (globalThis as any)[key]
  })

  it("keeps equal filenames in different libraries separate and bundles CSS-only libraries", async () => {
    const f = fixture()
    f.write(f.manifestPath, JSON.stringify({ library: "example:design", version: "1.0", components: [] }))
    rmSync(f.inDir, { recursive: true })
    f.write(join(f.clientDir, "theme.css"), `@import './nested/tokens.css';`)
    f.write(join(f.clientDir, "nested/tokens.css"), `:root { --accent: red; }`)
    await bundleLibraryAssets(f)
    const first = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    expect(first.globalStyles).toEqual(["styles/theme.css"])
    expect(first.modules).toEqual({})
    expect(libraryAssetNamespace("example:design", "1.0")).not.toEqual(libraryAssetNamespace("example:other", "1.0"))
    expect(libraryAssetNamespace("example:design", "1.0")).not.toEqual(libraryAssetNamespace("example:design", "2.0"))
  })

  it("keeps standalone CSS separate from CSS emitted for a same-named script", async () => {
    const f = fixture()
    f.write(join(f.clientDir, "theme.ts"), `import './nested/reset.css'; globalThis.themeStarted = true;`)
    f.write(join(f.clientDir, "theme.css"), `.theme { color: red; }`)
    f.write(join(f.clientDir, "nested/reset.css"), `.reset { margin: 0; }`)
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    expect(metadata.globalScripts).toEqual(["client/theme.js"])
    expect(metadata.globalStyles.sort()).toEqual(["client/theme.css", "styles/theme.css"])
    expect(readFileSync(join(f.outdir, metadata.resourcePrefix, "client/theme.css"), "utf8")).toContain(".reset")
    expect(readFileSync(join(f.outdir, metadata.resourcePrefix, "styles/theme.css"), "utf8")).toContain(".theme")
    expect(metadata.modules["ButtonPage.js"].styles).toEqual(["modules/ButtonPage.css"])
    for (const path of [...metadata.globalScripts, ...metadata.globalStyles]) {
      expect(metadata.files.some((file: { path: string }) => file.path === path)).toBe(true)
    }
  })

  it.each(["local", "hoisted"])("bundles %s linked npm packages without archiving their private sources", async (installation) => {
    const workspace = mkdtempSync(join(tmpdir(), "bml-library-workspace-"))
    directories.push(workspace)
    const f = fixture(join(workspace, "producer")), dependency = join(workspace, "packages/shared")
    f.write(join(dependency, "package.json"), JSON.stringify({ name: "@design/shared", main: "index.js" }))
    f.write(join(dependency, "index.js"), `import './theme.css'; export const label = 'workspace-label';`)
    f.write(join(dependency, "theme.css"), `.workspace { background: url('./icon.svg'); }`)
    f.write(join(dependency, "icon.svg"), '<svg xmlns="http://www.w3.org/2000/svg"/>')
    const installed = join(installation === "local" ? f.projectDir : workspace, "node_modules/@design/shared")
    mkdirSync(dirname(installed), { recursive: true })
    symlinkSync(dependency, installed, "junction")
    symlinkSync(f.projectDir, join(dirname(installed), "producer"), "junction")
    f.write(join(f.sourceRoot, "controls/button.ts"), `export { label } from '@design/shared';`)
    const input = join(f.projectDir, "styles-input.json")
    f.write(input, JSON.stringify([{ key: "button:1", source: "controls/button.bml", css: `@import '@design/shared/theme.css';`, digest: "original" }]))
    await bundleLibraryStyles({ ...f, input, outdir: f.stylesDir, library: "example:design", version: "1.0" })
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    expect(metadata.files.some((file: { path: string }) => file.path.endsWith(".svg"))).toBe(true)
    expect(metadata.sources.some((file: { path: string }) => /shared|node_modules|packages/.test(file.path))).toBe(false)
    expect(metadata.sources.map((file: { path: string }) => file.path)).toContain("bml/controls/button.ts")
    rmSync(dependency, { recursive: true })
    rmSync(join(f.projectDir, "src"), { recursive: true })
    rmSync(join(f.projectDir, "node_modules"), { recursive: true })
    const runtime = join(f.projectDir, "runtime.ts"), consumer = join(f.projectDir, "consumer")
    f.write(runtime, `export function defineComponent(tag, setup) { globalThis.linkedLabel = setup(); }`)
    await bundleIslands({ entryPoints: [join(f.outdir, metadata.resourcePrefix, metadata.modules["ButtonPage.js"].path)], outdir: consumer, runtime })
    new Function(readFileSync(join(consumer, "ButtonPage.js"), "utf8"))()
    expect((globalThis as any).linkedLabel).toBe("workspace-label")
    delete (globalThis as any).linkedLabel
  })

  it("removes stale asset outputs and rejects a missing declared client module", async () => {
    const f = fixture()
    await bundleLibraryAssets(f)
    f.write(f.manifestPath, JSON.stringify({ library: "example:design", version: "1.0", components: [] }))
    rmSync(f.sourceRoot, { recursive: true })
    await bundleLibraryAssets(f)
    const metadata = JSON.parse(readFileSync(join(f.outdir, "META-INF/bml/client-manifest.json"), "utf8"))
    expect(metadata.files).toEqual([])
    expect(metadata.sources).toEqual([])
    f.write(f.manifestPath, JSON.stringify({ library: "example:design", version: "1.0", components: [{ clientModule: "Missing.js" }] }))
    await expect(bundleLibraryAssets(f)).rejects.toThrow("Missing generated client module")
  })
})
