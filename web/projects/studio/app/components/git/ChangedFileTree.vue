<script setup lang="ts">
import ChangedFileTreeNode from './ChangedFileTreeNode.vue'
import { buildChangedFileTree, changedFileParentPaths, type ChangedFileTreeEntry } from '~/utils/gitChangedFileTree'

const props = defineProps<{
  files: ChangedFileTreeEntry[]
  selectedPath: string
}>()

const emit = defineEmits<{
  select: [path: string]
}>()

const { accent } = useCurrentSubsystem()
const collapsedDirectoryPaths = ref<Set<string>>(new Set())
const tree = computed(() => buildChangedFileTree(props.files))

function toggleDirectory(path: string) {
  const next = new Set(collapsedDirectoryPaths.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  collapsedDirectoryPaths.value = next
}

function expandSelectedAncestors(path: string) {
  const ancestors = changedFileParentPaths(path)
  if (!ancestors.some(ancestor => collapsedDirectoryPaths.value.has(ancestor))) return
  collapsedDirectoryPaths.value = new Set(
    Array.from(collapsedDirectoryPaths.value).filter(directory => !ancestors.includes(directory)),
  )
}

function selectFile(path: string) {
  expandSelectedAncestors(path)
  emit('select', path)
}

watch(() => props.selectedPath, expandSelectedAncestors, { immediate: true })
</script>

<template>
  <ul
    class="changed-file-tree"
    role="tree"
    aria-label="Changed file tree"
    :style="{ '--changed-file-tree-accent': accent }"
  >
    <ChangedFileTreeNode
      v-for="node in tree"
      :key="`${node.kind}:${node.path}`"
      :node="node"
      :level="0"
      :selected-path="selectedPath"
      :collapsed-directory-paths="collapsedDirectoryPaths"
      @select="selectFile"
      @toggle-directory="toggleDirectory"
    />
  </ul>
</template>

<style scoped>
.changed-file-tree {
  flex: 1;
  min-width: 0;
  min-height: 0;
  margin: 0;
  padding: 0 6px 8px;
  overflow-y: auto;
  scrollbar-gutter: stable;
  list-style: none;
}
</style>
