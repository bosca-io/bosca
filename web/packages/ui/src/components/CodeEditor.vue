<script setup lang="ts">
/**
 * Lightweight code editor wrapper. Renders a styled textarea with
 * monospace font. CodeMirror 6 integration will be added as a
 * progressive enhancement once the dependency is installed.
 */
import { ref, watch, onMounted } from 'vue'

const model = defineModel<string>({ default: '' })

withDefaults(defineProps<{
  label?: string
  language?: string
  rows?: number
  readonly?: boolean
  placeholder?: string
}>(), {
  language: 'text',
  rows: 10,
  placeholder: '',
})

const emit = defineEmits<{ blur: [] }>()

const el = ref<HTMLTextAreaElement>()

function handleTab(e: KeyboardEvent) {
  if (e.key === 'Tab') {
    e.preventDefault()
    const target = e.target as HTMLTextAreaElement
    const start = target.selectionStart
    const end = target.selectionEnd
    model.value = model.value.substring(0, start) + '  ' + model.value.substring(end)
    requestAnimationFrame(() => {
      target.selectionStart = target.selectionEnd = start + 2
    })
  }
}
</script>

<template>
  <div class="code-editor-root">
    <label v-if="label" class="code-editor-label">
      {{ label }}
      <span v-if="language !== 'text'" class="code-editor-lang">{{ language }}</span>
    </label>
    <textarea
      ref="el"
      v-model="model"
      class="code-editor-el mono"
      :rows
      :readonly
      :placeholder
      spellcheck="false"
      autocomplete="off"
      autocorrect="off"
      autocapitalize="off"
      @keydown="handleTab"
      @blur="emit('blur')"
    />
  </div>
</template>

<style scoped>
.code-editor-root {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.code-editor-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
  display: flex;
  align-items: center;
  gap: 8px;
}

.code-editor-lang {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--bg-3);
  color: var(--fg-3);
}

.code-editor-el {
  width: 100%;
  padding: 12px 14px;
  font-size: 12.5px;
  line-height: 1.6;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  color: var(--fg-0);
  outline: none;
  resize: vertical;
  tab-size: 2;
  transition: border-color 0.15s;
  white-space: pre;
  overflow-wrap: normal;
  overflow-x: auto;
}

.code-editor-el:focus {
  border-color: var(--brand-2);
}

.code-editor-el::placeholder {
  color: var(--fg-3);
}

.code-editor-el[readonly] {
  cursor: default;
  opacity: 0.8;
}
</style>
