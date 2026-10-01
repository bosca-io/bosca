<script setup lang="ts">
/**
 * Targeting-rules editor for the feature-flag detail page.
 *
 * Each rule is an ordered list of conditions plus a rollout that splits
 * matched users across variations by weight. Conditions are modeled as a
 * discriminated union so the editor renders the right fields per type and
 * the GraphQL `ConditionInput` shape it serialises matches what the
 * server's evaluator reads. The five condition variants are: Segment,
 * Principal (specific users), ProfileAttribute, DeviceAttribute, and
 * FlagDependency (prerequisite flag must resolve to a given variation).
 *
 * Deletion is gated behind a confirm prompt that lists any running
 * experiments depending on the rule, so the operator cannot silently
 * break them.
 */

import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

// ── Type model ──────────────────────────────────────────────────────

type ConditionType = 'Segment' | 'Principal' | 'ProfileAttribute' | 'DeviceAttribute' | 'FlagDependency'

export type AttrOperator =
  | 'EQUALS' | 'NOT_EQUALS'
  | 'IN' | 'NOT_IN'
  | 'CONTAINS' | 'STARTS_WITH' | 'ENDS_WITH'
  | 'GREATER_THAN' | 'LESS_THAN' | 'GTE' | 'LTE'
  | 'SEMVER_GT' | 'SEMVER_LT' | 'SEMVER_GTE' | 'SEMVER_LTE'

type AttrValue = string | number | boolean | null | string[]

interface SegmentCondition {
  type: 'Segment'
  negate: boolean
  segmentId: string | null
}

interface PrincipalCondition {
  type: 'Principal'
  negate: boolean
  principalIds: string[]
}

interface ProfileAttributeCondition {
  type: 'ProfileAttribute'
  negate: boolean
  typeId: string
  key: string
  operator: AttrOperator
  value: AttrValue
}

interface DeviceAttributeCondition {
  type: 'DeviceAttribute'
  negate: boolean
  key: string
  operator: AttrOperator
  value: AttrValue
}

interface FlagDependencyCondition {
  type: 'FlagDependency'
  negate: boolean
  flagKey: string
  requiredVariationKey: string
}

type AttributeCondition = ProfileAttributeCondition | DeviceAttributeCondition
type Condition = SegmentCondition | PrincipalCondition | AttributeCondition | FlagDependencyCondition

interface VariationWeight {
  variationKey: string
  weight: number
}

interface Rollout {
  variationWeights: VariationWeight[]
}

interface TargetingRule {
  id: string
  name?: string | null
  description?: string | null
  conditions: Condition[]
  rollout: Rollout
}

interface Variation {
  key: string
  name?: string
}

interface SegmentRow { id: string; name: string }
interface ProfileAttributeTypeRow { id: string; name: string; description?: string | null }

// ── Props / model ───────────────────────────────────────────────────

// `unknown[]` because the GraphQL `targetingRules` column is a JSON blob
// — anything could be in there. `normalizeRule` repairs malformed rules
// into the editor's typed shape, and writes back go through the typed
// helpers so the v-model emit shape is well-formed even if the input
// wasn't.
const model = defineModel<unknown[]>({ default: () => [] })
const props = defineProps<{
  variations?: Variation[]
  accent?: string
  /**
   * Map from rule id to the names of running experiments depending on
   * it. Used in the deletion confirm prompt so operators can see what
   * will silently break if they delete the rule.
   */
  runningExperimentsByRuleId?: Record<string, string[]>
}>()

// ── Lookups (segments, profile attribute types) ─────────────────────

const { useAsyncQuery } = useGraphQL()

const segmentsGql = gql`
  query GetSegmentsForTargeting {
    segments { all(limit: 100, offset: 0) { id name } }
  }
`

const profileAttributeTypesGql = gql`
  query GetProfileAttributeTypesForTargeting {
    profiles { attributeTypes { all { id name description } } }
  }
`

const { data: segmentsData } = useAsyncQuery<{
  segments: { all: SegmentRow[] }
}>('targeting-segments', segmentsGql, {}, { server: false })

const { data: profileTypesData } = useAsyncQuery<{
  profiles: { attributeTypes: { all: ProfileAttributeTypeRow[] } }
}>('targeting-profile-types', profileAttributeTypesGql, {}, { server: false })

const availableSegments = computed<SegmentRow[]>(() => segmentsData.value?.segments?.all ?? [])
const availableProfileTypes = computed<ProfileAttributeTypeRow[]>(
  () => profileTypesData.value?.profiles?.attributeTypes?.all ?? [],
)

// ── Option lists ────────────────────────────────────────────────────

const CONDITION_TYPE_OPTIONS: SelectOption[] = [
  { value: 'Segment', label: 'Segment' },
  { value: 'Principal', label: 'Specific Users' },
  { value: 'ProfileAttribute', label: 'Profile Attribute' },
  { value: 'DeviceAttribute', label: 'Device Attribute' },
  { value: 'FlagDependency', label: 'Flag Dependency' },
]

const OPERATOR_OPTIONS: SelectOption[] = [
  { value: 'EQUALS', label: 'equals' },
  { value: 'NOT_EQUALS', label: 'does not equal' },
  { value: 'IN', label: 'is one of' },
  { value: 'NOT_IN', label: 'is none of' },
  { value: 'CONTAINS', label: 'contains' },
  { value: 'STARTS_WITH', label: 'starts with' },
  { value: 'ENDS_WITH', label: 'ends with' },
  { value: 'GREATER_THAN', label: '> (numeric)' },
  { value: 'LESS_THAN', label: '< (numeric)' },
  { value: 'GTE', label: '≥ (numeric)' },
  { value: 'LTE', label: '≤ (numeric)' },
  { value: 'SEMVER_GT', label: '> (semver)' },
  { value: 'SEMVER_LT', label: '< (semver)' },
  { value: 'SEMVER_GTE', label: '≥ (semver)' },
  { value: 'SEMVER_LTE', label: '≤ (semver)' },
]

// Device attribute keys recognized by the server's targeting evaluator.
// These mirror the field names on the analytics SDK's Device class. Other
// keys are still allowed but will only match if the SDK has been extended
// to populate them.
const COMMON_DEVICE_KEYS = [
  'installationId', 'manufacturer', 'model', 'platform',
  'primaryLocale', 'systemName', 'timezone', 'type', 'version',
]

const DEVICE_KEY_OPTIONS: SelectOption[] = COMMON_DEVICE_KEYS.map((k) => ({ value: k, label: k }))

// ── Rule + condition helpers ────────────────────────────────────────

function newRuleId(): string {
  const c = typeof crypto !== 'undefined' ? (crypto as { randomUUID?: () => string }) : undefined
  if (c?.randomUUID) return c.randomUUID()
  return `rule-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function defaultRolloutWeights(): VariationWeight[] {
  const firstKey = props.variations?.[0]?.key
  if (!firstKey) return []
  return [{ variationKey: firstKey, weight: 1 }]
}

// Normalise legacy/raw rules into the editor's shape. Required because
// the GraphQL response uses `unknown` for the `targetingRules` JSON
// column — anything missing or wrong-shaped gets sane defaults so the
// editor never crashes on bad data.
function normalizeRule(raw: unknown): TargetingRule {
  const r = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  const rollout = (r.rollout && typeof r.rollout === 'object' ? r.rollout : {}) as Record<string, unknown>
  return {
    id: typeof r.id === 'string' && r.id.length > 0 ? r.id : newRuleId(),
    name: typeof r.name === 'string' ? r.name : null,
    description: typeof r.description === 'string' ? r.description : null,
    conditions: Array.isArray(r.conditions) ? (r.conditions as Condition[]) : [],
    rollout: {
      variationWeights: Array.isArray(rollout.variationWeights)
        ? (rollout.variationWeights as VariationWeight[])
        : defaultRolloutWeights(),
    },
  }
}

const rules = computed<TargetingRule[]>(() => {
  const raw = model.value
  if (!Array.isArray(raw)) return []
  return raw.map(normalizeRule)
})

function commitAll(next: TargetingRule[]) {
  model.value = next
}

function commitRule(index: number, rule: TargetingRule) {
  commitAll(rules.value.map((r, i) => (i === index ? rule : r)))
}

function updateRule(index: number, patch: Partial<TargetingRule>) {
  const rule = rules.value[index]
  if (!rule) return
  commitRule(index, { ...rule, ...patch })
}

function newCondition(type: ConditionType): Condition {
  switch (type) {
    case 'Segment':
      return { type: 'Segment', negate: false, segmentId: null }
    case 'Principal':
      return { type: 'Principal', negate: false, principalIds: [] }
    case 'ProfileAttribute':
      return {
        type: 'ProfileAttribute', negate: false,
        typeId: '', key: '', operator: 'EQUALS', value: '',
      }
    case 'DeviceAttribute':
      return {
        type: 'DeviceAttribute', negate: false,
        key: 'platform', operator: 'EQUALS', value: '',
      }
    case 'FlagDependency':
      return {
        type: 'FlagDependency', negate: false,
        flagKey: '', requiredVariationKey: '',
      }
  }
}

function addRule() {
  commitAll([
    ...rules.value,
    {
      id: newRuleId(),
      name: null,
      description: null,
      conditions: [],
      rollout: { variationWeights: defaultRolloutWeights() },
    },
  ])
}

function moveRule(index: number, dir: -1 | 1) {
  const target = index + dir
  if (target < 0 || target >= rules.value.length) return
  const next = [...rules.value]
  const a = next[index]
  const b = next[target]
  if (!a || !b) return
  next[index] = b
  next[target] = a
  commitAll(next)
}

function addCondition(ruleIndex: number, type: ConditionType) {
  const rule = rules.value[ruleIndex]
  if (!rule) return
  updateRule(ruleIndex, { conditions: [...rule.conditions, newCondition(type)] })
}

function removeCondition(ruleIndex: number, condIndex: number) {
  const rule = rules.value[ruleIndex]
  if (!rule) return
  updateRule(ruleIndex, { conditions: rule.conditions.filter((_, i) => i !== condIndex) })
}

// Merge a partial patch while preserving the discriminant `type`, so a
// stray patch can't accidentally turn a Segment condition into a
// ProfileAttribute. Type changes go through `changeConditionType`.
function updateCondition<T extends Condition>(
  ruleIndex: number,
  condIndex: number,
  patch: Partial<T>,
) {
  const rule = rules.value[ruleIndex]
  if (!rule) return
  const conditions = [...rule.conditions]
  const current = conditions[condIndex]
  if (!current) return
  conditions[condIndex] = { ...current, ...patch, type: current.type } as Condition
  updateRule(ruleIndex, { conditions })
}

function changeConditionType(ruleIndex: number, condIndex: number, type: ConditionType) {
  const rule = rules.value[ruleIndex]
  if (!rule) return
  const conditions = [...rule.conditions]
  conditions[condIndex] = newCondition(type)
  updateRule(ruleIndex, { conditions })
}

// Rollout editing helpers
function addVariationWeight(ruleIndex: number) {
  const rule = rules.value[ruleIndex]
  if (!rule) return
  const used = new Set(rule.rollout.variationWeights.map((vw) => vw.variationKey))
  const next = (props.variations ?? []).find((v) => !used.has(v.key))?.key
  if (!next) return
  updateRule(ruleIndex, {
    rollout: {
      variationWeights: [...rule.rollout.variationWeights, { variationKey: next, weight: 1 }],
    },
  })
}

function removeVariationWeight(ruleIndex: number, vwIndex: number) {
  const rule = rules.value[ruleIndex]
  if (!rule || rule.rollout.variationWeights.length <= 1) return
  updateRule(ruleIndex, {
    rollout: {
      variationWeights: rule.rollout.variationWeights.filter((_, i) => i !== vwIndex),
    },
  })
}

function updateVariationWeight(ruleIndex: number, vwIndex: number, patch: Partial<VariationWeight>) {
  const rule = rules.value[ruleIndex]
  if (!rule) return
  const next = [...rule.rollout.variationWeights]
  const current = next[vwIndex]
  if (!current) return
  next[vwIndex] = { ...current, ...patch }
  updateRule(ruleIndex, { rollout: { variationWeights: next } })
}

function totalWeight(rule: TargetingRule): number {
  return rule.rollout.variationWeights.reduce((sum, vw) => sum + (vw.weight || 0), 0)
}

function trafficPercent(rule: TargetingRule, vwIndex: number): string {
  const total = totalWeight(rule)
  if (!total) return '0%'
  const w = rule.rollout.variationWeights[vwIndex]?.weight || 0
  return `${((w / total) * 100).toFixed(1)}%`
}

const variationOptions = computed<SelectOption[]>(() =>
  (props.variations ?? []).map((v) => ({ value: v.key, label: v.name ? `${v.name} (${v.key})` : v.key })),
)

// ── Condition value parsing ─────────────────────────────────────────

function getPrincipalIdsString(c: Condition): string {
  return c.type === 'Principal' ? c.principalIds.join(', ') : ''
}

function setPrincipalIds(ruleIndex: number, condIndex: number, raw: string) {
  const ids = raw.split(',').map((s) => s.trim()).filter(Boolean)
  updateCondition<PrincipalCondition>(ruleIndex, condIndex, { principalIds: ids })
}

function attrValueAsString(c: AttributeCondition): string {
  if (c.operator === 'IN' || c.operator === 'NOT_IN') {
    return Array.isArray(c.value) ? c.value.join(', ') : ''
  }
  if (typeof c.value === 'string') return c.value
  if (c.value == null) return ''
  return String(c.value)
}

function setAttrValue(ruleIndex: number, condIndex: number, raw: string) {
  const rule = rules.value[ruleIndex]
  const cond = rule?.conditions[condIndex]
  if (!cond || (cond.type !== 'ProfileAttribute' && cond.type !== 'DeviceAttribute')) return
  let value: AttrValue
  if (cond.operator === 'IN' || cond.operator === 'NOT_IN') {
    value = raw.split(',').map((s) => s.trim()).filter(Boolean)
  } else if (
    cond.operator === 'GREATER_THAN' || cond.operator === 'LESS_THAN'
    || cond.operator === 'GTE' || cond.operator === 'LTE'
  ) {
    const n = Number(raw)
    value = Number.isNaN(n) ? raw : n
  } else {
    value = raw
  }
  if (cond.type === 'ProfileAttribute') {
    updateCondition<ProfileAttributeCondition>(ruleIndex, condIndex, { value })
  } else {
    updateCondition<DeviceAttributeCondition>(ruleIndex, condIndex, { value })
  }
}

// ── Delete confirmation ────────────────────────────────────────────

const confirmDeleteOpen = ref(false)
const confirmDeleteIndex = ref<number | null>(null)

const confirmDeleteRuleLabel = computed(() => {
  const idx = confirmDeleteIndex.value
  if (idx === null) return ''
  const rule = rules.value[idx]
  if (!rule) return ''
  return rule.name || rule.id.slice(0, 8)
})

const confirmDeleteDependents = computed<string[]>(() => {
  const idx = confirmDeleteIndex.value
  if (idx === null) return []
  const rule = rules.value[idx]
  if (!rule) return []
  return props.runningExperimentsByRuleId?.[rule.id] ?? []
})

function requestRemoveRule(index: number) {
  confirmDeleteIndex.value = index
  confirmDeleteOpen.value = true
}

function cancelRemoveRule() {
  confirmDeleteOpen.value = false
  confirmDeleteIndex.value = null
}

function performRemoveRule() {
  const idx = confirmDeleteIndex.value
  confirmDeleteOpen.value = false
  confirmDeleteIndex.value = null
  if (idx === null) return
  commitAll(rules.value.filter((_, i) => i !== idx))
}

// ── Display helpers ─────────────────────────────────────────────────

function matchesSummary(rule: TargetingRule): string {
  const count = rule.conditions?.length ?? 0
  if (count === 0) return 'Matches everyone'
  return `Matches when all ${count} condition${count === 1 ? '' : 's'} pass`
}

// Narrow helpers so the template doesn't need `as any` casts.
function asSegment(c: Condition): SegmentCondition {
  return c as SegmentCondition
}
function asProfileAttr(c: Condition): ProfileAttributeCondition {
  return c as ProfileAttributeCondition
}
function asDeviceAttr(c: Condition): DeviceAttributeCondition {
  return c as DeviceAttributeCondition
}
function asFlagDep(c: Condition): FlagDependencyCondition {
  return c as FlagDependencyCondition
}

const accentColor = computed(() => props.accent ?? 'var(--brand-2)')

const segmentOptions = computed<SelectOption[]>(() =>
  availableSegments.value.map((s) => ({ value: s.id, label: s.name })),
)

const profileTypeOptions = computed<SelectOption[]>(() =>
  availableProfileTypes.value.map((t) => ({ value: t.id, label: t.name })),
)
</script>

<template>
  <div class="rules-editor">
    <div
      v-for="(rule, ruleIndex) in rules"
      :key="rule.id"
      class="rule-card"
    >
      <!-- Rule header: number badge, name/summary, reorder + delete buttons -->
      <div class="rule-header">
        <Badge :color="accentColor">Rule {{ ruleIndex + 1 }}</Badge>
        <span v-if="rule.name" class="rule-name-label">{{ rule.name }}</span>
        <span class="rule-summary">{{ matchesSummary(rule) }}</span>
        <code class="rule-id mono">{{ rule.id.slice(0, 8) }}</code>
        <span class="spacer" />
        <Button
          size="xs"
          icon="chevron-up"
          :disabled="ruleIndex === 0"
          @click="moveRule(ruleIndex, -1)" />
        <Button
          size="xs"
          icon="chevron-down"
          :disabled="ruleIndex === rules.length - 1"
          @click="moveRule(ruleIndex, 1)" />
        <Button
          size="xs"
          icon="trash"
          danger
          @click="requestRemoveRule(ruleIndex)" />
      </div>

      <div class="rule-body">
        <!-- Name + description -->
        <div class="form-grid-2">
          <FormField label="Name" help="A short label shown in dashboards and the experiment rule picker.">
            <TextInput
              :model-value="rule.name ?? ''"
              placeholder="e.g. EU rollout"
              @update:model-value="updateRule(ruleIndex, { name: $event || null })" />
          </FormField>
          <FormField label="Description">
            <TextInput
              :model-value="rule.description ?? ''"
              placeholder="Why this rule exists, who it targets…"
              @update:model-value="updateRule(ruleIndex, { description: $event || null })" />
          </FormField>
        </div>

        <!-- Conditions -->
        <div class="section">
          <div class="section-header">
            <span class="section-title">Conditions</span>
            <span class="section-hint">all must match</span>
          </div>

          <div
            v-for="(cond, condIndex) in rule.conditions"
            :key="condIndex"
            class="condition-card"
          >
            <div class="condition-row-top">
              <Select
                :model-value="cond.type"
                :options="CONDITION_TYPE_OPTIONS"
                size="sm"
                @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && changeConditionType(ruleIndex, condIndex, v as ConditionType)"
              />
              <Checkbox
                :model-value="cond.negate"
                label="Negate"
                :accent="accentColor"
                @update:model-value="(v) => updateCondition(ruleIndex, condIndex, { negate: v } as Partial<Condition>)"
              />
              <span class="spacer" />
              <Button
                size="xs"
                icon="x"
                @click="removeCondition(ruleIndex, condIndex)" />
            </div>

            <!-- Segment -->
            <Select
              v-if="cond.type === 'Segment'"
              :model-value="asSegment(cond).segmentId ?? ''"
              :options="segmentOptions"
              size="sm"
              placeholder="Select a segment…"
              @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && updateCondition<SegmentCondition>(ruleIndex, condIndex, { segmentId: v || null })"
            />

            <!-- Principal -->
            <TextInput
              v-else-if="cond.type === 'Principal'"
              :model-value="getPrincipalIdsString(cond)"
              mono
              placeholder="Comma-separated user UUIDs"
              @update:model-value="setPrincipalIds(ruleIndex, condIndex, $event)"
            />

            <!-- Profile attribute -->
            <div v-else-if="cond.type === 'ProfileAttribute'" class="form-grid-2">
              <Select
                :model-value="asProfileAttr(cond).typeId || ''"
                :options="profileTypeOptions"
                size="sm"
                placeholder="Attribute type…"
                @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && updateCondition<ProfileAttributeCondition>(ruleIndex, condIndex, { typeId: v })"
              />
              <TextInput
                :model-value="asProfileAttr(cond).key"
                mono
                placeholder="Attribute key (e.g. city)"
                @update:model-value="updateCondition<ProfileAttributeCondition>(ruleIndex, condIndex, { key: $event })"
              />
              <Select
                :model-value="asProfileAttr(cond).operator"
                :options="OPERATOR_OPTIONS"
                size="sm"
                @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && updateCondition<ProfileAttributeCondition>(ruleIndex, condIndex, { operator: v as AttrOperator })"
              />
              <TextInput
                :model-value="attrValueAsString(cond)"
                :placeholder="cond.operator === 'IN' || cond.operator === 'NOT_IN' ? 'Comma-separated values' : 'Value'"
                @update:model-value="setAttrValue(ruleIndex, condIndex, $event)"
              />
            </div>

            <!-- Device attribute -->
            <div v-else-if="cond.type === 'DeviceAttribute'" class="form-grid-2">
              <Select
                :model-value="asDeviceAttr(cond).key"
                :options="DEVICE_KEY_OPTIONS"
                size="sm"
                placeholder="Device attribute key"
                @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && updateCondition<DeviceAttributeCondition>(ruleIndex, condIndex, { key: v })"
              />
              <Select
                :model-value="asDeviceAttr(cond).operator"
                :options="OPERATOR_OPTIONS"
                size="sm"
                @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && updateCondition<DeviceAttributeCondition>(ruleIndex, condIndex, { operator: v as AttrOperator })"
              />
              <TextInput
                class="span-2"
                :model-value="attrValueAsString(cond)"
                :placeholder="cond.operator === 'IN' || cond.operator === 'NOT_IN' ? 'Comma-separated values' : 'Value'"
                @update:model-value="setAttrValue(ruleIndex, condIndex, $event)"
              />
            </div>

            <!-- Flag dependency -->
            <div v-else-if="cond.type === 'FlagDependency'" class="form-grid-2">
              <TextInput
                :model-value="asFlagDep(cond).flagKey"
                mono
                placeholder="Prerequisite flag key (e.g. new-cart)"
                @update:model-value="updateCondition<FlagDependencyCondition>(ruleIndex, condIndex, { flagKey: $event })"
              />
              <TextInput
                :model-value="asFlagDep(cond).requiredVariationKey"
                mono
                placeholder="Required variation key (e.g. on)"
                @update:model-value="updateCondition<FlagDependencyCondition>(ruleIndex, condIndex, { requiredVariationKey: $event })"
              />
              <p class="hint span-2">
                Matches only when the named flag resolves to the required variation.
                Cycles are detected at evaluation time and fail closed (return false),
                so referencing this same flag will simply not match — no infinite recursion.
              </p>
            </div>
          </div>

          <!-- Add condition: a select that resets after each selection. -->
          <div class="add-condition">
            <Select
              model-value=""
              :options="CONDITION_TYPE_OPTIONS.map((o) => ({ ...o, label: `Add ${o.label}` }))"
              size="sm"
              placeholder="Add condition…"
              @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && v && addCondition(ruleIndex, v as ConditionType)"
            />
          </div>
        </div>

        <!-- Rollout -->
        <div class="section">
          <div class="section-header">
            <span class="section-title">Rollout</span>
            <span class="section-hint">Split matched users across variations</span>
          </div>

          <div
            v-for="(vw, vwIdx) in rule.rollout.variationWeights"
            :key="vwIdx"
            class="rollout-row"
          >
            <div class="rollout-share">
              <span class="rollout-share-pct mono tabular">{{ trafficPercent(rule, vwIdx) }}</span>
              <span class="rollout-share-label">share</span>
            </div>
            <Select
              :model-value="vw.variationKey"
              :options="variationOptions"
              size="sm"
              @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && updateVariationWeight(ruleIndex, vwIdx, { variationKey: v })"
            />
            <input
              type="number"
              class="weight-input mono"
              min="1"
              :value="vw.weight"
              @input="updateVariationWeight(ruleIndex, vwIdx, { weight: Number(($event.target as HTMLInputElement).value) || 1 })"
            >
            <span class="weight-label">weight</span>
            <Button
              size="xs"
              icon="x"
              :disabled="rule.rollout.variationWeights.length <= 1"
              @click="removeVariationWeight(ruleIndex, vwIdx)" />
          </div>

          <Button
            v-if="(props.variations?.length ?? 0) > rule.rollout.variationWeights.length"
            size="xs"
            icon="plus"
            :accent="accentColor"
            @click="addVariationWeight(ruleIndex)">Add variation to rollout</Button>
        </div>
      </div>
    </div>

    <div v-if="rules.length === 0" class="empty-rules">
      No targeting rules. The default variation will always be returned.
    </div>

    <Button
      size="sm"
      icon="plus"
      :accent="accentColor"
      :disabled="(props.variations?.length ?? 0) === 0"
      @click="addRule">Add Rule</Button>
    <p v-if="(props.variations?.length ?? 0) === 0" class="empty-hint">
      Add at least one variation above before creating targeting rules.
    </p>

    <!-- Deletion confirmation: lists running experiments that will silently break. -->
    <ConfirmModal
      v-if="confirmDeleteOpen"
      title="Delete targeting rule?"
      :subtitle="confirmDeleteDependents.length > 0 ? `${confirmDeleteDependents.length} running experiment(s) will silently break.` : 'This cannot be undone.'"
      confirm-label="Delete rule"
      @close="cancelRemoveRule"
      @confirm="performRemoveRule"
    >
      <p class="modal-text">
        Delete rule
        <code class="mono inline-code">{{ confirmDeleteRuleLabel }}</code>?
        This cannot be undone.
      </p>
      <div v-if="confirmDeleteDependents.length > 0" class="dependents-warn">
        <p class="dependents-title">
          {{ confirmDeleteDependents.length }} running experiment(s) depend on this rule:
        </p>
        <ul class="dependents-list">
          <li v-for="name in confirmDeleteDependents" :key="name">{{ name }}</li>
        </ul>
      </div>
    </ConfirmModal>
  </div>
</template>

<style scoped>
.rules-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.rule-card {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
}

.rule-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background: var(--bg-2);
  border-bottom: 1px solid var(--line);
}

.rule-name-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-1);
}

.rule-summary {
  font-size: 11.5px;
  color: var(--fg-3);
}

.rule-id {
  font-size: 10.5px;
  color: var(--fg-4);
}

.spacer { flex: 1; }

.rule-body {
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.span-2 { grid-column: span 2; }

/* Section: Conditions / Rollout */
.section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.section-title {
  font-size: 10.5px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--fg-3);
}

.section-hint {
  font-size: 11.5px;
  color: var(--fg-3);
}

/* Conditions */
.condition-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px;
  background: var(--bg-2);
  border: 1px solid color-mix(in oklch, var(--line) 70%, transparent);
  border-radius: var(--r-sm);
}

.condition-row-top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.hint {
  font-size: 11px;
  color: var(--fg-3);
  margin: 0;
  line-height: 1.5;
}

.add-condition {
  max-width: 220px;
}

/* Rollout rows */
.rollout-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rollout-share {
  min-width: 60px;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 6px 8px;
  border-radius: var(--r-sm);
  background: var(--bg-3);
}

.rollout-share-pct {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.rollout-share-label {
  font-size: 9.5px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--fg-3);
}

.weight-input {
  width: 64px;
  padding: 6px 8px;
  font-size: 12.5px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-0);
  outline: none;
  text-align: right;
  -moz-appearance: textfield;
  appearance: textfield;
}

.weight-input::-webkit-inner-spin-button,
.weight-input::-webkit-outer-spin-button {
  -webkit-appearance: none;
  margin: 0;
}

.weight-input:focus { border-color: var(--brand-2); }

.weight-label {
  font-size: 11px;
  color: var(--fg-3);
}

/* Empty / hints */
.empty-rules {
  text-align: center;
  padding: 18px;
  font-size: 13px;
  color: var(--fg-3);
  border: 1px dashed var(--line);
  border-radius: var(--r-sm);
}

.empty-hint {
  margin: 4px 0 0;
  font-size: 11.5px;
  color: var(--fg-3);
  text-align: center;
}

/* Modal contents */
.modal-text {
  font-size: 13px;
  color: var(--fg-2);
  line-height: 1.5;
  margin: 0;
}

.inline-code {
  background: var(--bg-3);
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 12px;
}

.dependents-warn {
  margin-top: 12px;
  padding: 10px 12px;
  background: color-mix(in oklch, var(--err) 6%, transparent);
  border: 1px solid color-mix(in oklch, var(--err) 35%, transparent);
  border-radius: var(--r-sm);
}

.dependents-title {
  margin: 0 0 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--err);
}

.dependents-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--fg-1);
  line-height: 1.5;
}
</style>
