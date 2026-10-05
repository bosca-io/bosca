import type { Context, Event } from './bosca_models'
import { DelayedAction } from './delayed_action'

export class PendingEvents {
  private readonly queue: EventQueue
  readonly eventCount: number
  readonly events: PendingContextEvents[] = []

  constructor(eventCount: number, queue: EventQueue, events: PendingContextEvents[]) {
    this.eventCount = eventCount
    this.queue = queue
    this.events = events
  }

  async failed(events: PendingContextEvents) {
    return await this.queue.failed(events)
  }

  async finish(events: PendingContextEvents) {
    return await this.queue.finish(events)
  }

  async close() {
    return await this.queue.close(this)
  }
}

export class PendingContextEvents {
  readonly context: Context
  readonly events: Event[] = []

  constructor(context: Context, events: Event[]) {
    this.context = context
    this.events = events
  }
}

interface PendingEvent {
  client_id: string
  context: Context
  event: Event
}

export class EventQueue {

  private pending: PendingEvent[] = []
  private failedToAdd: boolean = false
  private delayedAction: DelayedAction | null = null
  // eslint-disable-next-line no-undef
  private database: IDBDatabase | null = null
  private transaction = false
  private eventCount = 0

  constructor() {
    this.initialize().catch((e) => console.error('Database error:', e))
  }

  private async initialize() {
    // eslint-disable-next-line no-undef
    if (typeof indexedDB === 'undefined') {
      this.failedToAdd = true
      return
    }
    // eslint-disable-next-line no-undef
    const request = indexedDB.open('EventDB', 1)
    request.onupgradeneeded = () => {
      const store = request.result
      if (!store.objectStoreNames.contains('events')) {
        store.createObjectStore('events', { keyPath: 'client_id' })
      }
    }
    this.database = await toResult(request)
  }

  async pendingSize() {
    return this.pending.length
  }

  async size() {
    if (!this.database) return -1
    const transaction = this.database!.transaction('events', 'readonly')
    const store = transaction.objectStore('events')
    return await toResult(store.count())
  }

  async add(context: Context, event: Event) {
    this.eventCount++
    this.pending.push({
      client_id: event.client_id,
      context: {
        app_id: context.app_id,
        app_version: context.app_version,
        client_id: context.client_id,
        device: {
          installation_id: context.device?.installation_id,
          manufacturer: context.device?.manufacturer,
          model: context.device?.model,
          platform: context.device?.platform,
          primary_locale: context.device?.primary_locale,
          system_name: context.device?.system_name,
          timezone: context.device?.timezone,
          type: context.device?.type,
          version: context.device?.version,
        },
        geo: {
          city: context.geo?.city,
          region: context.geo?.region,
          country: context.geo?.country,
        },
        browser: context.browser ? {
          agent: context.browser.agent,
        } : null,
        session_id: context.session_id,
        user_id: context.user_id,
      },
      event: event,
    })
    try {
      await this.queueStore()
    } catch (e) {
      this.failedToAdd = true
      console.error('failed to add event: ', e)
      throw e
    }
  }

  async get(): Promise<PendingEvents | null> {
    if (this.failedToAdd) {
      const events = this.pending
      this.pending = []
      return this.toPendingEvents(events)
    }
    if (!this.database || this.transaction) return null
    this.transaction = true
    try {
      const transaction = this.database!.transaction('events', 'readonly')
      const store = transaction.objectStore('events')
      const events: PendingEvent[] = await toResult(store.getAll())
      if (events.length === 0) return new PendingEvents(this.eventCount, this, [])
      return this.toPendingEvents(events)
    } catch (e) {
      this.transaction = false
      throw e
    }
  }

  private toPendingEvents(events: PendingEvent[]): PendingEvents {
    const grouped = new Map<string, PendingContextEvents>()
    for (const event of events) {
      const contextKey = JSON.stringify([event.context.session_id, event.context.user_id ?? null])
      let pending = grouped.get(contextKey)
      if (!pending) {
        pending = new PendingContextEvents(event.context, [])
        grouped.set(contextKey, pending)
      }
      pending.events.push(event.event)
    }
    return new PendingEvents(this.eventCount, this, Array.from(grouped.values()))
  }

  async failed(events: PendingContextEvents) {
    if (!this.failedToAdd) return
    for (const event of events.events) {
      this.pending.push({ client_id: event.client_id, context: events.context, event: event })
    }
  }

  async finish(events: PendingContextEvents) {
    if (this.failedToAdd) return
    const transaction = this.database!.transaction('events', 'readwrite')
    const store = transaction.objectStore('events')
    const finished = []
    for (const event of events.events) {
      finished.push(toResult(store.delete(event.client_id)))
    }
    await Promise.all(finished)
    transaction.commit()
  }

  async close(events: PendingEvents) {
    this.transaction = false
    return this.eventCount !== events.eventCount
  }

  private async queueStore(): Promise<boolean> {
    if (!this.delayedAction) {
      const self = this
      this.delayedAction = new DelayedAction(
        async () => {
          await self.store()
        },
        () => {
          self.delayedAction = null
        },
      )
    } else {
      this.delayedAction.delay()
    }
    try {
      return await this.delayedAction.promise()
    } finally {
      this.delayedAction = null
    }
  }

  private async store() {
    if (!this.database) {
      this.failedToAdd = true
      return
    }
    if (this.pending.length === 0) return
    const transaction = this.database!.transaction('events', 'readwrite')
    const store = transaction.objectStore('events')
    const events = this.pending
    this.pending = []
    const added = []
    for (const pending of events) {
      added.push(toResult(store.add(pending)))
    }
    await Promise.all(added)
    transaction.commit()
  }
}

// eslint-disable-next-line no-undef
async function toResult<T>(request: IDBRequest<T>): Promise<T> {
  return await new Promise((resolve, reject) => {
    request.onsuccess = () => {
      resolve(request.result)
    }
    request.onerror = () => {
      reject(request.error)
    }
  })
}
