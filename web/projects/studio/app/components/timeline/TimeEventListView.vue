<script lang="ts" setup>
import type { TimeEvent, TimeEventType, TimeEventTypeAttribute, TimeEventInput, TypeGroup } from '~/composables/useTimeEvents'
import type { PdfImportProgress } from '~/composables/usePdfTimelineImport'
import type { CsvImportProgress } from '~/composables/useCsvTimelineImport'
import { formatMs, parseMs } from '~/utils/timeline'
import { useEventHoverPreview } from '~/composables/useEventPreview'

const props = defineProps<{
  typeGroups: TypeGroup[]
  eventTypes: TimeEventType[]
  selectedEventId: string | null
  currentTimeMs: number
  pdfImportProgress?: PdfImportProgress
  csvImportProgress?: CsvImportProgress
  rowSelection?: Record<string, boolean>
}>()

const emit = defineEmits<{
  (_e: 'select' | 'delete', _eventId: string): void
  (_e: 'save', _eventId: string, _input: TimeEventInput): void
  (_e: 'seek', _ms: number): void
  (_e: 'pdf-drop' | 'csv-drop', _file: File): void
  (_e: 'update:rowSelection', _value: Record<string, boolean>): void
}>()

const toast = useToast()
const isDraggingOver = ref(false)
const singleDeleteId = ref<string | null>(null)
const singleDeleteOpen = ref(false)

const { hoverEvent, hoverPreview, mouseX, mouseY, onEnter: onRowEnter, onMove: onRowMove, onLeave: onRowLeave } =
  useEventHoverPreview()

const inlineStartText = ref<Record<string, string>>({})
const inlineEndText = ref<Record<string, string>>({})
const inlineFocused = ref(false)
let focusTimer: ReturnType<typeof setTimeout> | null = null
const frozenOrderIds = ref<string[]>([])

const sortedEvents = computed<TimeEvent[]>(() =>
  props.typeGroups
    .flatMap((g) => g.events)
    .sort((a, b) => a.startOffsetMs - b.startOffsetMs || a.sort - b.sort),
)

const displayEvents = computed<TimeEvent[]>(() => {
  if (inlineFocused.value && frozenOrderIds.value.length > 0) {
    const eventMap = new Map(sortedEvents.value.map((e) => [e.id, e]))
    return frozenOrderIds.value.map((id) => eventMap.get(id)).filter((e): e is TimeEvent => e != null)
  }
  return sortedEvents.value
})

watch(
  sortedEvents,
  (events) => {
    for (const evt of events) {
      inlineStartText.value[evt.id] = formatMs(evt.startOffsetMs)
      inlineEndText.value[evt.id] = evt.endOffsetMs != null ? formatMs(evt.endOffsetMs) : ''
    }
  },
  { immediate: true },
)

const allAttributeColumns = computed<TimeEventTypeAttribute[]>(() => {
  const seen = new Map<string, TimeEventTypeAttribute>()
  for (const group of props.typeGroups) {
    for (const attr of group.type.attributes || []) {
      if (!seen.has(attr.key)) seen.set(attr.key, attr)
    }
  }
  return Array.from(seen.values())
})

const isImporting = computed(() => {
  const pdf = props.pdfImportProgress?.status
  const csv = props.csvImportProgress?.status
  return (
    (pdf && pdf !== 'idle' && pdf !== 'done' && pdf !== 'error') ||
    (csv && csv !== 'idle' && csv !== 'done' && csv !== 'error')
  )
})

const progressLabel = computed(() => {
  const pdf = props.pdfImportProgress?.status
  if (pdf === 'uploading') return 'Uploading PDF...'
  if (pdf === 'processing') return 'Processing PDF pages on server...'
  const csv = props.csvImportProgress
  if (csv?.status === 'parsing') return 'Parsing CSV...'
  if (csv?.status === 'updating-events') return `Updating events (${csv.currentRow} of ${csv.totalRows})...`
  return ''
})

const csvProgressPercent = computed(() => {
  const csv = props.csvImportProgress
  if (!csv || csv.totalRows === 0) return 0
  return Math.round((csv.currentRow / csv.totalRows) * 100)
})

const showProgressBar = computed(() => props.csvImportProgress?.status === 'updating-events')

function onInlineFocus(event: TimeEvent) {
  if (focusTimer) { clearTimeout(focusTimer); focusTimer = null }
  if (!inlineFocused.value) frozenOrderIds.value = sortedEvents.value.map((e) => e.id)
  inlineFocused.value = true
  inlineStartText.value[event.id] = formatMs(event.startOffsetMs)
  inlineEndText.value[event.id] = event.endOffsetMs != null ? formatMs(event.endOffsetMs) : ''
}

function onInlineBlurCleanup() {
  focusTimer = setTimeout(() => {
    inlineFocused.value = false
    frozenOrderIds.value = []
  }, 100)
}

function onInlineStartBlur(event: TimeEvent) {
  const text = inlineStartText.value[event.id] ?? ''
  const ms = parseMs(text)
  if (ms !== null && ms !== event.startOffsetMs) {
    inlineStartText.value[event.id] = formatMs(ms)
    emitTimeChange(event, ms, event.endOffsetMs)
  } else {
    inlineStartText.value[event.id] = formatMs(event.startOffsetMs)
  }
  onInlineBlurCleanup()
}

function onInlineEndBlur(event: TimeEvent) {
  const text = inlineEndText.value[event.id] ?? ''
  if (!text.trim()) {
    if (event.endOffsetMs != null) emitTimeChange(event, event.startOffsetMs, null)
    inlineEndText.value[event.id] = ''
    onInlineBlurCleanup()
    return
  }
  const ms = parseMs(text)
  if (ms !== null && ms !== event.endOffsetMs) {
    inlineEndText.value[event.id] = formatMs(ms)
    emitTimeChange(event, event.startOffsetMs, ms)
  } else {
    inlineEndText.value[event.id] = event.endOffsetMs != null ? formatMs(event.endOffsetMs) : ''
  }
  onInlineBlurCleanup()
}

function emitTimeChange(event: TimeEvent, startOffsetMs: number, endOffsetMs: number | null) {
  emit('save', event.id, {
    type: event.type.id,
    startOffsetMs,
    endOffsetMs,
    sort: event.sort,
    attributes: event.attributes ?? {},
  })
}

function getInlineDuration(event: TimeEvent): string {
  const startText = inlineStartText.value[event.id]
  const endText = inlineEndText.value[event.id]
  if (!endText) return '—'
  const startMs = parseMs(startText ?? '')
  const endMs = parseMs(endText)
  if (startMs === null || endMs === null) return '—'
  const d = endMs - startMs
  return d >= 0 ? formatMs(d) : '—'
}

function onInlineDelete(eventId: string) {
  singleDeleteId.value = eventId
  singleDeleteOpen.value = true
}

function confirmSingleDelete() {
  if (singleDeleteId.value) {
    emit('delete', singleDeleteId.value)
  }
  singleDeleteOpen.value = false
  singleDeleteId.value = null
}

function seekToEvent(event: TimeEvent) {
  emit('seek', event.startOffsetMs)
}

function getAttributeValue(event: TimeEvent, key: string): unknown {
  return event.attributes?.[key] ?? ''
}

function getMetadataAttributeId(event: TimeEvent, key: string): string | null {
  const val = event.attributes?.[key]
  if (!val) return null
  if (typeof val === 'string') return val
  if (typeof val === 'object' && 'id' in val) return (val as { id?: string }).id ?? null
  return null
}

const CSV_TYPES = ['text/csv', 'application/vnd.ms-excel']

function onDragOver(e: DragEvent) {
  e.preventDefault()
  if (e.dataTransfer?.types.includes('Files')) {
    isDraggingOver.value = true
  }
}

function onDragLeave(e: DragEvent) {
  const related = e.relatedTarget as Node | null
  const target = e.currentTarget as HTMLElement
  if (!related || !target.contains(related)) {
    isDraggingOver.value = false
  }
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  isDraggingOver.value = false
  const files = e.dataTransfer?.files
  if (!files || files.length === 0) return
  const file = files[0]!
  if (file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')) {
    emit('pdf-drop', file)
  } else if (CSV_TYPES.includes(file.type) || file.name.endsWith('.csv')) {
    emit('csv-drop', file)
  } else {
    toast.add({
      title: 'Unsupported file type',
      description: 'Drop a PDF or CSV file to import timeline events.',
      color: 'warning',
    })
  }
}

function isMetadataAttr(attrKey: string): boolean {
  for (const group of props.typeGroups) {
    const attr = group.type.attributes?.find((a) => a.key === attrKey)
    if (attr && (attr.ui === 'METADATA' || attr.ui === 'FILE')) return true
  }
  return false
}

const selection = computed(() => props.rowSelection ?? {})
const allSelected = computed(() => {
  const events = displayEvents.value
  return events.length > 0 && events.every((e) => selection.value[e.id])
})
const someSelected = computed(() => {
  const events = displayEvents.value
  return events.some((e) => selection.value[e.id]) && !allSelected.value
})

function toggleAll(checked: boolean) {
  const updated = { ...selection.value }
  for (const e of displayEvents.value) updated[e.id] = checked
  emit('update:rowSelection', updated)
}

function toggleRow(eventId: string, checked: boolean) {
  emit('update:rowSelection', { ...selection.value, [eventId]: checked })
}
</script>

<template>
  <div
    class="list-container"
    :class="{ 'list-container--drop': isDraggingOver }"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <!-- Import progress -->
    <div v-if="isImporting" class="import-progress">
      <Icon name="spinner" :size="14" color="var(--fg-3)" />
      <span>{{ progressLabel }}</span>
      <span v-if="showProgressBar" class="progress-pct">{{ csvProgressPercent }}%</span>
    </div>

    <!-- Drop overlay -->
    <div v-if="isDraggingOver" class="drop-overlay">
      <Icon name="download" :size="24" color="var(--brand-2)" />
      <p>Drop a PDF or CSV to create timeline events</p>
    </div>

    <div class="list-scroll">
      <table class="list-table">
        <thead>
          <tr class="list-header">
            <th class="list-th list-th--check">
              <input
                type="checkbox"
                :checked="allSelected"
                :indeterminate="someSelected"
                @change="toggleAll(($event.target as HTMLInputElement).checked)"
              >
            </th>
            <th class="list-th list-th--play" />
            <th class="list-th">Type</th>
            <th class="list-th">Start</th>
            <th class="list-th">End</th>
            <th class="list-th">Duration</th>
            <th v-for="attr in allAttributeColumns" :key="attr.key" class="list-th">
              {{ attr.name }}
            </th>
            <th class="list-th list-th--actions">Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="displayEvents.length === 0 && !isDraggingOver">
            <td :colspan="7 + allAttributeColumns.length" class="list-empty">
              No events. Click "Add Event" or drop a PDF/CSV to create events.
            </td>
          </tr>

          <template v-for="event in displayEvents" :key="event.id">
            <tr
              class="list-row"
              :class="{ 'list-row--selected': selectedEventId === event.id }"
              @click="emit('select', event.id)"
              @mouseenter="onRowEnter($event, event)"
              @mousemove="onRowMove"
              @mouseleave="onRowLeave"
            >
              <td class="list-td list-td--check" @click.stop>
                <input
                  type="checkbox"
                  :checked="!!selection[event.id]"
                  @change="toggleRow(event.id, ($event.target as HTMLInputElement).checked)"
                >
              </td>
              <td class="list-td list-td--play" @click.stop>
                <button class="seek-btn" title="Seek to event" @click="seekToEvent(event)">
                  <Icon name="arrowRight" :size="12" />
                </button>
              </td>
              <td class="list-td">
                <Badge>{{ event.type.name }}</Badge>
              </td>
              <td class="list-td" @click.stop>
                <input
                  v-model="inlineStartText[event.id]"
                  class="inline-time"
                  placeholder="0:00.0"
                  @focus="onInlineFocus(event)"
                  @blur="onInlineStartBlur(event)"
                  @keydown.enter="($event.target as HTMLInputElement)?.blur()"
                >
              </td>
              <td class="list-td" @click.stop>
                <input
                  v-model="inlineEndText[event.id]"
                  class="inline-time"
                  placeholder="—"
                  @focus="onInlineFocus(event)"
                  @blur="onInlineEndBlur(event)"
                  @keydown.enter="($event.target as HTMLInputElement)?.blur()"
                >
              </td>
              <td class="list-td list-td--mono">{{ getInlineDuration(event) }}</td>
              <td
                v-for="attr in allAttributeColumns"
                :key="attr.key"
                class="list-td list-td--attr"
              >
                <TimelineMetadataField
                  v-if="isMetadataAttr(attr.key) && getMetadataAttributeId(event, attr.key)"
                  :model-value="getMetadataAttributeId(event, attr.key)"
                  read-only
                />
                <template v-else>
                  {{ getAttributeValue(event, attr.key) }}
                </template>
              </td>
              <td class="list-td list-td--actions" @click.stop>
                <button class="row-action" title="Edit" @click="emit('select', event.id)">
                  <Icon name="pencil" :size="12" />
                </button>
                <button class="row-action row-action--delete" title="Delete" @click="onInlineDelete(event.id)">
                  <Icon name="trash" :size="12" />
                </button>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <TimeEventPreview
      v-if="hoverEvent"
      :event="hoverEvent"
      :preview="hoverPreview"
      :x="mouseX"
      :y="mouseY"
    />

    <!-- Delete confirmation -->
    <Teleport to="body">
      <div v-if="singleDeleteOpen" class="confirm-overlay" @click.self="singleDeleteOpen = false">
        <div class="confirm-dialog">
          <h3 class="confirm-title">Delete this event?</h3>
          <p class="confirm-text">This action cannot be undone.</p>
          <div class="confirm-actions">
            <button class="confirm-btn confirm-btn--cancel" @click="singleDeleteOpen = false">Cancel</button>
            <button class="confirm-btn confirm-btn--delete" @click="confirmSingleDelete">Delete</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.list-container {
  background: var(--bg-0);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
}

.list-container--drop {
  border-color: var(--brand-2);
}

.import-progress {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  font-size: 12px;
  color: var(--fg-3);
  border-bottom: 1px solid var(--line);
  background: var(--bg-1);
}

.progress-pct {
  margin-left: auto;
  font-family: var(--font-mono, monospace);
}

.drop-overlay {
  padding: 24px;
  text-align: center;
  border-bottom: 1px solid var(--line);
}

.drop-overlay p {
  font-size: 13px;
  color: var(--fg-3);
  margin: 8px 0 0;
}

.list-scroll {
  overflow-x: auto;
}

.list-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.list-header {
  border-bottom: 1px solid var(--line);
  background: var(--bg-1);
}

.list-th {
  padding: 6px 12px;
  text-align: left;
  font-size: 11px;
  font-weight: 500;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.03em;
  white-space: nowrap;
}

.list-th--check { width: 32px; padding-left: 12px; padding-right: 4px; }
.list-th--play { width: 32px; }
.list-th--actions { width: 80px; }

.list-row {
  border-bottom: 1px solid var(--line);
  cursor: pointer;
}

.list-row:hover {
  background: var(--bg-1);
}

.list-row--selected {
  background: var(--bg-1);
}

.list-td {
  padding: 4px 12px;
  color: var(--fg-1);
  white-space: nowrap;
}

.list-td--check { padding-left: 12px; padding-right: 4px; }
.list-td--mono { font-family: var(--font-mono, monospace); font-size: 12px; }
.list-td--attr { max-width: 192px; overflow: hidden; text-overflow: ellipsis; font-size: 12px; }
.list-td--actions { padding: 2px 12px; }

.list-empty {
  padding: 24px 12px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.seek-btn {
  width: 24px;
  height: 24px;
  background: none;
  border: none;
  color: var(--fg-3);
  cursor: pointer;
  border-radius: var(--r-sm);
  display: flex;
  align-items: center;
  justify-content: center;
}

.seek-btn:hover {
  color: var(--fg-0);
  background: var(--bg-2);
}

.inline-time {
  width: 72px;
  padding: 3px 6px;
  font-size: 12px;
  font-family: var(--font-mono, monospace);
  background: var(--bg-2);
  border: 1px solid transparent;
  border-radius: var(--r-xs);
  color: var(--fg-1);
  outline: none;
}

.inline-time:focus {
  border-color: var(--brand-2);
  background: var(--bg-0);
}

.row-action {
  width: 24px;
  height: 24px;
  background: none;
  border: none;
  color: var(--fg-3);
  cursor: pointer;
  border-radius: var(--r-sm);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.row-action:hover {
  color: var(--fg-0);
  background: var(--bg-2);
}

.row-action--delete:hover {
  color: var(--err);
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
</style>
