<script setup lang="ts">
/**
 * Structured builder for a Condition node: field / operator / value rows combined with ALL/ANY,
 * compiled to a canonical JSONata boolean expression (what the node stores and the engine runs).
 * The canonical form parses back into rows on reopen; anything hand-written that the builder can't
 * represent flips it into Advanced (raw JSONata) mode instead of mangling the expression.
 */
const model = defineModel<string>({ default: '' })

interface Row { id: string, field: string, op: string, value: string }

let seq = 0
function nextId(): string {
  seq += 1
  return `c${seq}`
}

const OPS = [
  { value: 'exists', label: 'exists' },
  { value: '=', label: '=' },
  { value: '!=', label: '≠' },
  { value: '>', label: '>' },
  { value: '>=', label: '≥' },
  { value: '<', label: '<' },
  { value: '<=', label: '≤' },
  { value: 'contains', label: 'contains' },
]
const COMBINATORS = [
  { value: 'and', label: 'ALL conditions must match' },
  { value: 'or', label: 'ANY condition matches' },
]

const rows = ref<Row[]>([])
const combinator = ref<'and' | 'or'>('and')
const advanced = ref(false)

// ─── Compile rows → canonical JSONata ────────────────────────────────────────────────────────
function literal(raw: string): string {
  const t = raw.trim()
  if (t === 'true' || t === 'false') return t
  if (t !== '' && !Number.isNaN(Number(t))) return t
  return JSON.stringify(t)
}
function compile(): string {
  const clauses = rows.value
    .filter(r => r.field.trim().length > 0)
    .map((r) => {
      if (r.op === 'exists') return r.field.trim()
      if (r.op === 'contains') return `$contains(${r.field.trim()}, ${JSON.stringify(r.value)})`
      return `${r.field.trim()} ${r.op} ${literal(r.value)}`
    })
  return clauses.join(` ${combinator.value} `)
}
function sync() {
  model.value = compile()
}

// ─── Parse canonical JSONata → rows (fall back to Advanced when it isn't canonical) ──────────
const CLAUSE = /^([\w$.[\]]+)\s*(!=|>=|<=|=|>|<)\s*(.+)$/
const CONTAINS = /^\$contains\(([\w$.[\]]+),\s*"((?:[^"\\]|\\.)*)"\)$/
const FIELD_ONLY = /^[\w$.[\]]+$/

function parseValue(v: string): string {
  const t = v.trim()
  if (t.startsWith('"') && t.endsWith('"')) {
    try {
      return JSON.parse(t) as string
    }
    catch {
      return t
    }
  }
  return t
}
/** Pure parse: canonical expression → builder state, or null when it isn't canonical. */
function tryParse(expression: string): { rows: Row[], combinator: 'and' | 'or' } | null {
  const expr = expression.trim()
  if (!expr) {
    return { rows: [{ id: nextId(), field: '', op: 'exists', value: '' }], combinator: 'and' }
  }
  const usesAnd = expr.includes(' and ')
  const usesOr = expr.includes(' or ')
  if (usesAnd && usesOr) return null
  const parts = expr.split(usesOr ? ' or ' : ' and ').map(p => p.trim())
  const parsed: Row[] = []
  for (const part of parts) {
    const contains = part.match(CONTAINS)
    if (contains) {
      parsed.push({ id: nextId(), field: contains[1] ?? '', op: 'contains', value: contains[2] ?? '' })
      continue
    }
    const cmp = part.match(CLAUSE)
    if (cmp) {
      parsed.push({ id: nextId(), field: cmp[1] ?? '', op: cmp[2] ?? '=', value: parseValue(cmp[3] ?? '') })
      continue
    }
    if (FIELD_ONLY.test(part)) {
      parsed.push({ id: nextId(), field: part, op: 'exists', value: '' })
      continue
    }
    return null
  }
  return { rows: parsed, combinator: usesOr ? 'or' : 'and' }
}

function applyParse(expression: string): boolean {
  const result = tryParse(expression)
  if (!result) return false
  rows.value = result.rows
  combinator.value = result.combinator
  return true
}

advanced.value = !applyParse(model.value)

function addRow() {
  rows.value.push({ id: nextId(), field: '', op: 'exists', value: '' })
  sync()
}
function removeRow(id: string) {
  rows.value = rows.value.filter(r => r.id !== id)
  sync()
}
function toAdvanced() {
  advanced.value = true
}
function toBuilder() {
  if (applyParse(model.value)) advanced.value = false
}
const builderAvailable = computed(() => tryParse(model.value) !== null)
</script>

<template>
  <div class="condition-builder">
    <template v-if="!advanced">
      <div class="combinator">
        <span class="lbl">True when</span>
        <Select
          :model-value="combinator"
          :options="COMBINATORS"
          size="sm"
          @update:model-value="(v: string | string[] | null | undefined) => { if (v === 'and' || v === 'or') { combinator = v; sync() } }"
        />
      </div>
      <div
        v-for="row in rows"
        :key="row.id"
        class="row"
      >
        <div class="row-field">
          <TextInput
            :model-value="row.field"
            size="sm"
            mono
            placeholder="field (e.g. properties.email)"
            @update:model-value="(v: string) => { row.field = v; sync() }"
          />
          <Button
            size="xs"
            icon="x"
            @click="removeRow(row.id)"
          />
        </div>
        <div class="row-op">
          <Select
            :model-value="row.op"
            :options="OPS"
            size="sm"
            @update:model-value="(v: string | string[] | null | undefined) => { if (typeof v === 'string') { row.op = v; sync() } }"
          />
          <TextInput
            v-if="row.op !== 'exists'"
            :model-value="row.value"
            size="sm"
            placeholder="value"
            @update:model-value="(v: string) => { row.value = v; sync() }"
          />
        </div>
      </div>
      <div class="actions">
        <Button
          size="xs"
          icon="plus"
          @click="addRow"
        >
          Add condition
        </Button>
        <Button
          size="xs"
          @click="toAdvanced"
        >
          Advanced (JSONata)
        </Button>
      </div>
    </template>

    <template v-else>
      <CodeEditor
        :model-value="model"
        label="Condition (JSONata — truthy takes the true branch)"
        language="javascript"
        :rows="4"
        placeholder="properties.count > 5"
        @update:model-value="(v: string) => model = v"
      />
      <div class="actions">
        <Button
          size="xs"
          :disabled="!builderAvailable"
          :title="builderAvailable ? '' : 'The expression is more complex than the builder supports'"
          @click="toBuilder"
        >
          Back to builder
        </Button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.condition-builder {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.combinator {
  display: flex;
  align-items: center;
  gap: 8px;
}
.lbl {
  font-size: 12px;
  color: var(--text-muted, #94a3b8);
}
/* Stacked rows: the field gets the full pane width (paths like properties.email need room),
   operator + value sit underneath. */
.row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px;
  border: 1px solid var(--border, rgba(148, 163, 184, 0.15));
  border-radius: 8px;
}
.row-field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px;
  align-items: center;
}
.row-op {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: 6px;
  align-items: center;
}
.row-field > *,
.row-op > * {
  min-width: 0;
}
.actions {
  display: flex;
  gap: 8px;
}
</style>
