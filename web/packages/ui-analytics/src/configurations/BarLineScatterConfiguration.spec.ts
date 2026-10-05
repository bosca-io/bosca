import { describe, it, expect } from 'vitest'
import { BarLineScatterConfiguration } from './BarLineScatterConfiguration'

describe('BarLineScatterConfiguration', () => {
  it('stores the visualization type', () => {
    expect(new BarLineScatterConfiguration('BAR').type).toBe('BAR')
    expect(new BarLineScatterConfiguration('LINE').type).toBe('LINE')
    expect(new BarLineScatterConfiguration('SCATTER').type).toBe('SCATTER')
  })

  it('is not ready without axes', () => {
    expect(new BarLineScatterConfiguration('BAR').isReady).toBe(false)
  })

  it('is not ready with only x axis', () => {
    const c = new BarLineScatterConfiguration('BAR')
    c.loadConfiguration({ x: 'date' })
    expect(c.isReady).toBe(false)
  })

  it('is ready with x and y axes', () => {
    const c = new BarLineScatterConfiguration('BAR')
    c.loadConfiguration({ x: 'date', y: ['count'] })
    expect(c.isReady).toBe(true)
  })

  it('loads y as array from single value', () => {
    const c = new BarLineScatterConfiguration('BAR')
    c.loadConfiguration({ x: 'date', y: 'count' })
    expect(c.yAxisKeys).toEqual(['count'])
  })

  it('does not throw on null config', () => {
    const c = new BarLineScatterConfiguration('BAR')
    c.loadConfiguration(null as unknown as Record<string, unknown>)
    expect(c.xAxisKey).toBeUndefined()
  })

  it('handles missing y in config', () => {
    const c = new BarLineScatterConfiguration('BAR')
    c.loadConfiguration({ x: 'date' })
    expect(c.yAxisKeys).toEqual([])
  })

  it('round-trips getConfiguration', () => {
    const c = new BarLineScatterConfiguration('LINE')
    c.loadConfiguration({ x: 'date', y: ['a', 'b'], xLabel: 'Date', yLabel: 'Value' })
    const out = c.getConfiguration()
    expect(out.x).toBe('date')
    expect(out.y).toEqual(['a', 'b'])
    expect(out.xLabel).toBe('Date')
    expect(out.yLabel).toBe('Value')
  })

  it('includes field settings in config when present', () => {
    const c = new BarLineScatterConfiguration('BAR')
    c.loadConfiguration({ x: 'x', y: ['y'], fields: { x: { label: 'X Axis' } } })
    expect(c.getConfiguration().fields).toBeDefined()
  })

  describe('labels', () => {
    it('returns custom x label', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'date', y: ['v'], xLabel: 'Date' })
      expect(c.xLabel).toBe('Date')
    })

    it('falls back to field setting label for x', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'date', y: ['v'], fields: { date: { label: 'Timestamp' } } })
      expect(c.xLabel).toBe('Timestamp')
    })

    it('returns undefined x label when no custom or field label', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'date', y: ['v'] })
      expect(c.xLabel).toBeUndefined()
    })

    it('returns custom y label', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'x', y: ['v'], yLabel: 'Count' })
      expect(c.yLabel).toBe('Count')
    })

    it('falls back to field setting label for y', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'x', y: ['v'], fields: { v: { label: 'Value' } } })
      expect(c.yLabel).toBe('Value')
    })

    it('returns undefined y label when no keys', () => {
      const c = new BarLineScatterConfiguration('BAR')
      expect(c.yLabel).toBeUndefined()
    })
  })

  describe('getCategories', () => {
    it('builds categories from y axis keys', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'x', y: ['revenue', 'cost'] })
      const cats = c.getCategories()
      expect(cats).toHaveProperty('revenue')
      expect(cats).toHaveProperty('cost')
    })

    it('uses field labels when available', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'x', y: ['rev'], fields: { rev: { label: 'Revenue' } } })
      const cats = c.getCategories() as Record<string, Record<string, unknown>>
      expect(cats.rev.name).toBe('Revenue')
    })
  })

  describe('xAxisFormatter', () => {
    it('returns undefined when x is not date type', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'name', y: ['v'] })
      expect(c.xAxisFormatter).toBeUndefined()
    })

    it('returns formatter for date type x axis', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'ts', y: ['v'], fields: { ts: { type: 'date' } } })
      expect(c.xAxisFormatter).toBeTypeOf('function')
    })

    it('formats date values from data', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'ts', y: ['v'], fields: { ts: { type: 'date', format: 'yyyy' } } })
      c.setData([{ ts: '2024-06-15', v: 10 }])
      const fmt = c.xAxisFormatter!
      expect(fmt(0)).toBe('2024')
    })

    it('returns empty string for undefined data index', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'ts', y: ['v'], fields: { ts: { type: 'date' } } })
      c.setData([{ ts: '2024-01-01', v: 1 }])
      expect(c.xAxisFormatter!(99)).toBe('')
    })
  })

  describe('yAxisFormatter', () => {
    it('returns undefined when y is not date type', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'x', y: ['v'] })
      expect(c.yAxisFormatter).toBeUndefined()
    })

    it('returns formatter for date type y axis', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'x', y: ['ts'], fields: { ts: { type: 'date' } } })
      expect(c.yAxisFormatter).toBeTypeOf('function')
    })

    it('returns empty string for undefined data', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'x', y: ['ts'], fields: { ts: { type: 'date' } } })
      c.setData([{ x: 1, ts: '2024-01-01' }])
      expect(c.yAxisFormatter!(99)).toBe('')
    })

    it('returns empty string for invalid date data', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'x', y: ['ts'], fields: { ts: { type: 'date' } } })
      c.setData([{ x: 1, ts: 'invalid-date' }])
      expect(c.yAxisFormatter!(0)).toBe('')
    })
  })

  describe('getBubbleProps', () => {
    it('returns empty for non-SCATTER types', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'x', y: ['v'] })
      expect(c.getBubbleProps()).toEqual({})
    })

    it('returns accessors for SCATTER type', () => {
      const c = new BarLineScatterConfiguration('SCATTER')
      c.loadConfiguration({ x: 'x', y: ['v'] })
      const props = c.getBubbleProps() as Record<string, (d: Record<string, unknown>) => unknown>
      expect(props.xAccessor({ x: 10 })).toBe(10)
      expect(props.yAccessor({ v: 20 })).toBe(20)
      expect(props.sizeAccessor({})).toBe(10)
    })

    it('returns empty when x or y missing for SCATTER', () => {
      const c = new BarLineScatterConfiguration('SCATTER')
      expect(c.getBubbleProps()).toEqual({})
    })
  })

  describe('valueLabel', () => {
    it('returns empty string for non-date y axis', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'x', y: ['v'] })
      expect(c.valueLabel!.label({ v: 42 })).toBe('')
    })

    it('formats date y values', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'x', y: ['ts'], fields: { ts: { type: 'date' } } })
      const date = new Date(2024, 5, 15)
      expect(c.valueLabel!.label({ ts: date })).toBe('15 Jun')
    })

    it('returns empty string for invalid date', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'x', y: ['ts'], fields: { ts: { type: 'date' } } })
      expect(c.valueLabel!.label({ ts: 'invalid' })).toBe('')
    })

    it('has expected style properties', () => {
      const c = new BarLineScatterConfiguration('BAR')
      expect(c.valueLabel!.labelSpacing).toBe(16)
      expect(c.valueLabel!.labelFontSize).toBe(10)
      expect(c.valueLabel!.color).toBe('var(--fg-1)')
    })
  })

  describe('tooltipTitleFormatter', () => {
    it('returns undefined when x is not date type', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'x', y: ['v'] })
      expect(c.tooltipTitleFormatter).toBeUndefined()
    })

    it('formats date x values', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'ts', y: ['v'], fields: { ts: { type: 'date', format: 'yyyy-MM-dd' } } })
      const date = new Date(2024, 5, 15)
      expect(c.tooltipTitleFormatter!(({ ts: date }))).toBe('2024-06-15')
    })

    it('falls back to String for invalid date', () => {
      const c = new BarLineScatterConfiguration('BAR')
      c.loadConfiguration({ x: 'ts', y: ['v'], fields: { ts: { type: 'date' } } })
      expect(c.tooltipTitleFormatter!({ ts: 'invalid' })).toBe('invalid')
    })
  })
})
