import { setupBoscaForms } from '@bosca/forms'

export default defineNuxtPlugin((nuxtApp) => {
  const config = useRuntimeConfig()
  const { $auth } = useNuxtApp()

  const forms = setupBoscaForms(nuxtApp, {
    apiUrl: config.public.apiUrl as string || '',
    getToken: () => $auth.token ?? undefined,
    getProfile: () => $auth.currentProfile ?? undefined,
  })

  return {
    provide: { forms },
  }
})
