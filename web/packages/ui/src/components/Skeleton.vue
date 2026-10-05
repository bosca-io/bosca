<script setup lang="ts">
withDefaults(defineProps<{
  width?: string
  height?: string
  radius?: string
  lines?: number
}>(), {
  width: '100%',
  height: '16px',
  radius: 'var(--r-sm)',
  lines: 1,
})
</script>

<template>
  <div class="skeleton-group">
    <div
      v-for="i in lines"
      :key="i"
      class="skeleton"
      :style="{
        width: i === lines && lines > 1 ? '60%' : width,
        height,
        borderRadius: radius,
      }"
    />
  </div>
</template>

<style scoped>
.skeleton-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.skeleton {
  background: linear-gradient(
    90deg,
    var(--bg-3) 25%,
    color-mix(in oklch, var(--bg-3) 60%, var(--bg-4)) 50%,
    var(--bg-3) 75%
  );
  background-size: 200% 100%;
  animation: shimmer-anim 1.5s ease-in-out infinite;
}
</style>
