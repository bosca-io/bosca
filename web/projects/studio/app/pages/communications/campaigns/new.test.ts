import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import NewCampaign from './new.vue'

const mockMutation = vi.fn().mockResolvedValue({
  campaigns: { add: { id: 'new-campaign-id' } },
})
const mockPush = vi.fn()

vi.stubGlobal('useGraphQL', () => ({
  query: vi.fn(),
  mutation: mockMutation,
  useAsyncQuery: () => ({
    data: ref({
      segments: {
        all: [
          { id: 'seg-1', name: 'Everyone', memberCount: 1000, status: 'ACTIVE' },
          { id: 'seg-2', name: 'Premium', memberCount: 200, status: 'ACTIVE' },
        ],
      },
    }),
    refresh: vi.fn(),
    status: ref('success'),
  }),
  useSubscription: vi.fn(),
}))

vi.stubGlobal('useRouter', () => ({ push: mockPush }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#ff7ac6' }))
vi.stubGlobal('buildBreadcrumb', (...args: string[]) => args)
vi.stubGlobal('definePageMeta', vi.fn())

const stubs = {
  PageShell: { template: '<div><slot /><slot name="header" /></div>' },
  PageHeader: { template: '<div class="mock-header"><slot name="actions" /></div>', props: ['accent', 'breadcrumb', 'title', 'subtitle'] },
  SectionCard: { template: '<div class="mock-section"><slot /></div>', props: ['title', 'padded'] },
  Select: { template: '<select class="mock-select" />', props: ['modelValue', 'options', 'label'] },
  Switch: { template: '<span class="mock-switch" />', props: ['modelValue'] },
  Button: { template: '<button class="mock-btn" @click="$emit(\'click\')"><slot /></button>', props: ['size', 'icon', 'primary', 'accent', 'disabled'] },
  TextInput: { template: '<input />', props: ['modelValue', 'label', 'disabled'] },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  CampaignContentEditor: { template: '<div class="mock-content-editor" />', props: ['modelValue', 'channel'] },
}

const globalConfig = {
  stubs,
  mocks: {
    buildBreadcrumb: (...args: string[]) => args,
  },
}

describe('Communications Campaign Creation Page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders creation form', () => {
    const wrapper = mount(NewCampaign, { global: globalConfig })
    expect(wrapper.find('.mock-header').exists()).toBe(true)
    expect(wrapper.findAll('.mock-section').length).toBeGreaterThanOrEqual(2)
  })

  it('renders channel selector', () => {
    const wrapper = mount(NewCampaign, { global: globalConfig })
    expect(wrapper.find('.mock-select').exists()).toBe(true)
  })

  it('renders content editor component', () => {
    const wrapper = mount(NewCampaign, { global: globalConfig })
    expect(wrapper.find('.mock-content-editor').exists()).toBe(true)
  })

  it('renders segment search section', () => {
    const wrapper = mount(NewCampaign, { global: globalConfig })
    expect(wrapper.find('.mock-section').exists()).toBe(true)
  })

  it('renders schedule date input', () => {
    const wrapper = mount(NewCampaign, { global: globalConfig })
    const dateInputs = wrapper.findAll('input[type="datetime-local"]')
    expect(dateInputs.length).toBeGreaterThanOrEqual(1)
  })

  it('renders create button', () => {
    const wrapper = mount(NewCampaign, { global: globalConfig })
    const btns = wrapper.findAll('.mock-btn')
    const createBtn = btns.find((b) => b.text().includes('Create Campaign'))
    expect(createBtn).toBeDefined()
  })
})
