import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref, computed, toValue } from 'vue'
import { useGraphQL } from './useGraphQL'

// `useAsyncQuery` delegates to Nuxt's global `useAsyncData`. In the happy-dom
// test env those auto-imports don't exist, so stub the two the composable
// reaches for at runtime and capture its key, handler, and options. Transport
// tests invoke the handler; cache tests only inspect its identity and watches.
interface CapturedCall {
  key: unknown
  handler: () => Promise<unknown>
  options: Record<string, unknown>
}
const calls: CapturedCall[] = []

beforeEach(() => {
  calls.length = 0
  vi.stubGlobal('toValue', toValue)
  vi.stubGlobal('useAsyncData', (key: unknown, handler: () => Promise<unknown>, options: Record<string, unknown>) => {
    calls.push({ key, handler, options })
    return { data: ref(null), status: ref('idle'), refresh: vi.fn() }
  })
})

describe('anonymous GraphQL queries', () => {
  const authHeaders = vi.fn()
  const fetch = vi.fn()
  beforeEach(() => {
    authHeaders.mockReset().mockResolvedValue({ Authorization: 'Bearer test-session', 'X-Installation-ID': 'studio' })
    fetch.mockReset().mockResolvedValue({ data: { recommendation: { recommended: [] } } })
    vi.stubGlobal('useNuxtApp', () => ({ $auth: { getAuthHeaders: authHeaders } }))
    vi.stubGlobal('$fetch', fetch)
  })

  it('omits session headers and cookies without changing ordinary queries or mutations', async () => {
    const { query, mutation } = useGraphQL()
    await query('query { recommendation { recommended(metadataId: "item", limit: 12) { id } } }', {}, { anonymous: true })
    expect(authHeaders).not.toHaveBeenCalled()
    expect(fetch).toHaveBeenLastCalledWith('/graphql', expect.objectContaining({
      credentials: 'omit', headers: { 'Content-Type': 'application/json' },
    }))
    await query(QUERY)
    await mutation('mutation { performAction }')
    expect(authHeaders).toHaveBeenCalledTimes(2)
    for (const [, options] of fetch.mock.calls.slice(1)) {
      expect(options.headers.Authorization).toBe('Bearer test-session')
      expect(options.credentials).toBeUndefined()
    }
  })

  it('separates authenticated and anonymous cache entries and executes anonymous lifecycle queries without credentials', async () => {
    const { useAsyncQuery } = useGraphQL()
    useAsyncQuery('preview', QUERY, { workloadId: 'item' })
    useAsyncQuery('preview', QUERY, { workloadId: 'item' }, { anonymous: true })
    expect(resolveKey(calls[0]!.key)).not.toBe(resolveKey(calls[1]!.key))
    await calls[1]!.handler()
    expect(authHeaders).not.toHaveBeenCalled()
    expect(fetch).toHaveBeenCalledWith('/graphql', expect.objectContaining({ credentials: 'omit' }))
    useAsyncQuery('preview', QUERY)
    useAsyncQuery('preview', QUERY, undefined, { anonymous: true })
    expect(resolveKey(calls[2]!.key)).not.toBe(resolveKey(calls[3]!.key))
  })

  it('surfaces GraphQL errors from an anonymous request', async () => {
    fetch.mockResolvedValue({ errors: [{ message: 'Public recommendations unavailable' }] })
    await expect(useGraphQL().query(QUERY, {}, { anonymous: true })).rejects.toThrow('Public recommendations unavailable')
  })
})

// The key Nuxt actually slots data under: a getter when the query has
// variables, a plain string otherwise.
function resolveKey(key: unknown): string {
  return typeof key === 'function' ? (key as () => string)() : String(key)
}

const QUERY = 'query Q($workloadId: ID) { kubernetes { pods(workloadId: $workloadId) { total } } }'

describe('useAsyncQuery cache keys', () => {
  it('folds distinct filter values into distinct keys', () => {
    const { useAsyncQuery } = useGraphQL()
    useAsyncQuery('k8s.pods', QUERY, { workloadId: computed(() => 'wl-1') })
    useAsyncQuery('k8s.pods', QUERY, { workloadId: computed(() => 'wl-2') })

    const k1 = resolveKey(calls[0]!.key)
    const k2 = resolveKey(calls[1]!.key)
    expect(k1).not.toBe(k2)
    expect(k1.startsWith('k8s.pods:')).toBe(true)
  })

  it('reuses one key for identical filter values', () => {
    const { useAsyncQuery } = useGraphQL()
    useAsyncQuery('k8s.pods', QUERY, { workloadId: computed(() => 'wl-1') })
    useAsyncQuery('k8s.pods', QUERY, { workloadId: computed(() => 'wl-1') })

    expect(resolveKey(calls[0]!.key)).toBe(resolveKey(calls[1]!.key))
  })

  it('separates the unfiltered list from a workload-scoped list (the reported bug)', () => {
    const { useAsyncQuery } = useGraphQL()
    // The pods page: cluster only, no workload filter.
    useAsyncQuery('k8s.pods', QUERY, {
      cluster: computed(() => 'c1'),
      workloadId: computed(() => null),
    })
    // The workload drawer: same cluster, scoped to one workload.
    useAsyncQuery('k8s.pods', QUERY, {
      cluster: computed(() => 'c1'),
      workloadId: computed(() => 'wl-1'),
    })

    expect(resolveKey(calls[0]!.key)).not.toBe(resolveKey(calls[1]!.key))
  })

  it('tracks reactive variable changes so the key follows the live filter', () => {
    const { useAsyncQuery } = useGraphQL()
    const workloadId = ref<string | null>(null)
    useAsyncQuery('k8s.pods', QUERY, { workloadId: computed(() => workloadId.value) })

    const before = resolveKey(calls[0]!.key)
    workloadId.value = 'wl-9'
    const after = resolveKey(calls[0]!.key)
    expect(before).not.toBe(after)
  })

  it('is independent of variable property order', () => {
    const { useAsyncQuery } = useGraphQL()
    useAsyncQuery('k8s.pods', QUERY, {
      cluster: computed(() => 'c1'),
      workloadId: computed(() => 'wl-1'),
    })
    useAsyncQuery('k8s.pods', QUERY, {
      workloadId: computed(() => 'wl-1'),
      cluster: computed(() => 'c1'),
    })

    expect(resolveKey(calls[0]!.key)).toBe(resolveKey(calls[1]!.key))
  })

  it('passes the reactive variables through as watch sources', () => {
    const { useAsyncQuery } = useGraphQL()
    useAsyncQuery('k8s.pods', QUERY, { workloadId: computed(() => 'wl-1') })

    expect(Array.isArray(calls[0]!.options.watch)).toBe(true)
    expect((calls[0]!.options.watch as unknown[]).length).toBe(1)
  })

  it('leaves variable-less queries on a plain static key', () => {
    const { useAsyncQuery } = useGraphQL()
    useAsyncQuery('k8s.clusters', 'query { kubernetes { clusters { id } } }')

    expect(calls[0]!.key).toBe('k8s.clusters')
  })

  it('unwraps refs nested inside a computed variables object without throwing', () => {
    // The feature-flag exposures query passes a computed that returns
    // `{ id: <ref>, since, until }`. Before resolveVariables unwrapped both
    // layers, the inner ref reached JSON.stringify and threw "Converting
    // circular structure to JSON" on Vue's Dep graph, crashing the page.
    const { useAsyncQuery } = useGraphQL()
    const flagId = ref('flag-1')
    const exposureVars = computed(() => ({ id: flagId, since: 's', until: 'u' }))

    expect(() => useAsyncQuery('flag.exposures', QUERY, exposureVars)).not.toThrow()

    const key = resolveKey(calls[0]!.key)
    // The unwrapped id value must land in the key, not a serialized ref.
    expect(key).toContain('flag-1')
    expect(key).not.toContain('__v_isRef')
  })

  it('tracks the outer computed when variables is a single computed object', () => {
    const { useAsyncQuery } = useGraphQL()
    const flagId = ref('flag-1')
    const exposureVars = computed(() => ({ id: flagId.value, since: 's', until: 'u' }))
    useAsyncQuery('flag.exposures', QUERY, exposureVars)

    const before = resolveKey(calls[0]!.key)
    flagId.value = 'flag-2'
    const after = resolveKey(calls[0]!.key)
    expect(before).not.toBe(after)
    // The whole computed is the single watch source.
    expect((calls[0]!.options.watch as unknown[]).length).toBe(1)
  })
})
