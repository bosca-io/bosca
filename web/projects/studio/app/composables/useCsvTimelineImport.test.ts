import { describe, it, expect } from 'vitest'
import {
  parseTimeValue,
  parseCsv,
  resolveColumns,
  findRelevantTypes,
} from './useCsvTimelineImport'
import type { TimeEventType } from './useTimeEvents'

describe('parseTimeValue', () => {
  it('parses M:SS format via parseMs', () => {
    expect(parseTimeValue('1:30')).toBe(90000)
    expect(parseTimeValue('0:05')).toBe(5000)
  })

  it('parses M:SS.s format', () => {
    expect(parseTimeValue('1:30.5')).toBe(90500)
  })

  it('parses HH:MM:SS format', () => {
    expect(parseTimeValue('01:02:03')).toBe(3723000)
    expect(parseTimeValue('00:01:30')).toBe(90000)
  })

  it('parses HH:MM:SS.mmm format', () => {
    expect(parseTimeValue('00:01:30.500')).toBe(90500)
  })

  it('parses negative HH:MM:SS', () => {
    expect(parseTimeValue('-01:02:03')).toBe(-3723000)
  })

  it('rejects HH:MM:SS with invalid minutes or seconds', () => {
    expect(parseTimeValue('01:60:00')).toBeNull()
    expect(parseTimeValue('01:00:60')).toBeNull()
  })

  it('parses seconds with suffix', () => {
    expect(parseTimeValue('90s')).toBe(90000)
    expect(parseTimeValue('1.5s')).toBe(1500)
  })

  it('parses raw milliseconds', () => {
    expect(parseTimeValue('1500')).toBe(1500)
    expect(parseTimeValue('250.5')).toBe(251)
  })

  it('returns null for empty string', () => {
    expect(parseTimeValue('')).toBeNull()
    expect(parseTimeValue('  ')).toBeNull()
  })

  it('returns null for invalid input', () => {
    expect(parseTimeValue('abc')).toBeNull()
    expect(parseTimeValue('not a time')).toBeNull()
  })
})

describe('parseCsv', () => {
  it('parses simple CSV', () => {
    const result = parseCsv('a,b,c\n1,2,3')
    expect(result).toEqual([['a', 'b', 'c'], ['1', '2', '3']])
  })

  it('handles quoted fields with commas', () => {
    const result = parseCsv('a,"b,c",d\n1,"2,3",4')
    expect(result).toEqual([['a', 'b,c', 'd'], ['1', '2,3', '4']])
  })

  it('handles quoted fields with newlines', () => {
    const result = parseCsv('a,"b\nc",d')
    expect(result).toEqual([['a', 'b\nc', 'd']])
  })

  it('handles escaped quotes', () => {
    const result = parseCsv('a,"b""c",d')
    expect(result).toEqual([['a', 'b"c', 'd']])
  })

  it('strips UTF-8 BOM', () => {
    const result = parseCsv('\uFEFFa,b\n1,2')
    expect(result).toEqual([['a', 'b'], ['1', '2']])
  })

  it('handles CRLF line endings', () => {
    const result = parseCsv('a,b\r\n1,2')
    expect(result).toEqual([['a', 'b'], ['1', '2']])
  })

  it('skips empty rows', () => {
    const result = parseCsv('a,b\n\n1,2')
    expect(result).toEqual([['a', 'b'], ['1', '2']])
  })

  it('handles single column', () => {
    const result = parseCsv('a\n1\n2')
    expect(result).toEqual([['a'], ['1'], ['2']])
  })
})

describe('resolveColumns', () => {
  it('detects start column by name', () => {
    const result = resolveColumns(['name', 'start', 'end'])
    expect(result.startCol).toBe(1)
    expect(result.endCol).toBe(2)
  })

  it('detects start_time and end_time (normalized)', () => {
    const result = resolveColumns(['Start Time', 'End Time', 'Title'])
    expect(result.startCol).toBe(0)
    expect(result.endCol).toBe(1)
  })

  it('detects begin and stop as aliases', () => {
    const result = resolveColumns(['begin', 'stop', 'notes'])
    expect(result.startCol).toBe(0)
    expect(result.endCol).toBe(1)
  })

  it('matches attribute keys case-insensitively', () => {
    const result = resolveColumns(['start', 'Title', 'Description'], ['title', 'description'])
    expect(result.attributeCols).toEqual([
      { col: 1, key: 'title' },
      { col: 2, key: 'description' },
    ])
  })

  it('falls back to positional when no matches', () => {
    const result = resolveColumns(['col1', 'col2', 'col3'])
    expect(result.startCol).toBe(0)
    expect(result.endCol).toBe(1)
  })

  it('does not use positional fallback when attribute columns are found', () => {
    const result = resolveColumns(['title', 'notes'], ['title', 'notes'])
    expect(result.startCol).toBe(-1)
    expect(result.attributeCols.length).toBe(2)
  })
})

describe('findRelevantTypes', () => {
  const makeType = (id: string, attrKeys: string[]): TimeEventType => ({
    id,
    name: id,
    description: '',
    schema: null,
    configuration: {},
    attributes: attrKeys.map((k) => ({
      key: k,
      name: k,
      description: '',
      type: 'STRING',
      ui: 'INPUT',
      list: false,
      configuration: null,
      supplementaryKey: null,
    })),
  })

  it('matches types by attribute columns', () => {
    const types = [
      makeType('slide', ['title', 'image']),
      makeType('chapter', ['name']),
    ]
    const cols = [{ col: 1, key: 'title' }]
    const result = findRelevantTypes(cols, types, new Set())
    expect(result.map((t) => t.id)).toEqual(['slide'])
  })

  it('falls back to existing types for time-only imports', () => {
    const types = [
      makeType('slide', ['title']),
      makeType('chapter', ['name']),
    ]
    const existing = new Set(['chapter'])
    const result = findRelevantTypes([], types, existing)
    expect(result.map((t) => t.id)).toEqual(['chapter'])
  })

  it('returns empty when no match', () => {
    const types = [makeType('slide', ['title'])]
    const cols = [{ col: 1, key: 'nonexistent' }]
    const result = findRelevantTypes(cols, types, new Set())
    expect(result).toEqual([])
  })
})
