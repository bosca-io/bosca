import type { SelectOption } from '@bosca/ui'

/**
 * The flat form model behind the feed-source create/edit UI. It is mapped to and from the typed
 * `FeedConfiguration` that the backend stores on the content `Source.configuration` — see the feeds
 * GraphQL `FeedSourceInput` (configuration JSON + a write-only `authSecret`).
 */
export interface FeedSourceForm {
  name: string
  description: string
  type: string
  endpoint: string
  cronInterval: string
  ownership: string
  enabled: boolean
  authKind: string
  authUsername: string
  authHeader: string
  authTokenUrl: string
  authClientId: string
  authScope: string
  authSecret: string
}

/** The secret-free auth descriptor as it appears inside a stored `FeedConfiguration`. */
interface FeedAuthJson {
  type?: string
  username?: string
  header?: string
  tokenUrl?: string
  clientId?: string
  scope?: string
}

/** The typed `FeedConfiguration` shape, as returned by the `FeedSource.configuration` JSON scalar. */
interface FeedConfigJson {
  type?: string
  endpoint?: string
  cronInterval?: string
  ownership?: string
  enabled?: boolean
  auth?: FeedAuthJson
}

export const FEED_TYPE_OPTIONS: SelectOption[] = [
  { value: 'RSS', label: 'RSS' },
  { value: 'ATOM', label: 'Atom' },
  { value: 'JSON_FEED', label: 'JSON Feed' },
  { value: 'API', label: 'API' },
]

export const FEED_OWNERSHIP_OPTIONS: SelectOption[] = [
  { value: 'MANAGED', label: 'Managed (global)' },
  { value: 'USER', label: 'User-owned' },
]

export const FEED_AUTH_OPTIONS: SelectOption[] = [
  { value: 'none', label: 'None' },
  { value: 'basic', label: 'Basic (username + password)' },
  { value: 'bearer', label: 'Bearer token' },
  { value: 'apiKey', label: 'API key header' },
  { value: 'oauth2', label: 'OAuth2 client credentials' },
]

export function emptyFeedSourceForm(): FeedSourceForm {
  return {
    name: '',
    description: '',
    type: 'RSS',
    endpoint: '',
    cronInterval: '0 * * * *',
    ownership: 'MANAGED',
    enabled: true,
    authKind: 'none',
    authUsername: '',
    authHeader: '',
    authTokenUrl: '',
    authClientId: '',
    authScope: '',
    authSecret: '',
  }
}

/** The label for the single secret value, which differs by auth kind. */
export function authSecretLabel(kind: string): string {
  switch (kind) {
    case 'basic': return 'Password'
    case 'bearer': return 'Bearer token'
    case 'apiKey': return 'API key value'
    case 'oauth2': return 'Client secret'
    default: return 'Secret'
  }
}

export function useFeedSourceForm() {
  /** Hydrate the flat form from a loaded FeedSource (its configuration is the typed FeedConfiguration). */
  function toForm(source: { name?: string; description?: string; configuration?: Record<string, unknown> | null } | null): FeedSourceForm {
    const f = emptyFeedSourceForm()
    if (!source) return f
    f.name = source.name ?? ''
    f.description = source.description ?? ''
    const c = (source.configuration ?? {}) as FeedConfigJson
    f.type = c.type ?? 'RSS'
    f.endpoint = c.endpoint ?? ''
    f.cronInterval = c.cronInterval ?? '0 * * * *'
    f.ownership = c.ownership ?? 'MANAGED'
    f.enabled = c.enabled ?? true
    const auth = c.auth ?? { type: 'none' }
    f.authKind = auth.type ?? 'none'
    f.authUsername = auth.username ?? ''
    f.authHeader = auth.header ?? ''
    f.authTokenUrl = auth.tokenUrl ?? ''
    f.authClientId = auth.clientId ?? ''
    f.authScope = auth.scope ?? ''
    f.authSecret = '' // write-only; the backend never returns it
    return f
  }

  /** Build the secret-free FeedAuth descriptor from the form (discriminated by `type`). */
  function buildAuth(form: FeedSourceForm): FeedAuthJson {
    switch (form.authKind) {
      case 'basic': return { type: 'basic', username: form.authUsername.trim() }
      case 'bearer': return { type: 'bearer' }
      case 'apiKey': return { type: 'apiKey', header: form.authHeader.trim() }
      case 'oauth2': return {
        type: 'oauth2',
        tokenUrl: form.authTokenUrl.trim(),
        clientId: form.authClientId.trim(),
        ...(form.authScope.trim() ? { scope: form.authScope.trim() } : {}),
      }
      default: return { type: 'none' }
    }
  }

  /** Produce the FeedSourceInput payload (configuration JSON + optional write-only authSecret). */
  function toInput(form: FeedSourceForm): {
    name: string
    description: string
    configuration: FeedConfigJson
    authSecret?: string
  } {
    const configuration: FeedConfigJson = {
      type: form.type,
      endpoint: form.endpoint.trim(),
      cronInterval: form.cronInterval.trim(),
      ownership: form.ownership,
      enabled: form.enabled,
      auth: buildAuth(form),
      // `url` is intentionally omitted — the service normalizes `endpoint` into the uniqueness key.
    }
    const secret = form.authSecret.trim()
    return {
      name: form.name.trim(),
      description: form.description.trim(),
      configuration,
      ...(secret ? { authSecret: secret } : {}),
    }
  }

  /** Validation shared by create + edit. Returns an error string, or null when valid. */
  function validate(form: FeedSourceForm): string | null {
    if (!form.name.trim()) return 'Name is required.'
    if (!form.endpoint.trim()) return 'Endpoint URL is required.'
    if (!form.cronInterval.trim()) return 'Fetch schedule (cron) is required.'
    if (form.authKind === 'apiKey' && !form.authHeader.trim()) return 'API key auth requires a header name.'
    if (form.authKind === 'oauth2' && (!form.authTokenUrl.trim() || !form.authClientId.trim())) {
      return 'OAuth2 requires a token URL and client id.'
    }
    return null
  }

  return { toForm, toInput, validate }
}
