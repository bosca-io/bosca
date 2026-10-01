<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query } = useGraphQL()
const toast = useToast()

const runId = computed(() => route.params.id as string)

const runGql = gql`
  query PipelineRun($id: UUID!) {
    git {
      pipelineRun(id: $id) {
        id pipelineId repositoryId commitSha ref
        triggerType triggeredBy status number
        concurrencyGroup created started finished durationSeconds
        jobs {
          id pipelineRunId name status runnerLabel agentId
          matrixValues dependsOn requirements pipelineRequirements
          requirementsSatisfiedAt requirementsDeadline awaitingRequirements
          requirementsBypassedAt requirementsBypassReason
          errorMessage started finished
          agent {
            id name status
          }
          steps {
            id pipelineJobId name ordinal status exitCode errorMessage started finished
          }
        }
        artifacts {
          id name sizeBytes created
        }
      }
    }
  }
`

interface PipelineStep {
  id: string; pipelineJobId: string; name: string; ordinal: number
  status: string; exitCode: number | null; errorMessage: string | null
  started: string | null; finished: string | null
}

interface PipelineAgent {
  id: string; name: string; status: string
}

interface PipelineJob {
  id: string; pipelineRunId: string; name: string; status: string
  runnerLabel: string; agentId: string | null; agent: PipelineAgent | null
  matrixValues: Record<string, unknown>; dependsOn: string[]
  requirements: unknown; pipelineRequirements: unknown
  requirementsSatisfiedAt: string | null
  requirementsDeadline: string | null; awaitingRequirements: boolean
  requirementsBypassedAt: string | null; requirementsBypassReason: string | null
  errorMessage: string | null
  started: string | null; finished: string | null
  steps: PipelineStep[]
}

interface PipelineArtifact {
  id: string; name: string; sizeBytes: number; created: string
}

interface PipelineRun {
  id: string; pipelineId: string; repositoryId: string; commitSha: string
  ref: string; triggerType: string; triggeredBy: string | null
  status: string; number: number; concurrencyGroup: string | null
  created: string; started: string | null; finished: string | null
  durationSeconds: number | null; jobs: PipelineJob[]; artifacts: PipelineArtifact[]
}

const { data, status, refresh } = useAsyncQuery<{
  git: { pipelineRun: PipelineRun | null }
}>('pipeline-run-detail', runGql, { id: runId }, { server: false })

const run = computed(() => data.value?.git?.pipelineRun ?? null)
const isLoading = computed(() => status.value === 'pending')

const repoName = ref('')
const repositoryCanExecute = ref(false)
watch(() => run.value?.repositoryId, async (repositoryId) => {
  repoName.value = ''
  repositoryCanExecute.value = false
  if (!repositoryId) return
  try {
    const result = await query<{ git: { repositoryById: { name: string; canExecute: boolean } | null } }>(gql`
      query RepoName($id: UUID!) { git { repositoryById(id: $id) { name canExecute } } }
    `, { id: repositoryId })
    if (repositoryId !== run.value?.repositoryId) return
    repoName.value = result.git?.repositoryById?.name ?? ''
    repositoryCanExecute.value = result.git?.repositoryById?.canExecute === true
  } catch { /* ignore */ }
}, { immediate: true })

const breadcrumb = computed(() => {
  const items: (string | { label: string; to?: string })[] = ['Git']
  items.push('Repositories')
  if (run.value && repoName.value) {
    items.push({ label: repoName.value, to: `/git/repositories/${run.value.repositoryId}` })
    items.push({ label: 'Pipelines', to: `/git/repositories/${run.value.repositoryId}?tab=Pipelines` })
  }
  items.push(run.value ? `Run #${run.value.number}` : 'Run')
  return buildBreadcrumb(...items)
})

const isActive = computed(() => {
  const s = run.value?.status
  return s === 'QUEUED' || s === 'RUNNING'
})

// Backend exposes no run-level subscription, so we poll while the
// run is in a transient state. 2s tick keeps the jobs/steps panel
// feeling live without hammering the API. Per-step log streaming
// happens via GitPipelineStepLogs's subscription, not this query.
let pollTimer: ReturnType<typeof setInterval> | null = null

watch(isActive, (active) => {
  if (active && !pollTimer) {
    pollTimer = setInterval(() => refresh(), 2000)
  } else if (!active && pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}, { immediate: true })

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})

const expandedStepId = ref<string | null>(null)

// Surface the failure without an extra click: when the run loads in a
// failed state, expand the first failed step's logs automatically. The
// guard makes this a one-shot so a manual collapse isn't fought by
// later refreshes of the run query.
const autoExpanded = ref(false)
watch(run, (r) => {
  if (!r || autoExpanded.value || expandedStepId.value) return
  if (r.status !== 'FAILURE') return
  for (const job of r.jobs) {
    const failed = job.steps.find(s => s.status === 'FAILURE')
    if (failed) {
      expandedStepId.value = failed.id
      autoExpanded.value = true
      break
    }
  }
}, { immediate: true })

function toggleStepLogs(stepId: string) {
  expandedStepId.value = expandedStepId.value === stepId ? null : stepId
}

function isStepActive(status: string): boolean {
  return status === 'QUEUED' || status === 'RUNNING'
}

const cancelGql = gql`
  mutation CancelPipelineRun($id: UUID!) { git { cancelPipelineRun(id: $id) { id status } } }
`
const rerunGql = gql`
  mutation RerunPipeline($runId: UUID!) { git { rerunPipeline(runId: $runId) { id } } }
`
const cancelJobGql = gql`
  mutation CancelPipelineJob($jobId: UUID!) {
    git {
      cancelPipelineJob(jobId: $jobId) { id status }
    }
  }
`
const rerunJobGql = gql`
  mutation RerunPipelineJob($jobId: UUID!) {
    git {
      rerunPipelineJob(jobId: $jobId) { id status }
    }
  }
`
const rerunFailedJobsGql = gql`
  mutation RerunFailedPipelineJobs($runId: UUID!) {
    git {
      rerunFailedJobs(runId: $runId) { id status }
    }
  }
`
const deleteRunGql = gql`
  mutation DeletePipelineRun($id: UUID!) {
    git {
      deletePipelineRun(id: $id)
    }
  }
`

const runControlBusy = ref(false)
const deleteConfirmOpen = ref(false)
const jobControlId = ref<string | null>(null)
const controlBusy = computed(() => runControlBusy.value || jobControlId.value !== null)

function controlError(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

async function cancelRun() {
  if (!run.value || controlBusy.value) return
  runControlBusy.value = true
  try {
    await query(cancelGql, { id: run.value.id })
    toast.success('Pipeline run cancelled')
    await refresh()
  } catch (error) {
    toast.error(controlError(error, 'Failed to cancel pipeline run'))
  } finally {
    runControlBusy.value = false
  }
}

async function rerunPipeline() {
  if (!run.value || controlBusy.value || !repositoryCanExecute.value) return
  runControlBusy.value = true
  try {
    const result = await query<{ git: { rerunPipeline: { id: string } } }>(rerunGql, { runId: run.value.id })
    toast.success('Pipeline re-triggered')
    router.push(`/git/pipelines/${result.git.rerunPipeline.id}`)
  } catch (error) {
    toast.error(controlError(error, 'Failed to rerun pipeline'))
  } finally {
    runControlBusy.value = false
  }
}

function canCancelJob(job: PipelineJob): boolean {
  return job.status === 'QUEUED' || job.status === 'RUNNING'
}

function isRerunnableJob(job: PipelineJob): boolean {
  return job.status === 'FAILURE' || job.status === 'CANCELLED'
}

function canRerunJob(job: PipelineJob): boolean {
  const runStatus = run.value?.status
  const runIsTerminalFailure = runStatus === 'FAILURE' || runStatus === 'CANCELLED'
  return repositoryCanExecute.value && runIsTerminalFailure && isRerunnableJob(job)
}

const rerunnableJobCount = computed(() => {
  const current = run.value
  if (!current || (current.status !== 'FAILURE' && current.status !== 'CANCELLED')) return 0
  return current.jobs.filter(isRerunnableJob).length
})

function rerunJobTitle(job: PipelineJob): string {
  if (!repositoryCanExecute.value) return 'Execute permission on the repository is required.'
  return canRerunJob(job)
    ? `Rerun only ${job.name}`
    : 'Wait for the pipeline run to finish before rerunning this job.'
}

async function cancelJob(job: PipelineJob) {
  if (controlBusy.value || !canCancelJob(job)) return
  jobControlId.value = job.id
  try {
    await query(cancelJobGql, { jobId: job.id })
    toast.success(`${job.name} cancelled`)
    await refresh()
  } catch (error) {
    toast.error(controlError(error, `Failed to cancel ${job.name}`))
  } finally {
    jobControlId.value = null
  }
}

async function rerunJob(job: PipelineJob) {
  if (controlBusy.value || !canRerunJob(job)) return
  jobControlId.value = job.id
  try {
    await query(rerunJobGql, { jobId: job.id })
    toast.success(`${job.name} queued again`)
    await refresh()
  } catch (error) {
    toast.error(controlError(error, `Failed to rerun ${job.name}`))
  } finally {
    jobControlId.value = null
  }
}

function onBuildAnywayBusy(jobId: string, busy: boolean) {
  if (busy) {
    jobControlId.value = jobId
  } else if (jobControlId.value === jobId) {
    jobControlId.value = null
  }
}

async function onBuildAnywayCompleted() {
  await refresh()
}

async function rerunFailedJobs() {
  const current = run.value
  const count = rerunnableJobCount.value
  if (!current || count < 2 || controlBusy.value || !repositoryCanExecute.value) return

  runControlBusy.value = true
  try {
    await query(rerunFailedJobsGql, { runId: current.id })
    toast.success(`${count} jobs queued again`)
    await refresh()
  } catch (error) {
    toast.error(controlError(error, 'Failed to rerun failed jobs'))
  } finally {
    runControlBusy.value = false
  }
}

async function deleteRun() {
  const current = run.value
  if (!current || isActive.value || controlBusy.value) return

  runControlBusy.value = true
  try {
    await query(deleteRunGql, { id: current.id })
    deleteConfirmOpen.value = false
    toast.success(`Pipeline run #${current.number} deleted`)
    await router.push(`/git/repositories/${current.repositoryId}?tab=Pipelines`)
  } catch (error) {
    toast.error(controlError(error, 'Failed to delete pipeline run'))
  } finally {
    runControlBusy.value = false
  }
}

function statusColor(s: string): string {
  if (s === 'SUCCESS') return 'var(--ok)'
  if (s === 'FAILURE') return 'var(--err)'
  if (s === 'RUNNING') return 'var(--brand-2)'
  if (s === 'CANCELLED') return 'var(--fg-4)'
  if (s === 'QUEUED') return 'var(--warn)'
  if (s === 'SKIPPED') return 'var(--fg-4)'
  return 'var(--fg-3)'
}

function statusBadgeBg(s: string): string {
  return `color-mix(in oklch, ${statusColor(s)} 16%, transparent)`
}

function statusIcon(s: string): string {
  if (s === 'SUCCESS') return 'check'
  if (s === 'FAILURE') return 'x'
  if (s === 'RUNNING') return 'pulse'
  if (s === 'CANCELLED') return 'x'
  if (s === 'QUEUED') return 'dots'
  if (s === 'SKIPPED') return 'arrowsLeftRight'
  return 'dots'
}

function formatTime(d: string | null): string {
  if (!d) return '—'
  return new Date(d).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function formatDuration(sec: number | null): string {
  if (sec == null) return '—'
  if (sec < 60) return `${sec}s`
  const m = Math.floor(sec / 60)
  const s = sec % 60
  if (m < 60) return `${m}m ${s}s`
  const h = Math.floor(m / 60)
  return `${h}h ${m % 60}m`
}

function jobDuration(job: PipelineJob): string {
  if (!job.started || !job.finished) return '—'
  const sec = Math.floor((new Date(job.finished).getTime() - new Date(job.started).getTime()) / 1000)
  return formatDuration(sec)
}

function stepDuration(step: PipelineStep): string {
  if (!step.started || !step.finished) return '—'
  const sec = Math.floor((new Date(step.finished).getTime() - new Date(step.started).getTime()) / 1000)
  return formatDuration(sec)
}

function triggerLabel(type: string): string {
  return type.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase())
}

function requirementObjects(value: unknown): Array<Record<string, unknown>> {
  if (!Array.isArray(value)) return []
  return value.filter((entry): entry is Record<string, unknown> =>
    entry !== null && typeof entry === 'object' && !Array.isArray(entry),
  )
}

function requirementString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}

function artifactRequirementLabels(job: PipelineJob): string[] {
  return requirementObjects(job.requirements).map((requirement) => {
    const type = requirementString(requirement.type) ?? 'artifact'
    const namespace = requirementString(requirement.namespace)
    const coordinate = requirementString(requirement.coordinate) ?? 'unknown coordinate'
    return `${namespace ? `${namespace} / ` : ''}${coordinate} (${type})`
  })
}

function pipelineRequirementLabels(job: PipelineJob): string[] {
  return requirementObjects(job.pipelineRequirements).map((requirement) => {
    const repository = requirementString(requirement.repository) ?? 'unknown repository'
    const pipeline = requirementString(requirement.pipeline) ?? 'unknown pipeline'
    const ref = requirementString(requirement.ref) ?? 'this ref'
    return `${repository} / ${pipeline} @ ${ref}`
  })
}

function waitingLabel(kind: 'artifact' | 'repository', count: number): string {
  return `Waiting on ${kind}${count === 1 ? '' : 's'}`
}

function formatArtifactSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function artifactDownloadUrl(artifact: PipelineArtifact): string {
  if (!run.value) return '#'
  return `/ci/artifacts/${run.value.repositoryId}/${run.value.id}/${artifact.name}`
}

const canCancel = computed(() => run.value && (run.value.status === 'RUNNING' || run.value.status === 'QUEUED'))
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="breadcrumb"
        :title="run ? `Run #${run.number}` : 'Pipeline Run'"
        :subtitle="run ? `${run.status} · ${formatDuration(run.durationSeconds)}` : ''"
      >
        <template v-if="run" #actions>
          <Button
            v-if="canCancel"
            size="sm"
            icon="x"
            :disabled="controlBusy"
            @click="cancelRun">Cancel</Button>
          <Button
            v-if="rerunnableJobCount > 1"
            size="sm"
            icon="refresh"
            :disabled="controlBusy || !repositoryCanExecute"
            :accent="accent"
            :title="repositoryCanExecute ? 'Rerun every failed or cancelled job in this run' : 'Execute permission on the repository is required.'"
            @click="rerunFailedJobs">Rerun failed jobs</Button>
          <Button
            size="sm"
            icon="refresh"
            :disabled="controlBusy || !repositoryCanExecute"
            :accent="accent"
            :title="repositoryCanExecute ? 'Start a new run' : 'Execute permission on the repository is required.'"
            @click="rerunPipeline">Rerun</Button>
          <Button
            v-if="!isActive"
            size="sm"
            icon="trash"
            :disabled="controlBusy"
            @click="deleteConfirmOpen = true">Delete</Button>
          <Button size="sm" icon="pulse" @click="refresh()">Refresh</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !run" class="empty-state">Loading…</div>
    <div v-else-if="!run" class="empty-state">Pipeline run not found.</div>

    <div v-else class="run-content">
      <!-- Summary cards -->
      <div class="summary-row">
        <div class="summary-card">
          <span class="summary-label">Status</span>
          <span class="status-badge" :style="{ background: statusBadgeBg(run.status), color: statusColor(run.status) }">
            <Icon :name="statusIcon(run.status)" :size="12" :color="statusColor(run.status)" />
            {{ run.status }}
          </span>
        </div>
        <div class="summary-card">
          <span class="summary-label">Duration</span>
          <span class="summary-value mono">{{ formatDuration(run.durationSeconds) }}</span>
        </div>
        <div class="summary-card">
          <span class="summary-label">Trigger</span>
          <span class="summary-value">
            <Icon name="git-branch" :size="12" color="var(--fg-2)" />
            {{ triggerLabel(run.triggerType) }}
          </span>
        </div>
        <div class="summary-card">
          <span class="summary-label">Ref</span>
          <span class="summary-value mono">{{ run.ref }}</span>
        </div>
        <div class="summary-card">
          <span class="summary-label">Commit</span>
          <span class="summary-value mono commit-sha">{{ run.commitSha.slice(0, 12) }}</span>
        </div>
      </div>

      <!-- Jobs -->
      <div class="jobs-section">
        <div v-for="job in run.jobs" :key="job.id" class="job-card">
          <div class="job-header">
            <div class="job-status-icon" :style="{ background: statusBadgeBg(job.status) }">
              <Icon :name="statusIcon(job.status)" :size="14" :color="statusColor(job.status)" />
            </div>
            <div class="job-title">
              <div class="job-name-row">
                <span class="job-name">{{ job.name }}</span>
                <span class="job-label-badge mono">{{ job.runnerLabel }}</span>
                <NuxtLink
                  v-if="job.agent"
                  :to="`/git/ci-agents/${job.agent.id}`"
                  class="agent-link"
                >
                  <Icon name="monitor" :size="11" color="var(--fg-3)" />
                  {{ job.agent.name }}
                </NuxtLink>
              </div>
              <span v-if="job.dependsOn.length" class="job-depends">depends on {{ job.dependsOn.join(', ') }}</span>
            </div>
            <div class="job-actions">
              <PipelineBuildAnywayAction
                :job="job"
                :run-status="run.status"
                :disabled="controlBusy || !repositoryCanExecute"
                :disabled-reason="repositoryCanExecute ? undefined : 'Execute permission on the repository is required.'"
                @busy="onBuildAnywayBusy(job.id, $event)"
                @completed="onBuildAnywayCompleted" />
              <Button
                v-if="canCancelJob(job)"
                size="sm"
                icon="x"
                :disabled="controlBusy"
                @click="cancelJob(job)">Cancel job</Button>
              <Button
                v-if="isRerunnableJob(job)"
                size="sm"
                icon="refresh"
                :accent="accent"
                :disabled="controlBusy || !canRerunJob(job)"
                :title="rerunJobTitle(job)"
                @click="rerunJob(job)">Rerun job</Button>
            </div>
            <span class="job-duration mono">{{ jobDuration(job) }}</span>
          </div>

          <div v-if="job.status === 'FAILURE' && job.errorMessage" class="job-error">
            <Icon name="x" :size="12" color="var(--err)" />
            <span class="job-error-message">{{ job.errorMessage }}</span>
          </div>

          <div v-if="job.requirementsBypassedAt" class="job-override">
            <Icon name="alert" :size="12" color="var(--warn)" />
            <div>
              <strong>External requirements bypassed</strong>
              <span>{{ formatTime(job.requirementsBypassedAt) }}</span>
              <span v-if="job.requirementsBypassReason" class="job-override-reason">
                {{ job.requirementsBypassReason }}
              </span>
            </div>
          </div>

          <div v-if="job.awaitingRequirements" class="job-waiting">
            <div class="job-waiting-heading">
              <Icon name="clock" :size="13" color="var(--warn)" />
              <strong>This job is queued until its requirements are available.</strong>
            </div>
            <div v-if="artifactRequirementLabels(job).length" class="job-waiting-group">
              <span class="job-waiting-label">{{ waitingLabel('artifact', artifactRequirementLabels(job).length) }}</span>
              <span
                v-for="requirement in artifactRequirementLabels(job)"
                :key="requirement"
                class="job-waiting-value mono">{{ requirement }}</span>
            </div>
            <div v-if="pipelineRequirementLabels(job).length" class="job-waiting-group">
              <span class="job-waiting-label">{{ waitingLabel('repository', pipelineRequirementLabels(job).length) }}</span>
              <span
                v-for="requirement in pipelineRequirementLabels(job)"
                :key="requirement"
                class="job-waiting-value mono">{{ requirement }}</span>
            </div>
            <span
              v-if="!artifactRequirementLabels(job).length && !pipelineRequirementLabels(job).length"
              class="job-waiting-value">Waiting on requirements</span>
            <span v-if="job.requirementsDeadline" class="job-waiting-deadline">
              Requirement deadline: {{ formatTime(job.requirementsDeadline) }}
            </span>
          </div>

          <!-- Steps -->
          <div class="step-list">
            <div v-for="step in job.steps" :key="step.id" class="step-item">
              <div
                class="step-row"
                :class="{ expanded: expandedStepId === step.id }"
                @click="toggleStepLogs(step.id)"
              >
                <Icon
                  name="chevronDown"
                  :size="12"
                  color="var(--fg-3)"
                  class="step-chevron"
                  :class="{ open: expandedStepId === step.id }"
                />
                <Icon :name="statusIcon(step.status)" :size="12" :color="statusColor(step.status)" />
                <span class="step-name">{{ step.name }}</span>
                <span v-if="step.exitCode != null" class="step-exit mono" :class="{ err: step.exitCode !== 0 }">
                  exit {{ step.exitCode }}
                </span>
                <span class="step-duration mono">{{ stepDuration(step) }}</span>
              </div>
              <div v-if="step.status === 'FAILURE' && step.errorMessage" class="step-error">
                <div class="step-error-title">
                  <Icon name="x" :size="12" color="var(--err)" />
                  Failure
                </div>
                <pre class="step-error-message mono">{{ step.errorMessage }}</pre>
              </div>
              <PipelineStepLogs
                v-if="expandedStepId === step.id"
                :repository-id="run.repositoryId"
                :step-id="step.id"
                :active="isStepActive(step.status)"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- Artifacts -->
      <SectionCard v-if="run.artifacts.length" title="Artifacts" glass>
        <div class="artifact-list">
          <a
            v-for="a in run.artifacts"
            :key="a.id"
            :href="artifactDownloadUrl(a)"
            download
            class="artifact-row">
            <Icon name="boxes" :size="14" :color="accent" />
            <span class="artifact-name mono">{{ a.name }}</span>
            <span class="artifact-size mono">{{ formatArtifactSize(a.sizeBytes) }}</span>
            <span class="artifact-time">{{ formatTime(a.created) }}</span>
            <Icon
              name="download"
              :size="14"
              color="var(--fg-3)"
              class="artifact-download-icon" />
          </a>
        </div>
      </SectionCard>

      <!-- Metadata -->
      <SectionCard title="Details" glass>
        <div class="detail-grid">
          <div class="detail-item">
            <span class="detail-label">Run ID</span>
            <span class="detail-value mono">{{ run.id }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">Pipeline ID</span>
            <span class="detail-value mono">{{ run.pipelineId }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">Repository ID</span>
            <span class="detail-value mono">{{ run.repositoryId }}</span>
          </div>
          <div v-if="run.concurrencyGroup" class="detail-item">
            <span class="detail-label">Concurrency Group</span>
            <span class="detail-value mono">{{ run.concurrencyGroup }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">Created</span>
            <span class="detail-value">{{ formatTime(run.created) }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">Started</span>
            <span class="detail-value">{{ formatTime(run.started) }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">Finished</span>
            <span class="detail-value">{{ formatTime(run.finished) }}</span>
          </div>
        </div>
      </SectionCard>
    </div>

    <ConfirmModal
      v-if="run && deleteConfirmOpen"
      :title="`Delete pipeline run #${run.number}?`"
      subtitle="This permanently removes the run, its job logs, and its artifacts."
      :loading="runControlBusy"
      @close="deleteConfirmOpen = false"
      @confirm="deleteRun" />
  </PageShell>
</template>

<style scoped>
.empty-state { text-align: center; padding: 64px 0; color: var(--fg-3); font-size: 13.5px; }

.run-content { display: flex; flex-direction: column; gap: 16px; }

.summary-row { display: flex; gap: 12px; flex-wrap: wrap; }
.summary-card {
  flex: 1; min-width: 130px; background: var(--bg-1); border: 1px solid var(--line);
  border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 6px;
}
.summary-label { font-size: 11px; color: var(--fg-3); text-transform: uppercase; letter-spacing: 0.05em; font-weight: 600; }
.summary-value { font-size: 13px; color: var(--fg-0); display: flex; align-items: center; gap: 6px; }
.commit-sha { color: v-bind(accent); }

.status-badge {
  display: inline-flex; align-items: center; gap: 5px; padding: 3px 10px;
  border-radius: 8px; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em;
}

.jobs-section { display: flex; flex-direction: column; gap: 14px; }

.job-card { background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }

.job-header {
  display: flex; align-items: center; gap: 12px; padding: 14px 16px;
  border-bottom: 1px solid var(--line);
}
.job-status-icon {
  width: 32px; height: 32px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.job-title { flex: 1; min-width: 0; }
.job-name-row { display: flex; align-items: center; gap: 8px; }
.job-name { font-weight: 600; font-size: 13.5px; color: var(--fg-0); }
.job-label-badge {
  font-size: 10.5px; padding: 2px 8px; border-radius: 5px;
  background: var(--bg-3); color: var(--fg-1); font-weight: 500;
}
.agent-link {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 11px; color: var(--fg-2); text-decoration: none;
  padding: 2px 8px; border-radius: 5px;
  background: color-mix(in oklch, var(--fg-3) 8%, transparent);
  transition: color 0.12s, background 0.12s;
}
.agent-link:hover { color: var(--fg-0); background: color-mix(in oklch, var(--fg-3) 16%, transparent); }
.job-depends { display: block; font-size: 11px; color: var(--fg-3); margin-top: 2px; }
.job-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.job-duration { font-size: 12px; color: var(--fg-2); flex-shrink: 0; }

.job-error {
  display: flex; align-items: flex-start; gap: 8px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--line);
  background: color-mix(in oklch, var(--err) 7%, transparent);
}
.job-error-message {
  font-size: 12px; color: var(--err);
  white-space: pre-wrap; word-break: break-word;
}

.job-override {
  display: flex; align-items: flex-start; gap: 8px;
  padding: 9px 16px;
  color: var(--fg-2);
  font-size: 11.5px;
  border-bottom: 1px solid var(--line);
  background: color-mix(in oklch, var(--warn) 5%, transparent);
}
.job-override > div { display: flex; flex-direction: column; gap: 2px; }
.job-override strong { color: var(--fg-0); font-size: 12px; }
.job-override-reason { color: var(--fg-1); white-space: pre-wrap; overflow-wrap: anywhere; }

.job-waiting {
  display: flex; flex-direction: column; gap: 8px;
  padding: 11px 16px;
  border-bottom: 1px solid var(--line);
  background: color-mix(in oklch, var(--warn) 7%, transparent);
}
.job-waiting-heading { display: flex; align-items: center; gap: 7px; color: var(--fg-0); font-size: 12px; }
.job-waiting-group { display: grid; grid-template-columns: minmax(120px, auto) 1fr; gap: 4px 12px; align-items: baseline; }
.job-waiting-label { color: var(--warn); font-size: 11px; font-weight: 600; }
.job-waiting-value { color: var(--fg-1); font-size: 11.5px; overflow-wrap: anywhere; }
.job-waiting-group .job-waiting-value + .job-waiting-value { grid-column: 2; }
.job-waiting-deadline { color: var(--fg-3); font-size: 11px; }

.step-list { display: flex; flex-direction: column; }
.step-item { border-bottom: 1px solid var(--line); }
.step-item:last-child { border-bottom: none; }

.step-row {
  display: flex; align-items: center; gap: 10px; padding: 8px 16px 8px 16px;
  cursor: pointer; transition: background 0.12s;
}
.step-row:hover { background: var(--bg-2); }
.step-row.expanded { background: color-mix(in oklch, v-bind(accent) 8%, transparent); }

.step-chevron { transition: transform 0.15s ease; flex-shrink: 0; }
.step-chevron.open { transform: rotate(180deg); }

.step-name { flex: 1; font-size: 12.5px; color: var(--fg-1); }
.step-exit { font-size: 10.5px; color: var(--fg-3); }
.step-exit.err { color: var(--err); }
.step-duration { font-size: 11px; color: var(--fg-2); width: 60px; text-align: right; }

.step-error {
  margin: 0 16px 10px 38px;
  border: 1px solid color-mix(in oklch, var(--err) 35%, transparent);
  border-radius: 8px;
  background: color-mix(in oklch, var(--err) 7%, transparent);
  overflow: hidden;
}
.step-error-title {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 12px;
  font-size: 11px; font-weight: 600; color: var(--err);
  text-transform: uppercase; letter-spacing: 0.05em;
  border-bottom: 1px solid color-mix(in oklch, var(--err) 20%, transparent);
}
.step-error-message {
  margin: 0; padding: 10px 12px;
  font-size: 11.5px; line-height: 1.6; color: var(--fg-1);
  white-space: pre-wrap; word-break: break-word;
  max-height: 260px; overflow: auto;
}

.artifact-list { display: flex; flex-direction: column; }
.artifact-row {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 16px; border-bottom: 1px solid var(--line);
  text-decoration: none; cursor: pointer; transition: background 0.12s;
}
.artifact-row:last-child { border-bottom: none; }
.artifact-row:hover { background: var(--bg-2); }
.artifact-row:hover .artifact-download-icon { color: var(--fg-1); }
.artifact-name { font-size: 13px; color: var(--fg-0); flex: 1; }
.artifact-size { font-size: 11px; color: var(--fg-2); }
.artifact-time { font-size: 11px; color: var(--fg-3); }
.artifact-download-icon { flex-shrink: 0; transition: color 0.12s; }

.detail-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; padding: 16px; }
.detail-item { display: flex; flex-direction: column; gap: 4px; }
.detail-label { font-size: 11px; color: var(--fg-3); text-transform: uppercase; letter-spacing: 0.05em; font-weight: 600; }
.detail-value { font-size: 12.5px; color: var(--fg-1); word-break: break-all; }
</style>
