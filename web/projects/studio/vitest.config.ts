import { defineConfig } from 'vitest/config'
import { resolve } from 'path'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '~': resolve(__dirname, 'app'),
      // vanilla-jsoneditor is a transitive dep (via json-editor-vue): Nuxt
      // resolves its theme CSS through ssr.noExternal, but vitest's strict
      // node resolution cannot, so pages importing it fail at transform time.
      'vanilla-jsoneditor/themes/jse-theme-dark.css': resolve(__dirname, 'vitest.empty-css-stub.css'),
    },
  },
  esbuild: {
    tsconfigRaw: {
      compilerOptions: {
        target: 'esnext',
        module: 'esnext',
        moduleResolution: 'bundler',
        jsx: 'preserve',
        strict: true,
        verbatimModuleSyntax: true,
      },
    },
  },
  test: {
    environment: 'happy-dom',
    include: ['app/**/*.test.ts'],
    setupFiles: ['./vitest.setup.ts'],
    css: false,
  },
})
