import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BoscaAuth } from './client'
import { signOut, signupWithPassword, signupThirdParty, loginWithPassword } from './graphql'
import { EmailAlreadyRegisteredError, EmailNotVerifiedError, GraphQLError, PrincipalNotVerifiedError } from './errors'

describe('account linking through the GraphQL transport', () => {
  let auth: BoscaAuth
  let fetchMock: ReturnType<typeof vi.fn>
  const response = () => ({
    principal: { id: 'principal', verified: true, primaryProfileId: null }, profile: [],
    token: { token: 'linked-session', expiresAt: Math.floor(Date.now() / 1000) + 3600, issuedAt: Math.floor(Date.now() / 1000) },
    refreshToken: 'linked-refresh', accountCreated: false, originator: null,
  })
  const reply = (data: unknown) => ({ ok: true, json: async () => ({ data }) })
  beforeEach(() => {
    auth = new BoscaAuth({ apiUrl: 'https://api.test', storage: 'memory', autoRefresh: false, defaultLanguageTag: 'ja-JP' })
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })
  afterEach(() => {
    auth.destroy()
    vi.unstubAllGlobals()
  })

  it.each(['password', 'email'] as const)('adopts the session only after %s proof succeeds', async proof => {
    const login = response()
    fetchMock.mockResolvedValueOnce(reply({ security: { link: { [proof === 'password' ? 'confirmPassword' : 'confirmEmail']: login } } }))
    const signedIn = vi.fn()
    auth.on('signedIn', signedIn)
    const result = proof === 'password'
      ? await auth.linkConfirmPassword('challenge', 'password')
      : await auth.linkConfirmEmail('email-proof')
    expect(result).toEqual(login)
    expect(auth.currentUser).toEqual(login.principal)
    expect(await auth.getToken()).toBe(login.token.token)
    expect(signedIn).toHaveBeenCalledWith(login)
    const request = JSON.parse(fetchMock.mock.calls[0][1].body)
    expect(request.variables).toEqual(proof === 'password' ? { token: 'challenge', password: 'password' } : { proofToken: 'email-proof' })
  })

  it('requests email proof and connects a provider without replacing the current session', async () => {
    fetchMock.mockResolvedValue(reply({ security: {} }))
    await auth.linkRequestEmailProof('challenge')
    await auth.connectThirdParty('GOOGLE', 'provider-token')
    expect(auth.isAuthenticated).toBe(false)
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).variables).toEqual({ token: 'challenge' })
    expect(JSON.parse(fetchMock.mock.calls[1][1].body).variables).toEqual({ type: 'GOOGLE', token: 'provider-token' })
  })

  it.each(['explicit', 'browser', 'default'] as const)('uses the %s language for provider sign-in', async source => {
    if (source === 'default') vi.stubGlobal('navigator', undefined)
    fetchMock.mockResolvedValueOnce(reply({ security: { signup: { thirdparty: { loginResponse: response(), linkChallenge: null } } } }))
    await auth.signInWithThirdParty('GOOGLE', 'provider-token', source === 'explicit' ? 'fr-FR' : undefined)
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).variables.languageTag).toBe(source === 'explicit' ? 'fr-FR' : source === 'browser' ? navigator.language : 'ja-JP')
    expect(auth.isAuthenticated).toBe(true)
  })

  it.each([null, 'session'])('sign-out sends cookies and an optional bearer token (%s)', async token => {
    fetchMock.mockResolvedValue({ ok: true })
    await signOut('https://api.test', token)
    expect(fetchMock.mock.calls[0][1].credentials).toBe('include')
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe(token ? `Bearer ${token}` : undefined)
    fetchMock.mockRejectedValueOnce(new TypeError('offline'))
    await expect(signOut('https://api.test', token)).resolves.toBeUndefined()
  })

  it.each(['principal not verified', 'email not verified', 'not verified'])('maps legacy login rejection: %s', async message => {
    fetchMock.mockResolvedValueOnce({ ok: true, json: async () => ({ errors: [{ message }] }) })
    await expect(loginWithPassword('https://api.test', 'user', 'password')).rejects.toBeInstanceOf(message.startsWith('email') ? EmailNotVerifiedError : PrincipalNotVerifiedError)
  })

  it('turns malformed login and signup transport rejections into GraphQL errors', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => { throw 'invalid body' } })
    await expect(loginWithPassword('https://api.test', 'user', 'password')).rejects.toBeInstanceOf(GraphQLError)
    await expect(signupWithPassword('https://api.test', { identifier: 'user', password: 'password', profile: { name: 'User', visibility: 'USER', attributes: [] } })).rejects.toBeInstanceOf(GraphQLError)
  })

  it.each(['duplicate key', 'already exists', 'unrelated signup failure'])('maps signup rejection: %s', async message => {
    fetchMock.mockResolvedValueOnce({ ok: true, json: async () => ({ errors: [{ message }] }) })
    await expect(signupWithPassword('https://api.test', { identifier: 'user', password: 'password', profile: { name: 'User', visibility: 'USER', attributes: [] } })).rejects.toBeInstanceOf(message === 'unrelated signup failure' ? GraphQLError : EmailAlreadyRegisteredError)
  })

  it.each(['password', 'thirdparty'])('rejects an empty %s signup response', async kind => {
    fetchMock.mockResolvedValueOnce(reply({ security: { signup: { [kind]: { principal: null, loginResponse: null, linkChallenge: null } } } }))
    const result = kind === 'password'
      ? signupWithPassword('https://api.test', { identifier: 'user', password: 'password', profile: { name: 'User', visibility: 'USER', attributes: [] } })
      : signupThirdParty('https://api.test', 'GOOGLE', 'provider-token')
    await expect(result).rejects.toBeInstanceOf(GraphQLError)
  })
})
