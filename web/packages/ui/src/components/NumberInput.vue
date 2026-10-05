<script setup lang="ts">
const model = defineModel<number | null>({ default: null })

withDefaults(defineProps<{
  placeholder?: string
  label?: string
  min?: number
  max?: number
  step?: number
  disabled?: boolean
}>(), {
  placeholder: '',
  step: 1,
})

const emit = defineEmits<{ blur: [] }>()

function onInput(e: Event) {
  const val = (e.target as HTMLInputElement).value
  model.value = val === '' ? null : Number(val)
}
</script>

<template>
  <div class="number-input-root">
    <label v-if="label" class="number-input-label">{{ label }}</label>
    <div class="number-input-wrap" :class="{ disabled }">
      <input
        type="number"
        class="number-input-el mono"
        :value="model ?? ''"
        :placeholder
        :min
        :max
        :step
        :disabled
        @input="onInput"
        @blur="emit('blur')"
      />
    </div>
  </div>
</template>

<style scoped>
.number-input-root {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.number-input-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.number-input-wrap {
  display: flex;
  align-items: center;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  height: 32px;
  padding: 0 10px;
  transition: border-color 0.15s;
}

.number-input-wrap:focus-within {
  border-color: var(--brand-2);
}

.number-input-wrap.disabled {
  opacity: 0.5;
  pointer-events: none;
}

.number-input-el {
  flex: 1;
  min-width: 0;
  width: 100%;
  background: none;
  border: none;
  outline: none;
  font-size: 13px;
  color: var(--fg-0);
  padding: 0;
}

.number-input-el::placeholder {
  color: var(--fg-3);
}

.number-input-el::-webkit-inner-spin-button,
.number-input-el::-webkit-outer-spin-button {
  opacity: 0.3;
}
</style>
