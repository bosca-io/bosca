import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useCreateFromQuery } from './useCreateFromQuery'

const replaceMock = vi.fn()
let currentQuery: Record<string, unknown> = {}

vi.stubGlobal('useRoute', () => ({ path: '/cms/documents', params: {}, query: currentQuery }))
vi.stubGlobal('useRouter', () => ({ push: vi.fn(), replace: replaceMock }))
// The setup-file onMounted no-ops outside a component instance; run the
// hook immediately so the composable's mount behavior is observable.
vi.stubGlobal('onMounted', (fn: () => void) => { fn() })

beforeEach(() => {
  replaceMock.mockClear()
  currentQuery = {}
})

describe('useCreateFromQuery', () => {
  it('opens the dialog and strips the `new` param while preserving other params', () => {
    currentQuery = { new: '1', tab: 'all' }
    const open = vi.fn()
    useCreateFromQuery(open)

    expect(open).toHaveBeenCalledTimes(1)
    expect(replaceMock).toHaveBeenCalledWith({ query: { tab: 'all' } })
  })

  it('does nothing when the `new` param is absent', () => {
    currentQuery = { tab: 'all' }
    const open = vi.fn()
    useCreateFromQuery(open)

    expect(open).not.toHaveBeenCalled()
    expect(replaceMock).not.toHaveBeenCalled()
  })

  it('treats a value-less `?new` (null in vue-router) as present', () => {
    currentQuery = { new: null }
    const open = vi.fn()
    useCreateFromQuery(open)

    expect(open).toHaveBeenCalledTimes(1)
    expect(replaceMock).toHaveBeenCalledWith({ query: {} })
  })
})
