import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import KitAnalyticsResult from './KitAnalyticsResult.vue'

const AnalyticsVisualizationStub = {
  props: ['name', 'type'],
  template: '<div class="visualization" :data-type="type">{{ name }}</div>',
}
const NuxtLinkStub = {
  props: ['to'],
  template: '<a :href="to"><slot /></a>',
}
const IconStub = { template: '<span />' }
const ClientOnlyStub = { template: '<div><slot /></div>' }
const ButtonStub = {
  props: ['disabled'],
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
}

function mountResult(props: Record<string, unknown> = {}) {
  return mount(KitAnalyticsResult, {
    props: {
      name: 'Signups',
      visualizationType: 'LINE',
      configuration: { x: 'week', y: ['signups'] },
      data: [{ week: '2026-01-01', signups: 12 }],
      ...props,
    },
    global: {
      stubs: {
        AnalyticsVisualization: AnalyticsVisualizationStub,
        NuxtLink: NuxtLinkStub,
        Icon: IconStub,
        ClientOnly: ClientOnlyStub,
        Button: ButtonStub,
      },
    },
  })
}

describe('KitAnalyticsResult', () => {
  it('renders artifact links and falls unsupported visualization types back to a table', () => {
    const wrapper = mountResult({
      visualizationType: 'GANTT',
      artifacts: [
        { kind: 'Saved query', label: 'weekly-signups', to: '/analytics/queries/query-id' },
        { kind: 'Dashboard', label: 'Dashboard', to: '/analytics/dashboards/dashboard-id' },
      ],
    })

    expect(wrapper.find('.visualization').attributes('data-type')).toBe('TABLE')
    expect(wrapper.findAll('.artifact-chip').map(link => link.text())).toEqual([
      'Saved query: weekly-signups',
      'Dashboard: Dashboard',
    ])
    expect(wrapper.findAll('.artifact-chip').map(link => link.attributes('href'))).toEqual([
      '/analytics/queries/query-id',
      '/analytics/dashboards/dashboard-id',
    ])
  })

  it('renders annotated and unannotated recorded steps in execution order', async () => {
    const wrapper = mountResult({
      sourceQuery: 'SELECT week, count(*) FROM signups GROUP BY week',
      investigation: [
        {
          sequence: 1, kind: 'DISCOVERY', tool: 'describe_table', resultSummary: '8 columns',
          startedAt: '2026-07-21T12:00:00Z',
        },
        {
          sequence: 2, kind: 'QUERY', tool: 'execute_query', sql: 'SELECT count(*) FROM signups',
          resultSummary: '1 rows in 12 ms', startedAt: '2026-07-21T12:00:01Z',
          purpose: 'Check the total before grouping.', conclusion: 'The total reconciles.',
        },
      ],
    })

    await wrapper.find('.provenance-toggle').trigger('click')
    const steps = wrapper.findAll('.investigation-step')
    expect(steps).toHaveLength(2)
    expect(steps[0]!.text()).toContain('8 columns')
    expect(steps[0]!.find('.step-annotation').exists()).toBe(false)
    expect(steps[1]!.text()).toContain('Check the total before grouping.')
    expect(steps[1]!.text()).toContain('The total reconciles.')
    expect(wrapper.text()).toContain('Headline query')
  })

  it('keeps legacy analytics turns unchanged when provenance and artifacts are absent', () => {
    const wrapper = mountResult()
    expect(wrapper.find('.visualization').attributes('data-type')).toBe('LINE')
    expect(wrapper.find('.artifact-links').exists()).toBe(false)
    expect(wrapper.find('.provenance').exists()).toBe(false)
  })

  it('offers save actions for an unsaved query result and emits each request', async () => {
    const wrapper = mountResult({ sourceQuery: 'SELECT week, count(*) FROM signups GROUP BY week' })

    const saveQuery = wrapper.get('[aria-label="Save query"]')
    const saveVisualization = wrapper.get('[aria-label="Save visualization"]')
    await saveQuery.trigger('click')
    await saveVisualization.trigger('click')

    expect(wrapper.emitted('save-query')).toHaveLength(1)
    expect(wrapper.emitted('save-visualization')).toHaveLength(1)
  })

  it('hides actions for artifacts that are already saved and disables remaining actions while busy', () => {
    const wrapper = mountResult({
      sourceQuery: 'SELECT 42',
      busy: true,
      artifacts: [
        { kind: 'Saved query', label: 'answer', to: '/analytics/queries/query-id' },
      ],
    })

    expect(wrapper.find('[aria-label="Save query"]').exists()).toBe(false)
    expect(wrapper.get('[aria-label="Save visualization"]').attributes('disabled')).toBeDefined()
  })

  it('hides the visualization action after the result has a saved visualization', () => {
    const wrapper = mountResult({
      sourceQuery: 'SELECT 42',
      artifacts: [
        { kind: 'Visualization', label: 'Answer', to: '/analytics/visualizations/visualization-id' },
      ],
    })

    expect(wrapper.find('[aria-label="Save query"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="Save visualization"]').exists()).toBe(false)
  })

  it('copies per-step SQL from the investigation timeline', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const wrapper = mountResult({
      investigation: [{
        sequence: 1, kind: 'QUERY', tool: 'execute_query', sql: 'SELECT 42',
        resultSummary: '1 rows in 1 ms', startedAt: '2026-07-21T12:00:00Z',
      }],
    })
    await wrapper.find('.provenance-toggle').trigger('click')
    await wrapper.find('[aria-label="Copy SQL for step 1"]').trigger('click')
    expect(writeText).toHaveBeenCalledWith('SELECT 42')
  })
})
