import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref, nextTick, type Ref } from 'vue'
import { usePagedList } from './usePagedList'

// usePagedList resolves its list via useGraphQL().useAsyncQuery — a Nuxt auto-import. Stub it to a
// controllable data ref and capture the vars the composable passes through.
let dataRef: Ref<unknown>
let capturedVars: Record<string, unknown>

beforeEach(() => {
  dataRef = ref<unknown>(null)
  capturedVars = {}
  vi.stubGlobal('useGraphQL', () => ({
    useAsyncQuery: (_key: string, _gql: unknown, vars: Record<string, unknown>) => {
      capturedVars = vars
      return { data: dataRef, status: ref('idle'), refresh: vi.fn(), error: ref(null) }
    },
  }))
})

interface Row { id: number }
const extract = (d: unknown) => (d as { ecom?: { items?: Row[] } })?.ecom?.items
const rows = (n: number): Row[] => Array.from({ length: n }, (_, i) => ({ id: i }))

describe('usePagedList', () => {
  it('slices to the page size and flags hasMore when an extra row comes back', () => {
    const { rows: page, hasMore } = usePagedList<Row>('k', {} as never, { companyId: ref('c') }, extract, 25)
    dataRef.value = { ecom: { items: rows(26) } } // pageSize + 1
    expect(page.value).toHaveLength(25)
    expect(hasMore.value).toBe(true)
  })

  it('returns everything and hasMore=false when under a full page', () => {
    const { rows: page, hasMore } = usePagedList<Row>('k', {} as never, { companyId: ref('c') }, extract, 25)
    dataRef.value = { ecom: { items: rows(10) } }
    expect(page.value).toHaveLength(10)
    expect(hasMore.value).toBe(false)
  })

  it('requests offset 0 and limit = pageSize + 1', () => {
    usePagedList<Row>('k', {} as never, { companyId: ref('c') }, extract, 25)
    expect((capturedVars.offset as Ref<number>).value).toBe(0)
    expect((capturedVars.limit as Ref<number>).value).toBe(26)
  })

  it('resets the offset to the first page when a scope var changes', async () => {
    const scope = ref('a')
    const { offset } = usePagedList<Row>('k', {} as never, { companyId: scope }, extract, 25)
    offset.value = 50
    scope.value = 'b'
    await nextTick()
    expect(offset.value).toBe(0)
  })
})
