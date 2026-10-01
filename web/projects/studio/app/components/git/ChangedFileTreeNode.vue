<script setup lang="ts">
import type { ChangedFileTreeNode } from '~/utils/gitChangedFileTree'

defineOptions({ name: 'ChangedFileTreeNode' })

const props = defineProps<{
  node: ChangedFileTreeNode
  level: number
  selectedPath: string
  collapsedDirectoryPaths: ReadonlySet<string>
}>()

const emit = defineEmits<{
  select: [path: string]
  toggleDirectory: [path: string]
}>()

const CHANGE_TYPE_COLORS: Record<string, string> = {
  ADD: '#34d99a',
  DELETE: '#ff5d6c',
  MODIFY: '#ffb547',
  RENAME: '#5ec5ff',
  COPY: '#a78bff',
}

const isExpanded = computed(() => props.node.kind === 'directory' && !props.collapsedDirectoryPaths.has(props.node.path))
const rowStyle = computed(() => ({ paddingInlineStart: `${6 + props.level * 14}px` }))
</script>

<template>
  <li
    class="changed-file-tree-node"
    role="treeitem"
    :aria-expanded="node.kind === 'directory' ? isExpanded : undefined"
    :aria-selected="node.kind === 'file' ? selectedPath === node.path : undefined"
  >
    <button
      v-if="node.kind === 'directory'"
      type="button"
      class="changed-file-directory-row"
      :style="rowStyle"
      :title="node.path"
      :aria-label="`${isExpanded ? 'Collapse' : 'Expand'} ${node.path}`"
      @click="emit('toggleDirectory', node.path)"
    >
      <Icon :name="isExpanded ? 'chevronDown' : 'chevron'" :size="11" color="var(--fg-3)" />
      <Icon :name="isExpanded ? 'folder-open' : 'folder'" :size="13" color="var(--fg-3)" />
      <span class="changed-file-tree-label mono">{{ node.name }}</span>
    </button>

    <button
      v-else
      type="button"
      class="changed-file-link"
      :class="{
        active: selectedPath === node.path,
        viewed: node.entry.viewed,
      }"
      :style="rowStyle"
      :title="node.path"
      :aria-label="node.path"
      @click="emit('select', node.path)"
    >
      <span
        class="changed-file-status"
        :class="{ viewed: node.entry.viewed }"
        :style="{ '--change-color': CHANGE_TYPE_COLORS[node.entry.changeType] || '#6c7388' }"
      >
        <Icon
          v-if="node.entry.viewed"
          name="check"
          :size="10"
          color="#fff" />
      </span>
      <span class="changed-file-tree-label mono">{{ node.name }}</span>
      <span v-if="node.entry.commentCount" class="changed-file-comments mono">
        {{ node.entry.commentCount }}
      </span>
    </button>

    <ul v-if="node.kind === 'directory' && isExpanded" class="changed-file-tree-group" role="group">
      <ChangedFileTreeNode
        v-for="child in node.children"
        :key="`${child.kind}:${child.path}`"
        :node="child"
        :level="level + 1"
        :selected-path="selectedPath"
        :collapsed-directory-paths="collapsedDirectoryPaths"
        @select="emit('select', $event)"
        @toggle-directory="emit('toggleDirectory', $event)"
      />
    </ul>
  </li>
</template>

<style scoped>
.changed-file-tree-node,
.changed-file-tree-group {
  min-width: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.changed-file-directory-row,
.changed-file-link {
  width: 100%;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding-block: 6px;
  padding-inline-end: 6px;
  color: var(--fg-1);
  text-align: left;
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.changed-file-directory-row:hover,
.changed-file-link:hover { background: var(--bg-2); }
.changed-file-directory-row { color: var(--fg-2); }
.changed-file-link.active { background: color-mix(in oklch, var(--changed-file-tree-accent) 10%, transparent); }
.changed-file-link.viewed { color: var(--fg-3); }

.changed-file-tree-label {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
}

.changed-file-status {
  width: 14px;
  height: 14px;
  flex: 0 0 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--change-color);
  border-radius: 50%;
}

.changed-file-status.viewed { background: #34d99a; border-color: #34d99a; }

.changed-file-comments {
  min-width: 18px;
  padding: 1px 4px;
  color: var(--fg-2);
  background: var(--bg-3);
  border-radius: 999px;
  text-align: center;
  font-size: 9px;
}
</style>
