import { setupNuxtAnalytics } from '@bosca/analytics-client-browser'

/**
 * Initializes the Bosca analytics client for the bosca.io site.
 *
 * Client-only: the SDK uses `fetch`/`sendBeacon`, so it must never run during
 * SSR. Events post to the same-origin `analyticsUrl` (`/api/v1` by default),
 * which the bosca.io HTTPRoute — and the local dev proxy — forward to
 * bosca-collector. Runs after `01.auth`, so `$auth` is already initialized and
 * can attribute events to the signed-in principal.
 */
export default defineNuxtPlugin((nuxtApp) => {
  const config = useRuntimeConfig()
  const analyticsUrl = config.public.analyticsUrl as string
  if (!analyticsUrl) return

  const { sink, instrumentation } = setupNuxtAnalytics(nuxtApp, {
    url: analyticsUrl,
    appId: 'bosca-io',
    appVersion: config.public.appVersion as string || '0.0.0',
    clientId: 'web',
    debug: import.meta.dev
  })

  // Attribute events to the signed-in principal when auth is present.
  const { $auth } = useNuxtApp()
  if ($auth) {
    if ($auth.currentUser) {
      sink.setUserId($auth.currentUser.id)
    }
    $auth.onAuthStateChanged((user: { id: string } | null) => {
      sink.setUserId(user?.id ?? null)
    })
  }

  return {
    provide: {
      analytics: { sink, instrumentation }
    }
  }
})
