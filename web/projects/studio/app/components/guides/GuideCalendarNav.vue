<script setup lang="ts">
import { dayKey, localDayKey, stepDayKey, stepPagesByDay, type DatedGuideStep } from '~/utils/guideCalendar'

const props = defineProps<{
  steps: DatedGuideStep[]
  currentPage: number
}>()

const emit = defineEmits<{ select: [page: number] }>()

const open = ref(false)

const pagesByDay = computed(() => stepPagesByDay(props.steps))
const sortedDayKeys = computed(() => [...pagesByDay.value.keys()].sort())
const hasDates = computed(() => sortedDayKeys.value.length > 0)

// Re-read on every open so a tab left overnight doesn't keep yesterday's
// "today" highlighted.
const todayKey = ref(localDayKey())

const selectedKey = computed(() => {
  if (props.currentPage < 2) return null
  return stepDayKey(props.steps[props.currentPage - 2]?.date)
})

function parseKey(key: string): { year: number, month: number } {
  const [year, month] = key.split('-').map(Number)
  return { year: year!, month: month! }
}

function monthIndexOf(key: string): number {
  const { year, month } = parseKey(key)
  return year * 12 + (month - 1)
}

function clampToStepRange(key: string): string {
  const keys = sortedDayKeys.value
  if (!keys.length) return key
  if (key < keys[0]!) return keys[0]!
  if (key > keys[keys.length - 1]!) return keys[keys.length - 1]!
  return key
}

// The month on display, tracked as year + 0-based month like Date. Starts on
// the active step's month (or today's, clamped into the guide's range) and
// snaps back to it each time the popover opens.
function anchorKey(): string {
  return selectedKey.value ?? clampToStepRange(todayKey.value)
}

const initialAnchor = parseKey(anchorKey())
const viewYear = ref(initialAnchor.year)
const viewMonth = ref(initialAnchor.month - 1)

watch(open, (isOpen) => {
  if (!isOpen) return
  todayKey.value = localDayKey()
  const { year, month } = parseKey(anchorKey())
  viewYear.value = year
  viewMonth.value = month - 1
})

const viewIndex = computed(() => viewYear.value * 12 + viewMonth.value)
const minMonthIndex = computed(() => hasDates.value ? monthIndexOf(sortedDayKeys.value[0]!) : null)
const maxMonthIndex = computed(() => hasDates.value ? monthIndexOf(sortedDayKeys.value[sortedDayKeys.value.length - 1]!) : null)
const canPrev = computed(() => minMonthIndex.value !== null && viewIndex.value > minMonthIndex.value)
const canNext = computed(() => maxMonthIndex.value !== null && viewIndex.value < maxMonthIndex.value)

function shiftMonth(offset: -1 | 1) {
  const index = viewIndex.value + offset
  viewYear.value = Math.floor(index / 12)
  viewMonth.value = ((index % 12) + 12) % 12
}

const monthLabel = computed(() =>
  new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(Date.UTC(viewYear.value, viewMonth.value, 1))))

const weekDays = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa']

interface DayCell {
  key: string
  day: number
  inMonth: boolean
  page: number | null
  isToday: boolean
  isSelected: boolean
}

const weeks = computed<DayCell[][]>(() => {
  const year = viewYear.value
  const month = viewMonth.value
  const firstDow = new Date(Date.UTC(year, month, 1)).getUTCDay()
  const daysInMonth = new Date(Date.UTC(year, month + 1, 0)).getUTCDate()
  const rowCount = Math.ceil((firstDow + daysInMonth) / 7)
  const rows: DayCell[][] = []
  for (let row = 0; row < rowCount; row++) {
    const cells: DayCell[] = []
    for (let col = 0; col < 7; col++) {
      const date = new Date(Date.UTC(year, month, row * 7 + col - firstDow + 1))
      const key = dayKey(date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate())
      cells.push({
        key,
        day: date.getUTCDate(),
        inMonth: date.getUTCMonth() === month,
        page: pagesByDay.value.get(key) ?? null,
        isToday: key === todayKey.value,
        isSelected: key === selectedKey.value,
      })
    }
    rows.push(cells)
  }
  return rows
})

const todayPage = computed(() => pagesByDay.value.get(todayKey.value) ?? null)

function selectCell(cell: DayCell) {
  if (!cell.page) return
  emit('select', cell.page)
  open.value = false
}

function selectToday() {
  if (todayPage.value === null) return
  emit('select', todayPage.value)
  open.value = false
}
</script>

<template>
  <Popover v-if="hasDates" v-model="open" placement="bottom-start">
    <template #trigger>
      <button class="guide-cal-trigger" type="button" title="Go to date">
        <Icon name="calendar" :size="14" />
      </button>
    </template>
    <div class="guide-cal">
      <div class="guide-cal-head">
        <button
          class="guide-cal-nav"
          type="button"
          title="Previous month"
          :disabled="!canPrev"
          @click="shiftMonth(-1)">
          <Icon name="chevronLeft" :size="14" />
        </button>
        <span class="guide-cal-month">{{ monthLabel }}</span>
        <button
          class="guide-cal-nav"
          type="button"
          title="Next month"
          :disabled="!canNext"
          @click="shiftMonth(1)">
          <Icon name="chevron" :size="14" />
        </button>
      </div>
      <div class="guide-cal-grid">
        <span v-for="label in weekDays" :key="label" class="guide-cal-dow">{{ label }}</span>
        <template v-for="(row, rowIndex) in weeks" :key="rowIndex">
          <button
            v-for="cell in row"
            :key="cell.key"
            type="button"
            class="guide-cal-day"
            :class="{ outside: !cell.inMonth, today: cell.isToday, selected: cell.isSelected }"
            :disabled="!cell.page"
            @click="selectCell(cell)"
          >{{ cell.day }}</button>
        </template>
      </div>
      <button
        class="guide-cal-today"
        type="button"
        :disabled="todayPage === null"
        @click="selectToday">Today</button>
    </div>
  </Popover>
</template>

<style scoped>
.guide-cal-trigger {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-sm);
  color: var(--fg-2);
  border: 1px solid var(--line);
  background: var(--bg-1);
  transition: all 0.15s;
}

.guide-cal-trigger:hover {
  background: var(--bg-3);
  color: var(--fg-0);
}

.guide-cal {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.guide-cal-head {
  display: flex;
  align-items: center;
  gap: 4px;
}

.guide-cal-month {
  flex: 1;
  text-align: center;
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-0);
}

.guide-cal-nav {
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-sm);
  color: var(--fg-2);
  transition: all 0.15s;
}

.guide-cal-nav:hover:not(:disabled) {
  background: var(--bg-2);
  color: var(--fg-0);
}

.guide-cal-nav:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.guide-cal-grid {
  display: grid;
  grid-template-columns: repeat(7, 30px);
  gap: 2px;
}

.guide-cal-dow {
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 500;
  color: var(--fg-3);
}

.guide-cal-day {
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12.5px;
  border-radius: var(--r-sm);
  color: var(--fg-1);
  transition: background 0.12s, color 0.12s;
}

.guide-cal-day:disabled {
  color: var(--fg-3);
  opacity: 0.45;
  cursor: default;
}

.guide-cal-day.outside {
  opacity: 0.25;
}

.guide-cal-day:hover:not(:disabled):not(.selected) {
  background: var(--bg-2);
  color: var(--fg-0);
}

/* Today gets a ring so it stays visible even when another day is selected. */
.guide-cal-day.today {
  box-shadow: inset 0 0 0 1px var(--brand-2);
}

.guide-cal-day.selected {
  background: var(--brand-2);
  color: #fff;
}

.guide-cal-today {
  align-self: center;
  padding: 4px 12px;
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: 500;
  color: var(--brand-2);
  transition: background 0.12s;
}

.guide-cal-today:hover:not(:disabled) {
  background: color-mix(in oklch, var(--brand-2) 12%, transparent);
}

.guide-cal-today:disabled {
  color: var(--fg-3);
  cursor: default;
}
</style>
