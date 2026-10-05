import gql from 'graphql-tag'
import type { Metadata } from '~/types/graphql'
import { Uploader } from '~/utils/editor/uploader'

const addUploadMetadataGql = gql`
  mutation AddCollectionUploadMetadata($metadata: MetadataInput!) {
    content {
      metadata {
        add(metadata: $metadata) {
          id
          content {
            urls {
              upload {
                url
                headers {
                  name
                  value
                }
              }
            }
          }
        }
      }
    }
  }
`

const getCollectionBySlugGql = gql`
  query GetUploaderCollectionBySlug($slug: String!) {
    content {
      slug(slug: $slug) {
        ... on Collection {
          id
        }
      }
    }
  }
`

const setMetadataReadyGql = gql`
  mutation setMetadataReady($metadataId: UUID!) {
    content {
      metadata {
        setMetadataReady(id: $metadataId)
      }
    }
  }
`

export const useUploader = () => {
  const { query, mutation } = useGraphQL()

  return new Uploader(
    async (file: File) => {
      const slug = file.type.split('/')[0] + 's'
      const result = await query<{ content?: { slug?: { id: string } } }>(getCollectionBySlugGql, { slug })
      return result?.content?.slug?.id
    },
    async (metadata) => {
      const result = await mutation<{ content?: { metadata?: { add?: Metadata } } }>(addUploadMetadataGql, { metadata })
      if (!result?.content?.metadata?.add) throw new Error('failed to create metadata upload')
      return result.content.metadata.add
    },
    async (metadataId) => {
      await mutation(setMetadataReadyGql, { metadataId })
    },
    () => {
      return () => {}
    }
  )
}
