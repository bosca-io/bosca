import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import ActiveRuns from './PipelineActiveRunsTable.vue'
import RunDetail from './PipelineRunDetailModal.vue'

const query = vi.fn()
vi.stubGlobal('useGraphQL', () => ({ query, mutation: vi.fn(), useSubscription: vi.fn() }))
const table = defineComponent({
  props: ['rows', 'columns'],
  template: '<div><div v-for="row in rows" :key="row.id"><div v-for="column in columns" :key="column.key"><slot :name="\'col-\' + column.key" :row="row">{{ row[column.key] }}</slot></div></div></div>',
})
const stubs = {
  Modal: { props: ['subtitle'], template: '<div><p>{{ subtitle }}</p><slot /></div>' },
  Button: { template: '<button><slot /></button>' },
  NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
  GlassTable: table,
  PipelineRunGraph: true,
  PipelinePeekModal: true,
  Badge: true,
}
const run = {
  id: 'run-1', pipelineId: 'pipeline-1', status: 'SUSPENDED',
  eventName: 'bosca.git.model.GitHubDelivery', input: { repositoryId: 'repo-1' },
  error: 'The originating GitHub user requires Bosca repository EDIT permission',
  createdAt: '2026-10-08T10:00:00Z', modifiedAt: '2026-10-08T10:00:01Z',
  awaitingNodeIds: ['import'], completedNodeIds: ['input'], awaitingNodes: [], steps: [], nodes: [],
}

describe('pipeline retry failures', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows a suspended attempt failure in the active runs table', async () => {
    query.mockResolvedValue({ pipelines: { activeRuns: [run] } })
    const wrapper = mount(ActiveRuns, { global: { stubs } })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('repository EDIT permission')
    expect(wrapper.text()).toContain('SUSPENDED')
    expect(wrapper.text()).toContain('GitHub Delivery')
    wrapper.unmount()
  })

  it('explains retries and links GitHub delivery errors to recovery settings', async () => {
    query.mockResolvedValue({ pipelines: { run } })
    const wrapper = mount(RunDetail, { props: { runId: run.id }, global: { stubs } })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('repository EDIT permission')
    expect(wrapper.text()).toContain('backing work retries')
    expect(wrapper.text()).toContain('GitHub Delivery')
    expect(wrapper.text()).toContain('Pull from GitHub')
    const links = wrapper.findAll('a').map(link => link.attributes('href'))
    expect(links).toContain('/git/settings/github')
    expect(links).toContain('/git/repositories/repo-1?tab=Settings&setting=permissions')
    expect(links).toContain('/git/repositories/repo-1?tab=Settings&setting=github')
    wrapper.unmount()
  })
})
