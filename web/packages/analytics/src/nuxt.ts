import { inject, type InjectionKey } from 'vue'
import { BoscaSink } from './bosca'
import { addSink, logError } from './sink'
import { AutoInstrumentation, type AutoInstrumentOptions } from './auto'

/**
 * Minimal Nuxt app interface so the library can register hooks and
 * provide state via Vue's injection system without depending on `nuxt/app`.
 */
export interface NuxtAppLike {
  hook(name: string, fn: (...args: any[]) => void): void
  vueApp: {
    provide: <T>(key: InjectionKey<T>, value: T) => void
    runWithContext: <T>(fn: () => T) => T
  }
}

/**
 * Configuration for the Nuxt analytics plugin. Extends auto-instrumentation
 * options with connection details for the analytics collector endpoint.
 */
export interface NuxtAnalyticsOptions {
  /** Base URL of the analytics collector service (e.g. "https://analytics.example.com") */
  url: string
  /** Application identifier used to group events in the analytics backend */
  appId: string
  /** Semantic version string of the application for filtering by release */
  appVersion: string
  /** Client type identifier (e.g. "web", "mobile-web", "electron") */
  clientId: string
  /** Optional authenticated user ID; can be set later via the returned sink */
  userId?: string
  /** Log events to console and warn about missing fields. Default false. */
  debug?: boolean
  /** When true, uses an ephemeral installation ID and prevents `setUserId`. Default false. */
  anonymous?: boolean
  /** When true, omits credentials (cookies) from all analytics HTTP requests. Default false. */
  omitCredentials?: boolean
  /** Fine-grained control over which auto-instrumentation features are enabled */
  options?: Partial<AutoInstrumentOptions>
}

/**
 * The return value from the Nuxt plugin setup, giving the consuming
 * application access to the sink (for setting user ID, flushing, etc.)
 * and the instrumentation instance (for stopping/restarting tracking).
 */
export interface NuxtAnalyticsInstance {
  /** The event sink for manual event logging and user ID management */
  sink: BoscaSink
  /** The auto-instrumentation controller */
  instrumentation: AutoInstrumentation
}

/** Vue injection key for the analytics instance, scoped per app instance. */
export const AnalyticsKey: InjectionKey<NuxtAnalyticsInstance> = Symbol('bosca-analytics')

/**
 * Sets up Bosca Analytics for a Nuxt 3 application. Designed to be called
 * from a Nuxt plugin file. Creates the event sink, registers it, starts
 * auto-instrumentation, and hooks into Vue/Nuxt error handlers to capture
 * framework-intercepted errors that never reach `window.onerror`.
 *
 * State is stored via Vue's `provide`/`inject` with a Symbol injection
 * key, scoped to the Vue app instance. During SSR each incoming request
 * gets its own app instance, so there is no cross-request leakage.
 *
 * Registers the following error hooks:
 * - `vue:error` — captures Vue component render and lifecycle errors
 * - `app:error` — captures Nuxt-level fatal and non-fatal errors
 *
 * @example
 * ```ts
 * // plugins/analytics.client.ts
 * import { setupNuxtAnalytics } from '@bosca/analytics-client-browser'
 *
 * export default defineNuxtPlugin((nuxtApp) => {
 *   const { sink, instrumentation } = setupNuxtAnalytics(nuxtApp, {
 *     url: 'https://analytics.example.com',
 *     appId: 'my-nuxt-app',
 *     appVersion: '1.0.0',
 *     clientId: 'web',
 *   })
 *
 *   return {
 *     provide: {
 *       analytics: { sink, instrumentation },
 *     },
 *   }
 * })
 * ```
 */
export function setupNuxtAnalytics(nuxtApp: NuxtAppLike, config: NuxtAnalyticsOptions): NuxtAnalyticsInstance {
  const existing = nuxtApp.vueApp.runWithContext(() => inject(AnalyticsKey, null))
  if (existing) {
    throw new Error('setupNuxtAnalytics has already been called for this app instance.')
  }

  const sink = new BoscaSink({
    url: config.url,
    appId: config.appId,
    appVersion: config.appVersion,
    clientId: config.clientId,
    debug: config.debug,
    anonymous: config.anonymous,
    omitCredentials: config.omitCredentials,
  })

  if (config.userId) {
    sink.setUserId(config.userId)
  }

  addSink(sink)

  const instrumentation = new AutoInstrumentation(config.options)

  if (typeof window !== 'undefined') {
    instrumentation.start()
  }

  nuxtApp.hook('vue:error', (error: unknown) => {
    captureFrameworkError(error, 'vue_error', false)
  })
  nuxtApp.hook('app:error', (error: unknown) => {
    captureFrameworkError(error, 'nuxt_error', true)
  })

  const instance: NuxtAnalyticsInstance = { sink, instrumentation }
  nuxtApp.vueApp.provide(AnalyticsKey, instance)

  return instance
}

function captureFrameworkError(error: unknown, elementType: string, fatal: boolean): void {
  let message: string
  let type: string
  let stack: string | undefined

  if (error instanceof Error) {
    message = error.message
    type = error.constructor.name
    stack = error.stack
  } else if (typeof error === 'string') {
    message = error
    type = 'Error'
  } else {
    message = 'Unknown framework error'
    type = 'Error'
  }

  logError(
    { message, type, stack_trace: stack, fatal },
    { id: '', type: elementType },
  ).catch(() => {})
}

/**
 * Vue composable that provides access to the analytics instance within
 * Nuxt components. Uses Vue's `inject` to retrieve the instance registered
 * by `setupNuxtAnalytics`, keeping it scoped per app instance.
 *
 * @example
 * ```vue
 * <script setup>
 * import { useAnalytics } from '@bosca/analytics-client-browser'
 * import { logInteraction } from '@bosca/analytics-client-browser'
 *
 * const { sink } = useAnalytics()
 *
 * function onSpecialAction() {
 *   logInteraction({ id: 'special-button', type: 'button' })
 * }
 * </script>
 * ```
 */
export function useAnalytics(): NuxtAnalyticsInstance {
  const instance = inject(AnalyticsKey, null)
  if (instance) {
    return instance
  }
  throw new Error(
    'Analytics not initialized. Ensure the analytics plugin is registered and setupNuxtAnalytics has been called.',
  )
}
