import { describe, it, expect } from 'vitest'
import { VisualizationConfiguration } from './VisualizationConfiguration'

class TestConfig extends VisualizationConfiguration {
  loaded: Record<string, unknown> = {}
  constructor() { super('TABLE') }
  loadConfiguration(config: Record<string, unknown>) { this.loaded = config }
  getConfiguration() { return this.loaded }
  get isReady() { return true }
}

describe('VisualizationConfiguration', () => {
  it('stores the visualization type', () => {
    expect(new TestConfig().type).toBe('TABLE')
  })

  it('returns empty defaults for optional overrides', () => {
    const c = new TestConfig()
    expect(c.getCategories()).toEqual({})
    expect(c.getDonutData()).toEqual([])
    expect(c.getBubbleProps()).toEqual({})
    expect(c.getNumberValue()).toBe('--')
    expect(c.xLabel).toBeUndefined()
    expect(c.yLabel).toBeUndefined()
    expect(c.xAxisFormatter).toBeUndefined()
    expect(c.yAxisFormatter).toBeUndefined()
    expect(c.tooltipTitleFormatter).toBeUndefined()
    expect(c.valueLabel).toBeUndefined()
  })

  it('getColumns returns all columns by default', () => {
    const c = new TestConfig()
    const cols = [{ accessorKey: 'a' }]
    expect(c.getColumns(cols)).toBe(cols)
  })

  it('getData returns empty before setData', () => {
    expect(new TestConfig().getData()).toEqual([])
  })

  describe('setData', () => {
    it('returns empty array for empty data', () => {
      expect(new TestConfig().setData([])).toEqual([])
    })

    it('returns empty array for null data', () => {
      expect(new TestConfig().setData(null as unknown as Record<string, unknown>[])).toEqual([])
    })

    it('converts number fields to numbers', () => {
      const c = new TestConfig()
      c.fieldSettings = { amt: { type: 'number' } }
      const result = c.setData([{ amt: '42' }])
      expect(result[0].amt).toBe(42)
    })

    it('leaves non-numeric values as-is for number fields', () => {
      const c = new TestConfig()
      c.fieldSettings = { amt: { type: 'number' } }
      const result = c.setData([{ amt: 'abc' }])
      expect(result[0].amt).toBe('abc')
    })

    it('converts string fields to strings', () => {
      const c = new TestConfig()
      c.fieldSettings = { code: { type: 'string' } }
      const result = c.setData([{ code: 123 }])
      expect(result[0].code).toBe('123')
    })

    it('converts epoch seconds to dates', () => {
      const c = new TestConfig()
      c.fieldSettings = { ts: { type: 'date' } }
      const result = c.setData([{ ts: 1700000000 }])
      expect(result[0].ts).toBeInstanceOf(Date)
    })

    it('converts epoch milliseconds to dates', () => {
      const c = new TestConfig()
      c.fieldSettings = { ts: { type: 'date' } }
      const result = c.setData([{ ts: 1700000000000 }])
      expect(result[0].ts).toBeInstanceOf(Date)
    })

    it('converts numeric strings to dates', () => {
      const c = new TestConfig()
      c.fieldSettings = { ts: { type: 'date' } }
      const result = c.setData([{ ts: '1700000000' }])
      expect(result[0].ts).toBeInstanceOf(Date)
    })

    it('converts ISO date strings to dates', () => {
      const c = new TestConfig()
      c.fieldSettings = { ts: { type: 'date' } }
      const result = c.setData([{ ts: '2024-01-15' }])
      expect(result[0].ts).toBeInstanceOf(Date)
    })

    it('converts null date values to null', () => {
      const c = new TestConfig()
      c.fieldSettings = { ts: { type: 'date' } }
      const result = c.setData([{ ts: null }])
      expect(result[0].ts).toBeNull()
    })

    it('converts empty string date values to null', () => {
      const c = new TestConfig()
      c.fieldSettings = { ts: { type: 'date' } }
      const result = c.setData([{ ts: '' }])
      expect(result[0].ts).toBeNull()
    })

    it('skips undefined date values', () => {
      const c = new TestConfig()
      c.fieldSettings = { ts: { type: 'date' } }
      const result = c.setData([{ ts: undefined }])
      expect(result[0].ts).toBeUndefined()
    })

    it('does not process fields not in fieldSettings', () => {
      const c = new TestConfig()
      c.fieldSettings = { amt: { type: 'number' } }
      const result = c.setData([{ amt: '10', name: 'test' }])
      expect(result[0].name).toBe('test')
    })

    it('skips fields where key is undefined in row', () => {
      const c = new TestConfig()
      c.fieldSettings = { missing: { type: 'number' } }
      const result = c.setData([{ other: 'val' }])
      expect(result[0].missing).toBeUndefined()
    })
  })
})
