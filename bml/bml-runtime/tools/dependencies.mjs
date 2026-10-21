import { createHash } from "node:crypto"
import { existsSync, readFileSync, readdirSync, realpathSync } from "node:fs"
import { dirname, join, relative, resolve, sep } from "node:path"
import { version as esbuildVersion } from "esbuild"

const digest = (bytes) => `sha256:${createHash("sha256").update(bytes).digest("hex")}`
const slash = (path) => path.split(sep).join("/")

// Esbuild reports canonical inputs. Track every installed package root, including ordinary
// directories beneath a linked node_modules or scope, to keep private sources out of the archive.
export function installedPackageRoots(directories) {
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

/**
 * Identify closed ESM graphs by their actual bytes, package metadata, and resolution edges.
 * No checkout paths enter the identity. CSS, CJS, external/dynamic imports, disabled browser
 * modules and source transforms are deliberately ineligible; they retain normal bundling.
 */
export function dependencyGraph(metafile, workingDirectory, packageRoots) {
  const nodes = new Map(), packages = new Map()
  const boundaries = new Set(Object.values(metafile.inputs).flatMap((info) => info.imports)
    .filter((it) => !it.external && it.original && !/^(?:\.|\/)/.test(it.original)).map((it) => it.path))
  for (const [input, info] of Object.entries(metafile.inputs)) {
    const path = resolve(workingDirectory, input)
    if (info.format !== "esm" || !/\.m?js$/.test(path) || !existsSync(path)) continue
    const file = realpathSync(path)
    const root = packageRoots.filter((root) => file.startsWith(`${root}${sep}`)).sort((a, b) => b.length - a.length)[0]
    if (!root) continue
    if (!packages.has(root)) {
      const manifest = join(root, "package.json")
      const bytes = existsSync(manifest) ? readFileSync(manifest) : null
      const metadata = bytes ? JSON.parse(bytes.toString()) : {}
      packages.set(root, metadata.name && metadata.version ? [metadata.name, metadata.version, digest(bytes)] : null)
    }
    const owner = packages.get(root)
    if (!owner) continue
    nodes.set(input, { file, identity: JSON.stringify([...owner, slash(relative(root, file))]),
      digest: digest(readFileSync(file)), imports: info.imports.filter((it) => it.path !== "<runtime>") })
  }
  const result = new Map()
  for (const [input, entry] of nodes) {
    if (!boundaries.has(input)) continue
    const visited = new Map()
    function visit(path) {
      if (visited.has(path)) return true
      const node = nodes.get(path)
      if (!node) return false
      visited.set(path, node)
      return node.imports.every((it) => !it.external && it.kind === "import-statement" && visit(it.path))
    }
    if (!visit(input)) continue
    const graph = [...visited.values()].map((node) => [node.identity, node.digest,
      node.imports.map((it) => [it.original, it.kind, nodes.get(it.path).identity]).sort()])
      .sort((a, b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)
    result.set(entry.file, digest(JSON.stringify([esbuildVersion, "browser", "es2020", entry.identity, graph])))
  }
  return result
}

/** Read only metadata extracted and digest-verified by the binary library linker. */
export function linkedDependencies(directory) {
  const index = directory && join(directory, "bml-client-dependencies.json")
  const modules = new Map(), entries = new Map()
  if (!index || !existsSync(index)) return { modules, entries }
  const data = JSON.parse(readFileSync(index, "utf8"))
  function path(file) {
    if (typeof file !== "string") throw new Error("Invalid linked BML dependency path")
    const resolved = resolve(directory, file)
    if (!resolved.startsWith(`${resolve(directory)}${sep}`) || !existsSync(resolved)) throw new Error("Missing or unsafe linked BML dependency module")
    return realpathSync(resolved)
  }
  for (const entry of data.modules) {
    if (!/^sha256:[a-f0-9]{64}$/.test(entry.digest)) throw new Error("Invalid linked BML dependency module")
    if (!modules.has(entry.digest)) modules.set(entry.digest, path(entry.path))
  }
  for (const [original, shared] of Object.entries(data.entries)) entries.set(path(original), path(shared))
  return { modules, entries }
}

/** Match the application's real resolution graph before redirecting any of its npm modules. */
export function dependencyPlugin(graph, linked, entries = new Map()) {
  const replacements = new Map([...graph].filter(([, digest]) => linked.has(digest)).map(([path, digest]) => [path, linked.get(digest)]))
  if (replacements.size > 0) for (const [path, shared] of entries) replacements.set(path, shared)
  return {
    name: "bml-shared-dependencies",
    setup(builder) {
      builder.onResolve({ filter: /.*/ }, async (args) => {
        if (args.pluginData?.bmlDependencyResolution || args.namespace !== "file" || args.kind === "entry-point") return
        const resolved = await builder.resolve(args.path, { importer: args.importer, resolveDir: args.resolveDir,
          kind: args.kind, namespace: args.namespace, pluginData: { bmlDependencyResolution: true } })
        const replacement = replacements.get(resolved.path)
        if (replacement) return { path: replacement, sideEffects: resolved.sideEffects, suffix: resolved.suffix }
      })
    },
  }
}
