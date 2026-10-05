<script setup lang="ts">
import type { DisplayNode } from '../../types'

defineProps<{
  node: DisplayNode
}>()
</script>

<template>
  <p class="help-text">{{ node.text }}</p>
</template>

<style scoped>
.help-text {
  margin: 0;
  font-size: 12px;
  color: var(--fg-3);
  line-height: 1.4;
}
</style>
