import { setupNuxtAuth, type TokenStorage } from '@bosca/auth-client-browser'
import { buildSsrTokenStorage } from '~/utils/authSsr'

/**
 * Initializes the Bosca auth client on both the server (SSR) and the
 * client. On the server, the browser-only `CookieStorage` cannot read
 * cookies, so we synchronously extract `_bat` / `_bat_rt` from the
 * incoming request and seed a `MemoryStorage` instance — that lets the
 * auth client mint Bearer headers during SSR data fetches via the same
 * `$auth.getAuthHeaders()` API used in the browser.
 *
 * All Nuxt composable calls happen synchronously before any await:
 * Nuxt's request context does not survive await boundaries in plugins.
 */
export default defineNuxtPlugin(async (nuxtApp) => {
  const config = useRuntimeConfig()

  // Browser uses an empty apiUrl so the dev proxy / Gateway handles
  // relative `/graphql`. SSR has no proxy in front of it, so it must
  // hit the private runtime apiUrl directly.
  const apiUrl = import.meta.server
    ? (config.apiUrl as string)
    : ((config.public.apiUrl as string) || '')
  const authCookiePrefix = (config.public.authCookiePrefix as string) || undefined
  const cookieHeader = import.meta.server
    ? useRequestHeaders(['cookie']).cookie
    : undefined
  let storage: 'cookie' | TokenStorage = 'cookie'
  if (import.meta.server) {
    storage = buildSsrTokenStorage(cookieHeader, authCookiePrefix)
  }

  const auth = await setupNuxtAuth(nuxtApp, {
    apiUrl,
    tokenName: authCookiePrefix,
    cookieDomain: config.public.authDomain as string,
    storage,
    autoRefresh: import.meta.client,
    refreshBuffer: 60_000,
    // The reactive profile state is only read on the client
    // (`useAuth()` is gated behind `import.meta.client` in the default
    // layout), so skip the SSR profile round-trip — the client plugin
    // rehydrates it after mount.
    fetchProfileOnInit: import.meta.client,
  })

  return {
    provide: { auth },
  }
})
