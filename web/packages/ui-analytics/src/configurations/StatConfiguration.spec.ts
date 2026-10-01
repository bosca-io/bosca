import { describe, it, expect } from 'vitest'
import { StatConfiguration } from './StatConfiguration'

describe('StatConfiguration', () => {
  it('has type STAT', () => {
    expect(new StatConfiguration().type).toBe('STAT')
  })

  it('is not ready without valueKey', () => {
    expect(new StatConfiguration().isReady).toBe(false)
  })

  it('is ready with valueKey', () => {
    const c = new StatConfiguration()
    c.loadConfiguration({ valueKey: 'count' })
    expect(c.isReady).toBe(true)
  })

  it('loads all config properties', () => {
    const c = new StatConfiguration()
    c.loadConfiguration({
      valueKey: 'count',
      labelKey: 'date',
      sparklineType: 'bar',
      aggregation: 'sum',
      prefix: '$',
      suffix: 'k',
      decimals: 2,
    })
    expect(c.valueKey).toBe('count')
    expect(c.labelKey).toBe('date')
    expect(c.sparklineType).toBe('bar')
    expect(c.aggregation).toBe('sum')
    expect(c.prefix).toBe('$')
    expect(c.suffix).toBe('k')
    expect(c.decimals).toBe(2)
  })

  it('uses defaults for missing config', () => {
    const c = new StatConfiguration()
    c.loadConfiguration({})
    expect(c.sparklineType).toBe('area')
    expect(c.aggregation).toBe('latest')
    expect(c.prefix).toBe('')
    expect(c.suffix).toBe('')
    expect(c.decimals).toBe(0)
  })

  it('does not throw on null config', () => {
    const c = new StatConfiguration()
    c.loadConfiguration(null as unknown as Record<string, unknown>)
    expect(c.valueKey).toBeUndefined()
  })

  describe('getStatValue', () => {
    it('returns -- without valueKey', () => {
      expect(new StatConfiguration().getStatValue()).toBe('--')
    })

    it('returns -- with no data', () => {
      const c = new StatConfiguration()
      c.loadConfiguration({ valueKey: 'v' })
      expect(c.getStatValue()).toBe('--')
    })

    it('returns -- when all values are NaN', () => {
      const c = new StatConfiguration()
      c.loadConfiguration({ valueKey: 'v' })
      c.setData([{ v: 'abc' }, { v: 'def' }])
      expect(c.getStatValue()).toBe('--')
    })

    it('calculates latest (last numeric value)', () => {
      const c = new StatConfiguration()
      c.loadConfiguration({ valueKey: 'v', aggregation: 'latest' })
      c.setData([{ v: 10 }, { v: 20 }, { v: 30 }])
      expect(c.getStatValue()).toBe('30')
    })

    it('calculates sum', () => {
      const c = new StatConfiguration()
      c.loadConfiguration({ valueKey: 'v', aggregation: 'sum' })
      c.setData([{ v: 10 }, { v: 20 }, { v: 30 }])
      expect(c.getStatValue()).toBe('60')
    })

    it('calculates avg', () => {
      const c = new StatConfiguration()
      c.loadConfiguration({ valueKey: 'v', aggregation: 'avg' })
      c.setData([{ v: 10 }, { v: 20 }, { v: 30 }])
      expect(c.getStatValue()).toBe('20')
    })

    it('calculates min', () => {
      const c = new StatConfiguration()
      c.loadConfiguration({ valueKey: 'v', aggregation: 'min' })
      c.setData([{ v: 10 }, { v: 5 }, { v: 30 }])
      expect(c.getStatValue()).toBe('5')
    })

    it('calculates max', () => {
      const c = new StatConfiguration()
      c.loadConfiguration({ valueKey: 'v', aggregation: 'max' })
      c.setData([{ v: 10 }, { v: 5 }, { v: 30 }])
      expect(c.getStatValue()).toBe('30')
    })

    it('applies prefix and suffix', () => {
      const c = new StatConfiguration()
      c.loadConfiguration({ valueKey: 'v', aggregation: 'sum', prefix: '$', suffix: 'k' })
      c.setData([{ v: 100 }])
      expect(c.getStatValue()).toBe('$100k')
    })

    it('applies decimal formatting', () => {
      const c = new StatConfiguration()
      c.loadConfiguration({ valueKey: 'v', aggregation: 'avg', decimals: 2 })
      c.setData([{ v: 10 }, { v: 20 }])
      expect(c.getStatValue()).toBe('15.00')
    })
  })

  describe('getSparklineData', () => {
    it('returns empty array without valueKey', () => {
      expect(new StatConfiguration().getSparklineData()).toEqual([])
    })

    it('returns data when valueKey is set', () => {
      const c = new StatConfiguration()
      c.loadConfiguration({ valueKey: 'v' })
      const data = [{ v: 1 }, { v: 2 }]
      c.setData(data)
      expect(c.getSparklineData()).toHaveLength(2)
    })
  })

  it('round-trips getConfiguration', () => {
    const c = new StatConfiguration()
    c.loadConfiguration({
      valueKey: 'v',
      labelKey: 'l',
      sparklineType: 'line',
      aggregation: 'max',
      prefix: '>',
      suffix: '<',
      decimals: 1,
    })
    const out = c.getConfiguration()
    expect(out.valueKey).toBe('v')
    expect(out.labelKey).toBe('l')
    expect(out.sparklineType).toBe('line')
    expect(out.aggregation).toBe('max')
    expect(out.prefix).toBe('>')
    expect(out.suffix).toBe('<')
    expect(out.decimals).toBe(1)
  })
})
