import { build } from "esbuild"
import { createHash } from "node:crypto"
import { existsSync, mkdirSync, readFileSync, readdirSync, realpathSync, rmSync, statSync, writeFileSync, copyFileSync } from "node:fs"
import { basename, dirname, join, relative, resolve, sep } from "node:path"
import { pathToFileURL } from "node:url"

const digest = (bytes) => `sha256:${createHash("sha256").update(bytes).digest("hex")}`
const slash = (path) => path.split(sep).join("/")
function files(dir, ancestors = new Set()) {
  if (!existsSync(dir)) return []
  const canonical = realpathSync(dir)
  if (ancestors.has(canonical)) throw new Error(`Cyclic library asset directory link: ${dir}`)
  const parents = new Set([...ancestors, canonical])
  return readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name)).flatMap((item) => {
    // Installed packages enter through imports, never as authored entries or copied source trees.
    if (item.name === "node_modules") return []
    const path = join(dir, item.name)
    // Follow each authored path independently, including multiple aliases of the same resource.
    const info = item.isSymbolicLink() ? statSync(path) : item
    return info.isDirectory() ? files(path, parents) : info.isFile() ? [path] : []
  })
}
const readJson = (file) => JSON.parse(readFileSync(file, "utf8"))
const write = (file, content) => { mkdirSync(dirname(file), { recursive: true }); writeFileSync(file, content) }
const copy = (from, to) => { mkdirSync(dirname(to), { recursive: true }); copyFileSync(from, to) }

// Esbuild reports canonical inputs. Track every installed package root, including ordinary
// directories beneath a linked node_modules or scope, to keep private sources out of the archive.
function installedPackageRoots(directories) {
  const visited = new Set(), roots = new Set()
  function packages(directory) {
    if (!existsSync(directory)) return
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name)
      if (entry.name.startsWith("@")) packages(path)
      else if ((entry.isDirectory() || entry.isSymbolicLink()) && existsSync(path)) roots.add(realpathSync(path))
    }
  }
  for (let directory of directories) {
    directory = resolve(directory)
    while (!visited.has(directory)) {
      visited.add(directory)
      packages(join(directory, "node_modules"))
      const parent = dirname(directory)
      if (parent === directory) break
      directory = parent
    }
  }
  return [...roots]
}

/** Shared by the CSS preparation and module bundling stages; independent of a producer checkout. */
export function libraryAssetNamespace(library, version) {
  return createHash("sha256").update(JSON.stringify([library, version])).digest("hex")
}
const prefix = (library, version) => `bml/libraries/${libraryAssetNamespace(library, version)}`
const publicPath = (library, version) => `/_bml/libraries/${libraryAssetNamespace(library, version)}`
const loaders = Object.fromEntries(["png", "jpg", "jpeg", "gif", "svg", "webp", "avif", "ico", "woff", "woff2", "ttf", "otf", "eot", "mp3", "mp4", "webm", "pdf", "wasm"].map((ext) => [`.${ext}`, "file"]))

function publicAssets(publicDir) {
  return {
    name: "bml-library-public-assets",
    setup(builder) {
      builder.onResolve({ filter: /^\// }, (args) => {
        if (args.path.startsWith("//") || (args.kind !== "url-token" && args.kind !== "import-rule")) return
        const [, path, suffix] = args.path.match(/^([^?#]+)(.*)$/s)
        const resolved = resolve(publicDir, `.${path}`)
        if (!resolved.startsWith(`${resolve(publicDir)}${sep}`)) throw new Error(`Invalid library public asset: ${args.path}`)
        return { path: resolved, suffix }
      })
    },
  }
}

function options(library, version, publicDir, projectDir, inputs) {
  return {
    absWorkingDir: resolve(projectDir), bundle: true, write: false, metafile: true,
    platform: "browser", target: "es2020", format: "esm", splitting: false,
    sourcemap: false, logLevel: "silent", legalComments: "eof",
    external: ["@bosca/bml", "@bosca/bml/*"], loader: loaders,
    assetNames: "assets/[name]-[hash]", publicPath: publicPath(library, version),
    plugins: [publicAssets(publicDir), {
      name: "bml-library-source-inputs",
      setup(builder) {
        const loaded = new Map(), workingDirectory = realpathSync(projectDir)
        builder.onLoad({ filter: /.*/, namespace: "file" }, ({ path, suffix }) => { loaded.set(path + suffix, path) })
        builder.onEnd((result) => {
          // Match physical loads to the final graph. Esbuild can call the file loader for an
          // import that is subsequently disabled; its synthetic metafile name will not match.
          for (const path of Object.keys(result.metafile?.inputs ?? {})) {
            const file = loaded.get(resolve(workingDirectory, path))
            if (file) inputs.add(file)
          }
        })
      },
    }],
  }
}

/** Bundle CSS before the compiler scopes it, including @imports and URL dependencies. */
export async function bundleLibraryStyles({ input, sourceRoot, publicDir, projectDir, outdir, library, version }) {
  rmSync(outdir, { recursive: true, force: true })
  const styles = {}, inputs = new Set()
  for (const entry of readJson(input)) {
    const source = realpathSync(resolve(sourceRoot, entry.source))
    const result = await build({
      ...options(library, version, publicDir, projectDir, inputs),
      stdin: { contents: entry.css, loader: "css", resolveDir: dirname(source), sourcefile: source },
      outfile: resolve(outdir, "resources", prefix(library, version), "inline.css"),
    })
    for (const output of result.outputFiles) {
      if (basename(output.path) === "inline.css") styles[entry.key] = { digest: entry.digest, css: output.text }
      else write(output.path, output.contents)
    }
  }
  write(join(outdir, "styles.json"), JSON.stringify(styles))
  write(join(outdir, "inputs.json"), JSON.stringify([...inputs].sort()))
}

/** Bundle and inventory a library's browser assets without embedding another copy of @bosca/bml. */
export async function bundleLibraryAssets({ manifestPath, inDir, sourceRoot, clientDir, publicDir, projectDir, stylesDir, outdir, sourcesOut, buildDir = join(projectDir, "build") }) {
  const manifest = readJson(manifestPath)
  const { library, version } = manifest
  const resourcePrefix = prefix(library, version)
  const root = resolve(outdir, resourcePrefix)
  rmSync(outdir, { recursive: true, force: true })
  rmSync(sourcesOut, { recursive: true, force: true })
  const inputs = new Set(existsSync(join(stylesDir, "inputs.json")) ? readJson(join(stylesDir, "inputs.json")) : [])
  const originRoot = existsSync(inDir) ? realpathSync(inDir) : resolve(inDir)
  const origins = existsSync(join(inDir, "bml-origins.json")) ? readJson(join(inDir, "bml-origins.json")) : {}
  const modules = {}, globalScripts = [], globalStyles = []
  const entryPoints = [], entryKinds = new Map(), generatedEntries = new Map()
  const sharedOptions = options(library, version, publicDir, projectDir, inputs)
  // Load generated TypeScript at its original BML path so both transforms and imports use
  // the source's configuration. Distinct generated modules keep their identity across aliases.
  const originPlugin = {
    name: "bml-original-client-sources",
    setup(builder) {
      builder.onResolve({ filter: /^\./ }, async (args) => {
        const importer = args.pluginData?.source
        if (!importer) return
        const sourceResult = await builder.resolve(args.path, { importer, resolveDir: dirname(importer), kind: args.kind, namespace: "file" })
        // Let esbuild attach the authored file's own tsconfig during normal resolution.
        if (sourceResult.errors.length === 0) return
        // Generated GraphQL/contract stubs use their established generated-directory imports.
        return builder.resolve(args.path, { resolveDir: resolve(inDir), kind: args.kind, namespace: "file" })
      })
      builder.onLoad({ filter: /.*/, namespace: "file" }, (args) => {
        const entry = generatedEntries.get(args.path + args.suffix)
        if (!entry) return
        return { contents: readFileSync(entry.generated, "utf8"), loader: "ts",
          resolveDir: dirname(args.path), pluginData: entry }
      })
    },
  }
  for (const module of [...new Set(manifest.components.map((it) => it.clientModule).filter(Boolean))].sort()) {
    if (!/^[A-Za-z_][A-Za-z0-9_]*\.js$/.test(module)) throw new Error(`Invalid library client module ${module}`)
    const entry = join(inDir, module.replace(/\.js$/, ".ts"))
    if (!existsSync(entry)) throw new Error(`Missing generated client module ${module} for ${library}`)
    const source = origins[slash(relative(originRoot, realpathSync(entry)))]
    const original = source ? realpathSync(resolve(sourceRoot, source)) : null
    const input = original ? `${original}?bml-module=${encodeURIComponent(module)}` : entry
    if (original) generatedEntries.set(input, { source: original, generated: entry })
    const path = `modules/${module}`
    entryPoints.push({ in: input, out: path.replace(/\.js$/, "") })
    modules[module] = { path, styles: [] }
    entryKinds.set(path, { module })
  }
  const entries = files(clientDir).filter((file) => /\.(ts|js|css)$/.test(file) && !file.endsWith(".d.ts"))
  const outputPaths = new Set()
  for (const entry of entries) {
    // JS entries may also emit CSS under client/. Keep authored CSS in a separate namespace.
    const path = `${entry.endsWith(".css") ? "styles" : "client"}/${slash(relative(clientDir, entry)).replace(/\.(ts|js)$/, ".js")}`
    if (outputPaths.has(path)) throw new Error(`Library client entries collide at ${path}`)
    outputPaths.add(path)
    entryPoints.push({ in: entry, out: path.replace(/\.(js|css)$/, "") })
    entryKinds.set(path, { global: dirname(entry) === resolve(clientDir), css: entry.endsWith(".css") })
  }
  if (entryPoints.length > 0) {
    const result = await build({ ...sharedOptions, entryPoints, outdir: root, splitting: true,
      chunkNames: "chunks/[name]-[hash]", plugins: [...sharedOptions.plugins, originPlugin] })
    const workingDirectory = realpathSync(projectDir)
    const outputMetadata = new Map(Object.entries(result.metafile.outputs).map(([path, info]) => [resolve(workingDirectory, path), info]))
    for (const output of result.outputFiles) {
      const info = outputMetadata.get(output.path)
      if (!info) throw new Error(`Missing esbuild output metadata for ${output.path}`)
      let content = output.contents
      if (output.path.endsWith(".js")) {
        let code = output.text
        // Asset URLs remain absolute, while ESM chunks use relative imports so a consumer can
        // rebundle the archive on disk. These specifiers come from esbuild's output metadata.
        for (const imported of info.imports.filter((it) => !it.external && ["import-statement", "dynamic-import"].includes(it.kind))) {
          const chunk = resolve(workingDirectory, imported.path)
          const specifier = `${publicPath(library, version)}/${slash(relative(root, chunk))}`
          const rel = slash(relative(dirname(output.path), chunk))
          code = code.split(JSON.stringify(specifier)).join(JSON.stringify(rel.startsWith(".") ? rel : `./${rel}`))
        }
        content = code
      }
      write(output.path, content)
      const path = slash(relative(root, output.path))
      const kind = entryKinds.get(path)
      const css = info.cssBundle ? slash(relative(root, resolve(workingDirectory, info.cssBundle))) : null
      if (kind?.module && css) modules[kind.module].styles.push(css)
      if (kind?.global) {
        if (kind.css) globalStyles.push(path)
        else { globalScripts.push(path); if (css) globalStyles.push(css) }
      }
    }
  }
  for (const file of files(join(stylesDir, "resources"))) copy(file, join(outdir, relative(join(stylesDir, "resources"), file)))
  for (const file of files(publicDir)) copy(file, join(root, "public", relative(publicDir, file)))
  // Original auxiliary files accompany the BML/Kotlin source attachment. Generated stubs and private
  // npm dependency sources are excluded; their executable code is already bundled above.
  const originals = new Map()
  for (const [dir, prefix] of [[sourceRoot, "bml"], [clientDir, "client"], [publicDir, "public"]]) {
    for (const file of files(dir)) {
      const path = resolve(file)
      if (!originals.has(path)) originals.set(path, `${prefix}/${slash(relative(resolve(dir), path))}`)
      inputs.add(path)
    }
  }
  const originalFiles = new Set([...originals.keys()].map((path) => realpathSync(path)))
  const projectRoot = realpathSync(projectDir)
  const buildRoot = existsSync(buildDir) ? realpathSync(buildDir) : resolve(buildDir)
  const sources = []
  const ownedRoots = [projectDir, sourceRoot].filter(existsSync).map((path) => realpathSync(path))
  const packageRoots = installedPackageRoots([projectDir, sourceRoot, ...[...inputs].map(dirname)])
    // A workspace also links the producer itself; its authored sources still belong in the archive.
    .filter((root) => !ownedRoots.some((owned) => owned === root || owned.startsWith(`${root}${sep}`)))
  for (const file of [...inputs].sort()) {
    if (file.split(sep).includes("node_modules") || file.startsWith(`${resolve(buildDir)}${sep}`)) continue
    if (!existsSync(file)) throw new Error(`Missing library asset source ${file}`)
    const realFile = realpathSync(file)
    if (realFile.startsWith(`${buildRoot}${sep}`)) continue
    const original = originals.get(file)
    // Esbuild reports canonical paths; preserve the authored source/archive paths, including aliases.
    if (!original && originalFiles.has(realFile)) continue
    if (!original && packageRoots.some((root) => realFile.startsWith(`${root}${sep}`))) continue
    const rel = relative(projectRoot, realFile)
    if (!original && (rel.startsWith(`..${sep}`) || rel === "..")) throw new Error(`Library asset must be inside its project or configured BML source root: ${file}`)
    const path = original ?? `assets/${slash(rel)}`
    copy(file, join(sourcesOut, path))
    sources.push({ path, digest: digest(readFileSync(file)) })
  }
  const inventory = files(root).map((file) => ({ path: slash(relative(root, file)), digest: digest(readFileSync(file)) }))
  write(join(outdir, "META-INF/bml/client-manifest.json"), JSON.stringify({
    formatVersion: 1, library, version, resourcePrefix, urlPrefix: publicPath(library, version),
    modules, globalScripts, globalStyles: [...new Set(globalStyles)], files: inventory, sources,
  }, null, 2))
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const arg = (flag) => process.argv[process.argv.indexOf(flag) + 1]
  bundleLibraryStyles({ input: arg("--input"), sourceRoot: arg("--source-root"), publicDir: arg("--public-in"),
    projectDir: arg("--project-dir"), outdir: arg("--out"), library: arg("--library-id"), version: arg("--library-version") })
    .catch((error) => { console.error(error); process.exitCode = 1 })
}
