<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import Icon from './Icon.vue'
/**
 * Positioned dropdown anchored to its parent via a wrapper element.
 * Wraps a trigger slot and renders a flat action list with optional icons,
 * disabled state, danger styling, and separator dividers.
 * Closes on item click, outside click, or Escape.
 */

export interface OverflowMenuItem {
  id: string
  label: string
  icon?: string
  disabled?: boolean
  danger?: boolean
  separator?: boolean
}

withDefaults(defineProps<{
  items: OverflowMenuItem[]
  anchor?: 'left' | 'right'
}>(), {
  anchor: 'right',
})

const emit = defineEmits<{
  select: [id: string]
}>()

const open = defineModel<boolean>('open', { default: false })
const wrapperRef = ref<HTMLElement>()

function onSelect(item: OverflowMenuItem) {
  if (item.disabled || item.separator) return
  emit('select', item.id)
  open.value = false
}

function toggle() {
  open.value = !open.value
}

function onClickOutside(e: MouseEvent) {
  if (wrapperRef.value && !wrapperRef.value.contains(e.target as Node)) {
    open.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') open.value = false
}

watch(open, (v) => {
  if (v) {
    document.addEventListener('click', onClickOutside, true)
    document.addEventListener('keydown', onKeydown)
  } else {
    document.removeEventListener('click', onClickOutside, true)
    document.removeEventListener('keydown', onKeydown)
  }
})

onUnmounted(() => {
  document.removeEventListener('click', onClickOutside, true)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div ref="wrapperRef" class="overflow-wrapper">
    <slot :toggle="toggle" :open="open" />
    <Transition name="overflow">
      <div
        v-if="open"
        class="overflow-menu"
        :class="[`anchor-${anchor}`]"
      >
        <template v-for="item in items" :key="item.id">
          <div v-if="item.separator" class="overflow-sep" />
          <button
            v-else
            class="overflow-item"
            :class="{ disabled: item.disabled, danger: item.danger }"
            :disabled="item.disabled"
            @click.stop="onSelect(item)"
          >
            <Icon v-if="item.icon" :name="item.icon" :size="14" :color="item.danger ? 'var(--err)' : 'var(--fg-3)'" />
            <span>{{ item.label }}</span>
          </button>
        </template>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.overflow-wrapper {
  position: relative;
  display: inline-flex;
}

.overflow-menu {
  position: absolute;
  top: calc(100% + 6px);
  min-width: 200px;
  max-width: 280px;
  background: color-mix(in oklch, var(--bg-1) 82%, transparent);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  box-shadow: 0 12px 40px -10px rgba(0, 0, 0, 0.45);
  padding: 4px;
  display: flex;
  flex-direction: column;
  z-index: 900;
}

.anchor-right {
  right: 0;
}

.anchor-left {
  left: 0;
}

.overflow-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 4px;
  font-size: 12.5px;
  color: var(--fg-1);
  cursor: pointer;
  background: none;
  border: none;
  text-align: left;
  width: 100%;
}

.overflow-item:hover:not(.disabled) {
  background: var(--bg-3);
}

.overflow-item.disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.overflow-item.danger {
  color: var(--err);
}

.overflow-item.danger:hover:not(.disabled) {
  background: color-mix(in oklch, var(--err) 10%, transparent);
}

.overflow-sep {
  height: 1px;
  background: var(--line);
  margin: 4px 6px;
}

.overflow-enter-active,
.overflow-leave-active {
  transition: opacity 0.12s ease, transform 0.12s ease;
}

.overflow-enter-from,
.overflow-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
