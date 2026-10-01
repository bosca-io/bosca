import { setupNuxtAuth } from '@bosca/auth-client-browser'

/**
 * Provides optional browser sign-in state for account pages and analytics.
 * Public pages render without resolving a session during SSR.
 */
export default defineNuxtPlugin(async (nuxtApp) => {
  const config = useRuntimeConfig()

  const auth = await setupNuxtAuth(nuxtApp, {
    // The browser stays same-origin; SSR keeps an absolute URL for server-side calls.
    apiUrl: import.meta.server
      ? (config.apiUrl as string)
      : ((config.public.apiUrl as string) || ''),
    tokenName: (config.public.authCookiePrefix as string) || undefined,
    cookieDomain: config.public.authDomain as string,
    autoRefresh: import.meta.client,
    refreshBuffer: 60_000,
    skipInitOnServer: true
  })

  return {
    provide: { auth }
  }
})
