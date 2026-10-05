<script lang="ts" setup>
import { NodeViewContent, NodeViewWrapper } from '@tiptap/vue-3'
import type { Editor } from '@tiptap/core'
import type { Node as ProseMirrorNode } from '@tiptap/pm/model'

const props = defineProps<{
  editor: Editor
  node: ProseMirrorNode
   
  updateAttributes: (attrs: Record<string, unknown>) => void
  getPos: () => number
}>()

const isMermaid = computed(() => props.node.attrs.language === 'mermaid')
const mermaidContent = computed(() => props.node.textContent)
const isEditable = computed(() => props.editor.isEditable)

function setLanguage(language: string) {
  props.updateAttributes({ language })
}
</script>

<template>
  <NodeViewWrapper class="code-block-wrapper">
    <template v-if="isMermaid && !isEditable">
      <DocumentMermaidDiagram :content="mermaidContent" />
    </template>
    <template v-else>
      <div class="code-block-header">
        <select
          :value="node.attrs.language || ''"
          :disabled="!isEditable"
          @change="setLanguage(($event.target as HTMLSelectElement).value)"
        >
          <option value="">
            Plain text
          </option>
          <option value="mermaid">
            Mermaid
          </option>
          <option value="javascript">
            JavaScript
          </option>
          <option value="typescript">
            TypeScript
          </option>
          <option value="html">
            HTML
          </option>
          <option value="css">
            CSS
          </option>
          <option value="json">
            JSON
          </option>
          <option value="graphql">
            GraphQL
          </option>
          <option value="sql">
            SQL
          </option>
        </select>
        <DocumentMermaidDiagram v-if="isMermaid" class="mermaid-preview" :content="mermaidContent" />
      </div>
      <pre><NodeViewContent as="code" /></pre>
    </template>
  </NodeViewWrapper>
</template>

<style scoped>
.code-block-wrapper {
  position: relative;
}

.code-block-header {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.code-block-header select {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-1);
  font-size: 11px;
  padding: 2px 6px;
  cursor: pointer;
}

.code-block-header select:disabled {
  opacity: 0.5;
  cursor: default;
}

.mermaid-preview {
  margin-bottom: 8px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}
</style>
