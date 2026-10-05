import { beforeEach, describe, expect, it, vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import ProfileDetail from './[id].vue'
import ProfileFlagAssignments from '~/components/experiments/ProfileFlagAssignments.vue'

const mockQuery = vi.fn()
const mockMutation = vi.fn()
const mockToastError = vi.fn()

const profile = {
  id: 'profile-1',
  name: 'Ada Lovelace',
  slug: 'ada',
  type: 'bosca/v-profile-generic',
  visibility: 'USER',
  created: '2026-08-01T00:00:00Z',
  modified: '2026-08-01T00:00:00Z',
  deletedAt: null,
  lastLogin: null,
  isPrimary: true,
  organizations: [],
  attributes: [],
  principal: {
    id: 'principal-1',
    verified: true,
    profiles: [{ id: 'profile-1', name: 'Ada Lovelace' }],
    credentials: [],
    groups: [
      { id: 'group-existing', name: 'editors', description: 'Editors', type: 'SYSTEM' },
    ],
  },
}

vi.stubGlobal('useGraphQL', () => ({
  query: mockQuery,
  mutation: mockMutation,
  useAsyncQuery: (key: string) => ({
    data: ref(key === 'profile-detail' ? { profiles: { profile } } : null),
    status: ref('success'),
    refresh: vi.fn(),
  }),
}))
vi.stubGlobal('useRoute', () => ({ params: { id: 'profile-1' } }))
vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#da9e30' }))
vi.stubGlobal('useToast', () => ({ success: vi.fn(), error: mockToastError }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const SelectStub = defineComponent({
  name: 'SelectStub',
  props: {
    placeholder: String,
    onSearch: Function,
  },
  template: '<div class="select-stub" />',
})

function mountPage() {
  return shallowMount(ProfileDetail, {
    global: {
      stubs: {
        PageShell: { template: '<main><slot name="header" /><slot /></main>' },
        PageHeader: {
          template: '<div><button class="security-tab" @click="$emit(\'tab\', \'Security\')" /><button class="flags-tab" @click="$emit(\'tab\', \'Feature Flags\')" /></div>',
        },
        SectionCard: { template: '<section><slot name="right" /><slot /></section>' },
        Select: SelectStub,
      },
      mocks: { buildBreadcrumb: (...parts: string[]) => parts },
    },
  })
}

describe('Profile Detail Page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('opens feature flag editing with the linked principal identity', async () => {
    const wrapper = mountPage()
    expect(wrapper.findComponent(ProfileFlagAssignments).exists()).toBe(false)
    await wrapper.find('.flags-tab').trigger('click')
    expect(wrapper.findComponent(ProfileFlagAssignments).props('principalId')).toBe('principal-1')
    wrapper.unmount()
  })

  it('waits for group search results and omits groups already assigned to the principal', async () => {
    mockQuery.mockResolvedValue({
      security: {
        groups: {
          find: [
            { id: 'group-existing', name: 'editors', description: 'Editors', type: 'SYSTEM' },
            { id: 'group-messaging', name: 'messaging', description: 'Messaging', type: 'SYSTEM' },
          ],
        },
      },
    })
    const wrapper = mountPage()
    await wrapper.find('.security-tab').trigger('click')

    const groupSelect = wrapper.findAllComponents(SelectStub)
      .find((select) => select.props('placeholder') === 'Search groups…')
    expect(groupSelect).toBeDefined()

    const results = await groupSelect!.props('onSearch')!('messaging')

    expect(mockQuery).toHaveBeenCalledOnce()
    expect(mockQuery.mock.calls[0]?.[1]).toEqual({ nameOrDescription: 'messaging' })
    expect(results).toEqual([
      { value: 'group-messaging', label: 'Messaging' },
    ])
  })
})
