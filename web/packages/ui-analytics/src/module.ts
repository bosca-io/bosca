import { defineNuxtModule, addComponent, createResolver } from '@nuxt/kit'
import type { NuxtModule } from '@nuxt/schema'
import { readdirSync } from 'fs'

const module: NuxtModule = defineNuxtModule({
  meta: {
    name: '@bosca/ui-analytics',
    configKey: 'boscaUiAnalytics',
  },
  setup(_options, _nuxt) {
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

  },
})

export default module
