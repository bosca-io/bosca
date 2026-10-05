import gql from 'graphql-tag'

/**
 * Server-level feature flags mirroring the backend `ServerFeatures` GraphQL type
 * (`server { features { … } }`). These reflect which optional modules a
 * deployment has enabled — independent of the current user's permissions. A
 * disabled module's GraphQL surface and seeded admin groups are absent, so the
 * UI must hide the corresponding subsystem and block its routes (see
 * `usePersonas().visibleSubsystems` and the per-subsystem route middleware).
 */
export interface ServerFeatureFlags {
  chat: boolean
  community: boolean
  introspection: boolean
  analyticsProcessor: boolean
  workops: boolean
  ecommerce: boolean
  comments: boolean
}

const serverFeaturesGql = gql`
  query ServerFeatures {
    server {
      features {
        chat
        community
        introspection
        analyticsProcessor
        workops
        ecommerce
        comments
      }
    }
  }
`

/**
 * Defaults are all-off. They are only the pre-load placeholder: gating treats an
 * unloaded state as "don't hide" (see `usePersonas`), so these never spuriously
 * remove a subsystem before the real flags arrive.
 */
const DEFAULT_FEATURES: ServerFeatureFlags = {
  chat: false,
  community: false,
  introspection: false,
  analyticsProcessor: false,
  workops: false,
  ecommerce: false,
  comments: false,
}

/**
 * In-flight load promise — module-level and CLIENT-ONLY. Deliberately NOT a
 * `useState`: Nuxt serializes every `useState` into the SSR payload and devalue
 * cannot stringify a Promise. On the server load() is awaited once per request
 * and guarded by `loaded` (no concurrent loads), so no in-flight holder is
 * needed there; on the client this collapses the eager loads fired by multiple
 * components during hydration into a single request. Only the resolved data
 * (`features`) and the `loaded` flag live in serializable `useState`.
 */
let clientLoad: Promise<void> | null = null

/**
 * Resolves the deployment's feature flags once and caches them in shared state.
 * Idempotent and SSR-aware, mirroring {@link usePersonas}: route middleware
 * `await load()` so the flags land in the SSR payload and the client reuses them
 * without re-fetching (avoiding a flash of a since-disabled subsystem).
 */
export function useServerFeatures() {
  const features = useState<ServerFeatureFlags>('server:features', () => ({ ...DEFAULT_FEATURES }))
  const loaded = useState<boolean>('server:features:loaded', () => false)

  // Capture the Nuxt app + GraphQL client now, while the composable is being
  // invoked in a guaranteed-valid context (component setup / middleware sync
  // top). They must NOT be resolved inside load(): when several loads run in a
  // `Promise.all`, an earlier load's `runWithContext()` restores (clears) the
  // Nuxt context before the next load's synchronous prefix runs, so a
  // `useNuxtApp()` there throws "called outside … setup".
  const nuxtApp = useNuxtApp()
  const { query } = useGraphQL()

  function load(): Promise<void> {
    if (loaded.value) return Promise.resolve()
    if (import.meta.client && clientLoad) return clientLoad
    const promise = (async () => {
      try {
        const result = await nuxtApp.runWithContext(() => query<{
          server: { features: ServerFeatureFlags }
        }>(serverFeaturesGql))
        if (result?.server?.features) {
          features.value = result.server.features
          // Only a successful lookup is authoritative — a failed one must not be
          // cached as "all off", which would wrongly hide enabled subsystems.
          loaded.value = true
        }
      } catch (err) {
        console.error('Failed to load server features:', err)
      } finally {
        if (import.meta.client) clientLoad = null
      }
    })()
    if (import.meta.client) clientLoad = promise
    return promise
  }

  // Eagerly resolve on the client for components that read flags reactively
  // (the nav). On the server, callers gate routing by awaiting load() so the
  // result is in the SSR payload.
  if (import.meta.client && !loaded.value && !clientLoad) {
    void load()
  }

  /**
   * True when [feature] is enabled, OR the flags have not loaded yet. The
   * not-loaded fallback biases toward showing a subsystem until we know it is
   * disabled, so an enabled subsystem never flashes out; SSR `await load()`
   * makes the first paint authoritative for the disabled case.
   */
  function isEnabled(feature: keyof ServerFeatureFlags): boolean {
    if (!loaded.value) return true
    return features.value[feature]
  }

  const ecommerceEnabled = computed(() => isEnabled('ecommerce'))
  const commentsEnabled = computed(() => isEnabled('comments'))

  return { features, loaded, load, isEnabled, ecommerceEnabled, commentsEnabled }
}
