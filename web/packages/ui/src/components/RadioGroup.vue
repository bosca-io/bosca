<script setup lang="ts">
withDefaults(defineProps<{
  modelValue: string | number
  options: { value: string | number; label: string }[]
  label?: string
  accent?: string
  disabled?: boolean
  direction?: 'horizontal' | 'vertical'
}>(), {
  accent: 'var(--brand-2)',
  direction: 'vertical',
})

const emit = defineEmits<{ 'update:modelValue': [value: string | number] }>()
</script>

<template>
  <fieldset class="radio-group" :class="{ disabled }">
    <legend v-if="label" class="radio-group-label">{{ label }}</legend>
    <div class="radio-options" :class="[direction]">
      <label
        v-for="opt in options"
        :key="opt.value"
        class="radio-option"
      >
        <button
          type="button"
          class="radio-circle"
          :class="{ selected: modelValue === opt.value }"
          :style="modelValue === opt.value ? { borderColor: accent } : {}"
          :disabled
          @click="emit('update:modelValue', opt.value)"
        >
          <span
            v-if="modelValue === opt.value"
            class="radio-dot"
            :style="{ background: accent }"
          />
        </button>
        <span class="radio-text">{{ opt.label }}</span>
      </label>
    </div>
  </fieldset>
</template>

<style scoped>
.radio-group {
  border: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.radio-group.disabled {
  opacity: 0.5;
  pointer-events: none;
}

.radio-group-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.radio-options {
  display: flex;
  gap: 10px;
}

.radio-options.vertical {
  flex-direction: column;
}

.radio-option {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.radio-circle {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--bg-2);
  border: 2px solid var(--line-2);
  cursor: pointer;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: border-color 0.15s ease;
}

.radio-circle:hover:not(:disabled) {
  border-color: var(--fg-3);
}

.radio-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.radio-text {
  font-size: 13px;
  color: var(--fg-1);
}
</style>
