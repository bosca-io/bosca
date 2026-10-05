<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query } = useGraphQL()
const router = useRouter()
const route = useRoute()

const showNewTask = ref(false)
useCreateFromQuery(() => { showNewTask.value = true })
const BQL_STORAGE_KEY = 'workops-tasks-bql'
const defaultBql = (route.query.bql as string) || (import.meta.client && localStorage.getItem(BQL_STORAGE_KEY)) || 'ORDER BY modified DESC'
const bqlSource = ref(defaultBql)
const bqlError = ref('')
const offset = ref(0)
const limit = ref(25)
const selectedIdx = ref(-1)

const STATUS_COLORS: Record<string, string> = {
  TODO: '#6c7388',
  IN_PROGRESS: '#a78bff',
  IN_REVIEW: '#ffb547',
  DONE: '#34d99a',
  CANCELLED: '#6c7388',
}

const PRIORITY_COLORS: Record<number, string> = {
  1: '#ff5d6c',
  2: '#ff8a4d',
  3: '#ffb547',
  4: '#5ec5ff',
  5: '#6c7388',
}

// ─── Saved filters ────────────────────────────────────────────────────────────
interface SavedFilter {
  id: string
  name: string
  bqlSource: string
}

const { data: filtersData } = useAsyncQuery<{
  workOps: { savedFilters: { mine: SavedFilter[] } }
}>('workops-tasks-filters', gql`
  query { workOps { savedFilters { mine(limit: 20, offset: 0) { id name bqlSource } } } }
`, undefined, { server: false })

const savedFilters = computed(() => filtersData.value?.workOps?.savedFilters?.mine ?? [])

// ─── Projects (for task creation) ────────────────────────────────────────────
interface ProjectOption {
  id: string
  key: string
  name: string
  taskCreationFormSchemaKey: string
}

const { data: projectsData } = useAsyncQuery<{
  workOps: { projects: { all: ProjectOption[] } }
}>('workops-tasks-projects', gql`
  query { workOps { projects { all { id key name taskCreationFormSchemaKey } } } }
`, undefined, { server: false })

const projects = computed(() =>
  (projectsData.value?.workOps?.projects?.all ?? []).filter(p => !('archivedAt' in p && (p as Record<string, unknown>).archivedAt)),
)

const selectedProject = computed(() => projects.value[0] ?? null)

const taskFormSchemaKey = computed(() => selectedProject.value?.taskCreationFormSchemaKey ?? 'workops.create-task')

function applyFilter(f: SavedFilter) {
  bqlSource.value = f.bqlSource
  offset.value = 0
  localStorage.setItem(BQL_STORAGE_KEY, f.bqlSource)
  router.replace({ query: { ...route.query, bql: f.bqlSource } })
  refresh()
}

// ─── Search ───────────────────────────────────────────────────────────────────
const searchGql = gql`
  query SearchTasks($source: String!, $limit: Int!, $offset: Long!) {
    workOps {
      savedFilters {
        searchTasks(source: $source, limit: $limit, offset: $offset) {
          rows {
            id key summary
            status { id name category }
            taskType { id name }
            priority { id name displayOrder }
            assignee { id name }
            dueDate
          }
          freeTextTerms
        }
      }
    }
  }
`

const validateGql = gql`
  query ValidateBql($source: String!) {
    workOps {
      savedFilters {
        validateBql(source: $source) {
          errors { message start end hint }
        }
      }
    }
  }
`

interface TaskRow {
  id: string
  key: string
  summary: string
  status: { id: string; name: string; category: string }
  taskType: { id: string; name: string }
  priority: { id: string; name: string; displayOrder: number }
  assignee: { id: string; name: string } | null
  dueDate: string | null
}

const { data, status, refresh } = useAsyncQuery<{
  workOps: { savedFilters: { searchTasks: { rows: TaskRow[]; freeTextTerms: string[] } } }
}>('workops-tasks-search', searchGql, { source: bqlSource, limit, offset }, { server: false })

const tasks = computed(() => data.value?.workOps?.savedFilters?.searchTasks?.rows ?? [])
const isLoading = computed(() => status.value === 'pending')
const hasMore = computed(() => tasks.value.length >= limit.value)

const columns: GlassTableColumn[] = [
  { key: 'key', label: 'Key', width: '120px' },
  { key: 'summary', label: 'Summary', width: '2fr' },
  { key: 'status', label: 'Status', width: '130px' },
  { key: 'priority', label: 'Priority', width: '110px' },
  { key: 'type', label: 'Type', width: '100px', muted: true },
  { key: 'assignee', label: 'Assignee', width: '1fr' },
  { key: 'due', label: 'Due', width: '110px', muted: true },
]

let validateTimeout: ReturnType<typeof setTimeout> | null = null

function onBqlInput() {
  bqlError.value = ''
  if (validateTimeout) clearTimeout(validateTimeout)
  validateTimeout = setTimeout(async () => {
    try {
      const result = await query<{
        workOps: { savedFilters: { validateBql: { errors: Array<{ message: string; hint?: string }> } } }
      }>(validateGql, { source: bqlSource.value })
      const errors = result.workOps?.savedFilters?.validateBql?.errors ?? []
      bqlError.value = errors.length ? errors.map(e => e.hint || e.message).join('; ') : ''
    } catch {
      // validation query failed — ignore
    }
  }, 400)
}

function executeSearch() {
  if (bqlError.value) return
  offset.value = 0
  localStorage.setItem(BQL_STORAGE_KEY, bqlSource.value)
  router.replace({ query: { ...route.query, bql: bqlSource.value } })
  refresh()
}

function clearBql() {
  bqlSource.value = ''
  bqlError.value = ''
  localStorage.removeItem(BQL_STORAGE_KEY)
  router.replace({ query: { ...route.query, bql: undefined } })
  offset.value = 0
  refresh()
}

function nextPage() {
  offset.value += limit.value
  refresh()
}

function prevPage() {
  offset.value = Math.max(0, offset.value - limit.value)
  refresh()
}

function onRowClick(row: TaskRow) {
  router.push(`/workops/tasks/${row.id}`)
}

function formatDue(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  const now = new Date()
  const diffDays = Math.floor((d.getTime() - now.getTime()) / 86_400_000)
  if (diffDays === 0) return 'Today'
  if (diffDays === 1) return 'Tomorrow'
  if (diffDays < 7 && diffDays > 0) return d.toLocaleDateString(undefined, { weekday: 'short' })
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function dueColor(iso: string | null): string {
  if (!iso) return 'var(--fg-3)'
  const d = new Date(iso)
  const diffDays = Math.floor((d.getTime() - Date.now()) / 86_400_000)
  if (diffDays < 0) return 'var(--err)'
  if (diffDays === 0) return 'var(--warn)'
  return 'var(--fg-2)'
}

function onKeyDown(e: KeyboardEvent) {
  if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
  if (e.key === 'j') selectedIdx.value = Math.min(selectedIdx.value + 1, tasks.value.length - 1)
  else if (e.key === 'k') selectedIdx.value = Math.max(selectedIdx.value - 1, 0)
  else if (e.key === 'Enter' && selectedIdx.value >= 0 && tasks.value[selectedIdx.value]) {
    router.push(`/workops/tasks/${tasks.value[selectedIdx.value]!.id}`)
  } else if (e.key === '/') {
    e.preventDefault()
    document.querySelector<HTMLTextAreaElement>('.bql-input')?.focus()
  }
}

onMounted(() => { document.addEventListener('keydown', onKeyDown) })
onUnmounted(() => { document.removeEventListener('keydown', onKeyDown) })
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Tasks')"
        title="Tasks"
        :subtitle="`${tasks.length} results`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedProject"
            @click="showNewTask = true">New Task</Button>
        </template>
      </PageHeader>
    </template>

    <!-- BQL search -->
    <SectionCard title="Search" glass class="bql-section">
      <template #right>
        <span class="mono bql-hint">BQL · press <kbd>/</kbd> to focus · <kbd>⌘↵</kbd> to search</span>
      </template>
      <div class="bql-row">
        <div class="bql-input-wrap">
          <textarea
            v-model="bqlSource"
            class="bql-input"
            rows="1"
            placeholder='status = "In Progress" ORDER BY priority ASC'
            spellcheck="false"
            @input="onBqlInput"
            @keydown.enter.ctrl="executeSearch"
            @keydown.enter.meta="executeSearch"
          />
          <button
            v-if="bqlSource"
            class="bql-clear"
            title="Clear search"
            @click="clearBql"
          >
            <Icon name="x" :size="14" />
          </button>
        </div>
      </div>
      <p v-if="bqlError" class="bql-error">
        <Icon name="alert" :size="12" color="var(--err)" />
        {{ bqlError }}
      </p>
    </SectionCard>

    <!-- Saved filters -->
    <div v-if="savedFilters.length" class="saved-filters">
      <button
        v-for="f in savedFilters"
        :key="f.id"
        class="filter-chip"
        :class="{ active: bqlSource === f.bqlSource }"
        @click="applyFilter(f)"
      >
        <Icon name="filter" :size="11" :color="bqlSource === f.bqlSource ? accent : 'var(--fg-3)'" />
        {{ f.name }}
      </button>
    </div>

    <!-- Results -->
    <GlassTable
      :columns="columns"
      :rows="tasks"
      row-key="id"
      :loading="isLoading"
      empty-text="No tasks found. Try a different BQL query."
      @row-click="onRowClick"
    >
      <template #col-key="{ value }">
        <span class="mono task-key">{{ value }}</span>
      </template>
      <template #col-summary="{ value }">
        <span class="task-summary">{{ value }}</span>
      </template>
      <template #col-status="{ row }">
        <Badge :color="STATUS_COLORS[row.status?.category] || '#6c7388'">
          {{ row.status?.name }}
        </Badge>
      </template>
      <template #col-priority="{ row }">
        <Badge :color="PRIORITY_COLORS[row.priority?.displayOrder] || '#6c7388'">
          {{ row.priority?.name }}
        </Badge>
      </template>
      <template #col-type="{ row }">
        {{ row.taskType?.name }}
      </template>
      <template #col-assignee="{ row }">
        <span v-if="row.assignee" class="assignee-cell">
          <Avatar :name="row.assignee.name" :idx="0" :size="18" />
          {{ row.assignee.name }}
        </span>
        <span v-else class="fg-3">Unassigned</span>
      </template>
      <template #col-due="{ row }">
        <span :style="{ color: dueColor(row.dueDate) }">{{ formatDue(row.dueDate) }}</span>
      </template>
    </GlassTable>

    <div v-if="tasks.length > 0" class="pagination">
      <Button size="sm" :disabled="offset === 0" @click="prevPage">Previous</Button>
      <span class="mono pagination-info">{{ offset + 1 }}–{{ offset + tasks.length }}</span>
      <Button size="sm" :disabled="!hasMore" @click="nextPage">Next</Button>
    </div>
    <NewTaskModal
      v-if="showNewTask"
      :accent="accent"
      :schema-key="taskFormSchemaKey"
      :project-id="selectedProject?.id"
      @close="showNewTask = false"
      @created="refresh()" />
  </PageShell>
</template>

<style scoped>
/* ─── Saved filters bar ────────────────────────────────────────────────────── */
.saved-filters {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: -12px 0 12px;
  flex-wrap: wrap;
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 6px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  color: var(--fg-1);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.filter-chip:hover {
  border-color: v-bind(accent);
}

.filter-chip.active {
  background: color-mix(in oklch, v-bind(accent) 12%, transparent);
  border-color: color-mix(in oklch, v-bind(accent) 40%, transparent);
  color: var(--fg-0);
  font-weight: 500;
}

/* ─── BQL search ───────────────────────────────────────────────────────────── */
.bql-hint {
  font-size: 11px;
  color: var(--fg-3);
}

.bql-hint kbd {
  font-family: var(--font-mono);
  font-size: 10px;
  padding: 1px 4px;
  border-radius: 3px;
  background: var(--bg-3);
  border: 1px solid var(--line);
  color: var(--fg-2);
}

.bql-section {
  margin-bottom: 0;
}

.bql-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 4px;
}

.bql-input-wrap {
  position: relative;
  flex: 1;
}

.bql-input {
  width: 100%;
  font-family: var(--font-mono);
  font-size: 12.5px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 36px 10px 14px;
  color: var(--fg-0);
  resize: vertical;
  min-height: 36px;
}

.bql-input:focus {
  outline: none;
  border-color: v-bind(accent);
}

.bql-clear {
  position: absolute;
  top: 50%;
  right: 8px;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  background: transparent;
  border: none;
  border-radius: 4px;
  color: var(--fg-3);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.bql-clear:hover {
  background: var(--bg-3);
  color: var(--fg-0);
}

.bql-input::placeholder {
  color: var(--fg-4);
}

.bql-error {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--err);
  font-size: 11px;
  margin: 4px 8px 8px;
  padding: 3px 8px;
  background: color-mix(in oklch, var(--err) 6%, transparent);
  border-radius: 4px;
  width: fit-content;
}

/* ─── Results ──────────────────────────────────────────────────────────────── */
.task-key {
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
  background: color-mix(in oklch, var(--bg-3) 30%, transparent);
  color: var(--fg-2);
  font-weight: 600;
}

.task-summary {
  font-weight: 500;
  color: var(--fg-0);
}

.assignee-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.fg-3 {
  color: var(--fg-3);
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin-top: 16px;
}

.pagination-info {
  font-size: 12px;
  color: var(--fg-2);
}

</style>
