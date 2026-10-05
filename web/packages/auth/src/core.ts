// The framework-free surface of the auth library — everything except the Nuxt/Vue integration.
// Non-Vue consumers (BML site client scripts bundled by esbuild without vue installed) import
// the `./core` subpath; nothing in this graph may import 'vue'.
export * from './types'
export * from './client'
export * from './errors'
export { type TokenStorage, CookieStorage, LocalStorageStorage, MemoryStorage, createStorage } from './storage'
// oauth.ts is intentionally not exported — BoscaAuth wraps all of it
// (signInWithRedirect / handleRedirectResult / getLinkFromUrl).
