<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { EditorState } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { markdown } from '@codemirror/lang-markdown'
import { basicSetup } from 'codemirror'

const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const host = ref<HTMLElement>()
let editor: EditorView | undefined
onMounted(() => {
  editor = new EditorView({ parent: host.value, state: EditorState.create({ doc: props.modelValue,
    extensions: [basicSetup, markdown(), EditorView.lineWrapping, EditorView.updateListener.of(update => {
      if (update.docChanged) emit('update:modelValue', update.state.doc.toString())
    })],
  }) })
})
watch(() => props.modelValue, value => {
  if (editor && editor.state.doc.toString() !== value) editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } })
})
onBeforeUnmount(() => editor?.destroy())
</script>

<template><div ref="host" class="workops-guide-editor" aria-label="Guide body" /></template>

<style scoped>
.workops-guide-editor { border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.workops-guide-editor :deep(.cm-editor) { min-height: 320px; color: var(--fg-1); background: var(--bg-2); }
.workops-guide-editor :deep(.cm-gutters) { background: var(--bg-3); color: var(--fg-3); border-color: var(--border); }
.workops-guide-editor :deep(.cm-content) { padding: 16px 0; font-family: var(--font-mono, monospace); }
.workops-guide-editor :deep(.cm-activeLine), .workops-guide-editor :deep(.cm-activeLineGutter) { background: var(--bg-3); }
</style>
