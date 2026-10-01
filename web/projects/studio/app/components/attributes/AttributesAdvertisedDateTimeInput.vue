<script lang="ts" setup>
import type { Collection, CollectionWorkflow, Metadata, MetadataWorkflow } from '~/types/graphql'
import type { AttributeState } from '~/utils/editor/attribute'
import type { TemplateAttributeTool } from '~/utils/editor/tool'

const props = defineProps<{
  item: Metadata | Collection | undefined | null
  state: MetadataWorkflow | CollectionWorkflow | null | undefined
  attribute: AttributeState
  published: AttributeState | null | undefined
  editable: boolean
  toolsEnabled: boolean
   
  onRunTool: (_attribute: AttributeState, _tool: TemplateAttributeTool) => void
}>()

const dateDisabled = computed(() =>
  !props.editable
  || props.state?.state === 'advertised'
  || props.state?.pending === 'advertised'
  || props.state?.state === 'published'
  || props.state?.pending === 'published'
)

const enabled = ref((props.attribute.dateTimeValueRaw || 0) > 0)

function msToDateTimeLocal(ms: number | null | undefined): string {
  if (!ms) return ''
  const d = new Date(ms)
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const dateValue = ref(msToDateTimeLocal(props.attribute.dateTimeValueRaw))

watch(dateValue, (v) => {
  if (!v) {
    props.attribute.forceSetDateTimeValue(null)
  } else {
    props.attribute.forceSetDateTimeValue(new Date(v).getTime())
  }
})

watch(enabled, (v) => {
  if (!v) {
    dateValue.value = ''
    props.attribute.forceSetDateTimeValue(null)
    props.attribute.reset()
  }
})

const hasPassedPublished = ref(true)

const available = computed(() => {
  if (!props.published) return false
  if (hasPassedPublished.value) return false
  return props.editable || props.attribute.hasValue || props.state?.pending === 'advertised'
})

let interval: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  if (props.published) {
    const check = () => {
      hasPassedPublished.value = (props.published?.dateTimeValueRaw || 0) < Date.now()
    }
    interval = setInterval(check, 1000)
    check()
  }
})

onUnmounted(() => {
  if (interval) clearInterval(interval)
})
</script>

<template>
  <div v-if="attribute && available" class="attr-field">
    <AttributesTitlebar
      :item="item"
      :attribute="attribute"
      :editable="editable"
      :tools-enabled="toolsEnabled"
      :on-run-tool="onRunTool"
    />
    <div class="adv-datetime-row">
      <input
        v-if="enabled || (state as any)?.pending === 'advertised'"
        v-model="dateValue"
        class="attr-input"
        type="datetime-local"
        :disabled="dateDisabled"
      >
      <div v-if="!enabled && editable" class="adv-datetime-hint">
        Enable advertising of this content
      </div>
      <Switch
        v-if="editable && !dateDisabled"
        :model-value="enabled"
        @update:model-value="enabled = $event"
      />
    </div>
  </div>
</template>

<style scoped>
.attr-field { margin-bottom: 14px; }

.adv-datetime-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.attr-input {
  flex: 1;
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

.attr-input:disabled { opacity: 0.6; cursor: not-allowed; }

.adv-datetime-hint {
  font-size: 12px;
  color: var(--fg-3);
  font-style: italic;
}
</style>
