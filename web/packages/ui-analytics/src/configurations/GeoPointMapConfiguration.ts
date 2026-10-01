import { VisualizationConfiguration } from './VisualizationConfiguration'
import { makePalette } from './colors'
import type { VisualizationType } from '../types'

export interface GeoPoint {
  id: string
  latitude: number
  longitude: number
  series?: string
  color?: string
  [key: string]: unknown
}

export interface GeoPointMapData {
  points: GeoPoint[]
}

/**
 * Muted, OPAQUE fill for the base world map so session markers stand out against it. Must be opaque:
 * a translucent color (e.g. the `--line` divider token) lets the host's background bleed through the
 * continents and washes them out. Hosts can override via `--map-land`; the fallback is a dark slate.
 */
export const GEO_POINT_MAP_BASE_AREA_COLOR = 'var(--map-land, #242a38)'

/** Fallback marker color for points that have no series (or no series column configured). */
export const GEO_POINT_MAP_DEFAULT_POINT_COLOR = 'var(--brand-2, #5ec5ff)'

const DEFAULT_JITTER = 0.25
const DEFAULT_POINT_RADIUS = 5

/**
 * A marker map: one point per row at its latitude/longitude, optionally colored by a
 * series column (e.g. app version). Unlike `TOPO_JSON_MAP` — a choropleth that colors
 * whole regions — this plots individual points, which is what the live sessions map needs.
 *
 * TTL / removal of stale points is the responsibility of the (live) data source that feeds
 * `setData`; this configuration renders whatever point set it is handed. A deterministic
 * per-id jitter separates co-located points that share a coarse city-centroid coordinate.
 */
export class GeoPointMapConfiguration extends VisualizationConfiguration {
  latitudeColumn: string = ''
  longitudeColumn: string = ''
  /** Column used as the stable point id (e.g. sessionId). Falls back to row index when unset. */
  idColumn: string = ''
  /** Column that colors and groups points (e.g. appVersion). No coloring/legend when unset. */
  seriesColumn: string = ''
  /** Per-point spread in degrees so co-located points don't fully overlap. `0` disables it. */
  jitter: number = DEFAULT_JITTER
  pointRadius: number = DEFAULT_POINT_RADIUS

  private seriesColors: Record<string, string> = {}

  constructor(type: VisualizationType = 'GEO_POINT_MAP') {
    super(type)
  }

  loadConfiguration(config: Record<string, unknown>) {
    if (!config) return
    this.latitudeColumn = (config.latitudeColumn as string) || ''
    this.longitudeColumn = (config.longitudeColumn as string) || ''
    this.idColumn = (config.idColumn as string) || ''
    this.seriesColumn = (config.seriesColumn as string) || ''
    if (typeof config.jitter === 'number') this.jitter = config.jitter
    if (typeof config.pointRadius === 'number') this.pointRadius = config.pointRadius
    this.fieldSettings = (config.fields as Record<string, unknown> ?? {}) as typeof this.fieldSettings
  }

  getConfiguration() {
    const config: Record<string, unknown> = {}
    if (this.latitudeColumn) config.latitudeColumn = this.latitudeColumn
    if (this.longitudeColumn) config.longitudeColumn = this.longitudeColumn
    if (this.idColumn) config.idColumn = this.idColumn
    if (this.seriesColumn) config.seriesColumn = this.seriesColumn
    if (this.jitter !== DEFAULT_JITTER) config.jitter = this.jitter
    if (this.pointRadius !== DEFAULT_POINT_RADIUS) config.pointRadius = this.pointRadius
    if (Object.keys(this.fieldSettings).length > 0) {
      config.fields = this.fieldSettings
    }
    return config
  }

  get isReady() {
    return !!this.latitudeColumn && !!this.longitudeColumn
  }

  override setData(data: Record<string, unknown>[]): Record<string, unknown>[] {
    if (!data || data.length === 0 || !this.isReady) {
      this.data = []
      this.seriesColors = {}
      return []
    }

    // Assign a stable color per distinct series value (e.g. app version), sorted so the
    // mapping is deterministic across data updates.
    const seriesValues = this.seriesColumn
      ? Array.from(
          new Set(data.map((row) => this.readSeries(row)).filter((s): s is string => s !== undefined)),
        ).sort()
      : []
    const palette = makePalette(Math.max(1, seriesValues.length))
    this.seriesColors = {}
    seriesValues.forEach((series, i) => {
      this.seriesColors[series] = palette[i % palette.length]!
    })

    const points: GeoPoint[] = []
    data.forEach((row, index) => {
      // Skip rows without a usable coordinate (e.g. a heartbeat with no Cloudflare geo).
      // Note `0` is a valid coordinate (equator / prime meridian), so absence is checked
      // explicitly rather than by falsiness — and `Number(null)` is `0`, not `NaN`.
      const latitude = this.toCoordinate(row[this.latitudeColumn])
      const longitude = this.toCoordinate(row[this.longitudeColumn])
      if (latitude === undefined || longitude === undefined) return

      const id = this.idColumn && row[this.idColumn] != null
        ? String(row[this.idColumn])
        : `${index}:${latitude},${longitude}`
      const series = this.readSeries(row)
      const [dx, dy] = this.jitterOffset(id)
      points.push({
        // Keep the source fields available to point tooltips and click handlers. Coordinates and
        // renderer-owned fields below deliberately win over same-named source columns.
        ...row,
        id,
        latitude: latitude + dy,
        longitude: longitude + dx,
        series,
        color: series !== undefined ? this.seriesColors[series] : undefined,
      })
    })

    this.data = [{ points }] as unknown as Record<string, unknown>[]
    return this.data
  }

  getMapData(): GeoPointMapData {
    const wrapped = this.data[0] as { points?: GeoPoint[] } | undefined
    return { points: wrapped?.points ?? [] }
  }

  /** Distinct series values present in the current data, in stable (sorted) order. */
  getSeries(): string[] {
    return Object.keys(this.seriesColors).sort()
  }

  /** Colors a point by its baked series color, falling back to a default. */
  getPointColor(): (d: { color?: string } | null | undefined) => string {
    return (d) => d?.color ?? GEO_POINT_MAP_DEFAULT_POINT_COLOR
  }

  /** Legend categories keyed by series value, matching point colors. Empty when no series column. */
  override getCategories(): Record<string, unknown> {
    const categories: Record<string, unknown> = {}
    for (const series of this.getSeries()) {
      categories[series] = { name: series, color: this.seriesColors[series] }
    }
    return categories
  }

  /** Parses a cell into a finite coordinate, or `undefined` for null/empty/non-numeric values. */
  private toCoordinate(value: unknown): number | undefined {
    if (value === null || value === undefined || value === '') return undefined
    const n = Number(value)
    return Number.isFinite(n) ? n : undefined
  }

  private readSeries(row: Record<string, unknown>): string | undefined {
    if (!this.seriesColumn) return undefined
    const value = row[this.seriesColumn]
    return value == null ? undefined : String(value)
  }

  /** Deterministic `[dx, dy]` offset in degrees for a point id, each in `[-jitter, +jitter]`. */
  private jitterOffset(id: string): [number, number] {
    if (!this.jitter) return [0, 0]
    const dx = (this.hashUnit(id) * 2 - 1) * this.jitter
    const dy = (this.hashUnit(`${id}#y`) * 2 - 1) * this.jitter
    return [dx, dy]
  }

  /** FNV-1a hash of `seed` mapped to `[0, 1)` — stable across renders for the same id. */
  private hashUnit(seed: string): number {
    let h = 2166136261
    for (let i = 0; i < seed.length; i++) {
      h ^= seed.charCodeAt(i)
      h = Math.imul(h, 16777619)
    }
    return ((h >>> 0) % 1_000_000) / 1_000_000
  }
}
