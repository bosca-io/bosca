import gql from 'graphql-tag'
import { onScopeDispose } from 'vue'
import type { Ref } from 'vue'

const subscriptionGql = gql`
  subscription CollectionChanges {
    collection {
      id
      type
    }
  }
`

export function useCollectionSubscription(
  collectionId: Ref<string>,
  refresh: () => Promise<void> | void,
  debounceMs = 500,
) {
  const { useSubscription } = useGraphQL()
  let timer: ReturnType<typeof setTimeout> | undefined

  function refreshDebounced() {
    clearTimeout(timer)
    timer = setTimeout(() => refresh(), debounceMs)
  }

  useSubscription(subscriptionGql, {}, (event: { collection?: { id?: string } }) => {
    if (event?.collection?.id === collectionId.value) {
      refreshDebounced()
    }
  })

  onScopeDispose(() => {
    if (timer) clearTimeout(timer)
  })
}
