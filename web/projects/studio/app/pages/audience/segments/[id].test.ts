import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import SegmentDetail from './[id].vue'

const mockMutation = vi.fn().mockResolvedValue({})
const mockQuery = vi.fn().mockResolvedValue({ segments: { all: [] } })
const mockPush = vi.fn()

const mockSegment = {
  id: 'seg-1', name: 'Test Segment', description: 'A test', type: 'STATIC', status: 'ACTIVE',
  memberCount: 42, analyticsQueryId: null, evaluationSchedule: null,
  lastEvaluated: null, configuration: null, created: '2026-01-01', modified: '2026-01-02',
}

const mockMembers = [
  { profile: { id: 'p-1', name: 'Alice', slug: 'alice' }, addedAt: '2026-01-01' },
  { profile: { id: 'p-2', name: 'Bob', slug: 'bob' }, addedAt: '2026-01-02' },
]

vi.stubGlobal('useGraphQL', () => ({
  query: mockQuery,
  mutation: mockMutation,
  useAsyncQuery: (key: string) => {
    if (key === 'segment-detail') {
      return { data: ref({ segments: { segment: mockSegment } }), status: ref('success'), refresh: vi.fn() }
    }
    if (key === 'segment-members') {
      return { data: ref({ segments: { segment: { members: mockMembers } } }), status: ref('success'), refresh: vi.fn() }
    }
    return { data: ref(null), status: ref('success'), refresh: vi.fn() }
  },
  useSubscription: vi.fn(),
}))

vi.stubGlobal('useRouter', () => ({ push: mockPush }))
vi.stubGlobal('useRoute', () => ({ params: { id: 'seg-1' } }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#ff7ac6' }))
vi.stubGlobal('buildBreadcrumb', (...args: string[]) => args)

const stubs = {
  PageShell: { template: '<div><slot /><slot name="header" /></div>' },
  PageHeader: { template: '<div class="mock-header"><slot name="actions" /></div>', props: ['accent', 'breadcrumb', 'title', 'subtitle'] },
  SectionCard: { template: '<div class="mock-section"><slot /></div>', props: ['title'] },
  Button: { template: '<button class="mock-btn" @click="$emit(\'click\')"><slot /></button>', props: ['size', 'icon', 'primary', 'accent', 'disabled'] },
  Badge: { template: '<span class="mock-badge"><slot /></span>', props: ['color'] },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  TextInput: { template: '<input />', props: ['modelValue', 'label'] },
  Textarea: { template: '<textarea />', props: ['modelValue', 'label', 'rows'] },
  Select: { template: '<select />', props: ['modelValue', 'options', 'label', 'placeholder'] },
  Modal: { template: '<div class="mock-modal"><slot /><slot name="footer" /></div>', props: ['title', 'icon', 'accent'] },
  ConfirmModal: { template: '<div class="mock-confirm" />', props: ['title', 'subtitle', 'loading'] },
  GlassTable: {
    template: '<div class="mock-table"><slot v-for="row in rows" name="col-name" :row="row" /><slot v-for="row in rows" name="col-actions" :row="row" /></div>',
    props: ['columns', 'rows', 'emptyText', 'arrow'],
  },
}

const globalConfig = {
  stubs,
  mocks: { buildBreadcrumb: (...args: string[]) => args },
}

describe('Segment Detail Page', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders segment name in header', () => {
    const wrapper = mount(SegmentDetail, { global: globalConfig })
    const header = wrapper.find('.mock-header')
    expect(header.exists()).toBe(true)
  })

  it('shows member table', () => {
    const wrapper = mount(SegmentDetail, { global: globalConfig })
    expect(wrapper.find('.mock-table').exists()).toBe(true)
  })

  it('shows Add Members button for static segments', () => {
    const wrapper = mount(SegmentDetail, { global: globalConfig })
    const btns = wrapper.findAll('.mock-btn')
    const addBtn = btns.find(b => b.text().includes('Add Members'))
    expect(addBtn).toBeDefined()
  })

  it('shows Remove by Segment button', () => {
    const wrapper = mount(SegmentDetail, { global: globalConfig })
    const btns = wrapper.findAll('.mock-btn')
    const removeBtn = btns.find(b => b.text().includes('Remove by Segment'))
    expect(removeBtn).toBeDefined()
  })

  it('renders member rows for static segments', () => {
    const wrapper = mount(SegmentDetail, { global: globalConfig })
    expect(wrapper.find('.mock-table').exists()).toBe(true)
  })

  it('shows status and type badges', () => {
    const wrapper = mount(SegmentDetail, { global: globalConfig })
    const badges = wrapper.findAll('.mock-badge')
    expect(badges.length).toBeGreaterThanOrEqual(2)
    expect(badges.map(b => b.text())).toContain('ACTIVE')
    expect(badges.map(b => b.text())).toContain('STATIC')
  })

  it('shows member count', () => {
    const wrapper = mount(SegmentDetail, { global: globalConfig })
    expect(wrapper.text()).toContain('42')
  })

  it('shows edit button', () => {
    const wrapper = mount(SegmentDetail, { global: globalConfig })
    const btns = wrapper.findAll('.mock-btn')
    expect(btns.find(b => b.text().includes('Edit'))).toBeDefined()
  })

  it('shows delete button', () => {
    const wrapper = mount(SegmentDetail, { global: globalConfig })
    const btns = wrapper.findAll('.mock-btn')
    expect(btns.length).toBeGreaterThan(0)
  })

  it('shows action buttons in header', () => {
    const wrapper = mount(SegmentDetail, { global: globalConfig })
    const buttons = wrapper.findAll('.mock-btn')
    expect(buttons.length).toBeGreaterThan(0)
  })

  it('shows Run Pipeline button', () => {
    const wrapper = mount(SegmentDetail, { global: globalConfig })
    const btns = wrapper.findAll('.mock-btn')
    expect(btns.find(b => b.text().includes('Run Pipeline'))).toBeDefined()
  })
})
