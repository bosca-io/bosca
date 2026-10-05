import type { SelectOption } from '@bosca/ui'
import type { OrderingInput } from '~/types/graphql'
import { AttributeLocation, AttributeType, Order } from '~/types/graphql'

/**
 * The simple "Sort items by" choices offered on the collection Ordering tab.
 * Each non-custom preset expands to exactly one ordering rule; `custom` means
 * "whatever rules are configured" and is what any other rule set reads back as.
 */
export type OrderingPreset = 'newest' | 'oldest' | 'manual' | 'custom'

export type OrderingPresetRuleKey = Exclude<OrderingPreset, 'custom'>

/** Attribute every item (metadata and collection) receives at creation, as epoch millis. */
export const PUBLISHED_ATTRIBUTE = 'published'
/** Relationship attribute the Items tab drag-reorder reads and writes. */
export const MANUAL_SORT_ATTRIBUTE = 'sort'

// `published` is epoch millis, so the rule must cast through DATE_TIME
// (bigint) — DATE casts to a 32-bit int and overflows.
const PRESET_RULES: Record<OrderingPresetRuleKey, OrderingInput> = {
  newest: {
    field: null,
    location: AttributeLocation.Item,
    path: [PUBLISHED_ATTRIBUTE],
    type: AttributeType.DateTime,
    order: Order.Descending,
  },
  oldest: {
    field: null,
    location: AttributeLocation.Item,
    path: [PUBLISHED_ATTRIBUTE],
    type: AttributeType.DateTime,
    order: Order.Ascending,
  },
  manual: {
    field: null,
    location: AttributeLocation.Relationship,
    path: [MANUAL_SORT_ATTRIBUTE],
    type: AttributeType.Int,
    order: Order.Ascending,
  },
}

export const ORDERING_PRESET_OPTIONS: SelectOption[] = [
  { value: 'newest', label: 'Descending by Publish Date (Newest First)', icon: 'calendar' },
  { value: 'oldest', label: 'Ascending by Publish Date (Oldest First)', icon: 'calendar' },
  { value: 'manual', label: 'Sort (drag and drop ordering)', icon: 'move' },
  { value: 'custom', label: 'Custom', icon: 'sliders' },
]

/** One-line explanation shown under the dropdown for the selected preset. */
export const ORDERING_PRESET_HINTS: Record<OrderingPreset, string> = {
  newest: 'Items with the most recent publish date appear first.',
  oldest: 'Items with the earliest publish date appear first.',
  manual: 'Drag items into place on the Items tab to set their order.',
  custom: 'Items are ordered by the rules below, applied in order.',
}

/** A fresh copy of the rule behind a preset (safe to mutate in the editor). */
export function orderingPresetRule(preset: OrderingPresetRuleKey): OrderingInput {
  const rule = PRESET_RULES[preset]
  return { ...rule, path: rule.path ? [...rule.path] : null }
}

/** A blank rule for the Custom choice — every field left for the editor to fill in. */
export function blankOrderingRule(): OrderingInput {
  return {
    field: '',
    location: AttributeLocation.Item,
    order: Order.Ascending,
    type: AttributeType.String,
    path: null,
  }
}

function isDateTimeType(type: AttributeType | null | undefined): boolean {
  // The server reads stored "datetime" back as DATE_TIME; accept either spelling.
  return type === AttributeType.DateTime || type === AttributeType.Datetime
}

function hasNoField(rule: OrderingInput): boolean {
  return !rule.field || rule.field.trim().length === 0
}

function hasSinglePath(rule: OrderingInput, key: string): boolean {
  return Array.isArray(rule.path) && rule.path.length === 1 && rule.path[0] === key
}

/**
 * Which preset a single rule is — the exact shape a preset writes, so the
 * dropdown never claims "Newest first" for a rule that behaves differently
 * (for example a `published` rule typed STRING, or `sort` in descending order).
 * Anything else, including legacy rules that merely resemble a preset, is custom.
 */
export function matchOrderingPresetRule(rule: OrderingInput): OrderingPresetRuleKey | null {
  if (!hasNoField(rule)) return null
  if (rule.location === AttributeLocation.Item && hasSinglePath(rule, PUBLISHED_ATTRIBUTE) && isDateTimeType(rule.type)) {
    if (rule.order === Order.Descending) return 'newest'
    if (rule.order === Order.Ascending) return 'oldest'
    return null
  }
  if (
    rule.location === AttributeLocation.Relationship
    && hasSinglePath(rule, MANUAL_SORT_ATTRIBUTE)
    && rule.type === AttributeType.Int
    && rule.order === Order.Ascending
  ) {
    return 'manual'
  }
  return null
}

/**
 * The dropdown value for a rule list: a preset when the list is exactly that
 * preset's one rule, `custom` for any other non-empty list, and `null` when
 * there are no rules yet (the dropdown shows its placeholder).
 */
export function matchOrderingPreset(rules: OrderingInput[]): OrderingPreset | null {
  if (rules.length === 0) return null
  if (rules.length === 1) {
    const first = rules[0]
    if (first) {
      const preset = matchOrderingPresetRule(first)
      if (preset) return preset
    }
  }
  return 'custom'
}
