// @vitest-environment node
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { tmpdir } from "node:os"
import { execFileSync } from "node:child_process"
import { pathToFileURL } from "node:url"
import { afterEach, describe, expect, it } from "vitest"
// @ts-expect-error - plain .mjs build helper
import { bundleLibraryAssets } from "../tools/library.mjs"
// @ts-expect-error - plain .mjs build helper
import { bundleIslands, bundleProduction } from "../tools/bundle.mjs"

const directories: string[] = []
afterEach(() => directories.splice(0).forEach((dir) => rmSync(dir, { recursive: true, force: true })))
const write = (path: string, value: string) => { mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, value) }
const temporary = () => { const dir = mkdtempSync(join(tmpdir(), "bml-dependency-")); directories.push(dir); return dir }
function packages(root: string) {
  write(join(root, "node_modules/engine/package.json"), JSON.stringify({ name: "engine", version: "1.0.0", type: "module", main: "index.js" }))
  write(join(root, "node_modules/engine/index.js"), `import { token } from 'leaf';
    import * as leafNamespace from 'leaf'; export const all = leafNamespace;
    globalThis.engineLoads = (globalThis.engineLoads ?? 0) + 1;
    export class Document { value = token() }
    export default Document;
    export const editorOnly = () => 'app-only-export';
    export const unused = () => 'UNUSED_DEPENDENCY_EXPORT';`)
  write(join(root, "node_modules/leaf/package.json"), JSON.stringify({ name: "leaf", version: "1.0.0", type: "module", main: "index.js" }))
  write(join(root, "node_modules/leaf/index.js"), `import { label } from './cycle.js'; export const token = () => label(); export const base = 'leaf'; export const namespaceOnly = 'UNUSED_NAMESPACE_MEMBER';`)
  write(join(root, "node_modules/leaf/cycle.js"), `import { base } from './index.js'; export const label = () => base;`)
}
async function fixture(mismatch = "") {
  const producer = temporary(), consumer = temporary()
  packages(producer); packages(consumer)
  if (mismatch === "environment") for (const project of [producer, consumer]) {
    const entry = join(project, "node_modules/engine/index.js")
    write(entry, readFileSync(entry, "utf8") + `
      export const environment = process.env.NODE_ENV;
      if (process.env.NODE_ENV !== 'production') globalThis.developmentMarker = 'DEVELOPMENT_ONLY_MARKER';`)
  }
  const sample = "var pretend_exports = {};\n__export(pretend_exports, {\n  value: () => value\n});"
  if (mismatch === "literal") for (const project of [producer, consumer]) {
    const entry = join(project, "node_modules/engine/index.js")
    write(entry, readFileSync(entry, "utf8") + `\nexport const codeSample = String.raw\`${sample}\`;`)
  }
  if (mismatch === "effects") for (const project of [producer, consumer]) {
    const entry = join(project, "node_modules/engine/index.js")
    write(entry, readFileSync(entry, "utf8") + `
      var __defProp = (target, name, descriptor) => {
        globalThis.namespaceEffects = (globalThis.namespaceEffects ?? 0) + 1;
        Object.defineProperty(target, name, descriptor);
      };
      var __export = (target, all) => {
        for (var name in all) __defProp(target, name, {get: all[name], enumerable: true});
      };
      var custom_exports = {};
      __export(custom_exports, {value: () => 'custom'});`)
  }
  const sourceRoot = join(producer, "src/bml"), clientDir = join(producer, "src/client")
  if (mismatch === "duplicate") {
    packages(join(clientDir, "nested"))
    write(join(clientDir, "nested/extra.ts"), `import Document from 'engine'; globalThis.secondDocument = new Document();`)
  }
  if (mismatch === "css" || mismatch === "dynamic" || mismatch === "commonjs") {
    for (const root of [producer, consumer]) {
      const entry = join(root, "node_modules/engine/index.js")
      if (mismatch === "css") {
        write(entry, `import './theme.css';\n` + readFileSync(entry, "utf8"))
        write(join(root, "node_modules/engine/theme.css"), '.engine { color: red; }')
      } else if (mismatch === "dynamic") {
        write(entry, readFileSync(entry, "utf8") + `\nexport const lazy = () => import('./lazy.js');`)
        write(join(root, "node_modules/engine/lazy.js"), `export const value = 'lazy';`)
      } else {
        write(join(root, "node_modules/engine/package.json"), JSON.stringify({ name: "engine", version: "1.0.0", type: "commonjs", main: "index.js" }))
        write(entry, `const {token} = require('leaf'); globalThis.engineLoads = (globalThis.engineLoads ?? 0) + 1;
          class Document { constructor() { this.value = token() } }
          module.exports = Document; module.exports.Document = Document; module.exports.editorOnly = () => 'app-only-export';`)
      }
    }
  }
  const inDir = join(producer, "build/ts"), outdir = join(producer, "build/assets")
  const manifestPath = join(producer, "manifest.json")
  write(join(sourceRoot, "viewer.bml"), '<component tag="viewer"/>')
  write(join(inDir, "bml-origins.json"), JSON.stringify({ "Viewer.ts": "viewer.bml" }))
  write(join(inDir, "Viewer.ts"), (mismatch === "duplicate" ? `import '../client/nested/extra';` : '') +
    (mismatch === "environment" ? `import { environment } from 'engine'; globalThis.viewerEnvironment = environment;` : '') +
    (mismatch === "literal" ? `import { codeSample } from 'engine'; globalThis.viewerSample = codeSample;` : '') +
    `import Document from 'engine'; globalThis.viewerDocument = new Document();`)
  write(manifestPath, JSON.stringify({ library: "example:viewer", version: "1.0", components: [{ clientModule: "Viewer.js" }] }))
  await bundleLibraryAssets({ projectDir: producer, sourceRoot, clientDir, inDir, outdir, manifestPath,
    publicDir: join(producer, "public"), stylesDir: join(producer, "styles"), sourcesOut: join(producer, "sources") })
  const metadata = JSON.parse(readFileSync(join(outdir, "META-INF/bml/client-manifest.json"), "utf8"))
  expect(metadata.dependencyModules.length).toBeGreaterThan(0)
  const linkedDir = join(consumer, "build/ts")
  cpSync(join(outdir, metadata.resourcePrefix), join(linkedDir, "libraries/viewer"), { recursive: true })
  write(join(linkedDir, "bml-client-dependencies.json"), JSON.stringify({ modules: metadata.dependencyModules.map((entry: any) => ({ ...entry, path: `./libraries/viewer/${entry.path}` })),
    entries: Object.fromEntries(Object.entries(metadata.sharedEntries).map(([path, shared]) => [`./libraries/viewer/${path}`, `./libraries/viewer/${shared}`])) }))
  write(join(linkedDir, "Viewer.ts"), `import ${JSON.stringify(`./libraries/viewer/${metadata.modules['Viewer.js'].path}`)}\n`)
  write(join(linkedDir, "Page.ts"), `import './Viewer'; import { Document, editorOnly } from 'engine';
    globalThis.result = { shared: globalThis.viewerDocument instanceof Document, value: editorOnly(), loads: globalThis.engineLoads,
      ...(globalThis.secondDocument ? { duplicateShared: globalThis.secondDocument instanceof Document } : {}) };`)
  write(join(linkedDir, "manifest.tsv"), 'bml-metrics\t1\nP\tPage\t/\tPage.js\tviewer\tViewer.js\n')
  write(join(consumer, "package.json"), '{"type":"module"}')
  if (mismatch === "version") write(join(consumer, "node_modules/engine/package.json"), JSON.stringify({ name: "engine", version: "2.0.0", type: "module", main: "index.js" }))
  if (mismatch === "bytes") write(join(consumer, "node_modules/engine/index.js"), readFileSync(join(consumer, "node_modules/engine/index.js"), "utf8") + '\nexport const patched = true;')
  if (mismatch === "transitive") write(join(consumer, "node_modules/leaf/package.json"), JSON.stringify({ name: "leaf", version: "2.0.0", type: "module", main: "index.js" }))
  if (mismatch === "nested") {
    cpSync(join(consumer, "node_modules/leaf"), join(consumer, "node_modules/engine/node_modules/leaf"), { recursive: true })
    write(join(consumer, "node_modules/engine/node_modules/leaf/package.json"), JSON.stringify({ name: "leaf", version: "2.0.0", type: "module", main: "index.js" }))
  }
  // Consume only extracted binary entries, with no producer source or installed packages left.
  rmSync(producer, { recursive: true })
  return { consumer, linkedDir }
}
function evaluate(file: string) {
  return JSON.parse(execFileSync(process.execPath, ['--input-type=module', '--eval',
    `await import(${JSON.stringify(pathToFileURL(file).href)}); console.log(JSON.stringify(globalThis.result));`], { encoding: 'utf8' }))
}

describe("compiled library dependency sharing", () => {
  it.each(["production", "development"])("retains final-build environment semantics in %s", async (mode) => {
    const { consumer, linkedDir } = await fixture("environment")
    write(join(linkedDir, "Page.ts"), `import './Viewer'; import { Document, environment } from 'engine';
      globalThis.result = { library: globalThis.viewerEnvironment, app: environment,
        shared: globalThis.viewerDocument instanceof Document, development: Boolean(globalThis.developmentMarker) };`)
    const outdir = join(consumer, mode)
    if (mode === "production") await bundleProduction({ manifestPath: join(linkedDir, "manifest.tsv"), inDir: linkedDir, outdir })
    else await bundleIslands({ entryPoints: [join(linkedDir, "Page.ts")], outdir, linkedDir, splitting: true })
    const production = mode === "production"
    expect(evaluate(join(outdir, production ? "index.page.js" : "Page.js"))).toEqual({
      library: mode, app: mode, shared: true, development: !production,
    })
    if (production) {
      const imports = JSON.parse(readFileSync(join(outdir, "bml-imports.json"), "utf8"))['index.page.js'] as string[]
      expect(imports.map(path => readFileSync(join(outdir, path), "utf8")).join('')).not.toContain('DEVELOPMENT_ONLY_MARKER')
    }
  })

  it.each(["production", "development"])("preserves one class identity, side effects and app-only exports in %s", async (mode) => {
    const { consumer, linkedDir } = await fixture()
    const outdir = join(consumer, mode)
    if (mode === "production") {
      await bundleProduction({ manifestPath: join(linkedDir, 'manifest.tsv'), inDir: linkedDir, outdir })
      expect(readFileSync(join(outdir, 'index.page.js'), 'utf8')).not.toContain('UNUSED_DEPENDENCY_EXPORT')
      const imports = JSON.parse(readFileSync(join(outdir, 'bml-imports.json'), 'utf8'))['index.page.js'] as string[]
      expect(imports.map(path => readFileSync(join(outdir, path), 'utf8')).join('')).not.toContain('UNUSED_NAMESPACE_MEMBER')
      expect(evaluate(join(outdir, 'index.page.js'))).toEqual({ shared: true, value: 'app-only-export', loads: 1 })
    } else {
      await bundleIslands({ entryPoints: [join(linkedDir, 'Viewer.ts'), join(linkedDir, 'Page.ts')], outdir, linkedDir, splitting: true })
      write(join(outdir, 'check.js'), `import './Viewer.js'; import './Page.js';`)
      expect(evaluate(join(outdir, 'check.js'))).toEqual({ shared: true, value: 'app-only-export', loads: 1 })
    }
  })

  it("retains namespace exports when the application actually uses them", async () => {
    const { consumer, linkedDir } = await fixture()
    write(join(linkedDir, 'Page.ts'), `import './Viewer'; import { all, Document } from 'engine';
      globalThis.result = { shared: globalThis.viewerDocument instanceof Document, namespace: all.namespaceOnly, token: all.token() };`)
    const outdir = join(consumer, 'production')
    await bundleProduction({ manifestPath: join(linkedDir, 'manifest.tsv'), inDir: linkedDir, outdir })
    expect(evaluate(join(outdir, 'index.page.js'))).toEqual({ shared: true, namespace: 'UNUSED_NAMESPACE_MEMBER', token: 'leaf' })
  })

  it("keeps pages without application dependencies on the lean library graph", async () => {
    const { consumer, linkedDir } = await fixture()
    write(join(linkedDir, 'Simple.ts'), `Object.defineProperty(globalThis, 'result', {
      get: () => ({ value: globalThis.viewerDocument.value, loads: globalThis.engineLoads }) });`)
    write(join(linkedDir, 'manifest.tsv'), readFileSync(join(linkedDir, 'manifest.tsv'), 'utf8') +
      'P\tSimple\t/simple\tSimple.js\tviewer\tViewer.js\n')
    const outdir = join(consumer, 'production')
    await bundleProduction({ manifestPath: join(linkedDir, 'manifest.tsv'), inDir: linkedDir, outdir })
    const imports = JSON.parse(readFileSync(join(outdir, 'bml-imports.json'), 'utf8'))
    const simple = imports['simple.page.js'].map((path: string) => readFileSync(join(outdir, path), 'utf8')).join('')
    expect(simple).not.toContain('app-only-export')
    expect(simple).not.toContain('UNUSED_NAMESPACE_MEMBER')
    expect(evaluate(join(outdir, 'simple.page.js'))).toEqual({ value: 'leaf', loads: 1 })
    expect(evaluate(join(outdir, 'index.page.js'))).toEqual({ shared: true, value: 'app-only-export', loads: 1 })
  })

  it("does not rewrite namespace-looking code samples inside tagged template strings", async () => {
    const { consumer, linkedDir } = await fixture("literal")
    write(join(linkedDir, 'Page.ts'), `import './Viewer'; import { Document } from 'engine';
      globalThis.result = { shared: globalThis.viewerDocument instanceof Document, sample: globalThis.viewerSample };`)
    const outdir = join(consumer, 'production')
    await bundleProduction({ manifestPath: join(linkedDir, 'manifest.tsv'), inDir: linkedDir, outdir })
    expect(evaluate(join(outdir, 'index.page.js'))).toEqual({ shared: true,
      sample: "var pretend_exports = {};\n__export(pretend_exports, {\n  value: () => value\n});" })
  })

  it("retains effects in authored namespace helpers", async () => {
    const { consumer, linkedDir } = await fixture("effects")
    write(join(linkedDir, 'Page.ts'), `import './Viewer'; import { Document } from 'engine';
      globalThis.result = { shared: globalThis.viewerDocument instanceof Document, effects: globalThis.namespaceEffects };`)
    const outdir = join(consumer, 'production')
    await bundleProduction({ manifestPath: join(linkedDir, 'manifest.tsv'), inDir: linkedDir, outdir })
    expect(evaluate(join(outdir, 'index.page.js'))).toEqual({ shared: true, effects: 1 })
  })

  it.each(["version", "bytes", "transitive", "nested", "css", "dynamic", "commonjs"])("preserves separate implementations for a %s graph", async (mismatch) => {
    const { consumer, linkedDir } = await fixture(mismatch)
    const outdir = join(consumer, 'production')
    await bundleProduction({ manifestPath: join(linkedDir, 'manifest.tsv'), inDir: linkedDir, outdir })
    expect(evaluate(join(outdir, 'index.page.js'))).toEqual({ shared: false, value: 'app-only-export', loads: 2 })
  })

  it("shares identical package graphs installed at multiple producer paths", async () => {
    const { consumer, linkedDir } = await fixture('duplicate')
    const outdir = join(consumer, 'production')
    await bundleProduction({ manifestPath: join(linkedDir, 'manifest.tsv'), inDir: linkedDir, outdir })
    expect(evaluate(join(outdir, 'index.page.js'))).toEqual({ shared: true, duplicateShared: true, value: 'app-only-export', loads: 1 })
  })
})
