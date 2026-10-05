<script lang="ts" setup>
import type { Metadata } from '~/types/graphql'
import type { TimeEventInput } from '~/composables/useTimeEvents'
import { useTimeEvents } from '~/composables/useTimeEvents'
import { usePdfTimelineImport } from '~/composables/usePdfTimelineImport'
import { useCsvTimelineImport } from '~/composables/useCsvTimelineImport'
import { formatMs } from '~/utils/timeline'

const props = defineProps<{
  metadata: Metadata
  currentTimeMs?: number
  durationMs?: number
  onSeek?: (_ms: number) => void
}>()

const toast = useToast()
const gql = useGraphQL()

const metadataId = computed(() => props.metadata.id)
const metadataVersion = computed(() => (props.metadata as unknown as { version?: number }).version ?? 1)

const {
  eventTypes,
  events,
  visibleTypeGroups,
  selectedEvent,
  selectedEventId,
  activeTypeFilter,
  currentTimeMs: timeEventsCurrentTimeMs,
  durationMs: timeEventsDurationMs,
  addEvent,
  editEvent,
  deleteEvent,
  deleteEvents,
  editMetadataRelationshipAttributes,
  refresh,
} = useTimeEvents(metadataId, metadataVersion)

const {
  progress: pdfProgress,
  importPdf,
} = usePdfTimelineImport(metadataId, metadataVersion, timeEventsDurationMs, eventTypes, activeTypeFilter)

const {
  progress: csvProgress,
  importCsv,
} = useCsvTimelineImport(events, eventTypes, addEvent, editEvent, refresh, activeTypeFilter)

const hasExternalPlayer = computed(() => props.onSeek != null)

const playerRef = ref<{ seek: (_ms: number) => void } | null>(null)
const viewMode = ref<'timeline' | 'list'>('timeline')

watch(() => props.currentTimeMs, (v) => {
  if (v != null && hasExternalPlayer.value) timeEventsCurrentTimeMs.value = v
})

watch(() => props.durationMs, (v) => {
  if (v != null && hasExternalPlayer.value) timeEventsDurationMs.value = v
})
const pixelsPerMs = ref(0.05)
const rowSelection = ref<Record<string, boolean>>({})
const bulkDeleteOpen = ref(false)
const bulkDeleting = ref(false)
const editorOpen = ref(false)

function openEditor(eventId: string) {
  selectedEventId.value = eventId
  editorOpen.value = true
}

function closeEditor() {
  editorOpen.value = false
  selectedEventId.value = null
}

const selectedIds = computed(() =>
  Object.entries(rowSelection.value)
    .filter(([, v]) => v)
    .map(([k]) => k),
)

const TIMELINE_CHANGED_SUB = `
  subscription TimeEventChanged($metadataId: UUID!) {
    timeEventChanged(metadataId: $metadataId) {
      metadataId
      metadataVersion
      eventCount
    }
  }
`

gql.useSubscription(TIMELINE_CHANGED_SUB, { metadataId: metadataId.value }, (data: Record<string, unknown>) => {
  const event = (data as { timeEventChanged?: { metadataId: string; eventCount: number } })?.timeEventChanged
  if (!event) return
  if (selectedEventId.value) return
  refresh()
  toast.add({
    title: 'Timeline updated',
    description: `${event.eventCount} event${event.eventCount !== 1 ? 's' : ''} imported`,
  })
})

function addEventAtPlayhead() {
  const type = activeTypeFilter.value || eventTypes.value[0]?.id
  if (!type) {
    toast.add({ title: 'No event types available', color: 'error' })
    return
  }
  addEvent({
    type,
    startOffsetMs: timeEventsCurrentTimeMs.value,
    sort: events.value.length,
  })
}

function onMoveEvent(eventId: string, newStartMs: number, newEndMs: number | null) {
  const evt = events.value.find((e) => e.id === eventId)
  if (!evt) return
  editEvent(eventId, {
    type: evt.type.id,
    startOffsetMs: newStartMs,
    endOffsetMs: newEndMs,
    sort: evt.sort,
    attributes: evt.attributes,
  })
}

// METADATA attributes configured with a relationship carry crop data that must be
// mirrored onto the metadata relationship itself, not just the event's attributes.
async function syncCropDataToRelationships(eventId: string, input: TimeEventInput) {
  if (!input.attributes) return
  const event = events.value.find((e) => e.id === eventId)
  if (!event) return
  const typeAttrs = event.type.attributes || []
  for (const typeAttr of typeAttrs) {
    if (typeAttr.type !== 'METADATA') continue
    const relationship = typeAttr.configuration?.relationship
    if (!relationship) continue
    const attrValue = input.attributes[typeAttr.key]
    if (!attrValue) continue
    const entries = typeAttr.list ? (Array.isArray(attrValue) ? attrValue : []) : [attrValue]
    for (const entry of entries) {
      const relMetadataId = typeof entry === 'string' ? entry : entry?.id
      const attrs = typeof entry === 'object' ? (entry?.attributes ?? {}) : {}
      if (!relMetadataId || !attrs.crop) continue
      await editMetadataRelationshipAttributes(eventId, relMetadataId, relationship, { crop: attrs.crop })
    }
  }
}

async function onSaveEvent(input: TimeEventInput) {
  if (!selectedEventId.value) return
  const eventId = selectedEventId.value
  await editEvent(eventId, input)
  await syncCropDataToRelationships(eventId, input)
  closeEditor()
}

async function onSaveEventById(eventId: string, input: TimeEventInput) {
  await editEvent(eventId, input)
  await syncCropDataToRelationships(eventId, input)
}

async function onDeleteEvent() {
  if (!selectedEventId.value) return
  await deleteEvent(selectedEventId.value)
  closeEditor()
}

function onDeleteEventById(eventId: string) {
  deleteEvent(eventId)
}

async function onBulkDelete() {
  if (selectedIds.value.length === 0) return
  bulkDeleting.value = true
  const ids = [...selectedIds.value]
  try {
    const deleted = await deleteEvents(ids)
    const skipped = ids.length - deleted
    rowSelection.value = {}
    if (skipped > 0) {
      toast.add({ title: `Deleted ${deleted} event${deleted !== 1 ? 's' : ''}, ${skipped} skipped`, color: 'warning' })
    } else {
      toast.add({ title: `Deleted ${deleted} event${deleted !== 1 ? 's' : ''}` })
    }
  } catch (e: unknown) {
    toast.add({ title: 'Bulk delete failed', description: e instanceof Error ? e.message : undefined, color: 'error' })
  } finally {
    bulkDeleting.value = false
    bulkDeleteOpen.value = false
  }
}

function zoomIn() {
  pixelsPerMs.value = Math.min(1.0, pixelsPerMs.value * 2)
}

function zoomOut() {
  pixelsPerMs.value = Math.max(0.005, pixelsPerMs.value / 2)
}

function seekPlayer(ms: number) {
  timeEventsCurrentTimeMs.value = ms
  if (hasExternalPlayer.value) {
    props.onSeek?.(ms)
  } else {
    playerRef.value?.seek(ms)
  }
}

function onRulerSeek(ms: number) {
  seekPlayer(ms)
}

function onListSeek(ms: number) {
  seekPlayer(ms)
}

function onTimeUpdate(ms: number) {
  timeEventsCurrentTimeMs.value = ms
}

function onDurationChange(ms: number) {
  timeEventsDurationMs.value = ms
}

function onPdfDrop(file: File) {
  importPdf(file)
}

function onCsvDrop(file: File) {
  importCsv(file)
}

function setTypeFilter(typeId: string | null) {
  activeTypeFilter.value = typeId
}
</script>

<template>
  <div class="timeline-editor">
    <!-- Media player (hidden when controlled by parent) -->
    <ClientOnly v-if="!hasExternalPlayer">
      <SMediaPlayer
        ref="playerRef"
        :item="metadata"
        @timeupdate="onTimeUpdate"
        @durationchange="onDurationChange"
      />
    </ClientOnly>

    <!-- Time display -->
    <div class="time-display">
      <span class="time-current">{{ formatMs(timeEventsCurrentTimeMs) }}</span>
      <span class="time-sep">/</span>
      <span class="time-duration">{{ formatMs(timeEventsDurationMs) }}</span>
    </div>

    <!-- Toolbar -->
    <div class="toolbar">
      <div class="toolbar-left">
        <!-- View mode toggle -->
        <button
          class="toolbar-btn"
          :class="{ 'toolbar-btn--active': viewMode === 'timeline' }"
          title="Timeline view"
          @click="viewMode = 'timeline'"
        >
          <Icon name="gantt" :size="14" />
        </button>
        <button
          class="toolbar-btn"
          :class="{ 'toolbar-btn--active': viewMode === 'list' }"
          title="List view"
          @click="viewMode = 'list'"
        >
          <Icon name="list" :size="14" />
        </button>

        <span class="toolbar-sep" />

        <!-- Zoom (timeline only) -->
        <template v-if="viewMode === 'timeline'">
          <button class="toolbar-btn" title="Zoom out" @click="zoomOut">
            <Icon name="zoomOut" :size="14" />
          </button>
          <button class="toolbar-btn" title="Zoom in" @click="zoomIn">
            <Icon name="zoomIn" :size="14" />
          </button>
          <span class="toolbar-sep" />
        </template>

        <!-- Type filter tabs -->
        <button
          class="toolbar-tab"
          :class="{ 'toolbar-tab--active': !activeTypeFilter }"
          @click="setTypeFilter(null)"
        >
          All
        </button>
        <button
          v-for="t in eventTypes"
          :key="t.id"
          class="toolbar-tab"
          :class="{ 'toolbar-tab--active': activeTypeFilter === t.id }"
          @click="setTypeFilter(t.id)"
        >
          {{ t.name }}
        </button>
      </div>

      <div class="toolbar-right">
        <!-- Bulk delete -->
        <button
          v-if="selectedIds.length > 0"
          class="toolbar-btn toolbar-btn--danger"
          :title="`Delete ${selectedIds.length} selected`"
          @click="bulkDeleteOpen = true"
        >
          <Icon name="trash" :size="14" />
          <span>{{ selectedIds.length }}</span>
        </button>

        <!-- Add event -->
        <button class="toolbar-btn toolbar-btn--primary" title="Add event at playhead" @click="addEventAtPlayhead">
          <Icon name="plus" :size="14" /> Add Event
        </button>
      </div>
    </div>

    <!-- Timeline view -->
    <div v-if="viewMode === 'timeline'" class="timeline-view">
      <div class="timeline-scroll">
        <TimelineRuler
          :duration-ms="timeEventsDurationMs"
          :current-time-ms="timeEventsCurrentTimeMs"
          :pixels-per-ms="pixelsPerMs"
          @seek="onRulerSeek"
        />

        <TimelineTrack
          v-for="group in visibleTypeGroups"
          :key="group.type.id"
          :type="group.type"
          :events="group.events"
          :duration-ms="timeEventsDurationMs"
          :current-time-ms="timeEventsCurrentTimeMs"
          :pixels-per-ms="pixelsPerMs"
          :selected-event-id="selectedEventId"
          @select="(id: string) => selectedEventId = id"
          @open="openEditor"
          @move="onMoveEvent"
        />

        <div v-if="visibleTypeGroups.length === 0" class="timeline-empty">
          No event types available. Create an event type in settings first.
        </div>
      </div>
    </div>

    <!-- List view -->
    <TimeEventListView
      v-else
      :type-groups="visibleTypeGroups"
      :event-types="eventTypes"
      :selected-event-id="selectedEventId"
      :current-time-ms="timeEventsCurrentTimeMs"
      :pdf-import-progress="pdfProgress"
      :csv-import-progress="csvProgress"
      :row-selection="rowSelection"
      @select="openEditor"
      @save="onSaveEventById"
      @delete="onDeleteEventById"
      @seek="onListSeek"
      @pdf-drop="onPdfDrop"
      @csv-drop="onCsvDrop"
      @update:row-selection="(v: Record<string, boolean>) => rowSelection = v"
    />

    <!-- Event editor modal -->
    <TimeEventPropertyPanel
      v-if="editorOpen && selectedEvent"
      :event="selectedEvent"
      :event-types="eventTypes"
      :current-time-ms="timeEventsCurrentTimeMs"
      @save="onSaveEvent"
      @delete="onDeleteEvent"
      @close="closeEditor"
    />

    <!-- Bulk delete confirmation -->
    <Teleport to="body">
      <div v-if="bulkDeleteOpen" class="confirm-overlay" @click.self="bulkDeleteOpen = false">
        <div class="confirm-dialog">
          <h3 class="confirm-title">Delete {{ selectedIds.length }} event{{ selectedIds.length !== 1 ? 's' : '' }}?</h3>
          <p class="confirm-text">This action cannot be undone.</p>
          <div class="confirm-actions">
            <button class="confirm-btn confirm-btn--cancel" :disabled="bulkDeleting" @click="bulkDeleteOpen = false">
              Cancel
            </button>
            <button class="confirm-btn confirm-btn--delete" :disabled="bulkDeleting" @click="onBulkDelete">
              {{ bulkDeleting ? 'Deleting...' : 'Delete' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.timeline-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.time-display {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 0;
  font-family: var(--font-mono, monospace);
  font-size: 13px;
}

.time-current { color: var(--fg-0); }
.time-sep { color: var(--fg-3); }
.time-duration { color: var(--fg-3); }

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid var(--line);
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 4px;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.toolbar-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 8px;
  font-size: 12px;
  background: none;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  color: var(--fg-3);
  cursor: pointer;
}

.toolbar-btn:hover {
  background: var(--bg-2);
  color: var(--fg-1);
}

.toolbar-btn--active {
  background: var(--bg-2);
  border-color: var(--line);
  color: var(--fg-0);
}

.toolbar-btn--primary {
  background: var(--brand-2);
  color: #fff;
  border-color: var(--brand-2);
  font-weight: 500;
}

.toolbar-btn--primary:hover {
  filter: brightness(1.1);
}

.toolbar-btn--danger {
  color: var(--err);
}

.toolbar-btn--danger:hover {
  background: var(--bg-2);
}

.toolbar-sep {
  width: 1px;
  height: 16px;
  background: var(--line);
  margin: 0 2px;
}

.toolbar-tab {
  padding: 4px 10px;
  font-size: 12px;
  background: none;
  border: none;
  border-radius: var(--r-sm);
  color: var(--fg-3);
  cursor: pointer;
}

.toolbar-tab:hover {
  color: var(--fg-1);
}

.toolbar-tab--active {
  background: var(--bg-2);
  color: var(--fg-0);
  font-weight: 500;
}

.timeline-view {
  display: flex;
  gap: 12px;
}

.timeline-scroll {
  flex: 1;
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--bg-0);
}

.timeline-empty {
  padding: 24px;
  text-align: center;
  font-size: 13px;
  color: var(--fg-3);
}

.confirm-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
}

.confirm-dialog {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  padding: 24px;
  max-width: 400px;
  width: 90%;
}

.confirm-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--fg-0);
  margin: 0 0 8px;
}

.confirm-text {
  font-size: 13px;
  color: var(--fg-3);
  margin: 0 0 16px;
}

.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.confirm-btn {
  padding: 6px 14px;
  font-size: 13px;
  border-radius: var(--r-sm);
  border: none;
  cursor: pointer;
}

.confirm-btn--cancel {
  background: var(--bg-2);
  color: var(--fg-2);
}

.confirm-btn--delete {
  background: var(--err);
  color: #fff;
}

.confirm-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
