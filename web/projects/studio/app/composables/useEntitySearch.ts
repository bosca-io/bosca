import gql from 'graphql-tag'

export interface EntitySearchResult {
  id: string
  label: string
  entityType: 'metadata' | 'collection' | 'profile'
}

const searchGql = gql`
  query SearchEntities($query: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
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

export function useEntitySearch() {
  const { query } = useGraphQL()

  async function searchEntities(q: string): Promise<EntitySearchResult[]> {
    try {
      const result = await query<{ search?: { search?: { documents?: Array<{ metadata?: { id: string; name?: string }; collection?: { id: string; name?: string }; profile?: { id: string; name?: string; slug?: string } }> } } }>(searchGql, { query: q, limit: 20, offset: 0 })
      const docs = result?.search?.search?.documents ?? []
      const results: EntitySearchResult[] = []
      for (const doc of docs) {
        if (doc.metadata) {
          results.push({
            id: doc.metadata.id,
            label: doc.metadata.name || doc.metadata.id,
            entityType: 'metadata',
          })
        } else if (doc.collection) {
          results.push({
            id: doc.collection.id,
            label: doc.collection.name || doc.collection.id,
            entityType: 'collection',
          })
        } else if (doc.profile) {
          results.push({
            id: doc.profile.id,
            label: doc.profile.name || doc.profile.slug || doc.profile.id,
            entityType: 'profile',
          })
        }
      }
      return results
    } catch {
      return []
    }
  }

  return { searchEntities }
}
