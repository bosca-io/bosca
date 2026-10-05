<script setup lang="ts">
import gql from 'graphql-tag'

/** Native git-ci release cockpit. Its plan and live state come from the same git-ci definitions. */
const props = defineProps<{
  releaseId: string
  projects: Array<{ projectId: string; project: { key: string; name: string } | null }>
}>()
const emit = defineEmits<{ changed: [] }>()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query, mutation } = useGraphQL()
const toast = useToast()

interface PlanInput {
  name: string; type: string; defaultValue: string | null; description: string | null
  options: string[]; required: boolean
}
interface PlanStep { name: string; action: string }
interface PlanJob {
  key: string; environment: string | null; approvalRequired: boolean; needs: string[]
  requirements: string[]; steps: PlanStep[]; depth: number
}
interface ReleasePlan {
  pipelineId: string; pipelineName: string; repositoryId: string; repositorySlug: string
  triggerType: string; environment: string | null; promotesFrom: string | null
  inputs: PlanInput[]; jobs: PlanJob[]
}
interface RunSummary {
  runId: string; status: string; startedAt: string; finishedAt: string | null; durationMs: number | null
}
interface RunStep {
  id: string; name: string; ordinal: number; status: string; uses: string | null
  exitCode: number | null; errorMessage: string | null; started: string | null; finished: string | null
}
interface RunJob {
  id: string; name: string; status: string; dependsOn: string[]; requirements: unknown
  pipelineRequirements: unknown; requirementsDeadline: string | null; awaitingRequirements: boolean
  environment: string | null; approvalRequired: boolean; approvedAt: string | null
  approvalComment: string | null; awaitingApproval: boolean; errorMessage: string | null
  attempt: number; started: string | null; finished: string | null; steps: RunStep[]
}
interface NativeRun {
  id: string; pipelineId: string; repositoryId: string; ref: string; triggerType: string
  status: string; number: number; parameters: Record<string, string>; created: string
  started: string | null; finished: string | null; durationSeconds: number | null; jobs: RunJob[]
}
interface ActionAccess {
  canManage: boolean
  repositories: Array<{ repositoryId: string; canEdit: boolean; canExecute: boolean }>
  environments: Array<{ key: string; canExecute: boolean }>
}

const cockpitGql = gql`
  query ReleasePipelineCockpit($releaseId: UUID!) {
    workOps { crossProject {
      releasePlans(releaseId: $releaseId) {
        pipelineId pipelineName repositoryId repositorySlug triggerType environment promotesFrom
        inputs { name type defaultValue description options required }
        jobs {
          key environment approvalRequired needs requirements depth
          steps { name action }
        }
      }
      releaseRuns(releaseId: $releaseId) { runId status startedAt finishedAt durationMs }
      releaseActionAccess(releaseId: $releaseId) {
        canManage
        repositories { repositoryId canEdit canExecute }
        environments { key canExecute }
      }
    } }
  }
`
const { data, status, refresh: refreshCockpit } = useAsyncQuery<{
  workOps: { crossProject: { releasePlans: ReleasePlan[]; releaseRuns: RunSummary[]; releaseActionAccess: ActionAccess | null } }
}>('release-pipeline-cockpit', cockpitGql, { releaseId: computed(() => props.releaseId) }, { server: false })

const plans = computed(() => data.value?.workOps?.crossProject?.releasePlans ?? [])
const summaries = computed(() => data.value?.workOps?.crossProject?.releaseRuns ?? [])
const access = computed(() => data.value?.workOps?.crossProject?.releaseActionAccess ?? null)
const releasePlans = computed(() => plans.value.filter(p => p.triggerType === 'RELEASE'))
const promotionPlans = computed(() => plans.value.filter(p => p.triggerType === 'PROMOTION'))
const promotionEnvironments = computed(() => [...new Set(promotionPlans.value.map(p => p.environment).filter((v): v is string => !!v))])

const runDetails = ref<Record<string, NativeRun>>({})
const selectedRunId = ref<string | null>(null)
const selectedRun = computed(() => {
  const id = selectedRunId.value ?? summaries.value[0]?.runId
  return id ? runDetails.value[id] ?? null : null
})

const runDetailGql = gql`
  query ReleaseNativeRun($id: UUID!) {
    git { pipelineRun(id: $id) {
      id pipelineId repositoryId ref triggerType status number parameters created started finished durationSeconds
      jobs {
        id name status dependsOn requirements pipelineRequirements requirementsDeadline awaitingRequirements
        environment approvalRequired approvedAt approvalComment awaitingApproval errorMessage attempt started finished
        steps { id name ordinal status uses exitCode errorMessage started finished }
      }
    } }
  }
`
async function loadRunDetails() {
  const entries = await Promise.all(summaries.value.map(async (summary) => {
    try {
      const result = await query<{ git: { pipelineRun: NativeRun | null } }>(runDetailGql, { id: summary.runId })
      return result?.git?.pipelineRun ? [summary.runId, result.git.pipelineRun] as const : null
    }
    catch { return null }
  }))
  runDetails.value = Object.fromEntries(entries.filter((entry): entry is readonly [string, NativeRun] => entry != null))
}
watch(() => summaries.value.map(r => `${r.runId}:${r.status}`).join(','), loadRunDetails, { immediate: true })

async function refresh() {
  await refreshCockpit()
  await loadRunDetails()
}
function openStart() {
  const reason = startDisabledReason.value
  if (reason) { toast.warn(reason); return }
  resetInputs(startValues, inputsFor(releasePlans.value))
  showStart.value = true
}
defineExpose({ refresh, openStart })

let pollTimer: ReturnType<typeof setInterval> | undefined
let lastSnapshot = ''
onMounted(() => {
  pollTimer = setInterval(async () => {
    if (typeof document !== 'undefined' && document.hidden) return
    if (!summaries.value.some(r => isActive(r.status))) return
    await refresh()
    const snapshot = summaries.value.map(r => `${r.runId}:${r.status}`).join(',')
    if (snapshot !== lastSnapshot) {
      lastSnapshot = snapshot
      emit('changed')
    }
  }, 3500)
})
onUnmounted(() => { if (pollTimer) clearInterval(pollTimer) })

function inputsFor(selectedPlans: ReleasePlan[]): PlanInput[] {
  const found = new Map<string, PlanInput>()
  selectedPlans.flatMap(plan => plan.inputs).forEach(input => {
    if (!found.has(input.name)) found.set(input.name, input)
  })
  return [...found.values()]
}
function resetInputs(target: Record<string, string | boolean>, inputs: PlanInput[]) {
  Object.keys(target).forEach(key => Reflect.deleteProperty(target, key))
  inputs.forEach(input => {
    target[input.name] = input.type === 'boolean'
      ? input.defaultValue === 'true'
      : (input.defaultValue ?? input.options[0] ?? '')
  })
}
function inputPayload(values: Record<string, string | boolean>): Record<string, string> {
  return Object.fromEntries(Object.entries(values).map(([key, value]) => [key, String(value)]))
}
function missingRequired(inputs: PlanInput[], values: Record<string, string | boolean>): boolean {
  return inputs.some(input => input.required && String(values[input.name] ?? '').trim() === '')
}

const canManage = computed(() => access.value?.canManage === true)
function canEditRepository(repositoryId: string): boolean {
  return access.value?.repositories.find(r => r.repositoryId === repositoryId)?.canEdit === true
}
function canExecuteRepository(repositoryId: string): boolean {
  return access.value?.repositories.find(r => r.repositoryId === repositoryId)?.canExecute === true
}
function canExecuteEnvironment(key: string | null): boolean {
  return !key || access.value?.environments.find(e => e.key === key)?.canExecute === true
}
function repositoryAccessReason(selectedPlans: ReleasePlan[]): string | null {
  const denied = selectedPlans.find(plan => !canEditRepository(plan.repositoryId))
  if (denied) return `Edit permission is required on ${denied.repositorySlug}.`
  const executionDenied = selectedPlans.find(plan => !canExecuteRepository(plan.repositoryId))
  return executionDenied ? `Execute permission is required on ${executionDenied.repositorySlug}.` : null
}
const startDisabledReason = computed(() => {
  if (!canManage.value) return 'Manage permission on the release program is required.'
  return repositoryAccessReason(releasePlans.value)
})

function sourceReached(plan: ReleasePlan): boolean {
  const source = plan.promotesFrom
  if (!source) return true
  return Object.values(runDetails.value).some(run => {
    if (run.pipelineId !== plan.pipelineId || run.status !== 'SUCCESS') return false
    if (run.triggerType === 'PROMOTION') return run.parameters?.['promotion.environment'] === source
    if (run.triggerType !== 'RELEASE') return false
    return releasePlans.value.some(candidate =>
      candidate.pipelineId === plan.pipelineId && candidate.jobs.some(job => job.environment === source),
    )
  })
}
function promotionDisabledReason(environment: string): string | null {
  const selected = promotionPlans.value.filter(plan => plan.environment === environment)
  if (!canManage.value) return 'Manage permission on the release program is required.'
  const repositoryReason = repositoryAccessReason(selected)
  if (repositoryReason) return repositoryReason
  if (!canExecuteEnvironment(environment)) return `Execute permission on ${environment} is required.`
  const blocked = selected.find(plan => !sourceReached(plan))
  return blocked?.promotesFrom ? `Promote through ${blocked.promotesFrom} first.` : null
}
function environmentHasSuccessfulRun(environment: string): boolean {
  return Object.values(runDetails.value).some(run =>
    run.status === 'SUCCESS' &&
    ((run.triggerType === 'PROMOTION' && run.parameters?.['promotion.environment'] === environment) ||
      (run.triggerType === 'RELEASE' && releasePlans.value.some(plan =>
        plan.pipelineId === run.pipelineId && plan.jobs.some(job => job.environment === environment),
      ))),
  )
}
function rollbackDisabledReason(environment: string): string | null {
  const permissionReason = promotionDisabledReason(environment)
  if (permissionReason && !permissionReason.startsWith('Promote through')) return permissionReason
  if (!environmentHasSuccessfulRun(environment)) return `This release has not reached ${environment}.`
  return null
}

const showStart = ref(false)
const startValues = reactive<Record<string, string | boolean>>({})
const starting = ref(false)
const releaseInputs = computed(() => inputsFor(releasePlans.value))
async function startRelease() {
  if (missingRequired(releaseInputs.value, startValues)) return
  starting.value = true
  try {
    await mutation(gql`
      mutation StartNativeRelease($releaseId: UUID!, $inputs: JSON) {
        workOps { crossProject { launchRelease(releaseId: $releaseId, inputs: $inputs) } }
      }
    `, { releaseId: props.releaseId, inputs: inputPayload(startValues) })
    toast.success('Release started')
    showStart.value = false
    await refresh()
    emit('changed')
  }
  catch (error: unknown) { toast.error(error instanceof Error ? error.message : 'Failed to start the release') }
  finally { starting.value = false }
}

const showPromote = ref(false)
const promotionEnvironment = ref('')
const promotionValues = reactive<Record<string, string | boolean>>({})
const allowDowngrade = ref(false)
const promoting = ref(false)
const selectedPromotionPlans = computed(() => promotionPlans.value.filter(p => p.environment === promotionEnvironment.value))
const promotionInputs = computed(() => inputsFor(selectedPromotionPlans.value))
function openPromotion(environment: string) {
  const reason = promotionDisabledReason(environment)
  if (reason) { toast.warn(reason); return }
  promotionEnvironment.value = environment
  allowDowngrade.value = false
  resetInputs(promotionValues, inputsFor(promotionPlans.value.filter(p => p.environment === environment)))
  showPromote.value = true
}
async function promote() {
  if (missingRequired(promotionInputs.value, promotionValues)) return
  promoting.value = true
  try {
    await mutation(gql`
      mutation PromoteNativeRelease($releaseId: UUID!, $input: PromoteWorkOpsReleaseInput!) {
        workOps { crossProject { promoteRelease(releaseId: $releaseId, input: $input) } }
      }
    `, {
      releaseId: props.releaseId,
      input: {
        environment: promotionEnvironment.value,
        allowDowngrade: allowDowngrade.value,
        inputs: inputPayload(promotionValues),
      },
    })
    toast.success(`Promotion to ${promotionEnvironment.value} started`)
    showPromote.value = false
    await refresh()
    emit('changed')
  }
  catch (error: unknown) { toast.error(error instanceof Error ? error.message : 'Failed to start the promotion') }
  finally { promoting.value = false }
}

const showRollback = ref(false)
const rollbackEnvironment = ref('')
const rollbackRevision = ref(0)
const rollingBack = ref(false)
function openRollback(environment: string) {
  const reason = rollbackDisabledReason(environment)
  if (reason) { toast.warn(reason); return }
  rollbackEnvironment.value = environment
  rollbackRevision.value = 0
  showRollback.value = true
}
async function rollbackEnvironmentNow() {
  rollingBack.value = true
  try {
    const result = await mutation<{ workOps: { crossProject: { rollbackReleaseEnvironment: string[] } } }>(gql`
      mutation RollbackReleaseEnvironment($releaseId: UUID!, $environmentKey: String!, $toRevision: Int!) {
        workOps { crossProject { rollbackReleaseEnvironment(releaseId: $releaseId, environmentKey: $environmentKey, toRevision: $toRevision) } }
      }
    `, { releaseId: props.releaseId, environmentKey: rollbackEnvironment.value, toRevision: rollbackRevision.value })
    const count = result?.workOps?.crossProject?.rollbackReleaseEnvironment.length ?? 0
    toast.success(`${count} ${rollbackEnvironment.value} target${count === 1 ? '' : 's'} rolled back`)
    showRollback.value = false
    await refresh(); emit('changed')
  }
  catch (error: unknown) { toast.error(error instanceof Error ? error.message : 'Failed to roll back the environment') }
  finally { rollingBack.value = false }
}

const busyRun = ref('')
const busyJob = ref('')
const controlBusy = computed(() => busyRun.value !== '' || busyJob.value !== '')

async function cancelRun(run: NativeRun) {
  if (controlBusy.value) return
  busyRun.value = run.id
  try {
    await mutation(gql`mutation CancelReleaseRun($id: UUID!) { git { cancelPipelineRun(id: $id) { id status } } }`, { id: run.id })
    toast.success('Run cancelled')
    await refresh(); emit('changed')
  }
  catch (error: unknown) { toast.error(error instanceof Error ? error.message : 'Failed to cancel the run') }
  finally { busyRun.value = '' }
}

function canRerunJob(run: NativeRun, job: RunJob): boolean {
  const terminalRun = run.status === 'FAILURE' || run.status === 'CANCELLED'
  return terminalRun && isRerunnableJob(job)
}

function isRerunnableJob(job: RunJob): boolean {
  return job.status === 'FAILURE' || job.status === 'CANCELLED'
}

function rerunJobDisabledReason(run: NativeRun, job: RunJob): string | null {
  if (!canExecuteRepository(run.repositoryId)) return 'Execute permission on the repository is required.'
  if (!canRerunJob(run, job)) {
    return 'Wait for the pipeline run to finish before rerunning this job.'
  }
  return null
}

async function cancelJob(job: RunJob) {
  if (controlBusy.value) return
  busyJob.value = job.id
  try {
    await mutation(gql`mutation CancelReleaseJob($jobId: UUID!) { git { cancelPipelineJob(jobId: $jobId) { id status } } }`, { jobId: job.id })
    toast.success(`${job.name} cancelled`)
    await refresh(); emit('changed')
  }
  catch (error: unknown) { toast.error(error instanceof Error ? error.message : `Failed to cancel ${job.name}`) }
  finally { busyJob.value = '' }
}

async function rerunJob(job: RunJob) {
  if (controlBusy.value) return
  busyJob.value = job.id
  try {
    await mutation(gql`mutation RerunReleaseJob($jobId: UUID!) { git { rerunPipelineJob(jobId: $jobId) { id status } } }`, { jobId: job.id })
    toast.success(`${job.name} queued again`)
    await refresh(); emit('changed')
  }
  catch (error: unknown) { toast.error(error instanceof Error ? error.message : `Failed to rerun ${job.name}`) }
  finally { busyJob.value = '' }
}

const approvalComments = reactive<Record<string, string>>({})
async function decideJob(job: RunJob, approve: boolean) {
  const run = selectedRun.value
  if (!run || controlBusy.value) return
  busyJob.value = job.id
  try {
    const document = approve
      ? gql`mutation ApproveReleaseJob($jobId: UUID!, $comment: String) { git { approvePipelineJob(jobId: $jobId, comment: $comment) { id status } } }`
      : gql`mutation RejectReleaseJob($jobId: UUID!, $comment: String) { git { rejectPipelineJob(jobId: $jobId, comment: $comment) { id status } } }`
    await mutation(document, { jobId: job.id, comment: approvalComments[job.id]?.trim() || null })
    toast.success(approve ? 'Job approved' : 'Job rejected')
    await refresh(); emit('changed')
  }
  catch (error: unknown) { toast.error(error instanceof Error ? error.message : 'Failed to record the decision') }
  finally { busyJob.value = '' }
}

const showPatch = ref(false)
const patchProjects = ref<string[]>([])
const patching = ref(false)
async function startPatch() {
  patching.value = true
  try {
    const result = await mutation<{ workOps: { crossProject: { startPatchRelease: { id: string } } } }>(gql`
      mutation StartReleasePatch($releaseId: UUID!, $input: StartWorkOpsPatchReleaseInput!) {
        workOps { crossProject { startPatchRelease(releaseId: $releaseId, input: $input) { id } } }
      }
    `, { releaseId: props.releaseId, input: { projectIds: patchProjects.value } })
    toast.success('Patch release created')
    showPatch.value = false
    const id = result?.workOps?.crossProject?.startPatchRelease.id
    if (id) await navigateTo(`/workops/releases/${id}`)
  }
  catch (error: unknown) { toast.error(error instanceof Error ? error.message : 'Failed to create the patch release') }
  finally { patching.value = false }
}

function planColumns(plan: ReleasePlan): Array<{ depth: number; jobs: PlanJob[] }> {
  const depths = [...new Set(plan.jobs.map(job => job.depth))].sort((a, b) => a - b)
  return depths.map(depth => ({ depth, jobs: plan.jobs.filter(job => job.depth === depth) }))
}
function isActive(value: string): boolean { return value === 'QUEUED' || value === 'RUNNING' }
function statusColor(value: string): string {
  if (value === 'SUCCESS') return 'var(--ok, #34d399)'
  if (value === 'FAILURE') return 'var(--err, #f87171)'
  if (value === 'RUNNING') return 'var(--info, #38bdf8)'
  if (value === 'QUEUED') return '#ffb547'
  return 'var(--fg-3)'
}
function phaseState(plan: ReleasePlan): string {
  const matching = Object.values(runDetails.value).filter(run =>
    run.pipelineId === plan.pipelineId && run.triggerType === plan.triggerType &&
    (plan.triggerType !== 'PROMOTION' || run.parameters?.['promotion.environment'] === plan.environment),
  )
  return matching[0]?.status ?? 'UPCOMING'
}
function requirementLabels(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value.map((entry: unknown) => {
    if (!entry || typeof entry !== 'object') return String(entry)
    const item = entry as Record<string, unknown>
    if (item.pipeline) return `${item.repository}/${item.pipeline}@${item.ref ?? 'this ref'}`
    return `${item.type ?? 'artifact'}:${item.namespace ?? ''}:${item.coordinate ?? ''}`
  })
}
function parkedReason(job: RunJob, run: NativeRun): string | null {
  if (job.awaitingApproval) return 'Awaiting approval'
  if (job.awaitingRequirements) {
    const gates = [...requirementLabels(job.requirements), ...requirementLabels(job.pipelineRequirements)]
    return gates.length ? `Waiting for ${gates.join(', ')}` : 'Waiting for requirements'
  }
  if (job.status === 'QUEUED' && job.dependsOn.length) {
    const waiting = job.dependsOn.filter(name => run.jobs.find(candidate => candidate.name === name)?.status !== 'SUCCESS')
    if (waiting.length) return `Waiting for ${waiting.join(', ')}`
  }
  return null
}
function formatRelative(value: string): string {
  const minutes = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 60_000))
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  return hours < 24 ? `${hours}h ago` : `${Math.floor(hours / 24)}d ago`
}
</script>

<template>
  <SectionCard title="Release pipelines" subtitle="Native git-ci plans and runs from repository-owned YAML" padded>
    <div v-if="status === 'pending' && !data" class="empty-state">Loading release plans…</div>
    <div v-else-if="!plans.length" class="empty-state">No attached repository declares a release or promotion trigger.</div>
    <template v-else>
      <div class="section-heading">
        <span>Plan</span>
        <span class="section-help">Exact jobs selected before a run is created</span>
      </div>

      <div class="phase-list">
        <article v-for="plan in releasePlans" :key="`release-${plan.pipelineId}`" class="phase-card">
          <header class="phase-head">
            <div>
              <span class="phase-kicker">Release · {{ plan.repositorySlug }}</span>
              <h4>{{ plan.pipelineName }}</h4>
            </div>
            <Badge :color="statusColor(phaseState(plan))">{{ phaseState(plan) }}</Badge>
          </header>
          <div class="plan-columns">
            <div v-for="column in planColumns(plan)" :key="column.depth" class="plan-column">
              <span class="column-label">{{ column.depth === 0 ? 'Starts' : `After ${column.depth}` }}</span>
              <div v-for="job in column.jobs" :key="job.key" class="plan-job">
                <div class="job-line"><strong>{{ job.key }}</strong><span v-if="job.approvalRequired" class="approval-chip">approval</span></div>
                <span v-if="job.environment" class="job-meta">{{ job.environment }}</span>
                <span v-if="job.requirements.length" class="job-meta">{{ job.requirements.join(' · ') }}</span>
                <span v-if="job.steps.length" class="job-meta">{{ job.steps.map(step => step.action).join(' → ') }}</span>
                <span v-else class="job-meta">checkpoint</span>
              </div>
            </div>
          </div>
        </article>

        <article v-for="environment in promotionEnvironments" :key="environment" class="phase-card promotion-card">
          <header class="phase-head">
            <div>
              <span class="phase-kicker">Promotion</span>
              <h4>{{ environment }}</h4>
              <span v-if="promotionPlans.find(p => p.environment === environment)?.promotesFrom" class="phase-source">
                after {{ promotionPlans.find(p => p.environment === environment)?.promotesFrom }}
              </span>
            </div>
            <div class="phase-actions">
              <Button
                size="sm"
                icon="undo"
                :disabled="!!rollbackDisabledReason(environment)"
                :title="rollbackDisabledReason(environment) ?? `Roll back this release in ${environment}`"
                @click="openRollback(environment)">Rollback</Button>
              <Button
                size="sm"
                icon="arrow-right"
                :disabled="!!promotionDisabledReason(environment)"
                :title="promotionDisabledReason(environment) ?? `Promote this release to ${environment}`"
                @click="openPromotion(environment)">Promote</Button>
            </div>
          </header>
          <div v-for="plan in promotionPlans.filter(p => p.environment === environment)" :key="plan.pipelineId" class="promotion-plan">
            <div class="promotion-title">
              <span>{{ plan.repositorySlug }} · {{ plan.pipelineName }}</span>
              <Badge :color="statusColor(phaseState(plan))">{{ phaseState(plan) }}</Badge>
            </div>
            <div class="plan-columns">
              <div v-for="column in planColumns(plan)" :key="column.depth" class="plan-column">
                <span class="column-label">{{ column.depth === 0 ? 'Starts' : `After ${column.depth}` }}</span>
                <div v-for="job in column.jobs" :key="job.key" class="plan-job">
                  <div class="job-line"><strong>{{ job.key }}</strong><span v-if="job.approvalRequired" class="approval-chip">approval</span></div>
                  <span v-if="job.requirements.length" class="job-meta">{{ job.requirements.join(' · ') }}</span>
                  <span v-if="job.steps.length" class="job-meta">{{ job.steps.map(step => step.action).join(' → ') }}</span>
                  <span v-else class="job-meta">checkpoint</span>
                </div>
              </div>
            </div>
          </div>
        </article>
      </div>

      <div class="section-heading runs-heading">
        <span>Runs</span>
        <div class="run-heading-actions">
          <Button
            size="sm"
            icon="plus"
            :disabled="!canManage"
            title="Create a roll-forward patch release for selected projects"
            @click="patchProjects = []; showPatch = true">Patch release</Button>
          <Button size="sm" icon="refresh" @click="refresh">Refresh</Button>
        </div>
      </div>

      <div v-if="!summaries.length" class="empty-state">No native git-ci runs yet. Start the release when the plan is ready.</div>
      <template v-else>
        <div class="run-strip">
          <button
            v-for="summary in summaries"
            :key="summary.runId"
            type="button"
            class="run-chip"
            :class="{ active: (selectedRunId ?? summaries[0]?.runId) === summary.runId }"
            @click="selectedRunId = summary.runId">
            <span class="status-dot" :style="{ background: statusColor(summary.status) }" />
            <span>{{ summary.status }}</span>
            <span class="muted">{{ formatRelative(summary.startedAt) }}</span>
          </button>
        </div>

        <div v-if="selectedRun" class="run-panel">
          <header class="run-head">
            <div>
              <span class="phase-kicker">{{ selectedRun.triggerType }} · run #{{ selectedRun.number }}</span>
              <h4>{{ selectedRun.parameters?.['promotion.environment'] ?? selectedRun.ref }}</h4>
            </div>
            <div class="run-actions">
              <Button
                v-if="isActive(selectedRun.status)"
                size="sm"
                icon="x"
                :disabled="controlBusy || !canEditRepository(selectedRun.repositoryId)"
                :title="canEditRepository(selectedRun.repositoryId) ? 'Cancel this native run' : 'Edit permission on the repository is required.'"
                @click="cancelRun(selectedRun)">Cancel</Button>
              <NuxtLink :to="`/git/pipelines/${selectedRun.id}`" class="details-link">Logs and details</NuxtLink>
            </div>
          </header>

          <div class="live-jobs">
            <article v-for="job in selectedRun.jobs" :key="job.id" class="live-job">
              <header class="live-job-head">
                <span class="status-dot" :style="{ background: statusColor(job.status) }" />
                <strong>{{ job.name }}</strong>
                <span v-if="job.attempt > 1" class="attempt">attempt {{ job.attempt }}</span>
                <span class="job-status" :style="{ color: statusColor(job.status) }">{{ job.status }}</span>
                <div class="live-job-actions">
                  <Button
                    v-if="isActive(job.status)"
                    size="sm"
                    icon="x"
                    :disabled="controlBusy || !canEditRepository(selectedRun.repositoryId)"
                    :title="canEditRepository(selectedRun.repositoryId) ? `Cancel ${job.name}` : 'Edit permission on the repository is required.'"
                    @click="cancelJob(job)">Cancel job</Button>
                  <Button
                    v-if="isRerunnableJob(job)"
                    size="sm"
                    icon="refresh"
                    :disabled="controlBusy || !!rerunJobDisabledReason(selectedRun, job)"
                    :title="rerunJobDisabledReason(selectedRun, job) ?? `Rerun only ${job.name}`"
                    @click="rerunJob(job)">Rerun job</Button>
                </div>
              </header>
              <p v-if="parkedReason(job, selectedRun)" class="parked">{{ parkedReason(job, selectedRun) }}</p>
              <p v-if="job.errorMessage" class="job-error">{{ job.errorMessage }}</p>
              <ul v-if="job.steps.length" class="step-list">
                <li v-for="step in job.steps" :key="step.id">
                  <span class="status-dot small" :style="{ background: statusColor(step.status) }" />
                  <span>{{ step.name }}</span>
                  <span class="muted">{{ step.uses ?? 'run' }}</span>
                  <span v-if="step.errorMessage" class="step-error">{{ step.errorMessage }}</span>
                </li>
              </ul>
              <div v-if="job.awaitingApproval" class="approval-actions">
                <input v-model="approvalComments[job.id]" class="comment-input" placeholder="Decision comment (optional)">
                <Button
                  size="sm"
                  :disabled="controlBusy || !canExecuteRepository(selectedRun.repositoryId) || !canExecuteEnvironment(job.environment)"
                  :title="!canExecuteRepository(selectedRun.repositoryId) ? 'Execute permission on the repository is required.' : (!canExecuteEnvironment(job.environment) ? `Execute permission on ${job.environment} is required.` : 'Reject this job')"
                  @click="decideJob(job, false)">Reject</Button>
                <Button
                  primary
                  size="sm"
                  :accent="accent"
                  :disabled="controlBusy || !canExecuteRepository(selectedRun.repositoryId) || !canExecuteEnvironment(job.environment)"
                  :title="!canExecuteRepository(selectedRun.repositoryId) ? 'Execute permission on the repository is required.' : (!canExecuteEnvironment(job.environment) ? `Execute permission on ${job.environment} is required.` : 'Approve this job')"
                  @click="decideJob(job, true)">Approve</Button>
              </div>
            </article>
          </div>
        </div>
      </template>
    </template>

    <Modal
      v-if="showStart"
      title="Start release"
      icon="play"
      :accent="accent"
      width="520px"
      @close="showStart = false">
      <div class="dialog-stack">
        <p class="dialog-copy">Creates native RELEASE runs for the repository plans shown here.</p>
        <div v-for="input in releaseInputs" :key="input.name" class="input-row">
          <label>
            <span>{{ input.name }}<em v-if="input.required">required</em></span>
            <small v-if="input.description">{{ input.description }}</small>
          </label>
          <input v-if="input.type === 'boolean'" v-model="startValues[input.name]" type="checkbox">
          <select v-else-if="input.type === 'choice'" v-model="startValues[input.name]" class="native-field">
            <option v-for="option in input.options" :key="option" :value="option">{{ option }}</option>
          </select>
          <input
            v-else
            v-model="startValues[input.name]"
            :type="input.type === 'number' ? 'number' : 'text'"
            class="native-field">
        </div>
        <p v-if="!releaseInputs.length" class="dialog-copy">These release triggers declare no operator inputs.</p>
      </div>
      <template #footer>
        <Button :disabled="starting" @click="showStart = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="starting || missingRequired(releaseInputs, startValues)"
          @click="startRelease">Start release</Button>
      </template>
    </Modal>

    <Modal
      v-if="showPromote"
      :title="`Promote to ${promotionEnvironment}`"
      icon="arrow-right"
      :accent="accent"
      width="520px"
      @close="showPromote = false">
      <div class="dialog-stack">
        <div v-for="input in promotionInputs" :key="input.name" class="input-row">
          <label><span>{{ input.name }}<em v-if="input.required">required</em></span><small v-if="input.description">{{ input.description }}</small></label>
          <input v-if="input.type === 'boolean'" v-model="promotionValues[input.name]" type="checkbox">
          <select v-else-if="input.type === 'choice'" v-model="promotionValues[input.name]" class="native-field">
            <option v-for="option in input.options" :key="option" :value="option">{{ option }}</option>
          </select>
          <input
            v-else
            v-model="promotionValues[input.name]"
            :type="input.type === 'number' ? 'number' : 'text'"
            class="native-field">
        </div>
        <label class="downgrade-row"><input v-model="allowDowngrade" type="checkbox"><span>Allow a version downgrade</span></label>
        <p v-if="allowDowngrade" class="danger-copy">This bypasses the environment's downgrade guard. The pipeline's approval and health gates still apply.</p>
      </div>
      <template #footer>
        <Button :disabled="promoting" @click="showPromote = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="promoting || missingRequired(promotionInputs, promotionValues)"
          @click="promote">Start promotion</Button>
      </template>
    </Modal>

    <Modal
      v-if="showPatch"
      title="Create patch release"
      icon="plus"
      :accent="accent"
      width="520px"
      @close="showPatch = false">
      <div class="dialog-stack">
        <p class="dialog-copy">Select the affected projects. Their versions are patch-bumped into a new roll-forward release.</p>
        <label v-for="project in projects" :key="project.projectId" class="project-choice">
          <input v-model="patchProjects" type="checkbox" :value="project.projectId">
          <strong>{{ project.project?.key ?? project.projectId.slice(0, 8) }}</strong>
          <span>{{ project.project?.name ?? 'Project' }}</span>
        </label>
      </div>
      <template #footer>
        <Button :disabled="patching" @click="showPatch = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="patching || !patchProjects.length"
          @click="startPatch">Create patch release</Button>
      </template>
    </Modal>

    <Modal
      v-if="showRollback"
      :title="`Roll back ${rollbackEnvironment}`"
      icon="undo"
      :accent="accent"
      width="500px"
      @close="showRollback = false">
      <div class="dialog-stack">
        <p class="dialog-copy">Rolls back every current target in this environment that belongs to this release through its target-native adapter.</p>
        <label class="revision-field">
          <span>Target revision</span>
          <input
            v-model.number="rollbackRevision"
            type="number"
            min="0"
            class="native-field">
          <small>Use 0 for the previous revision. A positive value selects an explicit target-native revision.</small>
        </label>
      </div>
      <template #footer>
        <Button :disabled="rollingBack" @click="showRollback = false">Cancel</Button>
        <Button
          primary
          accent="var(--err, #f87171)"
          :disabled="rollingBack || rollbackRevision < 0"
          @click="rollbackEnvironmentNow">Roll back environment</Button>
      </template>
    </Modal>
  </SectionCard>
</template>

<style scoped>
.empty-state { padding: 10px 2px; color: var(--fg-3); font-size: 12.5px; line-height: 1.5; }
.section-heading { display: flex; align-items: baseline; gap: 10px; margin: 0 0 10px; font-size: 11px; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; color: var(--fg-2); }
.section-help { font-size: 10.5px; font-weight: 400; letter-spacing: 0; text-transform: none; color: var(--fg-3); }
.runs-heading { margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--line); }
.run-heading-actions { margin-left: auto; display: flex; gap: 6px; }
.phase-list { display: flex; flex-direction: column; gap: 10px; }
.phase-card { border: 1px solid var(--line); border-radius: 10px; background: var(--bg-1); padding: 12px; }
.promotion-card { background: color-mix(in oklch, var(--info, #38bdf8) 3%, var(--bg-1)); }
.phase-head, .run-head, .live-job-head, .promotion-title { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.phase-actions { display: flex; gap: 6px; }
.phase-head h4, .run-head h4 { margin: 2px 0 0; font-size: 13px; color: var(--fg-0); }
.phase-kicker { font-size: 10px; color: var(--fg-3); text-transform: uppercase; letter-spacing: .07em; }
.phase-source { display: block; margin-top: 2px; font-size: 10.5px; color: var(--fg-3); }
.promotion-plan { margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--line); }
.promotion-title { margin-bottom: 8px; font-size: 11.5px; color: var(--fg-2); }
.plan-columns { display: grid; grid-auto-flow: column; grid-auto-columns: minmax(150px, 1fr); gap: 8px; margin-top: 10px; overflow-x: auto; }
.plan-column { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.column-label { font-size: 9.5px; color: var(--fg-3); text-transform: uppercase; letter-spacing: .06em; }
.plan-job { display: flex; flex-direction: column; gap: 3px; padding: 7px 8px; border: 1px solid var(--line); border-radius: 7px; background: var(--bg-0); min-width: 0; }
.job-line { display: flex; align-items: center; gap: 6px; font-size: 11px; color: var(--fg-1); }
.job-line strong { overflow-wrap: anywhere; }
.approval-chip, .attempt { font-size: 9px; color: #ffb547; border: 1px solid color-mix(in oklch, #ffb547 45%, transparent); border-radius: 4px; padding: 1px 4px; }
.job-meta { font-size: 9.5px; line-height: 1.35; color: var(--fg-3); overflow-wrap: anywhere; }
.run-strip { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.run-chip { display: inline-flex; align-items: center; gap: 6px; padding: 5px 9px; border: 1px solid var(--line); border-radius: 18px; background: transparent; color: var(--fg-2); font: inherit; font-size: 10.5px; cursor: pointer; }
.run-chip.active { border-color: var(--brand-2, #5ec5ff); background: color-mix(in oklch, var(--brand-2, #5ec5ff) 8%, transparent); color: var(--fg-0); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; flex: 0 0 auto; }
.status-dot.small { width: 6px; height: 6px; }
.muted { color: var(--fg-3); }
.run-panel { padding: 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--bg-1); }
.run-actions { display: flex; align-items: center; gap: 6px; }
.details-link { font-size: 10.5px; color: var(--brand-2, #5ec5ff); text-decoration: none; padding: 5px; }
.live-jobs { display: flex; flex-direction: column; gap: 7px; margin-top: 10px; }
.live-job { padding: 9px 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--bg-0); }
.live-job-head { justify-content: flex-start; font-size: 11.5px; }
.job-status { margin-left: auto; font-size: 9.5px; font-weight: 600; letter-spacing: .04em; }
.live-job-actions { display: flex; align-items: center; gap: 6px; }
.parked { margin: 6px 0 0 14px; color: #ffb547; font-size: 10.5px; line-height: 1.4; }
.job-error, .step-error { color: var(--err, #f87171); }
.job-error { margin: 6px 0 0 14px; font-size: 10.5px; }
.step-list { list-style: none; margin: 8px 0 0 14px; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.step-list li { display: flex; align-items: center; gap: 6px; font-size: 10.5px; color: var(--fg-2); }
.step-error { margin-left: auto; max-width: 45%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.approval-actions { display: flex; gap: 6px; margin-top: 9px; }
.comment-input, .native-field { min-width: 0; border: 1px solid var(--line); border-radius: 7px; background: var(--bg-1); color: var(--fg-0); font: inherit; font-size: 11.5px; padding: 6px 8px; }
.comment-input { flex: 1; }
.dialog-stack { display: flex; flex-direction: column; gap: 12px; }
.dialog-copy, .danger-copy { margin: 0; font-size: 12.5px; color: var(--fg-2); line-height: 1.5; }
.danger-copy { color: #ffb547; }
.input-row { display: grid; grid-template-columns: minmax(0, 1fr) 180px; gap: 12px; align-items: center; }
.input-row label { display: flex; flex-direction: column; gap: 2px; font-size: 12px; color: var(--fg-1); }
.input-row label span { display: flex; align-items: center; gap: 6px; }
.input-row em { font-style: normal; font-size: 9px; color: #ffb547; text-transform: uppercase; letter-spacing: .05em; }
.input-row small { color: var(--fg-3); font-size: 10.5px; line-height: 1.35; }
.downgrade-row, .project-choice { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--fg-1); }
.revision-field { display: flex; flex-direction: column; gap: 5px; font-size: 12px; color: var(--fg-1); }
.revision-field small { color: var(--fg-3); line-height: 1.4; }
.project-choice { padding: 7px 9px; border: 1px solid var(--line); border-radius: 7px; }
.project-choice span { color: var(--fg-3); }
@media (max-width: 760px) {
  .plan-columns { grid-auto-flow: row; grid-auto-columns: auto; }
  .run-head, .phase-head { align-items: flex-start; }
  .run-actions { flex-wrap: wrap; justify-content: flex-end; }
  .input-row { grid-template-columns: 1fr; }
}
</style>
