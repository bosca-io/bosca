import { VisualizationConfiguration } from './VisualizationConfiguration'

export type SparklineType = 'area' | 'bar' | 'line'
export type StatAggregation = 'latest' | 'sum' | 'avg' | 'min' | 'max'

export class StatConfiguration extends VisualizationConfiguration {
  public valueKey: string | undefined = undefined
  public labelKey: string | undefined = undefined
  public sparklineType: SparklineType = 'area'
  public aggregation: StatAggregation = 'latest'
  public prefix: string = ''
  public suffix: string = ''
  public decimals: number = 0

  constructor() {
    super('STAT')
  }

  loadConfiguration(config: Record<string, unknown>) {
    if (!config) return
    this.valueKey = config.valueKey as string | undefined
    this.labelKey = config.labelKey as string | undefined
    this.sparklineType = (config.sparklineType as SparklineType) || 'area'
    this.aggregation = (config.aggregation as StatAggregation) || 'latest'
    this.prefix = (config.prefix as string) || ''
    this.suffix = (config.suffix as string) || ''
    this.decimals = (config.decimals as number) ?? 0
    this.fieldSettings = (config.fields as Record<string, unknown> ?? {}) as typeof this.fieldSettings
  }

  getConfiguration() {
    return {
      valueKey: this.valueKey,
      labelKey: this.labelKey,
      sparklineType: this.sparklineType,
      aggregation: this.aggregation,
      prefix: this.prefix,
      suffix: this.suffix,
      decimals: this.decimals,
    }
  }

  get isReady() {
    return !!(this.valueKey)
  }

  getStatValue(): string {
    if (!this.valueKey || this.data.length === 0) return '--'
    const values = this.data.map(r => Number(r[this.valueKey!])).filter(n => !isNaN(n))
    if (values.length === 0) return '--'

    let result: number
    switch (this.aggregation) {
      case 'sum':
        result = values.reduce((a, b) => a + b, 0)
        break
      case 'avg':
        result = values.reduce((a, b) => a + b, 0) / values.length
        break
      case 'min':
        result = Math.min(...values)
        break
      case 'max':
        result = Math.max(...values)
        break
      case 'latest':
      default:
        result = values[values.length - 1]!
        break
    }

    const formatted = this.decimals > 0 ? result.toFixed(this.decimals) : Math.round(result).toLocaleString()
    return `${this.prefix}${formatted}${this.suffix}`
  }

  getSparklineData(): Record<string, unknown>[] {
    if (!this.valueKey) return []
    return this.data
  }
}
