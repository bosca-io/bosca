<script lang="ts" setup>
import { getMonthGrid, isSameDay, formatTime, addDays } from '~/utils/calendar'

export interface MonthEvent {
  id: string
  title: string
  allDay: boolean
  startsAt: string
  endsAt: string
  source: string
  calendarId: string
}

const WEEKDAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
const MAX_VISIBLE_EVENTS = 3
const DEAD_ZONE = 5

const props = defineProps<{
  current: Date
  events: MonthEvent[]
  calendarColors: Record<string, string>
}>()

const emit = defineEmits<{
  selectDay: [day: Date]
  newEvent: [day: Date]
  selectEvent: [event: MonthEvent]
  moveEvent: [eventId: string, startsAt: string, endsAt: string]
}>()

const today = new Date()
const grid = computed(() => getMonthGrid(props.current))

function eventsForDay(day: Date): MonthEvent[] {
  const dayStart = new Date(day)
  dayStart.setHours(0, 0, 0, 0)
  const dayEnd = addDays(dayStart, 1)

  return props.events.filter((evt) => {
    const start = new Date(evt.startsAt)
    const end = new Date(evt.endsAt)
    return start < dayEnd && end >= dayStart
  })
}

function colorFor(event: MonthEvent): string {
  return props.calendarColors[event.calendarId] ?? '#6b7280'
}

function timeLabel(event: MonthEvent): string {
  if (event.allDay) return ''
  return formatTime(new Date(event.startsAt))
}

function isCurrentMonth(day: Date): boolean {
  return day.getMonth() === props.current.getMonth()
}

// Drag state
const dragEventId = ref<string | null>(null)
let dragStartX = 0
let dragStartY = 0
let didMove = false
const dragTargetDay = ref<Date | null>(null)
const dragMouseX = ref(0)
const dragMouseY = ref(0)
const isDragging = ref(false)
const dragChipWidth = ref(0)
const dragChipHeight = ref(0)
const cellRefs = new Map<string, HTMLElement>()

const draggedEvent = computed(() => {
  if (!dragEventId.value || !isDragging.value) return null
  return props.events.find((e) => e.id === dragEventId.value) ?? null
})

function setCellRef(day: Date, el: HTMLElement | null) {
  const key = day.toISOString().slice(0, 10)
  if (el) cellRefs.set(key, el)
  else cellRefs.delete(key)
}

function resolveDayFromPointer(x: number, y: number): Date | null {
  for (const [key, el] of cellRefs) {
    const rect = el.getBoundingClientRect()
    if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
      return new Date(key + 'T00:00:00')
    }
  }
  return null
}

function onEventPointerDown(e: PointerEvent, event: MonthEvent) {
  if (event.source !== 'USER') return
  e.preventDefault()
  e.stopPropagation()

  dragEventId.value = event.id
  dragStartX = e.clientX
  dragStartY = e.clientY
  didMove = false
  dragTargetDay.value = null

  const chip = (e.currentTarget as HTMLElement)
  dragChipWidth.value = chip.offsetWidth
  dragChipHeight.value = chip.offsetHeight

  document.addEventListener('pointermove', onPointerMove)
  document.addEventListener('pointerup', onPointerUp)
}

function onPointerMove(e: PointerEvent) {
  const dx = e.clientX - dragStartX
  const dy = e.clientY - dragStartY
  if (!didMove && Math.abs(dx) < DEAD_ZONE && Math.abs(dy) < DEAD_ZONE) return
  didMove = true
  isDragging.value = true
  dragMouseX.value = e.clientX
  dragMouseY.value = e.clientY
  dragTargetDay.value = resolveDayFromPointer(e.clientX, e.clientY)
}

function onPointerUp() {
  document.removeEventListener('pointermove', onPointerMove)
  document.removeEventListener('pointerup', onPointerUp)

  if (didMove && dragEventId.value && dragTargetDay.value) {
    const evt = props.events.find((ev) => ev.id === dragEventId.value)
    if (evt) {
      const origStart = new Date(evt.startsAt)
      const origEnd = new Date(evt.endsAt)
      const durationMs = origEnd.getTime() - origStart.getTime()

      const newStart = new Date(dragTargetDay.value)
      newStart.setHours(origStart.getHours(), origStart.getMinutes(), origStart.getSeconds())
      const newEnd = new Date(newStart.getTime() + durationMs)

      emit('moveEvent', dragEventId.value!, newStart.toISOString(), newEnd.toISOString())
    }
  }

  dragEventId.value = null
  dragTargetDay.value = null
  isDragging.value = false
  didMove = false
}

function onDayClick(day: Date) {
  if (didMove) return
  emit('selectDay', day)
}

function onDayDblClick(day: Date) {
  emit('newEvent', day)
}

function onEventClick(event: MonthEvent) {
  if (didMove) return
  emit('selectEvent', event)
}

onUnmounted(() => {
  document.removeEventListener('pointermove', onPointerMove)
  document.removeEventListener('pointerup', onPointerUp)
})
</script>

<template>
  <div class="month-view">
    <!-- Weekday header -->
    <div class="weekday-header">
      <div v-for="label in WEEKDAY_LABELS" :key="label" class="weekday-label">
        {{ label }}
      </div>
    </div>

    <!-- Days grid -->
    <div class="days-grid" :style="{ gridTemplateRows: `repeat(${grid.length}, 1fr)` }">
      <template v-for="(row, wi) in grid" :key="wi">
        <div
          v-for="(day, di) in row"
          :key="wi + '-' + di"
          :ref="(el: any) => setCellRef(day, el as HTMLElement)"
          class="day-cell"
          :class="{
            'day-cell--other': !isCurrentMonth(day),
            'day-cell--today': isSameDay(day, today),
            'day-cell--drag-target': dragTargetDay && isSameDay(day, dragTargetDay),
          }"
          @click="onDayClick(day)"
          @dblclick.prevent="onDayDblClick(day)"
        >
          <span class="day-number" :class="{ 'day-number--today': isSameDay(day, today) }">
            {{ day.getDate() }}
          </span>

          <div class="day-events">
            <button
              v-for="evt in eventsForDay(day).slice(0, MAX_VISIBLE_EVENTS)"
              :key="evt.id"
              class="event-chip"
              :class="{ 'event-chip--hidden': isDragging && dragEventId === evt.id }"
              :style="{
                backgroundColor: colorFor(evt) + '1a',
                borderLeftColor: colorFor(evt),
                color: colorFor(evt),
              }"
              @click.stop="onEventClick(evt)"
              @pointerdown="onEventPointerDown($event, evt)"
            >
              <span v-if="!evt.allDay" class="event-time">{{ timeLabel(evt) }}</span>
              <span class="event-title">{{ evt.title }}</span>
            </button>

            <div
              v-if="eventsForDay(day).length > MAX_VISIBLE_EVENTS"
              class="event-overflow"
            >
              +{{ eventsForDay(day).length - MAX_VISIBLE_EVENTS }} more
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- Floating drag ghost -->
    <Teleport to="body">
      <div
        v-if="draggedEvent"
        class="drag-ghost"
        :style="{
          left: dragMouseX - dragChipWidth / 2 + 'px',
          top: dragMouseY - dragChipHeight / 2 + 'px',
          width: dragChipWidth + 'px',
          height: dragChipHeight + 'px',
          backgroundColor: colorFor(draggedEvent) + '1a',
          borderLeftColor: colorFor(draggedEvent),
          color: colorFor(draggedEvent),
        }"
      >
        <span v-if="!draggedEvent.allDay" class="drag-ghost-time">{{ timeLabel(draggedEvent) }}</span>
        <span class="drag-ghost-title">{{ draggedEvent.title }}</span>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.month-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
}

.weekday-header {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  border-bottom: 1px solid var(--line);
  background: var(--bg-1);
}

.weekday-label {
  padding: 6px 8px;
  font-size: 11px;
  font-weight: 600;
  color: var(--fg-3);
  text-align: center;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.days-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  flex: 1;
}

.day-cell {
  min-height: 96px;
  padding: 4px 6px;
  border-right: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.day-cell:nth-child(7n) {
  border-right: none;
}

.day-cell:hover {
  background: var(--bg-1);
}

.day-cell--other {
  opacity: 0.4;
}

.day-cell--today {
  background: color-mix(in oklch, var(--brand-2) 4%, transparent);
}

.day-cell--drag-target {
  background: color-mix(in oklch, var(--brand-2) 10%, transparent);
}

.day-number {
  font-size: 12px;
  color: var(--fg-2);
  text-align: right;
  padding: 2px 4px;
  line-height: 1;
}

.day-number--today {
  background: var(--brand-2);
  color: #fff;
  border-radius: 50%;
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-left: auto;
}

.day-events {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 2px;
  flex: 1;
  min-height: 0;
}

.event-chip {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 1px 4px;
  border-radius: 3px;
  border-left: 2px solid;
  font-size: 11px;
  cursor: pointer;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  background: none;
  border-top: none;
  border-right: none;
  border-bottom: none;
  text-align: left;
}

.event-chip:hover {
  filter: brightness(1.2);
}

.event-chip--hidden {
  opacity: 0.2;
}

.event-time {
  font-size: 10px;
  opacity: 0.7;
  flex-shrink: 0;
}

.event-title {
  overflow: hidden;
  text-overflow: ellipsis;
}

.event-overflow {
  font-size: 10px;
  color: var(--fg-3);
  padding: 0 4px;
}

.drag-ghost {
  position: fixed;
  z-index: 10000;
  pointer-events: none;
  padding: 3px 8px;
  border-radius: 4px;
  border-left: 3px solid;
  font-size: 12px;
  white-space: nowrap;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
  max-width: 200px;
  overflow: hidden;
  display: flex;
  align-items: center;
  gap: 4px;
}

.drag-ghost-time {
  font-size: 10px;
  opacity: 0.7;
}

.drag-ghost-title {
  overflow: hidden;
  text-overflow: ellipsis;
  font-weight: 500;
}
</style>
