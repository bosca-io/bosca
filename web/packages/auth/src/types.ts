/**
 * JWT access token issued after successful authentication, mirroring
 * the backend `Token` GraphQL type. Contains the encoded JWT string
 * and its validity window expressed as Unix epoch seconds.
 */
export interface BoscaToken {
  /** Encoded JWT string for use in Authorization headers */
  token: string
  /** Unix timestamp (seconds) when this token expires */
  expiresAt: number
  /** Unix timestamp (seconds) when this token was issued */
  issuedAt: number
}

/**
 * Complete authentication response returned after a successful login
 * or token exchange, mirroring the backend `LoginResponse` GraphQL type.
 */
export interface AuthResponse {
  /** The authenticated user account */
  principal: Principal
  /** User profiles associated with the principal, null when none exist */
  profile: Profile[] | null
  /** JWT access token for authenticating subsequent requests */
  token: BoscaToken
  /** Long-lived refresh token for obtaining new access tokens, null when refresh tokens are disabled */
  refreshToken: string | null
  /**
   * True when this sign-in created the account (a first-time third-party/OAuth signup), false when
   * authenticating an existing account. Surfaced on the `signedIn` event so app code can branch a
   * brand-new account into onboarding. Populated on the cross-domain exchange-token path and, for
   * same-domain OAuth, from the one-shot sign-in-result cookie surfaced by `initialize()`.
   */
  accountCreated: boolean
  /**
   * The caller-supplied originator of the login request (an app/client identifier), echoed straight back
   * so the caller can correlate the response. Null when none was provided. Populated on the cross-domain
   * exchange-token path and, for same-domain OAuth, from the one-shot sign-in-result cookie.
   */
  originator: string | null
}

/**
 * The outcome of a same-domain OAuth sign-in, handed back to the client in a
 * one-shot cookie. That path completes via a session cookie, mints no exchange
 * token, and would otherwise emit no `signedIn`, so the backend carries just
 * enough of the result for the client to announce it: the echoed `originator`
 * and whether the account was just created. Read exactly once by `initialize()`,
 * then cleared so ordinary reloads don't re-fire.
 */
export interface SignInResult {
  /** The login request originator echoed by the backend, or null when none. */
  originator: string | null
  /** True when this OAuth sign-in created the account. */
  accountCreated: boolean
}

/**
 * An authenticated user account in the system, representing the
 * identity that owns credentials, groups, and profiles.
 */
export interface Principal {
  /** Unique identifier for this principal */
  id: string
  /** Whether the principal's email or identity has been verified */
  verified: boolean
  /** ID of the primary user profile, null when not set */
  primaryProfileId: string | null
}

/**
 * A user profile containing display information and typed attributes.
 * A principal may own multiple profiles (e.g. personal and organization).
 */
export interface Profile {
  /** Unique identifier for this profile */
  id: string
  /** Display name of the profile */
  name: string
  /** Classification of the profile (generic user, organization, or child account) */
  type: ProfileType
  /** Privacy level controlling who can see this profile */
  visibility: ProfileVisibility
  /** URL-friendly identifier, null when not set */
  slug: string | null
  /** Whether this is the principal's primary profile */
  isPrimary: boolean
  /** ISO 8601 timestamp of when the profile was created */
  created: string
  /** Typed attributes attached to the profile (e.g. locale, email, name) */
  attributes: ProfileAttribute[]
}

/**
 * A single typed attribute on a profile, carrying a flexible JSON
 * value along with metadata about its origin and reliability.
 */
export interface ProfileAttribute {
  /** Unique identifier for this profile attribute instance */
  id: string
  /** Attribute type identifier (e.g. "bosca.profiles.locale") */
  typeId: string
  /** Attribute value whose structure depends on the type */
  attributes: Record<string, unknown>
  /** Origin of the attribute (e.g. "signup", "ai-inference", "import") */
  source: string
  /** Precedence when multiple attributes of the same type exist */
  priority: number
  /** Reliability score from 0 to 100 */
  confidence: number
  /** Privacy level for this attribute */
  visibility: ProfileVisibility
}

/**
 * Input for creating or updating a user profile, including
 * the display name, visibility, and initial attributes.
 */
export interface ProfileInput {
  /** Display name for the profile */
  name: string
  /** URL-friendly slug identifier */
  slug?: string
  /** Privacy level for the profile */
  visibility: ProfileVisibility
  /** Typed attributes to set on the profile */
  attributes: ProfileAttributeInput[]
}

/**
 * Input for setting a typed attribute on a profile during
 * creation or update operations.
 */
export interface ProfileAttributeInput {
  /** UUID of an existing attribute to update; omit when creating a new attribute */
  id?: string
  /** Attribute type identifier (e.g. "bosca.profiles.name") */
  typeId: string
  /** Attribute value whose structure depends on the type */
  attributes: Record<string, unknown>
  /** Origin of the attribute (e.g. "signup") */
  source: string
  /** Precedence when multiple attributes of the same type exist */
  priority: number
  /** Reliability score from 0 to 100 */
  confidence: number
  /** Privacy level for this attribute */
  visibility: ProfileVisibility
}

/** Classification of a user profile */
export type ProfileType = 'GENERIC' | 'ORGANIZATION' | 'CHILD'

/** Privacy level controlling visibility of profiles and attributes */
export type ProfileVisibility = 'USER' | 'FRIENDS' | 'FRIENDS_OF_FRIENDS' | 'PUBLIC' | 'SYSTEM'

/** Classification of a security group */
export type GroupType = 'PRINCIPAL' | 'SYSTEM'

/**
 * A security group that a principal can belong to, used for
 * role-based access control and permission scoping. System groups
 * are managed by the platform; principal groups are user-created.
 */
export interface Group {
  /** Unique identifier for this group */
  id: string
  /** Display name of the group */
  name: string
  /** Human-readable description of the group's purpose */
  description: string
  /** Whether this group is system-managed or user-created */
  type: GroupType
}

/** Supported third-party OAuth providers for social login */
export type ThirdPartyType = 'GOOGLE' | 'FACEBOOK' | 'APPLE'

/**
 * Lightweight metadata stored alongside the access token to allow
 * the token manager to schedule refresh timers after a page reload
 * without parsing the JWT itself.
 */
export interface TokenMetadata {
  /** Unix timestamp (seconds) when the associated access token expires */
  expiresAt: number
  /** Unix timestamp (seconds) when the associated access token was issued */
  issuedAt: number
}

/**
 * Events emitted by the auth client to notify listeners of state changes.
 * `tokenRefreshed` means this instance refreshed over the network; `tokenAdopted`
 * means it took over a fresh token another tab already stored.
 */
export type AuthEvent = 'signedIn' | 'signedOut' | 'tokenRefreshed' | 'tokenAdopted' | 'profileUpdated' | 'error'

/** Token persistence strategy */
export type StorageType = 'cookie' | 'localStorage' | 'memory'

/**
 * Configuration for initializing the Bosca auth client, controlling
 * the API endpoint, token storage, and automatic refresh behavior.
 */
export interface BoscaAuthConfig {
  /** Base URL of the Bosca API (e.g. "https://api.example.com" or "" for same-origin) */
  apiUrl: string
  /** Exact access-token cookie/storage name (default: "_bat"). */
  tokenName?: string
  /**
   * Where to persist tokens (default: "cookie"). Accepts a named
   * strategy or a fully-constructed `TokenStorage` instance — the
   * latter lets server-side code prefill a storage from the incoming
   * request's cookies so `getAuthHeaders()` works during SSR.
   */
  storage?: StorageType | import('./storage').TokenStorage
  /** Domain for the default `_bat` cookie; configured custom auth cookies are always host-only. */
  cookieDomain?: string
  /** Milliseconds before token expiry to trigger a refresh (default: 60000) */
  refreshBuffer?: number
  /** Whether to automatically refresh tokens before they expire (default: true) */
  autoRefresh?: boolean
  /** Milliseconds to wait before retrying a failed token refresh (default: 1000) */
  retryDelay?: number
  /**
   * Fallback IETF BCP 47 language tag used when `navigator.language` is
   * unavailable (e.g. during SSR). Typically populated from the incoming
   * request's `Accept-Language` header.
   */
  defaultLanguageTag?: string
}

/**
 * Options for creating a new user account with email and password,
 * including the initial profile information.
 */
export interface SignupOptions {
  /** Email address used as the login identifier */
  identifier: string
  /** Password for the new account */
  password: string
  /** Initial profile information for the new user */
  profile: ProfileInput
  /** IETF language tag for the user's preferred language */
  languageTag?: string
  /** Optional originating client/app identifier; stamped on the new credential and echoed back */
  originator?: string
}

/**
 * Options for initiating an OAuth redirect-based sign-in flow
 * with a third-party provider.
 */
export interface OAuthRedirectOptions {
  /** The OAuth provider to authenticate with */
  provider: ThirdPartyType
  /** URL to redirect back to after OAuth completes (default: current page) */
  redirectUrl?: string
  /** Organization signup token granting access to a specific organization */
  organization?: string
  /** Community signup token granting access to a specific community */
  community?: string
  /** Optional originating client/app identifier; stamped on a newly-created credential and echoed back on the login response */
  originator?: string
}
