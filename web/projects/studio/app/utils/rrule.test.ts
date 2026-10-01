import { describe, it, expect } from 'vitest'
import { parseRRule, buildRRule, formatUntilDate, parseUntilDate } from './rrule'

describe('parseRRule', () => {
  it('parses DAILY frequency', () => {
    const config = parseRRule('FREQ=DAILY')
    expect(config.freq).toBe('DAILY')
    expect(config.interval).toBe(1)
    expect(config.byDay).toEqual([])
  })

  it('parses WEEKLY with BYDAY', () => {
    const config = parseRRule('FREQ=WEEKLY;BYDAY=MO,WE,FR')
    expect(config.freq).toBe('WEEKLY')
    expect(config.byDay).toEqual(['MO', 'WE', 'FR'])
  })

  it('parses interval', () => {
    const config = parseRRule('FREQ=DAILY;INTERVAL=3')
    expect(config.interval).toBe(3)
  })

  it('parses COUNT', () => {
    const config = parseRRule('FREQ=WEEKLY;COUNT=10')
    expect(config.count).toBe(10)
    expect(config.until).toBeNull()
  })

  it('parses UNTIL', () => {
    const config = parseRRule('FREQ=DAILY;UNTIL=20260101T000000Z')
    expect(config.until).toBeInstanceOf(Date)
    expect(config.until!.getUTCFullYear()).toBe(2026)
    expect(config.until!.getUTCMonth()).toBe(0)
    expect(config.until!.getUTCDate()).toBe(1)
    expect(config.count).toBeNull()
  })

  it('parses MONTHLY', () => {
    const config = parseRRule('FREQ=MONTHLY;INTERVAL=2')
    expect(config.freq).toBe('MONTHLY')
    expect(config.interval).toBe(2)
  })

  it('parses YEARLY', () => {
    const config = parseRRule('FREQ=YEARLY')
    expect(config.freq).toBe('YEARLY')
  })

  it('handles RRULE: prefix', () => {
    const config = parseRRule('RRULE:FREQ=DAILY;COUNT=5')
    expect(config.freq).toBe('DAILY')
    expect(config.count).toBe(5)
  })

  it('handles case-insensitive input', () => {
    const config = parseRRule('freq=weekly;byday=mo,tu')
    expect(config.freq).toBe('WEEKLY')
    expect(config.byDay).toEqual(['MO', 'TU'])
  })
})

describe('buildRRule', () => {
  it('builds simple DAILY', () => {
    expect(buildRRule({ freq: 'DAILY', interval: 1, byDay: [], count: null, until: null }))
      .toBe('FREQ=DAILY')
  })

  it('includes INTERVAL when > 1', () => {
    expect(buildRRule({ freq: 'DAILY', interval: 3, byDay: [], count: null, until: null }))
      .toBe('FREQ=DAILY;INTERVAL=3')
  })

  it('omits INTERVAL when 1', () => {
    const result = buildRRule({ freq: 'WEEKLY', interval: 1, byDay: ['MO'], count: null, until: null })
    expect(result).not.toContain('INTERVAL')
  })

  it('includes BYDAY', () => {
    const result = buildRRule({ freq: 'WEEKLY', interval: 1, byDay: ['MO', 'WE', 'FR'], count: null, until: null })
    expect(result).toBe('FREQ=WEEKLY;BYDAY=MO,WE,FR')
  })

  it('includes COUNT', () => {
    const result = buildRRule({ freq: 'DAILY', interval: 1, byDay: [], count: 10, until: null })
    expect(result).toBe('FREQ=DAILY;COUNT=10')
  })

  it('includes UNTIL', () => {
    const until = new Date(Date.UTC(2026, 5, 15, 0, 0, 0))
    const result = buildRRule({ freq: 'DAILY', interval: 1, byDay: [], count: null, until })
    expect(result).toContain('UNTIL=20260615T000000Z')
  })

  it('prefers COUNT over UNTIL when both set', () => {
    const result = buildRRule({
      freq: 'DAILY', interval: 1, byDay: [],
      count: 5, until: new Date(Date.UTC(2026, 0, 1)),
    })
    expect(result).toContain('COUNT=5')
    expect(result).not.toContain('UNTIL')
  })
})

describe('round-trip', () => {
  it('parse then build produces equivalent string', () => {
    const original = 'FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=10'
    const config = parseRRule(original)
    const rebuilt = buildRRule(config)
    expect(rebuilt).toBe(original)
  })

  it('round-trips DAILY with UNTIL', () => {
    const original = 'FREQ=DAILY;UNTIL=20260615T120000Z'
    const config = parseRRule(original)
    const rebuilt = buildRRule(config)
    expect(rebuilt).toBe(original)
  })
})

describe('formatUntilDate', () => {
  it('formats UTC date to RRULE UNTIL format', () => {
    const date = new Date(Date.UTC(2026, 0, 15, 14, 30, 0))
    expect(formatUntilDate(date)).toBe('20260115T143000Z')
  })
})

describe('parseUntilDate', () => {
  it('parses RRULE UNTIL format to Date', () => {
    const date = parseUntilDate('20260115T143000Z')
    expect(date).not.toBeNull()
    expect(date!.getUTCFullYear()).toBe(2026)
    expect(date!.getUTCMonth()).toBe(0)
    expect(date!.getUTCDate()).toBe(15)
    expect(date!.getUTCHours()).toBe(14)
    expect(date!.getUTCMinutes()).toBe(30)
  })

  it('returns null for invalid format', () => {
    expect(parseUntilDate('invalid')).toBeNull()
    expect(parseUntilDate('2026-01-15')).toBeNull()
  })

  it('round-trips with formatUntilDate', () => {
    const original = new Date(Date.UTC(2026, 5, 1, 8, 0, 0))
    const formatted = formatUntilDate(original)
    const parsed = parseUntilDate(formatted)
    expect(parsed!.getTime()).toBe(original.getTime())
  })
})
