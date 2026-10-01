import { stringifyJson } from "./json"
import { pendingDeferredIdentity } from "./deferred-runtime"
import { sharedState } from "./shared-state"

/**
 * Client GraphQL bridge: islands query/mutate the Bosca GraphQL API
 * directly. Endpoint is configurable; the bearer token is supplied by the existing
 * Bosca TypeScript auth library via [configureBosca] (`getToken`) — passthrough,
 * no token management here.
 */
export interface BoscaConfig {
  endpoint: string
  getToken?: () => string | null | undefined
  /** Reads the installation identity on each request. Omitted or blank values add no header. */
  getInstallationId?: () => string | null | undefined
  /** Reads the active analytics session on each request, for example `() => analytics.sink.sessionId`. */
  getAnalyticsSessionId?: () => string | null | undefined
}

interface BoscaRuntimeState {
  config: BoscaConfig
}

const state = sharedState<BoscaRuntimeState>("@bosca/bml/config", () => ({
  config: { endpoint: "/graphql" },
}))

export function configureBosca(partial: Partial<BoscaConfig>): void {
  state.config = { ...state.config, ...partial }
}

export interface GraphQLOperation<V = Record<string, unknown>> {
  query: string
  variables?: V
  operationName?: string
}

export function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: "application/json",
    ...extra,
  }
  const token = state.config.getToken?.()
  if (token) headers["Authorization"] = token.startsWith("Bearer ") ? token : `Bearer ${token}`
  const installationId = state.config.getInstallationId?.()
  if (installationId?.trim()) {
    for (const name of Object.keys(headers)) {
      if (name.toLowerCase() === "x-installation-id") delete headers[name]
    }
    headers["X-Installation-ID"] = installationId
  }
  const sessionId = state.config.getAnalyticsSessionId?.()
  if (sessionId?.trim()) {
    for (const name of Object.keys(headers)) {
      if (name.toLowerCase() === "x-ba-session-id") delete headers[name]
    }
    headers["X-BA-Session-ID"] = sessionId
  }
  return headers
}

export function boscaEndpoint(): string {
  return state.config.endpoint
}

function isSameOriginEndpoint(endpoint: string): boolean {
  if (typeof window === "undefined") return true
  try {
    return new URL(endpoint, window.location.href).origin === window.location.origin
  } catch {
    return true
  }
}

async function execute<T, V = Record<string, unknown>>(op: GraphQLOperation<V> | string, variables?: V): Promise<T> {
  // Only the same-origin BML proxy receives the browser identity cookies the bootstrap establishes.
  // A cross-origin data plane is identified by headers, so it never waits on (or fails with) the bootstrap.
  const identity = isSameOriginEndpoint(state.config.endpoint) ? pendingDeferredIdentity() : null
  if (identity != null) await identity
  const body =
    typeof op === "string"
      ? { query: op, variables }
      : { query: op.query, variables: op.variables ?? variables, operationName: op.operationName }
  const res = await fetch(state.config.endpoint, {
    method: "POST",
    headers: authHeaders(),
    body: stringifyJson(body),
  })
  if (!res.ok) throw new Error(`GraphQL HTTP ${res.status}`)
  const json = (await res.json()) as { data?: T; errors?: Array<{ message: string }> }
  if (json.errors && json.errors.length > 0) {
    throw new Error(json.errors.map((e) => e.message).join("; "))
  }
  return json.data as T
}

export const bosca = {
  query<T = unknown, V = Record<string, unknown>>(op: GraphQLOperation<V> | string, variables?: V): Promise<T> {
    return execute<T, V>(op, variables)
  },
  mutate<T = unknown, V = Record<string, unknown>>(op: GraphQLOperation<V> | string, variables?: V): Promise<T> {
    return execute<T, V>(op, variables)
  },
}
