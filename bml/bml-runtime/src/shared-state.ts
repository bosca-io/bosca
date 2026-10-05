/**
 * Page-wide runtime state shared by every separately bundled copy of `@bosca/bml` on a page (the
 * global app bundle and each page bundle), keyed by a `Symbol.for` name on `globalThis`.
 *
 * Bundles built from different runtime releases can meet on one page during a deploy. The first
 * bundle to load creates the object; a newer bundle that loads later adds any fields it expects
 * but the older object lacks, so it never reads `undefined` where it expects state. Existing
 * fields are left alone. A field whose meaning changes must therefore get a new name.
 */
export function sharedState<T extends object>(key: string, defaults: () => T): T {
  const host = globalThis as unknown as Record<symbol, T | undefined>
  const symbol = Symbol.for(key)
  const existing = host[symbol]
  if (existing === undefined) {
    const created = defaults()
    host[symbol] = created
    return created
  }
  const fresh = defaults() as Record<string, unknown>
  const target = existing as Record<string, unknown>
  for (const field of Object.keys(fresh)) {
    if (!(field in target)) target[field] = fresh[field]
  }
  return existing
}
