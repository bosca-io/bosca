<script setup lang="ts">
import { computed } from 'vue'
import type { DisplayNode } from '../../types'

const props = defineProps<{
  node: DisplayNode
}>()

const VARIANT_COLORS: Record<string, string> = {
  warning: 'var(--warn)',
  error: 'var(--err)',
  success: 'var(--ok)',
  info: 'var(--info)',
}

const color = computed(() => VARIANT_COLORS[props.node.variant ?? 'info'] ?? 'var(--info)')
</script>

<template>
  <div
    class="alert"
    :style="{
      borderColor: `color-mix(in oklch, ${color} 30%, transparent)`,
      background: `color-mix(in oklch, ${color} 10%, transparent)`,
      color: color,
    }"
  >
    {{ node.text }}
  </div>
</template>

<style scoped>
.alert {
  padding: 10px 14px;
  border: 1px solid;
  border-radius: var(--r-sm);
  font-size: 13px;
  line-height: 1.4;
}
</style>
