import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import Welcome from './welcome.vue'

// Controllable access state, mirroring what usePersonas exposes to the page.
const isAdmin = ref(false)
const hasPersonas = ref(false)
const ready = ref(false)
const visibleSubsystems = ref<{ id: string }[]>([])
const navigateTo = vi.fn()

vi.stubGlobal('navigateTo', navigateTo)
vi.stubGlobal('usePersonas', () => ({ isAdmin, hasPersonas, ready, visibleSubsystems }))
vi.stubGlobal('useSubsystems', () => ({ defaultPage: () => 'dashboard' }))
vi.stubGlobal('useBoscaForms', () => ({ fetchSchema: vi.fn().mockResolvedValue(null) }))

vi.mock('@bosca/auth-client-browser', () => ({
  useAuth: () => ({ profile: ref(null), auth: { signOut: vi.fn() } }),
}))

const stubs = {
  BoscaMark: { template: '<span class="bosca-mark" />', props: ['size'] },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  BoscaForm: { template: '<div class="bosca-form" />', props: ['schemaKey', 'mode', 'submitLabel'] },
  // Render the default slot so card contents are assertable.
  ClientOnly: { template: '<div><slot /></div>' },
}

function mountWelcome() {
  return mount(Welcome, { global: { stubs } })
}

beforeEach(() => {
  isAdmin.value = false
  hasPersonas.value = false
  ready.value = false
  visibleSubsystems.value = []
  navigateTo.mockReset()
})

describe('welcome.vue', () => {
  it('shows a neutral loader (not the "no access" message) before access resolves', async () => {
    ready.value = false

    const wrapper = mountWelcome()
    await flushPromises()

    expect(wrapper.find('.welcome-resolving').exists()).toBe(true)
    expect(wrapper.find('.welcome-card').exists()).toBe(false)
    expect(wrapper.text()).not.toContain("you don't have access")
  })

  it('shows the request-access card once resolved as a genuine no-access user', async () => {
    ready.value = true
    isAdmin.value = false
    hasPersonas.value = false

    const wrapper = mountWelcome()
    await flushPromises()

    expect(wrapper.find('.welcome-card').exists()).toBe(true)
    expect(wrapper.text()).toContain("you don't have access")
    expect(navigateTo).not.toHaveBeenCalled()
  })

  it('redirects an admin to the home hub and never renders the no-access card', async () => {
    ready.value = true
    isAdmin.value = true

    const wrapper = mountWelcome()
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      '/',
      expect.objectContaining({ replace: true }),
    )
    expect(wrapper.find('.welcome-card').exists()).toBe(false)
    expect(wrapper.text()).not.toContain("you don't have access")
  })
})
