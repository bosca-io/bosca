import { build } from "esbuild"
import { readdirSync, readFileSync, existsSync, mkdirSync, rmSync, writeFileSync } from "node:fs"
import { join, resolve } from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"

function globalBootstrapPath() {
  return fileURLToPath(new URL("../src/identity-bootstrap.ts", import.meta.url))
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
 * @param {{ entryPoints: string[], outdir: string, minify?: boolean, runtime?: string }} options
 */
export async function bundleIslands({ entryPoints, outdir, minify = false, runtime }) {
  return build({
    entryPoints,
    outdir,
    bundle: true,
    minify,
    splitting: false,
    format: "esm",
    platform: "browser",
    target: "es2020",
    sourcemap: !minify,
    logLevel: "silent",
    ...(runtime ? { alias: { "@bosca/bml": resolve(runtime) } } : {}),
  })
}

/** Mirrors `BmlProductionAssets.slug` in bml-server: `/lists/{id}` -> `lists-id`, `/` -> `index`. */
export function routeSlug(route) {
  const cleaned = route.replace(/^\/+|\/+$/g, "").replace(/[^A-Za-z0-9]+/g, "-").replace(/^-+|-+$/g, "").toLowerCase()
  return cleaned.length > 0 ? cleaned : "index"
}

/**
 * Production bundling: ONE bundle per page — a synthesized entry importing the page's
 * client module plus every closure component's module, so a page downloads a single `<slug>.page.js`
 * (the runtime is inlined once per bundle). The page→module mapping comes from the compiler's
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
    const entries = modules
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
    await build({
      stdin: {
        contents: entries.map((m) => `import "./${m}"`).join("\n"),
        resolveDir: resolve(inDir),
        sourcefile: `${slug}.entry.ts`,
        loader: "ts",
      },
      outfile,
      bundle: true,
      splitting: false,
      format: "esm",
      platform: "browser",
      target: "es2020",
      ...productionOptimization,
      logLevel: "silent",
      ...(runtime ? { alias: { "@bosca/bml": resolve(runtime) } } : {}),
    })
    written.push(`${slug}.page.js`)
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
  const scripts = sources.filter((file) => /\.(?:js|ts)$/.test(file)).map((file) => join(inDir, file))
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
  const entryPoints = existsSync(inDir)
    ? readdirSync(inDir).filter((f) => f.endsWith(".ts")).map((f) => join(inDir, f))
    : []
  const jobs = []
  if (entryPoints.length > 0) {
    jobs.push(bundleIslands({ entryPoints, outdir: outDir, runtime }))
  }
  // Production bundles follow the manifest, not the .ts inputs: a page whose declared client modules
  // produced no TypeScript still needs its (empty) <slug>.page.js, because bml-server links it.
  if (manifest && prodOut && existsSync(manifest)) {
    jobs.push(bundleProduction({ manifestPath: manifest, inDir, outdir: prodOut, runtime }))
  }
  if (assetsIn && assetsOut) {
    jobs.push(bundleGlobalAssets({ inDir: assetsIn, outdir: assetsOut }))
  }
  if (jobs.length === 0) process.exit(0)
  Promise.all(jobs).then(
    () => process.exit(0),
    (err) => {
      console.error(err)
      process.exit(1)
    },
  )
}
