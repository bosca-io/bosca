/**
 * Base error class for all authentication-related failures.
 * Provides a machine-readable `code` string for programmatic
 * error handling alongside the human-readable message.
 */
export class BoscaAuthError extends Error {
  constructor(message: string, public readonly code: string) {
    super(message)
    this.name = 'BoscaAuthError'
  }
}

/**
 * Thrown when login credentials (email/password) are rejected
 * by the backend because they don't match any known account.
 */
export class InvalidCredentialsError extends BoscaAuthError {
  constructor(message = 'Invalid email or password') {
    super(message, 'auth/invalid-credentials')
    this.name = 'InvalidCredentialsError'
  }
}

/**
 * Thrown when a login attempt is made for a principal whose account-level
 * verification (`principal.verified`) is not yet complete. This is the gate the
 * backend enforces on password and passkey login. Distinct from
 * {@link EmailNotVerifiedError}, which is specific to an unverified email address.
 */
export class PrincipalNotVerifiedError extends BoscaAuthError {
  constructor(message = 'Account has not been verified') {
    super(message, 'auth/principal-not-verified')
    this.name = 'PrincipalNotVerifiedError'
  }
}

/**
 * Thrown when an email address specifically has not been verified — as opposed to
 * the account-level {@link PrincipalNotVerifiedError} gate. Reserved for flows that
 * check email-attribute verification directly.
 */
export class EmailNotVerifiedError extends BoscaAuthError {
  constructor(message = 'Email address has not been verified') {
    super(message, 'auth/email-not-verified')
    this.name = 'EmailNotVerifiedError'
  }
}

/**
 * Thrown when a sign-up's email is already registered to an existing account
 * that already has this kind of sign-in method (so there's nothing to link).
 * The user should sign in (or reset their password) instead. Distinct from
 * {@link AccountLinkRequiredError}, which fires when the sign-up would add a
 * genuinely new sign-in method to an existing verified account.
 */
export class EmailAlreadyRegisteredError extends BoscaAuthError {
  constructor(message = 'An account already exists for this email') {
    super(message, 'auth/email-already-registered')
    this.name = 'EmailAlreadyRegisteredError'
  }
}

/**
 * Thrown by client methods that require an authenticated session
 * when no valid access token is available locally. Indicates the
 * caller must sign in before invoking the operation; no network
 * request is made.
 */
export class UnauthenticatedError extends BoscaAuthError {
  constructor(message = 'Not authenticated') {
    super(message, 'auth/unauthenticated')
    this.name = 'UnauthenticatedError'
  }
}

/**
 * Thrown when an access or refresh token has expired and can
 * no longer be used for authentication.
 */
export class TokenExpiredError extends BoscaAuthError {
  constructor(message = 'Authentication token has expired') {
    super(message, 'auth/token-expired')
    this.name = 'TokenExpiredError'
  }
}

/**
 * Thrown when a network request to the Bosca backend fails
 * due to connectivity issues or an unreachable server.
 */
export class NetworkError extends BoscaAuthError {
  constructor(message = 'Network request failed') {
    super(message, 'auth/network-error')
    this.name = 'NetworkError'
  }
}

/**
 * Thrown when an OAuth redirect flow encounters an error,
 * such as the user denying consent or an invalid exchange token.
 */
export class OAuthError extends BoscaAuthError {
  constructor(message = 'OAuth authentication failed') {
    super(message, 'auth/oauth-error')
    this.name = 'OAuthError'
  }
}

/** A way to prove ownership of an existing account when linking a new sign-in method to it. */
export type LinkProofMethod = 'PASSWORD' | 'EMAIL'

/**
 * Thrown when a sign-up's email already belongs to an existing verified account. Carries the
 * single-use pending-link `token` and the available proof `methods` so the caller can drive the
 * account-linking challenge (`auth.linkConfirmPassword` / `linkRequestEmailProof` / `linkConfirmEmail`).
 */
export class AccountLinkRequiredError extends BoscaAuthError {
  public readonly token: string
  public readonly methods: LinkProofMethod[]

  constructor(
    token: string,
    methods: LinkProofMethod[],
    message = 'An account already exists for this email; sign in to link this method',
  ) {
    super(message, 'auth/account-link-required')
    this.name = 'AccountLinkRequiredError'
    this.token = token
    this.methods = methods
  }
}

/**
 * Thrown when the GraphQL endpoint returns errors in its response,
 * indicating a server-side rejection of the operation.
 */
export class GraphQLError extends BoscaAuthError {
  /** The raw GraphQL error objects returned by the server */
  public readonly errors: unknown[]

  constructor(message: string, errors: unknown[] = []) {
    super(message, 'auth/graphql-error')
    this.name = 'GraphQLError'
    this.errors = errors
  }
}
