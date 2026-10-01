/**
 * Nuxt 3 integration for the feature flag client. Pairs with the analytics
 * plugin to share the device installation ID for consistent targeting.
 *
 * ## SSR support
 *
 * Feature flags can be evaluated during SSR when authentication credentials
 * are present. The plugin should call `client.initialize()` during the
 * request to fetch flag values, which the composables then read
 * synchronously during component setup — producing server-rendered HTML
 * that already reflects the correct flag state.
 *
 * ## Request isolation
 *
 * State is stored via Vue's `provide`/`inject` with a Symbol injection
 * key, scoped to the Vue app instance. During SSR each incoming request
 * gets its own app instance, so there is no cross-request leakage — the
 * same pattern used by the auth module.
 */

import { inject, watchEffect, type InjectionKey } from 'vue'
import { BoscaSink } from './bosca'
import { FeatureFlagClient } from './feature_flags'
import type { FeatureFlagOptions } from './feature_flags'

/** Configuration for the Nuxt feature flag plugin. */
export interface NuxtFeatureFlagOptions {
  /** GraphQL endpoint URL (e.g., "/graphql") */
  graphqlUrl: string
  /** WebSocket endpoint for subscriptions (e.g., "/graphqlws") */
  wsUrl?: string
  /** Auth token or token getter for authenticated flag evaluation */
  authToken?: string | (() => string | null)
  /** Client platform identifier for platform-based targeting rules (defaults to "WEB") */
  platform?: string
}

/** The return value from the Nuxt plugin setup. */
export interface NuxtFeatureFlagInstance {
  /** The feature flag client for flag evaluation and subscription management */
  client: FeatureFlagClient
}

/**
 * Minimal Nuxt app interface so the library can call `vueApp.provide`
 * without depending on `nuxt/app`.
 */
interface NuxtAppLike {
  vueApp: {
    provide: <T>(key: InjectionKey<T>, value: T) => void
    runWithContext: <T>(fn: () => T) => T
  }
}

/** Vue injection key for the feature flag instance, scoped per app instance. */
export const FeatureFlagKey: InjectionKey<NuxtFeatureFlagInstance> = Symbol('bosca-feature-flags')

/**
 * Sets up the feature flag client for a Nuxt 3 application and provides
 * it via Vue's injection system. Uses the installation ID from the
 * analytics BoscaSink for consistent identity across feature flags and
 * analytics events.
 *
 * @example
 * ```ts
 * // plugins/feature-flags.ts (universal — runs server and client)
 * import { setupNuxtFeatureFlags } from '@bosca/analytics-client-browser'
 *
 * export default defineNuxtPlugin(async (nuxtApp) => {
 *   const { $analytics, $auth } = nuxtApp
 *   if (!$analytics) return
 *
 *   const { client } = setupNuxtFeatureFlags(nuxtApp, $analytics.sink, {
 *     graphqlUrl: '/graphql',
 *     wsUrl: '/graphqlws',
 *     authToken: () => $auth?.token ?? null,
 *   })
 *
 *   await client.initialize()
 *
 *   if (import.meta.client) {
 *     client.startListening()
 *   }
 * })
 * ```
 */
export function setupNuxtFeatureFlags(
  nuxtApp: NuxtAppLike,
  sink: BoscaSink,
  options: NuxtFeatureFlagOptions,
): NuxtFeatureFlagInstance {
  const existing = nuxtApp.vueApp.runWithContext(() => inject(FeatureFlagKey, null))
  if (existing) {
    throw new Error('setupNuxtFeatureFlags has already been called for this app instance.')
  }

  const client = new FeatureFlagClient({
    graphqlUrl: options.graphqlUrl,
    wsUrl: options.wsUrl,
    installationId: () => sink.installationId,
    authToken: options.authToken,
    platform: options.platform ?? 'WEB',
  })

  const isClient = typeof window !== 'undefined'
    || (nuxtApp.vueApp.runWithContext(() => typeof navigator !== 'undefined'))

  // When authToken is a getter function, automatically re-fetch flags and
  // reconnect subscriptions whenever the token changes (e.g. login/logout),
  // as long as the getter depends on Vue reactive state. We only watch on
  // the client so we don't leak watchers across SSR requests.
  if (isClient && typeof options.authToken === 'function') {
    // We skip the first automatic run because the manual await client.initialize()
    // pattern handles the first pass correctly in both SSR and client hydration.
    let firstRun = true
    nuxtApp.vueApp.runWithContext(() => {
      watchEffect(() => {
        // Read the getter to track its reactive dependencies.
        const _token = (options.authToken as () => string | null)()
        if (firstRun) {
          firstRun = false
          return
        }
        // Fire-and-forget; the client's internal promises handle their own errors.
        void client.refresh()
      })
    })
  }

  const instance: NuxtFeatureFlagInstance = { client }
  nuxtApp.vueApp.provide(FeatureFlagKey, instance)

  return instance
}

/**
 * Vue composable that provides access to the feature flag client within
 * components. Uses Vue's `inject` to retrieve the instance registered
 * by [setupNuxtFeatureFlags], keeping it scoped per app instance.
 *
 * Works in both SSR and client contexts.
 */
export function useFeatureFlags(): NuxtFeatureFlagInstance {
  const instance = inject(FeatureFlagKey, null)
  if (instance) return instance
  throw new Error(
    'Feature flags not initialized. Ensure the feature-flags plugin is registered ' +
    'and has called setupNuxtFeatureFlags().',
  )
}
