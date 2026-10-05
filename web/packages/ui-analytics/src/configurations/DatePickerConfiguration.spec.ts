import { describe, it, expect } from 'vitest'
import { DatePickerConfiguration } from './DatePickerConfiguration'

describe('DatePickerConfiguration', () => {
  it('has type DATEPICKER', () => {
    expect(new DatePickerConfiguration().type).toBe('DATEPICKER')
  })

  it('has default param names', () => {
    const c = new DatePickerConfiguration()
    expect(c.startDateParam).toBe('startDate')
    expect(c.endDateParam).toBe('endDate')
  })

  it('is always ready', () => {
    expect(new DatePickerConfiguration().isReady).toBe(true)
  })

  it('loads configuration', () => {
    const c = new DatePickerConfiguration()
    c.loadConfiguration({ startDateParam: 'from', endDateParam: 'to' })
    expect(c.startDateParam).toBe('from')
    expect(c.endDateParam).toBe('to')
  })

  it('uses defaults for missing params', () => {
    const c = new DatePickerConfiguration()
    c.loadConfiguration({})
    expect(c.startDateParam).toBe('startDate')
    expect(c.endDateParam).toBe('endDate')
  })

  it('round-trips through getConfiguration', () => {
    const c = new DatePickerConfiguration()
    c.loadConfiguration({ startDateParam: 'begin', endDateParam: 'end' })
    expect(c.getConfiguration()).toEqual({ startDateParam: 'begin', endDateParam: 'end' })
  })

  describe('resolveRange', () => {
    it('resolves plain string parameter values', () => {
      const c = new DatePickerConfiguration()
      const range = c.resolveRange([
        { parameter: 'startDate', value: '2026-01-01T00:00:00.000Z' },
        { parameter: 'endDate', value: '2026-01-31T00:00:00.000Z' },
      ])
      expect(range.start?.toISOString()).toBe('2026-01-01T00:00:00.000Z')
      expect(range.end?.toISOString()).toBe('2026-01-31T00:00:00.000Z')
    })

    it('resolves DateParameterValue objects with a fixed value', () => {
      const c = new DatePickerConfiguration()
      const range = c.resolveRange([
        { parameter: 'startDate', value: { value: '2026-02-01T00:00:00.000Z', now: false } },
        { parameter: 'endDate', value: { value: '2026-02-15T00:00:00.000Z', now: false } },
      ])
      expect(range.start?.toISOString()).toBe('2026-02-01T00:00:00.000Z')
      expect(range.end?.toISOString()).toBe('2026-02-15T00:00:00.000Z')
    })

    it('resolves now-relative values with a day offset', () => {
      const c = new DatePickerConfiguration()
      const range = c.resolveRange([
        { parameter: 'startDate', value: { now: true, nowDayOffset: -30 } },
        { parameter: 'endDate', value: { now: true } },
      ])
      const today = new Date()
      today.setHours(0, 0, 0, 0)
      const expectedStart = new Date(today)
      expectedStart.setDate(expectedStart.getDate() - 30)
      expect(range.start?.getTime()).toBe(expectedStart.getTime())
      expect(range.end?.getTime()).toBe(today.getTime())
    })

    it('respects configured parameter names', () => {
      const c = new DatePickerConfiguration()
      c.loadConfiguration({ startDateParam: 'from', endDateParam: 'to' })
      const range = c.resolveRange([
        { parameter: 'from', value: '2026-03-01T00:00:00.000Z' },
        { parameter: 'startDate', value: '2000-01-01T00:00:00.000Z' },
      ])
      expect(range.start?.toISOString()).toBe('2026-03-01T00:00:00.000Z')
      expect(range.end).toBeNull()
    })

    it('returns nulls when parameters are missing or undefined', () => {
      const c = new DatePickerConfiguration()
      expect(c.resolveRange(undefined)).toEqual({ start: null, end: null })
      expect(c.resolveRange([])).toEqual({ start: null, end: null })
    })

    it('returns null for unparsable values', () => {
      const c = new DatePickerConfiguration()
      const range = c.resolveRange([
        { parameter: 'startDate', value: 'not-a-date' },
        { parameter: 'endDate', value: { value: null, now: false } },
      ])
      expect(range.start).toBeNull()
      expect(range.end).toBeNull()
    })
  })
})
