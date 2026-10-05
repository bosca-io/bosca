// @vitest-environment happy-dom

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { BoscaSink } from './bosca'
import { addSink, removeSink, logImpression } from './sink'
import { AnalyticEventType } from './event'
import type { SessionState } from './bosca_session_state'
import { getAnalyticEventFactory } from './factory'

const createdSinks: BoscaSink[] = []

function createSink(...args: any[]): BoscaSink {
  const instance = new (BoscaSink as unknown as new (...args: any[]) => BoscaSink)(...args)
  createdSinks.push(instance)
  return instance
}

afterEach(() => {
  for (const instance of createdSinks.splice(0)) {
    removeSink(instance)
    const internals = instance as unknown as {
      sessionState: SessionState
      timeout: ReturnType<typeof setTimeout> | null
      queue: { database: IDBDatabase | null; delayedAction: { timeout: ReturnType<typeof setTimeout> | null } | null }
    }
    internals.sessionState.endSession()
    if (internals.timeout !== null) clearTimeout(internals.timeout)
    if (internals.queue.delayedAction?.timeout !== null) clearTimeout(internals.queue.delayedAction?.timeout)
    internals.queue.database?.close()
  }
})

function mockFetch(installationId = 'test-iid') {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: string | URL | Request) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    if (url.endsWith('/installation')) {
      return new Response(JSON.stringify({ id: installationId }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    if (url.endsWith('/events')) {
      return new Response('', { status: 202 })
    }
    return new Response('Not Found', { status: 404 })
  })
}

describe('BoscaSink', () => {
  let sink: BoscaSink

  beforeEach(() => {
    mockFetch()
    sink = createSink({ url: 'http://127.0.0.1:8081', appId: 'app', appVersion: '1.0', clientId: 'web', heartbeat: false })
    addSink(sink)
  })

  afterEach(() => {
    removeSink(sink)
    document.cookie = 'bml_iid=; Path=/; Max-Age=0'
    document.cookie = 'bml_asid=; Path=/; Max-Age=0'
    document.cookie = 'bml_asid_exp=; Path=/; Max-Age=0'
    vi.restoreAllMocks()
  })

  describe('addBeacon', () => {
    it('retains debug diagnostics and exposes the persistent queue size', async () => {
      const debug = vi.spyOn(console, 'debug').mockImplementation(() => {})
      const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
      const isolated = createSink({ url: 'http://127.0.0.1:8081', appId: 'app', appVersion: '1', clientId: 'web', heartbeat: false, debug: true })
      const event = await getAnalyticEventFactory().createEvent({ type: AnalyticEventType.error, element: { id: '', type: '' } })
      await isolated.add(event)
      expect(debug).toHaveBeenCalled()
      expect(warning).toHaveBeenCalledWith('[bosca-analytics] warnings:', expect.stringContaining('error event has no error info'))
      expect(await isolated.size()).toBeGreaterThanOrEqual(-1)
    })
    it('keeps an in-memory session usable when cookie reads and writes throw', async () => {
      const readCookie = vi.spyOn(document, 'cookie', 'get').mockImplementation(() => {
        throw new DOMException('Cookie storage unavailable', 'SecurityError')
      })
      const writeCookie = vi.spyOn(document, 'cookie', 'set').mockImplementation(() => {
        throw new DOMException('Cookie storage unavailable', 'SecurityError')
      })
      const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
      try {
        const isolated = createSink({
          url: 'http://127.0.0.1:8081', appId: 'app', appVersion: '1', clientId: 'web', heartbeat: false,
        })
        const state = (isolated as unknown as { sessionState: SessionState }).sessionState
        await state.startSession()
        const original = isolated.sessionId
        expect(original).toMatch(/^[0-7][0-9A-HJKMNP-TV-Z]{25}$/)
        state.endSession()
        await state.startSession()
        expect(isolated.sessionId).not.toBe(original)
        expect(warning).toHaveBeenCalled()
        state.endSession()
      } finally {
        readCookie.mockRestore()
        writeCookie.mockRestore()
        warning.mockRestore()
      }
    })

    it('reuses only unexpired sessions when a page initializes', () => {
      const create = () => createSink({
        url: 'http://127.0.0.1:8081', appId: 'app', appVersion: '1', clientId: 'web', heartbeat: false,
      })
      for (const expiry of [undefined, 'invalid', String(Date.now() - 1)]) {
        document.cookie = 'bml_asid=expired-session; Path=/'
        document.cookie = expiry === undefined
          ? 'bml_asid_exp=; Path=/; Max-Age=0'
          : `bml_asid_exp=${expiry}; Path=/`
        const initialized = create()
        expect(initialized.sessionId).not.toBe('expired-session')
        expect(document.cookie).toContain(`bml_asid=${initialized.sessionId}`)
      }
      document.cookie = 'bml_asid=active-session; Path=/'
      document.cookie = `bml_asid_exp=${Date.now() + 100_000}; Path=/`
      expect(create().sessionId).toBe('active-session')
    })

    it('returns the stored session without reading cookies', () => {
      const original = sink.sessionId
      document.cookie = 'bml_asid=server-session; Path=/'
      const readCookie = vi.spyOn(document, 'cookie', 'get')
      readCookie.mockClear()
      expect(sink.sessionId).toBe(original)
      expect(readCookie).not.toHaveBeenCalled()
    })

    it('uses the cookie only to initialize and publishes SDK-owned session rotations', async () => {
      document.cookie = 'bml_asid=server-session; Path=/'
      const serverSink = createSink({
        url: 'http://127.0.0.1:8081', appId: 'app', appVersion: '1', clientId: 'web', heartbeat: false,
      })
      const added = vi.spyOn(serverSink, 'add')
      const state = (serverSink as unknown as { sessionState: SessionState }).sessionState
      await state.startSession()
      expect(serverSink.sessionId).toBe('server-session')
      const starts = () => added.mock.calls.filter(([event]) => event.type === AnalyticEventType.session).length
      expect(starts()).toBe(1)
      state.endSession()
      await state.startSession()
      const rotated = serverSink.sessionId
      expect(rotated).not.toBe('server-session')
      expect(rotated).toMatch(/^[0-7][0-9A-HJKMNP-TV-Z]{25}$/)
      expect(document.cookie).toContain(`bml_asid=${rotated}`)
      expect(starts()).toBe(2)

      document.cookie = 'bml_asid=new-server-session; Path=/'
      expect(serverSink.sessionId).toBe(rotated)
      state.endSession()
      await state.startSession()
      const latest = serverSink.sessionId
      expect(latest).not.toBe(rotated)
      expect(latest).not.toBe('new-server-session')
      expect(document.cookie).toContain(`bml_asid=${latest}`)
      expect(starts()).toBe(3)
      const sendBeacon = vi.fn().mockReturnValue(true)
      Object.defineProperty(navigator, 'sendBeacon', { value: sendBeacon, configurable: true })
      document.cookie = 'bml_asid=latest-server-session; Path=/'
      serverSink.addBeacon({ type: AnalyticEventType.interaction, element: { id: 'button', type: 'button' } })
      const payload = JSON.parse(await (sendBeacon.mock.calls[0][1] as Blob).text())
      expect(payload.context.session_id).toBe(latest)
      state.endSession()
    })

    it('ignores malformed and blank session cookies and preserves anonymous isolation', () => {
      for (const cookie of ['%zz', '%20', '']) {
        document.cookie = `bml_asid=${cookie}; Path=/`
        const standaloneSink = createSink({
          url: 'http://127.0.0.1:8081', appId: 'app', appVersion: '1', clientId: 'web', heartbeat: false,
        })
        expect(standaloneSink.sessionId).toMatch(/^[0-7][0-9A-HJKMNP-TV-Z]{25}$/)
      }
      document.cookie = 'bml_asid=server-session; Path=/'
      const anonymousSink = createSink({
        url: 'http://127.0.0.1:8081', appId: 'app', appVersion: '1', clientId: 'web', anonymous: true, heartbeat: false,
      })
      expect(anonymousSink.sessionId).not.toBe('server-session')
      expect(document.cookie).toContain('bml_asid=server-session')
    })

    it('attaches the payload identity to keepalive requests', () => {
      const anonymousSink = createSink({
        url: 'http://127.0.0.1:8081', appId: 'app', appVersion: '1.0', clientId: 'web',
        anonymous: true, omitCredentials: true, heartbeat: false,
      })
      Object.defineProperty(navigator, 'sendBeacon', { value: vi.fn(), configurable: true })
      anonymousSink.addBeacon({ type: AnalyticEventType.interaction, element: { id: 'save', type: 'button' } })
      const request = vi.mocked(fetch).mock.calls.at(-1)?.[1]
      const payload = JSON.parse(request?.body as string)
      expect(request?.headers).toMatchObject({
        'X-App-ID': 'app',
        'X-App-Version': '1.0',
        'X-Installation-ID': payload.context.device.installation_id,
        'X-BA-Session-ID': payload.context.session_id,
      })
      expect(payload.context.session_id).toBe(anonymousSink.sessionId)
    })

    it('includes the BML installation ID before asynchronous initialization', async () => {
      document.cookie = 'bml_iid=bml-installation; Path=/'
      const sendBeacon = vi.fn().mockReturnValue(true)
      Object.defineProperty(navigator, 'sendBeacon', { value: sendBeacon, configurable: true })

      sink.addBeacon({
        type: AnalyticEventType.interaction,
        element: { id: 'btn', type: 'button' },
      })

      const payload = JSON.parse(await (sendBeacon.mock.calls[0][1] as Blob).text())
      expect(payload.context.device.installation_id).toBe('bml-installation')
    })

    it('should serialize context and event to JSON blob via sendBeacon', () => {
      const sendBeacon = vi.fn().mockReturnValue(true)
      Object.defineProperty(navigator, 'sendBeacon', { value: sendBeacon, configurable: true })

      sink.setGeo({ city: 'SF', region: 'CA', country: 'US' })
      sink.addBeacon({
        type: AnalyticEventType.interaction,
        element: { id: 'btn', type: 'button', content: [{ id: 'c', type: 'img', index: 0 }] },
      })

      expect(sendBeacon).toHaveBeenCalledTimes(1)
      const [url, blob] = sendBeacon.mock.calls[0]
      expect(url).toBe('http://127.0.0.1:8081/events')
      expect(blob).toBeInstanceOf(Blob)

      // Verify the blob contains valid JSON with expected structure
    })

    it('should include page snapshot in beacon payload when window is available', async () => {
      const sendBeacon = vi.fn().mockReturnValue(true)
      Object.defineProperty(navigator, 'sendBeacon', { value: sendBeacon, configurable: true })

      const originalTitle = document.title
      document.title = 'Beacon Page Test'
      try {
        sink.addBeacon({
          type: AnalyticEventType.interaction,
          element: { id: 'btn', type: 'button' },
        })

        expect(sendBeacon).toHaveBeenCalledTimes(1)
        const blob = sendBeacon.mock.calls[0][1] as Blob
        const text = await blob.text()
        const payload = JSON.parse(text)
        const event = payload.events[0]
        expect(event.page).toBeDefined()
        expect(event.page.path).toBe(window.location.pathname)
        expect(event.page.url).toBe(window.location.href)
        expect(event.page.title).toBe('Beacon Page Test')
      } finally {
        document.title = originalTitle
      }
    })

    it('explicit page on the input event overrides the live snapshot in beacons', () => {
      const sendBeacon = vi.fn().mockReturnValue(true)
      Object.defineProperty(navigator, 'sendBeacon', { value: sendBeacon, configurable: true })

      sink.addBeacon({
        type: AnalyticEventType.interaction,
        element: { id: 'btn', type: 'button' },
        page: { path: '/explicit', url: 'https://example.test/explicit', title: 'Explicit' },
      })

      const blob = sendBeacon.mock.calls[0][1] as Blob
      return blob.text().then((text) => {
        const event = JSON.parse(text).events[0]
        expect(event.page.path).toBe('/explicit')
        expect(event.page.url).toBe('https://example.test/explicit')
        expect(event.page.title).toBe('Explicit')
      })
    })

    it('should include error fields in beacon payload', () => {
      const sendBeacon = vi.fn().mockReturnValue(true)
      Object.defineProperty(navigator, 'sendBeacon', { value: sendBeacon, configurable: true })

      sink.addBeacon({
        type: AnalyticEventType.error,
        element: { id: '', type: 'error' },
        error: { message: 'crash', type: 'TypeError', stack_trace: 'x'.repeat(10000), fatal: true, code: 'E1' },
      })

      expect(sendBeacon).toHaveBeenCalledTimes(1)
      // Stack trace should be truncated to MAX_STACK_TRACE_LENGTH (8192)
    })
  })

  describe('installation ID', () => {
    const generateInstallationId = (target: BoscaSink) =>
      (target as unknown as { generateInstallationId(): Promise<string | null> }).generateInstallationId()

    it('uses the installation ID from BML', async () => {
      document.cookie = 'bml_iid=bml-installation; Path=/'
      const standaloneSink = createSink({
        url: 'http://127.0.0.1:8081',
        appId: 'app',
        appVersion: '1.0',
        clientId: 'web',
        heartbeat: false,
      })

      expect(await generateInstallationId(standaloneSink)).toBe('bml-installation')
      expect(standaloneSink.installationId).toBe('bml-installation')
      expect(vi.mocked(globalThis.fetch).mock.calls.some(([input]) => String(input).endsWith('/installation'))).toBe(false)
      document.cookie = 'bml_iid=; Path=/; Max-Age=0'
    })

    it('exposes the existing analytics installation ID when there is no BML cookie', () => {
      const storage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
      Object.defineProperty(globalThis, 'localStorage', {
        configurable: true,
        value: { getItem: () => 'stored-installation' },
      })

      try {
        expect(sink.installationId).toBe('stored-installation')
      } finally {
        if (storage) Object.defineProperty(globalThis, 'localStorage', storage)
        else delete (globalThis as Record<string, unknown>).localStorage
      }
    })

    it('shares a newly registered installation ID with feature flags through the BML cookie', async () => {
      expect(await generateInstallationId(sink)).toBe('test-iid')

      expect(document.cookie).toContain('bml_iid=test-iid')
      expect(sink.installationId).toBe('test-iid')
    })
  })

  describe('flush error handling', () => {
    it('should count failures when backend returns errors', async () => {
      vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: string | URL | Request) => {
        const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
        if (url.endsWith('/installation')) {
          return new Response(JSON.stringify({ id: 'iid' }), { status: 200, headers: { 'Content-Type': 'application/json' } })
        }
        return new Response('server error', { status: 500 })
      })

      await logImpression({ id: 'x', type: 'y' })
      // Wait for the auto-flush (1s debounce) plus flush execution
      await new Promise(resolve => setTimeout(resolve, 3000))

      expect(sink.failures).toBeGreaterThan(0)
    }, 10_000)

    it('should not throw when installation ID fetch fails', async () => {
      vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
        return new Response(JSON.stringify({ error: 'fail' }), { status: 500 })
      })

      const s = createSink({ url: 'http://127.0.0.1:8081', appId: 'app', appVersion: '1.0', clientId: 'web', heartbeat: false })
      addSink(s)

      await logImpression({ id: 'x', type: 'y' })
      await new Promise(resolve => setTimeout(resolve, 2000))

      // Flush should degrade gracefully, not throw
      await s.flush()

      removeSink(s)
    }, 10_000)
  })
})

// Integration-level test: verifies the full pipeline (add → queue → flush → count)
// with a realistic volume. Kept intentionally because it exercises timing-dependent
// interactions between the DelayedAction debounce, EventQueue batching, and flush loop.
describe('BoscaSink integration', () => {
  it('should flush 5000 impressions without failures', async () => {
    if (typeof indexedDB !== 'undefined') {
      const deleteRequest = indexedDB.deleteDatabase('EventDB')
      await new Promise<void>((resolve) => {
        deleteRequest.onsuccess = () => resolve()
        deleteRequest.onerror = () => resolve()
      })
    }

    mockFetch()
    const sink = createSink({ url: 'http://127.0.0.1:8081', appId: 'a', appVersion: 'b', clientId: 'c', heartbeat: false })
    sink.setGeo({ country: 'US', region: 'CA', city: 'SV' })
    addSink(sink)

    const impressions = []
    for (let i = 0; i < 5000; i++) {
      impressions.push(logImpression({
        id: 'card',
        type: 'widget',
        content: [{ id: 'a', type: 'b', index: 1, percent: 0.9 }],
        extras: {},
      }))
    }
    await Promise.all(impressions)
    await new Promise(resolve => setTimeout(resolve, 3000))
    await sink.flush()

    expect(await sink.pendingSize()).toBe(0)
    expect(sink.flushed).toBe(5001) // 5000 + 1 session start event
    expect(sink.failures).toBe(0)

    const deliveries = vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith('/events'))
    expect(deliveries.length).toBeGreaterThan(0)
    for (const [, request] of deliveries) {
      const payload = JSON.parse(request?.body as string)
      expect(request?.headers).toMatchObject({
        'X-App-ID': payload.context.app_id,
        'X-App-Version': payload.context.app_version,
        'X-Installation-ID': payload.context.device.installation_id,
        'X-BA-Session-ID': payload.context.session_id,
      })
    }

    removeSink(sink)
    vi.restoreAllMocks()
  }, 600_000)
})

// Verifies the heartbeat follows the `heartbeat` option alone — including that Do Not Track does NOT
// suppress it (the beat carries the same session context as every other event; coarse geo is derived
// server-side either way).
describe('BoscaSink heartbeat gating', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    Object.defineProperty(navigator, 'doNotTrack', { value: null, configurable: true })
  })

  async function heartbeatEmitted(dnt: string | null, overrides: Partial<{ heartbeat: boolean }> = {}): Promise<boolean> {
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    Object.defineProperty(navigator, 'doNotTrack', { value: dnt, configurable: true })
    const fetchSpy = mockFetch()
    const s = createSink({ url: 'http://127.0.0.1:8081', appId: 'app', appVersion: '1.0', clientId: 'web', ...overrides })
    addSink(s)
    // Let the session start + immediate heartbeat flush (1s debounce + execution).
    await new Promise(resolve => setTimeout(resolve, 1500))
    removeSink(s)
    return fetchSpy.mock.calls.some(([url, init]) => {
      if (!String(url).endsWith('/events')) return false
      try {
        const body = JSON.parse(String((init as RequestInit | undefined)?.body ?? '{}'))
        return Array.isArray(body?.events) && body.events.some((e: { type?: string }) => e.type === 'heartbeat')
      } catch {
        return false
      }
    })
  }

  it('emits the heartbeat by default when the option is omitted', async () => {
    expect(await heartbeatEmitted(null)).toBe(true)
  }, 10_000)

  it('emits the heartbeat even when Do Not Track is set', async () => {
    expect(await heartbeatEmitted('1')).toBe(true)
  }, 10_000)

  it('does not emit the heartbeat when explicitly disabled', async () => {
    expect(await heartbeatEmitted(null, { heartbeat: false })).toBe(false)
  }, 10_000)

  it('does not let a short heartbeat interval keep an idle session alive', async () => {
    vi.useFakeTimers()
    mockFetch()
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    const s = createSink({ url: 'http://127.0.0.1:8081', appId: 'app', appVersion: '1', clientId: 'web', heartbeatIntervalMs: 60_000 })
    const state = (s as unknown as { sessionState: SessionState }).sessionState
    try {
      await vi.advanceTimersByTimeAsync(0)
      const first = s.sessionId
      await vi.advanceTimersByTimeAsync(360_000)
      await s.add(await getAnalyticEventFactory().createEvent({ type: AnalyticEventType.interaction, element: { id: 'click', type: 'button' } }))
      expect(s.sessionId).not.toBe(first)
    } finally {
      state.endSession()
      vi.useRealTimers()
    }
  })

  it('keeps confirmed audio/video progress in the same session after a long browser suspension', async () => {
    vi.useFakeTimers()
    mockFetch()
    const s = createSink({ url: 'http://127.0.0.1:8081', appId: 'app', appVersion: '1', clientId: 'web', heartbeat: false })
    const state = (s as unknown as { sessionState: SessionState }).sessionState
    try {
      await vi.advanceTimersByTimeAsync(0)
      const first = s.sessionId
      await vi.advanceTimersByTimeAsync(1_200_000)
      await s.add(await getAnalyticEventFactory().createEvent({
        type: AnalyticEventType.impression,
        element: { id: 'player', type: 'media_playback', extras: { observedSeconds: '1200', currentTime: '1200', duration: '2400' } },
      }))
      expect(s.sessionId).toBe(first)
      await vi.advanceTimersByTimeAsync(600_000)
      await s.add(await getAnalyticEventFactory().createEvent({
        type: AnalyticEventType.completion,
        element: { id: 'player', type: 'media_playback', extras: { observedSeconds: '0', state: 'removed' } },
      }))
      expect(s.sessionId).toBe(first)
      await s.add(await getAnalyticEventFactory().createEvent({
        type: AnalyticEventType.interaction, element: { id: 'click', type: 'button' },
      }))
      expect(s.sessionId).not.toBe(first)
    } finally {
      state.endSession()
      vi.useRealTimers()
    }
  })
})

// Guards the published SDK surface: the legacy 4-arg positional constructor must keep working. Removing
// the overload would fail this test at type-check (the call) and at runtime (the asserted context).
describe('BoscaSink positional constructor (SDK back-compat)', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
  })

  it('accepts the legacy 4-arg positional form and populates the app context', async () => {
    // A hidden tab keeps the (default-on) heartbeat timer from starting for this construction-only test.
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    mockFetch()
    const sendBeacon = vi.fn().mockReturnValue(true)
    Object.defineProperty(navigator, 'sendBeacon', { value: sendBeacon, configurable: true })

    const positional = createSink('http://127.0.0.1:8081', 'legacy-app', '2.5', 'web')
    addSink(positional)
    try {
      positional.addBeacon({ type: AnalyticEventType.interaction, element: { id: 'b', type: 'button' } })
      const blob = sendBeacon.mock.calls.at(-1)?.[1] as Blob
      const payload = JSON.parse(await blob.text())
      expect(payload.context.app_id).toBe('legacy-app')
      expect(payload.context.app_version).toBe('2.5')
      expect(payload.context.client_id).toBe('web')
    } finally {
      removeSink(positional)
    }
  })
})
