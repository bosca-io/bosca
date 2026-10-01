<script setup lang="ts">
import gql from 'graphql-tag'
import type { BoardViewBoard } from '~/components/workops/BoardView.vue'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query, mutation } = useGraphQL()
const router = useRouter()

const selectedProjectId = ref('')
const selectedBoardId = ref('')
const showCreate = ref(false)
const saving = ref(false)
const error = ref('')

type BoardScope = 'single' | 'multi'

const form = reactive({
  name: '',
  type: 'KANBAN',
  swimlaneStrategy: 'NONE',
  scope: 'single' as BoardScope,
  selectedProjectIds: [] as string[],
})

const projectsGql = gql`
  query {
    workOps { projects { all { id key name archivedAt } } }
  }
`

const boardFieldsFragment = `
  id
  name
  type
  swimlaneStrategy
  projectId
  projects { id key name }
  columns {
    id name displayOrder wipLimit statusIds
    statuses { id name category }
  }
  version
`

const boardsByProjectGql = gql`
  query GetBoards($projectId: UUID!) {
    workOps {
      boards {
        byProject(projectId: $projectId) { ${boardFieldsFragment} }
      }
    }
  }
`

const multiProjectBoardsGql = gql`
  query GetMultiProjectBoards($projectIds: [UUID!]!) {
    workOps {
      boards {
        byProjects(projectIds: $projectIds) { ${boardFieldsFragment} }
      }
    }
  }
`

interface ProjectItem { id: string; key: string; name: string; archivedAt: string | null }

const { data: projectsData } = useAsyncQuery<{
  workOps: { projects: { all: ProjectItem[] } }
}>('workops-boards-projects', projectsGql)

const projects = computed(() =>
  (projectsData.value?.workOps?.projects?.all ?? []).filter(p => !p.archivedAt),
)

const projectOptions = computed(() =>
  projects.value.map(p => ({ value: p.id, label: `${p.key} — ${p.name}` })),
)

const selectedProject = computed(() => projects.value.find(p => p.id === selectedProjectId.value) ?? null)

interface BoardWithProjects extends BoardViewBoard {
  projectId: string | null
  projects: Array<{ id: string; key: string; name: string }>
}

const boards = ref<BoardWithProjects[]>([])
const boardsLoading = ref(false)

const boardOptions = computed(() => boards.value.map((b) => {
  const suffix = !b.projectId && b.projects.length > 1
    ? ` (${b.projects.map(p => p.key).join(', ')})`
    : ''
  return { value: b.id, label: `${b.name}${suffix}` }
}))
const selectedBoard = computed(() => boards.value.find(b => b.id === selectedBoardId.value) ?? null)

async function loadBoards() {
  if (!selectedProjectId.value) { boards.value = []; selectedBoardId.value = ''; return }
  boardsLoading.value = true
  try {
    const [projectResult, multiResult] = await Promise.all([
      query<{ workOps: { boards: { byProject: BoardWithProjects[] } } }>(
        boardsByProjectGql, { projectId: selectedProjectId.value },
      ),
      query<{ workOps: { boards: { byProjects: BoardWithProjects[] } } }>(
        multiProjectBoardsGql, { projectIds: [selectedProjectId.value] },
      ),
    ])
    const projectBoards = projectResult.workOps?.boards?.byProject ?? []
    const multiBoards = multiResult.workOps?.boards?.byProjects ?? []
    const seen = new Set(projectBoards.map(b => b.id))
    const combined = [...projectBoards, ...multiBoards.filter(b => !seen.has(b.id))]
    boards.value = combined
    if (combined.length && !combined.find(b => b.id === selectedBoardId.value) && combined[0]) {
      selectedBoardId.value = combined[0].id
    }
    if (!combined.length) selectedBoardId.value = ''
  } finally { boardsLoading.value = false }
}

// Registered BEFORE the auto-select watcher below: that watcher runs
// immediately, and when SSR has already hydrated `projects` it assigns
// `selectedProjectId` synchronously during setup — a loadBoards watcher
// registered after it would never see that first change and the page
// would sit on "No boards for this project" until a manual re-select.
watch(selectedProjectId, () => { loadBoards() })

watch(projects, (ps) => {
  if (ps.length && !selectedProjectId.value && ps[0]) {
    selectedProjectId.value = ps[0].id
  }
}, { immediate: true })

// ─── Board project keys (for BQL queries) ───────────────────────────────────

const selectedBoardProjectKeys = computed(() => {
  if (!selectedBoard.value) return []
  if (selectedBoard.value.projects.length) return selectedBoard.value.projects.map(p => p.key)
  if (selectedProject.value) return [selectedProject.value.key]
  return []
})

// ─── Sprint Filter ───────────────────────────────────────────────────────────

const selectedSprintFilter = ref('')

interface SprintOption {
  id: string
  name: string
  state: string
  endDate: string | null
  committedTaskIds: string[]
  addedDuringSprintTaskIds: string[]
}

const sprintOptions = ref<SprintOption[]>([])

const sprintFilterOptions = computed(() => [
  { value: '', label: 'All tasks' },
  ...sprintOptions.value
    .filter(s => s.state !== 'CLOSED')
    .map(s => ({ value: s.id, label: `${s.name} (${s.state})` })),
])

const sprintFilterIds = computed(() => {
  if (!selectedSprintFilter.value) return undefined
  const sprint = sprintOptions.value.find(s => s.id === selectedSprintFilter.value)
  if (!sprint) return undefined
  return [...new Set([...sprint.committedTaskIds, ...sprint.addedDuringSprintTaskIds])]
})

async function loadSprints() {
  if (!selectedBoardId.value) { sprintOptions.value = []; return }
  try {
    const result = await query<{
      workOps: { sprints: { byBoard: SprintOption[] } }
    }>(gql`
      query GetSprintsForBoardFilter($boardId: UUID!) {
        workOps { sprints { byBoard(boardId: $boardId) {
          id name state endDate committedTaskIds addedDuringSprintTaskIds
        } } }
      }
    `, { boardId: selectedBoardId.value })
    sprintOptions.value = result.workOps?.sprints?.byBoard ?? []
    const activeSprint = sprintOptions.value.find(s => s.state === 'ACTIVE')
    if (activeSprint && !selectedSprintFilter.value) {
      selectedSprintFilter.value = activeSprint.id
    }
  } catch { sprintOptions.value = [] }
}

const activeSprintInfo = computed(() => {
  if (!selectedSprintFilter.value) return null
  const s = sprintOptions.value.find(sp => sp.id === selectedSprintFilter.value)
  if (!s) return null
  return { name: s.name, endDate: s.endDate }
})

watch(selectedBoardId, () => { selectedSprintFilter.value = ''; loadSprints() })

// ─── Create board ────────────────────────────────────────────────────────────

const typeOptions = [
  { value: 'KANBAN', label: 'Kanban' },
  { value: 'SCRUM', label: 'Scrum' },
]

const swimlaneOptions = computed(() => {
  const base = [
    { value: 'NONE', label: 'None' },
    { value: 'ASSIGNEE', label: 'Assignee' },
    { value: 'EPIC', label: 'Epic' },
    { value: 'PRIORITY', label: 'Priority' },
  ]
  if (form.scope === 'multi') {
    base.push({ value: 'PROJECT', label: 'Project' })
  }
  return base
})

const scopeOptions = [
  { value: 'single', label: 'Single Project' },
  { value: 'multi', label: 'Multiple Projects' },
]

function toggleCreateProjectId(id: string) {
  const idx = form.selectedProjectIds.indexOf(id)
  if (idx >= 0) form.selectedProjectIds.splice(idx, 1)
  else form.selectedProjectIds.push(id)
}

function openCreate() {
  form.name = ''
  form.type = 'KANBAN'
  form.swimlaneStrategy = 'NONE'
  form.scope = 'single'
  form.selectedProjectIds = []
  error.value = ''
  showCreate.value = true
}

async function handleCreate() {
  if (!form.name.trim()) {
    error.value = 'Name is required'
    return
  }
  if (form.scope === 'single' && !selectedProjectId.value) {
    error.value = 'Select a project first'
    return
  }
  if (form.scope === 'multi' && form.selectedProjectIds.length < 2) {
    error.value = 'Select at least two projects'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const input: Record<string, unknown> = {
      name: form.name.trim(),
      type: form.type,
      swimlaneStrategy: form.swimlaneStrategy,
    }
    if (form.scope === 'single') {
      input.projectId = selectedProjectId.value
    } else {
      input.projectIds = form.selectedProjectIds
    }
    await mutation(gql`
      mutation CreateBoard($input: CreateWorkOpsBoardInput!) {
        workOps { boards { create(input: $input) { id } } }
      }
    `, { input })
    showCreate.value = false
    await loadBoards()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create board'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Boards')"
        title="Boards"
        :subtitle="selectedBoard ? `${selectedBoard.type} · ${selectedBoard.columns.length} columns` : ''"
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
          <Select
            v-if="sprintFilterOptions.length > 1"
            v-model="selectedSprintFilter"
            :options="sprintFilterOptions"
            placeholder="Sprint"
            :accent="accent"
            size="sm"
          />
          <Button
            v-if="selectedBoard"
            icon="settings"
            size="sm"
            @click="router.push(`/workops/boards/${selectedBoard.id}?tab=settings`)">
            Configure
          </Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">
            New Board
          </Button>
        </template>
      </PageHeader>
    </template>

    <BoardView
      v-if="selectedBoard && selectedBoardProjectKeys.length"
      :board="selectedBoard"
      :project-keys="selectedBoardProjectKeys"
      :project-id="selectedProjectId"
      :accent="accent"
      :sprint-filter-ids="sprintFilterIds"
      :active-sprint="activeSprintInfo"
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

    <div v-else-if="boardsLoading" class="loading-state">Loading boards…</div>

    <div v-else class="empty-state">
      <p v-if="!selectedProjectId">Select a project to view boards.</p>
      <p v-else>No boards for this project. Create one to get started.</p>
    </div>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Board"
      icon="columns"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="Board name" />
        <Select
          v-model="form.type"
          :options="typeOptions"
          label="Type"
          :accent="accent" />
        <Select
          v-model="form.scope"
          :options="scopeOptions"
          label="Scope"
          :accent="accent" />
        <Select
          v-model="form.swimlaneStrategy"
          :options="swimlaneOptions"
          label="Swimlane Strategy"
          :accent="accent" />

        <div v-if="form.scope === 'multi'" class="project-picker">
          <label class="project-picker-label">Projects</label>
          <p class="project-picker-hint">Select which projects this board spans.</p>
          <div class="project-chips">
            <button
              v-for="p in projects"
              :key="p.id"
              :class="['project-chip', { selected: form.selectedProjectIds.includes(p.id) }]"
              @click="toggleCreateProjectId(p.id)"
            >
              <span class="project-chip-key">{{ p.key }}</span>
              {{ p.name }}
            </button>
          </div>
          <p v-if="form.selectedProjectIds.length" class="project-picker-hint">
            {{ form.selectedProjectIds.length }} project{{ form.selectedProjectIds.length === 1 ? '' : 's' }} selected
          </p>
        </div>
        <p v-if="form.scope === 'single' && !selectedProjectId" class="form-hint">
          Select a project in the header to create a single-project board.
        </p>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreate">
          {{ saving ? 'Creating…' : 'Create Board' }}
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

.form-hint {
  font-size: 12px;
  color: var(--fg-3);
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
</style>
