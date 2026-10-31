/** Asset loaders shared by application and compiled-library CSS bundling. */
export const assetLoaders = Object.fromEntries([
  "png", "jpg", "jpeg", "gif", "svg", "webp", "avif", "ico", "woff", "woff2", "ttf", "otf", "eot",
  "mp3", "mp4", "webm", "pdf", "wasm",
].map(extension => [`.${extension}`, "file"]))

const absoluteReference = path => /^(?:\/|#|[a-z][a-z0-9+.-]*:)/i.test(path)

/** Preserve public and external URLs while esbuild resolves local source dependencies. */
export const publicCssUrls = {
  name: "bml-public-css-urls",
  setup(builder) {
    builder.onResolve({ filter: /.*/ }, args => {
      if (["url-token", "import-rule"].includes(args.kind) && absoluteReference(args.path)) {
        return { path: args.path, external: true }
      }
    })
  },
}

/** Validate parsed CSS references rather than matching URL-like text in strings or comments. */
export function validateCssOutputs(result) {
  for (const [file, output] of Object.entries(result.metafile.outputs)) {
    if (!file.endsWith(".css")) continue
    for (const dependency of output.imports) {
      if (dependency.external && !absoluteReference(dependency.path)) {
        throw new Error(`Unresolved relative CSS URL ${JSON.stringify(dependency.path)} in ${file}; use a resolvable source import or a URL beginning with /`)
      }
    }
  }
}
