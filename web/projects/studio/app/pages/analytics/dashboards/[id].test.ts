import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import type { DocumentNode, OperationDefinitionNode } from 'graphql'
import DashboardDetail from './[id].vue'

function operationName(doc: DocumentNode): string {
  const op = doc.definitions.find(
    (d): d is OperationDefinitionNode => d.kind === 'OperationDefinition',
  )
  return op?.name?.value ?? ''
}

const startDefault = { now: true, nowDayOffset: -30 }
const endDefault = { now: true }

const mockDashboard = {
  id: 'd-1',
  key: 'overview',
  name: 'Overview',
  description: '',
  configuration: {},
  visualizations: [
    {
      id: 'viz-picker',
      configuration: { x: 0, y: 0, w: 12, h: 3 },
      visualization: { id: 'v-1', key: 'range', name: 'Range', type: 'DATEPICKER', queryId: null, configuration: {} },
    },
    {
      id: 'viz-bar',
      configuration: { x: 0, y: 3, w: 24, h: 12 },
      visualization: { id: 'v-2', key: 'events', name: 'Events', type: 'BAR', queryId: 'q-1', configuration: {} },
    },
  ],
  parameters: [
    { parameter: 'startDate', name: 'Start', description: '', type: 'DATETIME', arrayType: 'NONE', defaultValue: startDefault, required: false },
    { parameter: 'endDate', name: 'End', description: '', type: 'DATETIME', arrayType: 'NONE', defaultValue: endDefault, required: false },
  ],
  permissions: [],
}

let executeResponse = {
  records: [] as Record<string, unknown>[],
  cached: false,
  stale: false,
  refreshedAt: null as string | null,
}

const mockQuery = vi.fn(async (doc: DocumentNode, _variables?: Record<string, unknown>) => {
  switch (operationName(doc)) {
    case 'GetQueryParams':
      return { analytics: { queries: { queryById: { parameters: [{ parameter: 'startDate' }, { parameter: 'endDate' }] } } } }
    case 'ExecuteQuery':
      return { analytics: { queries: { execute: executeResponse } } }
    default:
      return {}
  }
})
const mockMutation = vi.fn().mockResolvedValue({})

const dashboardData = ref<{ analytics: { dashboards: { byId: typeof mockDashboard } } } | null>(null)

vi.stubGlobal('useGraphQL', () => ({
  query: mockQuery,
  mutation: mockMutation,
  useAsyncQuery: (key: string) => {
    if (key === 'dashboard-detail') {
      return { data: dashboardData, status: ref('success'), refresh: vi.fn() }
    }
    if (key === 'all-visualizations') {
      return { data: ref({ analytics: { visualizations: { all: [] } } }), status: ref('success'), refresh: vi.fn() }
    }
    return { data: ref(null), status: ref('success'), refresh: vi.fn() }
  },
}))

vi.stubGlobal('useRoute', () => ({ params: { id: 'd-1' }, path: '/analytics/dashboards/d-1', query: {} }))
vi.stubGlobal('useRouter', () => ({ replace: vi.fn() }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#5ec5ff' }))
vi.stubGlobal('useToast', () => ({ success: vi.fn(), error: vi.fn() }))

const AnalyticsVisualizationStub = defineComponent({
  name: 'AnalyticsVisualization',
  props: ['name', 'type', 'configuration', 'data', 'loading', 'error', 'parameters', 'frameless'],
  emits: ['paramChange'],
  template: '<div class="mock-viz" :data-type="type" />',
})

const DashboardGridStub = defineComponent({
  name: 'DashboardGrid',
  props: ['layout', 'columns', 'cellHeight', 'gap', 'lockedIds'],
  template: '<div><template v-for="item in layout" :key="item.id"><slot name="item" :item="item" /></template></div>',
})

const stubs = {
  AnalyticsVisualization: AnalyticsVisualizationStub,
  DashboardGrid: DashboardGridStub,
  LiveSessionsVisualization: { template: '<div class="mock-live" />', props: ['name', 'configuration', 'frameless'] },
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: { template: '<div><slot name="actions" /></div>', props: ['accent', 'breadcrumb', 'title'] },
  SectionCard: { template: '<div class="mock-section" :data-title="title"><slot name="right" /><slot /></div>', props: ['title'] },
  Button: { template: '<button class="mock-btn" @click="$emit(\'click\')"><slot /></button>', props: ['size', 'icon', 'primary', 'accent', 'disabled'] },
  Badge: { template: '<span><slot /></span>', props: ['color'] },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  TextInput: { template: '<input />', props: ['modelValue', 'label', 'mono', 'disabled', 'size', 'type', 'placeholder'] },
  Textarea: { template: '<textarea />', props: ['modelValue', 'label', 'rows'] },
  Select: { template: '<select />', props: ['modelValue', 'options', 'label', 'size', 'searchable', 'placeholder', 'accent'] },
  Checkbox: { template: '<input type="checkbox" />', props: ['modelValue'] },
  DateParameterInput: { template: '<input />', props: ['modelValue', 'type'] },
  Modal: { template: '<div><slot /><slot name="footer" /></div>', props: ['title', 'icon', 'accent', 'width'] },
  ConfirmModal: { template: '<div />', props: ['title', 'loading'] },
  GlassTable: { template: '<div />', props: ['columns', 'rows', 'emptyText', 'rowActions'] },
}

function mountPage() {
  return mount(DashboardDetail, {
    global: {
      stubs,
      mocks: { buildBreadcrumb: (...args: string[]) => args },
    },
  })
}

function executeCalls() {
  return mockQuery.mock.calls.filter(([doc]) => operationName(doc) === 'ExecuteQuery')
}

function datePickerViz(wrapper: ReturnType<typeof mountPage>) {
  const viz = wrapper
    .findAllComponents(AnalyticsVisualizationStub)
    .find(v => v.props('type') === 'DATEPICKER')
  expect(viz).toBeDefined()
  return viz!
}

// Unmount between tests so stale page instances' dashboard watchers don't
// re-fire (and re-execute queries) when beforeEach resets the shared data ref.
enableAutoUnmount(afterEach)

describe('Analytics Dashboard Detail Page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    dashboardData.value = { analytics: { dashboards: { byId: { ...mockDashboard } } } }
    executeResponse = { records: [], cached: false, stale: false, refreshedAt: null }
  })

  it('executes queries with the dashboard default parameters on load', async () => {
    mountPage()
    await flushPromises()
    const calls = executeCalls()
    expect(calls).toHaveLength(1)
    expect(calls[0]![1]).toEqual({
      queryId: 'q-1',
      parameters: [
        { parameter: 'startDate', value: startDefault },
        { parameter: 'endDate', value: endDefault },
      ],
    })
  })

  it('passes the execution parameters to visualizations', async () => {
    const wrapper = mountPage()
    await flushPromises()
    expect(datePickerViz(wrapper).props('parameters')).toEqual([
      { parameter: 'startDate', value: startDefault },
      { parameter: 'endDate', value: endDefault },
    ])
  })

  it('re-executes queries with the picked range when the date picker changes', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const pickedStart = { value: '2026-06-01T00:00:00.000Z', now: false }
    const pickedEnd = { value: '2026-06-30T00:00:00.000Z', now: false }
    datePickerViz(wrapper).vm.$emit('paramChange', [
      { parameter: 'startDate', value: pickedStart },
      { parameter: 'endDate', value: pickedEnd },
    ])
    await flushPromises()

    const calls = executeCalls()
    expect(calls).toHaveLength(2)
    expect(calls[1]![1]).toEqual({
      queryId: 'q-1',
      parameters: [
        { parameter: 'startDate', value: pickedStart },
        { parameter: 'endDate', value: pickedEnd },
      ],
    })
    expect(datePickerViz(wrapper).props('parameters')).toEqual([
      { parameter: 'startDate', value: pickedStart },
      { parameter: 'endDate', value: pickedEnd },
    ])
  })

  it('labels stale visualization data with its last refresh time', async () => {
    executeResponse = {
      records: [{ total: 12 }],
      cached: true,
      stale: true,
      refreshedAt: '2026-07-01T00:00:00Z',
    }

    const wrapper = mountPage()
    await flushPromises()

    const freshness = wrapper.find('.grid-viz-asof')
    expect(freshness.text()).toContain('Stale · last refreshed')
    expect(freshness.classes()).toContain('grid-viz-asof--stale')
    expect(freshness.attributes('title')).toContain('Stale cached result')
  })
})
