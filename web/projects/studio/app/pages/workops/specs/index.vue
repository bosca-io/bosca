<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query } = useGraphQL()
const router = useRouter()
const route = useRoute()

const showNewSpec = ref(false)
useCreateFromQuery(() => { showNewSpec.value = true })
const BQL_STORAGE_KEY = 'workops-specs-bql'
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

// ─── Saved filters ────────────────────────────────────────────────────────────
interface SavedFilter {
  id: string
  name: string
  bqlSource: string
}

const { data: filtersData } = useAsyncQuery<{
  workOps: { savedFilters: { mine: SavedFilter[] } }
}>('workops-specs-filters', gql`
  query { workOps { savedFilters { mine(limit: 20, offset: 0) { id name bqlSource } } } }
`, undefined, { server: false })

const savedFilters = computed(() => filtersData.value?.workOps?.savedFilters?.mine ?? [])

function applyFilter(f: SavedFilter) {
  bqlSource.value = f.bqlSource
  offset.value = 0
  localStorage.setItem(BQL_STORAGE_KEY, f.bqlSource)
  router.replace({ query: { ...route.query, bql: f.bqlSource } })
  refresh()
}

// ─── Projects (for spec creation modal) ─────────────────────────────────────

interface ProjectOption { id: string; key: string; name: string }

const { data: projectsData } = useAsyncQuery<{
  workOps: { projects: { all: Array<ProjectOption & { archivedAt: string | null }> } }
}>('workops-specs-projects', gql`
  query { workOps { projects { all { id key name archivedAt } } } }
`, undefined, { server: false })

const projects = computed(() =>
  (projectsData.value?.workOps?.projects?.all ?? []).filter(p => !p.archivedAt),
)

// ─── Search ─────────────────────────────────────────────────────────────────

const SPEC_LIST_FIELDS = `
  id key metadataId
  metadata { id name }
  status { id name category }
  owner { id name }
  ownerProfileId
  project { id key name }
  parentSpecId
  childCount childDoneCount
  requirementCount
  gitRepositoryId gitPath
  labelIds
  createdAt modifiedAt
`

const searchGql = gql`
  query SearchSpecs($source: String!, $limit: Int!, $offset: Long!) {
    workOps {
      savedFilters {
        searchSpecs(source: $source, limit: $limit, offset: $offset) {
          rows { ${SPEC_LIST_FIELDS} }
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

interface SpecRow {
  id: string
  key: string
  metadataId: string
  metadata: { id: string; name: string } | null
  status: { id: string; name: string; category: string }
  owner: { id: string; name: string } | null
  ownerProfileId: string
  project: { id: string; key: string; name: string } | null
  parentSpecId: string | null
  childCount: number
  childDoneCount: number
  requirementCount: number
  gitRepositoryId: string | null
  gitPath: string | null
  labelIds: string[]
  createdAt: string
  modifiedAt: string
}

const { data, status, refresh } = useAsyncQuery<{
  workOps: { savedFilters: { searchSpecs: { rows: SpecRow[]; freeTextTerms: string[] } } }
}>('workops-specs-search', searchGql, { source: bqlSource, limit, offset }, { server: false })

const specs = computed(() => data.value?.workOps?.savedFilters?.searchSpecs?.rows ?? [])
const isLoading = computed(() => status.value === 'pending')
const hasMore = computed(() => specs.value.length >= limit.value)

// ─── BQL validation ─────────────────────────────────────────────────────────

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

// ─── Table ───────────────────────────────────────────────────────────────────

const columns: GlassTableColumn[] = [
  { key: 'key', label: 'Key', width: '110px' },
  { key: 'name', label: 'Name', width: '2fr' },
  { key: 'status', label: 'Status', width: '130px' },
  { key: 'progress', label: 'Progress', width: '140px' },
  { key: 'owner', label: 'Owner', width: '1fr' },
  { key: 'project', label: 'Project', width: '120px', muted: true },
  { key: 'modified', label: 'Modified', width: '110px', muted: true },
]

function onRowClick(row: SpecRow) {
  router.push(`/workops/specs/${row.id}`)
}

function relativeTime(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(ms / 60_000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  const days = Math.floor(hrs / 24)
  return `${days}d ago`
}

function reqProgress(row: SpecRow): number {
  if (!row.requirementCount) return 0
  return Math.round((row.childDoneCount / row.childCount) * 100) || 0
}

// ─── Keyboard Nav ────────────────────────────────────────────────────────────

function onKeyDown(e: KeyboardEvent) {
  if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
  if (e.key === 'j') selectedIdx.value = Math.min(selectedIdx.value + 1, specs.value.length - 1)
  else if (e.key === 'k') selectedIdx.value = Math.max(selectedIdx.value - 1, 0)
  else if (e.key === 'Enter' && selectedIdx.value >= 0 && specs.value[selectedIdx.value]) {
    router.push(`/workops/specs/${specs.value[selectedIdx.value]!.id}`)
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
        :breadcrumb="buildBreadcrumb('Work Ops', 'Specs')"
        title="Specs"
        :subtitle="`${specs.length} results`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showNewSpec = true">New Spec</Button>
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
            placeholder='status = "In Progress" ORDER BY modified DESC'
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
      :rows="specs"
      row-key="id"
      :loading="isLoading"
      empty-text="No specs found. Try a different BQL query."
      @row-click="onRowClick"
    >
      <template #col-key="{ value }">
        <span class="mono spec-key">{{ value }}</span>
      </template>
      <template #col-name="{ row }">
        <div class="spec-name-cell">
          <span class="spec-name">{{ row.metadata?.name || 'Untitled' }}</span>
          <div class="spec-name-meta">
            <span v-if="row.childCount > 0" class="child-count">{{ row.childDoneCount }}/{{ row.childCount }} children</span>
            <span v-if="row.gitRepositoryId" class="git-indicator">
              <Icon name="git-branch" :size="10" color="var(--fg-3)" />
            </span>
          </div>
        </div>
      </template>
      <template #col-status="{ row }">
        <Badge :color="STATUS_COLORS[row.status?.category ?? ''] || '#6c7388'">
          {{ row.status?.name }}
        </Badge>
      </template>
      <template #col-progress="{ row }">
        <div v-if="row.requirementCount > 0" class="progress-cell">
          <div class="progress-bar-track">
            <div class="progress-bar-fill" :style="{ width: `${reqProgress(row)}%` }" />
          </div>
          <span class="progress-label">{{ row.requirementCount }} reqs</span>
        </div>
        <span v-else class="fg-3">—</span>
      </template>
      <template #col-owner="{ row }">
        <span v-if="row.owner" class="owner-cell">
          <Avatar :name="row.owner.name" :idx="0" :size="18" />
          {{ row.owner.name }}
        </span>
        <span v-else class="fg-3">Unassigned</span>
      </template>
      <template #col-project="{ row }">
        {{ row.project?.key || '—' }}
      </template>
      <template #col-modified="{ row }">
        <span class="fg-2">{{ relativeTime(row.modifiedAt) }}</span>
      </template>
    </GlassTable>

    <div v-if="specs.length > 0" class="pagination">
      <Button size="sm" :disabled="offset === 0" @click="prevPage">Previous</Button>
      <span class="mono pagination-info">{{ offset + 1 }}–{{ offset + specs.length }}</span>
      <Button size="sm" :disabled="!hasMore" @click="nextPage">Next</Button>
    </div>

    <NewSpecModal
      v-if="showNewSpec"
      :accent="accent"
      :projects="projects"
      @close="showNewSpec = false"
      @created="refresh()"
    />
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

/* ─── Results ─────────────────────────────────────────────────────────────── */
.spec-key {
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
  background: color-mix(in oklch, var(--bg-3) 30%, transparent);
  color: var(--fg-2);
  font-weight: 600;
}

.spec-name-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.spec-name {
  font-weight: 500;
  color: var(--fg-0);
}

.spec-name-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.child-count {
  font-size: 11px;
  color: var(--fg-3);
}

.git-indicator {
  display: inline-flex;
  align-items: center;
}

.progress-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.progress-bar-track {
  flex: 1;
  height: 4px;
  background: var(--bg-3);
  border-radius: 2px;
  overflow: hidden;
}

.progress-bar-fill {
  height: 100%;
  background: v-bind(accent);
  border-radius: 2px;
  transition: width 0.3s ease;
}

.progress-label {
  font-size: 11px;
  color: var(--fg-3);
  white-space: nowrap;
}

.owner-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.fg-2 { color: var(--fg-2); }
.fg-3 { color: var(--fg-3); }

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
