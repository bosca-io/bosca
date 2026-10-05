<script lang="ts" setup>
import type { TimeEvent } from '~/composables/useTimeEvents'
import { useEventHoverPreview } from '~/composables/useEventPreview'
import { typeColor } from '~/utils/timeline'

const props = defineProps<{
  event: TimeEvent
  pixelsPerMs: number
  selected: boolean
}>()

const emit = defineEmits<{
  (_e: 'select' | 'open'): void
  (_e: 'move', _newStartMs: number, _newEndMs: number | null): void
}>()

const isDragging = ref(false)
const dragOffsetMs = ref(0)
let cleanupDrag: (() => void) | null = null

const { hoverEvent, hoverPreview, mouseX, mouseY, onEnter, onMove, onLeave, dismiss } =
  useEventHoverPreview()

const isRange = computed(() => props.event.endOffsetMs != null)

const color = computed(() => {
  return (props.event.type.configuration as Record<string, unknown> | null)?.color as string || typeColor(props.event.type.id)
})

const left = computed(() => {
  const ms = props.event.startOffsetMs + dragOffsetMs.value
  return Math.max(0, ms * props.pixelsPerMs)
})

const width = computed(() => {
  if (!isRange.value) return 12
  const startMs = props.event.startOffsetMs + dragOffsetMs.value
  const endMs = (props.event.endOffsetMs ?? 0) + dragOffsetMs.value
  return Math.max(4, (endMs - startMs) * props.pixelsPerMs)
})

function onMouseDown(e: MouseEvent) {
  if (e.button !== 0) return
  e.preventDefault()
  e.stopPropagation()

  emit('select')
  dismiss()

  const startX = e.clientX
  isDragging.value = false
  dragOffsetMs.value = 0

  function onMouseMove(me: MouseEvent) {
    const dx = me.clientX - startX
    if (!isDragging.value && Math.abs(dx) < 3) return
    isDragging.value = true
    dragOffsetMs.value = Math.round(dx / props.pixelsPerMs)
  }

  function onMouseUp() {
    if (isDragging.value && Math.abs(dragOffsetMs.value) > 10) {
      const newStart = Math.max(0, props.event.startOffsetMs + dragOffsetMs.value)
      const newEnd = props.event.endOffsetMs != null
        ? Math.max(0, props.event.endOffsetMs + dragOffsetMs.value)
        : null
      emit('move', newStart, newEnd)
    } else if (!isDragging.value) {
      // A click without a drag opens the event editor
      emit('open')
    }
    isDragging.value = false
    dragOffsetMs.value = 0
    cleanup()
  }

  function cleanup() {
    document.removeEventListener('mousemove', onMouseMove)
    document.removeEventListener('mouseup', onMouseUp)
    cleanupDrag = null
  }

  document.addEventListener('mousemove', onMouseMove)
  document.addEventListener('mouseup', onMouseUp)
  cleanupDrag = cleanup
}

function onHoverEnter(e: MouseEvent) {
  if (isDragging.value) return
  onEnter(e, props.event)
}

function onHoverMove(e: MouseEvent) {
  if (isDragging.value) return
  onMove(e)
}

onUnmounted(() => {
  cleanupDrag?.()
})
</script>

<template>
  <div
    class="marker"
    :class="{
      'marker--point': !isRange,
      'marker--range': isRange,
      'marker--selected': selected,
      'marker--dragging': isDragging,
    }"
    :style="{
      left: left + 'px',
      width: isRange ? width + 'px' : undefined,
      backgroundColor: isRange ? color : undefined,
    }"
    @mousedown="onMouseDown"
    @mouseenter="onHoverEnter"
    @mousemove="onHoverMove"
    @mouseleave="onLeave"
  >
    <div v-if="!isRange" class="marker-diamond" :style="{ backgroundColor: color }" />
    <span v-if="isRange && width > 40" class="marker-label">{{ event.type.name }}</span>

    <TimeEventPreview
      v-if="hoverEvent?.id === event.id && !isDragging"
      :event="event"
      :preview="hoverPreview"
      :x="mouseX"
      :y="mouseY"
    />
  </div>
</template>

<style scoped>
.marker {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  cursor: grab;
  z-index: 10;
}

.marker--point {
  width: 12px;
  height: 12px;
}

.marker--range {
  height: 24px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  padding: 0 4px;
  min-width: 4px;
}

.marker-diamond {
  width: 10px;
  height: 10px;
  transform: rotate(45deg);
  border-radius: 2px;
}

.marker--selected {
  outline: 2px solid #fff;
  outline-offset: 1px;
  z-index: 20;
}

.marker--dragging {
  opacity: 0.7;
  cursor: grabbing;
}

.marker:hover {
  filter: brightness(1.15);
}

.marker-label {
  font-size: 10px;
  color: #fff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.5);
  pointer-events: none;
}
</style>
