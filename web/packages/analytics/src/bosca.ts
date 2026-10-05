import { AnalyticEvent, AnalyticEventType, type IAnalyticEvent } from './event'
import { AnalyticEventSink } from './sink'
import { ulid } from 'ulid'
import { EventQueue } from './bosca_event_queue'
import type { Context, Device, ErrorInfo, Event, EventContent, EventElement, Geo } from './bosca_models'
import { SessionState } from './bosca_session_state'
import { getAnalyticEventFactory } from './factory'

const MAX_STACK_TRACE_LENGTH = 8192
const INSTALLATION_COOKIE_MAX_AGE_SECONDS = 365 * 24 * 60 * 60
const SESSION_TIMEOUT_MS = 300_000

/** Reads the analytics session issued by the BML request lifecycle. */
function bmlSessionId(): string | null {
  if (typeof document === 'undefined') return null
  try {
    const cookies = document.cookie.split(';').map(value => value.trim())
    const cookie = cookies.find(value => value.startsWith('bml_asid='))
    const expiry = Number(cookies.find(value => value.startsWith('bml_asid_exp='))?.slice('bml_asid_exp='.length))
    if (!cookie || !Number.isFinite(expiry) || expiry <= Date.now()) return null
    const value = decodeURIComponent(cookie.slice('bml_asid='.length).replace(/\+/g, ' '))
    return value.trim() ? value : null
  } catch {
    return null
  }
}

/** Shares SDK-owned session changes with subsequent BML requests and page navigations. */
function persistSessionId(sessionId: string, expiresAt: number): void {
  if (typeof document === 'undefined') return
  try {
    const secure = window.location.protocol === 'https:' ? '; Secure' : ''
    document.cookie = `bml_asid=${encodeURIComponent(sessionId)}; Path=/; SameSite=Lax${secure}`
    document.cookie = `bml_asid_exp=${expiresAt}; Path=/; SameSite=Lax${secure}`
  } catch (error) {
    console.warn('Failed to persist analytics session cookie:', error)
  }
}

/**
 * Returns sub-millisecond microseconds from the high-resolution timer.
 * Falls back to 0 in environments without `performance` (SSR, workers).
 */
function microsNow(): number {
  if (typeof performance === 'undefined') return 0
  return Math.floor((performance.now() % 1) * 1000)
}

function persistedInstallationId(): string | null {
  try {
    if (typeof document !== 'undefined') {
      const cookie = document.cookie.split(';')
        .map(value => value.trim())
        .find(value => value.startsWith('bml_iid='))
      const value = cookie?.slice('bml_iid='.length)
      if (value) return value.startsWith('"') && value.endsWith('"') ? value.slice(1, -1) : value
    }
  } catch {
    // Cookie access may be blocked; preserve the existing storage fallback.
  }
  try {
    return typeof localStorage === 'undefined' ? null : localStorage.getItem('__iid')
  } catch {
    return null
  }
}

function persistInstallationId(installationId: string): void {
  try {
    if (typeof document !== 'undefined') {
      const secure = typeof location !== 'undefined' && location.protocol === 'https:' ? '; Secure' : ''
      document.cookie = `bml_iid=${installationId}; Path=/; Max-Age=${INSTALLATION_COOKIE_MAX_AGE_SECONDS}; SameSite=Lax${secure}`
    }
  } catch (e) {
    console.warn('Failed to persist IID cookie:', e)
  }
  try {
    if (typeof localStorage !== 'undefined' && typeof localStorage.setItem === 'function') {
      localStorage.setItem('__iid', installationId)
    }
  } catch (e) {
    console.warn('Failed to persist IID:', e)
  }
}

/**
 * Configuration for the analytics sink. Pass as a single object to the
 * {@link BoscaSink} constructor, or use the legacy 4-arg positional form
 * for backward compatibility.
 */
export interface BoscaSinkConfig {
  url: string
  appId: string
  appVersion: string
  clientId: string
  /** Log events to console and warn about missing fields. Default false. */
  debug?: boolean
  /** When true, uses an ephemeral in-memory installation ID (not persisted
   *  to localStorage) and prevents `setUserId` from having any effect. Default false. */
  anonymous?: boolean
  /** When true, omits credentials (cookies) from all analytics HTTP requests. Default false. */
  omitCredentials?: boolean
  /** Emits a periodic session heartbeat while the tab is visible — powering active-session counts and the
   *  live sessions map. On by default; set `false` to disable. */
  heartbeat?: boolean
  /** Heartbeat cadence in milliseconds. Must match the server's 15-minute activity window: the backend
   *  counts exactly one heartbeat per session per 15-minute bucket as the active-session count, so beating
   *  more often would double-count a session within a bucket. Default 900000 (15 minutes). */
  heartbeatIntervalMs?: number
}

export class BoscaSink extends AnalyticEventSink {

  private readonly url: string
  private readonly context: Context
  private readonly queue = new EventQueue()
  private flushing = false
  private _flushed = 0
  private _failures = 0
  private timeout: any | null = null
  private sessionState: SessionState
  private retryDelay = 3_000
  private geo: Geo | null = null
  private readonly debug: boolean
  private readonly anonymous: boolean
  private readonly omitCredentials: boolean
  private ephemeralInstallationId: string | null = null

  /** Returns the current analytics session identifier. */
  get sessionId(): string {
    return this.context.session_id
  }

  private requestHeaders(context: Context): Record<string, string> {
    return {
      'Content-Type': 'application/json',
      'X-App-ID': context.app_id,
      'X-App-Version': context.app_version,
      'X-Installation-ID': context.device.installation_id || '',
      'X-BA-Session-ID': context.session_id,
    }
  }

  /** Returns the device installation ID used for anonymous user identity. */
  get installationId(): string {
    if (this.context.device.installation_id) return this.context.device.installation_id
    if (this.anonymous) {
      if (!this.ephemeralInstallationId) this.ephemeralInstallationId = ulid()
      return this.ephemeralInstallationId
    }
    return persistedInstallationId() || ''
  }

  constructor(config: BoscaSinkConfig)
  constructor(url: string, appId: string, appVersion: string, clientId: string)
  constructor(urlOrConfig: string | BoscaSinkConfig, appId?: string, appVersion?: string, clientId?: string) {
    super()
    // Normalize the legacy positional form to a config object, then read every field uniformly. The
    // positional overload predates the heartbeat option and is preserved for SDK backward compatibility;
    // it inherits the same defaults as an object with those keys omitted (heartbeat on).
    const config: BoscaSinkConfig = typeof urlOrConfig === 'object'
      ? urlOrConfig
      : { url: urlOrConfig, appId: appId!, appVersion: appVersion!, clientId: clientId! }
    const url = config.url
    const _appId = config.appId
    const _appVersion = config.appVersion
    const _clientId = config.clientId
    const debug = config.debug ?? false
    const anonymous = config.anonymous ?? false
    const omitCredentials = config.omitCredentials ?? false
    const heartbeat = config.heartbeat ?? true
    const heartbeatIntervalMs = config.heartbeatIntervalMs ?? 900_000
    const initialServerSession = anonymous ? null : bmlSessionId()

    this.url = url
    this.debug = debug
    this.anonymous = anonymous
    this.omitCredentials = omitCredentials
    this.context = {
      app_id: _appId,
      app_version: _appVersion,
      browser: typeof navigator === 'undefined' ? null : {
        // eslint-disable-next-line no-undef
        agent: navigator?.userAgent,
      },
      client_id: _clientId,
      device: {
        installation_id: '',
        manufacturer: '',
        model: '',
        platform: '',
        primary_locale: '',
        system_name: '',
        timezone: '',
        type: '',
        version: '',
      },
      geo: {
        city: '',
        region: '',
        country: '',
      },
      session_id: initialServerSession ?? ulid(),
    }
    if (!anonymous) persistSessionId(this.context.session_id, Date.now() + SESSION_TIMEOUT_MS)

    const self = this
    // Heartbeat carries only the standard event context (session/app/version); the server enriches the
    // coarse location from Cloudflare headers at ingest — the client never sends geo.
    const onHeartbeat = heartbeat
      ? async () => {
        const event = await getAnalyticEventFactory().createEvent({
          type: AnalyticEventType.heartbeat,
          element: {
            id: self.sessionId,
            type: 'session',
            content: [],
            extras: {},
          },
        })
        try {
          await self.add(event)
        } catch (e) {
          console.error('failed to emit heartbeat: ', e)
        }
      }
      : null
    let firstSession = true;
    this.sessionState = new SessionState(SESSION_TIMEOUT_MS, async () => {
      if (firstSession) {
        firstSession = false;
      } else {
        self.context.session_id = ulid()
      }
      const event = await getAnalyticEventFactory().createEvent({
        type: AnalyticEventType.session,
        element: {
          id: self.context.session_id,
          type: 'session',
          content: [],
          extras: {
            start: 'true',
          },
        },
      })
      try {
        await self.add(event)
      } catch (e) {
        console.error('failed to update session state: ', e)
      }
    }, onHeartbeat, heartbeatIntervalMs, expiresAt => {
      if (!self.anonymous) persistSessionId(self.context.session_id, expiresAt)
    })
  }

  setUserId(userId: string | null | undefined) {
    if (this.anonymous) return
    this.context.user_id = userId
  }

  override addBeacon(event: IAnalyticEvent): void {
    if (typeof navigator === 'undefined' || !navigator.sendBeacon) return
    const context = {
      ...this.context,
      device: {
        ...this.context.device,
        installation_id: this.installationId,
      },
    }
    const beaconPage = event.page ?? (
      typeof window !== 'undefined' && typeof document !== 'undefined'
        ? { path: window.location?.pathname, url: window.location?.href, title: document.title }
        : undefined
    )
    const beaconEvent: Event = {
      client_id: ulid(),
      type: event.type,
      created: Date.now(),
      created_micros: microsNow(),
      element: {
        id: event.element?.id || '',
        type: event.element?.type || '',
        content: (event.element?.content || []) as EventContent[],
        extras: event.element?.extras || {},
      },
      page: beaconPage ? {
        path: beaconPage.path ?? null,
        url: beaconPage.url ?? null,
        title: beaconPage.title ?? null,
      } : null,
    }
    if (event.error) {
      beaconEvent.error = {
        message: event.error.message,
        type: event.error.type ?? null,
        stack_trace: event.error.stack_trace?.substring(0, MAX_STACK_TRACE_LENGTH) ?? null,
        fatal: event.error.fatal ?? false,
        code: event.error.code ?? null,
      }
    }
    const payload = JSON.stringify({
      context,
      events: [beaconEvent],
      sent: Date.now(),
      sent_micros: microsNow(),
    })
    if (this.omitCredentials) {
      fetch(this.url + '/events', {
        method: 'POST',
        headers: this.requestHeaders(context),
        body: payload,
        credentials: 'omit',
        keepalive: true,
      }).catch(() => {})
    } else {
      const blob = new Blob([payload], { type: 'application/json' })
      navigator.sendBeacon(this.url + '/events', blob)
    }
  }

  get flushed() {
    return this._flushed
  }

  get failures() {
    return this._failures
  }

  async pendingSize(): Promise<number> {
    return this.queue.pendingSize()
  }

  async size(): Promise<number> {
    return this.queue.size()
  }

  protected async onAdd(_: AnalyticEvent, event: AnalyticEvent): Promise<void> {
    this.updateSessionActivity(event).catch(e => console.error('failed to update session state: ', e))
    const queuedEvent: Event = {
      client_id: ulid(),
      type: event.type,
      created: event.created.getTime(),
      created_micros: microsNow(),
      element: {
        id: event.element.id,
        type: event.element.type,
        content: event.element.content.map((c) => {
          return {
            id: c.id,
            type: c.type,
            index: c.index,
            percent: c.percent,
          } as EventContent
        }),
        extras: event.element.extras,
      } as EventElement,
      page: event.page ? {
        path: event.page.path ?? null,
        url: event.page.url ?? null,
        title: event.page.title ?? null,
      } : null,
    }
    if (event.error) {
      queuedEvent.error = {
        message: event.error.message,
        type: event.error.type ?? null,
        stack_trace: event.error.stack_trace?.substring(0, MAX_STACK_TRACE_LENGTH) ?? null,
        fatal: event.error.fatal ?? false,
        code: event.error.code ?? null,
      }
    }

    if (this.debug) {
      this.debugEvent(queuedEvent)
    }

    this.queue.add(this.context, queuedEvent).catch(e => console.error('failed to add event: ', e))

    if (event.type === AnalyticEventType.error) {
      this.queueFlush(0)
      if (event.error?.fatal) {
        this.addBeacon({
          type: event.type,
          element: { id: event.element.id, type: event.element.type },
          error: event.error,
        })
      }
    } else {
      this.queueFlush()
    }
  }

  private async updateSessionActivity(event: AnalyticEvent): Promise<void> {
    // Live-map heartbeats must not renew their own inactivity deadline.
    if (event.type === AnalyticEventType.heartbeat) return

    const isPlaybackEvent = event.element.type === 'media_playback' &&
      (event.type === AnalyticEventType.impression || event.type === AnalyticEventType.completion)
    if (!isPlaybackEvent) return this.sessionState.onEvent()

    const observedSeconds = Number(event.element.extras.observedSeconds)
    if (!Number.isFinite(observedSeconds) || observedSeconds <= 0) {
      // Closing an idle player records completion without starting or extending a session.
      if (event.type === AnalyticEventType.completion) return
      return this.sessionState.onEvent()
    }

    const playbackStartedAt = event.created.getTime() - observedSeconds * 1000
    return this.sessionState.onEvent(playbackStartedAt)
  }

  private queueRequests = 0

  private queueFlush(delay?: number) {
    if (this.timeout) {
      if (this.queueRequests > 10) {
        return
      }
      clearTimeout(this.timeout)
    }
    this.queueRequests++
    const self = this
    this.timeout = setTimeout(() => {
      self.timeout = null
      self.queueRequests = 0
      self.flush().catch(e => console.error('failed to flush: ', e))
    }, delay ?? 1000)
  }

  async flush() {
    if (this.flushing) {
      this.queueFlush()
      return
    }
    this.flushing = true
    try {
      await this.initializeContext()
      const allPendingEvents = await this.queue.get()
      if (!allPendingEvents) return
      let errors = false
      try {
        for (const pendingEvents of allPendingEvents.events) {
          try {
            const ctx = pendingEvents.context
            if (!ctx.app_id) ctx.app_id = this.context.app_id
            if (!ctx.app_version) ctx.app_version = this.context.app_version
            if (!ctx.client_id) ctx.client_id = this.context.client_id
            const events = {
              context: ctx,
              events: pendingEvents.events,
              sent: new Date().getTime(),
              sent_micros: microsNow(),
            }
            if (!events.context.device.installation_id) {
              events.context.device.installation_id = await this.generateInstallationId() || ''
            }

            if (this.debug) {
              console.debug('[bosca-analytics] flush', events.events.length, 'events')
            }

            const response = await fetch(this.url + '/events', {
              method: 'POST',
              headers: this.requestHeaders(events.context),
              body: JSON.stringify(events),
              ...(this.omitCredentials ? { credentials: 'omit' as RequestCredentials } : {}),
            })
            if (response.status !== 200 && response.status !== 202) {
              throw new Error('error sending events: ' + await response.text())
            }

            if (this.debug) {
              console.debug('[bosca-analytics] flush success', response.status)
            }

            await allPendingEvents.finish(pendingEvents)
            this._flushed += pendingEvents.events.length
          } catch (e: any) {
            errors = true
            this._failures += pendingEvents.events.length
            console.error('failed to flush: ', e)
            await allPendingEvents.failed(pendingEvents)
          }
        }
      } finally {
        if (await allPendingEvents.close()) {
          this.queueFlush()
        } else if (errors) {
          this.retryDelay = Math.min(this.retryDelay * 2, 60_000)
          this.queueFlush(this.retryDelay)
        } else {
          this.retryDelay = 3_000
        }
      }
    } finally {
      this.flushing = false
    }
  }

  private async initializeContext() {
    if (this.context.device.installation_id === '') {
      this.context.device = await this.getDeviceInfo()
    }
    if (this.context.geo.country === '') {
      this.context.geo = await this.getGeo()
    }
  }

  private async getDeviceInfo(): Promise<Device> {
    const w = typeof window === 'undefined' ? {
      navigator: {
        userAgent: '',
        platform: '',
      },
    } :
    // eslint-disable-next-line no-undef
      window
    const userAgent = w.navigator.userAgent
    // eslint-disable-next-line no-undef
    const platform = w.navigator.platform

    const isIOS = /iPhone|iPad|iPod/.test(userAgent)
    const isAndroid = /Android/.test(userAgent)
    const isMobile = /Mobile/.test(userAgent)

    let manufacturer = 'Unknown'
    let model = 'Unknown'

    if (isIOS) {
      manufacturer = 'Apple'
      if (userAgent.includes('iPhone')) model = 'iPhone'
      else if (userAgent.includes('iPad')) model = 'iPad'
      else if (userAgent.includes('iPod')) model = 'iPod'
    } else if (isAndroid) {
      manufacturer = userAgent.match(/Android.*?;.*?([^;]+)\s+Build/)?.[1]?.split(' ')[0] || 'Unknown'
      model = userAgent.match(/Android.*?;.*?([^;]+)\s+Build/)?.[1] || 'Unknown'
    } else {
      manufacturer = platform.split(' ')[0] ?? 'Unknown'
      model = platform
    }

    return {
      installation_id: await this.generateInstallationId(),
      manufacturer,
      model,
      platform: platform,
      // eslint-disable-next-line no-undef
      primary_locale: typeof navigator === 'undefined' ? 'zz' : navigator.language,
      system_name: isIOS ? 'iOS' : isAndroid ? 'Android' : 'Web',
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      type: isMobile ? 'mobile' : 'desktop',
      version: userAgent.match(/(?:iPhone|CPU|Android|Edge|Chrome|Firefox|Safari|Opera|Version)[\s/:]\s?(\d+[._\d]*)/)?.[1] || 'Unknown',
    } as Device
  }

  private async getGeo(): Promise<Geo> {
    return this.geo || { city: '', region: '', country: '' }
  }

  setGeo(geo: Geo) {
    this.geo = geo
  }

  private async generateInstallationId(): Promise<string | null> {
    if (this.anonymous) return this.installationId
    try {
      let installationId = this.installationId || null
      if (!installationId) {
        const response = await fetch(this.url + '/installation', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
          },
        })
        if (response.ok) {
          const data = await response.json()
          installationId = data.id
          persistInstallationId(installationId!)
        } else {
          throw new Error(await response.json())
        }
      }
      return installationId
    } catch (e) {
      console.error('Failed to register IID:', e)
      return null
    }
  }

  private debugEvent(event: Event) {
    const warnings: string[] = []
    if (!event.element.id) warnings.push('element.id is empty')
    if (!event.element.type) warnings.push('element.type is empty')
    if (!event.page) warnings.push('page context is missing')
    if (event.type === 'error' && !event.error) warnings.push('error event has no error info')

    console.debug('[bosca-analytics] event', event.type, event.element.type, event.element.id)
    if (warnings.length > 0) {
      console.warn('[bosca-analytics] warnings:', warnings.join('; '))
    }
  }
}
