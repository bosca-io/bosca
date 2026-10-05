<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'
import type { RecommendationStrategyInput, RecommendationStrategyType, RecommendationStrategyStatus } from '~/types/graphql'

definePageMeta({ middleware: 'recommendations-admin' })

const { accent } = useCurrentSubsystem()
const { mutation } = useGraphQL()
const router = useRouter()

interface StrategyRow {
  id: string
  name: string
  type: string
  status: string
  priority: number
  maxRecommendations: number
  evaluationSchedule: string | null
  lastEvaluated: string | null
}

const listGql = gql`
  query RecommendationStrategies($offset: Long!, $limit: Int!) {
    recommendation {
      strategies {
        all(offset: $offset, limit: $limit) {
          id name type status priority maxRecommendations evaluationSchedule lastEvaluated
        }
      }
    }
  }
`
const { rows, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<StrategyRow>(
  'recommendation-strategies', listGql, {},
  d => (d as { recommendation?: { strategies?: { all?: StrategyRow[] } } })?.recommendation?.strategies?.all,
)

const TYPE_OPTIONS: SelectOption[] = [
  { value: 'TRENDING', label: 'Trending' },
  { value: 'CO_ENGAGEMENT', label: 'People also viewed' },
  { value: 'COHORT_CO_ENGAGEMENT', label: 'People like you' },
  { value: 'PERSONALIZED', label: 'Personalized' },
]
const STATUS_OPTIONS: SelectOption[] = [
  { value: 'DRAFT', label: 'Draft' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'PAUSED', label: 'Paused' },
  { value: 'ARCHIVED', label: 'Archived' },
]

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Strategy', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Type', width: '200px' },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'priority', label: 'Priority', width: '80px', align: 'right', muted: true },
  { key: 'max', label: 'Max', width: '70px', align: 'right', muted: true },
  { key: 'evaluated', label: 'Last run', width: '1fr', muted: true },
]

function typeLabel(type: string): string {
  return TYPE_OPTIONS.find(t => t.value === type)?.label ?? type
}
function statusColor(s: string): string {
  return s === 'ACTIVE' ? '#10b981' : s === 'PAUSED' ? '#f59e0b' : s === 'ARCHIVED' ? '#64748b' : '#94a3b8'
}
function fmtDate(value: string | null): string {
  return value ? new Date(value).toLocaleString() : 'Never'
}

// Create
const showCreate = ref(false)
const saving = ref(false)
const formError = ref('')
const form = reactive({
  name: '',
  description: '',
  type: 'TRENDING',
  status: 'DRAFT',
  priority: 0,
  maxRecommendations: 20,
  evaluationSchedule: '',
  analyticsQueryId: '',
  configuration: '',
  perUserItemCap: DEFAULT_COHORT_CAP,
  perSourceCap: DEFAULT_COHORT_CAP,
})

// Mode-aware analytics-query options: filtered/annotated by what the selected strategy type requires.
const { options: queryOptions, usesQuery, requirementLabel } = useStrategyQueryOptions(toRef(form, 'type'))
// Drop a stale query binding when switching to a type that doesn't use one.
watch(usesQuery, (v) => { if (!v) form.analyticsQueryId = '' })
// "People like you" strategies edit their two tuning caps as number fields instead of raw JSON.
const isCohort = computed(() => form.type === 'COHORT_CO_ENGAGEMENT')

function openCreate() {
  form.name = ''
  form.description = ''
  form.type = 'TRENDING'
  form.status = 'DRAFT'
  form.priority = 0
  form.maxRecommendations = 20
  form.evaluationSchedule = ''
  form.analyticsQueryId = ''
  form.configuration = ''
  form.perUserItemCap = DEFAULT_COHORT_CAP
  form.perSourceCap = DEFAULT_COHORT_CAP
  formError.value = ''
  showCreate.value = true
}

const addGql = gql`
  mutation AddRecommendationStrategy($strategy: RecommendationStrategyInput!) {
    recommendation { strategies { add(strategy: $strategy) { id } } }
  }
`

async function handleCreate() {
  if (!form.name.trim()) { formError.value = 'Name is required.'; return }
  let configuration: unknown
  if (isCohort.value) {
    configuration = buildCohortConfiguration({ perUserItemCap: form.perUserItemCap, perSourceCap: form.perSourceCap })
  } else if (form.configuration.trim()) {
    try {
      configuration = JSON.parse(form.configuration)
    } catch {
      formError.value = 'Configuration must be valid JSON.'
      return
    }
  }
  saving.value = true
  formError.value = ''
  try {
    const strategy: RecommendationStrategyInput = {
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      type: form.type as RecommendationStrategyType,
      status: form.status as RecommendationStrategyStatus,
      priority: form.priority,
      maxRecommendations: form.maxRecommendations,
      evaluationSchedule: form.evaluationSchedule.trim() || undefined,
      analyticsQueryId: form.analyticsQueryId.trim() || undefined,
      configuration,
    }
    const result = await mutation<{ recommendation: { strategies: { add: { id: string } } } }>(
      addGql, { strategy },
    )
    showCreate.value = false
    await refresh()
    const id = result?.recommendation?.strategies?.add?.id
    if (id) router.push(`/recommendations/strategies/${id}`)
  } catch (e: unknown) {
    formError.value = e instanceof Error ? e.message : 'Failed to create strategy'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Recommendations', 'Strategies')"
        title="Strategies"
        subtitle="How content is selected and ranked for each profile">
        <template #actions>
          <Button
            icon="layers"
            size="sm"
            :accent="accent"
            @click="router.push('/recommendations/signals')">Signals</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Strategy</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load strategies — {{ loadError.message }}</div>

    <GlassTable
      :columns="columns"
      :rows="rows"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No strategies yet. Create one to start generating recommendations."
      arrow
      @row-click="(row) => router.push(`/recommendations/strategies/${row.id}`)">
      <template #col-name="{ row }">{{ row.name }}</template>
      <template #col-type="{ row }"><Badge :color="accent">{{ typeLabel(row.type) }}</Badge></template>
      <template #col-status="{ row }"><Badge :color="statusColor(row.status)">{{ row.status }}</Badge></template>
      <template #col-priority="{ row }">{{ row.priority }}</template>
      <template #col-max="{ row }">{{ row.maxRecommendations }}</template>
      <template #col-evaluated="{ row }">{{ fmtDate(row.lastEvaluated) }}</template>
    </GlassTable>
    <ListPager
      v-model:offset="offset"
      :page-size="pageSize"
      :count="rows.length"
      :has-more="hasMore" />

    <Modal
      v-if="showCreate"
      title="New Strategy"
      icon="sliders"
      :accent="accent"
      width="600px"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="e.g. Home feed — content similarity" />
        <Textarea
          v-model="form.description"
          label="Description"
          :rows="2"
          placeholder="How this strategy generates recommendations (optional)" />
        <div class="form-row">
          <Select
            v-model="form.type"
            label="Type"
            :options="TYPE_OPTIONS"
            :accent="accent" />
          <Select
            v-model="form.status"
            label="Status"
            :options="STATUS_OPTIONS"
            :accent="accent" />
        </div>
        <div class="form-row">
          <NumberInput v-model="form.priority" label="Priority" :min="0" />
          <NumberInput v-model="form.maxRecommendations" label="Max per profile" :min="1" />
        </div>
        <TextInput
          v-model="form.evaluationSchedule"
          label="Evaluation schedule (cron)"
          mono
          placeholder="0 3 * * *  — optional" />
        <div v-if="usesQuery" class="field">
          <Select
            v-model="form.analyticsQueryId"
            label="Analytics query"
            placeholder="None — query-less"
            :options="queryOptions"
            :accent="accent" />
          <p class="hint">Query must return {{ requirementLabel }}. Incompatible queries are shown disabled with the reason.</p>
        </div>
        <div v-if="isCohort" class="field">
          <div class="form-row">
            <NumberInput v-model="form.perUserItemCap" label="Items considered per viewer" :min="1" />
            <NumberInput v-model="form.perSourceCap" label="Recommendations kept per item" :min="1" />
          </div>
          <p class="hint">
            Tunes how “people like you” recommendations are computed each night. Higher values consider more
            of each viewer's history and keep more results per item, at a higher nightly compute cost.
          </p>
        </div>
        <Textarea
          v-else
          v-model="form.configuration"
          label="Configuration (JSON)"
          mono
          :rows="4"
          placeholder='{ "lookbackDays": 30 }' />
        <p v-if="formError" class="form-error">{{ formError }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreate">{{ saving ? 'Creating…' : 'Create' }}</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.query-error {
  background: var(--bg-3);
  border-left: 3px solid var(--err, #ff5c5c);
  padding: 8px 10px;
  font-size: 12.5px;
  color: var(--fg-2);
  border-radius: 4px;
  margin-bottom: 12px;
}
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-error { color: var(--err, #ff5c5c); font-size: 12px; margin: 0; }
.field { display: flex; flex-direction: column; gap: 5px; }
.hint { color: var(--fg-3); font-size: 11.5px; margin: 0; }
</style>
