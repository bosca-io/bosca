import { defineNuxtModule, addComponent, addImports, createResolver } from '@nuxt/kit'
import type { NuxtModule } from '@nuxt/schema'

/**
 * Nuxt module for the Bosca Forms library. Registers the public form
 * components for auto-import and exposes composables and utility
 * functions so consuming apps do not need manual imports.
 *
 * Components are referenced from source (via ../src/) so that Nuxt's
 * compiler resolves component imports at build time rather than
 * relying on runtime resolveComponent() calls from the pre-built dist.
 */
const module: NuxtModule = defineNuxtModule({
  meta: {
    name: '@bosca/forms',
    configKey: 'boscaForms',
    compatibility: { nuxt: '>=3.0.0' },
  },
  async setup(_options, nuxt) {
    const { resolve } = createResolver(import.meta.url)

    // resolve('../src/...') works from both src/module.ts and dist/module.js
    // because both are one level below the package root
    addComponent({ name: 'BoscaForm', filePath: resolve('../src/components/BoscaForm.vue') })
    addComponent({ name: 'BoscaFormRenderer', filePath: resolve('../src/components/BoscaFormRenderer.vue') })

    // Transpile the forms source so Nuxt processes .vue files through
    // its auto-import pipeline (resolving @bosca/ui components at build time)
    nuxt.options.build.transpile.push('@bosca/forms')

    // Alias package imports to source so every consumer (plugins, components,
    // composables) shares a single module instance and Nuxt processes
    // .vue files through its auto-import pipeline.
    nuxt.options.alias['@bosca/forms'] = resolve('../src/index.ts')
    nuxt.options.alias['@bosca/forms/nuxt'] = resolve('../src/nuxt.ts')

    addImports([
      { name: 'setupBoscaForms', from: resolve('../src/nuxt') },
      { name: 'useBoscaForms', from: resolve('../src/nuxt') },
      { name: 'registerControl', from: resolve('../src/controls') },
      { name: 'getControl', from: resolve('../src/controls') },
      { name: 'getRegisteredControls', from: resolve('../src/controls') },
      { name: 'validateFormData', from: resolve('../src/validation') },
      { name: 'validateField', from: resolve('../src/validation') },
    ])
  },
})

export default module
