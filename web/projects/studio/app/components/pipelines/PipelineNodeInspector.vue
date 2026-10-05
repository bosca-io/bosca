<script setup lang="ts">
import gql from 'graphql-tag'
import SchemaBuilder from '~/components/pipelines/SchemaBuilder.vue'
import PipelineSettingField from '~/components/pipelines/PipelineSettingField.vue'
import PipelineTypeIntrospect from '~/components/pipelines/PipelineTypeIntrospect.vue'
import PipelineShapeBuilder from '~/components/pipelines/PipelineShapeBuilder.vue'
import { isSettingVisible, type Coercion } from '~/components/pipelines/pipelineSettings'
import { shortTypeName } from '~/components/pipelines/pipelineNodeTypes'
import type { ReferenceContext, SettingMeta, PipelineOption, NodeFieldMeta, BrowsableType, ShapeField, MessageProjectInfo } from '~/components/pipelines/pipelineNodeTypes'

/**
 * Node settings panel for the pipeline editor. Each node type declares its editable settings as
 * metadata (`@SettingSlot` → `pipelines.nodeTypes.settings`); this renders them generically via
 * PipelineSettingField, so a new node type — including a contributed/integration one — gets its full
 * form with no change here. The only hand-written forms left are the two structural nodes (Input/Output,
 * whose contract two-way binds to page-level pipeline config) and the common Reliability block (shared
 * by every runnable node, not per-type).
 */
interface FlowNodeData {
  kind: string
  label: string
  category: string
  settings: Record<string, unknown>
}

/** One declared input requirement of the node type — shown in the "Expects" section. */
interface InputSlotMeta {
  name: string
  kind: string
  typeLabel: string
  description: string | null
  type: string | null
  schema: unknown
  required: boolean
  structure: NodeFieldMeta[] | null
}

/** What the node produces — a single typed output, or named ports (routing/error). */
interface OutputMeta {
  typeLabel: string | null
  description: string | null
  type: string | null
  kind: string
  structure: NodeFieldMeta[] | null
  outputs: { name: string, kind: string, error: boolean, type: string | null, typeLabel: string | null, description: string | null, structure: NodeFieldMeta[] | null }[]
}

const props = defineProps<{
  data: FlowNodeData
  /** The node type's declared settings — the form rendered generically. */
  settings: SettingMeta[]
  expects: InputSlotMeta[]
  produces: OutputMeta
  scripts: { value: string, label: string }[]
  jobs: { value: string, label: string }[]
  events: { value: string, label: string }[]
  searchIndexes: { value: string, label: string }[]
  attributeTypes: { value: string, label: string }[]
  secrets: { value: string, label: string }[]
  segments: { value: string, label: string }[]
  pipelines: PipelineOption[]
  gitPipelines: { value: string, label: string }[]
  /** The global environment-type catalog — a relay's Get Environment names a type, resolved per-run. */
  environmentTypes?: { value: string, label: string }[]
  /** Hosted message projects with each template's payload contract — the MESSAGE_PROJECT/MESSAGE_TEMPLATE references. */
  messageProjects?: MessageProjectInfo[]
  /** The notification-type catalog — the NOTIFICATION_TYPE reference (a Send Message Template's type picker). */
  notificationTypes?: { value: string, label: string }[]
  /** The catalogued object types that flow between nodes — offered when declaring the Input/Output type. */
  types: BrowsableType[]
  /** For an Output wired from an Objects → Map, its detected shape fields — for one-click adoption. */
  detectedShape: ShapeField[] | null
}>()
const emit = defineEmits<{ change: [] }>()

// ─── Git artifact reference (node-dependent) ──────────────────────────────────────────────────
// GIT_ARTIFACT options are NOT a global list — they're the declared artifacts of the git-ci pipeline the
// node picks in its sibling `gitPipelineId` setting. Refetch whenever that selection changes.
const { query: gqlQuery } = useGraphQL()
const gitArtifactOptions = ref<{ value: string, label: string }[]>([])
watch(() => props.data.settings.gitPipelineId, async (pipelineId) => {
  const id = typeof pipelineId === 'string' ? pipelineId : ''
  if (!id) { gitArtifactOptions.value = []; return }
  try {
    const res = await gqlQuery<{ git: { declaredArtifacts: { type: string, namespace: string, coordinate: string }[] } }>(gql`
      query DeclaredArtifacts($pipelineId: UUID!) {
        git { declaredArtifacts(pipelineId: $pipelineId) { type namespace coordinate } }
      }
    `, { pipelineId: id })
    gitArtifactOptions.value = (res.git?.declaredArtifacts ?? []).map(a => ({
      value: a.coordinate,
      label: `${a.coordinate} · ${a.namespace} (${a.type})`,
    }))
  }
  catch { gitArtifactOptions.value = [] }
}, { immediate: true })

// ─── Message template reference (node-dependent) ────────────────────────────────────────────────
// MESSAGE_TEMPLATE options are NOT a global list — they're the templates of the message project the node
// picks in its sibling `project` setting. Resolved from the already-loaded hosted-project catalog (no
// refetch). A node saved before the project/template split stores `project/template-key` in `template`
// with no `project`; its prefix stands in for the missing setting until the author re-picks.
const selectedMessageProject = computed<MessageProjectInfo | undefined>(() => {
  const project = typeof props.data.settings.project === 'string' ? props.data.settings.project : ''
  const template = typeof props.data.settings.template === 'string' ? props.data.settings.template : ''
  const legacy = template.includes('/') ? template.slice(0, template.indexOf('/')) : ''
  return (props.messageProjects ?? []).find(p => p.project === (project || legacy))
})
const messageTemplateOptions = computed(() =>
  (selectedMessageProject.value?.templates ?? []).map(t => ({ value: t.key, label: t.key })))

// When the author picks a different message project, keep the template setting honest: a legacy
// `project/key` whose prefix matches the new project migrates to the bare key; a template the new
// project doesn't have is dropped rather than silently kept as an invalid pair.
watch(() => props.data.settings.project, (project, previous) => {
  if (project === previous || typeof project !== 'string') return
  const template = props.data.settings.template
  if (typeof template !== 'string' || !template) return
  const templates = (props.messageProjects ?? []).find(p => p.project === project)?.templates ?? []
  if (template.includes('/')) {
    const key = template.slice(template.indexOf('/') + 1)
    if (template.slice(0, template.indexOf('/')) === project && templates.some(t => t.key === key)) set('template', key)
    else unset('template')
    return
  }
  if (!templates.some(t => t.key === template)) unset('template')
})

/** One displayed row of the payload contract: a dotted path, a readable type, and the required flag. */
interface PayloadRow { name: string, type: string, required: boolean }

/** Flattens the JSON-Schema subset (type/properties/required/items/enum) into displayable rows. */
function payloadRows(schema: unknown, prefix = '', depth = 0): PayloadRow[] {
  if (depth > 4 || schema == null || typeof schema !== 'object') return []
  const s = schema as Record<string, unknown>
  const properties = s.properties != null && typeof s.properties === 'object' ? s.properties as Record<string, unknown> : null
  if (!properties) return []
  const required = Array.isArray(s.required) ? s.required as unknown[] : []
  const rows: PayloadRow[] = []
  for (const [name, raw] of Object.entries(properties)) {
    const p = raw != null && typeof raw === 'object' ? raw as Record<string, unknown> : {}
    const isArray = p.type === 'array'
    const item = isArray && p.items != null && typeof p.items === 'object' ? p.items as Record<string, unknown> : p
    const baseType = typeof item.type === 'string' ? item.type : 'any'
    const type = Array.isArray(item.enum)
      ? (item.enum as unknown[]).map(v => String(v)).join(' | ')
      : isArray ? `${baseType}[]` : baseType
    const path = prefix ? `${prefix}.${name}` : name
    rows.push({ name: path, type, required: required.includes(name) })
    rows.push(...payloadRows(item, isArray ? `${path}[]` : path, depth + 1))
  }
  return rows
}

/**
 * The chosen message template's payload contract, derived server-side from the template's declared
 * payload serializer — so the author wiring the `payload` port sees exactly which fields the typed
 * decode expects and which must be present. Null on non-message-template nodes and until a template is picked.
 */
const messagePayloadContract = computed(() => {
  if (!props.settings.some(s => s.reference === 'MESSAGE_TEMPLATE')) return null
  const raw = typeof props.data.settings.template === 'string' ? props.data.settings.template : ''
  if (!raw) return null
  const key = raw.includes('/') ? raw.slice(raw.indexOf('/') + 1) : raw
  const template = selectedMessageProject.value?.templates.find(t => t.key === key)
  if (!template) return null
  const rows = payloadRows(template.payloadSchema)
  if (rows.length === 0 && template.samplePayload == null) return null
  return { rows, sample: template.samplePayload ?? null }
})
const showPayloadSample = ref(false)
const payloadSampleJson = computed(() =>
  messagePayloadContract.value?.sample != null ? JSON.stringify(messagePayloadContract.value.sample, null, 2) : '')

/** Required top-level fields a slot's JSON-schema demands (e.g. `id`), for the "must include" hint. */
function slotRequiredFields(schema: unknown): string[] {
  if (schema == null || typeof schema !== 'object' || Array.isArray(schema)) return []
  const req = (schema as Record<string, unknown>).required
  return Array.isArray(req) ? req.filter((r): r is string => typeof r === 'string') : []
}

/** Friendly name for an output kind when the node declares no explicit label. */
function friendlyKind(kind: string): string {
  switch (kind) {
    case 'OBJECT': return 'Object'
    case 'ARRAY': return 'List'
    case 'UUID': return 'Id (UUID)'
    case 'STRING': return 'Text'
    case 'INTEGER': return 'Integer'
    case 'NUMBER': return 'Number'
    case 'BOOLEAN': return 'Boolean'
    default: return 'A value'
  }
}
// Show "Produces" whenever the node says something concrete about its output — a typed label, named
// ports (routing/error), or a non-dynamic kind. Pure dynamic/no-output nodes get no misleading line.
const showProduces = computed(() =>
  props.produces.outputs.length > 0 || !!props.produces.typeLabel || props.produces.kind !== 'ANY')

// ─── Settings mutation (the single owner of data.settings) ────────────────────────────────────
function set(key: string, value: unknown) {
  props.data.settings[key] = value
  emit('change')
}
/** Remove a setting entirely — the backend models default a value when the key is absent. */
function unset(key: string) {
  delete props.data.settings[key]
  emit('change')
}
/** Apply a field's set/unset decision (computed by the tested coercion helper). */
function applySetting(name: string, coercion: Coercion) {
  if (coercion.action === 'unset') unset(name)
  else set(name, coercion.value)
}
function str(key: string): string {
  const v = props.data.settings[key]
  return v == null ? '' : String(v)
}
/**
 * Numeric fields with a backend default: store a positive integer, otherwise drop the key so the field
 * can sit empty mid-edit and the default (shown as the placeholder) applies at run time.
 */
function setPositiveInt(key: string, v: string) {
  const n = Number.parseInt(v, 10)
  if (n > 0) set(key, n)
  else unset(key)
}

// ─── Generic settings: the node type's declared form ──────────────────────────────────────────
const references = computed<ReferenceContext>(() => ({
  JOB: props.jobs,
  SCRIPT: props.scripts,
  EVENT: props.events,
  PIPELINE: props.pipelines,
  ATTRIBUTE_TYPE: props.attributeTypes,
  SEARCH_INDEX: props.searchIndexes,
  SECRET: props.secrets,
  SEGMENT: props.segments,
  GIT_PIPELINE: props.gitPipelines,
  GIT_ARTIFACT: gitArtifactOptions.value,
  TYPE: props.types.map(t => ({ value: t.name, label: t.shortName })),
  ENVIRONMENT_TYPE: props.environmentTypes ?? [],
  MESSAGE_PROJECT: (props.messageProjects ?? []).map(p => ({ value: p.project, label: p.project })),
  MESSAGE_TEMPLATE: messageTemplateOptions.value,
  NOTIFICATION_TYPE: props.notificationTypes ?? [],
}))

const visibleSettings = computed(() => props.settings.filter(s => isSettingVisible(s, props.data.settings, props.settings)))
/** Settings with no section header — rendered first, in declared order. */
const ungroupedSettings = computed(() => visibleSettings.value.filter(s => !s.group))
/** Settings grouped under a section header (e.g. "Index only when"), preserving first-seen order. */
const groupedSettings = computed(() => {
  const groups: { name: string, settings: SettingMeta[] }[] = []
  for (const s of visibleSettings.value) {
    if (!s.group) continue
    const existing = groups.find(g => g.name === s.group)
    if (existing) existing.settings.push(s)
    else groups.push({ name: s.group, settings: [s] })
  }
  return groups
})

// ─── Input/output contract (the two structural nodes, page-state coupled) ─────────────────────
const INPUT_MODES = [
  { value: 'event', label: 'A catalogued event' },
  { value: 'shape', label: 'An object shape (typed fields)' },
  { value: 'json', label: 'JSON (callable pipeline)' },
]
const inputMode = computed(() => {
  const t = str('acceptedType')
  return t === 'JSON' ? 'json' : t === 'SHAPE' ? 'shape' : 'event'
})
function setInputMode(mode: string) {
  if (mode === 'json') { set('acceptedType', 'JSON'); set('fields', []) }
  else if (mode === 'shape') { set('acceptedType', 'SHAPE'); set('schema', null) }
  else { set('acceptedType', ''); set('schema', null); set('fields', []) }
}

// A declared object shape's typed fields (Input/Output), and the per-field type choices: primitives,
// JSON, and every catalogued type in both single and `[]` list form.
const shapeFields = computed<ShapeField[]>(() =>
  Array.isArray(props.data.settings.fields) ? props.data.settings.fields as ShapeField[] : [],
)
function setShapeFields(fields: ShapeField[]) { set('fields', fields) }
/** One-click: adopt the shape detected from a wired Objects → Map as the declared output. */
function useDetectedShape() {
  if (!props.detectedShape?.length) return
  set('outputType', 'SHAPE')
  set('schema', null)
  set('fields', props.detectedShape)
}
const shapeTypeOptions = computed(() => [
  { value: 'String', label: 'String' },
  { value: 'Number', label: 'Number' },
  { value: 'Boolean', label: 'Boolean' },
  { value: 'UUID', label: 'UUID' },
  { value: 'JSON', label: 'JSON' },
  ...props.types.map(t => ({ value: t.name, label: t.shortName })),
])

// ─── JSONata: auto-detect the produced shape from the expression's object construction ───────────

/** A shape detected from a JSONata expression: its top-level object keys (+ whether it maps an array). */
interface DetectedJsonataShape { array: boolean, fields: ShapeField[] }

/**
 * Reads the shape a JSONata expression constructs — the keys of its (last, top-level) `{ … }` object
 * mapping, quote- and depth-aware. Field types are a best-effort guess from each value expression
 * (string/number/boolean literals; everything computed stays JSON). Purely a convenience: the author
 * reviews the detected fields before saving them as a reusable type.
 */
function detectJsonataShape(expression: string): DetectedJsonataShape | null {
  const trimmed = expression.trim()
  if (!trimmed) return null
  const array = trimmed.startsWith('[') && trimmed.endsWith(']')
  // Find the last top-level object block (a mapping is usually `path.{ … }` or a bare `{ … }`).
  let depth = 0
  let quote: string | null = null
  let start = -1
  let block: string | null = null
  for (let i = 0; i < trimmed.length; i++) {
    const c = trimmed[i]!
    if (quote) {
      if (c === quote && trimmed[i - 1] !== '\\') quote = null
      continue
    }
    if (c === '"' || c === '\'') { quote = c; continue }
    if (c === '{') { if (depth === 0) start = i; depth++ }
    else if (c === '}') { depth--; if (depth === 0 && start >= 0) block = trimmed.slice(start + 1, i) }
  }
  if (block === null) return null
  // Split the block into `key: value` entries at depth 0 (commas inside nested structures don't count).
  const fields: ShapeField[] = []
  let entry = ''
  depth = 0
  quote = null
  const entries: string[] = []
  for (let i = 0; i < block.length; i++) {
    const c = block[i]!
    if (quote) { entry += c; if (c === quote && block[i - 1] !== '\\') quote = null; continue }
    if (c === '"' || c === '\'') { quote = c; entry += c; continue }
    if (c === '{' || c === '[' || c === '(') depth++
    else if (c === '}' || c === ']' || c === ')') depth--
    if (c === ',' && depth === 0) { entries.push(entry); entry = ''; continue }
    entry += c
  }
  if (entry.trim()) entries.push(entry)
  for (const e of entries) {
    const m = e.match(/^\s*["']([^"']+)["']\s*:\s*([\s\S]+)$/) ?? e.match(/^\s*([A-Za-z_][\w]*)\s*:\s*([\s\S]+)$/)
    if (!m) continue
    const value = m[2]!.trim()
    const type = /^["']/.test(value) ? 'String'
      : /^-?\d/.test(value) ? 'Number'
        : value === 'true' || value === 'false' ? 'Boolean'
          : 'JSON'
    fields.push({ name: m[1]!, type })
  }
  return fields.length > 0 ? { array, fields } : null
}

const saveNamedShapeInj = inject<((name: string, fields: ShapeField[]) => Promise<void>) | null>('saveNamedShape', null)

/** The shape the JSONata expression constructs, when this node is a JSONata and one is detectable. */
const jsonataDetected = computed<DetectedJsonataShape | null>(() =>
  props.data.kind === 'jsonata' && saveNamedShapeInj ? detectJsonataShape(str('expression')) : null,
)

const jsonataShapeFormOpen = ref(false)
const jsonataShapeName = ref('')
const jsonataShapeSaving = ref(false)

/** Saves the detected fields as a named reusable type and declares it as this JSONata's output. */
async function adoptJsonataShape() {
  const detected = jsonataDetected.value
  const name = jsonataShapeName.value.trim()
  if (!detected || !name || !saveNamedShapeInj) return
  jsonataShapeSaving.value = true
  try {
    await saveNamedShapeInj(name, detected.fields)
    set('outputKind', detected.array ? 'ARRAY' : 'OBJECT')
    set('outputType', `shape:${name}`)
    jsonataShapeFormOpen.value = false
    jsonataShapeName.value = ''
  }
  finally {
    jsonataShapeSaving.value = false
  }
}

// The Output node's declared contract: undeclared (''), a JSON shape ('JSON'), or a specific catalogued
// object type (its serial name) — the same type catalog as the page-level input-type dropdown. A `[]`
// suffix marks a list of that type (e.g. `…ProjectRepository[]`); the backend stores/serves outputType
// verbatim, so no backend change. Declaring a type is what a Run Pipeline / For Each node reads as the
// sub-pipeline's produced type instead of "undeclared".
const ARRAY_SUFFIX = '[]'
const outputTypeOptions = computed(() => [
  { value: '', label: 'Undeclared' },
  { value: 'JSON', label: 'JSON (a shape)' },
  { value: 'SHAPE', label: 'Object shape (typed fields)' },
  ...props.types.map(t => ({ value: t.name, label: t.shortName })),
])
// The Select binds to the base type (without the array suffix), so it matches an option.
const outputTypeBase = computed(() => str('outputType').replace(/\[\]$/, ''))
const outputIsArray = computed(() => str('outputType').endsWith(ARRAY_SUFFIX))
const outputIsSpecificType = computed(() =>
  outputTypeBase.value !== '' && outputTypeBase.value !== 'JSON' && outputTypeBase.value !== 'SHAPE',
)

function setOutputBase(base: string) {
  if (base === 'JSON') { set('outputType', 'JSON'); set('fields', []); return }
  if (base === 'SHAPE') { set('outputType', 'SHAPE'); set('schema', null); return }
  if (base === '') { set('outputType', ''); set('schema', null); set('fields', []); return }
  // A specific object type — keep the current array flag and drop any JSON schema.
  set('outputType', outputIsArray.value ? base + ARRAY_SUFFIX : base)
  set('schema', null)
  set('fields', [])
}
function setOutputArray(v: boolean) {
  const base = outputTypeBase.value
  if (base === '' || base === 'JSON') return
  set('outputType', v ? base + ARRAY_SUFFIX : base)
}

function nodeSchema(): Record<string, unknown> | null {
  const s = props.data.settings.schema
  return s != null && typeof s === 'object' && !Array.isArray(s) ? s as Record<string, unknown> : null
}

// ─── Reliability (retry + timeout, common to every runnable node) ─────────────────────────────
// Input/Output are structural (seed/collect), not "run", so they have no reliability config.
const showReliability = computed(() => props.data.kind !== 'input' && props.data.kind !== 'output')
const retryConfig = computed(() => props.data.settings.retry as Record<string, unknown> | undefined)
const retryEnabled = computed(() => retryConfig.value != null)
function setRetryEnabled(on: boolean) {
  if (on) set('retry', { maxAttempts: 3 })
  else unset('retry')
}
function retryNum(key: string): string {
  const v = retryConfig.value?.[key]
  return v == null ? '' : String(v)
}
/** Write a numeric field of the retry policy; maxAttempts is required (never dropped below 1). */
function setRetryNum(key: string, v: string, isFloat = false) {
  const current = { ...(retryConfig.value ?? {}) }
  const n = isFloat ? Number.parseFloat(v) : Number.parseInt(v, 10)
  if (Number.isFinite(n) && n > 0) current[key] = n
  else if (key === 'maxAttempts') current[key] = 1
  else delete current[key]
  set('retry', current)
}
/** Rollback-pipeline options: "(none)" plus every pipeline. */
const rollbackOptions = computed(() => [{ value: '', label: '(none)' }, ...props.pipelines])
</script>

<template>
  <div class="inspector">
    <h4>{{ data.label }}</h4>

    <TextInput
      :model-value="str('name')"
      label="Name"
      :placeholder="data.label"
      @update:model-value="(v: string) => set('name', v)"
    />
    <Textarea
      :model-value="str('description')"
      label="Description"
      :rows="2"
      placeholder="What this node does in this pipeline"
      @update:model-value="(v: string) => set('description', v)"
    />

    <div
      v-if="expects.length"
      class="expects"
    >
      <span class="expects-title">Expects</span>
      <div
        v-for="slot in expects"
        :key="slot.name"
        class="expects-slot"
      >
        <span class="expects-head">
          <PipelineTypeIntrospect
            :type="slot.type"
            :label="slot.typeLabel"
          />
          <span :class="['expects-badge', slot.required ? 'is-required' : 'is-optional']">
            {{ slot.required ? 'required' : 'optional' }}
          </span>
        </span>
        <p
          v-if="slot.type && shortTypeName(slot.type) !== slot.typeLabel"
          class="expects-type"
        >
          <code>{{ slot.type }}</code>
        </p>
        <p
          v-if="slot.description"
          class="expects-desc"
        >
          {{ slot.description }}
        </p>
        <p
          v-if="slotRequiredFields(slot.schema).length"
          class="expects-fields"
        >
          Must include: <code>{{ slotRequiredFields(slot.schema).join(', ') }}</code>
        </p>
      </div>
    </div>

    <div
      v-if="showProduces"
      class="expects"
    >
      <span class="expects-title">Produces</span>
      <template v-if="produces.outputs.length">
        <div
          v-for="o in produces.outputs"
          :key="o.name"
          class="expects-slot"
        >
          <span class="expects-head">
            <PipelineTypeIntrospect
              :type="o.type"
              :label="o.typeLabel || o.name"
            />
            <span :class="['expects-badge', o.error ? 'is-required' : 'is-optional']">
              {{ o.error ? 'error port' : o.kind.toLowerCase() }}
            </span>
          </span>
          <p
            v-if="o.typeLabel"
            class="expects-port"
          >
            on <code>{{ o.name }}</code>
          </p>
          <p
            v-if="o.type && shortTypeName(o.type) !== (o.typeLabel || o.name)"
            class="expects-type"
          >
            <code>{{ o.type }}</code>
          </p>
          <p
            v-if="o.description"
            class="expects-desc"
          >
            {{ o.description }}
          </p>
        </div>
      </template>
      <div
        v-else
        class="expects-slot"
      >
        <span class="expects-head">
          <PipelineTypeIntrospect
            :type="produces.type"
            :label="produces.typeLabel || friendlyKind(produces.kind)"
          />
          <span class="expects-badge is-optional">{{ produces.kind.toLowerCase() }}</span>
        </span>
        <ul
          v-if="produces.structure?.length"
          class="produces-fields"
        >
          <li
            v-for="f in produces.structure"
            :key="f.name"
            class="produces-field"
          >
            <code class="pf-name">{{ f.name }}</code>
            <span class="pf-type">{{ f.type }}</span>
          </li>
        </ul>
        <p
          v-else-if="produces.type && shortTypeName(produces.type) !== (produces.typeLabel || friendlyKind(produces.kind))"
          class="expects-type"
        >
          <code>{{ produces.type }}</code>
        </p>
        <p
          v-if="produces.description"
          class="expects-desc"
        >
          {{ produces.description }}
        </p>
      </div>
    </div>

    <!-- Input node: the accepted-event / JSON-schema contract, two-way bound to page-level pipeline config. -->
    <template v-if="data.kind === 'input'">
      <Select
        :model-value="inputMode"
        :options="INPUT_MODES"
        label="Input source"
        @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && setInputMode(v)"
      />
      <template v-if="inputMode === 'event'">
        <Select
          :model-value="str('acceptedType')"
          :options="events"
          label="Accepts event"
          searchable
          placeholder="Pick the event this pipeline accepts…"
          @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && set('acceptedType', v)"
        />
      </template>
      <template v-else-if="inputMode === 'shape'">
        <PipelineShapeBuilder
          :model-value="shapeFields"
          :type-options="shapeTypeOptions"
          @update:model-value="setShapeFields"
        />
        <p class="hint">
          The object this pipeline accepts — its typed fields. A caller (e.g. a For Each body) sees this
          shape as the input contract.
        </p>
      </template>
      <template v-else>
        <SchemaBuilder
          :model-value="nodeSchema()"
          @update:model-value="(v: Record<string, unknown> | null) => set('schema', v)"
        />
        <p class="hint">
          The input contract for callers (Run Pipeline nodes and manual runs) — payloads are validated
          against it. No fields = any JSON. JSON-input pipelines can't be triggered by events.
        </p>
      </template>
    </template>

    <!-- Output node: the declared output contract shown to authors wiring this as a sub-pipeline. -->
    <template v-else-if="data.kind === 'output'">
      <Select
        :model-value="outputTypeBase"
        :options="outputTypeOptions"
        label="Declared output"
        searchable
        placeholder="Pick what this pipeline outputs…"
        @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && setOutputBase(v)"
      />
      <Button
        v-if="detectedShape?.length"
        size="xs"
        variant="ghost"
        icon="wand-2"
        class="detect-shape-btn"
        @click="useDetectedShape"
      >
        Use detected shape ({{ detectedShape.length }} {{ detectedShape.length === 1 ? 'field' : 'fields' }})
      </Button>
      <Switch
        v-if="outputIsSpecificType"
        :model-value="outputIsArray"
        label="A list of these"
        @update:model-value="setOutputArray"
      />
      <template v-if="outputTypeBase === 'SHAPE'">
        <PipelineShapeBuilder
          :model-value="shapeFields"
          :type-options="shapeTypeOptions"
          @update:model-value="setShapeFields"
        />
      </template>
      <template v-else-if="outputTypeBase === 'JSON'">
        <SchemaBuilder
          :model-value="nodeSchema()"
          @update:model-value="(v: Record<string, unknown> | null) => set('schema', v)"
        />
      </template>
      <p class="hint">
        The declared contract shown to authors wiring this pipeline as a sub-pipeline — pick a specific
        type, an object shape with typed fields, a JSON shape, or leave it undeclared. For a list, turn on
        “a list of these”. Declaration only.
      </p>
    </template>

    <!-- Every other node type: its declared settings, rendered generically. -->
    <template v-else>
      <PipelineSettingField
        v-for="s in ungroupedSettings"
        :key="s.name"
        :setting="s"
        :value="data.settings[s.name]"
        :references="references"
        @change="(c: Coercion) => applySetting(s.name, c)"
      />
      <!-- JSONata: the expression's object construction is a detectable shape — one click away from
           a named reusable type declared as this node's output. -->
      <template v-if="jsonataDetected">
        <Button
          v-if="!jsonataShapeFormOpen"
          size="xs"
          variant="ghost"
          icon="wand-2"
          class="detect-shape-btn"
          @click="jsonataShapeFormOpen = true"
        >
          Use detected shape ({{ jsonataDetected.fields.map(f => f.name).join(', ') }})
        </Button>
        <div
          v-else
          class="jsonata-shape-form"
        >
          <TextInput
            :model-value="jsonataShapeName"
            label="Save the detected shape as"
            placeholder="e.g. RepositoryTag"
            @update:model-value="(v: string) => { jsonataShapeName = v }"
          />
          <p class="hint">
            {{ jsonataDetected.array ? 'A list of' : 'An object of' }}
            {{ jsonataDetected.fields.map(f => `${f.name}: ${f.type}`).join(', ') }} — saved as a
            reusable type and declared as this node's output.
          </p>
          <div class="jsonata-shape-actions">
            <Button
              primary
              size="xs"
              :disabled="!jsonataShapeName.trim() || jsonataShapeSaving"
              @click="adoptJsonataShape"
            >
              Save &amp; declare
            </Button>
            <Button
              size="xs"
              variant="ghost"
              :disabled="jsonataShapeSaving"
              @click="jsonataShapeFormOpen = false"
            >
              Cancel
            </Button>
          </div>
        </div>
      </template>
      <div
        v-for="g in groupedSettings"
        :key="g.name"
        class="setting-group"
      >
        <p class="section-label">{{ g.name }}</p>
        <PipelineSettingField
          v-for="s in g.settings"
          :key="s.name"
          :setting="s"
          :value="data.settings[s.name]"
          :references="references"
          @change="(c: Coercion) => applySetting(s.name, c)"
        />
      </div>
      <!-- The chosen message template's typed payload contract — what the wired `payload` port must carry. -->
      <div
        v-if="messagePayloadContract"
        class="expects"
      >
        <span class="expects-title">Payload contract</span>
        <p class="expects-desc">
          What the wired payload must look like — derived from the template's typed contract.
        </p>
        <ul
          v-if="messagePayloadContract.rows.length"
          class="produces-fields"
        >
          <li
            v-for="f in messagePayloadContract.rows"
            :key="f.name"
            class="produces-field"
          >
            <span class="payload-name">
              <code class="pf-name">{{ f.name }}</code>
              <span
                v-if="f.required"
                class="expects-badge is-required"
              >required</span>
            </span>
            <span class="pf-type">{{ f.type }}</span>
          </li>
        </ul>
        <template v-if="payloadSampleJson">
          <Button
            size="xs"
            variant="ghost"
            :icon="showPayloadSample ? 'chevron-up' : 'chevron-down'"
            class="payload-sample-toggle"
            @click="showPayloadSample = !showPayloadSample"
          >
            {{ showPayloadSample ? 'Hide sample payload' : 'Show sample payload' }}
          </Button>
          <pre
            v-if="showPayloadSample"
            class="payload-sample"
          >{{ payloadSampleJson }}</pre>
        </template>
      </div>
    </template>

    <div
      v-if="showReliability"
      class="reliability"
    >
      <h5 class="reliability-title">
        Reliability
      </h5>
      <TextInput
        :model-value="str('timeoutSeconds')"
        label="Timeout (seconds)"
        placeholder="None"
        @update:model-value="(v: string) => setPositiveInt('timeoutSeconds', v)"
      />
      <Switch
        :model-value="retryEnabled"
        label="Retry on failure"
        @update:model-value="setRetryEnabled"
      />
      <template v-if="retryEnabled">
        <TextInput
          :model-value="retryNum('maxAttempts')"
          label="Max attempts"
          placeholder="3"
          @update:model-value="(v: string) => setRetryNum('maxAttempts', v)"
        />
        <TextInput
          :model-value="retryNum('initialDelaySeconds')"
          label="Initial delay (seconds)"
          placeholder="0"
          @update:model-value="(v: string) => setRetryNum('initialDelaySeconds', v)"
        />
        <TextInput
          :model-value="retryNum('multiplier')"
          label="Backoff multiplier"
          placeholder="1 = fixed, >1 = exponential"
          @update:model-value="(v: string) => setRetryNum('multiplier', v, true)"
        />
      </template>
      <p class="hint">
        A timeout fails the node when exceeded. Retries re-run it (with in-line backoff) before the
        failure propagates — configure them only on idempotent nodes.
      </p>
      <Select
        :model-value="str('rollbackPipeline')"
        :options="rollbackOptions"
        label="Rollback pipeline"
        searchable
        placeholder="(none)"
        @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && (v ? set('rollbackPipeline', v) : unset('rollbackPipeline'))"
      />
      <p class="hint">
        If this node completes and the run later fails, this pipeline runs — with this node's output as
        input — to undo the side effect. Completed rollback-enabled nodes roll back in reverse order.
      </p>
    </div>
  </div>
</template>

<style scoped>
.inspector {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
/* JSONata detected-shape adoption: name the shape, save it as a reusable type, declare it. */
.jsonata-shape-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border-radius: 8px;
  border: 1px solid var(--line, #2c3242);
  background: var(--bg-1, #171b26);
}
.jsonata-shape-actions {
  display: flex;
  gap: 8px;
}
/* The shared Select sizes its trigger to the widest option via an inline minWidth; in this narrow
   pane that overflows, so clamp it to the pane (the dropdown menu itself is teleported and fine). */
.inspector :deep(.select-trigger) {
  min-width: 0 !important;
  max-width: 100%;
}
.inspector > * {
  min-width: 0;
}
.inspector h4 {
  margin: 0;
  font-size: 13px;
}
.setting-group {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.reliability {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 4px;
  padding-top: 12px;
  border-top: 1px solid var(--border, rgba(148, 163, 184, 0.15));
}
.reliability-title {
  margin: 0;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--text-muted, #94a3b8);
}
.hint {
  margin: 0;
  font-size: 11px;
  color: var(--text-muted, #94a3b8);
}
.section-label {
  margin: 4px 0 0;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--text-muted, #94a3b8);
}
.expects {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border, rgba(148, 163, 184, 0.15));
  border-radius: 8px;
  background: var(--surface-2, rgba(148, 163, 184, 0.06));
}
.expects-title {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--text-muted, #94a3b8);
}
.expects-slot {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.expects-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.expects-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text, #e2e8f0);
}
.expects-badge {
  font-size: 9px;
  font-weight: 600;
  letter-spacing: 0.04em;
  padding: 1px 6px;
  border-radius: 6px;
}
.expects-badge.is-required {
  color: var(--accent, #f59e0b);
  background: color-mix(in oklch, var(--accent, #f59e0b) 16%, transparent);
}
.expects-badge.is-optional {
  color: var(--text-muted, #94a3b8);
  background: var(--surface-3, rgba(148, 163, 184, 0.12));
}
.expects-desc {
  margin: 0;
  font-size: 11px;
  color: var(--text-muted, #94a3b8);
}
.expects-port {
  margin: 0;
  font-size: 10.5px;
  color: var(--text-muted, #94a3b8);
}
.expects-port code {
  font-family: var(--font-mono, monospace);
  color: var(--text, #e2e8f0);
}
.expects-type {
  margin: 0;
}
.expects-type code {
  font-family: var(--font-mono, monospace);
  font-size: 10.5px;
  color: var(--text-muted, #94a3b8);
  word-break: break-all;
}
.produces-fields {
  list-style: none;
  margin: 6px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.produces-field {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}
.pf-name {
  font-family: var(--font-mono, monospace);
  font-size: 11.5px;
  color: var(--text, #e2e8f0);
}
.pf-type {
  font-size: 11px;
  color: var(--text-muted, #94a3b8);
}
.expects-fields {
  margin: 0;
  font-size: 11px;
  color: var(--text-muted, #94a3b8);
}
.expects-fields code {
  font-family: var(--font-mono, monospace);
  color: var(--text, #e2e8f0);
}
.payload-name {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}
.payload-sample-toggle {
  align-self: flex-start;
}
.payload-sample {
  margin: 0;
  padding: 8px;
  border-radius: 6px;
  border: 1px solid var(--border, rgba(148, 163, 184, 0.15));
  background: var(--bg-1, #171b26);
  font-family: var(--font-mono, monospace);
  font-size: 10.5px;
  line-height: 1.5;
  color: var(--text, #e2e8f0);
  overflow-x: auto;
}
</style>
