<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{
  values: number[]
  accent?: string
  height?: number
  width?: number
}>(), {
  accent: 'var(--brand-accent)',
  height: 28,
  width: 80,
})

const points = computed(() => {
  const max = Math.max(...props.values)
  const min = Math.min(...props.values)
  const range = max - min || 1
  // Inset the line 1px from the top and bottom edges so the stroke isn't half-clipped by the
  // viewBox at the extremes — a flat series sits ON the bottom edge, a peak ON the top.
  const pad = 1
  const drawable = props.height - pad * 2
  return props.values
    .map((v, i) => `${(i / (props.values.length - 1)) * props.width},${pad + drawable - ((v - min) / range) * drawable}`)
    .join(' ')
})

const fillPoints = computed(() => `0,${props.height} ${points.value} ${props.width},${props.height}`)
</script>

<template>
  <svg :width="props.width" :height="props.height" :viewBox="`0 0 ${props.width} ${props.height}`" preserveAspectRatio="none">
    <polyline :points="points" fill="none" :stroke="props.accent" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
    <polyline :points="fillPoints" :fill="`color-mix(in oklch, ${props.accent} 14%, transparent)`" stroke="none" />
  </svg>
</template>
