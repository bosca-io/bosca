import { describe, it, expect } from 'vitest'
import {
  coerceControlValue,
  effectiveSettingValue,
  isSettingVisible,
  moveItem,
  parseDefault,
  seedNodeDefaults,
} from './pipelineSettings'
import type { SettingControl, SettingMeta } from './pipelineNodeTypes'

/** Build a SettingMeta with sensible empty defaults, overriding only what a test needs. */
function setting(name: string, control: SettingControl, over: Partial<SettingMeta> = {}): SettingMeta {
  return {
    name,
    control,
    label: null,
    description: null,
    placeholder: null,
    default: null,
    required: false,
    secret: false,
    mono: false,
    language: null,
    reference: null,
    options: [],
    fields: [],
    itemLabel: null,
    group: null,
    visibleWhenSetting: null,
    visibleWhenEquals: null,
    ...over,
  }
}

describe('parseDefault', () => {
  it('returns undefined when there is no default', () => {
    expect(parseDefault(setting('a', 'TEXT'))).toBeUndefined()
    expect(parseDefault(setting('a', 'TEXT', { default: '' }))).toBeUndefined()
  })

  it('parses booleans', () => {
    expect(parseDefault(setting('a', 'BOOLEAN', { default: 'true' }))).toBe(true)
    expect(parseDefault(setting('a', 'BOOLEAN', { default: 'false' }))).toBe(false)
  })

  it('parses integers and numbers', () => {
    expect(parseDefault(setting('a', 'INTEGER', { default: '100' }))).toBe(100)
    expect(parseDefault(setting('a', 'NUMBER', { default: '1.5' }))).toBe(1.5)
    expect(parseDefault(setting('a', 'INTEGER', { default: 'x' }))).toBeUndefined()
  })

  it('passes strings through for text and enum', () => {
    expect(parseDefault(setting('a', 'TEXT', { default: 'aiSummary' }))).toBe('aiSummary')
    expect(parseDefault(setting('a', 'ENUM', { default: 'add' }))).toBe('add')
  })
})

describe('effectiveSettingValue', () => {
  it('returns the stored value when present', () => {
    expect(effectiveSettingValue(setting('a', 'ENUM', { default: 'add' }), { a: 'update' })).toBe('update')
  })
  it('falls back to the parsed default when absent', () => {
    expect(effectiveSettingValue(setting('a', 'ENUM', { default: 'add' }), {})).toBe('add')
  })
  it('returns undefined for an unknown setting', () => {
    expect(effectiveSettingValue(undefined, { a: 1 })).toBeUndefined()
  })
})

describe('isSettingVisible', () => {
  const operation = setting('operation', 'ENUM', { default: 'add' })
  const typeId = setting('typeId', 'REFERENCE', { visibleWhenSetting: 'operation', visibleWhenEquals: 'add' })
  const all = [operation, typeId]

  it('always shows a setting with no gate', () => {
    expect(isSettingVisible(operation, {}, all)).toBe(true)
  })
  it('shows when the controller matches by its default (absent but defaulted)', () => {
    expect(isSettingVisible(typeId, {}, all)).toBe(true)
  })
  it('shows when the controller matches by its stored value', () => {
    expect(isSettingVisible(typeId, { operation: 'add' }, all)).toBe(true)
  })
  it('hides when the controller does not match', () => {
    expect(isSettingVisible(typeId, { operation: 'delete' }, all)).toBe(false)
  })
  it('hides when the controller is missing entirely', () => {
    expect(isSettingVisible(setting('x', 'TEXT', { visibleWhenSetting: 'nope', visibleWhenEquals: 'y' }), {}, all)).toBe(false)
  })
})

describe('coerceControlValue', () => {
  it('stores a positive integer, omits otherwise', () => {
    expect(coerceControlValue(setting('a', 'INTEGER'), '5')).toEqual({ action: 'set', value: 5 })
    expect(coerceControlValue(setting('a', 'INTEGER'), '0')).toEqual({ action: 'unset' })
    expect(coerceControlValue(setting('a', 'INTEGER'), '')).toEqual({ action: 'unset' })
  })

  it('stores a positive float, omits otherwise', () => {
    expect(coerceControlValue(setting('a', 'NUMBER'), '1.5')).toEqual({ action: 'set', value: 1.5 })
    expect(coerceControlValue(setting('a', 'NUMBER'), '-1')).toEqual({ action: 'unset' })
  })

  it('stores a boolean only when it differs from the default (default false)', () => {
    const s = setting('a', 'BOOLEAN', { default: 'false' })
    expect(coerceControlValue(s, true)).toEqual({ action: 'set', value: true })
    expect(coerceControlValue(s, false)).toEqual({ action: 'unset' })
  })

  it('stores a boolean only when it differs from the default (default true — inverted toggle)', () => {
    const s = setting('a', 'BOOLEAN', { default: 'true' })
    expect(coerceControlValue(s, false)).toEqual({ action: 'set', value: false })
    expect(coerceControlValue(s, true)).toEqual({ action: 'unset' })
  })

  it('splits a comma list, omits when empty', () => {
    expect(coerceControlValue(setting('a', 'LIST'), 'a, b ,c,')).toEqual({ action: 'set', value: ['a', 'b', 'c'] })
    expect(coerceControlValue(setting('a', 'LIST'), '  ,  ')).toEqual({ action: 'unset' })
  })

  it('omits an empty schema/group, stores a non-empty one', () => {
    expect(coerceControlValue(setting('a', 'SCHEMA'), null)).toEqual({ action: 'unset' })
    expect(coerceControlValue(setting('a', 'SCHEMA'), {})).toEqual({ action: 'unset' })
    expect(coerceControlValue(setting('a', 'GROUP_LIST'), [])).toEqual({ action: 'unset' })
    expect(coerceControlValue(setting('a', 'GROUP_LIST'), [{ label: 'x' }])).toEqual({ action: 'set', value: [{ label: 'x' }] })
  })

  it('omits a blank optional string, stores a blank required string', () => {
    expect(coerceControlValue(setting('a', 'TEXT'), '   ')).toEqual({ action: 'unset' })
    expect(coerceControlValue(setting('a', 'TEXT', { required: true }), '')).toEqual({ action: 'set', value: '' })
    expect(coerceControlValue(setting('a', 'TEXT'), 'hi')).toEqual({ action: 'set', value: 'hi' })
  })

  it('stores a reference pick, omits when cleared', () => {
    const s = setting('a', 'REFERENCE', { reference: 'JOB' })
    expect(coerceControlValue(s, 'job-key')).toEqual({ action: 'set', value: 'job-key' })
    expect(coerceControlValue(s, '')).toEqual({ action: 'unset' })
  })
})

describe('moveItem', () => {
  it('swaps within bounds', () => {
    expect(moveItem(['a', 'b', 'c'], 0, 1)).toEqual(['b', 'a', 'c'])
    expect(moveItem(['a', 'b', 'c'], 2, -1)).toEqual(['a', 'c', 'b'])
  })
  it('is a no-op out of bounds', () => {
    expect(moveItem(['a', 'b'], 0, -1)).toEqual(['a', 'b'])
    expect(moveItem(['a', 'b'], 1, 1)).toEqual(['a', 'b'])
  })
})

describe('seedNodeDefaults', () => {
  it('seeds non-blank defaults and required-no-default placeholders', () => {
    const seed = seedNodeDefaults([
      setting('defaultLabel', 'TEXT', { default: 'default' }),
      setting('maxConcurrency', 'INTEGER', { default: '1' }),
      setting('html', 'BOOLEAN', { default: 'true' }),
      setting('jobName', 'REFERENCE', { reference: 'JOB', required: true }),
      setting('expression', 'CODE', { required: true }),
      setting('pipelineId', 'REFERENCE', { reference: 'PIPELINE' }),
    ])
    expect(seed).toEqual({
      defaultLabel: 'default',
      maxConcurrency: 1,
      html: true,
      jobName: '',
      expression: '',
    })
  })

  it('seeds a switch-like group with required string fields to empty strings', () => {
    const seed = seedNodeDefaults([
      setting('label', 'TEXT', { required: true }),
      setting('expression', 'TEXT', { required: true }),
    ])
    expect(seed).toEqual({ label: '', expression: '' })
  })

  it('returns an empty object for a settings-free node', () => {
    expect(seedNodeDefaults([])).toEqual({})
  })
})
