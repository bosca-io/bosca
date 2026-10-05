import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

interface ProfileSearchOptions {
  includeEmailInLabel?: boolean
}

interface SearchProfile {
  id: string
  name?: string
  slug?: string
  attributes?: Array<{
    typeId: string
    attributes?: Record<string, unknown> | null
  }>
}

export interface ProfileMentionOption {
  id: string
  name: string
  handle: string
}

const searchProfilesGql = gql`
  query SearchProfiles($query: String!, $limit: Int!, $offset: Int!, $includeEmail: Boolean!) {
    search {
      search(query: {
        query: $query
        filter: ["_type = \\"profile\\""]
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          profile {
            id
            name
            slug
            attributes @include(if: $includeEmail) {
              typeId
              attributes
            }
          }
        }
      }
    }
  }
`

function profileLabel(profile: SearchProfile, includeEmail: boolean): string {
  const name = profile.name || profile.slug || profile.id
  if (!includeEmail) return name

  const email = profile.attributes
    ?.find(attribute => attribute.typeId === 'bosca.profiles.email')
    ?.attributes?.email
  return typeof email === 'string' && email ? `${name} · ${email}` : name
}

export function useProfileSearch(options: ProfileSearchOptions = {}) {
  const { query } = useGraphQL()
  const includeEmail = options.includeEmailInLabel ?? false

  async function findProfiles(q: string): Promise<SearchProfile[]> {
    try {
      const result = await query<{ search?: { search?: { documents?: Array<{ profile?: SearchProfile }> } } }>(
        searchProfilesGql,
        { query: q, limit: 20, offset: 0, includeEmail },
      )
      return (result?.search?.search?.documents ?? [])
        .filter((d) => d.profile != null)
        .map((d) => d.profile!)
    } catch {
      return []
    }
  }

  async function searchProfiles(q: string): Promise<SelectOption[]> {
    return (await findProfiles(q))
      .map(profile => ({ value: profile.id, label: profileLabel(profile, includeEmail) }))
  }

  async function searchProfileMentions(q: string): Promise<ProfileMentionOption[]> {
    return (await findProfiles(q)).flatMap((profile) => {
      if (!profile.slug) return []
      return [{
        id: profile.id,
        name: profile.name || profile.slug,
        handle: profile.slug,
      }]
    })
  }

  return { searchProfiles, searchProfileMentions }
}
