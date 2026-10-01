import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { EmailNotVerifiedError, InvalidCredentialsError, PrincipalNotVerifiedError } from '@bosca/auth-client-browser'
import Login from './login.vue'

const signInWithPassword = vi.fn()
const currentUser: { verified: boolean } = { verified: true }
const isAuthenticatedRef = { value: false }
const navigateTo = vi.fn()
// login.vue reads an OAuth link-challenge (token + offered proof methods) off the
// URL via auth.getLinkFromUrl() in onMounted; tests drive it via these refs
// (default "nothing present").
const linkTokenRef: { value: string | null } = { value: null }
const linkMethodsRef: { value: ('PASSWORD' | 'EMAIL')[] | null } = { value: null }

vi.stubGlobal('navigateTo', navigateTo)

// Keep the real exports (the typed error classes login.vue checks with
// `instanceof`) and only override the composable.
vi.mock('@bosca/auth-client-browser', async () => {
  const actual = await vi.importActual<typeof import('@bosca/auth-client-browser')>('@bosca/auth-client-browser')
  return {
    ...actual,
    useAuth: () => ({
      isAuthenticated: isAuthenticatedRef,
      auth: {
        getLinkFromUrl: () =>
          linkTokenRef.value ? { token: linkTokenRef.value, methods: linkMethodsRef.value } : null,
      },
    }),
  }
})

vi.stubGlobal('useNuxtApp', () => ({
  $auth: {
    signInWithPassword: (...args: unknown[]) => signInWithPassword(...args),
    get currentUser() { return currentUser },
  },
}))

const routeQuery: { unauthorized?: string; accountVerified?: string; returnTo?: string } = {}
vi.stubGlobal('useRoute', () => ({ query: routeQuery }))
vi.stubGlobal('useRequestURL', () => new URL('http://localhost:3000/auth/login'))

const stubs = {
  AuthFormCard: { template: '<div><slot /></div>' },
  AuthHeading: {
    template: '<div class="auth-heading"><slot /></div>',
    props: ['eyebrow', 'title', 'sub', 'accent'],
  },
  AuthBanner: {
    template: '<div class="auth-banner" :data-tone="tone"><slot /></div>',
    props: ['tone'],
  },
  AuthDivider: { template: '<div class="auth-divider"><slot /></div>' },
  AuthProviderRow: { template: '<div class="auth-provider-row" />', props: ['returnTo'] },
  AuthInput: {
    template: '<label class="auth-input-wrap"><input class="auth-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" :data-label="label" /><span v-if="error" class="auth-input-error">{{ error }}</span></label>',
    props: ['modelValue', 'label', 'type', 'placeholder', 'accent', 'error'],
    emits: ['update:modelValue'],
  },
  AuthBtn: {
    template: '<button class="auth-btn" :type="type" :disabled="loading" @click="$emit(\'click\')"><slot /></button>',
    props: ['primary', 'full', 'accent', 'loading', 'icon', 'type'],
    emits: ['click'],
  },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  NuxtLink: { template: '<a :data-to="to"><slot /></a>', props: ['to'] },
}

function mountLogin() {
  return mount(Login, { global: { stubs } })
}

beforeEach(() => {
  signInWithPassword.mockReset()
  navigateTo.mockReset()
  currentUser.verified = true
  isAuthenticatedRef.value = false
  linkTokenRef.value = null
  linkMethodsRef.value = null
  delete routeQuery.unauthorized
  delete routeQuery.accountVerified
  delete routeQuery.returnTo
})

describe('login.vue', () => {
  it('does not render the non-functional "Sign in with a passkey" button', () => {
    const wrapper = mountLogin()
    expect(wrapper.text()).not.toContain('Sign in with a passkey')
    expect(wrapper.find('.passkey-btn').exists()).toBe(false)
  })

  it('renders the Google provider row and an email/password form', () => {
    const wrapper = mountLogin()
    expect(wrapper.find('.auth-provider-row').exists()).toBe(true)
    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.findAll('.auth-input').length).toBeGreaterThanOrEqual(2)
  })

  it('blocks submit and shows an error when email is empty', async () => {
    const wrapper = mountLogin()
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(signInWithPassword).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Enter a valid email address.')
  })

  it('redirects verified users to the home page after successful sign-in', async () => {
    signInWithPassword.mockResolvedValueOnce(undefined)
    currentUser.verified = true

    const wrapper = mountLogin()
    const inputs = wrapper.findAll('.auth-input')
    await inputs[0]!.setValue('user@example.com')
    await inputs[1]!.setValue('hunter2')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(signInWithPassword).toHaveBeenCalledWith('user@example.com', 'hunter2', 'studio')
    expect(navigateTo).toHaveBeenCalledWith('/', expect.objectContaining({ replace: true, external: true }))
  })

  it('returns a verified password login to the Studio gateway handoff', async () => {
    routeQuery.returnTo = '/auth/gateway?redirect=https%3A%2F%2Fwarehouse.example.com%2Fauth%2Fstudio%2Fcallback%3Fstate%3Dabc'
    signInWithPassword.mockResolvedValueOnce(undefined)

    const wrapper = mountLogin()
    const inputs = wrapper.findAll('.auth-input')
    await inputs[0]!.setValue('user@example.com')
    await inputs[1]!.setValue('hunter2')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      routeQuery.returnTo,
      expect.objectContaining({ replace: true, external: true }),
    )
  })

  it('does not use an external post-login return target', async () => {
    routeQuery.returnTo = 'https://evil.example'
    signInWithPassword.mockResolvedValueOnce(undefined)

    const wrapper = mountLogin()
    const inputs = wrapper.findAll('.auth-input')
    await inputs[0]!.setValue('user@example.com')
    await inputs[1]!.setValue('hunter2')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      '/',
      expect.objectContaining({ replace: true, external: true }),
    )
  })

  it('redirects unverified users to /auth/verify with their email', async () => {
    signInWithPassword.mockResolvedValueOnce(undefined)
    currentUser.verified = false

    const wrapper = mountLogin()
    const inputs = wrapper.findAll('.auth-input')
    await inputs[0]!.setValue('unverified@example.com')
    await inputs[1]!.setValue('hunter2')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      '/auth/verify?email=unverified%40example.com',
      expect.objectContaining({ replace: true, external: true }),
    )
  })

  it('shows a friendly message for invalid credentials', async () => {
    signInWithPassword.mockRejectedValueOnce(new InvalidCredentialsError())

    const wrapper = mountLogin()
    const inputs = wrapper.findAll('.auth-input')
    await inputs[0]!.setValue('user@example.com')
    await inputs[1]!.setValue('wrong')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(navigateTo).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('The email or password you entered is incorrect.')
  })

  it('never surfaces a raw backend exception to the user', async () => {
    // Regression: the backend's "missing credentials" SecurityException used to
    // be rendered verbatim as "Exception while fetching data … : missing
    // credentials". Any unrecognized error must fall back to generic copy.
    signInWithPassword.mockRejectedValueOnce(
      new Error('Exception while fetching data (/security/login/password) : missing credentials'),
    )

    const wrapper = mountLogin()
    const inputs = wrapper.findAll('.auth-input')
    await inputs[0]!.setValue('user@example.com')
    await inputs[1]!.setValue('wrong')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(navigateTo).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('Exception while fetching data')
    expect(wrapper.text()).not.toContain('missing credentials')
    expect(wrapper.text()).toContain('Something went wrong while signing you in.')
  })

  it('routes an unverified account to the verification page instead of erroring', async () => {
    signInWithPassword.mockRejectedValueOnce(new EmailNotVerifiedError())

    const wrapper = mountLogin()
    const inputs = wrapper.findAll('.auth-input')
    await inputs[0]!.setValue('unverified@example.com')
    await inputs[1]!.setValue('hunter2')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      '/auth/verify?email=unverified%40example.com',
      expect.objectContaining({ replace: true, external: true }),
    )
    expect(wrapper.find('.auth-banner[data-tone="err"]').exists()).toBe(false)
  })

  it('routes an unverified principal (the account-level login gate) to the verification page', async () => {
    signInWithPassword.mockRejectedValueOnce(new PrincipalNotVerifiedError())

    const wrapper = mountLogin()
    const inputs = wrapper.findAll('.auth-input')
    await inputs[0]!.setValue('unverified@example.com')
    await inputs[1]!.setValue('hunter2')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      '/auth/verify?email=unverified%40example.com',
      expect.objectContaining({ replace: true, external: true }),
    )
    expect(wrapper.find('.auth-banner[data-tone="err"]').exists()).toBe(false)
  })

  it('routes an OAuth account collision to link-confirm, forwarding the offered proof methods', async () => {
    // An OAuth sign-in whose email already belongs to a verified account bounces
    // back with `?link=` + `?methods=`; an OAuth-only account exposes EMAIL only,
    // so the confirm page must receive that and never show a password field.
    linkTokenRef.value = 'pl-tok'
    linkMethodsRef.value = ['EMAIL']

    mountLogin()
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      '/auth/link/confirm?token=pl-tok&methods=EMAIL',
      expect.objectContaining({ replace: true }),
    )
  })

  it('does not show the unauthorized notice when the query param is absent', () => {
    const wrapper = mountLogin()
    expect(wrapper.find('.auth-banner[data-tone="warn"]').exists()).toBe(false)
  })

  it('shows a persistent success notice when redirected with ?accountVerified', () => {
    routeQuery.accountVerified = 'true'

    const wrapper = mountLogin()
    const banner = wrapper.find('.auth-banner[data-tone="ok"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('Email verified')
  })

  it('does not show the verified notice when the query param is absent', () => {
    const wrapper = mountLogin()
    expect(wrapper.find('.auth-banner[data-tone="ok"]').exists()).toBe(false)
  })

  it('shows a warning notice when redirected with ?unauthorized', () => {
    routeQuery.unauthorized = 'true'

    const wrapper = mountLogin()
    const banner = wrapper.find('.auth-banner[data-tone="warn"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain("You're not authorized to view that page.")
    expect(banner.text()).toContain('editor permissions')
  })

  it('does not redirect an authenticated user away when they were rejected', () => {
    routeQuery.unauthorized = 'true'
    isAuthenticatedRef.value = true

    mountLogin()
    expect(navigateTo).not.toHaveBeenCalled()
  })

  it('replaces the unauthorized notice with the error banner after a failed sign-in', async () => {
    routeQuery.unauthorized = 'true'
    signInWithPassword.mockRejectedValueOnce(new InvalidCredentialsError())

    const wrapper = mountLogin()
    const inputs = wrapper.findAll('.auth-input')
    await inputs[0]!.setValue('user@example.com')
    await inputs[1]!.setValue('wrong')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.find('.auth-banner[data-tone="warn"]').exists()).toBe(false)
    expect(wrapper.find('.auth-banner[data-tone="err"]').exists()).toBe(true)
  })
})
