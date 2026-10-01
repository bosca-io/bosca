import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Forgot from './forgot.vue'

const forgotPassword = vi.fn()

// The real `useAuth` is a thin wrapper over Vue's `inject()`, which only works
// during component setup. Replicate that contract here — throw when called
// without an active component instance — so this suite genuinely guards against
// the regression of calling `useAuth()` inside an event handler (where it
// silently fails and no reset request is ever made).
vi.mock('@bosca/auth-client-browser', async () => {
  const { getCurrentInstance } = await import('vue')
  return {
    useAuth: () => {
      if (!getCurrentInstance()) {
        throw new Error('Auth not initialized: useAuth() called outside setup.')
      }
      return { auth: { forgotPassword } }
    },
  }
})

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
    template: '<label class="auth-input-wrap"><input class="auth-input" :data-label="label" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>',
    props: ['modelValue', 'label', 'type', 'placeholder', 'accent'],
    emits: ['update:modelValue'],
  },
  AuthBtn: {
    template: '<button class="auth-btn" :disabled="loading" @click="$emit(\'click\')"><slot /></button>',
    props: ['primary', 'full', 'accent', 'loading', 'icon'],
    emits: ['click'],
  },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  NuxtLink: { template: '<a><slot /></a>', props: ['to'] },
}

beforeEach(() => {
  forgotPassword.mockReset()
  forgotPassword.mockResolvedValue(undefined)
})

describe('forgot.vue', () => {
  it('requests a reset link for the entered email when the button is clicked', async () => {
    const wrapper = mount(Forgot, { global: { stubs } })

    await wrapper.find('.auth-input').setValue('user@example.com')
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(forgotPassword).toHaveBeenCalledWith('user@example.com')
    expect(wrapper.find('.auth-banner[data-tone="ok"]').exists()).toBe(true)
  })

  it('does not request a reset link when the email is empty', async () => {
    const wrapper = mount(Forgot, { global: { stubs } })

    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(forgotPassword).not.toHaveBeenCalled()
    expect(wrapper.find('.auth-banner[data-tone="ok"]').exists()).toBe(false)
  })

  it('still shows the neutral confirmation when the request fails (no account enumeration)', async () => {
    forgotPassword.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mount(Forgot, { global: { stubs } })

    await wrapper.find('.auth-input').setValue('user@example.com')
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(forgotPassword).toHaveBeenCalledWith('user@example.com')
    expect(wrapper.find('.auth-banner[data-tone="ok"]').exists()).toBe(true)
  })
})
