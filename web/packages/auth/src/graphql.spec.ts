import { describe, it, expect, vi, beforeEach } from 'vitest'
import { graphqlRequest, loginWithPassword, refreshToken, exchangeToken, forgotPassword, resetPassword, changePassword, changeIdentifier, setPrimaryProfile, signupWithPassword, signupThirdParty, verifyEmail, resendVerification, getCurrentPrincipal, getCurrentProfiles, getCurrentGroups, editProfile } from './graphql'
import { AccountLinkRequiredError, EmailAlreadyRegisteredError, EmailNotVerifiedError, GraphQLError, InvalidCredentialsError, NetworkError, PrincipalNotVerifiedError } from './errors'

// Mock global fetch
const mockFetch = vi.fn()
vi.stubGlobal('fetch', mockFetch)

function mockGraphQLResponse(data: unknown, errors?: unknown[]) {
  const body: Record<string, unknown> = {}
  if (data !== undefined) body.data = data
  if (errors) body.errors = errors

  mockFetch.mockResolvedValueOnce({
    ok: true,
    json: () => Promise.resolve(body),
  } as Response)
}

function mockNetworkError() {
  mockFetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))
}

function mockHttpError(status: number) {
  mockFetch.mockResolvedValueOnce({
    ok: false,
    status,
    json: () => Promise.resolve({}),
  } as Response)
}

const sampleLoginResponse = {
  principal: { id: 'p-1', verified: true, primaryProfileId: 'pr-1' },
  profile: [{
    id: 'pr-1', name: 'Test User', type: 'GENERIC', visibility: 'USER',
    slug: 'test-user', isPrimary: true, created: '2024-01-01T00:00:00Z', attributes: [],
  }],
  token: { token: 'jwt-token', expiresAt: 1700000000, issuedAt: 1699999000 },
  refreshToken: 'refresh-token-string',
  accountCreated: true,
  originator: 'studio',
}

beforeEach(() => {
  mockFetch.mockReset()
})

// ---------------------------------------------------------------------------
// graphqlRequest
// ---------------------------------------------------------------------------

describe('graphqlRequest', () => {
  it('sends POST request with correct body and headers', async () => {
    mockGraphQLResponse({ test: true })

    await graphqlRequest('https://api.test', 'query { test }', { foo: 'bar' })

    expect(mockFetch).toHaveBeenCalledWith('https://api.test/graphql', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
      body: JSON.stringify({ query: 'query { test }', variables: { foo: 'bar' } }),
    })
  })

  it('includes Authorization header when token provided', async () => {
    mockGraphQLResponse({ test: true })

    await graphqlRequest('https://api.test', 'query {}', {}, 'my-token')

    const call = mockFetch.mock.calls[0]
    expect(call[1].headers['Authorization']).toBe('Bearer my-token')
  })

  it('returns data on success', async () => {
    mockGraphQLResponse({ result: 42 })

    const data = await graphqlRequest<{ result: number }>('https://api.test', 'q', {})
    expect(data.result).toBe(42)
  })

  it('throws NetworkError on fetch failure', async () => {
    mockNetworkError()

    await expect(graphqlRequest('https://api.test', 'q', {}))
      .rejects.toThrow(NetworkError)
  })

  it('throws NetworkError on non-OK HTTP status', async () => {
    mockHttpError(500)

    await expect(graphqlRequest('https://api.test', 'q', {}))
      .rejects.toThrow(NetworkError)
  })

  it('throws GraphQLError when response contains errors', async () => {
    mockGraphQLResponse(undefined, [{ message: 'Invalid field' }])

    await expect(graphqlRequest('https://api.test', 'q', {}))
      .rejects.toThrow(GraphQLError)
  })

  it('throws GraphQLError when response has no data', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({}),
    } as Response)

    await expect(graphqlRequest('https://api.test', 'q', {}))
      .rejects.toThrow(GraphQLError)
  })

  it('uses first error message from GraphQL errors array', async () => {
    mockGraphQLResponse(undefined, [{ message: 'First error' }, { message: 'Second' }])

    try {
      await graphqlRequest('https://api.test', 'q', {})
      expect.fail('should have thrown')
    } catch (err) {
      expect((err as GraphQLError).message).toBe('First error')
      expect((err as GraphQLError).errors).toHaveLength(2)
    }
  })
})

// ---------------------------------------------------------------------------
// loginWithPassword
// ---------------------------------------------------------------------------

describe('loginWithPassword', () => {
  it('sends login mutation and returns auth response', async () => {
    mockGraphQLResponse({
      security: { login: { password: sampleLoginResponse } },
    })

    const result = await loginWithPassword('https://api.test', 'user@test.com', 'pass123')

    expect(result.principal.id).toBe('p-1')
    expect(result.token.token).toBe('jwt-token')
    expect(result.refreshToken).toBe('refresh-token-string')
  })

  it('passes identifier and password as variables', async () => {
    mockGraphQLResponse({
      security: { login: { password: sampleLoginResponse } },
    })

    await loginWithPassword('https://api.test', 'user@test.com', 'pass123')

    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.identifier).toBe('user@test.com')
    expect(body.variables.password).toBe('pass123')
  })

  it('passes the caller-supplied originator as a variable', async () => {
    mockGraphQLResponse({
      security: { login: { password: sampleLoginResponse } },
    })

    await loginWithPassword('https://api.test', 'user@test.com', 'pass123', 'studio')

    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.originator).toBe('studio')
  })

  it('sends a null originator when none is provided', async () => {
    mockGraphQLResponse({
      security: { login: { password: sampleLoginResponse } },
    })

    await loginWithPassword('https://api.test', 'user@test.com', 'pass123')

    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.originator).toBeNull()
  })

  it('maps an unknown identifier ("missing credentials") to a generic InvalidCredentialsError', async () => {
    // The backend wraps the SecurityException in graphql-java's data-fetcher prefix.
    mockGraphQLResponse(undefined, [{
      message: 'Exception while fetching data (/security/login/password) : missing credentials',
    }])

    await expect(loginWithPassword('https://api.test', 'nobody@test.com', 'pass123'))
      .rejects.toBeInstanceOf(InvalidCredentialsError)
  })

  it('maps a wrong password ("invalid password") to the same generic InvalidCredentialsError', async () => {
    // Collapsed with "missing credentials" so the response can't be used to
    // enumerate which emails are registered.
    mockGraphQLResponse(undefined, [{
      message: 'Exception while fetching data (/security/login/password) : invalid password',
    }])

    const error = await loginWithPassword('https://api.test', 'user@test.com', 'wrong')
      .catch((e: unknown) => e)
    expect(error).toBeInstanceOf(InvalidCredentialsError)
    expect((error as InvalidCredentialsError).code).toBe('auth/invalid-credentials')
    expect((error as InvalidCredentialsError).message).toBe('Invalid email or password')
  })

  it('maps an unverified principal ("principal not verified") to PrincipalNotVerifiedError', async () => {
    mockGraphQLResponse(undefined, [{
      message: 'Exception while fetching data (/security/login/password) : principal not verified',
    }])

    await expect(loginWithPassword('https://api.test', 'user@test.com', 'pass123'))
      .rejects.toBeInstanceOf(PrincipalNotVerifiedError)
  })

  it('maps a PRINCIPAL_NOT_VERIFIED error code to PrincipalNotVerifiedError regardless of message', async () => {
    // Code-based mapping: the message intentionally contains no legacy substring.
    mockGraphQLResponse(undefined, [{
      message: 'Exception while fetching data (/security/login/password) : an unrelated message',
      extensions: { code: 'PRINCIPAL_NOT_VERIFIED' },
    }])
    await expect(loginWithPassword('https://api.test', 'user@test.com', 'pass123'))
      .rejects.toBeInstanceOf(PrincipalNotVerifiedError)
  })

  it('maps an EMAIL_NOT_VERIFIED error code to EmailNotVerifiedError regardless of message', async () => {
    // Code-based mapping: the message intentionally contains no legacy substring.
    mockGraphQLResponse(undefined, [{
      message: 'Exception while fetching data (/security/login/password) : an unrelated message',
      extensions: { code: 'EMAIL_NOT_VERIFIED' },
    }])
    await expect(loginWithPassword('https://api.test', 'user@test.com', 'pass123'))
      .rejects.toBeInstanceOf(EmailNotVerifiedError)
  })

  it('maps an INVALID_CREDENTIALS error code to InvalidCredentialsError', async () => {
    mockGraphQLResponse(undefined, [{
      message: 'Exception while fetching data (/security/login/password) : an unrelated message',
      extensions: { code: 'INVALID_CREDENTIALS' },
    }])
    await expect(loginWithPassword('https://api.test', 'user@test.com', 'pass123'))
      .rejects.toBeInstanceOf(InvalidCredentialsError)
  })

  it('passes through a network error unchanged (not masked as invalid credentials)', async () => {
    mockNetworkError()

    await expect(loginWithPassword('https://api.test', 'user@test.com', 'pass123'))
      .rejects.toBeInstanceOf(NetworkError)
  })

  it('preserves an unrecognized GraphQL error rather than mislabeling it', async () => {
    mockGraphQLResponse(undefined, [{ message: 'rate limit exceeded' }])

    const error = await loginWithPassword('https://api.test', 'user@test.com', 'pass123')
      .catch((e: unknown) => e)
    expect(error).toBeInstanceOf(GraphQLError)
    expect(error).not.toBeInstanceOf(InvalidCredentialsError)
  })
})

// ---------------------------------------------------------------------------
// refreshToken
// ---------------------------------------------------------------------------

describe('refreshToken', () => {
  it('sends refresh mutation and returns auth response', async () => {
    mockGraphQLResponse({
      security: { login: { refreshToken: sampleLoginResponse } },
    })

    const result = await refreshToken('https://api.test', 'old-refresh-token')

    expect(result.token.token).toBe('jwt-token')
    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.refreshToken).toBe('old-refresh-token')
  })
})

// ---------------------------------------------------------------------------
// exchangeToken
// ---------------------------------------------------------------------------

describe('exchangeToken', () => {
  it('sends exchange mutation and returns auth response', async () => {
    mockGraphQLResponse({
      security: { login: { exchangeToken: sampleLoginResponse } },
    })

    const result = await exchangeToken('https://api.test', 'exchange-123')

    expect(result.principal.id).toBe('p-1')
    // The cross-domain exchange carries the account-created flag and originator back to the client.
    expect(result.accountCreated).toBe(true)
    expect(result.originator).toBe('studio')
    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.token).toBe('exchange-123')
  })
})

// ---------------------------------------------------------------------------
// forgotPassword
// ---------------------------------------------------------------------------

describe('forgotPassword', () => {
  it('sends forgot password mutation', async () => {
    mockGraphQLResponse({ security: { login: { forgotPassword: true } } })

    await forgotPassword('https://api.test', 'user@test.com')

    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.identifier).toBe('user@test.com')
  })
})

// ---------------------------------------------------------------------------
// resetPassword
// ---------------------------------------------------------------------------

describe('resetPassword', () => {
  it('sends reset password mutation', async () => {
    mockGraphQLResponse({ security: { login: { resetPassword: true } } })

    await resetPassword('https://api.test', 'reset-token', 'newpass')

    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.token).toBe('reset-token')
    expect(body.variables.password).toBe('newpass')
  })
})

// ---------------------------------------------------------------------------
// changePassword
// ---------------------------------------------------------------------------

describe('changePassword', () => {
  it('sends change password mutation with bearer token', async () => {
    mockGraphQLResponse({ security: { principal: { password: true } } })

    await changePassword('https://api.test', 'access-token', 'newpass', 'oldpass')

    const call = mockFetch.mock.calls[0]
    expect(call[1].headers['Authorization']).toBe('Bearer access-token')
    const body = JSON.parse(call[1].body)
    expect(body.variables.newPassword).toBe('newpass')
    expect(body.variables.oldPassword).toBe('oldpass')
    expect(body.variables.identifier).toBeUndefined()
  })
})

// ---------------------------------------------------------------------------
// changeIdentifier
// ---------------------------------------------------------------------------

describe('changeIdentifier', () => {
  it('sends change identifier mutation with bearer token', async () => {
    mockGraphQLResponse({ security: { principal: { identifier: true } } })

    await changeIdentifier('https://api.test', 'access-token', 'new@test.com', 'pw')

    const call = mockFetch.mock.calls[0]
    expect(call[1].headers['Authorization']).toBe('Bearer access-token')
    const body = JSON.parse(call[1].body)
    expect(body.variables.identifier).toBe('new@test.com')
    expect(body.variables.password).toBe('pw')
  })
})

// ---------------------------------------------------------------------------
// setPrimaryProfile
// ---------------------------------------------------------------------------

describe('setPrimaryProfile', () => {
  it('sends set primary profile mutation with null principalId by default', async () => {
    mockGraphQLResponse({ security: { principal: { setPrimaryProfile: true } } })

    await setPrimaryProfile('https://api.test', 'access-token', 'profile-uuid')

    const call = mockFetch.mock.calls[0]
    expect(call[1].headers['Authorization']).toBe('Bearer access-token')
    const body = JSON.parse(call[1].body)
    expect(body.variables.profileId).toBe('profile-uuid')
    expect(body.variables.principalId).toBeNull()
  })

  it('forwards explicit principalId when provided (admin targeting)', async () => {
    mockGraphQLResponse({ security: { principal: { setPrimaryProfile: true } } })

    await setPrimaryProfile('https://api.test', 'access-token', 'profile-uuid', 'principal-uuid')

    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.principalId).toBe('principal-uuid')
  })
})

// ---------------------------------------------------------------------------
// signupWithPassword
// ---------------------------------------------------------------------------

describe('signupWithPassword', () => {
  it('sends signup mutation and returns principal', async () => {
    mockGraphQLResponse({
      security: { signup: { password: { principal: { id: 'new-p', verified: false }, linkChallenge: null } } },
    })

    const result = await signupWithPassword('https://api.test', {
      identifier: 'new@test.com',
      password: 'pass',
      profile: {
        name: 'New User',
        visibility: 'USER',
        attributes: [{
          typeId: 'bosca.profiles.name',
          attributes: { name: 'New User' },
          source: 'signup',
          priority: 1,
          confidence: 100,
          visibility: 'USER',
        }],
      },
    })

    expect(result.id).toBe('new-p')
    expect(result.verified).toBe(false)
  })

  it('passes languageTag when provided', async () => {
    mockGraphQLResponse({
      security: { signup: { password: { principal: { id: 'new-p', verified: false }, linkChallenge: null } } },
    })

    await signupWithPassword('https://api.test', {
      identifier: 'new@test.com',
      password: 'pass',
      profile: { name: 'Test', visibility: 'USER', attributes: [] },
      languageTag: 'en-US',
    })

    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.languageTag).toBe('en-US')
  })

  it('passes the originator when provided', async () => {
    mockGraphQLResponse({
      security: { signup: { password: { principal: { id: 'new-p', verified: false }, linkChallenge: null } } },
    })

    await signupWithPassword('https://api.test', {
      identifier: 'new@test.com',
      password: 'pass',
      profile: { name: 'Test', visibility: 'USER', attributes: [] },
      originator: 'studio',
    })

    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.originator).toBe('studio')
  })

  it('sends null languageTag when not provided', async () => {
    mockGraphQLResponse({
      security: { signup: { password: { principal: { id: 'new-p', verified: false }, linkChallenge: null } } },
    })

    await signupWithPassword('https://api.test', {
      identifier: 'new@test.com',
      password: 'pass',
      profile: { name: 'Test', visibility: 'USER', attributes: [] },
    })

    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.languageTag).toBeNull()
  })

  it('throws AccountLinkRequiredError with the token and methods when the email already has a verified account', async () => {
    mockGraphQLResponse({
      security: { signup: { password: { principal: null, linkChallenge: { token: 'pl-tok', methods: ['PASSWORD', 'EMAIL'] } } } },
    })

    try {
      await signupWithPassword('https://api.test', {
        identifier: 'taken@test.com',
        password: 'pass',
        profile: { name: 'T', visibility: 'USER', attributes: [] },
      })
      expect.fail('expected AccountLinkRequiredError')
    } catch (e) {
      expect(e).toBeInstanceOf(AccountLinkRequiredError)
      expect((e as AccountLinkRequiredError).token).toBe('pl-tok')
      expect((e as AccountLinkRequiredError).methods).toEqual(['PASSWORD', 'EMAIL'])
    }
  })

  it('maps a clean "credential already in use" conflict to EmailAlreadyRegisteredError', async () => {
    mockGraphQLResponse(undefined, [{
      message: 'Exception while fetching data (/security/signup/password) : credential already in use',
    }])

    await expect(signupWithPassword('https://api.test', {
      identifier: 'taken@test.com',
      password: 'pass',
      profile: { name: 'T', visibility: 'USER', attributes: [] },
    })).rejects.toBeInstanceOf(EmailAlreadyRegisteredError)
  })

  it('maps a raw unique-constraint violation (unverified duplicate) to EmailAlreadyRegisteredError', async () => {
    // The case that leaked: an existing UNVERIFIED account slips past the
    // verified-only signup guard and the credential insert violates
    // ix_principal_identifier.
    mockGraphQLResponse(undefined, [{
      message: 'Exception while fetching data (/security/signup/password) : ERROR: duplicate key value '
        + 'violates unique constraint "ix_principal_identifier" Detail: Key ((attributes ->> '
        + "'identifier'::text))=(member@example.com) already exists.",
    }])

    await expect(signupWithPassword('https://api.test', {
      identifier: 'member@example.com',
      password: 'pass',
      profile: { name: 'T', visibility: 'USER', attributes: [] },
    })).rejects.toBeInstanceOf(EmailAlreadyRegisteredError)
  })

  it('maps a CREDENTIAL_CONFLICT error code to EmailAlreadyRegisteredError regardless of message', async () => {
    // Code-based mapping: the message intentionally contains no legacy substring.
    mockGraphQLResponse(undefined, [{
      message: 'Exception while fetching data (/security/signup/passwordV2) : an unrelated message',
      extensions: { code: 'CREDENTIAL_CONFLICT' },
    }])
    await expect(signupWithPassword('https://api.test', {
      identifier: 'taken@test.com',
      password: 'pass',
      profile: { name: 'T', visibility: 'USER', attributes: [] },
    })).rejects.toBeInstanceOf(EmailAlreadyRegisteredError)
  })

  it('passes through a network error unchanged on signup', async () => {
    mockNetworkError()

    await expect(signupWithPassword('https://api.test', {
      identifier: 'new@test.com',
      password: 'pass',
      profile: { name: 'T', visibility: 'USER', attributes: [] },
    })).rejects.toBeInstanceOf(NetworkError)
  })
})

// ---------------------------------------------------------------------------
// verifyEmail
// ---------------------------------------------------------------------------

describe('verifyEmail', () => {
  it('sends verify mutation', async () => {
    mockGraphQLResponse({ security: { signup: { verify: { linkChallenge: null } } } })

    await verifyEmail('https://api.test', 'verify-token-123')

    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.verificationToken).toBe('verify-token-123')
  })

  it('throws AccountLinkRequiredError when the proven email already belongs to a verified account', async () => {
    // The proven email is already verified-owned by another account; the backend hands back a link challenge
    // (token + proof methods) instead of erroring, so the caller can route the user into the link flow.
    mockGraphQLResponse({
      security: { signup: { verify: { linkChallenge: { token: 'pending-link-tok', methods: ['EMAIL'] } } } },
    })

    await expect(verifyEmail('https://api.test', 'verify-token-123')).rejects.toBeInstanceOf(AccountLinkRequiredError)
  })
})

// ---------------------------------------------------------------------------
// resendVerification
// ---------------------------------------------------------------------------

describe('resendVerification', () => {
  it('sends resend mutation', async () => {
    mockGraphQLResponse({ security: { signup: { resendPasswordVerification: true } } })

    await resendVerification('https://api.test', 'user@test.com')

    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.identifier).toBe('user@test.com')
  })
})

// ---------------------------------------------------------------------------
// getCurrentPrincipal
// ---------------------------------------------------------------------------

describe('getCurrentPrincipal', () => {
  it('sends query with auth token and returns principal', async () => {
    const principal = { id: 'p-1', verified: true, primaryProfileId: 'pr-1' }
    mockGraphQLResponse({ security: { principals: { current: principal } } })

    const result = await getCurrentPrincipal('https://api.test', 'bearer-token')

    expect(result).toEqual(principal)
    const call = mockFetch.mock.calls[0]
    expect(call[1].headers['Authorization']).toBe('Bearer bearer-token')
  })
})

// ---------------------------------------------------------------------------
// getCurrentProfiles
// ---------------------------------------------------------------------------

describe('getCurrentProfiles', () => {
  it('sends query with auth token and returns profiles', async () => {
    const profiles = [{ id: 'pr-1', name: 'User', type: 'GENERIC', visibility: 'USER', slug: null, isPrimary: true, created: '2024-01-01T00:00:00Z', attributes: [] }]
    mockGraphQLResponse({ profiles: { current: profiles } })

    const result = await getCurrentProfiles('https://api.test', 'bearer-token')

    expect(result).toEqual(profiles)
    const call = mockFetch.mock.calls[0]
    expect(call[1].headers['Authorization']).toBe('Bearer bearer-token')
  })

  it('returns empty array when current is null', async () => {
    mockGraphQLResponse({ profiles: { current: null } })

    const result = await getCurrentProfiles('https://api.test', 'token')
    expect(result).toEqual([])
  })
})

// ---------------------------------------------------------------------------
// getCurrentGroups
// ---------------------------------------------------------------------------

describe('getCurrentGroups', () => {
  it('sends query with auth token and returns groups', async () => {
    const groups = [
      { id: 'g-1', name: 'admins', description: 'System administrators', type: 'SYSTEM' },
      { id: 'g-2', name: 'editors', description: 'Content editors', type: 'PRINCIPAL' },
    ]
    mockGraphQLResponse({ security: { principals: { current: { groups } } } })

    const result = await getCurrentGroups('https://api.test', 'bearer-token')

    expect(result).toEqual(groups)
    const call = mockFetch.mock.calls[0]
    expect(call[1].headers['Authorization']).toBe('Bearer bearer-token')
  })

  it('returns empty array when user has no groups', async () => {
    mockGraphQLResponse({ security: { principals: { current: { groups: [] } } } })

    const result = await getCurrentGroups('https://api.test', 'bearer-token')
    expect(result).toEqual([])
  })
})

// ---------------------------------------------------------------------------
// editProfile
// ---------------------------------------------------------------------------

describe('editProfile', () => {
  it('sends edit mutation and returns updated profile', async () => {
    const updated = { id: 'pr-1', name: 'Updated', type: 'GENERIC', visibility: 'PUBLIC', slug: 'updated', isPrimary: true, created: '2024-01-01T00:00:00Z', attributes: [] }
    mockGraphQLResponse({ profiles: { edit: updated } })

    const result = await editProfile('https://api.test', 'token', 'pr-1', {
      name: 'Updated',
      visibility: 'PUBLIC',
      attributes: [],
    })

    expect(result.name).toBe('Updated')
  })

  it('passes null id for primary profile edit', async () => {
    const updated = { id: 'pr-1', name: 'Test', type: 'GENERIC', visibility: 'USER', slug: null, isPrimary: true, created: '2024-01-01T00:00:00Z', attributes: [] }
    mockGraphQLResponse({ profiles: { edit: updated } })

    await editProfile('https://api.test', 'token', null, {
      name: 'Test',
      visibility: 'USER',
      attributes: [],
    })

    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.id).toBeNull()
  })
})

// ---------------------------------------------------------------------------
// signupThirdParty
// ---------------------------------------------------------------------------

describe('signupThirdParty', () => {
  it('sends third party signup mutation with type, token, and languageTag', async () => {
    mockGraphQLResponse({
      security: { signup: { thirdparty: { loginResponse: sampleLoginResponse, linkChallenge: null } } },
    })

    const result = await signupThirdParty('https://api.test', 'GOOGLE', 'google-id-token', 'en-US')

    expect(result.principal.id).toBe('p-1')
    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.type).toBe('GOOGLE')
    expect(body.variables.token).toBe('google-id-token')
    expect(body.variables.languageTag).toBe('en-US')
  })

  it('sends null languageTag when not provided', async () => {
    mockGraphQLResponse({
      security: { signup: { thirdparty: { loginResponse: sampleLoginResponse, linkChallenge: null } } },
    })

    await signupThirdParty('https://api.test', 'FACEBOOK', 'fb-token')

    const body = JSON.parse(mockFetch.mock.calls[0][1].body)
    expect(body.variables.languageTag).toBeNull()
  })

  it('throws AccountLinkRequiredError when the verified email already has a different account', async () => {
    mockGraphQLResponse({
      security: { signup: { thirdparty: { loginResponse: null, linkChallenge: { token: 'pl-tok-2', methods: ['EMAIL'] } } } },
    })

    try {
      await signupThirdParty('https://api.test', 'GOOGLE', 'google-id-token')
      expect.fail('expected AccountLinkRequiredError')
    } catch (e) {
      expect(e).toBeInstanceOf(AccountLinkRequiredError)
      expect((e as AccountLinkRequiredError).token).toBe('pl-tok-2')
      expect((e as AccountLinkRequiredError).methods).toEqual(['EMAIL'])
    }
  })
})

// ---------------------------------------------------------------------------
// GraphQL error edge cases
// ---------------------------------------------------------------------------

describe('graphqlRequest edge cases', () => {
  it('uses fallback message when error has no message field', async () => {
    mockGraphQLResponse(undefined, [{ code: 'SOME_ERROR' }])

    try {
      await graphqlRequest('https://api.test', 'q', {})
      expect.fail('should have thrown')
    } catch (err: any) {
      expect(err.message).toBe('GraphQL request failed')
      expect(err.errors).toHaveLength(1)
    }
  })

  it('handles non-Error thrown from fetch (e.g. string)', async () => {
    mockFetch.mockRejectedValueOnce('network down')

    try {
      await graphqlRequest('https://api.test', 'q', {})
      expect.fail('should have thrown')
    } catch (err: any) {
      expect(err).toBeInstanceOf(NetworkError)
      expect(err.message).toContain('network down')
    }
  })
})
