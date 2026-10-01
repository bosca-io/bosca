<script setup lang="ts">
import gql from 'graphql-tag'

const props = defineProps<{ releaseId: string }>()
const { useAsyncQuery } = useGraphQL()

interface StoreState {
  projectId: string
  environmentKey: string
  store: string
  applicationId: string
  appVersion: string
  buildNumber: string | null
  track: string | null
  rolloutPercentage: number | null
  releaseState: string | null
  reviewState: string | null
  betaReviewState: string | null
  buildProcessingState: string | null
  phasedReleaseState: string | null
  testFlightGroups: string[]
  testFlightCrashFeedback: Array<{
    id: string
    comment: string | null
    email: string | null
    deviceModel: string | null
    osVersion: string | null
    createdAt: string | null
  }>
}

interface StoreObservation {
  store: string
  applicationId: string
  appVersion: string
  telemetryType: string
  observedAt: string
  payload: Record<string, unknown>
}

const telemetryGql = gql`
  query ReleaseStoreTelemetry($releaseId: UUID!) {
    workOps { multiRepo { releaseStoreTelemetry(releaseId: $releaseId) {
      states {
        projectId environmentKey store applicationId appVersion buildNumber track rolloutPercentage
        releaseState reviewState betaReviewState buildProcessingState phasedReleaseState testFlightGroups
        testFlightCrashFeedback { id comment email deviceModel osVersion createdAt }
      }
      observations { store applicationId appVersion telemetryType observedAt payload }
    } } }
  }
`
const { data, status, error } = useAsyncQuery<{
  workOps: { multiRepo: { releaseStoreTelemetry: { states: StoreState[]; observations: StoreObservation[] } } }
}>(
  'release-store-telemetry',
  telemetryGql,
  { releaseId: computed(() => props.releaseId || undefined) },
  { server: false },
)

const states = computed(() => data.value?.workOps?.multiRepo?.releaseStoreTelemetry?.states ?? [])
const observations = computed(() => data.value?.workOps?.multiRepo?.releaseStoreTelemetry?.observations ?? [])

function matching(state: StoreState): StoreObservation[] {
  return observations.value.filter(item => item.store === state.store
    && item.applicationId === state.applicationId
    && item.appVersion === state.appVersion)
}

function number(payload: Record<string, unknown>, key: string): number | null {
  const value = payload[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function vitals(state: StoreState): StoreObservation | null {
  return matching(state).find(item => item.telemetryType === 'store.vitals') ?? null
}

function reviews(state: StoreState): Array<Record<string, unknown>> {
  const item = matching(state).find(observation => observation.telemetryType === 'store.reviews')
  const value = item?.payload?.reviews
  return Array.isArray(value) ? value.filter((review): review is Record<string, unknown> => !!review && typeof review === 'object') : []
}

function crashTrend(state: StoreState): number[] {
  return matching(state)
    .filter(item => item.telemetryType === 'store.vitals')
    .map(item => number(item.payload, 'crashRate'))
    .filter((value): value is number => value !== null)
    .reverse()
}

function ratingTrend(state: StoreState): number[] {
  return matching(state)
    .filter(item => item.telemetryType === 'store.reviews')
    .map((item) => {
      const items = item.payload.reviews
      if (!Array.isArray(items)) return null
      const ratings = items
        .filter((review): review is Record<string, unknown> => !!review && typeof review === 'object')
        .map(review => number(review, 'rating'))
        .filter((rating): rating is number => rating !== null)
      return ratings.length ? ratings.reduce((sum, rating) => sum + rating, 0) / ratings.length : null
    })
    .filter((rating): rating is number => rating !== null)
    .reverse()
}

function stateSummary(state: StoreState): string {
  if (state.store === 'GOOGLE_PLAY') {
    const rollout = state.rolloutPercentage === null ? '' : ` · ${state.rolloutPercentage.toFixed(0)}%`
    return `${state.track ?? 'track'} · ${state.releaseState ?? 'unknown'}${rollout}`
  }
  return [state.reviewState, state.phasedReleaseState, state.buildProcessingState].filter(Boolean).join(' · ') || 'Unknown'
}

function reviewText(review: Record<string, unknown>): string {
  const body = review.body
  return typeof body === 'string' && body.trim() ? body : 'No review text'
}

function reviewRating(review: Record<string, unknown>): number | null {
  return number(review, 'rating')
}
</script>

<template>
  <div v-if="status === 'pending' && !data" class="store-state">Loading store telemetry…</div>
  <div v-else-if="error" class="store-state store-error">Store telemetry failed: {{ error.message }}</div>
  <div v-else-if="states.length" class="store-list">
    <article v-for="state in states" :key="`${state.environmentKey}:${state.store}:${state.applicationId}`" class="store-row">
      <div class="store-top">
        <span class="store-mark" :class="state.store === 'GOOGLE_PLAY' ? 'play' : 'apple'" />
        <strong>{{ state.applicationId }}</strong>
        <span class="store-version">{{ state.appVersion }}</span>
        <span class="store-environment">{{ state.environmentKey }}</span>
      </div>
      <p class="store-summary">{{ stateSummary(state) }}</p>
      <div v-if="vitals(state)" class="store-metrics">
        <span>Crash {{ number(vitals(state)!.payload, 'crashRate')?.toFixed(3) ?? '—' }}%</span>
        <span>ANR {{ number(vitals(state)!.payload, 'anrRate')?.toFixed(3) ?? '—' }}%</span>
        <Sparkline
          v-if="crashTrend(state).length > 1"
          :values="crashTrend(state)"
          accent="var(--err, #f87171)"
          :width="56"
          :height="16" />
      </div>
      <p v-if="state.testFlightGroups.length" class="store-detail">
        TestFlight: {{ state.testFlightGroups.join(', ') }}
      </p>
      <div v-if="ratingTrend(state).length" class="store-metrics">
        <span>Rating {{ ratingTrend(state).at(-1)?.toFixed(2) }}★</span>
        <Sparkline
          v-if="ratingTrend(state).length > 1"
          :values="ratingTrend(state)"
          accent="#ffb547"
          :width="56"
          :height="16" />
      </div>
      <div v-if="state.testFlightCrashFeedback.length" class="feedback-list">
        <div v-for="feedback in state.testFlightCrashFeedback.slice(0, 3)" :key="feedback.id" class="review-row">
          <span class="feedback-label">TestFlight crash</span>
          <span>{{ feedback.comment || [feedback.deviceModel, feedback.osVersion].filter(Boolean).join(' · ') || 'No tester comment' }}</span>
        </div>
      </div>
      <div v-if="reviews(state).length" class="review-list">
        <div v-for="review in reviews(state).slice(0, 3)" :key="String(review.id)" class="review-row">
          <span class="review-rating">{{ reviewRating(review) ?? '—' }}★</span>
          <span>{{ reviewText(review) }}</span>
        </div>
      </div>
    </article>
  </div>
</template>

<style scoped>
.store-state { padding: 7px 0; color: var(--fg-3); font-size: 12.5px; }
.store-error { color: var(--err, #f87171); }
.store-list { display: flex; flex-direction: column; }
.store-row { padding: 9px 0; border-top: 1px solid color-mix(in oklch, var(--line) 40%, transparent); }
.store-top { display: flex; align-items: center; gap: 7px; min-width: 0; font-size: 12.5px; }
.store-top strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.store-mark { width: 7px; height: 7px; border-radius: 50%; flex: 0 0 auto; }
.store-mark.play { background: #34a853; }
.store-mark.apple { background: var(--fg-1); }
.store-version { color: var(--fg-2); }
.store-environment { margin-left: auto; color: var(--fg-3); font-size: 10px; }
.store-summary, .store-detail { margin: 3px 0 0 14px; color: var(--fg-2); font-size: 11.5px; }
.store-metrics { display: flex; align-items: center; gap: 8px; margin: 5px 0 0 14px; color: var(--fg-2); font-size: 11.5px; }
.store-metrics > :last-child { margin-left: auto; }
.review-list { display: flex; flex-direction: column; gap: 3px; margin: 7px 0 0 14px; }
.feedback-list { display: flex; flex-direction: column; gap: 3px; margin: 7px 0 0 14px; }
.review-row { display: flex; gap: 7px; color: var(--fg-2); font-size: 11px; line-height: 1.35; }
.review-rating { color: #ffb547; white-space: nowrap; }
.feedback-label { color: var(--err, #f87171); white-space: nowrap; }
</style>
