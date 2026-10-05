import gql from 'graphql-tag'

export type OmniSearchKind = 'metadata' | 'collection' | 'profile' | 'task' | 'spec'

export type OmniSearchSource = 'entities' | 'workops'

export interface OmniSearchHit {
  kind: OmniSearchKind
  id: string
  label: string
  hint: string
  /** App route for the matched entity's detail page. */
  path: string
}

export interface OmniSearchSources {
  /** Search the platform entity index (metadata, collections, profiles). Defaults to true. */
  entities?: boolean
  /** Search Work Ops tasks and specs via BQL free-text. Defaults to true. */
  workops?: boolean
}

const ENTITY_LIMIT = 10
const WORKOPS_LIMIT = 5

const entitySearchGql = gql`
  query OmniSearchEntities($query: String!, $limit: Int!) {
    search {
      search(query: {
        query: $query
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: 0
      }) {
        documents {
          metadata { id name }
          collection { id name }
          profile { id name slug }
        }
      }
    }
  }
`

const workOpsSearchGql = gql`
  query OmniSearchWorkOps($taskSource: String!, $specSource: String!, $limit: Int!) {
    workOps {
      savedFilters {
        searchTasks(source: $taskSource, limit: $limit, offset: 0) {
          rows {
            id key summary
            status { name }
          }
        }
        searchSpecs(source: $specSource, limit: $limit, offset: 0) {
          rows {
            id key
            metadata { name }
            status { name }
          }
        }
      }
    }
  }
`

interface EntitySearchResponse {
  search?: {
    search?: {
      documents?: Array<{
        metadata?: { id: string; name?: string } | null
        collection?: { id: string; name?: string } | null
        profile?: { id: string; name?: string; slug?: string } | null
      }>
    }
  }
}

interface WorkOpsSearchResponse {
  workOps?: {
    savedFilters?: {
      searchTasks?: { rows?: Array<{ id: string; key: string; summary: string; status?: { name?: string } | null }> }
      searchSpecs?: { rows?: Array<{ id: string; key: string; metadata?: { name?: string } | null; status?: { name?: string } | null }> }
    }
  }
}

/**
 * Builds the BQL source for a free-text query against one TEXT field
 * (`summary` for tasks, `key` for specs — the only TEXT fields in their
 * respective catalogs). `"` and `\` are escaped per the BQL tokenizer's
 * string-literal rules.
 */
export function toBqlFreeText(field: string, q: string): string {
  const escaped = q.replace(/[\\"]/g, ch => `\\${ch}`)
  return `${field} ~ "${escaped}" ORDER BY modified DESC`
}

/**
 * Omni-search across the platform: content (metadata + collections), people
 * (profiles), and Work Ops (tasks + specs). Sources are queried in parallel
 * and fail independently — a broken source surfaces in `failedSources`
 * without blanking the others' results.
 */
export function useOmniSearch() {
  const { query } = useGraphQL()

  const content = ref<OmniSearchHit[]>([])
  const people = ref<OmniSearchHit[]>([])
  const workops = ref<OmniSearchHit[]>([])
  const searching = ref(false)
  const failedSources = ref<OmniSearchSource[]>([])

  // Monotonic token: a search only commits its results if no newer search
  // (or clear) has started while it was in flight.
  let generation = 0

  function clear() {
    generation++
    content.value = []
    people.value = []
    workops.value = []
    failedSources.value = []
    searching.value = false
  }

  async function runEntities(q: string): Promise<{ content: OmniSearchHit[]; people: OmniSearchHit[] }> {
    const result = await query<EntitySearchResponse>(entitySearchGql, { query: q, limit: ENTITY_LIMIT })
    const docs = result?.search?.search?.documents ?? []
    const contentHits: OmniSearchHit[] = []
    const peopleHits: OmniSearchHit[] = []
    for (const doc of docs) {
      if (doc.metadata) {
        contentHits.push({
          kind: 'metadata',
          id: doc.metadata.id,
          label: doc.metadata.name || doc.metadata.id,
          hint: 'CMS · Content',
          path: `/cms/metadata/${doc.metadata.id}`,
        })
      } else if (doc.collection) {
        contentHits.push({
          kind: 'collection',
          id: doc.collection.id,
          label: doc.collection.name || doc.collection.id,
          hint: 'CMS · Collection',
          path: `/cms/collections/${doc.collection.id}`,
        })
      } else if (doc.profile) {
        peopleHits.push({
          kind: 'profile',
          id: doc.profile.id,
          label: doc.profile.name || doc.profile.slug || doc.profile.id,
          hint: doc.profile.slug ? `@${doc.profile.slug}` : 'Profile',
          path: `/audience/profiles/${doc.profile.id}`,
        })
      }
    }
    return { content: contentHits, people: peopleHits }
  }

  async function runWorkOps(q: string): Promise<OmniSearchHit[]> {
    const result = await query<WorkOpsSearchResponse>(workOpsSearchGql, {
      taskSource: toBqlFreeText('summary', q),
      specSource: toBqlFreeText('key', q),
      limit: WORKOPS_LIMIT,
    })
    const tasks = result?.workOps?.savedFilters?.searchTasks?.rows ?? []
    const specs = result?.workOps?.savedFilters?.searchSpecs?.rows ?? []
    const hits: OmniSearchHit[] = []
    for (const task of tasks) {
      hits.push({
        kind: 'task',
        id: task.id,
        label: task.summary || task.key,
        hint: task.status?.name ? `${task.key} · ${task.status.name}` : task.key,
        path: `/workops/tasks/${task.id}`,
      })
    }
    for (const spec of specs) {
      hits.push({
        kind: 'spec',
        id: spec.id,
        label: spec.metadata?.name || spec.key,
        hint: spec.status?.name ? `${spec.key} · ${spec.status.name}` : spec.key,
        path: `/workops/specs/${spec.id}`,
      })
    }
    return hits
  }

  async function search(q: string, sources: OmniSearchSources = {}): Promise<void> {
    const trimmed = q.trim()
    if (!trimmed) {
      clear()
      return
    }

    const wantEntities = sources.entities !== false
    const wantWorkops = sources.workops !== false
    const gen = ++generation
    searching.value = true

    const [entityResult, workopsResult] = await Promise.allSettled([
      wantEntities ? runEntities(trimmed) : Promise.resolve(null),
      wantWorkops ? runWorkOps(trimmed) : Promise.resolve(null),
    ])

    // A newer search (or clear) superseded this one — drop the results.
    if (gen !== generation) return

    const failures: OmniSearchSource[] = []

    if (entityResult.status === 'fulfilled') {
      content.value = entityResult.value?.content ?? []
      people.value = entityResult.value?.people ?? []
    } else {
      console.error('[omni-search] entity search failed', entityResult.reason)
      failures.push('entities')
      content.value = []
      people.value = []
    }

    if (workopsResult.status === 'fulfilled') {
      workops.value = workopsResult.value ?? []
    } else {
      console.error('[omni-search] workops search failed', workopsResult.reason)
      failures.push('workops')
      workops.value = []
    }

    failedSources.value = failures
    searching.value = false
  }

  return { content, people, workops, searching, failedSources, search, clear }
}
