import { describe, it, expect } from 'vitest'
import { dayKey, stepDayKey, localDayKey, stepPagesByDay, pageForDay } from './guideCalendar'

describe('dayKey', () => {
  it('zero-pads months and days so keys sort lexicographically', () => {
    expect(dayKey(2026, 3, 7)).toBe('2026-03-07')
    expect(dayKey(2026, 11, 25)).toBe('2026-11-25')
  })
})

describe('stepDayKey', () => {
  it('uses the UTC calendar day of an ISO datetime', () => {
    expect(stepDayKey('2026-01-05T00:00:00Z')).toBe('2026-01-05')
    // Late-evening UTC stays on the same UTC day no matter the local zone.
    expect(stepDayKey('2026-01-05T23:30:00Z')).toBe('2026-01-05')
  })

  it('returns null for absent or unparsable dates', () => {
    expect(stepDayKey(null)).toBeNull()
    expect(stepDayKey(undefined)).toBeNull()
    expect(stepDayKey('')).toBeNull()
    expect(stepDayKey('not-a-date')).toBeNull()
  })
})

describe('localDayKey', () => {
  it('uses the local calendar day', () => {
    // Construct via local parts, so this holds in every timezone.
    expect(localDayKey(new Date(2026, 0, 5, 12, 0, 0))).toBe('2026-01-05')
    expect(localDayKey(new Date(2026, 11, 31, 23, 59, 0))).toBe('2026-12-31')
  })
})

describe('stepPagesByDay', () => {
  it('maps each dated step to its page, skipping undated steps', () => {
    const pages = stepPagesByDay([
      { date: '2026-03-01T00:00:00Z' },
      { date: null },
      { date: '2026-03-03T00:00:00Z' },
    ])
    expect(pages.get('2026-03-01')).toBe(2)
    expect(pages.get('2026-03-03')).toBe(4)
    expect(pages.size).toBe(2)
  })

  it('keeps the first step when two share a day, matching the strip order', () => {
    const pages = stepPagesByDay([
      { date: '2026-03-01T00:00:00Z' },
      { date: '2026-03-01T06:00:00Z' },
    ])
    expect(pages.get('2026-03-01')).toBe(2)
  })
})

describe('pageForDay', () => {
  const steps = [
    { date: '2026-03-02T00:00:00Z' },
    { date: '2026-03-03T00:00:00Z' },
    { date: '2026-03-06T00:00:00Z' },
  ]

  it('returns the page of the step matching the day', () => {
    expect(pageForDay(steps, '2026-03-03')).toBe(3)
  })

  it('falls back to the latest earlier step across gaps', () => {
    expect(pageForDay(steps, '2026-03-05')).toBe(3)
  })

  it('returns the introduction when the day precedes every step', () => {
    expect(pageForDay(steps, '2026-03-01')).toBe(1)
  })

  it('returns the last step when the day is past the end of the guide', () => {
    expect(pageForDay(steps, '2026-04-20')).toBe(4)
  })

  it('picks the latest earlier day even when steps are unordered', () => {
    const unordered = [
      { date: '2026-03-06T00:00:00Z' },
      { date: '2026-03-02T00:00:00Z' },
      { date: '2026-03-03T00:00:00Z' },
    ]
    expect(pageForDay(unordered, '2026-03-04')).toBe(4)
  })

  it('ignores undated steps and returns the introduction when none are dated', () => {
    expect(pageForDay([{ date: null }, {}], '2026-03-04')).toBe(1)
  })
})
