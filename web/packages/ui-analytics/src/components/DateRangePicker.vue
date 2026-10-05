<script setup lang="ts">
import { computed, ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import {
  format,
  startOfMonth,
  endOfMonth,
  startOfWeek,
  endOfWeek,
  addDays,
  addMonths,
  subMonths,
  subDays,
  isSameDay,
  isAfter,
  isSameMonth,
  isWithinInterval,
} from 'date-fns'
import { Button, Icon } from '@bosca/ui'

export interface DateRange {
  start: Date
  end: Date
}

const props = defineProps<{
  modelValue: DateRange
}>()

const emit = defineEmits<{
  'update:modelValue': [value: DateRange]
}>()

const triggerEl = ref<HTMLElement>()
const dropdownEl = ref<HTMLElement>()
const open = ref(false)
const hoverDate = ref<Date | null>(null)
const selecting = ref(false)
const pendingStart = ref<Date | null>(null)
const positioned = ref(false)
const dropdownStyle = ref<Record<string, string>>({
  position: 'fixed',
  top: '-9999px',
  left: '-9999px',
  zIndex: '9999',
})

const presets = [
  { label: 'Last 7 days', days: 7 },
  { label: 'Last 14 days', days: 14 },
  { label: 'Last 30 days', days: 30 },
  { label: 'Last 3 months', months: 3 },
  { label: 'Last 6 months', months: 6 },
  { label: 'Last year', months: 12 },
]

const viewMonth = ref(startOfMonth(subMonths(new Date(), 1)))

const displayRange = computed(() => {
  const { start, end } = props.modelValue
  return `${format(start, 'MMM d, yyyy')} – ${format(end, 'MMM d, yyyy')}`
})

function positionDropdown() {
  if (!triggerEl.value || !dropdownEl.value) return
  const trigger = triggerEl.value.getBoundingClientRect()
  const dd = dropdownEl.value.getBoundingClientRect()
  const vw = window.innerWidth
  const vh = window.innerHeight
  const gap = 4

  let top = trigger.bottom + gap
  let left = trigger.left

  if (left + dd.width > vw - 8) {
    left = Math.max(8, vw - dd.width - 8)
  }

  if (top + dd.height > vh - 8) {
    const above = trigger.top - gap - dd.height
    if (above >= 8) {
      top = above
    } else {
      top = Math.max(8, vh - dd.height - 8)
    }
  }

  dropdownStyle.value = {
    position: 'fixed',
    top: `${top}px`,
    left: `${left}px`,
    zIndex: '9999',
  }
}

function toggle() {
  open.value = !open.value
}

function onClickOutside(e: MouseEvent) {
  const target = e.target as Node
  if (
    triggerEl.value?.contains(target) ||
    dropdownEl.value?.contains(target)
  ) return
  open.value = false
}

onMounted(() => {
  document.addEventListener('mousedown', onClickOutside)
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onClickOutside)
})

function isPresetActive(preset: { days?: number; months?: number }) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  let presetStart: Date
  if (preset.days) {
    presetStart = subDays(today, preset.days)
  } else {
    presetStart = subMonths(today, preset.months!)
  }
  return isSameDay(props.modelValue.start, presetStart) && isSameDay(props.modelValue.end, today)
}

function selectPreset(preset: { days?: number; months?: number }) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  let start: Date
  if (preset.days) {
    start = subDays(today, preset.days)
  } else {
    start = subMonths(today, preset.months!)
  }
  emit('update:modelValue', { start, end: today })
  selecting.value = false
  pendingStart.value = null
  open.value = false
}

const months = computed(() => {
  const m1 = viewMonth.value
  const m2 = addMonths(m1, 1)
  return [buildMonth(m1), buildMonth(m2)]
})

function buildMonth(date: Date) {
  const monthStart = startOfMonth(date)
  const monthEnd = endOfMonth(date)
  const calStart = startOfWeek(monthStart)
  const calEnd = endOfWeek(monthEnd)
  const days: Date[] = []
  let d = calStart
  while (d <= calEnd) {
    days.push(d)
    d = addDays(d, 1)
  }
  const weeks: Date[][] = []
  for (let i = 0; i < days.length; i += 7) {
    weeks.push(days.slice(i, i + 7))
  }
  return { label: format(monthStart, 'MMMM yyyy'), month: monthStart, weeks }
}

function prevMonth() {
  viewMonth.value = subMonths(viewMonth.value, 1)
}

function nextMonth() {
  viewMonth.value = addMonths(viewMonth.value, 1)
}

function onDayClick(day: Date) {
  if (!selecting.value || !pendingStart.value) {
    pendingStart.value = day
    selecting.value = true
  } else {
    let start = pendingStart.value
    let end = day
    if (isAfter(start, end)) {
      ;[start, end] = [end, start]
    }
    emit('update:modelValue', { start, end })
    selecting.value = false
    pendingStart.value = null
    open.value = false
  }
}

function onDayHover(day: Date) {
  if (selecting.value) {
    hoverDate.value = day
  }
}

function isInRange(day: Date) {
  if (selecting.value && pendingStart.value && hoverDate.value) {
    let s = pendingStart.value
    let e = hoverDate.value
    if (isAfter(s, e)) [s, e] = [e, s]
    return isWithinInterval(day, { start: s, end: e })
  }
  const { start, end } = props.modelValue
  if (!start || !end) return false
  return isWithinInterval(day, { start, end })
}

function isRangeStart(day: Date) {
  if (selecting.value && pendingStart.value) {
    return isSameDay(day, pendingStart.value)
  }
  return isSameDay(day, props.modelValue.start)
}

function isRangeEnd(day: Date) {
  if (selecting.value && hoverDate.value && pendingStart.value) {
    const s = pendingStart.value
    const e = hoverDate.value
    return isSameDay(day, isAfter(s, e) ? s : e)
  }
  return isSameDay(day, props.modelValue.end)
}

const weekDays = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa']

watch(open, (v) => {
  if (v) {
    positioned.value = false
    dropdownStyle.value = { position: 'fixed', top: '-9999px', left: '-9999px', zIndex: '9999' }
    selecting.value = false
    pendingStart.value = null
    hoverDate.value = null
    viewMonth.value = startOfMonth(subMonths(props.modelValue.end, 1))
  }
})

watch(dropdownEl, (el) => {
  if (el) {
    requestAnimationFrame(() => {
      positionDropdown()
      positioned.value = true
    })
  }
})
</script>

<template>
  <div ref="triggerEl" class="drp-trigger">
    <Button icon="calendar" @click="toggle">
      {{ displayRange }}
      <Icon name="chevronDown" :size="12" color="var(--fg-3)" />
    </Button>
  </div>

  <Teleport to="body">
    <div v-if="open" ref="dropdownEl" class="drp-dropdown" :class="{ 'drp-measuring': !positioned }" :style="dropdownStyle">
        <div class="drp">
          <div class="drp-presets">
            <button
              v-for="preset in presets"
              :key="preset.label"
              class="drp-preset"
              :class="{ active: isPresetActive(preset) }"
              @click="selectPreset(preset)"
            >
              {{ preset.label }}
            </button>
          </div>
          <div class="drp-calendars">
            <div class="drp-nav">
              <button class="drp-nav-btn" @click="prevMonth">
                <Icon name="chevronLeft" :size="14" color="var(--fg-2)" />
              </button>
              <div class="drp-month-labels">
                <span v-for="m in months" :key="m.label" class="drp-month-label">{{ m.label }}</span>
              </div>
              <button class="drp-nav-btn" @click="nextMonth">
                <Icon name="chevron" :size="14" color="var(--fg-2)" />
              </button>
            </div>
            <div class="drp-grids">
              <div v-for="m in months" :key="m.label" class="drp-grid">
                <div class="drp-weekdays">
                  <span v-for="wd in weekDays" :key="wd" class="drp-wd">{{ wd }}</span>
                </div>
                <div v-for="(week, wi) in m.weeks" :key="wi" class="drp-week">
                  <button
                    v-for="(day, di) in week"
                    :key="di"
                    class="drp-day"
                    :class="{
                      outside: !isSameMonth(day, m.month),
                      'in-range': isInRange(day),
                      'range-start': isRangeStart(day),
                      'range-end': isRangeEnd(day),
                      today: isSameDay(day, new Date()),
                    }"
                    @click="onDayClick(day)"
                    @mouseenter="onDayHover(day)"
                  >
                    {{ day.getDate() }}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
    </div>
  </Teleport>
</template>

<style scoped>
.drp-trigger {
  display: inline-flex;
}
</style>

<style>
.drp-dropdown {
  background: var(--bg-1, #1a1c22);
  border: 1px solid var(--line, #2a2d35);
  border-radius: var(--r-md, 10px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.4);
}

.drp-dropdown.drp-measuring {
  visibility: hidden;
  pointer-events: none;
}

.drp {
  display: flex;
  min-width: 540px;
}

.drp-presets {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--line, #2a2d35);
  padding: 4px 0;
  min-width: 130px;
}

.drp-preset {
  padding: 7px 14px;
  font-size: 12.5px;
  color: var(--fg-2, #999);
  text-align: left;
  background: none;
  border: none;
  cursor: pointer;
  white-space: nowrap;
}

.drp-preset:hover {
  background: var(--bg-3, #252830);
  color: var(--fg-1, #ccc);
}

.drp-preset.active {
  background: var(--bg-3, #252830);
  color: var(--fg-0, #fff);
}

.drp-calendars {
  flex: 1;
  padding: 10px;
}

.drp-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.drp-nav-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: none;
  border: 1px solid var(--line-2, #32353e);
  border-radius: var(--r-sm, 6px);
  cursor: pointer;
  color: var(--fg-2);
}

.drp-nav-btn:hover {
  background: var(--bg-3, #252830);
}

.drp-month-labels {
  display: flex;
  gap: 24px;
}

.drp-month-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-1, #ccc);
}

.drp-grids {
  display: flex;
  gap: 16px;
}

.drp-grid {
  flex: 1;
}

.drp-weekdays {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  margin-bottom: 4px;
}

.drp-wd {
  font-size: 10.5px;
  font-weight: 600;
  color: var(--fg-3, #6c7388);
  text-align: center;
  padding: 4px 0;
}

.drp-week {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
}

.drp-day {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  aspect-ratio: 1;
  font-size: 12px;
  color: var(--fg-1, #ccc);
  background: none;
  border: none;
  border-radius: 0;
  cursor: pointer;
  position: relative;
}

.drp-day:hover:not(.outside) {
  background: var(--bg-3, #252830);
  color: var(--fg-0, #fff);
}

.drp-day.outside {
  color: var(--fg-3, #6c7388);
  opacity: 0.4;
}

.drp-day.today {
  font-weight: 700;
  color: var(--brand-2, #5ec5ff);
}

.drp-day.in-range {
  background: color-mix(in oklch, var(--brand-2, #5ec5ff) 12%, transparent);
}

.drp-day.range-start,
.drp-day.range-end {
  background: var(--brand-2, #5ec5ff);
  color: var(--bg-0, #13151a);
  font-weight: 600;
  border-radius: var(--r-sm, 6px);
}

.drp-day.range-start.today,
.drp-day.range-end.today {
  color: var(--bg-0, #13151a);
}

@media (max-width: 600px) {
  .drp {
    flex-direction: column;
    min-width: 280px;
  }

  .drp-presets {
    flex-direction: row;
    flex-wrap: wrap;
    border-right: none;
    border-bottom: 1px solid var(--line, #2a2d35);
    padding: 4px;
  }

  .drp-grids {
    flex-direction: column;
  }
}
</style>
