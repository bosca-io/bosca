<script lang="ts" setup>
import gql from 'graphql-tag'
import type { Collection, CollectionWorkflow, ContentJobHistory, Metadata, MetadataWorkflow } from '~/types/graphql'
import type { AttributeState } from '~/utils/editor/attribute'
import type { TemplateAttributeTool } from '~/utils/editor/tool'
import type { Uploader } from '~/utils/editor/uploader'
import type * as Y from 'yjs'
import { getWorkflowBadge } from '~/utils/workflowStatus'

const { useAsyncQuery } = useGraphQL()
const { requestCancel: onCancelJob, cancellingJobId } = useJobCancel()

const slugEditorRef = ref()
defineExpose({ slugEditor: slugEditorRef })

const emit = defineEmits<{
  'edit-slug': []
}>()

const statesGql = gql`
  query GetSidebarStates {
    content {
      states {
        all { id name }
      }
    }
  }
`

const { data: statesData } = await useAsyncQuery<{
  content: { states: { all: Array<{ id: string; name: string }> } }
}>('sidebar-states', statesGql, {})

const props = withDefaults(defineProps<{
  content: Metadata | Collection | undefined | null
  state: MetadataWorkflow | CollectionWorkflow | undefined | null
  ydoc: Y.Doc
  attributes: Map<string, AttributeState>
  uploader: Uploader
  editable: boolean
  toolsEnabled: boolean

  onRunTool: (_attribute: AttributeState, _tool: TemplateAttributeTool) => void
  title?: string
  // Absent boolean props are cast to false by Vue, so the "show by default"
  // behavior needs an explicit default — `!== false` checks never see undefined.
  showSlug?: boolean
  /** Shown in place of the field list when the template defines no attributes. */
  emptyText?: string
}>(), {
  title: undefined,
  showSlug: true,
  emptyText: 'No template attributes.',
})

const shouldShowSlug = computed(() => props.showSlug)

const hasChanges = ref(false)

let listeningYDoc: Y.Doc | null = null
function onYDocUpdate() {
  hasChanges.value = props.ydoc?.getText('changes')?.getAttribute('changes') === 'true'
}

watch(() => props.ydoc, (ydoc) => {
  if (listeningYDoc) {
    listeningYDoc.getText('changes').unobserve(onYDocUpdate)
  }
  if (!ydoc) return
  listeningYDoc = ydoc
  ydoc.getText('changes').observe(onYDocUpdate)
  onYDocUpdate()
}, { immediate: true })

onUnmounted(() => {
  if (listeningYDoc) {
    listeningYDoc.getText('changes').unobserve(onYDocUpdate)
  }
})

// ── Workflow status ─────────────────────────────────────────────────────────
const allStates = computed(() => statesData.value?.content?.states?.all ?? [])

const currentStateName = computed(() => {
  const id = props.state?.state
  return allStates.value.find(s => s.id === id)?.name ?? id ?? 'Draft'
})

const currentStateBadge = computed(() => getWorkflowBadge(props.state?.state || 'draft'))

const pendingStateName = computed(() => {
  const id = props.state?.pending
  if (!id) return ''
  return allStates.value.find(s => s.id === id)?.name ?? id
})

const hasPending = computed(() => !!props.state?.pending)

const activeJobs = computed(() => props.state?.activeJobs ?? [])
const activeJobCount = computed(() => activeJobs.value.length)

function isJobScheduled(job: ContentJobHistory) {
  return job.delayedUntil && new Date(job.delayedUntil) > new Date()
}

function isJobPending(job: ContentJobHistory) {
  return !isJobScheduled(job) && job.status !== 'Running'
}

const noRunningJobs = computed(() =>
  activeJobCount.value > 0 && activeJobs.value.every(
    job => isJobScheduled(job) || isJobPending(job),
  ),
)

const sortedAttributes = computed(() => {
  if (!props.attributes) return []
  return Array.from(props.attributes.entries())
    .map(([key, attr]) => ({ key, attr }))
})

</script>

<template>
  <div class="sidebar">
    <!-- Workflow status -->
    <div v-if="state" class="state-badge-row">
      <div class="state-badge-left">
        <Badge
          v-if="hasChanges"
          color="var(--warn)"
        >
          <template v-if="state?.state === 'published'">Unsaved &amp; unpublished changes</template>
          <template v-else>Unsaved changes</template>
        </Badge>
      </div>

      <Popover v-if="activeJobCount > 0" trigger="mouseenter" placement="bottom-end">
        <template #trigger>
          <span class="job-icon-btn">
            <Icon
              v-if="noRunningJobs"
              name="clock"
              :size="14"
              color="var(--brand-2)"
            />
            <Icon
              v-else
              name="spinner"
              :size="14"
              color="var(--brand-2)"
              class="spin"
            />
          </span>
        </template>
        <div class="jobs-popover">
          <div class="jobs-header">Active Jobs ({{ activeJobCount }})</div>
          <div
            v-for="job in activeJobs"
            :key="job.jobId"
            class="job-row"
          >
            <span class="job-name">{{ job.displayName }}</span>
            <div class="job-actions">
              <Badge
                v-if="isJobScheduled(job)"
                color="var(--brand-2)"
              >Scheduled</Badge>
              <Badge
                v-else-if="isJobPending(job)"
                color="var(--fg-3)"
              >Pending</Badge>
              <Badge
                v-else
                color="var(--warn)"
              >Running</Badge>
              <button
                class="cancel-job-btn"
                :disabled="cancellingJobId === job.jobId"
                @click="onCancelJob(job)"
              >
                <Icon name="x" :size="11" color="var(--fg-3)" />
              </button>
            </div>
          </div>
        </div>
      </Popover>

      <Badge
        v-if="hasPending"
        color="var(--warn)"
      >
        <small>Pending:</small> {{ pendingStateName }}
      </Badge>

      <Badge :color="currentStateBadge.color">{{ currentStateName }}</Badge>
    </div>

    <div class="sidebar-scroll">
      <!-- Slug (read-only: auto-generated from the title; clicking opens the slug modal) -->
      <CommonSlugEditor
        v-if="shouldShowSlug && content"
        ref="slugEditorRef"
        :item="content"
        :editable="false"
        :ydoc="ydoc"
        @edit="emit('edit-slug')"
      />

      <div v-if="sortedAttributes.length === 0" class="sidebar-empty">{{ emptyText }}</div>

      <!-- Attribute fields -->
      <template v-for="{ key, attr } in sortedAttributes" :key="key">
        <!-- Collections (multi) -->
        <AttributesCollections
          v-if="attr.type === 'COLLECTION' && attr.list"
          :item="content"
          :attribute="attr"
          :editable="editable"
          :tools-enabled="toolsEnabled"
          :on-run-tool="onRunTool"
        />

        <!-- Collection (single) -->
        <AttributesCollection
          v-else-if="attr.type === 'COLLECTION' && !attr.list"
          :item="content"
          :attribute="attr"
          :editable="editable"
          :tools-enabled="toolsEnabled"
          :on-run-tool="onRunTool"
        />

        <!-- Date/DateTime -->
        <AttributesDateTimeInput
          v-else-if="attr.type === 'DATE' || attr.type === 'DATETIME' || attr.type === 'DATE_TIME'"
          :item="content"
          :state="state"
          :attribute="attr"
          :editable="editable"
          :tools-enabled="toolsEnabled"
          :on-run-tool="onRunTool"
        />

        <!-- Number -->
        <AttributesNumberInput
          v-else-if="attr.type === 'INT' || attr.type === 'FLOAT'"
          :item="content"
          :attribute="attr"
          :editable="editable"
          :tools-enabled="toolsEnabled"
          :on-run-tool="onRunTool"
        />

        <!-- TextArea -->
        <AttributesTextArea
          v-else-if="attr.ui === 'TEXTAREA'"
          :item="content"
          :attribute="attr"
          :editable="editable"
          :tools-enabled="toolsEnabled"
          :on-run-tool="onRunTool"
        />

        <!-- Image -->
        <AttributesImage
          v-else-if="attr.ui === 'IMAGE'"
          :item="content"
          :attribute="attr"
          :uploader="uploader"
          :editable="editable"
          :tools-enabled="toolsEnabled"
          :on-run-tool="onRunTool"
        />

        <!-- File (metadata, non-image) -->
        <AttributesFile
          v-else-if="attr.type === 'METADATA' && !attr.list"
          :item="content"
          :attribute="attr"
          :uploader="uploader"
          :editable="editable"
          :tools-enabled="toolsEnabled"
          :on-run-tool="onRunTool"
        />

        <!-- Metadatas (multi) -->
        <AttributesMetadatas
          v-else-if="attr.type === 'METADATA' && attr.list"
          :item="content"
          :attribute="attr"
          :editable="editable"
          :tools-enabled="toolsEnabled"
          :on-run-tool="onRunTool"
        />

        <!-- Video HLS player -->
        <template v-else-if="key === 'video.hls' && attr.textValue">
          <AttributesTitlebar
            :item="content"
            :attribute="attr"
            :editable="editable"
            :tools-enabled="toolsEnabled"
            :on-run-tool="onRunTool"
          />
          <div class="sidebar-player">
            <ClientOnly>
              <SMediaPlayer :src="attr.textValue" />
            </ClientOnly>
          </div>
        </template>

        <!-- Default: text input -->
        <AttributesInput
          v-else-if="attr.type === 'STRING' || attr.type !== 'PROFILE'"
          :item="content"
          :attribute="attr"
          :editable="editable"
          :tools-enabled="toolsEnabled"
          :on-run-tool="onRunTool"
        />
      </template>
    </div>

    <CommonJobCancelConfirmModal />
  </div>
</template>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.state-badge-row {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 6px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}

.state-badge-left { flex: 1; min-width: 0; }

.job-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: var(--r-xs);
  cursor: pointer;
  transition: background 0.12s;
}
.job-icon-btn:hover { background: var(--bg-3); }

.spin { animation: spin 1s linear infinite; }
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.jobs-popover { min-width: 220px; max-width: 340px; max-height: 200px; overflow-y: auto; }
.jobs-header { font-size: 10.5px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.06em; color: var(--fg-3); margin-bottom: 10px; }

.job-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 4px 0;
  font-size: 12.5px;
}

.job-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--fg-1); }

.job-actions { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }

.cancel-job-btn {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-xs);
  background: none;
  border: 1px solid var(--line);
  cursor: pointer;
  transition: background 0.12s;
}
.cancel-job-btn:hover { background: var(--bg-3); }
.cancel-job-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.sidebar-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 16px 16px 24px;
}

.sidebar-empty {
  padding: 24px 8px;
  text-align: center;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--fg-4);
}

.sidebar-player {
  margin-bottom: 14px;
}
</style>
