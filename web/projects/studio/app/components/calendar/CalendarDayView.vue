<script lang="ts" setup>
import { formatTime, snapToQuarter } from '~/utils/calendar'
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

const HOURS = Array.from({ length: 24 }, (_, i) => i)

function colorFor(event: MonthEvent): string {
  return props.calendarColors[event.calendarId] ?? '#6b7280'
}

const dayEvents = computed(() => {
  const dayStart = new Date(props.current); dayStart.setHours(0, 0, 0, 0)
  const dayEnd = new Date(props.current); dayEnd.setHours(23, 59, 59, 999)
  return props.events.filter((evt) => {
    const s = new Date(evt.startsAt)
    const e = new Date(evt.endsAt)
    return s <= dayEnd && e >= dayStart
  })
})

const timedEvents = computed(() => dayEvents.value.filter((e) => !e.allDay))
const allDayEvents = computed(() => dayEvents.value.filter((e) => e.allDay))

function topFor(event: MonthEvent): number {
  const d = new Date(event.startsAt)
  return d.getHours() * HOUR_HEIGHT + (d.getMinutes() / 60) * HOUR_HEIGHT
}

function heightFor(event: MonthEvent): number {
  const s = new Date(event.startsAt)
  const e = new Date(event.endsAt)
  const mins = Math.max(15, (e.getTime() - s.getTime()) / 60000)
  return Math.max(24, (mins / 60) * HOUR_HEIGHT)
}

function formatHour(h: number): string {
  if (h === 0) return '12 AM'
  if (h < 12) return `${h} AM`
  if (h === 12) return '12 PM'
  return `${h - 12} PM`
}

// Drag state (simplified single-column version)
const drag = ref<{
  active: boolean; eventId: string; mode: 'move' | 'resize'; top: number; height: number
} | null>(null)

let originY = 0; let originTop = 0; let originHeight = 0; let durationMinutes = 0; let didMove = false

function pxToMinutes(px: number): number { return (px / HOUR_HEIGHT) * 60 }
function minutesToPx(minutes: number): number { return (minutes / 60) * HOUR_HEIGHT }

function onEventPointerDown(e: PointerEvent, event: MonthEvent) {
  if (event.source !== 'USER') return
  e.preventDefault(); e.stopPropagation()
  const evtTop = topFor(event); const evtHeight = heightFor(event)
  const isResize = (e.target as HTMLElement).classList.contains('resize-handle')
  originY = e.clientY; originTop = evtTop; originHeight = evtHeight
  durationMinutes = pxToMinutes(evtHeight); didMove = false
  drag.value = { active: true, eventId: event.id, mode: isResize ? 'resize' : 'move', top: evtTop, height: evtHeight }
  document.addEventListener('pointermove', onPointerMove)
  document.addEventListener('pointerup', onPointerUp)
}

function onPointerMove(e: PointerEvent) {
  if (!drag.value) return
  const dy = e.clientY - originY
  if (!didMove && Math.abs(dy) < DEAD_ZONE) return
  didMove = true
  if (drag.value.mode === 'move') {
    const newMin = snapToQuarter(pxToMinutes(originTop + dy))
    drag.value.top = minutesToPx(Math.max(0, Math.min(1440 - durationMinutes, newMin)))
  } else {
    const newMin = Math.max(SNAP_MINUTES, snapToQuarter(pxToMinutes(originHeight + dy)))
    drag.value.height = minutesToPx(newMin)
  }
}

function onPointerUp() {
  document.removeEventListener('pointermove', onPointerMove)
  document.removeEventListener('pointerup', onPointerUp)
  if (!drag.value || !didMove) { drag.value = null; return }
  const d = drag.value
  const startMin = snapToQuarter(pxToMinutes(d.top))
  const endMin = d.mode === 'resize' ? startMin + snapToQuarter(pxToMinutes(d.height)) : startMin + durationMinutes
  const startsAt = new Date(props.current); startsAt.setHours(0, 0, 0, 0); startsAt.setMinutes(Math.max(0, startMin))
  const endsAt = new Date(props.current); endsAt.setHours(0, 0, 0, 0); endsAt.setMinutes(Math.min(1440, endMin))
  emit('moveEvent', d.eventId, startsAt.toISOString(), endsAt.toISOString())
  setTimeout(() => { drag.value = null }, 500)
}

let clickSuppressed = false

function onEventClick(evt: MonthEvent) {
  if (clickSuppressed) { clickSuppressed = false; return }
  if (didMove) { didMove = false; clickSuppressed = true; return }
  emit('selectEvent', evt)
}

function eventTop(event: MonthEvent): number {
  return drag.value?.eventId === event.id && drag.value.mode === 'move' ? drag.value.top : topFor(event)
}
function eventHeight(event: MonthEvent): number {
  return drag.value?.eventId === event.id ? drag.value.height : heightFor(event)
}

onUnmounted(() => { document.removeEventListener('pointermove', onPointerMove); document.removeEventListener('pointerup', onPointerUp) })
</script>

<template>
  <div class="day-view">
    <!-- All-day row -->
    <div v-if="allDayEvents.length" class="all-day-section">
      <span class="all-day-label">All day</span>
      <div class="all-day-events">
        <div
          v-for="evt in allDayEvents"
          :key="evt.id"
          class="all-day-chip"
          :style="{ backgroundColor: colorFor(evt) + '1a', borderLeftColor: colorFor(evt), color: colorFor(evt) }"
          @click="emit('selectEvent', evt)"
        >{{ evt.title }}</div>
      </div>
    </div>

    <!-- Time grid -->
    <div class="time-grid-scroll">
      <div class="time-grid">
        <div class="gutter">
          <div
            v-for="h in HOURS"
            :key="h"
            class="hour-label"
            :style="{ height: HOUR_HEIGHT + 'px' }">
            {{ formatHour(h) }}
          </div>
        </div>

        <div class="day-column">
          <div
            v-for="h in HOURS"
            :key="h"
            class="hour-slot"
            :style="{ height: HOUR_HEIGHT + 'px' }"
            @click="emit('selectSlot', current, h)" />

          <div
            v-for="evt in timedEvents"
            :key="evt.id"
            class="timed-event"
            :class="{ 'timed-event--dragging': drag?.eventId === evt.id }"
            :style="{ top: eventTop(evt) + 'px', height: eventHeight(evt) + 'px', backgroundColor: colorFor(evt) + '1a', borderLeftColor: colorFor(evt), color: colorFor(evt) }"
            @click.stop="onEventClick(evt)"
            @pointerdown="onEventPointerDown($event, evt)"
          >
            <span class="timed-event-time">{{ formatTime(new Date(evt.startsAt)) }}</span>
            <span class="timed-event-title">{{ evt.title }}</span>
            <div v-if="evt.source === 'USER'" class="resize-handle" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.day-view { display: flex; flex-direction: column; height: 100%; border: 1px solid var(--line); border-radius: var(--r-md); overflow: hidden; }
.all-day-section { display: flex; align-items: center; gap: 8px; padding: 4px 8px; border-bottom: 1px solid var(--line); background: var(--bg-0); }
.all-day-label { font-size: 10px; color: var(--fg-3); text-transform: uppercase; width: 60px; text-align: right; }
.all-day-events { display: flex; gap: 4px; flex-wrap: wrap; }
.all-day-chip { font-size: 11px; padding: 1px 6px; border-radius: 3px; border-left: 2px solid; cursor: pointer; }
.time-grid-scroll { flex: 1; overflow-y: auto; }
.time-grid { display: grid; grid-template-columns: 60px 1fr; position: relative; background-image: repeating-linear-gradient(to bottom, transparent, transparent 47px, var(--line) 47px, var(--line) 48px); background-size: 100% 48px; }
.gutter { position: relative; }
.hour-label { display: flex; align-items: flex-start; justify-content: flex-end; padding: 2px 8px 0; font-size: 10px; color: var(--fg-3); box-sizing: border-box; }
.day-column { position: relative; border-left: 1px solid var(--line); }
.hour-slot { cursor: pointer; box-sizing: border-box; }
.hour-slot:hover { background: var(--bg-1); }
.timed-event { position: absolute; left: 2px; right: 2px; border-left: 3px solid; border-radius: 4px; padding: 2px 6px; font-size: 11px; cursor: pointer; overflow: hidden; z-index: 5; display: flex; flex-direction: column; gap: 1px; }
.timed-event:hover { filter: brightness(1.15); }
.timed-event--dragging { opacity: 0.8; z-index: 20; cursor: grabbing; }
.timed-event-time { font-size: 10px; opacity: 0.7; }
.timed-event-title { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.resize-handle { position: absolute; bottom: 0; left: 0; right: 0; height: 8px; cursor: ns-resize; }
</style>
