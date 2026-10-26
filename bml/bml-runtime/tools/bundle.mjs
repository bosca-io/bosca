import { build } from "esbuild"
import { bundleLibraryAssets } from "./library.mjs"
import { dependencyGraph, dependencyPlugin, installedPackageRoots, linkedDependencies } from "./dependencies.mjs"
import { readdirSync, readFileSync, existsSync, mkdirSync, rmSync, writeFileSync, cpSync, realpathSync } from "node:fs"
import { createHash } from "node:crypto"
import { dirname, join, resolve, relative } from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"

function globalBootstrapPath() {
  return fileURLToPath(new URL("../src/identity-bootstrap.ts", import.meta.url))
}

async function applicationGraph(options) {
  const scan = await build({ ...options, write: false, metafile: true })
  const directory = options.absWorkingDir ?? process.cwd()
  const inputs = (options.entryPoints ?? []).map((entry) => typeof entry === "string" ? entry : entry.in)
  const roots = installedPackageRoots([directory, options.stdin?.resolveDir, ...inputs.map((it) => dirname(it)),
    ...Object.keys(scan.metafile.inputs).map((input) => dirname(resolve(directory, input)))].filter(Boolean))
  return dependencyGraph(scan.metafile, directory, roots)
}

async function buildApplication(options, linkedDir) {
  const linked = linkedDependencies(linkedDir)
  if (linked.modules.size === 0) return build(options)
  const graph = await applicationGraph(options)
  return build({ ...options, plugins: [...(options.plugins ?? []), dependencyPlugin(graph, linked.modules, linked.entries)] })
}

// Linker bridges contain only JSON-quoted side-effect imports. Inline those imports into the
// virtual page so its selected library variant is independent of other pages in this build.
function pageImports(entries, inDir, linked, shared) {
  return entries.map((entry) => {
    const source = readFileSync(join(inDir, `${entry}.ts`), "utf8")
    const imports = source.trim().split(/\r?\n/).map(line => /^import ("(?:[^"\\]|\\["\\/bfnrt]|\\u[\da-fA-F]{4})*");?$/.exec(line))
    if (imports.some(match => !match)) return `import ${JSON.stringify(`./${entry}`)}`
    const paths = imports.map(match => resolve(inDir, JSON.parse(match[1])))
    if (!paths.every(path => existsSync(path) && linked.entries.has(realpathSync(path)))) return `import ${JSON.stringify(`./${entry}`)}`
    return paths.map(path => `import ${JSON.stringify(shared ? linked.entries.get(realpathSync(path)) : path)}`).join("\n")
  }).join("\n")
}

/**
 * Bundle BML island client modules (extracted `<script client>` TS + the
 * `@bosca/bml` runtime) into browser modules. The Gradle plugin
 * invokes this over a project's island entry points; output is
 * content-addressable and served by `bml-server`.
 *
 * `runtime` aliases the bare `@bosca/bml` specifier the compiler plugin emits to a concrete
 * runtime entry — needed when `@bosca/bml` isn't installed in node_modules (e.g. this monorepo,
 * where it's local source). Omit it when consumers have the package installed.
 *
 * @param {{ entryPoints: string[], outdir: string, minify?: boolean, runtime?: string, splitting?: boolean, linkedDir?: string }} options
 */
export async function bundleIslands({ entryPoints, outdir, minify = false, runtime, splitting = false, linkedDir }) {
  return buildApplication({
    entryPoints,
    outdir,
    bundle: true,
    minify,
    splitting,
    format: "esm",
    platform: "browser",
    target: "es2020",
    sourcemap: !minify,
    logLevel: "silent",
    ...(runtime ? { alias: { "@bosca/bml": resolve(runtime) } } : {}),
  }, linkedDir)
}

/** Mirrors `BmlProductionAssets.slug` in bml-server: `/lists/{id}` -> `lists-id`, `/` -> `index`. */
export function routeSlug(route) {
  const cleaned = route.replace(/^\/+|\/+$/g, "").replace(/[^A-Za-z0-9]+/g, "-").replace(/^-+|-+$/g, "").toLowerCase()
  return cleaned.length > 0 ? cleaned : "index"
}

/**
 * Production bundling: one entry per page plus content-hashed shared ESM chunks. Each entry imports
 * only its page's client module and closure components; common engines can be cached across pages.
 * The page→module mapping comes from the compiler's
 * metrics manifest (`manifest.tsv`); output names use the same route-slug algorithm as
 * `BmlProductionAssets` in bml-server (uniqueness by sorted-route order with `-2`, `-3`… suffixes),
 * so server and build agree without a lookup file.
 *
 * Production deliberately uses esbuild's syntax, whitespace, and identifier minifiers but does
 * not enable property mangling: generated contracts, DOM datasets, and public `window` properties
 * are string-addressed runtime APIs and must keep their names. Source maps stay out of deployed
 * output so the original identifiers and sources are not shipped beside the optimized bundle.
 *
 * @param {{ manifestPath: string, inDir: string, outdir: string, runtime?: string }} options
 * @returns {Promise<string[]>} the bundle file names written
 */
export async function bundleProduction({ manifestPath, inDir, outdir, runtime }) {
  const lines = readFileSync(manifestPath, "utf8")
    .split("\n")
    .filter((line) => line.startsWith("P\t"))
    .map((line) => line.split("\t"))
    .sort((a, b) => (a[2] < b[2] ? -1 : a[2] > b[2] ? 1 : 0)) // sort by route, matching the server
  // The server reserves a slug for a page with either page-specific CSS or client JS. Component
  // styles present on every page move into app.css, so they do not reserve a page slug by themselves.
  const cssTagsByPage = lines.map(([, , , , cssTags]) => cssTags === "-" ? [] : cssTags.split(","))
  const sharedCssTags = new Set(cssTagsByPage[0] ?? [])
  for (const cssTags of cssTagsByPage.slice(1)) {
    for (const tag of sharedCssTags) if (!cssTags.includes(tag)) sharedCssTags.delete(tag)
  }
  const taken = new Set()
  const written = []
  const pages = new Map()
  const linked = linkedDependencies(inDir)
  for (const [index, [, , route, pageJs, , componentJs]] of lines.entries()) {
    const modules = []
    if (pageJs !== "-") modules.push(pageJs)
    if (componentJs !== "-") modules.push(...componentJs.split(","))
    const hasPageCss = cssTagsByPage[index].some((tag) => !sharedCssTags.has(tag))
    if (!hasPageCss && modules.length === 0) continue
    let slug = routeSlug(route)
    for (let n = 2; taken.has(slug); n++) slug = `${routeSlug(route)}-${n}`
    taken.add(slug)
    if (modules.length === 0) continue
    const orderedModules = [...modules.filter((m) => m.startsWith("BmlLibraries_")), ...modules.filter((m) => !m.startsWith("BmlLibraries_"))]
    const entries = orderedModules
      .map((m) => m.replace(/\.js$/, ""))
      .filter((m) => existsSync(join(inDir, `${m}.ts`)))
    const outfile = join(outdir, `${slug}.page.js`)
    if (entries.length === 0) {
      // The server links this page's bundle because the manifest names client modules; none of them
      // produced TypeScript, so publish an empty module rather than leave the link returning 404.
      mkdirSync(outdir, { recursive: true })
      writeFileSync(outfile, "")
      written.push(`${slug}.page.js`)
      continue
    }
    let shared = false
    if (linked.modules.size > 0) {
      const graph = await applicationGraph({
        absWorkingDir: resolve(inDir),
        entryPoints: entries.map(entry => resolve(inDir, `${entry}.ts`)),
        outdir: resolve(outdir), bundle: true, format: "esm", platform: "browser", target: "es2020", logLevel: "silent",
        ...(runtime ? { alias: { "@bosca/bml": resolve(runtime) } } : {}),
      })
      shared = [...graph.values()].some(digest => linked.modules.has(digest))
    }
    pages.set(`bml-page:${slug}`, pageImports(entries, inDir, linked, shared))
    written.push(`${slug}.page.js`)
  }
  if (pages.size > 0) {
    const result = await buildApplication({
      absWorkingDir: resolve(inDir),
      entryPoints: [...pages.keys()].map((key) => ({ in: key, out: `${key.slice(9)}.page` })),
      outdir: resolve(outdir),
      bundle: true,
      splitting: true,
      chunkNames: "chunks/[name]-[hash]",
      format: "esm",
      platform: "browser",
      target: "es2020",
      metafile: true,
      ...productionOptimization,
      logLevel: "silent",
      plugins: [{ name: "bml-page-entries", setup(builder) {
        builder.onResolve({ filter: /^bml-page:/ }, (args) => ({ path: args.path, namespace: "bml-page" }))
        builder.onLoad({ filter: /.*/, namespace: "bml-page" }, (args) => ({
          contents: pages.get(args.path), resolveDir: resolve(inDir), loader: "ts",
        }))
      } }],
      ...(runtime ? { alias: { "@bosca/bml": resolve(runtime) } } : {}),
    }, inDir)
    // Metrics use the emitted graph, rather than treating a tiny entry as the whole page payload.
    const outputs = result.metafile.outputs
    const imports = {}
    for (const name of written) {
      const seen = new Set()
      const visit = (path) => {
        if (seen.has(path)) return
        seen.add(path)
        for (const child of outputs[path]?.imports ?? []) if (!child.external && child.kind === "import-statement") visit(child.path)
      }
      const entry = Object.keys(outputs).find((path) => resolve(inDir, path) === resolve(outdir, name))
      if (entry) visit(entry)
      imports[name] = entry ? [...seen].map((path) => relative(resolve(outdir), resolve(inDir, path)).replaceAll("\\", "/")) : [name]
    }
    writeFileSync(join(outdir, "bml-imports.json"), JSON.stringify(imports))
  }
  return written
}

/**
 * Builds the site's every-page assets from `src/main/client`. CSS is parsed and minified instead
 * of being whitespace-stripped heuristically; JS/TS is bundled with the same safe production
 * identifier minification as page bundles. Basenames are preserved (`profiles.ts` ->
 * `profiles.js`) because site launchers load these resources by their established names.
 *
 * @param {{ inDir: string, outdir: string }} options
 * @returns {Promise<string[]>} the emitted top-level asset names
 */
export async function bundleGlobalAssets({ inDir, outdir }) {
  if (!existsSync(inDir)) return []
  const sources = readdirSync(inDir)
  const scripts = sources.filter((file) => /\.(?:js|ts)$/.test(file) && !file.endsWith(".d.ts")).map((file) => join(inDir, file))
  const styles = sources.filter((file) => file.endsWith(".css")).map((file) => join(inDir, file))
  if (scripts.length === 0 && styles.length === 0) return []
  await Promise.all([
    scripts.length > 0 && build({
      entryPoints: scripts,
      outdir,
      entryNames: "[name]",
      inject: [globalBootstrapPath()],
      bundle: true,
      splitting: false,
      format: "esm",
      platform: "browser",
      target: "es2020",
      ...productionOptimization,
      logLevel: "silent",
    }),
    styles.length > 0 && build({
      entryPoints: styles,
      outdir,
      entryNames: "[name]",
      // Keep public-root url()/@import references intact; BmlServer serves this CSS in memory,
      // not esbuild's otherwise-emitted content-hashed dependency files.
      bundle: false,
      ...productionOptimization,
      logLevel: "silent",
    }),
  ])
  return readdirSync(outdir).filter((file) => /\.(?:css|js)$/.test(file)).sort()
}

/** Packages both client modes and indexes the exact application/library resource bytes. */
export function packageApplicationAssets({ devDir, prodDir, resourcesDir, outdir }) {
  rmSync(outdir, { recursive: true, force: true })
  mkdirSync(outdir, { recursive: true })
  for (const [mode, source] of [["dev", devDir], ["prod", prodDir]]) {
    if (source && existsSync(source)) cpSync(source, join(outdir, mode), { recursive: true })
  }
  const files = []
  function inventory(root, directory = root) {
    if (!root || !existsSync(directory)) return
    for (const entry of readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name < b.name ? -1 : 1)) {
      const file = join(directory, entry.name)
      if (entry.isDirectory()) inventory(root, file)
      else if (entry.isFile()) files.push({
        path: relative(root, file).replaceAll("\\", "/"),
        digest: `sha256:${createHash("sha256").update(readFileSync(file)).digest("hex")}`,
      })
    }
  }
  inventory(outdir)
  inventory(resourcesDir)
  files.sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0)
  if (new Set(files.map((file) => file.path)).size !== files.length) throw new Error("Duplicate packaged BML asset path")
  writeFileSync(join(outdir, "client-manifest.json"), JSON.stringify({ formatVersion: 1, files }, null, 2))
}

const productionOptimization = {
  minifySyntax: true,
  minifyWhitespace: true,
  minifyIdentifiers: true,
  treeShaking: true,
  legalComments: "eof",
  sourcemap: false,
}

// CLI: `node bundle.mjs --in <tsDir> --out <jsDir> [--runtime <path-to-@bosca/bml-entry>]
//       [--manifest <manifest.tsv> --prod-out <jsProdDir>]
//       [--assets-in <src/main/client> --assets-out <generated resources>]`
// Bundles every `*.ts` under <tsDir> (the dev tier); with --manifest/--prod-out it ALSO writes
// one production bundle per page. Used by the `bmlBundleClient` Gradle task.
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const argOf = (flag) => {
    const i = process.argv.indexOf(flag)
    return i >= 0 ? process.argv[i + 1] : undefined
  }
  const inDir = argOf("--in")
  const outDir = argOf("--out")
  const runtime = argOf("--runtime")
  const manifest = argOf("--manifest")
  const prodOut = argOf("--prod-out")
  const assetsIn = argOf("--assets-in")
  const assetsOut = argOf("--assets-out")
  if (!inDir || !outDir) {
    console.error("usage: node bundle.mjs --in <tsDir> --out <jsDir> [--runtime <path>] [--manifest <tsv> --prod-out <dir>] [--assets-in <dir> --assets-out <dir>]")
    process.exit(2)
  }
  // esbuild does not remove outputs whose entry point disappeared. These directories contain only
  // derived BML bundles, so rebuild them cleanly from the current compiler manifest/input set.
  rmSync(outDir, { recursive: true, force: true })
  mkdirSync(outDir, { recursive: true })
  if (prodOut) {
    rmSync(prodOut, { recursive: true, force: true })
    mkdirSync(prodOut, { recursive: true })
  }
  if (assetsOut) {
    rmSync(assetsOut, { recursive: true, force: true })
    mkdirSync(assetsOut, { recursive: true })
  }
  const libraryManifest = argOf("--library-manifest")
  if (libraryManifest) {
    await bundleLibraryAssets({ manifestPath: libraryManifest, inDir, sourceRoot: argOf("--source-root"),
      buildDir: argOf("--build-dir"), clientDir: assetsIn, publicDir: argOf("--public-in"), projectDir: argOf("--project-dir"),
      stylesDir: argOf("--library-styles"), outdir: argOf("--library-out"), sourcesOut: argOf("--library-sources-out"),
      inputsOut: argOf("--library-inputs-out") })
    process.exit(0)
  }
  const entryPoints = existsSync(inDir)
    ? readdirSync(inDir).filter((f) => f.endsWith(".ts") && !f.endsWith(".d.ts")).map((f) => join(inDir, f))
    : []
  const jobs = []
  if (entryPoints.length > 0) {
    jobs.push(bundleIslands({ entryPoints, outdir: outDir, runtime, splitting: true, linkedDir: inDir }))
  }
  // Production bundles follow the manifest, not the .ts inputs: a page whose declared client modules
  // produced no TypeScript still needs its (empty) <slug>.page.js, because bml-server links it.
  if (manifest && prodOut && existsSync(manifest)) {
    jobs.push(bundleProduction({ manifestPath: manifest, inDir, outdir: prodOut, runtime }))
  }
  if (assetsIn && assetsOut) {
    jobs.push(bundleGlobalAssets({ inDir: assetsIn, outdir: assetsOut }))
  }
  Promise.all(jobs).then(
    () => {
      const applicationOut = argOf("--application-out")
      if (applicationOut) packageApplicationAssets({ devDir: outDir, prodDir: prodOut,
        resourcesDir: argOf("--application-resources"), outdir: applicationOut })
      process.exit(0)
    },
    (err) => {
      console.error(err)
      process.exit(1)
    },
  )
}
