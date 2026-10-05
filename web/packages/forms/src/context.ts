import { inject, provide, type InjectionKey } from 'vue'

/**
 * API context provided by BoscaForm to child controls that need to
 * make HTTP requests (e.g. fetching dropdown options from scripts or
 * GraphQL endpoints). Uses Vue's provide/inject so controls are
 * decoupled from the module-level singleton in nuxt.ts, avoiding
 * dual-instance issues when Vite aliases remap package paths.
 */
export interface BoscaFormContext {
  /** Base URL of the Bosca API (e.g. "https://api.example.com") */
  apiUrl: string
  /** Returns the current auth token, or null for anonymous access */
  getToken: () => string | null | undefined
}

export const BoscaFormContextKey: InjectionKey<BoscaFormContext> = Symbol('BoscaFormContext')

/**
 * Provides the API context to all descendant form controls.
 * Called once by BoscaForm during setup.
 */
export function provideBoscaFormContext(context: BoscaFormContext): void {
  provide(BoscaFormContextKey, context)
}

/**
 * Injects the API context provided by a parent BoscaForm.
 * Returns null if no BoscaForm ancestor exists (e.g. standalone control usage).
 */
export function useBoscaFormContext(): BoscaFormContext | null {
  return inject(BoscaFormContextKey, null)
}
