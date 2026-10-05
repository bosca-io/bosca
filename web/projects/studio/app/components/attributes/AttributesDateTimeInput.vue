<script lang="ts" setup>
import type { AttributeState } from '~/utils/editor/attribute'
import type { TemplateAttributeTool } from '~/utils/editor/tool'
import type { Collection, CollectionWorkflow, Metadata, MetadataWorkflow } from '~/types/graphql'

const props = defineProps<{
  item: Metadata | Collection | undefined | null
  state: MetadataWorkflow | CollectionWorkflow | null | undefined
  attribute: AttributeState
  editable: boolean
  toolsEnabled: boolean
   
  onRunTool: (_attribute: AttributeState, _tool: TemplateAttributeTool) => void
}>()

const dateValue = ref('')

function msToDateTimeLocal(ms: number | null): string {
  if (!ms) return ''
  const d = new Date(ms)
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function dateTimeLocalToMs(v: string): number | null {
  if (!v) return null
  return new Date(v).getTime()
}

function syncFromAttribute() {
  dateValue.value = msToDateTimeLocal(props.attribute.dateTimeValue)
}

watch(() => props.attribute.changeRef.value, syncFromAttribute)
onMounted(syncFromAttribute)

watch(dateValue, (v) => {
  // AttributeState.dateTimeValue is a class setter that updates the Yjs doc, not a plain prop
  // eslint-disable-next-line vue/no-mutating-props
  props.attribute.dateTimeValue = dateTimeLocalToMs(v)
})
</script>

<template>
  <div class="attr-field">
    <AttributesTitlebar
      :item="item"
      :attribute="attribute"
      :editable="editable"
      :tools-enabled="toolsEnabled"
      :on-run-tool="onRunTool"
    />
    <input
      v-model="dateValue"
      class="attr-input"
      type="datetime-local"
      :disabled="!editable"
    >
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
  font-family: inherit;
  color-scheme: dark;
}

.attr-input:focus {
  border-color: var(--brand-2);
}

.attr-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
