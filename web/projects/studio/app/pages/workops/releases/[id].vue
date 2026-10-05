<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'
import ReleasePipelineSection from '~/components/releases/ReleasePipelineSection.vue'
import ReleaseTelemetryRail from '~/components/releases/ReleaseTelemetryRail.vue'
import ReleaseArtifactsSection from '~/components/releases/ReleaseArtifactsSection.vue'
import ReleaseHealthSection from '~/components/releases/ReleaseHealthSection.vue'
import ReleaseLiveSessionsSection from '~/components/releases/ReleaseLiveSessionsSection.vue'
import ReleaseNotesSection from '~/components/releases/ReleaseNotesSection.vue'
import ReleaseEditModal from '~/components/releases/ReleaseEditModal.vue'

/**
 * Release detail: one release's whole cycle — the bundled component versions and
 * their deployment status, native git-ci runs, per-environment state and drift, and live health
 * telemetry. The list of releases lives at /workops/releases; this page owns a single release.
 */

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation, query } = useGraphQL()
const toast = useToast()
const route = useRoute()
const releaseId = computed(() => String(route.params.id ?? ''))

interface Release {
  id: string; programId: string; name: string; description: string | null
  releaseDate: string | null; releasedAt: string | null; version: number
}
interface ReleaseProjectVersion {
  releaseId: string; projectId: string; versionId: string
  deploymentOrder: number | null; deploymentStatus: string; deployedAt: string | null; rollbackVersionId: string | null
  project: { key: string; name: string } | null
  version: { name: string } | null
}

// ── The release itself (carries its programId, so the page self-resolves from the URL) ───────
const releaseGql = gql`
  query ReleaseById($id: UUID!) {
    workOps { crossProject { release(id: $id) {
      id programId name description releaseDate releasedAt version
    } } }
  }
`
const { data: releaseData, status: releaseStatus, refresh: refreshRelease } = useAsyncQuery<{
  workOps: { crossProject: { release: Release | null } }
}>('release-detail', releaseGql, { id: releaseId })
const release = computed(() => releaseData.value?.workOps?.crossProject?.release ?? null)
const programId = computed(() => release.value?.programId ?? '')

// ── Bundled component versions ────────────────────────────────────────────────
const componentsGql = gql`
  query ReleaseComponents($releaseId: UUID!) {
    workOps { crossProject { versionsForRelease(releaseId: $releaseId) {
      releaseId projectId versionId deploymentOrder deploymentStatus deployedAt rollbackVersionId
      project { key name }
      version { name }
    } } }
  }
`
const { data: componentsData, status: componentsStatus, refresh: refreshComponents } = useAsyncQuery<{
  workOps: { crossProject: { versionsForRelease: ReleaseProjectVersion[] } }
}>('release-components', componentsGql, { releaseId })

const components = computed(() =>
  [...(componentsData.value?.workOps?.crossProject?.versionsForRelease ?? [])]
    .sort((a, b) => (a.deploymentOrder ?? 0) - (b.deploymentOrder ?? 0)),
)

const deployedCount = computed(() => components.value.filter(c => c.deploymentStatus.toUpperCase() === 'DEPLOYED').length)
const pendingCount = computed(() => components.value.length - deployedCount.value)

// ── Per-environment state, folded into the Projects table (promotion order) ───
// For each environment, environmentDrift(env, release) lists the channel-compatible projects whose
// deployed version differs from what this release ships (or that aren't deployed there); a compatible
// project absent from the drift list is in sync — deployed at the release's version. A project the
// environment's channel can't take at all (a server project vs a Play track) gets a blank cell.
interface ReleaseEnvironment { id: string; name: string; displayOrder: number; promotionSourceIds: string[] }
interface EnvDriftRow {
  projectId: string; driftType: string
  deployedVersion: { name: string } | null
  expectedVersion: { name: string } | null
}
interface EnvReleaseState { drift: EnvDriftRow[]; compatibleProjectIds: string[] }
const environments = ref<ReleaseEnvironment[]>([])
const stateByEnv = ref<Record<string, EnvReleaseState>>({})
async function refreshEnvironments() {
  if (!programId.value) return
  try {
    const envRes = await query<{ workOps: { multiRepo: { environments: ReleaseEnvironment[] } } }>(gql`
      query ReleasePageEnvironments($programId: UUID!) {
        workOps { multiRepo { environments(programId: $programId) { id name displayOrder promotionSourceIds } } }
      }
    `, { programId: programId.value })
    environments.value = promotionOrder(envRes?.workOps?.multiRepo?.environments ?? [])
    const states = await Promise.all(environments.value.map(async (env) => {
      try {
        const res = await query<{ workOps: { multiRepo: { environmentDrift: EnvDriftRow[]; environmentCompatibleProjects: string[] } } }>(gql`
          query ReleasePageEnvironmentDrift($environmentId: UUID!, $releaseId: UUID!) {
            workOps { multiRepo {
              environmentDrift(environmentId: $environmentId, releaseId: $releaseId) {
                projectId driftType
                deployedVersion { name }
                expectedVersion { name }
              }
              environmentCompatibleProjects(environmentId: $environmentId, releaseId: $releaseId)
            } }
          }
        `, { environmentId: env.id, releaseId: releaseId.value })
        return [env.id, {
          drift: res?.workOps?.multiRepo?.environmentDrift ?? [],
          compatibleProjectIds: res?.workOps?.multiRepo?.environmentCompatibleProjects ?? [],
        }] as const
      }
      catch { return [env.id, { drift: [], compatibleProjectIds: [] }] as const }
    }))
    stateByEnv.value = Object.fromEntries(states)
  }
  catch { environments.value = [] }
}
watch(programId, () => { if (import.meta.client && programId.value) refreshEnvironments() }, { immediate: true })

type EnvCellState = 'in-sync' | 'differs' | 'absent' | 'na'
interface EnvCellInfo { state: EnvCellState; label: string; color: string; detail: string }
function envCell(row: ReleaseProjectVersion, env: ReleaseEnvironment): EnvCellInfo {
  const state = stateByEnv.value[env.id]
  if (!state || !state.compatibleProjectIds.includes(row.projectId)) {
    return { state: 'na', label: '', color: '', detail: '' }
  }
  const ships = row.version?.name ?? 'this release'
  const drift = state.drift.find(d => d.projectId === row.projectId)
  if (!drift) {
    return {
      state: 'in-sync',
      label: 'In sync',
      color: 'var(--ok, #34d399)',
      detail: `${ships} is deployed — exactly what this release ships.`,
    }
  }
  if (drift.driftType === 'NOT_DEPLOYED' || !drift.deployedVersion) {
    return {
      state: 'absent',
      label: 'Not deployed',
      color: 'var(--fg-3)',
      detail: `This release (${drift.expectedVersion?.name ?? ships}) hasn't reached this environment yet.`,
    }
  }
  return {
    state: 'differs',
    label: 'Differs',
    color: '#ffb547',
    detail: `${drift.deployedVersion.name} is deployed; this release ships ${drift.expectedVersion?.name ?? ships}.`,
  }
}
function envCells(row: ReleaseProjectVersion): Array<{ env: ReleaseEnvironment; cell: EnvCellInfo }> {
  return environments.value.map(env => ({ env, cell: envCell(row, env) }))
}

// Launch-blocking dependency violations — the same list Start refuses on, shown BEFORE it's pressed.
const readinessGql = gql`
  query ReleaseReadiness($releaseId: UUID!) {
    workOps { crossProject { releaseReadiness(releaseId: $releaseId) } }
  }
`
const { data: readinessData, refresh: refreshReadiness } = useAsyncQuery<{
  workOps: { crossProject: { releaseReadiness: string[] } }
}>('release-readiness', readinessGql, { releaseId })
const readinessViolations = computed(() => readinessData.value?.workOps?.crossProject?.releaseReadiness ?? [])

// The latest native git-ci run and release action capability, for the derived lifecycle and controls.
const lifecycleRunsGql = gql`
  query ReleaseLifecycleRuns($releaseId: UUID!) {
    workOps { crossProject {
      releaseRuns(releaseId: $releaseId) { runId status }
      releaseActionAccess(releaseId: $releaseId) {
        canManage repositories { repositoryId canEdit canExecute }
      }
    } }
  }
`
const { data: lifecycleRunsData, refresh: refreshLifecycleRuns } = useAsyncQuery<{
  workOps: { crossProject: {
    releaseRuns: Array<{ runId: string, status: string }>
    releaseActionAccess: { canManage: boolean; repositories: Array<{ repositoryId: string; canEdit: boolean; canExecute: boolean }> } | null
  } }
}>('release-lifecycle-runs', lifecycleRunsGql, { releaseId })
const latestRun = computed(() => lifecycleRunsData.value?.workOps?.crossProject?.releaseRuns?.[0] ?? null)
/** Whether this release has ever been started — gates attempt-scoped actions like Roll back. */
const hasRuns = computed(() => (lifecycleRunsData.value?.workOps?.crossProject?.releaseRuns?.length ?? 0) > 0)
const latestRunStatus = computed(() => latestRun.value?.status ?? null)
const releaseAccess = computed(() => lifecycleRunsData.value?.workOps?.crossProject?.releaseActionAccess ?? null)
const canManageRelease = computed(() => releaseAccess.value?.canManage === true)
const canEditReleaseRepositories = computed(() =>
  !!releaseAccess.value && releaseAccess.value.repositories.every(repository => repository.canEdit),
)
const canExecuteReleaseRepositories = computed(() =>
  !!releaseAccess.value && releaseAccess.value.repositories.every(repository => repository.canExecute),
)
const startDisabledReason = computed(() => {
  if (!canManageRelease.value) return 'Manage permission on the release program is required.'
  if (!canEditReleaseRepositories.value) return 'Edit permission on every release repository is required.'
  if (!canExecuteReleaseRepositories.value) return 'Execute permission on every release repository is required.'
  if (!components.value.length) return 'Add at least one project before starting the release.'
  if (readinessViolations.value.length) return 'Resolve the release dependency blockers first.'
  return null
})

/**
 * The release's lifecycle stage, DERIVED from its real parts — the latest native git-ci run + each project's
 * deployment status — rather than a stored enum (which would duplicate that state). `releasedAt` wins
 * (only a human declares "cut"); otherwise the live run and deployments drive it.
 */
interface Lifecycle { label: string; color: string | undefined; hint: string }
const lifecycle = computed<Lifecycle>(() => {
  const rel = release.value
  if (!rel) return { label: '—', color: undefined, hint: '' }
  if (rel.releasedAt) return { label: 'Released', color: 'var(--ok, #34d399)', hint: `Released ${formatDate(rel.releasedAt)}` }
  const run = latestRunStatus.value
  const comps = components.value
  const deployed = comps.filter(c => c.deploymentStatus.toUpperCase() === 'DEPLOYED').length
  const deploying = comps.some(c => c.deploymentStatus.toUpperCase() === 'DEPLOYING')
  if (run === 'QUEUED') return { label: 'Queued', color: '#ffb547', hint: 'The native release run is waiting to dispatch' }
  if (run === 'RUNNING') return { label: 'Deploying', color: 'var(--info, #38bdf8)', hint: 'The native release run is active' }
  if (deploying) return { label: 'Deploying', color: 'var(--info, #38bdf8)', hint: `${deployed}/${comps.length} projects deployed` }
  if (run === 'FAILURE') return { label: 'Failed', color: 'var(--err, #f87171)', hint: 'The last native release run failed' }
  if (run === 'CANCELLED') return { label: 'Cancelled', color: 'var(--fg-3)', hint: 'The last native release run was cancelled' }
  if (comps.length && deployed === comps.length) return { label: 'Deployed', color: 'var(--ok, #34d399)', hint: 'All projects deployed — mark released when it ships' }
  if (deployed > 0) return { label: 'Deploying', color: 'var(--info, #38bdf8)', hint: `${deployed}/${comps.length} projects deployed` }
  return { label: 'Draft', color: undefined, hint: rel.releaseDate ? `Not yet released · planned ${formatDate(rel.releaseDate)}` : 'Assembled — not launched or released yet' }
})

// ── Actions ──────────────────────────────────────────────────────────────────
const pipelineRef = ref<{ refresh: () => void; openStart: () => void } | null>(null)
const artifactsRef = ref<{ refresh: () => void } | null>(null)
const telemetryRef = ref<{ refresh: () => void } | null>(null)
const showEdit = ref(false)

/** Secondary release actions, tucked behind the overflow menu — Start stays the visible primary. */
const releaseMenuItems = computed(() => [
  { id: 'edit', label: 'Edit release', icon: 'edit' },
  ...(!release.value?.releasedAt && hasRuns.value
    ? [{ id: 'rollback', label: 'Roll back attempt', icon: 'undo' }]
    : []),
  { id: 'delete', label: 'Delete release', icon: 'trash', danger: true },
])
function onReleaseMenu(id: string) {
  if (id === 'edit') showEdit.value = true
  else if (id === 'rollback') confirmRollback.value = true
  else if (id === 'delete') confirmDeleteRelease.value = true
}

function refreshAll() {
  refreshRelease()
  refreshComponents()
  refreshLifecycleRuns()
  refreshReadiness()
  refreshEnvironments()
  artifactsRef.value?.refresh()
  telemetryRef.value?.refresh()
  pipelineRef.value?.refresh()
}

// ── Roll back the release attempt — tags + publications undone so it can re-run ──
const confirmRollback = ref(false)
const rollingBack = ref(false)
async function rollbackAttempt() {
  rollingBack.value = true
  try {
    await mutation(gql`
      mutation RollbackReleaseArtifacts($releaseId: UUID!) {
        workOps { crossProject { rollbackReleaseArtifacts(releaseId: $releaseId) } }
      }
    `, { releaseId: releaseId.value })
    toast.success('Release attempt rolled back — tags and publications removed')
    confirmRollback.value = false
    refreshAll()
    pipelineRef.value?.refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to roll back the release attempt')
  } finally {
    rollingBack.value = false
  }
}

// ── Delete release — returns to the list ─────────────────────────────────────
const confirmDeleteRelease = ref(false)
const deletingRelease = ref(false)
async function deleteReleaseNow() {
  deletingRelease.value = true
  try {
    await mutation(gql`
      mutation DeleteRelease($id: UUID!) {
        workOps { crossProject { deleteRelease(id: $id) } }
      }
    `, { id: releaseId.value })
    toast.success('Release deleted')
    confirmDeleteRelease.value = false
    await navigateTo('/workops/releases')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to delete the release')
  } finally {
    deletingRelease.value = false
  }
}

// ── Presentation ──────────────────────────────────────────────────────────────
const columns = computed<GlassTableColumn[]>(() => [
  // The order column only earns its place when an order has actually been assigned — a column of
  // dashes says nothing.
  ...(components.value.some(c => c.deploymentOrder != null)
    ? [{ key: 'order', label: '#', width: '40px', muted: true } as GlassTableColumn]
    : []),
  { key: 'project', label: 'Project', width: 'minmax(160px, 1.4fr)' },
  { key: 'version', label: 'Version', width: '120px' },
  { key: 'status', label: 'Status', width: '120px' },
  { key: 'deployed', label: 'Deployed', width: '110px', muted: true },
  // Wide enough for its own header — the dots never need more than the label does.
  ...(environments.value.length
    ? [{ key: 'envs', label: 'Environments', width: '130px', align: 'right' } as GlassTableColumn]
    : []),
])
const DEPLOY_COLORS: Record<string, string> = {
  DEPLOYED: 'var(--ok, #34d399)', DEPLOYING: 'var(--info, #38bdf8)', PENDING: '#94a3b8',
  FAILED: 'var(--err, #f87171)', ROLLED_BACK: '#ffb547',
}
function statusColor(s: string): string { return DEPLOY_COLORS[s.toUpperCase()] ?? '#94a3b8' }
function short(id: string): string { return id.slice(0, 8) }
function formatDate(d: string | null): string {
  return d ? new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' }) : '—'
}
function formatRelative(d: string | null): string {
  if (!d) return '—'
  const mins = Math.floor((Date.now() - new Date(d).getTime()) / 60_000)
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.floor(hrs / 24)}d ago`
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Releases', release?.name ?? '…')"
        :title="release?.name ?? 'Release'"
        :subtitle="release?.description || undefined">
        <template #actions>
          <div class="header-actions">
            <Button size="sm" icon="refresh" @click="refreshAll">Refresh</Button>
            <Button
              primary
              size="sm"
              icon="play"
              :accent="accent"
              :disabled="!!startDisabledReason"
              :title="startDisabledReason ?? 'Review declared inputs and create native git-ci release runs.'"
              @click="pipelineRef?.openStart()">Start release</Button>
            <OverflowMenu
              :items="releaseMenuItems"
              @select="onReleaseMenu">
              <template #default="{ toggle }">
                <button class="menu-btn" title="Release actions" @click.stop="toggle">
                  <Icon name="more" :size="16" color="var(--fg-3)" />
                </button>
              </template>
            </OverflowMenu>
          </div>
        </template>
      </PageHeader>
    </template>

    <div v-if="releaseStatus === 'pending' && !release" class="page-empty">Loading…</div>
    <div v-else-if="!release" class="page-empty">This release doesn't exist (it may have been deleted).</div>

    <template v-else>
      <!-- Overview KPIs -->
      <StatGrid :columns="4">
        <StatTile label="Projects" :value="components.length.toLocaleString()" />
        <StatTile label="Deployed" :value="deployedCount.toLocaleString()" :accent="components.length && deployedCount === components.length ? 'var(--ok, #34d399)' : undefined" />
        <StatTile label="Pending" :value="pendingCount.toLocaleString()" :accent="pendingCount ? '#ffb547' : undefined" />
        <StatTile
          label="Release status"
          :value="lifecycle.label"
          value-size="sm"
          :sub="lifecycle.hint"
          :accent="lifecycle.color" />
      </StatGrid>

      <!-- Launch blockers: dependency violations Start would refuse on -->
      <div v-if="readinessViolations.length" class="readiness-warning">
        <Icon name="alert-triangle" :size="15" color="#ffb547" />
        <div class="readiness-body">
          <span class="readiness-title">This release can't start yet</span>
          <ul class="readiness-list">
            <li v-for="(violation, i) in readinessViolations" :key="i">{{ violation }}</li>
          </ul>
          <span class="readiness-hint">Add the missing projects to the release, or release a satisfying provider version first.</span>
        </div>
      </div>

      <!-- Projects -->
      <SectionCard title="Projects" subtitle="The project versions this release ships, in deployment order">
        <GlassTable
          :columns="columns"
          :rows="components"
          :loading="componentsStatus === 'pending' && components.length === 0"
          empty-text="No projects in this release yet — add them with Edit release."
          row-key="versionId">
          <template #col-order="{ row }">{{ (row as ReleaseProjectVersion).deploymentOrder ?? '—' }}</template>
          <template #col-project="{ row }">
            <span class="proj-key mono">{{ (row as ReleaseProjectVersion).project?.key ?? short((row as ReleaseProjectVersion).projectId) }}</span>
            <span v-if="(row as ReleaseProjectVersion).project?.name" class="proj-name">{{ (row as ReleaseProjectVersion).project?.name }}</span>
          </template>
          <template #col-version="{ row }">{{ (row as ReleaseProjectVersion).version?.name ?? short((row as ReleaseProjectVersion).versionId) }}</template>
          <template #col-status="{ row }">
            <Badge :color="statusColor((row as ReleaseProjectVersion).deploymentStatus)">{{ (row as ReleaseProjectVersion).deploymentStatus.toUpperCase() }}</Badge>
          </template>
          <template #col-deployed="{ row }">{{ formatRelative((row as ReleaseProjectVersion).deployedAt) }}</template>
          <template #col-envs="{ row }">
            <div class="env-dots">
              <template v-for="{ env, cell } in envCells(row as ReleaseProjectVersion)" :key="env.id">
                <span v-if="cell.state === 'na'" class="env-dot na" />
                <Popover
                  v-else
                  trigger="mouseenter"
                  placement="top"
                  :delay="150">
                  <template #trigger>
                    <span class="env-dot" :class="cell.state" />
                  </template>
                  <div class="env-pop">
                    <p class="env-pop-head">
                      <span class="env-pop-name">{{ env.name }}</span>
                      <span class="env-pop-state" :style="{ color: cell.color }">{{ cell.label }}</span>
                    </p>
                    <p class="env-pop-detail">{{ cell.detail }}</p>
                  </div>
                </Popover>
              </template>
            </div>
          </template>
        </GlassTable>
      </SectionCard>

      <!-- Native release pipeline plan/runs + the right-side artifact and environment rail. -->
      <div class="release-layout">
        <ReleasePipelineSection
          ref="pipelineRef"
          :release-id="releaseId"
          :projects="components"
          @changed="refreshAll" />
        <div class="rail-col">
          <ReleaseArtifactsSection
            ref="artifactsRef"
            :release-id="releaseId"
            :components="components"
            :live="latestRunStatus === 'RUNNING' || latestRunStatus === 'QUEUED'" />
          <ReleaseTelemetryRail
            ref="telemetryRef"
            :program-id="programId"
            :release-id="releaseId" />
          <ReleaseNotesSection :release-id="releaseId" :can-manage="canManageRelease" />
          <ReleaseHealthSection :release-id="releaseId" />
          <!-- Where sessions on this release are, right now -->
          <ReleaseLiveSessionsSection :components="components" />
        </div>
      </div>

    </template>

    <ReleaseEditModal
      v-if="showEdit && release"
      :program-id="programId"
      :release-id="releaseId"
      @close="showEdit = false"
      @changed="refreshAll" />

    <Modal
      v-if="confirmRollback && release"
      title="Roll back release attempt"
      icon="undo"
      :accent="accent"
      width="480px"
      @close="confirmRollback = false">
      <p class="confirm-text">
        Rolls “{{ release.name }}” back so it can be released again: deletes the git tags created by
        its native release runs, deletes the versions' artifact publications, and resets deployment statuses. Live runs
        must be cancelled first. Store uploads (Google Play / App Store) are not withdrawn.
      </p>
      <template #footer>
        <Button :disabled="rollingBack" @click="confirmRollback = false">Cancel</Button>
        <Button
          primary
          accent="var(--err, #f87171)"
          :disabled="rollingBack"
          @click="rollbackAttempt">Roll back</Button>
      </template>
    </Modal>

    <Modal
      v-if="confirmDeleteRelease && release"
      title="Delete release"
      icon="trash"
      :accent="accent"
      width="440px"
      @close="confirmDeleteRelease = false">
      <p class="confirm-text">
        Delete “{{ release.name }}”? This removes it from the list. It's recoverable — the release and
        its bundle are retained.
      </p>
      <template #footer>
        <Button :disabled="deletingRelease" @click="confirmDeleteRelease = false">Cancel</Button>
        <Button
          primary
          accent="var(--err, #f87171)"
          :disabled="deletingRelease"
          @click="deleteReleaseNow">Delete release</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.header-actions { display: flex; align-items: center; gap: 8px; }
.menu-btn {
  background: none; border: 1px solid var(--line); border-radius: 8px;
  width: 32px; height: 32px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: background 0.15s, border-color 0.15s;
}
.menu-btn:hover { background: var(--bg-2); border-color: var(--fg-3); }
.release-layout { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 16px; align-items: start; }
.rail-col { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
@media (max-width: 1080px) { .release-layout { grid-template-columns: 1fr; } }
.page-empty { padding: 40px 4px; text-align: center; font-size: 13px; color: var(--fg-3); }
.readiness-warning {
  display: flex; gap: 10px; align-items: flex-start; padding: 12px 14px;
  border: 1px solid color-mix(in oklch, #ffb547 45%, transparent); border-radius: 10px;
  background: color-mix(in oklch, #ffb547 7%, transparent);
}
.readiness-body { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.readiness-title { font-size: 12.5px; font-weight: 600; color: #ffb547; }
.readiness-list { margin: 0; padding-left: 16px; font-size: 12.5px; color: var(--fg-1); line-height: 1.5; }
.readiness-hint { font-size: 11.5px; color: var(--fg-3); }
.confirm-text { margin: 0; font-size: 13.5px; color: var(--fg-1); line-height: 1.55; }
.proj-key { color: var(--fg-0); font-size: 12.5px; font-weight: 500; }
.proj-name { color: var(--fg-3); font-size: 11px; margin-left: 8px; }
.env-dots { display: flex; align-items: center; justify-content: flex-end; gap: 8px; }
.env-dot { width: 9px; height: 9px; border-radius: 50%; flex: 0 0 auto; }
.env-dot.in-sync { background: var(--ok, #34d399); }
.env-dot.differs { background: #ffb547; }
.env-dot.absent { border: 1.5px solid var(--fg-3); background: transparent; }
/* Channel-incompatible cell — keeps the dot grid aligned without claiming any deploy state. */
.env-dot.na { visibility: hidden; }
.env-pop { display: flex; flex-direction: column; gap: 5px; min-width: 200px; max-width: 260px; }
.env-pop-head { margin: 0; display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.env-pop-name { font-size: 12.5px; font-weight: 600; color: var(--fg-0); }
.env-pop-state { font-size: 10.5px; text-transform: uppercase; letter-spacing: 0.06em; font-weight: 600; }
.env-pop-detail { margin: 0; font-size: 11.5px; color: var(--fg-2); line-height: 1.5; }
</style>
