import { defineConfig } from 'vitest/config'
import { playwright } from '@vitest/browser-playwright'

export default defineConfig({
  test: {
    clearMocks: true,
    include: ['**/*.spec.ts'],
    globals: true,
    environment: 'happy-dom',
    setupFiles: ['src/test-setup.ts'],
    browser: {
      provider: playwright(),
      instances: [{ browser: 'chromium' }],
    },
    coverage: {
      provider: 'v8',
      include: [
        'src/**/*.ts',
      ],
      exclude: [
        'src/**/*.spec.ts',
        'src/**/*.test.ts',
        'src/bosca_models.ts',
        'src/index.ts',
        'src/test-setup.ts',
      ],
      thresholds: {
        // Preserve the package's current coverage as integer regression floors while the
        // remaining legacy browser paths are brought under test incrementally.
        statements: 90,
        branches: 80,
        functions: 94,
        lines: 90,
        // The instrumentation changed in this release is held to complete coverage.
        'src/auto/attribute_extractor.ts': {
          statements: 100,
          branches: 100,
          functions: 100,
          lines: 100,
        },
        'src/auto/element_identifier.ts': {
          statements: 100,
          branches: 100,
          functions: 100,
          lines: 100,
        },
      },
    },
  },
})
