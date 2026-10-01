import gql from 'graphql-tag'
import type { Ref } from 'vue'

const subscriptionGql = gql`
  subscription MetadataChanges {
    metadata {
      id
      type
      version
    }
  }
`

export function useMetadataSubscription(
  metadataId: Ref<string>,
  refresh: () => Promise<void> | void,
  debounceMs = 500,
) {
  const { useSubscription } = useGraphQL()
  let timer: ReturnType<typeof setTimeout> | undefined

  function refreshDebounced() {
    clearTimeout(timer)
    timer = setTimeout(() => refresh(), debounceMs)
  }

  useSubscription(subscriptionGql, {}, (event: { metadata?: { id?: string } }) => {
    if (event?.metadata?.id === metadataId.value) {
      refreshDebounced()
    }
  })
}
