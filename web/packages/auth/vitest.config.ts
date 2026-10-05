import { defineConfig } from 'vitest/config'
import { playwright } from '@vitest/browser-playwright'

export default defineConfig({
  test: {
    environment: 'happy-dom',
    setupFiles: ['src/test-setup.ts'],
    include: ['src/**/*.spec.ts'],
    browser: {
      provider: playwright(),
      // SSR and redirect tests replace globals that native browsers cannot redefine.
      // The CI DOM pass runs every test, including these environment simulations.
      instances: [{ browser: 'chromium', exclude: ['src/ssr.spec.ts', 'src/oauth.spec.ts'] }],
    },
    coverage: {
      provider: 'v8',
      include: ['src/**/*.ts'],
      exclude: ['src/**/*.spec.ts', 'src/types.ts'],
      thresholds: {
        statements: 100,
        branches: 100,
        functions: 100,
        lines: 100,
      },
    },
  },
})
