import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import HomeLauncher from './HomeLauncher.vue'

// Minimal subsystems with real ids so the imported groupSubsystems() buckets
// them into their actual categories (cms → Content & Experience, analytics →
// Measure). Only the fields the launcher renders are populated.
const visibleSubsystems = ref<Array<Record<string, unknown>>>([])
const navigateTo = vi.fn()
const DOCS_URL = 'https://docs.example.test/studio'

vi.stubGlobal('navigateTo', navigateTo)
vi.stubGlobal('usePersonas', () => ({ visibleSubsystems }))
vi.stubGlobal('useSubsystems', () => ({ defaultPage: (id: string) => (id === 'analytics' ? 'dashboard' : 'collections') }))
vi.stubGlobal('useRuntimeConfig', () => ({ public: { docsUrl: DOCS_URL } }))
vi.stubGlobal('useLastSubsystem', () => ({ recents: () => [] }))

vi.mock('@bosca/auth-client-browser', () => ({
  useAuth: () => ({ profile: ref(null), auth: { signOut: vi.fn() } }),
}))

const stubs = {
  BoscaMark: { template: '<span class="bosca-mark" />', props: ['size'] },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  NuxtLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
  // Render the default slot so client-only content is assertable.
  ClientOnly: { template: '<div><slot /></div>' },
}

function cms() {
  return { id: 'cms', label: 'CMS', sub: 'Manage collections and media', icon: 'library', accent: '#f7828c', nav: [] }
}
function analytics() {
  return { id: 'analytics', label: 'Analytics', sub: 'Track events and insights', icon: 'dashboard', accent: '#268e71', nav: [] }
}

function mountHome() {
  return mount(HomeLauncher, { global: { stubs } })
}

beforeEach(() => {
  visibleSubsystems.value = [cms(), analytics()]
  navigateTo.mockReset()
})

describe('HomeLauncher', () => {
  it('renders a card for every visible subsystem, grouped by category', () => {
    const wrapper = mountHome()

    const cards = wrapper.findAll('.home-card')
    expect(cards).toHaveLength(2)

    const text = wrapper.text()
    expect(text).toContain('CMS')
    expect(text).toContain('Manage collections and media')
    expect(text).toContain('Analytics')
    // Category labels come from groupSubsystems().
    expect(text).toContain('Content & Experience')
    expect(text).toContain('Measure')
  })

  it('links the documentation action to the configured docs URL in a new tab', () => {
    const wrapper = mountHome()

    const docs = wrapper.findAll('a').find(a => a.attributes('href') === DOCS_URL)
    expect(docs).toBeTruthy()
    expect(docs!.attributes('target')).toBe('_blank')
    expect(docs!.attributes('rel')).toContain('noopener')
  })

  it('navigates into a subsystem default page when its card is clicked', async () => {
    const wrapper = mountHome()

    const analyticsCard = wrapper.findAll('.home-card').find(c => c.text().includes('Analytics'))
    await analyticsCard!.trigger('click')

    expect(navigateTo).toHaveBeenCalledWith('/analytics/dashboard')
  })

  it('points the Bosca Studio wordmark at the home hub', () => {
    const wrapper = mountHome()
    const brand = wrapper.find('.home-brand')
    expect(brand.attributes('href')).toBe('/')
  })

  it('signs out and returns to the login page', async () => {
    const wrapper = mountHome()

    const signOut = wrapper.findAll('button').find(b => b.text().includes('Sign out'))
    expect(signOut).toBeTruthy()
    await signOut!.trigger('click')
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith('/auth/login')
  })
})
