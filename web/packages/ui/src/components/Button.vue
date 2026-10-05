<script setup lang="ts">
import { computed } from 'vue'
import Icon from './Icon.vue'
const props = withDefaults(defineProps<{
  primary?: boolean
  icon?: string
  size?: 'xs' | 'sm' | 'md'
  accent?: string
  disabled?: boolean
}>(), {
  primary: false,
  size: 'md',
  accent: '#5ec5ff',
  disabled: false,
})

const emit = defineEmits<{
  click: [e: MouseEvent]
}>()

function onClick(e: MouseEvent) {
  if (!props.disabled) {
    emit('click', e)
  }
}

const dynamicStyle = computed(() => ({
  padding: props.size === 'xs' ? '4px 8px' : props.size === 'sm' ? '6px 10px' : '8px 14px',
  fontSize: props.size === 'xs' ? '12px' : props.size === 'sm' ? '12.5px' : '13px',
  border: `1px solid ${props.primary ? `color-mix(in srgb, ${props.accent} 60%, transparent)` : 'var(--line-2)'}`,
  fontWeight: props.primary ? 550 : 500,
}))
</script>

<template>
  <button class="btn" :class="{ 'btn--disabled': disabled }" :disabled="disabled" :style="dynamicStyle" @click="onClick">
    <Icon v-if="icon" :name="icon" :size="size === 'xs' ? 12 : 14" :color="primary ? accent : 'var(--fg-2)'" />
    <slot />
  </button>
</template>

<style scoped>
.btn {
  border-radius: var(--r-sm);
  background: var(--bg-2);
  color: var(--fg-1);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  line-height: 1;
}

.btn:hover:not(.btn--disabled) {
  background: var(--bg-3);
}

.btn--disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
</style>
