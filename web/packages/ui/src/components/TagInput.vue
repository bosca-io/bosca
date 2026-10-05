<script setup lang="ts">
import { ref } from 'vue'
import Icon from './Icon.vue'

const model = defineModel<string[]>({ default: () => [] })

withDefaults(defineProps<{
  placeholder?: string
  label?: string
  disabled?: boolean
}>(), {
  placeholder: 'Add tag…',
})

const input = ref('')

function add() {
  const val = input.value.trim()
  if (val && !model.value.includes(val)) {
    model.value = [...model.value, val]
  }
  input.value = ''
}

function remove(tag: string) {
  model.value = model.value.filter(t => t !== tag)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault()
    add()
  } else if (e.key === 'Backspace' && input.value === '' && model.value.length) {
    model.value = model.value.slice(0, -1)
  }
}
</script>

<template>
  <div class="tag-input-root">
    <label v-if="label" class="tag-input-label">{{ label }}</label>
    <div class="tag-input-wrap" :class="{ disabled }">
      <span v-for="tag in model" :key="tag" class="tag-chip">
        {{ tag }}
        <button type="button" class="tag-remove" @click="remove(tag)">
          <Icon name="x" :size="10" color="var(--fg-3)" />
        </button>
      </span>
      <input
        v-model="input"
        class="tag-input-el"
        :placeholder="model.length === 0 ? placeholder : ''"
        :disabled
        @keydown="onKeydown"
        @blur="add"
      />
    </div>
  </div>
</template>

<style scoped>
.tag-input-root {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tag-input-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.tag-input-wrap {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  min-height: 32px;
  padding: 3px 8px;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  transition: border-color 0.15s;
}

.tag-input-wrap:focus-within {
  border-color: var(--brand-2);
}

.tag-input-wrap.disabled {
  opacity: 0.5;
  pointer-events: none;
}

.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--bg-3);
  font-size: 12px;
  color: var(--fg-1);
  font-weight: 500;
}

.tag-remove {
  display: flex;
  align-items: center;
  opacity: 0.6;
  transition: opacity 0.15s;
}

.tag-remove:hover {
  opacity: 1;
}

.tag-input-el {
  flex: 1;
  min-width: 60px;
  background: none;
  border: none;
  outline: none;
  font-size: 13px;
  color: var(--fg-0);
  padding: 2px 0;
}

.tag-input-el::placeholder {
  color: var(--fg-3);
}
</style>
