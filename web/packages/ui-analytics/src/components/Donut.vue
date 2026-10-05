<script setup lang="ts">
import { computed } from 'vue'

/**
 * Small inline donut chart for 0–100% values. Renders an SVG ring with the
 * percentage label centered. Cheap enough to use in dashboards and tiles.
 */
const props = withDefaults(defineProps<{
  /** Current value, clamped to 0–100. */
  pct: number
  /** Outer size in pixels (width = height). */
  size?: number
  /** Stroke width of the ring. */
  stroke?: number
  /** Ring color. Accepts any CSS color, including CSS custom properties. */
  color?: string
  /** Background track color. Defaults to --line. */
  track?: string
  /** Hide the centered percentage label. */
  hideLabel?: boolean
}>(), {
  size: 64,
  stroke: 7,
  color: 'var(--brand-2)',
  track: 'var(--line)',
  hideLabel: false,
})

const r = computed(() => (props.size - props.stroke) / 2)
const C = computed(() => 2 * Math.PI * r.value)
const off = computed(() => C.value - (Math.min(100, Math.max(0, props.pct)) / 100) * C.value)
const center = computed(() => props.size / 2)
const rounded = computed(() => Math.round(Math.min(100, Math.max(0, props.pct))))
</script>

<template>
  <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`" role="img" :aria-label="`${rounded}%`">
    <circle :cx="center" :cy="center" :r="r" :stroke="track" :stroke-width="stroke" fill="none" />
    <circle
      :cx="center"
      :cy="center"
      :r="r"
      :stroke="color"
      :stroke-width="stroke"
      fill="none"
      stroke-linecap="round"
      :stroke-dasharray="C"
      :stroke-dashoffset="off"
      :transform="`rotate(-90 ${center} ${center})`"
      style="transition: stroke-dashoffset 0.6s"
    />
    <text
      v-if="!hideLabel"
      x="50%"
      y="52%"
      text-anchor="middle"
      dominant-baseline="middle"
      :font-size="size * 0.28"
      font-weight="600"
      fill="currentColor"
      style="font-variant-numeric: tabular-nums"
    >{{ rounded }}%</text>
  </svg>
</template>
