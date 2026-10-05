import { VisualizationConfiguration } from './VisualizationConfiguration'

export class NumberConfiguration extends VisualizationConfiguration {
  public valueKey: string | undefined = undefined

  constructor() {
    super('NUMBER')
  }

  loadConfiguration(config: Record<string, unknown>) {
    if (!config) return
    this.valueKey = config.value as string | undefined
    this.fieldSettings = (config.fields as Record<string, unknown> ?? {}) as typeof this.fieldSettings
  }

  getConfiguration() {
    const config: Record<string, unknown> = {}
    if (this.valueKey) config.value = this.valueKey
    if (Object.keys(this.fieldSettings).length > 0) {
      config.fields = this.fieldSettings
    }
    return config
  }

  get isReady() {
    return !!this.valueKey
  }

  override getNumberValue(): string {
    if (this.valueKey) {
      const data = this.getData()
      if (data && data.length > 0) {
        const val = data[0]![this.valueKey]
        const num = Number(val)
        if (!isNaN(num)) {
          const fmt = this.fieldSettings[this.valueKey]?.format
          if (fmt === 'currency') return num.toLocaleString(undefined, { style: 'currency', currency: 'USD' })
          if (fmt === 'percent') return num.toLocaleString(undefined, { style: 'percent' })
          return num.toLocaleString()
        }
        return String(val)
      }
    }
    return '--'
  }
}
