import { parse } from "acorn"
import { posix } from "node:path"

/** Keep bundled BML imports beneath their deployment URL without changing unrelated JS literals. */
export function relativeBmlModuleImports(code, modulePath) {
  if (!code.includes("/_bml/")) return code
  const edits = []
  const visit = (node) => {
    const source = ["ImportDeclaration", "ExportNamedDeclaration", "ExportAllDeclaration", "ImportExpression"].includes(node.type)
      ? node.source : null
    const specifier = source?.type === "Literal" ? source.value
      : source?.type === "TemplateLiteral" && source.expressions.length === 0 ? source.quasis[0].value.cooked : null
    if (typeof specifier === "string" && (specifier.startsWith("/_bml/js/") || specifier.startsWith("/_bml/libraries/"))) {
      const logical = specifier.startsWith("/_bml/js/") ? specifier.slice("/_bml/js/".length) : specifier.slice("/_bml/".length)
      const [, path, suffix] = logical.match(/^([^?#]*)(.*)$/s)
      const relative = posix.relative(posix.dirname(modulePath), path)
      const replacement = (relative.startsWith(".") ? relative : `./${relative}`) + suffix
      edits.push({ start: source.start, end: source.end, value: JSON.stringify(replacement) })
    }
    for (const value of Object.values(node)) {
      if (Array.isArray(value)) value.forEach(child => { if (child?.type) visit(child) })
      else if (value?.type) visit(value)
    }
  }
  visit(parse(code, { ecmaVersion: "latest", sourceType: "module" }))
  for (const edit of edits.sort((a, b) => b.start - a.start)) code = code.slice(0, edit.start) + edit.value + code.slice(edit.end)
  return code
}
