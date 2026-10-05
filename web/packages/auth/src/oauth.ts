import type { LinkProofMethod } from './errors'
import type { OAuthRedirectOptions } from './types'

/**
 * Navigates the browser to the Bosca backend's OAuth login endpoint,
 * initiating a redirect-based authentication flow with the specified
 * third-party provider. The backend handles PKCE, state management,
 * and the full OAuth dance before redirecting back with an exchange token.
 *
 * @param apiUrl - Base URL of the Bosca API
 * @param options - OAuth provider and redirect configuration
 */
export function startOAuthRedirect(apiUrl: string, options: OAuthRedirectOptions): void {
  const provider = options.provider.toLowerCase()
  const redirectUrl = options.redirectUrl ?? (globalThis.window ? window.location.href : '')

  const params = new URLSearchParams()
  params.set('redirect', redirectUrl)

  if (options.organization) {
    params.set('organization', options.organization)
  }
  if (options.community) {
    params.set('community', options.community)
  }
  if (options.originator) {
    params.set('originator', options.originator)
  }

  const url = `${apiUrl}/oauth2/${provider}/login?${params.toString()}`

  if (globalThis.window) {
    window.location.href = url
  }
}

/**
 * Checks the current URL for an OAuth exchange token returned by the
 * backend after a successful redirect-based OAuth flow. The backend
 * appends the token as a query parameter when redirecting back.
 *
 * @returns The exchange token string if present, or null if this is
 *          not an OAuth redirect return
 */
export function getExchangeTokenFromUrl(): string | null {
  if (!globalThis.window) return null

  const params = new URLSearchParams(window.location.search)
  const token = params.get('exchangeToken')

  if (token) {
    // Clean the token parameter from the URL to avoid reuse on page refresh
    const url = new URL(window.location.href)
    url.searchParams.delete('exchangeToken')
    window.history.replaceState({}, '', url.toString())
  }

  return token
}

/**
 * Checks the current URL for an account-linking token. The backend appends
 * `?link=<token>` when an OAuth sign-in collides with an existing verified
 * account, so the app can route the user into the proof/linking challenge.
 * Mirrors {@link getExchangeTokenFromUrl}.
 *
 * @returns The pending-link token if present, or null
 */
export function getLinkTokenFromUrl(): string | null {
  if (!globalThis.window) return null

  const params = new URLSearchParams(window.location.search)
  const token = params.get('link')

  if (token) {
    // Clean the token parameter from the URL to avoid reuse on page refresh
    const url = new URL(window.location.href)
    url.searchParams.delete('link')
    window.history.replaceState({}, '', url.toString())
  }

  return token
}

/**
 * Checks the current URL for the proof methods the backend offers for the
 * colliding account. The OAuth callback appends `?methods=PASSWORD,EMAIL`
 * (or just `EMAIL` for an OAuth-only account with no password) alongside the
 * `?link=` token, so the confirm screen can hide proofs that would only fail.
 * Mirrors {@link getLinkTokenFromUrl}: reads, then strips its own param so a
 * refresh doesn't replay stale state.
 *
 * Deletes only `methods`, so it composes with {@link getLinkTokenFromUrl}
 * regardless of call order (each helper strips its own parameter).
 *
 * @returns The parsed proof methods, or null if the parameter is absent
 */
export function getLinkMethodsFromUrl(): LinkProofMethod[] | null {
  if (!globalThis.window) return null

  const params = new URLSearchParams(window.location.search)
  const raw = params.get('methods')
  if (!raw) return null

  const url = new URL(window.location.href)
  url.searchParams.delete('methods')
  window.history.replaceState({}, '', url.toString())

  return raw
    .split(',')
    .map((m) => m.trim())
    .filter((m): m is LinkProofMethod => m === 'PASSWORD' || m === 'EMAIL')
}
