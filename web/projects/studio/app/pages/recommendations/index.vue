<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

definePageMeta({ middleware: 'recommendations-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()
const router = useRouter()

interface StrategyLite {
  id: string
  name: string
  type: string
  status: string
  priority: number
  maxRecommendations: number
  lastEvaluated: string | null
}
interface PlacementLite { id: string; name: string; slug: string }
interface OverviewData {
  recommendation: {
    strategies: { all: StrategyLite[] }
    placements: { all: PlacementLite[] }
  }
}

const overviewGql = gql`
  query RecommendationsOverview {
    recommendation {
      strategies {
        all(offset: 0, limit: 100) {
          id name type status priority maxRecommendations lastEvaluated
        }
      }
      placements { all { id name slug } }
    }
  }
`
const { data, status, error } = useAsyncQuery<OverviewData>('recommendations-overview', overviewGql)

const strategies = computed(() => data.value?.recommendation?.strategies?.all ?? [])
const placements = computed(() => data.value?.recommendation?.placements?.all ?? [])
const activeCount = computed(() => strategies.value.filter(s => s.status === 'ACTIVE').length)
const mlStrategies = computed(() => strategies.value.filter(s => s.type === 'PERSONALIZED'))
const evaluatedCount = computed(() => strategies.value.filter(s => s.lastEvaluated).length)

const byType = computed(() => {
  const counts = new Map<string, number>()
  for (const s of strategies.value) counts.set(s.type, (counts.get(s.type) ?? 0) + 1)
  return [...counts.entries()].map(([type, count]) => ({ type, count }))
})

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Strategy', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Type', width: '150px' },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'priority', label: 'Priority', width: '90px', align: 'right', muted: true },
  { key: 'evaluated', label: 'Last evaluated', width: '1fr', muted: true },
]

const TYPE_LABELS: Record<string, string> = {
  TRENDING: 'Trending',
  CO_ENGAGEMENT: 'People also viewed',
  COHORT_CO_ENGAGEMENT: 'People like you',
  PERSONALIZED: 'Personalized',
}
function typeLabel(type: string): string {
  return TYPE_LABELS[type] ?? type.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase())
}
function statusColor(s: string): string {
  return s === 'ACTIVE' ? '#10b981' : s === 'PAUSED' ? '#f59e0b' : s === 'ARCHIVED' ? '#64748b' : '#94a3b8'
}
function fmtDate(value: string | null): string {
  return value ? new Date(value).toLocaleString() : 'Never'
}

// One-click setup of the ML-vs-heuristic A/B test. The backend provisions the flag (50/50 split),
// the experiment, and its conversion goals idempotently, created paused — the operator reviews and
// starts it from Experiments.
interface ProvisionResult {
  recommendation: { strategies: { provisionEngineExperiment: { experimentId: string; flagKey: string; created: boolean } } }
}
const provisionAbGql = gql`
  mutation ProvisionRecommendationEngineExperiment {
    recommendation { strategies { provisionEngineExperiment { experimentId flagKey created } } }
  }
`
const provisioningAb = ref(false)
const abExperimentId = ref<string | null>(null)
async function setupAbTest() {
  provisioningAb.value = true
  try {
    const res = await mutation<ProvisionResult>(provisionAbGql)
    const result = res.recommendation.strategies.provisionEngineExperiment
    abExperimentId.value = result.experimentId
    toast.success(result.created
      ? 'A/B test created — ML vs heuristic recommendations. Review and start it in Experiments.'
      : 'A/B test already set up — open it in Experiments.')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to set up the A/B test')
  } finally {
    provisioningAb.value = false
  }
}

// Model-vs-model online A/B: route traffic between two TF Serving model versions and measure which wins.
interface ProvisionModelResult {
  recommendation: { strategies: { provisionModelExperiment: { experimentId: string; flagKey: string; created: boolean } } }
}
const provisionModelGql = gql`
  mutation ProvisionRecommendationModelExperiment($champion: Long!, $challenger: Long!) {
    recommendation {
      strategies {
        provisionModelExperiment(championVersion: $champion, challengerVersion: $challenger) {
          experimentId flagKey created
        }
      }
    }
  }
`
const championVersion = ref<number | null>(null)
const challengerVersion = ref<number | null>(null)
const provisioningModel = ref(false)
const modelExperimentId = ref<string | null>(null)
async function setupModelAbTest() {
  if (championVersion.value == null || challengerVersion.value == null) {
    toast.error('Enter both model versions')
    return
  }
  if (championVersion.value === challengerVersion.value) {
    toast.error('Champion and challenger versions must differ')
    return
  }
  provisioningModel.value = true
  try {
    const res = await mutation<ProvisionModelResult>(provisionModelGql, {
      champion: championVersion.value,
      challenger: challengerVersion.value,
    })
    const result = res.recommendation.strategies.provisionModelExperiment
    modelExperimentId.value = result.experimentId
    toast.success(`Model A/B set up — v${championVersion.value} vs v${challengerVersion.value}. Review and start it in Experiments.`)
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to set up the model A/B test')
  } finally {
    provisioningModel.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Recommendations', 'Overview')"
        title="Recommendations"
        subtitle="Personalized content discovery — strategies, placements, the ML model, and live testing">
        <template #actions>
          <Button
            icon="help"
            size="sm"
            :accent="accent"
            @click="router.push('/recommendations/help')">Guide</Button>
          <Button
            icon="sliders"
            size="sm"
            :accent="accent"
            @click="router.push('/recommendations/strategies')">Strategies</Button>
          <Button
            icon="layers"
            size="sm"
            :accent="accent"
            @click="router.push('/recommendations/signals')">Signals</Button>
          <Button
            icon="play"
            size="sm"
            primary
            :accent="accent"
            @click="router.push('/recommendations/test')">Test feeds</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="error" class="query-error">Couldn't load recommendation data — {{ error.message }}</div>

    <div class="stat-grid">
      <div class="stat" :style="{ '--tile-accent': accent }">
        <span class="stat-value">{{ strategies.length }}</span>
        <span class="stat-label">Strategies</span>
        <span class="stat-sub">{{ activeCount }} active</span>
      </div>
      <div class="stat">
        <span class="stat-value">{{ placements.length }}</span>
        <span class="stat-label">Placements</span>
        <span class="stat-sub">display locations</span>
      </div>
      <div class="stat">
        <span class="stat-value">{{ mlStrategies.length }}</span>
        <span class="stat-label">Personalized strategies</span>
        <span class="stat-sub">
          <NuxtLink class="inline-link" to="/recommendations/model">inspect model →</NuxtLink>
        </span>
      </div>
      <div class="stat">
        <span class="stat-value">{{ evaluatedCount }}</span>
        <span class="stat-label">Evaluated</span>
        <span class="stat-sub">have generated recs</span>
      </div>
    </div>

    <SectionCard title="Strategy mix" subtitle="Strategies by algorithm type" padded>
      <div v-if="byType.length" class="type-mix">
        <div v-for="t in byType" :key="t.type" class="type-chip">
          <Badge :color="accent">{{ typeLabel(t.type) }}</Badge>
          <span class="type-count">{{ t.count }}</span>
        </div>
      </div>
      <p v-else class="muted">No strategies configured yet.</p>
    </SectionCard>

    <SectionCard title="A/B testing" subtitle="Measure the ML recommender against the heuristic, and model versions against each other" padded>
      <div class="ab-test">
        <div class="ab-block">
          <h4 class="ab-heading">Engine: ML vs heuristic</h4>
          <p class="ab-copy muted">
            Splits traffic 50/50 between the ML ranker and the heuristic assembler, with engagement and
            positive-feedback goals. Created paused — review and start it from Experiments.
          </p>
          <div class="ab-actions">
            <Button
              icon="flask"
              size="sm"
              primary
              :accent="accent"
              :disabled="provisioningAb"
              @click="setupAbTest">{{ provisioningAb ? 'Setting up…' : 'Set up engine A/B' }}</Button>
            <NuxtLink v-if="abExperimentId" class="inline-link" :to="`/experiments/exp/${abExperimentId}`">
              Open experiment →
            </NuxtLink>
          </div>
        </div>

        <div class="ab-block">
          <h4 class="ab-heading">Model: version vs version</h4>
          <p class="ab-copy muted">
            Routes traffic 50/50 between two model versions and measures which wins from real engagement
            and positive feedback. Use it when you want product-quality evidence from actual exposure.
          </p>
          <div class="ab-actions">
            <label class="ab-field">Champion
              <input
                v-model.number="championVersion"
                type="number"
                min="1"
                class="ab-input"
                placeholder="e.g. 9">
            </label>
            <label class="ab-field">Challenger
              <input
                v-model.number="challengerVersion"
                type="number"
                min="1"
                class="ab-input"
                placeholder="e.g. 10">
            </label>
            <Button
              icon="beaker"
              size="sm"
              primary
              :accent="accent"
              :disabled="provisioningModel"
              @click="setupModelAbTest">{{ provisioningModel ? 'Setting up…' : 'Set up model A/B' }}</Button>
            <NuxtLink v-if="modelExperimentId" class="inline-link" :to="`/experiments/exp/${modelExperimentId}`">
              Open experiment →
            </NuxtLink>
          </div>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="Strategies" padded>
      <template #right>
        <Button
          size="xs"
          icon="plus"
          :accent="accent"
          @click="router.push('/recommendations/strategies')">Manage</Button>
      </template>
      <GlassTable
        :columns="columns"
        :rows="strategies"
        row-key="id"
        :loading="status === 'pending'"
        empty-text="No strategies yet — create one to start generating recommendations."
        arrow
        @row-click="(row) => router.push(`/recommendations/strategies/${row.id}`)">
        <template #col-name="{ row }">{{ row.name }}</template>
        <template #col-type="{ row }"><Badge :color="accent">{{ typeLabel(row.type) }}</Badge></template>
        <template #col-status="{ row }"><Badge :color="statusColor(row.status)">{{ row.status }}</Badge></template>
        <template #col-priority="{ row }">{{ row.priority }}</template>
        <template #col-evaluated="{ row }">{{ fmtDate(row.lastEvaluated) }}</template>
      </GlassTable>
    </SectionCard>
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
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 16px;
  background: var(--bg-2);
  border: 1px solid var(--border-1, rgba(255, 255, 255, 0.06));
  border-radius: 10px;
}
.stat-value {
  font-size: 26px;
  font-weight: 600;
  color: var(--tile-accent, var(--fg-1));
  line-height: 1.1;
}
.stat-label { font-size: 13px; color: var(--fg-1); }
.stat-sub { font-size: 11.5px; color: var(--fg-3); }
.inline-link { color: var(--fg-2); text-decoration: none; }
.inline-link:hover { color: var(--fg-1); }
.type-mix { display: flex; flex-wrap: wrap; gap: 12px; }
.type-chip { display: inline-flex; align-items: center; gap: 6px; }
.type-count { font-size: 13px; color: var(--fg-2); font-variant-numeric: tabular-nums; }
.muted { color: var(--fg-3); font-size: 13px; margin: 0; }
.ab-test { display: flex; flex-direction: column; gap: 20px; }
.ab-block { display: flex; flex-direction: column; gap: 8px; }
.ab-heading { margin: 0; font-size: 13px; font-weight: 600; color: var(--fg-1); }
.ab-copy { max-width: 64ch; line-height: 1.5; }
.ab-actions { display: flex; align-items: flex-end; gap: 14px; flex-wrap: wrap; }
.ab-field { display: flex; flex-direction: column; gap: 4px; font-size: 11.5px; color: var(--fg-3); }
.ab-input {
  width: 90px; padding: 6px 8px; font-size: 13px;
  background: var(--bg-2); color: var(--fg-1);
  border: 1px solid var(--line-2); border-radius: 6px;
}
.ab-input:focus { outline: none; border-color: var(--line-1); }
</style>
