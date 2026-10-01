import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import GatewayAuth from './gateway.vue'

const navigateTo = vi.fn()
const isAuthenticated = { value: false }
const route = {
  fullPath: '/auth/gateway?redirect=https%3A%2F%2Fwarehouse.example.com%2Fauth%2Fstudio%2Fcallback%3Fstate%3Dstate-123',
  query: {
    redirect: 'https://warehouse.example.com/auth/studio/callback?state=state-123',
  } as Record<string, string>,
}
vi.stubGlobal('navigateTo', navigateTo)
vi.stubGlobal('useRoute', () => route)

vi.mock('@bosca/auth-client-browser', () => ({
  useAuth: () => ({ isAuthenticated }),
}))

const stubs = {
  AuthFormCard: { template: '<div><slot /></div>' },
  AuthHeading: { template: '<div />', props: ['eyebrow', 'title', 'sub', 'accent'] },
  AuthBanner: {
    template: '<div class="auth-banner" :data-tone="tone"><slot /></div>',
    props: ['tone'],
  },
}

beforeEach(() => {
  navigateTo.mockReset()
  isAuthenticated.value = false
  route.query.redirect = 'https://warehouse.example.com/auth/studio/callback?state=state-123'
})

describe('gateway auth handoff', () => {
  it('sends an unauthenticated visitor through Studio login and back to the handoff', async () => {
    mount(GatewayAuth, { global: { stubs } })
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      expect.stringContaining('/auth/login?returnTo=%2Fauth%2Fgateway'),
      expect.objectContaining({ replace: true, external: true }),
    )
  })

  it('sends an existing Studio session to the server-owned exchange redirect', async () => {
    isAuthenticated.value = true

    mount(GatewayAuth, { global: { stubs } })
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      '/api/v1/security/exchange-token?redirect=https%3A%2F%2Fwarehouse.example.com%2Fauth%2Fstudio%2Fcallback%3Fstate%3Dstate-123',
      expect.objectContaining({ replace: true, external: true }),
    )
  })

  it('passes callback origin authorization to the server redirect endpoint', async () => {
    isAuthenticated.value = true
    route.query.redirect = 'https://evil.example/auth/studio/callback?state=state-123'

    mount(GatewayAuth, { global: { stubs } })
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      '/api/v1/security/exchange-token?redirect=https%3A%2F%2Fevil.example%2Fauth%2Fstudio%2Fcallback%3Fstate%3Dstate-123',
      expect.objectContaining({ replace: true, external: true }),
    )
  })

})
