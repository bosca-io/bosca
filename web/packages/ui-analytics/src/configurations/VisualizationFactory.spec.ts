import { describe, it, expect } from 'vitest'
import { createVisualization } from './VisualizationFactory'
import { BarLineScatterConfiguration } from './BarLineScatterConfiguration'
import { PieDoughnutConfiguration } from './PieDoughnutConfiguration'
import { NumberConfiguration } from './NumberConfiguration'
import { StatConfiguration } from './StatConfiguration'
import { TableConfiguration } from './TableConfiguration'
import { LabelConfiguration } from './LabelConfiguration'
import { DatePickerConfiguration } from './DatePickerConfiguration'
import { TopoJsonMapConfiguration } from './TopoJsonMapConfiguration'
import { GeoPointMapConfiguration } from './GeoPointMapConfiguration'
import { LiveSessionsMapConfiguration } from './LiveSessionsMapConfiguration'

describe('createVisualization', () => {
  it('creates BarLineScatterConfiguration for BAR', () => {
    expect(createVisualization('BAR')).toBeInstanceOf(BarLineScatterConfiguration)
  })

  it('creates BarLineScatterConfiguration for LINE', () => {
    expect(createVisualization('LINE')).toBeInstanceOf(BarLineScatterConfiguration)
  })

  it('creates BarLineScatterConfiguration for SCATTER', () => {
    expect(createVisualization('SCATTER')).toBeInstanceOf(BarLineScatterConfiguration)
  })

  it('creates PieDoughnutConfiguration for PIE', () => {
    expect(createVisualization('PIE')).toBeInstanceOf(PieDoughnutConfiguration)
  })

  it('creates PieDoughnutConfiguration for DOUGHNUT', () => {
    expect(createVisualization('DOUGHNUT')).toBeInstanceOf(PieDoughnutConfiguration)
  })

  it('creates NumberConfiguration for NUMBER', () => {
    expect(createVisualization('NUMBER')).toBeInstanceOf(NumberConfiguration)
  })

  it('creates StatConfiguration for STAT', () => {
    expect(createVisualization('STAT')).toBeInstanceOf(StatConfiguration)
  })

  it('creates TableConfiguration for TABLE', () => {
    expect(createVisualization('TABLE')).toBeInstanceOf(TableConfiguration)
  })

  it('creates LabelConfiguration for LABEL', () => {
    expect(createVisualization('LABEL')).toBeInstanceOf(LabelConfiguration)
  })

  it('creates DatePickerConfiguration for DATEPICKER', () => {
    expect(createVisualization('DATEPICKER')).toBeInstanceOf(DatePickerConfiguration)
  })

  it('creates TopoJsonMapConfiguration for TOPO_JSON_MAP', () => {
    expect(createVisualization('TOPO_JSON_MAP')).toBeInstanceOf(TopoJsonMapConfiguration)
  })

  it('creates GeoPointMapConfiguration for GEO_POINT_MAP', () => {
    expect(createVisualization('GEO_POINT_MAP')).toBeInstanceOf(GeoPointMapConfiguration)
  })

  it('creates LiveSessionsMapConfiguration for LIVE_SESSIONS_MAP', () => {
    expect(createVisualization('LIVE_SESSIONS_MAP')).toBeInstanceOf(LiveSessionsMapConfiguration)
  })

  it('creates a LIVE_SESSIONS_MAP that is also a GeoPointMapConfiguration', () => {
    expect(createVisualization('LIVE_SESSIONS_MAP')).toBeInstanceOf(GeoPointMapConfiguration)
  })

  it('defaults to TableConfiguration for unknown type', () => {
    expect(createVisualization('UNKNOWN' as any)).toBeInstanceOf(TableConfiguration)
  })

  it('loads config when provided', () => {
    const viz = createVisualization('LABEL', { label: 'Hi' })
    expect((viz as LabelConfiguration).label).toBe('Hi')
  })

  it('loads data when provided', () => {
    const viz = createVisualization('NUMBER', { value: 'x' }, [{ x: 42 }])
    expect(viz.getData()).toHaveLength(1)
  })

  it('works with no config or data', () => {
    const viz = createVisualization('TABLE')
    expect(viz.getData()).toEqual([])
  })
})
