import { print, type DocumentNode } from 'graphql'
import type { WatchSource } from 'vue'

type GraphQLQuery = string | DocumentNode

interface GraphQLQueryOptions {
  /** Send the query without session headers or cookies, using public visibility and anonymous defaults. */
  anonymous?: boolean
}

function resolveQuery(query: GraphQLQuery): string {
  return typeof query === 'string' ? query : print(query)
}

interface GraphQLResponse<T> {
  data?: T
  errors?: GraphQLError[]
}

interface GraphQLError {
  message: string
  locations?: { line: number; column: number }[]
  path?: (string | number)[]
  extensions?: Record<string, unknown>
}

export class GraphQLClientError extends Error {
  readonly errors: GraphQLError[]
  readonly response?: GraphQLResponse<unknown>

  constructor(
    message: string,
    errors: GraphQLError[],
    response?: GraphQLResponse<unknown>,
  ) {
    super(message)
    this.name = 'GraphQLClientError'
    this.errors = errors
    this.response = response
  }
}

async function graphqlFetch<T>(
  query: GraphQLQuery,
  variables?: Record<string, unknown>,
  options?: GraphQLQueryOptions,
): Promise<T> {
  // Nuxt's composable context is not guaranteed to survive across
  // await boundaries on the server, so every value that depends on
  // `useRuntimeConfig` / `useNuxtApp` is captured synchronously here
  // before the first await. Calling these after the await previously
  // threw "composable called outside Nuxt context" and silently broke
  // every SSR data fetch that went through this composable.
  //
  // routeRules' proxy target is baked at build time (CI builds with no
  // API_URL, so the production bundle has localhost:8080 baked in). On
  // the server, bypass the proxy and hit the runtime apiUrl directly.
  // Client requests keep the relative URL so the dev proxy / production
  // Gateway handle them.
  const url = import.meta.server
    ? `${useRuntimeConfig().apiUrl}/graphql`
    : '/graphql'

  // Auth is owned by `@bosca/auth-client-browser` end-to-end — including
  // SSR. The studio's auth plugin seeds an SSR-side `MemoryStorage` from
  // the request's `_bat` cookie, so `$auth.getAuthHeaders()` returns a
  // Bearer header on the server just like it does in the browser. The
  // composable never reads raw cookies or forwards request headers.
  let nuxtAuth: { getAuthHeaders: () => Promise<Record<string, string>> } | undefined
  try {
    const { $auth } = useNuxtApp()
    if ($auth && !options?.anonymous) {
      nuxtAuth = $auth as typeof nuxtAuth
    }
  } catch {
    // Auth plugin has not registered yet (e.g. an early hook). Public
    // queries still succeed; protected ones will surface an auth error
    // from the backend, which is the correct signal.
  }

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  if (nuxtAuth) {
    Object.assign(headers, await nuxtAuth.getAuthHeaders())
  }

  const vars: Record<string, unknown> = {}
  if (variables) {
    for (const key in variables) {
      vars[key] = isRef(variables[key]) ? unref(variables[key]) : variables[key]
    }
  }

  const response = await $fetch<GraphQLResponse<T>>(url, {
    method: 'POST',
    headers,
    ...(options?.anonymous ? { credentials: 'omit' as const } : {}),
    body: { query: resolveQuery(query), variables: vars },
  })

  if (response.errors?.length) {
    throw new GraphQLClientError(
      response.errors[0]!.message,
      response.errors,
      response,
    )
  }

  if (!response.data) {
    throw new GraphQLClientError('No data in GraphQL response', [])
  }

  return response.data
}

type ReactiveVariables = Record<string, MaybeRef<unknown>> | ComputedRef<Record<string, unknown>>

/**
 * Resolves a variables object where values may be refs into a plain object
 * suitable for a GraphQL request.
 *
 * The variables argument may be a plain object, OR a ref/computed that
 * resolves to an object. In the latter case, unwrapping the outer ref can
 * still leave property values that are themselves refs (a computed that
 * returns `{ id: someRef, ... }`). Both layers are resolved here so the
 * result is always a fully-unwrapped plain object — otherwise a raw ref
 * leaks into the GraphQL body and the cache-key `JSON.stringify`, which then
 * throws "Converting circular structure to JSON" on Vue's internal Dep graph.
 */
function resolveVariables(vars: ReactiveVariables): Record<string, unknown> {
  const source = isRef(vars) ? toValue(vars) : vars
  const resolved: Record<string, unknown> = {}
  for (const key in source) {
    resolved[key] = toValue(source[key])
  }
  return resolved
}

/**
 * Collects all reactive (ref/computed) values from a variables object
 * so they can be watched for changes.
 */
function collectWatchSources(vars: ReactiveVariables): WatchSource[] {
  if (isRef(vars)) return [vars as WatchSource]
  const sources: WatchSource[] = []
  for (const key in vars) {
    if (isRef(vars[key])) {
      sources.push(vars[key] as WatchSource)
    }
  }
  return sources
}

/**
 * Returns true if any resolved variable value is `undefined`. Used as
 * a short-circuit guard so dependent queries don't fire while an
 * upstream ref (cluster id, parent record id) is still loading.
 *
 * `null` is **not** treated as "skip" — composables routinely send
 * `null` to indicate "no filter" for optional GraphQL args (a
 * nullable `String` / `Int` in the schema). Treating null the same as
 * undefined would skip every query that has optional filters until
 * every filter was filled in — exactly the failure mode the
 * kubernetes pages were hitting (no GraphQL call ever fired on
 * /workloads because `namespace` and `kind` resolved to null).
 *
 * Convention: a composable that wants the fetch to wait should leave
 * the variable as `undefined` (e.g. `toValue(filter.cluster)` without
 * a fallback). A composable that wants the variable sent as null
 * should fall back with `?? null`.
 */
function hasUndefinedVariable(resolved: Record<string, unknown>): boolean {
  for (const key in resolved) {
    if (resolved[key] === undefined) return true
  }
  return false
}

/**
 * Stable string encoding of a resolved variables object, appended to the
 * `useAsyncData` key so each distinct filter combination gets its own
 * cache slot. Property keys are sorted so the result is independent of
 * insertion order (SSR and client must produce the same key for a given
 * filter set), and `undefined` is mapped to a sentinel because
 * `JSON.stringify` would otherwise drop the property — collapsing
 * "not yet loaded" into a different shape than "explicitly null".
 */
const UNDEFINED_SENTINEL = '__undefined__'
function serializeVariables(resolved: Record<string, unknown>): string {
  const entries = Object.keys(resolved)
    .sort()
    .map(k => [k, resolved[k] === undefined ? UNDEFINED_SENTINEL : resolved[k]])
  return JSON.stringify(entries)
}

export function useGraphQL() {
  async function query<T = unknown>(
    gql: GraphQLQuery,
    variables?: Record<string, unknown>,
    options?: GraphQLQueryOptions,
  ): Promise<T> {
    return graphqlFetch<T>(gql, variables, options)
  }

  async function mutation<T = unknown>(
    gql: GraphQLQuery,
    variables?: Record<string, unknown>,
  ): Promise<T> {
    return graphqlFetch<T>(gql, variables)
  }

  /**
   * Reactive GraphQL query bound to Nuxt's data-fetching lifecycle.
   * Variable values can be refs — they are automatically watched and the
   * query re-executes whenever any of them change.
   *
   * Skip semantics: if any resolved variable is `undefined`, the fetch
   * is skipped and the result resolves to `null`. `null` values are
   * sent as-is — they're valid for nullable GraphQL args ("no filter
   * for this column"). Watching a computed that resolves to undefined
   * while a parent query is still loading keeps SSR clean without
   * starving optional-arg queries (the namespace / kind filters on
   * the kubernetes pages all resolve to null, not undefined).
   */
  function useAsyncQuery<T = unknown>(
    key: string,
    gql: GraphQLQuery,
    variables?: ReactiveVariables,
    options?: GraphQLQueryOptions & { server?: boolean },
  ) {
    const watchSources = variables ? collectWatchSources(variables) : []
    const asyncDataOptions: Record<string, unknown> = {}
    if (watchSources.length) asyncDataOptions.watch = watchSources
    if (options?.server === false) asyncDataOptions.server = false

    // Nuxt keys `useAsyncData` state globally: every caller of a given key
    // shares one `data` / `status` / `refresh` slot, and a component
    // mounting against a key that already holds data reuses that payload
    // instead of refetching. A query whose filters vary by call site — the
    // pod list is fetched unfiltered (pods page), workload-scoped (workload
    // drawer / detail), and search-scoped (pod detail) — therefore clobbers
    // itself: the workload drawer renders the cached *all-pods* payload from
    // the pods page rather than asking the backend the filtered question.
    //
    // Folding the resolved variables into the key gives each filter
    // combination its own slot. The key is a getter so it stays reactive;
    // Nuxt re-executes when it changes, and its `keyChanging` guard
    // suppresses the otherwise-redundant params-watch fetch.
    // Anonymous previews must never reuse data fetched with the editor's identity and permissions.
    const queryKey = options?.anonymous ? `${key}:anonymous` : key
    const asyncKey = variables
      ? () => `${queryKey}:${serializeVariables(resolveVariables(variables))}`
      : queryKey

    return useAsyncData<T>(
      asyncKey,
      () => {
        const resolved = variables ? resolveVariables(variables) : undefined
        if (resolved && hasUndefinedVariable(resolved)) {
          return Promise.resolve(null as unknown as T)
        }
        return graphqlFetch<T>(gql, resolved, options)
      },
      asyncDataOptions,
    )
  }

  /**
   * Subscribes to a GraphQL subscription over WebSocket using the
   * graphql-transport-ws protocol. Connects on mount, disposes on unmount.
   *
   * `variables` may be a getter — the subscription then follows it reactively: it stays disconnected
   * while any resolved variable is undefined/null/empty ("not known yet") and reconnects when the
   * resolved variables change (e.g. a new run id after a relaunch).
   */
  function useSubscription<T = unknown>(
    gql: GraphQLQuery,
    variables?: Record<string, unknown> | (() => Record<string, unknown> | null),
    onData?: (data: T) => void,
  ) {
    if (import.meta.server) return

    let ws: WebSocket | undefined
    const resolveVariables = () => (typeof variables === 'function' ? variables() : variables) ?? {}
    const connectable = (vars: Record<string, unknown>) =>
      !Object.values(vars).some(v => v === undefined || v === null || v === '')

    async function connect(resolvedVariables: Record<string, unknown>) {
      const config = useRuntimeConfig()
      const wsBase = (config.public as { wsUrl?: string }).wsUrl || `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}`
      const url = `${wsBase}/graphqlws`

      let connectionParams: Record<string, unknown> = {}
      try {
        const { $auth } = useNuxtApp()
        if ($auth) {
          const headers = await $auth.getAuthHeaders()
          const token = headers?.['Authorization']?.replace('Bearer ', '')
          if (token) connectionParams = { authToken: token }
        }
      } catch { /* auth not ready */ }

      ws = new WebSocket(url, 'graphql-transport-ws')

      ws.onopen = () => {
        ws!.send(JSON.stringify({ type: 'connection_init', payload: connectionParams }))
      }

      ws.onmessage = (event) => {
        const msg = JSON.parse(event.data)
        switch (msg.type) {
          case 'connection_ack':
            ws!.send(JSON.stringify({
              id: '1',
              type: 'subscribe',
              payload: { query: resolveQuery(gql), variables: resolvedVariables },
            }))
            break
          case 'ping':
            ws!.send(JSON.stringify({ type: 'pong' }))
            break
          case 'next':
            if (msg.payload?.data && onData) onData(msg.payload.data as T)
            break
          case 'error':
            console.error('Subscription error', msg.payload)
            break
        }
      }

      ws.onerror = (err) => {
        console.error('WebSocket error', err)
      }
    }

    function disconnect() {
      if (ws && ws.readyState <= WebSocket.OPEN) ws.close()
      ws = undefined
    }

    onMounted(() => {
      watch(resolveVariables, (vars, prev) => {
        if (prev !== undefined && JSON.stringify(vars) === JSON.stringify(prev)) return
        disconnect()
        if (connectable(vars)) connect(vars)
      }, { immediate: true })
    })
    onUnmounted(disconnect)
  }

  return { query, mutation, useAsyncQuery, useSubscription }
}
