/**
 * Client-side feature flag evaluation and real-time synchronization.
 *
 * Fetches resolved flag values from the server via GraphQL, caches them
 * locally, and optionally subscribes to real-time updates via GraphQL
 * subscriptions over WebSocket.
 */

/** The resolved value of a single feature flag for the current user/device context. */
export interface FlagEvaluation {
  flagKey: string
  value: boolean | string | number | Record<string, unknown> | unknown[]
  variationKey?: string
  experimentId?: string
}

/** Configuration for the feature flag client. */
export interface FeatureFlagOptions {
  /** GraphQL endpoint URL (e.g., "/graphql" or "https://api.example.com/graphql") */
  graphqlUrl: string
  /** WebSocket endpoint for GraphQL subscriptions (e.g., "wss://api.example.com/graphqlws") */
  wsUrl?: string
  /** Installation ID shared with the analytics sink for consistent identity */
  installationId: string | (() => string | null)
  /** Auth token for authenticated flag evaluation */
  authToken?: string | (() => string | null)
  /** Client platform identifier for platform-based targeting rules (e.g., "WEB", "IOS", "ANDROID") */
  platform?: string
  /**
   * Maximum number of consecutive reconnect attempts before the client
   * gives up and stops trying to re-establish the subscription. Defaults
   * to 20.
   */
  maxReconnectAttempts?: number
  /**
   * Base delay in milliseconds for exponential reconnect backoff. The
   * actual delay doubles with each consecutive failure up to
   * [maxReconnectDelayMs]. Defaults to 1000.
   */
  baseReconnectDelayMs?: number
  /**
   * Upper bound in milliseconds for the exponential reconnect backoff.
   * Defaults to 60000 (one minute).
   */
  maxReconnectDelayMs?: number
}

type FlagChangeListener = (flags: Map<string, FlagEvaluation>) => void

/**
 * Feature flag client that fetches, caches, and synchronizes feature flag
 * evaluations for the current user/device. Supports real-time updates
 * via GraphQL subscriptions.
 */
export class FeatureFlagClient {
  private flags: Map<string, FlagEvaluation> = new Map()
  private listeners: Set<FlagChangeListener> = new Set()
  private ws: WebSocket | null = null
  private options: FeatureFlagOptions
  private reconnectAttempts: number = 0
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private intentionallyStopped: boolean = false
  /**
   * Monotonically increasing counter that tags every WebSocket the client
   * opens with a unique "generation". Each handler closes over the
   * generation it was registered for, and ignores events / reconnect
   * decisions when the live generation has moved on. This prevents an
   * old socket whose `onclose` is racing with `startListening` from
   * driving state into the new client.
   */
  private wsGeneration: number = 0
  /**
   * Set of flag keys whose last `refreshFlag` call failed. Each entry
   * is retried on the next subscription push so the local cache cannot
   * stay silently stale after a transient HTTP error.
   */
  private dirtyFlags: Set<string> = new Set()
  /** Set to true after initialize() completes successfully. */
  private initialized: boolean = false
  private initWarningLogged: boolean = false
  private readonly maxReconnectAttempts: number
  private readonly baseReconnectDelayMs: number
  private maxReconnectDelayMs: number
  private realtimeActiveCount: number = 0

  constructor(options: FeatureFlagOptions) {
    this.options = options
    this.maxReconnectAttempts = options.maxReconnectAttempts ?? 20
    this.baseReconnectDelayMs = options.baseReconnectDelayMs ?? 1000
    this.maxReconnectDelayMs = options.maxReconnectDelayMs ?? 60000

    this.tryLoadFromCookie()
  }

  /** Returns all currently cached flag evaluations. Useful for SSR hydration. */
  getCache(): FlagEvaluation[] {
    return Array.from(this.flags.values())
  }

  /** Restores the flag cache from a previous snapshot (e.g., SSR payload). */
  restoreCache(evaluations: FlagEvaluation[]): void {
    this.flags.clear()
    for (const evaluation of evaluations) {
      this.flags.set(evaluation.flagKey, evaluation)
    }
    this.initialized = true
    this.saveToCookie()
    this.notifyListeners()
  }

  private getCacheKey(): string {
    return `bosca_flags_${this.getInstallationId() || 'default'}`
  }

  private tryLoadFromCookie(): void {
    if (typeof document === 'undefined') return
    try {
      const key = this.getCacheKey()
      const saved = this.getCookie(key)
      const savedAuth = this.getCookie(`${key}_auth`)
      const currentAuth = this.getAuthToken() || ''
      
      if (saved && savedAuth === currentAuth) {
        const evaluations = JSON.parse(decodeURIComponent(saved)) as FlagEvaluation[]
        for (const evaluation of evaluations) {
          this.flags.set(evaluation.flagKey, evaluation)
        }
        // We consider it initialized if we loaded from a valid cache
        this.initialized = true
      }
    } catch (e) {
      // Ignore cookie errors
    }
  }

  private saveToCookie(): void {
    if (typeof document === 'undefined') return
    try {
      const key = this.getCacheKey()
      this.setCookie(key, encodeURIComponent(JSON.stringify(this.getCache())), 365)
      this.setCookie(`${key}_auth`, encodeURIComponent(this.getAuthToken() || ''), 365)
    } catch (e) {
      // Ignore
    }
  }

  private getCookie(name: string): string | null {
    if (typeof document === 'undefined') return null
    const match = document.cookie.match(new RegExp('(?:^|; )' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '=([^;]*)'))
    return match ? decodeURIComponent(match[1]!) : null
  }

  private setCookie(name: string, value: string, days: number): void {
    if (typeof document === 'undefined') return
    const expires = new Date(Date.now() + days * 86400000).toUTCString()
    document.cookie = `${name}=${value}; expires=${expires}; path=/; SameSite=Lax`
  }

  /** Resolves the current installation ID from the configured source. */
  private getInstallationId(): string {
    const installationId = typeof this.options.installationId === 'function'
      ? this.options.installationId()
      : this.options.installationId
    if (!installationId) {
      throw new Error('[FeatureFlagClient] installationId is required for feature flag evaluation')
    }
    return installationId
  }

  /** Resolves the current auth token from the configured source. */
  private getAuthToken(): string | null {
    if (!this.options.authToken) return null
    if (typeof this.options.authToken === 'function') {
      return this.options.authToken()
    }
    return this.options.authToken
  }

  /**
   * Builds the common HTTP headers for GraphQL requests, including
   * the Authorization header when an auth token is available.
   */
  private buildHeaders(): Record<string, string> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    }
    const token = this.getAuthToken()
    if (token) {
      headers['Authorization'] = `Bearer ${token}`
    }
    return headers
  }

  /**
   * Builds the typed `DeviceInput` payload sent to the server's `evaluate` /
   * `evaluateAll` GraphQL fields. Mirrors the analytics SDK's `Device` model
   * field-for-field so a single object populated client-side flows into both
   * flag evaluation (this method) and analytics ingestion.
   *
   * Browser-derived fields use `navigator` and `Intl` APIs; non-browser
   * runtimes get empty strings, which the server's targeting evaluator
   * treats the same as "no device" — every `DeviceAttribute` condition
   * fails by default rather than matching against an empty string.
   */
  private buildDeviceInput(installationId: string): Record<string, string> | null {
    if (typeof navigator === 'undefined') {
      console.warn('[FeatureFlagClient] navigator is undefined (SSR/Node.js context) — device-targeting conditions will not match')
      return null
    }
    const platform = this.options.platform || 'web'
    const locale = (typeof navigator.language === 'string' && navigator.language) || ''
    const timezone =
      (typeof Intl !== 'undefined' && Intl.DateTimeFormat?.().resolvedOptions?.().timeZone) || ''
    const userAgent = (typeof navigator.userAgent === 'string' && navigator.userAgent) || ''
    return {
      installationId,
      manufacturer: '',
      model: '',
      platform,
      primaryLocale: locale,
      systemName: 'web',
      timezone,
      type: 'web',
      version: userAgent,
    }
  }

  /**
   * Fetches all flag evaluations from the server and populates the local cache.
   * Throws on network errors or GraphQL errors so callers can handle failures.
   */
  async initialize(): Promise<void> {
    const installationId = this.getInstallationId()

    const response = await fetch(this.options.graphqlUrl, {
      method: 'POST',
      headers: this.buildHeaders(),
      body: JSON.stringify({
        query: `query EvaluateAllFlags($installationId: String!, $device: AnalyticsDevice) {
            featureFlags {
              evaluateAll(installationId: $installationId, device: $device) {
                flagKey
                value
                variationKey
                experimentId
              }
            }
          }`,
        variables: { installationId, device: this.buildDeviceInput(installationId) },
      }),
    })

    if (!response.ok) {
      throw new Error(`[FeatureFlagClient] Flag evaluation failed: HTTP ${response.status}`)
    }

    const json = await response.json()
    if (json?.errors?.length) {
      if (!json?.data?.featureFlags?.evaluateAll) {
        throw new Error(
          `[FeatureFlagClient] GraphQL errors during initialization: ${JSON.stringify(json.errors)}`,
        )
      }
      console.error('[FeatureFlagClient] GraphQL partial errors during initialization:', json.errors)
    }
    const evaluations: FlagEvaluation[] = json?.data?.featureFlags?.evaluateAll ?? []

    this.flags.clear()
    for (const evaluation of evaluations) {
      this.flags.set(evaluation.flagKey, evaluation)
    }
    this.initialized = true
    this.saveToCookie()
    this.notifyListeners()
  }

  /**
   * Re-evaluates all feature flags and reconnects the real-time subscription
   * (if active). Call this method after a user logs in or out so the local
   * cache reflects the new user's targeting rules.
   */
  async refresh(): Promise<void> {
    await this.initialize()
    if (this.realtimeActiveCount > 0 && !this.intentionallyStopped) {
      this.startListening()
    }
  }

  /**
   * Registers interest in real-time flag updates. The underlying WebSocket
   * subscription is opened when the first listener registers.
   */
  addRealtimeListener(): void {
    this.realtimeActiveCount++
    if (this.realtimeActiveCount === 1) {
      this.intentionallyStopped = false
      if (this.initialized) {
        this.startListening()
      }
    }
  }

  /**
   * Removes interest in real-time flag updates. The underlying WebSocket
   * subscription is closed when the last listener unregisters.
   */
  removeRealtimeListener(): void {
    if (this.realtimeActiveCount > 0) {
      this.realtimeActiveCount--
      if (this.realtimeActiveCount === 0) {
        this.stopListening()
      }
    }
  }

  /**
   * Starts listening for real-time flag updates via a GraphQL subscription
   * over WebSocket (graphql-transport-ws protocol). When a flag is updated,
   * the client re-fetches that flag's evaluation to get the new value.
   *
   * Waits for `connection_ack` before sending the `subscribe` message,
   * per the graphql-transport-ws protocol specification.
   */
  startListening(): void {
    if (!this.options.wsUrl || typeof WebSocket === 'undefined') return

    // Close any existing connection before creating a new one. This
    // bumps `wsGeneration` and detaches every handler from the old
    // socket so its in-flight events cannot drive state into the new
    // client.
    this.stopListening()
    this.intentionallyStopped = false

    // Capture the generation for THIS socket. Every closure below
    // checks `if (myGeneration !== this.wsGeneration) return` before
    // mutating client state, so a delayed event from a stale socket is
    // a no-op even if the handler was never detached.
    this.wsGeneration += 1
    const myGeneration = this.wsGeneration

    try {
      const ws = new WebSocket(this.options.wsUrl, 'graphql-transport-ws')
      this.ws = ws

      ws.onopen = () => {
        if (myGeneration !== this.wsGeneration) return
        this.reconnectAttempts = 0
        // Retry any flags that previous refresh attempts failed to fetch
        // — a healthy `onopen` is the cheapest signal that the server
        // is reachable again.
        this.retryDirtyFlags()
        // Resolve the token at connection time, not at startListening() time,
        // to avoid using a stale token if there was a delay before connecting
        const token = this.getAuthToken()
        const payload: Record<string, unknown> = {}
        if (token) {
          payload.authorization = `Bearer ${token}`
        }
        ws.send(JSON.stringify({ type: 'connection_init', payload }))
      }

      ws.onmessage = (event) => {
        if (myGeneration !== this.wsGeneration) return
        let msg: Record<string, unknown>
        try {
          msg = JSON.parse(event.data)
        } catch (e) {
          console.warn('[FeatureFlagClient] Received malformed WebSocket message:', e)
          return
        }

        if (msg.type === 'connection_ack') {
          ws.send(JSON.stringify({
            id: '1',
            type: 'subscribe',
            payload: {
              query: `subscription { flagUpdated { flagKey flagId action } }`,
            },
          }))
          return
        }

        if (msg.type === 'next') {
          const payload = msg.payload as Record<string, unknown> | undefined
          const data = payload?.data as Record<string, unknown> | undefined
          const flagUpdated = data?.flagUpdated as Record<string, string> | undefined
          if (flagUpdated) {
            const flagKey = flagUpdated.flagKey
            if (flagKey) {
              if (flagUpdated.action === 'DELETED') {
                this.flags.delete(flagKey)
                this.dirtyFlags.delete(flagKey)
                this.saveToCookie()
                this.notifyListeners()
              } else {
                this.refreshFlag(flagKey)
              }
            }
          }
        }
      }

      ws.onerror = (event) => {
        if (myGeneration !== this.wsGeneration) return
        console.error('[FeatureFlagClient] WebSocket error:', event)
      }

      ws.onclose = () => {
        if (myGeneration !== this.wsGeneration) return
        if (this.intentionallyStopped) return
        if (this.reconnectAttempts >= this.maxReconnectAttempts) {
          console.warn('[FeatureFlagClient] Max reconnection attempts reached, stopping.')
          this.notifyListeners()
          return
        }
        const delay = Math.min(
          this.baseReconnectDelayMs * Math.pow(2, this.reconnectAttempts),
          this.maxReconnectDelayMs,
        )
        this.reconnectAttempts++
        // Clear any prior timer before scheduling a new one — without
        // this an `onclose` racing with the previous timer's tick could
        // stack two reconnects.
        if (this.reconnectTimer !== null) {
          clearTimeout(this.reconnectTimer)
        }
        this.reconnectTimer = setTimeout(() => {
          this.reconnectTimer = null
          this.startListening()
        }, delay)
      }
    } catch (e) {
      console.error('[FeatureFlagClient] Failed to start subscription:', e)
    }
  }

  /** Stops the WebSocket subscription and cancels any pending reconnection. */
  stopListening(): void {
    this.intentionallyStopped = true
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.reconnectAttempts = 0
    // Bump the generation BEFORE closing so any handler that fires
    // synchronously inside `close()` sees the new generation and
    // short-circuits.
    this.wsGeneration += 1
    if (this.ws) {
      // Detach every handler so the old socket cannot deliver one last
      // event after `close()` returns. Belt-and-suspenders alongside the
      // generation check above.
      this.ws.onopen = null
      this.ws.onmessage = null
      this.ws.onerror = null
      this.ws.onclose = null
      this.ws.close()
      this.ws = null
    }
  }

  /**
   * Retries every flag in `dirtyFlags` one at a time. Called when the
   * WebSocket re-opens — that's the cheapest signal that the server is
   * reachable again, so piggy-backing the recovery on subscription
   * traffic avoids needing a separate timer.
   */
  private retryDirtyFlags(): void {
    if (this.dirtyFlags.size === 0) return
    const snapshot = Array.from(this.dirtyFlags)
    for (const key of snapshot) {
      // Fire-and-forget; refreshFlag handles its own success/failure.
      void this.refreshFlag(key)
    }
  }

  /** Returns a boolean flag value, defaulting if the flag is not found. */
  getBoolean(key: string, defaultValue: boolean = false): boolean {
    this.warnIfNotInitialized()
    const flag = this.flags.get(key)
    if (!flag) return defaultValue
    return typeof flag.value === 'boolean' ? flag.value : defaultValue
  }

  /** Returns a string flag value, defaulting if the flag is not found. */
  getString(key: string, defaultValue: string = ''): string {
    this.warnIfNotInitialized()
    const flag = this.flags.get(key)
    if (!flag) return defaultValue
    return typeof flag.value === 'string' ? flag.value : defaultValue
  }

  /** Returns a JSON flag value, or undefined if not found. */
  getJson(key: string): unknown {
    this.warnIfNotInitialized()
    return this.flags.get(key)?.value
  }

  /** Returns the raw flag evaluation for a key. */
  getFlag(key: string): FlagEvaluation | undefined {
    return this.flags.get(key)
  }

  /**
   * Logs a one-time warning when accessor methods are called before
   * initialize() has completed. This surfaces a common integration
   * mistake where all flags silently return defaults.
   */
  private warnIfNotInitialized(): void {
    if (!this.initialized && !this.initWarningLogged) {
      this.initWarningLogged = true
      console.warn(
        '[FeatureFlagClient] Flag accessor called before initialize() completed — ' +
        'all flags will return default values. Call await client.initialize() first.',
      )
    }
  }

  /** Registers a listener that is called whenever flag values change. Returns an unsubscribe function. */
  onChange(listener: FlagChangeListener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  /** Re-fetches a single flag evaluation from the server. */
  private async refreshFlag(flagKey: string): Promise<void> {
    const installationId = this.getInstallationId()

    try {
      const response = await fetch(this.options.graphqlUrl, {
        method: 'POST',
        headers: this.buildHeaders(),
        body: JSON.stringify({
          query: `query EvaluateFlag($flagKey: String!, $installationId: String!, $device: AnalyticsDevice) {
            featureFlags {
              evaluate(flagKey: $flagKey, installationId: $installationId, device: $device) {
                flagKey
                value
                variationKey
                experimentId
              }
            }
          }`,
          variables: { flagKey, installationId, device: this.buildDeviceInput(installationId) },
        }),
      })

      if (!response.ok) {
        // Mark the flag dirty so the next subscription event triggers
        // another refresh attempt. Without this, a transient HTTP error
        // would leave the local cache claiming to be in sync while
        // actually serving the previous value forever.
        console.error(`[FeatureFlagClient] HTTP ${response.status} refreshing flag '${flagKey}' (queued for retry)`)
        this.dirtyFlags.add(flagKey)
        return
      }

      const json = await response.json()
      if (json?.errors?.length) {
        console.error(`[FeatureFlagClient] GraphQL errors refreshing flag '${flagKey}':`, json.errors)
      }
      const evaluation: FlagEvaluation | undefined = json?.data?.featureFlags?.evaluate
      if (evaluation) {
        this.flags.set(evaluation.flagKey, evaluation)
        this.dirtyFlags.delete(flagKey)
        this.saveToCookie()
        this.notifyListeners()
      } else {
        // No data and no thrown exception — soft failure, queue retry.
        console.warn(`[FeatureFlagClient] No evaluation data returned for flag '${flagKey}' (queued for retry)`)
        this.dirtyFlags.add(flagKey)
      }
    } catch (e) {
      console.error(`[FeatureFlagClient] Failed to refresh flag '${flagKey}' (queued for retry):`, e)
      this.dirtyFlags.add(flagKey)
    }
  }

  private notifyListeners(): void {
    for (const listener of this.listeners) {
      try {
        listener(this.flags)
      } catch (e) {
        console.error('[FeatureFlagClient] Listener error:', e)
      }
    }
  }
}
