<script lang="ts" setup>
import { AttributeType } from '~/types/graphql'
import type { AttributeState } from '~/utils/editor/attribute'
import type { TemplateAttributeTool } from '~/utils/editor/tool'
import type { Collection, Metadata } from '~/types/graphql'
import { getWorkflowBadge, getWorkflowState } from '~/utils/workflowStatus'

const props = defineProps<{
  item: Metadata | Collection | undefined | null
  attribute: AttributeState
  editable: boolean
  toolsEnabled: boolean
   
  onRunTool: (_attribute: AttributeState, _tool: TemplateAttributeTool) => void
  showStatus?: boolean
  notPublic?: boolean
}>()

const publishOpen = ref(false)

const metadataIds = computed(() => {
  const a = props.attribute
  if (!a) return [] as string[]
  if (a.list) return (a.metadatas || []).map(m => m.id)
  const id = a.metadata?.id
  return id ? [id] : []
})

const published = computed(() => {
  const item = props.item
  if (!item) return false
  return ('workflow' in item) ? item.workflow?.state === 'published' : false
})

const isRelated = ref(true)
const changeCount = ref(0)

function updateIsRelated() {
  switch (props.attribute.type) {
    case AttributeType.Metadata: {
      // "Connected" means the content this attribute references has a saved
      // relationship row. Match by metadata id — the relationship name can
      // drift when a template's configuration changes after values were saved.
      const relationships = props.attribute.relationships.value || []
      const entries = props.attribute.list
        ? (props.attribute.metadatas || [])
        : (props.attribute.metadata ? [props.attribute.metadata] : [])
      isRelated.value = entries.length > 0
        && entries.every(e => relationships.some(r => (r.metadata as { id?: string } | undefined)?.id === e.id))
      break
    }
    case AttributeType.Collection: {
      // "Connected" means each selected collection has a saved parent-collection
      // membership. Match by collection id — a template's configuration.type can
      // be absent (a generic, untyped collections picker) or drift after values
      // were saved, so the membership's type tag is unreliable. Mirrors the
      // metadata case above.
      const parentCollections = props.attribute.parentCollections.value || []
      const entries = props.attribute.list
        ? (props.attribute.collections || [])
        : (props.attribute.collection ? [props.attribute.collection] : [])
      isRelated.value = entries.length > 0
        && entries.every(e => parentCollections.some(c => c.id === e.id))
      break
    }
    default:
      isRelated.value = true
      break
  }
}

function onChanged() {
  changeCount.value++
  updateIsRelated()
}

// Initialize during setup so the first render reflects the real state
updateIsRelated()

watch(props.attribute.relationships, () => onChanged())
watch(props.attribute.parentCollections, () => onChanged())
watch(props.attribute.changeRef, () => onChanged())

onMounted(() => {
  props.attribute.addListener(onChanged)
  onChanged()
})

onUnmounted(() => {
  props.attribute.removeListener(onChanged)
})

const workflow = computed(() => {
  if (!props.showStatus || !props.item) return null
  return ('workflow' in props.item) ? props.item.workflow ?? null : null
})

const badge = computed(() => {
  if (!workflow.value) return null
  return getWorkflowBadge(getWorkflowState(workflow.value))
})
</script>

<template>
  <div class="titlebar">
    <span class="titlebar-label">{{ attribute.name }}</span>

    <template v-if="showStatus && item">
      <span v-if="badge" class="titlebar-badge" :style="{ background: `color-mix(in oklch, ${badge.color} 18%, transparent)`, color: badge.color }">
        {{ badge.label }}
      </span>
    </template>

    <!-- Not connected indicator -->
    <Popover
      v-if="!isRelated && attribute.hasValue"
      trigger="mouseenter"
      placement="bottom"
      :interactive="false"
      :delay="150"
    >
      <template #trigger>
        <span
          class="indicator-badge indicator-badge--unlinked"
          :class="{ 'indicator-badge--warn': published }"
        >
          <Icon name="link" :size="18" />
        </span>
      </template>
      <div class="status-popover">
        <div class="status-popover-title" :class="{ 'status-popover-title--warn': published }">
          <Icon name="link" :size="16" /> Not connected
        </div>
        <p class="status-popover-text">
          This content is referenced, but the relationship hasn't been saved yet.
          Save to finish the connection.
        </p>
        <p v-if="published" class="status-popover-warn">
          This item is published — the missing connection is visible to consumers.
        </p>
      </div>
    </Popover>

    <!-- Not public indicator (clickable → opens publish modal) -->
    <Popover
      v-if="notPublic && attribute.hasValue"
      trigger="mouseenter"
      placement="bottom"
      :interactive="false"
      :delay="150"
    >
      <template #trigger>
        <span
          class="indicator-badge indicator-badge--not-public"
          :class="{ 'indicator-badge--warn': published }"
          @click.stop="editable && (publishOpen = true)"
        >
          <Icon name="key" :size="18" />
        </span>
      </template>
      <div class="status-popover">
        <div class="status-popover-title" :class="{ 'status-popover-title--warn': published }">
          <Icon name="key" :size="16" /> Not publicly available
        </div>
        <p class="status-popover-text">
          This content isn't visible to the public yet.
        </p>
        <p v-if="published" class="status-popover-warn">
          This item is published — consumers may see missing content.
        </p>
        <p v-if="editable" class="status-popover-hint">Click the icon to adjust visibility.</p>
      </div>
    </Popover>

    <span class="spacer" />
    <AttributesToolButton
      :attribute="attribute"
      :editable="editable"
      :tools-enabled="toolsEnabled"
      :on-run-tool="onRunTool"
    />

    <AttributesPublishModal
      v-if="publishOpen"
      v-model:open="publishOpen"
      :metadata-ids="metadataIds"
    />
  </div>
</template>

<style scoped>
.titlebar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.titlebar-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-1);
}

.spacer { flex: 1; }

.titlebar-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 999px;
  letter-spacing: 0.02em;
}

.indicator-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--r-xs);
  color: var(--fg-3);
  cursor: default;
}

.indicator-badge--not-public { cursor: pointer; }
.indicator-badge--not-public:hover { background: var(--bg-3); }

.indicator-badge--warn { color: var(--warn); }

.indicator-badge--unlinked {
  position: relative;
}
.indicator-badge--unlinked::after {
  content: '';
  position: absolute;
  width: 18px;
  height: 2px;
  background: currentColor;
  transform: rotate(-45deg);
  top: 50%;
  left: 50%;
  margin-top: -1px;
  margin-left: -9px;
}

.status-popover {
  max-width: 320px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 6px;
}

.status-popover-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.status-popover-title--warn {
  color: var(--warn);
}

.status-popover-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--fg-2);
}

.status-popover-warn {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--warn);
}

.status-popover-hint {
  margin: 0;
  font-size: 12px;
  color: var(--fg-3);
}
</style>
