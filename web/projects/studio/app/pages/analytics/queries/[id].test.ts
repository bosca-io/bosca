import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import type { DocumentNode, OperationDefinitionNode } from 'graphql'
import QueryDetail from './[id].vue'

vi.mock('@bosca/auth-client-browser', () => ({
  useAuth: () => ({ profile: ref(null) }),
}))

vi.mock('json-editor-vue', () => ({
  default: { name: 'JsonEditorVue', template: '<div class="mock-json-editor" />' },
}))

const mockQueryRecord = {
  id: 'q-1',
  key: 'daily-events',
  name: 'Daily Events',
  description: 'Events per day',
  query: 'SELECT 1',
  configuration: {},
  refreshIntervalSeconds: null,
  parameters: [],
  permissions: [],
}

function operationName(doc: DocumentNode): string {
  const op = doc.definitions.find(
    (d): d is OperationDefinitionNode => d.kind === 'OperationDefinition',
  )
  return op?.name?.value ?? ''
}

// Swapped per test to simulate execution success/failure.
let executeImpl: () => Promise<unknown> = async () => ({
  analytics: { queries: { execute: { records: [], cached: false, stale: false, refreshedAt: null } } },
})

const mockQuery = vi.fn(async (doc: DocumentNode) => {
  switch (operationName(doc)) {
    case 'ExecuteQuery':
      return executeImpl()
    case 'GetQuerySourceRef':
      return { git: { querySourceRef: null } }
    case 'AnalyticsQueryGroups':
      return { security: { groups: { all: [] } } }
    default:
      return {}
  }
})
const mockMutation = vi.fn().mockResolvedValue({})
const mockRefresh = vi.fn()
const mockToastError = vi.fn()
vi.stubGlobal('useToast', () => ({ success: vi.fn(), error: mockToastError }))

// Shared ref so tests can simulate a refetch (e.g. the refresh() after save)
// by replacing the payload, which re-fires the page's queryData watch.
const queryDetailData = ref<{ analytics: { queries: { queryById: typeof mockQueryRecord } } } | null>(null)

vi.stubGlobal('useGraphQL', () => ({
  query: mockQuery,
  mutation: mockMutation,
  useAsyncQuery: (key: string) => {
    if (key === 'query-detail') {
      return {
        data: queryDetailData,
        status: ref('success'),
        refresh: mockRefresh,
      }
    }
    return { data: ref(null), status: ref('success'), refresh: vi.fn() }
  },
  useSubscription: vi.fn(),
}))

vi.stubGlobal('useRoute', () => ({ params: { id: 'q-1' }, path: '/analytics/queries/q-1', query: {} }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#5ec5ff' }))
vi.stubGlobal('buildBreadcrumb', (...args: string[]) => args)
vi.stubGlobal('resolveGitCommitAuthor', () => ({ authorName: 'Test', authorEmail: 'test@example.com' }))

const stubs = {
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: {
    template: '<div class="mock-header"><slot name="actions" /></div>',
    props: ['accent', 'breadcrumb', 'title'],
  },
  SectionCard: {
    template: '<div class="mock-section" :data-title="title"><slot name="right" /><slot /></div>',
    props: ['title'],
  },
  Button: {
    template: '<button class="mock-btn" :disabled="disabled" :title="title" @click="$emit(\'click\')"><slot /></button>',
    props: ['size', 'icon', 'primary', 'accent', 'disabled', 'title'],
  },
  Badge: { template: '<span class="mock-badge"><slot /></span>', props: ['color'] },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  TextInput: {
    template: '<input :data-label="label" :value="modelValue" :min="min" @input="$emit(\'update:modelValue\', $event.target.value)" />',
    props: ['modelValue', 'label', 'mono', 'disabled', 'size', 'type', 'placeholder', 'min'],
  },
  Textarea: { template: '<textarea />', props: ['modelValue', 'label', 'rows'] },
  Select: { template: '<select />', props: ['modelValue', 'options', 'label', 'size', 'searchable', 'placeholder', 'accent'] },
  Checkbox: { template: '<input type="checkbox" />', props: ['modelValue'] },
  CodeEditor: {
    template: '<textarea class="mock-code" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
    props: ['modelValue', 'language', 'rows', 'placeholder', 'readonly'],
  },
  GitFilePicker: { template: '<div class="mock-git-picker" />', props: ['contentType', 'allowCreate', 'newFilePlaceholder', 'initialContent'] },
  DateParameterInput: { template: '<input />', props: ['modelValue', 'type'] },
  Modal: { template: '<div class="mock-modal"><slot /><slot name="footer" /></div>', props: ['title', 'icon', 'accent', 'width'] },
  ConfirmModal: { template: '<div class="mock-confirm" />', props: ['title', 'loading'] },
  GlassTable: { template: '<div class="mock-table" />', props: ['columns', 'rows', 'emptyText', 'rowActions'] },
  ClientOnly: { template: '<div><slot /></div>' },
}

function mountPage() {
  return mount(QueryDetail, {
    global: {
      stubs,
      // Template-only auto-imports resolve from the component instance, not
      // globalThis, so they must be provided as mocks rather than stubGlobal.
      mocks: { buildBreadcrumb: (...args: string[]) => args },
    },
  })
}

function resultsSection(wrapper: ReturnType<typeof mountPage>) {
  return wrapper
    .findAll('.mock-section')
    .find(s => s.attributes('data-title') === 'Results')
}

function executeButton(wrapper: ReturnType<typeof mountPage>) {
  const btn = wrapper.findAll('.mock-btn').find(b => b.text().includes('Execute'))
  expect(btn).toBeDefined()
  return btn!
}

async function clickExecute(wrapper: ReturnType<typeof mountPage>) {
  await executeButton(wrapper).trigger('click')
  await flushPromises()
}

describe('Analytics Query Detail Page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    queryDetailData.value = { analytics: { queries: { queryById: { ...mockQueryRecord } } } }
    executeImpl = async () => ({
      analytics: { queries: { execute: { records: [], cached: false, stale: false, refreshedAt: null } } },
    })
  })

  it('does not render the Results section before any execution', async () => {
    const wrapper = mountPage()
    await flushPromises()
    expect(resultsSection(wrapper)).toBeUndefined()
  })

  it('shows the error message when execution fails', async () => {
    executeImpl = async () => { throw new Error('Table not found: events') }
    const wrapper = mountPage()
    await flushPromises()

    await clickExecute(wrapper)

    const section = resultsSection(wrapper)
    expect(section).toBeDefined()
    expect(section!.find('.error-msg').text()).toBe('Table not found: events')
  })

  it('shows a fallback message when execution rejects with a non-Error value', async () => {
    executeImpl = async () => { throw 'boom' }
    const wrapper = mountPage()
    await flushPromises()

    await clickExecute(wrapper)

    const section = resultsSection(wrapper)
    expect(section).toBeDefined()
    expect(section!.find('.error-msg').text()).toBe('Execution failed')
  })

  it('renders result rows on successful execution', async () => {
    executeImpl = async () => ({
      analytics: {
        queries: {
          execute: {
            records: [{ day: '2026-07-01', count: 12 }],
            cached: false,
            stale: false,
            refreshedAt: null,
          },
        },
      },
    })
    const wrapper = mountPage()
    await flushPromises()

    await clickExecute(wrapper)

    const section = resultsSection(wrapper)
    expect(section).toBeDefined()
    expect(section!.find('.error-msg').exists()).toBe(false)
    expect(section!.text()).toContain('2026-07-01')
    expect(section!.text()).toContain('12')
  })

  it('shows "No results returned." when execution succeeds with zero rows', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await clickExecute(wrapper)

    const section = resultsSection(wrapper)
    expect(section).toBeDefined()
    expect(section!.find('.error-msg').text()).toBe('No results returned.')
  })

  it('replaces a previous error with results when a re-run succeeds', async () => {
    executeImpl = async () => { throw new Error('syntax error at or near "FRUM"') }
    const wrapper = mountPage()
    await flushPromises()

    await clickExecute(wrapper)
    expect(resultsSection(wrapper)!.find('.error-msg').text()).toContain('FRUM')

    executeImpl = async () => ({
      analytics: {
        queries: {
          execute: { records: [{ total: 3 }], cached: false, stale: false, refreshedAt: null },
        },
      },
    })
    await clickExecute(wrapper)

    const section = resultsSection(wrapper)
    expect(section!.find('.error-msg').exists()).toBe(false)
    expect(section!.text()).toContain('3')
  })

  it('enables Execute when the loaded form is unmodified', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(executeButton(wrapper).attributes('disabled')).toBeUndefined()
  })

  it('disables Execute with an explanatory tooltip when the SQL has unsaved changes', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.find('.mock-code').setValue('SELECT 2')

    const btn = executeButton(wrapper)
    expect(btn.attributes('disabled')).toBeDefined()
    expect(btn.attributes('title')).toContain('Save your changes first')
  })

  it('disables Execute when the name has unsaved changes', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.find('input[data-label="Name"]').setValue('Renamed')

    expect(executeButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it('does not run the query while Execute is disabled by unsaved changes', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.find('.mock-code').setValue('SELECT 2')
    await clickExecute(wrapper)

    const executeCalls = mockQuery.mock.calls.filter(([doc]) => operationName(doc) === 'ExecuteQuery')
    expect(executeCalls).toHaveLength(0)
  })

  it('re-enables Execute when an edit is reverted to the saved value', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const editor = wrapper.find('.mock-code')
    await editor.setValue('SELECT 2')
    expect(executeButton(wrapper).attributes('disabled')).toBeDefined()

    await editor.setValue(mockQueryRecord.query)
    expect(executeButton(wrapper).attributes('disabled')).toBeUndefined()
  })

  it('re-enables Execute after a save reloads the query data', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.find('.mock-code').setValue('SELECT 2')
    expect(executeButton(wrapper).attributes('disabled')).toBeDefined()

    const saveBtn = wrapper.findAll('.mock-btn').find(b => b.text() === 'Save')
    expect(saveBtn).toBeDefined()
    await saveBtn!.trigger('click')
    await flushPromises()
    expect(mockRefresh).toHaveBeenCalled()

    // Simulate the refetch triggered by refresh() returning the saved state.
    queryDetailData.value = {
      analytics: { queries: { queryById: { ...mockQueryRecord, query: 'SELECT 2' } } },
    }
    await flushPromises()

    expect(executeButton(wrapper).attributes('disabled')).toBeUndefined()
  })

  it('rejects cache refresh intervals below the supported minimum', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const interval = wrapper.find('input[data-label="Cache Refresh Interval (seconds)"]')
    expect(interval.attributes('min')).toBe('60')
    await interval.setValue('30')
    const saveBtn = wrapper.findAll('.mock-btn').find(b => b.text() === 'Save')
    await saveBtn!.trigger('click')
    await flushPromises()

    expect(mockMutation).not.toHaveBeenCalled()
    expect(mockToastError).toHaveBeenCalledWith('Cache refresh interval must be at least 60 seconds')
  })

  it('shows cached metadata badge when results come from the cache', async () => {
    executeImpl = async () => ({
      analytics: {
        queries: {
          execute: {
            records: [{ total: 3 }],
            cached: true,
            stale: false,
            refreshedAt: '2026-07-01T00:00:00Z',
          },
        },
      },
    })
    const wrapper = mountPage()
    await flushPromises()

    await clickExecute(wrapper)

    const section = resultsSection(wrapper)
    expect(section!.find('.mock-badge').text()).toBe('Cached')
    expect(section!.text()).toContain('fresh as of')
  })

  it('labels overdue cached results as stale with their last refresh time', async () => {
    executeImpl = async () => ({
      analytics: {
        queries: {
          execute: {
            records: [{ total: 3 }],
            cached: true,
            stale: true,
            refreshedAt: '2026-07-01T00:00:00Z',
          },
        },
      },
    })
    const wrapper = mountPage()
    await flushPromises()

    await clickExecute(wrapper)

    const section = resultsSection(wrapper)
    expect(section!.find('.mock-badge').text()).toBe('Stale')
    expect(section!.text()).toContain('last refreshed')
    expect(section!.find('.results-meta__time--stale').exists()).toBe(true)
  })
})
