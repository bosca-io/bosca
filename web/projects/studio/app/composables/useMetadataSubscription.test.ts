import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'
import { useMetadataSubscription } from './useMetadataSubscription'

let capturedCallback: ((event: unknown) => void) | null = null
const mockUseSubscription = vi.fn((_, __, cb) => { capturedCallback = cb })

vi.stubGlobal('useGraphQL', () => ({
  useSubscription: mockUseSubscription,
}))

beforeEach(() => {
  vi.clearAllMocks()
  capturedCallback = null
})

describe('useMetadataSubscription', () => {
  it('calls useSubscription on setup', () => {
    const id = ref('abc-123')
    const refresh = vi.fn()
    useMetadataSubscription(id, refresh)
    expect(mockUseSubscription).toHaveBeenCalledTimes(1)
  })

  it('refreshes when matching metadata id event arrives', async () => {
    vi.useFakeTimers()
    const id = ref('abc-123')
    const refresh = vi.fn()
    useMetadataSubscription(id, refresh, 100)

    capturedCallback!({ metadata: { id: 'abc-123' } })
    vi.advanceTimersByTime(150)
    expect(refresh).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })

  it('does not refresh for non-matching metadata id', async () => {
    vi.useFakeTimers()
    const id = ref('abc-123')
    const refresh = vi.fn()
    useMetadataSubscription(id, refresh, 100)

    capturedCallback!({ metadata: { id: 'other-id' } })
    vi.advanceTimersByTime(150)
    expect(refresh).not.toHaveBeenCalled()
    vi.useRealTimers()
  })

  it('debounces rapid events', async () => {
    vi.useFakeTimers()
    const id = ref('abc-123')
    const refresh = vi.fn()
    useMetadataSubscription(id, refresh, 200)

    capturedCallback!({ metadata: { id: 'abc-123' } })
    vi.advanceTimersByTime(50)
    capturedCallback!({ metadata: { id: 'abc-123' } })
    vi.advanceTimersByTime(50)
    capturedCallback!({ metadata: { id: 'abc-123' } })
    vi.advanceTimersByTime(250)
    expect(refresh).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })
})
