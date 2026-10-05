<script setup lang="ts">
import JsonEditorVue from 'json-editor-vue'
import type { Collection, CollectionWorkflow, Metadata, MetadataWorkflow } from '~/types/graphql'
import type * as Y from 'yjs'
import type { Uploader } from '~/utils/editor/uploader'
import type { AttributeState } from '~/utils/editor/attribute'
import { executeTool, type TemplateAttributeTool } from '~/utils/editor/tool'
import { applyAttributes } from '~/utils/editor/attributes'

const props = defineProps<{
  metadata: Metadata | Collection | null | undefined
  ydoc: Y.Doc | null | undefined
  attributes: Map<string, AttributeState>
  uploader: Uploader
  /**
   * Workflow shown in the sidebar's status row. Defaults to the item's own
   * workflow; pass an override when the editor is bound to a language variant,
   * whose workflow lives on the variant rather than the base item.
   */
  state?: MetadataWorkflow | CollectionWorkflow | null
  /** Sidebar message when the template defines no attributes. */
  emptyText?: string
}>()

const rawAttributes = defineModel('rawAttributes', { type: Object })
const advancedAttrs = ref(false)

watch(advancedAttrs, () => {
  try {
    if (advancedAttrs.value) {
      applyAttributes(props.metadata!, props.attributes, rawAttributes as Ref<Record<string, unknown>>, [], [])
      for (const attr of props.attributes.values()) {
        attr.reset()
      }
    } else {
      for (const attr of props.attributes.values()) {
        attr.reset()
      }
    }
  } catch (e) {
    console.error('Error updating attributes:', e)
  }
})

async function onRunTool(attribute: AttributeState, tool: TemplateAttributeTool) {
  if (props.metadata) {
    // The attribute's own loading ref drives the tool button's spinner state.
    await executeTool(props.metadata, tool, attribute, attribute.loading)
  }
}
</script>

<template>
  <div class="metadata-editor">
    <AttributesSidebar
      v-if="metadata && ydoc && attributes && !advancedAttrs"
      :content="metadata"
      :ydoc="ydoc"
      :editable="true"
      :attributes="attributes"
      :state="state ?? metadata.workflow"
      :uploader="uploader"
      :tools-enabled="true"
      :on-run-tool="onRunTool"
      :show-slug="false"
      :empty-text="emptyText"
    />
    <JsonEditorVue
      v-if="metadata && advancedAttrs"
      v-model="rawAttributes"
      :main-menu-bar="false"
      :navigation-bar="false"
      class="json-editor" />
    <div class="metadata-editor-toggle">
      <span class="spacer" />
      <Switch
        :model-value="advancedAttrs"
        label="Raw Attributes"
        @update:model-value="advancedAttrs = $event"
      />
    </div>
  </div>
</template>

<style scoped>
.metadata-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}

.metadata-editor-toggle {
  display: flex;
  align-items: center;
}

.spacer { flex: 1; }

.json-editor {
  flex: 1;
  min-height: 200px;
}
</style>
