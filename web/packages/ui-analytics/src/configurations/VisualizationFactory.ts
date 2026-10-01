import type { VisualizationType } from '../types'
import type { VisualizationConfiguration } from './VisualizationConfiguration'
import { BarLineScatterConfiguration } from './BarLineScatterConfiguration'
import { PieDoughnutConfiguration } from './PieDoughnutConfiguration'
import { NumberConfiguration } from './NumberConfiguration'
import { TableConfiguration } from './TableConfiguration'
import { LabelConfiguration } from './LabelConfiguration'
import { DatePickerConfiguration } from './DatePickerConfiguration'
import { TopoJsonMapConfiguration } from './TopoJsonMapConfiguration'
import { GeoPointMapConfiguration } from './GeoPointMapConfiguration'
import { LiveSessionsMapConfiguration } from './LiveSessionsMapConfiguration'
import { StatConfiguration } from './StatConfiguration'

export function createVisualization(
  type: VisualizationType,
  config?: Record<string, unknown>,
  data?: Record<string, unknown>[],
): VisualizationConfiguration {
  let configuration: VisualizationConfiguration

  switch (type) {
    case 'BAR':
    case 'LINE':
    case 'SCATTER':
      configuration = new BarLineScatterConfiguration(type)
      break
    case 'PIE':
    case 'DOUGHNUT':
      configuration = new PieDoughnutConfiguration(type)
      break
    case 'NUMBER':
      configuration = new NumberConfiguration()
      break
    case 'STAT':
      configuration = new StatConfiguration()
      break
    case 'TABLE':
      configuration = new TableConfiguration()
      break
    case 'LABEL':
      configuration = new LabelConfiguration()
      break
    case 'DATEPICKER':
      configuration = new DatePickerConfiguration()
      break
    case 'TOPO_JSON_MAP':
      configuration = new TopoJsonMapConfiguration()
      break
    case 'GEO_POINT_MAP':
      configuration = new GeoPointMapConfiguration()
      break
    case 'LIVE_SESSIONS_MAP':
      configuration = new LiveSessionsMapConfiguration()
      break
    default:
      configuration = new TableConfiguration()
      break
  }

  if (config) {
    configuration.loadConfiguration(config)
  }
  if (data) {
    configuration.setData(data)
  }
  return configuration
}
