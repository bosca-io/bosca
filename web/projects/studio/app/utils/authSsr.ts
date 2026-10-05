import { MemoryStorage, type TokenStorage } from '@bosca/auth-client-browser'

/** Builds request-local SSR token storage using the same cookie prefix as browser storage. */
export function buildSsrTokenStorage(
  cookieHeader: string | undefined,
  authCookiePrefix?: string,
): TokenStorage {
  const storage = new MemoryStorage()
  if (!cookieHeader) return storage

  const tokenName = authCookiePrefix?.trim() || '_bat'
  const accessToken = readCookie(cookieHeader, tokenName)
  if (accessToken) {
    storage.setToken(accessToken)
  }

  const refreshToken = readCookie(cookieHeader, `${tokenName}_rt`)
  if (refreshToken) storage.setRefreshToken(refreshToken)

  return storage
}

function readCookie(cookieHeader: string, name: string): string | null {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = cookieHeader.match(new RegExp('(?:^|;\\s*)' + escaped + '=([^;]*)'))
  return match?.[1] ? decodeURIComponent(match[1]) : null
}
