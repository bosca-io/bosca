export class SessionState {
  private sessionTimeout: number
  private onSessionStart: () => Promise<void>
  private onHeartbeat: (() => Promise<void>) | null
  private heartbeatInterval: number
  private isSessionActive: boolean
  private timeoutId: number | null = null
  private heartbeatId: number | null = null
  private expiresAt = 0

  constructor(
    sessionTimeout: number,
    onSessionStart: () => Promise<void>,
    onHeartbeat: (() => Promise<void>) | null = null,
    heartbeatInterval: number = 900_000, // 15 minutes — one beat per session per server activity bucket
    /** Called when activity advances the inactivity deadline, so it can survive page navigation. */
    private readonly onActivity: ((expiresAt: number) => void) | null = null,
  ) {
    this.sessionTimeout = sessionTimeout
    this.onSessionStart = onSessionStart || (() => console.log('Session started'))
    // Optional periodic heartbeat: keeps an active, visible session present on the live sessions map.
    this.onHeartbeat = onHeartbeat
    this.heartbeatInterval = heartbeatInterval
    this.isSessionActive = false
    this.timeoutId = null
    if (typeof document !== 'undefined') {
      // eslint-disable-next-line no-undef
      document.addEventListener('visibilitychange', this.handleVisibilityChange.bind(this))
    }
    queueMicrotask(() => {
      void this.startSession()
    })
  }

  /** Confirmed playback can cover an inactivity deadline even if browser timers were suspended. */
  async onEvent(activityStartedAt?: number) {
    await this.resumeSession(activityStartedAt)
  }

  handleVisibilityChange() {
    // eslint-disable-next-line no-undef
    if (document.visibilityState === 'hidden') {
      this.pauseSession()
      // eslint-disable-next-line no-undef
    } else if (document.visibilityState === 'visible') {
      if (!this.isSessionActive || Date.now() >= this.expiresAt) {
        void this.startSession()
      } else {
        this.resetTimeout()
        this.startHeartbeat()
      }
    }
  }

  async startSession() {
    if (this.isSessionActive && Date.now() >= this.expiresAt) this.endSession()
    let started: Promise<void> | undefined
    if (!this.isSessionActive) {
      this.isSessionActive = true
      this.expiresAt = Date.now() + this.sessionTimeout
      // The callback updates session identity synchronously, before its asynchronous event work.
      started = this.onSessionStart()
    }
    this.resetTimeout()
    this.startHeartbeat()
    // Completing event delivery must not extend an expired session or restart its timers.
    await started
  }

  pauseSession() {
    if (this.isSessionActive) {
      this.clearTimeout()
      this.stopHeartbeat()
    }
  }

  async resumeSession(activityStartedAt?: number) {
    if (this.expiresAt > 0 && activityStartedAt !== undefined && Number.isFinite(activityStartedAt) &&
        activityStartedAt < this.expiresAt && activityStartedAt <= Date.now()) {
      if (!this.isSessionActive) {
        this.isSessionActive = true
        this.startHeartbeat()
      }
    } else if (this.isSessionActive && Date.now() >= this.expiresAt) this.endSession()
    if (!this.isSessionActive) {
      await this.startSession()
    } else {
      this.resetTimeout()
    }
  }

  endSession() {
    if (this.isSessionActive) {
      this.isSessionActive = false
      this.clearTimeout()
      this.stopHeartbeat()
    }
  }

  resetTimeout() {
    this.clearTimeout()
    this.expiresAt = Date.now() + this.sessionTimeout
    this.onActivity?.(this.expiresAt)
    // @ts-ignore
    this.timeoutId = setTimeout(() => {
      this.endSession()
    }, this.sessionTimeout)
  }

  clearTimeout() {
    if (this.timeoutId) {
      clearTimeout(this.timeoutId)
      this.timeoutId = null
    }
  }

  /**
   * Emit a heartbeat now, then on an interval, for as long as the session is active and visible.
   * Fired on session start and on tab re-focus (never per-event), so an active session appears on
   * the live map immediately and stays present without flooding the pipeline. No-op when no
   * heartbeat callback was configured.
   */
  private startHeartbeat() {
    if (!this.onHeartbeat) return
    // Only in a browser with a visible tab; a tab that loads hidden waits for visibilitychange.
    // eslint-disable-next-line no-undef
    if (typeof document === 'undefined' || document.visibilityState === 'hidden') return
    this.stopHeartbeat()
    void this.onHeartbeat()
    // @ts-ignore
    this.heartbeatId = setInterval(() => {
      void this.onHeartbeat!()
    }, this.heartbeatInterval)
  }

  private stopHeartbeat() {
    if (this.heartbeatId) {
      clearInterval(this.heartbeatId)
      this.heartbeatId = null
    }
  }
}
