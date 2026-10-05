<script setup lang="ts">
withDefaults(defineProps<{
  label?: string
  type?: string
  modelValue?: string
  placeholder?: string
  autoFocus?: boolean
  error?: string | null
  hint?: string
  mono?: boolean
  accent?: string
}>(), {
  label: undefined,
  type: 'text',
  modelValue: '',
  placeholder: undefined,
  error: undefined,
  hint: undefined,
  accent: '#7c5cff',
})

const emit = defineEmits<{
  'update:modelValue': [val: string]
}>()
</script>

<template>
  <label class="auth-input-wrap">
    <span v-if="label" class="auth-input-label" :class="{ 'has-error': error }">{{ label }}</span>
    <div
      class="auth-input-box"
      :class="{ focused: autoFocus, errored: !!error }"
      :style="{
        borderColor: error
          ? 'color-mix(in oklch, #ff5470 60%, transparent)'
          : autoFocus
            ? `color-mix(in oklch, ${accent} 50%, transparent)`
            : undefined,
        boxShadow: autoFocus
          ? `0 0 0 3px color-mix(in oklch, ${accent} 15%, transparent)`
          : undefined,
      }"
    >
      <slot name="prefix" />
      <input
        :type="type"
        :value="modelValue"
        :placeholder="placeholder"
        :class="{ mono }"
        class="auth-input-el"
        @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      >
      <slot name="suffix" />
    </div>
    <span v-if="error || hint" class="auth-input-hint" :class="{ 'has-error': error }">
      <Icon
        v-if="error"
        name="alert"
        :size="12"
        color="#ff5470" />
      {{ error || hint }}
    </span>
  </label>
</template>

<style scoped>
.auth-input-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.auth-input-label {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--fg-2);
}
.auth-input-label.has-error { color: #ff5470; }

.auth-input-box {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 12px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  min-width: 0;
}

.auth-input-el {
  flex: 1;
  min-width: 0;
  background: transparent;
  border: none;
  outline: none;
  color: var(--fg-0);
  font-size: 13.5px;
}
.auth-input-el.mono { font-family: var(--font-mono); }

.auth-input-hint {
  font-size: 11.5px;
  color: var(--fg-3);
  display: flex;
  align-items: center;
  gap: 4px;
}
.auth-input-hint.has-error { color: #ff5470; }
</style>
