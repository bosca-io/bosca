<script setup lang="ts">
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'
import { GitPipelineInputType, type GitPipelineInputDefinition, type GitPipelineInputValueInput } from '~/types/graphql'

const { accent } = useCurrentSubsystem()
const { query } = useGraphQL()
const router = useRouter()
const { profile } = import.meta.client ? useAuth() : { profile: ref(null) }

const { save: saveLastOwner, load: loadLastOwner } = useLastGitOwner()
const savedOwner = loadLastOwner()
const selectedOwner = ref(savedOwner?.id ?? '')
const { searchProfiles } = useProfileSearch()

watch(() => profile.value?.id, (id) => {
  if (id && !selectedOwner.value) selectedOwner.value = id
}, { immediate: true })

watch(selectedOwner, (id) => {
  if (!id) return
  const label = knownProfiles.value.get(id) ?? id
  saveLastOwner({ id, label })
})

const knownProfiles = ref<Map<string, string>>(new Map())
if (savedOwner) knownProfiles.value.set(savedOwner.id, savedOwner.label)

const ownerOptions = computed(() => {
  const opts: { value: string; label: string }[] = []
  const p = profile.value
  if (p?.id) {
    const label = p.name || p.slug || p.id
    opts.push({ value: p.id, label })
    knownProfiles.value.set(p.id, label)
  }
  if (savedOwner && savedOwner.id !== p?.id) opts.push({ value: savedOwner.id, label: savedOwner.label })
  return opts
})

async function searchProfilesAndTrack(q: string) {
  const results = await searchProfiles(q)
  for (const opt of results) knownProfiles.value.set(opt.value, opt.label)
  return results
}

interface PipelineRun {
  id: string; number: number; status: string; triggerType: string
  ref: string; commitSha: string; created: string
  durationSeconds: number | null
}
interface Pipeline {
  id: string; repositoryId: string; filePath: string; name: string
  created: string; updated: string; triggerTypes: string[]; runs: PipelineRun[]
}
interface RepoWithPipelines {
  id: string; name: string; canExecute: boolean
  pipelines: Pipeline[]
}

const repos = ref<RepoWithPipelines[]>([])
const isLoading = ref(false)

const pipelinesGql = gql`
  query Pipelines($repositoryId: UUID!) {
    git {
      pipelines(repositoryId: $repositoryId) {
        id repositoryId filePath name created updated triggerTypes
        runs(limit: 1) {
          id number status triggerType ref commitSha created durationSeconds
        }
      }
    }
  }
`

let loadToken = 0

watch(selectedOwner, async (id) => {
  if (!id) { repos.value = []; return }
  const token = ++loadToken
  isLoading.value = true
  try {
    const repoResult = await query<{ git: { repositories: Array<{ id: string; name: string; canExecute: boolean }> } }>(gql`
      query ReposForPipelines($ownerId: UUID!) {
        git { repositories(ownerId: $ownerId) { id name canExecute } }
      }
    `, { ownerId: id })
    if (token !== loadToken) return
    const repoList = repoResult.git?.repositories ?? []
    const settled = await Promise.allSettled(
      repoList.map(repo =>
        query<{ git: { pipelines: Pipeline[] } }>(pipelinesGql, { repositoryId: repo.id })
          .then(r => ({ repo, pipelines: r.git?.pipelines ?? [] })),
      ),
    )
    if (token !== loadToken) return
    const results: RepoWithPipelines[] = []
    for (const s of settled) {
      if (s.status === 'fulfilled' && s.value.pipelines.length) {
        results.push({ ...s.value.repo, pipelines: s.value.pipelines })
      }
    }
    repos.value = results
  } catch {
    if (token === loadToken) repos.value = []
  }
  if (token === loadToken) isLoading.value = false
}, { immediate: true })

async function refresh() {
  const id = selectedOwner.value
  if (!id) return
  selectedOwner.value = ''
  await nextTick()
  selectedOwner.value = id
}

const totalPipelines = computed(() => repos.value.reduce((sum, r) => sum + r.pipelines.length, 0))

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
  return new Date(d).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
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

function latestRun(p: Pipeline): PipelineRun | undefined {
  return p.runs[0]
}

function latestStatus(p: Pipeline): string {
  return latestRun(p)?.status ?? 'UNKNOWN'
}

function isManual(p: Pipeline): boolean {
  return p.triggerTypes?.includes('MANUAL') ?? false
}

const runTarget = ref<{ repoId: string; repoName: string; pipeline: Pipeline } | null>(null)
const runRef = ref('')
const refOptions = ref<{ value: string; label: string }[]>([])
const refsLoading = ref(false)
const runError = ref('')
const runStarting = ref(false)
const runInputs = ref<GitPipelineInputDefinition[]>([])
const inputValues = ref<Record<string, string>>({})
const inputsLoading = ref(false)
const inputsError = ref('')
const missingInputs = computed(() => runInputs.value
  .some(input => input.required && !(inputValues.value[input.name] ?? '').trim()))
const canStartRun = computed(() => !!runRef.value && !runStarting.value && !refsLoading.value
  && !inputsLoading.value && !inputsError.value && !missingInputs.value
  && repos.value.some(repo => repo.id === runTarget.value?.repoId && repo.canExecute))

let inputsToken = 0
watch(() => [runTarget.value?.pipeline.id, runRef.value] as const, async ([pipelineId, selectedRef]) => {
  const token = ++inputsToken
  runInputs.value = []
  inputValues.value = {}
  inputsError.value = ''
  inputsLoading.value = !!pipelineId && !!selectedRef
  if (!pipelineId || !selectedRef) return
  try {
    const result = await query<{ git: { pipelineInputs: GitPipelineInputDefinition[] } }>(gql`
      query PipelineRunInputs($pipelineId: UUID!, $ref: String!) {
        git {
          pipelineInputs(pipelineId: $pipelineId, ref: $ref) {
            name type defaultValue description options required
          }
        }
      }
    `, { pipelineId, ref: selectedRef })
    if (token !== inputsToken) return
    runInputs.value = result.git.pipelineInputs
    runInputs.value.forEach(input => {
      inputValues.value[input.name] = input.defaultValue ?? ''
    })
  } catch (e) {
    if (token !== inputsToken) return
    inputsError.value = e instanceof Error ? e.message : 'Failed to load pipeline inputs.'
  }
  if (token === inputsToken) inputsLoading.value = false
}, { flush: 'sync' })

function inputOptions(input: GitPipelineInputDefinition) {
  const options = input.type === GitPipelineInputType.Boolean ? ['true', 'false'] : input.options
  return options.map(value => ({ value, label: value }))
}

async function openRunModal(repo: RepoWithPipelines, p: Pipeline) {
  if (!repo.canExecute) return
  runTarget.value = { repoId: repo.id, repoName: repo.name, pipeline: p }
  runError.value = ''
  runRef.value = ''
  refOptions.value = []
  refsLoading.value = true
  try {
    const result = await query<{ git: { tags: Array<{ name: string }>; branches: Array<{ name: string }> } }>(gql`
      query PipelineRunRefs($repositoryId: UUID!) {
        git {
          tags(repositoryId: $repositoryId) { name }
          branches(repositoryId: $repositoryId) { name }
        }
      }
    `, { repositoryId: repo.id })
    const tags = [...(result.git?.tags ?? [])]
      .sort((a, b) => b.name.localeCompare(a.name, undefined, { numeric: true }))
    const branches = result.git?.branches ?? []
    refOptions.value = [
      ...tags.map(t => ({ value: `refs/tags/${t.name}`, label: `${t.name} (tag)` })),
      ...branches.map(b => ({ value: `refs/heads/${b.name}`, label: `${b.name} (branch)` })),
    ]
    runRef.value = refOptions.value[0]?.value ?? ''
  } catch (e) {
    runError.value = e instanceof Error ? e.message : 'Failed to load refs.'
  }
  refsLoading.value = false
}

async function startRun() {
  const target = runTarget.value
  if (!target || !canStartRun.value) return
  runStarting.value = true
  runError.value = ''
  try {
    const inputs: GitPipelineInputValueInput[] = Object.entries(inputValues.value)
      .filter(([, value]) => value !== '')
      .map(([name, value]) => ({ name, value }))
    const result = await query<{ git: { triggerPipeline: { id: string } } }>(gql`
      mutation TriggerPipeline($pipelineId: UUID!, $ref: String!, $inputs: [GitPipelineInputValueInput!]) {
        git { triggerPipeline(pipelineId: $pipelineId, ref: $ref, inputs: $inputs) { id } }
      }
    `, {
      pipelineId: target.pipeline.id,
      ref: runRef.value,
      inputs,
    })
    const runId = result.git?.triggerPipeline?.id
    runTarget.value = null
    if (runId) router.push(`/git/pipelines/${runId}`)
  } catch (e) {
    runError.value = e instanceof Error ? e.message : 'Failed to trigger the pipeline.'
  }
  runStarting.value = false
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', 'Pipelines')"
        title="Pipelines"
        :subtitle="`${totalPipelines} pipelines`"
      >
        <template #actions>
          <Select
            v-model="selectedOwner"
            :options="ownerOptions"
            :on-search="searchProfilesAndTrack"
            searchable
            placeholder="Owner…"
            icon="user"
            size="sm"
            :accent="accent"
          />
          <Button size="sm" icon="pulse" @click="refresh()">Refresh</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="!selectedOwner" class="empty-state">Select an owner to view pipelines.</div>
    <div v-else-if="isLoading" class="empty-state">Loading pipelines…</div>
    <div v-else-if="repos.length === 0" class="empty-state">
      <div class="empty-icon"><Icon name="workflow" :size="32" color="var(--fg-4)" /></div>
      <p>No pipelines found.</p>
    </div>

    <div v-else class="pipeline-list">
      <div v-for="repo in repos" :key="repo.id" class="repo-group">
        <div class="repo-heading">
          <Icon name="folder" :size="12" color="var(--fg-3)" />
          <span class="repo-name">{{ repo.name }}</span>
          <span class="repo-count">{{ repo.pipelines.length }}</span>
        </div>
        <div class="pipeline-table">
          <div
            v-for="p in repo.pipelines"
            :key="p.id"
            class="pipeline-row"
            @click="latestRun(p) ? router.push(`/git/pipelines/${latestRun(p)!.id}`) : undefined"
          >
            <span class="pipeline-status-icon" :style="{ background: statusBadgeBg(latestStatus(p)) }">
              <Icon :name="statusIcon(latestStatus(p))" :size="12" :color="statusColor(latestStatus(p))" />
            </span>
            <span class="pipeline-name">{{ p.name }}</span>
            <span class="pipeline-path mono">{{ p.filePath }}</span>
            <template v-if="latestRun(p)">
              <span class="run-ref mono">{{ latestRun(p)!.ref }}</span>
              <span class="run-sha mono">{{ latestRun(p)!.commitSha.slice(0, 8) }}</span>
              <span class="run-duration mono">{{ formatDuration(latestRun(p)!.durationSeconds) }}</span>
              <span class="run-time">{{ formatTime(latestRun(p)!.created) }}</span>
            </template>
            <template v-else>
              <span class="no-runs-label">No runs</span>
            </template>
            <span
              class="pipeline-status-badge"
              :style="{ background: statusBadgeBg(latestStatus(p)), color: statusColor(latestStatus(p)) }"
            >
              {{ latestStatus(p) }}
            </span>
            <Button
              v-if="isManual(p)"
              size="sm"
              icon="play"
              :accent="accent"
              :disabled="!repo.canExecute"
              :title="repo.canExecute ? 'Start this pipeline' : 'Execute permission on the repository is required.'"
              @click.stop="openRunModal(repo, p)"
            >
              Run
            </Button>
          </div>
        </div>
      </div>
    </div>

    <Modal
      v-if="runTarget"
      title="Run Pipeline"
      :subtitle="`${runTarget.pipeline.name} — ${runTarget.repoName}`"
      icon="play"
      :accent="accent"
      @close="runTarget = null"
    >
      <div class="run-form">
        <Select
          v-model="runRef"
          :options="refOptions"
          searchable
          label="Ref"
          :placeholder="refsLoading ? 'Loading refs…' : 'Select a tag or branch…'"
          :accent="accent"
        />
        <p class="run-hint">
          The pipeline definition is read from the selected ref and the run executes at that ref.
        </p>
        <p v-if="inputsLoading" class="run-hint">Loading pipeline inputs…</p>
        <template v-for="input in runInputs" :key="input.name">
          <Select
            v-if="input.type === GitPipelineInputType.Choice || input.type === GitPipelineInputType.Boolean"
            v-model="inputValues[input.name]"
            :options="inputOptions(input)"
            :label="input.name"
            :placeholder="`Select ${input.name}…`"
            :accent="accent"
          />
          <Input
            v-else
            v-model="inputValues[input.name]"
            :type="input.type === GitPipelineInputType.Number ? 'number' : 'text'"
            :label="input.name"
            :accent="accent"
          />
          <p v-if="input.description" class="run-hint">{{ input.description }}</p>
        </template>
        <p v-if="inputsError" class="form-error">{{ inputsError }}</p>
        <p v-if="runError" class="form-error">{{ runError }}</p>
      </div>
      <template #footer>
        <Button @click="runTarget = null">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="!canStartRun"
          @click="startRun"
        >
          {{ runStarting ? 'Starting…' : 'Run' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.empty-state {
  text-align: center; padding: 64px 0; color: var(--fg-3); font-size: 13.5px;
}
.empty-icon { margin-bottom: 12px; }

.pipeline-list { display: flex; flex-direction: column; gap: 20px; }

.repo-group {}

.repo-heading {
  display: flex; align-items: center; gap: 6px;
  padding: 0 4px 8px; font-size: 12px; color: var(--fg-3); font-weight: 600;
  text-transform: uppercase; letter-spacing: 0.04em;
}
.repo-name { color: var(--fg-2); }
.repo-count {
  margin-left: auto; font-size: 11px; color: var(--fg-4);
  background: var(--bg-3); padding: 1px 6px; border-radius: 8px; font-weight: 500;
}

.pipeline-table {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px; overflow: hidden;
}

.pipeline-row {
  display: flex; align-items: center; gap: 12px; padding: 10px 16px;
  border-bottom: 1px solid var(--line); cursor: pointer; transition: background 0.12s;
}
.pipeline-row:last-child { border-bottom: none; }
.pipeline-row:hover { background: var(--bg-2); }

.pipeline-status-icon {
  width: 24px; height: 24px; border-radius: 6px;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}

.pipeline-name { font-weight: 600; font-size: 13px; color: var(--fg-0); white-space: nowrap; }
.pipeline-path {
  font-size: 11px; color: var(--fg-3); flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

.run-ref {
  font-size: 11px; color: var(--fg-2); max-width: 120px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.run-sha { font-size: 11px; color: v-bind(accent); }
.run-duration { font-size: 11px; color: var(--fg-2); width: 60px; text-align: right; }
.run-time { font-size: 11px; color: var(--fg-3); width: 120px; text-align: right; }

.no-runs-label { font-size: 11px; color: var(--fg-4); flex: 1; text-align: right; }

.pipeline-status-badge {
  font-size: 10px; padding: 2px 8px; border-radius: 6px;
  width: 80px; text-align: center; font-weight: 600;
  text-transform: uppercase; letter-spacing: 0.05em; flex-shrink: 0;
}

.run-form { display: flex; flex-direction: column; gap: 12px; }
.run-hint { font-size: 12px; color: var(--fg-3); margin: 0; }
.form-error { font-size: 12.5px; color: var(--err); margin: 0; }
</style>
