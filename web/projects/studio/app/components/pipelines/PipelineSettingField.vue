<script setup lang="ts">
/**
 * Renders one declared node setting as the right form control, dispatching on `setting.control`. It is a
 * controlled component: it reads `value` and emits a `change` carrying a set/unset decision (the
 * inspector owns the single `data.settings` mutation), so all value coercion stays in the tested
 * `pipelineSettings` helpers. GROUP_LIST recurses into this same component for each row's sub-fields.
 */
import SchemaBuilder from '~/components/pipelines/SchemaBuilder.vue'
import ConditionBuilder from '~/components/pipelines/ConditionBuilder.vue'
import PipelineJsonataField from '~/components/pipelines/PipelineJsonataField.vue'
import PipelineContractSummary from '~/components/pipelines/PipelineContractSummary.vue'
import PipelinePeekModal from '~/components/pipelines/PipelinePeekModal.vue'
import {
  coerceControlValue,
  moveItem,
  parseDefault,
  seedNodeDefaults,
  type Coercion,
} from '~/components/pipelines/pipelineSettings'
import type { SettingMeta, ReferenceContext } from '~/components/pipelines/pipelineNodeTypes'

defineOptions({ name: 'PipelineSettingField' })

const props = defineProps<{
  setting: SettingMeta
  value: unknown
  references: ReferenceContext
}>()
const emit = defineEmits<{ change: [coercion: Coercion] }>()

function onValue(raw: unknown) {
  emit('change', coerceControlValue(props.setting, raw))
}

const label = computed(() => `${props.setting.label ?? props.setting.name}${props.setting.required ? ' *' : ''}`)
const placeholder = computed(() => props.setting.placeholder ?? props.setting.default ?? undefined)

// ─── Per-control display values ──────────────────────────────────────────────────────────────
const stringValue = computed(() => {
  if (props.setting.control === 'ENUM') return String(props.value ?? parseDefault(props.setting) ?? '')
  return typeof props.value === 'string' ? props.value : (props.value == null ? '' : String(props.value))
})
const boolValue = computed(() => typeof props.value === 'boolean' ? props.value : parseDefault(props.setting) === true)
const numberText = computed(() => props.value == null ? '' : String(props.value))
const listText = computed(() => Array.isArray(props.value) ? (props.value as string[]).join(', ') : '')
const schemaValue = computed<Record<string, unknown> | null>(() =>
  props.value != null && typeof props.value === 'object' && !Array.isArray(props.value)
    ? props.value as Record<string, unknown>
    : null)

const enumOptions = computed(() => props.setting.options.map(o => ({ value: o.value, label: o.label ?? o.value })))
const referenceOptions = computed(() => {
  const literals = props.setting.options.map(o => ({ value: o.value, label: o.label ?? o.value }))
  const list = props.setting.reference ? (props.references[props.setting.reference] ?? []) : []
  const options = [...literals, ...list]
  // A saved value must always render, even when the referenced pool doesn't (yet) contain it —
  // e.g. a relay's Get Environment names "development" before any environment has been created.
  const current = typeof props.value === 'string' ? props.value : ''
  if (current && !options.some(o => o.value === current)) options.push({ value: current, label: current })
  return options
})
// A Run Pipeline / For Each pipeline reference shows the picked pipeline's declared contract beneath it.
const selectedPipeline = computed(() =>
  props.setting.reference === 'PIPELINE'
    ? props.references.PIPELINE?.find(p => p.value === props.value)
    : undefined)
// A picked pipeline can be peeked into (a read-only view of its graph). Keyed on the raw value, not
// selectedPipeline: a saved reference outside the pool (e.g. a self-reference the picker excludes)
// still peeks — the modal reports a pipeline that truly doesn't exist.
const peekPipelineId = computed(() =>
  props.setting.reference === 'PIPELINE' && typeof props.value === 'string' && props.value.length > 0
    ? props.value
    : null)
const peeking = ref(false)

const isJsonataCode = computed(() => props.setting.control === 'CODE' && props.setting.language === 'jsonata')
const codeLanguage = computed(() => {
  const allowed = ['json', 'sql', 'javascript', 'graphql', 'text']
  return allowed.includes(props.setting.language ?? '') ? props.setting.language! : 'text'
})

// ─── GROUP_LIST (repeatable object rows, e.g. Switch cases) ───────────────────────────────────
const rows = computed<Record<string, unknown>[]>(() =>
  Array.isArray(props.value) ? props.value as Record<string, unknown>[] : [])
function emitRows(next: Record<string, unknown>[]) {
  emit('change', coerceControlValue(props.setting, next))
}
function addRow() {
  emitRows([...rows.value, seedNodeDefaults(props.setting.fields)])
}
function removeRow(i: number) {
  emitRows(rows.value.filter((_, idx) => idx !== i))
}
function moveRow(i: number, delta: number) {
  emitRows(moveItem(rows.value, i, delta))
}
function onSubChange(i: number, field: SettingMeta, c: Coercion) {
  const row = { ...rows.value[i] }
  if (c.action === 'unset') delete row[field.name]
  else row[field.name] = c.value
  emitRows(rows.value.map((r, idx) => (idx === i ? row : r)))
}
</script>

<template>
  <!-- GROUP_LIST: repeatable rows, each a set of recursive sub-fields -->
  <div
    v-if="setting.control === 'GROUP_LIST'"
    class="group-list"
  >
    <p
      v-if="setting.description"
      class="hint"
    >
      {{ setting.description }}
    </p>
    <div
      v-for="(row, i) in rows"
      :key="i"
      class="group-row"
    >
      <div class="group-row-head">
        <span class="group-row-num">{{ setting.itemLabel ?? 'Item' }} {{ i + 1 }}</span>
        <div class="group-row-actions">
          <Button
            size="xs"
            variant="ghost"
            :disabled="i === 0"
            title="Move up"
            @click="moveRow(i, -1)"
          >
            ↑
          </Button>
          <Button
            size="xs"
            variant="ghost"
            :disabled="i === rows.length - 1"
            title="Move down"
            @click="moveRow(i, 1)"
          >
            ↓
          </Button>
          <Button
            size="xs"
            variant="ghost"
            icon="x"
            title="Remove"
            @click="removeRow(i)"
          />
        </div>
      </div>
      <PipelineSettingField
        v-for="field in setting.fields"
        :key="field.name"
        :setting="field"
        :value="row[field.name]"
        :references="references"
        @change="(c: Coercion) => onSubChange(i, field, c)"
      />
    </div>
    <Button
      size="xs"
      icon="plus"
      @click="addRow"
    >
      Add {{ (setting.itemLabel ?? 'item').toLowerCase() }}
    </Button>
  </div>

  <!-- BOOLEAN -->
  <Switch
    v-else-if="setting.control === 'BOOLEAN'"
    :model-value="boolValue"
    :label="label"
    @update:model-value="(v: boolean) => onValue(v)"
  />

  <!-- Everything else: a labeled field plus an optional hint -->
  <div
    v-else
    class="setting-field"
  >
    <TextInput
      v-if="setting.control === 'TEXT'"
      :model-value="stringValue"
      :label="label"
      :placeholder="placeholder"
      :mono="setting.mono"
      :type="setting.secret ? 'password' : 'text'"
      @update:model-value="(v: string) => onValue(v)"
    />
    <Textarea
      v-else-if="setting.control === 'TEXTAREA'"
      :model-value="stringValue"
      :label="label"
      :placeholder="placeholder"
      :rows="4"
      @update:model-value="(v: string) => onValue(v)"
    />
    <TextInput
      v-else-if="setting.control === 'INTEGER' || setting.control === 'NUMBER'"
      :model-value="numberText"
      :label="label"
      :placeholder="placeholder"
      @update:model-value="(v: string) => onValue(v)"
    />
    <TextInput
      v-else-if="setting.control === 'LIST'"
      :model-value="listText"
      :label="label"
      :placeholder="placeholder"
      :mono="setting.mono"
      @update:model-value="(v: string) => onValue(v)"
    />
    <Select
      v-else-if="setting.control === 'ENUM'"
      :model-value="stringValue"
      :options="enumOptions"
      :label="label"
      @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && onValue(v)"
    />
    <template v-else-if="setting.control === 'REFERENCE'">
      <Select
        :model-value="stringValue"
        :options="referenceOptions"
        :label="label"
        searchable
        :placeholder="placeholder"
        @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && onValue(v)"
      />
      <PipelineContractSummary
        v-if="selectedPipeline"
        :pipeline="selectedPipeline"
      />
      <Button
        v-if="peekPipelineId"
        class="peek-button"
        size="xs"
        icon="eye"
        @click="peeking = true"
      >
        Peek inside
      </Button>
      <PipelinePeekModal
        v-if="peeking && peekPipelineId"
        :pipeline-id="peekPipelineId"
        :pipeline-name="selectedPipeline?.label"
        @close="peeking = false"
      />
    </template>
    <PipelineJsonataField
      v-else-if="isJsonataCode"
      :model-value="stringValue"
      :label="label"
      :placeholder="placeholder"
      @update:model-value="(v: string) => onValue(v)"
    />
    <CodeEditor
      v-else-if="setting.control === 'CODE'"
      :model-value="stringValue"
      :label="label"
      :language="codeLanguage"
      :rows="8"
      :placeholder="placeholder"
      @update:model-value="(v: string) => onValue(v)"
    />
    <ConditionBuilder
      v-else-if="setting.control === 'CONDITION'"
      :model-value="stringValue"
      @update:model-value="(v: string) => onValue(v)"
    />
    <SchemaBuilder
      v-else-if="setting.control === 'SCHEMA'"
      :model-value="schemaValue"
      @update:model-value="(v: Record<string, unknown> | null) => onValue(v)"
    />

    <p
      v-if="setting.description && setting.control !== 'CONDITION'"
      class="hint"
    >
      {{ setting.description }}
    </p>
  </div>
</template>

<style scoped>
.setting-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.peek-button {
  align-self: flex-start;
}
.group-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.group-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
  border: 1px solid var(--border, rgba(148, 163, 184, 0.15));
  border-radius: 8px;
}
.group-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.group-row-num {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-muted, #94a3b8);
}
.group-row-actions {
  display: inline-flex;
  gap: 4px;
}
.hint {
  margin: 0;
  font-size: 11px;
  color: var(--text-muted, #94a3b8);
}
</style>
