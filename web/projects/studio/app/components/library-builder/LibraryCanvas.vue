<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick, watch } from 'vue'
import Sortable from 'sortablejs'
import {
  RENDER_AS_ITEM,
  blockBindsToContent,
  blockCanDrill,
  blockTypeIcon,
  blockTypeLabel,
  childRenderAsValue,
  computeBlockHealth,
} from './library-utils'
import type { ItemTemplate, TemplateSource } from './library-utils'

// The canvas IS the structure surface — it shows the order of blocks/items
// and lets the user reorder, select, and drill. It NEVER hosts settings
// editors (the Item Presentation editor used to live here; it now lives in
// the right Properties panel where every other "settings" surface is).
// drillContext + effectiveTemplate are still received so the canvas can
// render items via LibraryItemPreview, but it does not surface them as
// editors here.

interface CanvasBlock {
  id: string
  name: string
  uiType: string
  isCollection: boolean
  childCount: number
  featuredImageUrl: string | null
  squareImageUrl: string | null
  // Per-item presentation override (content view), so an item the user has
  // customised renders its own way in the canvas peek instead of the
  // collection default. Optional — absent for the page/drilled shapes.
  bindingItemTemplateOverride?: ItemTemplate | null
}

const props = defineProps<{
  blocks: CanvasBlock[]
  selectedIdx: number | null
  breadcrumb: Array<{ id: string; name: string }>
  libraryName: string
  accent: string
  // When set, the canvas is inside a container's child collection. The pinned
  // ItemTemplate editor renders at the top and items render through
  // LibraryItemPreview using `effectiveTemplate`. When null, the canvas
  // renders its flat list (the original behavior, used at root and for tree
  // navigation without parent context).
  drillContext: {
    parentUiType: string
    parentBindingBlockId: string
  } | null
  effectiveTemplate: { template: ItemTemplate; source: TemplateSource } | null
  // When true, the current view is a content collection: render each child as a
  // content item (using `contentTemplate`, the collection's presentation) with a
  // "Render as" tag, instead of a flat block row. A child overridden to a
  // container shows as a section chip rather than an item peek.
  contentView: boolean
  contentTemplate: { template: ItemTemplate; source: TemplateSource } | null
}>()

function blockIssues(block: CanvasBlock) {
  return computeBlockHealth(block)
}

// A content-view child renders as a plain ITEM unless it's been overridden to a
// container layout (carousel/grid/…), which expands its contents inline.
function isRenderedAsItem(block: CanvasBlock): boolean {
  return childRenderAsValue(block.uiType) === RENDER_AS_ITEM
}

// "Item", or the container label (e.g. "Carousel") when overridden.
function renderAsLabel(block: CanvasBlock): string {
  return isRenderedAsItem(block) ? 'Item' : blockTypeLabel(block.uiType)
}

// The template a content item renders with: its own per-item override when set,
// otherwise the collection's presentation.
function contentItemTemplate(block: CanvasBlock): ItemTemplate | null {
  if (!props.contentTemplate) return null
  return block.bindingItemTemplateOverride ?? props.contentTemplate.template
}

// Health for a content child: an item is always valid (no "no UI config"
// noise); only a container override with no items is worth warning about.
function contentIssues(block: CanvasBlock) {
  if (isRenderedAsItem(block)) return []
  return computeBlockHealth(block)
}

const emit = defineEmits<{
  select: [index: number]
  remove: [index: number]
  reorder: [from: number, to: number]
  // `index` is the desired insertion index when the user drags from the
  // palette; undefined for plain palette/empty-canvas clicks (append).
  'add-block': [type: string, index?: number]
  'drill-down': [index: number]
  'navigate-up': [index: number]
}>()

const topContainer = ref<HTMLElement | null>(null)
let sortableInstances: Sortable[] = []

function cleanupSortables() {
  sortableInstances.forEach(s => s.destroy())
  sortableInstances = []
}

// Sortable mutates the DOM directly; Vue then re-renders authoritatively from
// `blocks`. We undo Sortable's mutation here so the two systems don't fight
// over node ordering during the reactive update.
function revertDom(evt: Sortable.SortableEvent, oldIndex: number) {
  evt.item.remove()
  const ref = evt.from.children[oldIndex]
  if (ref) evt.from.insertBefore(evt.item, ref)
  else evt.from.appendChild(evt.item)
}

function handleDragEnd(evt: Sortable.SortableEvent) {
  const oldIndex = evt.oldIndex
  const newIndex = evt.newIndex
  if (oldIndex === undefined || newIndex === undefined) return
  if (oldIndex === newIndex) return
  revertDom(evt, oldIndex)
  emit('reorder', oldIndex, newIndex)
}

function handleAdd(evt: Sortable.SortableEvent) {
  const el = evt.item
  const blockType = el.getAttribute('data-block-type')
  // Sortable inserts the dragged element into the DOM; we remove it because
  // Vue is the authoritative renderer. `newIndex` is where the user actually
  // dropped — pass it up so the new block lands there, not always at the end.
  const dropIndex = evt.newIndex
  el.remove()
  if (blockType) {
    emit('add-block', blockType, dropIndex)
  }
}

function initSortables() {
  cleanupSortables()

  // Canvas is a drop target for the palette, never a source for foreign groups.
  // `pull: false` keeps users from dragging items out into the palette where
  // they'd visually disappear until the next reactive re-render.
  const groupConfig = { name: 'library-blocks', pull: false as const, put: true as const }

  if (topContainer.value) {
    sortableInstances.push(
      Sortable.create(topContainer.value, {
        group: groupConfig,
        handle: '.drag-handle',
        animation: 150,
        ghostClass: 'canvas-ghost',
        emptyInsertThreshold: 50,
        onEnd: handleDragEnd,
        onAdd: handleAdd,
      }),
    )
  }
}

onMounted(() => nextTick(initSortables))
onUnmounted(cleanupSortables)
watch(() => props.blocks, () => nextTick(initSortables), { deep: true })

</script>

<template>
  <div class="canvas">
    <!--
      Breadcrumb only appears when the user has drilled in. At root the
      PageHeader already shows the library name, so a breadcrumb-of-one
      would just be a redundant non-clickable label.
    -->
    <div v-if="breadcrumb.length > 0" class="breadcrumb">
      <template v-for="(crumb, i) in breadcrumb" :key="crumb.id">
        <button class="breadcrumb-item" @click="emit('navigate-up', i)">
          {{ crumb.name || 'Library' }}
        </button>
        <Icon name="chevronRight" :size="12" color="var(--fg-3)" />
      </template>
      <span class="breadcrumb-current">{{ libraryName || 'Library' }}</span>
    </div>

    <div ref="topContainer" class="canvas-nodes">
      <div
        v-if="blocks.length === 0"
        class="canvas-empty"
      >
        Drag blocks from the palette or click to add
      </div>

      <template v-else>
        <div
          v-for="(block, i) in blocks"
          :key="block.id"
        >
          <!--
            In a drilled-down container view, each item renders through
            LibraryItemPreview using the effective template so the canvas
            actually shows what the parent will show — not a flat list.
            The preview is constrained to a sensible peek-width (≈ half a
            phone) so tile/card variants don't blow up to the full canvas;
            row/detail-row variants stretch naturally to that same width.
            We wrap it with the same selection / drag / drill / delete chrome.
            Outside a drill context we fall back to the original compact row.
          -->
          <div
            v-if="drillContext && effectiveTemplate && !contentView"
            class="canvas-item"
            :class="{ selected: selectedIdx === i }"
            :style="selectedIdx === i ? { borderColor: accent } : {}"
            @click="emit('select', i)"
          >
            <span class="drag-handle drag-handle--item">
              <Icon name="grip" :size="14" color="var(--fg-4)" />
            </span>
            <div class="canvas-item-render">
              <LibraryItemPreview
                :item="{
                  id: block.id,
                  name: block.name,
                  imageUrl: block.featuredImageUrl ?? block.squareImageUrl,
                }"
                :template="effectiveTemplate.template"
                :selected="selectedIdx === i"
              />
            </div>
            <div class="canvas-item-meta">
              <span class="canvas-item-label">{{ block.name }}</span>
              <span class="canvas-item-variant">{{ effectiveTemplate.template.variant }}</span>
            </div>
            <div class="canvas-item-actions">
              <span
                v-if="blockIssues(block).length"
                class="health-marker"
                :title="blockIssues(block).map(i => i.message).join('\n')"
              >
                <Icon name="alert" :size="13" color="var(--warn, #e3b341)" />
              </span>
              <button
                v-if="blockCanDrill(block.uiType, block.isCollection)"
                class="canvas-drill"
                title="Edit children"
                @click.stop="emit('drill-down', i)"
              >
                <Icon name="chevron-right" :size="14" color="var(--fg-3)" />
              </button>
              <button class="canvas-delete" @click.stop="emit('remove', i)">
                <Icon name="trash" :size="12" color="var(--err)" />
              </button>
            </div>
          </div>

          <!--
            CONTENT VIEW: each child is a content item. Items render a peek
            through the collection's presentation; a child overridden to a
            container shows a section chip ("Carousel") instead. The right
            "Render as" tag names the override. No UNKNOWN badge, no
            "no UI configuration" warning — content items don't carry a block
            type of their own.
          -->
          <div
            v-else-if="contentView && contentTemplate"
            class="canvas-item"
            :class="{ selected: selectedIdx === i }"
            :style="selectedIdx === i ? { borderColor: accent } : {}"
            @click="emit('select', i)"
          >
            <span class="drag-handle drag-handle--item">
              <Icon name="grip" :size="14" color="var(--fg-4)" />
            </span>
            <div class="canvas-item-render">
              <LibraryItemPreview
                v-if="isRenderedAsItem(block) && contentItemTemplate(block)"
                :item="{
                  id: block.id,
                  name: block.name,
                  imageUrl: block.featuredImageUrl ?? block.squareImageUrl,
                }"
                :template="contentItemTemplate(block)!"
                :selected="selectedIdx === i"
              />
              <div v-else class="canvas-section-chip">
                <Icon :name="blockTypeIcon(block.uiType)" :size="18" color="var(--fg-3)" />
              </div>
            </div>
            <div class="canvas-item-meta">
              <span class="canvas-item-label">{{ block.name }}</span>
              <span class="canvas-item-variant">Render as {{ renderAsLabel(block) }}</span>
            </div>
            <div class="canvas-item-actions">
              <span
                v-if="contentIssues(block).length"
                class="health-marker"
                :title="contentIssues(block).map(i => i.message).join('\n')"
              >
                <Icon name="alert" :size="13" color="var(--warn, #e3b341)" />
              </span>
              <button
                v-if="blockCanDrill(block.uiType, block.isCollection)"
                class="canvas-drill"
                title="Edit this collection"
                @click.stop="emit('drill-down', i)"
              >
                <Icon name="chevron-right" :size="14" color="var(--fg-3)" />
              </button>
              <button class="canvas-delete" @click.stop="emit('remove', i)">
                <Icon name="trash" :size="12" color="var(--err)" />
              </button>
            </div>
          </div>

          <div
            v-else
            class="canvas-leaf"
            :class="{
              selected: selectedIdx === i,
              'canvas-leaf--structural': !blockBindsToContent(block.uiType),
            }"
            :style="selectedIdx === i ? { borderColor: accent } : {}"
            @click="emit('select', i)"
          >
            <span class="drag-handle">
              <Icon name="grip" :size="14" color="var(--fg-4)" />
            </span>
            <span
              class="kind-marker"
              :title="blockBindsToContent(block.uiType) ? 'Linked to content' : 'Structural block'"
            >
              <Icon
                :name="blockBindsToContent(block.uiType) ? 'link' : 'puzzle'"
                :size="12"
                color="var(--fg-4)"
              />
            </span>
            <span class="canvas-node-label">
              <span class="node-type">{{ block.uiType }}</span>
              {{ block.name }}
            </span>
            <span
              v-if="blockIssues(block).length"
              class="health-marker"
              :title="blockIssues(block).map(i => i.message).join('\n')"
            >
              <Icon name="alert" :size="13" color="var(--warn, #e3b341)" />
            </span>
            <span v-if="block.childCount > 0" class="child-badge">{{ block.childCount }}</span>
            <button
              v-if="blockCanDrill(block.uiType, block.isCollection)"
              class="canvas-drill"
              title="Edit children"
              @click.stop="emit('drill-down', i)"
            >
              <Icon name="chevronRight" :size="14" color="var(--fg-3)" />
            </button>
            <button class="canvas-delete" @click.stop="emit('remove', i)">
              <Icon name="trash" :size="12" color="var(--err)" />
            </button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.canvas {
  min-height: 400px;
  padding: 14px;
}

.breadcrumb {
  display: flex; align-items: center; gap: 4px; margin-bottom: 14px; flex-shrink: 0;
}
.breadcrumb-item {
  all: unset; cursor: pointer; font-size: 13px; color: var(--fg-2);
  padding: 2px 6px; border-radius: var(--r-xs); transition: color 0.15s, background 0.15s;
}
.breadcrumb-item:hover { color: var(--fg-0); background: color-mix(in oklch, var(--fg-3) 8%, transparent); }
.breadcrumb-current {
  font-size: 13px; font-weight: 500; color: var(--fg-0);
  padding: 2px 6px;
}

.canvas-nodes {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 200px;
}

.canvas-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  color: var(--fg-3);
  font-size: 13px;
  border: 2px dashed var(--line);
  border-radius: var(--r-md);
  padding: 32px;
}

.canvas-leaf {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  cursor: pointer;
  font-size: 13px;
  transition: border-color 0.15s;
}

.canvas-leaf:hover { border-color: var(--fg-4); }

.canvas-leaf.selected {
  background: color-mix(in oklch, var(--brand-2) 6%, transparent);
}

/* Structural blocks (banner, status-row, section-header) carry only ui config,
 * no linked content — a dashed left border keeps that distinction visible
 * without screaming for attention. */
.canvas-leaf--structural {
  border-left: 2px dashed color-mix(in oklch, var(--fg-3) 50%, transparent);
}

/* Drilled-down item view — the LibraryItemPreview shows a real peek of how
 * the parent will render this child; the meta block names the item and tags
 * its variant; the action chrome on the right holds drag/health/drill/delete.
 * The wrapper is the source of selection and click-targets.
 *
 * canvas-item-render is hard-capped to a peek width so tile/card variants
 * don't fill the canvas. Row/detail-row variants stretch to that width too —
 * the cap is intentional, the canvas is not a full client render, the floating
 * preview is. */
.canvas-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.canvas-item:hover { border-color: var(--fg-4); }
.canvas-item.selected {
  background: color-mix(in oklch, var(--brand-2) 6%, transparent);
}
.canvas-item-render {
  width: 200px;
  flex-shrink: 0;
  min-width: 0;
}
/* Section chip — stands in for a child overridden to a container layout
 * (carousel/grid/…). The child expands inline in the real app; here we show a
 * compact placeholder with the layout's icon so it reads as "a section", not a
 * tile. */
.canvas-section-chip {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 56px;
  border-radius: var(--r-sm);
  border: 1px dashed color-mix(in oklch, var(--fg-3) 35%, transparent);
  background: color-mix(in oklch, var(--fg-3) 5%, transparent);
}
.canvas-item-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.canvas-item-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.canvas-item-variant {
  font-size: 10.5px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--fg-3);
}
.canvas-item-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.drag-handle--item {
  align-self: center;
}

.kind-marker {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  opacity: 0.7;
}

.health-marker {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  cursor: help;
}

.canvas-node-label {
  flex: 1;
  font-weight: 500;
  color: var(--fg-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-type {
  font-size: 10.5px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--fg-3);
  margin-right: 6px;
}

.child-badge {
  font-size: 11px; font-weight: 600; color: var(--fg-3);
  background: color-mix(in oklch, var(--fg-3) 12%, transparent);
  padding: 2px 7px; border-radius: var(--r-xs);
}

.drag-handle {
  cursor: move;
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

.canvas-drill {
  flex-shrink: 0; padding: 4px; border-radius: var(--r-xs);
  opacity: 0.5; transition: opacity 0.12s, background 0.12s; cursor: pointer;
}
.canvas-leaf:hover .canvas-drill,
.canvas-item:hover .canvas-drill { opacity: 1; }
.canvas-drill:hover { background: color-mix(in oklch, var(--fg-3) 12%, transparent); }

.canvas-delete {
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.12s;
  padding: 4px;
  border-radius: var(--r-xs);
}

.canvas-leaf:hover .canvas-delete,
.canvas-item:hover .canvas-delete { opacity: 1; }
.canvas-delete:hover { background: color-mix(in oklch, var(--err) 12%, transparent); }

.canvas-ghost { opacity: 0.3; }
</style>
