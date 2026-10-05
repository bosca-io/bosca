import type { FieldSetting, ValueLabel, VisualizationType } from '../types'

export abstract class VisualizationConfiguration {
  fieldSettings: Record<string, FieldSetting> = {}
  protected data: Record<string, unknown>[] = []

  protected constructor(public readonly type: VisualizationType) {}

  abstract loadConfiguration(config: Record<string, unknown>): void
  abstract getConfiguration(): Record<string, unknown>
  abstract get isReady(): boolean

  getCategories(): Record<string, unknown> {
    return {}
  }

  getDonutData(): number[] {
    return []
  }

  getBubbleProps(): Record<string, unknown> {
    return {}
  }

  getNumberValue(): string {
    return '--'
  }

  getColumns(allColumns: Record<string, unknown>[]): Record<string, unknown>[] {
    return allColumns
  }

  getData(): Record<string, unknown>[] {
    return this.data
  }

  setData(data: Record<string, unknown>[]): Record<string, unknown>[] {
    if (!data || data.length === 0) return []
    this.data = data.map((row) => {
      const newRow: Record<string, unknown> = { ...row }
      for (const [key, settings] of Object.entries(this.fieldSettings)) {
        if (newRow[key] !== undefined) {
          if (settings.type === 'number') {
            const num = Number(newRow[key])
            newRow[key] = isNaN(num) ? newRow[key] : num
          } else if (settings.type === 'date') {
            const val = newRow[key]
            if (val === null || val === undefined || val === '') {
              newRow[key] = null
            } else {
              const isNumericString = typeof val === 'string' && !isNaN(Number(val)) && val.trim() !== ''
              const isNumber = typeof val === 'number'
              if (isNumber || isNumericString) {
                let numVal = Number(val)
                if (numVal < 10_000_000_000) numVal *= 1000
                newRow[key] = new Date(numVal)
              } else {
                newRow[key] = new Date(val as string)
              }
            }
          } else if (settings.type === 'string') {
            newRow[key] = String(newRow[key])
          }
        }
      }
      return newRow
    })
    return this.data
  }

  get xLabel(): string | undefined {
    return undefined
  }

  get yLabel(): string | undefined {
    return undefined
  }

  get xAxisFormatter(): ((tick: number) => string) | undefined {
    return undefined
  }

  get yAxisFormatter(): ((tick: number) => string) | undefined {
    return undefined
  }

  get tooltipTitleFormatter(): ((data: Record<string, unknown>) => string) | undefined {
    return undefined
  }

  get valueLabel(): ValueLabel | undefined {
    return undefined
  }
}
