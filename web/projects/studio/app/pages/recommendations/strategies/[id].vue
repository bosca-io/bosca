<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'
import type { RecommendationStrategyInput, RecommendationStrategyType, RecommendationStrategyStatus } from '~/types/graphql'

definePageMeta({ middleware: 'recommendations-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const route = useRoute()
const router = useRouter()

const strategyId = computed(() => route.params.id as string)

interface StrategyDetail {
  id: string
  name: string
  description: string
  type: string
  status: string
  priority: number
  maxRecommendations: number
  analyticsQueryId: string | null
  configuration: unknown
  evaluationSchedule: string | null
  scheduledJobId: string | null
  lastEvaluated: string | null
  created: string
  modified: string
}

const detailGql = gql`
  query GetRecommendationStrategy($id: UUID!) {
    recommendation {
      strategies {
        strategy(id: $id) {
          id name description type status priority maxRecommendations
          analyticsQueryId configuration evaluationSchedule scheduledJobId
          lastEvaluated created modified
        }
      }
    }
  }
`
const { data, status, error, refresh } = useAsyncQuery<{ recommendation: { strategies: { strategy: StrategyDetail | null } } }>(
  'recommendation-strategy', detailGql, { id: strategyId },
)
const strategy = computed(() => data.value?.recommendation?.strategies?.strategy ?? null)
const isMlModel = computed(() => strategy.value?.type === 'PERSONALIZED')
// "People like you" strategies edit their two tuning caps as number fields instead of raw JSON.
const isCohort = computed(() => form.type === 'COHORT_CO_ENGAGEMENT')

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

// Sync form when the strategy loads.
watch(strategy, (s) => {
  if (!s) return
  form.name = s.name
  form.description = s.description ?? ''
  form.type = s.type
  form.status = s.status
  form.priority = s.priority
  form.maxRecommendations = s.maxRecommendations
  form.evaluationSchedule = s.evaluationSchedule ?? ''
  form.analyticsQueryId = s.analyticsQueryId ?? ''
  form.configuration = s.configuration != null ? JSON.stringify(s.configuration, null, 2) : ''
  const caps = readCohortCaps(s.configuration)
  form.perUserItemCap = caps.perUserItemCap
  form.perSourceCap = caps.perSourceCap
}, { immediate: true })

const saving = ref(false)
const evaluating = ref(false)
const actionError = ref('')
const savedAt = ref<string>('')

const editGql = gql`
  mutation EditRecommendationStrategy($id: UUID!, $strategy: RecommendationStrategyInput!) {
    recommendation { strategies { edit(id: $id, strategy: $strategy) { id } } }
  }
`
async function handleSave() {
  if (!form.name.trim()) { actionError.value = 'Name is required.'; return }
  let configuration: unknown
  if (isCohort.value) {
    configuration = buildCohortConfiguration({ perUserItemCap: form.perUserItemCap, perSourceCap: form.perSourceCap })
  } else if (form.configuration.trim()) {
    try {
      configuration = JSON.parse(form.configuration)
    } catch {
      actionError.value = 'Configuration must be valid JSON.'
      return
    }
  }
  saving.value = true
  actionError.value = ''
  try {
    const input: RecommendationStrategyInput = {
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
    await mutation(editGql, { id: strategyId.value, strategy: input })
    savedAt.value = new Date().toLocaleTimeString()
    await refresh()
  } catch (e: unknown) {
    actionError.value = e instanceof Error ? e.message : 'Failed to save strategy'
  } finally {
    saving.value = false
  }
}

const evaluateGql = gql`
  mutation EvaluateRecommendationStrategy($strategyId: UUID!) {
    recommendation { strategies { evaluate(strategyId: $strategyId) { id lastEvaluated } } }
  }
`
async function handleEvaluate() {
  evaluating.value = true
  actionError.value = ''
  try {
    await mutation(evaluateGql, { strategyId: strategyId.value })
    await refresh()
  } catch (e: unknown) {
    actionError.value = e instanceof Error ? e.message : 'Failed to evaluate strategy'
  } finally {
    evaluating.value = false
  }
}

const showDelete = ref(false)
const deleting = ref(false)
const deleteGql = gql`
  mutation DeleteRecommendationStrategy($id: UUID!) {
    recommendation { strategies { delete(id: $id) } }
  }
`
async function handleDelete() {
  deleting.value = true
  try {
    await mutation(deleteGql, { id: strategyId.value })
    router.push('/recommendations/strategies')
  } catch (e: unknown) {
    actionError.value = e instanceof Error ? e.message : 'Failed to delete strategy'
    deleting.value = false
    showDelete.value = false
  }
}

function fmtDate(value: string | null): string {
  return value ? new Date(value).toLocaleString() : 'Never'
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Recommendations', { label: 'Strategies', to: '/recommendations/strategies' }, strategy?.name ?? 'Strategy')"
        :title="strategy?.name ?? 'Strategy'"
        subtitle="Edit configuration and generate recommendations">
        <template #actions>
          <Button
            icon="play"
            size="sm"
            :accent="accent"
            :disabled="evaluating || !strategy"
            @click="handleEvaluate">
            {{ evaluating ? 'Evaluating…' : 'Evaluate now' }}
          </Button>
          <Button
            icon="trash"
            size="sm"
            :disabled="!strategy"
            @click="showDelete = true">Delete</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="error" class="query-error">Couldn't load strategy — {{ error.message }}</div>
    <div v-else-if="status === 'pending' && !strategy" class="state">Loading…</div>
    <div v-else-if="!strategy" class="state">Strategy not found.</div>

    <template v-else>
      <div v-if="isMlModel" class="ml-eval-note">
        <Icon name="info" :size="14" />
        <span>
          <strong>Evaluate now</strong> scores profiles against the trained model served by TF Serving —
          it doesn't train one. Train the model from
          <NuxtLink to="/system/scheduler" class="ml-eval-link">System → Scheduler</NuxtLink>
          (the “Train recommendation model” job); see
          <NuxtLink to="/recommendations/model" class="ml-eval-link">Recommendations → ML Model</NuxtLink>
          for how training and serving fit together.
        </span>
      </div>

      <SectionCard title="Details" padded>
        <div class="form-stack">
          <TextInput v-model="form.name" label="Name" />
          <Textarea v-model="form.description" label="Description" :rows="2" />
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
              Defaults are {{ DEFAULT_COHORT_CAP }} and {{ DEFAULT_COHORT_CAP }}.
            </p>
          </div>
          <Textarea
            v-else
            v-model="form.configuration"
            label="Configuration (JSON)"
            mono
            :rows="6"
            placeholder='{ }' />
          <p v-if="actionError" class="form-error">{{ actionError }}</p>
          <div class="save-row">
            <span v-if="savedAt" class="saved-note">Saved at {{ savedAt }}</span>
            <Button
              primary
              :accent="accent"
              :disabled="saving"
              @click="handleSave">{{ saving ? 'Saving…' : 'Save changes' }}</Button>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="Activity" padded>
        <dl class="meta">
          <div><dt>Last evaluated</dt><dd>{{ fmtDate(strategy.lastEvaluated) }}</dd></div>
          <div><dt>Scheduled job</dt><dd>{{ strategy.scheduledJobId ?? 'None' }}</dd></div>
          <div><dt>Created</dt><dd>{{ fmtDate(strategy.created) }}</dd></div>
          <div><dt>Modified</dt><dd>{{ fmtDate(strategy.modified) }}</dd></div>
        </dl>
      </SectionCard>
    </template>

    <ConfirmModal
      v-if="showDelete"
      title="Delete Strategy"
      subtitle="Deletes the strategy and all recommendations it generated. This cannot be undone."
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete">
      <p>Delete <strong>{{ strategy?.name }}</strong>?</p>
    </ConfirmModal>
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
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-error { color: var(--err, #ff5c5c); font-size: 12px; margin: 0; }
.field { display: flex; flex-direction: column; gap: 5px; }
.hint { color: var(--fg-3); font-size: 11.5px; margin: 0; }
.save-row { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.saved-note { font-size: 12px; color: var(--fg-3); }
.meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; margin: 0; }
.meta dt { font-size: 11.5px; color: var(--fg-3); margin-bottom: 2px; }
.meta dd { font-size: 13px; color: var(--fg-1); margin: 0; font-variant-numeric: tabular-nums; }
.ml-eval-note {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 12px;
  padding: 10px 12px;
  background: var(--bg-2);
  border-left: 3px solid v-bind(accent);
  border-radius: 4px;
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--fg-2);
}
.ml-eval-link { color: v-bind(accent); text-decoration: none; }
.ml-eval-link:hover { text-decoration: underline; }
</style>
