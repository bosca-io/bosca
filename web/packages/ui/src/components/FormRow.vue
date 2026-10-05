<script setup lang="ts">
withDefaults(defineProps<{
  gap?: number
}>(), {
  gap: 14,
})
</script>

<template>
  <div class="form-row" :style="{ gap: `${gap}px` }">
    <slot />
  </div>
</template>

<style scoped>
.form-row {
  display: grid;
  grid-template-columns: repeat(12, 1fr);
}
</style>
