export type VisualizationType =
  | 'BAR'
  | 'LINE'
  | 'SCATTER'
  | 'PIE'
  | 'DOUGHNUT'
  | 'NUMBER'
  | 'STAT'
  | 'TABLE'
  | 'LABEL'
  | 'DATEPICKER'
  | 'TOPO_JSON_MAP'
  | 'GEO_POINT_MAP'
  | 'LIVE_SESSIONS_MAP'

export interface FieldSetting {
  label?: string
  type?: 'auto' | 'string' | 'number' | 'date'
  format?: string
}

export interface ValueLabel {
  label: (d: Record<string, unknown>) => string
  labelSpacing: number
  labelFontSize: number
  color: string
}

export interface VisualizationDefinition {
  id: string
  name: string
  type: VisualizationType
  queryId: string | null
  configuration: Record<string, unknown> | null
}

export interface QueryExecutionParameter {
  parameter: string
  value: unknown
}
