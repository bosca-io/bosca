import { describe, it, expect } from 'vitest'
import {
  LiveSessionsMapConfiguration,
  DEFAULT_LIVE_SESSION_TTL_MS,
  DEFAULT_LIVE_POINT_RADIUS,
} from './LiveSessionsMapConfiguration'

/** A ready-to-stream config (app set, jitter off so coordinates are exact). */
function live(overrides: Record<string, unknown> = {}): LiveSessionsMapConfiguration {
  const c = new LiveSessionsMapConfiguration()
  c.loadConfiguration({ appId: 'app-1', jitter: 0, ...overrides })
  return c
}

describe('LiveSessionsMapConfiguration', () => {
  it('has type LIVE_SESSIONS_MAP', () => {
    expect(new LiveSessionsMapConfiguration().type).toBe('LIVE_SESSIONS_MAP')
  })

  it('defaults the point columns to the fixed stream shape', () => {
    const c = new LiveSessionsMapConfiguration()
    expect(c.latitudeColumn).toBe('lat')
    expect(c.longitudeColumn).toBe('lon')
    expect(c.idColumn).toBe('sessionId')
    // No appId yet → the all-applications scope, which colors by application.
    expect(c.seriesColumn).toBe('appId')
  })

  it('defaults the TTL to 15 minutes', () => {
    expect(new LiveSessionsMapConfiguration().ttlMs).toBe(DEFAULT_LIVE_SESSION_TTL_MS)
    expect(DEFAULT_LIVE_SESSION_TTL_MS).toBe(15 * 60 * 1000)
  })

  it('defaults the point radius to the smaller live-map size', () => {
    expect(new LiveSessionsMapConfiguration().pointRadius).toBe(DEFAULT_LIVE_POINT_RADIUS)
  })

  describe('readiness', () => {
    it('is ready without an appId — that is the all-applications map', () => {
      expect(new LiveSessionsMapConfiguration().isReady).toBe(true)
    })

    it('is ready with an appId', () => {
      const c = new LiveSessionsMapConfiguration()
      c.loadConfiguration({ appId: 'app-1' })
      expect(c.isReady).toBe(true)
    })
  })

  describe('loadConfiguration', () => {
    it('reads appId, appVersion and ttlMs', () => {
      const c = new LiveSessionsMapConfiguration()
      c.loadConfiguration({ appId: 'app-1', appVersion: '1.2.0', ttlMs: 60000 })
      expect(c.appId).toBe('app-1')
      expect(c.appVersion).toBe('1.2.0')
      expect(c.ttlMs).toBe(60000)
    })

    it('keeps default columns when the config omits them', () => {
      const c = new LiveSessionsMapConfiguration()
      c.loadConfiguration({ appId: 'app-1' })
      expect(c.latitudeColumn).toBe('lat')
      expect(c.seriesColumn).toBe('appVersion')
    })

    it('defaults the series to the application in all-applications scope', () => {
      const c = new LiveSessionsMapConfiguration()
      c.loadConfiguration({})
      expect(c.seriesColumn).toBe('appId')
    })

    it('allows overriding the columns', () => {
      const c = new LiveSessionsMapConfiguration()
      c.loadConfiguration({ appId: 'a', latitudeColumn: 'y', longitudeColumn: 'x', idColumn: 'sid', seriesColumn: 'ver' })
      expect(c.latitudeColumn).toBe('y')
      expect(c.idColumn).toBe('sid')
      expect(c.seriesColumn).toBe('ver')
    })

    it('falls back to the default TTL when omitted', () => {
      const c = new LiveSessionsMapConfiguration()
      c.loadConfiguration({ appId: 'a' })
      expect(c.ttlMs).toBe(DEFAULT_LIVE_SESSION_TTL_MS)
    })

    it('does not throw on null config', () => {
      const c = new LiveSessionsMapConfiguration()
      c.loadConfiguration(null as unknown as Record<string, unknown>)
      expect(c.appId).toBe('')
      expect(c.latitudeColumn).toBe('lat')
    })

    it('honors an explicit pointRadius', () => {
      const c = new LiveSessionsMapConfiguration()
      c.loadConfiguration({ appId: 'a', pointRadius: 6 })
      expect(c.pointRadius).toBe(6)
    })
  })

  describe('getConfiguration', () => {
    it('omits the default columns but keeps appId/appVersion/ttlMs', () => {
      const c = live({ appVersion: '2.0.0', ttlMs: 60000, jitter: 0.25 })
      expect(c.getConfiguration()).toEqual({ appId: 'app-1', appVersion: '2.0.0', ttlMs: 60000 })
    })

    it('omits ttlMs when at the default', () => {
      const c = live({ jitter: 0.25 })
      expect(c.getConfiguration()).toEqual({ appId: 'app-1' })
    })

    it('round-trips overridden columns and a custom jitter', () => {
      const c = new LiveSessionsMapConfiguration()
      const config = { appId: 'a', latitudeColumn: 'y', jitter: 0.5 }
      c.loadConfiguration(config)
      expect(c.getConfiguration()).toEqual(config)
    })

    it('omits the live default point radius but round-trips a custom one', () => {
      expect(live().getConfiguration()).not.toHaveProperty('pointRadius')
      expect(live({ pointRadius: 6 }).getConfiguration()).toMatchObject({ pointRadius: 6 })
    })

    it('omits the implied series for each scope but round-trips an explicit one', () => {
      // Single app → 'appVersion' implied; all apps → 'appId' implied.
      expect(live().getConfiguration()).not.toHaveProperty('seriesColumn')
      const allApps = new LiveSessionsMapConfiguration()
      allApps.loadConfiguration({ jitter: 0.25 })
      expect(allApps.getConfiguration()).not.toHaveProperty('seriesColumn')
      expect(live({ seriesColumn: 'ver' }).getConfiguration()).toMatchObject({ seriesColumn: 'ver' })
    })
  })

  describe('applyMessage', () => {
    it('upserts a heartbeat into the live set', () => {
      const c = live()
      c.applyMessage({ sessionId: 's1', lat: 40.7, lon: -74 }, 0)
      expect(c.sessionCount).toBe(1)
      expect(c.getMapData().points[0]).toMatchObject({ id: 's1', latitude: 40.7, longitude: -74 })
    })

    it('ignores messages without a sessionId', () => {
      const c = live()
      c.applyMessage({ lat: 1, lon: 2 }, 0)
      expect(c.sessionCount).toBe(0)
      expect(c.getMapData().points).toEqual([])
    })

    it('dedups by sessionId and keeps the latest position', () => {
      const c = live()
      c.applyMessage({ sessionId: 's1', lat: 1, lon: 2 }, 0)
      c.applyMessage({ sessionId: 's1', lat: 5, lon: 6 }, 10)
      expect(c.sessionCount).toBe(1)
      expect(c.getMapData().points[0]).toMatchObject({ latitude: 5, longitude: 6 })
    })

    it('renders sessions with no app configured — the all-applications map', () => {
      const c = new LiveSessionsMapConfiguration()
      c.loadConfiguration({ jitter: 0 })
      c.applyMessage({ sessionId: 's1', lat: 1, lon: 2, appId: 'app-1' }, 0)
      expect(c.getMapData().points[0]).toMatchObject({ id: 's1', latitude: 1, longitude: 2 })
    })
  })

  describe('applySnapshot', () => {
    it('replaces the entire live set', () => {
      const c = live()
      c.applyMessage({ sessionId: 's1', lat: 1, lon: 2 }, 0)
      c.applySnapshot([
        { sessionId: 's2', lat: 3, lon: 4 },
        { sessionId: 's3', lat: 5, lon: 6 },
      ], 10)
      expect(c.sessionCount).toBe(2)
      expect(c.getMapData().points.map((p) => p.id).sort()).toEqual(['s2', 's3'])
    })
  })

  describe('evictStale', () => {
    it('removes sessions past the TTL and keeps fresh ones', () => {
      const c = live({ ttlMs: 1000 })
      c.applyMessage({ sessionId: 's1', lat: 1, lon: 2 }, 0)
      c.applyMessage({ sessionId: 's2', lat: 3, lon: 4 }, 500)
      expect(c.evictStale(1001)).toBe(true) // s1 age 1001 > 1000, s2 age 501 <= 1000
      expect(c.sessionCount).toBe(1)
      expect(c.getMapData().points.map((p) => p.id)).toEqual(['s2'])
    })

    it('returns false and changes nothing when all sessions are fresh', () => {
      const c = live({ ttlMs: 1000 })
      c.applyMessage({ sessionId: 's1', lat: 1, lon: 2 }, 0)
      expect(c.evictStale(500)).toBe(false)
      expect(c.sessionCount).toBe(1)
    })

    it('a re-heartbeat refreshes last-seen so the session survives eviction', () => {
      const c = live({ ttlMs: 1000 })
      c.applyMessage({ sessionId: 's1', lat: 1, lon: 2 }, 0)
      c.applyMessage({ sessionId: 's1', lat: 1, lon: 2 }, 800) // refresh
      expect(c.evictStale(1001)).toBe(false) // last-seen 800, age 201 <= 1000
      expect(c.sessionCount).toBe(1)
    })
  })

  describe('getSessions', () => {
    it('returns the raw current session rows, one per live session', () => {
      const c = live()
      c.applyMessage({ sessionId: 's1', lat: 1, lon: 2, appVersion: '1.0.0' }, 0)
      c.applyMessage({ sessionId: 's1', lat: 5, lon: 6, appVersion: '1.0.0' }, 10) // dedups
      c.applyMessage({ sessionId: 's2', lat: 3, lon: 4, appVersion: '2.0.0' }, 10)
      const rows = c.getSessions()
      expect(rows).toHaveLength(2)
      expect(rows).toContainEqual({ sessionId: 's1', lat: 5, lon: 6, appVersion: '1.0.0' })
      expect(rows).toContainEqual({ sessionId: 's2', lat: 3, lon: 4, appVersion: '2.0.0' })
    })

    it('is empty before any message', () => {
      expect(new LiveSessionsMapConfiguration().getSessions()).toEqual([])
    })
  })

  describe('reset', () => {
    it('clears the live set', () => {
      const c = live()
      c.applyMessage({ sessionId: 's1', lat: 1, lon: 2 }, 0)
      c.reset()
      expect(c.sessionCount).toBe(0)
      expect(c.getMapData().points).toEqual([])
    })
  })

  describe('inherited rendering', () => {
    it('colors sessions by app version with a matching legend', () => {
      const c = live()
      c.applySnapshot([
        { sessionId: 's1', lat: 1, lon: 2, appVersion: '1.0.0' },
        { sessionId: 's2', lat: 3, lon: 4, appVersion: '1.1.0' },
      ], 0)
      const points = c.getMapData().points
      const v1 = points.find((p) => p.id === 's1')!
      const v2 = points.find((p) => p.id === 's2')!
      expect(v1.series).toBe('1.0.0')
      expect(v1.color).toBeDefined()
      expect(v1.color).not.toBe(v2.color)
      const categories = c.getCategories() as Record<string, { name: string, color: string }>
      expect(Object.keys(categories)).toEqual(['1.0.0', '1.1.0'])
      expect(categories['1.0.0']).toEqual({ name: '1.0.0', color: v1.color })
    })

    it('colors sessions by application in all-applications scope', () => {
      const c = new LiveSessionsMapConfiguration()
      c.loadConfiguration({ jitter: 0 })
      c.applySnapshot([
        { sessionId: 's1', lat: 1, lon: 2, appId: 'studio', appVersion: '1.0.0' },
        { sessionId: 's2', lat: 3, lon: 4, appId: 'web', appVersion: '1.0.0' },
      ], 0)
      const points = c.getMapData().points
      const p1 = points.find((p) => p.id === 's1')!
      const p2 = points.find((p) => p.id === 's2')!
      expect(p1.series).toBe('studio')
      expect(p2.series).toBe('web')
      expect(p1.color).not.toBe(p2.color)
      expect(Object.keys(c.getCategories())).toEqual(['studio', 'web'])
    })
  })
})
