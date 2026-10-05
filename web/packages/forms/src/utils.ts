/** Navigates a dot-separated path into a nested object. */
export function resolvePath(obj: unknown, path: string): unknown {
  return path.split('.').reduce(
    (acc, key) => (acc && typeof acc === 'object') ? (acc as Record<string, unknown>)[key] : undefined,
    obj,
  )
}
