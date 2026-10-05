// The framework-free surface of the analytics library — everything except the Nuxt/Vue
// integrations. Non-Vue consumers (BML site client scripts bundled by esbuild without vue
// installed) import the `./core` subpath; nothing in this graph may import 'vue'.
export * from './event'
export * from './factory'
export * from './sink'
export * from './bosca'
export * from './auto'
export { FeatureFlagClient } from './feature_flags'
export type { FlagEvaluation, FeatureFlagOptions } from './feature_flags'
// nuxt.ts, nuxt_feature_flags.ts, and vue_feature_flags.ts are intentionally not exported —
// they import 'vue'. Vue/Nuxt apps use the root export, which layers them on top of this.
