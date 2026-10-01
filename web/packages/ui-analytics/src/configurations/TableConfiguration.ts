import { VisualizationConfiguration } from './VisualizationConfiguration'

export class TableConfiguration extends VisualizationConfiguration {
  public tableColumnKeys: string[] = []

  constructor() {
    super('TABLE')
  }

  loadConfiguration(config: Record<string, unknown>) {
    if (!config) return
    const cols = config.columns
    this.tableColumnKeys = cols ? (Array.isArray(cols) ? cols : [cols as string]) : []
    this.fieldSettings = (config.fields as Record<string, unknown> ?? {}) as typeof this.fieldSettings
  }

  getConfiguration() {
    const config: Record<string, unknown> = {}
    if (this.tableColumnKeys.length > 0) config.columns = this.tableColumnKeys
    if (Object.keys(this.fieldSettings).length > 0) {
      config.fields = this.fieldSettings
    }
    return config
  }

  get isReady() {
    return true
  }

  override getColumns(allColumns: Record<string, unknown>[]): Record<string, unknown>[] {
    if (this.tableColumnKeys.length === 0) return allColumns
    return allColumns.filter(col => this.tableColumnKeys.includes(col.accessorKey as string))
  }
}
