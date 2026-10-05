import { format } from 'date-fns'
import { VisualizationConfiguration } from './VisualizationConfiguration'
import { makePalette } from './colors'
import type { VisualizationType } from '../types'

export class PieDoughnutConfiguration extends VisualizationConfiguration {
  public labelKey: string | undefined = undefined
  public valueKey: string | undefined = undefined

  constructor(type: VisualizationType) {
    super(type)
  }

  loadConfiguration(config: Record<string, unknown>) {
    if (!config) return
    this.labelKey = config.label as string | undefined
    this.valueKey = config.value as string | undefined
    this.fieldSettings = (config.fields as Record<string, unknown> ?? {}) as typeof this.fieldSettings
  }

  getConfiguration() {
    const config: Record<string, unknown> = {}
    if (this.labelKey) config.label = this.labelKey
    if (this.valueKey) config.value = this.valueKey
    if (Object.keys(this.fieldSettings).length > 0) {
      config.fields = this.fieldSettings
    }
    return config
  }

  get isReady() {
    return !!(this.labelKey && this.valueKey)
  }

  override getCategories() {
    if (!this.labelKey || !this.valueKey) return {}
    const palette = makePalette(this.getData().length)
    return this.getData().reduce((acc, row, index) => {
      const label = row[this.labelKey!]
      let displayLabel: string
      if (this.labelKey && this.fieldSettings[this.labelKey]?.type === 'date') {
        const fmt = this.fieldSettings[this.labelKey]?.format || 'dd MMM'
        try {
          displayLabel = label instanceof Date ? format(label, fmt) : format(new Date(label as string), fmt)
        } catch {
          displayLabel = String(label)
        }
      } else {
        displayLabel = String(label)
      }
      acc[index] = { name: displayLabel, color: palette[index] }
      return acc
    }, {} as Record<string, unknown>)
  }

  override getDonutData(): number[] {
    if (this.valueKey) {
      return this.getData().map(row => Number(row[this.valueKey!] || 0))
    }
    return []
  }

  override get tooltipTitleFormatter() {
    if (this.labelKey && this.fieldSettings[this.labelKey]?.type === 'date') {
      return (data: Record<string, unknown>) => {
        const label = data.label
        const fmt = this.fieldSettings[this.labelKey!]?.format || 'dd MMM'
        try {
          if (label instanceof Date) return format(label, fmt)
          return format(new Date(label as string), fmt)
        } catch {
          return String(label)
        }
      }
    }
    return undefined
  }
}
