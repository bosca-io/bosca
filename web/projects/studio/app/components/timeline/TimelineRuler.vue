<script lang="ts" setup>
import { formatMs } from '~/utils/timeline'

const props = defineProps<{
  durationMs: number
  currentTimeMs: number
  pixelsPerMs: number
}>()

const emit = defineEmits<{
  (_e: 'seek', _ms: number): void
}>()

const totalWidth = computed(() => props.durationMs * props.pixelsPerMs)
const playheadLeft = computed(() => Math.max(0, props.currentTimeMs * props.pixelsPerMs))

const ticks = computed(() => {
  const pxPerSec = props.pixelsPerMs * 1000
  let intervalMs: number
  if (pxPerSec > 100) intervalMs = 1000
  else if (pxPerSec > 20) intervalMs = 5000
  else if (pxPerSec > 5) intervalMs = 15000
  else intervalMs = 60000

  const result: { ms: number; left: number; label: string; major: boolean }[] = []
  for (let ms = 0; ms <= props.durationMs; ms += intervalMs) {
    result.push({
      ms,
      left: ms * props.pixelsPerMs,
      label: formatMs(ms),
      major: ms % (intervalMs * 5) === 0 || intervalMs >= 60000,
    })
  }
  return result
})

function onRulerClick(e: MouseEvent) {
  const target = e.currentTarget as HTMLElement
  const rect = target.getBoundingClientRect()
  const scrollLeft = target.scrollLeft || 0
  const x = e.clientX - rect.left + scrollLeft
  const ms = Math.max(0, Math.min(props.durationMs, Math.round(x / props.pixelsPerMs)))
  emit('seek', ms)
}
</script>

<template>
  <div class="ruler" @click="onRulerClick">
    <div class="ruler-track" :style="{ width: totalWidth + 'px' }">
      <div
        v-for="tick in ticks"
        :key="tick.ms"
        class="ruler-tick"
        :class="{ 'ruler-tick--major': tick.major }"
        :style="{ left: tick.left + 'px' }"
      >
        <span v-if="tick.major" class="ruler-label">{{ tick.label }}</span>
      </div>

      <div
        class="ruler-playhead"
        :style="{ left: playheadLeft + 'px' }"
      >
        <div class="ruler-playhead-diamond" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.ruler {
  height: 32px;
  background: var(--bg-1);
  border-bottom: 1px solid var(--line);
  overflow: hidden;
  position: relative;
  cursor: pointer;
  user-select: none;
}

.ruler-track {
  position: relative;
  height: 100%;
  min-width: 100%;
}

.ruler-tick {
  position: absolute;
  top: 0;
  width: 1px;
  height: 12px;
  background: var(--fg-3);
  opacity: 0.3;
}

.ruler-tick--major {
  height: 100%;
  opacity: 0.5;
}

.ruler-label {
  position: absolute;
  top: 2px;
  left: 4px;
  font-size: 10px;
  color: var(--fg-3);
  white-space: nowrap;
  pointer-events: none;
  font-family: var(--font-mono, monospace);
}

.ruler-playhead {
  position: absolute;
  top: 0;
  width: 1px;
  height: 100%;
  background: #ef4444;
  z-index: 10;
  pointer-events: none;
}

.ruler-playhead-diamond {
  position: absolute;
  top: -2px;
  left: -4px;
  width: 8px;
  height: 8px;
  background: #ef4444;
  transform: rotate(45deg);
}
</style>
