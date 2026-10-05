/**
 * Type augmentation for json-editor-vue to allow string literal mode values
 * in Vue templates (e.g. mode="text") without requiring an explicit Mode enum import.
 *
 * The vanilla-jsoneditor Mode enum has values "text", "tree", and "table" —
 * this widens the prop type so Vue's template type checking accepts those literals.
 */
declare module 'json-editor-vue' {
  import type { DefineComponent, Plugin } from 'vue'

  type SFCWithInstall<T> = T & Plugin

  const JsonEditorVue: DefineComponent<{
    modelValue?: unknown
    value?: unknown
    mode?: 'text' | 'tree' | 'table'
    debounce?: number
    stringified?: boolean
    mainMenuBar?: boolean
    navigationBar?: boolean
    statusBar?: boolean
    askToFormat?: boolean
    readOnly?: boolean
    escapeControlCharacters?: boolean
    escapeUnicodeCharacters?: boolean
    flattenColumns?: boolean
  }>

  export default JsonEditorVue as SFCWithInstall<typeof JsonEditorVue>
}
