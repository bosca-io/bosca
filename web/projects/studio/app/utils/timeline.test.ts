import { describe, it, expect } from 'vitest'
import { formatMs, parseMs, typeColor } from './timeline'

describe('formatMs', () => {
  it('formats zero', () => {
    expect(formatMs(0)).toBe('0:00.0')
  })

  it('formats seconds', () => {
    expect(formatMs(5000)).toBe('0:05.0')
  })

  it('formats minutes and seconds', () => {
    expect(formatMs(90000)).toBe('1:30.0')
  })

  it('formats with tenths', () => {
    expect(formatMs(1500)).toBe('0:01.5')
    expect(formatMs(90500)).toBe('1:30.5')
  })

  it('formats large values', () => {
    expect(formatMs(3661200)).toBe('61:01.2')
  })

  it('formats negative values', () => {
    expect(formatMs(-5000)).toBe('-0:05.0')
    expect(formatMs(-90500)).toBe('-1:30.5')
  })

  it('handles sub-100ms tenths correctly', () => {
    expect(formatMs(50)).toBe('0:00.0')
    expect(formatMs(100)).toBe('0:00.1')
    expect(formatMs(999)).toBe('0:00.9')
  })
})

describe('parseMs', () => {
  it('parses M:SS format', () => {
    expect(parseMs('1:30')).toBe(90000)
    expect(parseMs('0:05')).toBe(5000)
  })

  it('parses M:SS.s format', () => {
    expect(parseMs('1:30.5')).toBe(90500)
    expect(parseMs('0:01.5')).toBe(1500)
  })

  it('parses M:SS.sss format', () => {
    expect(parseMs('0:01.250')).toBe(1250)
  })

  it('parses negative values', () => {
    expect(parseMs('-1:30')).toBe(-90000)
  })

  it('returns null for empty string', () => {
    expect(parseMs('')).toBeNull()
    expect(parseMs('  ')).toBeNull()
  })

  it('returns null for invalid formats', () => {
    expect(parseMs('abc')).toBeNull()
    expect(parseMs('1:60')).toBeNull()
    expect(parseMs('1:99')).toBeNull()
  })

  it('handles single-digit seconds', () => {
    expect(parseMs('1:5')).toBe(65000)
  })

  it('roundtrips with formatMs', () => {
    expect(parseMs(formatMs(90500))).toBe(90500)
    expect(parseMs(formatMs(0))).toBe(0)
    expect(parseMs(formatMs(5000))).toBe(5000)
  })
})

describe('typeColor', () => {
  it('returns an HSL color string', () => {
    const color = typeColor('chapter')
    expect(color).toMatch(/^hsl\(\d+, 60%, 50%\)$/)
  })

  it('is deterministic', () => {
    expect(typeColor('chapter')).toBe(typeColor('chapter'))
    expect(typeColor('annotation')).toBe(typeColor('annotation'))
  })

  it('produces different colors for different IDs', () => {
    expect(typeColor('chapter')).not.toBe(typeColor('annotation'))
  })

  it('handles empty string', () => {
    const color = typeColor('')
    expect(color).toMatch(/^hsl\(\d+, 60%, 50%\)$/)
  })
})
