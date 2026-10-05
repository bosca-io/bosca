import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'
import { useCollectionSubscription } from './useCollectionSubscription'

let capturedCallback: ((event: unknown) => void) | null = null
const mockUseSubscription = vi.fn((_, __, cb) => { capturedCallback = cb })

vi.stubGlobal('useGraphQL', () => ({
  useSubscription: mockUseSubscription,
}))

beforeEach(() => {
  vi.clearAllMocks()
  capturedCallback = null
})

describe('useCollectionSubscription', () => {
  it('calls useSubscription on setup', () => {
    const id = ref('col-123')
    const refresh = vi.fn()
    useCollectionSubscription(id, refresh)
    expect(mockUseSubscription).toHaveBeenCalledTimes(1)
  })

  it('refreshes when matching collection id event arrives', async () => {
    vi.useFakeTimers()
    const id = ref('col-123')
    const refresh = vi.fn()
    useCollectionSubscription(id, refresh, 100)

    capturedCallback!({ collection: { id: 'col-123' } })
    vi.advanceTimersByTime(150)
    expect(refresh).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })

  it('does not refresh for non-matching collection id', async () => {
    vi.useFakeTimers()
    const id = ref('col-123')
    const refresh = vi.fn()
    useCollectionSubscription(id, refresh, 100)

    capturedCallback!({ collection: { id: 'other-id' } })
    vi.advanceTimersByTime(150)
    expect(refresh).not.toHaveBeenCalled()
    vi.useRealTimers()
  })
})
