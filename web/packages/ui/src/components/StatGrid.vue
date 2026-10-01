<script setup lang="ts">
/**
 * Grid wrapper for StatTile components. Automatically sizes columns
 * to the number of tiles provided via the default slot.
 */
withDefaults(defineProps<{
  columns?: number
}>(), {
  columns: 4,
})
</script>

<template>
  <div class="stat-grid" :style="{ gridTemplateColumns: `repeat(${columns}, 1fr)` }">
    <slot />
  </div>
</template>

<style scoped>
.stat-grid {
  display: grid;
  gap: 12px;
  margin-bottom: 18px;
}
</style>
