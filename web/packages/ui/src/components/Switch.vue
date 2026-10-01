<script setup lang="ts">
/**
 * Toggle switch styled to match the design system.
 * Accepts v-model for boolean binding.
 */
withDefaults(defineProps<{
  modelValue: boolean
  label?: string
  accent?: string
}>(), {
  accent: '#5ec5ff',
})

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <label class="switch-row">
    <button
      type="button"
      class="switch"
      :class="{ on: modelValue }"
      :style="modelValue ? { background: accent } : {}"
      @click="emit('update:modelValue', !modelValue)"
    >
      <span class="switch-thumb" />
    </button>
    <span v-if="label" class="switch-label">{{ label }}</span>
    <slot />
  </label>
</template>

<style scoped>
.switch-row {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}

.switch {
  position: relative;
  width: 36px;
  height: 20px;
  border-radius: 10px;
  background: var(--bg-3);
  border: 1px solid var(--fg-4);
  cursor: pointer;
  padding: 0;
  transition: background 0.15s ease, border-color 0.15s ease;
  flex-shrink: 0;
}

.switch.on {
  border-color: transparent;
}

.switch-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--fg-3);
  transition: transform 0.15s ease, background 0.15s ease;
}

.switch.on .switch-thumb {
  transform: translateX(16px);
  background: #fff;
}

.switch-label {
  font-size: 13px;
  color: var(--fg-1);
}
</style>
