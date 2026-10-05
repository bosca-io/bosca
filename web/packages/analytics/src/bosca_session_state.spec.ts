// @vitest-environment happy-dom

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { SessionState } from './bosca_session_state'

describe('SessionState', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('should fire onSessionStart once on construction, not again while active', async () => {
    const onStart = vi.fn().mockResolvedValue(undefined)
    const state = new SessionState(30000, onStart)
    await vi.advanceTimersByTimeAsync(0)
    expect(onStart).toHaveBeenCalledTimes(1)

    // Activity while session is active should not re-trigger
    await state.onEvent()
    await state.onEvent()
    expect(onStart).toHaveBeenCalledTimes(1)
  })

  it('should end session after timeout, then restart on next event', async () => {
    const onStart = vi.fn().mockResolvedValue(undefined)
    const state = new SessionState(1000, onStart)
    await vi.advanceTimersByTimeAsync(0)
    expect(onStart).toHaveBeenCalledTimes(1)

    // Let timeout expire — session ends
    await vi.advanceTimersByTimeAsync(1500)

    // Next event triggers a new session
    await state.onEvent()
    expect(onStart).toHaveBeenCalledTimes(2)
  })

  it('publishes rotated identity before start work completes and does not revive an expired session', async () => {
    let identity = 0
    const onStart = vi.fn(async () => { identity++ })
    const published: Array<{ identity: number, expiresAt: number }> = []
    const onHeartbeat = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: true })
    const state = new SessionState(1000, onStart, onHeartbeat, 500, expiresAt => {
      published.push({ identity, expiresAt })
    })
    await vi.advanceTimersByTimeAsync(0)
    state.endSession()
    let finish = () => {}
    onStart.mockImplementationOnce(() => {
      identity++
      return new Promise<void>(resolve => { finish = resolve })
    })

    const started = state.startSession()
    expect(published.at(-1)).toEqual({ identity: 2, expiresAt: Date.now() + 1000 })
    const publications = published.length
    await vi.advanceTimersByTimeAsync(1500)
    const heartbeats = onHeartbeat.mock.calls.length
    finish()
    await started
    expect(published).toHaveLength(publications)
    await vi.advanceTimersByTimeAsync(500)
    expect(onHeartbeat).toHaveBeenCalledTimes(heartbeats)

    await state.onEvent()
    expect(identity).toBe(3)
    state.endSession()
  })

  it('does not move an activity deadline when earlier start work completes', async () => {
    const onStart = vi.fn().mockResolvedValue(undefined)
    const onActivity = vi.fn()
    const state = new SessionState(1000, onStart, null, 900_000, onActivity)
    await vi.advanceTimersByTimeAsync(0)
    state.endSession()
    let finish = () => {}
    onStart.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
    const started = state.startSession()
    await vi.advanceTimersByTimeAsync(400)
    await state.onEvent()
    const deadline = Date.now() + 1000
    const publications = onActivity.mock.calls.length
    await vi.advanceTimersByTimeAsync(200)
    finish()
    await started
    expect(onActivity).toHaveBeenCalledTimes(publications)
    expect(onActivity).toHaveBeenLastCalledWith(deadline)
    state.endSession()
  })

  it('should keep session alive when events reset the timeout', async () => {
    const onStart = vi.fn().mockResolvedValue(undefined)
    const state = new SessionState(1000, onStart)
    await vi.advanceTimersByTimeAsync(0)

    // Fire event at 800ms — resets the 1000ms timeout
    await vi.advanceTimersByTimeAsync(800)
    await state.onEvent()

    // At 1600ms from start (800ms since reset) — still within the new window
    await vi.advanceTimersByTimeAsync(800)
    await state.onEvent()

    // Never timed out, so still just 1 session
    expect(onStart).toHaveBeenCalledTimes(1)
  })

  it('rotates after expiry while hidden even though the timer was paused', async () => {
    const onStart = vi.fn().mockResolvedValue(undefined)
    const state = new SessionState(1000, onStart)
    await vi.advanceTimersByTimeAsync(0)
    expect(onStart).toHaveBeenCalledTimes(1)

    // Tab goes hidden — timeout is cleared
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', writable: true })
    state.handleVisibilityChange()

    // The timer is paused, but the inactivity deadline still passes.
    await vi.advanceTimersByTimeAsync(5000)

    // Resuming checks the deadline rather than trusting the paused timer.
    Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: true })
    state.handleVisibilityChange()

    expect(onStart).toHaveBeenCalledTimes(2)
  })

  it('publishes the sliding inactivity deadline and checks it when timers have not run', async () => {
    const onStart = vi.fn().mockResolvedValue(undefined)
    const onActivity = vi.fn()
    const state = new SessionState(1000, onStart, null, 900_000, onActivity)
    await vi.advanceTimersByTimeAsync(0)
    expect(onActivity).toHaveBeenLastCalledWith(Date.now() + 1000)
    await vi.advanceTimersByTimeAsync(800)
    await state.onEvent()
    expect(onActivity).toHaveBeenLastCalledWith(Date.now() + 1000)
    state.pauseSession()
    vi.setSystemTime(Date.now() + 1001)
    await state.onEvent()
    expect(onStart).toHaveBeenCalledTimes(2)
    expect(onActivity).toHaveBeenLastCalledWith(Date.now() + 1000)
    state.endSession()
  })

  it('should start new session when visibility resumes after explicit end', async () => {
    const onStart = vi.fn().mockResolvedValue(undefined)
    const state = new SessionState(1000, onStart)
    await vi.advanceTimersByTimeAsync(0)
    expect(onStart).toHaveBeenCalledTimes(1)

    state.endSession()

    Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: true })
    state.handleVisibilityChange()
    await vi.advanceTimersByTimeAsync(0)
    expect(onStart).toHaveBeenCalledTimes(2)
  })

  describe('heartbeat', () => {
    const idle = () => vi.fn().mockResolvedValue(undefined)

    beforeEach(() => {
      Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: true })
    })

    it('fires a heartbeat immediately on session start', async () => {
      const onHeartbeat = idle()
      new SessionState(30000, idle(), onHeartbeat, 10000)
      await vi.advanceTimersByTimeAsync(0)
      expect(onHeartbeat).toHaveBeenCalledTimes(1)
    })

    it('fires a heartbeat on the interval while active', async () => {
      const onHeartbeat = idle()
      new SessionState(30000, idle(), onHeartbeat, 10000)
      await vi.advanceTimersByTimeAsync(0)
      await vi.advanceTimersByTimeAsync(10000)
      await vi.advanceTimersByTimeAsync(10000)
      expect(onHeartbeat).toHaveBeenCalledTimes(3)
    })

    it('does not fire a heartbeat per event', async () => {
      const onHeartbeat = idle()
      const state = new SessionState(30000, idle(), onHeartbeat, 10000)
      await vi.advanceTimersByTimeAsync(0)
      await state.onEvent()
      await state.onEvent()
      expect(onHeartbeat).toHaveBeenCalledTimes(1)
    })

    it('stops heartbeats while the tab is hidden', async () => {
      const onHeartbeat = idle()
      const state = new SessionState(30000, idle(), onHeartbeat, 10000)
      await vi.advanceTimersByTimeAsync(0)
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', writable: true })
      state.handleVisibilityChange()
      await vi.advanceTimersByTimeAsync(30000)
      expect(onHeartbeat).toHaveBeenCalledTimes(1)
    })

    it('resumes heartbeats immediately when the tab becomes visible', async () => {
      const onHeartbeat = idle()
      const state = new SessionState(30000, idle(), onHeartbeat, 10000)
      await vi.advanceTimersByTimeAsync(0)
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', writable: true })
      state.handleVisibilityChange()
      Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: true })
      state.handleVisibilityChange()
      await vi.advanceTimersByTimeAsync(0)
      expect(onHeartbeat).toHaveBeenCalledTimes(2)
    })

    it('stops heartbeats when the session ends', async () => {
      const onHeartbeat = idle()
      const state = new SessionState(30000, idle(), onHeartbeat, 10000)
      await vi.advanceTimersByTimeAsync(0)
      state.endSession()
      await vi.advanceTimersByTimeAsync(30000)
      expect(onHeartbeat).toHaveBeenCalledTimes(1)
    })

    it('does not heartbeat when a tab loads hidden until it becomes visible', async () => {
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', writable: true })
      const onHeartbeat = idle()
      const state = new SessionState(30000, idle(), onHeartbeat, 10000)
      await vi.advanceTimersByTimeAsync(20000)
      expect(onHeartbeat).toHaveBeenCalledTimes(0)

      Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: true })
      state.handleVisibilityChange()
      await vi.advanceTimersByTimeAsync(0)
      expect(onHeartbeat).toHaveBeenCalledTimes(1)
    })
  })

  it('preserves session identity when confirmed playback spans a twenty-minute timer suspension', async () => {
    const onStart = vi.fn().mockResolvedValue(undefined)
    const state = new SessionState(300_000, onStart)
    await vi.advanceTimersByTimeAsync(0)
    const playbackStart = Date.now()
    await vi.advanceTimersByTimeAsync(1_200_000)
    await state.onEvent(playbackStart)
    expect(onStart).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(300_000)
    await state.onEvent()
    expect(onStart).toHaveBeenCalledTimes(2)
    state.endSession()
  })

  it.each([300_000, 400_000, NaN, Infinity])('does not bridge playback beginning after expiry or invalid start %s', async (offset) => {
    const onStart = vi.fn().mockResolvedValue(undefined)
    const state = new SessionState(300_000, onStart)
    await vi.advanceTimersByTimeAsync(0)
    const start = Date.now()
    await vi.advanceTimersByTimeAsync(1_200_000)
    await state.onEvent(start + offset)
    expect(onStart).toHaveBeenCalledTimes(2)
    state.endSession()
  })
})
