// @ts-check
import withNuxt from './.nuxt/eslint.config.mjs'

export default withNuxt({
  rules: {
    'vue/html-indent': ['error', 2, { attribute: 1, baseIndent: 1, closeBracket: 0, alignAttributesVertically: true }]
  }
}, {
  files: ['app/components/Callout.vue'],
  rules: {
    'vue/multi-word-component-names': 'off'
  }
})
