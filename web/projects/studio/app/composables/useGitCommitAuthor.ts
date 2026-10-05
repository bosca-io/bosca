/**
 * Resolves the `authorName` / `authorEmail` pair used as commit metadata when
 * Studio writes back to git (analytics-query edit push-back, backfill, etc.).
 *
 * Pulls from the authenticated profile so a Studio-driven commit reads as the
 * actual user who triggered it. Falls back to neutral placeholders when the
 * profile is missing fields (or absent entirely, e.g. during SSR).
 *
 * Kept as a pure function so it can be unit-tested without a live Nuxt /
 * `useAuth` setup. Components consume it via the `useGitCommitAuthor` composable
 * below, which wires in `useAuth()` and returns `Ref<string>` for template use.
 */

export interface ProfileLike {
  name?: string | null
  slug?: string | null
  attributes?: Array<{
    typeId: string
    attributes?: { email?: unknown } | null
  }> | null
}

export interface GitCommitAuthor {
  authorName: string
  authorEmail: string
}

const NAME_FALLBACK = 'Studio User'
const EMAIL_FALLBACK = 'studio@bosca.io'
const EMAIL_ATTR_TYPE_ID = 'bosca.profiles.email'

/**
 * Pure resolver: profile in → author pair out. Used by the composable and by
 * the unit tests.
 */
export function resolveGitCommitAuthor(profile: ProfileLike | null | undefined): GitCommitAuthor {
  const name = profile?.name || profile?.slug || NAME_FALLBACK
  const emailAttr = profile?.attributes?.find(a => a.typeId === EMAIL_ATTR_TYPE_ID)
  const rawEmail = emailAttr?.attributes?.email
  const email = typeof rawEmail === 'string' && rawEmail ? rawEmail : EMAIL_FALLBACK
  return { authorName: name, authorEmail: email }
}
