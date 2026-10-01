export interface ActiveProfileMention {
  start: number
  end: number
  query: string
}

const handleCharacter = /^[A-Za-z0-9._-]$/
const handleBoundaryCharacter = /^[A-Za-z0-9_@]$/

export function findActiveProfileMention(value: string, cursor: number): ActiveProfileMention | null {
  const safeCursor = Math.max(0, Math.min(cursor, value.length))
  const beforeCursor = value.slice(0, safeCursor)
  const start = beforeCursor.lastIndexOf('@')
  if (start < 0) return null

  const preceding = start > 0 ? value[start - 1]! : ''
  if (preceding && handleBoundaryCharacter.test(preceding)) return null

  const query = value.slice(start + 1, safeCursor)
  if ([...query].some(character => !handleCharacter.test(character))) return null

  let end = safeCursor
  while (end < value.length && handleCharacter.test(value[end]!)) end++

  return { start, end, query }
}

export function replaceProfileMention(
  value: string,
  mention: ActiveProfileMention,
  handle: string,
): { value: string; cursor: number } {
  const suffix = value.slice(mention.end)
  const replacement = `@${handle}${/^\s/.test(suffix) ? '' : ' '}`
  return {
    value: value.slice(0, mention.start) + replacement + suffix,
    cursor: mention.start + replacement.length,
  }
}
