import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import KitHarnessControls from './KitHarnessControls.vue'
import KitHarnessTurn from './KitHarnessTurn.vue'
import KitHarnessContent from './KitHarnessContent.vue'
import KitHarnessSettings from './KitHarnessSettings.vue'
import KitHarnessContext from './KitHarnessContext.vue'
import KitGraphQLResult from './KitGraphQLResult.vue'
import { harnessAnalyticsResource, harnessEvent, harnessGraphQLResource, newerHarnessEvent, safeHarnessLink } from '~/utils/kitHarness'

vi.mock('json-editor-vue', () => ({ default: { name: 'JsonEditorVue', props: ['modelValue', 'readOnly', 'mainMenuBar', 'navigationBar'], template: '<div class="json-editor" />' } }))

const Button = { template: '<button><slot /></button>' }
const KitMessageContent = { props: ['text'], template: '<p>{{ text }}</p>' }
const event = (values = {}) => harnessEvent({ type: 'harness-turn', turnId: 'turn', generation: 2, epoch: 4, revision: 3, status: 'STREAMING', ...values })!

describe('Harness conversation', () => {
  const id = '11111111-1111-1111-1111-111111111111'
  const NuxtLink = { props: ['to'], template: '<a :href="to"><slot /></a>' }
  const resultResource = (overrides = {}) => ({ mimeType: 'application/json', uri: `bosca://analytics/queries/${id}/results`,
    text: JSON.stringify({ records: [{ day: 'Monday', sessions: 12 }], returnedRecords: 1, truncated: true, cached: true, refreshedAt: '2026-10-23T12:00:00Z' }), ...overrides })

  it('renders stored GraphQL payloads and schema without interpreting their strings', () => {
    const data = { data: { title: '<script>stored</script>', missing: null }, errors: [{ message: 'Partial result' }] }
    const sdl = 'type Metadata { name: String } # <script>schema</script>'
    const wrapper = mount(KitGraphQLResult, { props: { data, dataType: '[Metadata]', sdl },
      global: { stubs: { ClientOnly: { template: '<slot />' } } } })
    expect(wrapper.getComponent({ name: 'JsonEditorVue' }).props('modelValue')).toEqual(data)
    expect(wrapper.getComponent({ name: 'JsonEditorVue' }).props('readOnly')).toBe(true)
    expect(wrapper.get('pre').text()).toBe(sdl)
    expect(wrapper.text()).toContain('GraphQL result · [Metadata]')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('a').exists()).toBe(false)
  })

  it('renders exact GraphQL data and partial errors in a read-only JSON viewer without fetching', () => {
    const value = { data: { name: '<script>private result</script>', nullable: null, values: [1, false] }, errors: [{ message: 'A field was denied' }] }
    const resource = { mimeType: 'application/json', uri: 'bosca://graphql/results/execute', text: JSON.stringify(value) }
    const wrapper = mount(KitHarnessContent, { props: { content: { type: 'resource', resource } },
      global: { stubs: { ClientOnly: { template: '<slot />' }, KitMessageContent, NuxtLink } } })
    expect(wrapper.get('summary').text()).toBe('GraphQL result')
    expect(wrapper.getComponent({ name: 'JsonEditorVue' }).props()).toEqual({ modelValue: value, readOnly: true, mainMenuBar: false, navigationBar: false })
    expect(wrapper.findComponent(KitMessageContent).exists()).toBe(false)
    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.find('script').exists()).toBe(false)
    for (const patch of [{ uri: 'https://remote/result' }, { uri: 'bosca://graphql/results/execute?extra=1' }, { mimeType: 'text/html' },
      { text: 'invalid' }, { text: '[]' }, { text: '{}' }, { text: '{"data":{},"errors":["bad"]}' }]) {
      expect(harnessGraphQLResource({ ...resource, ...patch })).toBeNull()
    }
    expect(harnessGraphQLResource({ ...resource, text: '{"data":null}' })).toEqual({ data: null })
  })

  it('renders bounded analytics rows with cache and truncation evidence and a saved query link', () => {
    const KitAnalyticsResult = { props: ['name', 'visualizationType', 'configuration', 'data'], template: '<div class="table" />' }
    const wrapper = mount(KitHarnessContent, { props: { content: { type: 'content', content: { type: 'resource', resource: resultResource() } } },
      global: { stubs: { KitAnalyticsResult, NuxtLink, KitMessageContent } } })
    expect(wrapper.getComponent(KitAnalyticsResult).props()).toEqual({ name: 'Query results', visualizationType: 'TABLE', configuration: {}, data: [{ day: 'Monday', sessions: 12 }] })
    expect(wrapper.text()).toContain('1 row · Results truncated · Cached result · Refreshed 2026-10-23T12:00:00Z')
    expect(wrapper.get('a').attributes('href')).toBe(`/analytics/queries/${id}`)
    expect(wrapper.findComponent(KitMessageContent).exists()).toBe(false)
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('renders cached SQL and resolved parameters from the original saved query execution', () => {
    const KitAnalyticsResult = { template: '<div class="table" />' }
    const evidence = { queryId: id, sql: 'SELECT ? AS value', executedAt: '2026-10-22T12:00:00Z', durationMs: 17,
      parameters: [{ position: 1, parameter: 'since', type: 'DATE', value: '2026-10-21<script>' }] }
    const value = { records: [{ value: 1 }], returnedRecords: 1, truncated: false, cached: true, stale: true, evidence }
    const resource = resultResource({ text: JSON.stringify(value) })
    const wrapper = mount(KitHarnessContent, { props: { content: { type: 'resource', resource } },
      global: { stubs: { KitAnalyticsResult, NuxtLink, KitMessageContent } } })
    expect(wrapper.get('summary').text()).toContain('17 ms · 2026-10-22T12:00:00Z')
    expect(wrapper.findAll('pre').map(pre => pre.text())).toEqual([evidence.sql, JSON.stringify(evidence.parameters, null, 2)])
    expect(wrapper.text()).toContain('Cached result · Refresh overdue')
    expect(wrapper.find('script').exists()).toBe(false)
    const withoutEvidence = harnessAnalyticsResource({ ...resource, text: JSON.stringify({ ...value, evidence: null }) })
    expect(withoutEvidence?.data).toEqual(value.records)
    expect(withoutEvidence?.sql).toBeUndefined()
    for (const patch of [{ queryId: 'bad' }, { sql: null }, { durationMs: -1 }, { parameters: [{ position: 2 }] }]) {
      expect(harnessAnalyticsResource({ ...resource, text: JSON.stringify({ ...value, evidence: { ...evidence, ...patch } }) })).toBeNull()
    }
  })

  it('renders an ad-hoc chart with the SQL and timing returned by execution', () => {
    const KitAnalyticsResult = { props: ['name', 'visualizationType', 'configuration', 'data'], template: '<div class="chart" />' }
    const evidence = { records: [{ sessions: 42 }], returnedRecords: 1, cached: false, truncated: true,
      sql: 'SELECT 42 AS sessions -- <script>', executedAt: '2026-10-24T00:00:00Z', durationMs: 7,
      visualizationType: 'NUMBER', configuration: { value: 'sessions' } }
    const resource = resultResource({ uri: `bosca://analytics/sql/${id}/results`, text: JSON.stringify(evidence) })
    const wrapper = mount(KitHarnessContent, { props: { content: { type: 'resource', resource } },
      global: { stubs: { KitAnalyticsResult, NuxtLink, KitMessageContent } } })
    expect(wrapper.getComponent(KitAnalyticsResult).props('visualizationType')).toBe('NUMBER')
    expect(wrapper.getComponent(KitAnalyticsResult).props('configuration')).toEqual({ value: 'sessions' })
    expect(wrapper.get('pre').text()).toBe(evidence.sql)
    expect(wrapper.get('summary').text()).toContain('7 ms')
    expect(wrapper.text()).toContain('Results truncated')
    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.find('script').exists()).toBe(false)
    for (const patch of [{ sql: null }, { durationMs: -1 }, { visualizationType: 'REMOTE' }, { configuration: { url: 'https://remote' } }]) {
      expect(harnessAnalyticsResource({ ...resource, text: JSON.stringify({ ...evidence, ...patch }) })).toBeNull()
    }
  })

  it('keeps a query key as text rather than inventing an artifact id or fetching a resource', () => {
    const result = harnessAnalyticsResource(resultResource({ uri: 'bosca://analytics/queries/by-key/metrics%20%2F%3F%23%E2%9C%93/results' }))
    expect(result?.title).toBe('Query results: metrics /?#✓')
    expect(result?.queryHref).toBeNull()
  })

  it.each([
    { uri: 'https://remote.test/results' }, { mimeType: 'text/html' }, { text: '{' },
    { uri: 'bosca://analytics/queries/by-key/%ZZ/results' },
    { text: JSON.stringify({ records: [3], returnedRecords: 1, truncated: false, cached: false }) },
    { text: JSON.stringify({ records: [], returnedRecords: 5, truncated: false, cached: false }) },
  ])('falls back to ordinary resource text when the typed result is unsupported: %j', (override) => {
    expect(harnessAnalyticsResource(resultResource(override))).toBeNull()
  })

  it('routes known Bosca artifacts through Studio and rejects unknown paths and invalid ids', () => {
    const wrapper = mount(KitHarnessContent, { props: { content: { type: 'resource_link', name: '<script>Report</script>', uri: `bosca://content/metadata/${id}` } },
      global: { stubs: { NuxtLink } } })
    expect(wrapper.get('a').attributes('href')).toBe(`/cms/editor/${id}`)
    expect(wrapper.find('script').exists()).toBe(false)
    for (const kind of ['queries', 'visualizations', 'dashboards']) expect(safeHarnessLink(`bosca://analytics/${kind}/${id}`)).toBe(`/analytics/${kind}/${id}`)
    expect(safeHarnessLink(`bosca://pipelines/${id}`)).toBe(`/pipelines/${id}`)
    expect(safeHarnessLink(`bosca://content/collections/${id}`)).toBe(`/cms/collections/${id}`)
    expect(safeHarnessLink(`bosca://content/collections/${id}?redirect=evil`)).toBeNull()
    expect(safeHarnessLink(`bosca://pipelines/runs/${id}`)).toBe(`/pipelines/runs?runId=${id}`)
    expect(safeHarnessLink(`bosca://pipelines/runs/${id}?redirect=evil`)).toBeNull()
    for (const uri of [`bosca://content/metadata/${id}/../../admin`, `bosca://analytics/queries/${id}?redirect=evil`, 'bosca://unknown/path', 'bosca://content/metadata/../../admin']) expect(safeHarnessLink(uri)).toBeNull()
  })

  it('opens saved memories in the content editor and explains their independent retention', () => {
    const NuxtLink = { props: ['to'], template: '<a :href="to"><slot /></a>' }
    const wrapper = mount(KitHarnessContext, { global: { stubs: { Button, NuxtLink } }, props: { context: {
      sources: [{ kind: 'memory', id: 'saved', title: 'Prefer concise answers' }], integrations: ['bosca'], notices: [],
    } } })
    expect(wrapper.get('a').attributes('href')).toBe('/cms/editor/saved')
    expect(wrapper.text()).toContain('Saved memory')
    expect(wrapper.text()).toContain('Saved memories remain when a conversation is deleted')
    expect(wrapper.text()).toContain('Ask to forget')
  })
  it('renders current source labels as text and removes revoked sources after refresh', async () => {
    const wrapper = mount(KitHarnessContext, { global: { stubs: { Button } }, props: { attachments: ['source'], context: {
      sources: [{ kind: 'metadata', id: 'source', title: '<script>private title</script>' }], integrations: ['bosca'], notices: [],
    } } })
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.text()).toContain('<script>private title</script>')
    expect(wrapper.text()).toContain('Bosca tools are available')
    await wrapper.get('[aria-label="Remove <script>private title</script> from context"]').trigger('click')
    expect(wrapper.emitted('remove')).toEqual([['metadata', 'source']])
    await wrapper.findAll('button').find(button => button.text() === 'Add content')!.trigger('click')
    expect(wrapper.emitted('addContent')).toEqual([[]])
    await wrapper.setProps({ context: { sources: [], integrations: ['bosca'], notices: ['Some selected sources are unavailable.'] } })
    expect(wrapper.text()).not.toContain('private title')
    expect(wrapper.get('[role="status"]').text()).toContain('unavailable')
  })
  it('shows a managed summary and offers only authorized profile choices', async () => {
    const settings = { policyVersion: 'version', effectiveProfile: 'managed', source: 'SYSTEM', canOverride: false, choices: [] as string[] }
    const Select = { props: ['options', 'modelValue'], emits: ['update:modelValue'],
      template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="option in options" :value="option.value">{{ option.label }}</option></select>' }
    const wrapper = mount(KitHarnessSettings, { props: { settings }, global: { stubs: { Select } } })
    expect(wrapper.text()).toContain('Harness: managed')
    expect(wrapper.find('select').exists()).toBe(false)
    await wrapper.setProps({ settings: { ...settings, canOverride: true, choices: ['managed', 'advanced'] } })
    expect(wrapper.findAll('option').map(option => option.text())).toEqual(['Managed defaults', 'managed', 'advanced'])
    await wrapper.get('select').setValue('advanced')
    expect(wrapper.emitted('change')).toEqual([['advanced']])
    await wrapper.get('select').setValue('__managed__')
    expect(wrapper.emitted('change')?.[1]).toEqual([null])
    await wrapper.setProps({ settings: { ...settings, notice: 'Custom access was removed.' } })
    expect(wrapper.get('[role="status"]').text()).toContain('removed')
    expect(wrapper.find('select').exists()).toBe(false)
  })

  it('keeps Git files in one repository distinct and removes the selected ref and path', async () => {
    const wrapper = mount(KitHarnessContext, { global: { stubs: { Button } }, props: { context: {
      sources: [{ kind: 'git_file', id: 'repository', title: 'First file', ref: 'main', path: 'one.md', selected: true },
        { kind: 'git_file', id: 'repository', title: 'Second file', ref: 'release', path: 'two.md', selected: true }],
      integrations: [], notices: [],
    } } })
    expect(wrapper.findAll('li')).toHaveLength(2)
    await wrapper.get('[aria-label="Remove Second file (release) from context"]').trigger('click')
    expect(wrapper.find('[aria-label="Remove First file (main) from context"]').exists()).toBe(true)
    expect(wrapper.emitted('remove')).toEqual([['git_file', 'repository', 'release', 'two.md']])
    await wrapper.findAll('button').find(button => button.text() === 'Add WorkOps')!.trigger('click')
    expect(wrapper.emitted('addWorkOps')).toEqual([[]])
    await wrapper.setProps({ context: { sources: [], integrations: [], notices: ['Unavailable sources'], selectionCount: 16 } })
    await wrapper.findAll('button').find(button => button.text() === 'Clear WorkOps and Git selections')!.trigger('click')
    expect(wrapper.emitted('clear')).toEqual([[]])
  })

  it('does not treat a managed domain source as a content attachment with the same id', () => {
    const wrapper = mount(KitHarnessContext, { global: { stubs: { Button } }, props: { attachments: ['shared-id'], context: {
      sources: [{ kind: 'spec', id: 'shared-id', title: 'Managed source', selected: false }], integrations: [], notices: [],
    } } })
    expect(wrapper.find('[aria-label="Remove Managed source from context"]').exists()).toBe(false)
  })
  it('allows only optional MCP selections and shows readiness failures without registry details', async () => {
    const settings = { policyVersion: 'version', effectiveProfile: 'managed', source: 'SYSTEM', canOverride: true,
      choices: ['managed'], mcpServers: ['required', 'optional'],
      mcpOptions: [{ key: 'required', name: 'Company documents', required: true }, { key: 'optional', name: 'Calendar', required: false }],
      mcpNotices: ['MCP calendar is unavailable. Review its credentials.'] }
    const wrapper = mount(KitHarnessSettings, { props: { settings }, global: { stubs: { Select: true } } })
    const inputs = wrapper.findAll('input[type="checkbox"]')
    expect(inputs[0]!.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Company documents (required)')
    await inputs[1]!.setValue(false)
    expect(wrapper.emitted('connections')).toEqual([[['required']]])
    expect(wrapper.get('[role="status"]').text()).toContain('calendar is unavailable')
    await wrapper.setProps({ settings: { ...settings, canOverride: false, mcpOptions: [] } })
    expect(wrapper.find('fieldset').exists()).toBe(false)
    expect(wrapper.text()).toContain('Connections: required, optional')
  })
  it('renders approved model and reasoning controls and clears effort when the model changes', async () => {
    const settings = { policyVersion: 'version', effectiveProfile: 'managed', source: 'SYSTEM', canOverride: true,
      choices: ['managed'], model: 'small', reasoning: 'medium', capabilityVersion: 'caps',
      models: [{ value: 'small', name: 'Small' }, { value: 'large', name: 'Large' }],
      reasoningOptions: [{ value: 'medium', name: 'Medium' }] }
    const wrapper = mount(KitHarnessSettings, { props: { settings }, global: { stubs: { Select: true } } })
    const selectors = wrapper.findAllComponents({ name: 'Select' })
    expect(selectors).toHaveLength(3)
    selectors[1]!.vm.$emit('update:model-value', 'large')
    expect(wrapper.emitted('model')).toEqual([['large', null]])
    await wrapper.setProps({ settings: { ...settings, requestedModel: 'large' } })
    expect(wrapper.text()).toContain('applies to the next turn')
    expect(wrapper.findAllComponents({ name: 'Select' })[2]!.attributes('disabled')).toBe('true')
    await wrapper.setProps({ settings: { ...settings, canOverride: false } })
    expect(wrapper.findAllComponents({ name: 'Select' })).toHaveLength(0)
    expect(wrapper.text()).toContain('Model: small')
    expect(wrapper.text()).toContain('Reasoning: medium')
  })
  it('keeps the latest snapshot across duplicated, delayed and reconnected deliveries', () => {
    const current = event({ text: 'latest', revision: 8 })
    expect(newerHarnessEvent(current, event({ revision: 7 }))).toBe(current)
    expect(newerHarnessEvent(current, event({ epoch: 3, revision: 100 }))).toBe(current)
    expect(newerHarnessEvent(current, event({ generation: 1, epoch: 100 }))).toBe(current)
    expect(newerHarnessEvent(current, event({ epoch: 5, revision: 1 })).epoch).toBe(5)
    expect(newerHarnessEvent(current, current)).toBe(current)
  })

  it('updates streaming text, tool progress and plan in place and retains rich results at completion', async () => {
    const wrapper = mount(KitHarnessTurn, {
      props: { event: event({ text: 'Working', tools: [{ toolCallId: 'tool', title: 'Read file', status: 'in_progress' }] }) },
      global: { stubs: { KitMessageContent, NuxtLink }, components: { KitHarnessContent } },
    })
    expect(wrapper.text()).toContain('Working')
    expect(wrapper.text()).toContain('in progress')
    await wrapper.setProps({ event: event({ revision: 4, status: 'COMPLETED',
      plan: [{ content: 'Check file', status: 'completed' }],
      tools: [{ toolCallId: 'tool', title: 'Read file', status: 'completed', content: [{ type: 'content', content: { type: 'text', text: 'file contents' } }] }],
      content: [{ type: 'resource_link', uri: '/cms/metadata/example', name: 'Saved file' }],
    }) })
    expect(wrapper.text()).not.toContain('Working')
    expect(wrapper.findAll('details')).toHaveLength(1)
    expect(wrapper.text()).toContain('file contents')
    expect(wrapper.get('li').classes()).toContain('completed')
    expect(wrapper.get('a').attributes('href')).toBe('/cms/metadata/example')
  })

  it('keeps a generated image and its saved content link through turn completion', async () => {
    const id = '11111111-1111-4111-8111-111111111111'
    const content = [
      { type: 'content', content: { type: 'resource_link', name: 'Bird', uri: `bosca://content/metadata/${id}` } },
      { type: 'content', content: { type: 'image', mimeType: 'image/png', data: 'aGVsbG8=' } },
    ]
    const wrapper = mount(KitHarnessTurn, {
      props: { event: event({ tools: [{ toolCallId: 'image', title: 'Generate image', status: 'completed', content }] }) },
      global: { stubs: { KitMessageContent, NuxtLink, KitAnalyticsResult: true }, components: { KitHarnessContent } },
    })
    expect(wrapper.get('img').attributes('src')).toBe('data:image/png;base64,aGVsbG8=')
    expect(wrapper.get('a').attributes('href')).toBe(`/cms/editor/${id}`)
    await wrapper.setProps({ event: event({ revision: 4, status: 'COMPLETED',
      tools: [{ toolCallId: 'image', title: 'Generate image', status: 'completed', content }],
    }) })
    expect(wrapper.findAll('img')).toHaveLength(1)
    expect(wrapper.get('a').text()).toBe('Bird')
  })

  it('offers the native permission options and disables decisions while stopping', async () => {
    const wrapper = mount(KitHarnessControls, {
      props: { state: { state: 'WAITING_FOR_APPROVAL', cancellationRequested: false }, processing: true, busy: false,
        approvals: [{ id: 'approval', expires: 'later', request: { toolCall: { title: 'Write report' }, permissions: [
          { optionId: 'yes', name: 'Allow once', kind: 'allow_once' }, { optionId: 'no', name: 'Deny', kind: 'reject_once' },
        ] } }],
      }, global: { stubs: { Button } },
    })
    await wrapper.findAll('button')[2]!.trigger('click')
    expect(wrapper.emitted('decide')).toEqual([['approval', 'no']])
    await wrapper.findAll('button')[0]!.trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    await wrapper.setProps({ state: { state: 'WAITING_FOR_APPROVAL', cancellationRequested: true } })
    expect(wrapper.text()).toContain('Stopping current work')
    expect(wrapper.findAll('button').every(button => button.attributes('disabled') !== undefined)).toBe(true)
  })

  it('archives idle sessions and retries queued work without another message', async () => {
    const wrapper = mount(KitHarnessControls, { props: { state: { state: 'READY', cancellationRequested: false },
      approvals: [], processing: false, busy: false }, global: { stubs: { Button } } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('archive')).toEqual([[]])
    await wrapper.setProps({ state: { state: 'FAILED', cancellationRequested: false, failure: 'MCP documents is unavailable.' } })
    expect(wrapper.get('button').text()).toBe('Retry session')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('activate')).toEqual([[]])
    await wrapper.setProps({ processing: true, state: { state: 'BUSY', cancellationRequested: false } })
    expect(wrapper.text()).not.toContain('Archive session')
  })
  it('renders interruption truthfully without claiming work was undone', () => {
    const wrapper = mount(KitHarnessTurn, { props: { event: event({ status: 'INTERRUPTED' }) } })
    expect(wrapper.text()).toContain('outcome could not be confirmed')
  })

  it('renders typed raster images and escapes diff text', () => {
    const image = mount(KitHarnessContent, { props: { content: { type: 'image', mimeType: 'image/png', data: 'aGVsbG8=' } } })
    expect(image.get('img').attributes('src')).toBe('data:image/png;base64,aGVsbG8=')
    const diff = mount(KitHarnessContent, { props: { content: { type: 'diff', path: 'file', newText: '<script>evil()</script>' } } })
    expect(diff.find('script').exists()).toBe(false)
    expect(diff.get('pre').text()).toBe('<script>evil()</script>')
  })

  it.each(['javascript:alert(1)', 'data:text/html,evil', '//remote.test', '/\\remote.test', 'https://user:secret@example.com', 'https:\n//example.com'])('rejects unsafe resource links: %s', (uri) => {
    expect(safeHarnessLink(uri)).toBeNull()
    const wrapper = mount(KitHarnessContent, { props: { content: { type: 'resource_link', uri } } })
    expect(wrapper.find('a').exists()).toBe(false)
  })
})
