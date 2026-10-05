import { describe, it, expect } from 'vitest'
import { PieDoughnutConfiguration } from './PieDoughnutConfiguration'

describe('PieDoughnutConfiguration', () => {
  it('stores the visualization type', () => {
    expect(new PieDoughnutConfiguration('PIE').type).toBe('PIE')
    expect(new PieDoughnutConfiguration('DOUGHNUT').type).toBe('DOUGHNUT')
  })

  it('is not ready without keys', () => {
    expect(new PieDoughnutConfiguration('PIE').isReady).toBe(false)
  })

  it('is ready with both keys', () => {
    const c = new PieDoughnutConfiguration('PIE')
    c.loadConfiguration({ label: 'name', value: 'count' })
    expect(c.isReady).toBe(true)
  })

  it('is not ready with only label key', () => {
    const c = new PieDoughnutConfiguration('PIE')
    c.loadConfiguration({ label: 'name' })
    expect(c.isReady).toBe(false)
  })

  it('does not throw on null config', () => {
    const c = new PieDoughnutConfiguration('PIE')
    c.loadConfiguration(null as unknown as Record<string, unknown>)
    expect(c.labelKey).toBeUndefined()
  })

  it('round-trips getConfiguration', () => {
    const c = new PieDoughnutConfiguration('DOUGHNUT')
    c.loadConfiguration({ label: 'name', value: 'count' })
    const out = c.getConfiguration()
    expect(out.label).toBe('name')
    expect(out.value).toBe('count')
  })

  it('omits empty values from config', () => {
    const c = new PieDoughnutConfiguration('PIE')
    expect(c.getConfiguration()).toEqual({})
  })

  describe('getCategories', () => {
    it('returns empty when keys missing', () => {
      expect(new PieDoughnutConfiguration('PIE').getCategories()).toEqual({})
    })

    it('builds categories from data rows', () => {
      const c = new PieDoughnutConfiguration('PIE')
      c.loadConfiguration({ label: 'name', value: 'count' })
      c.setData([{ name: 'A', count: 10 }, { name: 'B', count: 20 }])
      const cats = c.getCategories() as Record<string, Record<string, unknown>>
      expect(cats[0].name).toBe('A')
      expect(cats[1].name).toBe('B')
    })

    it('formats date labels', () => {
      const c = new PieDoughnutConfiguration('PIE')
      c.loadConfiguration({ label: 'ts', value: 'count', fields: { ts: { type: 'date', format: 'yyyy' } } })
      c.setData([{ ts: '2024-01-15', count: 10 }])
      const cats = c.getCategories() as Record<string, Record<string, unknown>>
      expect(cats[0].name).toBe('2024')
    })

    it('falls back to String for invalid date labels', () => {
      const c = new PieDoughnutConfiguration('PIE')
      c.loadConfiguration({ label: 'ts', value: 'count', fields: { ts: { type: 'date' } } })
      c.setData([{ ts: 'invalid', count: 10 }])
      const cats = c.getCategories() as Record<string, Record<string, unknown>>
      expect(typeof cats[0].name).toBe('string')
    })
  })

  describe('getDonutData', () => {
    it('returns empty without value key', () => {
      expect(new PieDoughnutConfiguration('PIE').getDonutData()).toEqual([])
    })

    it('returns numeric values from data', () => {
      const c = new PieDoughnutConfiguration('PIE')
      c.loadConfiguration({ label: 'name', value: 'count' })
      c.setData([{ name: 'A', count: 10 }, { name: 'B', count: 20 }])
      expect(c.getDonutData()).toEqual([10, 20])
    })

    it('treats missing values as 0', () => {
      const c = new PieDoughnutConfiguration('PIE')
      c.loadConfiguration({ label: 'name', value: 'count' })
      c.setData([{ name: 'A' }])
      expect(c.getDonutData()).toEqual([0])
    })
  })

  describe('tooltipTitleFormatter', () => {
    it('returns undefined when label is not date type', () => {
      const c = new PieDoughnutConfiguration('PIE')
      c.loadConfiguration({ label: 'name', value: 'count' })
      expect(c.tooltipTitleFormatter).toBeUndefined()
    })

    it('formats date labels in tooltips', () => {
      const c = new PieDoughnutConfiguration('PIE')
      c.loadConfiguration({ label: 'ts', value: 'count', fields: { ts: { type: 'date', format: 'yyyy' } } })
      const date = new Date(2024, 5, 15)
      expect(c.tooltipTitleFormatter!({ label: date })).toBe('2024')
    })

    it('falls back to String for invalid dates', () => {
      const c = new PieDoughnutConfiguration('PIE')
      c.loadConfiguration({ label: 'ts', value: 'count', fields: { ts: { type: 'date' } } })
      expect(c.tooltipTitleFormatter!({ label: 'invalid' })).toBe('invalid')
    })
  })
})
