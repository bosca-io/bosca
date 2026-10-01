<script setup lang="ts">
withDefaults(defineProps<{
  tabs: string[]
  modelValue: string
  accent?: string
}>(), {
  accent: 'var(--brand-2)',
})

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template>
  <div class="tabs-bar">
    <button
      v-for="t in tabs"
      :key="t"
      type="button"
      class="tab"
      :class="{ active: t === modelValue }"
      :style="t === modelValue ? { borderBottomColor: accent, color: 'var(--fg-0)', fontWeight: 500 } : {}"
      @click="emit('update:modelValue', t)"
    >
      {{ t }}
    </button>
  </div>
</template>

<style scoped>
.tabs-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 55%, transparent);
  overflow-x: auto;
  scrollbar-width: none;
}

.tabs-bar::-webkit-scrollbar {
  display: none;
}

.tab {
  padding: 10px 12px;
  font-size: 13px;
  color: var(--fg-3);
  font-weight: 400;
  margin-bottom: -1px;
  border-bottom: 2px solid transparent;
  transition: color 0.15s, border-color 0.15s;
  white-space: nowrap;
  flex-shrink: 0;
}

.tab:hover {
  color: var(--fg-1);
}
</style>
