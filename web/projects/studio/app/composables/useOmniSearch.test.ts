import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useOmniSearch, toBqlFreeText } from './useOmniSearch'

const queryMock = vi.fn()

vi.stubGlobal('useGraphQL', () => ({ query: queryMock }))

function entityResponse(documents: unknown[] = [
  { metadata: { id: 'm1', name: 'Hero Banner' } },
  { collection: { id: 'c1', name: 'Winter Assets' } },
  { profile: { id: 'p1', name: 'Ari Chen', slug: 'ari' } },
]) {
  return { search: { search: { documents } } }
}

function workOpsResponse() {
  return {
    workOps: {
      savedFilters: {
        searchTasks: {
          rows: [
            { id: 't1', key: 'TASK-1', summary: 'Fix hero banner', status: { name: 'In Progress' } },
          ],
        },
        searchSpecs: {
          rows: [
            { id: 's1', key: 'SPEC-2', metadata: { name: 'Hero spec' }, status: { name: 'Draft' } },
          ],
        },
      },
    },
  }
}

function isWorkOpsCall(vars: Record<string, unknown> | undefined) {
  return vars !== undefined && 'taskSource' in vars
}

beforeEach(() => {
  queryMock.mockReset()
  queryMock.mockImplementation(async (_gql, vars) =>
    isWorkOpsCall(vars) ? workOpsResponse() : entityResponse(),
  )
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('toBqlFreeText', () => {
  it('wraps the query in a free-text match against the given field ordered by modified', () => {
    expect(toBqlFreeText('summary', 'hero')).toBe('summary ~ "hero" ORDER BY modified DESC')
    expect(toBqlFreeText('key', 'SPEC-4')).toBe('key ~ "SPEC-4" ORDER BY modified DESC')
  })

  it('escapes double quotes and backslashes', () => {
    expect(toBqlFreeText('summary', 'say "hi" \\ now')).toBe(
      'summary ~ "say \\"hi\\" \\\\ now" ORDER BY modified DESC',
    )
  })
})

describe('useOmniSearch', () => {
  it('maps entity documents into content and people hits with detail routes', async () => {
    const omni = useOmniSearch()
    await omni.search('hero')

    expect(omni.content.value).toEqual([
      { kind: 'metadata', id: 'm1', label: 'Hero Banner', hint: 'CMS · Content', path: '/cms/metadata/m1' },
      { kind: 'collection', id: 'c1', label: 'Winter Assets', hint: 'CMS · Collection', path: '/cms/collections/c1' },
    ])
    expect(omni.people.value).toEqual([
      { kind: 'profile', id: 'p1', label: 'Ari Chen', hint: '@ari', path: '/audience/profiles/p1' },
    ])
  })

  it('maps workops tasks and specs into hits with key/status hints', async () => {
    const omni = useOmniSearch()
    await omni.search('hero')

    expect(omni.workops.value).toEqual([
      { kind: 'task', id: 't1', label: 'Fix hero banner', hint: 'TASK-1 · In Progress', path: '/workops/tasks/t1' },
      { kind: 'spec', id: 's1', label: 'Hero spec', hint: 'SPEC-2 · Draft', path: '/workops/specs/s1' },
    ])
  })

  it('sends escaped per-catalog BQL sources to the workops search', async () => {
    const omni = useOmniSearch()
    await omni.search('say "hi"')

    const workOpsVars = queryMock.mock.calls
      .map(call => call[1] as Record<string, unknown>)
      .find(vars => isWorkOpsCall(vars))
    expect(workOpsVars?.taskSource).toBe('summary ~ "say \\"hi\\"" ORDER BY modified DESC')
    expect(workOpsVars?.specSource).toBe('key ~ "say \\"hi\\"" ORDER BY modified DESC')
  })

  it('falls back to id/slug/key when names are missing', async () => {
    queryMock.mockImplementation(async (_gql, vars) => {
      if (isWorkOpsCall(vars)) {
        return {
          workOps: {
            savedFilters: {
              searchTasks: { rows: [{ id: 't9', key: 'TASK-9', summary: '', status: null }] },
              searchSpecs: { rows: [{ id: 's9', key: 'SPEC-9', metadata: null, status: null }] },
            },
          },
        }
      }
      return entityResponse([
        { metadata: { id: 'm9' } },
        { profile: { id: 'p9', slug: 'p-slug' } },
      ])
    })

    const omni = useOmniSearch()
    await omni.search('x y')

    expect(omni.content.value).toEqual([
      { kind: 'metadata', id: 'm9', label: 'm9', hint: 'CMS · Content', path: '/cms/metadata/m9' },
    ])
    expect(omni.people.value).toEqual([
      { kind: 'profile', id: 'p9', label: 'p-slug', hint: '@p-slug', path: '/audience/profiles/p9' },
    ])
    expect(omni.workops.value).toEqual([
      { kind: 'task', id: 't9', label: 'TASK-9', hint: 'TASK-9', path: '/workops/tasks/t9' },
      { kind: 'spec', id: 's9', label: 'SPEC-9', hint: 'SPEC-9', path: '/workops/specs/s9' },
    ])
  })

  it('skips the workops source when disabled', async () => {
    const omni = useOmniSearch()
    await omni.search('hero', { workops: false })

    expect(queryMock).toHaveBeenCalledTimes(1)
    expect(isWorkOpsCall(queryMock.mock.calls[0]?.[1] as Record<string, unknown>)).toBe(false)
    expect(omni.workops.value).toEqual([])
    expect(omni.content.value.length).toBeGreaterThan(0)
  })

  it('skips the entity source when disabled', async () => {
    const omni = useOmniSearch()
    await omni.search('hero', { entities: false })

    expect(queryMock).toHaveBeenCalledTimes(1)
    expect(isWorkOpsCall(queryMock.mock.calls[0]?.[1] as Record<string, unknown>)).toBe(true)
    expect(omni.content.value).toEqual([])
    expect(omni.people.value).toEqual([])
    expect(omni.workops.value.length).toBeGreaterThan(0)
  })

  it('keeps workops results and reports the failed source when entity search throws', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    queryMock.mockImplementation(async (_gql, vars) => {
      if (isWorkOpsCall(vars)) return workOpsResponse()
      throw new Error('index offline')
    })

    const omni = useOmniSearch()
    await omni.search('hero')

    expect(omni.content.value).toEqual([])
    expect(omni.people.value).toEqual([])
    expect(omni.workops.value.length).toBe(2)
    expect(omni.failedSources.value).toEqual(['entities'])
    expect(consoleError).toHaveBeenCalled()
  })

  it('keeps entity results and reports the failed source when workops search throws', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    queryMock.mockImplementation(async (_gql, vars) => {
      if (isWorkOpsCall(vars)) throw new Error('bql planner down')
      return entityResponse()
    })

    const omni = useOmniSearch()
    await omni.search('hero')

    expect(omni.content.value.length).toBe(2)
    expect(omni.workops.value).toEqual([])
    expect(omni.failedSources.value).toEqual(['workops'])
    expect(consoleError).toHaveBeenCalled()
  })

  it('clears the failure flag once a subsequent search succeeds', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    queryMock.mockRejectedValueOnce(new Error('boom')).mockRejectedValueOnce(new Error('boom'))

    const omni = useOmniSearch()
    await omni.search('hero')
    expect(omni.failedSources.value).toEqual(['entities', 'workops'])

    await omni.search('hero again')
    expect(omni.failedSources.value).toEqual([])
  })

  it('ignores stale responses when a newer search resolves first', async () => {
    const pending = new Map<string, (value: unknown) => void>()
    queryMock.mockImplementation((_gql, vars) => new Promise((resolve) => {
      pending.set((vars as { query: string }).query, resolve)
    }))

    const omni = useOmniSearch()
    const first = omni.search('alpha', { workops: false })
    const second = omni.search('beta', { workops: false })

    pending.get('beta')?.(entityResponse([{ metadata: { id: 'm-beta', name: 'Beta Doc' } }]))
    await second
    expect(omni.content.value.map(h => h.id)).toEqual(['m-beta'])

    pending.get('alpha')?.(entityResponse([{ metadata: { id: 'm-alpha', name: 'Alpha Doc' } }]))
    await first
    expect(omni.content.value.map(h => h.id)).toEqual(['m-beta'])
  })

  it('toggles the searching flag around a search', async () => {
    let resolve: ((value: unknown) => void) | undefined
    queryMock.mockImplementation(() => new Promise((r) => { resolve = r }))

    const omni = useOmniSearch()
    const run = omni.search('hero', { workops: false })
    expect(omni.searching.value).toBe(true)

    resolve?.(entityResponse())
    await run
    expect(omni.searching.value).toBe(false)
  })

  it('clears results and issues no queries for a blank query', async () => {
    const omni = useOmniSearch()
    await omni.search('hero')
    expect(omni.content.value.length).toBeGreaterThan(0)

    queryMock.mockClear()
    await omni.search('   ')
    expect(queryMock).not.toHaveBeenCalled()
    expect(omni.content.value).toEqual([])
    expect(omni.people.value).toEqual([])
    expect(omni.workops.value).toEqual([])
  })

  it('clear() resets all state and supersedes in-flight searches', async () => {
    const pending = new Map<string, (value: unknown) => void>()
    queryMock.mockImplementation((_gql, vars) => new Promise((resolve) => {
      pending.set((vars as { query: string }).query, resolve)
    }))

    const omni = useOmniSearch()
    const run = omni.search('alpha', { workops: false })
    omni.clear()
    expect(omni.searching.value).toBe(false)

    pending.get('alpha')?.(entityResponse())
    await run
    expect(omni.content.value).toEqual([])
  })
})
