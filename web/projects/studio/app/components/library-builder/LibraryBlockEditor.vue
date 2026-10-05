<script setup lang="ts">
import { computed } from 'vue'
import {
  BLOCK_DEFAULTS,
  BLOCK_TYPES,
  RENDER_AS_ITEM,
  blockBindsToContent,
  blockCanDrill,
  childRenderAsOptions,
  childRenderAsValue,
  collectionLayoutOptions,
  computeBlockHealth,
} from './library-utils'
import type { ItemTemplate, TemplateSource } from './library-utils'
import type { SelectOption } from '@bosca/ui'

interface BlockProp {
  id: string
  name: string
  uiType: string
  uiConfig: Record<string, unknown>
  isCollection: boolean
  childCount: number
  featuredImageUrl: string | null
  squareImageUrl: string | null
  bindsToContent: boolean
}

const props = defineProps<{
  block: BlockProp | null
  accent: string
  // When set, the editor is showing the right-panel for a DRILLED-DOWN view
  // — i.e., we're inside a container's child collection. In that case the
  // editor reorganises around two questions:
  //   1. "How should items in this view render?" → Item Presentation (the
  //      parent container's template; pinned at the top of the panel)
  //   2. "Settings for the selected child" → only the common, content-agnostic
  //      fields (Label, Subtitle, Hidden). Block-type / type-specific
  //      settings don't apply to a child whose appearance is decided by the
  //      parent's template, so they're suppressed.
  // When null, the editor falls back to the standard top-level block editor.
  drillContext: {
    parentUiType: string
    parentBindingBlockId: string
  } | null
  effectiveTemplate: { template: ItemTemplate; source: TemplateSource } | null
  hasBindingOverride: boolean
  // Mirror of effectiveTemplate / hasBindingOverride but for the SELECTED
  // container block at root view. When non-null, the editor renders an
  // Item Presentation section below the block's own settings, letting the
  // user control "how this container's items render" without first drilling
  // in. Only meaningful when drillContext is null AND the selected block is
  // a drillable container — the page computes that and passes null
  // otherwise.
  childEffectiveTemplate: { template: ItemTemplate; source: TemplateSource } | null
  childHasBindingOverride: boolean
  // ── Content-collection view ──
  // When true, the current view is a content collection: the panel shows the
  // collection's Layout + Item Presentation, plus (when an item is selected) its
  // "Render as" override, Item details, and an optional per-item presentation
  // override. Takes precedence over the drilled/page surfaces.
  contentView: boolean
  // The collection's own layout (a container type) + its presentation.
  collectionLayout: string
  contentTemplate: { template: ItemTemplate; source: TemplateSource } | null
  // The SELECTED item's effective presentation + whether it carries a per-item
  // override — for the "Customize this item" editor. Null when no item is
  // selected or the item is rendered as a container (edited by opening it).
  itemTemplate: { template: ItemTemplate; source: TemplateSource } | null
  itemHasOverride: boolean
}>()

const emit = defineEmits<{
  'update-config': [blockId: string, config: Record<string, unknown>]
  'replace-config': [blockId: string, config: Record<string, unknown>]
  'drill-down': [blockId: string]
  'change-content': [blockId: string]
  'update-item-template': [template: ItemTemplate, scope: 'collection' | 'binding']
  'clear-binding-override': []
  'lift-binding-to-collection': [template: ItemTemplate]
  // Content-view emits.
  'update-collection-layout': [layout: string]
  'update-collection-template': [template: ItemTemplate]
  'set-render-as': [blockId: string, renderAs: string]
}>()

// Content view supersedes the drilled binding-template editor — a content
// collection is edited through its own layout/presentation, not through the
// parent binding.
const inDrilledView = computed(() =>
  !props.contentView && props.drillContext != null && props.effectiveTemplate != null,
)

const layoutOptions = computed<SelectOption[]>(() =>
  collectionLayoutOptions().map((o) => ({ label: o.label, value: o.value })),
)
const renderAsOptions = computed<SelectOption[]>(() =>
  childRenderAsOptions().map((o) => ({ label: o.label, value: o.value })),
)
// "item" / a container type, for the selected item's "Render as" control.
const selectedRenderAs = computed(() =>
  props.block ? childRenderAsValue(props.block.uiType) : RENDER_AS_ITEM,
)

function onLayoutChange(v: string | string[] | null | undefined) {
  if (typeof v === 'string') emit('update-collection-layout', v)
}
function onRenderAsChange(v: string | string[] | null | undefined) {
  if (typeof v === 'string' && props.block) emit('set-render-as', props.block.id, v)
}
function onCollectionTemplateUpdate(template: ItemTemplate) {
  emit('update-collection-template', template)
}

function onTemplateUpdate(template: ItemTemplate, scope: 'collection' | 'binding') {
  emit('update-item-template', template, scope)
}
function onClearBindingOverride() {
  emit('clear-binding-override')
}
function onLiftToCollection(template: ItemTemplate) {
  emit('lift-binding-to-collection', template)
}

// Keys that are meaningful across every block type. When the user changes the
// block's type via the dropdown, these carry over; everything else is wiped and
// repopulated from the new type's defaults.
const COMMON_KEYS = ['label', 'subtitle', 'hidden'] as const

function onTypeChange(newType: string | string[] | null | undefined) {
  if (typeof newType !== 'string' || !props.block) return
  if (newType === props.block.uiType) return
  // Refuse cross-boundary type changes (bound ↔ structural). Crossing the
  // boundary leaves the underlying parent-child link in a state the new type
  // can't use: e.g. switching a Featured (linked to a metadata) to Banner
  // would leave an orphan metadata reference the banner UI ignores entirely.
  // The user has to delete and re-add to cross.
  if (blockBindsToContent(props.block.uiType) !== blockBindsToContent(newType)) return
  const defaults = BLOCK_DEFAULTS[newType] ?? { type: newType }
  const preserved: Record<string, unknown> = {}
  for (const key of COMMON_KEYS) {
    const value = props.block.uiConfig[key]
    if (value !== undefined) preserved[key] = value
  }
  emit('replace-config', props.block.id, { ...defaults, ...preserved })
}

const healthIssues = computed(() => {
  if (!props.block) return []
  return computeBlockHealth(props.block)
})

// The CMS edit page differs by kind: collections live under /cms/collections,
// metadata under /cms/metadata. Both expose the relationship + attribute editors
// the user needs to actually fix a content-health issue. Structural placeholder
// collections don't get a link because the user shouldn't go editing them
// directly — the library builder is the right place to manage them.
const cmsEditLink = computed(() => {
  if (!props.block) return null
  if (!props.block.bindsToContent) return null
  return props.block.isCollection
    ? `/cms/collections/${props.block.id}`
    : `/cms/metadata/${props.block.id}`
})

// Filter the dropdown so users can only pick types in the same category as
// the current block. The actual guard against cross-boundary changes is in
// `onTypeChange`; filtering is a UX courtesy so the option simply isn't there
// rather than appearing and silently no-op'ing.
const typeOptions = computed<SelectOption[]>(() => {
  const block = props.block
  if (!block) return BLOCK_TYPES.map(b => ({ label: b.label, value: b.type }))
  const isBound = blockBindsToContent(block.uiType)
  return BLOCK_TYPES
    .filter(b => blockBindsToContent(b.type) === isBound)
    .map(b => ({ label: b.label, value: b.type }))
})

const heightOptions: SelectOption[] = [
  { label: 'Large', value: 'large' },
  { label: 'Medium', value: 'medium' },
  { label: 'Small', value: 'small' },
]

const textAlignOptions: SelectOption[] = [
  { label: 'Center', value: 'center' },
  { label: 'Left', value: 'left' },
  { label: 'Right', value: 'right' },
  { label: 'Bottom Left', value: 'bottom-left' },
  { label: 'Bottom Right', value: 'bottom-right' },
]

const overlayOptions: SelectOption[] = [
  { label: 'Dark', value: 'dark' },
  { label: 'Light', value: 'light' },
  { label: 'None', value: 'none' },
]

const itemWidthOptions: SelectOption[] = [
  { label: 'Small', value: 'small' },
  { label: 'Medium', value: 'medium' },
  { label: 'Large', value: 'large' },
]

const iconOptions: SelectOption[] = [
  { label: 'Book Open', value: 'book-open' },
  { label: 'Bookmark', value: 'bookmark' },
  { label: 'Clock', value: 'clock' },
]

const dataSourceOptions: SelectOption[] = [
  { label: 'Guide Progress', value: 'guide-progress' },
  { label: 'Marks (Saved)', value: 'marks' },
  { label: 'Bookmarks', value: 'bookmarks' },
]

const bannerStyleOptions: SelectOption[] = [
  { label: 'Filled', value: 'filled' },
  { label: 'Outlined', value: 'outlined' },
  { label: 'Gradient', value: 'gradient' },
]

const sectionStyleOptions: SelectOption[] = [
  { label: 'Large', value: 'large' },
  { label: 'Small', value: 'small' },
]

const stackSpacingOptions: SelectOption[] = [
  { label: 'Tight', value: 'tight' },
  { label: 'Medium', value: 'medium' },
  { label: 'Loose', value: 'loose' },
]

const masonryGapOptions: SelectOption[] = [
  { label: 'Tight', value: 'tight' },
  { label: 'Medium', value: 'medium' },
  { label: 'Loose', value: 'loose' },
]

const detailListDensityOptions: SelectOption[] = [
  { label: 'Compact', value: 'compact' },
  { label: 'Comfortable', value: 'comfortable' },
  { label: 'Spacious', value: 'spacious' },
]

const heroRailItemWidthOptions: SelectOption[] = [
  { label: 'Small', value: 'small' },
  { label: 'Medium', value: 'medium' },
  { label: 'Large', value: 'large' },
]

function update(key: string, value: unknown) {
  if (!props.block) return
  emit('update-config', props.block.id, { [key]: value })
}

// Text fields use null (not undefined) when cleared. undefined gets dropped
// by JSON serialization, so the server's merge semantics would keep the prior
// value forever — the user can never erase a label they once set.
function updateStr(key: string, value: string) {
  update(key, value && value.trim().length > 0 ? value : null)
}

function updateSelect(key: string, value: string | string[] | null | undefined) {
  if (typeof value === 'string') update(key, value)
}

function updateNumber(key: string, value: number | null) {
  update(key, value ?? 0)
}

// `uiConfig` is keyed `Record<string, unknown>` because itemAttributes ship as
// opaque JSON. These helpers narrow safely at the binding boundary so a
// template binding never silently renders a number as a string or vice-versa.
function strVal(key: string, fallback = ''): string {
  const v = props.block?.uiConfig?.[key]
  return typeof v === 'string' ? v : fallback
}
function numVal(key: string, fallback: number): number {
  const v = props.block?.uiConfig?.[key]
  return typeof v === 'number' && Number.isFinite(v) ? v : fallback
}
function boolVal(key: string, fallback = false): boolean {
  const v = props.block?.uiConfig?.[key]
  return typeof v === 'boolean' ? v : fallback
}
</script>

<template>
  <div class="editor">
    <div class="editor-heading">Properties</div>

    <!--
      CONTENT VIEW (a collection of content items):
        - Collection layout (how items are arranged) + Item Presentation
          (how each item looks) — the collection-wide controls, always shown.
        - When an item is selected: "Render as" (Item vs. a container that
          expands it inline), Item details (label/subtitle/hidden), and an
          optional per-item presentation override. No "Block Type", no
          UNKNOWN/no-config warnings — items don't carry their own block type.
    -->
    <template v-if="contentView">
      <div class="editor-section">
        <div class="editor-subheading">Collection layout</div>
        <Select
          :model-value="collectionLayout"
          :options="layoutOptions"
          label="Layout"
          :accent="accent"
          @update:model-value="onLayoutChange"
        />
      </div>

      <div v-if="contentTemplate" class="editor-section editor-section--template">
        <LibraryItemTemplateEditor
          key="content-collection-template"
          :parent-ui-type="collectionLayout"
          :template="contentTemplate.template"
          :source="contentTemplate.source"
          :has-binding-override="false"
          locked-scope="collection"
          :accent="accent"
          @update-template="onCollectionTemplateUpdate"
        />
      </div>

      <template v-if="block">
        <!-- Render as — only collections can expand into a container; a metadata
             leaf is always a single item, so it shows no layout choice. -->
        <div v-if="block.isCollection" class="editor-section">
          <div class="editor-subheading">Render as</div>
          <Select
            :model-value="selectedRenderAs"
            :options="renderAsOptions"
            :accent="accent"
            @update:model-value="onRenderAsChange"
          />
          <p v-if="selectedRenderAs === 'item'" class="editor-runtime-note">
            Renders as a single item using the collection's presentation above.
          </p>
          <template v-else>
            <p class="editor-runtime-note">
              Expands “{{ block.name }}”'s own contents inline as a {{ selectedRenderAs }}.
            </p>
            <Button
              v-if="blockCanDrill(block.uiType, block.isCollection)"
              size="sm"
              :accent="accent"
              @click="emit('drill-down', block.id)"
            >
              Edit its items
            </Button>
          </template>
        </div>

        <div class="editor-section">
          <div class="editor-subheading">{{ block.name || 'Item' }}</div>
          <TextInput
            :model-value="strVal('label')"
            label="Label"
            placeholder="Override display name"
            @update:model-value="(v: string) => updateStr('label', v)"
          />
          <TextInput
            :model-value="strVal('subtitle')"
            label="Subtitle"
            placeholder="Optional subtitle"
            @update:model-value="(v: string) => updateStr('subtitle', v)"
          />
          <Switch
            :model-value="boolVal('hidden')"
            label="Hidden"
            :accent="accent"
            @update:model-value="(v: boolean) => update('hidden', v)"
          />
        </div>

        <!--
          Per-item presentation override — customise how THIS one item renders
          (its image, title, variant) without affecting the rest. Only when the
          item renders as an item; container-overridden children are edited by
          opening them.
        -->
        <div
          v-if="selectedRenderAs === 'item' && itemTemplate"
          class="editor-section editor-section--template editor-section--child-template"
        >
          <div class="editor-subheading">Customize this item</div>
          <LibraryItemTemplateEditor
            :key="`item-${block.id}`"
            :parent-ui-type="collectionLayout"
            :template="itemTemplate.template"
            :source="itemTemplate.source"
            :has-binding-override="itemHasOverride"
            locked-scope="binding"
            :accent="accent"
            @update-template="onTemplateUpdate"
            @clear-binding-override="onClearBindingOverride"
          />
        </div>
      </template>

      <div v-else class="editor-hint">
        Select an item to set how it renders, override its appearance, or edit
        its label.
      </div>
    </template>

    <!--
      DRILLED VIEW (inside a container's child collection):
        - Item Presentation = the parent's template, pinned at top, always
          visible regardless of selection. This is the "how do items render"
          control surface; the canvas reflects whatever the template says.
        - If a child item is selected, its Common fields appear below.
        - Everything else (Block Type, type-specific settings, content
          picker, image surfaces) is suppressed — items are rendered by the
          parent's template, not their own block-type, so exposing those
          controls would be misleading.
    -->
    <template v-else-if="inDrilledView && effectiveTemplate && drillContext">
      <div class="editor-section editor-section--template">
        <LibraryItemTemplateEditor
          :key="`drill-${drillContext.parentBindingBlockId}`"
          :parent-ui-type="drillContext.parentUiType"
          :template="effectiveTemplate.template"
          :source="effectiveTemplate.source"
          :has-binding-override="hasBindingOverride"
          :accent="accent"
          @update-template="onTemplateUpdate"
          @clear-binding-override="onClearBindingOverride"
          @lift-binding-to-collection="onLiftToCollection"
        />
      </div>

      <div v-if="block" class="editor-section">
        <div class="editor-subheading">Item</div>
        <TextInput
          :model-value="strVal('label')"
          label="Label"
          placeholder="Override display name"
          @update:model-value="(v: string) => updateStr('label', v)"
        />
        <TextInput
          :model-value="strVal('subtitle')"
          label="Subtitle"
          placeholder="Optional subtitle"
          @update:model-value="(v: string) => updateStr('subtitle', v)"
        />
        <Switch
          :model-value="boolVal('hidden')"
          label="Hidden"
          :accent="accent"
          @update:model-value="(v: boolean) => update('hidden', v)"
        />
      </div>

      <div v-if="!block" class="editor-hint">
        Select an item in the canvas to edit its label, subtitle, or visibility.
      </div>
    </template>

    <!--
      ROOT / NON-DRILLED VIEW:
        - Standard block editor: Block Type, type-specific settings, Common
          fields, Content/Images surfaces. Items at this level ARE the
          blocks; their uiType determines how they render in the client app.
    -->
    <template v-else-if="block">
      <div class="editor-section">
        <Select
          :model-value="block.uiType"
          :options="typeOptions"
          label="Block Type"
          :accent="accent"
          @update:model-value="onTypeChange"
        />
        <div v-if="healthIssues.length" class="health-callout">
          <div v-for="(issue, i) in healthIssues" :key="i" class="health-issue">
            <Icon name="alert" :size="13" color="var(--warn, #e3b341)" />
            <span class="health-message">{{ issue.message }}</span>
          </div>
          <NuxtLink
            v-if="cmsEditLink"
            :to="cmsEditLink"
            class="health-action"
            target="_blank"
          >
            <Icon name="external-link" :size="12" color="currentColor" />
            <span>Open in CMS</span>
          </NuxtLink>
        </div>
      </div>

      <div class="editor-section">
        <div class="editor-subheading">Common</div>
        <TextInput
          :model-value="strVal('label')"
          label="Label"
          placeholder="Override display name"
          @update:model-value="(v: string) => updateStr('label', v)"
        />
        <TextInput
          :model-value="strVal('subtitle')"
          label="Subtitle"
          placeholder="Optional subtitle"
          @update:model-value="(v: string) => updateStr('subtitle', v)"
        />
        <Switch
          :model-value="boolVal('hidden')"
          label="Hidden"
          :accent="accent"
          @update:model-value="(v: boolean) => update('hidden', v)"
        />
      </div>

      <!-- Featured -->
      <div v-if="block.uiType === 'featured'" class="editor-section">
        <div class="editor-subheading">Featured Settings</div>
        <Select
          :model-value="strVal('height', 'large')"
          :options="heightOptions"
          label="Height"
          :accent="accent"
          @update:model-value="(v) => updateSelect('height', v)"
        />
        <Select
          :model-value="strVal('textAlignment', 'center')"
          :options="textAlignOptions"
          label="Text Alignment"
          :accent="accent"
          @update:model-value="(v) => updateSelect('textAlignment', v)"
        />
        <Select
          :model-value="strVal('overlay', 'dark')"
          :options="overlayOptions"
          label="Overlay"
          :accent="accent"
          @update:model-value="(v) => updateSelect('overlay', v)"
        />
      </div>

      <!--
        Grid Settings: ONLY layout-level controls. Aspect ratio used to live
        here but it's now a per-item concern handled by Item Presentation
        (template.image.aspect) — keeping both controls would create two
        sources of truth and the preview would silently ignore one.
      -->
      <div v-if="block.uiType === 'grid'" class="editor-section">
        <div class="editor-subheading">Grid Settings</div>
        <NumberInput
          :model-value="numVal('columns', 2)"
          label="Columns"
          :min="1"
          :max="4"
          @update:model-value="(v) => updateNumber('columns', v)"
        />
        <Switch
          :model-value="boolVal('showMoreLink')"
          label="Show See-all link"
          :accent="accent"
          @update:model-value="(v: boolean) => update('showMoreLink', v)"
        />
      </div>

      <!-- Carousel -->
      <div v-if="block.uiType === 'carousel'" class="editor-section">
        <div class="editor-subheading">Carousel Settings</div>
        <Select
          :model-value="strVal('itemWidth', 'medium')"
          :options="itemWidthOptions"
          label="Item Width"
          :accent="accent"
          @update:model-value="(v) => updateSelect('itemWidth', v)"
        />
        <Switch
          :model-value="boolVal('showTitle', true)"
          label="Show Title"
          :accent="accent"
          @update:model-value="(v: boolean) => update('showTitle', v)"
        />
      </div>

      <!-- Status Row -->
      <div v-if="block.uiType === 'status-row'" class="editor-section">
        <div class="editor-subheading">Status Row Settings</div>
        <Select
          :model-value="strVal('icon', 'book-open')"
          :options="iconOptions"
          label="Icon"
          :accent="accent"
          @update:model-value="(v) => updateSelect('icon', v)"
        />
        <Select
          :model-value="strVal('dataSource', 'guide-progress')"
          :options="dataSourceOptions"
          label="Data Source"
          :accent="accent"
          @update:model-value="(v) => updateSelect('dataSource', v)"
        />
        <p class="editor-runtime-note">Data source pulls live counts at runtime; the preview shows a placeholder.</p>
        <TextInput
          :model-value="strVal('emptyMessage', 'No items yet')"
          label="Empty Message"
          @update:model-value="(v: string) => updateStr('emptyMessage', v)"
        />
      </div>

      <!-- Banner -->
      <div v-if="block.uiType === 'banner'" class="editor-section">
        <div class="editor-subheading">Banner Settings</div>
        <Select
          :model-value="strVal('style', 'filled')"
          :options="bannerStyleOptions"
          label="Style"
          :accent="accent"
          @update:model-value="(v) => updateSelect('style', v)"
        />
        <TextInput
          :model-value="strVal('actionLabel')"
          label="Action Label"
          placeholder="e.g. Learn More"
          @update:model-value="(v: string) => updateStr('actionLabel', v)"
        />
        <TextInput
          :model-value="strVal('actionRoute')"
          label="Action Route"
          placeholder="e.g. /guides/intro"
          @update:model-value="(v: string) => updateStr('actionRoute', v)"
        />
        <p class="editor-runtime-note">Route is used at runtime; not shown in preview.</p>
        <Switch
          :model-value="boolVal('dismissible')"
          label="Dismissible"
          :accent="accent"
          @update:model-value="(v: boolean) => update('dismissible', v)"
        />
      </div>

      <!-- Section Header -->
      <div v-if="block.uiType === 'section-header'" class="editor-section">
        <div class="editor-subheading">Section Header Settings</div>
        <Select
          :model-value="strVal('style', 'large')"
          :options="sectionStyleOptions"
          label="Style"
          :accent="accent"
          @update:model-value="(v) => updateSelect('style', v)"
        />
        <Switch
          :model-value="boolVal('showDivider', true)"
          label="Show Divider"
          :accent="accent"
          @update:model-value="(v: boolean) => update('showDivider', v)"
        />
      </div>

      <!--
        Compact List has no container-level config of its own. Thumbnail and
        Progress are both controlled by Item Presentation (template.image
        and template.progress); a separate Compact List Settings section
        would just be a confusing alternate path to the same fields.
      -->

      <!-- Detail List -->
      <div v-if="block.uiType === 'detail-list'" class="editor-section">
        <div class="editor-subheading">Detail List Settings</div>
        <Select
          :model-value="strVal('density', 'comfortable')"
          :options="detailListDensityOptions"
          label="Density"
          :accent="accent"
          @update:model-value="(v) => updateSelect('density', v)"
        />
        <Switch
          :model-value="boolVal('showDividers', true)"
          label="Show Dividers"
          :accent="accent"
          @update:model-value="(v: boolean) => update('showDividers', v)"
        />
      </div>

      <!-- Stack -->
      <div v-if="block.uiType === 'stack'" class="editor-section">
        <div class="editor-subheading">Stack Settings</div>
        <Select
          :model-value="strVal('spacing', 'medium')"
          :options="stackSpacingOptions"
          label="Spacing"
          :accent="accent"
          @update:model-value="(v) => updateSelect('spacing', v)"
        />
        <Switch
          :model-value="boolVal('fullBleed', true)"
          label="Full Bleed"
          :accent="accent"
          @update:model-value="(v: boolean) => update('fullBleed', v)"
        />
      </div>

      <!-- Masonry -->
      <div v-if="block.uiType === 'masonry'" class="editor-section">
        <div class="editor-subheading">Masonry Settings</div>
        <NumberInput
          :model-value="numVal('columns', 2)"
          label="Columns"
          :min="1"
          :max="4"
          @update:model-value="(v) => updateNumber('columns', v)"
        />
        <Select
          :model-value="strVal('gap', 'medium')"
          :options="masonryGapOptions"
          label="Gap"
          :accent="accent"
          @update:model-value="(v) => updateSelect('gap', v)"
        />
      </div>

      <!-- Hero Rail -->
      <div v-if="block.uiType === 'hero-rail'" class="editor-section">
        <div class="editor-subheading">Hero + Rail Settings</div>
        <Select
          :model-value="strVal('railItemWidth', 'medium')"
          :options="heroRailItemWidthOptions"
          label="Rail Item Width"
          :accent="accent"
          @update:model-value="(v) => updateSelect('railItemWidth', v)"
        />
        <Switch
          :model-value="boolVal('showRailTitle', true)"
          label="Show Rail Title"
          :accent="accent"
          @update:model-value="(v: boolean) => update('showRailTitle', v)"
        />
      </div>

      <!-- Content (only for blocks that link to a real metadata/collection;
           structural blocks like banner/status-row/section-header have no
           content to manage — their `ui` config is the whole payload) -->
      <div v-if="block.bindsToContent" class="editor-section">
        <div class="editor-subheading">Content</div>
        <div class="content-row">
          <span class="content-name">{{ block.name }}</span>
          <span v-if="block.childCount > 0" class="content-count">{{ block.childCount }} items</span>
        </div>
        <div class="content-actions">
          <Button size="sm" @click="emit('change-content', block.id)">
            Change Content
          </Button>
          <Button
            v-if="blockCanDrill(block.uiType, block.isCollection)"
            size="sm"
            :accent="accent"
            @click="emit('drill-down', block.id)"
          >
            Edit Children
          </Button>
        </div>
      </div>

      <!-- Images (read-only, from source item's relationships) -->
      <div v-if="block.featuredImageUrl || block.squareImageUrl" class="editor-section">
        <div class="editor-subheading">Images</div>
        <div v-if="block.featuredImageUrl" class="image-slot">
          <span class="image-label">Featured (16:9)</span>
          <img
            :src="block.featuredImageUrl"
            class="image-thumb landscape"
            alt="Featured"
          >
        </div>
        <div v-if="block.squareImageUrl" class="image-slot">
          <span class="image-label">Square (1:1)</span>
          <img
            :src="block.squareImageUrl"
            class="image-thumb square"
            alt="Square"
          >
        </div>
      </div>

      <!--
        Item Presentation for a container block, edited in place at root.
        Without this, the user has to drill into the container just to
        toggle Action or pick a Variant — even though the preview right
        next to them already shows the items they want to control. The
        section is added below the block's own settings so the user's
        mental model is "this block's settings, then how its items render."
      -->
      <div
        v-if="childEffectiveTemplate"
        class="editor-section editor-section--template editor-section--child-template"
      >
        <LibraryItemTemplateEditor
          :key="`child-${block.id}`"
          :parent-ui-type="block.uiType"
          :template="childEffectiveTemplate.template"
          :source="childEffectiveTemplate.source"
          :has-binding-override="childHasBindingOverride"
          :accent="accent"
          @update-template="onTemplateUpdate"
          @clear-binding-override="onClearBindingOverride"
          @lift-binding-to-collection="onLiftToCollection"
        />
      </div>
    </template>

    <div v-else class="editor-empty">
      <Icon name="inspect" :size="24" color="var(--fg-3)" />
      <p>Select a block to edit its properties</p>
    </div>
  </div>
</template>

<style scoped>
.editor {
  padding: 14px;
}

.editor-heading {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--fg-3);
  margin-bottom: 16px;
}

.editor-subheading {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-2);
  margin-bottom: 8px;
}

.editor-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}

.editor-section:last-child {
  border-bottom: none;
}

.editor-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 48px 24px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

/* Hint text shown when no item is selected in the drilled view. Quiet,
 * unbordered — it's just a one-line nudge, not a content card. */
.editor-hint {
  padding: 8px 4px;
  font-size: 11.5px;
  color: var(--fg-3);
  line-height: 1.4;
  font-style: italic;
}

/* Inline note attached to a field that doesn't visually affect the preview
 * (route URLs, data-source identifiers). Sits directly under the input. */
.editor-runtime-note {
  margin: -4px 0 4px;
  font-size: 11px;
  color: var(--fg-3);
  font-style: italic;
  line-height: 1.4;
}

/* The Item Presentation editor draws its own internal dividers (Scope
 * section has a border-top). The base .editor-section's border-bottom +
 * padding-bottom would stack a redundant "funky line" between it and the
 * next section. Strip the border + padding; keep margin-bottom so the
 * "Item" section below has breathing room. */
.editor-section--template {
  border-bottom: none;
  padding-bottom: 0;
}
/* When Item Presentation is rendered at the bottom of a root-level
 * container block's editor (NOT in drilled view), gain a top border so it
 * visually separates from the block's own settings above. */
.editor-section--child-template {
  border-top: 1px solid var(--line);
  padding-top: 16px;
  margin-top: -4px;
}

.content-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.content-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.health-callout {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
  border-radius: var(--r-sm);
  background: color-mix(in oklch, var(--warn, #e3b341) 8%, transparent);
  border: 1px solid color-mix(in oklch, var(--warn, #e3b341) 25%, transparent);
}
.health-issue {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 12px;
  color: var(--fg-1);
}
.health-message { line-height: 1.4; }
.health-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  color: var(--fg-2);
  text-decoration: none;
  align-self: flex-start;
  padding-top: 2px;
}
.health-action:hover { color: var(--fg-0); }

.content-name {
  font-size: 13px;
  color: var(--fg-1);
}

.content-count {
  font-size: 11px;
  color: var(--fg-3);
  background: color-mix(in oklch, var(--fg-3) 10%, transparent);
  padding: 2px 6px;
  border-radius: var(--r-xs);
}

.image-slot {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.image-label {
  font-size: 12px;
  color: var(--fg-3);
}

.image-thumb-wrap {
  position: relative;
  display: inline-block;
}

.image-thumb {
  border-radius: var(--r-sm);
  object-fit: cover;
}

.image-thumb.landscape {
  width: 100%;
  aspect-ratio: 16 / 9;
}

.image-thumb.square {
  width: 80px;
  height: 80px;
}

</style>
