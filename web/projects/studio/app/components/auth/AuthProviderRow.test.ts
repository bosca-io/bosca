import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import AuthProviderRow from './AuthProviderRow.vue'

vi.stubGlobal('useRequestURL', () => new URL('https://studio.example.com/auth/login'))

describe('AuthProviderRow', () => {
  it('preserves a gateway handoff through provider authentication', () => {
    const returnTo = '/auth/gateway?redirect=https%3A%2F%2Fwarehouse.example.com%2Fauth%2Fstudio%2Fcallback%3Fstate%3Dabc'
    const wrapper = mount(AuthProviderRow, {
      props: { returnTo },
      global: {
        stubs: {
          AuthIcon: { template: '<span />', props: ['name', 'size'] },
        },
      },
    })

    const providerUrl = new URL(
      wrapper.find('a.provider-btn').attributes('href')!,
      'https://studio.example.com',
    )
    const redirect = new URL(providerUrl.searchParams.get('redirect')!)
    expect(redirect.pathname).toBe('/auth/login')
    expect(redirect.searchParams.get('returnTo')).toBe(returnTo)
  })

  it('drops an unsafe provider return target', () => {
    const wrapper = mount(AuthProviderRow, {
      props: { returnTo: 'https://evil.example' },
      global: {
        stubs: {
          AuthIcon: { template: '<span />', props: ['name', 'size'] },
        },
      },
    })

    const providerUrl = new URL(
      wrapper.find('a.provider-btn').attributes('href')!,
      'https://studio.example.com',
    )
    const redirect = new URL(providerUrl.searchParams.get('redirect')!)
    expect(redirect.searchParams.has('returnTo')).toBe(false)
  })
})
