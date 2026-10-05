import { format } from 'date-fns'
import { VisualizationConfiguration } from './VisualizationConfiguration'
import { makePalette } from './colors'
import type { ValueLabel, VisualizationType } from '../types'

export class BarLineScatterConfiguration extends VisualizationConfiguration {
  public xAxisKey: string | undefined = undefined
  public yAxisKeys: string[] = []
  public customXLabel: string | undefined = undefined
  public customYLabel: string | undefined = undefined

  constructor(type: VisualizationType) {
    super(type)
  }

  loadConfiguration(config: Record<string, unknown>) {
    if (!config) return
    this.xAxisKey = config.x as string | undefined
    this.yAxisKeys = config.y ? (Array.isArray(config.y) ? config.y : [config.y as string]) : []
    this.customXLabel = config.xLabel as string | undefined
    this.customYLabel = config.yLabel as string | undefined
    this.fieldSettings = (config.fields as Record<string, unknown> ?? {}) as typeof this.fieldSettings
  }

  getConfiguration() {
    const config: Record<string, unknown> = {
      x: this.xAxisKey,
      y: this.yAxisKeys,
      xLabel: this.customXLabel,
      yLabel: this.customYLabel,
    }
    if (Object.keys(this.fieldSettings).length > 0) {
      config.fields = this.fieldSettings
    }
    return config
  }

  get isReady() {
    return !!(this.xAxisKey && this.yAxisKeys.length > 0)
  }

  override getCategories() {
    const palette = makePalette(this.yAxisKeys.length)
    return this.yAxisKeys.reduce((acc, key, index) => {
      acc[key] = {
        name: this.fieldSettings[key]?.label || key,
        color: palette[index],
      }
      return acc
    }, {} as Record<string, unknown>)
  }

  override get xLabel() {
    if (this.customXLabel) return this.customXLabel
    if (this.xAxisKey && this.fieldSettings[this.xAxisKey]?.label) {
      return this.fieldSettings[this.xAxisKey]?.label
    }
    return undefined
  }

  override get yLabel() {
    if (this.customYLabel) return this.customYLabel
    if (this.yAxisKeys[0] && this.fieldSettings[this.yAxisKeys[0]]?.label) {
      return this.fieldSettings[this.yAxisKeys[0]]?.label
    }
    return undefined
  }

  override get xAxisFormatter() {
    if (this.xAxisKey && this.fieldSettings[this.xAxisKey]?.type === 'date') {
      return (tick: number) => {
        const label = this.xAxisKey!
        const data = this.getData()?.[tick]?.[label]
        if (data === undefined) return ''
        const fmt = this.fieldSettings[label]?.format || 'dd MMM'
        try {
          if (data instanceof Date) return format(data, fmt)
          return format(new Date(data as string), fmt)
        } catch {
          return ''
        }
      }
    }
    return undefined
  }

  override get yAxisFormatter() {
    if (this.yAxisKeys[0] && this.fieldSettings[this.yAxisKeys[0]]?.type === 'date') {
      return (tick: number) => {
        const label = this.yAxisKeys[0]!
        const data = this.getData()?.[tick]?.[label]
        if (data === undefined) return ''
        const fmt = this.fieldSettings[label]?.format || 'dd MMM'
        try {
          if (data instanceof Date) return format(data, fmt)
          return format(new Date(data as string), fmt)
        } catch {
          return ''
        }
      }
    }
    return undefined
  }

  override getBubbleProps() {
    if (this.type === 'SCATTER' && this.xAxisKey && this.yAxisKeys.length > 0) {
      return {
        xAccessor: (d: Record<string, unknown>) => d[this.xAxisKey!],
        yAccessor: (d: Record<string, unknown>) => d[this.yAxisKeys[0] || ''] || '',
        sizeAccessor: () => 10,
      }
    }
    return {}
  }

  override get valueLabel(): ValueLabel | undefined {
    return {
      label: (d) => {
        if (this.yAxisKeys[0] && this.fieldSettings[this.yAxisKeys[0]]?.type === 'date') {
          try {
            const tick = d[this.yAxisKeys[0]]
            if (tick instanceof Date) return format(tick, 'dd MMM')
            return format(new Date(tick as string), 'dd MMM')
          } catch {
            return ''
          }
        }
        return ''
      },
      labelSpacing: 16,
      labelFontSize: 10,
      color: 'var(--fg-1)',
    }
  }

  override get tooltipTitleFormatter() {
    if (this.xAxisKey && this.fieldSettings[this.xAxisKey]?.type === 'date') {
      return (d: Record<string, unknown>) => {
        const tick = d[this.xAxisKey!]
        const fmt = this.fieldSettings[this.xAxisKey!]?.format || 'dd MMM'
        try {
          if (tick instanceof Date) return format(tick, fmt)
          return format(new Date(tick as string), fmt)
        } catch {
          return String(tick)
        }
      }
    }
    return undefined
  }
}
