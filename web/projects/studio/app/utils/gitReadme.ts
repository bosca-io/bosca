export interface RepositoryTreeEntry {
  name: string
  path: string
  type: string
}

const README_PRIORITY = [
  'readme.md',
  'readme.markdown',
  'readme.mdown',
  'readme.mkdn',
  'readme',
] as const
const README_PRIORITY_BY_NAME = new Map<string, number>(
  README_PRIORITY.map((name, priority) => [name, priority]),
)

/** Selects the conventional root README that Studio can safely render as Markdown. */
export function selectRepositoryReadme<T extends RepositoryTreeEntry>(entries: readonly T[]): T | null {
  const candidates = entries.filter(entry =>
    entry.type !== 'TREE' &&
    !entry.path.includes('/') &&
    README_PRIORITY_BY_NAME.has(entry.name.toLowerCase()),
  )
  if (!candidates.length) return null

  return [...candidates].sort((a, b) => {
    const aPriority = README_PRIORITY_BY_NAME.get(a.name.toLowerCase()) ?? Number.MAX_SAFE_INTEGER
    const bPriority = README_PRIORITY_BY_NAME.get(b.name.toLowerCase()) ?? Number.MAX_SAFE_INTEGER
    return aPriority - bPriority || a.name.localeCompare(b.name)
  })[0] ?? null
}
