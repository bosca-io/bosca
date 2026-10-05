<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem, SelectOption } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const STATUS_COLORS: Record<string, string> = {
  RUNNING: 'var(--brand-2)',
  DRAFT: '#6c7388',
  PAUSED: '#f59e42',
  COMPLETED: '#5ec5ff',
  ARCHIVED: '#3a4256',
}

const offset = ref(0)
const limit = ref(10)
const createModalOpen = ref(false)
const creating = ref(false)
const deleteTarget = ref<Experiment | null>(null)
const deleteLoading = ref(false)

const experimentsGql = gql`
  query GetAllExperiments($limit: Int!, $offset: Long!) {
    experiments {
      all(limit: $limit, offset: $offset) {
        id name description status startDate endDate targetSampleSize created
      }
    }
  }
`

const flagsGql = gql`
  query GetFlagsForExperiment {
    featureFlags {
      all(limit: 100, offset: 0) {
        id key name variations targetingRules
      }
    }
  }
`

const addGql = gql`
  mutation AddExperiment($experiment: ExperimentInput!) {
    experiments { add(experiment: $experiment) { id } }
  }
`

const deleteGql = gql`
  mutation DeleteExperiment($id: UUID!) {
    experiments { delete(id: $id) }
  }
`

interface Experiment {
  id: string
  name: string
  description: string | null
  status: string
  startDate: string | null
  endDate: string | null
  targetSampleSize: number | null
  created: string
}

interface FeatureFlag {
  id: string
  key: string
  name: string
  targetingRules: unknown
  variations: unknown
}

const { data, status, refresh } = useAsyncQuery<{
  experiments: { all: Experiment[] }
}>('experiments-list', experimentsGql, { limit, offset }, { server: false })

const { data: flagsData } = useAsyncQuery<{
  featureFlags: { all: FeatureFlag[] }
}>('experiment-flags', flagsGql, {}, { server: false })

const experiments = computed(() => data.value?.experiments?.all ?? [])
const flags = computed(() => flagsData.value?.featureFlags?.all ?? [])
const isLoading = computed(() => status.value === 'pending')

const currentPage = computed(() => Math.floor(offset.value / limit.value) + 1)
const totalPages = computed(() => {
  // If we got a full page, there may be more
  const count = experiments.value.length
  if (count < limit.value) return currentPage.value
  return currentPage.value + 1
})

function goToPage(page: number) {
  if (page < 1) return
  offset.value = (page - 1) * limit.value
}

// Creation form
const newExperiment = reactive({
  name: '',
  featureFlagId: '',
  targetingRuleId: '',
  controlVariationKey: '',
  description: '',
  hypothesis: '',
})

const selectedFlag = computed(() => flags.value.find((f) => f.id === newExperiment.featureFlagId) ?? null)

interface TargetingRule {
  id: string
  name?: string
  conditions?: unknown[]
  rollout?: { variationWeights?: Array<{ variationKey: string; weight: number }> }
}

interface Variation {
  key: string
  name: string
  value: unknown
}

const attachedRuleOptions = computed(() => {
  if (!selectedFlag.value) return []
  const palette = new Set(
    (Array.isArray(selectedFlag.value.variations) ? selectedFlag.value.variations : [])
      .map((variation: Variation) => variation.key),
  )
  const rules: TargetingRule[] = Array.isArray(selectedFlag.value.targetingRules)
    ? selectedFlag.value.targetingRules
    : []
  const options: SelectOption[] = []
  rules.forEach((rule, i) => {
    const involved = new Set(
      (rule.rollout?.variationWeights ?? [])
        .map(weight => weight.variationKey)
        .filter(key => palette.has(key)),
    )
    if (involved.size < 2) return
    const condCount = Array.isArray(rule.conditions) ? rule.conditions.length : 0
    const label = rule.name ? `Rule ${i + 1}: ${rule.name}` : `Rule ${i + 1}: ${condCount} condition${condCount !== 1 ? 's' : ''}`
    options.push({ value: rule.id, label })
  })
  return options
})

const controlVariationOptions = computed<SelectOption[]>(() => {
  const flag = selectedFlag.value
  if (!flag) return []
  const variations: Variation[] = Array.isArray(flag.variations) ? flag.variations : []
  const rules: TargetingRule[] = Array.isArray(flag.targetingRules) ? flag.targetingRules : []
  if (!newExperiment.targetingRuleId) return []
  const rule = rules.find((candidate) => candidate.id === newExperiment.targetingRuleId)
  const weights = rule?.rollout?.variationWeights ?? []
  const involved = newExperiment.targetingRuleId
    ? new Set(weights.map((weight) => weight.variationKey))
    : new Set(variations.map((variation) => variation.key))
  const total = weights.reduce((sum, weight) => sum + weight.weight, 0)
  return variations
    .filter((variation) => involved.has(variation.key))
    .map((variation) => {
      const weight = weights.find((candidate) => candidate.variationKey === variation.key)?.weight
      const share = weight != null && total > 0 ? ` · ${((weight / total) * 100).toFixed(1)}%` : ''
      return {
        value: variation.key,
        label: `${variation.name} (${variation.key}) · ${JSON.stringify(variation.value)}${share}`,
      }
    })
})

watch(
  () => [newExperiment.featureFlagId, newExperiment.targetingRuleId, controlVariationOptions.value],
  () => {
    if (!attachedRuleOptions.value.some((option) => option.value === newExperiment.targetingRuleId)) {
      newExperiment.targetingRuleId = ''
    }
    if (!controlVariationOptions.value.some((option) => option.value === newExperiment.controlVariationKey)) {
      newExperiment.controlVariationKey = ''
    }
  },
  { deep: true },
)

const canCreate = computed(() =>
  newExperiment.name.trim()
  && newExperiment.featureFlagId
  && newExperiment.targetingRuleId
  && newExperiment.controlVariationKey,
)

function openCreateModal() {
  newExperiment.name = ''
  newExperiment.featureFlagId = ''
  newExperiment.targetingRuleId = ''
  newExperiment.controlVariationKey = ''
  newExperiment.description = ''
  newExperiment.hypothesis = ''
  createModalOpen.value = true
}
useCreateFromQuery(openCreateModal)

async function onCreate() {
  if (!canCreate.value) return
  creating.value = true
  try {
    await gqlMutation(addGql, {
      experiment: {
        name: newExperiment.name.trim(),
        featureFlagId: newExperiment.featureFlagId,
        targetingRuleId: newExperiment.targetingRuleId,
        controlVariationKey: newExperiment.controlVariationKey,
        description: newExperiment.description.trim() || null,
        hypothesis: newExperiment.hypothesis.trim() || null,
      },
    })
    createModalOpen.value = false
    toast.success('Experiment created')
    refresh()
  } catch {
    toast.error('Failed to create experiment')
  } finally {
    creating.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    deleteTarget.value = null
    toast.success('Experiment deleted')
    refresh()
  } catch {
    toast.error('Failed to delete experiment')
  } finally {
    deleteLoading.value = false
  }
}

function formatDate(d: string | null): string {
  if (!d) return '—'
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'sample', label: 'Target Sample', width: '130px', align: 'right', muted: true },
  { key: 'created', label: 'Created', width: '130px', muted: true },
]

function getRowActions(): OverflowMenuItem[] {
  return [
    { id: 'open', label: 'View details', icon: 'eye' },
    { id: 'sep', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onRowAction(action: string, row: Experiment) {
  if (action === 'open') router.push(`/experiments/exp/${row.id}`)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Experiments', 'Experiments')"
        title="Experiments"
        :subtitle="`${experiments.length} experiment${experiments.length !== 1 ? 's' : ''}`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreateModal">New Experiment</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Experiments">
      <GlassTable
        :columns="columns"
        :rows="experiments"
        :loading="isLoading && experiments.length === 0"
        empty-text="No experiments found."
        :row-actions="getRowActions"
        arrow
        @row-click="(row: Experiment) => router.push(`/experiments/exp/${row.id}`)"
        @row-action="({ action, row }) => onRowAction(action, row as Experiment)"
      >
        <template #col-name="{ row }">
          <div>
            <div class="cell-name">{{ row.name }}</div>
            <div v-if="row.description" class="cell-desc">{{ row.description }}</div>
          </div>
        </template>
        <template #col-status="{ row }">
          <Badge :color="STATUS_COLORS[row.status] ?? '#6c7388'">{{ row.status }}</Badge>
        </template>
        <template #col-sample="{ row }">
          <span class="mono tabular">{{ row.targetSampleSize?.toLocaleString() ?? '—' }}</span>
        </template>
        <template #col-created="{ row }">
          <span class="mono tabular">{{ formatDate(row.created) }}</span>
        </template>
      </GlassTable>

      <Pagination
        v-if="totalPages > 1"
        :page="currentPage"
        :total-pages="totalPages"
        @prev="goToPage(currentPage - 1)"
        @next="goToPage(currentPage + 1)"
      />
    </SectionCard>

    <!-- Create Modal -->
    <Modal
      v-if="createModalOpen"
      title="New Experiment"
      icon="flask"
      :accent="accent"
      @close="createModalOpen = false"
    >
      <TextInput v-model="newExperiment.name" label="Name" placeholder="Experiment name" />
      <Select
        v-model="newExperiment.featureFlagId"
        label="Feature Flag"
        placeholder="Select a feature flag"
        :options="flags.map((f) => ({ value: f.id, label: `${f.name} (${f.key})` }))"
      />
      <Select
        v-if="selectedFlag"
        v-model="newExperiment.targetingRuleId"
        label="Attached Rule"
        placeholder="Select a targeting rule"
        :options="attachedRuleOptions"
      />
      <Select
        v-if="selectedFlag"
        v-model="newExperiment.controlVariationKey"
        label="Control variation"
        placeholder="Select the baseline variation"
        :options="controlVariationOptions"
      />
      <Textarea
        v-model="newExperiment.description"
        label="Description"
        placeholder="Optional description"
        :rows="3" />
      <Textarea
        v-model="newExperiment.hypothesis"
        label="Hypothesis"
        placeholder="What do you expect to happen?"
        :rows="3" />
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="createModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="creating || !canCreate"
          @click="onCreate">
          {{ creating ? 'Creating…' : 'Create' }}
        </Button>
      </template>
    </Modal>

    <!-- Delete Confirmation -->
    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      message="This will permanently delete the experiment and all associated results, goals, and analysis reports. This action cannot be undone."
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.cell-name {
  font-weight: 500;
  color: var(--fg-0);
}

.cell-desc {
  font-size: 11.5px;
  color: var(--fg-3);
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 360px;
}

.spacer {
  flex: 1;
}
</style>
