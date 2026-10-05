<script setup lang="ts">
/**
 * Standard text input matching the design system's field styling.
 * Aligns with Select trigger dimensions for side-by-side layouts.
 */
const model = defineModel<string>({ default: '' })

withDefaults(defineProps<{
  placeholder?: string
  label?: string
  icon?: string
  autofocus?: boolean
  disabled?: boolean
  size?: 'sm' | 'md'
  mono?: boolean
  type?: 'text' | 'password' | 'email' | 'url' | 'tel' | 'search' | 'number' | 'date' | 'time' | 'datetime-local'
  min?: number
}>(), {
  placeholder: '',
  size: 'md',
  type: 'text',
})

const emit = defineEmits<{
  blur: []
}>()
</script>

<template>
  <div class="text-input-root" :class="[`size-${size}`]">
    <label v-if="label" class="text-input-label">{{ label }}</label>
    <div class="text-input-wrap" :class="{ disabled }">
      <Icon
        v-if="icon"
        :name="icon"
        :size="14"
        color="var(--fg-3)"
        class="text-input-icon"
      />
      <input
        v-model="model"
        class="text-input-el"
        :class="{ mono }"
        :type
        :placeholder
        :autofocus
        :disabled
        :min
        @blur="emit('blur')"
      />
    </div>
  </div>
</template>

<style scoped>
.text-input-root {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.text-input-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.text-input-wrap {
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

.size-sm .text-input-wrap {
  height: 30px;
}

.text-input-wrap:focus-within {
  border-color: var(--brand-2);
}

.text-input-wrap.disabled {
  opacity: 0.5;
  pointer-events: none;
}

.text-input-icon {
  flex-shrink: 0;
}

.text-input-el {
  flex: 1;
  min-width: 0;
  background: none;
  border: none;
  outline: none;
  font: inherit;
  font-size: 13px;
  color: var(--fg-0);
  padding: 0;
}

.size-sm .text-input-el {
  font-size: 12.5px;
}

.text-input-el::placeholder {
  color: var(--fg-3);
}
</style>
