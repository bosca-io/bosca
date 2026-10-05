import { describe, it, expect, beforeEach } from 'vitest'
import { useFlash } from './useFlash'

beforeEach(() => {
  window.sessionStorage.clear()
})

describe('useFlash', () => {
  it('round-trips a message and clears it so it only surfaces once', () => {
    const { setFlash, takeFlash } = useFlash()
    setFlash('Account linked — you’re signed in.')

    expect(takeFlash()).toBe('Account linked — you’re signed in.')
    // Cleared once taken: a later remount must not re-surface a stale confirmation.
    expect(takeFlash()).toBeNull()
  })

  it('returns null when no flash is pending', () => {
    expect(useFlash().takeFlash()).toBeNull()
  })

  it('persists in sessionStorage so it survives the auth→app hard reload', () => {
    useFlash().setFlash('hello')
    // A fresh useFlash() instance (the post-reload analogue) still reads it.
    expect(useFlash().takeFlash()).toBe('hello')
  })
})
