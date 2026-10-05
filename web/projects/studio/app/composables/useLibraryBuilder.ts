import { computed, onScopeDispose, ref } from 'vue'
import gql from 'graphql-tag'
import {
  BLOCK_DEFAULTS,
  DEFAULT_COLLECTION_LAYOUT,
  UNKNOWN_UI_TYPE,
  RENDER_AS_ITEM,
  blockBindsToContent,
  blockCanDrill,
  blockTypeLabel,
  childRenderAsValue,
  cloneTemplate,
  collectionLayoutFromUi,
  containerDefaultTemplate,
  isContentCollection,
  isVariantCompatible,
  mergeUiKey,
  parseItemTemplate,
  resolveEffectiveTemplate,
  type ItemTemplate,
  type TemplateSource,
} from '~/components/library-builder/library-utils'

export interface LibraryBlockChild {
  id: string
  name: string
  imageUrl: string | null
  // True when this child is itself a Collection (and therefore can be
  // "opened" — drilled into in the editor, or navigated to in the preview).
  // False for Metadata children, which open into a content reader at
  // runtime (out of scope for the library builder's preview).
  isCollection: boolean
  // The child's own block uiType and the parent→child binding override, so the
  // preview can resolve this child's items consistently when navigated into.
  uiType: string
  bindingItemTemplateOverride: ItemTemplate | null
}

export interface LibraryBlock {
  id: string
  // The underlying collection/metadata name as stored in Bosca. Stable for the
  // lifetime of the block.
  itemName: string
  // Display name: `ui.label` override when set, otherwise `itemName`. Recomputed
  // locally whenever `ui.label` changes so canvas/tree update without waiting
  // for a server roundtrip.
  name: string
  typename: string
  uiType: string
  uiConfig: Record<string, unknown>
  isCollection: boolean
  childCount: number
  featuredImageUrl: string | null
  squareImageUrl: string | null
  position: number | null
  // null when not loaded; empty array when loaded with no children. Only set
  // by loadChildrenForContainers on user-initiated navigation, not on background
  // refresh (those skip the N+1 preview fetch).
  children: LibraryBlockChild[] | null
  // The child collection's own item template (lives on the collection itself
  // via attributes.ui.itemTemplate). Null when the child is a Metadata, or
  // when the collection has no template set yet. Used by container previews
  // to know how to render each item, and lifted onto the canvas when the
  // user drills into this block.
  collectionItemTemplate: ItemTemplate | null
  // Per-binding override stored on THIS block's itemAttributes.ui.itemTemplateOverride.
  // When present, takes precedence over collectionItemTemplate.
  bindingItemTemplateOverride: ItemTemplate | null
  // The child collection's FULL `attributes.ui` object (clone), or null for a
  // Metadata child. Retained so a collection-template write can read-modify-
  // write the whole `ui` — the server's shallow JSONB merge replaces `ui`
  // wholesale, so a partial write would wipe every other key.
  collectionAttributesUi: Record<string, unknown> | null
  // The child collection's OWN layout (its `attributes.ui.type`, defaulted to a
  // vertical list). Null for Metadata children. Drives how the child renders
  // its own items when expanded inline as a container, and the default the
  // preview uses when navigating into it.
  collectionLayout: string | null
}

export interface LibraryBreadcrumbItem {
  id: string
  name: string
}

// When the user drills into a container block, we capture the *parent's*
// uiType and the binding block-id so the drilled-down canvas can:
//   1. Show the parent context ("rendering as Grid items, from Main Library")
//   2. Resolve the effective ItemTemplate (override > collection > container default)
//   3. Decide where to persist edits — collection-level vs binding-level override
export interface LibraryDrillContext {
  // The container's uiType (grid/carousel/stack/...). Drives compatibility
  // matrix and container default template.
  parentUiType: string
  // The id of the container block in its parent collection. Used as itemId
  // when persisting binding-level overrides via mergeCollectionItemAttributes
  // (which targets the parent-child relationship).
  parentBindingBlockId: string
  // The collection id that contains the binding row — needed because
  // mergeCollectionItemAttributes targets (parentCollectionId, childItemId).
  parentCollectionId: string
  // The container's uiConfig (columns, aspectRatio, itemWidth, etc.) at the
  // moment of drill. Needed by the mobile preview so the drilled-down view
  // can render a SYNTHETIC parent block in its real layout (a 3-column grid
  // shows 3 columns even though we're inside the grid's collection now).
  // Snapshot only — edits in this view don't change the parent's container
  // config, only its ItemTemplate.
  parentUiConfig: Record<string, unknown>
  // Snapshot of the binding override at drill time. Updated locally as the
  // user edits so the canvas re-renders without a refetch round-trip.
  bindingOverride: ItemTemplate | null
}

// Carried in the tree's navigate event so the page can establish a
// drillContext from outline clicks (not just chevron-drill). Without this,
// users who navigate via the outline never see the ItemTemplate editor.
// `parentIsContainer === false` means the navigation target is a
// non-container block at root level — in that case the page clears
// drillContext rather than constructing a meaningless one.
export interface LibraryTreeNavigateParentInfo {
  parentCollectionId: string
  parentBindingBlockId: string
  parentUiType: string
  parentUiConfig: Record<string, unknown>
  parentIsContainer: boolean
  bindingOverride: ItemTemplate | null
  childCollectionTemplate: ItemTemplate | null
}

export interface ContentPick {
  id: string
  name: string
  type: string
  typename: string
}


const POSITION_STEP = 100
const POSITION_PATH = ['position']

const fetchGql = gql`
  query GetLibraryCollection($id: UUID!) {
    content { collections { collection(id: $id) {
      id name slug itemsCount attributes
      ordering { location order path }
      items(limit: 100, offset: 0) {
        ... on Collection {
          __typename id name slug itemAttributes itemsCount attributes
          metadataRelationships { relationship metadata { id slug } }
        }
        ... on Metadata {
          __typename id name slug itemAttributes
          relationships(filter: ["image.featured", "image.featured.square"]) {
            relationship metadata { id slug }
          }
        }
      }
    } } }
  }
`
const setOrderingGql = gql`
  mutation SetLibraryOrdering($id: UUID!, $ordering: [OrderingInput!]!) {
    content { collection { setCollectionOrdering(id: $id, ordering: $ordering) } }
  }
`
const createPlaceholderGql = gql`
  mutation CreateStructuralBlock($collection: CollectionInput!) {
    content { collection { add(collection: $collection) { id } } }
  }
`
const addChildCollGql = gql`
  mutation AddChildColl($id: UUID!, $child: UUID!, $attrs: JSON!) {
    content { collection { addChildCollection(id: $id, collectionId: $child, attributes: $attrs) { id } } }
  }
`
const addChildMetaGql = gql`
  mutation AddChildMeta($id: UUID!, $child: UUID!, $attrs: JSON!) {
    content { collection { addChildMetadata(id: $id, metadataId: $child, attributes: $attrs) { id } } }
  }
`
const removeChildCollGql = gql`
  mutation RemoveChildColl($parentId: UUID!, $childId: UUID!) {
    content { collection { removeChildCollection(id: $parentId, collectionId: $childId) { id } } }
  }
`
const removeChildMetaGql = gql`
  mutation RemoveChildMeta($parentId: UUID!, $childId: UUID!) {
    content { collection { removeChildMetadata(id: $parentId, metadataId: $childId) { id } } }
  }
`
const mergeCollItemAttrsGql = gql`
  mutation MergeCollAttrs($id: UUID!, $itemId: UUID!, $attrs: JSON!) {
    content { collection { mergeCollectionItemAttributes(id: $id, itemId: $itemId, attributes: $attrs) } }
  }
`
const mergeMetaItemAttrsGql = gql`
  mutation MergeMetaAttrs($id: UUID!, $itemId: UUID!, $attrs: JSON!) {
    content { collection { mergeMetadataItemAttributes(id: $id, itemId: $itemId, attributes: $attrs) } }
  }
`
// Collection-level merge — used to persist the collection's own ItemTemplate
// (lives at attributes.ui.itemTemplate, NOT itemAttributes). itemAttributes
// is per-binding (the parent's view of this child); attributes is the
// collection's intrinsic state.
const mergeCollAttrsGql = gql`
  mutation MergeCollAttributes($id: UUID!, $attrs: JSON!) {
    content { collection { mergeCollectionAttributes(id: $id, attributes: $attrs) } }
  }
`
const deleteCollGql = gql`
  mutation DeleteOrphanedPlaceholder($id: UUID!) {
    content { collection { delete(id: $id) } }
  }
`

interface RawRelationship {
  relationship: string
  metadata?: { id?: string; slug?: string } | null
}

interface RawItem {
  __typename: string
  id: string
  name: string
  slug?: string | null
  itemAttributes?: Record<string, unknown> | null
  // Only present on Collection items — the child collection's own attributes,
  // queried so we can read attributes.ui.itemTemplate without an extra fetch.
  attributes?: Record<string, unknown> | null
  itemsCount?: number
  metadataRelationships?: RawRelationship[]
  relationships?: RawRelationship[]
}

interface CollectionResponse {
  content: {
    collections: {
      collection: {
        id: string
        name: string
        slug: string | null
        itemsCount: number
        // The collection's own attributes — primarily for attributes.ui.itemTemplate
        // when this collection is the *current* drilled-down view.
        attributes?: Record<string, unknown> | null
        ordering: Array<{ location?: string; order?: string; path?: string[] }> | null
        items: RawItem[] | null
      } | null
    }
  }
}

function resolveImg(rels: RawRelationship[] | undefined, rel: string): string | null {
  const slug = rels?.find((r) => r.relationship === rel)?.metadata?.slug
  return slug ? `/content/image/${slug}` : null
}

function deriveName(itemName: string, ui: Record<string, unknown> | undefined): string {
  const label = ui?.label
  if (typeof label === 'string' && label.trim().length > 0) return label
  return itemName
}

function parseBlock(item: RawItem): LibraryBlock {
  const isCol = item.__typename === 'Collection'
  const itemAttributes = item.itemAttributes ?? undefined
  const ui = itemAttributes?.ui as Record<string, unknown> | undefined
  const rels = isCol ? item.metadataRelationships : item.relationships
  const rawPos = itemAttributes?.position
  const position = typeof rawPos === 'number' && Number.isFinite(rawPos) ? rawPos : null
  // Binding-level override lives in the parent's view of this child:
  //   itemAttributes.ui.itemTemplateOverride
  // — i.e. it travels with the parent → child relationship.
  const bindingOverride = parseItemTemplate(ui?.itemTemplateOverride)
  // Collection's own template lives on the child Collection's `attributes`
  // — i.e. it travels with the collection itself, regardless of parent.
  const collectionAttrs = isCol ? (item.attributes ?? undefined) : undefined
  const collectionUi = collectionAttrs?.ui as Record<string, unknown> | undefined
  const collectionTemplate = isCol ? parseItemTemplate(collectionUi?.itemTemplate) : null
  return {
    id: item.id,
    itemName: item.name,
    name: deriveName(item.name, ui),
    typename: item.__typename,
    uiType: (ui?.type as string) ?? UNKNOWN_UI_TYPE,
    uiConfig: ui ? { ...ui } : {},
    isCollection: isCol,
    childCount: isCol ? (item.itemsCount ?? 0) : 0,
    featuredImageUrl: resolveImg(rels, 'image.featured'),
    squareImageUrl: resolveImg(rels, 'image.featured.square'),
    position,
    children: null,
    collectionItemTemplate: collectionTemplate,
    bindingItemTemplateOverride: bindingOverride,
    collectionAttributesUi: isCol ? { ...(collectionUi ?? {}) } : null,
    collectionLayout: isCol ? collectionLayoutFromUi(collectionUi) : null,
  }
}


function hasPositionOrdering(ordering: Array<{ path?: string[] }> | null | undefined): boolean {
  if (!Array.isArray(ordering)) return false
  return ordering.some((o) => {
    const path = o.path
    return Array.isArray(path) && path.length === 1 && path[0] === POSITION_PATH[0]
  })
}

const collectionEventGql = gql`
  subscription LibraryBuilderCollectionChanges {
    collection { id type }
  }
`

export function useLibraryBuilder() {
  const { query: gqlQuery, mutation: gqlMutation, useSubscription } = useGraphQL()
  const toast = useToast()

  // Root library (top-level), separate from currentCollection — when the editor
  // drills into a container block, currentCollection moves but rootId stays put.
  // rootName tracks the library's own display name independently so the tree
  // can label its root regardless of where the user has drilled to.
  const rootId = ref('')
  const rootName = ref('')
  const currentCollectionId = ref('')
  const currentCollectionName = ref('')

  const blocks = ref<LibraryBlock[]>([])
  const selectedBlockId = ref<string | null>(null)
  const breadcrumb = ref<LibraryBreadcrumbItem[]>([])

  // The currently-viewed collection's own ItemTemplate (lives on the
  // collection itself, attributes.ui.itemTemplate). Tracks the drilled-down
  // child so the canvas can render its items using that template. Null when
  // not set or when the current view is the root library (which is not
  // itself rendered as items in a container).
  const currentCollectionTemplate = ref<ItemTemplate | null>(null)
  // The current collection's FULL `attributes.ui` (clone). Retained so a
  // collection-template write read-modify-writes the whole `ui` rather than a
  // partial that the server's shallow merge would use to wipe sibling keys.
  const currentCollectionAttributesUi = ref<Record<string, unknown>>({})
  // Drill context STACK — one entry per drilled level, aligned with the
  // breadcrumb. stack.length == breadcrumb.length when navigation is
  // consistent (every level we drilled INTO has a context for that level's
  // parent). Top of stack = active context for the current view.
  //
  // Using a stack rather than a single ref means breadcrumb-up navigation to
  // an intermediate level recovers THAT level's parent context (the editor
  // stays visible) instead of clearing to null — which is what happened
  // before and broke the editor for any drill-then-breadcrumb-back flow.
  // Entries may be null for levels whose real binding context we couldn't
  // reconstruct (intermediate levels reached via a tree jump). A null entry
  // means "no drill context for this level" — the editor falls back to the
  // standard view and never persists an override onto a guessed relationship.
  const drillContextStack = ref<Array<LibraryDrillContext | null>>([])
  const drillContext = computed<LibraryDrillContext | null>(() =>
    drillContextStack.value.length > 0
      ? drillContextStack.value[drillContextStack.value.length - 1] ?? null
      : null,
  )

  // Two loading states with very different UX contracts:
  //   `loading` covers user-initiated navigation (initial load, drill-down,
  //   breadcrumb click, tree click). The page blanks the canvas — the user
  //   expects a transition.
  //   `refreshing` covers background refetches triggered by subscriptions or
  //   explicit refresh. The canvas stays mounted, selection is preserved, and
  //   the UI shows a subtle indicator at most.
  const loading = ref(true)
  const refreshing = ref(false)

  // Server-side ordering is configured exactly once per library (lazy, on first
  // reorder or add). After that the server returns items in order and we don't
  // need to client-sort. Kept internal — callers don't care.
  const orderingConfigured = ref(false)

  // Per-block debounced save state. Declared early because applyView reads it
  // to decide whether to keep local uiConfig over incoming server state for
  // blocks the user is mid-editing.
  interface PendingSave {
    timer: ReturnType<typeof setTimeout>
    collectionId: string
    config: Record<string, unknown>
    isCollection: boolean
  }
  const pendingSaves = new Map<string, PendingSave>()

  // Ids (collection ids and block ids) with an item-template/override write in
  // flight. A concurrent background refresh must NOT re-parse their template
  // state from the server (which may not yet reflect the write) or it reverts
  // the user's optimistic edit. Mirrors the role `pendingSaves` plays for the
  // block-config debounce, but template writes are immediate (not debounced),
  // so this is a plain in-flight set rather than a timer map.
  const pendingTemplateSaves = new Set<string>()

  // Monotonic counter for in-flight loads. Each loadCollection/refreshCurrentView
  // captures a value and bails its applyView/finally if a newer load has started
  // — otherwise a slow earlier fetch could land after a fast later one and
  // overwrite the current view with stale state.
  let loadSeq = 0

  const selectedBlock = computed(() =>
    blocks.value.find((b) => b.id === selectedBlockId.value) ?? null,
  )

  function nextPosition(): number {
    if (!blocks.value.length) return 0
    const max = blocks.value.reduce(
      (m, b) => (b.position != null && b.position > m ? b.position : m),
      -POSITION_STEP,
    )
    return max + POSITION_STEP
  }

  // Position for a NEW block dropped at the given index.
  //   - Null/undefined or out-of-range → append (nextPosition).
  //   - Between two known positions with room ≥ 2 → midpoint.
  //   - Top-of-list (no before, has after): if afterPos has room above 0
  //     return its half; otherwise return afterPos - POSITION_STEP so the new
  //     block lands strictly before. Negative positions are valid (the schema
  //     stores Int) and sort correctly; drift is bounded in practice by the
  //     fact that reorders renumber to non-negative values.
  //   - Tight middle (gap < 2): fall back to append. The very next reorder
  //     will trigger a renumber that spreads positions out again.
  function positionForInsert(index: number | undefined): number {
    if (index == null || index >= blocks.value.length) return nextPosition()
    const before = index > 0 ? blocks.value[index - 1] : null
    const after = blocks.value[index]
    const beforePos = before?.position ?? null
    const afterPos = after?.position ?? null
    if (beforePos != null && afterPos != null && afterPos - beforePos >= 2) {
      return Math.floor((beforePos + afterPos) / 2)
    }
    if (beforePos == null && afterPos != null) {
      // Inserting at the very top. If there's headroom above 0, take the
      // half — otherwise step below the existing first block. Returning
      // afterPos itself (or 0 on a 0-positioned first block) would create
      // duplicate positions and yield undefined ordering between them.
      if (afterPos >= POSITION_STEP) return Math.floor(afterPos / 2)
      return afterPos - POSITION_STEP
    }
    return nextPosition()
  }

  async function fetchView(id: string): Promise<CollectionResponse['content']['collections']['collection'] | null> {
    const r = await gqlQuery<CollectionResponse>(fetchGql, { id })
    return r?.content?.collections?.collection ?? null
  }

  function applyView(
    col: NonNullable<CollectionResponse['content']['collections']['collection']>,
    opts: { resetSelection: boolean; preserveLocal: boolean },
  ): void {
    const wasOrdered = hasPositionOrdering(col.ordering)
    orderingConfigured.value = wasOrdered
    currentCollectionId.value = col.id
    currentCollectionName.value = col.name
    if (col.id === rootId.value) {
      rootName.value = col.name
    }
    // Pick up the current collection's own template (the one that travels with
    // the collection). Only meaningful when the user is inside a drilled-down
    // view; at root it's parsed but generally unused.
    const rootAttrs = (col.attributes ?? undefined) as Record<string, unknown> | undefined
    const rootUi = rootAttrs?.ui as Record<string, unknown> | undefined
    // Skip clobbering the optimistic template while a save for THIS collection
    // is still in flight — otherwise a concurrent background refresh reverts
    // the user's just-made edit (the server may not have the write yet).
    if (!pendingTemplateSaves.has(col.id)) {
      currentCollectionTemplate.value = parseItemTemplate(rootUi?.itemTemplate)
      currentCollectionAttributesUi.value = { ...(rootUi ?? {}) }
    }

    // Snapshot prior blocks for merge passes. On refresh we carry forward
    // already-fetched container children (no N+1 refetch) and we keep local
    // `uiConfig` for any block the user is mid-editing (a pending save will
    // flush the user's intent shortly).
    const prevById = opts.preserveLocal
      ? new Map(blocks.value.map((b) => [b.id, b]))
      : null

    const parsed = (col.items ?? []).map((rawItem) => {
      const block = parseBlock(rawItem)
      const prev = prevById?.get(block.id)
      if (prev) {
        if (prev.children !== null) block.children = prev.children
        const savingConfig = pendingSaves.has(block.id)
        const savingTemplate = pendingTemplateSaves.has(block.id)
        if (savingConfig || savingTemplate) {
          // The user has an unsaved edit to this block — don't stomp it with
          // server state; the in-flight write will reconcile shortly.
          block.uiConfig = { ...prev.uiConfig }
          block.uiType = prev.uiType
          block.name = prev.name
        }
        if (savingTemplate) {
          block.collectionItemTemplate = prev.collectionItemTemplate
          block.collectionAttributesUi = prev.collectionAttributesUi
          block.bindingItemTemplateOverride = prev.bindingItemTemplateOverride
        }
      }
      return block
    })

    if (!wasOrdered) {
      // Pre-migration libraries: items arrive in server-default order. Sort by
      // position so items that have one settle into their slot; the rest keep
      // server order via stable sort.
      parsed.sort((a, b) => {
        const ap = a.position ?? Number.MAX_SAFE_INTEGER
        const bp = b.position ?? Number.MAX_SAFE_INTEGER
        return ap - bp
      })
    }
    blocks.value = parsed

    if (opts.resetSelection) {
      selectedBlockId.value = null
    } else if (selectedBlockId.value && !parsed.some((b) => b.id === selectedBlockId.value)) {
      // Selection survived if it still exists; otherwise drop it.
      selectedBlockId.value = null
    }
  }

  // User-initiated load: full reset. Used for first load, drill-down, breadcrumb,
  // tree navigation. The page blanks the canvas while this runs.
  //
  // Tri-state result so callers roll back breadcrumb/stack ONLY on a genuine,
  // still-current failure ('error'). A 'superseded' result means a newer
  // navigation has already started and now owns breadcrumb/stack — rolling
  // back there would clobber the newer (correct) state with this stale one.
  async function loadCollection(id: string): Promise<'ok' | 'error' | 'superseded'> {
    const seq = ++loadSeq
    loading.value = true
    selectedBlockId.value = null
    try {
      const col = await fetchView(id)
      if (seq !== loadSeq) return 'superseded'  // a newer load owns the view now
      if (!col) throw new Error('Collection not found')
      applyView(col, { resetSelection: true, preserveLocal: false })
      // Fire-and-forget preview children fetch, tagged with our seq so it
      // bails if the user navigates again before it completes.
      void loadChildrenForContainers(seq)
      return 'ok'
    } catch (e: unknown) {
      if (seq !== loadSeq) return 'superseded'
      toast.error(e instanceof Error ? e.message : 'Failed to load')
      return 'error'
    } finally {
      if (seq === loadSeq) loading.value = false
    }
  }

  // Background refresh: subscription-driven or explicit. Preserves selection
  // AND any in-flight local edits (via `preserveLocal: true` and pendingSaves
  // gating). Skips the N+1 preview fetch — last-known children carry forward
  // via the prevById merge in applyView.
  async function refreshCurrentView(): Promise<void> {
    const id = currentCollectionId.value
    if (!id) return
    const seq = ++loadSeq
    refreshing.value = true
    try {
      const col = await fetchView(id)
      if (seq !== loadSeq) return
      if (!col) return
      applyView(col, { resetSelection: false, preserveLocal: true })
    } catch (e: unknown) {
      console.warn('[library-builder] background refresh failed', e)
    } finally {
      if (seq === loadSeq) refreshing.value = false
    }
  }

  // Preview shows real child thumbnails for container blocks. Each per-container
  // fetch races against potential navigation/refresh — we look up the live block
  // by id at completion time and only write if it still exists in `blocks.value`.
  // The seq parameter additionally aborts the whole pass if the user has moved
  // on entirely.
  async function loadChildrenForContainers(seq: number): Promise<void> {
    const containerIds = blocks.value
      .filter((b) => b.isCollection && b.childCount > 0)
      .map((b) => b.id)
    if (containerIds.length === 0) return
    let anyUpdated = false
    await Promise.all(containerIds.map(async (containerId) => {
      try {
        const col = await fetchView(containerId)
        if (seq !== loadSeq) return
        if (!col?.items) return
        const live = blocks.value.find((b) => b.id === containerId)
        if (!live) return
        live.children = col.items.map((child) => {
          const isCol = child.__typename === 'Collection'
          const rels = isCol ? child.metadataRelationships : child.relationships
          // Use the binding's display label (ui.label) when set, mirroring how
          // parseBlock derives a block's name — otherwise the preview shows the
          // raw content name (e.g. "niv.zip") at root while the drilled view
          // shows the label (e.g. "Test 1"), an inconsistency.
          const childUi = (child.itemAttributes?.ui ?? undefined) as Record<string, unknown> | undefined
          return {
            id: child.id,
            name: deriveName(child.name, childUi),
            imageUrl: resolveImg(rels, 'image.featured') ?? resolveImg(rels, 'image.featured.square'),
            isCollection: isCol,
            uiType: (childUi?.type as string) ?? UNKNOWN_UI_TYPE,
            bindingItemTemplateOverride: parseItemTemplate(childUi?.itemTemplateOverride),
          }
        })
        live.childCount = live.children.length
        anyUpdated = true
      } catch (e: unknown) {
        console.warn('[library-builder] preview children fetch failed for', containerId, e)
      }
    }))
    if (seq !== loadSeq) return
    if (anyUpdated) blocks.value = [...blocks.value]
  }

  // Lazy fetch for the tree component. No internal cache — the tree owns its
  // own per-instance expansion state. Returns parsed blocks for the given
  // collection so the tree can render them directly.
  async function fetchChildren(id: string): Promise<LibraryBlock[]> {
    try {
      const col = await fetchView(id)
      if (!col) return []
      return (col.items ?? []).map(parseBlock)
    } catch (e: unknown) {
      console.warn('[library-builder] fetchChildren failed for', id, e)
      return []
    }
  }

  // Like fetchChildren, but also returns the collection's OWN layout
  // (attributes.ui.type, defaulted to a vertical list) and item template
  // (attributes.ui.itemTemplate) in the same round-trip. The preview uses these
  // when the user navigates INTO a collection: a content collection (one whose
  // children are items, not blocks) renders its items through ITS OWN layout +
  // template — the same way the editor shows it — not as a stack of "unknown"
  // blocks and not under the parent binding's type.
  async function fetchCollectionPreview(id: string): Promise<{ blocks: LibraryBlock[]; layout: string; itemTemplate: ItemTemplate | null }> {
    try {
      const col = await fetchView(id)
      if (!col) return { blocks: [], layout: DEFAULT_COLLECTION_LAYOUT, itemTemplate: null }
      const attrs = (col.attributes ?? undefined) as Record<string, unknown> | undefined
      const attrsUi = attrs?.ui as Record<string, unknown> | undefined
      return {
        blocks: (col.items ?? []).map(parseBlock),
        layout: collectionLayoutFromUi(attrsUi),
        itemTemplate: parseItemTemplate(attrsUi?.itemTemplate),
      }
    } catch (e: unknown) {
      console.warn('[library-builder] fetchCollectionPreview failed for', id, e)
      return { blocks: [], layout: DEFAULT_COLLECTION_LAYOUT, itemTemplate: null }
    }
  }

  async function load(id: string): Promise<void> {
    rootId.value = id
    breadcrumb.value = []
    drillContextStack.value = []
    await loadCollection(id)
  }

  async function ensureOrdering(): Promise<void> {
    if (orderingConfigured.value) return
    const parentId = currentCollectionId.value
    const ordered = blocks.value.map((b, i) => ({
      ...b,
      position: b.position ?? i * POSITION_STEP,
    }))
    const toWrite = ordered.filter((b, i) => b.position !== blocks.value[i]?.position)
    await Promise.all(toWrite.map((block) => {
      const mutation = block.isCollection ? mergeCollItemAttrsGql : mergeMetaItemAttrsGql
      return gqlMutation(mutation, {
        id: parentId,
        itemId: block.id,
        attrs: { position: block.position },
      })
    }))
    await gqlMutation(setOrderingGql, {
      id: parentId,
      ordering: [{ location: 'RELATIONSHIP', path: POSITION_PATH, order: 'ASCENDING', type: 'INT' }],
    })
    blocks.value = ordered
    orderingConfigured.value = true
  }

  function isAlreadyLinked(id: string): boolean {
    return blocks.value.some((b) => b.id === id)
  }

  async function addBoundBlock(
    uiType: string,
    picked: ContentPick,
    insertAt?: number,
  ): Promise<void> {
    if (isAlreadyLinked(picked.id)) {
      toast.error(`"${picked.name}" is already in this collection`)
      return
    }
    const defaults = BLOCK_DEFAULTS[uiType] ?? { type: uiType }
    try {
      await ensureOrdering()
      const attrs = { ui: { ...defaults }, position: positionForInsert(insertAt) }
      const mutation = picked.typename === 'Collection' ? addChildCollGql : addChildMetaGql
      await gqlMutation(mutation, { id: currentCollectionId.value, child: picked.id, attrs })
      toast.success(`Added "${picked.name}" as ${blockTypeLabel(uiType)}`)
      const result = await loadCollection(currentCollectionId.value)
      // Auto-select the just-added block so the editor focuses immediately
      // on the user's new work. If the load failed, leave selection cleared.
      if (result === 'ok') selectedBlockId.value = picked.id
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to add item')
    }
  }

  // Find a unique display name for a new structural placeholder. If there's
  // already a "Banner" in the view, the new one becomes "Banner 2" rather
  // than rendering two indistinguishable "Banner" rows in the canvas/tree.
  // Dedups against display name (`block.name`, which is `ui.label || itemName`)
  // because that's what the user actually sees — a legacy placeholder whose
  // raw itemName differs but whose label happens to be "Banner" would still
  // visually collide.
  function uniquePlaceholderName(baseLabel: string): string {
    const existingNames = new Set(blocks.value.map((b) => b.name))
    if (!existingNames.has(baseLabel)) return baseLabel
    for (let i = 2; i < 1000; i++) {
      const candidate = `${baseLabel} ${i}`
      if (!existingNames.has(candidate)) return candidate
    }
    return baseLabel
  }

  async function addStructuralBlock(uiType: string, insertAt?: number): Promise<void> {
    const defaults = BLOCK_DEFAULTS[uiType] ?? { type: uiType }
    const label = blockTypeLabel(uiType)
    const placeholderName = uniquePlaceholderName(label)
    try {
      await ensureOrdering()
      // Mint a placeholder collection to carry this block's itemAttributes.
      // Deliberately untagged with `content-library` so it doesn't appear in
      // the libraries index — it only exists as a child of this library.
      const created = await gqlMutation<{ content: { collection: { add: { id: string } } } }>(
        createPlaceholderGql,
        { collection: { name: placeholderName, collectionType: 'STANDARD' } },
      )
      const childId = created.content.collection.add.id
      const attrs = { ui: { ...defaults }, position: positionForInsert(insertAt) }
      try {
        await gqlMutation(addChildCollGql, { id: currentCollectionId.value, child: childId, attrs })
      } catch (linkError: unknown) {
        // The placeholder collection was created but couldn't be attached as a
        // child. Best-effort cleanup so we don't leak an orphan into the
        // collections graph; if cleanup itself fails, we surface the *link*
        // error to the user (that's the one they were trying to perform).
        try {
          await gqlMutation(deleteCollGql, { id: childId })
        } catch (cleanupError: unknown) {
          console.warn('[library-builder] orphan placeholder cleanup failed for', childId, cleanupError)
        }
        throw linkError
      }
      toast.success(`Added ${label}`)
      const result = await loadCollection(currentCollectionId.value)
      if (result === 'ok') selectedBlockId.value = childId
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : `Failed to add ${label}`)
    }
  }

  function addFromPalette(uiType: string, insertAt?: number): { needsContent: boolean; uiType: string; insertAt?: number } {
    if (blockBindsToContent(uiType)) return { needsContent: true, uiType, insertAt }
    void addStructuralBlock(uiType, insertAt)
    return { needsContent: false, uiType, insertAt }
  }

  // Cancel any pending debounced save for the given block id. Used before
  // mutations that remove the block server-side, so the timer doesn't fire and
  // try to save attrs onto a relationship that no longer exists.
  function cancelPendingSave(blockId: string): void {
    const pending = pendingSaves.get(blockId)
    if (!pending) return
    clearTimeout(pending.timer)
    pendingSaves.delete(blockId)
  }

  async function removeBlock(blockId: string): Promise<void> {
    const block = blocks.value.find((b) => b.id === blockId)
    if (!block) return
    cancelPendingSave(blockId)
    // Structural blocks (banner, status-row, section-header) own a
    // placeholder Collection that was minted just to carry their ui config.
    // Unlinking it from the parent leaves an orphan with no way back; delete
    // it so it doesn't accumulate untagged collections in the database.
    // Content-bound blocks (featured, grid, etc.) reference user-authored
    // content that exists independently — we only unlink there.
    const isPlaceholder = block.isCollection && !blockBindsToContent(block.uiType)
    try {
      const mutation = block.isCollection ? removeChildCollGql : removeChildMetaGql
      await gqlMutation(mutation, { parentId: currentCollectionId.value, childId: blockId })
      blocks.value = blocks.value.filter((b) => b.id !== blockId)
      if (selectedBlockId.value === blockId) selectedBlockId.value = null
      if (isPlaceholder) {
        try {
          await gqlMutation(deleteCollGql, { id: blockId })
        } catch (cleanupError: unknown) {
          console.warn('[library-builder] placeholder cleanup failed for', blockId, cleanupError)
        }
      }
      toast.success('Removed')
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to remove')
    }
  }

  // Sparse reorder: only the moved block changes position, slotted to the
  // midpoint between its new neighbors. The full renumber-everyone path runs
  // only when neighbors collide (no room left to subdivide).
  async function reorderBlocks(fromIdx: number, toIdx: number): Promise<void> {
    if (fromIdx === toIdx) return
    const previous = blocks.value
    const updated = [...previous]
    const [moved] = updated.splice(fromIdx, 1)
    if (!moved) return
    updated.splice(toIdx, 0, moved)
    const libraryId = currentCollectionId.value

    const prev = toIdx > 0 ? updated[toIdx - 1] : null
    const next = toIdx < updated.length - 1 ? updated[toIdx + 1] : null
    const prevPos = prev?.position ?? null
    const nextPos = next?.position ?? null

    const tooTight = prevPos != null && nextPos != null && nextPos - prevPos < 2
    const missingNeighbor = (prev != null && prevPos == null) || (next != null && nextPos == null)
    const needsRenumber = !orderingConfigured.value || tooTight || missingNeighbor

    blocks.value = updated  // optimistic
    try {
      if (needsRenumber) {
        const renumbered = updated.map((block, i) => ({ ...block, position: i * POSITION_STEP }))
        blocks.value = renumbered
        await Promise.all(renumbered.map((block) => {
          const mutation = block.isCollection ? mergeCollItemAttrsGql : mergeMetaItemAttrsGql
          return gqlMutation(mutation, {
            id: libraryId,
            itemId: block.id,
            attrs: { position: block.position },
          })
        }))
        if (!orderingConfigured.value) {
          await gqlMutation(setOrderingGql, {
            id: libraryId,
            ordering: [{ location: 'RELATIONSHIP', path: POSITION_PATH, order: 'ASCENDING', type: 'INT' }],
          })
          orderingConfigured.value = true
        }
      } else {
        // Compute a single midpoint and write only the moved block.
        let newPos: number
        if (prevPos != null && nextPos != null) newPos = Math.floor((prevPos + nextPos) / 2)
        else if (prevPos != null) newPos = prevPos + POSITION_STEP
        else if (nextPos != null) newPos = nextPos - POSITION_STEP
        else newPos = 0
        const movedWithPos = { ...moved, position: newPos }
        updated[toIdx] = movedWithPos
        blocks.value = [...updated]
        const mutation = moved.isCollection ? mergeCollItemAttrsGql : mergeMetaItemAttrsGql
        await gqlMutation(mutation, {
          id: libraryId,
          itemId: moved.id,
          attrs: { position: newPos },
        })
      }
    } catch (e: unknown) {
      blocks.value = previous
      toast.error(e instanceof Error ? e.message : 'Failed to save order')
    }
  }

  async function swapBlockContent(blockId: string, picked: ContentPick): Promise<void> {
    const oldBlock = blocks.value.find((b) => b.id === blockId)
    if (!oldBlock) return
    if (oldBlock.id === picked.id) {
      toast.success('No change')
      return
    }
    if (isAlreadyLinked(picked.id)) {
      toast.error(`"${picked.name}" is already in this collection`)
      return
    }
    // The old block is about to be removed server-side; cancel any pending
    // debounced save so it doesn't fire against the dead relationship.
    cancelPendingSave(oldBlock.id)
    const preservedAttrs = {
      ui: { ...oldBlock.uiConfig },
      position: oldBlock.position ?? nextPosition(),
    }
    try {
      // Add-then-remove: a remove failure leaves a recoverable duplicate rather
      // than an empty slot.
      const addMutation = picked.typename === 'Collection' ? addChildCollGql : addChildMetaGql
      await gqlMutation(addMutation, {
        id: currentCollectionId.value,
        child: picked.id,
        attrs: preservedAttrs,
      })
      const removeMutation = oldBlock.isCollection ? removeChildCollGql : removeChildMetaGql
      try {
        await gqlMutation(removeMutation, {
          parentId: currentCollectionId.value,
          childId: oldBlock.id,
        })
        toast.success(`Linked to "${picked.name}"`)
      } catch (removeError: unknown) {
        // Add succeeded, remove failed — the user now has two blocks at the
        // same position. Tell them so they don't think the swap silently
        // worked while having a duplicate hanging around.
        console.warn('[library-builder] swap removed old failed', removeError)
        toast.error(`Linked new content, but failed to remove old. Delete the duplicate manually.`)
      }
      selectedBlockId.value = null
      await loadCollection(currentCollectionId.value)
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to change content')
      await loadCollection(currentCollectionId.value)
    }
  }

  // Per-block debounce: edits to block A must not clobber a pending save for
  // block B. The snapshot captures the config at schedule time so even if the
  // block object is replaced by a refresh before the timer fires, we save the
  // user's actual edit, not whatever state happens to be in `blocks` later.
  // (The `pendingSaves` map itself is declared up top so `applyView` can read
  // it during refresh merges.)
  function scheduleConfigSave(block: LibraryBlock): void {
    const existing = pendingSaves.get(block.id)
    if (existing) clearTimeout(existing.timer)
    const collectionId = currentCollectionId.value
    const config = { ...block.uiConfig }
    const isCollection = block.isCollection
    const timer = setTimeout(async () => {
      pendingSaves.delete(block.id)
      try {
        const mutation = isCollection ? mergeCollItemAttrsGql : mergeMetaItemAttrsGql
        await gqlMutation(mutation, {
          id: collectionId,
          itemId: block.id,
          attrs: { ui: config },
        })
      } catch (e: unknown) {
        toast.error(e instanceof Error ? e.message : 'Failed to save config')
      }
    }, 400)
    pendingSaves.set(block.id, { timer, collectionId, config, isCollection })
  }

  function updateBlockConfig(blockId: string, partial: Record<string, unknown>): void {
    const block = blocks.value.find((b) => b.id === blockId)
    if (!block) return
    block.uiConfig = { ...block.uiConfig, ...partial }
    block.uiType = (block.uiConfig.type as string) ?? block.uiType
    // Display name is derived from `ui.label`; keep it in sync synchronously so
    // the canvas/tree update with every keystroke, not only after a refresh.
    block.name = deriveName(block.itemName, block.uiConfig)
    blocks.value = [...blocks.value]
    scheduleConfigSave(block)
  }

  // Replace the entire `ui` config wholesale. Used when the editor changes
  // block type — old type-specific fields must be wiped, not merged, or the client's
  // parser sees stale keys (e.g. a `height` left over from a Featured block
  // sitting on a Grid).
  function replaceBlockConfig(blockId: string, newConfig: Record<string, unknown>): void {
    const block = blocks.value.find((b) => b.id === blockId)
    if (!block) return
    // Preserve the binding-level template override across a type change —
    // it's the user's intent for THIS binding regardless of which container
    // type the block becomes. The container ↔ variant matrix may then flag
    // it as incompatible, prompting an override-or-pick-new-variant choice
    // in the editor.
    const preservedOverride = block.uiConfig.itemTemplateOverride
    block.uiConfig = preservedOverride != null
      ? { ...newConfig, itemTemplateOverride: preservedOverride }
      : { ...newConfig }
    block.uiType = (newConfig.type as string) ?? block.uiType
    block.name = deriveName(block.itemName, block.uiConfig)
    blocks.value = [...blocks.value]
    scheduleConfigSave(block)
  }

  // ── ItemTemplate persistence ─────────────────────────────────────────────
  // Two persistence surfaces, kept distinct on purpose:
  //   - Collection template: stored on the *currently viewed* collection's
  //     `attributes.ui.itemTemplate`. Affects every parent that renders this
  //     collection unless they have a binding override.
  //   - Binding override: stored on the parent → child relationship's
  //     `itemAttributes.ui.itemTemplateOverride`. Only affects this one
  //     binding-site.
  // Both go through optimistic local update + async server write.

  // The effective template for the drilled-down view. When the user is
  // outside a drill context this is null — the canvas falls back to the
  // bare list and the template editor isn't shown.
  const effectiveTemplate = computed<{ template: ItemTemplate; source: TemplateSource } | null>(() => {
    const ctx = drillContext.value
    if (!ctx) return null
    return resolveEffectiveTemplate(
      ctx.parentUiType,
      currentCollectionTemplate.value,
      ctx.bindingOverride,
    )
  })

  // ── Content-collection view ────────────────────────────────────────────────
  // The current collection's OWN layout (how it arranges its items), read from
  // its `attributes.ui.type` and defaulted to a vertical list. Drives the
  // content-view canvas/preview and the Layout picker.
  const currentCollectionLayout = computed<string>(() =>
    collectionLayoutFromUi(currentCollectionAttributesUi.value),
  )

  // True when the current view is a content collection (a list of content items
  // rendered through one layout + per-child overrides) rather than a designed
  // page of blocks. Scoped to NON-root views so the top-level library page
  // keeps its block-building experience untouched. Empty views fall back to the
  // page experience (the palette empty-state) since we can't tell which they are.
  const isContentView = computed<boolean>(() =>
    currentCollectionId.value !== rootId.value &&
    blocks.value.length > 0 &&
    isContentCollection(blocks.value),
  )

  // The collection's item presentation for the content view: how each plain
  // item renders, resolved from the collection's own template under its chosen
  // layout (no parent binding here — that's the page owner's concern). Null when
  // not a content view so the canvas/editor fall back to their page behaviour.
  const currentViewTemplate = computed<{ template: ItemTemplate; source: TemplateSource } | null>(() => {
    if (!isContentView.value) return null
    return resolveEffectiveTemplate(
      currentCollectionLayout.value,
      currentCollectionTemplate.value,
      null,
    )
  })

  // Write the template to the current collection's `attributes.ui.itemTemplate`.
  // Local state is updated synchronously so the canvas re-renders immediately.
  // The write sends the FULL `ui` (read-modify-write) because the server's
  // shallow JSONB merge replaces `ui` wholesale — a partial write would wipe
  // every other key on the collection's ui. `pendingTemplateSaves` blocks a
  // concurrent refresh from reverting the optimistic value.
  async function updateCollectionItemTemplate(template: ItemTemplate | null): Promise<void> {
    const collectionId = currentCollectionId.value
    if (!collectionId) return
    const priorTemplate = currentCollectionTemplate.value
    const priorUi = currentCollectionAttributesUi.value
    const nextUi = mergeUiKey(priorUi, 'itemTemplate', template)
    currentCollectionTemplate.value = template ? cloneTemplate(template) : null
    currentCollectionAttributesUi.value = nextUi
    pendingTemplateSaves.add(collectionId)
    try {
      await gqlMutation(mergeCollAttrsGql, { id: collectionId, attrs: { ui: nextUi } })
    } catch (e: unknown) {
      currentCollectionTemplate.value = priorTemplate
      currentCollectionAttributesUi.value = priorUi
      toast.error(e instanceof Error ? e.message : 'Failed to save item template')
    } finally {
      pendingTemplateSaves.delete(collectionId)
    }
  }

  // Change the current collection's own layout (`attributes.ui.type`). Written
  // with the same full-`ui` read-modify-write as the item template, since the
  // server's shallow merge replaces `ui` wholesale. When the existing item
  // template's variant doesn't fit the new layout, snap the template to the
  // layout's default variant so we never persist an incompatible pairing; an
  // already-compatible template (the user's tweaks) is preserved.
  async function updateCollectionLayout(layout: string): Promise<void> {
    const collectionId = currentCollectionId.value
    if (!collectionId) return
    const priorUi = currentCollectionAttributesUi.value
    const priorTemplate = currentCollectionTemplate.value
    const nextTemplate = priorTemplate
      ? (isVariantCompatible(layout, priorTemplate.variant) ? priorTemplate : containerDefaultTemplate(layout))
      : null
    let nextUi = mergeUiKey(priorUi, 'type', layout)
    nextUi = mergeUiKey(nextUi, 'itemTemplate', nextTemplate)
    currentCollectionAttributesUi.value = nextUi
    currentCollectionTemplate.value = nextTemplate ? cloneTemplate(nextTemplate) : null
    pendingTemplateSaves.add(collectionId)
    try {
      await gqlMutation(mergeCollAttrsGql, { id: collectionId, attrs: { ui: nextUi } })
    } catch (e: unknown) {
      currentCollectionAttributesUi.value = priorUi
      currentCollectionTemplate.value = priorTemplate
      toast.error(e instanceof Error ? e.message : 'Failed to save layout')
    } finally {
      pendingTemplateSaves.delete(collectionId)
    }
  }

  // Set a single child's "Render as" — how it renders inside the current content
  // collection. RENDER_AS_ITEM clears the binding's container type (the child
  // renders as a tile/row via the collection's presentation); a container type
  // expands the child's own contents inline as that layout. Either way the
  // child's per-item template override and Common fields (label/subtitle/hidden)
  // survive. Persists through the standard debounced binding-`ui` save.
  function setChildRenderAs(blockId: string, renderAs: string): void {
    const block = blocks.value.find((b) => b.id === blockId)
    if (!block) return
    if (childRenderAsValue(block.uiType) === renderAs) return
    const preserved: Record<string, unknown> = {}
    for (const key of ['label', 'subtitle', 'hidden']) {
      const value = block.uiConfig[key]
      if (value !== undefined) preserved[key] = value
    }
    const override = block.uiConfig.itemTemplateOverride
    if (renderAs === RENDER_AS_ITEM) {
      block.uiConfig = override != null ? { ...preserved, itemTemplateOverride: override } : { ...preserved }
      block.uiType = UNKNOWN_UI_TYPE
    } else {
      const defaults = BLOCK_DEFAULTS[renderAs] ?? { type: renderAs }
      block.uiConfig = override != null
        ? { ...defaults, ...preserved, itemTemplateOverride: override }
        : { ...defaults, ...preserved }
      block.uiType = renderAs
    }
    block.name = deriveName(block.itemName, block.uiConfig)
    blocks.value = [...blocks.value]
    scheduleConfigSave(block)
  }

  // ── Selected-child template save (root view, container block selected) ──
  // When the editor is at root with a container block selected, the user
  // edits "how this container's items render" without drilling. The save
  // targets the SELECTED block's identity, not the drillContext / current
  // view. Two flavours match the drilled-view pair:
  //   - …CollectionTemplate writes the block's OWN collection attributes
  //     (block.id IS that collection's id)
  //   - …BindingOverride writes the parent's child-relationship attributes
  //     (parent = current view's collection; child = the block)
  // Optimistic state mirrors are on the block itself (collectionItemTemplate
  // / bindingItemTemplateOverride) so the canvas + preview re-render before
  // the server roundtrip lands.
  async function updateBlockChildCollectionTemplate(blockId: string, template: ItemTemplate | null): Promise<void> {
    const block = blocks.value.find(b => b.id === blockId)
    if (!block) return
    const priorTemplate = block.collectionItemTemplate
    const priorUi = block.collectionAttributesUi
    const nextUi = mergeUiKey(priorUi, 'itemTemplate', template)
    block.collectionItemTemplate = template ? cloneTemplate(template) : null
    block.collectionAttributesUi = nextUi
    blocks.value = [...blocks.value]
    pendingTemplateSaves.add(blockId)
    try {
      // Full-ui write (read-modify-write) — see updateCollectionItemTemplate.
      await gqlMutation(mergeCollAttrsGql, { id: blockId, attrs: { ui: nextUi } })
    } catch (e: unknown) {
      const live = blocks.value.find(b => b.id === blockId)
      if (live) {
        live.collectionItemTemplate = priorTemplate
        live.collectionAttributesUi = priorUi
        blocks.value = [...blocks.value]
      }
      toast.error(e instanceof Error ? e.message : 'Failed to save item template')
    } finally {
      pendingTemplateSaves.delete(blockId)
    }
  }

  async function updateBlockChildBindingOverride(blockId: string, template: ItemTemplate | null): Promise<void> {
    const block = blocks.value.find(b => b.id === blockId)
    if (!block) return
    const parentCollectionId = currentCollectionId.value
    if (!parentCollectionId) return
    // A pending debounced config save for this block holds a `ui` snapshot
    // WITHOUT this override; if it fired it would clobber the override (C2).
    // Cancel it — block.uiConfig already carries the latest config edits
    // (applied synchronously by updateBlockConfig), and our write below sends
    // the full, current ui.
    cancelPendingSave(blockId)
    const priorOverride = block.bindingItemTemplateOverride
    const priorUi = block.uiConfig
    const nextUi = mergeUiKey(priorUi, 'itemTemplateOverride', template)
    block.bindingItemTemplateOverride = template ? cloneTemplate(template) : null
    block.uiConfig = nextUi
    blocks.value = [...blocks.value]
    pendingTemplateSaves.add(blockId)
    try {
      // The binding lives on a collection-child OR a metadata-child relationship;
      // each has its own attribute-merge mutation. A metadata item (e.g. a single
      // article overridden inside a content collection) must use the metadata
      // mutation, or the write targets a relationship that doesn't exist.
      const mutation = block.isCollection ? mergeCollItemAttrsGql : mergeMetaItemAttrsGql
      await gqlMutation(mutation, { id: parentCollectionId, itemId: blockId, attrs: { ui: nextUi } })
    } catch (e: unknown) {
      const live = blocks.value.find(b => b.id === blockId)
      if (live) {
        live.bindingItemTemplateOverride = priorOverride
        live.uiConfig = priorUi
        blocks.value = [...blocks.value]
      }
      toast.error(e instanceof Error ? e.message : 'Failed to save binding override')
    } finally {
      pendingTemplateSaves.delete(blockId)
    }
  }

  // Write the override to the parent's child-relationship attributes. Targets
  // the specific binding (parentCollectionId, parentBindingBlockId); other
  // bindings of the same collection are unaffected.
  //
  // Reactivity contract: drillContextStack is a `ref<Array>`, but objects
  // INSIDE the array are not deeply reactive — mutating `ctx.bindingOverride`
  // directly does not fire the ref, so `effectiveTemplate` (a computed that
  // reads through `drillContext`) never re-evaluates and the preview goes
  // stale. To trigger reactivity we replace the stack entry with a new
  // object via array reassignment.
  async function updateBindingItemTemplateOverride(template: ItemTemplate | null): Promise<void> {
    const stack = drillContextStack.value
    if (stack.length === 0) return
    const idx = stack.length - 1
    const ctx = stack[idx]
    // ctx may be null for an intermediate level reached via the tree (its real
    // binding context is unknown). Refuse to write — we must never persist an
    // override onto a guessed relationship.
    if (!ctx) return
    cancelPendingSave(ctx.parentBindingBlockId)
    const priorCtx = ctx
    // The binding's full `ui` is the parent block's uiConfig snapshot taken at
    // drill time (parentUiConfig). Read-modify-write it so the override write
    // doesn't wipe type/columns/label on the binding (C1).
    const nextUi = mergeUiKey(ctx.parentUiConfig, 'itemTemplateOverride', template)
    const nextOverride = template ? cloneTemplate(template) : null
    // Reassign the stack entry — objects inside a ref array aren't deeply
    // reactive, so `effectiveTemplate` (read through drillContext) only
    // re-evaluates on array reassignment. Keep parentUiConfig in sync so a
    // second override edit in this session merges on top of the first.
    drillContextStack.value = [
      ...stack.slice(0, idx),
      { ...ctx, parentUiConfig: nextUi, bindingOverride: nextOverride },
      ...stack.slice(idx + 1),
    ]
    // Mirror to the matching block in the parent view (if still loaded) so
    // navigate-up reflects it without a refetch. Reassign blocks for reactivity.
    const parentBlock = blocks.value.find((b) => b.id === ctx.parentBindingBlockId)
    if (parentBlock) {
      parentBlock.bindingItemTemplateOverride = nextOverride ? cloneTemplate(nextOverride) : null
      parentBlock.uiConfig = { ...nextUi }
      blocks.value = [...blocks.value]
    }
    pendingTemplateSaves.add(ctx.parentBindingBlockId)
    try {
      await gqlMutation(mergeCollItemAttrsGql, {
        id: ctx.parentCollectionId,
        itemId: ctx.parentBindingBlockId,
        attrs: { ui: nextUi },
      })
    } catch (e: unknown) {
      // Roll back the stack entry and the parent-block mirror to the snapshot.
      const live = drillContextStack.value
      if (idx < live.length && live[idx]) {
        drillContextStack.value = [
          ...live.slice(0, idx),
          priorCtx,
          ...live.slice(idx + 1),
        ]
      }
      const pb = blocks.value.find((b) => b.id === ctx.parentBindingBlockId)
      if (pb) {
        pb.bindingItemTemplateOverride = priorCtx.bindingOverride ? cloneTemplate(priorCtx.bindingOverride) : null
        pb.uiConfig = { ...priorCtx.parentUiConfig }
        blocks.value = [...blocks.value]
      }
      toast.error(e instanceof Error ? e.message : 'Failed to save binding override')
    } finally {
      pendingTemplateSaves.delete(ctx.parentBindingBlockId)
    }
  }

  async function drillDown(blockId: string): Promise<void> {
    const block = blocks.value.find((b) => b.id === blockId)
    // Only drillable blocks (container types bound to a Collection) can be
    // entered. `blockCanDrill` is the single source of truth shared with the
    // canvas and editor — if a UI affordance shows, this must permit it.
    if (!block || !blockCanDrill(block.uiType, block.isCollection)) return
    const prev = breadcrumb.value
    const prevStack = drillContextStack.value
    breadcrumb.value = [
      ...prev,
      { id: currentCollectionId.value, name: currentCollectionName.value },
    ]
    // Push the new drill level onto the stack. Each entry describes the
    // parent of ONE drilled level — the top of the stack is the parent of
    // the current view; the level below is its grandparent; etc.
    drillContextStack.value = [
      ...prevStack,
      {
        parentUiType: block.uiType,
        parentBindingBlockId: block.id,
        parentCollectionId: currentCollectionId.value,
        parentUiConfig: { ...block.uiConfig },
        bindingOverride: block.bindingItemTemplateOverride
          ? cloneTemplate(block.bindingItemTemplateOverride)
          : null,
      },
    ]
    const result = await loadCollection(block.id)
    // Roll back ONLY on a genuine, still-current failure — a 'superseded'
    // result means a newer navigation already owns breadcrumb/stack.
    if (result === 'error') {
      breadcrumb.value = prev
      drillContextStack.value = prevStack
    }
  }

  async function navigateUp(index: number): Promise<void> {
    const prev = breadcrumb.value
    const prevStack = drillContextStack.value
    if (index < 0) {
      // Navigating to root means depth 0 — no drill context at all.
      breadcrumb.value = []
      drillContextStack.value = []
      const result = await loadCollection(rootId.value)
      if (result === 'error') {
        breadcrumb.value = prev
        drillContextStack.value = prevStack
      }
      return
    }
    const target = breadcrumb.value[index]
    if (!target) return
    breadcrumb.value = breadcrumb.value.slice(0, index)
    // After landing at `target`, the new view's depth is `index`. We need
    // `index` drill-context entries (one for each level we're still inside).
    // Slicing the stack to that length preserves the editor for the
    // intermediate level instead of dropping it to null.
    drillContextStack.value = prevStack.slice(0, index)
    const result = await loadCollection(target.id)
    if (result === 'error') {
      breadcrumb.value = prev
      drillContextStack.value = prevStack
    }
  }

  // Jump to an arbitrary collection. Caller supplies the full path (root → ...
  // → target's parent) — for the tree, this is the recursion chain at the
  // clicked node. parentInfo carries the binding identity of the target's
  // immediate parent so a tree-driven navigation gives the editor full
  // context. The drill-stack is rebuilt to match the new depth:
  //   - root click (path=[]) → empty stack
  //   - tree click into a container's child → push one entry built from parentInfo
  // We can't reconstruct intermediate stack entries (they would require
  // fetching every ancestor) — for now jumping to deep targets via the tree
  // gives the top-level (active) drill context but leaves intermediate
  // levels unfilled. The editor only reads the top, so this is correct for
  // the current view; breadcrumb up-navigation from there will surface
  // null at intermediates until the user re-drills.
  async function navigateTo(
    targetId: string,
    path: LibraryBreadcrumbItem[],
    parentInfo?: LibraryTreeNavigateParentInfo,
  ): Promise<void> {
    if (targetId === currentCollectionId.value) return
    const prev = breadcrumb.value
    const prevStack = drillContextStack.value
    breadcrumb.value = path
    if (parentInfo && parentInfo.parentIsContainer && path.length > 0) {
      // We know the binding context for ONLY the active (top) level — it's the
      // target's immediate parent, supplied by the tree node. The intermediate
      // ancestors' contexts are genuinely unknown (reconstructing them would
      // require fetching every ancestor's binding), so they're left null.
      // Earlier this padded every level with the SAME `top` reference, which
      // made breadcrumb-up promote a wrong-level context — and any binding
      // override edited there persisted onto the WRONG relationship. Null is
      // correct: an unknown intermediate simply shows the standard view until
      // the user re-drills, and never writes to a guessed binding.
      const top: LibraryDrillContext = {
        parentUiType: parentInfo.parentUiType,
        parentBindingBlockId: parentInfo.parentBindingBlockId,
        parentCollectionId: parentInfo.parentCollectionId,
        parentUiConfig: { ...parentInfo.parentUiConfig },
        bindingOverride: parentInfo.bindingOverride
          ? cloneTemplate(parentInfo.bindingOverride) : null,
      }
      drillContextStack.value = [
        ...Array<LibraryDrillContext | null>(path.length - 1).fill(null),
        top,
      ]
    } else {
      drillContextStack.value = []
    }
    const result = await loadCollection(targetId)
    if (result === 'error') {
      breadcrumb.value = prev
      drillContextStack.value = prevStack
    }
  }

  // Make `parentId` the current view and select `selectId` within it. Used by
  // the outline: clicking any node opens that block in the editor, which
  // requires its parent collection to be the active view first (the editor
  // reads from `blocks.value`). `navigateTo` no-ops when `parentId` is already
  // current (selection preserved) and otherwise loads it (clearing selection);
  // either way we re-assert the selection once the view is in place. If the
  // navigation failed, the target block won't be present and we leave the
  // selection untouched rather than pointing at a block that isn't loaded.
  async function navigateToAndSelect(
    parentId: string,
    path: LibraryBreadcrumbItem[],
    parentInfo: LibraryTreeNavigateParentInfo | undefined,
    selectId: string,
  ): Promise<void> {
    await navigateTo(parentId, path, parentInfo)
    if (blocks.value.some((b) => b.id === selectId)) {
      selectedBlockId.value = selectId
    }
  }

  function selectBlock(blockId: string | null): void {
    selectedBlockId.value = blockId
  }

  function selectByIndex(idx: number | null): void {
    if (idx === null) {
      selectedBlockId.value = null
      return
    }
    selectedBlockId.value = blocks.value[idx]?.id ?? null
  }

  // Subscribe inline (rather than via the shared `useCollectionSubscription`)
  // because we need to handle every event, not just the ones matching the
  // current view. The tree owns its own cache of sibling subtrees and needs
  // a signal when any of them go stale; we expose `changedId` for that.
  const changedId = ref<string | null>(null)
  const changedTick = ref(0)
  let subscriptionRefreshTimer: ReturnType<typeof setTimeout> | null = null

  useSubscription(collectionEventGql, {}, (event: { collection?: { id?: string } }) => {
    const id = event?.collection?.id
    if (!id) return
    // Surface every event for the tree to invalidate its own cache. The tick
    // bumps on every event so watchers fire even when the same id changes
    // twice in a row.
    changedId.value = id
    changedTick.value++
    // Debounced refresh of the current view when the event matches.
    if (id !== currentCollectionId.value) return
    if (subscriptionRefreshTimer) clearTimeout(subscriptionRefreshTimer)
    subscriptionRefreshTimer = setTimeout(() => {
      subscriptionRefreshTimer = null
      void refreshCurrentView()
    }, 500)
  })

  // Clean up debounce timers on scope teardown so a navigation-away doesn't
  // leak a setTimeout that resurrects the dead composable graph.
  onScopeDispose(() => {
    for (const { timer } of pendingSaves.values()) clearTimeout(timer)
    pendingSaves.clear()
    if (subscriptionRefreshTimer) clearTimeout(subscriptionRefreshTimer)
  })

  return {
    // state
    rootId,
    rootName,
    currentCollectionId,
    currentCollectionName,
    blocks,
    selectedBlockId,
    selectedBlock,
    breadcrumb,
    loading,
    refreshing,
    changedId,
    changedTick,
    drillContext,
    currentCollectionTemplate,
    effectiveTemplate,
    isContentView,
    currentCollectionLayout,
    currentViewTemplate,

    // actions
    load,
    updateCollectionLayout,
    setChildRenderAs,
    addFromPalette,
    addBoundBlock,
    removeBlock,
    reorderBlocks,
    swapBlockContent,
    updateBlockConfig,
    replaceBlockConfig,
    drillDown,
    navigateUp,
    navigateTo,
    fetchChildren,
    fetchCollectionPreview,
    navigateToAndSelect,
    selectBlock,
    selectByIndex,
    refreshCurrentView,
    updateCollectionItemTemplate,
    updateBindingItemTemplateOverride,
    updateBlockChildCollectionTemplate,
    updateBlockChildBindingOverride,
  }
}
