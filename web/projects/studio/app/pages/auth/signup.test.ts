import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { AccountLinkRequiredError, EmailAlreadyRegisteredError } from '@bosca/auth-client-browser'
import Signup from './signup.vue'

const signUp = vi.fn()
const signInWithPassword = vi.fn()
const navigateTo = vi.fn()
const routeQuery: { returnTo?: string } = {}
const publicConfig = { termsUrl: '', privacyUrl: '' }

vi.stubGlobal('navigateTo', navigateTo)
vi.stubGlobal('useRoute', () => ({ query: routeQuery }))
vi.stubGlobal('useRuntimeConfig', () => ({ public: publicConfig }))

vi.mock('@bosca/auth-client-browser', async () => {
  // Keep the real exports (notably `AccountLinkRequiredError`, which the page
  // references in its catch block via `instanceof`) and only override useAuth.
  const actual = await vi.importActual<typeof import('@bosca/auth-client-browser')>('@bosca/auth-client-browser')
  return {
    ...actual,
    useAuth: () => ({ auth: { signUp, signInWithPassword } }),
  }
})

const stubs = {
  AuthFormCard: { template: '<div><slot /></div>', props: ['width'] },
  AuthHeading: {
    template: '<div><slot /></div>',
    props: ['eyebrow', 'title', 'accent'],
  },
  AuthBanner: {
    template: '<div class="auth-banner" :data-tone="tone"><slot /></div>',
    props: ['tone'],
  },
  AuthDivider: { template: '<div><slot /></div>' },
  AuthProviderRow: { template: '<div class="auth-provider-row" />', props: ['returnTo'] },
  AuthInput: {
    template: '<label class="auth-input-wrap"><input class="auth-input" :data-label="label" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /><span v-if="error" class="auth-input-error">{{ error }}</span></label>',
    props: ['modelValue', 'label', 'type', 'placeholder', 'accent', 'error'],
    emits: ['update:modelValue'],
  },
  AuthBtn: {
    template: '<button class="auth-btn" :disabled="loading" @click="$emit(\'click\')"><slot /></button>',
    props: ['primary', 'full', 'accent', 'loading', 'icon'],
    emits: ['click'],
  },
  NuxtLink: { template: '<a><slot /></a>', props: ['to'] },
}

function getInput(wrapper: ReturnType<typeof mount>, label: string) {
  const el = wrapper.findAll('.auth-input').find((i) => i.attributes('data-label') === label)
  if (!el) throw new Error(`No input with label ${label}`)
  return el
}

function agreeToTerms(wrapper: ReturnType<typeof mount>) {
  return wrapper.find('input[type="checkbox"]').setValue(true)
}

beforeEach(() => {
  signUp.mockReset()
  signInWithPassword.mockReset()
  signInWithPassword.mockResolvedValue(undefined)
  navigateTo.mockReset()
  delete routeQuery.returnTo
  publicConfig.termsUrl = 'https://legal.example.com/terms'
  publicConfig.privacyUrl = 'https://legal.example.com/privacy'
})

describe('signup.vue', () => {
  it('only shows the Google provider row (no Facebook/Apple/Microsoft/GitHub)', () => {
    const wrapper = mount(Signup, { global: { stubs } })
    expect(wrapper.find('.auth-provider-row').exists()).toBe(true)
    const text = wrapper.text()
    expect(text).not.toMatch(/Facebook/i)
    expect(text).not.toMatch(/Apple/i)
    expect(text).not.toMatch(/Microsoft/i)
    expect(text).not.toMatch(/GitHub/i)
  })

  it('blocks signup with an empty email', async () => {
    const wrapper = mount(Signup, { global: { stubs } })
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(signUp).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Enter a valid email address.')
  })

  it('leaves the terms checkbox unchecked by default', () => {
    const wrapper = mount(Signup, { global: { stubs } })
    const checkbox = wrapper.find('input[type="checkbox"]').element as HTMLInputElement
    expect(checkbox.checked).toBe(false)
  })

  it('blocks signup if the terms checkbox is unchecked', async () => {
    const wrapper = mount(Signup, { global: { stubs } })
    await getInput(wrapper, 'Email').setValue('user@example.com')
    await wrapper.find('input[type="checkbox"]').setValue(false)
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(signUp).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('You must agree to the Terms and Privacy Policy.')
  })

  it('links the configured terms and privacy documents in a new tab', () => {
    const wrapper = mount(Signup, { global: { stubs } })
    const links = wrapper.findAll('.terms-check a')
    expect(links.map(link => link.attributes('href'))).toEqual([
      'https://legal.example.com/terms',
      'https://legal.example.com/privacy',
    ])
    expect(links.every(link => link.attributes('target') === '_blank')).toBe(true)
    expect(wrapper.find('.terms-check').text()).toBe('I agree to the Terms and Privacy Policy.')
  })

  it('requires agreement to only the configured document', async () => {
    publicConfig.termsUrl = ''
    const wrapper = mount(Signup, { global: { stubs } })
    expect(wrapper.find('.terms-check').text()).toBe('I agree to the Privacy Policy.')

    await getInput(wrapper, 'Email').setValue('user@example.com')
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(signUp).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('You must agree to the Privacy Policy.')
  })

  it('omits the consent step when no legal documents are configured', async () => {
    publicConfig.termsUrl = ''
    publicConfig.privacyUrl = ''
    signUp.mockResolvedValueOnce({ verified: false })
    const wrapper = mount(Signup, { global: { stubs } })
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)

    await getInput(wrapper, 'Email').setValue('user@example.com')
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(signUp).toHaveBeenCalledOnce()
  })

  it('calls signUp with the trimmed full name and name/email profile attributes', async () => {
    signUp.mockResolvedValueOnce({ verified: false })
    const wrapper = mount(Signup, { global: { stubs } })

    await getInput(wrapper, 'First').setValue('Ada')
    await getInput(wrapper, 'Last').setValue('Lovelace')
    await getInput(wrapper, 'Email').setValue('ada@example.com')
    await getInput(wrapper, 'Password').setValue('a-strong-password-123!')
    await agreeToTerms(wrapper)
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(signUp).toHaveBeenCalledWith({
      identifier: 'ada@example.com',
      password: 'a-strong-password-123!',
      originator: 'studio',
      profile: {
        name: 'Ada Lovelace',
        visibility: 'USER',
        attributes: [
          {
            typeId: 'bosca.profiles.name',
            attributes: { name: 'Ada Lovelace' },
            source: 'signup',
            priority: 1,
            confidence: 100,
            visibility: 'USER',
          },
          {
            typeId: 'bosca.profiles.email',
            attributes: { email: 'ada@example.com' },
            source: 'signup',
            priority: 1,
            confidence: 100,
            visibility: 'USER',
          },
        ],
      },
    })
  })

  it('normalizes the email (trim + lowercase) for the identifier and email attribute', async () => {
    signUp.mockResolvedValueOnce({ verified: false })
    const wrapper = mount(Signup, { global: { stubs } })

    await getInput(wrapper, 'Email').setValue('  Ada@Example.COM  ')
    await agreeToTerms(wrapper)
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    const arg = signUp.mock.calls[0]![0] as {
      identifier: string
      profile: { attributes: Array<{ typeId: string, attributes: { email?: string } }> }
    }
    expect(arg.identifier).toBe('ada@example.com')
    const emailAttr = arg.profile.attributes.find(a => a.typeId === 'bosca.profiles.email')
    expect(emailAttr?.attributes.email).toBe('ada@example.com')
  })

  it('redirects unverified accounts to /auth/verify with the email', async () => {
    signUp.mockResolvedValueOnce({ verified: false })
    const wrapper = mount(Signup, { global: { stubs } })

    await getInput(wrapper, 'Email').setValue('new-user@example.com')
    await agreeToTerms(wrapper)
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      '/auth/verify?email=new-user%40example.com',
      expect.objectContaining({ replace: true, external: true }),
    )
  })

  it('forwards the proof methods to the link-confirm page on an account collision', async () => {
    // An OAuth-only existing account exposes EMAIL proof only — the confirm page
    // must receive that so it never shows a dead-end password field.
    signUp.mockRejectedValueOnce(new AccountLinkRequiredError('pl-tok', ['EMAIL']))
    const wrapper = mount(Signup, { global: { stubs } })

    await getInput(wrapper, 'Email').setValue('taken@example.com')
    await agreeToTerms(wrapper)
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      '/auth/link/confirm?token=pl-tok&methods=EMAIL',
      expect.objectContaining({ replace: true }),
    )
  })

  it('signs in already-verified accounts before sending them home', async () => {
    // The backend's password sign-up never mints a session, so an
    // auto-verified account must be signed in with the entered credentials
    // before navigating into the app — otherwise the Nitro auth gate bounces
    // it to /auth/login?unauthorized=true.
    signUp.mockResolvedValueOnce({ verified: true })
    const wrapper = mount(Signup, { global: { stubs } })

    await getInput(wrapper, 'Email').setValue('verified@example.com')
    await getInput(wrapper, 'Password').setValue('a-strong-password-123!')
    await agreeToTerms(wrapper)
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(signInWithPassword).toHaveBeenCalledWith('verified@example.com', 'a-strong-password-123!', 'studio')
    expect(navigateTo).toHaveBeenCalledWith('/', expect.objectContaining({ replace: true, external: true }))
  })

  it('returns an auto-verified signup to the gateway handoff', async () => {
    routeQuery.returnTo = '/auth/gateway?redirect=https%3A%2F%2Fwarehouse.example.com%2Fauth%2Fstudio%2Fcallback%3Fstate%3Dabc'
    signUp.mockResolvedValueOnce({ verified: true })
    const wrapper = mount(Signup, { global: { stubs } })

    await getInput(wrapper, 'Email').setValue('verified@example.com')
    await getInput(wrapper, 'Password').setValue('a-strong-password-123!')
    await agreeToTerms(wrapper)
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      routeQuery.returnTo,
      expect.objectContaining({ replace: true, external: true }),
    )
  })

  it('does not use an external post-signup return target', async () => {
    routeQuery.returnTo = 'https://evil.example'
    signUp.mockResolvedValueOnce({ verified: true })
    const wrapper = mount(Signup, { global: { stubs } })

    await getInput(wrapper, 'Email').setValue('verified@example.com')
    await getInput(wrapper, 'Password').setValue('a-strong-password-123!')
    await agreeToTerms(wrapper)
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(navigateTo).toHaveBeenCalledWith(
      '/',
      expect.objectContaining({ replace: true, external: true }),
    )
  })

  it('does not navigate home when the post-signup sign-in fails', async () => {
    signUp.mockResolvedValueOnce({ verified: true })
    signInWithPassword.mockRejectedValueOnce(new Error('Invalid credentials'))
    const wrapper = mount(Signup, { global: { stubs } })

    await getInput(wrapper, 'Email').setValue('verified@example.com')
    await getInput(wrapper, 'Password').setValue('a-strong-password-123!')
    await agreeToTerms(wrapper)
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(navigateTo).not.toHaveBeenCalled()
    // Raw errors are never shown verbatim — a generic line is rendered instead.
    expect(wrapper.text()).toContain('Something went wrong creating your account.')
  })

  it('shows a friendly message (and no raw DB error) when the email already exists', async () => {
    // Regression: an existing account used to surface a raw Postgres unique-
    // constraint violation ("... duplicate key value violates unique constraint
    // \"ix_principal_identifier\" ...") straight in the UI.
    signUp.mockRejectedValueOnce(new EmailAlreadyRegisteredError())
    const wrapper = mount(Signup, { global: { stubs } })

    await getInput(wrapper, 'Email').setValue('taken@example.com')
    await agreeToTerms(wrapper)
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(navigateTo).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('An account with that email already exists. Try signing in instead.')
    expect(wrapper.text()).not.toContain('ix_principal_identifier')
    expect(wrapper.text()).not.toContain('duplicate key')
  })

  it('surfaces a generic error for an unexpected signup failure', async () => {
    signUp.mockRejectedValueOnce(new Error('kaboom'))
    const wrapper = mount(Signup, { global: { stubs } })

    await getInput(wrapper, 'Email').setValue('taken@example.com')
    await agreeToTerms(wrapper)
    await wrapper.find('button.auth-btn').trigger('click')
    await flushPromises()

    expect(navigateTo).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Something went wrong creating your account.')
    // The raw underlying message must not leak into the UI.
    expect(wrapper.text()).not.toContain('kaboom')
  })
})
