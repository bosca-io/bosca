import { setupNuxtAnalytics } from '@bosca/analytics-client-browser'

export default defineNuxtPlugin((nuxtApp) => {
  const config = useRuntimeConfig()
  const analyticsUrl = config.public.analyticsUrl as string
  if (!analyticsUrl) return

  const { sink, instrumentation } = setupNuxtAnalytics(nuxtApp, {
    url: analyticsUrl,
    appId: 'studio',
    appVersion: config.public.appVersion as string || '0.0.0',
    clientId: 'web',
    debug: import.meta.dev,
    anonymous: config.public.analyticsAnonymous as boolean ?? false,
    omitCredentials: config.public.analyticsOmitCredentials as boolean ?? false,
  })

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
      analytics: { sink, instrumentation },
    },
  }
})
