import { inject, ref, type InjectionKey, type Ref } from 'vue'
import { BoscaAuth } from './client'
import type { AuthResponse, BoscaAuthConfig, Principal, Profile } from './types'

/**
 * Extended configuration for the Nuxt auth plugin, adding options
 * for automatic profile loading and route protection.
 */
export interface NuxtAuthOptions extends BoscaAuthConfig {
  /** Fetch the user profile on initialization when a stored token exists (default: true) */
  fetchProfileOnInit?: boolean
  /**
   * Skip session restoration and OAuth redirect handling on the server.
   * When true, the plugin still provides empty reactive auth state during
   * SSR so `useAuth()` works, but defers all network calls to the client.
   * Useful when a separate server middleware handles SSR auth checks and
   * browser-only storage (cookies via `document.cookie`) makes server-side
   * token restoration impossible anyway. (default: false)
   */
  skipInitOnServer?: boolean
  /**
   * Called for every `signedIn` event, including the one emitted while
   * `setupNuxtAuth` completes an OAuth exchange-token redirect during plugin
   * initialization. That redirect is processed before app code regains control
   * from `await setupNuxtAuth(...)`, so this callback is the only point at which
   * a first-time OAuth signup's `response.accountCreated` is observable. Use it
   * to branch a brand-new account into onboarding, analytics, or a welcome flow.
   */
  onSignedIn?: (response: AuthResponse) => void
}

/**
 * Reactive auth state returned by the `useAuth` composable. Wraps
 * the core `BoscaAuth` client with Vue-compatible reactive references
 * for use in Nuxt components and pages.
 */
export interface NuxtAuthState {
  /** Reactive reference to the authenticated user principal */
  user: Ref<Principal | null>
  /** Reactive reference to the user's primary profile */
  profile: Ref<Profile | null>
  /** Reactive flag indicating whether a user is authenticated */
  isAuthenticated: Ref<boolean>
  /** Reactive flag indicating whether auth initialization is in progress */
  isLoading: Ref<boolean>
  /** Direct access to the underlying BoscaAuth client for full API access */
  auth: BoscaAuth
}

/**
 * Minimal Nuxt app interface accepted by `setupNuxtAuth` so the
 * library can call `vueApp.provide` without depending on `nuxt/app`.
 */
interface NuxtAppLike {
  vueApp: {
    provide: <T>(key: InjectionKey<T>, value: T) => void
    runWithContext: <T>(fn: () => T) => T
  }
}

/** Vue injection key used to store and retrieve auth state per app instance. */
export const BoscaAuthKey: InjectionKey<NuxtAuthState> = Symbol('bosca-auth')

/**
 * Initializes a `BoscaAuth` instance for use in a Nuxt client plugin.
 * Handles session restoration from stored tokens, processes OAuth redirect
 * results, and sets up reactive state that the `useAuth` composable can access.
 *
 * The `nuxtApp` parameter is used to register the auth state via Vue's
 * provide/inject, keeping it scoped to the app instance rather than
 * module-level — which is critical for SSR where multiple requests
 * share a single Node.js process.
 *
 * @example
 * ```ts
 * // plugins/auth.client.ts
 * import { setupNuxtAuth } from '@bosca/auth-client-browser'
 *
 * export default defineNuxtPlugin(async (nuxtApp) => {
 *   const config = useRuntimeConfig()
 *   const auth = await setupNuxtAuth(nuxtApp, {
 *     apiUrl: config.public.apiUrl ?? '',
 *     cookieDomain: config.public.authDomain,
 *   })
 *
 *   return {
 *     provide: { auth },
 *   }
 * })
 * ```
 *
 * @param nuxtApp - The Nuxt app instance from `defineNuxtPlugin`
 * @param options - Auth configuration with optional Nuxt-specific settings
 * @returns The initialized BoscaAuth client
 */
export async function setupNuxtAuth(nuxtApp: NuxtAppLike, options: NuxtAuthOptions): Promise<BoscaAuth> {
  const existing = nuxtApp.vueApp.runWithContext(() => inject(BoscaAuthKey, null))
  if (existing) {
    throw new Error('setupNuxtAuth has already been called for this app instance.')
  }

  const isServer = typeof window === 'undefined'
  // `skipInitOnServer` promises that SSR performs no auth I/O. Use isolated memory storage for
  // that request; hydration creates a separate browser instance.
  const resolvedOptions = isServer && options.skipInitOnServer
    ? { ...options, storage: 'memory' as const }
    : options
  const auth = new BoscaAuth(resolvedOptions)
  const fetchProfile = options.fetchProfileOnInit !== false

  const state: NuxtAuthState = {
    user: ref<Principal | null>(null),
    profile: ref<Profile | null>(null),
    isAuthenticated: ref(false),
    isLoading: ref(true),
    auth,
  }

  // Sync reactive state with auth events
  auth.on('signedIn', () => {
    state.user.value = auth.currentUser
    state.profile.value = auth.currentProfile
    state.isAuthenticated.value = true
  })

  auth.on('signedOut', () => {
    state.user.value = null
    state.profile.value = null
    state.isAuthenticated.value = false
  })

  auth.on('profileUpdated', () => {
    state.profile.value = auth.currentProfile
  })

  // Forward sign-ins to the app's optional callback. Registered here — before
  // `initialize()`/`handleRedirectResult()` below — so it catches the `signedIn`
  // emitted synchronously during the OAuth exchange-token redirect, which is the
  // only chance to read `response.accountCreated` for a first-time OAuth signup.
  if (options.onSignedIn) {
    const onSignedIn = options.onSignedIn
    auth.on('signedIn', (response) => onSignedIn(response as AuthResponse))
  }

  nuxtApp.vueApp.provide(BoscaAuthKey, state)

  if (isServer && options.skipInitOnServer) {
    state.isLoading.value = false
  } else {
    try {
      await auth.initialize(fetchProfile)
      await auth.handleRedirectResult()
    } catch (err) {
      console.error('Auth initialization failed:', err)
    } finally {
      state.user.value = auth.currentUser
      state.profile.value = auth.currentProfile
      state.isAuthenticated.value = auth.isAuthenticated
      state.isLoading.value = false
    }
  }

  return auth
}

/**
 * Composable that provides access to the reactive authentication state
 * within Nuxt components. Uses Vue's `inject` to retrieve the state
 * registered by `setupNuxtAuth`, keeping it scoped per app instance.
 *
 * @example
 * ```vue
 * <script setup>
 * import { useAuth } from '@bosca/auth-client-browser'
 *
 * const { user, profile, isAuthenticated, auth } = useAuth()
 *
 * async function handleSignIn() {
 *   await auth.signInWithPassword(email.value, password.value)
 * }
 *
 * async function handleSignOut() {
 *   await auth.signOut()
 * }
 * </script>
 * ```
 *
 * @throws Error if called before `setupNuxtAuth` has been initialized
 */
export function useAuth(): NuxtAuthState {
  const state = inject(BoscaAuthKey, null)
  if (state) {
    return state
  }
  throw new Error(
    'Auth not initialized. Ensure the auth plugin is registered and setupNuxtAuth has been called.',
  )
}
