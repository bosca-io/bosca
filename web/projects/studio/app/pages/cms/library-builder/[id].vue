<script setup lang="ts">
import { computed, onMounted, onScopeDispose, reactive, ref, watch } from 'vue'
import { RENDER_AS_ITEM, blockBindsToContent, blockCanDrill, blockTypeLabel, buildSyntheticParentPreviewBlock, childRenderAsValue, isContainerType, isContentCollection, previewContainerType, resolveBlockEffectiveTemplate, resolveEffectiveTemplate } from '~/components/library-builder/library-utils'
import type { ItemTemplate, PreviewChild } from '~/components/library-builder/library-utils'
import type { ContentPick, LibraryBlock, LibraryBlockChild, LibraryBreadcrumbItem, LibraryTreeNavigateParentInfo } from '~/composables/useLibraryBuilder'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()

const lib = useLibraryBuilder()

// ── UI-only state ──
const showPreview = ref(true)
const showTree = ref(false)
const previewDevice = ref<'mobile' | 'tablet' | 'desktop'>('mobile')

const showContentPicker = ref(false)
const pendingUiType = ref<string | null>(null)
const pendingChangeId = ref<string | null>(null)
const removeTarget = ref<{ id: string; name: string } | null>(null)

// The canvas needs an index (Sortable.js is index-based). The composable
// stores selection as an id; derive the index locally.
const selectedIndex = computed<number | null>(() => {
  const id = lib.selectedBlockId.value
  if (!id) return null
  const idx = lib.blocks.value.findIndex((b) => b.id === id)
  return idx < 0 ? null : idx
})

const selectedBlockForEditor = computed(() => {
  const block = lib.selectedBlock.value
  if (!block) return null
  return { ...block, bindsToContent: blockBindsToContent(block.uiType) }
})

// The preview mirrors the EDITOR's current view, with optional tap-navigation
// layered on top. Three modes, in priority order:
//
// 1. Tap navigation active (previewNavStack non-empty) — the user tapped a
//    tile inside the preview to explore a sub-collection. Show that level.
// 2. Editor drilled into a container — show the PARENT'S container layout
//    populated with the current view's items (synthetic parent block), so the
//    preview shows e.g. a 3-column grid, not bare rows.
// 3. Editor at a normal view — render each block independently, resolving each
//    block's effective ItemTemplate so right-panel edits appear immediately.
const previewBlocks = computed<PreviewLevelBlock[]>(() => {
  const navTop = previewNavStack.value[previewNavStack.value.length - 1]
  if (navTop) {
    return navTop.blocks
  }
  // Content collection: render its items under the collection's layout, with
  // any container-overridden child expanded inline. Takes precedence over a
  // drill context, exactly like the canvas/editor. Grandchildren for expanded
  // children are already loaded (loadChildrenForContainers), so the resolver is
  // synchronous here.
  const viewTemplate = lib.currentViewTemplate.value
  if (lib.isContentView.value && viewTemplate) {
    return buildContentPreviewBlocks(
      lib.blocks.value,
      previewContainerType(lib.currentCollectionLayout.value, viewTemplate.template.variant),
      viewTemplate.template,
      lib.currentCollectionId.value,
      (b) => (b.children ?? []).map(childToPreviewChild),
    )
  }
  const ctx = lib.drillContext.value
  const effective = lib.effectiveTemplate.value
  if (ctx && effective) {
    return [buildSyntheticParentPreviewBlock({
      parentBindingBlockId: ctx.parentBindingBlockId,
      parentUiType: ctx.parentUiType,
      parentUiConfig: ctx.parentUiConfig,
      collectionName: lib.currentCollectionName.value,
      children: lib.blocks.value.map(blockToPreviewChild),
      effectiveTemplate: effective.template,
    })]
  }
  return lib.blocks.value.map(toPreviewBlock)
})

// Build the preview blocks for a content collection: a run of consecutive plain
// items collapses into ONE block under `runContainerUiType` (rendered through
// `runTemplate`); each child overridden to a container becomes its own expanded
// section in place, preserving order. `childrenOf` resolves an expanded child's
// grandchildren — synchronously from already-loaded projections (editor mirror)
// or from a pre-fetched map (preview tap-navigation). Shared by both so the
// grouping rule lives in exactly one place.
function buildContentPreviewBlocks(
  blocks: LibraryBlock[],
  runContainerUiType: string,
  runTemplate: ItemTemplate,
  idPrefix: string,
  childrenOf: (b: LibraryBlock) => PreviewChild[],
): PreviewLevelBlock[] {
  const out: PreviewLevelBlock[] = []
  let run: LibraryBlock[] = []
  const flush = () => {
    if (run.length === 0) return
    out.push({
      id: `${idPrefix}::items::${out.length}`,
      name: '',
      uiType: runContainerUiType,
      uiConfig: {},
      featuredImageUrl: null,
      children: run.map(blockToPreviewChild),
      itemTemplate: runTemplate,
    })
    run = []
  }
  for (const b of blocks) {
    if (childRenderAsValue(b.uiType) === RENDER_AS_ITEM) {
      run.push(b)
      continue
    }
    flush()
    const { template } = resolveBlockEffectiveTemplate(b)
    out.push({
      id: b.id,
      name: b.name,
      uiType: b.uiType,
      uiConfig: b.uiConfig,
      featuredImageUrl: b.featuredImageUrl,
      children: childrenOf(b),
      itemTemplate: template,
    })
  }
  flush()
  return out
}

// ── Palette ──
// `insertAt` is the target index when the click originated from a drop on a
// specific canvas slot; undefined for plain clicks (which append).
const pendingInsertAt = ref<number | undefined>(undefined)

function onPaletteClick(type: string, insertAt?: number) {
  const result = lib.addFromPalette(type, insertAt)
  if (result.needsContent) {
    pendingUiType.value = result.uiType
    pendingChangeId.value = null
    pendingInsertAt.value = insertAt
    showContentPicker.value = true
  }
}

function onChangeContentRequest(blockId: string) {
  const block = lib.blocks.value.find((b) => b.id === blockId)
  if (!block) return
  pendingChangeId.value = blockId
  pendingUiType.value = block.uiType
  showContentPicker.value = true
}

async function onContentSelected(item: ContentPick) {
  showContentPicker.value = false
  const changeId = pendingChangeId.value
  pendingChangeId.value = null
  const uiType = pendingUiType.value ?? 'featured'
  pendingUiType.value = null
  const insertAt = pendingInsertAt.value
  pendingInsertAt.value = undefined

  if (changeId !== null) {
    await lib.swapBlockContent(changeId, item)
  } else {
    await lib.addBoundBlock(uiType, item, insertAt)
  }
}

function closeContentPicker() {
  showContentPicker.value = false
  pendingUiType.value = null
  pendingChangeId.value = null
  pendingInsertAt.value = undefined
}

const pickerTitle = computed(() => {
  if (pendingChangeId.value !== null) return 'Change Content'
  return `Add ${pendingUiType.value ? blockTypeLabel(pendingUiType.value) : 'Block'}`
})

// Container types render their children, so they MUST be linked to a
// Collection — Metadata has no children. Featured can be either kind (it
// just renders the linked item as a single tile). The picker enforces this
// at the point of selection rather than letting the user create a broken
// block they'd then have to delete.
const pickerFilter = computed<'collection' | 'metadata' | 'both'>(() => {
  const type = pendingUiType.value
  if (type && isContainerType(type)) return 'collection'
  return 'both'
})

// ── Canvas bridges: canvas thinks in indices, composable thinks in ids ──
function onCanvasSelect(idx: number) {
  lib.selectByIndex(idx)
}

function onCanvasRemove(idx: number) {
  const block = lib.blocks.value[idx]
  if (!block) return
  removeTarget.value = { id: block.id, name: block.name }
}

async function confirmRemoveBlock() {
  const target = removeTarget.value
  removeTarget.value = null
  if (!target) return
  await lib.removeBlock(target.id)
}

function onCanvasDrillDown(idx: number) {
  const block = lib.blocks.value[idx]
  if (!block) return
  void lib.drillDown(block.id)
}

// ── ItemTemplate persistence ──
// Two contexts converge on the same UI emit:
//   - DRILLED view: the editor edits the CURRENT view's template; saves
//     target currentCollection / drillContext top.
//   - ROOT view with a container selected: the editor edits the SELECTED
//     block's child-template; saves target the block's identity.
// The editor doesn't know which it is — the page picks the function pair
// based on lib.drillContext.value. Same emit surface, contextual routing.
function onUpdateItemTemplate(template: ItemTemplate, scope: 'collection' | 'binding') {
  // Content view: the only `update-item-template` source is the "Customize this
  // item" editor (binding scope) — it overrides the SELECTED item, regardless of
  // how we reached this collection. The collection-wide presentation uses its
  // own emit (`update-collection-template`), never this one.
  if (lib.isContentView.value) {
    const block = lib.selectedBlock.value
    if (block && scope === 'binding') void lib.updateBlockChildBindingOverride(block.id, template)
    return
  }
  if (lib.drillContext.value) {
    if (scope === 'binding') void lib.updateBindingItemTemplateOverride(template)
    else void lib.updateCollectionItemTemplate(template)
  } else {
    const block = lib.selectedBlock.value
    if (!block) return
    if (scope === 'binding') void lib.updateBlockChildBindingOverride(block.id, template)
    else void lib.updateBlockChildCollectionTemplate(block.id, template)
  }
}

function onClearBindingOverride() {
  if (lib.isContentView.value) {
    const block = lib.selectedBlock.value
    if (block) void lib.updateBlockChildBindingOverride(block.id, null)
    return
  }
  if (lib.drillContext.value) {
    void lib.updateBindingItemTemplateOverride(null)
  } else {
    const block = lib.selectedBlock.value
    if (!block) return
    void lib.updateBlockChildBindingOverride(block.id, null)
  }
}

function onLiftBindingToCollection(template: ItemTemplate) {
  // "Lift" means: write what's currently per-binding onto the collection,
  // then drop the binding override so the collection template takes over
  // as the effective template. Both writes are independent server calls;
  // running them in parallel keeps the round-trip short.
  if (lib.drillContext.value) {
    void Promise.all([
      lib.updateCollectionItemTemplate(template),
      lib.updateBindingItemTemplateOverride(null),
    ])
  } else {
    const block = lib.selectedBlock.value
    if (!block) return
    void Promise.all([
      lib.updateBlockChildCollectionTemplate(block.id, template),
      lib.updateBlockChildBindingOverride(block.id, null),
    ])
  }
}

const hasBindingOverride = computed(() => lib.drillContext.value?.bindingOverride != null)

// ── Item Presentation when a container block is selected at root ──
// Mirror of the drilled-view template state, but resolved from the SELECTED
// block (not the drillContext). Lets the user toggle Action / change Variant
// for a container's children without first drilling into it.
const childEffectiveTemplate = computed(() => {
  if (lib.drillContext.value != null) return null  // drilled view supplies its own
  if (lib.isContentView.value) return null          // content view supplies its own
  const block = lib.selectedBlock.value
  if (!block) return null
  if (!blockCanDrill(block.uiType, block.isCollection)) return null
  return resolveBlockEffectiveTemplate(block)
})
const childHasBindingOverride = computed(() => {
  if (lib.drillContext.value != null) return false
  if (lib.isContentView.value) return false
  return lib.selectedBlock.value?.bindingItemTemplateOverride != null
})

// ── Content view: selected item's per-item presentation override ──
// The "Customize this item" editor starts from the item's effective look (its
// own override if any, else the collection's presentation) so the user tweaks
// what they currently see. Only for items rendered AS items — a child overridden
// to a container is edited by opening it. Writes go to the binding override.
const contentItemTemplate = computed(() => {
  if (!lib.isContentView.value) return null
  const block = lib.selectedBlock.value
  if (!block) return null
  if (childRenderAsValue(block.uiType) !== RENDER_AS_ITEM) return null
  const base = lib.currentViewTemplate.value?.template ?? null
  return resolveEffectiveTemplate(
    lib.currentCollectionLayout.value,
    base,
    block.bindingItemTemplateOverride,
  )
})
const contentItemHasOverride = computed(() =>
  lib.isContentView.value && lib.selectedBlock.value?.bindingItemTemplateOverride != null,
)

// ── Preview navigation ──
// The floating preview behaves like a mini-app simulator. Clicking an item
// that points to a Collection opens that collection inside the preview frame
// (fetching its blocks lazily). Back retreats one level. An empty
// previewNavStack means "mirror the editor's current view"; once the stack has
// entries, the preview's current level is the top entry's fetched blocks.
const toast = useToast()

interface PreviewLevelBlock {
  id: string
  name: string
  uiType: string
  uiConfig: Record<string, unknown>
  featuredImageUrl: string | null
  children: PreviewChild[]
  itemTemplate: ItemTemplate
}

interface PreviewNavLevel {
  id: string
  name: string
  blocks: PreviewLevelBlock[]
}

const previewNavStack = ref<PreviewNavLevel[]>([])
const previewLoading = ref(false)

// The preview's tap-navigation is relative to the editor's current view. When
// the editor navigates (drill, breadcrumb, tree, or load), any in-preview
// navigation history is stale — its Back stack was built against a view that
// no longer exists. Reset it so the preview re-mirrors the editor.
watch(() => lib.currentCollectionId.value, () => {
  if (previewNavStack.value.length > 0) {
    previewFetchSeq++  // drop any in-flight tap-nav fetch
    previewNavStack.value = []
    previewLoading.value = false
  }
})

// Track in-flight fetches so a slow earlier click can't overwrite a fast
// later one. Monotonic id; the result of an older fetch is dropped if a
// newer one started before it landed.
let previewFetchSeq = 0

// Project a loaded block / child into a PreviewChild, carrying the binding
// context (uiType + override) so the preview can navigate INTO it and resolve
// its items the same way the editor does.
function blockToPreviewChild(b: LibraryBlock): PreviewChild {
  return {
    id: b.id,
    name: b.name,
    imageUrl: b.featuredImageUrl ?? b.squareImageUrl,
    isCollection: b.isCollection,
    uiType: b.uiType,
    bindingOverride: b.bindingItemTemplateOverride,
  }
}

function childToPreviewChild(c: LibraryBlockChild): PreviewChild {
  return {
    id: c.id,
    name: c.name,
    imageUrl: c.imageUrl,
    isCollection: c.isCollection,
    uiType: c.uiType,
    bindingOverride: c.bindingItemTemplateOverride,
  }
}

// Project a top-level block (root / library-page view) into a renderable
// preview block, resolving its effective item template. Shared by the editor
// mirror (previewBlocks) and the preview tap-navigation level builder.
function toPreviewBlock(b: LibraryBlock): PreviewLevelBlock {
  const { template } = resolveBlockEffectiveTemplate(b)
  return {
    id: b.id,
    name: b.name,
    uiType: b.uiType,
    uiConfig: b.uiConfig,
    featuredImageUrl: b.featuredImageUrl,
    children: (b.children ?? []).map(childToPreviewChild),
    itemTemplate: template,
  }
}

// Fetch a complete preview level for collection `id`: its own blocks AND each
// container block's direct children (so containers render with their items, not
// "No items" placeholders). A shallow fetch — the preview shows ONE level at a
// time; clicking into a child drills another level. A content collection renders
// its items under ITS OWN layout + presentation (attributes.ui), exactly like
// the editor shows it — NOT under the parent binding's type, which only
// described how the collection looked as a tile in its parent.
async function fetchPreviewLevel(
  id: string,
  name: string,
): Promise<PreviewNavLevel> {
  const { blocks: topBlocks, layout, itemTemplate: collTemplate } = await lib.fetchCollectionPreview(id)

  // A "content collection" — children are content items rather than a page of
  // blocks — renders as its items through ITS layout + presentation, with any
  // child overridden to a container expanded inline (its grandchildren fetched).
  // A run of plain items collapses into one block; container children break the
  // run, preserving order.
  if (isContentCollection(topBlocks)) {
    const { template } = resolveEffectiveTemplate(layout, collTemplate, null)
    const runContainer = previewContainerType(layout, template.variant)
    // Pre-fetch grandchildren for every container-overridden child in parallel
    // (independent fetches — a sequential await-per-child would stack their
    // latencies). The grouping itself is then synchronous via the shared helper.
    const expanded = new Map<string, PreviewChild[]>()
    await Promise.all(
      topBlocks
        .filter((b) => childRenderAsValue(b.uiType) !== RENDER_AS_ITEM && b.isCollection && b.childCount > 0)
        .map(async (b) => {
          try {
            expanded.set(b.id, (await lib.fetchChildren(b.id)).map(blockToPreviewChild))
          } catch (err: unknown) {
            console.warn('[library-builder] preview grandchildren fetch failed for', b.id, err)
          }
        }),
    )
    const blocks = buildContentPreviewBlocks(
      topBlocks,
      runContainer,
      template,
      id,
      (b) => expanded.get(b.id) ?? [],
    )
    return { id, name, blocks }
  }

  // Library page: resolve each block; for containers, fetch grandchildren so
  // they render with their items, not "No items" placeholders. Build each
  // PreviewLevelBlock directly rather than mutating LibraryBlock.children.
  const blocksOut = await Promise.all(topBlocks.map(async (b): Promise<PreviewLevelBlock> => {
    let children: PreviewChild[] = []
    if (b.isCollection && isContainerType(b.uiType) && b.childCount > 0) {
      try {
        children = (await lib.fetchChildren(b.id)).map(blockToPreviewChild)
      } catch (err: unknown) {
        // A failed grandchild fetch shouldn't kill the whole level — the
        // container just shows "No items."
        console.warn('[library-builder] preview grandchildren fetch failed for', b.id, err)
      }
    }
    const { template } = resolveBlockEffectiveTemplate(b)
    return {
      id: b.id,
      name: b.name,
      uiType: b.uiType,
      uiConfig: b.uiConfig,
      featuredImageUrl: b.featuredImageUrl,
      children,
      itemTemplate: template,
    }
  }))
  return { id, name, blocks: blocksOut }
}

async function onPreviewItemClick(info: {
  itemId: string | null
  itemName: string
  isCollection: boolean
  template: ItemTemplate
  // The tapped child's own block uiType and the parent→child binding override,
  // so navigating into it resolves its items consistently with the editor.
  ownUiType?: string
  bindingOverride?: ItemTemplate | null
}) {
  // Leaf items (Metadata) can't be opened in the preview — that's a runtime
  // content reader, not a library page. Describe the click via toast.
  if (!info.isCollection) {
    const action = info.template.action
    const label = action.label && action.label.trim().length > 0
      ? action.label
      : (action.kind === 'play' ? 'Play' : 'Open')
    const verb = action.kind === 'play' ? 'Play' : 'Open content'
    toast.info(`${label} → ${verb}: "${info.itemName}"`)
    return
  }
  if (!info.itemId) return
  const seq = ++previewFetchSeq
  previewLoading.value = true
  try {
    const level = await fetchPreviewLevel(info.itemId, info.itemName)
    if (seq !== previewFetchSeq) return  // a newer click landed; drop this result
    previewNavStack.value = [...previewNavStack.value, level]
  } catch (e: unknown) {
    if (seq !== previewFetchSeq) return
    toast.error(e instanceof Error ? e.message : 'Failed to open collection')
  } finally {
    if (seq === previewFetchSeq) previewLoading.value = false
  }
}

function onPreviewBack() {
  if (previewNavStack.value.length === 0) return
  // Bump the fetch seq so any in-flight click result is dropped — otherwise
  // a slow click + immediate back would land the result AFTER the back.
  previewFetchSeq++
  previewLoading.value = false
  previewNavStack.value = previewNavStack.value.slice(0, -1)
}

// Top-level collection name shown in the preview header. When the user has
// navigated into a sub-collection from the preview, show that name. Falls back
// to the editor's current view name when at the preview root.
const previewTitle = computed(() => {
  const top = previewNavStack.value[previewNavStack.value.length - 1]
  return top?.name ?? lib.currentCollectionName.value
})

// ── Tree ──
async function onTreeFetchChildren(id: string): Promise<LibraryBlock[]> {
  return lib.fetchChildren(id)
}

// Root-only: the outline's root row resets the canvas to the top level.
function onTreeNavigate(id: string, path: LibraryBreadcrumbItem[]) {
  void lib.navigateTo(id, path)
}

// Clicking any non-root outline node opens it in the editor: bring its parent
// collection into view, then select the node.
function onTreeSelect(
  selectId: string,
  parentId: string,
  path: LibraryBreadcrumbItem[],
  parentInfo?: LibraryTreeNavigateParentInfo,
) {
  void lib.navigateToAndSelect(parentId, path, parentInfo, selectId)
}

// ── Keyboard ──
// The picker modal teleports its DOM to <body>, so its keydown events don't
// bubble through the PageShell's listener. We attach a document-level listener
// while the picker is open so Escape closes it from anywhere, including from
// inside the search input.
function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    if (showContentPicker.value) { closeContentPicker(); return }
    lib.selectBlock(null)
  }
}

function handleDocumentKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && showContentPicker.value) {
    closeContentPicker()
  }
}

if (typeof window !== 'undefined') {
  watch(showContentPicker, (open) => {
    if (open) {
      document.addEventListener('keydown', handleDocumentKeydown)
    } else {
      document.removeEventListener('keydown', handleDocumentKeydown)
    }
  })
  onScopeDispose(() => {
    document.removeEventListener('keydown', handleDocumentKeydown)
  })
}

// ── Floating preview window — purely visual state. The position isn't known
//    until we measure window dimensions in onMounted; `previewReady` gates
//    the render so we don't flash the window at (0, 0) during hydration.
const previewPos = reactive({ x: 0, y: 0, width: 420, height: 680 })
const previewReady = ref(false)
const dragging = ref(false)
const resizing = ref(false)
let dragStartX = 0, dragStartY = 0

onMounted(() => {
  const id = route.params.id as string
  if (id) void lib.load(id)
  if (typeof window !== 'undefined') {
    previewPos.x = Math.max(0, window.innerWidth - previewPos.width - 24)
    previewPos.y = 80
    clampPreviewPos()
    previewReady.value = true
  }
})

// Keep the preview window's title bar reachable so the user can always drag
// it back. Dragging past the viewport edge would otherwise strand the window
// off-screen with no way to retrieve it short of a page reload.
const TITLEBAR_RESERVE_PX = 80
function clampPreviewPos() {
  if (typeof window === 'undefined') return
  const maxX = Math.max(0, window.innerWidth - TITLEBAR_RESERVE_PX)
  const maxY = Math.max(0, window.innerHeight - TITLEBAR_RESERVE_PX)
  previewPos.x = Math.min(Math.max(0, previewPos.x), maxX)
  previewPos.y = Math.min(Math.max(0, previewPos.y), maxY)
}

function onDragStart(e: MouseEvent) {
  dragging.value = true
  dragStartX = e.clientX - previewPos.x
  dragStartY = e.clientY - previewPos.y
  window.addEventListener('mousemove', onDrag)
  window.addEventListener('mouseup', onDragEnd)
}
function onDrag(e: MouseEvent) {
  previewPos.x = e.clientX - dragStartX
  previewPos.y = e.clientY - dragStartY
  clampPreviewPos()
}
function onDragEnd() { dragging.value = false; window.removeEventListener('mousemove', onDrag); window.removeEventListener('mouseup', onDragEnd) }
function onResizeStart(e: MouseEvent) { e.preventDefault(); resizing.value = true; window.addEventListener('mousemove', onResize); window.addEventListener('mouseup', onResizeEnd) }
function onResize(e: MouseEvent) { previewPos.width = Math.max(300, e.clientX - previewPos.x); previewPos.height = Math.max(400, e.clientY - previewPos.y) }
function onResizeEnd() { resizing.value = false; window.removeEventListener('mousemove', onResize); window.removeEventListener('mouseup', onResizeEnd) }

// Window-resize listener — if the viewport shrinks, an offscreen preview
// window slides back into view rather than disappearing.
if (typeof window !== 'undefined') {
  const onResizeWindow = () => clampPreviewPos()
  window.addEventListener('resize', onResizeWindow)
  onScopeDispose(() => window.removeEventListener('resize', onResizeWindow))
}

// The empty/loading state is shown only when we have nothing to render. Once
// blocks are present, the layout stays mounted and any refetch goes through
// the subtle "refreshing" indicator — selection, scroll, drag state survive.
const showEmptyLoading = computed(() => lib.loading.value && lib.blocks.value.length === 0)
</script>

<template>
  <PageShell tabindex="-1" style="outline: none;" @keydown="handleKeydown">
    <template #header>
      <PageHeader :title="lib.rootName.value || 'Library Builder'" :accent="accent">
        <template #before-title>
          <button class="back-btn" @click="router.push('/cms/library-builder')">
            <Icon name="chevronLeft" :size="16" color="var(--fg-2)" />
          </button>
        </template>
        <template #actions>
          <span
            v-if="lib.refreshing.value"
            class="refresh-indicator"
            title="Refreshing…"
          >
            <Icon
              name="loader"
              :size="14"
              color="var(--fg-3)"
              class="spin"
            />
          </span>
          <Button
            icon="library"
            size="sm"
            :accent="accent"
            @click="showTree = !showTree"
          >
            {{ showTree ? 'Hide Outline' : 'Outline' }}
          </Button>
          <Button
            icon="eye"
            size="sm"
            :accent="accent"
            @click="showPreview = !showPreview"
          >
            {{ showPreview ? 'Hide Preview' : 'Preview' }}
          </Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="showEmptyLoading" class="loading-state">Loading…</div>

    <div v-else class="builder-layout">
      <div class="builder-palette">
        <LibraryBlockPalette @add-block="onPaletteClick" />
      </div>

      <div v-if="showTree" class="builder-tree">
        <LibraryTree
          :root-id="lib.rootId.value"
          :root-name="lib.rootName.value"
          :current-id="lib.currentCollectionId.value"
          :current-blocks="lib.blocks.value"
          :fetch-children="onTreeFetchChildren"
          :changed-id="lib.changedId.value"
          :changed-tick="lib.changedTick.value"
          :accent="accent"
          @navigate="onTreeNavigate"
          @select="onTreeSelect"
        />
      </div>

      <div class="builder-canvas">
        <LibraryCanvas
          :blocks="lib.blocks.value"
          :selected-idx="selectedIndex"
          :breadcrumb="lib.breadcrumb.value"
          :library-name="lib.currentCollectionName.value"
          :accent="accent"
          :drill-context="lib.drillContext.value"
          :effective-template="lib.effectiveTemplate.value"
          :content-view="lib.isContentView.value"
          :content-template="lib.currentViewTemplate.value"
          @select="onCanvasSelect"
          @remove="onCanvasRemove"
          @reorder="lib.reorderBlocks"
          @add-block="onPaletteClick"
          @drill-down="onCanvasDrillDown"
          @navigate-up="lib.navigateUp"
        />
      </div>

      <div class="builder-editor">
        <LibraryBlockEditor
          :block="selectedBlockForEditor"
          :accent="accent"
          :drill-context="lib.drillContext.value"
          :effective-template="lib.effectiveTemplate.value"
          :has-binding-override="hasBindingOverride"
          :child-effective-template="childEffectiveTemplate"
          :child-has-binding-override="childHasBindingOverride"
          :content-view="lib.isContentView.value"
          :collection-layout="lib.currentCollectionLayout.value"
          :content-template="lib.currentViewTemplate.value"
          :item-template="contentItemTemplate"
          :item-has-override="contentItemHasOverride"
          @update-config="(blockId, partial) => lib.updateBlockConfig(blockId, partial)"
          @replace-config="(blockId, full) => lib.replaceBlockConfig(blockId, full)"
          @drill-down="(blockId) => lib.drillDown(blockId)"
          @change-content="onChangeContentRequest"
          @update-item-template="onUpdateItemTemplate"
          @clear-binding-override="onClearBindingOverride"
          @lift-binding-to-collection="onLiftBindingToCollection"
          @update-collection-layout="(layout) => lib.updateCollectionLayout(layout)"
          @update-collection-template="(template) => lib.updateCollectionItemTemplate(template)"
          @set-render-as="(blockId, renderAs) => lib.setChildRenderAs(blockId, renderAs)"
        />
      </div>
    </div>

    <LibraryContentPicker
      v-if="showContentPicker"
      :title="pickerTitle"
      :accent="accent"
      :filter="pickerFilter"
      @select="onContentSelected"
      @close="closeContentPicker"
    />

    <!-- Floating Preview Window -->
    <ClientOnly>
      <Teleport to="body">
        <div
          v-if="showPreview && !showEmptyLoading && previewReady"
          class="preview-window"
          :class="{ 'preview-window--active': dragging || resizing }"
          :style="{ left: previewPos.x+'px', top: previewPos.y+'px', width: previewPos.width+'px', height: previewPos.height+'px' }"
        >
          <div class="preview-titlebar" @mousedown.prevent="onDragStart">
            <span class="preview-titlebar-text">Preview</span>
            <div class="preview-titlebar-actions">
              <button
                v-for="d in (['mobile','tablet','desktop'] as const)"
                :key="d"
                class="device-btn"
                :class="{ active: previewDevice === d }"
                :style="previewDevice === d ? { color: accent } : {}"
                @click="previewDevice = d"
              >{{ d === 'mobile' ? '📱' : d === 'tablet' ? '📋' : '🖥' }}</button>
              <button class="preview-close-btn" @click="showPreview = false">
                <Icon name="x" :size="14" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <div class="preview-body">
            <LibraryPreview
              :blocks="previewBlocks"
              :device="previewDevice"
              :accent="accent"
              :title="previewTitle"
              :on-item-click="onPreviewItemClick"
              :can-go-back="previewNavStack.length > 0"
              :on-back="onPreviewBack"
              :loading="previewLoading"
            />
          </div>
          <div class="preview-resize-handle" @mousedown.prevent="onResizeStart" />
        </div>
      </Teleport>
    </ClientOnly>
    <ConfirmModal
      v-if="removeTarget"
      title="Remove Block"
      :message="`Remove &quot;${removeTarget.name}&quot; from this collection?`"
      confirm-label="Remove"
      danger
      @confirm="confirmRemoveBlock"
      @cancel="removeTarget = null"
    />
  </PageShell>
</template>

<style scoped>
.back-btn {
  all: unset; cursor: pointer; padding: 4px;
  border-radius: var(--r-xs); margin-right: 8px; transition: background 0.15s;
}
.back-btn:hover { background: color-mix(in oklch, var(--fg-3) 10%, transparent); }

.loading-state {
  flex: 1; display: flex; align-items: center; justify-content: center;
  color: var(--fg-3); font-size: 14px;
}

.refresh-indicator {
  display: inline-flex;
  align-items: center;
  padding: 0 4px;
}
.spin { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.builder-layout {
  display: flex; flex: 1; min-height: 0; overflow: hidden; margin: -22px;
}
.builder-palette {
  width: 210px; flex: 0 0 210px; border-right: 1px solid var(--line); overflow-y: auto;
}
.builder-tree {
  width: 240px; flex: 0 0 240px; border-right: 1px solid var(--line); overflow-y: auto;
}
.builder-canvas { flex: 1; overflow-y: auto; }
.builder-editor {
  width: 300px; flex: 0 0 300px; border-left: 1px solid var(--line); overflow-y: auto;
}

/* Floating Preview */
.preview-window {
  position: fixed; z-index: 900; display: flex; flex-direction: column;
  border-radius: 14px; background: var(--bg-1); border: 1px solid var(--line);
  box-shadow: 0 8px 32px rgba(0,0,0,0.3); overflow: hidden; transition: box-shadow 0.2s;
}
.preview-window--active { transition: none; user-select: none; box-shadow: 0 12px 48px rgba(0,0,0,0.4); }
.preview-titlebar {
  display: flex; align-items: center; padding: 8px 12px;
  background: color-mix(in oklch, var(--bg-2) 80%, transparent);
  border-bottom: 1px solid var(--line); cursor: grab; flex-shrink: 0;
}
.preview-window--active .preview-titlebar { cursor: grabbing; }
.preview-titlebar-text { font-size: 12px; font-weight: 600; color: var(--fg-2); flex: 1; }
.preview-titlebar-actions { display: flex; align-items: center; gap: 2px; }
.device-btn {
  all: unset; cursor: pointer; padding: 4px 6px; border-radius: var(--r-xs);
  color: var(--fg-3); transition: color 0.15s, background 0.15s;
}
.device-btn:hover { color: var(--fg-1); }
.device-btn.active { background: color-mix(in oklch, var(--fg-3) 12%, transparent); }
.preview-close-btn {
  all: unset; cursor: pointer; padding: 4px; border-radius: var(--r-xs);
  margin-left: 6px; transition: background 0.15s;
}
.preview-close-btn:hover { background: color-mix(in oklch, var(--fg-3) 12%, transparent); }
.preview-body { flex: 1; overflow-y: auto; min-height: 0; }
.preview-resize-handle {
  position: absolute; bottom: 0; right: 0; width: 16px; height: 16px; cursor: nwse-resize;
  background: linear-gradient(135deg,
    transparent 40%, color-mix(in oklch, var(--fg-3) 30%, transparent) 40%,
    color-mix(in oklch, var(--fg-3) 30%, transparent) 50%, transparent 50%,
    transparent 65%, color-mix(in oklch, var(--fg-3) 30%, transparent) 65%,
    color-mix(in oklch, var(--fg-3) 30%, transparent) 75%, transparent 75%);
  border-radius: 0 0 14px 0;
}
</style>
