import { describe, it, expect } from 'vitest'
import { TopoJsonMapConfiguration } from './TopoJsonMapConfiguration'

describe('TopoJsonMapConfiguration', () => {
  it('has type TOPO_JSON_MAP', () => {
    expect(new TopoJsonMapConfiguration().type).toBe('TOPO_JSON_MAP')
  })

  it('is not ready without columns', () => {
    expect(new TopoJsonMapConfiguration().isReady).toBe(false)
  })

  it('is ready with both columns set', () => {
    const c = new TopoJsonMapConfiguration()
    c.loadConfiguration({ regionColumn: 'state', valueColumn: 'count' })
    expect(c.isReady).toBe(true)
  })

  it('is not ready with only region column', () => {
    const c = new TopoJsonMapConfiguration()
    c.loadConfiguration({ regionColumn: 'state' })
    expect(c.isReady).toBe(false)
  })

  it('does not throw on null config', () => {
    const c = new TopoJsonMapConfiguration()
    c.loadConfiguration(null as unknown as Record<string, unknown>)
    expect(c.regionColumn).toBe('')
  })

  it('transforms data into areas format', () => {
    const c = new TopoJsonMapConfiguration()
    c.loadConfiguration({ regionColumn: 'state', valueColumn: 'pop' })
    const result = c.setData([
      { state: 'CA', pop: 39 },
      { state: 'TX', pop: 29 },
    ])
    expect(result).toHaveLength(1)
    const areas = (result[0] as Record<string, unknown>).areas as Array<Record<string, unknown>>
    expect(areas).toHaveLength(2)
    expect(areas[0]).toEqual({ id: 'CA', name: 'CA', count: 39 })
    expect(areas[1]).toEqual({ id: 'TX', name: 'TX', count: 29 })
  })

  it('returns empty array for empty data', () => {
    const c = new TopoJsonMapConfiguration()
    c.loadConfiguration({ regionColumn: 'state', valueColumn: 'pop' })
    expect(c.setData([])).toEqual([])
  })

  it('round-trips getConfiguration', () => {
    const c = new TopoJsonMapConfiguration()
    c.loadConfiguration({ regionColumn: 'r', valueColumn: 'v' })
    expect(c.getConfiguration()).toEqual({ regionColumn: 'r', valueColumn: 'v' })
  })

  it('omits empty values from config', () => {
    const c = new TopoJsonMapConfiguration()
    expect(c.getConfiguration()).toEqual({})
  })

  describe('getMapData', () => {
    it('returns empty areas before data is set', () => {
      const c = new TopoJsonMapConfiguration()
      expect(c.getMapData()).toEqual({ areas: [] })
    })

    it('returns the unwrapped areas object after setData', () => {
      const c = new TopoJsonMapConfiguration()
      c.loadConfiguration({ regionColumn: 'state', valueColumn: 'pop' })
      c.setData([
        { state: 'CA', pop: 39 },
        { state: 'TX', pop: 29 },
      ])
      const map = c.getMapData()
      expect(map.areas).toHaveLength(2)
      expect(map.areas[0]).toEqual({ id: 'CA', name: 'CA', count: 39 })
    })
  })

  describe('getAreaColor', () => {
    it('returns a baseline color for areas with no numeric count', () => {
      const c = new TopoJsonMapConfiguration()
      const colorOf = c.getAreaColor()
      expect(colorOf(null)).toContain('--ui-color-primary-200')
      expect(colorOf({})).toContain('--ui-color-primary-200')
    })

    it('returns the darkest color for the maximum count', () => {
      const c = new TopoJsonMapConfiguration()
      c.loadConfiguration({ regionColumn: 'r', valueColumn: 'v' })
      c.setData([{ r: 'A', v: 1 }, { r: 'B', v: 10 }, { r: 'C', v: 100 }])
      const colorOf = c.getAreaColor()
      expect(colorOf({ count: 100 })).toContain('--ui-color-primary-900')
      expect(colorOf({ count: 1 })).toContain('--ui-color-primary-500')
    })

    it('falls back to base color when range is zero', () => {
      const c = new TopoJsonMapConfiguration()
      c.loadConfiguration({ regionColumn: 'r', valueColumn: 'v' })
      c.setData([{ r: 'A', v: 5 }, { r: 'B', v: 5 }])
      const colorOf = c.getAreaColor()
      expect(colorOf({ count: 5 })).toContain('--ui-color-primary-500')
    })
  })
})
