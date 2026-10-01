<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query, mutation } = useGraphQL()
const toast = useToast()
const router = useRouter()

const selectedProjectId = ref('')
const selectedBoardId = ref('')
const showCreate = ref(false)
const showStart = ref(false)
const saving = ref(false)
const error = ref('')
const showClosed = ref(false)

const sprintForm = reactive({ name: '', goal: '' })
const startForm = reactive({ sprintId: '', startDate: '', endDate: '' })

interface Sprint {
  id: string
  name: string
  state: 'ACTIVE' | 'FUTURE' | 'CLOSED'
  goal: string | null
  startDate: string | null
  endDate: string | null
  completeDate: string | null
  committedTaskIds: string[]
  addedDuringSprintTaskIds: string[]
  velocityPoints: number | null
  boardId: string
  version: number
}

// ─── Projects ────────────────────────────────────────────────────────────────

const projectsGql = gql`
  query { workOps { projects { all { id key name archivedAt } } } }
`

const { data: projectsData } = useAsyncQuery<{
  workOps: { projects: { all: Array<{ id: string; key: string; name: string; archivedAt: string | null }> } }
}>('workops-sprints-projects', projectsGql)

const projects = computed(() =>
  (projectsData.value?.workOps?.projects?.all ?? []).filter(p => !p.archivedAt),
)

const projectOptions = computed(() =>
  projects.value.map(p => ({ value: p.id, label: `${p.key} — ${p.name}` })),
)

// ─── Boards ──────────────────────────────────────────────────────────────────

const boardsGql = gql`
  query GetBoardsForSprints($projectId: UUID!) {
    workOps {
      boards {
        byProject(projectId: $projectId) {
          id name type
        }
      }
    }
  }
`

interface Board {
  id: string
  name: string
  type: string
}

const boards = ref<Board[]>([])
const boardsLoading = ref(false)
const boardOptions = computed(() => boards.value.map(b => ({ value: b.id, label: `${b.name} (${b.type})` })))

async function loadBoards() {
  if (!selectedProjectId.value) { boards.value = []; selectedBoardId.value = ''; return }
  boardsLoading.value = true
  try {
    const result = await query<{ workOps: { boards: { byProject: Board[] } } }>(
      boardsGql, { projectId: selectedProjectId.value },
    )
    boards.value = result.workOps?.boards?.byProject ?? []
    if (boards.value.length && !boards.value.find(b => b.id === selectedBoardId.value) && boards.value[0]) {
      selectedBoardId.value = boards.value[0].id
    }
    if (!boards.value.length) selectedBoardId.value = ''
  } finally { boardsLoading.value = false }
}

// ─── Sprints ─────────────────────────────────────────────────────────────────

const sprintsGql = gql`
  query GetSprintsByBoard($boardId: UUID!) {
    workOps {
      sprints {
        byBoard(boardId: $boardId) {
          id name state goal
          startDate endDate completeDate
          committedTaskIds addedDuringSprintTaskIds
          velocityPoints boardId version
        }
      }
    }
  }
`

const sprints = ref<Sprint[]>([])
const sprintsLoading = ref(false)

const activeSprint = computed(() => sprints.value.find(s => s.state === 'ACTIVE'))
const futureSprints = computed(() => sprints.value.filter(s => s.state === 'FUTURE'))
const closedSprints = computed(() => sprints.value.filter(s => s.state === 'CLOSED'))

async function loadSprints() {
  if (!selectedBoardId.value) { sprints.value = []; return }
  sprintsLoading.value = true
  try {
    const result = await query<{ workOps: { sprints: { byBoard: Sprint[] } } }>(
      sprintsGql, { boardId: selectedBoardId.value },
    )
    sprints.value = result.workOps?.sprints?.byBoard ?? []
  } finally { sprintsLoading.value = false }
}

// ─── Watchers ────────────────────────────────────────────────────────────────

watch(projects, (ps) => {
  if (ps.length && !selectedProjectId.value && ps[0]) {
    selectedProjectId.value = ps[0].id
  }
}, { immediate: true })

watch(selectedProjectId, () => { loadBoards() })
watch(selectedBoardId, () => { loadSprints() })

// ─── Create Sprint ───────────────────────────────────────────────────────────

function openCreate() {
  sprintForm.name = ''
  sprintForm.goal = ''
  error.value = ''
  showCreate.value = true
}

async function handleCreate() {
  if (!sprintForm.name.trim()) { error.value = 'Name is required.'; return }
  saving.value = true
  error.value = ''
  try {
    await mutation(gql`
      mutation CreateSprint($input: CreateWorkOpsSprintInput!) {
        workOps { sprints { create(input: $input) { id } } }
      }
    `, {
      input: {
        boardId: selectedBoardId.value,
        name: sprintForm.name.trim(),
        goal: sprintForm.goal.trim() || null,
      },
    })
    showCreate.value = false
    toast.success('Sprint created')
    await loadSprints()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create sprint'
  } finally { saving.value = false }
}

// ─── Start Sprint ────────────────────────────────────────────────────────────

interface BacklogTask {
  id: string
  key: string
  summary: string
  priority: { displayOrder: number; name: string }
}

const backlogTasks = ref<BacklogTask[]>([])
const backlogLoading = ref(false)
const selectedCommitIds = ref<Set<string>>(new Set())

async function loadBacklog() {
  backlogLoading.value = true
  try {
    const result = await query<{
      workOps: { savedFilters: { searchTasks: { rows: BacklogTask[] } } }
    }>(gql`
      query BacklogForHubStart($source: String!, $limit: Int!) {
        workOps { savedFilters { searchTasks(source: $source, limit: $limit, offset: 0) {
          rows { id key summary priority { displayOrder name } }
        } } }
      }
    `, { source: 'status != "Done" AND status != "Cancelled" ORDER BY priority ASC', limit: 100 })
    backlogTasks.value = result.workOps?.savedFilters?.searchTasks?.rows ?? []
  } catch { backlogTasks.value = [] }
  finally { backlogLoading.value = false }
}

function toggleCommit(id: string) {
  const s = new Set(selectedCommitIds.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  selectedCommitIds.value = s
}

async function openStart(sprint: Sprint) {
  const today = new Date()
  const twoWeeks = new Date(today.getTime() + 14 * 86_400_000)
  startForm.sprintId = sprint.id
  startForm.startDate = today.toISOString().slice(0, 10)
  startForm.endDate = twoWeeks.toISOString().slice(0, 10)
  selectedCommitIds.value = new Set()
  error.value = ''
  showStart.value = true
  await loadBacklog()
}

async function handleStart() {
  if (!startForm.sprintId) return
  const sprint = sprints.value.find(s => s.id === startForm.sprintId)
  if (!sprint) return
  saving.value = true
  error.value = ''
  try {
    await mutation(gql`
      mutation StartSprint($input: StartWorkOpsSprintInput!) {
        workOps { sprints { start(input: $input) { id } } }
      }
    `, {
      input: {
        sprintId: startForm.sprintId,
        expectedVersion: sprint.version,
        startDate: startForm.startDate || null,
        endDate: startForm.endDate || null,
        committedTaskIds: selectedCommitIds.value.size ? [...selectedCommitIds.value] : undefined,
      },
    })
    showStart.value = false
    toast.success('Sprint started')
    await loadSprints()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to start sprint'
  } finally { saving.value = false }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function formatDate(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

function sprintDateRange(s: Sprint): string {
  if (!s.startDate) return 'Not started'
  const start = formatDate(s.startDate)
  const end = s.endDate ? formatDate(s.endDate) : '?'
  return `${start} → ${end}`
}

function daysRemaining(s: Sprint): { label: string; color: string } | null {
  if (s.state !== 'ACTIVE' || !s.endDate) return null
  const diff = Math.floor((new Date(s.endDate).getTime() - Date.now()) / 86_400_000)
  if (diff < 0) return { label: `${Math.abs(diff)}d overdue`, color: 'var(--err)' }
  if (diff === 0) return { label: 'Ends today', color: 'var(--warn)' }
  if (diff <= 3) return { label: `${diff}d left`, color: 'var(--warn)' }
  return { label: `${diff}d left`, color: 'var(--fg-3)' }
}

function stateColor(state: string): string {
  if (state === 'ACTIVE') return '#34d99a'
  if (state === 'FUTURE') return accent.value
  return 'var(--fg-3)'
}

function scopeCreepCount(s: Sprint): number {
  return s.addedDuringSprintTaskIds.length
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Sprints')"
        title="Sprints"
        :subtitle="activeSprint ? `Active: ${activeSprint.name}` : `${sprints.length} sprints`"
      >
        <template #actions>
          <Select
            v-model="selectedProjectId"
            :options="projectOptions"
            placeholder="Project"
            :accent="accent"
            size="sm"
          />
          <Select
            v-model="selectedBoardId"
            :options="boardOptions"
            placeholder="Board"
            :accent="accent"
            size="sm"
          />
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedBoardId"
            @click="openCreate"
          >
            New Sprint
          </Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="sprintsLoading" class="loading-state">Loading sprints…</div>

    <div v-else-if="!selectedBoardId" class="empty-state">
      <p v-if="!selectedProjectId">Select a project to view sprints.</p>
      <p v-else>No boards for this project.</p>
    </div>

    <div v-else-if="!sprints.length" class="empty-state">
      <Icon name="refresh" :size="32" color="var(--fg-3)" />
      <p>No sprints yet. Create one to start planning iterations.</p>
    </div>

    <div v-else class="sprint-list">
      <!-- Active Sprint -->
      <SectionCard v-if="activeSprint" title="Active Sprint" glass>
        <div class="card-body">
          <div
            class="sprint-card active"
            @click="router.push(`/workops/sprints/${activeSprint.id}`)"
          >
            <div class="sprint-row">
              <div class="sprint-info">
                <div class="sprint-name-row">
                  <Badge :color="stateColor('ACTIVE')" solid>ACTIVE</Badge>
                  <span class="sprint-name">{{ activeSprint.name }}</span>
                  <span v-if="daysRemaining(activeSprint)" class="days-hint" :style="{ color: daysRemaining(activeSprint)!.color }">
                    {{ daysRemaining(activeSprint)!.label }}
                  </span>
                </div>
                <p v-if="activeSprint.goal" class="sprint-goal">{{ activeSprint.goal }}</p>
                <div class="sprint-meta">
                  <span>{{ sprintDateRange(activeSprint) }}</span>
                  <span>{{ activeSprint.committedTaskIds.length }} committed</span>
                  <span v-if="scopeCreepCount(activeSprint)" class="scope-creep">
                    +{{ scopeCreepCount(activeSprint) }} added
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </SectionCard>

      <!-- Future Sprints -->
      <SectionCard v-if="futureSprints.length" title="Planned" glass>
        <div class="card-body">
          <div
            v-for="s in futureSprints"
            :key="s.id"
            class="sprint-card"
            @click="router.push(`/workops/sprints/${s.id}`)"
          >
            <div class="sprint-row">
              <div class="sprint-info">
                <div class="sprint-name-row">
                  <Badge :color="stateColor('FUTURE')">PLANNED</Badge>
                  <span class="sprint-name">{{ s.name }}</span>
                </div>
                <p v-if="s.goal" class="sprint-goal">{{ s.goal }}</p>
              </div>
              <div class="sprint-actions" @click.stop>
                <Button
                  size="sm"
                  primary
                  :accent="accent"
                  :disabled="!!activeSprint"
                  @click="openStart(s)"
                >
                  Start
                </Button>
              </div>
            </div>
          </div>
        </div>
      </SectionCard>

      <!-- Closed Sprints -->
      <div v-if="closedSprints.length" class="closed-section">
        <button class="closed-toggle" @click="showClosed = !showClosed">
          <Icon :name="showClosed ? 'chevronDown' : 'chevron'" :size="12" color="var(--fg-3)" />
          <span>Closed ({{ closedSprints.length }})</span>
        </button>
        <SectionCard v-if="showClosed" :title="`Closed (${closedSprints.length})`" glass>
          <div class="card-body">
            <div
              v-for="s in closedSprints"
              :key="s.id"
              class="sprint-card closed"
              @click="router.push(`/workops/sprints/${s.id}`)"
            >
              <div class="sprint-row">
                <div class="sprint-info">
                  <div class="sprint-name-row">
                    <Badge :color="stateColor('CLOSED')">CLOSED</Badge>
                    <span class="sprint-name">{{ s.name }}</span>
                  </div>
                  <div class="sprint-meta">
                    <span>{{ sprintDateRange(s) }}</span>
                    <span>{{ s.committedTaskIds.length }} tasks</span>
                    <span v-if="s.velocityPoints != null">{{ s.velocityPoints }} pts</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </SectionCard>
      </div>
    </div>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Sprint"
      icon="refresh"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="sprintForm.name"
          label="Name"
          placeholder="e.g. Sprint 12"
          autofocus />
        <TextInput v-model="sprintForm.goal" label="Goal" placeholder="What should be achieved this sprint?" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="!sprintForm.name.trim() || saving"
          @click="handleCreate">
          {{ saving ? 'Creating…' : 'Create Sprint' }}
        </Button>
      </template>
    </Modal>

    <!-- Start Modal -->
    <Modal
      v-if="showStart"
      title="Start Sprint"
      icon="refresh"
      :accent="accent"
      @close="showStart = false">
      <div class="form-stack">
        <TextInput v-model="startForm.startDate" label="Start Date" type="date" />
        <TextInput v-model="startForm.endDate" label="End Date" type="date" />

        <div class="commit-section">
          <div class="commit-header">
            <span class="commit-label">Commit Tasks ({{ selectedCommitIds.size }} selected)</span>
          </div>
          <div v-if="backlogLoading" class="commit-loading">Loading tasks…</div>
          <div v-else-if="!backlogTasks.length" class="commit-empty">No available tasks.</div>
          <div v-else class="commit-list">
            <label
              v-for="t in backlogTasks"
              :key="t.id"
              class="commit-task-row"
              :class="{ selected: selectedCommitIds.has(t.id) }"
            >
              <input type="checkbox" :checked="selectedCommitIds.has(t.id)" @change="toggleCommit(t.id)" >
              <span class="commit-task-key">{{ t.key }}</span>
              <span class="commit-task-summary">{{ t.summary }}</span>
            </label>
          </div>
        </div>

        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showStart = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleStart">
          {{ saving ? 'Starting…' : 'Start Sprint' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.loading-state,
.empty-state {
  color: var(--fg-3);
  text-align: center;
  padding: 60px 0;
  font-size: 14px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.sprint-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-body {
  padding: 4px 0;
}

.sprint-card {
  padding: 10px 16px;
  cursor: pointer;
  transition: background 0.15s;
}

.sprint-card + .sprint-card {
  border-top: 1px solid var(--border-1);
}

.sprint-card:hover {
  background: var(--bg-2);
}

.sprint-card.closed {
  opacity: 0.65;
}

.sprint-card.active {
  border-left: 3px solid #34d99a;
  padding-left: 13px;
}

.closed-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sprint-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.sprint-info {
  flex: 1;
  min-width: 0;
}

.sprint-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sprint-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--fg-0);
}

.days-hint {
  font-size: 11px;
  font-weight: 500;
}

.sprint-goal {
  color: var(--fg-2);
  font-size: 13px;
  margin: 4px 0 0;
  line-height: 1.4;
}

.sprint-meta {
  display: flex;
  gap: 12px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--fg-3);
}

.scope-creep {
  color: var(--warn);
  font-weight: 500;
}

.sprint-actions {
  flex-shrink: 0;
  padding-top: 2px;
}

.closed-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  color: var(--fg-3);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  padding: 0;
}

.closed-toggle:hover {
  color: var(--fg-1);
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-error {
  color: var(--err);
  font-size: 12px;
  margin: 0;
}

/* Commit task picker */
.commit-section {
  border-top: 1px solid var(--border-1);
  padding-top: 12px;
}

.commit-header { margin-bottom: 8px; }

.commit-label {
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--fg-2);
}

.commit-loading,
.commit-empty {
  font-size: 12px;
  color: var(--fg-3);
  padding: 8px 0;
}

.commit-list {
  max-height: 240px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.commit-task-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  transition: background 0.1s;
}

.commit-task-row:hover { background: var(--bg-2); }

.commit-task-row.selected {
  background: color-mix(in oklch, var(--bg-2) 80%, v-bind(accent) 20%);
}

.commit-task-row input[type="checkbox"] {
  flex-shrink: 0;
  accent-color: v-bind(accent);
}

.commit-task-key {
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 600;
  color: var(--fg-2);
  flex-shrink: 0;
}

.commit-task-summary {
  flex: 1;
  color: var(--fg-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
