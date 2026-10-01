import { setupBoscaForms } from '@bosca/forms'

/**
 * Bosca Forms for the public marketing site.
 *
 * Visitors here are anonymous, so no token is supplied — requests go
 * same-origin to the `/graphql` proxy declared in nuxt.config, which forwards
 * to the API. Client-only: forms are opened from a modal, never rendered
 * during prerender.
 */
export default defineNuxtPlugin((nuxtApp) => {
  const config = useRuntimeConfig()

  const forms = setupBoscaForms(nuxtApp, {
    apiUrl: (config.public.apiUrl as string) || ''
  })

  return {
    provide: { forms }
  }
})
