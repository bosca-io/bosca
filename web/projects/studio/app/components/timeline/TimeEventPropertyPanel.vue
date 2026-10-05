<script lang="ts" setup>
 
import type { TimeEvent, TimeEventType, TimeEventInput } from '~/composables/useTimeEvents'
import type { AttributeState } from '~/utils/editor/attribute'
import type { TemplateAttributeTool } from '~/utils/editor/tool'
import { useTimeEventAttributes } from '~/composables/useTimeEventAttributes'
import { useUploader } from '~/composables/useUploader'
import { formatMs, parseMs } from '~/utils/timeline'

const props = defineProps<{
  event: TimeEvent
  eventTypes: TimeEventType[]
  currentTimeMs: number
}>()

const emit = defineEmits<{
  (_e: 'save', _input: TimeEventInput): void
  (_e: 'delete' | 'close'): void
}>()

const localType = ref(props.event.type.id)
const localStartMs = ref(props.event.startOffsetMs)
const localEndMs = ref<number | null>(props.event.endOffsetMs)
const localSort = ref(props.event.sort)
const startText = ref(formatMs(props.event.startOffsetMs))
const endText = ref(props.event.endOffsetMs != null ? formatMs(props.event.endOffsetMs) : '')

const deleteConfirmOpen = ref(false)

const currentType = computed(() => props.eventTypes.find((t) => t.id === localType.value))
const currentTypeAttributes = computed(() => currentType.value?.attributes || [])
const currentAttributeValues = computed(() => props.event.attributes ?? {})

const { attributes: attrStates, extractAttributes } = useTimeEventAttributes(
  currentTypeAttributes,
  currentAttributeValues,
)

const uploader = useUploader()

watch(
  () => props.event,
  (evt) => {
    localType.value = evt.type.id
    localStartMs.value = evt.startOffsetMs
    localEndMs.value = evt.endOffsetMs
    localSort.value = evt.sort
    startText.value = formatMs(evt.startOffsetMs)
    endText.value = evt.endOffsetMs != null ? formatMs(evt.endOffsetMs) : ''
  },
  { immediate: true },
)

function onStartBlur() {
  const ms = parseMs(startText.value)
  if (ms !== null) {
    localStartMs.value = ms
    startText.value = formatMs(ms)
  } else {
    startText.value = formatMs(localStartMs.value)
  }
}

function onEndBlur() {
  if (!endText.value.trim()) {
    localEndMs.value = null
    return
  }
  const ms = parseMs(endText.value)
  if (ms !== null) {
    localEndMs.value = ms
    endText.value = formatMs(ms)
  } else {
    endText.value = localEndMs.value != null ? formatMs(localEndMs.value) : ''
  }
}

function setStartToPlayhead() {
  localStartMs.value = props.currentTimeMs
  startText.value = formatMs(props.currentTimeMs)
}

function setEndToPlayhead() {
  localEndMs.value = props.currentTimeMs
  endText.value = formatMs(props.currentTimeMs)
}

function clearEnd() {
  localEndMs.value = null
  endText.value = ''
}

function onRunTool(_attribute: AttributeState, _tool: TemplateAttributeTool) {}

function onSave() {
  emit('save', {
    type: localType.value,
    startOffsetMs: localStartMs.value,
    endOffsetMs: localEndMs.value,
    sort: localSort.value,
    attributes: extractAttributes(),
  })
}

function confirmDelete() {
  deleteConfirmOpen.value = false
  emit('delete')
}

const typeOptions = computed(() =>
  props.eventTypes.map((t) => ({ value: t.id, label: t.name })),
)

const durationDisplay = computed(() => {
  if (localEndMs.value == null) return null
  const d = localEndMs.value - localStartMs.value
  return { text: d >= 0 ? formatMs(d) : 'Invalid (end before start)', invalid: d < 0 }
})
</script>

<template>
  <Modal
    title="Edit Event"
    icon="clock"
    width="560px"
    @close="emit('close')">
    <div class="panel-body">
      <label class="field-label">Type</label>
      <select v-model="localType" class="field-select">
        <option v-for="opt in typeOptions" :key="opt.value" :value="opt.value">
          {{ opt.label }}
        </option>
      </select>

      <div class="time-grid">
        <div class="time-field">
          <label class="field-label">Start</label>
          <div class="time-row">
            <input
              v-model="startText"
              class="field-input field-input--mono"
              placeholder="0:00.0"
              @blur="onStartBlur"
              @keydown.enter="($event.target as HTMLInputElement)?.blur()"
            >
            <button
              class="time-btn"
              :title="'Set to ' + formatMs(currentTimeMs)"
              @click="setStartToPlayhead"
            >
              <Icon name="target" :size="12" />
            </button>
          </div>
        </div>

        <div class="time-field">
          <label class="field-label">End</label>
          <div class="time-row">
            <input
              v-model="endText"
              class="field-input field-input--mono"
              placeholder="none"
              @blur="onEndBlur"
              @keydown.enter="($event.target as HTMLInputElement)?.blur()"
            >
            <button
              class="time-btn"
              :title="'Set to ' + formatMs(currentTimeMs)"
              @click="setEndToPlayhead"
            >
              <Icon name="target" :size="12" />
            </button>
            <button
              v-if="localEndMs != null"
              class="time-btn"
              title="Clear end"
              @click="clearEnd"
            >
              <Icon name="x" :size="12" />
            </button>
          </div>
        </div>
      </div>

      <div
        v-if="durationDisplay"
        class="duration-display"
        :class="{ 'duration-display--invalid': durationDisplay.invalid }"
      >
        Duration: {{ durationDisplay.text }}
      </div>

      <!-- Attribute editors -->
      <template v-for="attr in attrStates.values()" :key="event.id + '-' + attr.key">
        <template v-if="attr.ui === 'COLLECTION' && attr.list">
          <AttributesCollections
            :item="null"
            :attribute="(attr as any)"
            :editable="true"
            :tools-enabled="false"
            :on-run-tool="onRunTool"
          />
        </template>
        <template v-else-if="attr.ui === 'COLLECTION'">
          <AttributesCollection
            :item="null"
            :attribute="(attr as any)"
            :editable="true"
            :tools-enabled="false"
            :on-run-tool="onRunTool"
          />
        </template>
        <template v-else-if="attr.ui === 'INPUT'">
          <AttributesDateTimeInput
            v-if="attr.type === 'DATE_TIME' || attr.type === 'DATETIME'"
            :item="null"
            :state="null"
            :attribute="(attr as any)"
            :editable="true"
            :tools-enabled="false"
            :on-run-tool="onRunTool"
          />
          <AttributesNumberInput
            v-else-if="attr.type === 'INT' || attr.type === 'FLOAT'"
            :item="null"
            :attribute="(attr as any)"
            :editable="true"
            :tools-enabled="false"
            :on-run-tool="onRunTool"
          />
          <AttributesInput
            v-else
            :item="null"
            :attribute="(attr as any)"
            :editable="true"
            :tools-enabled="false"
            :on-run-tool="onRunTool"
          />
        </template>
        <template v-else-if="attr.ui === 'TEXTAREA'">
          <AttributesTextArea
            :item="null"
            :attribute="(attr as any)"
            :editable="true"
            :tools-enabled="false"
            :on-run-tool="onRunTool"
          />
        </template>
        <template v-else-if="attr.ui === 'IMAGE'">
          <AttributesImage
            :item="null"
            :attribute="(attr as any)"
            :editable="true"
            :uploader="uploader"
            :tools-enabled="false"
            :on-run-tool="onRunTool"
          />
        </template>
        <template v-else-if="attr.ui === 'FILE' || attr.ui === 'METADATA'">
          <AttributesMetadatas
            v-if="attr.list"
            :item="null"
            :attribute="(attr as any)"
            :editable="true"
            :tools-enabled="false"
            :on-run-tool="onRunTool"
          />
          <AttributesFile
            v-else
            :item="null"
            :attribute="(attr as any)"
            :editable="true"
            :uploader="uploader"
            :tools-enabled="false"
            :on-run-tool="onRunTool"
          />
        </template>
      </template>

    </div>

    <template #footer>
      <button class="panel-delete-btn" title="Delete" @click="deleteConfirmOpen = true">
        <Icon name="trash" :size="14" /> Delete
      </button>
      <span class="footer-spacer" />
      <button class="cancel-btn" @click="emit('close')">Cancel</button>
      <button class="save-btn" @click="onSave">
        <Icon name="save" :size="14" /> Save
      </button>
    </template>

    <!-- Delete confirmation -->
    <Teleport to="body">
      <div v-if="deleteConfirmOpen" class="confirm-overlay" @click.self="deleteConfirmOpen = false">
        <div class="confirm-dialog">
          <h3 class="confirm-title">Delete this event?</h3>
          <p class="confirm-text">This action cannot be undone.</p>
          <div class="confirm-actions">
            <button class="confirm-btn confirm-btn--cancel" @click="deleteConfirmOpen = false">Cancel</button>
            <button class="confirm-btn confirm-btn--delete" @click="confirmDelete">Delete</button>
          </div>
        </div>
      </div>
    </Teleport>
  </Modal>
</template>

<style scoped>
.panel-delete-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  font-size: 13px;
  background: none;
  border: none;
  color: var(--fg-3);
  cursor: pointer;
  border-radius: var(--r-sm);
}

.panel-delete-btn:hover {
  color: var(--err);
  background: var(--bg-2);
}

.footer-spacer {
  flex: 1;
}

.cancel-btn {
  padding: 6px 14px;
  font-size: 13px;
  background: var(--bg-2);
  color: var(--fg-2);
  border: none;
  border-radius: var(--r-sm);
  cursor: pointer;
}

.cancel-btn:hover {
  color: var(--fg-0);
}

.panel-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.field-label {
  font-size: 11px;
  font-weight: 500;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.03em;
  margin-bottom: 4px;
  display: block;
}

.field-select {
  width: 100%;
  padding: 6px 8px;
  font-size: 13px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-1);
  outline: none;
}

.field-select:focus {
  border-color: var(--brand-2);
}

.field-input {
  flex: 1;
  padding: 6px 8px;
  font-size: 13px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-1);
  outline: none;
  min-width: 0;
}

.field-input:focus {
  border-color: var(--brand-2);
}

.field-input--mono {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}

.time-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.time-field {
  display: flex;
  flex-direction: column;
}

.time-row {
  display: flex;
  gap: 4px;
  align-items: center;
}

.time-btn {
  width: 26px;
  height: 26px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.time-btn:hover {
  color: var(--fg-1);
  border-color: var(--brand-2);
}

.duration-display {
  font-size: 12px;
  color: var(--fg-3);
}

.duration-display--invalid {
  color: var(--err);
}

.save-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 500;
  background: var(--brand-2);
  color: #fff;
  border: none;
  border-radius: var(--r-sm);
  cursor: pointer;
}

.save-btn:hover {
  filter: brightness(1.1);
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
