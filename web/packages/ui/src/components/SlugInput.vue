<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import Icon from './Icon.vue'
/**
 * Slug input that auto-generates from a source string (typically a title).
 * Supports async availability validation via an onValidate callback.
 * Once the user edits the slug manually, auto-generation stops until reset.
 */
const model = defineModel<string>({ default: '' })

const props = withDefaults(defineProps<{
  source?: string
  label?: string
  disabled?: boolean
  size?: 'sm' | 'md'
  onValidate?: (slug: string) => Promise<boolean>
  debounce?: number
}>(), {
  source: '',
  size: 'md',
  debounce: 350,
})

const manuallyEdited = ref(false)
const validating = ref(false)
const available = ref<boolean | null>(null)
let debounceTimer: ReturnType<typeof setTimeout> | null = null

function formatSlug(value: string): string {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9\s-]/g, '')
    .replace(/[\s_]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
}

function scheduleValidation(slug: string) {
  if (debounceTimer) clearTimeout(debounceTimer)
  available.value = null
  if (!slug || !props.onValidate) {
    validating.value = false
    return
  }
  validating.value = true
  debounceTimer = setTimeout(async () => {
    try {
      available.value = await props.onValidate!(slug)
    } finally {
      validating.value = false
    }
  }, props.debounce)
}

watch(() => props.source, (src) => {
  if (!manuallyEdited.value) {
    const slug = formatSlug(src)
    model.value = slug
    scheduleValidation(slug)
  }
})

function onInput(e: Event) {
  const raw = (e.target as HTMLInputElement).value
  manuallyEdited.value = true
  const slug = formatSlug(raw)
  model.value = slug
  scheduleValidation(slug)
}

function onReset() {
  manuallyEdited.value = false
  const slug = formatSlug(props.source)
  model.value = slug
  scheduleValidation(slug)
}

onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
})

defineExpose({ available })
</script>

<template>
  <div class="slug-input-root" :class="[`size-${size}`]">
    <div v-if="label" class="slug-label-row">
      <label class="slug-input-label">{{ label }}</label>
      <span v-if="validating" class="slug-status slug-checking">
        <Icon name="spinner" :size="11" color="var(--fg-4)" class="slug-spinner" />
        checking…
      </span>
      <span v-else-if="available === true && model" class="slug-status slug-available">
        <Icon name="check" :size="11" color="#34d99a" />
        available
      </span>
      <span v-else-if="available === false && model" class="slug-status slug-taken">
        <Icon name="x" :size="11" color="#ff5d6c" />
        taken
      </span>
    </div>
    <div
      class="slug-input-wrap"
      :class="{
        disabled,
        'is-available': available === true && model,
        'is-taken': available === false && model,
      }"
    >
      <Icon
        name="link"
        :size="14"
        color="var(--fg-3)"
        class="slug-input-icon"
      />
      <input
        :value="model"
        class="slug-input-el mono"
        type="text"
        placeholder="auto-generated-slug"
        :disabled
        @input="onInput"
      />
      <button
        v-if="manuallyEdited"
        class="slug-reset"
        title="Reset to auto-generated slug"
        @click="onReset"
      >
        <Icon name="reply" :size="12" color="var(--fg-3)" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.slug-input-root {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.slug-label-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.slug-input-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.slug-status {
  font-size: 11px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.slug-checking {
  color: var(--fg-4);
}

.slug-available {
  color: #34d99a;
}

.slug-taken {
  color: #ff5d6c;
}

.slug-spinner {
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.slug-input-wrap {
  display: flex;
  align-items: center;
  gap: 5px;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  color: var(--fg-1);
  transition: border-color 0.15s;
  width: 100%;
  box-sizing: border-box;
  padding: 0 10px;
  height: 32px;
}

.size-sm .slug-input-wrap {
  height: 30px;
}

.slug-input-wrap:focus-within {
  border-color: var(--brand-2);
}

.slug-input-wrap.is-available:not(:focus-within) {
  border-color: color-mix(in oklch, #34d99a 40%, transparent);
}

.slug-input-wrap.is-taken:not(:focus-within) {
  border-color: color-mix(in oklch, #ff5d6c 40%, transparent);
}

.slug-input-wrap.disabled {
  opacity: 0.5;
  pointer-events: none;
}

.slug-input-icon {
  flex-shrink: 0;
}

.slug-input-el {
  flex: 1;
  min-width: 0;
  background: none;
  border: none;
  outline: none;
  font-size: 12.5px;
  color: var(--fg-0);
  padding: 0;
}

.slug-input-el::placeholder {
  color: var(--fg-4);
}

.slug-reset {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 4px;
  transition: background 0.1s;
}

.slug-reset:hover {
  background: var(--bg-3);
}
</style>
