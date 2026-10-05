<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const rawFingerprint = String(route.params.fingerprint || '')
const fingerprint = computed(() => /^[a-f0-9]{32}$/i.test(rawFingerprint) ? rawFingerprint : '')

if (!fingerprint.value) {
  navigateTo('/analytics/errors')
}

type ErrorGroupStatus = 'OPEN' | 'RESOLVED' | 'IGNORED'

interface ErrorGroup {
  fingerprint: string
  appId: string | null
  type: string
  message: string
  fatal: boolean
  status: ErrorGroupStatus
  firstSeen: string
  lastSeen: string
  eventCount: number
  sampleEventId: string | null
  sampleStack: string | null
  aiSummary: string | null
  aiSummaryAt: string | null
  assigneeId: string | null
}

const errorGroupGql = gql`
  query ErrorGroup($fingerprint: String!) {
    analytics {
      errors {
        group(fingerprint: $fingerprint) {
          fingerprint appId type message fatal status
          firstSeen lastSeen eventCount sampleEventId
          sampleStack aiSummary aiSummaryAt assigneeId
        }
      }
    }
  }
`

const setStatusGql = gql`
  mutation SetErrorGroupStatus($fingerprint: String!, $status: ErrorGroupStatus!) {
    analytics { errors { setStatus(fingerprint: $fingerprint, status: $status) { fingerprint status } } }
  }
`

const analyzeGql = gql`
  mutation AnalyzeErrorGroup($fingerprint: String!) {
    analytics { errors { analyze(fingerprint: $fingerprint) { fingerprint aiSummary aiSummaryAt } } }
  }
`

const { data, status, refresh } = useAsyncQuery<{ analytics: { errors: { group: ErrorGroup | null } } }>(
  'error-group-detail', errorGroupGql, { fingerprint },
)

const group = computed(() => data.value?.analytics?.errors?.group ?? null)
const analyzing = ref(false)

const ANALYSIS_COOLDOWN_MS = 5 * 60 * 1000

const analysisCooldownRemaining = computed(() => {
  const at = group.value?.aiSummaryAt
  if (!at) return 0
  const analyzedAt = new Date(at).getTime()
  if (Number.isNaN(analyzedAt)) return 0
  return Math.max(0, (analyzedAt + ANALYSIS_COOLDOWN_MS) - Date.now())
})

const analyzeDisabled = computed(() => analyzing.value || analysisCooldownRemaining.value > 0)

const analyzeTooltip = computed(() => {
  const remaining = analysisCooldownRemaining.value
  if (remaining <= 0) return undefined
  return `Re-analysis available in ${Math.ceil(remaining / 60_000)}m`
})

async function setStatus(target: ErrorGroupStatus) {
  if (!group.value) return
  try {
    await gqlMutation(setStatusGql, { fingerprint: group.value.fingerprint, status: target })
    toast.success(`Error group ${target.toLowerCase()}`)
    refresh()
  } catch {
    toast.error('Failed to update status')
  }
}

async function analyze() {
  if (!group.value) return
  analyzing.value = true
  try {
    await gqlMutation(analyzeGql, { fingerprint: group.value.fingerprint })
    toast.success('Analysis complete')
    refresh()
  } catch {
    toast.error('Analysis failed')
  } finally {
    analyzing.value = false
  }
}

const STATUS_COLORS: Record<ErrorGroupStatus, string> = {
  OPEN: '#ff5d6c',
  RESOLVED: '#34d99a',
  IGNORED: '#6c7388',
}

const dateFormatter = new Intl.DateTimeFormat('en-US', { dateStyle: 'short', timeStyle: 'medium' })

function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? '—' : dateFormatter.format(d)
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Analytics', 'Errors', group?.type ?? '…')"
        :title="group?.message ?? 'Loading…'"
      >
        <template #title>
          <span class="title-truncated">{{ group?.message ?? 'Loading…' }}</span>
        </template>
        <template #actions>
          <Button
            size="sm"
            icon="pulse"
            :disabled="status === 'pending'"
            @click="refresh()">Refresh</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="group" class="detail-layout">
      <!-- Summary card -->
      <SectionCard title="Summary">
        <div class="card-body">
          <div class="summary-header">
            <div class="badges">
              <Badge :color="STATUS_COLORS[group.status]">{{ group.status }}</Badge>
              <Badge v-if="group.fatal" color="var(--err)">Fatal</Badge>
              <Badge color="var(--fg-3)">{{ group.eventCount.toLocaleString() }} events</Badge>
            </div>
            <div class="status-actions">
              <Button
                v-if="group.status !== 'RESOLVED'"
                size="sm"
                icon="checkmark"
                @click="setStatus('RESOLVED')">Resolve</Button>
              <Button
                v-if="group.status === 'RESOLVED'"
                size="sm"
                icon="pulse"
                @click="setStatus('OPEN')">Reopen</Button>
              <Button v-if="group.status !== 'IGNORED'" size="sm" @click="setStatus('IGNORED')">Ignore</Button>
              <Button v-if="group.status === 'IGNORED'" size="sm" @click="setStatus('OPEN')">Unignore</Button>
            </div>
          </div>

          <div class="error-type mono">{{ group.type }}</div>
          <div class="error-message">{{ group.message }}</div>

          <div class="meta-grid">
            <div class="meta-item">
              <span class="meta-label">App</span>
              <span class="meta-value mono">{{ group.appId ?? '—' }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">Fingerprint</span>
              <span class="meta-value mono">{{ group.fingerprint }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">First seen</span>
              <span class="meta-value">{{ formatDate(group.firstSeen) }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">Last seen</span>
              <span class="meta-value">{{ formatDate(group.lastSeen) }}</span>
            </div>
          </div>
        </div>
      </SectionCard>

      <!-- AI Analysis -->
      <SectionCard title="AI Root Cause Analysis">
        <template #right>
          <Button
            size="sm"
            icon="wand"
            :accent="accent"
            primary
            :disabled="analyzeDisabled"
            :title="analyzeTooltip"
            @click="analyze"
          >
            {{ analyzing ? 'Analyzing…' : group.aiSummary ? 'Re-analyze' : 'Analyze' }}
          </Button>
        </template>
        <div class="card-body">
          <div v-if="group.aiSummary" class="ai-analysis">
            <pre class="ai-text">{{ group.aiSummary }}</pre>
            <div v-if="group.aiSummaryAt" class="ai-timestamp">Generated {{ formatDate(group.aiSummaryAt) }}</div>
          </div>
          <div v-else class="empty-state">
            No AI analysis yet. Click <strong>Analyze</strong> to generate a root cause hypothesis from the stack trace.
          </div>
        </div>
      </SectionCard>

      <!-- Stack Trace -->
      <SectionCard title="Stack Trace">
        <div class="card-body">
          <div v-if="group.sampleStack" class="stack-container">
            <pre class="stack-text">{{ group.sampleStack }}</pre>
          </div>
          <div v-else class="empty-state">
            No stack trace captured for this error group.
          </div>
        </div>
      </SectionCard>
    </div>

    <div v-else-if="status !== 'pending'" class="not-found">
      <p>Error group with fingerprint <code>{{ rawFingerprint }}</code> was not found.</p>
      <Button size="sm" @click="router.push('/analytics/errors')">Back to errors</Button>
    </div>
  </PageShell>
</template>

<style scoped>
.detail-layout {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 1000px;
}

.card-body {
  padding: 14px 16px;
}

.title-truncated {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
  min-width: 0;
}

:deep(.title-row) {
  min-width: 0;
}

:deep(.title) {
  min-width: 0;
  overflow: hidden;
}

.summary-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}

.badges {
  display: flex;
  gap: 6px;
  align-items: center;
}

.status-actions {
  display: flex;
  gap: 6px;
}

.error-type {
  margin-top: 12px;
  font-size: 12.5px;
  color: var(--fg-2);
  word-break: break-all;
}

.error-message {
  margin-top: 4px;
  font-size: 14px;
  font-weight: 500;
  color: var(--fg-0);
  word-break: break-word;
}

.meta-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 24px;
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.meta-label {
  font-size: 10.5px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  font-weight: 600;
}

.meta-value {
  font-size: 12.5px;
  color: var(--fg-1);
}

.ai-analysis {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ai-text {
  font-size: 13px;
  font-family: var(--font-mono);
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--fg-1);
  line-height: 1.6;
  margin: 0;
}

.ai-timestamp {
  font-size: 11px;
  color: var(--fg-3);
}

.stack-container {
  max-height: 400px;
  overflow: auto;
}

.stack-text {
  font-size: 11.5px;
  font-family: var(--font-mono);
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--fg-1);
  line-height: 1.5;
  margin: 0;
}

.empty-state {
  font-size: 13px;
  color: var(--fg-3);
  padding: 8px 0;
}

.not-found {
  text-align: center;
  padding: 48px 0;
  color: var(--fg-3);
  font-size: 13px;
}

.not-found code {
  font-family: var(--font-mono);
  font-size: 11.5px;
}
</style>
