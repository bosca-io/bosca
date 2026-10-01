import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import dts from 'vite-plugin-dts'
import { resolve } from 'path'

export default defineConfig({
  esbuild: { supported: { destructuring: true } },
  plugins: [vue(), dts({ tsconfigPath: './tsconfig.json' })],
  build: {
    minify: false,
    lib: {
      entry: resolve(__dirname, 'src/index.ts'),
      formats: ['es'],
      fileName: () => 'index.js',
    },
    rollupOptions: {
      external: [
        'vue',
        '@bosca/ui',
        'vue-chrts',
        'date-fns',
      ],
    },
  },
})
