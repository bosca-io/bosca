import { VisualizationConfiguration } from './VisualizationConfiguration'

export interface TopoJsonMapArea {
  id: string
  name: string
  count: number
}

export interface TopoJsonMapData {
  areas: TopoJsonMapArea[]
}

export class TopoJsonMapConfiguration extends VisualizationConfiguration {
  regionColumn: string = ''
  valueColumn: string = ''

  constructor() {
    super('TOPO_JSON_MAP')
  }

  loadConfiguration(config: Record<string, unknown>) {
    if (!config) return
    this.regionColumn = (config.regionColumn as string) || ''
    this.valueColumn = (config.valueColumn as string) || ''
    this.fieldSettings = (config.fields as Record<string, unknown> ?? {}) as typeof this.fieldSettings
  }

  getConfiguration() {
    const config: Record<string, unknown> = {}
    if (this.regionColumn) config.regionColumn = this.regionColumn
    if (this.valueColumn) config.valueColumn = this.valueColumn
    if (Object.keys(this.fieldSettings).length > 0) {
      config.fields = this.fieldSettings
    }
    return config
  }

  override setData(data: Record<string, unknown>[]): Record<string, unknown>[] {
    if (!data || data.length === 0) return []
    this.data = [{
      areas: data.map((row) => ({
        id: row[this.regionColumn],
        name: row[this.regionColumn],
        count: row[this.valueColumn],
      })),
    }] as unknown as Record<string, unknown>[]
    return this.data
  }

  getMapData(): TopoJsonMapData {
    const wrapped = this.data[0] as { areas?: TopoJsonMapArea[] } | undefined
    return { areas: wrapped?.areas ?? [] }
  }

  getAreaColor(): (d: { count?: number } | null | undefined) => string {
    const counts = this.getMapData().areas
      .map((a) => a.count)
      .filter((v): v is number => typeof v === 'number')
    const min = counts.length ? Math.min(...counts) : 0
    const max = counts.length ? Math.max(...counts) : 0
    const range = Math.max(1, max - min)

    return (d) => {
      if (!d || typeof d.count !== 'number') return 'var(--ui-color-primary-200, #b3d4ff)'
      const t = range === 0 ? 0 : (d.count - min) / range
      if (t > 0.8) return 'var(--ui-color-primary-900, #0b2545)'
      if (t > 0.6) return 'var(--ui-color-primary-800, #133a6e)'
      if (t > 0.4) return 'var(--ui-color-primary-700, #1c5097)'
      if (t > 0.2) return 'var(--ui-color-primary-600, #2666bf)'
      return 'var(--ui-color-primary-500, #3079e0)'
    }
  }

  get isReady() {
    return !!this.regionColumn && !!this.valueColumn
  }
}
