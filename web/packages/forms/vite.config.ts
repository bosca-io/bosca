import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import dts from 'vite-plugin-dts'
import { resolve } from 'path'

export default defineConfig({
  esbuild: { supported: { destructuring: true } },
  plugins: [vue(), dts({ tsconfigPath: './tsconfig.json' })],
  build: {
    // Disable minification so that class/function names in shared chunks
    // don't get mangled to short identifiers (like `h`) that collide with
    // Vue's auto-imported `h` render function in consuming Nuxt apps.
    minify: false,
    lib: {
      entry: {
        index: resolve(__dirname, 'src/index.ts'),
        nuxt: resolve(__dirname, 'src/nuxt.ts'),
        module: resolve(__dirname, 'src/module.ts'),
      },
      formats: ['es'],
      fileName: (format, entryName) => `${entryName}.js`,
    },
    rollupOptions: {
      external: [
        'vue',
        '@nuxt/kit',
        '@bosca/ui',
        '@bosca/auth-client-browser',
        '@bosca/analytics-client-browser',
        'ajv',
        'ajv-formats',
      ],
    },
  },
})
