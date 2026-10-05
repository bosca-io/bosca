<script setup lang="ts">
import { computed } from 'vue'
import type { LibraryBlock, LibraryBreadcrumbItem, LibraryTreeNavigateParentInfo } from '~/composables/useLibraryBuilder'
import { blockCanDrill } from './library-utils'

const props = defineProps<{
  id: string
  name: string
  isCollection: boolean
  // Number of items in this node's underlying collection (0 for Metadata or
  // empty collections). The outline mirrors the real content tree, so a node
  // is expandable whenever it's a Collection with children — regardless of its
  // uiType. A Collection tile (e.g. "test") shown inside a Grid still has its
  // own children worth browsing, even though it isn't a container layout.
  childCount: number
  // The block's uiType (or undefined for the root node, which has no type).
  // Used to build a child's drill context when navigating to edit it.
  uiType: string | undefined
  // True if this node is the root library, which is always expandable.
  isRoot: boolean
  depth: number
  ancestors: LibraryBreadcrumbItem[]
  currentId: string
  accent: string
  isExpanded: boolean
  isFetching: boolean
  children: LibraryBlock[] | null
  childrenFor: (id: string) => LibraryBlock[] | null
  isExpandedFn: (id: string) => boolean
  isFetchingFn: (id: string) => boolean
}>()

const emit = defineEmits<{
  toggle: [id: string]
  // Root-only: make the root library the canvas view. The root isn't a child
  // block of anything, so it can't be "selected" for editing — clicking it
  // just resets the canvas to the top level.
  navigate: [id: string, path: LibraryBreadcrumbItem[]]
  // Select an outline node for editing. The clicked node (`selectId`) lives
  // inside `parentId`; `path` is the breadcrumb that makes `parentId` the
  // canvas view, and `parentInfo` describes `parentId`'s own binding so that
  // view gets a drill context (the ItemTemplate editor). `parentInfo` is
  // filled in by whichever ancestor has `parentId` as a DIRECT child (only
  // that ancestor holds `parentId`'s full block object); every other node
  // forwards it unchanged.
  select: [
    selectId: string,
    parentId: string,
    path: LibraryBreadcrumbItem[],
    parentInfo?: LibraryTreeNavigateParentInfo,
  ]
}>()

const isCurrent = computed(() => props.id === props.currentId)
// Expandable iff this is the root or a Collection that actually has children.
// This mirrors the real content hierarchy in the outline: any collection with
// items can be opened, not just container-typed blocks. Metadata and empty
// collections stay leaves (nothing to reveal).
const canExpand = computed(() => props.isRoot || (props.isCollection && props.childCount > 0))
const indentPx = computed(() => props.depth * 14)

// Ancestors to pass to MY children = my ancestors + me.
const childAncestors = computed<LibraryBreadcrumbItem[]>(() =>
  [...props.ancestors, { id: props.id, name: props.name }],
)

// Describe a DIRECT child as a binding within this node's collection — the
// shape the page needs to build a drill context for that child's view. Only
// the parent holds the child's full block object (uiConfig, template
// overrides), which is why this is built here rather than by the child itself.
function buildParentInfo(child: LibraryBlock): LibraryTreeNavigateParentInfo {
  return {
    parentCollectionId: props.id,
    parentBindingBlockId: child.id,
    parentUiType: child.uiType,
    parentUiConfig: { ...child.uiConfig },
    parentIsContainer: blockCanDrill(child.uiType, child.isCollection),
    bindingOverride: child.bindingItemTemplateOverride,
    childCollectionTemplate: child.collectionItemTemplate,
  }
}

function onRowClick() {
  if (props.isRoot) {
    emit('navigate', props.id, props.ancestors)
    return
  }
  // To edit this node, its parent collection must become the canvas view. The
  // immediate parent is the last ancestor; the path to it is everything above.
  const parent = props.ancestors[props.ancestors.length - 1]
  if (!parent) return
  emit('select', props.id, parent.id, props.ancestors.slice(0, -1), undefined)
}
</script>

<template>
  <li class="row">
    <div
      class="row-inner"
      :class="{ 'row-inner--current': isCurrent }"
      :style="{ paddingLeft: `${indentPx}px`, ...(isCurrent ? { color: accent } : {}) }"
    >
      <button
        v-if="canExpand"
        class="row-toggle"
        :aria-expanded="isExpanded"
        @click.stop="emit('toggle', id)"
      >
        <Icon
          v-if="isFetching"
          name="loader"
          :size="12"
          color="currentColor"
          class="spin"
        />
        <Icon
          v-else
          :name="isExpanded ? 'chevron-down' : 'chevron-right'"
          :size="12"
          color="currentColor"
        />
      </button>
      <span v-else class="row-toggle-spacer" />
      <button
        class="row-label"
        @click="onRowClick"
      >
        {{ name || '(untitled)' }}
      </button>
    </div>
    <ul v-if="isExpanded && children && children.length" class="children">
      <LibraryTreeNode
        v-for="child in children"
        :id="child.id"
        :key="child.id"
        :name="child.name"
        :is-collection="child.isCollection"
        :child-count="child.childCount"
        :ui-type="child.uiType"
        :is-root="false"
        :depth="depth + 1"
        :ancestors="childAncestors"
        :current-id="currentId"
        :accent="accent"
        :is-expanded="isExpandedFn(child.id)"
        :is-fetching="isFetchingFn(child.id)"
        :children="childrenFor(child.id)"
        :children-for="childrenFor"
        :is-expanded-fn="isExpandedFn"
        :is-fetching-fn="isFetchingFn"
        @toggle="(cid) => emit('toggle', cid)"
        @select="(sid, pid, path, parentInfo) => emit(
          'select',
          sid,
          pid,
          path,
          // Fill the drill context only when THIS node's direct child is the
          // navigation target (`pid`) — that's the one ancestor holding the
          // target's block object. Otherwise forward unchanged so a deeper
          // node's parent fills it.
          parentInfo ?? (child.id === pid ? buildParentInfo(child) : undefined),
        )"
      />
    </ul>
  </li>
</template>

<style scoped>
.row { list-style: none; }
.row-inner {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 4px 6px;
  border-radius: var(--r-xs);
  transition: background 0.12s;
}
.row-inner:hover { background: color-mix(in oklch, var(--fg-3) 8%, transparent); }
.row-inner--current {
  background: color-mix(in oklch, var(--brand-2) 6%, transparent);
  font-weight: 500;
}

.row-toggle {
  all: unset;
  cursor: pointer;
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-xs);
  color: var(--fg-3);
  flex-shrink: 0;
}
.row-toggle:hover { color: var(--fg-1); }
.row-toggle-spacer {
  display: inline-block;
  width: 18px;
  flex-shrink: 0;
}

.row-label {
  all: unset;
  cursor: pointer;
  flex: 1;
  padding: 0 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--fg-1);
}
.row-inner--current .row-label { color: inherit; }

.children {
  list-style: none;
  padding: 0;
  margin: 0;
}

.spin {
  animation: spin 0.9s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
