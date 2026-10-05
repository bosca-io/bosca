<script setup lang="ts">
import ProjectDependenciesSection from '~/components/projects/ProjectDependenciesSection.vue'
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'

// ─── Boards ───────────────────────────────────────────────────────────────────
import type { BoardViewBoard } from '~/components/workops/BoardView.vue'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation, query } = useGraphQL()

const projectId = computed(() => route.params.id as string)
const activeTab = ref('Overview')
const saving = ref(false)
const error = ref('')
const showMove = ref(false)

const STATUS_COLORS: Record<string, string> = {
  TODO: '#6c7388', IN_PROGRESS: '#a78bff', IN_REVIEW: '#ffb547', DONE: '#34d99a', CANCELLED: '#6c7388',
}
const PRIORITY_COLORS: Record<number, string> = {
  1: '#ff5d6c', 2: '#ff8a4d', 3: '#ffb547', 4: '#5ec5ff', 5: '#6c7388',
}
const STATUS_CATEGORIES = [
  { key: 'TODO', label: 'To Do', color: '#6c7388' },
  { key: 'IN_PROGRESS', label: 'In Progress', color: '#a78bff' },
  { key: 'IN_REVIEW', label: 'In Review', color: '#ffb547' },
  { key: 'DONE', label: 'Done', color: '#34d99a' },
]

// ─── Project ──────────────────────────────────────────────────────────────────
interface Project {
  id: string; key: string; name: string; description: string | null
  ownerProfileId: string; owner: { id: string; name: string } | null
  program: { id: string; key: string; name: string } | null
  archivedAt: string | null; createdAt: string; modifiedAt: string; version: number
}

const { data: projectData, status: projectStatus, refresh: refreshProject } = useAsyncQuery<{
  workOps: { projects: { project: Project | null } }
}>('workops-project-detail', gql`
  query GetProject($id: UUID!) {
    workOps { projects { project(id: $id) {
      id key name description ownerProfileId
      owner { id name } program { id key name }
      archivedAt createdAt modifiedAt version
    } } }
  }
`, { id: projectId }, { server: false })

const project = computed(() => projectData.value?.workOps?.projects?.project ?? null)
const isLoading = computed(() => projectStatus.value === 'pending')

async function handleMoved() {
  showMove.value = false
  await refreshProject()
}

const selectedBoardId = ref('')

const { data: boardsData } = useAsyncQuery<{
  workOps: { boards: { byProject: BoardViewBoard[] } }
}>('workops-project-boards', gql`
  query ProjectBoards($projectId: UUID!) {
    workOps { boards { byProject(projectId: $projectId) {
      id name type swimlaneStrategy columns { id name displayOrder wipLimit statusIds statuses { id name category } } version
    } } }
  }
`, { projectId }, { server: false })

const boards = computed(() => boardsData.value?.workOps?.boards?.byProject ?? [])
const boardOptions = computed(() => boards.value.map(b => ({ value: b.id, label: b.name })))
const selectedBoard = computed(() => boards.value.find(b => b.id === selectedBoardId.value) ?? null)

watch(boards, (bs) => {
  if (bs.length && !bs.find(b => b.id === selectedBoardId.value) && bs[0]) selectedBoardId.value = bs[0].id
  if (!bs.length) selectedBoardId.value = ''
})

// ─── Sprint Count ────────────────────────────────────────────────────────────

const sprintCount = ref(0)
const activeSprintCount = ref(0)

async function loadSprintCounts() {
  let total = 0
  let active = 0
  for (const board of boards.value) {
    try {
      const { query: gqlQuery } = useGraphQL()
      const result = await gqlQuery<{
        workOps: { sprints: { byBoard: Array<{ state: string }> } }
      }>(gql`
        query SprintCount($boardId: UUID!) {
          workOps { sprints { byBoard(boardId: $boardId) { state } } }
        }
      `, { boardId: board.id })
      const sprints = result.workOps?.sprints?.byBoard ?? []
      total += sprints.length
      active += sprints.filter(s => s.state === 'ACTIVE').length
    } catch { /* ignore */ }
  }
  sprintCount.value = total
  activeSprintCount.value = active
}

watch(boards, (bs) => { if (bs.length) loadSprintCounts() })

// ─── Versions ─────────────────────────────────────────────────────────────────
const { data: versionsData, refresh: refreshVersions } = useAsyncQuery<{
  workOps: { versions: { byProject: Array<{ id: string; name: string; description: string | null; released: boolean; releaseDate: string | null; archived: boolean; version: number }> } }
}>('workops-project-versions', gql`
  query ProjectVersions($projectId: UUID!) {
    workOps { versions { byProject(projectId: $projectId) { id name description released releaseDate archived version } } }
  }
`, { projectId }, { server: false })
const versions = computed(() => versionsData.value?.workOps?.versions?.byProject ?? [])

const showCreateVersion = ref(false)
const versionForm = reactive({ name: '', description: '' })

async function handleCreateVersion() {
  if (!versionForm.name) { error.value = 'Name required'; return }
  saving.value = true; error.value = ''
  try {
    await mutation(gql`mutation($input: CreateWorkOpsVersionInput!) { workOps { versions { create(input: $input) { id } } } }`,
      { input: { name: versionForm.name, description: versionForm.description || null, projectId: projectId.value } })
    showCreateVersion.value = false; versionForm.name = ''; versionForm.description = ''
    await refreshVersions()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to create version' } finally { saving.value = false }
}

async function releaseVersion(v: { id: string; version: number }) {
  try {
    await mutation(gql`mutation($id: UUID!, $version: Long!) { workOps { versions { release(id: $id, version: $version) { id } } } }`,
      { id: v.id, version: v.version })
    await refreshVersions()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to release version' }
}

// ─── Components ───────────────────────────────────────────────────────────────
const { data: componentsData, refresh: refreshComponents } = useAsyncQuery<{
  workOps: { components: { byProject: Array<{ id: string; name: string; description: string | null; assigneeMode: string; version: number }> } }
}>('workops-project-components', gql`
  query ProjectComponents($projectId: UUID!) {
    workOps { components { byProject(projectId: $projectId) { id name description assigneeMode version } } }
  }
`, { projectId }, { server: false })
const components = computed(() => componentsData.value?.workOps?.components?.byProject ?? [])

const showCreateComponent = ref(false)
const componentForm = reactive({ name: '', description: '', assigneeMode: 'UNASSIGNED' })
const assigneeModeOptions = [
  { value: 'UNASSIGNED', label: 'Unassigned' },
  { value: 'PROJECT_DEFAULT', label: 'Project Default' },
  { value: 'COMPONENT_LEAD', label: 'Component Lead' },
  { value: 'COMPONENT_LEAD_OR_PROJECT_DEFAULT', label: 'Lead or Project Default' },
]

async function handleCreateComponent() {
  if (!componentForm.name) { error.value = 'Name required'; return }
  saving.value = true; error.value = ''
  try {
    await mutation(gql`mutation($input: CreateWorkOpsComponentInput!) { workOps { components { create(input: $input) { id } } } }`,
      { input: { name: componentForm.name, description: componentForm.description || null, assigneeMode: componentForm.assigneeMode, projectId: projectId.value } })
    showCreateComponent.value = false; componentForm.name = ''; componentForm.description = ''
    await refreshComponents()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to create component' } finally { saving.value = false }
}

async function deleteComponent(c: { id: string; version: number }) {
  try {
    await mutation(gql`mutation($id: UUID!, $version: Long!) { workOps { components { delete(id: $id, version: $version) } } }`, { id: c.id, version: c.version })
    await refreshComponents()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to delete component' }
}

// ─── Labels ───────────────────────────────────────────────────────────────────
const { data: labelsData, refresh: refreshLabels } = useAsyncQuery<{
  workOps: { labels: { byProject: Array<{ id: string; name: string; colorHex: string | null; version: number }> } }
}>('workops-project-labels', gql`
  query ProjectLabels($projectId: UUID!) {
    workOps { labels { byProject(projectId: $projectId) { id name colorHex version } } }
  }
`, { projectId }, { server: false })
const labels = computed(() => labelsData.value?.workOps?.labels?.byProject ?? [])

const showCreateLabel = ref(false)
const labelForm = reactive({ name: '', colorHex: '#a78bff' })

async function handleCreateLabel() {
  if (!labelForm.name) { error.value = 'Name required'; return }
  saving.value = true; error.value = ''
  try {
    await mutation(gql`mutation($input: CreateWorkOpsLabelInput!) { workOps { labels { create(input: $input) { id } } } }`,
      { input: { name: labelForm.name, colorHex: labelForm.colorHex, projectId: projectId.value, scope: 'PROJECT' } })
    showCreateLabel.value = false; labelForm.name = ''
    await refreshLabels()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to create label' } finally { saving.value = false }
}

async function deleteLabel(l: { id: string; version: number }) {
  try {
    await mutation(gql`mutation($id: UUID!, $version: Long!) { workOps { labels { delete(id: $id, version: $version) } } }`, { id: l.id, version: l.version })
    await refreshLabels()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to delete label' }
}

// ─── Analytics identifiers ──────────────────────────────────────────────────────
// The appIds (sessions/errors) and service names (http response codes) this project owns; the release
// dashboard reads these to scope health telemetry to a release's projects.
const { data: analyticsData, refresh: refreshAnalytics } = useAsyncQuery<{
  workOps: { projects: { project: {
    analyticsApplications: Array<{ id: string; applicationId: string }>
    analyticsServices: Array<{ id: string; service: string }>
  } | null } }
}>('workops-project-analytics', gql`
  query ProjectAnalytics($id: UUID!) {
    workOps { projects { project(id: $id) {
      analyticsApplications { id applicationId }
      analyticsServices { id service }
    } } }
  }
`, { id: projectId }, { server: false })
const analyticsApps = computed(() => analyticsData.value?.workOps?.projects?.project?.analyticsApplications ?? [])
const analyticsServices = computed(() => analyticsData.value?.workOps?.projects?.project?.analyticsServices ?? [])

const newAppId = ref('')
const newService = ref('')

async function addAnalyticsApp() {
  const value = newAppId.value.trim()
  if (!value) return
  try {
    await mutation(gql`mutation($projectId: UUID!, $applicationId: String!) { workOps { projects { addAnalyticsApplication(projectId: $projectId, applicationId: $applicationId) { id } } } }`,
      { projectId: projectId.value, applicationId: value })
    newAppId.value = ''
    await refreshAnalytics()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to add application' }
}
async function removeAnalyticsApp(id: string) {
  try {
    await mutation(gql`mutation($projectId: UUID!, $id: UUID!) { workOps { projects { removeAnalyticsApplication(projectId: $projectId, id: $id) } } }`,
      { projectId: projectId.value, id })
    await refreshAnalytics()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to remove application' }
}
async function addAnalyticsSvc() {
  const value = newService.value.trim()
  if (!value) return
  try {
    await mutation(gql`mutation($projectId: UUID!, $service: String!) { workOps { projects { addAnalyticsService(projectId: $projectId, service: $service) { id } } } }`,
      { projectId: projectId.value, service: value })
    newService.value = ''
    await refreshAnalytics()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to add service' }
}
async function removeAnalyticsSvc(id: string) {
  try {
    await mutation(gql`mutation($projectId: UUID!, $id: UUID!) { workOps { projects { removeAnalyticsService(projectId: $projectId, id: $id) } } }`,
      { projectId: projectId.value, id })
    await refreshAnalytics()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to remove service' }
}

// ─── Git repositories ───────────────────────────────────────────────────────────
// The git repos this project owns; native release planning reads these to
// tag & build each repo when the project ships in a release.
interface GitRepo { id: string; name: string; slug: string; archived: boolean }

const { data: reposData, refresh: refreshRepositories } = useAsyncQuery<{
  workOps: { projects: { project: {
    repositories: Array<{ id: string; repositoryId: string }>
  } | null } }
}>('workops-project-repositories', gql`
  query ProjectRepositories($id: UUID!) {
    workOps { projects { project(id: $id) {
      repositories { id repositoryId }
    } } }
  }
`, { id: projectId }, { server: false })
const projectRepositories = computed(() => reposData.value?.workOps?.projects?.project?.repositories ?? [])

// Every git repository, for the picker and to resolve a stored repositoryId to a human name.
const gitRepos = ref<GitRepo[]>([])
const gitReposById = computed(() => {
  const map: Record<string, GitRepo> = {}
  for (const r of gitRepos.value) map[r.id] = r
  return map
})
const selectedRepoId = ref('')

// Offer only repositories that aren't already attached (and aren't archived).
const repoOptions = computed(() => {
  const attached = new Set(projectRepositories.value.map(r => r.repositoryId))
  return gitRepos.value
    .filter(r => !r.archived && !attached.has(r.id))
    .map(r => ({ value: r.id, label: r.name }))
})

onMounted(async () => {
  try {
    const result = await query<{ git: { repositories: GitRepo[] } }>(gql`
      query AllGitRepositories {
        git { repositories { id name slug archived } }
      }
    `)
    gitRepos.value = result.git?.repositories ?? []
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to load git repositories' }
})

async function addRepository() {
  const repositoryId = selectedRepoId.value
  if (!repositoryId) return
  try {
    await mutation(gql`mutation($projectId: UUID!, $repositoryId: UUID!) { workOps { projects { addProjectRepository(projectId: $projectId, repositoryId: $repositoryId) { id } } } }`,
      { projectId: projectId.value, repositoryId })
    selectedRepoId.value = ''
    await refreshRepositories()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to attach repository' }
}
async function removeRepository(id: string) {
  try {
    await mutation(gql`mutation($projectId: UUID!, $id: UUID!) { workOps { projects { removeProjectRepository(projectId: $projectId, id: $id) } } }`,
      { projectId: projectId.value, id })
    await refreshRepositories()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to remove repository' }
}
function repoName(repositoryId: string): string {
  return gitReposById.value[repositoryId]?.name ?? repositoryId
}
function repoSlug(repositoryId: string): string | null {
  return gitReposById.value[repositoryId]?.slug ?? null
}

// Deploy-config setup: generates a starter .bosca/deploy.yaml (one stanza per program environment,
// target chosen from each environment's target type) and commits it to the repository, attributed to
// the acting user. The server never overwrites an existing file — its error is surfaced as-is.
const toast = useToast()
const auth = import.meta.client ? useAuth() : null
const commitAuthor = computed(() => resolveGitCommitAuthor(auth?.profile?.value))
const deployConfigBusyId = ref('')
const deployConfigResult = ref<{ repository: string, path: string, branch: string, content: string } | null>(null)

async function createDeployConfig(repositoryId: string) {
  deployConfigBusyId.value = repositoryId
  try {
    const res = await mutation<{ workOps: { multiRepo: { createProjectDeployConfig: { path: string, branch: string, content: string } } } }>(gql`
      mutation CreateProjectDeployConfig($projectId: UUID!, $repositoryId: UUID!, $authorName: String!, $authorEmail: String!) {
        workOps { multiRepo { createProjectDeployConfig(
          projectId: $projectId, repositoryId: $repositoryId, authorName: $authorName, authorEmail: $authorEmail
        ) { path branch content } } }
      }
    `, {
      projectId: projectId.value,
      repositoryId,
      authorName: commitAuthor.value.authorName,
      authorEmail: commitAuthor.value.authorEmail,
    })
    const file = res?.workOps?.multiRepo?.createProjectDeployConfig
    if (file) {
      deployConfigResult.value = { repository: repoName(repositoryId), path: file.path, branch: file.branch, content: file.content }
      toast.success(`Committed ${file.path} to ${file.branch}`)
    }
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create the deploy config')
  }
  finally {
    deployConfigBusyId.value = ''
  }
}

// ─── Recent tasks ─────────────────────────────────────────────────────────────
interface RecentTask {
  id: string; key: string; summary: string
  status: { name: string; category: string }
  priority: { name: string; displayOrder: number }
  assignee: { id: string; name: string } | null
  modifiedAt: string
}
const recentTasks = ref<RecentTask[]>([])

watch(project, async (p) => {
  if (!p) return
  try {
    const result = await query<{
      workOps: { savedFilters: { searchTasks: { rows: RecentTask[] } } }
    }>(gql`
      query RecentTasks($source: String!) {
        workOps { savedFilters { searchTasks(source: $source, limit: 8, offset: 0) {
          rows { id key summary status { name category } priority { name displayOrder } assignee { id name } modifiedAt }
        } } }
      }
    `, { source: `project = "${p.key}" ORDER BY modified DESC` })
    recentTasks.value = result.workOps?.savedFilters?.searchTasks?.rows ?? []
  } catch { /* ignore */ }
}, { immediate: true })

const tasksByStatus = computed(() => {
  const counts: Record<string, number> = {}
  for (const t of recentTasks.value) counts[t.status.category] = (counts[t.status.category] || 0) + 1
  return counts
})

// ─── Helpers ──────────────────────────────────────────────────────────────────
function formatDate(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}
function relativeTime(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(ms / 60_000)
  if (mins < 1) return 'now'
  if (mins < 60) return `${mins}m`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h`
  return `${Math.floor(hrs / 24)}d`
}
function assigneeModeLabel(mode: string): string {
  return mode.replace(/_/g, ' ').split(' ').map(w => w.charAt(0) + w.slice(1).toLowerCase()).join(' ')
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Projects', project?.key || '…')"
        :title="project?.name || 'Loading…'"
        :subtitle="project ? `${project.key} · ${project.program?.name ?? 'No program'} · Owner ${project.owner?.name ?? '—'}` : ''"
        :tabs="['Overview', 'Board', 'Versions', 'Components', 'Dependencies', 'Labels', 'Repositories', 'Analytics']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #title>
          <span class="title-row">
            <span class="project-mark" :style="{ background: accent }">{{ project?.key?.slice(0, 2) ?? '' }}</span>
            {{ project?.name || 'Loading…' }}
            <Badge v-if="project?.archivedAt" color="#6c7388">Archived</Badge>
          </span>
        </template>
        <template #actions>
          <Button
            v-if="activeTab === 'Overview' && project && !project.archivedAt"
            icon="arrowRight"
            size="sm"
            :accent="accent"
            @click="showMove = true">Move Project</Button>
          <template v-if="activeTab === 'Board'">
            <Select
              v-model="selectedBoardId"
              :options="boardOptions"
              placeholder="Board"
              :accent="accent"
              size="sm" />
          </template>
          <Button
            v-if="activeTab === 'Versions'"
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreateVersion = true">New Version</Button>
          <Button
            v-if="activeTab === 'Components'"
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreateComponent = true">New Component</Button>
          <Button
            v-if="activeTab === 'Labels'"
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreateLabel = true">New Label</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading" class="loading-state">Loading project…</div>

    <template v-else-if="project">
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Overview                                                        -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Overview'">
        <StatGrid :columns="5">
          <StatTile
            label="Boards"
            :value="String(boards.length)"
            sub="active"
            :accent="accent" />
          <StatTile
            label="Sprints"
            :value="String(sprintCount)"
            :sub="`${activeSprintCount} active`"
            :accent="accent" />
          <StatTile
            label="Versions"
            :value="String(versions.length)"
            :sub="`${versions.filter(v => v.released).length} released`"
            :accent="accent" />
          <StatTile
            label="Components"
            :value="String(components.length)"
            sub="configured"
            :accent="accent" />
          <StatTile
            label="Labels"
            :value="String(labels.length)"
            sub="defined"
            :accent="accent" />
        </StatGrid>

        <div class="overview-layout">
          <div class="overview-sidebar">
            <!-- Project info card -->
            <div class="card">
              <div class="card-section-title">Project</div>
              <div class="card-info-list">
                <div class="card-info-row">
                  <span class="card-info-label">Key</span>
                  <span class="mono card-info-value">{{ project.key }}</span>
                </div>
                <div class="card-info-row">
                  <span class="card-info-label">Program</span>
                  <span class="card-info-value">{{ project.program?.name ?? '—' }}</span>
                </div>
                <div class="card-info-row">
                  <span class="card-info-label">Owner</span>
                  <span class="card-info-value">{{ project.owner?.name ?? '—' }}</span>
                </div>
                <div class="card-info-row">
                  <span class="card-info-label">Created</span>
                  <span class="card-info-value">{{ formatDate(project.createdAt) }}</span>
                </div>
                <div class="card-info-row">
                  <span class="card-info-label">Modified</span>
                  <span class="card-info-value">{{ formatDate(project.modifiedAt) }}</span>
                </div>
              </div>
              <p v-if="project.description" class="card-description">{{ project.description }}</p>
            </div>

            <!-- Labels card -->
            <div v-if="labels.length" class="card">
              <div class="card-section-title">Labels</div>
              <div class="card-labels">
                <span v-for="l in labels" :key="l.id" class="card-label-chip">
                  <span class="card-label-dot" :style="{ background: l.colorHex || '#6c7388' }" />
                  {{ l.name }}
                </span>
              </div>
            </div>

            <!-- Components card -->
            <div v-if="components.length" class="card">
              <div class="card-section-title">Components</div>
              <div class="card-components">
                <div v-for="c in components" :key="c.id" class="card-component-row">
                  <Icon name="boxes" :size="13" :color="accent" />
                  <div class="card-component-info">
                    <span class="card-component-name">{{ c.name }}</span>
                    <span class="card-component-mode">{{ assigneeModeLabel(c.assigneeMode) }}</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- Versions card -->
            <div v-if="versions.length" class="card">
              <div class="card-section-title">Versions</div>
              <div class="card-versions">
                <div v-for="v in versions" :key="v.id" class="card-version-row">
                  <Icon name="flag" :size="13" :color="v.released ? '#34d99a' : accent" />
                  <span class="card-version-name">{{ v.name }}</span>
                  <Badge v-if="v.released" color="#34d99a" solid>Released</Badge>
                  <Badge v-else :color="accent">Unreleased</Badge>
                </div>
              </div>
            </div>
          </div>

          <div class="overview-main">
            <!-- Status breakdown -->
            <SectionCard title="Task Status">
              <template #right>
                <span class="mono section-meta">{{ recentTasks.length }} recent</span>
              </template>
              <div class="status-breakdown">
                <div v-for="cat in STATUS_CATEGORIES" :key="cat.key" class="status-row">
                  <span class="status-name">{{ cat.label }}</span>
                  <div class="status-bar-wrap">
                    <ProgressBar
                      :value="recentTasks.length ? ((tasksByStatus[cat.key] || 0) / recentTasks.length) * 100 : 0"
                      :accent="cat.color"
                      :height="6"
                    />
                  </div>
                  <span class="mono tabular status-count" :style="{ color: cat.color }">{{ tasksByStatus[cat.key] || 0 }}</span>
                </div>
              </div>
            </SectionCard>

            <!-- Recent tasks -->
            <SectionCard title="Recent Tasks">
              <div v-if="recentTasks.length" class="activity-list">
                <div
                  v-for="t in recentTasks"
                  :key="t.id"
                  class="activity-row"
                  @click="router.push(`/workops/tasks/${t.id}`)"
                >
                  <div
                    class="activity-icon"
                    :style="{
                      background: `color-mix(in oklch, ${PRIORITY_COLORS[t.priority.displayOrder] || '#6c7388'} 14%, transparent)`,
                      border: `1px solid color-mix(in oklch, ${PRIORITY_COLORS[t.priority.displayOrder] || '#6c7388'} 25%, transparent)`,
                    }"
                  >
                    <Icon name="check" :size="13" :color="PRIORITY_COLORS[t.priority.displayOrder] || '#6c7388'" />
                  </div>
                  <div class="activity-body">
                    <div class="activity-label">
                      <span class="mono activity-key">{{ t.key }}</span>
                      {{ t.summary }}
                    </div>
                    <div class="activity-meta">
                      <Badge :color="STATUS_COLORS[t.status.category] || '#6c7388'">{{ t.status.name }}</Badge>
                      <span v-if="t.assignee" class="activity-assignee">
                        <Avatar :name="t.assignee.name" :idx="0" :size="16" />
                        {{ t.assignee.name }}
                      </span>
                    </div>
                  </div>
                  <span class="mono activity-time">{{ relativeTime(t.modifiedAt) }}</span>
                </div>
              </div>
              <div v-else class="empty-msg">No tasks yet.</div>
            </SectionCard>
          </div>
        </div>
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Board                                                           -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Board'">
        <BoardView
          v-if="selectedBoard && project"
          :board="selectedBoard"
          :project-keys="[project.key]"
          :project-id="projectId"
          :accent="accent"
          @card-click="router.push(`/workops/tasks/${$event}`)"
        >
          <template #empty-action>
            <Button
              primary
              :accent="accent"
              size="sm"
              @click="router.push(`/workops/boards/${selectedBoard.id}?tab=columns`)">
              Configure Board
            </Button>
          </template>
        </BoardView>
        <div v-else-if="!boards.length" class="empty-msg">No boards for this project.</div>
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Versions                                                        -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Versions'">
        <SectionCard title="All Versions">
          <template #right>
            <span class="mono section-meta">{{ versions.length }} total</span>
          </template>
          <div v-if="versions.length" class="entity-list">
            <div v-for="v in versions" :key="v.id" class="entity-row">
              <Icon name="flag" :size="15" :color="v.released ? '#34d99a' : accent" />
              <div class="entity-info">
                <span class="entity-name">{{ v.name }}</span>
                <span v-if="v.description" class="entity-desc">{{ v.description }}</span>
              </div>
              <Badge v-if="v.released" color="#34d99a" solid>Released</Badge>
              <Badge v-else-if="v.archived" color="#6c7388">Archived</Badge>
              <Badge v-else :color="accent">Unreleased</Badge>
              <span v-if="v.releaseDate" class="mono entity-date">{{ formatDate(v.releaseDate) }}</span>
              <Button v-if="!v.released && !v.archived" size="sm" @click="releaseVersion(v)">Release</Button>
            </div>
          </div>
          <div v-else class="empty-msg">No versions yet.</div>
        </SectionCard>
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Components                                                      -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Dependencies                                                    -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Dependencies'">
        <ProjectDependenciesSection :project-id="projectId" :accent="accent" />
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Components'">
        <SectionCard title="All Components">
          <template #right>
            <span class="mono section-meta">{{ components.length }} total</span>
          </template>
          <div v-if="components.length" class="entity-list">
            <div v-for="c in components" :key="c.id" class="entity-row">
              <Icon name="boxes" :size="15" :color="accent" />
              <div class="entity-info">
                <span class="entity-name">{{ c.name }}</span>
                <span v-if="c.description" class="entity-desc">{{ c.description }}</span>
              </div>
              <Badge :color="accent">{{ assigneeModeLabel(c.assigneeMode) }}</Badge>
              <button class="entity-delete" @click="deleteComponent(c)">
                <Icon name="trash" :size="13" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <div v-else class="empty-msg">No components yet.</div>
        </SectionCard>
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Labels                                                          -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Labels'">
        <SectionCard title="All Labels">
          <template #right>
            <span class="mono section-meta">{{ labels.length }} total</span>
          </template>
          <div v-if="labels.length" class="labels-grid">
            <div v-for="l in labels" :key="l.id" class="label-card">
              <span class="label-card-dot" :style="{ background: l.colorHex || '#6c7388' }" />
              <span class="label-card-name">{{ l.name }}</span>
              <button class="entity-delete" @click="deleteLabel(l)">
                <Icon name="trash" :size="12" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <div v-else class="empty-msg">No labels yet.</div>
        </SectionCard>
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Repositories                                                    -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Repositories'" class="analytics-tab">
        <SectionCard title="Git Repositories" subtitle="The git repositories this project owns — a release tags & builds each one when the project ships">
          <div class="analytics-add">
            <Select
              v-model="selectedRepoId"
              :options="repoOptions"
              :accent="accent"
              size="sm"
              :placeholder="repoOptions.length ? 'Pick a repository…' : 'No more repositories to add'" />
            <Button
              primary
              size="sm"
              icon="plus"
              :accent="accent"
              :disabled="!selectedRepoId"
              @click="addRepository">Add</Button>
          </div>
          <div v-if="projectRepositories.length" class="entity-list">
            <div v-for="r in projectRepositories" :key="r.id" class="entity-row">
              <Icon name="git-branch" :size="15" :color="accent" />
              <div class="entity-info">
                <span class="entity-name">{{ repoName(r.repositoryId) }}</span>
                <span v-if="repoSlug(r.repositoryId)" class="entity-desc mono">{{ repoSlug(r.repositoryId) }}</span>
              </div>
              <button
                class="entity-delete"
                title="Create .bosca/deploy.yaml from the program's environments"
                :disabled="deployConfigBusyId === r.repositoryId"
                @click="createDeployConfig(r.repositoryId)">
                <Icon name="rocket" :size="13" color="var(--fg-3)" />
              </button>
              <button class="entity-delete" @click="removeRepository(r.id)">
                <Icon name="trash" :size="13" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <div v-else class="empty-msg">No repositories attached yet.</div>
        </SectionCard>
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Analytics                                                       -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Analytics'" class="analytics-tab">
        <SectionCard title="Analytics Applications" subtitle="App identifiers this project owns — sessions & error/crash telemetry (sessions.<appId>)">
          <div class="analytics-add">
            <TextInput v-model="newAppId" placeholder="e.g. mobile" size="sm" />
            <Button
              primary
              size="sm"
              icon="plus"
              :accent="accent"
              :disabled="!newAppId.trim()"
              @click="addAnalyticsApp">Add</Button>
          </div>
          <div v-if="analyticsApps.length" class="entity-list">
            <div v-for="a in analyticsApps" :key="a.id" class="entity-row">
              <Icon name="target" :size="15" :color="accent" />
              <div class="entity-info"><span class="entity-name mono">{{ a.applicationId }}</span></div>
              <button class="entity-delete" @click="removeAnalyticsApp(a.id)">
                <Icon name="trash" :size="13" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <div v-else class="empty-msg">No analytics applications yet.</div>
        </SectionCard>

        <SectionCard title="Analytics Services" subtitle="Service names this project owns — API response-code telemetry (http.<service>)">
          <div class="analytics-add">
            <TextInput v-model="newService" placeholder="e.g. bosca" size="sm" />
            <Button
              primary
              size="sm"
              icon="plus"
              :accent="accent"
              :disabled="!newService.trim()"
              @click="addAnalyticsSvc">Add</Button>
          </div>
          <div v-if="analyticsServices.length" class="entity-list">
            <div v-for="s in analyticsServices" :key="s.id" class="entity-row">
              <Icon name="zap" :size="15" :color="accent" />
              <div class="entity-info"><span class="entity-name mono">{{ s.service }}</span></div>
              <button class="entity-delete" @click="removeAnalyticsSvc(s.id)">
                <Icon name="trash" :size="13" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <div v-else class="empty-msg">No analytics services yet.</div>
        </SectionCard>
      </div>
    </template>

    <!-- ─── Modals ─────────────────────────────────────────────────────────── -->
    <Modal
      v-if="showCreateVersion"
      title="New Version"
      icon="flag"
      :accent="accent"
      @close="showCreateVersion = false">
      <div class="form-stack">
        <TextInput v-model="versionForm.name" label="Name" placeholder="v1.0.0" />
        <TextInput v-model="versionForm.description" label="Description" placeholder="Optional" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreateVersion = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreateVersion">Create</Button>
      </template>
    </Modal>

    <MoveProjectModal
      v-if="showMove && project"
      :project="project"
      :accent="accent"
      @close="showMove = false"
      @moved="handleMoved"
    />

    <Modal
      v-if="showCreateComponent"
      title="New Component"
      icon="boxes"
      :accent="accent"
      @close="showCreateComponent = false">
      <div class="form-stack">
        <TextInput v-model="componentForm.name" label="Name" placeholder="Frontend" />
        <TextInput v-model="componentForm.description" label="Description" placeholder="Optional" />
        <Select v-model="componentForm.assigneeMode" :options="assigneeModeOptions" :accent="accent" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreateComponent = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreateComponent">Create</Button>
      </template>
    </Modal>

    <Modal
      v-if="showCreateLabel"
      title="New Label"
      icon="tag"
      :accent="accent"
      @close="showCreateLabel = false">
      <div class="form-stack">
        <TextInput v-model="labelForm.name" label="Name" placeholder="bug-fix" />
        <div class="color-row">
          <label class="field-label">Color</label>
          <input v-model="labelForm.colorHex" type="color" class="color-picker" >
          <span class="mono color-hex">{{ labelForm.colorHex }}</span>
        </div>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreateLabel = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreateLabel">Create</Button>
      </template>
    </Modal>

    <!-- Committed deploy config: show what landed so the TODO placeholders get filled in. -->
    <Modal
      v-if="deployConfigResult"
      :title="`${deployConfigResult.path} · ${deployConfigResult.repository}`"
      icon="rocket"
      :accent="accent"
      width="640px"
      @close="deployConfigResult = null">
      <div class="deploy-config-view">
        <p class="deploy-config-hint">
          Committed to <span class="mono">{{ deployConfigResult.branch }}</span>. Fill in the TODO fields
          in the repository before the first release run.
        </p>
        <pre class="deploy-config-content mono">{{ deployConfigResult.content }}</pre>
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" :accent="accent" @click="deployConfigResult = null">Done</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
/* ─── Shared ───────────────────────────────────────────────────────────────── */
.loading-state, .empty-msg {
  color: var(--fg-3); text-align: center; padding: 48px 0; font-size: 13.5px;
}
.title-row { display: inline-flex; align-items: center; gap: 12px; }
.project-mark {
  width: 36px; height: 36px; border-radius: 10px;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 700; color: #fff; flex: 0 0 36px;
}
.section-meta { font-size: 11px; color: var(--fg-3); }

/* ─── Overview Layout (matches org detail) ─────────────────────────────────── */
.overview-layout {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 16px;
  align-items: start;
}
.overview-sidebar { display: flex; flex-direction: column; gap: 12px; }
.overview-main    { display: flex; flex-direction: column; gap: 12px; }

/* ─── Sidebar cards (matches org-card) ─────────────────────────────────────── */
.card {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 16px;
}
.card-section-title {
  font-size: 11px; color: var(--fg-3); text-transform: uppercase;
  letter-spacing: .08em; font-weight: 600; margin-bottom: 10px;
}

.card-info-list   { display: flex; flex-direction: column; gap: 8px; }
.card-info-row    { display: flex; justify-content: space-between; align-items: center; }
.card-info-label  { font-size: 12px; color: var(--fg-3); }
.card-info-value  { font-size: 12.5px; color: var(--fg-0); font-weight: 500; }

.card-description {
  margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--line);
  font-size: 12.5px; line-height: 1.55; color: var(--fg-2);
}

.card-labels { display: flex; flex-wrap: wrap; gap: 6px; }
.card-label-chip {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 11.5px; padding: 3px 9px; border-radius: 5px;
  background: var(--bg-2); color: var(--fg-1);
}
.card-label-dot { width: 8px; height: 8px; border-radius: 50%; flex: 0 0 8px; }

.card-components { display: flex; flex-direction: column; gap: 6px; }
.card-component-row { display: flex; align-items: center; gap: 8px; padding: 6px 0; }
.card-component-info { flex: 1; min-width: 0; }
.card-component-name { font-size: 12.5px; font-weight: 500; color: var(--fg-0); display: block; }
.card-component-mode { font-size: 10.5px; color: var(--fg-3); }

.card-versions { display: flex; flex-direction: column; gap: 6px; }
.card-version-row { display: flex; align-items: center; gap: 8px; padding: 6px 0; }
.card-version-name { font-size: 12.5px; font-weight: 500; color: var(--fg-0); flex: 1; }

/* ─── Status breakdown (uses ProgressBar) ──────────────────────────────────────────── */
.status-breakdown { padding: 10px 16px; display: flex; flex-direction: column; gap: 10px; }
.status-row { display: flex; align-items: center; gap: 12px; }
.status-name { font-size: 12px; color: var(--fg-2); min-width: 90px; }
.status-bar-wrap { flex: 1; }
.status-count { font-size: 12px; font-weight: 600; min-width: 20px; text-align: right; }

/* ─── Activity feed (matches org activity) ─────────────────────────────────── */
.activity-list { padding: 4px 0; }
.activity-row {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 16px; border-top: 1px solid var(--line);
  cursor: pointer; transition: background 0.1s;
}
.activity-row:hover { background: var(--bg-2); }
.activity-icon {
  width: 28px; height: 28px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center; flex: 0 0 auto;
}
.activity-body { flex: 1; min-width: 0; }
.activity-label { font-size: 13px; color: var(--fg-0); display: flex; align-items: center; gap: 6px; }
.activity-key {
  font-size: 10px; padding: 1px 5px; background: var(--bg-3);
  border-radius: 3px; color: var(--fg-3); font-weight: 600;
}
.activity-meta { display: flex; align-items: center; gap: 8px; margin-top: 3px; }
.activity-assignee { display: flex; align-items: center; gap: 5px; font-size: 11px; color: var(--fg-3); }
.activity-time { font-size: 11px; color: var(--fg-3); flex: 0 0 auto; }

/* ─── Entity list (Versions/Components tabs) ───────────────────────────────── */
.entity-list { padding: 4px 0; }
.entity-row {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 16px; border-top: 1px solid var(--line);
}
.entity-info { flex: 1; min-width: 0; }
.entity-name { font-size: 13.5px; font-weight: 500; color: var(--fg-0); display: block; }
.entity-desc { font-size: 11.5px; color: var(--fg-3); }
.entity-date { font-size: 11px; color: var(--fg-3); }
.entity-delete {
  background: none; border: none; padding: 4px; cursor: pointer;
  border-radius: 4px; display: flex; opacity: 0.4; transition: opacity 0.1s;
}
.entity-delete:hover { opacity: 1; background: color-mix(in oklch, var(--err) 10%, transparent); }

/* ─── Analytics (tab) ──────────────────────────────────────────────────────── */
.analytics-tab { display: flex; flex-direction: column; gap: 16px; }
.analytics-add { display: flex; align-items: center; gap: 8px; padding: 12px 16px 6px; max-width: 420px; }
.analytics-add > :first-child { flex: 1; }

/* ─── Labels grid (tab) ────────────────────────────────────────────────────── */
.labels-grid { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px 16px; }
.label-card {
  display: flex; align-items: center; gap: 8px;
  font-size: 13px; padding: 8px 14px; border-radius: 8px;
  background: var(--bg-2); border: 1px solid var(--line); color: var(--fg-0);
}
.label-card-dot { width: 10px; height: 10px; border-radius: 50%; flex: 0 0 10px; }
.label-card-name { flex: 1; font-weight: 500; }

/* ─── Forms ────────────────────────────────────────────────────────────────── */
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.color-row { display: flex; align-items: center; gap: 10px; }
.field-label { font-size: 12px; font-weight: 600; color: var(--fg-2); }
.color-picker { width: 32px; height: 32px; border: none; border-radius: 6px; cursor: pointer; background: none; }
.color-hex { font-size: 12px; color: var(--fg-2); }

/* ─── Deploy config ────────────────────────────────────────────────────────── */
.deploy-config-view { display: flex; flex-direction: column; gap: 10px; }
.deploy-config-hint { margin: 0; font-size: 12.5px; color: var(--fg-2); line-height: 1.5; }
.deploy-config-content {
  margin: 0; padding: 12px; font-size: 12px; line-height: 1.55; color: var(--fg-1);
  background: var(--bg-0); border: 1px solid var(--line); border-radius: 8px;
  overflow-x: auto; max-height: 420px;
}
.spacer { flex: 1; }
</style>
