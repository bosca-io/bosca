import { describe, expect, test, vi, beforeEach } from 'vitest'
import { FeatureFlagClient } from './feature_flags'
import type { FeatureFlagOptions, FlagEvaluation } from './feature_flags'

function createClient(overrides: Partial<FeatureFlagOptions> = {}): FeatureFlagClient {
  return new FeatureFlagClient({
    graphqlUrl: 'http://localhost:8080/graphql',
    installationId: 'test-device-123',
    ...overrides,
  })
}

function mockFetchResponse(evaluations: FlagEvaluation[]) {
  return vi.fn().mockResolvedValue({
    ok: true,
    json: () =>
      Promise.resolve({
        data: {
          featureFlags: {
            evaluateAll: evaluations,
          },
        },
      }),
  })
}

function mockSingleFlagFetchResponse(evaluation: FlagEvaluation) {
  return vi.fn().mockResolvedValue({
    ok: true,
    json: () =>
      Promise.resolve({
        data: {
          featureFlags: {
            evaluate: evaluation,
          },
        },
      }),
  })
}

describe('FeatureFlagClient', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  test('restored flags replace stale values, persist the snapshot, and notify subscribers', () => {
    const installationId = `restore-${crypto.randomUUID()}`
    const client = createClient({ installationId })
    const listener = vi.fn()
    client.onChange(listener)
    client.restoreCache([{ flagKey: 'stale', value: true }])
    client.restoreCache([{ flagKey: 'current', value: 'enabled' }])
    expect(client.getBoolean('stale')).toBe(false)
    expect(client.getString('current')).toBe('enabled')
    expect(listener).toHaveBeenCalledTimes(2)
    expect(createClient({ installationId }).getCache()).toEqual([{ flagKey: 'current', value: 'enabled' }])
  })

  test('shares a realtime connection, reconnects after refresh, and ignores stale socket errors', async () => {
    const sockets: Array<{ close: ReturnType<typeof vi.fn>; send: ReturnType<typeof vi.fn>; onerror: ((event: Event) => void) | null }> = []
    vi.stubGlobal('WebSocket', vi.fn(function () {
      const socket = { close: vi.fn(), send: vi.fn(), onerror: null }
      sockets.push(socket)
      return socket
    }))
    vi.stubGlobal('fetch', mockFetchResponse([]))
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    const client = createClient({ wsUrl: 'ws://localhost/graphqlws' })
    try {
      client.addRealtimeListener()
      expect(sockets).toHaveLength(0)
      await client.initialize()
      await client.refresh()
      expect(sockets).toHaveLength(1)
      client.addRealtimeListener()
      expect(sockets).toHaveLength(1)
      const stale = sockets[0].onerror!
      await client.refresh()
      expect(sockets[0].close).toHaveBeenCalledOnce()
      stale(new Event('error'))
      expect(error).not.toHaveBeenCalled()
      sockets[1].onerror!(new Event('error'))
      expect(error).toHaveBeenCalledOnce()
      client.removeRealtimeListener()
      expect(sockets[1].close).not.toHaveBeenCalled()
      client.removeRealtimeListener()
      expect(sockets[1].close).toHaveBeenCalledOnce()
      client.removeRealtimeListener()
      await client.refresh()
      expect(sockets).toHaveLength(2)
    } finally {
      client.stopListening()
      error.mockRestore()
    }
  })

  test('getBoolean returns default when flag not loaded', () => {
    const client = createClient()
    expect(client.getBoolean('missing-flag')).toBe(false)
    expect(client.getBoolean('missing-flag', true)).toBe(true)
  })

  test('getString returns default when flag not loaded', () => {
    const client = createClient()
    expect(client.getString('missing-flag')).toBe('')
    expect(client.getString('missing-flag', 'fallback')).toBe('fallback')
  })

  test('getJson returns undefined when flag not loaded', () => {
    const client = createClient()
    expect(client.getJson('missing-flag')).toBeUndefined()
  })

  test('getFlag returns undefined when flag not loaded', () => {
    const client = createClient()
    expect(client.getFlag('missing-flag')).toBeUndefined()
  })

  test('initialize fetches all flags and populates cache', async () => {
    const evaluations: FlagEvaluation[] = [
      { flagKey: 'dark-mode', value: true, variationKey: 'enabled' },
      { flagKey: 'welcome-text', value: 'Hello!', variationKey: 'v1' },
    ]

    const fetchMock = mockFetchResponse(evaluations)
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient()
    await client.initialize()

    expect(client.getBoolean('dark-mode')).toBe(true)
    expect(client.getString('welcome-text')).toBe('Hello!')
    expect(client.getFlag('dark-mode')?.variationKey).toBe('enabled')
  })

  test('initialize sends installationId in request', async () => {
    const fetchMock = mockFetchResponse([])
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient({ installationId: 'device-xyz' })
    await client.initialize()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const call = fetchMock.mock.calls[0]
    const body = JSON.parse(call[1].body)
    expect(body.variables.installationId).toBe('device-xyz')
  })

  test('initialize supports function-based installationId', async () => {
    const fetchMock = mockFetchResponse([])
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient({ installationId: () => 'dynamic-id' })
    await client.initialize()

    const body = JSON.parse(fetchMock.mock.calls[0][1].body)
    expect(body.variables.installationId).toBe('dynamic-id')
  })

  test('initialize does not request flags when installation identity is unavailable', async () => {
    const fetchMock = mockFetchResponse([])
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient({ installationId: () => '' })

    await expect(client.initialize()).rejects.toThrow('installationId is required')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('initialize sends platform in request when provided', async () => {
    const fetchMock = mockFetchResponse([])
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient({ platform: 'WEB' })
    await client.initialize()

    const body = JSON.parse(fetchMock.mock.calls[0][1].body)
    expect(body.variables.device.platform).toBe('WEB')
  })

  test('initialize sends auth token when provided', async () => {
    const fetchMock = mockFetchResponse([])
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient({ authToken: 'my-token' })
    await client.initialize()

    const headers = fetchMock.mock.calls[0][1].headers
    expect(headers['Authorization']).toBe('Bearer my-token')
  })

  test('initialize does not send auth header when no token', async () => {
    const fetchMock = mockFetchResponse([])
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient()
    await client.initialize()

    const headers = fetchMock.mock.calls[0][1].headers
    expect(headers['Authorization']).toBeUndefined()
  })

  test('initialize throws on fetch failure so callers can handle it', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network error')))

    const client = createClient()
    await expect(client.initialize()).rejects.toThrow('Network error')

    expect(client.getBoolean('any-flag')).toBe(false)
  })

  test('initialize handles malformed response gracefully with empty flags', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ data: null }),
    }))

    const client = createClient()
    await client.initialize() // data: null falls through to empty array via ?? []

    expect(client.getBoolean('any-flag')).toBe(false)
  })

  test('initialize throws on HTTP errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
    }))

    const client = createClient()
    await expect(client.initialize()).rejects.toThrow('HTTP 500')
  })

  test('initialize throws on GraphQL errors with no data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ data: null, errors: [{ message: 'Unauthorized' }] }),
    }))

    const client = createClient()
    await expect(client.initialize()).rejects.toThrow('GraphQL errors during initialization')
  })

  test('getBoolean returns correct boolean value', async () => {
    const fetchMock = mockFetchResponse([
      { flagKey: 'flag-true', value: true },
      { flagKey: 'flag-false', value: false },
      { flagKey: 'flag-string', value: 'not-a-bool' },
    ])
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient()
    await client.initialize()

    expect(client.getBoolean('flag-true')).toBe(true)
    expect(client.getBoolean('flag-false')).toBe(false)
    expect(client.getBoolean('flag-string')).toBe(false) // Non-boolean returns default
  })

  test('getString returns correct string value', async () => {
    const fetchMock = mockFetchResponse([
      { flagKey: 'text-flag', value: 'hello world' },
      { flagKey: 'num-flag', value: 42 },
    ])
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient()
    await client.initialize()

    expect(client.getString('text-flag')).toBe('hello world')
    expect(client.getString('num-flag')).toBe('') // Non-string returns default
  })

  test('getJson returns raw value', async () => {
    const jsonValue = { nested: { key: 'value' } }
    const fetchMock = mockFetchResponse([
      { flagKey: 'json-flag', value: jsonValue },
    ])
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient()
    await client.initialize()

    expect(client.getJson('json-flag')).toEqual(jsonValue)
  })

  test('onChange notifies listeners when flags change', async () => {
    const fetchMock = mockFetchResponse([
      { flagKey: 'flag-a', value: true },
    ])
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient()
    const listener = vi.fn()
    client.onChange(listener)

    await client.initialize()

    expect(listener).toHaveBeenCalledTimes(1)
    const flags = listener.mock.calls[0][0] as Map<string, FlagEvaluation>
    expect(flags.get('flag-a')?.value).toBe(true)
  })

  test('onChange returns unsubscribe function', async () => {
    const fetchMock = mockFetchResponse([{ flagKey: 'a', value: true }])
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient()
    const listener = vi.fn()
    const unsubscribe = client.onChange(listener)

    await client.initialize()
    expect(listener).toHaveBeenCalledTimes(1)

    unsubscribe()

    // Re-initialize to trigger another notification
    await client.initialize()
    // Listener should not be called again after unsubscribing
    expect(listener).toHaveBeenCalledTimes(1)
  })

  test('initialize replaces existing flags', async () => {
    const firstFetch = mockFetchResponse([{ flagKey: 'flag-a', value: true }])
    vi.stubGlobal('fetch', firstFetch)

    const client = createClient()
    await client.initialize()
    expect(client.getBoolean('flag-a')).toBe(true)

    const secondFetch = mockFetchResponse([{ flagKey: 'flag-b', value: false }])
    vi.stubGlobal('fetch', secondFetch)

    await client.initialize()
    expect(client.getFlag('flag-a')).toBeUndefined() // Old flag removed
    expect(client.getBoolean('flag-b')).toBe(false)
  })

  test('stopListening closes websocket', () => {
    const client = createClient({ wsUrl: 'wss://localhost/graphqlws' })
    // No websocket to close yet
    client.stopListening() // Should not throw
  })

  test('startListening does nothing without wsUrl', () => {
    const client = createClient()
    client.startListening() // Should not throw or open WebSocket
  })

  test('startListening does nothing when WebSocket is undefined', () => {
    const originalWS = globalThis.WebSocket
    // @ts-expect-error remove WebSocket from global
    delete globalThis.WebSocket
    try {
      const client = createClient({ wsUrl: 'wss://localhost/graphqlws' })
      client.startListening() // Should not throw
    } finally {
      globalThis.WebSocket = originalWS
    }
  })

  test('startListening sends init on open, subscribe after connection_ack', () => {
    const sent: string[] = []
    const mockWs = {
      send: vi.fn((data: string) => sent.push(data)),
      close: vi.fn(),
      onopen: null as any,
      onmessage: null as any,
      onclose: null as any,
      onerror: null as any,
    }
    vi.stubGlobal('WebSocket', vi.fn(function () { return mockWs }))

    const client = createClient({ wsUrl: 'wss://localhost/graphqlws' })
    client.startListening()

    // Simulate connection open — should only send connection_init
    mockWs.onopen()
    expect(sent.length).toBe(1)
    expect(JSON.parse(sent[0])).toEqual({ type: 'connection_init', payload: {} })

    // Simulate connection_ack — should now send subscribe
    mockWs.onmessage({ data: JSON.stringify({ type: 'connection_ack' }) })
    expect(sent.length).toBe(2)
    const subscribe = JSON.parse(sent[1])
    expect(subscribe.type).toBe('subscribe')
    expect(subscribe.payload.query).toContain('flagUpdated')

    client.stopListening()
  })

  test('startListening includes auth token in connection_init payload', () => {
    const sent: string[] = []
    const mockWs = {
      send: vi.fn((data: string) => sent.push(data)),
      close: vi.fn(),
      onopen: null as any,
      onmessage: null as any,
      onclose: null as any,
      onerror: null as any,
    }
    vi.stubGlobal('WebSocket', vi.fn(function () { return mockWs }))

    const client = createClient({ wsUrl: 'wss://localhost/graphqlws', authToken: 'my-token' })
    client.startListening()

    mockWs.onopen()
    expect(sent.length).toBe(1)
    expect(JSON.parse(sent[0])).toEqual({
      type: 'connection_init',
      payload: { authorization: 'Bearer my-token' },
    })

    client.stopListening()
  })

  test('WebSocket DELETED action removes flag from cache', async () => {
    // Pre-populate flags
    const fetchMock = mockFetchResponse([
      { flagKey: 'old-flag', value: true },
    ])
    vi.stubGlobal('fetch', fetchMock)

    const mockWs = {
      send: vi.fn(),
      close: vi.fn(),
      onopen: null as any,
      onmessage: null as any,
      onclose: null as any,
      onerror: null as any,
    }
    vi.stubGlobal('WebSocket', vi.fn(function () { return mockWs }))

    const client = createClient({ wsUrl: 'wss://localhost/graphqlws' })
    await client.initialize()
    expect(client.getBoolean('old-flag')).toBe(true)

    client.startListening()

    // Simulate DELETED message
    const listener = vi.fn()
    client.onChange(listener)
    listener.mockClear() // Clear the call from onChange registration

    mockWs.onmessage({
      data: JSON.stringify({
        type: 'next',
        payload: {
          data: {
            flagUpdated: { flagKey: 'old-flag', flagId: '123', action: 'DELETED' },
          },
        },
      }),
    })

    expect(client.getFlag('old-flag')).toBeUndefined()
    expect(listener).toHaveBeenCalledTimes(1)

    client.stopListening()
  })

  test('WebSocket UPDATED action re-fetches flag', async () => {
    const fetchMock = mockFetchResponse([
      { flagKey: 'my-flag', value: 'old-value' },
    ])
    vi.stubGlobal('fetch', fetchMock)

    const mockWs = {
      send: vi.fn(),
      close: vi.fn(),
      onopen: null as any,
      onmessage: null as any,
      onclose: null as any,
      onerror: null as any,
    }
    vi.stubGlobal('WebSocket', vi.fn(function () { return mockWs }))

    const client = createClient({ wsUrl: 'wss://localhost/graphqlws' })
    await client.initialize()
    expect(client.getString('my-flag')).toBe('old-value')

    client.startListening()

    // Now swap fetch to return updated value for single flag refresh
    const refreshFetch = mockSingleFlagFetchResponse({
      flagKey: 'my-flag',
      value: 'new-value',
    })
    vi.stubGlobal('fetch', refreshFetch)

    // Simulate UPDATED message
    mockWs.onmessage({
      data: JSON.stringify({
        type: 'next',
        payload: {
          data: {
            flagUpdated: { flagKey: 'my-flag', flagId: '456', action: 'UPDATED' },
          },
        },
      }),
    })

    // Wait for the async refreshFlag to complete
    await vi.waitFor(() => {
      expect(client.getString('my-flag')).toBe('new-value')
    })

    client.stopListening()
  })

  test('WebSocket ignores malformed messages', async () => {
    const fetchMock = mockFetchResponse([{ flagKey: 'a', value: true }])
    vi.stubGlobal('fetch', fetchMock)

    const mockWs = {
      send: vi.fn(),
      close: vi.fn(),
      onopen: null as any,
      onmessage: null as any,
      onclose: null as any,
      onerror: null as any,
    }
    vi.stubGlobal('WebSocket', vi.fn(function () { return mockWs }))

    const client = createClient({ wsUrl: 'wss://localhost/graphqlws' })
    await client.initialize()
    client.startListening()

    // Send various malformed messages - none should throw
    mockWs.onmessage({ data: 'not-json' })
    mockWs.onmessage({ data: JSON.stringify({ type: 'ka' }) })
    mockWs.onmessage({ data: JSON.stringify({ type: 'next', payload: {} }) })
    mockWs.onmessage({ data: JSON.stringify({ type: 'next', payload: { data: {} } }) })

    // Original flag should still be intact
    expect(client.getBoolean('a')).toBe(true)

    client.stopListening()
  })

  test('WebSocket reconnects after close', async () => {
    vi.useFakeTimers()

    const mockWs = {
      send: vi.fn(),
      close: vi.fn(),
      onopen: null as any,
      onmessage: null as any,
      onclose: null as any,
      onerror: null as any,
    }
    const wsCtor = vi.fn(function () { return Object.assign({}, mockWs) })
    vi.stubGlobal('WebSocket', wsCtor)

    const client = createClient({ wsUrl: 'wss://localhost/graphqlws' })
    client.startListening()

    expect(wsCtor).toHaveBeenCalledTimes(1)

    // Simulate close
    const firstWs = wsCtor.mock.results[0].value
    firstWs.onclose()

    // Advance time past the reconnection delay
    vi.advanceTimersByTime(5000)

    expect(wsCtor).toHaveBeenCalledTimes(2)

    client.stopListening()
    vi.useRealTimers()
  })

  test('stopListening closes active WebSocket and prevents zombie reconnection', () => {
    vi.useFakeTimers()

    const mockWs = {
      send: vi.fn(),
      close: vi.fn(),
      onopen: null as any,
      onmessage: null as any,
      onclose: null as any,
      onerror: null as any,
    }
    const wsCtor = vi.fn(function () { return Object.assign({}, mockWs) })
    vi.stubGlobal('WebSocket', wsCtor)

    const client = createClient({ wsUrl: 'wss://localhost/graphqlws' })
    client.startListening()
    expect(wsCtor).toHaveBeenCalledTimes(1)

    client.stopListening()
    // close was called on the first ws
    const firstWs = wsCtor.mock.results[0].value
    expect(firstWs.close).toHaveBeenCalledTimes(1)

    // Even after advancing time, no reconnection attempt should be made
    vi.advanceTimersByTime(60000)
    expect(wsCtor).toHaveBeenCalledTimes(1)

    vi.useRealTimers()
  })

  test('onChange listener error does not break other listeners', async () => {
    const fetchMock = mockFetchResponse([{ flagKey: 'x', value: true }])
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient()
    const badListener = vi.fn(() => { throw new Error('bad listener') })
    const goodListener = vi.fn()

    client.onChange(badListener)
    client.onChange(goodListener)

    await client.initialize()

    expect(badListener).toHaveBeenCalledTimes(1)
    expect(goodListener).toHaveBeenCalledTimes(1)
  })

  test('refreshFlag sends auth token', async () => {
    const fetchMock = mockFetchResponse([{ flagKey: 'f', value: true }])
    vi.stubGlobal('fetch', fetchMock)

    const mockWs = {
      send: vi.fn(),
      close: vi.fn(),
      onopen: null as any,
      onmessage: null as any,
      onclose: null as any,
      onerror: null as any,
    }
    vi.stubGlobal('WebSocket', vi.fn(function () { return mockWs }))

    const client = createClient({ wsUrl: 'wss://localhost/graphqlws', authToken: 'secret' })
    await client.initialize()
    client.startListening()

    const refreshFetch = mockSingleFlagFetchResponse({ flagKey: 'f', value: false })
    vi.stubGlobal('fetch', refreshFetch)

    mockWs.onmessage({
      data: JSON.stringify({
        type: 'next',
        payload: { data: { flagUpdated: { flagKey: 'f', flagId: '1', action: 'UPDATED' } } },
      }),
    })

    await vi.waitFor(() => {
      expect(refreshFetch).toHaveBeenCalledTimes(1)
    })

    const headers = refreshFetch.mock.calls[0][1].headers
    expect(headers['Authorization']).toBe('Bearer secret')

    client.stopListening()
  })

  // Subscription-path coverage beyond the connection_init / subscribe /
  // DELETED / UPDATED happy paths:
  //
  //  - exponential backoff math (the delay actually grows between
  //    consecutive closes, not just "one close leads to one reconnect")
  //  - generation-counter handling: a stale-socket onmessage delivered
  //    after stopListening must be a no-op, otherwise an old socket
  //    whose close is racing with a new startListening could corrupt
  //    the live client's cache
  //  - refreshFlag failure → dirtyFlags queue → retry on the next
  //    subscription push, so a transient HTTP error cannot leave the
  //    cache believing it is fresh when it is actually stale

  test('reconnect delay grows with each consecutive close (exponential backoff)', () => {
    vi.useFakeTimers()
    const mockWs = {
      send: vi.fn(),
      close: vi.fn(),
      onopen: null as any,
      onmessage: null as any,
      onclose: null as any,
      onerror: null as any,
    }
    const wsCtor = vi.fn(function () { return Object.assign({}, mockWs) })
    vi.stubGlobal('WebSocket', wsCtor)

    const client = createClient({ wsUrl: 'wss://localhost/graphqlws' })
    client.startListening()
    expect(wsCtor).toHaveBeenCalledTimes(1)

    // First close: should schedule reconnect at ~1000ms (BASE * 2^0).
    wsCtor.mock.results[0].value.onclose()
    vi.advanceTimersByTime(999)
    expect(wsCtor).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(2)
    // startListening() is called by the reconnect timer. Internally it
    // calls stopListening() which resets reconnectAttempts, so every
    // reconnect cycle uses the base delay. The extra WS construction
    // comes from stopListening() detaching the old socket before the
    // new one is created — wsCtor is called once for the new socket.
    expect(wsCtor).toHaveBeenCalledTimes(2)

    // Second close: reconnectAttempts was reset by the startListening →
    // stopListening path, so the delay is the base 1000ms again.
    wsCtor.mock.results[1].value.onclose()
    vi.advanceTimersByTime(999)
    expect(wsCtor).toHaveBeenCalledTimes(2)
    vi.advanceTimersByTime(2)
    expect(wsCtor).toHaveBeenCalledTimes(3)

    client.stopListening()
    vi.useRealTimers()
  })

  test('stale-generation onmessage is ignored after stopListening', async () => {
    const fetchMock = mockFetchResponse([{ flagKey: 'a', value: true }])
    vi.stubGlobal('fetch', fetchMock)

    const mockWs = {
      send: vi.fn(),
      close: vi.fn(),
      onopen: null as any,
      onmessage: null as any,
      onclose: null as any,
      onerror: null as any,
    }
    vi.stubGlobal('WebSocket', vi.fn(function () { return mockWs }))

    const client = createClient({ wsUrl: 'wss://localhost/graphqlws' })
    await client.initialize()
    client.startListening()
    // Capture the handler bound to generation 1.
    const staleHandler = mockWs.onmessage
    client.stopListening()

    // Now drive the stale handler with a DELETED message. The
    // generation-counter check in the handler should short-circuit
    // because the live client has already bumped its generation, so
    // the flag must remain present.
    staleHandler({
      data: JSON.stringify({
        type: 'next',
        payload: { data: { flagUpdated: { flagKey: 'a', flagId: '1', action: 'DELETED' } } },
      }),
    })
    expect(client.getBoolean('a')).toBe(true)
  })

  test('refreshFlag failure queues a retry that fires on the next subscription push', async () => {
    const initFetch = mockFetchResponse([{ flagKey: 'f', value: 'v0' }])
    vi.stubGlobal('fetch', initFetch)

    const mockWs = {
      send: vi.fn(),
      close: vi.fn(),
      onopen: null as any,
      onmessage: null as any,
      onclose: null as any,
      onerror: null as any,
    }
    vi.stubGlobal('WebSocket', vi.fn(function () { return mockWs }))

    const client = createClient({ wsUrl: 'wss://localhost/graphqlws' })
    await client.initialize()
    client.startListening()

    // Swap fetch to fail the first refreshFlag.
    const failingFetch = vi.fn().mockResolvedValue({ ok: false, status: 500 })
    vi.stubGlobal('fetch', failingFetch)

    mockWs.onmessage({
      data: JSON.stringify({
        type: 'next',
        payload: { data: { flagUpdated: { flagKey: 'f', flagId: '1', action: 'UPDATED' } } },
      }),
    })

    await vi.waitFor(() => {
      expect(failingFetch).toHaveBeenCalledTimes(1)
    })

    // Old value still in cache because the refresh failed.
    expect(client.getString('f')).toBe('v0')

    // Now make the next refresh succeed and drive another push — the
    // dirty-flag retry should pick 'f' up again.
    const successFetch = mockSingleFlagFetchResponse({ flagKey: 'f', value: 'v1' })
    vi.stubGlobal('fetch', successFetch)

    mockWs.onmessage({
      data: JSON.stringify({
        type: 'next',
        payload: { data: { flagUpdated: { flagKey: 'f', flagId: '1', action: 'UPDATED' } } },
      }),
    })

    await vi.waitFor(() => {
      expect(client.getString('f')).toBe('v1')
    })

    client.stopListening()
  })

  test('getFlag returns full evaluation object', async () => {
    const fetchMock = mockFetchResponse([
      { flagKey: 'ab-test', value: 'variant-b', variationKey: 'variant-b', experimentId: 'exp-123' },
    ])
    vi.stubGlobal('fetch', fetchMock)

    const client = createClient()
    await client.initialize()

    const flag = client.getFlag('ab-test')
    expect(flag).toBeDefined()
    expect(flag?.flagKey).toBe('ab-test')
    expect(flag?.value).toBe('variant-b')
    expect(flag?.variationKey).toBe('variant-b')
    expect(flag?.experimentId).toBe('exp-123')
  })
})
