import { build, transform } from "esbuild"
import { parse } from "acorn"
import { dependencyGraph, dependencyPlugin, installedPackageRoots } from "./dependencies.mjs"
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

// Keep generated namespace construction removable when the binary is bundled again. Its fresh
// object and getter table have no outside effects; callers using that namespace retain the IIFE.
function namespaceModule(code) {
  const ast = code.includes("__export") ? parse(code, { ecmaVersion: "latest", sourceType: "module" }) : null
  const helpers = new Set()
  for (const statement of ast?.body ?? []) for (const declaration of statement.declarations ?? []) {
    if (/^__export\d*$/.test(declaration.id.name) && declaration.init?.type === "ArrowFunctionExpression" &&
      /^\(target, all\) => \{\s*for \(var name in all\)\s*__defProp\d*\(target, name, \{ get: all\[name\], enumerable: true \}\);\s*\}$/.test(code.slice(declaration.init.start, declaration.init.end)))
      helpers.add(declaration.id.name)
  }
  const exports = new Set((ast?.body ?? []).flatMap(statement => statement.type === "ExportNamedDeclaration"
    ? statement.specifiers.filter(specifier => helpers.has(specifier.local.name)).map(specifier => specifier.exported.name) : []))
  return { code, ast, helpers, exports }
}

function pureNamespaces(module, path, modules) {
  let { code } = module
  const helpers = new Set(module.helpers), edits = []
  const body = module.ast?.body ?? []
  for (const statement of body) if (statement.type === "ImportDeclaration") {
    const exports = modules.get(resolve(dirname(path), statement.source.value))?.exports
    for (const binding of statement.specifiers) if (binding.type === "ImportSpecifier" && exports?.has(binding.imported.name)) helpers.add(binding.local.name)
  }
  for (let i = 0; i + 1 < body.length; i++) {
    const statement = body[i], next = body[i + 1], declaration = statement.declarations?.[0]
    const call = next.type === "ExpressionStatement" ? next.expression : null
    if (statement.type !== "VariableDeclaration" || statement.kind !== "var" || statement.declarations.length !== 1 ||
      declaration.id.type !== "Identifier" || declaration.init?.type !== "ObjectExpression" || declaration.init.properties.length ||
      call?.type !== "CallExpression" || call.callee.type !== "Identifier" || !helpers.has(call.callee.name) || call.arguments.length !== 2 ||
      call.arguments[0].type !== "Identifier" || call.arguments[0].name !== declaration.id.name || call.arguments[1].type !== "ObjectExpression" ||
      !call.arguments[1].properties.every(property => property.type === "Property" && property.kind === "init" && !property.computed && property.value.type === "ArrowFunctionExpression")) continue
    const name = declaration.id.name
    edits.push({ start: statement.start, end: next.end,
      code: `var ${name} = /* @__PURE__ */ (() => { ${code.slice(statement.start, next.end)} return ${name}; })();` })
  }
  for (const edit of edits.reverse()) code = code.slice(0, edit.start) + edit.code + code.slice(edit.end)
  return code
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
    // The consuming build selects its environment; a reusable binary must retain both branches.
    define: { "process.env.NODE_ENV": "process.env.NODE_ENV" },
    minifySyntax: true, minifyWhitespace: true, minifyIdentifiers: true,
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
export async function bundleLibraryAssets({ manifestPath, inDir, sourceRoot, clientDir, publicDir, projectDir, stylesDir, outdir, sourcesOut, inputsOut, buildDir = join(projectDir, "build") }) {
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
  const dependencyModules = [], sharedEntries = {}
  if (entryPoints.length > 0) {
    const buildOptions = { ...sharedOptions, entryPoints, outdir: root, splitting: true,
      minifySyntax: false, minifyWhitespace: false, minifyIdentifiers: false,
      chunkNames: "chunks/[name]-[hash]", plugins: [...sharedOptions.plugins, originPlugin] }
    const ordinary = await build(buildOptions)
    const sharedRoot = join(root, "shared")
    const sharedPoints = entryPoints.filter((it) => !it.in.endsWith(".css"))
    buildOptions.entryPoints = sharedPoints
    buildOptions.outdir = sharedRoot
    buildOptions.publicPath = `${publicPath(library, version)}/shared`
    const dependencyEntries = new Map()
    let result
    // Export full ESM dependency surfaces, not just the symbols used by this producer. A
    // consumer can then share the compiled module while tree-shaking its own selected exports.
    for (;;) {
      result = sharedPoints.length > 0 ? await build(buildOptions) : ordinary
      const packageRoots = installedPackageRoots([projectDir, sourceRoot, clientDir,
        ...Object.keys(result.metafile.inputs).map((input) => dirname(resolve(projectDir, input)))])
      const graph = dependencyGraph(result.metafile, projectDir, packageRoots)
      const additions = [...graph].filter(([path]) => !dependencyEntries.has(path))
      if (additions.length === 0) {
        for (const [path, digest] of graph) {
          if (!dependencyModules.some((it) => it.digest === digest)) dependencyModules.push({ digest, path: "shared/" + dependencyEntries.get(path) + ".js" })
        }
        break
      }
      const canonical = new Map()
      for (const [path, digest] of graph) if (!canonical.has(digest)) canonical.set(digest, path)
      buildOptions.plugins = [...sharedOptions.plugins, originPlugin, dependencyPlugin(graph, canonical)]
      const emitted = new Set(dependencyEntries.values())
      for (const [path, digest] of additions) {
        const output = `dependencies/${digest.slice(7)}`
        dependencyEntries.set(path, output)
        if (!emitted.has(output)) { sharedPoints.push({ in: path, out: output }); emitted.add(output) }
      }
    }
    // Keep the ordinary entry graph for pages without matching application dependencies.
    // Full dependency exports can retain otherwise unused initializers after rebundling.
    const ordinaryFiles = new Map(ordinary.outputFiles.map((it) => [it.path, it]))
    const sharedFiles = new Map(result.outputFiles.map((it) => [it.path, it]))
    const workingDirectory = realpathSync(projectDir)
    const componentStyles = Object.values(ordinary.metafile.outputs)
      .filter((it) => it.cssBundle && !it.entryPoint?.endsWith(".css")).map((it) => resolve(workingDirectory, it.cssBundle))
    const compatibleStyles = componentStyles.every((path) => {
      const other = sharedFiles.get(join(sharedRoot, relative(root, path)))
      return other && other.text.split(`${publicPath(library, version)}/shared/`).join(`${publicPath(library, version)}/`) === ordinaryFiles.get(path).text
    })
    const outputs = [{ result: ordinary }]
    if (dependencyModules.length > 0 && compatibleStyles) {
      outputs.push({ result })
      for (const path of entryKinds.keys()) if (path.endsWith(".js") && ordinaryFiles.has(join(root, path)) && sharedFiles.has(join(sharedRoot, path))) sharedEntries[path] = `shared/${path}`
    } else dependencyModules.splice(0)
    for (const { result } of outputs) {
      const outputMetadata = new Map(Object.entries(result.metafile.outputs).map(([path, info]) => [resolve(workingDirectory, path), info]))
      const scripts = new Map()
      for (const output of result.outputFiles.filter(file => file.path.endsWith(".js"))) {
        let code = output.text
        for (const imported of outputMetadata.get(output.path).imports.filter(it => !it.external && ["import-statement", "dynamic-import"].includes(it.kind))) {
          const chunk = resolve(workingDirectory, imported.path)
          const specifier = `${publicPath(library, version)}/${slash(relative(root, chunk))}`
          const rel = slash(relative(dirname(output.path), chunk))
          code = code.split(JSON.stringify(specifier)).join(JSON.stringify(rel.startsWith(".") ? rel : `./${rel}`))
        }
        scripts.set(output.path, namespaceModule(code))
      }
      for (const output of result.outputFiles) {
        const info = outputMetadata.get(output.path)
        if (!info) throw new Error(`Missing esbuild output metadata for ${output.path}`)
        let content = output.contents
        if (output.path.endsWith(".js")) {
          // Whitespace minification removes PURE annotations needed by the consuming bundle.
          content = (await transform(pureNamespaces(scripts.get(output.path), output.path, scripts), { loader: "js", format: "esm", target: "es2020",
            minifySyntax: true, minifyIdentifiers: true, minifyWhitespace: false,
            legalComments: "eof", define: { "process.env.NODE_ENV": "process.env.NODE_ENV" } })).code
        } else if (output.path.endsWith(".css")) {
          content = (await transform(output.text, { loader: "css", minify: true, legalComments: "eof" })).code
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
  }
  for (const file of files(join(stylesDir, "resources"))) copy(file, join(outdir, relative(join(stylesDir, "resources"), file)))
  for (const file of files(publicDir)) copy(file, join(root, "public", relative(publicDir, file)))
  // Authored declarations travel in the binary too. Ambient modules provide stable type-only
  // imports without an npm package or a dependency on the producer's source attachment.
  for (const file of files(clientDir).filter((file) => file.endsWith(".d.ts"))) {
    copy(file, join(root, "client", relative(clientDir, file)))
  }
  // Original auxiliary files accompany the BML/Kotlin source attachment. Generated stubs and private
  // npm dependency sources are excluded; their executable code is already bundled above.
  const originals = new Map()
  for (const [dir, prefix] of [[sourceRoot, "bml"], [clientDir, "client"], [publicDir, "public"]]) {
    for (const file of files(dir)) {
      // Kotlin beside BML is a Kotlin source-set file; the source attachment keeps it at its source-root path.
      if (prefix === "bml" && /\.kts?$/.test(file)) continue
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
    modules, globalScripts, globalStyles: [...new Set(globalStyles)], dependencyModules, sharedEntries, files: inventory, sources,
  }, null, 2))
  // Every file this build read, so the build tool can track exactly these (plus their siblings).
  if (inputsOut) write(inputsOut, JSON.stringify([...inputs].sort()))
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const arg = (flag) => {
    const index = process.argv.indexOf(flag)
    if (index < 0 || index + 1 >= process.argv.length) throw new Error(`Missing required argument ${flag}`)
    return process.argv[index + 1]
  }
  bundleLibraryStyles({ input: arg("--input"), sourceRoot: arg("--source-root"), publicDir: arg("--public-in"),
    projectDir: arg("--project-dir"), outdir: arg("--out"), library: arg("--library-id"), version: arg("--library-version") })
    .catch((error) => { console.error(error); process.exitCode = 1 })
}
