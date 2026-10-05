<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'
import type { RecommendationContext, RecommendationContextModel } from '~/types/graphql'

const props = defineProps<{ contextId: string }>()
const contextId = computed(() => props.contextId)
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const selectedModel = ref<RecommendationContextModel | null>(null)
const modelToDelete = ref<RecommendationContextModel | null>(null)
const actionError = ref('')

const lifecycleGql = gql`
  query RecommendationContextModels($id: UUID!) {
    recommendation { contexts {
      context(id: $id) { activeModelVersion requestedModelVersion revision }
      models(contextId: $id) {
        version revision status exported personalized pinned failure created started completed
        context { name type description activeModelVersion
          weights {
            similarity { semantic categories labels language mime type collections }
            typePreferences { type weight }
            defaultTypePreference
            content
            coEngagement
            cohortCoEngagement
            learnedNeighbor
            personalization
            rating
          }
          contentFilter {
            metadata {
              includedContentTypePrefixes
              excludedContentTypePrefixes
              includedAttributeTypes
              excludedAttributeTypes
            }
            collections {
              includedTypes
              excludedTypes
              includedAttributeTypes
              excludedAttributeTypes
            }
          }
        }
      }
    } }
  }
`
interface LifecycleData {
  recommendation: { contexts: {
    context: Pick<RecommendationContext, 'activeModelVersion' | 'requestedModelVersion' | 'revision'> | null
    models: RecommendationContextModel[]
  } }
}
const { data: lifecycle, error: lifecycleError, status: lifecycleStatus, refresh: refreshLifecycle } = useAsyncQuery<LifecycleData>(
  `recommendation-context-models-${props.contextId}`, lifecycleGql, { id: contextId },
)
const models = computed(() => lifecycle.value?.recommendation.contexts.models ?? [])
const activeVersion = computed(() => lifecycle.value?.recommendation.contexts.context?.activeModelVersion)
const requestedVersion = computed(() => lifecycle.value?.recommendation.contexts.context?.requestedModelVersion)
let statusTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  statusTimer = setInterval(() => { void refreshLifecycle() }, 10000)
})
onBeforeUnmount(() => { if (statusTimer) clearInterval(statusTimer) })

const activateGql = gql`
  mutation ActivateRecommendationContextModel($id: UUID!, $version: Long!) {
    recommendation { contexts { activateModel(contextId: $id, version: $version) { version } } }
  }
`
const pinGql = gql`
  mutation PinRecommendationContextModel($id: UUID!, $version: Long!, $pinned: Boolean!) {
    recommendation { contexts { pinModel(contextId: $id, version: $version, pinned: $pinned) { version pinned } } }
  }
`
const modelActionPending = ref(false)
async function changeModel(model: RecommendationContextModel, action: 'activate' | 'pin') {
  modelActionPending.value = true
  actionError.value = ''
  try {
    await mutation(action === 'activate' ? activateGql : pinGql, {
      id: contextId.value, version: model.version, ...(action === 'pin' ? { pinned: !model.pinned } : {}),
    })
    await refreshLifecycle()
  } catch (modelError: unknown) {
    actionError.value = modelError instanceof Error ? modelError.message : 'Failed to update model selection'
  } finally {
    modelActionPending.value = false
  }
}


const deleteGql = gql`
  mutation DeleteRecommendationContextModel($id: UUID!, $version: Long!) {
    recommendation { contexts { deleteModel(contextId: $id, version: $version) } }
  }
`
const columns: GlassTableColumn[] = [
  { key: 'version', label: 'Version', width: '80px' },
  { key: 'revision', label: 'Revision', width: '80px' },
  { key: 'model', label: 'Models', width: 'minmax(140px, 1fr)' },
  { key: 'status', label: 'Status', width: 'minmax(210px, 1.5fr)' },
  { key: 'created', label: 'Created', width: 'minmax(150px, 1fr)', muted: true },
  { key: 'selection', label: 'Selection', width: '100px' },
]
function canDelete(model: RecommendationContextModel): boolean {
  return (model.status === 'COMPLETED' || model.status === 'FAILED')
    && model.version !== activeVersion.value && model.version !== requestedVersion.value
    && !models.value.some(candidate => (candidate.status === 'QUEUED' || candidate.status === 'RUNNING')
      && candidate.context.activeModelVersion === model.version)
}
function rowActions(model: RecommendationContextModel): OverflowMenuItem[] {
  return [
    { id: 'details', label: 'Model details', icon: 'eye' },
    { id: 'activate', label: 'Activate', icon: 'play', disabled: modelActionPending.value || model.status !== 'COMPLETED' || model.version === activeVersion.value || model.version === requestedVersion.value },
    { id: 'pin', label: model.pinned ? 'Unpin' : 'Pin', icon: 'pin', disabled: modelActionPending.value || model.status !== 'COMPLETED' },
    { id: 'delete', label: 'Delete model', icon: 'trash', danger: true, disabled: modelActionPending.value || !canDelete(model) },
  ]
}
async function rowAction({ action, row }: { action: string; row: RecommendationContextModel }) {
  if (action === 'details') selectedModel.value = row
  else if (action === 'delete' && canDelete(row)) modelToDelete.value = row
  else if (action === 'activate' || action === 'pin') await changeModel(row, action)
}
async function deleteModel() {
  const model = modelToDelete.value
  if (!model || modelActionPending.value) return
  modelActionPending.value = true
  actionError.value = ''
  try {
    await mutation(deleteGql, { id: props.contextId, version: model.version })
    modelToDelete.value = null
    await refreshLifecycle()
  } catch (error: unknown) {
    actionError.value = error instanceof Error ? error.message : 'Failed to delete model'
  } finally {
    modelActionPending.value = false
  }
}
function statusLabel(model: RecommendationContextModel): string {
  if (model.status === 'RUNNING') return model.exported ? 'Trained · waiting for serving' : 'Training'
  return { QUEUED: 'Queued', COMPLETED: 'Completed', FAILED: 'Failed' }[model.status]
}
function fmtDate(value: string): string {
  return new Date(value).toLocaleString()
}
defineExpose({ refresh: refreshLifecycle })
</script>

<template>
  <SectionCard title="Trained versions" padded>
    <template #right>
      <Button
        size="xs"
        icon="refresh"
        title="Refresh model status"
        aria-label="Refresh model status"
        @click="refreshLifecycle()" />
    </template>
    <p class="selection-note">
      {{ activeVersion ? `Active model: ${activeVersion}.` : 'No active model yet.' }}
      <template v-if="requestedVersion && requestedVersion !== activeVersion">Waiting for model {{ requestedVersion }} to load.</template>
      Saved revision {{ lifecycle?.recommendation.contexts.context?.revision }}.
    </p>
    <p v-if="lifecycleError" class="query-error">Couldn't refresh model status: {{ lifecycleError.message }}</p>
    <p v-if="actionError && !modelToDelete" class="query-error">{{ actionError }}</p>
    <GlassTable
      :columns="columns"
      :rows="models"
      row-key="version"
      :loading="lifecycleStatus === 'pending' && models.length === 0"
      :row-actions="rowActions"
      empty-text="No training runs yet. Use Train model on the context or Train all contexts to start."
      @row-action="rowAction"
      @row-click="selectedModel = $event">
      <template #col-version="{ row }"><strong>v{{ row.version }}</strong></template>
      <template #col-model="{ row }">{{ row.exported ? (row.personalized ? 'Content + personalized' : 'Content') : '—' }}</template>
      <template #col-status="{ row }">
        <span :title="row.failure ?? undefined">{{ statusLabel(row) }}</span>
      </template>
      <template #col-created="{ row }">{{ fmtDate(row.created) }}</template>
      <template #col-selection="{ row }">
        <Badge v-if="row.version === activeVersion" :color="accent">Active</Badge>
        <Badge v-else-if="row.version === requestedVersion" :color="accent">Requested</Badge>
        <span v-if="row.pinned">Pinned</span>
      </template>
    </GlassTable>
  </SectionCard>
  <RecommendationModelDetails
    v-if="selectedModel"
    :version="selectedModel.version"
    :revision="selectedModel.revision"
    :context="selectedModel.context"
    @close="selectedModel = null" />
  <ConfirmModal
    v-if="modelToDelete"
    :title="`Delete model ${modelToDelete.version}`"
    subtitle="Remove this version from the context's model history."
    confirm-label="Delete model"
    :loading="modelActionPending"
    @close="modelToDelete = null"
    @confirm="deleteModel">
    <p>Model {{ modelToDelete.version }} and its stored exports will be removed. This cannot be undone.</p>
    <p v-if="actionError" class="query-error">{{ actionError }}</p>
  </ConfirmModal>
</template>

<style scoped>
.selection-note { margin: 0 0 14px; color: var(--fg-3); font-size: 13px; }
.query-error { color: var(--err); margin: 10px 0; }
</style>
