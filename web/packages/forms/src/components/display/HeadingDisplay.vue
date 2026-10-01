<script setup lang="ts">
import { computed } from 'vue'
import type { DisplayNode } from '../../types'

const props = defineProps<{
  node: DisplayNode
}>()

const tag = computed(() => {
  const level = props.node.level ?? 3
  return `h${Math.min(Math.max(level, 1), 6)}`
})

const sizeClass = computed(() => {
  const level = props.node.level ?? 3
  switch (level) {
    case 1: return 'text-2xl font-bold'
    case 2: return 'text-xl font-semibold'
    case 3: return 'text-lg font-semibold'
    case 4: return 'text-base font-semibold'
    case 5: return 'text-sm font-semibold'
    case 6: return 'text-xs font-semibold'
    default: return 'text-lg font-semibold'
  }
})
</script>

<template>
  <component :is="tag" :class="sizeClass">
    {{ node.text }}
  </component>
</template>
