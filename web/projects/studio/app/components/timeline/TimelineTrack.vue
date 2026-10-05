<script lang="ts" setup>
import type { TimeEvent, TimeEventType } from '~/composables/useTimeEvents'
import { typeColor } from '~/utils/timeline'

const props = defineProps<{
  type: TimeEventType
  events: TimeEvent[]
  durationMs: number
  currentTimeMs: number
  pixelsPerMs: number
  selectedEventId: string | null
}>()

const emit = defineEmits<{
  (_e: 'select' | 'open', _eventId: string): void
  (_e: 'move', _eventId: string, _newStartMs: number, _newEndMs: number | null): void
}>()

const totalWidth = computed(() => props.durationMs * props.pixelsPerMs)

const trackColor = computed(() => {
  return (props.type.configuration as Record<string, unknown> | null)?.color as string || typeColor(props.type.id)
})

const playheadLeft = computed(() => Math.max(0, props.currentTimeMs * props.pixelsPerMs))
</script>

<template>
  <div class="track">
    <div class="track-label">
      <span class="track-dot" :style="{ backgroundColor: trackColor }" />
      <span class="track-name">{{ type.name }}</span>
    </div>

    <div class="track-area" :style="{ width: totalWidth + 'px' }">
      <div class="track-playhead" :style="{ left: playheadLeft + 'px' }" />

      <TimeEventMarker
        v-for="evt in events"
        :key="evt.id"
        :event="evt"
        :pixels-per-ms="pixelsPerMs"
        :selected="evt.id === selectedEventId"
        @select="emit('select', evt.id)"
        @open="emit('open', evt.id)"
        @move="(s: number, e: number | null) => emit('move', evt.id, s, e)"
      />
    </div>
  </div>
</template>

<style scoped>
.track {
  display: flex;
  height: 40px;
  border-bottom: 1px solid var(--line);
}

.track-label {
  width: 120px;
  min-width: 120px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  border-right: 1px solid var(--line);
  background: var(--bg-1);
}

.track-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.track-name {
  font-size: 11px;
  color: var(--fg-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.track-area {
  position: relative;
  min-width: 100%;
  background: var(--bg-0);
}

.track-area:hover {
  background: var(--bg-1);
}

.track-playhead {
  position: absolute;
  top: 0;
  width: 1px;
  height: 100%;
  background: #ef4444;
  opacity: 0.3;
  z-index: 5;
  pointer-events: none;
}
</style>
