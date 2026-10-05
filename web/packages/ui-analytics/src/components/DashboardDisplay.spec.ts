import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, nextTick } from 'vue'
import { shallowMount } from '../test-helpers'
import DashboardDisplay from './DashboardDisplay.vue'

const AnalyticsVisualizationStub = defineComponent({
  props: ['name', 'type', 'configuration', 'data', 'loading', 'error', 'frameless', 'parameters'],
  template: '<div class="viz-stub" />',
})

function mockFetch(responses: Record<string, unknown>[]) {
  let callIndex = 0
  return vi.fn().mockImplementation(() =>
    Promise.resolve({
      json: () => Promise.resolve(responses[callIndex++] ?? responses[responses.length - 1]),
    }),
  )
}

function makeDashboardResponse(visualizations: any[] = [], parameters: any[] = []) {
  return {
    data: {
      analytics: {
        dashboards: {
          byKey: {
            id: '1',
            key: 'test',
            name: 'Test Dashboard',
            description: '',
            parameters,
            visualizations,
          },
        },
      },
    },
  }
}

function makeQueryResponse(records: Record<string, unknown>[] = []) {
  return {
    data: {
      analytics: {
        queries: {
          execute: { records },
        },
      },
    },
  }
}

function makeQueryParametersResponse(parameters: string[]) {
  return {
    data: {
      analytics: {
        queries: {
          queryById: {
            parameters: parameters.map(parameter => ({ parameter })),
          },
        },
      },
    },
  }
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('DashboardDisplay', () => {
  it('shows loading state initially', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    expect(wrapper.find('.dashboard-display__state').text()).toBe('Loading…')
    vi.unstubAllGlobals()
  })

  it('shows error when fetch fails', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('Network error'))))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(wrapper.find('.dashboard-display__state--error').exists()).toBe(true)
    })
    expect(wrapper.text()).toContain('Network error')
    vi.unstubAllGlobals()
  })

  it('shows error when dashboard not found', async () => {
    vi.stubGlobal('fetch', mockFetch([{ data: { analytics: { dashboards: { byKey: null } } } }]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'missing' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(wrapper.find('.dashboard-display__state--error').exists()).toBe(true)
    })
    expect(wrapper.text()).toContain('not found')
    vi.unstubAllGlobals()
  })

  it('shows empty message when no visualizations', async () => {
    vi.stubGlobal('fetch', mockFetch([makeDashboardResponse()]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('No visualizations')
    })
    vi.unstubAllGlobals()
  })

  it('renders visualizations from dashboard', async () => {
    const vizs = [{
      id: 'v1',
      configuration: { x: 0, y: 0, w: 6, h: 3 },
      visualization: { id: '1', key: 'v1', name: 'Chart', type: 'BAR', queryId: null, configuration: {} },
    }]
    vi.stubGlobal('fetch', mockFetch([makeDashboardResponse(vizs)]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(wrapper.findAll('.dashboard-display__item')).toHaveLength(1)
    })
    vi.unstubAllGlobals()
  })

  it('loads query data for visualizations with queryId', async () => {
    const vizs = [{
      id: 'v1',
      configuration: { x: 0, y: 0, w: 6, h: 3 },
      visualization: { id: '1', key: 'v1', name: 'Chart', type: 'BAR', queryId: 'q1', configuration: { x: 'date', y: ['count'] } },
    }]
    vi.stubGlobal('fetch', mockFetch([
      makeDashboardResponse(vizs),
      makeQueryParametersResponse([]),
      makeQueryResponse([{ date: '2024-01-01', count: 10 }]),
    ]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(wrapper.findAll('.dashboard-display__item')).toHaveLength(1)
    })
    vi.unstubAllGlobals()
  })

  it('handles query execution errors', async () => {
    const vizs = [{
      id: 'v1',
      configuration: {},
      visualization: { id: '1', key: 'v1', name: 'Chart', type: 'BAR', queryId: 'q1', configuration: {} },
    }]
    vi.stubGlobal('fetch', mockFetch([
      makeDashboardResponse(vizs),
      makeQueryParametersResponse([]),
      { errors: [{ message: 'Query failed' }] },
    ]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(wrapper.findAll('.dashboard-display__item')).toHaveLength(1)
    })
    vi.unstubAllGlobals()
  })

  it('uses custom graphqlUrl', async () => {
    const fetchMock = mockFetch([makeDashboardResponse()])
    vi.stubGlobal('fetch', fetchMock)
    shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test', graphqlUrl: '/custom-gql' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('/custom-gql', expect.any(Object))
    })
    vi.unstubAllGlobals()
  })

  it('uses getHeaders when provided', async () => {
    const fetchMock = mockFetch([makeDashboardResponse()])
    vi.stubGlobal('fetch', fetchMock)
    const getHeaders = vi.fn().mockResolvedValue({ Authorization: 'Bearer token' })
    shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test', getHeaders },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(getHeaders).toHaveBeenCalled()
    })
    vi.unstubAllGlobals()
  })

  it('sets CSS grid variables', async () => {
    const vizs = [{
      id: 'v1',
      configuration: { x: 0, y: 0, w: 6, h: 3 },
      visualization: { id: '1', key: 'v1', name: 'Chart', type: 'BAR', queryId: null, configuration: {} },
    }]
    vi.stubGlobal('fetch', mockFetch([makeDashboardResponse(vizs)]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test', columns: 24, cellHeight: 30, gap: 2 },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      const style = wrapper.find('.dashboard-display').attributes('style')!
      expect(style).toContain('--columns: 24')
      expect(style).toContain('--cell-height: 30px')
      expect(style).toContain('--grid-gap: 2px')
    })
    vi.unstubAllGlobals()
  })

  it('applies no-border class when showBorder is false', async () => {
    const vizs = [{
      id: 'v1',
      configuration: { x: 0, y: 0, w: 6, h: 3, showBorder: false },
      visualization: { id: '1', key: 'v1', name: 'Chart', type: 'BAR', queryId: null, configuration: {} },
    }]
    vi.stubGlobal('fetch', mockFetch([makeDashboardResponse(vizs)]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(wrapper.find('.dashboard-display__item--no-border').exists()).toBe(true)
    })
    vi.unstubAllGlobals()
  })

  it('applies no-bg class when showBackground is false', async () => {
    const vizs = [{
      id: 'v1',
      configuration: { x: 0, y: 0, w: 6, h: 3, showBackground: false },
      visualization: { id: '1', key: 'v1', name: 'Chart', type: 'BAR', queryId: null, configuration: {} },
    }]
    vi.stubGlobal('fetch', mockFetch([makeDashboardResponse(vizs)]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(wrapper.find('.dashboard-display__item--no-bg').exists()).toBe(true)
    })
    vi.unstubAllGlobals()
  })

  it('hides title when showTitle is false', async () => {
    const vizs = [{
      id: 'v1',
      configuration: { x: 0, y: 0, w: 6, h: 3, showTitle: false },
      visualization: { id: '1', key: 'v1', name: 'Chart', type: 'BAR', queryId: null, configuration: {} },
    }]
    vi.stubGlobal('fetch', mockFetch([makeDashboardResponse(vizs)]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(wrapper.find('.dashboard-display__title').exists()).toBe(false)
    })
    vi.unstubAllGlobals()
  })

  it('uses titleOverride when set', async () => {
    const vizs = [{
      id: 'v1',
      configuration: { x: 0, y: 0, w: 6, h: 3, titleOverride: 'Custom Title' },
      visualization: { id: '1', key: 'v1', name: 'Chart', type: 'BAR', queryId: null, configuration: {} },
    }]
    vi.stubGlobal('fetch', mockFetch([makeDashboardResponse(vizs)]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(wrapper.find('.dashboard-display__title').text()).toBe('Custom Title')
    })
    vi.unstubAllGlobals()
  })

  it('reloads when dashboardKey changes', async () => {
    const fetchMock = mockFetch([makeDashboardResponse(), makeDashboardResponse()])
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test1' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    await wrapper.setProps({ dashboardKey: 'test2' })
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    vi.unstubAllGlobals()
  })

  it('sends parameters with query execution', async () => {
    const vizs = [{
      id: 'v1',
      configuration: {},
      visualization: { id: '1', key: 'v1', name: 'Chart', type: 'BAR', queryId: 'q1', configuration: {} },
    }]
    const params = [{ parameter: 'startDate', name: 'Start', defaultValue: '2024-01-01' }]
    const fetchMock = mockFetch([
      makeDashboardResponse(vizs, params),
      makeQueryParametersResponse(['startDate']),
      makeQueryResponse([]),
    ])
    vi.stubGlobal('fetch', fetchMock)
    shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(3)
      const executionCallBody = JSON.parse((fetchMock.mock.calls[2][1] as any).body)
      expect(executionCallBody.variables.parameters).toEqual([{ parameter: 'startDate', value: '2024-01-01' }])
    })
    vi.unstubAllGlobals()
  })

  it('passes only the dashboard parameters declared by each query', async () => {
    const vizs = [
      {
        id: 'v1',
        configuration: {},
        visualization: { id: '1', key: 'dated', name: 'Dated', type: 'BAR', queryId: 'q-dated', configuration: {} },
      },
      {
        id: 'v2',
        configuration: {},
        visualization: { id: '2', key: 'count', name: 'Count', type: 'NUMBER', queryId: 'q-count', configuration: {} },
      },
    ]
    const params = [
      { parameter: 'startDate', name: 'Start', defaultValue: '2024-01-01' },
      { parameter: 'endDate', name: 'End', defaultValue: '2024-01-31' },
    ]
    const fetchMock = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      const body = JSON.parse(init.body as string)
      let response: Record<string, unknown>
      if (body.query.includes('GetDashboard(')) {
        response = makeDashboardResponse(vizs, params)
      } else if (body.query.includes('GetDashboardQueryParameters')) {
        response = makeQueryParametersResponse(
          body.variables.queryId === 'q-dated' ? ['startDate', 'endDate'] : [],
        )
      } else {
        response = makeQueryResponse([])
      }
      return Promise.resolve({ json: () => Promise.resolve(response) })
    })
    vi.stubGlobal('fetch', fetchMock)

    shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })

    await vi.waitFor(() => {
      const executionBodies = fetchMock.mock.calls
        .map(([, init]) => JSON.parse((init as RequestInit).body as string))
        .filter(body => body.query.includes('ExecuteQuery'))
      expect(executionBodies).toHaveLength(2)
      expect(executionBodies.find(body => body.variables.queryId === 'q-dated')?.variables.parameters).toEqual([
        { parameter: 'startDate', value: '2024-01-01' },
        { parameter: 'endDate', value: '2024-01-31' },
      ])
      expect(executionBodies.find(body => body.variables.queryId === 'q-count')?.variables.parameters).toEqual([])
    })
    vi.unstubAllGlobals()
  })

  it('passes merged parameters down to visualizations', async () => {
    const vizs = [{
      id: 'v1',
      configuration: {},
      visualization: { id: '1', key: 'v1', name: 'Range', type: 'DATEPICKER', queryId: null, configuration: {} },
    }]
    const params = [
      { parameter: 'startDate', name: 'Start', defaultValue: { now: true, nowDayOffset: -30 } },
      { parameter: 'endDate', name: 'End', defaultValue: { now: true } },
    ]
    vi.stubGlobal('fetch', mockFetch([makeDashboardResponse(vizs, params)]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      const viz = wrapper.findComponent(AnalyticsVisualizationStub)
      expect(viz.exists()).toBe(true)
      expect(viz.props('parameters')).toEqual([
        { parameter: 'startDate', value: { now: true, nowDayOffset: -30 } },
        { parameter: 'endDate', value: { now: true } },
      ])
    })
    vi.unstubAllGlobals()
  })

  it('handles GraphQL errors in dashboard query', async () => {
    vi.stubGlobal('fetch', mockFetch([{ errors: [{ message: 'Auth required' }] }]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      expect(wrapper.find('.dashboard-display__state--error').exists()).toBe(true)
    })
    expect(wrapper.text()).toContain('Auth required')
    vi.unstubAllGlobals()
  })

  it('renders the live-sessions slot for LIVE_SESSIONS_MAP visualizations', async () => {
    const vizs = [{
      id: 'v1',
      configuration: { x: 0, y: 0, w: 6, h: 3, titleOverride: 'Live Map' },
      visualization: { id: '1', key: 'v1', name: 'Sessions', type: 'LIVE_SESSIONS_MAP', queryId: null, configuration: { appId: 'app-1' } },
    }]
    vi.stubGlobal('fetch', mockFetch([makeDashboardResponse(vizs)]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
      slots: {
        'live-sessions': `
          <template #live-sessions="{ title, configuration }">
            <div class="live-slot">{{ title }}:{{ configuration.appId }}</div>
          </template>
        `,
      },
    })
    await vi.waitFor(() => {
      expect(wrapper.find('.live-slot').text()).toBe('Live Map:app-1')
    })
    expect(wrapper.findComponent(AnalyticsVisualizationStub).exists()).toBe(false)
    vi.unstubAllGlobals()
  })

  it('falls back to the static map for LIVE_SESSIONS_MAP without a live-sessions slot', async () => {
    const vizs = [{
      id: 'v1',
      configuration: { x: 0, y: 0, w: 6, h: 3 },
      visualization: { id: '1', key: 'v1', name: 'Sessions', type: 'LIVE_SESSIONS_MAP', queryId: null, configuration: { appId: 'app-1' } },
    }]
    vi.stubGlobal('fetch', mockFetch([makeDashboardResponse(vizs)]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      const viz = wrapper.findComponent(AnalyticsVisualizationStub)
      expect(viz.exists()).toBe(true)
      expect(viz.props('type')).toBe('LIVE_SESSIONS_MAP')
      expect(viz.props('configuration')).toEqual({ appId: 'app-1' })
    })
    vi.unstubAllGlobals()
  })

  it('does not render the live-sessions slot for query-fed visualizations', async () => {
    const vizs = [{
      id: 'v1',
      configuration: { x: 0, y: 0, w: 6, h: 3 },
      visualization: { id: '1', key: 'v1', name: 'Chart', type: 'BAR', queryId: null, configuration: {} },
    }]
    vi.stubGlobal('fetch', mockFetch([makeDashboardResponse(vizs)]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
      slots: {
        'live-sessions': '<div class="live-slot" />',
      },
    })
    await vi.waitFor(() => {
      expect(wrapper.findComponent(AnalyticsVisualizationStub).exists()).toBe(true)
    })
    expect(wrapper.find('.live-slot').exists()).toBe(false)
    vi.unstubAllGlobals()
  })

  it('applies title size and bold classes', async () => {
    const vizs = [{
      id: 'v1',
      configuration: { x: 0, y: 0, w: 6, h: 3, titleSize: 'lg', boldTitle: true },
      visualization: { id: '1', key: 'v1', name: 'Chart', type: 'BAR', queryId: null, configuration: {} },
    }]
    vi.stubGlobal('fetch', mockFetch([makeDashboardResponse(vizs)]))
    const wrapper = shallowMount(DashboardDisplay, {
      props: { dashboardKey: 'test' },
      global: { stubs: { AnalyticsVisualization: AnalyticsVisualizationStub } },
    })
    await vi.waitFor(() => {
      const title = wrapper.find('.dashboard-display__title')
      expect(title.classes()).toContain('dashboard-display__title--lg')
      expect(title.classes()).toContain('dashboard-display__title--bold')
    })
    vi.unstubAllGlobals()
  })
})
