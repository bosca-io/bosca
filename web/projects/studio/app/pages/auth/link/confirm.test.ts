import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Confirm from './confirm.vue'

const linkConfirmPassword = vi.fn()
const linkRequestEmailProof = vi.fn()
const linkConfirmEmail = vi.fn()
const navigateTo = vi.fn()
const setFlash = vi.fn()

vi.stubGlobal('navigateTo', navigateTo)
// confirm.vue stashes a flash before its hard redirect into the app; the success
// confirmation can't ride an in-memory banner across the reload.
vi.stubGlobal('useFlash', () => ({ setFlash, takeFlash: vi.fn() }))

vi.mock('@bosca/auth-client-browser', () => ({
  useAuth: () => ({ auth: { linkConfirmPassword, linkRequestEmailProof, linkConfirmEmail } }),
}))

// `?token=` is the pending-link token; `?methods=` is the proof methods the
// backend offered for the colliding account; `?proof=` is the emailed magic-link.
const routeQuery: { token?: string; methods?: string | string[]; proof?: string } = {}
vi.stubGlobal('useRoute', () => ({ query: routeQuery }))

const stubs = {
  AuthFormCard: { template: '<div><slot /></div>' },
  AuthHeading: {
    template: '<div class="auth-heading"><slot /></div>',
    props: ['eyebrow', 'title', 'accent'],
  },
  AuthBanner: {
    template: '<div class="auth-banner" :data-tone="tone"><slot /></div>',
    props: ['tone'],
  },
  AuthDivider: { template: '<div class="auth-divider"><slot /></div>' },
  AuthInput: {
    template: '<label class="auth-input-wrap"><input class="auth-input" :type="type" :data-label="label" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>',
    props: ['modelValue', 'label', 'type', 'placeholder', 'accent', 'error'],
    emits: ['update:modelValue'],
  },
  AuthBtn: {
    template: '<button class="auth-btn" :type="type" :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>',
    props: ['primary', 'full', 'accent', 'loading', 'disabled', 'icon', 'type'],
    emits: ['click'],
  },
  NuxtLink: { template: '<a><slot /></a>', props: ['to'] },
}

function mountConfirm() {
  return mount(Confirm, { global: { stubs } })
}

function passwordInput(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('.auth-input').find((i) => i.attributes('data-label') === 'Password')
}

beforeEach(() => {
  linkConfirmPassword.mockReset()
  linkRequestEmailProof.mockReset()
  linkConfirmEmail.mockReset()
  navigateTo.mockReset()
  setFlash.mockReset()
  delete routeQuery.token
  delete routeQuery.methods
  delete routeQuery.proof
})

describe('confirm.vue', () => {
  it('hides the password field for an OAuth-only account (methods=EMAIL)', () => {
    routeQuery.token = 'tok'
    routeQuery.methods = 'EMAIL'

    const wrapper = mountConfirm()

    expect(passwordInput(wrapper)).toBeUndefined()
    expect(wrapper.text()).toContain('Email me a link to confirm')
    expect(wrapper.text()).toContain('signs in with a connected provider')
  })

  it('requests email proof (never password) for an OAuth-only account', async () => {
    routeQuery.token = 'tok'
    routeQuery.methods = 'EMAIL'

    const wrapper = mountConfirm()
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(linkRequestEmailProof).toHaveBeenCalledWith('tok')
    expect(linkConfirmPassword).not.toHaveBeenCalled()
    expect(wrapper.find('.auth-banner[data-tone="ok"]').text()).toContain('Check your inbox')
  })

  it('shows the password field and confirms with password when the account has one', async () => {
    routeQuery.token = 'tok'
    routeQuery.methods = 'PASSWORD,EMAIL'
    linkConfirmPassword.mockResolvedValueOnce(undefined)

    const wrapper = mountConfirm()
    const input = passwordInput(wrapper)
    expect(input).toBeDefined()
    // Password accounts still get the email fallback offered alongside the form.
    expect(wrapper.find('.auth-divider').exists()).toBe(true)

    await input!.setValue('s3cret')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(linkConfirmPassword).toHaveBeenCalledWith('tok', 's3cret')
    expect(setFlash).toHaveBeenCalledWith('Account linked — you’re signed in.')
    expect(navigateTo).toHaveBeenCalledWith('/', expect.objectContaining({ replace: true, external: true }))
  })

  it('falls back to showing the password field when methods are unknown', () => {
    routeQuery.token = 'tok'

    const wrapper = mountConfirm()

    expect(passwordInput(wrapper)).toBeDefined()
  })

  it('auto-confirms and goes home when an emailed proof token is present', async () => {
    routeQuery.proof = 'proof-tok'
    linkConfirmEmail.mockResolvedValueOnce(undefined)

    mountConfirm()
    await flushPromises()

    expect(linkConfirmEmail).toHaveBeenCalledWith('proof-tok')
    expect(setFlash).toHaveBeenCalledWith('Account linked — you’re signed in.')
    expect(navigateTo).toHaveBeenCalledWith('/', expect.objectContaining({ replace: true, external: true }))
  })
})
