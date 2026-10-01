<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { query, mutation } = useGraphQL()
const toast = useToast()

const sprintId = computed(() => route.params.id as string)
const activeTab = ref('Tasks')
const loading = ref(true)
const showStart = ref(false)
const showClose = ref(false)
const saving = ref(false)
const error = ref('')

const startForm = reactive({ startDate: '', endDate: '' })

const STATUS_COLORS: Record<string, string> = {
  TODO: '#6c7388',
  IN_PROGRESS: '#a78bff',
  IN_REVIEW: '#ffb547',
  DONE: '#34d99a',
  CANCELLED: '#6c7388',
}

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
  board: { id: string; name: string; type: string }
  version: number
  createdAt: string
  modifiedAt: string
}

interface TaskSummary {
  id: string
  key: string
  summary: string
  status: { id: string; name: string; category: string }
  priority: { id: string; name: string; displayOrder: number }
  assignee: { id: string; name: string } | null
  version?: number
}

const sprint = ref<Sprint | null>(null)
const committedTasks = ref<TaskSummary[]>([])
const addedTasks = ref<TaskSummary[]>([])
const tasksLoading = ref(false)

// ─── Load Sprint ─────────────────────────────────────────────────────────────

const sprintGql = gql`
  query GetSprint($id: UUID!) {
    workOps {
      sprints {
        sprint(id: $id) {
          id name state goal
          startDate endDate completeDate
          committedTaskIds addedDuringSprintTaskIds
          velocityPoints boardId
          board { id name type }
          version createdAt modifiedAt
        }
      }
    }
  }
`

async function loadSprint() {
  loading.value = true
  try {
    const result = await query<{ workOps: { sprints: { sprint: Sprint | null } } }>(
      sprintGql, { id: sprintId.value },
    )
    sprint.value = result.workOps?.sprints?.sprint ?? null
    if (sprint.value) await loadTasks()
  } finally { loading.value = false }
}

// ─── Load Tasks ──────────────────────────────────────────────────────────────

const taskGql = gql`
  query GetTaskForSprint($id: UUID!) {
    workOps {
      tasks {
        task(id: $id) {
          id key summary
          status { id name category }
          priority { id name displayOrder }
          assignee { id name }
          version
        }
      }
    }
  }
`

async function fetchTask(taskId: string): Promise<TaskSummary | null> {
  try {
    const result = await query<{ workOps: { tasks: { task: TaskSummary | null } } }>(
      taskGql, { id: taskId },
    )
    return result.workOps?.tasks?.task ?? null
  } catch { return null }
}

async function loadTasks() {
  if (!sprint.value) return
  tasksLoading.value = true
  try {
    const committed = await Promise.all(sprint.value.committedTaskIds.map(fetchTask))
    committedTasks.value = committed.filter((t): t is TaskSummary => t != null)

    const addedIds = sprint.value.addedDuringSprintTaskIds.filter(
      id => !sprint.value!.committedTaskIds.includes(id),
    )
    if (addedIds.length) {
      const added = await Promise.all(addedIds.map(fetchTask))
      addedTasks.value = added.filter((t): t is TaskSummary => t != null)
    } else {
      addedTasks.value = []
    }
  } finally { tasksLoading.value = false }
}

// ─── Actions ─────────────────────────────────────────────────────────────────

const backlogTasks = ref<TaskSummary[]>([])
const backlogLoading = ref(false)
const selectedTaskIds = ref<Set<string>>(new Set())

async function loadBacklogTasks() {
  if (!sprint.value) return
  backlogLoading.value = true
  try {
    const result = await query<{
      workOps: { savedFilters: { searchTasks: { rows: TaskSummary[] } } }
    }>(gql`
      query BacklogTasks($source: String!, $limit: Int!) {
        workOps { savedFilters { searchTasks(source: $source, limit: $limit, offset: 0) {
          rows { id key summary status { id name category } priority { id name displayOrder } assignee { id name } version }
        } } }
      }
    `, { source: 'status != "Done" AND status != "Cancelled" ORDER BY priority ASC', limit: 100 })
    const rows = result.workOps?.savedFilters?.searchTasks?.rows ?? []
    const sprintTaskIds = new Set([
      ...(sprint.value?.committedTaskIds ?? []),
      ...(sprint.value?.addedDuringSprintTaskIds ?? []),
    ])
    backlogTasks.value = rows.filter(t => !sprintTaskIds.has(t.id))
  } catch { backlogTasks.value = [] }
  finally { backlogLoading.value = false }
}

function toggleTask(id: string) {
  const s = new Set(selectedTaskIds.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  selectedTaskIds.value = s
}

async function openStart() {
  const today = new Date()
  const twoWeeks = new Date(today.getTime() + 14 * 86_400_000)
  startForm.startDate = today.toISOString().slice(0, 10)
  startForm.endDate = twoWeeks.toISOString().slice(0, 10)
  selectedTaskIds.value = new Set()
  error.value = ''
  showStart.value = true
  await loadBacklogTasks()
}

async function handleStart() {
  if (!sprint.value) return
  saving.value = true
  error.value = ''
  try {
    await mutation(gql`
      mutation StartSprint($input: StartWorkOpsSprintInput!) {
        workOps { sprints { start(input: $input) { id } } }
      }
    `, {
      input: {
        sprintId: sprint.value.id,
        expectedVersion: sprint.value.version,
        startDate: startForm.startDate || null,
        endDate: startForm.endDate || null,
        committedTaskIds: selectedTaskIds.value.size ? [...selectedTaskIds.value] : undefined,
      },
    })
    showStart.value = false
    toast.success('Sprint started')
    await loadSprint()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to start sprint'
  } finally { saving.value = false }
}

const futureSprints = ref<Array<{ id: string; name: string }>>([])

async function loadFutureSprints() {
  if (!sprint.value) return
  try {
    const result = await query<{
      workOps: { sprints: { byBoard: Array<{ id: string; name: string; state: string }> } }
    }>(gql`
      query GetFutureSprints($boardId: UUID!) {
        workOps { sprints { byBoard(boardId: $boardId) { id name state } } }
      }
    `, { boardId: sprint.value.boardId })
    futureSprints.value = (result.workOps?.sprints?.byBoard ?? [])
      .filter(s => s.state === 'FUTURE')
  } catch { futureSprints.value = [] }
}

async function openClose() {
  await loadFutureSprints()
  showClose.value = true
}

async function onCloseCompleted() {
  showClose.value = false
  await loadSprint()
}

// ─── Task Search & Add ───────────────────────────────────────────────────────

const taskSearchQuery = ref('')
const taskSearchResults = ref<TaskSummary[]>([])

const searchTasksGql = gql`
  query SearchTasksForSprint($source: String!, $limit: Int!, $offset: Long!) {
    workOps {
      savedFilters {
        searchTasks(source: $source, limit: $limit, offset: $offset) {
          rows {
            id key summary
            status { id name category }
            priority { id name displayOrder }
            assignee { id name }
            version
          }
        }
      }
    }
  }
`

async function searchTasks() {
  if (!taskSearchQuery.value.trim()) return
  try {
    const bql = `summary ~ "${taskSearchQuery.value.trim()}" ORDER BY priority ASC`
    const result = await query<{
      workOps: { savedFilters: { searchTasks: { rows: TaskSummary[] } } }
    }>(searchTasksGql, { source: bql, limit: 20, offset: 0 })
    const rows = result.workOps?.savedFilters?.searchTasks?.rows ?? []
    const existingIds = new Set([
      ...(sprint.value?.committedTaskIds ?? []),
      ...(sprint.value?.addedDuringSprintTaskIds ?? []),
    ])
    taskSearchResults.value = rows.filter(t => !existingIds.has(t.id))
  } catch {
    toast.error('Search failed')
    taskSearchResults.value = []
  }
}

async function addTaskToSprint(task: TaskSummary) {
  if (!sprint.value) return
  saving.value = true
  try {
    await mutation(gql`
      mutation UpdateTask($id: UUID!, $input: UpdateWorkOpsTaskInput!) {
        workOps { tasks { update(id: $id, input: $input) { id version } } }
      }
    `, {
      id: task.id,
      input: { sprintId: sprint.value.id, expectedVersion: task.version ?? 0 },
    })
    taskSearchResults.value = taskSearchResults.value.filter(t => t.id !== task.id)
    toast.success(`${task.key} added to sprint`)
    await loadSprint()
  } catch {
    toast.error(`Failed to add ${task.key} to sprint`)
  } finally { saving.value = false }
}

// ─── Computed ────────────────────────────────────────────────────────────────

const allTasks = computed(() => [...committedTasks.value, ...addedTasks.value])

const doneTasks = computed(() =>
  allTasks.value.filter(t => t.status.category === 'DONE'),
)

const completionPercent = computed(() => {
  if (!allTasks.value.length) return 0
  return Math.round((doneTasks.value.length / allTasks.value.length) * 100)
})

const scopeCreepCount = computed(() => addedTasks.value.length)

// ─── Helpers ─────────────────────────────────────────────────────────────────

function formatDate(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

function stateColor(state: string): string {
  if (state === 'ACTIVE') return '#34d99a'
  if (state === 'FUTURE') return accent.value
  return 'var(--fg-3)'
}

function daysRemaining(): { label: string; color: string } | null {
  if (!sprint.value || sprint.value.state !== 'ACTIVE' || !sprint.value.endDate) return null
  const diff = Math.floor((new Date(sprint.value.endDate).getTime() - Date.now()) / 86_400_000)
  if (diff < 0) return { label: `${Math.abs(diff)}d overdue`, color: 'var(--err)' }
  if (diff === 0) return { label: 'Ends today', color: 'var(--warn)' }
  if (diff <= 3) return { label: `${diff}d left`, color: 'var(--warn)' }
  return { label: `${diff}d left`, color: 'var(--fg-3)' }
}

function priorityColor(order: number): string {
  const map: Record<number, string> = { 1: '#ff5d6c', 2: '#ff8a4d', 3: '#ffb547', 4: '#5ec5ff', 5: '#6c7388' }
  return map[order] ?? '#6c7388'
}

// ─── Init ────────────────────────────────────────────────────────────────────

// ─── Keyboard Navigation ─────────────────────────────────────────────────────

const selectedTaskIdx = ref(-1)

function onKeyDown(e: KeyboardEvent) {
  if (activeTab.value !== 'Tasks') return
  if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
  const taskList = allTasks.value
  if (e.key === 'j') {
    e.preventDefault()
    selectedTaskIdx.value = Math.min(selectedTaskIdx.value + 1, taskList.length - 1)
  } else if (e.key === 'k') {
    e.preventDefault()
    selectedTaskIdx.value = Math.max(selectedTaskIdx.value - 1, 0)
  } else if (e.key === 'Enter' && selectedTaskIdx.value >= 0 && taskList[selectedTaskIdx.value]) {
    router.push(`/workops/tasks/${taskList[selectedTaskIdx.value]!.key}`)
  }
}

onMounted(() => { document.addEventListener('keydown', onKeyDown) })
onUnmounted(() => { document.removeEventListener('keydown', onKeyDown) })

// ─── Init ────────────────────────────────────────────────────────────────────

loadSprint()
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        v-if="sprint"
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', { label: 'Sprints', to: '/workops/sprints' }, sprint.name)"
        :title="sprint.name"
        :subtitle="sprint.board.name"
      >
        <template #title-after>
          <Badge :color="stateColor(sprint.state)" :solid="sprint.state === 'ACTIVE'">
            {{ sprint.state }}
          </Badge>
          <span v-if="daysRemaining()" class="days-badge" :style="{ color: daysRemaining()!.color }">
            {{ daysRemaining()!.label }}
          </span>
        </template>
        <template #actions>
          <Button
            v-if="sprint.state === 'FUTURE'"
            primary
            size="sm"
            :accent="accent"
            @click="openStart"
          >
            Start Sprint
          </Button>
          <Button
            v-if="sprint.state === 'ACTIVE'"
            size="sm"
            @click="openClose"
          >
            Complete Sprint
          </Button>
        </template>
      </PageHeader>
      <PageHeader
        v-else-if="loading"
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', { label: 'Sprints', to: '/workops/sprints' }, '…')"
        title="Loading…"
      />
      <PageHeader
        v-else
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', { label: 'Sprints', to: '/workops/sprints' }, 'Not Found')"
        title="Sprint Not Found"
      />
    </template>

    <div v-if="loading" class="loading-state">Loading sprint…</div>

    <div v-else-if="!sprint" class="empty-state">
      <p>This sprint could not be found.</p>
      <Button :accent="accent" @click="router.push('/workops/sprints')">Back to Sprints</Button>
    </div>

    <template v-else>
      <div class="tab-bar">
        <button
          v-for="tab in ['Overview', 'Tasks', 'Progress']"
          :key="tab"
          class="tab-btn"
          :class="{ active: activeTab === tab }"
          :style="activeTab === tab ? { borderColor: accent } : {}"
          @click="activeTab = tab"
        >
          {{ tab }}
        </button>
      </div>

      <!-- Overview Tab -->
      <div v-if="activeTab === 'Overview'" class="tab-content">
        <SectionCard title="Details" glass>
          <div class="card-body details-grid">
            <div class="detail-item">
              <span class="detail-label">Goal</span>
              <span class="detail-value">{{ sprint.goal || 'No goal set' }}</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">Board</span>
              <NuxtLink class="detail-link" :to="`/workops/boards/${sprint.boardId}`">
                {{ sprint.board.name }}
              </NuxtLink>
            </div>
            <div class="detail-item">
              <span class="detail-label">Type</span>
              <span class="detail-value">{{ sprint.board.type }}</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">Start Date</span>
              <span class="detail-value">{{ formatDate(sprint.startDate) }}</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">End Date</span>
              <span class="detail-value">{{ formatDate(sprint.endDate) }}</span>
            </div>
            <div v-if="sprint.completeDate" class="detail-item">
              <span class="detail-label">Completed</span>
              <span class="detail-value">{{ formatDate(sprint.completeDate) }}</span>
            </div>
            <div v-if="sprint.velocityPoints != null" class="detail-item">
              <span class="detail-label">Velocity</span>
              <span class="detail-value">{{ sprint.velocityPoints }} pts</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">Created</span>
              <span class="detail-value">{{ formatDate(sprint.createdAt) }}</span>
            </div>
          </div>
        </SectionCard>
      </div>

      <!-- Tasks Tab -->
      <div v-if="activeTab === 'Tasks'" class="tab-content">
        <!-- Add Task Search -->
        <div class="add-task-bar">
          <TextInput
            v-model="taskSearchQuery"
            placeholder="Search tasks by key or summary to add…"
            @keydown.enter="searchTasks"
          />
          <Button
            size="sm"
            :accent="accent"
            :disabled="!taskSearchQuery.trim()"
            @click="searchTasks">Search</Button>
        </div>

        <SectionCard v-if="taskSearchResults.length" title="Search Results" glass>
          <div class="card-body">
            <div
              v-for="task in taskSearchResults"
              :key="task.id"
              class="task-row task-row-addable"
            >
              <Badge
                :color="STATUS_COLORS[task.status.category] ?? '#6c7388'"
                class="task-status"
              >
                {{ task.status.name }}
              </Badge>
              <span class="task-key">{{ task.key }}</span>
              <span class="task-summary">{{ task.summary }}</span>
              <Button
                size="sm"
                primary
                :accent="accent"
                @click="addTaskToSprint(task)"
              >
                Add
              </Button>
            </div>
          </div>
        </SectionCard>

        <div v-if="tasksLoading" class="loading-state">Loading tasks…</div>
        <template v-else>
          <SectionCard :title="`Committed (${committedTasks.length})`" glass>
            <div class="card-body">
              <div v-if="!committedTasks.length" class="empty-msg">No committed tasks.</div>
              <div
                v-for="(task, idx) in committedTasks"
                :key="task.id"
                class="task-row"
                :class="{ 'task-row-selected': selectedTaskIdx === idx }"
                @click="router.push(`/workops/tasks/${task.key}`)"
              >
                <Badge
                  :color="STATUS_COLORS[task.status.category] ?? '#6c7388'"
                  :solid="task.status.category === 'DONE'"
                  class="task-status"
                >
                  {{ task.status.name }}
                </Badge>
                <span class="task-key">{{ task.key }}</span>
                <span class="task-summary">{{ task.summary }}</span>
                <span class="task-priority" :style="{ color: priorityColor(task.priority.displayOrder) }">
                  {{ task.priority.name }}
                </span>
                <Avatar v-if="task.assignee" :name="task.assignee.name" :size="22" />
                <span v-else class="unassigned">—</span>
              </div>
            </div>
          </SectionCard>

          <SectionCard v-if="addedTasks.length" :title="`Added During Sprint (${addedTasks.length})`" glass>
            <div class="card-body">
              <p class="scope-hint">These tasks were added after the sprint started.</p>
              <div
                v-for="(task, idx) in addedTasks"
                :key="task.id"
                class="task-row"
                :class="{ 'task-row-selected': selectedTaskIdx === committedTasks.length + idx }"
                @click="router.push(`/workops/tasks/${task.key}`)"
              >
                <Badge
                  :color="STATUS_COLORS[task.status.category] ?? '#6c7388'"
                  :solid="task.status.category === 'DONE'"
                  class="task-status"
                >
                  {{ task.status.name }}
                </Badge>
                <span class="task-key">{{ task.key }}</span>
                <span class="task-summary">{{ task.summary }}</span>
                <span class="task-priority" :style="{ color: priorityColor(task.priority.displayOrder) }">
                  {{ task.priority.name }}
                </span>
                <Avatar v-if="task.assignee" :name="task.assignee.name" :size="22" />
                <span v-else class="unassigned">—</span>
              </div>
            </div>
          </SectionCard>
        </template>
      </div>

      <!-- Progress Tab -->
      <div v-if="activeTab === 'Progress'" class="tab-content">
        <SectionCard title="Sprint Progress" glass>
          <div class="card-body">
            <div class="progress-stats">
              <div class="stat-card">
                <span class="stat-value">{{ allTasks.length }}</span>
                <span class="stat-label">Total Tasks</span>
              </div>
              <div class="stat-card">
                <span class="stat-value" style="color: #34d99a">{{ doneTasks.length }}</span>
                <span class="stat-label">Done</span>
              </div>
              <div class="stat-card">
                <span class="stat-value">{{ allTasks.length - doneTasks.length }}</span>
                <span class="stat-label">Remaining</span>
              </div>
              <div class="stat-card">
                <span class="stat-value" :style="{ color: scopeCreepCount ? 'var(--warn)' : 'var(--fg-3)' }">
                  {{ scopeCreepCount }}
                </span>
                <span class="stat-label">Scope Creep</span>
              </div>
            </div>

            <div class="progress-bar-container">
              <div class="progress-bar-track">
                <div
                  class="progress-bar-fill"
                  :style="{ width: `${completionPercent}%` }"
                />
              </div>
              <span class="progress-label">{{ completionPercent }}% complete</span>
            </div>

            <div v-if="sprint.state === 'CLOSED' && sprint.velocityPoints != null" class="velocity-note">
              <Icon name="flag" :size="14" color="#34d99a" />
              <span>Velocity: <strong>{{ sprint.velocityPoints }} points</strong></span>
            </div>
          </div>
        </SectionCard>
      </div>
    </template>

    <!-- Start Sprint Modal -->
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
            <span class="commit-label">Commit Tasks ({{ selectedTaskIds.size }} selected)</span>
          </div>
          <div v-if="backlogLoading" class="commit-loading">Loading tasks…</div>
          <div v-else-if="!backlogTasks.length" class="commit-empty">No available tasks to commit.</div>
          <div v-else class="commit-list">
            <label
              v-for="t in backlogTasks"
              :key="t.id"
              class="commit-task-row"
              :class="{ selected: selectedTaskIds.has(t.id) }"
            >
              <input
                type="checkbox"
                :checked="selectedTaskIds.has(t.id)"
                @change="toggleTask(t.id)"
              >
              <span class="commit-task-key">{{ t.key }}</span>
              <span class="commit-task-summary">{{ t.summary }}</span>
              <span class="commit-task-priority" :style="{ color: priorityColor(t.priority.displayOrder) }">
                {{ t.priority.name }}
              </span>
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

    <!-- Close Sprint Modal -->
    <SprintCloseModal
      v-if="showClose && sprint"
      :sprint-id="sprint.id"
      :sprint-name="sprint.name"
      :sprint-version="sprint.version"
      :committed-task-ids="sprint.committedTaskIds"
      :added-during-sprint-task-ids="sprint.addedDuringSprintTaskIds"
      :future-sprints="futureSprints"
      :accent="accent"
      @close="showClose = false"
      @completed="onCloseCompleted"
    />
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

.days-badge {
  font-size: 12px;
  font-weight: 500;
  margin-left: 4px;
}

/* Tabs */
.tab-bar {
  display: flex;
  gap: 0;
  border-bottom: 1px solid var(--border-1);
  margin-bottom: 20px;
}

.tab-btn {
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  color: var(--fg-3);
  font-size: 13px;
  font-weight: 500;
  padding: 8px 16px;
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}

.tab-btn:hover {
  color: var(--fg-1);
}

.tab-btn.active {
  color: var(--fg-0);
}

.tab-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-body {
  padding: 12px 16px;
}

.add-task-bar {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}

/* Overview */
.details-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.detail-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--fg-3);
}

.detail-value {
  font-size: 14px;
  color: var(--fg-1);
}

.detail-link {
  font-size: 14px;
  color: var(--fg-1);
  text-decoration: none;
}

.detail-link:hover {
  text-decoration: underline;
}

/* Tasks */
.task-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border-1);
  cursor: pointer;
  border-radius: 4px;
  transition: background 0.15s;
}

.task-row:hover,
.task-row-selected {
  background: var(--bg-2);
}

.task-row:last-child {
  border-bottom: none;
}

.task-status {
  flex-shrink: 0;
}

.task-key {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
  font-family: var(--font-mono);
  flex-shrink: 0;
}

.task-summary {
  flex: 1;
  font-size: 13px;
  color: var(--fg-0);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.task-priority {
  font-size: 11px;
  font-weight: 500;
  flex-shrink: 0;
}

.unassigned {
  color: var(--fg-3);
  font-size: 12px;
}

.task-row-addable {
  cursor: default;
}

.scope-hint {
  font-size: 12px;
  color: var(--warn);
  margin: 0 0 8px;
}

.empty-msg {
  color: var(--fg-3);
  font-size: 13px;
  padding: 16px 0;
  text-align: center;
}

/* Progress */
.progress-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}

.stat-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 16px;
  background: var(--bg-2);
  border-radius: 8px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--fg-0);
}

.stat-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--fg-3);
}

.progress-bar-container {
  display: flex;
  align-items: center;
  gap: 12px;
}

.progress-bar-track {
  flex: 1;
  height: 8px;
  background: var(--bg-3);
  border-radius: 4px;
  overflow: hidden;
}

.progress-bar-fill {
  height: 100%;
  background: #34d99a;
  border-radius: 4px;
  transition: width 0.4s ease;
}

.progress-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-2);
  flex-shrink: 0;
}

.velocity-note {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding: 10px 14px;
  background: var(--bg-2);
  border-radius: 6px;
  font-size: 13px;
  color: var(--fg-2);
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

/* Commit section in start modal */
.commit-section {
  border-top: 1px solid var(--border-1);
  padding-top: 12px;
}

.commit-header {
  margin-bottom: 8px;
}

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
  max-height: 260px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.commit-task-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  transition: background 0.1s;
}

.commit-task-row:hover {
  background: var(--bg-2);
}

.commit-task-row.selected {
  background: color-mix(in oklch, var(--bg-2) 80%, #a78bff 20%);
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

.commit-task-priority {
  font-size: 11px;
  font-weight: 500;
  flex-shrink: 0;
}
</style>
