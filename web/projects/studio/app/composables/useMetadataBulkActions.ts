import gql from 'graphql-tag'

export interface BulkActionItem {
  id: string
  ready?: boolean
  public?: boolean
  publicContent?: boolean
  publicSupplementary?: boolean
  searchable?: boolean
  recommendable?: boolean
  workflow?: { state: string; pending: string | null } | null
  content?: { type?: string | null } | null
  /** Processed media asset (HLS streams etc.); present once media processing has run. */
  media?: unknown
}

export interface MetadataBulkActionsOptions {
  /** Called after any bulk operation completes to refresh the listing. */
  refreshAll: () => Promise<void> | void
  /** Label used in toast messages (e.g. "document(s)", "data item(s)"). */
  itemLabel?: string
  /** Reactive list of all items currently visible — used to determine which actions are relevant. */
  data?: Ref<BulkActionItem[]> | ComputedRef<BulkActionItem[]>
}

/**
 * Manages bulk selection state and batch operations for metadata listings.
 * Provides reactive selection tracking, batch GraphQL mutations, and modal
 * state for confirmation dialogs — all reusable across CMS pages.
 */
export function useMetadataBulkActions(options: MetadataBulkActionsOptions) {
  const { refreshAll, itemLabel = 'item(s)' } = options
  const { mutation: gqlMutation } = useGraphQL()
  const toast = useToast()

  // ── Selection state ─────────────────────────────────────────────────────────
  const selected = ref<Set<string>>(new Set())
  const selectedCount = computed(() => selected.value.size)

  const selectedItems = computed<BulkActionItem[]>(() => {
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
  function getEffectiveState(item: BulkActionItem): string {
    return item.workflow?.pending ?? item.workflow?.state ?? 'draft'
  }

  const showReady = computed(() => {
    if (selectedItems.value.length === 0) return true
    return selectedItems.value.some(m => !m.ready)
  })

  const showPublish = computed(() => {
    if (selectedItems.value.length === 0) return true
    return selectedItems.value.some(m => getEffectiveState(m) !== 'published')
  })

  const showUnpublish = computed(() => {
    if (selectedItems.value.length === 0) return true
    return selectedItems.value.some(m => getEffectiveState(m) === 'published')
  })

  function isMediaContentType(type?: string | null) {
    return !!type && (type.startsWith('video/') || type.startsWith('audio/'))
  }

  // Media (Mux) actions only apply on listings that include media items, so
  // unlike the other actions these stay hidden when nothing media-related is
  // selected — a "Process Media" button on a documents listing is noise.
  const showProcessMedia = computed(() =>
    selectedItems.value.some(m => isMediaContentType(m.content?.type) && m.media == null))

  const showDeleteMedia = computed(() =>
    selectedItems.value.some(m => m.media != null))

  // ── Modal state ─────────────────────────────────────────────────────────────
  const publishOpen = ref(false)
  const publishing = ref(false)
  const publishPublic = ref(true)
  const publishPublicContent = ref(true)
  const publishPublicSupplementary = ref(true)
  const publishSearchable = ref(true)
  const publishRecommendable = ref(true)

  const unpublishOpen = ref(false)
  const unpublishing = ref(false)

  const readyOpen = ref(false)
  const readying = ref(false)

  const publicOpen = ref(false)
  const publicUpdating = ref(false)
  const publicPublic = ref(true)
  const publicContent = ref(true)
  const publicSupplementary = ref(true)
  const publicSearchable = ref(true)
  const publicRecommendable = ref(true)

  const deleteOpen = ref(false)
  const deleting = ref(false)

  const processMediaOpen = ref(false)
  const processingMedia = ref(false)

  const deleteMediaOpen = ref(false)
  const deletingMedia = ref(false)

  // ── Batch mutations ─────────────────────────────────────────────────────────
  const bulkTransitionGql = gql`
    mutation BulkTransitions($metadataIds: [UUID!]!, $stateId: String!, $status: String!) {
      content {
        transitions {
          beginTransitions(metadataIds: $metadataIds, stateId: $stateId, status: $status)
        }
      }
    }
  `

  const bulkSetPublicGql = gql`
    mutation BulkSetPublic($metadataIds: [UUID!]!, $public: Boolean!, $publicContent: Boolean!, $publicSupplementary: Boolean!, $searchable: Boolean!, $recommendable: Boolean!) {
      content {
        metadata {
          setPublicAll(metadataIds: $metadataIds, public: $public, publicContent: $publicContent, publicSupplementary: $publicSupplementary, searchable: $searchable, recommendable: $recommendable)
        }
      }
    }
  `

  const bulkSetReadyGql = gql`
    mutation BulkSetReady($metadataIds: [UUID!]!) {
      content {
        metadata {
          setMetadataReadyAll(metadataIds: $metadataIds)
        }
      }
    }
  `

  const bulkDeleteGql = gql`
    mutation BulkDelete($metadataIds: [UUID!]!) {
      content {
        metadata {
          deleteAll(metadataIds: $metadataIds)
        }
      }
    }
  `

  const bulkProcessMediaGql = gql`
    mutation BulkProcessMedia($metadataIds: [UUID!]!) {
      content {
        metadata {
          processMediaAll(metadataIds: $metadataIds)
        }
      }
    }
  `

  const bulkDeleteMediaGql = gql`
    mutation BulkDeleteMedia($metadataIds: [UUID!]!) {
      content {
        metadata {
          deleteMediaAll(metadataIds: $metadataIds)
        }
      }
    }
  `

  // ── Actions ─────────────────────────────────────────────────────────────────

  async function onPublish() {
    publishing.value = true
    const ids = [...selected.value]
    try {
      await gqlMutation(bulkSetPublicGql, {
        metadataIds: ids,
        public: publishPublic.value,
        publicContent: publishPublicContent.value,
        publicSupplementary: publishPublicSupplementary.value,
        searchable: publishSearchable.value,
        recommendable: publishRecommendable.value,
      })
      const result = await gqlMutation<{
        content: { transitions: { beginTransitions: number } }
      }>(bulkTransitionGql, {
        metadataIds: ids,
        stateId: 'published',
        status: 'Bulk published',
      })
      const published = result.content.transitions.beginTransitions
      const skipped = ids.length - published
      if (skipped > 0) {
        toast.warn(`Published ${published} ${itemLabel}, ${skipped} skipped`)
      } else {
        toast.success(`Published ${published} ${itemLabel}`)
      }
    } catch (e: unknown) {
      toast.error(`Bulk publish failed: ${e instanceof Error ? e.message : 'Unknown error'}`)
    }
    publishing.value = false
    publishOpen.value = false
    clearSelection()
    await refreshAll()
  }

  async function onUnpublish() {
    unpublishing.value = true
    const ids = [...selected.value]
    try {
      const result = await gqlMutation<{
        content: { transitions: { beginTransitions: number } }
      }>(bulkTransitionGql, {
        metadataIds: ids,
        stateId: 'draft',
        status: 'Bulk unpublished',
      })
      const unpublished = result.content.transitions.beginTransitions
      const skipped = ids.length - unpublished
      if (skipped > 0) {
        toast.warn(`Unpublished ${unpublished} ${itemLabel}, ${skipped} skipped`)
      } else {
        toast.success(`Unpublished ${unpublished} ${itemLabel}`)
      }
    } catch (e: unknown) {
      toast.error(`Bulk unpublish failed: ${e instanceof Error ? e.message : 'Unknown error'}`)
    }
    unpublishing.value = false
    unpublishOpen.value = false
    clearSelection()
    await refreshAll()
  }

  async function onSetReady() {
    readying.value = true
    const ids = [...selected.value]
    try {
      const result = await gqlMutation<{
        content: { metadata: { setMetadataReadyAll: number } }
      }>(bulkSetReadyGql, { metadataIds: ids })
      const updated = result.content.metadata.setMetadataReadyAll
      const skipped = ids.length - updated
      if (skipped > 0) {
        toast.warn(`Set ready: ${updated} ${itemLabel}, ${skipped} skipped`)
      } else {
        toast.success(`Set ${updated} ${itemLabel} as ready`)
      }
    } catch (e: unknown) {
      toast.error(`Bulk set ready failed: ${e instanceof Error ? e.message : 'Unknown error'}`)
    }
    readying.value = false
    readyOpen.value = false
    clearSelection()
    await refreshAll()
  }

  async function onSetPublic() {
    publicUpdating.value = true
    const ids = [...selected.value]
    try {
      const result = await gqlMutation<{
        content: { metadata: { setPublicAll: number } }
      }>(bulkSetPublicGql, {
        metadataIds: ids,
        public: publicPublic.value,
        publicContent: publicContent.value,
        publicSupplementary: publicSupplementary.value,
        searchable: publicSearchable.value,
        recommendable: publicRecommendable.value,
      })
      const updated = result.content.metadata.setPublicAll
      const skipped = ids.length - updated
      if (skipped > 0) {
        toast.warn(`Updated ${updated} ${itemLabel}, ${skipped} skipped`)
      } else {
        toast.success(`Updated ${updated} ${itemLabel}`)
      }
    } catch (e: unknown) {
      toast.error(`Bulk set public failed: ${e instanceof Error ? e.message : 'Unknown error'}`)
    }
    publicUpdating.value = false
    publicOpen.value = false
    clearSelection()
    await refreshAll()
  }

  async function onDelete() {
    deleting.value = true
    const ids = [...selected.value]
    try {
      const result = await gqlMutation<{
        content: { metadata: { deleteAll: number } }
      }>(bulkDeleteGql, { metadataIds: ids })
      const deleted = result.content.metadata.deleteAll
      const skipped = ids.length - deleted
      if (skipped > 0) {
        toast.warn(`Deleted ${deleted} ${itemLabel}, ${skipped} skipped`)
      } else {
        toast.success(`Deleted ${deleted} ${itemLabel}`)
      }
    } catch (e: unknown) {
      toast.error(`Bulk delete failed: ${e instanceof Error ? e.message : 'Unknown error'}`)
    }
    deleting.value = false
    deleteOpen.value = false
    clearSelection()
    await refreshAll()
  }

  async function onProcessMedia() {
    processingMedia.value = true
    const ids = [...selected.value]
    try {
      const result = await gqlMutation<{
        content: { metadata: { processMediaAll: number } }
      }>(bulkProcessMediaGql, { metadataIds: ids })
      const processed = result.content.metadata.processMediaAll
      const skipped = ids.length - processed
      if (skipped > 0) {
        toast.warn(`Submitted ${processed} ${itemLabel} for media processing, ${skipped} skipped`)
      } else {
        toast.success(`Submitted ${processed} ${itemLabel} for media processing`)
      }
    } catch (e: unknown) {
      toast.error(`Bulk media processing failed: ${e instanceof Error ? e.message : 'Unknown error'}`)
    }
    processingMedia.value = false
    processMediaOpen.value = false
    clearSelection()
    await refreshAll()
  }

  async function onDeleteMedia() {
    deletingMedia.value = true
    const ids = [...selected.value]
    try {
      const result = await gqlMutation<{
        content: { metadata: { deleteMediaAll: number } }
      }>(bulkDeleteMediaGql, { metadataIds: ids })
      const removed = result.content.metadata.deleteMediaAll
      const skipped = ids.length - removed
      if (skipped > 0) {
        toast.warn(`Removed media for ${removed} ${itemLabel}, ${skipped} skipped`)
      } else {
        toast.success(`Removed media for ${removed} ${itemLabel}`)
      }
    } catch (e: unknown) {
      toast.error(`Bulk media removal failed: ${e instanceof Error ? e.message : 'Unknown error'}`)
    }
    deletingMedia.value = false
    deleteMediaOpen.value = false
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

    showReady,
    showPublish,
    showUnpublish,
    showProcessMedia,
    showDeleteMedia,

    publishOpen,
    publishing,
    publishPublic,
    publishPublicContent,
    publishPublicSupplementary,
    publishSearchable,
    publishRecommendable,
    onPublish,

    unpublishOpen,
    unpublishing,
    onUnpublish,

    readyOpen,
    readying,
    onSetReady,

    publicOpen,
    publicUpdating,
    publicPublic,
    publicContent,
    publicSupplementary,
    publicSearchable,
    publicRecommendable,
    onSetPublic,

    deleteOpen,
    deleting,
    onDelete,

    processMediaOpen,
    processingMedia,
    onProcessMedia,

    deleteMediaOpen,
    deletingMedia,
    onDeleteMedia,
  }
}
