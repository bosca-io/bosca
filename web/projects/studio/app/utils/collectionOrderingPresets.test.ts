import { describe, expect, it } from 'vitest'
import type { OrderingInput } from '~/types/graphql'
import { AttributeLocation, AttributeType, Order } from '~/types/graphql'
import {
  ORDERING_PRESET_HINTS,
  ORDERING_PRESET_OPTIONS,
  blankOrderingRule,
  matchOrderingPreset,
  matchOrderingPresetRule,
  orderingPresetRule,
} from './collectionOrderingPresets'

const newest = (): OrderingInput => orderingPresetRule('newest')
const oldest = (): OrderingInput => orderingPresetRule('oldest')
const manual = (): OrderingInput => orderingPresetRule('manual')

describe('orderingPresetRule', () => {
  it('newest/oldest sort the published attribute as DATE_TIME on the item, descending/ascending', () => {
    expect(newest()).toEqual({
      field: null,
      location: AttributeLocation.Item,
      path: ['published'],
      type: AttributeType.DateTime,
      order: Order.Descending,
    })
    expect(oldest()).toEqual({ ...newest(), order: Order.Ascending })
  })

  it('manual sorts the relationship `sort` attribute as INT ascending — what the Items tab drag-reorder writes', () => {
    expect(manual()).toEqual({
      field: null,
      location: AttributeLocation.Relationship,
      path: ['sort'],
      type: AttributeType.Int,
      order: Order.Ascending,
    })
  })

  it('returns a fresh copy each time so editing one rule cannot corrupt the preset', () => {
    const a = newest()
    a.path?.push('extra')
    a.order = Order.Ascending
    expect(newest().path).toEqual(['published'])
    expect(newest().order).toBe(Order.Descending)
  })
})

describe('blankOrderingRule', () => {
  it('has neither field nor path, so it reads as invalid until the editor fills it in', () => {
    const rule = blankOrderingRule()
    expect(rule.field).toBe('')
    expect(rule.path).toBeNull()
    expect(rule.location).toBe(AttributeLocation.Item)
    expect(rule.order).toBe(Order.Ascending)
    expect(rule.type).toBe(AttributeType.String)
  })
})

describe('matchOrderingPresetRule', () => {
  it('recognises each preset rule exactly', () => {
    expect(matchOrderingPresetRule(newest())).toBe('newest')
    expect(matchOrderingPresetRule(oldest())).toBe('oldest')
    expect(matchOrderingPresetRule(manual())).toBe('manual')
  })

  it('accepts the legacy DATETIME spelling for the publish-date presets', () => {
    expect(matchOrderingPresetRule({ ...newest(), type: AttributeType.Datetime })).toBe('newest')
    expect(matchOrderingPresetRule({ ...oldest(), type: AttributeType.Datetime })).toBe('oldest')
  })

  it('does not claim a publish-date preset for a rule that would behave differently', () => {
    // STRING cast sorts epoch millis lexicographically — not a date sort.
    expect(matchOrderingPresetRule({ ...newest(), type: AttributeType.String })).toBeNull()
    // Missing order falls back to descending server-side, but it is not the explicit preset.
    expect(matchOrderingPresetRule({ ...newest(), order: null })).toBeNull()
    // RELATIONSHIP reads a different attribute store.
    expect(matchOrderingPresetRule({ ...newest(), location: AttributeLocation.Relationship })).toBeNull()
    // Missing location defaults to RELATIONSHIP server-side.
    expect(matchOrderingPresetRule({ ...newest(), location: null })).toBeNull()
    // Nested or different paths.
    expect(matchOrderingPresetRule({ ...newest(), path: ['meta', 'published'] })).toBeNull()
    expect(matchOrderingPresetRule({ ...newest(), path: ['date'] })).toBeNull()
    // A field set alongside the path is not the shape a preset writes.
    expect(matchOrderingPresetRule({ ...newest(), field: 'name' })).toBeNull()
  })

  it('only treats INT ascending `sort` on the relationship as the manual preset', () => {
    // Legacy admin left new rules typed STRING; "10" sorts before "2" — not the preset.
    expect(matchOrderingPresetRule({ ...manual(), type: AttributeType.String })).toBeNull()
    expect(matchOrderingPresetRule({ ...manual(), type: null })).toBeNull()
    expect(matchOrderingPresetRule({ ...manual(), order: Order.Descending })).toBeNull()
    expect(matchOrderingPresetRule({ ...manual(), location: AttributeLocation.Item })).toBeNull()
    expect(matchOrderingPresetRule({ ...manual(), path: ['position'] })).toBeNull()
    expect(matchOrderingPresetRule({ ...manual(), path: ['sort', 'x'] })).toBeNull()
  })

  it('returns null for column rules and blank rules', () => {
    expect(matchOrderingPresetRule({ field: 'name', location: AttributeLocation.Item, order: Order.Ascending, type: AttributeType.String, path: null })).toBeNull()
    expect(matchOrderingPresetRule(blankOrderingRule())).toBeNull()
    expect(matchOrderingPresetRule({ ...newest(), field: '   ' })).toBe('newest')
  })
})

describe('matchOrderingPreset', () => {
  it('is null for an empty rule list (dropdown shows its placeholder)', () => {
    expect(matchOrderingPreset([])).toBeNull()
  })

  it('is the preset when the list is exactly that one rule', () => {
    expect(matchOrderingPreset([newest()])).toBe('newest')
    expect(matchOrderingPreset([oldest()])).toBe('oldest')
    expect(matchOrderingPreset([manual()])).toBe('manual')
  })

  it('is custom for any other non-empty list', () => {
    expect(matchOrderingPreset([newest(), manual()])).toBe('custom')
    expect(matchOrderingPreset([blankOrderingRule()])).toBe('custom')
    expect(matchOrderingPreset([{ ...manual(), type: AttributeType.String }])).toBe('custom')
    expect(matchOrderingPreset([{ field: 'name', location: AttributeLocation.Item, order: Order.Ascending, type: AttributeType.String, path: null }])).toBe('custom')
  })
})

describe('dropdown metadata', () => {
  it('offers the four choices in the agreed order with a hint for each', () => {
    expect(ORDERING_PRESET_OPTIONS.map(o => o.value)).toEqual(['newest', 'oldest', 'manual', 'custom'])
    expect(ORDERING_PRESET_OPTIONS.map(o => o.label)).toEqual([
      'Descending by Publish Date (Newest First)',
      'Ascending by Publish Date (Oldest First)',
      'Sort (drag and drop ordering)',
      'Custom',
    ])
    for (const option of ORDERING_PRESET_OPTIONS) {
      expect(ORDERING_PRESET_HINTS[option.value as keyof typeof ORDERING_PRESET_HINTS]).toBeTruthy()
    }
  })
})
