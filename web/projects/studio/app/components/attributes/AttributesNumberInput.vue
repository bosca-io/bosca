<script lang="ts" setup>
import type { AttributeState } from '~/utils/editor/attribute'
import type { TemplateAttributeTool } from '~/utils/editor/tool'
import type { Collection, Metadata } from '~/types/graphql'

defineProps<{
  item: Metadata | Collection | undefined | null
  attribute: AttributeState
  editable: boolean
  toolsEnabled: boolean
   
  onRunTool: (_attribute: AttributeState, _tool: TemplateAttributeTool) => void
}>()
</script>

<template>
  <!--
    Not keyed on `attribute.changeRef.value`: the v-model getter reads
    `changeRef`, so external Yjs edits already re-render `:value` without a
    remount. Keying here would remount the <input> on every local keystroke
    (the setter writes to Yjs, which bumps changeRef) and steal focus.
  -->
  <div class="attr-field">
    <AttributesTitlebar
      :item="item"
      :attribute="attribute"
      :editable="editable"
      :tools-enabled="toolsEnabled"
      :on-run-tool="onRunTool"
    />
    <!-- AttributeState.numberValue is a class setter that updates the Yjs doc, not a plain prop -->
    <!-- eslint-disable vue/no-mutating-props -->
    <input
      v-model="attribute.numberValue"
      class="attr-input"
      type="number"
      :disabled="!editable"
    >
    <!-- eslint-enable vue/no-mutating-props -->
  </div>
</template>

<style scoped>
.attr-field {
  margin-bottom: 14px;
}

.attr-input {
  width: 100%;
  padding: 7px 10px;
  border-radius: 6px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  font-size: 12.5px;
  color: var(--fg-0);
  outline: none;
  font-family: var(--font-mono);
}

.attr-input:focus {
  border-color: var(--brand-2);
}

.attr-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
