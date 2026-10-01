import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.spec.ts'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,vue}'],
      exclude: ['src/**/*.spec.ts', 'src/test-helpers.ts', 'src/module.ts', 'src/index.ts'],
      thresholds: {
        statements: 87,
        branches: 79,
        functions: 84,
        lines: 90,
      },
    },
  },
})
