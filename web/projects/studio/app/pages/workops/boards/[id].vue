<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation, query } = useGraphQL()

const boardId = computed(() => route.params.id as string)
const TAB_FROM_QUERY: Record<string, string> = {
  board: 'Board',
  sprints: 'Sprints',
  columns: 'Columns',
  settings: 'Settings',
}
const activeTab = ref(TAB_FROM_QUERY[route.query.tab as string] ?? 'Board')
const saving = ref(false)
const toast = useToast()

const STATUS_COLORS: Record<string, string> = {
  TODO: '#6c7388',
  IN_PROGRESS: '#a78bff',
  IN_REVIEW: '#ffb547',
  DONE: '#34d99a',
  CANCELLED: '#6c7388',
}

const boardGql = gql`
  query GetBoard($id: UUID!) {
    workOps {
      boards {
        board(id: $id) {
          id
          name
          type
          swimlaneStrategy
          projectId
          project { id key name }
          projects { id key name }
          columns {
            id
            name
            displayOrder
            wipLimit
            statusIds
            statuses { id name category }
          }
          version
        }
      }
    }
  }
`

interface BoardColumn {
  id: string
  name: string
  displayOrder: number
  wipLimit: number | null
  statusIds: string[]
  statuses: Array<{ id: string; name: string; category: string }>
}

interface ProjectRef { id: string; key: string; name: string }

interface Board {
  id: string
  name: string
  type: string
  swimlaneStrategy: string
  projectId: string | null
  project: ProjectRef | null
  projects: ProjectRef[]
  columns: BoardColumn[]
  version: number
}

const { data: boardData, refresh: refreshBoard } = useAsyncQuery<{
  workOps: { boards: { board: Board | null } }
}>('workops-board-detail', boardGql, { id: boardId }, { server: false })

const board = computed(() => boardData.value?.workOps?.boards?.board ?? null)
const sortedColumns = computed(() =>
  [...(board.value?.columns ?? [])].sort((a, b) => a.displayOrder - b.displayOrder),
)

const boardProjectKeys = computed(() => {
  if (!board.value) return []
  if (board.value.projects.length) return board.value.projects.map(p => p.key)
  if (board.value.project) return [board.value.project.key]
  return []
})

const boardSubtitle = computed(() => {
  if (!board.value) return ''
  const projectLabel = board.value.projects.length > 1
    ? board.value.projects.map(p => p.key).join(', ')
    : board.value.project?.key ?? ''
  return `${board.value.type} · ${projectLabel}`
})

// ─── Sprints ─────────────────────────────────────────────────────────────────
interface Sprint {
  id: string
  name: string
  state: string
  goal: string | null
  startDate: string | null
  endDate: string | null
  completeDate: string | null
  committedTaskIds: string[]
  addedDuringSprintTaskIds: string[]
  velocityPoints: number | null
  version: number
}

const sprints = ref<Sprint[]>([])
const showCreateSprint = ref(false)
const showStartSprint = ref(false)
const showCloseSprint = ref(false)
const sprintForm = reactive({ name: '', goal: '' })
const startForm = reactive({ sprintId: '', startDate: '', endDate: '' })
const closeForm = reactive({ sprintId: '', expectedVersion: 0 })

async function loadSprints() {
  if (!boardId.value) return
  try {
    const result = await query<{
      workOps: { sprints: { byBoard: Sprint[] } }
    }>(gql`
      query GetSprints($boardId: UUID!) {
        workOps { sprints { byBoard(boardId: $boardId) {
          id name state goal startDate endDate completeDate committedTaskIds addedDuringSprintTaskIds velocityPoints version
        } } }
      }
    `, { boardId: boardId.value })
    sprints.value = result.workOps?.sprints?.byBoard ?? []
  } catch { sprints.value = [] }
}

const activeSprint = computed(() => sprints.value.find(s => s.state === 'ACTIVE'))
const futureSprints = computed(() => sprints.value.filter(s => s.state === 'FUTURE'))
const closedSprints = computed(() => sprints.value.filter(s => s.state === 'CLOSED'))

async function createSprint() {
  if (!sprintForm.name.trim()) return
  saving.value = true
  try {
    await mutation(gql`
      mutation CreateSprint($input: CreateWorkOpsSprintInput!) {
        workOps { sprints { create(input: $input) { id } } }
      }
    `, { input: { boardId: boardId.value, name: sprintForm.name.trim(), goal: sprintForm.goal.trim() || null } })
    showCreateSprint.value = false
    sprintForm.name = ''
    sprintForm.goal = ''
    toast.success('Sprint created')
    await loadSprints()
  } catch { toast.error('Failed to create sprint') }
  finally { saving.value = false }
}

async function startSprint() {
  if (!startForm.sprintId) return
  const sprint = sprints.value.find(s => s.id === startForm.sprintId)
  if (!sprint) return
  saving.value = true
  try {
    await mutation(gql`
      mutation StartSprint($input: StartWorkOpsSprintInput!) {
        workOps { sprints { start(input: $input) { id } } }
      }
    `, {
      input: {
        sprintId: startForm.sprintId,
        expectedVersion: sprint.version,
        startDate: startForm.startDate ? new Date(startForm.startDate).toISOString() : null,
        endDate: startForm.endDate ? new Date(startForm.endDate).toISOString() : null,
        committedTaskIds: selectedCommitIds.value.size ? [...selectedCommitIds.value] : undefined,
      },
    })
    showStartSprint.value = false
    toast.success('Sprint started')
    await loadSprints()
  } catch { toast.error('Failed to start sprint') }
  finally { saving.value = false }
}

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
      query BacklogForStart($source: String!, $limit: Int!) {
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

async function openStartSprint(s: Sprint) {
  startForm.sprintId = s.id
  startForm.startDate = new Date().toISOString().slice(0, 10)
  startForm.endDate = new Date(Date.now() + 14 * 86_400_000).toISOString().slice(0, 10)
  selectedCommitIds.value = new Set()
  showStartSprint.value = true
  await loadBacklog()
}

function openCloseSprint(s: Sprint) {
  closeForm.sprintId = s.id
  closeForm.expectedVersion = s.version
  showCloseSprint.value = true
}

async function onCloseCompleted() {
  showCloseSprint.value = false
  await loadSprints()
}

// ─── Column Management ──────────────────────────────────────────────────────
interface StatusOption {
  id: string
  name: string
  category: string
}

const allStatuses = ref<StatusOption[]>([])
const showAddColumn = ref(false)
const editingColumn = ref<BoardColumn | null>(null)
const columnForm = reactive({
  name: '',
  statusIds: [] as string[],
  wipLimit: null as number | null,
})

const statusesGql = gql`
  query GetStatuses {
    workOps { statuses { all { id name category } } }
  }
`

async function loadStatuses() {
  if (allStatuses.value.length) return
  try {
    const result = await query<{
      workOps: { statuses: { all: StatusOption[] } }
    }>(statusesGql)
    allStatuses.value = result.workOps?.statuses?.all ?? []
  } catch { /* ignore */ }
}

const availableStatuses = computed(() => {
  const usedIds = new Set(
    (board.value?.columns ?? [])
      .filter(c => c.id !== editingColumn.value?.id)
      .flatMap(c => c.statusIds),
  )
  return allStatuses.value.filter(s => !usedIds.has(s.id))
})

function openAddColumn() {
  editingColumn.value = null
  columnForm.name = ''
  columnForm.statusIds = []
  columnForm.wipLimit = null
  loadStatuses()
  showAddColumn.value = true
}

function openEditColumn(col: BoardColumn) {
  editingColumn.value = col
  columnForm.name = col.name
  columnForm.statusIds = [...col.statusIds]
  columnForm.wipLimit = col.wipLimit
  loadStatuses()
  showAddColumn.value = true
}

function toggleStatus(id: string) {
  const idx = columnForm.statusIds.indexOf(id)
  if (idx >= 0) columnForm.statusIds.splice(idx, 1)
  else columnForm.statusIds.push(id)
}

async function saveColumn() {
  if (!columnForm.name.trim() || !columnForm.statusIds.length) return
  saving.value = true
  try {
    if (editingColumn.value) {
      await mutation(gql`
        mutation UpdateColumn($input: UpdateWorkOpsBoardColumnInput!) {
          workOps { boards { updateColumn(input: $input) { id } } }
        }
      `, {
        input: {
          id: editingColumn.value.id,
          name: columnForm.name.trim(),
          displayOrder: editingColumn.value.displayOrder,
          statusIds: columnForm.statusIds,
          wipLimit: columnForm.wipLimit || null,
        },
      })
      toast.success('Column updated')
    } else {
      await mutation(gql`
        mutation AddColumn($input: CreateWorkOpsBoardColumnInput!) {
          workOps { boards { addColumn(input: $input) { id } } }
        }
      `, {
        input: {
          boardId: boardId.value,
          name: columnForm.name.trim(),
          displayOrder: (board.value?.columns.length ?? 0) + 1,
          statusIds: columnForm.statusIds,
          wipLimit: columnForm.wipLimit || null,
        },
      })
      toast.success('Column added')
    }
    showAddColumn.value = false
    editingColumn.value = null
    await refreshBoard()
  } catch { toast.error('Failed to save column') }
  finally { saving.value = false }
}

// Reorder = swap displayOrder with the neighbor. updateColumn requires the
// full column payload, so both rows are rewritten with their other fields
// unchanged. (board_id, display_order) is unique, so the swap has to route
// through a temporary order that no column holds.
async function moveColumn(index: number, direction: -1 | 1) {
  const cols = sortedColumns.value
  const col = cols[index]
  const target = cols[index + direction]
  if (!col || !target || saving.value) return
  saving.value = true
  try {
    const setOrder = (c: BoardColumn, displayOrder: number) => mutation(gql`
      mutation UpdateColumn($input: UpdateWorkOpsBoardColumnInput!) {
        workOps { boards { updateColumn(input: $input) { id } } }
      }
    `, {
      input: { id: c.id, name: c.name, displayOrder, statusIds: c.statusIds, wipLimit: c.wipLimit },
    })
    const tempOrder = Math.max(...cols.map(c => c.displayOrder)) + 1
    await setOrder(col, tempOrder)
    await setOrder(target, col.displayOrder)
    await setOrder(col, target.displayOrder)
    await refreshBoard()
  } catch {
    toast.error('Failed to reorder columns')
    await refreshBoard()
  }
  finally { saving.value = false }
}

const deletingColumnId = ref<string | null>(null)

async function deleteColumn(colId: string) {
  saving.value = true
  deletingColumnId.value = null
  try {
    await mutation(gql`
      mutation DeleteColumn($columnId: UUID!) {
        workOps { boards { deleteColumn(columnId: $columnId) } }
      }
    `, { columnId: colId })
    toast.success('Column deleted')
    await refreshBoard()
  } catch { toast.error('Failed to delete column') }
  finally { saving.value = false }
}

// ─── Settings ────────────────────────────────────────────────────────────────
const typeOptions = [
  { value: 'KANBAN', label: 'Kanban' },
  { value: 'SCRUM', label: 'Scrum' },
]

const settingsForm = reactive({
  name: '',
  type: 'KANBAN',
  swimlaneStrategy: 'NONE',
  projectIds: [] as string[],
})

const boardProjects = computed(() => board.value?.projects ?? [])
// Multi-project boards carry their project set in `projects` (the join
// table); single-project boards only have `projectId`, which `update`
// cannot change — so the project picker is multi-board only.
const isMultiProject = computed(() => boardProjects.value.length > 0)

const settingsSwimlaneOptions = computed(() => {
  const base = [
    { value: 'NONE', label: 'None' },
    { value: 'ASSIGNEE', label: 'Assignee' },
    { value: 'EPIC', label: 'Epic' },
    { value: 'PRIORITY', label: 'Priority' },
  ]
  if (isMultiProject.value) base.push({ value: 'PROJECT', label: 'Project' })
  // A board may already use a strategy outside the offered set (e.g. QUERY,
  // or PROJECT on a board that dropped to one project) — keep it selectable
  // so the Select doesn't silently show the wrong value.
  const current = settingsForm.swimlaneStrategy
  if (current && !base.some(o => o.value === current)) {
    base.push({ value: current, label: current.charAt(0) + current.slice(1).toLowerCase() })
  }
  return base
})

function resetSettingsForm() {
  if (!board.value) return
  settingsForm.name = board.value.name
  settingsForm.type = board.value.type
  settingsForm.swimlaneStrategy = board.value.swimlaneStrategy
  settingsForm.projectIds = boardProjects.value.map(p => p.id)
}

watch(board, resetSettingsForm, { immediate: true })

interface ProjectOption { id: string; key: string; name: string; archivedAt: string | null }
const allProjects = ref<ProjectOption[]>([])

async function loadProjects() {
  if (allProjects.value.length) return
  try {
    const result = await query<{
      workOps: { projects: { all: ProjectOption[] } }
    }>(gql`
      query { workOps { projects { all { id key name archivedAt } } } }
    `)
    allProjects.value = (result.workOps?.projects?.all ?? []).filter(p => !p.archivedAt)
  } catch { toast.error('Failed to load projects') }
}

function toggleSettingsProject(id: string) {
  const idx = settingsForm.projectIds.indexOf(id)
  if (idx >= 0) settingsForm.projectIds.splice(idx, 1)
  else settingsForm.projectIds.push(id)
}

const settingsDirty = computed(() => {
  if (!board.value) return false
  const currentIds = new Set(boardProjects.value.map(p => p.id))
  const formIds = new Set(settingsForm.projectIds)
  const projectsChanged = currentIds.size !== formIds.size
    || [...formIds].some(id => !currentIds.has(id))
  return settingsForm.name.trim() !== board.value.name
    || settingsForm.type !== board.value.type
    || settingsForm.swimlaneStrategy !== board.value.swimlaneStrategy
    || projectsChanged
})

const settingsValid = computed(() =>
  !!settingsForm.name.trim() && (!isMultiProject.value || settingsForm.projectIds.length >= 2),
)

const savingSettings = ref(false)

async function saveSettings() {
  if (!board.value || !settingsDirty.value || !settingsValid.value) return
  const currentIds = new Set(boardProjects.value.map(p => p.id))
  const formIds = new Set(settingsForm.projectIds)
  const addProjectIds = [...formIds].filter(id => !currentIds.has(id))
  const removeProjectIds = [...currentIds].filter(id => !formIds.has(id))
  savingSettings.value = true
  try {
    const input: Record<string, unknown> = {
      name: settingsForm.name.trim(),
      type: settingsForm.type,
      swimlaneStrategy: settingsForm.swimlaneStrategy,
      expectedVersion: board.value.version,
    }
    if (addProjectIds.length) input.addProjectIds = addProjectIds
    if (removeProjectIds.length) input.removeProjectIds = removeProjectIds
    await mutation(gql`
      mutation UpdateBoard($id: UUID!, $input: UpdateWorkOpsBoardInput!) {
        workOps { boards { update(id: $id, input: $input) { id } } }
      }
    `, { id: board.value.id, input })
    toast.success('Board updated')
    await refreshBoard()
  } catch { toast.error('Failed to update board') }
  finally { savingSettings.value = false }
}

watch(activeTab, (tab) => {
  if (tab === 'Sprints' && sprints.value.length === 0) loadSprints()
  if (tab === 'Columns') loadStatuses()
  if (tab === 'Settings' && isMultiProject.value) loadProjects()
})
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Boards', board?.name || '…')"
        :title="board?.name || 'Loading…'"
        :subtitle="boardSubtitle"
        :tabs="['Board', 'Sprints', 'Columns', 'Settings']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      />
    </template>

    <!-- Board -->
    <BoardView
      v-if="activeTab === 'Board' && board && boardProjectKeys.length"
      :board="board"
      :project-keys="boardProjectKeys"
      :project-id="board.projectId || board.projects[0]?.id || ''"
      :accent="accent"
      quick-add
      @card-click="router.push(`/workops/tasks/${$event}`)"
    />

    <!-- Columns Tab -->
    <div v-if="activeTab === 'Columns'">
      <div class="columns-toolbar">
        <Button
          primary
          icon="plus"
          size="sm"
          :accent="accent"
          @click="openAddColumn">
          Add Column
        </Button>
      </div>

      <SectionCard v-if="sortedColumns.length" title="Columns" glass>
        <div v-for="(col, i) in sortedColumns" :key="col.id" class="column-row">
          <span class="column-order mono">{{ col.displayOrder }}</span>
          <span class="column-name-cell">{{ col.name }}</span>
          <span class="column-statuses">
            <Badge v-for="s in col.statuses" :key="s.id" :color="STATUS_COLORS[s.category] || '#6c7388'">
              {{ s.name }}
            </Badge>
          </span>
          <span v-if="col.wipLimit" class="mono column-wip">WIP: {{ col.wipLimit }}</span>
          <span class="column-actions">
            <Button
              size="sm"
              icon="chevron-up"
              :disabled="i === 0 || saving"
              @click="moveColumn(i, -1)" />
            <Button
              size="sm"
              icon="chevron-down"
              :disabled="i === sortedColumns.length - 1 || saving"
              @click="moveColumn(i, 1)" />
            <Button size="sm" icon="pencil" @click="openEditColumn(col)" />
            <Button size="sm" icon="trash" @click="deletingColumnId = col.id" />
          </span>
        </div>
      </SectionCard>

      <div v-else class="empty-msg">
        No columns yet. Add columns and map them to workflow statuses.
      </div>

      <!-- Add / Edit Column Modal -->
      <Modal
        v-if="showAddColumn"
        :title="editingColumn ? 'Edit Column' : 'Add Column'"
        icon="columns"
        :accent="accent"
        @close="showAddColumn = false"
      >
        <div class="form-stack">
          <TextInput
            v-model="columnForm.name"
            label="Name"
            placeholder="e.g. To Do, In Progress, Done"
            autofocus />
          <NumberInput
            v-model="columnForm.wipLimit"
            label="WIP Limit (optional)"
            :min="0"
            placeholder="No limit" />
          <div class="status-picker">
            <label class="status-picker-label">Statuses</label>
            <p class="status-picker-hint">Select which workflow statuses map to this column.</p>
            <div class="status-chips">
              <button
                v-for="s in availableStatuses"
                :key="s.id"
                :class="['status-chip', { selected: columnForm.statusIds.includes(s.id) }]"
                @click="toggleStatus(s.id)"
              >
                <span class="status-dot" :style="{ background: STATUS_COLORS[s.category] || '#6c7388' }" />
                {{ s.name }}
              </button>
              <Badge
                v-for="s in (editingColumn?.statuses ?? []).filter(st => !availableStatuses.find(a => a.id === st.id))"
                :key="s.id"
                :color="STATUS_COLORS[s.category] || '#6c7388'"
                :class="['status-chip', { selected: columnForm.statusIds.includes(s.id) }]"
                @click="toggleStatus(s.id)"
              >
                {{ s.name }}
              </Badge>
            </div>
            <p v-if="!availableStatuses.length && !editingColumn" class="status-picker-hint">
              All statuses are already assigned to other columns.
            </p>
          </div>
        </div>
        <template #footer>
          <span class="spacer" />
          <Button size="sm" @click="showAddColumn = false">Cancel</Button>
          <Button
            size="sm"
            primary
            :accent="accent"
            :disabled="!columnForm.name.trim() || !columnForm.statusIds.length || saving"
            @click="saveColumn"
          >
            {{ saving ? 'Saving…' : (editingColumn ? 'Update' : 'Add Column') }}
          </Button>
        </template>
      </Modal>

      <!-- Delete Column Confirm -->
      <ConfirmModal
        v-if="deletingColumnId"
        title="Delete this column?"
        @close="deletingColumnId = null"
        @confirm="deleteColumn(deletingColumnId!)"
      />
    </div>

    <!-- Sprints Tab -->
    <div v-if="activeTab === 'Sprints'">
      <div class="sprint-actions-bar">
        <Button
          primary
          icon="plus"
          size="sm"
          :accent="accent"
          @click="showCreateSprint = true">New Sprint</Button>
      </div>

      <SectionCard v-if="activeSprint" title="Active Sprint" glass>
        <div class="sprint-card active-sprint">
          <div class="sprint-header">
            <NuxtLink :to="`/workops/sprints/${activeSprint.id}`" class="sprint-name sprint-link">{{ activeSprint.name }}</NuxtLink>
            <Badge color="#4ade80">ACTIVE</Badge>
            <Badge v-if="activeSprint.addedDuringSprintTaskIds.length" color="var(--warn)">
              +{{ activeSprint.addedDuringSprintTaskIds.length }} added
            </Badge>
          </div>
          <p v-if="activeSprint.goal" class="sprint-goal">{{ activeSprint.goal }}</p>
          <div class="sprint-meta">
            <span v-if="activeSprint.startDate">Started: {{ new Date(activeSprint.startDate).toLocaleDateString() }}</span>
            <span v-if="activeSprint.endDate">Ends: {{ new Date(activeSprint.endDate).toLocaleDateString() }}</span>
            <span>{{ activeSprint.committedTaskIds.length }} tasks committed</span>
          </div>
          <div class="sprint-row-actions">
            <Button size="sm" :accent="accent" @click="openCloseSprint(activeSprint)">Complete Sprint</Button>
          </div>
        </div>
      </SectionCard>

      <SectionCard v-if="futureSprints.length" title="Planned Sprints" glass>
        <div v-for="s in futureSprints" :key="s.id" class="sprint-card">
          <div class="sprint-header">
            <NuxtLink :to="`/workops/sprints/${s.id}`" class="sprint-name sprint-link">{{ s.name }}</NuxtLink>
            <Badge color="#6c7388">FUTURE</Badge>
          </div>
          <p v-if="s.goal" class="sprint-goal">{{ s.goal }}</p>
          <div class="sprint-row-actions">
            <Button
              size="sm"
              primary
              :accent="accent"
              :disabled="!!activeSprint"
              @click="openStartSprint(s)">Start Sprint</Button>
          </div>
        </div>
      </SectionCard>

      <SectionCard v-if="closedSprints.length" title="Closed Sprints" glass>
        <div v-for="s in closedSprints" :key="s.id" class="sprint-card closed">
          <div class="sprint-header">
            <NuxtLink :to="`/workops/sprints/${s.id}`" class="sprint-name sprint-link">{{ s.name }}</NuxtLink>
            <Badge color="#6c7388">CLOSED</Badge>
          </div>
          <div class="sprint-meta">
            <span v-if="s.completeDate">Completed: {{ new Date(s.completeDate).toLocaleDateString() }}</span>
            <span>{{ s.committedTaskIds.length }} tasks</span>
            <span v-if="s.velocityPoints != null">Velocity: {{ s.velocityPoints }} pts</span>
          </div>
        </div>
      </SectionCard>

      <div v-if="!activeSprint && !futureSprints.length && !closedSprints.length" class="empty-msg">
        No sprints yet. Create one to start planning iterations.
      </div>

      <!-- Create Sprint Modal -->
      <Modal
        v-if="showCreateSprint"
        title="New Sprint"
        icon="plus"
        :accent="accent"
        @close="showCreateSprint = false">
        <div class="form-stack">
          <TextInput
            v-model="sprintForm.name"
            label="Name"
            placeholder="e.g. Sprint 12"
            autofocus />
          <Textarea
            v-model="sprintForm.goal"
            label="Goal"
            :rows="2"
            placeholder="What should be achieved this sprint?" />
        </div>
        <template #footer>
          <span class="spacer" />
          <Button size="sm" @click="showCreateSprint = false">Cancel</Button>
          <Button
            size="sm"
            primary
            :accent="accent"
            :disabled="!sprintForm.name.trim() || saving"
            @click="createSprint">Create</Button>
        </template>
      </Modal>

      <!-- Start Sprint Modal -->
      <Modal
        v-if="showStartSprint"
        title="Start Sprint"
        icon="arrowRight"
        :accent="accent"
        @close="showStartSprint = false">
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
        </div>
        <template #footer>
          <span class="spacer" />
          <Button size="sm" @click="showStartSprint = false">Cancel</Button>
          <Button
            size="sm"
            primary
            :accent="accent"
            :disabled="saving"
            @click="startSprint">Start</Button>
        </template>
      </Modal>

      <!-- Close Sprint Modal -->
      <SprintCloseModal
        v-if="showCloseSprint && activeSprint"
        :sprint-id="closeForm.sprintId"
        :sprint-name="activeSprint.name"
        :sprint-version="closeForm.expectedVersion"
        :committed-task-ids="activeSprint.committedTaskIds"
        :added-during-sprint-task-ids="activeSprint.addedDuringSprintTaskIds"
        :future-sprints="futureSprints.map(s => ({ id: s.id, name: s.name }))"
        :accent="accent"
        @close="showCloseSprint = false"
        @completed="onCloseCompleted"
      />
    </div>

    <!-- Settings Tab -->
    <div v-if="activeTab === 'Settings' && board">
      <SectionCard title="Board Settings" glass padded>
        <div class="settings-form">
          <TextInput
            v-model="settingsForm.name"
            label="Name"
            placeholder="Board name" />
          <Select
            v-model="settingsForm.type"
            :options="typeOptions"
            label="Type"
            :accent="accent" />
          <Select
            v-model="settingsForm.swimlaneStrategy"
            :options="settingsSwimlaneOptions"
            label="Swimlane Strategy"
            :accent="accent" />

          <div v-if="isMultiProject" class="project-picker">
            <label class="project-picker-label">Projects</label>
            <p class="project-picker-hint">Select which projects this board spans (at least two).</p>
            <div class="project-chips">
              <button
                v-for="p in (allProjects.length ? allProjects : boardProjects)"
                :key="p.id"
                :class="['project-chip', { selected: settingsForm.projectIds.includes(p.id) }]"
                @click="toggleSettingsProject(p.id)"
              >
                <span class="project-chip-key">{{ p.key }}</span>
                {{ p.name }}
              </button>
            </div>
            <p v-if="settingsForm.projectIds.length < 2" class="form-error">
              A multi-project board needs at least two projects.
            </p>
          </div>
          <div v-else-if="board.project" class="setting-row">
            <span class="setting-label">Project</span>
            <NuxtLink :to="`/workops/projects/${board.project.id}`" class="setting-link">
              {{ board.project.key }} — {{ board.project.name }}
            </NuxtLink>
          </div>

          <div class="settings-actions">
            <Button
              size="sm"
              :disabled="!settingsDirty || savingSettings"
              @click="resetSettingsForm">Reset</Button>
            <Button
              size="sm"
              primary
              :accent="accent"
              :disabled="!settingsDirty || !settingsValid || savingSettings"
              @click="saveSettings">
              {{ savingSettings ? 'Saving…' : 'Save Changes' }}
            </Button>
          </div>
        </div>
      </SectionCard>
    </div>
  </PageShell>
</template>

<style scoped>
/* Columns Tab */
.column-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line);
}

.column-row:last-child { border-bottom: none; }

.column-order {
  font-size: 11px;
  color: var(--fg-3);
  width: 20px;
}

.column-name-cell {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
  min-width: 120px;
}

.column-statuses {
  display: flex;
  gap: 4px;
  flex: 1;
}

.column-wip {
  font-size: 11px;
  color: var(--fg-3);
}

.column-actions {
  display: flex;
  gap: 4px;
  margin-left: auto;
  opacity: 0;
  transition: opacity 0.15s;
}

.column-row:hover .column-actions {
  opacity: 1;
}

.columns-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 16px;
}

/* Status Picker */
.status-picker {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.status-picker-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-1);
}

.status-picker-hint {
  font-size: 11.5px;
  color: var(--fg-3);
  margin: 0;
}

.status-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--fg-2);
  background: var(--bg-2);
  border: 1px solid var(--line);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
}

.status-chip:hover {
  border-color: var(--fg-3);
}

.status-chip.selected {
  border-color: v-bind(accent);
  background: color-mix(in oklch, v-bind(accent) 12%, var(--bg-2));
  color: var(--fg-0);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

/* Settings Tab */
.settings-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 480px;
}

.settings-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 4px;
}

.setting-row {
  display: flex;
  align-items: center;
  gap: 16px;
}

.setting-label {
  font-size: 12px;
  color: var(--fg-3);
  min-width: 140px;
  font-weight: 600;
}

.setting-link {
  font-size: 13px;
  color: v-bind(accent);
  text-decoration: none;
}

.setting-link:hover { text-decoration: underline; }

.form-error {
  color: var(--err);
  font-size: 12px;
  margin: 0;
}

.project-picker {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.project-picker-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-1);
}

.project-picker-hint {
  font-size: 11.5px;
  color: var(--fg-3);
  margin: 0;
}

.project-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.project-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--fg-2);
  background: var(--bg-2);
  border: 1px solid var(--line);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
}

.project-chip:hover {
  border-color: var(--fg-3);
}

.project-chip.selected {
  border-color: v-bind(accent);
  background: color-mix(in oklch, v-bind(accent) 12%, var(--bg-2));
  color: var(--fg-0);
}

.project-chip-key {
  font-family: var(--font-mono);
  font-size: 10.5px;
  font-weight: 600;
  color: var(--fg-3);
}

.project-chip.selected .project-chip-key {
  color: v-bind(accent);
}

/* Sprints Tab */
.sprint-actions-bar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 16px;
}

.sprint-card {
  padding: 14px;
  border-bottom: 1px solid var(--line);
}

.sprint-card:last-child { border-bottom: none; }

.sprint-card.closed { opacity: 0.7; }

.sprint-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.sprint-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.sprint-link {
  text-decoration: none;
}

.sprint-link:hover {
  text-decoration: underline;
}

.sprint-goal {
  font-size: 13px;
  color: var(--fg-2);
  margin: 4px 0 8px;
}

.sprint-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--fg-3);
}

.sprint-row-actions {
  margin-top: 10px;
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.spacer { flex: 1; }

.empty-msg {
  color: var(--fg-3);
  text-align: center;
  padding: 40px 0;
  font-size: 13px;
}

/* Commit task picker in start modal */
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
