export interface GridItem {
  id: string
  x: number
  y: number
  w: number
  h: number
}

export interface GridConfig {
  columns: number
  cellHeight: number
  gap: number
}

export const DEFAULT_CONFIG: GridConfig = {
  columns: 12,
  cellHeight: 60,
  gap: 8,
}
