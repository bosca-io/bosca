<script setup lang="ts">
/**
 * Structured builder for a pipeline input/output JSON schema: field name / type / required rows
 * compiled to the engine's JSON Schema subset ({type: "object", required, properties}). The
 * canonical form parses back into rows on reopen; a schema using anything beyond the subset
 * (nested property schemas, extra keywords) flips into Advanced (raw JSON) mode instead of
 * mangling it. An empty builder models "no schema" (null) — any JSON accepted.
 */
const model = defineModel<Record<string, unknown> | null>({ default: null })

interface Row { id: string, name: string, type: string, required: boolean }

let seq = 0
function nextId(): string {
  seq += 1
  return `f${seq}`
}

const TYPES = [
  { value: 'string', label: 'string' },
  { value: 'number', label: 'number' },
  { value: 'integer', label: 'integer' },
  { value: 'boolean', label: 'boolean' },
  { value: 'object', label: 'object' },
  { value: 'array', label: 'array' },
]

const rows = ref<Row[]>([])
const advanced = ref(false)
const rawSchema = ref('')
const rawError = ref('')

// ─── Compile rows → canonical schema (null when no fields are named) ─────────────────────────
function compile(): Record<string, unknown> | null {
  const named = rows.value.filter(r => r.name.trim().length > 0)
  if (named.length === 0) return null
  const properties: Record<string, unknown> = {}
  for (const r of named) properties[r.name.trim()] = { type: r.type }
  const required = named.filter(r => r.required).map(r => r.name.trim())
  const schema: Record<string, unknown> = { type: 'object', properties }
  if (required.length > 0) schema.required = required
  return schema
}
function sync() {
  model.value = compile()
}

// ─── Parse canonical schema → rows (null when it needs Advanced) ──────────────────────────────
const TYPE_VALUES = new Set(TYPES.map(t => t.value))

/** Pure parse: canonical subset schema → builder rows, or null when it isn't canonical. */
function tryParse(schema: unknown): Row[] | null {
  if (schema == null) return [{ id: nextId(), name: '', type: 'string', required: false }]
  if (typeof schema !== 'object' || Array.isArray(schema)) return null
  const s = schema as Record<string, unknown>
  if (s.type !== 'object') return null
  const allowedKeys = new Set(['type', 'properties', 'required'])
  if (Object.keys(s).some(k => !allowedKeys.has(k))) return null
  const required = Array.isArray(s.required) ? s.required.filter((r): r is string => typeof r === 'string') : []
  const properties = (s.properties ?? {}) as Record<string, unknown>
  if (typeof properties !== 'object' || Array.isArray(properties)) return null
  const parsed: Row[] = []
  for (const [name, prop] of Object.entries(properties)) {
    if (prop == null || typeof prop !== 'object' || Array.isArray(prop)) return null
    const p = prop as Record<string, unknown>
    if (Object.keys(p).some(k => k !== 'type')) return null
    if (typeof p.type !== 'string' || !TYPE_VALUES.has(p.type)) return null
    parsed.push({ id: nextId(), name, type: p.type, required: required.includes(name) })
  }
  if (parsed.length === 0) parsed.push({ id: nextId(), name: '', type: 'string', required: false })
  return parsed
}

function applyParse(schema: unknown): boolean {
  const parsed = tryParse(schema)
  if (!parsed) return false
  rows.value = parsed
  return true
}

advanced.value = !applyParse(model.value)
rawSchema.value = model.value ? JSON.stringify(model.value, null, 2) : ''

function addRow() {
  rows.value.push({ id: nextId(), name: '', type: 'string', required: false })
  sync()
}
function removeRow(id: string) {
  rows.value = rows.value.filter(r => r.id !== id)
  sync()
}
function toAdvanced() {
  rawSchema.value = model.value ? JSON.stringify(model.value, null, 2) : ''
  rawError.value = ''
  advanced.value = true
}
function toBuilder() {
  if (applyParse(model.value)) advanced.value = false
}
function applyRaw(v: string) {
  rawSchema.value = v
  if (v.trim() === '') {
    rawError.value = ''
    model.value = null
    return
  }
  try {
    const parsed = JSON.parse(v) as unknown
    if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      rawError.value = 'The schema must be a JSON object'
      return
    }
    rawError.value = ''
    model.value = parsed as Record<string, unknown>
  }
  catch {
    rawError.value = 'Not valid JSON yet — keep typing'
  }
}
const builderAvailable = computed(() => tryParse(model.value) !== null)
</script>

<template>
  <div class="schema-builder">
    <template v-if="!advanced">
      <div
        v-for="row in rows"
        :key="row.id"
        class="row"
      >
        <div class="row-name">
          <TextInput
            :model-value="row.name"
            size="sm"
            mono
            placeholder="field name"
            @update:model-value="(v: string) => { row.name = v; sync() }"
          />
          <Button
            size="xs"
            icon="x"
            @click="removeRow(row.id)"
          />
        </div>
        <div class="row-type">
          <Select
            :model-value="row.type"
            :options="TYPES"
            size="sm"
            @update:model-value="(v: string | string[] | null | undefined) => { if (typeof v === 'string') { row.type = v; sync() } }"
          />
          <Switch
            :model-value="row.required"
            label="required"
            @update:model-value="(v: boolean) => { row.required = v; sync() }"
          />
        </div>
      </div>
      <div class="actions">
        <Button
          size="xs"
          icon="plus"
          @click="addRow"
        >
          Add field
        </Button>
        <Button
          size="xs"
          @click="toAdvanced"
        >
          Advanced (JSON Schema)
        </Button>
      </div>
    </template>

    <template v-else>
      <CodeEditor
        :model-value="rawSchema"
        label="Schema (JSON Schema subset: type, properties, required, items, enum)"
        language="json"
        :rows="8"
        @update:model-value="applyRaw"
      />
      <p
        v-if="rawError"
        class="error"
      >
        {{ rawError }}
      </p>
      <div class="actions">
        <Button
          size="xs"
          :disabled="!builderAvailable"
          :title="builderAvailable ? '' : 'The schema uses more than flat typed fields — edit it as JSON'"
          @click="toBuilder"
        >
          Back to builder
        </Button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.schema-builder {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px;
  border: 1px solid var(--border, rgba(148, 163, 184, 0.15));
  border-radius: 8px;
}
.row-name {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px;
  align-items: center;
}
.row-type {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: 6px;
  align-items: center;
}
.row-name > *,
.row-type > * {
  min-width: 0;
}
.actions {
  display: flex;
  gap: 8px;
}
.error {
  margin: 0;
  font-size: 11px;
  color: var(--danger, #f87171);
}
</style>
