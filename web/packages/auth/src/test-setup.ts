/**
 * Vitest setup file that works around Node.js 22+ shipping a built-in
 * `localStorage` global that is incomplete (e.g. missing `clear()`).
 * The built-in shadows happy-dom's full implementation, breaking any
 * test that calls `localStorage.clear()`.
 *
 * This replaces the global with a simple in-memory implementation that
 * satisfies the Storage interface used by the auth library.
 */

function createMemoryStorage(): Storage {
  let store: Record<string, string> = {}
  return {
    get length() {
      return Object.keys(store).length
    },
    clear() {
      store = {}
    },
    getItem(key: string) {
      return store[key] ?? null
    },
    key(index: number) {
      return Object.keys(store)[index] ?? null
    },
    removeItem(key: string) {
      delete store[key]
    },
    setItem(key: string, value: string) {
      store[key] = String(value)
    },
  }
}

if (typeof globalThis.localStorage === 'undefined' || typeof globalThis.localStorage.clear !== 'function') {
  Object.defineProperty(globalThis, 'localStorage', {
    value: createMemoryStorage(),
    writable: true,
    configurable: true,
  })
}
