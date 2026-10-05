import { GeoPointMapConfiguration } from './GeoPointMapConfiguration'

/** Default live-session inactivity window (ms) — mirrors the heartbeat stream's 15-minute TTL. */
export const DEFAULT_LIVE_SESSION_TTL_MS = 15 * 60 * 1000

/**
 * Live dots are smaller than the geo point map's default: sessions cluster around population
 * centers, and the pulse animation already draws the eye without needing bulk.
 */
export const DEFAULT_LIVE_POINT_RADIUS = 3

/** A single live-session heartbeat as delivered by the `liveSessions` subscription. */
export interface LiveSessionMessage {
  sessionId: string
  lat: number
  lon: number
  appVersion?: string
  [key: string]: unknown
}

/**
 * The live sessions map: a streaming variant of the geo point map whose data is a set that
 * updates constantly rather than a query result. It keeps a rolling set of sessions keyed by
 * `sessionId` — each incoming heartbeat upserts a point, and sessions with no heartbeat within
 * the TTL are evicted. All rendering (color-by-version, jitter, legend, projection) is inherited
 * unchanged from {@link GeoPointMapConfiguration}; only the data lifecycle differs.
 *
 * This class is deliberately network-free and time-explicit (methods take `now`): the host
 * (Studio) owns the `liveSessions` subscription and drives this state machine via
 * `applySnapshot` / `applyMessage` / `evictStale`, so the streaming logic stays fully unit-testable.
 */
export class LiveSessionsMapConfiguration extends GeoPointMapConfiguration {
  /** The application whose sessions are streamed (subscription argument). Empty = every application. */
  appId: string = ''
  /** Optional version filter passed to the subscription; empty = all versions. */
  appVersion: string = ''
  /** Inactivity window after which a silent session is evicted. */
  ttlMs: number = DEFAULT_LIVE_SESSION_TTL_MS

  private sessions = new Map<string, { row: Record<string, unknown>, lastSeen: number }>()

  constructor() {
    super('LIVE_SESSIONS_MAP')
    // The stream has a fixed message shape, so the point columns are defaulted rather than
    // user-mapped: { sessionId, lat, lon, appId, appVersion }. The series (color/legend) default
    // depends on scope — see [defaultSeriesColumn] — and the constructor state is all-apps.
    this.latitudeColumn = 'lat'
    this.longitudeColumn = 'lon'
    this.idColumn = 'sessionId'
    this.seriesColumn = this.defaultSeriesColumn()
    this.pointRadius = DEFAULT_LIVE_POINT_RADIUS
  }

  override loadConfiguration(config: Record<string, unknown>) {
    const c = config ?? {}
    this.latitudeColumn = (c.latitudeColumn as string) || 'lat'
    this.longitudeColumn = (c.longitudeColumn as string) || 'lon'
    this.idColumn = (c.idColumn as string) || 'sessionId'
    this.appId = (c.appId as string) || ''
    this.appVersion = (c.appVersion as string) || ''
    // After appId: the implied series depends on whether the map is scoped to one app.
    this.seriesColumn = (c.seriesColumn as string) || this.defaultSeriesColumn()
    this.ttlMs = typeof c.ttlMs === 'number' ? c.ttlMs : DEFAULT_LIVE_SESSION_TTL_MS
    if (typeof c.jitter === 'number') this.jitter = c.jitter
    if (typeof c.pointRadius === 'number') this.pointRadius = c.pointRadius
    this.fieldSettings = (c.fields as Record<string, unknown> ?? {}) as typeof this.fieldSettings
  }

  override getConfiguration() {
    const config = super.getConfiguration()
    // Point columns are implied defaults for the fixed stream shape — omit them when unchanged.
    if (config.latitudeColumn === 'lat') delete config.latitudeColumn
    if (config.longitudeColumn === 'lon') delete config.longitudeColumn
    if (config.idColumn === 'sessionId') delete config.idColumn
    if (config.seriesColumn === this.defaultSeriesColumn()) delete config.seriesColumn
    // The base class emits any radius that differs from ITS default (5); the live default is 3.
    if (config.pointRadius === DEFAULT_LIVE_POINT_RADIUS) delete config.pointRadius
    if (this.appId) config.appId = this.appId
    if (this.appVersion) config.appVersion = this.appVersion
    if (this.ttlMs !== DEFAULT_LIVE_SESSION_TTL_MS) config.ttlMs = this.ttlMs
    return config
  }

  /**
   * The implied series (point color + legend) for the current scope: a single-app map colors by
   * version; an all-applications map colors by application — versions from unrelated apps would
   * collide and mislead.
   */
  private defaultSeriesColumn(): string {
    return this.appId ? 'appVersion' : 'appId'
  }

  /** Always ready: an empty appId is the all-applications map, not a missing configuration. */
  override get isReady() {
    return true
  }

  /** Number of live sessions currently on the map. */
  get sessionCount(): number {
    return this.sessions.size
  }

  /** The raw current session rows (one per live session) — for feeding a renderer or exporting. */
  getSessions(): Record<string, unknown>[] {
    return Array.from(this.sessions.values(), (entry) => entry.row)
  }

  /** Replace the entire live set (e.g. from the subscription's initial snapshot). */
  applySnapshot(messages: Record<string, unknown>[], now: number): void {
    this.sessions.clear()
    for (const message of messages) this.remember(message, now)
    this.rebuild()
  }

  /** Upsert a single heartbeat, refreshing that session's last-seen time. */
  applyMessage(message: Record<string, unknown>, now: number): void {
    if (this.remember(message, now)) this.rebuild()
  }

  /** Evict sessions with no heartbeat within the TTL. Returns whether anything changed. */
  evictStale(now: number): boolean {
    let changed = false
    for (const [id, entry] of this.sessions) {
      if (now - entry.lastSeen > this.ttlMs) {
        this.sessions.delete(id)
        changed = true
      }
    }
    if (changed) this.rebuild()
    return changed
  }

  /** Clear the live set. */
  reset(): void {
    this.sessions.clear()
    this.rebuild()
  }

  private remember(message: Record<string, unknown>, now: number): boolean {
    const raw = message[this.idColumn]
    // No sessionId → the point can't be deduped or evicted, so it is ignored.
    if (raw == null) return false
    this.sessions.set(String(raw), { row: message, lastSeen: now })
    return true
  }

  /** Re-derives the rendered point set from the current rolling session set. */
  private rebuild(): void {
    super.setData(Array.from(this.sessions.values(), (entry) => entry.row))
  }
}
