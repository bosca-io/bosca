<script setup lang="ts">
import gql from 'graphql-tag'

interface GuideProgressStatistics {
  activeProgressions: number
  activeProfiles: number
  historicalProgressions: number
  completions: number
  totalProgressions: number
  uniqueProfiles: number
}

interface GuideProgressStep {
  id: number
  metadata: { id: string; name: string } | null
}

interface ActiveGuideProgression {
  profile: { id: string; name: string }
  progress: {
    version: number
    completedStepIds: number[]
    started: string
    modified: string
  }
  steps: GuideProgressStep[]
}

interface GuideProgressResult {
  content: {
    guides: {
      progress: {
        statistics: GuideProgressStatistics
        activeProfiles: GuideProgressProfile[]
      }
    }
  }
}

interface ProfileGuideProgressRecord {
  version: number
  completedStepIds: number[]
  started: string
  modified: string
  guide: { steps: GuideProgressStep[] } | null
}

interface GuideProgressProfile {
  profile: { id: string; name: string }
  progressions: ProfileGuideProgressRecord[]
}

const props = defineProps<{
  guideId: string
  guideName: string
  accent?: string
}>()

const emit = defineEmits<{ close: [] }>()
const { query } = useGraphQL()

const PAGE_SIZE = 50
const statistics = ref<GuideProgressStatistics | null>(null)
const progressions = ref<ActiveGuideProgression[]>([])
const loadedProfileCount = ref(0)
const loading = ref(true)
const loadingMore = ref(false)
const errorMessage = ref('')
const expandedRows = ref<Set<string>>(new Set())

const guideProgressGql = gql`
  query GetGuideProgress($id: UUID!, $limit: Int!, $offset: Long!) {
    content {
      guides {
        progress(id: $id) {
          statistics {
            activeProgressions
            activeProfiles
            historicalProgressions
            completions
            totalProgressions
            uniqueProfiles
          }
          activeProfiles(limit: $limit, offset: $offset) {
            profile { id name }
            progressions {
              version
              completedStepIds
              started
              modified
              guide {
                steps { id metadata { id name } }
              }
            }
          }
        }
      }
    }
  }
`

const hasMore = computed(() =>
  loadedProfileCount.value < (statistics.value?.activeProfiles ?? 0))

const completionRate = computed(() => {
  const total = statistics.value?.totalProgressions ?? 0
  if (!total) return 0
  return Math.round(((statistics.value?.completions ?? 0) / total) * 100)
})

async function loadProgressions(append = false) {
  if (append) loadingMore.value = true
  else loading.value = true
  errorMessage.value = ''
  try {
    const result = await query<GuideProgressResult>(guideProgressGql, {
      id: props.guideId,
      limit: PAGE_SIZE,
      offset: append ? loadedProfileCount.value : 0,
    })
    const guideProgress = result.content.guides.progress
    const loadedProgressions = guideProgress.activeProfiles.flatMap(activeGuideProgressions)
    statistics.value = guideProgress.statistics
    progressions.value = append
      ? [...progressions.value, ...loadedProgressions]
      : loadedProgressions
    loadedProfileCount.value = append
      ? loadedProfileCount.value + guideProgress.activeProfiles.length
      : guideProgress.activeProfiles.length
  } catch (error: unknown) {
    errorMessage.value = error instanceof Error
      ? error.message
      : 'Guide progress could not be loaded.'
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function activeGuideProgressions(profileProgress: GuideProgressProfile): ActiveGuideProgression[] {
  return profileProgress.progressions.map(progress => ({
    profile: profileProgress.profile,
    progress: {
      version: progress.version,
      completedStepIds: progress.completedStepIds,
      started: progress.started,
      modified: progress.modified,
    },
    steps: progress.guide?.steps ?? [],
  }))
}

function rowKey(progression: ActiveGuideProgression): string {
  return `${progression.profile.id}-${progression.progress.version}`
}

function toggleRow(progression: ActiveGuideProgression) {
  const key = rowKey(progression)
  const next = new Set(expandedRows.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedRows.value = next
}

function isExpanded(progression: ActiveGuideProgression): boolean {
  return expandedRows.value.has(rowKey(progression))
}

function completedStepCount(progression: ActiveGuideProgression): number {
  const validStepIds = new Set(progression.steps.map(step => step.id))
  return progression.progress.completedStepIds.filter(id => validStepIds.has(id)).length
}

function percentage(progression: ActiveGuideProgression): number {
  if (!progression.steps.length) return 0
  return Math.round((completedStepCount(progression) / progression.steps.length) * 100)
}

function stepStatus(
  progression: ActiveGuideProgression,
  step: GuideProgressStep,
): 'completed' | 'current' | 'remaining' {
  const completed = new Set(progression.progress.completedStepIds)
  if (completed.has(step.id)) return 'completed'
  const current = progression.steps.find(candidate => !completed.has(candidate.id))
  return current?.id === step.id ? 'current' : 'remaining'
}

function stepStatusLabel(status: ReturnType<typeof stepStatus>): string {
  if (status === 'completed') return 'Completed'
  if (status === 'current') return 'In progress'
  return 'Not started'
}

function formatDate(value: string): string {
  return new Date(value).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  })
}

onMounted(() => loadProgressions())
</script>

<template>
  <Modal
    title="Guide Progress"
    :subtitle="`${guideName} · all guide versions`"
    icon="segment"
    :accent="accent"
    width="min(1040px, calc(100vw - 48px))"
    @close="emit('close')"
  >
    <div class="progress-body">
      <div v-if="loading" class="state-message">
        <Icon name="spinner" :size="18" />
        Loading guide progress…
      </div>

      <div v-else-if="errorMessage" class="state-message state-message--error">
        <Icon name="alert" :size="18" />
        <span>{{ errorMessage }}</span>
        <Button size="sm" icon="refresh" @click="loadProgressions()">Retry</Button>
      </div>

      <template v-else-if="statistics">
        <div class="statistics-grid">
          <div class="statistic-card">
            <span class="statistic-label">Active</span>
            <strong>{{ statistics.activeProgressions.toLocaleString() }}</strong>
            <span>currently in progress</span>
          </div>
          <div class="statistic-card">
            <span class="statistic-label">Historical</span>
            <strong>{{ statistics.historicalProgressions.toLocaleString() }}</strong>
            <span>past progressions</span>
          </div>
          <div class="statistic-card">
            <span class="statistic-label">Completions</span>
            <strong>{{ statistics.completions.toLocaleString() }}</strong>
            <span>{{ completionRate }}% of all progressions</span>
          </div>
          <div class="statistic-card">
            <span class="statistic-label">Profiles</span>
            <strong>{{ statistics.uniqueProfiles.toLocaleString() }}</strong>
            <span>unique participants</span>
          </div>
          <div class="statistic-card">
            <span class="statistic-label">All progressions</span>
            <strong>{{ statistics.totalProgressions.toLocaleString() }}</strong>
            <span>active and historical</span>
          </div>
        </div>

        <section class="active-section">
          <div class="section-heading">
            <div>
              <h3>Profiles in progress</h3>
              <p>Step status is shown for the guide version each profile started.</p>
            </div>
            <span class="section-count mono">
              {{ loadedProfileCount.toLocaleString() }} of {{ statistics.activeProfiles.toLocaleString() }}
            </span>
          </div>

          <div v-if="!progressions.length" class="empty-state">
            No profiles are currently progressing through this guide.
          </div>

          <div v-else class="progression-list">
            <article
              v-for="progression in progressions"
              :key="rowKey(progression)"
              class="progression-card"
            >
              <button
                class="progression-header"
                :aria-expanded="isExpanded(progression)"
                @click="toggleRow(progression)"
              >
                <span class="profile-avatar">{{ progression.profile.name.slice(0, 1).toUpperCase() }}</span>
                <span class="profile-summary">
                  <span class="profile-name">{{ progression.profile.name }}</span>
                  <span class="profile-meta">
                    Version {{ progression.progress.version }} · started {{ formatDate(progression.progress.started) }} · updated {{ formatDate(progression.progress.modified) }}
                  </span>
                </span>
                <span class="completion-summary">
                  <strong>{{ percentage(progression) }}%</strong>
                  <span>{{ completedStepCount(progression) }} of {{ progression.steps.length }} steps</span>
                </span>
                <Icon :name="isExpanded(progression) ? 'chevron-down' : 'chevron'" :size="16" />
              </button>

              <div v-if="isExpanded(progression)" class="step-status-list">
                <div
                  v-for="(step, index) in progression.steps"
                  :key="step.id"
                  class="step-status"
                  :class="`step-status--${stepStatus(progression, step)}`"
                >
                  <span class="step-marker">
                    <Icon
                      :name="stepStatus(progression, step) === 'completed' ? 'check' : stepStatus(progression, step) === 'current' ? 'play' : 'minus'"
                      :size="12"
                    />
                  </span>
                  <span class="step-name">{{ step.metadata?.name ?? `Step ${index + 1}` }}</span>
                  <span class="step-label">{{ stepStatusLabel(stepStatus(progression, step)) }}</span>
                </div>
                <div v-if="!progression.steps.length" class="empty-steps">
                  This guide version has no steps.
                </div>
              </div>
            </article>
          </div>

          <Button
            v-if="hasMore"
            class="load-more"
            size="sm"
            :disabled="loadingMore"
            @click="loadProgressions(true)"
          >
            {{ loadingMore ? 'Loading…' : 'Load more profiles' }}
          </Button>
        </section>
      </template>
    </div>

    <template #footer>
      <span class="spacer" />
      <Button size="sm" @click="emit('close')">Close</Button>
    </template>
  </Modal>
</template>

<style scoped>
.progress-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-height: min(72vh, 760px);
  overflow-y: auto;
  padding-right: 2px;
}

.state-message {
  min-height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--fg-3);
  font-size: 13px;
}

.state-message :deep(svg) { animation: spin 1s linear infinite; }
.state-message--error { color: var(--err); }
.state-message--error :deep(svg) { animation: none; }
@keyframes spin { to { transform: rotate(360deg); } }

.statistics-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.statistic-card {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--bg-2);
}

.statistic-card strong {
  color: var(--fg-0);
  font-size: 24px;
  line-height: 1.2;
}

.statistic-card > span:last-child {
  color: var(--fg-4);
  font-size: 10.5px;
}

.statistic-label {
  color: var(--fg-2);
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.active-section { display: flex; flex-direction: column; gap: 10px; }

.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.section-heading h3 { margin: 0; color: var(--fg-0); font-size: 14px; }
.section-heading p { margin: 3px 0 0; color: var(--fg-3); font-size: 11.5px; }
.section-count { flex-shrink: 0; color: var(--fg-3); font-size: 11px; }

.progression-list { display: flex; flex-direction: column; gap: 6px; }

.progression-card {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--bg-1);
}

.progression-header {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  text-align: left;
}

.progression-header:hover { background: var(--bg-2); }

.profile-avatar {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--fg-0);
  background: color-mix(in oklch, var(--brand-2) 18%, var(--bg-3));
  font-size: 12px;
  font-weight: 700;
}

.profile-summary { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 2px; }
.profile-name { color: var(--fg-0); font-size: 13px; font-weight: 600; }
.profile-meta { overflow: hidden; color: var(--fg-4); font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.completion-summary { display: flex; flex-direction: column; align-items: flex-end; flex-shrink: 0; }
.completion-summary strong { color: var(--fg-0); font-size: 13px; }
.completion-summary span { color: var(--fg-4); font-size: 10.5px; }

.step-status-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 5px;
  padding: 10px 12px 12px 52px;
  border-top: 1px solid var(--line);
  background: var(--bg-2);
}

.step-status {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  padding: 6px 8px;
  border-radius: var(--r-sm);
  color: var(--fg-3);
  background: var(--bg-1);
}

.step-marker {
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--bg-3);
}

.step-status--completed { color: var(--ok); }
.step-status--current { color: var(--brand-1); }
.step-status--completed .step-marker { background: color-mix(in oklch, var(--ok) 16%, var(--bg-3)); }
.step-status--current .step-marker { background: color-mix(in oklch, var(--brand-1) 16%, var(--bg-3)); }
.step-name { min-width: 0; flex: 1; overflow: hidden; color: var(--fg-1); font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }
.step-label { flex-shrink: 0; font-size: 10px; }
.empty-state, .empty-steps { padding: 24px; text-align: center; color: var(--fg-3); font-size: 12px; }
.load-more { align-self: center; }
.spacer { flex: 1; }

@media (max-width: 820px) {
  .statistics-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .step-status-list { grid-template-columns: 1fr; padding-left: 12px; }
}
</style>
