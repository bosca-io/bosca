import { describe, it, expect, vi } from 'vitest'
import { defineComponent } from 'vue'
import { shallowMount } from '../test-helpers'
import AnalyticsVisualization from './AnalyticsVisualization.vue'
import DateRangePicker from './DateRangePicker.vue'

const chartStub = defineComponent({ template: '<div />' })

const stubs = {
  AreaChart: chartStub,
  BarChart: chartStub,
  LineChart: chartStub,
  DonutChart: chartStub,
  BubbleChart: chartStub,
  TopoJSONMap: chartStub,
}

function mountViz(props: Record<string, unknown>) {
  return shallowMount(AnalyticsVisualization, {
    props: { name: 'Test', ...props },
    global: { stubs },
  })
}

describe('AnalyticsVisualization', () => {
  describe('LABEL type', () => {
    it('renders label text', () => {
      const wrapper = mountViz({
        type: 'LABEL',
        configuration: { label: 'Hello', fontSize: '20px', fontWeight: '700', alignment: 'center' },
      })
      expect(wrapper.find('.viz-label').text()).toBe('Hello')
    })

    it('does not render header for labels', () => {
      const wrapper = mountViz({ type: 'LABEL', configuration: { label: 'Hi' } })
      expect(wrapper.find('.viz-header').exists()).toBe(false)
    })
  })

  describe('NUMBER type', () => {
    it('renders number value', () => {
      const wrapper = mountViz({
        type: 'NUMBER',
        configuration: { value: 'count' },
        data: [{ count: 42 }],
      })
      expect(wrapper.find('.viz-number').text()).toBe('42')
    })

    it('renders -- when no data', () => {
      const wrapper = mountViz({ type: 'NUMBER', configuration: { value: 'count' } })
      expect(wrapper.find('.viz-number').text()).toBe('--')
    })
  })

  describe('loading state', () => {
    it('shows spinner when loading', () => {
      const wrapper = mountViz({ type: 'BAR', loading: true })
      expect(wrapper.find('.viz-spinner').exists()).toBe(true)
    })
  })

  describe('error state', () => {
    it('shows error message', () => {
      const wrapper = mountViz({ type: 'BAR', error: 'Failed' })
      expect(wrapper.find('.viz-error').text()).toBe('Failed')
    })
  })

  describe('BAR type', () => {
    it('shows configure message when not ready', () => {
      const wrapper = mountViz({ type: 'BAR' })
      expect(wrapper.text()).toContain('Configure X and Y axes')
    })

    it('renders BarChart when ready with data', () => {
      const wrapper = mountViz({
        type: 'BAR',
        configuration: { x: 'date', y: ['count'] },
        data: [{ date: '2024-01-01', count: 10 }],
      })
      expect(wrapper.findComponent(chartStub).exists()).toBe(true)
    })
  })

  describe('LINE type', () => {
    it('shows configure message when not ready', () => {
      const wrapper = mountViz({ type: 'LINE' })
      expect(wrapper.text()).toContain('Configure X and Y axes')
    })

    it('renders LineChart when ready with data', () => {
      const wrapper = mountViz({
        type: 'LINE',
        configuration: { x: 'date', y: ['count'] },
        data: [{ date: '2024-01-01', count: 10 }],
      })
      expect(wrapper.findComponent(chartStub).exists()).toBe(true)
    })
  })

  describe('PIE type', () => {
    it('shows configure message when not ready', () => {
      const wrapper = mountViz({ type: 'PIE' })
      expect(wrapper.text()).toContain('Configure label and value fields')
    })

    it('renders DonutChart when ready with data', () => {
      const wrapper = mountViz({
        type: 'PIE',
        configuration: { label: 'name', value: 'count' },
        data: [{ name: 'A', count: 10 }],
      })
      expect(wrapper.findComponent(chartStub).exists()).toBe(true)
    })
  })

  describe('DOUGHNUT type', () => {
    it('shows configure message when not ready', () => {
      const wrapper = mountViz({ type: 'DOUGHNUT' })
      expect(wrapper.text()).toContain('Configure label and value fields')
    })
  })

  describe('SCATTER type', () => {
    it('renders BubbleChart when ready with data', () => {
      const wrapper = mountViz({
        type: 'SCATTER',
        configuration: { x: 'x', y: ['y'] },
        data: [{ x: 1, y: 2 }],
      })
      expect(wrapper.findComponent(chartStub).exists()).toBe(true)
    })
  })

  describe('TABLE type', () => {
    it('renders table with data', () => {
      const wrapper = mountViz({
        type: 'TABLE',
        data: [{ name: 'Alice', email: 'a@b.com' }],
      })
      expect(wrapper.find('.viz-table').exists()).toBe(true)
      expect(wrapper.findAll('.viz-table th')).toHaveLength(2)
      expect(wrapper.findAll('.viz-table td')).toHaveLength(2)
    })

    it('renders empty state with no data', () => {
      const wrapper = mountViz({ type: 'TABLE' })
      expect(wrapper.find('.viz-table').exists()).toBe(false)
      expect(wrapper.text()).toContain('No data to display')
    })

    it('formats date cell values', () => {
      const wrapper = mountViz({
        type: 'TABLE',
        configuration: { fields: { ts: { type: 'date' } } },
        data: [{ ts: '2024-01-15' }],
      })
      const cell = wrapper.find('.viz-table td')
      expect(cell.exists()).toBe(true)
    })

    it('formats currency cell values', () => {
      const wrapper = mountViz({
        type: 'TABLE',
        configuration: { fields: { amt: { type: 'number', format: 'currency' } } },
        data: [{ amt: 1234 }],
      })
      const cell = wrapper.find('.viz-table td')
      expect(cell.text()).toContain('1,234')
    })

    it('formats null cell as --', () => {
      const wrapper = mountViz({
        type: 'TABLE',
        data: [{ val: null }],
      })
      expect(wrapper.find('.viz-table td').text()).toBe('--')
    })
  })

  describe('STAT type', () => {
    it('shows configure message when not ready', () => {
      const wrapper = mountViz({ type: 'STAT' })
      expect(wrapper.text()).toContain('Configure value field')
    })

    it('renders stat value when ready', () => {
      const wrapper = mountViz({
        type: 'STAT',
        configuration: { valueKey: 'v', labelKey: 'l', sparklineType: 'area' },
        data: [{ v: 42, l: 'a' }],
      })
      expect(wrapper.find('.viz-stat__value').text()).toBe('42')
    })

    it('renders bar sparkline', () => {
      const wrapper = mountViz({
        type: 'STAT',
        configuration: { valueKey: 'v', sparklineType: 'bar' },
        data: [{ v: 10 }, { v: 20 }],
      })
      expect(wrapper.find('.viz-stat__sparkline').exists()).toBe(true)
    })

    it('renders line sparkline', () => {
      const wrapper = mountViz({
        type: 'STAT',
        configuration: { valueKey: 'v', labelKey: 'l', sparklineType: 'line' },
        data: [{ v: 10, l: 'a' }, { v: 20, l: 'b' }],
      })
      expect(wrapper.find('.viz-stat__sparkline').exists()).toBe(true)
    })
  })

  describe('DATEPICKER type', () => {
    it('renders date range picker', () => {
      const wrapper = mountViz({ type: 'DATEPICKER' })
      expect(wrapper.find('.viz-datepicker').exists()).toBe(true)
    })

    it('defaults to the last 7 days without parameters', () => {
      const wrapper = mountViz({ type: 'DATEPICKER' })
      const range = wrapper.findComponent(DateRangePicker).props('modelValue')
      const today = new Date()
      today.setHours(0, 0, 0, 0)
      expect(range.end.getTime()).toBe(today.getTime())
      expect(range.start.getTime()).toBe(today.getTime() - 7 * 24 * 60 * 60 * 1000)
    })

    it('binds the initial range to dashboard start/end parameters', () => {
      const wrapper = mountViz({
        type: 'DATEPICKER',
        parameters: [
          { parameter: 'startDate', value: '2026-01-01T00:00:00.000Z' },
          { parameter: 'endDate', value: { value: '2026-01-31T00:00:00.000Z', now: false } },
        ],
      })
      const range = wrapper.findComponent(DateRangePicker).props('modelValue')
      expect(range.start.toISOString()).toBe('2026-01-01T00:00:00.000Z')
      expect(range.end.toISOString()).toBe('2026-01-31T00:00:00.000Z')
    })

    it('binds using configured parameter names', () => {
      const wrapper = mountViz({
        type: 'DATEPICKER',
        configuration: { startDateParam: 'from', endDateParam: 'to' },
        parameters: [
          { parameter: 'from', value: '2026-02-01T00:00:00.000Z' },
          { parameter: 'to', value: '2026-02-15T00:00:00.000Z' },
        ],
      })
      const range = wrapper.findComponent(DateRangePicker).props('modelValue')
      expect(range.start.toISOString()).toBe('2026-02-01T00:00:00.000Z')
      expect(range.end.toISOString()).toBe('2026-02-15T00:00:00.000Z')
    })

    it('updates the range when parameters change', async () => {
      const wrapper = mountViz({ type: 'DATEPICKER' })
      await wrapper.setProps({
        parameters: [
          { parameter: 'startDate', value: '2026-03-01T00:00:00.000Z' },
          { parameter: 'endDate', value: '2026-03-10T00:00:00.000Z' },
        ],
      })
      const range = wrapper.findComponent(DateRangePicker).props('modelValue')
      expect(range.start.toISOString()).toBe('2026-03-01T00:00:00.000Z')
      expect(range.end.toISOString()).toBe('2026-03-10T00:00:00.000Z')
    })

    it('emits paramChange when the user picks a range', async () => {
      const wrapper = mountViz({ type: 'DATEPICKER' })
      wrapper.findComponent(DateRangePicker).vm.$emit('update:modelValue', {
        start: new Date('2026-04-01T00:00:00.000Z'),
        end: new Date('2026-04-30T00:00:00.000Z'),
      })
      const emitted = wrapper.emitted('paramChange')
      expect(emitted).toHaveLength(1)
      expect(emitted![0]![0]).toEqual([
        { parameter: 'startDate', value: { value: '2026-04-01T00:00:00.000Z', now: false } },
        { parameter: 'endDate', value: { value: '2026-04-30T00:00:00.000Z', now: false } },
      ])
    })
  })

  describe('TOPO_JSON_MAP type', () => {
    it('shows configure message when not ready', () => {
      const wrapper = mountViz({ type: 'TOPO_JSON_MAP' })
      expect(wrapper.text()).toContain('Configure region and value columns')
    })

    it('renders TopoJSONMap when ready with data', () => {
      const wrapper = mountViz({
        type: 'TOPO_JSON_MAP',
        configuration: { regionColumn: 'country', valueColumn: 'visits' },
        data: [
          { country: 'United States', visits: 120 },
          { country: 'France', visits: 80 },
        ],
      })
      expect(wrapper.findComponent(chartStub).exists()).toBe(true)
      expect(wrapper.text()).not.toContain('Unsupported')
      expect(wrapper.text()).not.toContain('Configure region and value columns')
    })
  })

  describe('GEO_POINT_MAP type', () => {
    it('shows configure message when not ready', () => {
      const wrapper = mountViz({ type: 'GEO_POINT_MAP' })
      expect(wrapper.text()).toContain('Configure latitude and longitude columns')
    })

    it('renders the map when ready with data', () => {
      const wrapper = mountViz({
        type: 'GEO_POINT_MAP',
        configuration: { latitudeColumn: 'lat', longitudeColumn: 'lon', idColumn: 'sessionId', seriesColumn: 'appVersion' },
        data: [
          { sessionId: 's1', lat: 40.7, lon: -74, appVersion: '1.0.0' },
          { sessionId: 's2', lat: 48.8, lon: 2.3, appVersion: '1.1.0' },
        ],
      })
      expect(wrapper.findComponent(chartStub).exists()).toBe(true)
      expect(wrapper.text()).not.toContain('Unsupported')
      expect(wrapper.text()).not.toContain('Configure latitude and longitude columns')
    })
  })

  describe('LIVE_SESSIONS_MAP type', () => {
    it('renders the map with no app configured (all applications)', () => {
      const wrapper = mountViz({
        type: 'LIVE_SESSIONS_MAP',
        data: [{ sessionId: 's1', lat: 40.7, lon: -74, appId: 'app-1', appVersion: '1.0.0' }],
      })
      expect(wrapper.findComponent(chartStub).exists()).toBe(true)
      expect(wrapper.text()).not.toContain('Unsupported')
    })

    it('renders the map when configured with an app', () => {
      const wrapper = mountViz({
        type: 'LIVE_SESSIONS_MAP',
        configuration: { appId: 'app-1' },
        data: [{ sessionId: 's1', lat: 40.7, lon: -74, appVersion: '1.0.0' }],
      })
      expect(wrapper.findComponent(chartStub).exists()).toBe(true)
      expect(wrapper.text()).not.toContain('Unsupported')
      expect(wrapper.text()).not.toContain('Select an application to stream')
    })

    it('forwards the mapped point to a custom tooltip and emits point clicks', async () => {
      const clicked = vi.fn()
      const mapStub = defineComponent({
        props: ['data'],
        emits: ['pointClick'],
        template: `
          <div>
            <slot name="tooltip" :values="{ geometry: { type: 'Point', coordinates: [] }, properties: data.points[0] }" />
            <button
              class="map-point"
              @click="$emit('pointClick', { geometry: { type: 'Point', coordinates: [] }, properties: data.points[0] }, $event)"
            />
          </div>
        `,
      })
      const wrapper = shallowMount(AnalyticsVisualization, {
        props: {
          name: 'Live Sessions',
          type: 'LIVE_SESSIONS_MAP',
          data: [{ sessionId: 's1', lat: 40.7, lon: -74, appId: 'studio', appVersion: '2.4.1' }],
          onPointClick: clicked,
        },
        slots: {
          pointTooltip: '<template #pointTooltip="{ point }"><span class="point-tooltip">{{ point.appVersion }}</span></template>',
        },
        global: { stubs: { ...stubs, TopoJSONMap: mapStub } },
      })

      expect(wrapper.get('.point-tooltip').text()).toBe('2.4.1')
      await wrapper.get('.map-point').trigger('click')
      expect(clicked).toHaveBeenCalledOnce()
      expect(clicked.mock.calls[0]![0]).toMatchObject({ sessionId: 's1', appId: 'studio', appVersion: '2.4.1' })
    })

    it('keeps direct source points working for compatible map renderers', async () => {
      const clicked = vi.fn()
      const mapStub = defineComponent({
        props: ['data'],
        emits: ['pointClick'],
        template: `
          <div>
            <slot name="tooltip" :values="data.points[0]" />
            <button class="map-point" @click="$emit('pointClick', data.points[0], $event)" />
          </div>
        `,
      })
      const wrapper = shallowMount(AnalyticsVisualization, {
        props: {
          name: 'Live Sessions',
          type: 'LIVE_SESSIONS_MAP',
          data: [{ sessionId: 's1', lat: 40.7, lon: -74, appId: 'studio', appVersion: '2.4.1' }],
          onPointClick: clicked,
        },
        slots: {
          pointTooltip: '<template #pointTooltip="{ point }"><span class="point-tooltip">{{ point.appVersion }}</span></template>',
        },
        global: { stubs: { ...stubs, TopoJSONMap: mapStub } },
      })

      expect(wrapper.get('.point-tooltip').text()).toBe('2.4.1')
      await wrapper.get('.map-point').trigger('click')
      expect(clicked.mock.calls[0]![0]).toMatchObject({ sessionId: 's1', appId: 'studio', appVersion: '2.4.1' })
    })
  })

  describe('header', () => {
    it('renders name in header', () => {
      const wrapper = mountViz({ type: 'NUMBER', configuration: { value: 'v' } })
      expect(wrapper.find('.viz-title').text()).toBe('Test')
    })

    it('hides header when frameless', () => {
      const wrapper = mountViz({ type: 'NUMBER', configuration: { value: 'v' }, frameless: true })
      expect(wrapper.find('.viz-header').exists()).toBe(false)
    })
  })
})
