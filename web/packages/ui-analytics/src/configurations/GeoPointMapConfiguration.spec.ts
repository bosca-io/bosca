import { describe, it, expect } from 'vitest'
import {
  GeoPointMapConfiguration,
  GEO_POINT_MAP_DEFAULT_POINT_COLOR,
  type GeoPoint,
} from './GeoPointMapConfiguration'

function ready(overrides: Record<string, unknown> = {}): GeoPointMapConfiguration {
  const c = new GeoPointMapConfiguration()
  c.loadConfiguration({ latitudeColumn: 'lat', longitudeColumn: 'lon', jitter: 0, ...overrides })
  return c
}

describe('GeoPointMapConfiguration', () => {
  it('has type GEO_POINT_MAP', () => {
    expect(new GeoPointMapConfiguration().type).toBe('GEO_POINT_MAP')
  })

  it('is not ready without columns', () => {
    expect(new GeoPointMapConfiguration().isReady).toBe(false)
  })

  it('is ready with latitude and longitude columns', () => {
    const c = new GeoPointMapConfiguration()
    c.loadConfiguration({ latitudeColumn: 'lat', longitudeColumn: 'lon' })
    expect(c.isReady).toBe(true)
  })

  it('is not ready with only a latitude column', () => {
    const c = new GeoPointMapConfiguration()
    c.loadConfiguration({ latitudeColumn: 'lat' })
    expect(c.isReady).toBe(false)
  })

  it('does not throw on null config', () => {
    const c = new GeoPointMapConfiguration()
    c.loadConfiguration(null as unknown as Record<string, unknown>)
    expect(c.latitudeColumn).toBe('')
  })

  describe('getConfiguration', () => {
    it('omits empty/default values', () => {
      expect(new GeoPointMapConfiguration().getConfiguration()).toEqual({})
    })

    it('round-trips a full configuration', () => {
      const c = new GeoPointMapConfiguration()
      const config = {
        latitudeColumn: 'lat',
        longitudeColumn: 'lon',
        idColumn: 'sessionId',
        seriesColumn: 'appVersion',
        jitter: 0.5,
        pointRadius: 8,
      }
      c.loadConfiguration(config)
      expect(c.getConfiguration()).toEqual(config)
    })

    it('omits jitter and pointRadius when at their defaults', () => {
      const c = ready({ jitter: 0.25, pointRadius: 5 })
      expect(c.getConfiguration()).toEqual({ latitudeColumn: 'lat', longitudeColumn: 'lon' })
    })

    it('preserves field settings', () => {
      const c = ready({ fields: { lat: { label: 'Latitude' } } })
      expect(c.getConfiguration().fields).toEqual({ lat: { label: 'Latitude' } })
    })
  })

  describe('setData', () => {
    it('returns empty and clears series when there is no data', () => {
      const c = ready({ seriesColumn: 'v' })
      c.setData([{ lat: 1, lon: 2, v: 'a' }])
      expect(c.setData([])).toEqual([])
      expect(c.getMapData().points).toEqual([])
      expect(c.getSeries()).toEqual([])
    })

    it('returns empty when not ready even with data', () => {
      const c = new GeoPointMapConfiguration()
      expect(c.setData([{ lat: 1, lon: 2 }])).toEqual([])
    })

    it('maps rows to points at their coordinates', () => {
      const c = ready({ idColumn: 'sessionId' })
      c.setData([{ sessionId: 's1', lat: 40.7, lon: -74 }])
      const points = c.getMapData().points
      expect(points).toHaveLength(1)
      expect(points[0]).toMatchObject({ id: 's1', latitude: 40.7, longitude: -74 })
    })

    it('preserves source fields for point tooltips and click handlers', () => {
      const c = ready({ idColumn: 'sessionId', seriesColumn: 'appVersion' })
      c.setData([{ sessionId: 's1', lat: 40.7, lon: -74, appId: 'studio', appVersion: '2.4.1' }])
      expect(c.getMapData().points[0]).toMatchObject({
        id: 's1',
        sessionId: 's1',
        appId: 'studio',
        appVersion: '2.4.1',
      })
    })

    it('skips rows with non-finite coordinates', () => {
      const c = ready()
      c.setData([
        { lat: 40.7, lon: -74 },
        { lat: 'n/a', lon: -74 },
        { lat: 10, lon: null },
      ])
      expect(c.getMapData().points).toHaveLength(1)
    })

    it('keeps points at (0, 0) since 0 is a valid coordinate', () => {
      const c = ready()
      c.setData([{ lat: 0, lon: 0 }])
      expect(c.getMapData().points).toHaveLength(1)
      expect(c.getMapData().points[0]).toMatchObject({ latitude: 0, longitude: 0 })
    })

    it('derives a stable id from the id column', () => {
      const c = ready({ idColumn: 'sessionId' })
      c.setData([{ sessionId: 's1', lat: 1, lon: 2 }])
      expect(c.getMapData().points[0]!.id).toBe('s1')
    })

    it('falls back to an index-based id when the id column is missing/null', () => {
      const c = ready({ idColumn: 'sessionId' })
      c.setData([{ lat: 1, lon: 2 }])
      expect(c.getMapData().points[0]!.id).toBe('0:1,2')
    })

    it('assigns a distinct color per series value and bakes it into points', () => {
      const c = ready({ seriesColumn: 'appVersion' })
      c.setData([
        { lat: 1, lon: 2, appVersion: '1.0.0' },
        { lat: 3, lon: 4, appVersion: '1.1.0' },
        { lat: 5, lon: 6, appVersion: '1.0.0' },
      ])
      const points = c.getMapData().points
      const byVersion = (v: string) => points.find((p) => p.series === v)!
      expect(byVersion('1.0.0').color).toBeDefined()
      expect(byVersion('1.1.0').color).toBeDefined()
      expect(byVersion('1.0.0').color).not.toBe(byVersion('1.1.0').color)
      // Same version → same color across rows.
      expect(points.filter((p) => p.series === '1.0.0').map((p) => p.color)).toEqual([
        byVersion('1.0.0').color,
        byVersion('1.0.0').color,
      ])
    })

    it('leaves series/color undefined when no series column is configured', () => {
      const c = ready()
      c.setData([{ lat: 1, lon: 2 }])
      const point = c.getMapData().points[0]!
      expect(point.series).toBeUndefined()
      expect(point.color).toBeUndefined()
    })

    it('treats a null series value as no series', () => {
      const c = ready({ seriesColumn: 'appVersion' })
      c.setData([{ lat: 1, lon: 2, appVersion: null }])
      const point = c.getMapData().points[0]!
      expect(point.series).toBeUndefined()
      expect(c.getSeries()).toEqual([])
    })
  })

  describe('jitter', () => {
    it('applies no offset when jitter is 0', () => {
      const c = ready({ idColumn: 'id', jitter: 0 })
      c.setData([{ id: 'x', lat: 10, lon: 20 }])
      expect(c.getMapData().points[0]).toMatchObject({ latitude: 10, longitude: 20 })
    })

    it('is deterministic for the same id across calls', () => {
      const c = ready({ idColumn: 'id', jitter: 0.5 })
      c.setData([{ id: 'sess-1', lat: 10, lon: 20 }])
      const first = { ...c.getMapData().points[0] } as GeoPoint
      c.setData([{ id: 'sess-1', lat: 10, lon: 20 }])
      const second = c.getMapData().points[0]!
      expect(second.latitude).toBe(first.latitude)
      expect(second.longitude).toBe(first.longitude)
    })

    it('keeps the offset within [-jitter, +jitter]', () => {
      const c = ready({ idColumn: 'id', jitter: 0.5 })
      c.setData(
        Array.from({ length: 50 }, (_, i) => ({ id: `s${i}`, lat: 0, lon: 0 })),
      )
      for (const p of c.getMapData().points) {
        expect(Math.abs(p.latitude)).toBeLessThanOrEqual(0.5)
        expect(Math.abs(p.longitude)).toBeLessThanOrEqual(0.5)
      }
    })

    it('separates two points that share the same coordinate', () => {
      const c = ready({ idColumn: 'id', jitter: 0.5 })
      c.setData([
        { id: 'a', lat: 51.5, lon: -0.12 },
        { id: 'b', lat: 51.5, lon: -0.12 },
      ])
      const [a, b] = c.getMapData().points
      expect(a!.latitude === b!.latitude && a!.longitude === b!.longitude).toBe(false)
    })
  })

  describe('getMapData', () => {
    it('returns empty points before data is set', () => {
      expect(new GeoPointMapConfiguration().getMapData()).toEqual({ points: [] })
    })
  })

  describe('getSeries', () => {
    it('returns distinct series values in sorted order', () => {
      const c = ready({ seriesColumn: 'v' })
      c.setData([
        { lat: 1, lon: 1, v: '2.0.0' },
        { lat: 2, lon: 2, v: '1.0.0' },
        { lat: 3, lon: 3, v: '1.0.0' },
      ])
      expect(c.getSeries()).toEqual(['1.0.0', '2.0.0'])
    })
  })

  describe('getPointColor', () => {
    it('returns the baked point color', () => {
      const c = ready({ seriesColumn: 'v' })
      c.setData([{ lat: 1, lon: 2, v: '1.0.0' }])
      const point = c.getMapData().points[0]!
      expect(c.getPointColor()(point)).toBe(point.color)
    })

    it('falls back to the default color for points without one', () => {
      const colorOf = ready().getPointColor()
      expect(colorOf({})).toBe(GEO_POINT_MAP_DEFAULT_POINT_COLOR)
      expect(colorOf(null)).toBe(GEO_POINT_MAP_DEFAULT_POINT_COLOR)
    })
  })

  describe('getCategories', () => {
    it('is empty with no series column', () => {
      const c = ready()
      c.setData([{ lat: 1, lon: 2 }])
      expect(c.getCategories()).toEqual({})
    })

    it('maps each series value to a legend entry matching its color', () => {
      const c = ready({ seriesColumn: 'v' })
      c.setData([
        { lat: 1, lon: 2, v: '1.0.0' },
        { lat: 3, lon: 4, v: '1.1.0' },
      ])
      const categories = c.getCategories() as Record<string, { name: string, color: string }>
      expect(Object.keys(categories)).toEqual(['1.0.0', '1.1.0'])
      const v1 = c.getMapData().points.find((p) => p.series === '1.0.0')!
      expect(categories['1.0.0']).toEqual({ name: '1.0.0', color: v1.color })
    })
  })
})
