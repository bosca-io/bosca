<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'

const model = defineModel<string>({ default: '' })

withDefaults(defineProps<{
  placeholder?: string
  label?: string
  rows?: number
  disabled?: boolean
  mono?: boolean
  autoResize?: boolean
}>(), {
  placeholder: '',
  rows: 3,
})

const emit = defineEmits<{ blur: [] }>()

const el = ref<HTMLTextAreaElement>()

function resize() {
  if (!el.value) return
  el.value.style.height = 'auto'
  el.value.style.height = el.value.scrollHeight + 'px'
}

onMounted(() => { if (el.value) resize() })
watch(model, () => resize())
</script>

<template>
  <div class="textarea-root">
    <label v-if="label" class="textarea-label">{{ label }}</label>
    <textarea
      ref="el"
      v-model="model"
      class="textarea-el"
      :class="{ mono }"
      :placeholder
      :rows="autoResize ? 1 : rows"
      :disabled
      @blur="emit('blur')"
      @input="autoResize && resize()"
    />
  </div>
</template>

<style scoped>
.textarea-root {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.textarea-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.textarea-el {
  width: 100%;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.5;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  color: var(--fg-0);
  outline: none;
  resize: vertical;
  font-family: inherit;
  transition: border-color 0.15s;
}

.textarea-el.mono {
  font-family: var(--font-mono);
  font-feature-settings: 'tnum', 'zero';
  letter-spacing: 0;
}

.textarea-el:focus {
  border-color: var(--brand-2);
}

.textarea-el::placeholder {
  color: var(--fg-3);
}

.textarea-el:disabled {
  opacity: 0.5;
  pointer-events: none;
}
</style>
