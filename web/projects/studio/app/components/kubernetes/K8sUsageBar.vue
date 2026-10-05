<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  pct: number
  threshold?: [number, number]
  height?: number
  showValue?: boolean
}>(), {
  threshold: () => [70, 90],
  height: 6,
  showValue: true,
})

const tone = computed(() => {
  if (props.pct >= props.threshold[1]) return 'err'
  if (props.pct >= props.threshold[0]) return 'warn'
  return 'ok'
})
const color = computed(() => tone.value === 'err' ? 'var(--err)' : tone.value === 'warn' ? 'var(--warn)' : 'var(--ok)')
</script>

<template>
  <div class="row" :class="{ 'no-value': !showValue }">
    <div v-if="showValue" class="v">{{ pct }}%</div>
    <div class="track" :style="{ height: `${height}px` }">
      <div class="fill" :style="{ width: `${Math.min(100, Math.max(0, pct))}%`, background: color }" />
    </div>
  </div>
</template>

<style scoped>
/* Value leads the bar so the number sits under its own column header
   and reads as part of this bar — trailing it right-aligns the number
   flush against the next table column, where it looks like it belongs
   to the neighboring bar. */
.row { display: grid; grid-template-columns: 36px 1fr; gap: 8px; align-items: center; }
.row.no-value { grid-template-columns: 1fr; }
.track {
  background: var(--bg-2);
  border-radius: 999px;
  overflow: hidden;
  border: 1px solid var(--line);
}
.fill { height: 100%; border-radius: 999px; transition: width 0.4s; }
.v {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-2);
  text-align: right;
  font-variant-numeric: tabular-nums;
}
</style>
