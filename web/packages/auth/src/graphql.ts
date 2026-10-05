import { AccountLinkRequiredError, EmailAlreadyRegisteredError, EmailNotVerifiedError, GraphQLError, InvalidCredentialsError, NetworkError, PrincipalNotVerifiedError } from './errors'
import type { LinkProofMethod } from './errors'
import type { AuthResponse, Group, Principal, Profile, ProfileInput, SignupOptions } from './types'

/** The `linkChallenge` payload returned by a sign-up that collided with an existing verified account. */
interface LinkChallengeData {
  token: string
  methods: LinkProofMethod[]
}

/**
 * Sends a raw GraphQL request to the Bosca backend using native `fetch`.
 * Handles network errors and surfaces GraphQL-level errors as exceptions.
 *
 * @typeParam T - The expected shape of the `data` field in the response
 * @param apiUrl - Base URL of the Bosca API
 * @param query - The GraphQL query or mutation string
 * @param variables - Variables to pass alongside the query
 * @param token - Optional Bearer token for authenticated operations
 * @returns The `data` field from the GraphQL JSON response
 */
export async function graphqlRequest<T>(
  apiUrl: string,
  query: string,
  variables: Record<string, unknown>,
  token?: string,
): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
  }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }

  let response: Response
  try {
    response = await fetch(`${apiUrl}/graphql`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ query, variables }),
    })
  } catch (err) {
    throw new NetworkError(
      `Failed to reach ${apiUrl}/graphql: ${err instanceof Error ? err.message : String(err)}`,
    )
  }

  if (!response.ok) {
    throw new NetworkError(`GraphQL request failed with status ${response.status}`)
  }

  const json = await response.json() as { data?: T; errors?: unknown[] }

  if (json.errors && json.errors.length > 0) {
    const message = (json.errors[0] as { message?: string })?.message ?? 'GraphQL request failed'
    throw new GraphQLError(message, json.errors)
  }

  if (!json.data) {
    throw new GraphQLError('GraphQL response contained no data')
  }

  return json.data
}

// ---------------------------------------------------------------------------
// GraphQL fragment for the full LoginResponse fields
// ---------------------------------------------------------------------------

const LOGIN_RESPONSE_FIELDS = `
  principal { id verified primaryProfileId }
  profile { id name type visibility slug isPrimary created attributes { id typeId attributes source priority confidence visibility } }
  token { token expiresAt issuedAt }
  refreshToken
  accountCreated
  originator
`

// ---------------------------------------------------------------------------
// Mutations & Queries
// ---------------------------------------------------------------------------

const LOGIN_PASSWORD = `
  mutation LoginPassword($identifier: String!, $password: String!, $originator: String) {
    security {
      login {
        password(identifier: $identifier, password: $password, originator: $originator) {
          ${LOGIN_RESPONSE_FIELDS}
        }
      }
    }
  }
`

const REFRESH_TOKEN = `
  mutation RefreshToken($refreshToken: String!) {
    security {
      login {
        refreshToken(refreshToken: $refreshToken) {
          ${LOGIN_RESPONSE_FIELDS}
        }
      }
    }
  }
`

const EXCHANGE_TOKEN = `
  mutation ExchangeToken($token: String!) {
    security {
      login {
        exchangeToken(token: $token) {
          ${LOGIN_RESPONSE_FIELDS}
        }
      }
    }
  }
`

const SIGN_OUT = `
  mutation SignOut {
    security {
      login {
        signOut
      }
    }
  }
`

const FORGOT_PASSWORD = `
  mutation ForgotPassword($identifier: String!) {
    security {
      login {
        forgotPassword(identifier: $identifier)
      }
    }
  }
`

const RESET_PASSWORD = `
  mutation ResetPassword($token: String!, $password: String!) {
    security {
      login {
        resetPassword(token: $token, password: $password)
      }
    }
  }
`

const LINK_CHALLENGE_FIELDS = `
  linkChallenge { token methods }
`

const SIGNUP_PASSWORD = `
  mutation SignupPassword($identifier: String!, $password: String!, $profile: ProfileInput!, $languageTag: String, $originator: String) {
    security {
      signup {
        password: passwordV2(identifier: $identifier, password: $password, profile: $profile, languageTag: $languageTag, originator: $originator) {
          principal { id verified }
          ${LINK_CHALLENGE_FIELDS}
        }
      }
    }
  }
`

const SIGNUP_THIRDPARTY = `
  mutation SignupThirdparty($type: ThirdPartyType!, $token: String!, $languageTag: String) {
    security {
      signup {
        thirdparty: thirdpartyV2(type: $type, token: $token, languageTag: $languageTag) {
          loginResponse { ${LOGIN_RESPONSE_FIELDS} }
          ${LINK_CHALLENGE_FIELDS}
        }
      }
    }
  }
`

const LINK_CONFIRM_PASSWORD = `
  mutation LinkConfirmPassword($token: String!, $password: String!) {
    security {
      link {
        confirmPassword(token: $token, password: $password) {
          ${LOGIN_RESPONSE_FIELDS}
        }
      }
    }
  }
`

const LINK_REQUEST_EMAIL_PROOF = `
  mutation LinkRequestEmailProof($token: String!) {
    security {
      link {
        requestEmailProof(token: $token)
      }
    }
  }
`

const LINK_CONFIRM_EMAIL = `
  mutation LinkConfirmEmail($proofToken: String!) {
    security {
      link {
        confirmEmail(proofToken: $proofToken) {
          ${LOGIN_RESPONSE_FIELDS}
        }
      }
    }
  }
`

const CONNECT_THIRD_PARTY = `
  mutation ConnectThirdParty($type: ThirdPartyType!, $token: String!) {
    security {
      connectThirdParty(type: $type, token: $token)
    }
  }
`

const VERIFY_EMAIL = `
  mutation VerifyEmail($verificationToken: String!) {
    security {
      signup {
        verify: passwordVerifyV2(verificationToken: $verificationToken) {
          ${LINK_CHALLENGE_FIELDS}
        }
      }
    }
  }
`

const CHANGE_PASSWORD = `
  mutation ChangePassword($newPassword: String!, $oldPassword: String!) {
    security {
      principal {
        password(newPassword: $newPassword, oldPassword: $oldPassword)
      }
    }
  }
`

const CHANGE_IDENTIFIER = `
  mutation ChangeIdentifier($identifier: String!, $password: String!) {
    security {
      principal {
        identifier(identifier: $identifier, password: $password)
      }
    }
  }
`

const SET_PRIMARY_PROFILE = `
  mutation SetPrimaryProfile($profileId: UUID!, $principalId: UUID) {
    security {
      principal {
        setPrimaryProfile(profileId: $profileId, principalId: $principalId)
      }
    }
  }
`

const RESEND_VERIFICATION = `
  mutation ResendVerification($identifier: String!) {
    security {
      signup {
        resendPasswordVerification(identifier: $identifier)
      }
    }
  }
`

const GET_CURRENT_PRINCIPAL = `
  query GetCurrentPrincipal {
    security {
      principals {
        current {
          id
          verified
          primaryProfileId
        }
      }
    }
  }
`

const GET_CURRENT_PROFILES = `
  query GetCurrentProfiles {
    profiles {
      current {
        id name type visibility slug isPrimary created
        attributes { id typeId attributes source priority confidence visibility }
      }
    }
  }
`

const GET_CURRENT_GROUPS = `
  query GetCurrentGroups {
    security {
      principals {
        current {
          groups { id name description type }
        }
      }
    }
  }
`

const EDIT_PROFILE = `
  mutation EditProfile($id: UUID, $profile: ProfileInput!) {
    profiles {
      edit(id: $id, profile: $profile) {
        id name type visibility slug isPrimary created
        attributes { id typeId attributes source priority confidence visibility }
      }
    }
  }
`

// ---------------------------------------------------------------------------
// Typed API functions
// ---------------------------------------------------------------------------

/**
 * Authenticates a user with their email/username and password.
 * Returns the full authentication response including tokens and profile.
 */
export async function loginWithPassword(
  apiUrl: string,
  identifier: string,
  password: string,
  originator?: string,
): Promise<AuthResponse> {
  type R = { security: { login: { password: AuthResponse } } }
  try {
    const data = await graphqlRequest<R>(apiUrl, LOGIN_PASSWORD, { identifier, password, originator: originator ?? null })
    return data.security.login.password
  } catch (err) {
    throw mapPasswordLoginError(err)
  }
}

/**
 * Translates the backend's raw security exceptions from a password login into
 * typed, user-safe auth errors.
 *
 * "missing credentials" (no account owns the identifier) and "invalid password"
 * (account exists, wrong password) are deliberately collapsed into a single
 * `InvalidCredentialsError` with one generic message: surfacing them separately
 * lets an attacker enumerate which emails are registered. An unverified principal
 * (the account-level login gate) maps to `PrincipalNotVerifiedError`, and an
 * unverified email maps to `EmailNotVerifiedError`, so the UI can route the user to
 * verification. Anything else (network failure, unexpected GraphQL error) is preserved as-is.
 */
/** Reads the backend's machine-readable error code from `errors[0].extensions.code`, if present. */
function errorCode(err: unknown): string | undefined {
  if (!(err instanceof GraphQLError)) return undefined
  const code = (err.errors[0] as { extensions?: { code?: unknown } } | undefined)?.extensions?.code
  return typeof code === 'string' ? code : undefined
}

function mapPasswordLoginError(err: unknown): Error {
  // Prefer the typed error code; fall back to message-substring matching for older backends.
  const code = errorCode(err)
  if (code === 'PRINCIPAL_NOT_VERIFIED') return new PrincipalNotVerifiedError()
  if (code === 'EMAIL_NOT_VERIFIED') return new EmailNotVerifiedError()
  if (code === 'INVALID_CREDENTIALS') return new InvalidCredentialsError()
  if (err instanceof GraphQLError) {
    const message = err.message.toLowerCase()
    // Order matters: "principal not verified" also contains "not verified", so match the
    // specific phrases before the generic fallback.
    if (message.includes('principal not verified')) {
      return new PrincipalNotVerifiedError()
    }
    if (message.includes('email not verified')) {
      return new EmailNotVerifiedError()
    }
    if (message.includes('not verified')) {
      // A legacy backend emitted the account-level gate without a distinct code; treat a bare
      // "not verified" as the principal gate, which is the condition that blocks login.
      return new PrincipalNotVerifiedError()
    }
    if (message.includes('missing credentials') || message.includes('invalid password')) {
      return new InvalidCredentialsError()
    }
  }
  return err instanceof Error ? err : new GraphQLError('Login failed')
}

/**
 * Obtains a new access token using a valid refresh token.
 * Used to extend the authenticated session without re-entering credentials.
 */
export async function refreshToken(
  apiUrl: string,
  token: string,
): Promise<AuthResponse> {
  type R = { security: { login: { refreshToken: AuthResponse } } }
  const data = await graphqlRequest<R>(apiUrl, REFRESH_TOKEN, { refreshToken: token })
  return data.security.login.refreshToken
}

/**
 * Exchanges a single-use token (from OAuth redirect) for a full
 * JWT access token and refresh token pair.
 */
export async function exchangeToken(
  apiUrl: string,
  token: string,
): Promise<AuthResponse> {
  type R = { security: { login: { exchangeToken: AuthResponse } } }
  const data = await graphqlRequest<R>(apiUrl, EXCHANGE_TOKEN, { token })
  return data.security.login.exchangeToken
}

/**
 * Terminates the current session on the server: invalidates every
 * outstanding access and refresh token for the authenticated
 * principal, and triggers a Set-Cookie that clears the HTTP-only
 * `_bat` session cookie (which JS cannot delete on its own).
 *
 * This call uses `credentials: 'include'` so that
 *   (a) the browser sends the current `_bat` cookie alongside the
 *       bearer token — the server needs either one to identify the
 *       principal to invalidate, and on a cross-origin setup the
 *       cookie would otherwise be withheld; and
 *   (b) the browser honors the server's response `Set-Cookie`
 *       clearing headers, which it ignores cross-origin unless
 *       credentials are opted in.
 *
 * The bearer token is also passed in the `Authorization` header so
 * the server can authenticate the call on same-origin setups where
 * the cookie may not be the canonical credential.
 *
 * Silently swallows transport-level failures: the caller is about
 * to wipe local state regardless, and a network blip here must not
 * leave the client thinking it's still signed in. Server-side
 * cleanup will either have happened or it will not; the local
 * wipe proceeds either way.
 */
export async function signOut(apiUrl: string, token: string | null): Promise<void> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
  }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  try {
    await fetch(`${apiUrl}/graphql`, {
      method: 'POST',
      credentials: 'include',
      headers,
      body: JSON.stringify({ query: SIGN_OUT, variables: {} }),
    })
  } catch {
    // Transport failure — see function docs. The client proceeds
    // to clear local state regardless.
  }
}

/**
 * Initiates a forgot-password flow by sending a password reset
 * email to the provided identifier.
 */
export async function forgotPassword(
  apiUrl: string,
  identifier: string,
): Promise<void> {
  await graphqlRequest(apiUrl, FORGOT_PASSWORD, { identifier })
}

/**
 * Resets a user's password using a verification token received
 * via the forgot-password email.
 */
export async function resetPassword(
  apiUrl: string,
  token: string,
  password: string,
): Promise<void> {
  await graphqlRequest(apiUrl, RESET_PASSWORD, { token, password })
}

/**
 * Registers a new user account with email, password, and an
 * initial profile containing the display name and attributes.
 */
export async function signupWithPassword(
  apiUrl: string,
  options: SignupOptions,
): Promise<Principal> {
  type R = { security: { signup: { password: { principal: Principal | null; linkChallenge: LinkChallengeData | null } } } }
  let data: R
  try {
    data = await graphqlRequest<R>(apiUrl, SIGNUP_PASSWORD, {
      identifier: options.identifier,
      password: options.password,
      profile: options.profile,
      languageTag: options.languageTag ?? null,
      originator: options.originator ?? null,
    })
  } catch (err) {
    throw mapSignupError(err)
  }
  const result = data.security.signup.password
  if (result.linkChallenge) {
    throw new AccountLinkRequiredError(result.linkChallenge.token, result.linkChallenge.methods)
  }
  if (!result.principal) {
    throw new GraphQLError('Sign-up returned neither a principal nor a link challenge')
  }
  return result.principal
}

/**
 * Translates the backend's raw sign-up failures into typed, user-safe auth errors.
 *
 * The login identifier is globally unique (`ix_principal_identifier`), so a
 * collision means an account already owns this email. The backend surfaces that
 * either cleanly ("credential already in use") or, when the colliding account is
 * unverified, as a raw unique-constraint violation — both map to
 * `EmailAlreadyRegisteredError`. Anything else (network failure, unexpected
 * GraphQL error) is preserved as-is.
 */
function mapSignupError(err: unknown): Error {
  // Prefer the typed error code; fall back to message-substring matching for older backends.
  if (errorCode(err) === 'CREDENTIAL_CONFLICT') return new EmailAlreadyRegisteredError()
  if (err instanceof GraphQLError) {
    const message = err.message.toLowerCase()
    if (
      message.includes('credential already in use')
      || message.includes('ix_principal_identifier')
      || message.includes('duplicate key')
      || message.includes('already exists')
    ) {
      return new EmailAlreadyRegisteredError()
    }
  }
  return err instanceof Error ? err : new GraphQLError('Sign-up failed')
}

/**
 * Registers or signs in using a third-party OAuth provider token.
 * The token is the provider-issued access token or ID token.
 */
export async function signupThirdParty(
  apiUrl: string,
  type: string,
  token: string,
  languageTag?: string,
): Promise<AuthResponse> {
  type R = { security: { signup: { thirdparty: { loginResponse: AuthResponse | null; linkChallenge: LinkChallengeData | null } } } }
  const data = await graphqlRequest<R>(apiUrl, SIGNUP_THIRDPARTY, {
    type,
    token,
    languageTag: languageTag ?? null,
  })
  const result = data.security.signup.thirdparty
  if (result.linkChallenge) {
    throw new AccountLinkRequiredError(result.linkChallenge.token, result.linkChallenge.methods)
  }
  if (!result.loginResponse) {
    throw new GraphQLError('Sign-up returned neither a login response nor a link challenge')
  }
  return result.loginResponse
}

/**
 * Completes a pending account link by re-authenticating with the existing
 * account's password. Returns a full auth response for that account, now
 * carrying the newly-linked sign-in method.
 */
export async function linkConfirmPassword(
  apiUrl: string,
  token: string,
  password: string,
): Promise<AuthResponse> {
  type R = { security: { link: { confirmPassword: AuthResponse } } }
  const data = await graphqlRequest<R>(apiUrl, LINK_CONFIRM_PASSWORD, { token, password })
  return data.security.link.confirmPassword
}

/**
 * Requests a one-time magic-link to the existing account's verified email so
 * the user can prove ownership without a password (e.g. for OAuth-only accounts).
 */
export async function linkRequestEmailProof(apiUrl: string, token: string): Promise<void> {
  await graphqlRequest(apiUrl, LINK_REQUEST_EMAIL_PROOF, { token })
}

/**
 * Completes a pending account link using the one-time token delivered by the
 * email magic-link. Returns a full auth response for the existing account.
 */
export async function linkConfirmEmail(apiUrl: string, proofToken: string): Promise<AuthResponse> {
  type R = { security: { link: { confirmEmail: AuthResponse } } }
  const data = await graphqlRequest<R>(apiUrl, LINK_CONFIRM_EMAIL, { proofToken })
  return data.security.link.confirmEmail
}

/**
 * Connects an additional third-party (OAuth) login to the currently
 * authenticated account. Requires a provider-issued token.
 */
export async function connectThirdParty(apiUrl: string, type: string, token: string): Promise<void> {
  await graphqlRequest(apiUrl, CONNECT_THIRD_PARTY, { type, token })
}

/**
 * Verifies a new account's email address using the token
 * sent during the signup flow.
 */
export async function verifyEmail(
  apiUrl: string,
  verificationToken: string,
): Promise<void> {
  type R = { security: { signup: { verify: { linkChallenge: LinkChallengeData | null } } } }
  const data = await graphqlRequest<R>(apiUrl, VERIFY_EMAIL, { verificationToken })
  const result = data.security.signup.verify
  if (result.linkChallenge) {
    // The proven email already belongs to an existing verified account. Surface the proof/linking challenge
    // (mirroring the sign-up collision path) so the caller can route the user into the link flow — prove the
    // existing account to attach this sign-in method to it and retire this duplicate — rather than treating a
    // successful verification as a failure.
    throw new AccountLinkRequiredError(result.linkChallenge.token, result.linkChallenge.methods)
  }
}

/**
 * Resends the email verification message for a pending signup
 * that has not yet confirmed their email.
 */
export async function resendVerification(
  apiUrl: string,
  identifier: string,
): Promise<void> {
  await graphqlRequest(apiUrl, RESEND_VERIFICATION, { identifier })
}

/**
 * Changes the currently authenticated user's password. The old
 * password is required and re-verified server-side before the
 * change is applied.
 */
export async function changePassword(
  apiUrl: string,
  token: string,
  newPassword: string,
  oldPassword: string,
): Promise<void> {
  await graphqlRequest(apiUrl, CHANGE_PASSWORD, { newPassword, oldPassword }, token)
}

/**
 * Changes the currently authenticated user's login identifier
 * (typically email or username). The current password is required
 * for re-verification before the change is applied.
 */
export async function changeIdentifier(
  apiUrl: string,
  token: string,
  identifier: string,
  password: string,
): Promise<void> {
  await graphqlRequest(apiUrl, CHANGE_IDENTIFIER, { identifier, password }, token)
}

/**
 * Sets the primary profile for a principal. When `principalId` is
 * omitted, the currently authenticated principal is targeted.
 * Targeting another principal requires admin privileges server-side.
 */
export async function setPrimaryProfile(
  apiUrl: string,
  token: string,
  profileId: string,
  principalId?: string,
): Promise<void> {
  await graphqlRequest(
    apiUrl,
    SET_PRIMARY_PROFILE,
    { profileId, principalId: principalId ?? null },
    token,
  )
}

/**
 * Retrieves the currently authenticated principal using the provided
 * Bearer token. Used during session restoration to recover the full
 * auth state from a stored token.
 */
export async function getCurrentPrincipal(
  apiUrl: string,
  token: string,
): Promise<Principal> {
  type R = { security: { principals: { current: Principal } } }
  const data = await graphqlRequest<R>(apiUrl, GET_CURRENT_PRINCIPAL, {}, token)
  return data.security.principals.current
}

/**
 * Retrieves the profiles associated with the currently authenticated
 * user, using the provided Bearer token for authorization.
 */
export async function getCurrentProfiles(
  apiUrl: string,
  token: string,
): Promise<Profile[]> {
  type R = { profiles: { current: Profile[] | null } }
  const data = await graphqlRequest<R>(apiUrl, GET_CURRENT_PROFILES, {}, token)
  return data.profiles.current ?? []
}

/**
 * Retrieves the security groups that the currently authenticated
 * principal belongs to. The server returns only groups visible to
 * the caller: a principal's own groups, or all groups for admins.
 */
export async function getCurrentGroups(
  apiUrl: string,
  token: string,
): Promise<Group[]> {
  type R = { security: { principals: { current: { groups: Group[] } } } }
  const data = await graphqlRequest<R>(apiUrl, GET_CURRENT_GROUPS, {}, token)
  return data.security.principals.current.groups
}

/**
 * Updates an existing profile's name, attributes, and visibility.
 * If id is null, edits the current user's primary profile.
 */
export async function editProfile(
  apiUrl: string,
  token: string,
  id: string | null,
  profile: ProfileInput,
): Promise<Profile> {
  type R = { profiles: { edit: Profile } }
  const data = await graphqlRequest<R>(apiUrl, EDIT_PROFILE, { id, profile }, token)
  return data.profiles.edit
}
