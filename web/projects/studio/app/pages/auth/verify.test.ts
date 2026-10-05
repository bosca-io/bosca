import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { AccountLinkRequiredError } from '@bosca/auth-client-browser'
import Verify from './verify.vue'

const verifyEmail = vi.fn()
const resendVerification = vi.fn()
const navigateTo = vi.fn()

vi.stubGlobal('navigateTo', navigateTo)

vi.mock('@bosca/auth-client-browser', async () => {
  // Keep the real exports (notably `AccountLinkRequiredError`, which verify.vue
  // checks with `instanceof` in its catch block) and only override useAuth.
  const actual = await vi.importActual<typeof import('@bosca/auth-client-browser')>('@bosca/auth-client-browser')
  return {
    ...actual,
    useAuth: () => ({ auth: { verifyEmail, resendVerification } }),
  }
})

const routeQuery: { email?: string; token?: string } = {}
vi.stubGlobal('useRoute', () => ({ query: routeQuery }))

const stubs = {
  AuthFormCard: { template: '<div class="auth-form-card"><slot /></div>' },
  AuthHeading: {
    template: '<div class="auth-heading"><slot /></div>',
    props: ['eyebrow', 'title', 'accent'],
  },
  AuthBanner: {
    template: '<div class="auth-banner" :data-tone="tone"><slot /></div>',
    props: ['tone'],
  },
  AuthBtn: {
    template: '<button class="auth-btn" :disabled="disabled || loading" @click="$emit(\'click\')"><slot /></button>',
    props: ['primary', 'full', 'accent', 'loading', 'disabled', 'icon'],
    emits: ['click'],
  },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  NuxtLink: { template: '<a><slot /></a>', props: ['to'] },
}

function mountVerify() {
  return mount(Verify, { global: { stubs } })
}

beforeEach(() => {
  verifyEmail.mockReset()
  resendVerification.mockReset()
  navigateTo.mockReset()
  delete routeQuery.email
  delete routeQuery.token
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('verify.vue', () => {
  it('renders the email from the query string, not a hardcoded address', () => {
    routeQuery.email = 'real-user@example.com'
    const wrapper = mountVerify()

    expect(wrapper.text()).toContain('real-user@example.com')
    expect(wrapper.text()).not.toContain('unrelated@example.com')
  })

  it('falls back to a generic message when no email is in the query', () => {
    const wrapper = mountVerify()

    expect(wrapper.text()).toContain('the email you signed up with')
    expect(wrapper.text()).not.toContain('unrelated@example.com')
  })

  it('does not render a 6-digit PIN input (backend uses URL token)', () => {
    routeQuery.email = 'user@example.com'
    const wrapper = mountVerify()

    expect(wrapper.findAll('input[inputmode="numeric"]')).toHaveLength(0)
    expect(wrapper.findAll('.code-digit')).toHaveLength(0)
  })

  it('auto-verifies and hands off to login with a success flag when ?token= is valid', async () => {
    routeQuery.email = 'user@example.com'
    routeQuery.token = 'good-token'
    verifyEmail.mockResolvedValueOnce(undefined)

    mountVerify()
    await flushPromises()

    // The confirmation is rendered on the login page (persisted via the query flag),
    // not flashed here as this page unmounts.
    expect(verifyEmail).toHaveBeenCalledWith('good-token')
    expect(navigateTo).toHaveBeenCalledWith('/auth/login?accountVerified=true')
  })

  it('routes an account collision into the link-confirm page with the proof methods', async () => {
    // Verifying a brand-new email can reveal it already belongs to a verified
    // account; route into the proof challenge carrying the offered methods so the
    // confirm page only shows proofs that can succeed.
    routeQuery.email = 'user@example.com'
    routeQuery.token = 'verify-token'
    verifyEmail.mockRejectedValueOnce(new AccountLinkRequiredError('pl-tok', ['EMAIL']))

    mountVerify()
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      '/auth/link/confirm?token=pl-tok&methods=EMAIL',
      expect.objectContaining({ replace: true }),
    )
  })

  it('shows an error banner when verification with ?token= fails', async () => {
    routeQuery.email = 'user@example.com'
    routeQuery.token = 'bad-token'
    verifyEmail.mockRejectedValueOnce(new Error('Token expired'))

    const wrapper = mountVerify()
    await flushPromises()

    expect(navigateTo).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Token expired')
  })

  it('does not auto-verify when no ?token= query is present', async () => {
    routeQuery.email = 'user@example.com'
    mountVerify()
    await flushPromises()

    expect(verifyEmail).not.toHaveBeenCalled()
  })

  it('resends the verification email using the query email and starts a 30s cooldown', async () => {
    routeQuery.email = 'user@example.com'
    resendVerification.mockResolvedValueOnce(undefined)

    const wrapper = mountVerify()
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(resendVerification).toHaveBeenCalledWith('user@example.com')
    expect(wrapper.text()).toContain('Verification email sent')
    expect(wrapper.text()).toContain('Resend in 30s')

    vi.advanceTimersByTime(30_000)
    await nextTick()
    expect(wrapper.text()).toContain('Resend verification email')
  })

  it('ignores rapid double-clicks while a resend is in flight', async () => {
    routeQuery.email = 'user@example.com'
    let resolveResend: () => void = () => {}
    resendVerification.mockImplementationOnce(() => new Promise<void>((r) => { resolveResend = r }))

    const wrapper = mountVerify()
    const btn = wrapper.find('button.auth-btn')
    await btn.trigger('click')
    await btn.trigger('click')
    await btn.trigger('click')

    expect(resendVerification).toHaveBeenCalledTimes(1)

    resolveResend()
    await flushPromises()
  })

  it('does not start a cooldown when the resend request fails', async () => {
    routeQuery.email = 'user@example.com'
    resendVerification.mockRejectedValueOnce(new Error('Server unavailable'))

    const wrapper = mountVerify()
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Server unavailable')
    expect(wrapper.text()).toContain('Resend verification email')
    expect(wrapper.text()).not.toContain('Resend in ')
  })

  it('disables the resend button and surfaces an error when no email is in the query', async () => {
    const wrapper = mountVerify()
    const btn = wrapper.find('button.auth-btn')

    expect(btn.attributes('disabled')).toBeDefined()

    // Force-click via the component (button is disabled but click handler should also no-op)
    await btn.trigger('click')
    await flushPromises()

    expect(resendVerification).not.toHaveBeenCalled()
  })
})
