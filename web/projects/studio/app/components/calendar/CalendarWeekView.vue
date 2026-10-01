<script lang="ts" setup>
import { getWeekDays, isSameDay, formatTime, snapToQuarter } from '~/utils/calendar'
import type { MonthEvent } from '~/components/calendar/CalendarMonthView.vue'

const HOUR_HEIGHT = 48
const DEAD_ZONE = 4
const SNAP_MINUTES = 15

const props = defineProps<{
  current: Date
  events: MonthEvent[]
  calendarColors: Record<string, string>
}>()

const emit = defineEmits<{
  selectSlot: [day: Date, hour: number]
  selectEvent: [event: MonthEvent]
  moveEvent: [eventId: string, startsAt: string, endsAt: string]
}>()

const today = new Date()
const weekDays = computed(() => getWeekDays(props.current))

const HOURS = Array.from({ length: 24 }, (_, i) => i)
const WEEKDAY_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

const headerRef = ref<HTMLElement | null>(null)
const headerHeight = ref(56)

onMounted(() => {
  if (headerRef.value) {
    headerHeight.value = headerRef.value.offsetHeight
  }
})

watch(() => props.current, () => {
  nextTick(() => {
    if (headerRef.value) {
      headerHeight.value = headerRef.value.offsetHeight
    }
  })
})

function colorFor(event: MonthEvent): string {
  return props.calendarColors[event.calendarId] ?? '#6b7280'
}

function timedEventsForDay(day: Date): MonthEvent[] {
  const dayStart = new Date(day); dayStart.setHours(0, 0, 0, 0)
  const dayEnd = new Date(day); dayEnd.setHours(23, 59, 59, 999)
  return props.events.filter((evt) => {
    if (evt.allDay) return false
    const s = new Date(evt.startsAt)
    return s >= dayStart && s <= dayEnd
  })
}

function allDayEventsForDay(day: Date): MonthEvent[] {
  const dayStart = new Date(day); dayStart.setHours(0, 0, 0, 0)
  const dayEnd = new Date(day); dayEnd.setHours(23, 59, 59, 999)
  return props.events.filter((evt) => {
    if (!evt.allDay) return false
    const s = new Date(evt.startsAt)
    const e = new Date(evt.endsAt)
    return s <= dayEnd && e >= dayStart
  })
}

function topFor(event: MonthEvent): number {
  const d = new Date(event.startsAt)
  return d.getHours() * HOUR_HEIGHT + (d.getMinutes() / 60) * HOUR_HEIGHT
}

function heightFor(event: MonthEvent): number {
  const s = new Date(event.startsAt)
  const e = new Date(event.endsAt)
  const mins = Math.max(15, (e.getTime() - s.getTime()) / 60000)
  return Math.max(20, (mins / 60) * HOUR_HEIGHT)
}

function formatHour(h: number): string {
  if (h === 0) return '12 AM'
  if (h < 12) return `${h} AM`
  if (h === 12) return '12 PM'
  return `${h - 12} PM`
}

// Drag state
const drag = ref<{
  active: boolean
  eventId: string
  mode: 'move' | 'resize'
  top: number
  height: number
  dayIndex: number
} | null>(null)

let originY = 0
let originTop = 0
let originHeight = 0
let originDayIndex = 0
let durationMinutes = 0
let didMove = false
const dayColRefs = ref<(HTMLElement | null)[]>([])

function setDayColRef(idx: number, el: HTMLElement | null) {
  dayColRefs.value[idx] = el
}

function resolveDayIndex(clientX: number): number {
  for (let i = 0; i < dayColRefs.value.length; i++) {
    const el = dayColRefs.value[i]
    if (!el) continue
    const rect = el.getBoundingClientRect()
    if (clientX >= rect.left && clientX <= rect.right) return i
  }
  return originDayIndex
}

function pxToMinutes(px: number): number {
  return (px / HOUR_HEIGHT) * 60
}

function minutesToPx(minutes: number): number {
  return (minutes / 60) * HOUR_HEIGHT
}

function onEventPointerDown(e: PointerEvent, event: MonthEvent, dayIdx: number) {
  if (event.source !== 'USER') return
  e.preventDefault()
  e.stopPropagation()

  const eventTop = topFor(event)
  const eventHeight = heightFor(event)
  const target = e.target as HTMLElement
  const isResize = target.classList.contains('resize-handle')

  originY = e.clientY
  originTop = eventTop
  originHeight = eventHeight
  originDayIndex = dayIdx
  durationMinutes = pxToMinutes(eventHeight)
  didMove = false

  drag.value = {
    active: true,
    eventId: event.id,
    mode: isResize ? 'resize' : 'move',
    top: eventTop,
    height: eventHeight,
    dayIndex: dayIdx,
  }

  document.addEventListener('pointermove', onPointerMove)
  document.addEventListener('pointerup', onPointerUp)
}

function onPointerMove(e: PointerEvent) {
  if (!drag.value) return
  const dy = e.clientY - originY
  if (!didMove && Math.abs(dy) < DEAD_ZONE) return
  didMove = true

  if (drag.value.mode === 'move') {
    const newMinutes = snapToQuarter(pxToMinutes(originTop + dy))
    drag.value.top = minutesToPx(Math.max(0, Math.min(1440 - durationMinutes, newMinutes)))
    drag.value.dayIndex = resolveDayIndex(e.clientX)
  } else {
    const newMinutes = Math.max(SNAP_MINUTES, snapToQuarter(pxToMinutes(originHeight + dy)))
    drag.value.height = minutesToPx(newMinutes)
  }
}

function onPointerUp() {
  document.removeEventListener('pointermove', onPointerMove)
  document.removeEventListener('pointerup', onPointerUp)

  if (!drag.value || !didMove) {
    drag.value = null
    return
  }

  const d = drag.value
  const baseDay = weekDays.value[d.dayIndex]
  if (!baseDay) { drag.value = null; return }

  const startMinutes = snapToQuarter(pxToMinutes(d.top))
  const endMinutes = d.mode === 'resize'
    ? startMinutes + snapToQuarter(pxToMinutes(d.height))
    : startMinutes + durationMinutes

  const startsAt = new Date(baseDay)
  startsAt.setHours(0, 0, 0, 0)
  startsAt.setMinutes(Math.max(0, startMinutes))

  const endsAt = new Date(baseDay)
  endsAt.setHours(0, 0, 0, 0)
  endsAt.setMinutes(Math.min(1440, endMinutes))

  emit('moveEvent', d.eventId, startsAt.toISOString(), endsAt.toISOString())
  // Keep drag visual briefly so the event doesn't flash while data refreshes
  setTimeout(() => { drag.value = null }, 500)
}

function onSlotClick(day: Date, hour: number) {
  if (didMove) return
  emit('selectSlot', day, hour)
}

function onEventClick(event: MonthEvent) {
  if (didMove) {
    didMove = false
    return
  }
  emit('selectEvent', event)
}

const draggedEvent = computed(() => {
  if (!drag.value) return null
  return props.events.find((e) => e.id === drag.value!.eventId) ?? null
})

function dragTimeLabel(evt: MonthEvent): string {
  if (!drag.value || drag.value.eventId !== evt.id) return formatTime(new Date(evt.startsAt))
  const startMin = Math.round(pxToMinutes(drag.value.top))
  const endMin = drag.value.mode === 'resize'
    ? startMin + Math.round(pxToMinutes(drag.value.height))
    : startMin + Math.round(pxToMinutes(heightFor(evt)))
  const sh = Math.floor(startMin / 60)
  const sm = startMin % 60
  const eh = Math.floor(endMin / 60)
  const em = endMin % 60
  return `${sh.toString().padStart(2, '0')}:${sm.toString().padStart(2, '0')} – ${eh.toString().padStart(2, '0')}:${em.toString().padStart(2, '0')}`
}

onUnmounted(() => {
  document.removeEventListener('pointermove', onPointerMove)
  document.removeEventListener('pointerup', onPointerUp)
})
</script>

<template>
  <div class="week-view">
    <!-- Single grid, scrollable, with sticky header + all-day -->
    <div class="week-scroll">
      <div class="week-grid">
        <!-- Row 1: Day headers (sticky) -->
        <div ref="headerRef" class="g-gutter g-header" />
        <div
          v-for="(day, idx) in weekDays"
          :key="'h-' + idx"
          class="g-day g-header"
          :class="{ 'g-header--today': isSameDay(day, today) }"
        >
          <span class="day-label">{{ WEEKDAY_SHORT[idx] }}</span>
          <span class="day-date" :class="{ 'day-date--today': isSameDay(day, today) }">
            {{ day.getDate() }}
          </span>
        </div>

        <!-- Row 2: All-day (sticky below header) -->
        <div class="g-gutter g-allday g-allday-label" :style="{ top: headerHeight + 'px' }">All day</div>
        <div
          v-for="(day, idx) in weekDays"
          :key="'ad-' + idx"
          class="g-day g-allday"
          :style="{ top: headerHeight + 'px' }">
          <div
            v-for="evt in allDayEventsForDay(day)"
            :key="evt.id"
            class="all-day-chip"
            :style="{ backgroundColor: colorFor(evt) + '1a', borderLeftColor: colorFor(evt), color: colorFor(evt) }"
            @click="onEventClick(evt)"
          >
            {{ evt.title }}
          </div>
        </div>

        <!-- Row 3: Time grid -->
        <div class="g-gutter g-time">
          <div
            v-for="h in HOURS"
            :key="h"
            class="hour-label"
            :style="{ height: HOUR_HEIGHT + 'px' }">
            {{ formatHour(h) }}
          </div>
        </div>

        <div
          v-for="(day, dayIdx) in weekDays"
          :key="'tc-' + dayIdx"
          :ref="(el: any) => setDayColRef(dayIdx, el as HTMLElement)"
          class="g-day day-column"
        >
          <div
            v-for="h in HOURS"
            :key="h"
            class="hour-slot"
            :style="{ height: HOUR_HEIGHT + 'px' }"
            @click="onSlotClick(day, h)" />

          <div
            v-for="evt in timedEventsForDay(day)"
            :key="evt.id"
            class="timed-event"
            :class="{ 'timed-event--dragging': drag?.eventId === evt.id }"
            :style="{
              top: (drag?.eventId === evt.id ? drag.top : topFor(evt)) + 'px',
              height: (drag?.eventId === evt.id ? drag.height : heightFor(evt)) + 'px',
              backgroundColor: colorFor(evt) + '1a',
              borderLeftColor: colorFor(evt),
              color: colorFor(evt),
              display: drag?.eventId === evt.id && drag.mode === 'move' && drag.dayIndex !== dayIdx ? 'none' : 'flex',
            }"
            @click.stop="onEventClick(evt)"
            @pointerdown="onEventPointerDown($event, evt, dayIdx)"
          >
            <span class="timed-event-time">{{ drag?.eventId === evt.id ? dragTimeLabel(evt) : formatTime(new Date(evt.startsAt)) }}</span>
            <span class="timed-event-title">{{ evt.title }}</span>
            <div v-if="evt.source === 'USER'" class="resize-handle" />
          </div>

          <div
            v-if="drag && drag.mode === 'move' && drag.dayIndex === dayIdx && draggedEvent && !timedEventsForDay(day).some(e => e.id === drag!.eventId)"
            :key="'ghost-' + drag.eventId"
            class="timed-event timed-event--dragging"
            :style="{
              top: drag.top + 'px',
              height: drag.height + 'px',
              backgroundColor: colorFor(draggedEvent) + '1a',
              borderLeftColor: colorFor(draggedEvent),
              color: colorFor(draggedEvent),
            }"
          >
            <span class="timed-event-time">{{ formatTime(new Date(draggedEvent.startsAt)) }}</span>
            <span class="timed-event-title">{{ draggedEvent.title }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.week-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
}

.week-scroll {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

.week-grid {
  display: grid;
  grid-template-columns: 60px repeat(7, 1fr);
}

.g-day { border-left: 1px solid var(--line); }

/* Row 1: header (sticky at top of scroll) */
.g-header {
  position: sticky;
  top: 0;
  z-index: 10;
  background: var(--bg-1);
  border-bottom: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 6px 0;
}

.g-header--today {
  background: color-mix(in oklch, var(--brand-2) 6%, var(--bg-1));
}

.day-label { font-size: 11px; color: var(--fg-3); text-transform: uppercase; }
.day-date { font-size: 18px; font-weight: 600; color: var(--fg-1); line-height: 1.2; }
.day-date--today {
  background: var(--brand-2); color: #fff; border-radius: 50%;
  width: 28px; height: 28px; display: flex; align-items: center; justify-content: center;
}

/* Row 2: all-day (sticky below header) */
.g-allday {
  position: sticky;
  z-index: 9;
  background: var(--bg-1);
  border-bottom: 1px solid var(--line);
  min-height: 28px;
  padding: 2px 4px;
}

.g-allday-label {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  color: var(--fg-3);
  text-transform: uppercase;
  padding: 0;
}

.all-day-chip {
  font-size: 11px;
  padding: 1px 4px;
  border-radius: 3px;
  border-left: 2px solid;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* Row 3: time grid */
.g-time { }

.hour-label {
  display: flex;
  align-items: flex-start;
  justify-content: flex-end;
  padding: 2px 8px 0;
  font-size: 10px;
  color: var(--fg-3);
  box-sizing: border-box;
}

.day-column {
  position: relative;
}

.hour-slot {
  cursor: pointer;
  box-sizing: border-box;
  border-bottom: 1px solid var(--line);
}

.hour-slot:hover {
  background: var(--bg-1);
}

.timed-event {
  position: absolute;
  left: 2px;
  right: 2px;
  border-left: 3px solid;
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 11px;
  cursor: pointer;
  overflow: hidden;
  z-index: 5;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.timed-event:hover { filter: brightness(1.15); }
.timed-event--dragging { opacity: 0.8; z-index: 20; cursor: grabbing; }

.timed-event-time { font-size: 10px; opacity: 0.7; }
.timed-event-title { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.resize-handle {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 8px;
  cursor: ns-resize;
}
</style>
