import { VisualizationConfiguration } from './VisualizationConfiguration'
import type { QueryExecutionParameter } from '../types'

export interface DatePickerRange {
  start: Date | null
  end: Date | null
}

export class DatePickerConfiguration extends VisualizationConfiguration {
  startDateParam: string = 'startDate'
  endDateParam: string = 'endDate'

  constructor() {
    super('DATEPICKER')
  }

  loadConfiguration(config: Record<string, unknown>) {
    this.startDateParam = (config.startDateParam as string) || 'startDate'
    this.endDateParam = (config.endDateParam as string) || 'endDate'
  }

  getConfiguration() {
    return {
      startDateParam: this.startDateParam,
      endDateParam: this.endDateParam,
    }
  }

  get isReady() {
    return true
  }

  /**
   * Resolves the picker's start/end dates from the dashboard's execution parameters so the
   * picker reflects the range the queries actually run with. Parameter values arrive either
   * as plain date strings or as the DateParameterValue shape ({ value, now, nowDayOffset })
   * used by dashboard parameter defaults, where `now: true` means today plus a day offset.
   */
  resolveRange(parameters: QueryExecutionParameter[] | undefined): DatePickerRange {
    const find = (name: string) => parameters?.find(p => p.parameter === name)?.value
    return {
      start: resolveDateValue(find(this.startDateParam)),
      end: resolveDateValue(find(this.endDateParam)),
    }
  }
}

function resolveDateValue(value: unknown): Date | null {
  if (value == null) return null
  if (value instanceof Date) {
    return isNaN(value.getTime()) ? null : value
  }
  if (typeof value === 'string') {
    const d = new Date(value)
    return isNaN(d.getTime()) ? null : d
  }
  if (typeof value === 'object') {
    const v = value as { value?: unknown; now?: unknown; nowDayOffset?: unknown }
    if (v.now === true) {
      const d = new Date()
      d.setHours(0, 0, 0, 0)
      const offset = typeof v.nowDayOffset === 'number' ? v.nowDayOffset : 0
      d.setDate(d.getDate() + offset)
      return d
    }
    return resolveDateValue(v.value)
  }
  return null
}
