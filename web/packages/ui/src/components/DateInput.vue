<script setup lang="ts">
const model = defineModel<string>({ default: '' })

withDefaults(defineProps<{
  label?: string
  type?: 'date' | 'datetime-local' | 'time'
  disabled?: boolean
  min?: string
  max?: string
}>(), {
  type: 'datetime-local',
})

const emit = defineEmits<{ blur: [] }>()
</script>

<template>
  <div class="date-input-root">
    <label v-if="label" class="date-input-label">{{ label }}</label>
    <div class="date-input-wrap" :class="{ disabled }">
      <input
        v-model="model"
        class="date-input-el"
        :type
        :disabled
        :min
        :max
        @blur="emit('blur')"
      />
    </div>
  </div>
</template>

<style scoped>
.date-input-root {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.date-input-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.date-input-wrap {
  display: flex;
  align-items: center;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  height: 32px;
  padding: 0 10px;
  transition: border-color 0.15s;
}

.date-input-wrap:focus-within {
  border-color: var(--brand-2);
}

.date-input-wrap.disabled {
  opacity: 0.5;
  pointer-events: none;
}

.date-input-el {
  flex: 1;
  min-width: 0;
  width: 100%;
  background: none;
  border: none;
  outline: none;
  font-size: 13px;
  color: var(--fg-0);
  padding: 0;
  color-scheme: dark;
}
</style>
