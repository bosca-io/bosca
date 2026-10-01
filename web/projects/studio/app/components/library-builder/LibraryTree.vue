<script setup lang="ts">
import { ref, watch } from 'vue'
import type {
  LibraryBlock,
  LibraryBreadcrumbItem,
  LibraryTreeNavigateParentInfo,
} from '~/composables/useLibraryBuilder'

const props = defineProps<{
  rootId: string
  rootName: string
  currentId: string
  // The live `blocks` from the active view. The tree reads these directly for
  // whichever node is currently being viewed, so external changes to the
  // current collection auto-flow into the tree without any cache invalidation.
  currentBlocks: LibraryBlock[]
  // Lazy children fetch for any node that's NOT the current view. Returns a
  // fresh fetch every call — no internal cache, the tree component handles
  // session-scoped memoization.
  fetchChildren: (id: string) => Promise<LibraryBlock[]>
  // Bumped by the composable on every collection-change event. The tree reads
  // `changedId` and invalidates its own cache entry so the next expand will
  // refetch fresh data instead of showing stale children.
  changedId: string | null
  changedTick: number
  accent: string
}>()

const emit = defineEmits<{
  // Root-only: reset the canvas to the top-level library view.
  navigate: [id: string, path: LibraryBreadcrumbItem[]]
  // Select a node for editing — see LibraryTreeNode for the payload contract.
  select: [
    selectId: string,
    parentId: string,
    path: LibraryBreadcrumbItem[],
    parentInfo?: LibraryTreeNavigateParentInfo,
  ]
}>()

const expanded = ref<Set<string>>(new Set([props.rootId]))
const childrenById = ref<Map<string, LibraryBlock[]>>(new Map())
const fetchingIds = ref<Set<string>>(new Set())

// When the user navigates to a different collection, the prior current's
// children would disappear from the tree because they only existed in
// `currentBlocks` (props), not in our `childrenById` map. Snapshot the
// outgoing pair into the map so the old node keeps rendering its children
// in the tree even while the user is somewhere else.
watch(
  [() => props.currentId, () => props.currentBlocks],
  ([newId], [oldId, oldBlocks]) => {
    if (!oldId || oldId === newId || !oldBlocks) return
    childrenById.value.set(oldId, oldBlocks)
    childrenById.value = new Map(childrenById.value)
  },
  { flush: 'sync' },
)

// Drop our cached entry for any id that the composable says has changed
// externally. The next expand will refetch. We can't tell whether the change
// is an add/edit/delete so we just invalidate; refetching is cheap relative
// to showing stale state.
watch(
  () => props.changedTick,
  () => {
    const id = props.changedId
    if (!id) return
    if (!childrenById.value.has(id)) return
    childrenById.value.delete(id)
    childrenById.value = new Map(childrenById.value)
  },
)

function childrenFor(id: string): LibraryBlock[] | null {
  if (id === props.currentId) return props.currentBlocks
  return childrenById.value.get(id) ?? null
}

function isExpanded(id: string): boolean {
  return expanded.value.has(id)
}

function isFetching(id: string): boolean {
  return fetchingIds.value.has(id)
}

async function toggle(id: string) {
  if (expanded.value.has(id)) {
    expanded.value.delete(id)
    expanded.value = new Set(expanded.value)
    return
  }
  expanded.value.add(id)
  expanded.value = new Set(expanded.value)
  // Lazy-fetch for any node that isn't the current view and hasn't been
  // expanded before in this session.
  if (id !== props.currentId && !childrenById.value.has(id)) {
    fetchingIds.value.add(id)
    fetchingIds.value = new Set(fetchingIds.value)
    try {
      const items = await props.fetchChildren(id)
      childrenById.value.set(id, items)
      childrenById.value = new Map(childrenById.value)
    } finally {
      fetchingIds.value.delete(id)
      fetchingIds.value = new Set(fetchingIds.value)
    }
  }
}

function onNavigate(id: string, path: LibraryBreadcrumbItem[]) {
  emit('navigate', id, path)
}

function onSelect(
  selectId: string,
  parentId: string,
  path: LibraryBreadcrumbItem[],
  parentInfo?: LibraryTreeNavigateParentInfo,
) {
  emit('select', selectId, parentId, path, parentInfo)
}
</script>

<template>
  <div class="tree">
    <div class="tree-heading">Outline</div>
    <ul class="tree-list">
      <LibraryTreeNode
        :id="rootId"
        :name="rootName"
        :is-collection="true"
        :child-count="childrenFor(rootId)?.length ?? 0"
        :ui-type="undefined"
        :is-root="true"
        :depth="0"
        :ancestors="[]"
        :current-id="currentId"
        :accent="accent"
        :is-expanded="isExpanded(rootId)"
        :is-fetching="isFetching(rootId)"
        :children="childrenFor(rootId)"
        :children-for="childrenFor"
        :is-expanded-fn="isExpanded"
        :is-fetching-fn="isFetching"
        @toggle="toggle"
        @navigate="onNavigate"
        @select="onSelect"
      />
    </ul>
  </div>
</template>

<style scoped>
.tree {
  padding: 14px 10px;
  font-size: 12.5px;
}

.tree-heading {
  font-size: 10.5px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--fg-3);
  margin-bottom: 8px;
  padding-left: 4px;
}

.tree-list {
  list-style: none;
  padding: 0;
  margin: 0;
}
</style>
