<script setup lang="ts">
import { addDays, addMonths, startOfWeek } from '~/utils/calendar'
import { useCalendar } from '~/composables/useCalendar'
import { useCalendarEvents } from '~/composables/useCalendarEvents'
import type { MonthEvent } from '~/components/calendar/CalendarMonthView.vue'
import type { EventDraft, EventScope, EventParticipant } from '~/components/calendar/CalendarEventModal.vue'
import type { CalendarItem } from '~/components/calendar/CalendarSidebar.vue'

const { accent } = useCurrentSubsystem()
const toast = useToast()
const gql = useGraphQL()

const ADD_PARTICIPANT_GQL = `
  mutation AddParticipant($input: EventParticipantInput!) {
    calendars { addParticipant(input: $input) { profileId } }
  }
`
const REMOVE_PARTICIPANT_GQL = `
  mutation RemoveParticipant($eventId: UUID!, $profileId: UUID!) {
    calendars { removeParticipant(eventId: $eventId, profileId: $profileId) }
  }
`

async function saveParticipants(eventId: string, current: EventParticipant[], originalIds: string[]) {
  const originalSet = new Set(originalIds)
  const currentIds = new Set(current.map(p => p.profileId))

  const toAdd = current.filter(p => !originalSet.has(p.profileId))
  const toRemove = originalIds.filter(id => !currentIds.has(id))

  for (const p of toAdd) {
    try {
      await gql.mutation(ADD_PARTICIPANT_GQL, { input: { eventId, profileId: p.profileId, role: p.role || 'attendee', status: p.status || 'pending' } })
    } catch (e: unknown) {
      toast.add({ title: 'Failed to add participant', description: e instanceof Error ? e.message : undefined, color: 'error' })
    }
  }
  for (const profileId of toRemove) {
    try {
      await gql.mutation(REMOVE_PARTICIPANT_GQL, { eventId, profileId })
    } catch (e: unknown) {
      toast.add({ title: 'Failed to remove participant', description: e instanceof Error ? e.message : undefined, color: 'error' })
    }
  }
}


const {
  calendars,
  visibleCalendarIds,
  allCalendarRefs,
  toggleCalendar,
  addCalendar,
  editCalendar,
  deleteCalendar,
} = useCalendar()

const viewMode = ref<'month' | 'week' | 'day'>('month')
const currentDate = ref(new Date())

const rangeFrom = computed(() => {
  if (viewMode.value === 'month') {
    const first = new Date(currentDate.value.getFullYear(), currentDate.value.getMonth(), 1)
    return addDays(startOfWeek(first), -7).toISOString()
  }
  if (viewMode.value === 'week') {
    return addDays(startOfWeek(currentDate.value), -1).toISOString()
  }
  const d = new Date(currentDate.value)
  d.setHours(0, 0, 0, 0)
  return d.toISOString()
})

const rangeTo = computed(() => {
  if (viewMode.value === 'month') {
    const first = new Date(currentDate.value.getFullYear(), currentDate.value.getMonth(), 1)
    return addDays(startOfWeek(first), 49).toISOString()
  }
  if (viewMode.value === 'week') {
    return addDays(startOfWeek(currentDate.value), 8).toISOString()
  }
  const d = new Date(currentDate.value)
  d.setHours(23, 59, 59, 999)
  return d.toISOString()
})

// Events are fetched for every calendar across the visible date range.
// The range computeds are passed directly so navigating to another
// month/week/day reactively re-fetches; calendar on/off visibility is
// applied client-side in `mappedEvents` below, so toggling is instant.
const {
  occurrences,
  addEvent,
  editEvent,
  deleteEvent,
  editOccurrence,
  cancelOccurrence,
  splitSeriesAt,
  moveEvent,
  syntheticEvents,
} = useCalendarEvents(allCalendarRefs, rangeFrom, rangeTo)

const SYNTHETIC_COLORS = {
  CAMPAIGN: '#ff7ac6',
  SCHEDULED_JOB: '#ffb547',
  SCHEDULED_PUBLISH: '#34d99a',
} as const

const mappedEvents = computed<MonthEvent[]>(() => {
  const userEvents = occurrences.value
    .filter((occ) => visibleCalendarIds.value.has(occ.event.calendar.metadataId))
    .map((occ) => ({
      id: occ.event.id,
      title: occ.event.title,
      allDay: occ.event.allDay,
      startsAt: occ.startsAt,
      endsAt: occ.endsAt,
      source: 'USER',
      calendarId: occ.event.calendar.metadataId,
    }))

  const synthetic = syntheticEvents.value
    .filter((evt) => visibleCalendarIds.value.has(`__synthetic_${evt.source}`))
    .map((evt) => ({
      id: evt.id,
      title: evt.title,
      allDay: false,
      startsAt: evt.startsAt,
      endsAt: evt.endsAt,
      source: evt.source,
      calendarId: `__synthetic_${evt.source}`,
    }))

  return [...userEvents, ...synthetic]
})

const calendarColors = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const cal of calendars.value) {
    map[cal.metadataId] = cal.color
  }
  map['__synthetic_CAMPAIGN'] = SYNTHETIC_COLORS.CAMPAIGN
  map['__synthetic_SCHEDULED_JOB'] = SYNTHETIC_COLORS.SCHEDULED_JOB
  map['__synthetic_SCHEDULED_PUBLISH'] = SYNTHETIC_COLORS.SCHEDULED_PUBLISH
  return map
})

const sidebarCalendars = computed<CalendarItem[]>(() => [
  ...calendars.value.map((c) => ({
    key: c.metadataId,
    metadataId: c.metadataId,
    version: c.version,
    name: c.metadata.name,
    color: c.color,
    canEdit: true,
  })),
  { key: '__synthetic_CAMPAIGN', metadataId: '', version: 0, name: 'Campaigns', color: SYNTHETIC_COLORS.CAMPAIGN, canEdit: false },
  { key: '__synthetic_SCHEDULED_JOB', metadataId: '', version: 0, name: 'Scheduled Jobs', color: SYNTHETIC_COLORS.SCHEDULED_JOB, canEdit: false },
  { key: '__synthetic_SCHEDULED_PUBLISH', metadataId: '', version: 0, name: 'Publishes', color: SYNTHETIC_COLORS.SCHEDULED_PUBLISH, canEdit: false },
])

// Event modal state
const eventModalOpen = ref(false)
const eventDraft = ref<EventDraft | null>(null)
const eventSaving = ref(false)
const eventDeleting = ref(false)

// Calendar edit modal state
const calEditOpen = ref(false)
const calEditName = ref('')
const calEditColor = ref('#3a86ff')
const calEditDescription = ref('')
const calEditRef = ref<{ metadataId: string; version: number } | null>(null)
const calEditSaving = ref(false)

function navigatePrev() {
  if (viewMode.value === 'month') currentDate.value = addMonths(currentDate.value, -1)
  else if (viewMode.value === 'week') currentDate.value = addDays(currentDate.value, -7)
  else currentDate.value = addDays(currentDate.value, -1)
}

function navigateNext() {
  if (viewMode.value === 'month') currentDate.value = addMonths(currentDate.value, 1)
  else if (viewMode.value === 'week') currentDate.value = addDays(currentDate.value, 7)
  else currentDate.value = addDays(currentDate.value, 1)
}

function navigateToday() {
  currentDate.value = new Date()
}

const headerTitle = computed(() => {
  const d = currentDate.value
  const months = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']
  if (viewMode.value === 'month') return `${months[d.getMonth()]} ${d.getFullYear()}`
  if (viewMode.value === 'week') {
    const ws = startOfWeek(d)
    const we = addDays(ws, 6)
    return `${months[ws.getMonth()]} ${ws.getDate()} – ${we.getDate()}, ${we.getFullYear()}`
  }
  return `${months[d.getMonth()]} ${d.getDate()}, ${d.getFullYear()}`
})

function openNewEvent(day: Date, hour?: number) {
  const defaultCal = calendars.value[0]
  if (!defaultCal) { toast.add({ title: 'Create a calendar first', color: 'error' }); return }

  const startsAt = new Date(day)
  if (hour != null) startsAt.setHours(hour, 0, 0, 0)
  else startsAt.setHours(9, 0, 0, 0)

  const endsAt = new Date(startsAt)
  endsAt.setHours(startsAt.getHours() + 1)

  eventDraft.value = {
    metadataId: defaultCal.metadataId,
    version: defaultCal.version,
    title: '',
    description: '',
    location: '',
    allDay: hour == null,
    startsAt: startsAt.toISOString(),
    endsAt: endsAt.toISOString(),
    rrule: null,
  }
  eventModalOpen.value = true
}

// Calendars load async, so the `?new=1` deep link (command palette) defers
// opening the event dialog until the default calendar is available.
useCreateFromQuery(() => {
  if (calendars.value.length > 0) {
    openNewEvent(new Date())
    return
  }
  const stop = watch(calendars, (cals) => {
    if (cals.length === 0) return
    stop()
    openNewEvent(new Date())
  })
})

function openEditEvent(event: MonthEvent) {
  const occ = occurrences.value.find((o) => o.event.id === event.id)
  if (!occ) return
  const evt = occ.event

  eventDraft.value = {
    id: evt.id,
    metadataId: evt.calendar.metadataId,
    version: evt.calendar.version,
    title: evt.title,
    description: evt.description,
    location: evt.location,
    allDay: evt.allDay,
    startsAt: occ.startsAt,
    endsAt: occ.endsAt,
    rrule: evt.rrule,
    isRecurring: occ.isRecurring,
    isException: occ.isException,
    recurrenceId: occ.recurrenceId,
  }
  eventModalOpen.value = true
}

async function onSaveEvent(draft: EventDraft, scope: EventScope) {
  eventSaving.value = true
  let eventId: string | undefined = draft.id
  try {
    const input = {
      metadataId: draft.metadataId,
      version: draft.version,
      title: draft.title,
      description: draft.description,
      location: draft.location,
      allDay: draft.allDay,
      startsAt: draft.startsAt,
      endsAt: draft.endsAt,
      rrule: draft.rrule,
    }


    if (!eventId) {
      eventId = await addEvent(input) ?? undefined
    } else if (scope === 'this' && draft.isRecurring && draft.recurrenceId) {
      await editOccurrence(draft.id!, draft.recurrenceId, {
        title: draft.title,
        description: draft.description,
        location: draft.location,
        allDay: draft.allDay,
        startsAt: draft.startsAt,
        endsAt: draft.endsAt,
      })
    } else if (scope === 'following' && draft.isRecurring && draft.recurrenceId) {
      await splitSeriesAt(draft.id!, draft.recurrenceId, input)
    } else {
      await editEvent(draft.id!, input)
    }
  } catch {
    // error already toasted by composable
  }

  // Save participant changes
  if (eventId && draft.participants) {
    await saveParticipants(eventId, draft.participants, draft.originalParticipantIds ?? [])
  }

  eventSaving.value = false
  eventModalOpen.value = false
}

async function onRemoveEvent(draft: EventDraft, scope: EventScope) {
  if (!draft.id) return
  eventDeleting.value = true
  try {
    if (scope === 'this' && draft.isRecurring && draft.recurrenceId) {
      await cancelOccurrence(draft.id, draft.recurrenceId)
    } else {
      await deleteEvent(draft.id)
    }
  } catch {
    // error already toasted
  }
  eventDeleting.value = false
  eventModalOpen.value = false
}

async function onMoveEvent(eventId: string, startsAt: string, endsAt: string) {
  await moveEvent(eventId, startsAt, endsAt)
}

// Calendar management
function onAddCalendar() {
  calEditRef.value = null
  calEditName.value = ''
  calEditColor.value = '#3a86ff'
  calEditDescription.value = ''
  calEditOpen.value = true
}

function onEditCalendar(cal: CalendarItem) {
  calEditRef.value = { metadataId: cal.metadataId, version: cal.version }
  calEditName.value = cal.name
  calEditColor.value = cal.color
  calEditDescription.value = ''
  calEditOpen.value = true
}

async function onSaveCalendar() {
  calEditSaving.value = true
  try {
    if (calEditRef.value) {
      await editCalendar(calEditRef.value, { color: calEditColor.value, description: calEditDescription.value }, calEditName.value)
    } else {
      await addCalendar(calEditName.value, calEditColor.value, calEditDescription.value)
    }
  } catch {
    // error already toasted by composable
  }
  calEditSaving.value = false
  calEditOpen.value = false
}

const calDeleteConfirmOpen = ref(false)
const calDeleting = ref(false)

async function onDeleteCalendar() {
  if (!calEditRef.value) return
  calDeleting.value = true
  try {
    await deleteCalendar(calEditRef.value.metadataId)
  } catch {
    // error already toasted
  }
  calDeleting.value = false
  calDeleteConfirmOpen.value = false
  calEditOpen.value = false
}

</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Calendar')"
        :title="headerTitle"
      >
        <template #actions>
          <div class="nav-group">
            <button class="nav-btn" @click="navigatePrev"><Icon name="chevronLeft" :size="14" /></button>
            <button class="nav-btn nav-btn--today" @click="navigateToday">Today</button>
            <button class="nav-btn" @click="navigateNext"><Icon name="chevron" :size="14" /></button>
          </div>

          <div class="view-toggle">
            <button class="view-btn" :class="{ 'view-btn--active': viewMode === 'month' }" @click="viewMode = 'month'">Month</button>
            <button class="view-btn" :class="{ 'view-btn--active': viewMode === 'week' }" @click="viewMode = 'week'">Week</button>
            <button class="view-btn" :class="{ 'view-btn--active': viewMode === 'day' }" @click="viewMode = 'day'">Day</button>
          </div>
        </template>
      </PageHeader>
    </template>

    <div class="calendar-layout">
      <CalendarSidebar
        :calendars="sidebarCalendars"
        :visible="visibleCalendarIds"
        @toggle="toggleCalendar"
        @add="onAddCalendar"
        @edit="onEditCalendar"
      />

      <div class="calendar-main">
        <CalendarMonthView
          v-if="viewMode === 'month'"
          :current="currentDate"
          :events="mappedEvents"
          :calendar-colors="calendarColors"
          @select-day="(d: Date) => { currentDate = d; viewMode = 'day' }"
          @new-event="(d: Date) => openNewEvent(d)"
          @select-event="openEditEvent"
          @move-event="onMoveEvent"
        />

        <CalendarWeekView
          v-else-if="viewMode === 'week'"
          :current="startOfWeek(currentDate)"
          :events="mappedEvents"
          :calendar-colors="calendarColors"
          @select-slot="(d: Date, h: number) => openNewEvent(d, h)"
          @select-event="openEditEvent"
          @move-event="onMoveEvent"
        />

        <CalendarDayView
          v-else
          :current="currentDate"
          :events="mappedEvents"
          :calendar-colors="calendarColors"
          @select-slot="(d: Date, h: number) => openNewEvent(d, h)"
          @select-event="openEditEvent"
          @move-event="onMoveEvent"
        />
      </div>
    </div>

    <!-- Event modal -->
    <CalendarEventModal
      :open="eventModalOpen"
      :event="eventDraft"
      :saving="eventSaving"
      :deleting="eventDeleting"
      @save="onSaveEvent"
      @remove="onRemoveEvent"
      @close="eventModalOpen = false"
    />

    <!-- Calendar edit modal -->
    <Teleport to="body">
      <div v-if="calEditOpen" class="cal-modal-overlay" @click.self="calEditOpen = false">
        <div class="cal-modal">
          <h3 class="cal-modal-title">{{ calEditRef ? 'Edit Calendar' : 'New Calendar' }}</h3>
          <div class="cal-modal-body">
            <div class="field">
              <label class="field-label">Name</label>
              <input
                v-model="calEditName"
                class="field-input"
                placeholder="Calendar name"
                autofocus>
            </div>
            <div class="field">
              <label class="field-label">Color</label>
              <input v-model="calEditColor" type="color" class="field-color">
            </div>
            <div class="field">
              <label class="field-label">Description</label>
              <input v-model="calEditDescription" class="field-input" placeholder="Optional">
            </div>
          </div>
          <div class="cal-modal-footer">
            <button
              v-if="calEditRef"
              class="cal-btn cal-btn--danger"
              :disabled="calDeleting"
              @click="calDeleteConfirmOpen = true">
              Delete
            </button>
            <span style="flex: 1" />
            <button class="cal-btn" @click="calEditOpen = false">Cancel</button>
            <button class="cal-btn cal-btn--primary" :disabled="calEditSaving || !calEditName.trim()" @click="onSaveCalendar">
              {{ calEditSaving ? 'Saving…' : calEditRef ? 'Save' : 'Create' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- Calendar delete confirmation -->
    <ConfirmModal
      v-if="calDeleteConfirmOpen"
      title="Delete Calendar"
      :subtitle="`Delete '${calEditName}'? All events in this calendar will be removed.`"
      :loading="calDeleting"
      @close="calDeleteConfirmOpen = false"
      @confirm="onDeleteCalendar"
    />
  </PageShell>
</template>

<style scoped>
.calendar-layout {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.calendar-main {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  padding: 14px;
  display: flex;
  flex-direction: column;
}

.nav-group {
  display: flex;
  align-items: center;
  gap: 2px;
}

.nav-btn {
  padding: 4px 8px;
  background: none;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-2);
  cursor: pointer;
  font-size: 12px;
}

.nav-btn:hover { background: var(--bg-2); color: var(--fg-0); }
.nav-btn--today { font-weight: 500; }

.view-toggle {
  display: flex;
  gap: 2px;
  background: var(--bg-2);
  border-radius: var(--r-sm);
  padding: 2px;
}

.view-btn {
  padding: 4px 10px;
  font-size: 12px;
  background: none;
  border: none;
  border-radius: var(--r-xs);
  color: var(--fg-3);
  cursor: pointer;
}

.view-btn--active {
  background: var(--bg-0);
  color: var(--fg-0);
  font-weight: 500;
}

.field { display: flex; flex-direction: column; gap: 4px; }
.field-label { font-size: 12px; font-weight: 500; color: var(--fg-3); }
.field-input {
  padding: 7px 10px; font-size: 13px; background: var(--bg-2);
  border: 1px solid var(--line); border-radius: var(--r-sm); color: var(--fg-1); outline: none;
}
.field-input:focus { border-color: var(--brand-2); }
.field-color { width: 48px; height: 32px; border: 1px solid var(--line); border-radius: var(--r-sm); cursor: pointer; }

.cal-modal-overlay {
  position: fixed; inset: 0; z-index: 9000;
  background: rgba(0, 0, 0, 0.5); display: flex;
  align-items: center; justify-content: center;
}
.cal-modal {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: var(--r-lg);
  padding: 24px; max-width: 400px; width: 90%;
}
.cal-modal-title { font-size: 15px; font-weight: 600; color: var(--fg-0); margin: 0 0 16px; }
.cal-modal-body { display: flex; flex-direction: column; gap: 12px; }
.cal-modal-footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
.cal-btn { padding: 6px 14px; font-size: 13px; border-radius: var(--r-sm); border: none; cursor: pointer; background: var(--bg-2); color: var(--fg-2); }
.cal-btn--primary { background: var(--brand-2); color: #fff; }
.cal-btn--danger { background: none; color: var(--err); }
.cal-btn--danger:hover { background: var(--bg-3); }
.cal-btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
