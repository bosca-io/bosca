import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ResetPassword from './reset-password.vue'

const resetPassword = vi.fn()
const navigateTo = vi.fn()

vi.stubGlobal('navigateTo', navigateTo)

// The real `useAuth` is a thin wrapper over Vue's `inject()`, which only resolves
// during synchronous component setup. Replicate that contract here — throw when
// called without an active component instance — so this suite genuinely guards
// against the regression of calling `useAuth()` inside the async submit handler
// (where inject returns null and `auth.resetPassword` is never reached).
vi.mock('@bosca/auth-client-browser', async () => {
  const { getCurrentInstance } = await import('vue')
  return {
    useAuth: () => {
      if (!getCurrentInstance()) {
        throw new Error('Auth not initialized: useAuth() called outside setup.')
      }
      return { auth: { resetPassword } }
    },
  }
})

const routeQuery: { token?: string } = {}
vi.stubGlobal('useRoute', () => ({ query: routeQuery }))

const stubs = {
  AuthFormCard: { template: '<div><slot /></div>' },
  AuthHeading: {
    template: '<div><slot /></div>',
    props: ['eyebrow', 'title', 'sub', 'accent'],
  },
  AuthBanner: {
    template: '<div class="auth-banner" :data-tone="tone"><slot /></div>',
    props: ['tone'],
  },
  AuthInput: {
    template: '<label class="auth-input-wrap"><input class="auth-input" :data-label="label" :data-error="error" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>',
    props: ['modelValue', 'label', 'type', 'accent', 'error'],
    emits: ['update:modelValue'],
  },
  AuthBtn: {
    template: '<button class="auth-btn" :disabled="loading" @click="$emit(\'click\')"><slot /></button>',
    props: ['primary', 'full', 'accent', 'loading'],
    emits: ['click'],
  },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
}

function mountReset() {
  return mount(ResetPassword, { global: { stubs } })
}

function setPasswords(wrapper: ReturnType<typeof mountReset>, next: string, confirm: string) {
  return Promise.all([
    wrapper.find('[data-label="New password"]').setValue(next),
    wrapper.find('[data-label="Confirm password"]').setValue(confirm),
  ])
}

beforeEach(() => {
  resetPassword.mockReset()
  resetPassword.mockResolvedValue(undefined)
  navigateTo.mockReset()
  delete routeQuery.token
})

describe('reset-password.vue', () => {
  it('resets the password with the URL token and redirects to sign in on success', async () => {
    routeQuery.token = 'reset-token'
    const wrapper = mountReset()

    await setPasswords(wrapper, 'a-strong-passw0rd!', 'a-strong-passw0rd!')
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(resetPassword).toHaveBeenCalledWith('reset-token', 'a-strong-passw0rd!')
    expect(navigateTo).toHaveBeenCalledWith('/auth/login')
    expect(wrapper.find('.auth-banner[data-tone="ok"]').exists()).toBe(true)
  })

  it('shows a mismatch error and never submits when the passwords differ', async () => {
    routeQuery.token = 'reset-token'
    const wrapper = mountReset()

    await setPasswords(wrapper, 'a-strong-passw0rd!', 'different-passw0rd!')
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(resetPassword).not.toHaveBeenCalled()
    expect(navigateTo).not.toHaveBeenCalled()
    expect(wrapper.find('[data-label="Confirm password"]').attributes('data-error')).toBe("Passwords don't match")
  })

  it('surfaces a missing-token error and never submits when no ?token= is present', async () => {
    const wrapper = mountReset()

    await setPasswords(wrapper, 'a-strong-passw0rd!', 'a-strong-passw0rd!')
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(resetPassword).not.toHaveBeenCalled()
    expect(navigateTo).not.toHaveBeenCalled()
    const banner = wrapper.find('.auth-banner[data-tone="err"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('Missing reset token')
  })

  it('shows an error banner and stays on the page when the reset request fails', async () => {
    routeQuery.token = 'reset-token'
    resetPassword.mockRejectedValueOnce(new Error('Token expired'))
    const wrapper = mountReset()

    await setPasswords(wrapper, 'a-strong-passw0rd!', 'a-strong-passw0rd!')
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(resetPassword).toHaveBeenCalledWith('reset-token', 'a-strong-passw0rd!')
    expect(navigateTo).not.toHaveBeenCalled()
    const banner = wrapper.find('.auth-banner[data-tone="err"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('Token expired')
  })
})
