<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick, watch, type ComponentPublicInstance } from 'vue'
import Sortable from 'sortablejs'
import type { UiSchemaNode, RowNode, SectionNode } from '@bosca/forms'
import { buildNodeFromElement } from './palette-utils'

const props = defineProps<{
  layout: UiSchemaNode[]
  selectedPath: number[] | null
  accent: string
}>()

const emit = defineEmits<{
  select: [path: number[]]
  remove: [path: number[]]
  'update:layout': [layout: UiSchemaNode[]]
  'add:node': [node: UiSchemaNode, index: number, parentIndex: number | null]
}>()

const topContainer = ref<HTMLElement | null>(null)
const childContainers = ref<Map<number, HTMLElement>>(new Map())

let sortableInstances: Sortable[] = []

function cleanupSortables() {
  sortableInstances.forEach(s => s.destroy())
  sortableInstances = []
}

function deepClone<T>(obj: T): T {
  return JSON.parse(JSON.stringify(obj))
}

function revertDom(evt: Sortable.SortableEvent) {
  evt.item.remove()
  const ref = evt.from.children[evt.oldIndex!]
  if (ref) {
    evt.from.insertBefore(evt.item, ref)
  } else {
    evt.from.appendChild(evt.item)
  }
}

function handleDragEnd(evt: Sortable.SortableEvent) {
  if (evt.oldIndex === undefined || evt.newIndex === undefined) return

  const fromParent = evt.from.getAttribute('data-parent-index')
  const toParent = evt.to.getAttribute('data-parent-index')
  const oldIndex = evt.oldIndex
  const newIndex = evt.newIndex

  if (fromParent === toParent && oldIndex === newIndex) return

  revertDom(evt)

  const layout = deepClone(props.layout)
  const fromIdx = fromParent !== null ? parseInt(fromParent) : null
  const toIdx = toParent !== null ? parseInt(toParent) : null

  const sourceArray = fromIdx !== null
    ? (layout[fromIdx]! as RowNode | SectionNode).children
    : layout
  const moved = sourceArray.splice(oldIndex, 1)[0]!

  // When moving from root into a container, the splice shifts container indices down by 1 if
  // the removed root node was before the target container. The reverse (container→root) doesn't
  // need adjustment because removing a child doesn't change root-level indices.
  let adjustedToIdx = toIdx
  if (fromIdx === null && toIdx !== null && oldIndex < toIdx) {
    adjustedToIdx = toIdx - 1
  }

  const targetArray = adjustedToIdx !== null
    ? (layout[adjustedToIdx]! as RowNode | SectionNode).children
    : layout

  targetArray.splice(newIndex, 0, moved)
  emit('update:layout', layout)
}

function handleAdd(evt: Sortable.SortableEvent) {
  const el = evt.item
  if (!el.getAttribute('data-palette-type')) return

  const node = buildNodeFromElement(el)
  if (!node) {
    el.remove()
    return
  }

  el.remove()

  const toParent = evt.to.getAttribute('data-parent-index')
  const toIdx = toParent !== null ? parseInt(toParent) : null
  const newIndex = evt.newIndex ?? 0

  emit('add:node', node, newIndex, toIdx)
}

function initSortables() {
  cleanupSortables()

  const groupConfig = { name: 'layout-nodes', pull: true as const, put: true as const }

  if (topContainer.value) {
    sortableInstances.push(
      Sortable.create(topContainer.value, {
        group: groupConfig,
        handle: '.drag-handle',
        animation: 150,
        ghostClass: 'canvas-ghost',
        onEnd: handleDragEnd,
        onAdd: handleAdd,
      }),
    )
  }

  childContainers.value.forEach((el) => {
    if (!el) return
    sortableInstances.push(
      Sortable.create(el, {
        group: groupConfig,
        handle: '.drag-handle',
        animation: 150,
        ghostClass: 'canvas-ghost',
        emptyInsertThreshold: 24,
        onEnd: handleDragEnd,
        onAdd: handleAdd,
      }),
    )
  })
}

function setChildRef(index: number, el: Element | ComponentPublicInstance | null) {
  if (el) {
    childContainers.value.set(index, el as HTMLElement)
  } else {
    childContainers.value.delete(index)
  }
}

onMounted(() => nextTick(initSortables))
onUnmounted(cleanupSortables)

watch(() => props.layout, () => nextTick(initSortables), { deep: true })

function isSelected(path: number[]): boolean {
  if (!props.selectedPath) return false
  return (
    path.length === props.selectedPath.length &&
    path.every((v, i) => v === props.selectedPath![i])
  )
}

function nodeLabel(node: UiSchemaNode): string {
  switch (node.type) {
    case 'section':
      return `Section: ${node.label ?? 'Untitled'}`
    case 'row':
      return `Row (${node.children.length} field${node.children.length !== 1 ? 's' : ''})`
    case 'field':
      return node.label || node.property
    case 'display':
      return node.control
    default:
      return 'Unknown'
  }
}

function nodeIcon(node: UiSchemaNode): string {
  switch (node.type) {
    case 'section': return 'container'
    case 'row': return 'columns'
    case 'field': return 'form'
    case 'display': return 'eye'
    default: return 'info'
  }
}
</script>

<template>
  <div class="canvas">
    <div ref="topContainer" class="canvas-nodes">
      <div
        v-if="layout.length === 0"
        class="canvas-empty"
      >
        Drag controls from the palette or click to add
      </div>

      <template v-else>
        <div v-for="(node, index) in layout" :key="index" :data-node-type="node.type">
          <!-- Container node (section / row) -->
          <template v-if="node.type === 'section' || node.type === 'row'">
            <div
              class="canvas-container"
              :class="{ selected: isSelected([index]) }"
              :style="isSelected([index]) ? { borderColor: accent } : {}"
            >
              <div
                class="canvas-container-header"
                @click="emit('select', [index])"
              >
                <span class="drag-handle">
                  <Icon name="grip" :size="14" color="var(--fg-4)" />
                </span>
                <Icon :name="nodeIcon(node)" :size="14" color="var(--fg-3)" />
                <span class="canvas-node-label">{{ nodeLabel(node) }}</span>
                <button class="canvas-delete" @click.stop="emit('remove', [index])">
                  <Icon name="trash" :size="12" color="var(--err)" />
                </button>
              </div>

              <div
                :ref="(el) => setChildRef(index, el)"
                :data-parent-index="index"
                class="canvas-children"
              >
                <div
                  v-for="(child, ci) in (node as SectionNode | RowNode).children"
                  :key="ci"
                  :data-node-type="child.type"
                  class="canvas-child"
                  :class="{ selected: isSelected([index, ci]) }"
                  :style="isSelected([index, ci]) ? { borderColor: accent } : {}"
                  @click.stop="emit('select', [index, ci])"
                >
                  <span class="drag-handle">
                    <Icon name="grip" :size="12" color="var(--fg-4)" />
                  </span>
                  <Icon :name="nodeIcon(child)" :size="13" color="var(--fg-3)" />
                  <span class="canvas-node-label">{{ nodeLabel(child) }}</span>
                  <button class="canvas-delete" @click.stop="emit('remove', [index, ci])">
                    <Icon name="trash" :size="11" color="var(--err)" />
                  </button>
                </div>
                <div
                  v-if="(node as SectionNode | RowNode).children.length === 0"
                  class="canvas-drop-hint"
                >
                  Drag fields here
                </div>
              </div>
            </div>
          </template>

          <!-- Leaf node (field / display) -->
          <template v-else>
            <div
              class="canvas-leaf"
              :class="{ selected: isSelected([index]) }"
              :style="isSelected([index]) ? { borderColor: accent } : {}"
              @click="emit('select', [index])"
            >
              <span class="drag-handle">
                <Icon name="grip" :size="14" color="var(--fg-4)" />
              </span>
              <Icon :name="nodeIcon(node)" :size="14" color="var(--fg-3)" />
              <span class="canvas-node-label">{{ nodeLabel(node) }}</span>
              <button class="canvas-delete" @click.stop="emit('remove', [index])">
                <Icon name="trash" :size="12" color="var(--err)" />
              </button>
            </div>
          </template>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.canvas {
  min-height: 400px;
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
  pointer-events: none;
}

/* ── Container nodes (section / row) ─────────────────────────── */
.canvas-container {
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  transition: border-color 0.15s;
}

.canvas-container.selected {
  background: color-mix(in oklch, var(--brand-2) 6%, transparent);
}

.canvas-container-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  cursor: pointer;
}

.canvas-children {
  margin: 0 10px 10px;
  min-height: 36px;
  border: 1px dashed var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in oklch, var(--bg-3) 30%, transparent);
  padding: 6px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.canvas-child {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--bg-1);
  cursor: pointer;
  font-size: 13px;
  transition: border-color 0.15s;
}

.canvas-child:hover {
  border-color: var(--fg-4);
}

.canvas-child.selected {
  background: color-mix(in oklch, var(--brand-2) 6%, transparent);
}

.canvas-drop-hint {
  font-size: 12px;
  color: var(--fg-4);
  text-align: center;
  padding: 8px;
}

/* ── Leaf nodes (field / display) ──────────────────────────── */
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

.canvas-leaf:hover {
  border-color: var(--fg-4);
}

.canvas-leaf.selected {
  background: color-mix(in oklch, var(--brand-2) 6%, transparent);
}

/* ── Shared ─────────────────────────────────────────── */
.canvas-node-label {
  flex: 1;
  font-weight: 500;
  color: var(--fg-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drag-handle {
  cursor: move;
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

.canvas-delete {
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.12s;
  padding: 4px;
  border-radius: var(--r-xs);
}

.canvas-container-header:hover .canvas-delete,
.canvas-child:hover .canvas-delete,
.canvas-leaf:hover .canvas-delete {
  opacity: 1;
}

.canvas-delete:hover {
  background: color-mix(in oklch, var(--err) 12%, transparent);
}

.canvas-ghost {
  opacity: 0.3;
}
</style>
