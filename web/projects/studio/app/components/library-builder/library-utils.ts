export interface BlockTypeDefinition {
  type: string
  label: string
  icon: string
  isContainer: boolean
  // True when the block displays user-authored content (a guide, a category collection, etc.)
  // and therefore needs the editor to pick a metadata/collection to link.
  // False for purely structural blocks (status-row pulls from profile APIs; banner and
  // section-header are decoration). Those auto-create a placeholder child when added.
  bindsToContent: boolean
  group: 'content' | 'layout' | 'ui'
}

// Every block type has a UNIQUE icon. Two block types sharing an icon means
// the user can't tell them apart in the palette/canvas/outline at a glance.
// When adding a new block type, pick an icon that visually communicates the
// layout's distinguishing trait — verify it's not already in use here.
export const BLOCK_TYPES: BlockTypeDefinition[] = [
  { type: 'featured', label: 'Featured', icon: 'star', isContainer: false, bindsToContent: true, group: 'content' },
  { type: 'grid', label: 'Grid', icon: 'grid9', isContainer: true, bindsToContent: true, group: 'layout' },
  { type: 'carousel', label: 'Carousel', icon: 'arrowRight', isContainer: true, bindsToContent: true, group: 'layout' },
  { type: 'compact-list', label: 'Compact List', icon: 'list', isContainer: true, bindsToContent: true, group: 'layout' },
  { type: 'detail-list', label: 'Detail List', icon: 'rows-3', isContainer: true, bindsToContent: true, group: 'layout' },
  { type: 'stack', label: 'Stack', icon: 'layers', isContainer: true, bindsToContent: true, group: 'layout' },
  { type: 'masonry', label: 'Masonry', icon: 'layout-grid', isContainer: true, bindsToContent: true, group: 'layout' },
  { type: 'hero-rail', label: 'Hero + Rail', icon: 'gallery-horizontal-end', isContainer: true, bindsToContent: true, group: 'layout' },
  { type: 'status-row', label: 'Status Row', icon: 'pulse', isContainer: false, bindsToContent: false, group: 'ui' },
  { type: 'banner', label: 'Banner', icon: 'megaphone', isContainer: false, bindsToContent: false, group: 'content' },
  { type: 'section-header', label: 'Section Header', icon: 'heading2', isContainer: false, bindsToContent: false, group: 'ui' },
]

export const BLOCK_DEFAULTS: Record<string, Record<string, unknown>> = {
  'featured': { type: 'featured', height: 'large', textAlignment: 'center', overlay: 'dark' },
  'grid': { type: 'grid', columns: 2, aspectRatio: 1.0, showMoreLink: false },
  'carousel': { type: 'carousel', itemWidth: 'medium', showTitle: true, peekAmount: 0.15 },
  'compact-list': { type: 'compact-list', showThumbnail: true, showProgress: false },
  'detail-list': { type: 'detail-list', showDividers: true, density: 'comfortable' },
  'stack': { type: 'stack', spacing: 'medium', fullBleed: true },
  'masonry': { type: 'masonry', columns: 2, gap: 'medium' },
  'hero-rail': { type: 'hero-rail', railItemWidth: 'medium', showRailTitle: true },
  'status-row': { type: 'status-row', icon: 'book-open', dataSource: 'guide-progress', emptyMessage: 'No items yet' },
  'banner': { type: 'banner', style: 'filled', dismissible: false },
  'section-header': { type: 'section-header', style: 'large', showDivider: true },
}

// The uiType assigned to a child relationship that carries NO `ui.type` — i.e.
// a content item / navigable collection that isn't a layout block. It is
// load-bearing in several decisions (content-collection detection, the editor's
// container-agnostic mode, the preview's variant-derived container fallback),
// so it gets a name rather than being a bare string literal scattered around.
// When porting to Kotlin, model the block kind as a sealed type and let this
// be the `Unconfigured`/`ContentItem` case.
export const UNKNOWN_UI_TYPE = 'unknown'

export function isContainerType(type: string): boolean {
  return BLOCK_TYPES.find(b => b.type === type)?.isContainer ?? false
}

// Unknown/legacy uiTypes default to NOT binding to content. Treating unknown
// as a structural block means the editor doesn't show a Content section for
// blocks the user has no way to edit, and prevents the canvas from offering
// a drill chevron on broken data.
export function blockBindsToContent(type: string): boolean {
  return BLOCK_TYPES.find(b => b.type === type)?.bindsToContent ?? false
}

// Block types that are Collections only as an implementation detail — they're
// minted as placeholder collections to carry their `ui` config, but they have
// no navigable contents. Opening into them would show an empty, meaningless
// view, so they're excluded from drilling.
const STRUCTURAL_PLACEHOLDER_TYPES = new Set(['banner', 'section-header', 'status-row'])

// A block is drillable iff its underlying item is a Collection that has real
// navigable contents — i.e. any Collection except a structural placeholder.
// This deliberately includes Collections that aren't themselves a *container*
// layout (e.g. a navigable "group" that appears as a tile inside its parent):
// opening into them is the only way to manage how THEIR items render (their
// own item template lives on the collection). Metadata leaves have no children
// to open into, so they're never drillable. This is the single source of truth
// — canvas, editor, tree, and composable all use it to decide whether to show
// drill affordances or honor a drill request.
export function blockCanDrill(uiType: string, isCollection: boolean): boolean {
  return isCollection && !STRUCTURAL_PLACEHOLDER_TYPES.has(uiType)
}

export function blockTypeLabel(type: string): string {
  return BLOCK_TYPES.find(b => b.type === type)?.label ?? type
}

export function blockTypeIcon(type: string): string {
  return BLOCK_TYPES.find(b => b.type === type)?.icon ?? 'boxes'
}

export function buildBlockFromElement(el: HTMLElement): { type: string } | null {
  const blockType = el.getAttribute('data-block-type')
  if (!blockType) return null
  return { type: blockType }
}

export interface BlockHealthIssue {
  severity: 'warning'
  message: string
}

export interface BlockHealthInput {
  uiType: string
  childCount: number
  featuredImageUrl: string | null
  squareImageUrl: string | null
}

// Surface conditions that will cause a block to render badly in the client app but that
// the builder itself can't fix — empty containers, missing featured images,
// blocks with no ui config. The editor displays these inline; the user goes
// elsewhere (the CMS metadata/collection page) to actually resolve them.
export function computeBlockHealth(block: BlockHealthInput): BlockHealthIssue[] {
  const issues: BlockHealthIssue[] = []
  if (block.uiType === 'unknown') {
    issues.push({ severity: 'warning', message: 'Block has no UI configuration' })
    return issues
  }
  if (block.uiType === 'featured' && !block.featuredImageUrl) {
    issues.push({ severity: 'warning', message: 'Missing featured image (16:9) — tile will render without imagery' })
  }
  if (isContainerType(block.uiType) && block.childCount === 0) {
    issues.push({ severity: 'warning', message: 'Empty container — add items to the linked collection' })
  }
  return issues
}

// ───────────────────────────────────────────────────────────────────────────
// Item Template
//
// An ItemTemplate describes how a single child item is rendered when it
// appears inside a container block (Grid, Carousel, Stack, etc.). It lives on
// the *child collection*, not on the container's child-relationship — so the
// answer to "how should this collection's items look" travels with the
// collection and survives moving it between parents. A per-binding override
// (stored on the parent's child-relationship as itemAttributes.ui.itemTemplateOverride)
// lets a single binding-site customise without affecting other places the
// same collection is used.
//
// Effective template = bindingOverride ?? collectionTemplate ?? containerDefault
// ───────────────────────────────────────────────────────────────────────────

export type ItemVariant = 'tile' | 'card' | 'row' | 'detail-row' | 'hero-card'

export type ItemImagePlacement = 'background' | 'top' | 'left' | 'none'
export type ItemImageAspect = '1:1' | '3:4' | '4:3' | '16:9'
export type ItemTitlePlacement = 'overlay' | 'below' | 'beside'
export type ItemSubtitleSource = 'subtitle' | 'description' | 'category'
export type ItemBadgeSource = 'category' | 'duration' | 'status'
export type ItemProgressSource = 'guide-progress' | 'reading' | 'none'
// Action click behaviour. Items are ALWAYS clickable in the app — `kind`
// describes what happens (navigate to the item, or play it). Whether to
// show a visible label is a separate concern (`action.show`); two
// independent decisions instead of one overloaded enum.
export type ItemActionKind = 'navigate' | 'play'

export interface ItemTemplate {
  variant: ItemVariant
  image: { placement: ItemImagePlacement; aspect: ItemImageAspect }
  title: { show: boolean; placement: ItemTitlePlacement; lines: 1 | 2 | 3 }
  subtitle: { show: boolean; source: ItemSubtitleSource }
  badges: { show: boolean; source: ItemBadgeSource }
  progress: { show: boolean; source: ItemProgressSource }
  // show — render an action label on the item ("Read", "Open", etc.).
  // kind — what clicking the item does in the app (always one of two
  //   real actions; there's no "non-interactive item").
  // label — explicit label text; falls back to "Open"/"Play" at render time.
  action: { show: boolean; kind: ItemActionKind; label?: string }
  // Set to true on a binding-level override when the user has explicitly
  // accepted a variant that the container ↔ variant compat matrix flags as
  // incompatible. The flag stays scoped to the binding (or collection)
  // that carries it — we don't propagate it implicitly.
  variantCompatOverride?: boolean
}

export const ITEM_VARIANTS: Array<{ value: ItemVariant; label: string; description: string }> = [
  { value: 'tile', label: 'Tile', description: 'Image-dominant, label overlay or below' },
  { value: 'card', label: 'Card', description: 'Image + title + subtitle + optional action' },
  { value: 'row', label: 'Row', description: 'Compact horizontal row — thumbnail + label' },
  { value: 'detail-row', label: 'Detail Row', description: 'Rich row — image + multi-line text + badges + progress' },
  { value: 'hero-card', label: 'Hero Card', description: 'Large featured-style card for stacks and rails' },
]

// Per-variant default template. Picked by container defaults; also the
// starting point when the user first switches a collection to a new variant.
export const VARIANT_DEFAULTS: Record<ItemVariant, ItemTemplate> = {
  'tile': {
    variant: 'tile',
    image: { placement: 'background', aspect: '1:1' },
    title: { show: true, placement: 'overlay', lines: 2 },
    subtitle: { show: false, source: 'subtitle' },
    badges: { show: false, source: 'category' },
    progress: { show: false, source: 'none' },
    action: { show: false, kind: 'navigate' },
  },
  'card': {
    variant: 'card',
    image: { placement: 'top', aspect: '16:9' },
    title: { show: true, placement: 'below', lines: 2 },
    subtitle: { show: true, source: 'subtitle' },
    badges: { show: false, source: 'category' },
    progress: { show: false, source: 'none' },
    action: { show: false, kind: 'navigate' },
  },
  'row': {
    variant: 'row',
    image: { placement: 'left', aspect: '1:1' },
    title: { show: true, placement: 'beside', lines: 1 },
    subtitle: { show: false, source: 'subtitle' },
    badges: { show: false, source: 'category' },
    progress: { show: false, source: 'none' },
    action: { show: false, kind: 'navigate' },
  },
  'detail-row': {
    variant: 'detail-row',
    image: { placement: 'left', aspect: '1:1' },
    title: { show: true, placement: 'beside', lines: 2 },
    subtitle: { show: true, source: 'subtitle' },
    badges: { show: true, source: 'category' },
    progress: { show: true, source: 'guide-progress' },
    action: { show: false, kind: 'navigate' },
  },
  'hero-card': {
    variant: 'hero-card',
    image: { placement: 'background', aspect: '16:9' },
    title: { show: true, placement: 'overlay', lines: 2 },
    subtitle: { show: true, source: 'subtitle' },
    badges: { show: false, source: 'category' },
    progress: { show: false, source: 'none' },
    action: { show: false, kind: 'navigate' },
  },
}

// Container ↔ variant compatibility matrix. A "compatible" pairing is one
// whose layout has been designed for that item shape — the container's
// per-item slot is shaped right (aspect, alignment, density). Incompatible
// pairings produce a working but visually wrong render — the user has to
// explicitly accept the risk via a per-binding override.
const CONTAINER_COMPAT: Record<string, ItemVariant[]> = {
  'grid':         ['tile', 'card'],
  'carousel':     ['tile', 'card'],
  'compact-list': ['row'],
  'detail-list':  ['detail-row'],
  'stack':        ['card', 'hero-card'],
  'masonry':      ['tile', 'card'],
  'hero-rail':    ['tile', 'card', 'hero-card'],
}

// Map each container to the variant it defaults to when no template is set.
// Driven by CONTAINER_COMPAT[0] for consistency: the first compatible variant
// is what the container was designed for.
const CONTAINER_DEFAULT_VARIANT: Record<string, ItemVariant> = {
  'grid': 'tile',
  'carousel': 'card',
  'compact-list': 'row',
  'detail-list': 'detail-row',
  'stack': 'hero-card',
  'masonry': 'tile',
  'hero-rail': 'card',
}

export function compatibleVariantsForContainer(containerType: string): ItemVariant[] {
  return CONTAINER_COMPAT[containerType] ?? []
}

export function isVariantCompatible(containerType: string, variant: ItemVariant): boolean {
  return compatibleVariantsForContainer(containerType).includes(variant)
}

export function containerDefaultTemplate(containerType: string): ItemTemplate {
  const variant = CONTAINER_DEFAULT_VARIANT[containerType] ?? 'tile'
  return cloneTemplate(VARIANT_DEFAULTS[variant])
}

export function cloneTemplate(t: ItemTemplate): ItemTemplate {
  return {
    variant: t.variant,
    image: { ...t.image },
    title: { ...t.title },
    subtitle: { ...t.subtitle },
    badges: { ...t.badges },
    progress: { ...t.progress },
    action: { ...t.action },
    ...(t.variantCompatOverride ? { variantCompatOverride: true } : {}),
  }
}

// Validate and lift a raw `ui` blob into a typed ItemTemplate. Returns null
// when there's no usable template — falls through to container default. Any
// unknown enum value falls back to the variant default's value for that
// field, so a partial / hand-edited record still produces a coherent template.
export function parseItemTemplate(raw: unknown): ItemTemplate | null {
  if (!raw || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  const variant = isItemVariant(r.variant) ? r.variant : null
  if (!variant) return null
  const base = VARIANT_DEFAULTS[variant]
  const image = (r.image && typeof r.image === 'object')
    ? r.image as Record<string, unknown> : {}
  const title = (r.title && typeof r.title === 'object')
    ? r.title as Record<string, unknown> : {}
  const subtitle = (r.subtitle && typeof r.subtitle === 'object')
    ? r.subtitle as Record<string, unknown> : {}
  const badges = (r.badges && typeof r.badges === 'object')
    ? r.badges as Record<string, unknown> : {}
  const progress = (r.progress && typeof r.progress === 'object')
    ? r.progress as Record<string, unknown> : {}
  const action = (r.action && typeof r.action === 'object')
    ? r.action as Record<string, unknown> : {}
  return {
    variant,
    image: {
      placement: isImagePlacement(image.placement) ? image.placement : base.image.placement,
      aspect: isImageAspect(image.aspect) ? image.aspect : base.image.aspect,
    },
    title: {
      show: typeof title.show === 'boolean' ? title.show : base.title.show,
      placement: isTitlePlacement(title.placement) ? title.placement : base.title.placement,
      lines: isTitleLines(title.lines) ? title.lines : base.title.lines,
    },
    subtitle: {
      show: typeof subtitle.show === 'boolean' ? subtitle.show : base.subtitle.show,
      source: isSubtitleSource(subtitle.source) ? subtitle.source : base.subtitle.source,
    },
    badges: {
      show: typeof badges.show === 'boolean' ? badges.show : base.badges.show,
      source: isBadgeSource(badges.source) ? badges.source : base.badges.source,
    },
    progress: {
      show: typeof progress.show === 'boolean' ? progress.show : base.progress.show,
      source: isProgressSource(progress.source) ? progress.source : base.progress.source,
    },
    action: parseAction(action, base.action),
    ...(r.variantCompatOverride === true ? { variantCompatOverride: true } : {}),
  }
}

// Source tells the editor where the effective template came from so it can
// label the editor accordingly ("Inherited from collection", "Container default",
// "Binding override").
export type TemplateSource = 'binding-override' | 'collection-template' | 'container-default'

export function resolveEffectiveTemplate(
  containerType: string,
  collectionTemplate: ItemTemplate | null,
  bindingOverride: ItemTemplate | null,
): { template: ItemTemplate; source: TemplateSource } {
  // Every path returns a FRESH clone. The resolved template flows into render
  // props and the editor; returning a shared reference to `bindingOverride` /
  // `collectionTemplate` (which alias reactive state and the drill stack) would
  // let any consumer that mutates it corrupt that state. cloneTemplate is cheap
  // and keeps value-semantics — important for the planned Kotlin port.
  if (bindingOverride) return { template: cloneTemplate(bindingOverride), source: 'binding-override' }
  if (collectionTemplate) return { template: cloneTemplate(collectionTemplate), source: 'collection-template' }
  return { template: containerDefaultTemplate(containerType), source: 'container-default' }
}

// Resolve the effective template for a Block at root view — using the
// block's own loaded collection template + binding override. Callers MUST
// use this (not resolveEffectiveTemplate with nulls) so edits made through
// the right-panel Item Presentation actually appear in the preview. Bug
// history: previewBlocks at root once passed null for the collection
// template here, so toggling Title off (etc.) saved correctly but the
// preview kept rendering container defaults.
export function resolveBlockEffectiveTemplate(block: {
  uiType: string
  collectionItemTemplate: ItemTemplate | null
  bindingItemTemplateOverride: ItemTemplate | null
}): { template: ItemTemplate; source: TemplateSource } {
  return resolveEffectiveTemplate(
    block.uiType,
    block.collectionItemTemplate,
    block.bindingItemTemplateOverride,
  )
}

function isItemVariant(v: unknown): v is ItemVariant {
  return v === 'tile' || v === 'card' || v === 'row' || v === 'detail-row' || v === 'hero-card'
}
function isImagePlacement(v: unknown): v is ItemImagePlacement {
  return v === 'background' || v === 'top' || v === 'left' || v === 'none'
}
function isImageAspect(v: unknown): v is ItemImageAspect {
  return v === '1:1' || v === '3:4' || v === '4:3' || v === '16:9'
}
function isTitlePlacement(v: unknown): v is ItemTitlePlacement {
  return v === 'overlay' || v === 'below' || v === 'beside'
}
function isTitleLines(v: unknown): v is 1 | 2 | 3 {
  return v === 1 || v === 2 || v === 3
}
function isSubtitleSource(v: unknown): v is ItemSubtitleSource {
  return v === 'subtitle' || v === 'description' || v === 'category'
}
function isBadgeSource(v: unknown): v is ItemBadgeSource {
  return v === 'category' || v === 'duration' || v === 'status'
}
function isProgressSource(v: unknown): v is ItemProgressSource {
  return v === 'guide-progress' || v === 'reading' || v === 'none'
}
function isActionKind(v: unknown): v is ItemActionKind {
  return v === 'navigate' || v === 'play'
}

// Action parsing with legacy migration. The OLD schema used
// `kind: 'none' | 'navigate' | 'play'` — `'none'` meant both "no label"
// AND "no click behavior." The new schema splits these: `show` is label
// visibility; `kind` is always one of the two real click behaviours. Old
// records on disk migrate on read so historic libraries keep working.
//
//   Old { kind: 'none' }                → { show: false, kind: 'navigate' }
//   Old { kind: 'navigate', label: 'X' } → { show: true,  kind: 'navigate', label: 'X' }
//   Old { kind: 'play' }                 → { show: true,  kind: 'play' }
//   New { show: true, kind: 'play' }     → unchanged
//
// `show` is derived from legacy `kind !== 'none'` ONLY when the new field
// isn't present; an explicit `show` always wins.
function parseAction(
  raw: Record<string, unknown>,
  fallback: ItemTemplate['action'],
): ItemTemplate['action'] {
  const kind: ItemActionKind = isActionKind(raw.kind) ? raw.kind : fallback.kind

  let show: boolean
  if (typeof raw.show === 'boolean') {
    show = raw.show
  } else if (raw.kind === 'none') {
    show = false
  } else if (isActionKind(raw.kind)) {
    show = true
  } else {
    show = fallback.show
  }

  const result: ItemTemplate['action'] = { show, kind }
  if (typeof raw.label === 'string') result.label = raw.label
  return result
}

export function aspectRatioNumber(aspect: ItemImageAspect): number {
  switch (aspect) {
    case '1:1':  return 1
    case '3:4':  return 0.75
    case '4:3':  return 4 / 3
    case '16:9': return 16 / 9
  }
}

// ───────────────────────────────────────────────────────────────────────────
// Preview-side types shared across every container preview component and the
// page-level synthetic-block construction. isCollection flows through these
// so click handlers can decide between navigate-into and describe-only.
// ───────────────────────────────────────────────────────────────────────────

// The container layout that naturally renders each item variant. Used to give
// a navigable Collection that has NO container layout of its own (uiType
// 'unknown' — e.g. a "group") a sensible layout in the preview, so its items
// render through their item template instead of an "unknown type" placeholder.
const VARIANT_PREVIEW_CONTAINER: Record<ItemVariant, string> = {
  'tile': 'grid',
  'card': 'grid',
  'row': 'compact-list',
  'detail-row': 'detail-list',
  'hero-card': 'stack',
}

// Pick the container type to render a SET of items under in the preview. The
// result must be a MULTI-ITEM container — the items render INSIDE it. So we
// honour `uiType` only when it is an actual container layout (grid/carousel/
// compact-list/…); otherwise we derive the container from the item variant.
//
// Crucially we must NOT honour a non-container renderable type here
// (featured/banner/section-header/status-row). Those render as a single
// decoration block and would silently DROP every item — the "one bar, no
// items" bug. A collection that carries a legacy/stray non-container `ui.type`
// (set by the old editor) still renders its items as a real list/grid.
export function previewContainerType(uiType: string, variant: ItemVariant): string {
  return isContainerType(uiType) ? uiType : VARIANT_PREVIEW_CONTAINER[variant]
}

// ── The single answer to "how do this collection's items render?" ───────────
// Every surface (drilled canvas, drilled preview, root preview, preview
// tap-navigation, and the mobile renderer once ported) must resolve the
// container + template the SAME way, or the same collection renders
// inconsistently across surfaces. This is that one function. Inputs are plain
// data (no Vue refs, no GraphQL types) so it ports verbatim to Kotlin.
//
//   container — the layout to arrange the items under (a renderable type;
//               remapped from a non-container collection's uiType via its
//               variant). Never the "unknown" placeholder.
//   template  — the fully-resolved, freshly-cloned ItemTemplate (precedence:
//               bindingOverride > collectionTemplate > containerDefault).
//   source    — where the template came from, for the editor's label.
export interface ItemRenderPlan {
  container: string
  template: ItemTemplate
  source: TemplateSource
}

export function resolveItemRenderPlan(
  ownUiType: string,
  collectionTemplate: ItemTemplate | null,
  bindingOverride: ItemTemplate | null,
): ItemRenderPlan {
  const { template, source } = resolveEffectiveTemplate(ownUiType, collectionTemplate, bindingOverride)
  return { container: previewContainerType(ownUiType, template.variant), template, source }
}

// ── Page vs. content collection ─────────────────────────────────────────────
// A collection is edited one of two ways:
//   • A "page" — a deliberately-authored layout of blocks (a Section Header, a
//     Banner, a Grid bound to some content). Edited block-by-block.
//   • A "content collection" — a list of content (sub-collections / metadata).
//     Edited with ONE collection-wide layout + per-child render overrides.
//
// The distinguishing signal is the presence of a STRUCTURAL page block —
// banner / section-header / status-row. Those are pure decoration minted by the
// palette; their presence means the user built a page. A child rendered as a
// container (grid/carousel/…) does NOT make a collection a page — that's just a
// content item expanded inline — so container types are content, not page.
//
// `featured` is intentionally NOT structural here: a single content item can be
// promoted to a hero inside a content collection without turning the whole
// collection into a page.
const PAGE_STRUCTURAL_TYPES = new Set(['banner', 'section-header', 'status-row'])

// The minimal child shape needed to classify a collection. LibraryBlock and the
// preview's block projection both satisfy it.
export interface ContentClassChild {
  uiType: string
  isCollection: boolean
  childCount: number
}

// A child is a "page block" only when it carries a structural page type AND is
// not itself a navigable collection-with-children. A real collection that
// happens to carry a stray structural type (e.g. a `section-header` mis-set by
// the old per-item dropdown) is still content — we classify by what the child
// IS (real content with its own items), not by a value the broken editor wrote.
function isPageBlock(c: ContentClassChild): boolean {
  if (!PAGE_STRUCTURAL_TYPES.has(c.uiType)) return false
  if (c.isCollection && c.childCount > 0) return false
  return true
}

// True when a collection's children are content (rendered through the
// collection's layout + per-child overrides) rather than a designed page of
// structural blocks. An empty collection is treated as content (an empty list).
export function isContentCollection(children: ReadonlyArray<ContentClassChild>): boolean {
  if (children.length === 0) return true
  return children.every((c) => !isPageBlock(c))
}

// ── Collection layout (how a collection arranges its own items) ─────────────
// A collection's own layout lives on its `attributes.ui.type` — parallel to a
// binding's `itemAttributes.ui.type`, but it answers "how does THIS collection
// arrange its items by default" and travels with the collection. When unset it
// defaults to a vertical list.
export const DEFAULT_COLLECTION_LAYOUT = 'compact-list'

// The container layouts a collection (or a single child) can render as — exactly
// the container block types. Non-container block types (featured/banner/
// section-header/status-row) are page-building blocks, not item layouts.
export function collectionLayoutOptions(): Array<{ value: string; label: string }> {
  return BLOCK_TYPES.filter((b) => b.isContainer).map((b) => ({ value: b.type, label: b.label }))
}

// Read a collection's layout from its own `attributes.ui` (or the default). A
// stray/invalid `type` (e.g. a non-container value) falls back to the default
// so the editor never shows a layout that can't actually arrange items.
export function collectionLayoutFromUi(ui: Record<string, unknown> | null | undefined): string {
  const t = ui?.type
  return typeof t === 'string' && isContainerType(t) ? t : DEFAULT_COLLECTION_LAYOUT
}

// ── Per-child "Render as" ───────────────────────────────────────────────────
// A child of a content collection renders either as a single ITEM (a tile/row
// using the parent collection's presentation) or, when it's a collection, as
// its own CONTAINER (carousel/grid/…) that expands its contents inline. "Item"
// is the ABSENCE of a container type on the binding — uiType is UNKNOWN (or any
// non-container value, which we normalise to "item").
export const RENDER_AS_ITEM = 'item'

// The dropdown value for a child given its binding uiType: a container type maps
// to itself; everything else (UNKNOWN, a stray structural type, …) is "Item".
export function childRenderAsValue(uiType: string): string {
  return isContainerType(uiType) ? uiType : RENDER_AS_ITEM
}

// Options for the per-child "Render as" control: Item first, then the containers.
export function childRenderAsOptions(): Array<{ value: string; label: string }> {
  return [{ value: RENDER_AS_ITEM, label: 'Item' }, ...collectionLayoutOptions()]
}

// Read-modify-write a single key within a `ui` object, returning a NEW object.
// The Bosca server merges attributes with a shallow JSONB `||` that REPLACES
// the whole `ui` value rather than deep-merging it — so any persistence write
// must send the COMPLETE `ui`. Sending a partial `{ ui: { oneKey } }` would
// destroy every sibling key (type, columns, label, …). Callers therefore read
// the current full `ui`, apply one key via this helper, and write the result.
//   value == null  → the key is OMITTED from the result (clears it once the
//                    full-`ui` value replaces what's on the server).
//   value != null  → the key is set to value.
// Pure and value-semantic — ports directly to the Kotlin client, which must
// replicate the same full-replace contract.
export function mergeUiKey(
  ui: Record<string, unknown> | null | undefined,
  key: string,
  value: unknown,
): Record<string, unknown> {
  const next: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(ui ?? {})) {
    if (k !== key) next[k] = v
  }
  if (value != null) next[key] = value
  return next
}

export interface PreviewChild {
  id: string
  name: string
  imageUrl: string | null
  // True when this child item points to a Collection (drillable / openable
  // in the preview's app simulator). False for Metadata. Required so the
  // click handler can make a reliable navigate-vs-describe decision.
  isCollection: boolean
  // The child collection's own block uiType and the parent→child binding
  // override. Carried so navigating INTO this child (preview tap-navigation)
  // resolves its items' template via the SAME precedence as the drilled
  // editor — `bindingOverride ?? childCollectionTemplate ?? containerDefault`.
  // Omitted for leaf items and for lean child projections that don't track it
  // (callers then fall back to childCollectionTemplate ?? default).
  uiType?: string
  bindingOverride?: ItemTemplate | null
}

export type SyntheticPreviewChild = PreviewChild

export interface SyntheticPreviewBlock {
  id: string
  name: string
  uiType: string
  uiConfig: Record<string, unknown>
  featuredImageUrl: null
  children: PreviewChild[]
  itemTemplate: ItemTemplate
}

export function buildSyntheticParentPreviewBlock(args: {
  parentBindingBlockId: string
  parentUiType: string
  parentUiConfig: Record<string, unknown>
  collectionName: string
  children: SyntheticPreviewChild[]
  effectiveTemplate: ItemTemplate
}): SyntheticPreviewBlock {
  return {
    id: args.parentBindingBlockId,
    name: args.collectionName,
    // A drilled-into collection that isn't itself a container layout (uiType
    // 'unknown') would render as an "unknown type" placeholder — so the
    // preview never reflects the item template being edited. Remap it to the
    // layout that fits the variant so the items actually render.
    uiType: previewContainerType(args.parentUiType, args.effectiveTemplate.variant),
    uiConfig: { ...args.parentUiConfig },
    featuredImageUrl: null,
    children: args.children,
    itemTemplate: args.effectiveTemplate,
  }
}
