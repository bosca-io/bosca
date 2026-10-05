<script setup lang="ts">
import { computed } from 'vue'

const model = defineModel<string>({ default: '#3b82f6' })

withDefaults(defineProps<{
  label?: string
  presets?: string[]
  disabled?: boolean
}>(), {
  presets: () => [
    '#ef4444', '#f97316', '#eab308', '#22c55e', '#14b8a6',
    '#3b82f6', '#6366f1', '#8b5cf6', '#ec4899', '#6b7280',
  ],
})

const previewStyle = computed(() => ({
  background: model.value,
  border: `1px solid color-mix(in oklch, ${model.value} 50%, transparent)`,
}))
</script>

<template>
  <div class="color-picker-root" :class="{ disabled }">
    <label v-if="label" class="color-picker-label">{{ label }}</label>
    <div class="color-picker-row">
      <span class="color-preview" :style="previewStyle" />
      <input
        v-model="model"
        type="text"
        class="color-input mono"
        maxlength="9"
        :disabled
      />
    </div>
    <div v-if="presets.length" class="color-presets">
      <button
        v-for="c in presets"
        :key="c"
        type="button"
        class="color-swatch"
        :class="{ active: model === c }"
        :style="{ background: c }"
        :disabled
        @click="model = c"
      />
    </div>
  </div>
</template>

<style scoped>
.color-picker-root {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.color-picker-root.disabled {
  opacity: 0.5;
  pointer-events: none;
}

.color-picker-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.color-picker-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.color-preview {
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  flex-shrink: 0;
}

.color-input {
  width: 90px;
  height: 32px;
  padding: 0 10px;
  font-size: 12.5px;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  color: var(--fg-0);
  outline: none;
  transition: border-color 0.15s;
}

.color-input:focus {
  border-color: var(--brand-2);
}

.color-presets {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.color-swatch {
  width: 22px;
  height: 22px;
  border-radius: 4px;
  border: 2px solid transparent;
  cursor: pointer;
  transition: border-color 0.15s, transform 0.1s;
}

.color-swatch:hover {
  transform: scale(1.15);
}

.color-swatch.active {
  border-color: var(--fg-0);
}
</style>
