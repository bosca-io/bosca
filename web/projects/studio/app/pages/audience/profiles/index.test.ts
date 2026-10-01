import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ProfilesPage from './index.vue'

const profile = {
  id: 'profile-1',
  name: 'Ada Lovelace',
  type: 'bosca/v-profile-generic',
  created: '2026-07-28T12:00:00Z',
  lastLogin: null,
  deletedAt: null,
  organizations: [],
  attributes: [
    {
      typeId: 'bosca.profiles.email',
      attributes: { email: 'ada.profile@example.com' },
    },
  ],
  principal: {
    verified: true,
    credentials: [
      { type: 'PASSWORD', identifier: 'ada.credential@example.com' },
    ],
  },
}

let currentProfile = profile

vi.stubGlobal('useGraphQL', () => ({
  mutation: vi.fn(),
  useAsyncQuery: () => ({
    data: ref({
      search: {
        search: {
          documents: [{ profile: currentProfile }],
          estimatedHits: 1,
        },
      },
    }),
    status: ref('success'),
  }),
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#ff7ac6' }))
vi.stubGlobal('useDashboardStats', () => ({ tiles: [] }))
vi.stubGlobal('buildBreadcrumb', (...args: string[]) => args)

const globalConfig = {
  stubs: {
    PageShell: { template: '<div><slot name="header" /><slot /></div>' },
    PageHeader: { template: '<header><slot name="actions" /></header>' },
    SectionCard: { template: '<section><slot name="right" /><slot /></section>' },
    SearchInput: { template: '<input />' },
    GlassTable: {
      props: ['rows'],
      template: `
        <div>
          <div v-for="row in rows" :key="row.id">
            <slot name="col-name" :row="row" />
          </div>
        </div>
      `,
    },
    Avatar: { template: '<span />' },
    Button: { template: '<button><slot /></button>' },
    Pagination: { template: '<div />' },
    StatGrid: { template: '<div><slot /></div>' },
    StatTile: { template: '<div />' },
    Modal: { template: '<div><slot /></div>' },
    TextInput: { template: '<input />' },
    Select: { template: '<select />' },
  },
  mocks: {
    buildBreadcrumb: (...args: string[]) => args,
  },
}

describe('Profiles Page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    currentProfile = profile
  })

  it('shows the profile email attribute instead of a principal credential', () => {
    const wrapper = mount(ProfilesPage, { global: globalConfig })

    expect(wrapper.find('.profile-email').text()).toBe('ada.profile@example.com')
    expect(wrapper.text()).not.toContain('ada.credential@example.com')
  })

  it('does not substitute a principal credential when the email attribute is absent', () => {
    currentProfile = { ...profile, attributes: [] }

    const wrapper = mount(ProfilesPage, { global: globalConfig })

    expect(wrapper.find('.profile-email').text()).toBe('')
    expect(wrapper.text()).not.toContain('ada.credential@example.com')
  })
})
