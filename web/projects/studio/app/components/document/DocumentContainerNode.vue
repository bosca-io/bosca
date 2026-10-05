<script lang="ts" setup>
/* eslint-disable @typescript-eslint/no-explicit-any, vue/prop-name-casing */
import { NodeViewContent, NodeViewWrapper } from '@tiptap/vue-3'
import type { Editor } from '@tiptap/core'
import type { Node as ProseMirrorNode } from '@tiptap/pm/model'
import { executeTool, type TemplateAttributeTool } from '~/utils/editor/tool'
import { normalizeDocumentReferences } from '~/utils/editor/document'

const props = defineProps<{
  extension: any
  editor: Editor
  getPos: () => number
   
  updateAttributes: (attrs: Record<string, unknown>) => void
  node: ProseMirrorNode
  HTMLAttributes: Record<string, any>
}>()

const loading = ref(false)
const metadataId = ref<string | undefined>()
const renderer = ref<string | undefined>()
const references = ref<string[]>([])
const expanded = ref(true)

const isEditable = ref(props.editor.isEditable)

function onEditorUpdate() {
  isEditable.value = props.editor.isEditable
}

onMounted(() => {
  references.value = normalizeDocumentReferences(props.node.attrs.references)
  metadataId.value = props.node.attrs.metadataId || undefined
  renderer.value = props.node.attrs.renderer || undefined
  props.editor.on('update', onEditorUpdate)
})

onUnmounted(() => {
  props.editor.off('update', onEditorUpdate)
})

const nodeContainer = computed(() => {
  return props.extension.options.containers?.find((c: any) =>
    c.id === props.HTMLAttributes.name
  )
})

const containerTools = computed<TemplateAttributeTool[]>(() => {
  const tools = nodeContainer.value?.tools || []
  if (tools.length > 0) return tools
  const configuredQuery = nodeContainer.value?.configuration?.generateQuery
  if (typeof configuredQuery !== 'string' || configuredQuery.length === 0) return []
  return [
    {
      name: nodeContainer.value?.name,
      description: nodeContainer.value?.description,
      query: configuredQuery,
      resultPath: nodeContainer.value?.configuration?.generateResultPath
    }
  ]
})

const canRunTools = computed(() => isEditable.value && containerTools.value.length > 0)

// A container may expose several tools, but the header always shows a single
// button. One tool runs immediately; multiple tools open a picker so the header
// never accumulates a button per tool.
const pickerOpen = ref(false)

const toolButtonTitle = computed(() => {
  const list = containerTools.value
  if (list.length === 1) return list[0]!.description || list[0]!.name || 'Run tool'
  return 'Run a tool'
})

const toolPopoverTitle = computed(() => {
  const list = containerTools.value
  if (list.length === 1) return list[0]!.name || 'Run tool'
  return 'Tools'
})

const toolPopoverHint = computed(() => {
  if (loading.value) return 'Running…'
  return containerTools.value.length > 1 ? 'Click to choose a tool.' : 'Click to run.'
})

function onToolButtonClick() {
  if (loading.value) return
  const list = containerTools.value
  if (list.length === 1) {
    onRunTool(list[0]!)
  } else if (list.length > 1) {
    pickerOpen.value = true
  }
}

function onPickTool(tool: TemplateAttributeTool) {
  pickerOpen.value = false
  onRunTool(tool)
}

function isEqual(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) return false
  }
  return true
}

watch(references, (value) => {
  if (nodeContainer.value?.type === 'BIBLE') {
    const normalizedReferences = normalizeDocumentReferences(value)
    if (!isEqual(value, normalizedReferences)) {
      references.value = normalizedReferences
      return
    }
    if (
      !isEqual(props.node.attrs.references || [], normalizedReferences)
      || (props.node.attrs.metadataId || '').toString()
      !== (metadataId.value || '').toString()
    ) {
      props.updateAttributes({
        references: toRaw(normalizedReferences),
        metadataId: metadataId.value || undefined
      })
    }
  }
})

watch([metadataId, renderer], () => {
  if (nodeContainer.value?.type === 'METADATA') {
    if (
      (props.node.attrs.metadataId || '').toString() !== (metadataId.value || '').toString()
      || (props.node.attrs.renderer || '') !== (renderer.value || '')
    ) {
      props.updateAttributes({
        metadataId: metadataId.value || undefined,
        renderer: renderer.value || undefined
      })
    }
  }
})

/**
 * Normalizes a tool result into ProseMirror nodes. Tools can return a node
 * JSON, a whole doc JSON, an array of node JSONs, a JSON string of any of
 * those, or plain generated text (inserted as a paragraph).
 */
function toolResultToNodes(result: unknown): ProseMirrorNode[] {
  let value = result
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      if (parsed && typeof parsed === 'object') value = parsed
    } catch {
      // Plain generated text — wrap it in a paragraph.
      return [props.editor.schema.nodeFromJSON({
        type: 'paragraph',
        content: [{ type: 'text', text: value }],
      })]
    }
  }
  if (Array.isArray(value)) {
    return value.map(n => props.editor.schema.nodeFromJSON(n))
  }
  if (value && typeof value === 'object' && 'type' in value) {
    const json = value as { type: string; content?: unknown[] }
    if (json.type === 'doc') {
      return (json.content ?? []).map(n => props.editor.schema.nodeFromJSON(n))
    }
    return [props.editor.schema.nodeFromJSON(json)]
  }
  throw new Error('Tool did not return document content')
}

async function onRunTool(tool: TemplateAttributeTool) {
  const toast = useToast()
  const result = await executeTool(
    props.extension.options.metadata,
    tool,
    null,
    loading,
  )

  const pos = props.getPos?.()
  if (typeof pos !== 'number') {
    console.error('Could not determine position of the node')
    loading.value = false
    return
  }

  if (result == null) {
    toast.error(`${tool.name || 'Tool'} did not return a result`)
    return
  }

  try {
    const nodes = toolResultToNodes(result)
    if (!nodes.length) {
      toast.error(`${tool.name || 'Tool'} returned empty content`)
      return
    }
    props.editor.view.dispatch(
      props.editor.state.tr.replaceWith(
        pos + 1,
        pos + props.node.content.size + 1,
        nodes
      )
    )
  } catch (e: unknown) {
    console.error('Failed to apply tool result', e, result)
    toast.error(e instanceof Error ? e.message : 'Failed to apply tool result')
  }
}

async function onRemove() {
  const pos = props.getPos?.()
  if (typeof pos !== 'number') return
  props.editor.view.dispatch(
    props.editor.state.tr.deleteRange(pos, pos + props.node.nodeSize)
  )
}
</script>

<template>
  <NodeViewWrapper>
    <div class="editor-container">
      <div class="container-header">
        <span class="container-name">{{ nodeContainer?.name || HTMLAttributes.name }}</span>
        <span class="spacer" />
        <Popover
          v-if="canRunTools"
          trigger="mouseenter"
          placement="bottom"
          :interactive="false"
          :delay="150"
        >
          <template #trigger>
            <button
              class="container-action"
              :title="toolButtonTitle"
              :disabled="loading"
              @click="onToolButtonClick"
            >
              <Icon
                :name="loading ? 'spinner' : 'sparkles'"
                :size="13"
                :class="{ 'tool-spin': loading }" />
            </button>
          </template>
          <div class="tool-popover">
            <div class="tool-popover-title">
              <Icon name="sparkles" :size="16" /> {{ toolPopoverTitle }}
            </div>
            <p
              v-if="containerTools.length === 1 && containerTools[0]?.description"
              class="tool-popover-text"
            >
              {{ containerTools[0]?.description }}
            </p>
            <ul v-else-if="containerTools.length > 1" class="tool-popover-list">
              <li
                v-for="(tool, i) in containerTools"
                :key="tool.name ?? i"
                class="tool-popover-item"
              >
                <span class="tool-popover-name">{{ tool.name || 'Tool' }}</span>
                <span v-if="tool.description" class="tool-popover-desc">{{ tool.description }}</span>
              </li>
            </ul>
            <p class="tool-popover-hint">{{ toolPopoverHint }}</p>
          </div>
        </Popover>

        <AttributesToolPickerModal
          v-if="pickerOpen"
          :tools="containerTools"
          @select="onPickTool"
          @close="pickerOpen = false"
        />
        <button
          class="container-action"
          :title="expanded ? 'Collapse' : 'Expand'"
          @click="expanded = !expanded"
        >
          <Icon :name="expanded ? 'chevronDown' : 'chevron'" :size="13" />
        </button>
        <button
          v-if="isEditable"
          class="container-action container-action--remove"
          title="Remove"
          :disabled="loading"
          @click="onRemove()"
        >
          <Icon name="x" :size="13" />
        </button>
      </div>
      <div v-show="expanded" class="container-body">
        <DocumentBibleReferences
          v-if="nodeContainer?.type === 'BIBLE'"
          v-model:references="references"
          v-model:metadata="metadataId"
        />
        <DocumentMetadataReference
          v-else-if="nodeContainer?.type === 'METADATA'"
          v-model:metadata-id="metadataId"
          v-model:renderer="renderer"
          :renderers="nodeContainer?.renderers || []"
          :filters="nodeContainer?.filters || []"
        />
        <NodeViewContent v-else />
      </div>
    </div>
  </NodeViewWrapper>
</template>

<style scoped>
.editor-container {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  margin: 8px 0;
}

.container-header {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 7px 12px;
  border-bottom: 1px solid var(--line);
  background: var(--bg-3);
  border-top-left-radius: 8px;
  border-top-right-radius: 8px;
}

.container-name {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--fg-1);
}

.spacer {
  flex: 1;
}

.container-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 4px;
  background: transparent;
  border: none;
  cursor: pointer;
  color: var(--fg-3);
}

.container-action:hover {
  background: var(--bg-2);
  color: var(--fg-1);
}

.container-action:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.container-action--remove:hover {
  color: var(--err);
}

.container-body {
  padding: 14px;
  min-height: 40px;
}

.tool-spin {
  animation: tool-spin 0.9s linear infinite;
}

@keyframes tool-spin {
  to { transform: rotate(360deg); }
}

.tool-popover {
  max-width: 320px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 6px;
}

.tool-popover-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.tool-popover-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--fg-2);
}

.tool-popover-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tool-popover-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tool-popover-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-1);
}

.tool-popover-desc {
  font-size: 12.5px;
  line-height: 1.4;
  color: var(--fg-3);
}

.tool-popover-hint {
  margin: 0;
  font-size: 12px;
  color: var(--fg-3);
}
</style>
