import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import dts from 'vite-plugin-dts'
import { resolve } from 'path'

export default defineConfig({
  // Every configured ES2020 browser target supports destructuring.
  esbuild: { supported: { destructuring: true } },
  plugins: [vue(), dts({ tsconfigPath: './tsconfig.json' })],
  build: {
    minify: false,
    lib: {
      entry: {
        index: resolve(__dirname, 'src/index.ts'),
        module: resolve(__dirname, 'src/module.ts'),
      },
      formats: ['es'],
      fileName: (_format, entryName) => `${entryName}.js`,
    },
    rollupOptions: {
      external: [
        'vue',
        '@nuxt/kit',
        'tippy.js',
        'fs',
        'path',
        'node:fs',
        'node:path',
      ],
    },
  },
})
