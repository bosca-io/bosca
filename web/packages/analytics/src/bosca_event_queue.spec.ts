import { describe, it, expect, vi, afterEach } from 'vitest'
import { EventQueue } from './bosca_event_queue'
import type { Context, Event } from './bosca_models'

function makeContext(sessionId = 'sess-1', userId?: string): Context {
  return {
    app_id: 'test', app_version: '1.0', client_id: 'client-1',
    device: {
      installation_id: 'iid', manufacturer: '', model: '', platform: 'web',
      primary_locale: 'en', system_name: 'Web', timezone: 'UTC', type: 'desktop', version: '1',
    },
    geo: { city: '', region: '', country: '' },
    browser: { agent: 'test' },
    session_id: sessionId,
    user_id: userId,
  }
}

function makeEvent(clientId: string): Event {
  return {
    client_id: clientId, type: 'impression', created: Date.now(), created_micros: 0,
    element: { id: 'el', type: 'div', content: [], extras: {} },
  }
}

// In node, indexedDB is undefined → initialize() sets failedToAdd = true.
// add() pushes to pending[], then queueStore() → store() → failedToAdd short-circuit.
// The DelayedAction never stores to IDB, but the event stays in pending[].
// get() in failback mode drains pending[] synchronously.

const describeInMemory = typeof indexedDB === 'undefined' ? describe : describe.skip
const describeIndexedDb = typeof indexedDB === 'undefined' ? describe.skip : describe

describeInMemory('EventQueue (in-memory fallback)', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('should return -1 for size() when database is unavailable', async () => {
    const queue = new EventQueue()
    await Promise.resolve()
    expect(await queue.size()).toBe(-1)
  })

  it('should store events in-memory and group them by session on get()', async () => {
    vi.useFakeTimers()
    const queue = new EventQueue()
    await vi.advanceTimersByTimeAsync(0) // let initialize() resolve

    // add() pushes to pending. Don't await — it waits on DelayedAction.
    const p1 = queue.add(makeContext('sess-a'), makeEvent('ev-1'))
    const p2 = queue.add(makeContext('sess-a'), makeEvent('ev-2'))
    const p3 = queue.add(makeContext('sess-b'), makeEvent('ev-3'))

    // Advance past DelayedAction's 500ms timeout to resolve the add promises
    await vi.advanceTimersByTimeAsync(600)
    await Promise.all([p1, p2, p3])

    const pending = await queue.get()
    expect(pending).not.toBeNull()
    expect(pending!.events).toHaveLength(2)
    expect(pending!.events[0].context.session_id).toBe('sess-a')
    expect(pending!.events[0].events).toHaveLength(2)
    expect(pending!.events[1].context.session_id).toBe('sess-b')
    expect(pending!.events[1].events).toHaveLength(1)
  })

  it('takes a new context group when the user changes within a session', async () => {
    vi.useFakeTimers()
    const queue = new EventQueue()
    await vi.advanceTimersByTimeAsync(0)

    const first = queue.add(makeContext('sess-a', 'user-a'), makeEvent('ev-1'))
    const second = queue.add(makeContext('sess-a', 'user-b'), makeEvent('ev-2'))
    await vi.advanceTimersByTimeAsync(600)
    await Promise.all([first, second])

    const pending = await queue.get()
    expect(pending!.events).toHaveLength(2)
    expect(pending!.events.map((group) => group.context.user_id).sort())
      .toEqual(['user-a', 'user-b'])
  })

  it('clones context when adding events so mutations to the original context object do not affect queued events', async () => {
    vi.useFakeTimers()
    const queue = new EventQueue()
    await vi.advanceTimersByTimeAsync(0)

    const context = makeContext('sess-a', 'user-1')
    const p1 = queue.add(context, makeEvent('ev-1'))
    context.app_id = 'changed-app'
    context.device.installation_id = 'changed-iid'
    context.geo.country = 'US'
    context.browser!.agent = 'changed-agent'
    context.user_id = 'user-2'
    context.session_id = 'sess-b'
    const p2 = queue.add(context, makeEvent('ev-2'))

    await vi.advanceTimersByTimeAsync(600)
    await Promise.all([p1, p2])

    const pending = await queue.get()
    expect(pending!.events).toHaveLength(2)
    const event1Group = pending!.events.find((g) => g.events.some((e) => e.client_id === 'ev-1'))
    const event2Group = pending!.events.find((g) => g.events.some((e) => e.client_id === 'ev-2'))
    expect(event1Group?.context.user_id).toBe('user-1')
    expect(event1Group?.context.session_id).toBe('sess-a')
    expect(event1Group?.context.app_id).toBe('test')
    expect(event1Group?.context.device.installation_id).toBe('iid')
    expect(event1Group?.context.geo.country).toBe('')
    expect(event1Group?.context.browser?.agent).toBe('test')
    expect(event2Group?.context.user_id).toBe('user-2')
    expect(event2Group?.context.session_id).toBe('sess-b')
    expect(event2Group?.context.app_id).toBe('changed-app')
    expect(event2Group?.context.device.installation_id).toBe('changed-iid')
    expect(event2Group?.context.geo.country).toBe('US')
    expect(event2Group?.context.browser?.agent).toBe('changed-agent')
  })

  it('should re-queue events on failed() so they survive a retry cycle', async () => {
    vi.useFakeTimers()
    const queue = new EventQueue()
    await vi.advanceTimersByTimeAsync(0)

    const p = queue.add(makeContext(), makeEvent('ev-1'))
    await vi.advanceTimersByTimeAsync(600)
    await p

    const batch1 = await queue.get()
    expect(batch1!.events).toHaveLength(1)

    // Simulate flush failure — push events back
    await batch1!.failed(batch1!.events[0])
    const queued = queue as unknown as { pending: Array<{ client_id: string }> }
    expect(queued.pending[0].client_id).toBe('ev-1')

    const batch2 = await queue.get()
    expect(batch2!.events).toHaveLength(1)
    expect(batch2!.events[0].events[0].client_id).toBe('ev-1')
  })

  it('should signal via close() whether new events arrived during flush', async () => {
    vi.useFakeTimers()
    const queue = new EventQueue()
    await vi.advanceTimersByTimeAsync(0)

    const p1 = queue.add(makeContext(), makeEvent('ev-1'))
    await vi.advanceTimersByTimeAsync(600)
    await p1

    const pending = await queue.get()
    // No new events since get()
    expect(await pending!.close()).toBe(false)

    // Now add while a second batch is open
    const p2 = queue.add(makeContext(), makeEvent('ev-2'))
    await vi.advanceTimersByTimeAsync(600)
    await p2

    const pending2 = await queue.get()
    // Add ev-3 while pending2 is open
    const p3 = queue.add(makeContext(), makeEvent('ev-3'))
    await vi.advanceTimersByTimeAsync(600)
    await p3

    // close returns true because eventCount changed
    expect(await pending2!.close()).toBe(true)
  })
})

describeIndexedDb('EventQueue (IndexedDB)', () => {
  it('persists the captured context and removes finished events', async () => {
    await new Promise<void>((resolve) => {
      const request = indexedDB.deleteDatabase('EventDB')
      request.onsuccess = () => resolve()
      request.onerror = () => resolve()
    })

    const queue = new EventQueue()
    for (let attempt = 0; attempt < 100 && await queue.size() === -1; attempt++) {
      await new Promise(resolve => setTimeout(resolve, 10))
    }

    const context = makeContext('sess-a', 'user-a')
    const added = queue.add(context, makeEvent('ev-1'))
    context.device.installation_id = 'changed-iid'
    context.geo.country = 'US'
    context.browser!.agent = 'changed-agent'
    await added

    expect(await queue.size()).toBe(1)
    const pending = await queue.get()
    expect(pending?.events).toHaveLength(1)
    expect(pending?.events[0].context.device.installation_id).toBe('iid')
    expect(pending?.events[0].context.geo.country).toBe('')
    expect(pending?.events[0].context.browser?.agent).toBe('test')
    expect(await queue.get()).toBeNull()

    await pending!.finish(pending!.events[0])
    expect(await pending!.close()).toBe(false)
    expect(await queue.size()).toBe(0)
    const internals = queue as unknown as { database: IDBDatabase | null }
    internals.database?.close()
  })
})
