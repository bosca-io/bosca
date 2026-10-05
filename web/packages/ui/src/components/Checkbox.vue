<script setup lang="ts">
withDefaults(defineProps<{
  modelValue: boolean
  label?: string
  accent?: string
  disabled?: boolean
  indeterminate?: boolean
}>(), {
  accent: 'var(--brand-2)',
})

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <label class="checkbox-row" :class="{ disabled }">
    <button
      type="button"
      class="checkbox-box"
      :class="{ checked: modelValue || indeterminate }"
      :style="(modelValue || indeterminate) ? { background: accent, borderColor: accent } : {}"
      :disabled
      @click="emit('update:modelValue', !modelValue)"
    >
      <svg v-if="indeterminate && !modelValue" width="12" height="12" viewBox="0 0 12 12" fill="none">
        <path d="M3 6h6" stroke="#fff" stroke-width="1.5" stroke-linecap="round" />
      </svg>
      <svg v-else-if="modelValue" width="12" height="12" viewBox="0 0 12 12" fill="none">
        <path d="M2.5 6L5 8.5L9.5 4" stroke="#fff" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </button>
    <span v-if="label" class="checkbox-label">{{ label }}</span>
    <slot />
  </label>
</template>

<style scoped>
.checkbox-row {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.checkbox-row.disabled {
  opacity: 0.5;
  pointer-events: none;
}

.checkbox-box {
  width: 18px;
  height: 18px;
  border-radius: 4px;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  cursor: pointer;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.checkbox-box:hover:not(:disabled) {
  border-color: var(--fg-3);
}

.checkbox-label {
  font-size: 13px;
  color: var(--fg-1);
}
</style>
