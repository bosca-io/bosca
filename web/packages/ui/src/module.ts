import { defineNuxtModule, addComponent, addImports, createResolver } from '@nuxt/kit'
import type { NuxtModule } from '@nuxt/schema'
import { readdirSync } from 'fs'

const module: NuxtModule = defineNuxtModule({
  meta: {
    name: '@bosca/ui',
    configKey: 'boscaUi',
  },
  setup(_options, nuxt) {
    const { resolve } = createResolver(import.meta.url)

    const componentsDir = resolve('./components')
    const componentFiles = readdirSync(componentsDir).filter(f => f.endsWith('.vue'))

    for (const file of componentFiles) {
      const name = file.replace('.vue', '')
      addComponent({
        name,
        filePath: resolve(`./components/${file}`),
      })
    }

    addImports({
      name: 'useToast',
      from: resolve('./composables/useToast'),
    })

    nuxt.options.css = nuxt.options.css || []
    if (!nuxt.options.css.includes(resolve('./styles/index.css'))) {
      nuxt.options.css.unshift(resolve('./styles/index.css'))
    }
  },
})

export default module
