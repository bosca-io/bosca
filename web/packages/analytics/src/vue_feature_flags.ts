/**
 * Vue composables for reactive feature flag access in components.
 * Automatically subscribes to flag changes and updates reactive refs.
 *
 * ## SSR behavior
 *
 * When a feature flag client is available during SSR (e.g., the server
 * plugin called `client.initialize()` with authentication credentials),
 * `useFeatureFlag` reads the cached flag value synchronously during
 * setup so the server-rendered HTML reflects the real flag state.
 *
 * On the client the same synchronous read happens during setup, and
 * `onBeforeMount` subscribes to real-time updates. Because both passes
 * read from the same initialized client, the SSR markup and client
 * hydration agree — no hydration mismatch.
 *
 * If the client is NOT available during SSR (no auth, plugin not
 * registered), the composable falls back to `defaultValue` gracefully.
 */

import { ref, computed, onBeforeMount, onUnmounted, getCurrentInstance } from 'vue'
import type { ComputedRef } from 'vue'
import type { FeatureFlagClient } from './feature_flags'
import { useFeatureFlags } from './nuxt_feature_flags'

export interface UseFeatureFlagOptions {
  /**
   * If true, connects a WebSocket to listen for real-time updates to this flag.
   * When multiple flags request realtime updates, a single shared WebSocket is used.
   * Realtime connections are closed automatically when no active components require them.
   * Defaults to false.
   */
  realtime?: boolean
}

/**
 * Vue composable that provides reactive access to a single feature flag.
 * The returned value automatically updates when the flag changes via
 * real-time subscription.
 *
 * During SSR, reads the flag synchronously from the pre-initialized client
 * when available, producing server-rendered HTML that already reflects the
 * correct flag state. Falls back to `defaultValue` when no client is
 * configured for the server.
 *
 * @example
 * ```vue
 * <script setup>
 * import { useFeatureFlag } from '@bosca/analytics-client-browser'
 *
 * const { value: showNewCheckout } = useFeatureFlag('new-checkout-flow', false)
 * </script>
 *
 * <template>
 *   <NewCheckout v-if="showNewCheckout" />
 *   <LegacyCheckout v-else />
 * </template>
 * ```
 *
 * @param key the feature flag key
 * @param defaultValue the value to return when the flag is not found or the client is unavailable
 * @param options optional configuration for the feature flag request
 * @returns an object with reactive `value` and `variationKey` computed properties
 */
export function useFeatureFlag(
  key: string,
  defaultValue: unknown = false,
  options?: UseFeatureFlagOptions
): { value: ComputedRef<unknown>; variationKey: ComputedRef<string | undefined> } {
  const valueRef = ref(defaultValue)
  const variationKeyRef = ref<string | undefined>(undefined)

  let client: FeatureFlagClient | null = null
  try {
    client = useFeatureFlags().client
  } catch {
    // No client available — return defaults
  }

  if (client) {
    const flag = client.getFlag(key)
    if (flag) {
      valueRef.value = flag.value ?? defaultValue
      variationKeyRef.value = flag.variationKey
    }
  }

  const isClient = typeof window !== 'undefined'
    || (getCurrentInstance() && typeof navigator !== 'undefined')

  if (isClient && client) {
    let unsubscribe: (() => void) | null = null

    onBeforeMount(() => {
      const flag = client!.getFlag(key)
      if (flag) {
        valueRef.value = flag.value ?? defaultValue
        variationKeyRef.value = flag.variationKey
      }

      unsubscribe = client!.onChange((flags) => {
        const updated = flags.get(key)
        valueRef.value = updated?.value ?? defaultValue
        variationKeyRef.value = updated?.variationKey
      })

      if (options?.realtime) {
        client!.addRealtimeListener()
      }
    })

    onUnmounted(() => {
      unsubscribe?.()
      if (options?.realtime) {
        client!.removeRealtimeListener()
      }
    })
  }

  return {
    value: computed(() => valueRef.value),
    variationKey: computed(() => variationKeyRef.value),
  }
}
