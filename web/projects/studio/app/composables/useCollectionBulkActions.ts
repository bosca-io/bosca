import gql from 'graphql-tag'

export interface CollectionBulkActionItem {
  id: string
  workflow?: { state: string; pending: string | null } | null
}

export interface CollectionBulkActionsOptions {
  /** Called after any bulk operation completes to refresh the listing. */
  refreshAll: () => Promise<void> | void
  /** Reactive list of all items currently visible — used to determine which actions are relevant. */
  data?: Ref<CollectionBulkActionItem[]> | ComputedRef<CollectionBulkActionItem[]>
}

/**
 * Manages bulk selection state and batch operations for collection listings.
 * Collections use different GraphQL mutations than metadata (e.g.
 * beginCollectionTransition instead of beginTransition).
 */
export function useCollectionBulkActions(options: CollectionBulkActionsOptions) {
  const { refreshAll } = options
  const { mutation: gqlMutation } = useGraphQL()
  const toast = useToast()
  const itemLabel = 'collection(s)'

  // ── Selection state ─────────────────────────────────────────────────────────
  const selected = ref<Set<string>>(new Set())
  const selectedCount = computed(() => selected.value.size)

  const selectedItems = computed<CollectionBulkActionItem[]>(() => {
    if (!options.data) return []
    const ids = selected.value
    return toValue(options.data).filter(item => ids.has(item.id))
  })

  function toggleSelect(id: string) {
    const s = new Set(selected.value)
    if (s.has(id)) s.delete(id)
    else s.add(id)
    selected.value = s
  }

  function clearSelection() {
    selected.value = new Set()
  }

  function isSelected(id: string): boolean {
    return selected.value.has(id)
  }

  function allOnPageSelected(pageIds: string[]): boolean {
    return pageIds.length > 0 && pageIds.every(id => selected.value.has(id))
  }

  function someOnPageSelected(pageIds: string[]): boolean {
    return pageIds.some(id => selected.value.has(id)) && !allOnPageSelected(pageIds)
  }

  function toggleSelectAll(pageIds: string[]) {
    if (allOnPageSelected(pageIds)) {
      const s = new Set(selected.value)
      for (const id of pageIds) s.delete(id)
      selected.value = s
    } else {
      const s = new Set(selected.value)
      for (const id of pageIds) s.add(id)
      selected.value = s
    }
  }

  // ── Action visibility ────────────────────────────────────────────────────────
  function getEffectiveState(item: CollectionBulkActionItem): string {
    return item.workflow?.pending ?? item.workflow?.state ?? 'draft'
  }

  const showPublish = computed(() => {
    if (selectedItems.value.length === 0) return true
    return selectedItems.value.some(c => getEffectiveState(c) !== 'published')
  })

  const showUnpublish = computed(() => {
    if (selectedItems.value.length === 0) return true
    return selectedItems.value.some(c => getEffectiveState(c) === 'published')
  })

  // ── Modal state ─────────────────────────────────────────────────────────────
  const publishOpen = ref(false)
  const publishing = ref(false)

  const unpublishOpen = ref(false)
  const unpublishing = ref(false)

  const readyOpen = ref(false)
  const readying = ref(false)

  const deleteOpen = ref(false)
  const deleting = ref(false)

  // ── Mutations ───────────────────────────────────────────────────────────────
  const transitionGql = gql`
    mutation BulkCollectionTransition($id: UUID!, $state: String!, $status: String!) {
      content {
        transitions {
          beginCollectionTransition(request: { collectionId: $id, stateId: $state, status: $status })
        }
      }
    }
  `

  const setReadyGql = gql`
    mutation BulkCollectionSetReady($id: UUID!) {
      content { collections { setReady(id: $id) } }
    }
  `

  const deleteAllGql = gql`
    mutation BulkDeleteCollections($ids: [UUID!]!) {
      content { collection { deleteAll(collectionIds: $ids) } }
    }
  `

  // ── Actions ─────────────────────────────────────────────────────────────────

  async function onPublish() {
    publishing.value = true
    const ids = [...selected.value]
    let ok = 0
    for (const id of ids) {
      try {
        await gqlMutation(transitionGql, { id, state: 'published', status: 'Bulk published' })
        ok++
      } catch { /* skip failures */ }
    }
    const skipped = ids.length - ok
    if (skipped > 0) {
      toast.warn(`Published ${ok} ${itemLabel}, ${skipped} skipped`)
    } else {
      toast.success(`Published ${ok} ${itemLabel}`)
    }
    publishing.value = false
    publishOpen.value = false
    clearSelection()
    await refreshAll()
  }

  async function onUnpublish() {
    unpublishing.value = true
    const ids = [...selected.value]
    let ok = 0
    for (const id of ids) {
      try {
        await gqlMutation(transitionGql, { id, state: 'draft', status: 'Bulk unpublished' })
        ok++
      } catch { /* skip failures */ }
    }
    const skipped = ids.length - ok
    if (skipped > 0) {
      toast.warn(`Unpublished ${ok} ${itemLabel}, ${skipped} skipped`)
    } else {
      toast.success(`Unpublished ${ok} ${itemLabel}`)
    }
    unpublishing.value = false
    unpublishOpen.value = false
    clearSelection()
    await refreshAll()
  }

  async function onSetReady() {
    readying.value = true
    const ids = [...selected.value]
    let ok = 0
    for (const id of ids) {
      try {
        await gqlMutation(setReadyGql, { id })
        ok++
      } catch { /* skip failures */ }
    }
    const skipped = ids.length - ok
    if (skipped > 0) {
      toast.warn(`Set ready: ${ok} ${itemLabel}, ${skipped} skipped`)
    } else {
      toast.success(`Set ${ok} ${itemLabel} as ready`)
    }
    readying.value = false
    readyOpen.value = false
    clearSelection()
    await refreshAll()
  }

  async function onDelete() {
    deleting.value = true
    const ids = [...selected.value]
    let ok = 0
    try {
      const result = await gqlMutation<{ content: { collection: { deleteAll: number } } }>(deleteAllGql, { ids })
      ok = result?.content?.collection?.deleteAll ?? 0
    } catch (e) {
      console.error('Failed to bulk delete collections', e)
      toast.error('Failed to delete collections')
      deleting.value = false
      deleteOpen.value = false
      return
    }
    const skipped = ids.length - ok
    if (skipped > 0) {
      toast.warn(`Deleted ${ok} ${itemLabel}, ${skipped} skipped`)
    } else {
      toast.success(`Deleted ${ok} ${itemLabel}`)
    }
    deleting.value = false
    deleteOpen.value = false
    clearSelection()
    await refreshAll()
  }

  return {
    selected,
    selectedCount,
    toggleSelect,
    clearSelection,
    isSelected,
    allOnPageSelected,
    someOnPageSelected,
    toggleSelectAll,

    showPublish,
    showUnpublish,

    publishOpen,
    publishing,
    onPublish,

    unpublishOpen,
    unpublishing,
    onUnpublish,

    readyOpen,
    readying,
    onSetReady,

    deleteOpen,
    deleting,
    onDelete,
  }
}
