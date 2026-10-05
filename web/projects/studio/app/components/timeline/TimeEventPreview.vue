<script lang="ts" setup>
import type { TimeEvent } from '~/composables/useTimeEvents'
import type { EventPreview } from '~/composables/useEventPreview'
import { formatMs } from '~/utils/timeline'

const TOOLTIP_WIDTH = 224
const TOOLTIP_MAX_HEIGHT = 200
const OFFSET = 12
const MARGIN = 8

const props = defineProps<{
  event: TimeEvent
  preview: EventPreview | null
  x: number
  y: number
}>()

const clampedLeft = computed(() => {
  if (typeof window === 'undefined') return props.x + OFFSET
  return Math.min(props.x + OFFSET, window.innerWidth - TOOLTIP_WIDTH - MARGIN)
})

const clampedTop = computed(() => {
  if (typeof window === 'undefined') return props.y + OFFSET
  const maxTop = window.innerHeight - TOOLTIP_MAX_HEIGHT - MARGIN
  if (props.y + OFFSET > maxTop) {
    return props.y - TOOLTIP_MAX_HEIGHT - OFFSET
  }
  return props.y + OFFSET
})
</script>

<template>
  <Teleport to="body">
    <div
      class="preview-tooltip"
      :style="{
        left: clampedLeft + 'px',
        top: clampedTop + 'px',
      }"
    >
      <img
        v-if="preview?.type === 'image'"
        :src="preview.src"
        alt=""
        class="preview-image"
      >
      <p v-else-if="preview?.type === 'text'" class="preview-text">
        {{ preview.text }}
      </p>

      <div class="preview-meta">
        <span class="preview-type">{{ event.type.name }}</span>
        <span class="preview-time">{{ formatMs(event.startOffsetMs) }}</span>
        <template v-if="event.endOffsetMs != null">
          <span class="preview-sep">–</span>
          <span class="preview-time">{{ formatMs(event.endOffsetMs) }}</span>
        </template>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.preview-tooltip {
  position: fixed;
  z-index: 9999;
  pointer-events: none;
  max-width: 224px;
  max-height: 200px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow: hidden;
}

.preview-image {
  max-height: 144px;
  object-fit: contain;
  border-radius: var(--r-sm);
}

.preview-text {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
}

.preview-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--fg-3);
}

.preview-type {
  font-weight: 500;
  color: var(--fg-2);
}

.preview-time {
  font-family: var(--font-mono, monospace);
}

.preview-sep {
  color: var(--fg-3);
}
</style>
