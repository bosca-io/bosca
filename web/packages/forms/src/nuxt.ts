import { inject, ref, type InjectionKey, type Ref } from 'vue'
import type { FormSchema, FormSchemaInput, FormSubmissionInput, SubmittedForm } from './types'
import * as api from './graphql'

/**
 * Configuration for the Bosca Forms Nuxt plugin. Mirrors the
 * setupNuxtAuth pattern from @bosca/auth-client-browser.
 */
/**
 * Minimal profile attribute shape used for pre-filling form fields.
 * Matches the attribute structure from @bosca/auth-client-browser
 * without creating a hard dependency.
 */
export interface FormsProfileAttribute {
  typeId: string
  attributes: Record<string, unknown>
}

/**
 * Minimal profile shape used for pre-filling form fields from
 * the authenticated user's profile. Pass from the auth library's
 * `useAuth().profile` reactive ref.
 */
export interface FormsProfile {
  name: string
  attributes: FormsProfileAttribute[]
}

export interface BoscaFormsConfig {
  /** Base URL of the Bosca API (e.g. "https://api.example.com") */
  apiUrl: string
  /** Token provider function — returns a Bearer token or null for anonymous access */
  getToken?: () => string | null | undefined
  /** Optional analytics instance for form event tracking */
  analytics?: AnalyticsInstance
  /** Provider for the current user's profile, used to pre-fill mapped fields */
  getProfile?: () => FormsProfile | null | undefined
}

/**
 * Minimal analytics interface matching @bosca/analytics-client-browser.
 * Kept minimal to avoid a hard dependency — only the methods we actually call.
 */
export interface AnalyticsInstance {
  sink: {
    logEvent(event: { type: string; element: { id: string; type: string }; error?: { message: string } }): void
  }
}

/**
 * Reactive state returned by useBoscaForms(). Provides access to the
 * API client and schema cache for use in Vue components.
 */
export interface BoscaFormsState {
  /** API URL configured during setup */
  apiUrl: string
  /** Returns the current auth token, or null */
  getToken: () => string | null | undefined
  /** Returns the current user's profile for pre-fill, or null */
  getProfile: () => FormsProfile | null | undefined
  /** Optional analytics instance */
  analytics: AnalyticsInstance | null
  /** In-memory schema cache to avoid redundant fetches */
  schemaCache: Ref<Map<string, FormSchema>>
  /** Fetch a schema by key, using cache */
  fetchSchema: (key: string) => Promise<FormSchema | null>
  /** Remove a schema from the in-memory cache so the next fetch hits the server */
  invalidateSchema: (key: string) => void
  /** All API functions with pre-configured apiUrl and token */
  api: {
    getByKey: (key: string) => Promise<FormSchema | null>
    getById: (id: string) => Promise<FormSchema | null>
    getAll: () => Promise<FormSchema[]>
    save: (input: FormSchemaInput) => Promise<FormSchema>
    delete: (id: string) => Promise<boolean>
    submitForm: (input: FormSubmissionInput) => Promise<SubmittedForm>
  }
}

// Re-export input types for convenience
export type { FormSchemaInput, FormSubmissionInput, SubmittedForm } from './types'

/**
 * Minimal Nuxt app interface accepted by `setupBoscaForms` so the
 * library can call `vueApp.provide` without depending on `nuxt/app`.
 */
interface NuxtAppLike {
  vueApp: {
    provide: <T>(key: InjectionKey<T>, value: T) => void
    runWithContext: <T>(fn: () => T) => T
  }
}

/** Vue injection key used to store and retrieve forms state per app instance. */
export const BoscaFormsKey: InjectionKey<BoscaFormsState> = Symbol('bosca-forms')

/**
 * Initializes the Bosca Forms client for use in a Nuxt plugin.
 * Call once from a defineNuxtPlugin, then use useBoscaForms() in components.
 *
 * The `nuxtApp` parameter is used to register the forms state via Vue's
 * provide/inject, keeping it scoped to the app instance rather than
 * module-level — which is critical for SSR where multiple requests
 * share a single Node.js process.
 *
 * @example
 * ```ts
 * // plugins/02.forms.client.ts
 * import { setupBoscaForms } from '@bosca/forms/nuxt'
 *
 * export default defineNuxtPlugin((nuxtApp) => {
 *   const config = useRuntimeConfig()
 *   const { $auth } = useNuxtApp()
 *
 *   const forms = setupBoscaForms(nuxtApp, {
 *     apiUrl: config.public.apiUrl as string || '',
 *     getToken: () => $auth.getStoredToken(),
 *   })
 *
 *   return { provide: { forms } }
 * })
 * ```
 *
 * @param nuxtApp - The Nuxt app instance from `defineNuxtPlugin`
 * @param config - Forms configuration including API URL and token provider
 * @returns The initialized forms state
 */
export function setupBoscaForms(nuxtApp: NuxtAppLike, config: BoscaFormsConfig): BoscaFormsState {
  const existing = nuxtApp.vueApp.runWithContext(() => inject(BoscaFormsKey, null))
  if (existing) {
    throw new Error('setupBoscaForms has already been called for this app instance.')
  }

  const getToken = config.getToken ?? (() => null)
  const getProfile = config.getProfile ?? (() => null)
  const schemaCache = ref(new Map<string, FormSchema>()) as Ref<Map<string, FormSchema>>

  const invalidateSchema = (key: string): void => {
    schemaCache.value.delete(key)
  }

  const fetchSchema = async (key: string): Promise<FormSchema | null> => {
    const cached = schemaCache.value.get(key)
    if (cached) return cached

    const schema = await api.getFormSchemaByKey(config.apiUrl, key, getToken() ?? undefined)
    if (schema) {
      schemaCache.value.set(key, schema)
    }
    return schema
  }

  const state: BoscaFormsState = {
    apiUrl: config.apiUrl,
    getToken,
    getProfile,
    analytics: config.analytics ?? null,
    schemaCache,
    fetchSchema,
    invalidateSchema,
    api: {
      getByKey: (key) => api.getFormSchemaByKey(config.apiUrl, key, getToken() ?? undefined),
      getById: (id) => api.getFormSchemaById(config.apiUrl, id, getToken() ?? undefined),
      getAll: () => api.getAllFormSchemas(config.apiUrl, getToken() ?? undefined),
      save: async (input) => {
        const result = await api.saveFormSchema(config.apiUrl, input, getToken() ?? undefined)
        invalidateSchema(input.key)
        return result
      },
      delete: (id) => api.deleteFormSchema(config.apiUrl, id, getToken() ?? undefined),
      submitForm: (input) => api.submitForm(config.apiUrl, input, getToken() ?? undefined),
    },
  }

  nuxtApp.vueApp.provide(BoscaFormsKey, state)
  return state
}

/**
 * Composable that provides access to the Bosca Forms state within
 * Vue/Nuxt components. Uses Vue's `inject` to retrieve the state
 * registered by `setupBoscaForms`, keeping it scoped per app instance.
 *
 * @throws Error if called before `setupBoscaForms` has been initialized
 */
export function useBoscaForms(): BoscaFormsState {
  const state = inject(BoscaFormsKey, null)
  if (state) {
    return state
  }
  throw new Error(
    'Bosca Forms not initialized. Ensure setupBoscaForms() has been called in a Nuxt plugin.',
  )
}
