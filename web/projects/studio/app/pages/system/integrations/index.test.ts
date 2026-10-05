import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import IntegrationsPage from './index.vue'

const configurations = ref<unknown>(null)

vi.stubGlobal('useGraphQL', () => ({
  useAsyncQuery: () => ({ data: configurations }),
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#2272f2' }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const globalConfig = {
  mocks: {
    buildBreadcrumb: (...parts: string[]) => parts,
  },
  stubs: {
    PageShell: { template: '<main><slot name="header" /><slot /></main>' },
    PageHeader: { template: '<header />' },
    NuxtLink: {
      props: ['to'],
      template: '<a :data-to="to"><slot /></a>',
    },
    Icon: { template: '<span />' },
    Badge: { template: '<span class="status"><slot /></span>' },
  },
}

beforeEach(() => {
  configurations.value = {
    sendgrid: { configuration: null },
    mailgun: {
      configuration: {
        id: 'mailgun-id',
        key: 'mailgun',
        value: { apiKey: 'secret', domain: 'mg.example.com' },
      },
    },
    push: { configuration: null },
    hubspot: { configuration: null },
    mux: { configuration: null },
    federation: { federation: { peers: [] } },
  }
})

describe('Integrations Page', () => {
  it('links to Mailgun configuration and reports complete credentials as connected', () => {
    const wrapper = mount(IntegrationsPage, { global: globalConfig })
    const card = wrapper.get('[data-to="/system/integrations/mailgun"]')

    expect(card.text()).toContain('Mailgun')
    expect(card.text()).toContain('Connected')
  })

  it('reports Mailgun as not configured when the sending domain is missing', () => {
    configurations.value = {
      ...(configurations.value as Record<string, unknown>),
      mailgun: {
        configuration: {
          id: 'mailgun-id',
          key: 'mailgun',
          value: { apiKey: 'secret' },
        },
      },
    }
    const wrapper = mount(IntegrationsPage, { global: globalConfig })
    const card = wrapper.get('[data-to="/system/integrations/mailgun"]')

    expect(card.text()).toContain('Not configured')
  })

  it('links to federation administration and reports registered peers as connected', () => {
    configurations.value = {
      ...(configurations.value as Record<string, unknown>),
      federation: { federation: { peers: [{ id: 'peer-id' }] } },
    }
    const wrapper = mount(IntegrationsPage, { global: globalConfig })
    const card = wrapper.get('[data-to="/system/integrations/federation"]')

    expect(card.text()).toContain('Federation')
    expect(card.text()).toContain('Connected')
  })
})
