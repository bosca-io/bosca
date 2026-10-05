<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation, query } = useGraphQL()
const router = useRouter()
const toast = useToast()

const showCreate = ref(false)
const showEdit = ref(false)
const showDelete = ref(false)
const editTarget = ref<SavedFilter | null>(null)
const deleteTarget = ref<SavedFilter | null>(null)
const saving = ref(false)
const deleting = ref(false)
const error = ref('')

const form = reactive({ name: '', description: '', bqlSource: '' })

const listGql = gql`
  query {
    workOps {
      savedFilters {
        mine(limit: 50, offset: 0) {
          id
          name
          description
          bqlSource
          createdAt
          modifiedAt
          version
        }
      }
    }
  }
`

interface SavedFilter {
  id: string
  name: string
  description: string | null
  bqlSource: string
  createdAt: string
  modifiedAt: string
  version: number
}

const { data, status, refresh } = useAsyncQuery<{
  workOps: { savedFilters: { mine: SavedFilter[] } }
}>('workops-filters', listGql, undefined, { server: false })

const filters = computed(() => data.value?.workOps?.savedFilters?.mine ?? [])
const isLoading = computed(() => status.value === 'pending')

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: '1.5fr' },
  { key: 'bql', label: 'BQL Query', width: '2fr' },
  { key: 'modified', label: 'Modified', width: '120px', muted: true },
]

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function openCreate() {
  form.name = ''
  form.description = ''
  form.bqlSource = ''
  error.value = ''
  showCreate.value = true
}

function openEdit(f: SavedFilter) {
  editTarget.value = f
  form.name = f.name
  form.description = f.description ?? ''
  form.bqlSource = f.bqlSource
  error.value = ''
  showEdit.value = true
}

function openDelete(f: SavedFilter) {
  deleteTarget.value = f
  showDelete.value = true
}

function runFilter(f: SavedFilter) {
  router.push({ path: '/workops/tasks', query: { bql: f.bqlSource } })
}

async function handleCreate() {
  if (!form.name || !form.bqlSource) {
    error.value = 'Name and BQL query are required.'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await mutation(gql`
      mutation CreateFilter($input: WorkOpsSavedFilterInput!) {
        workOps { savedFilters { create(input: $input) { id } } }
      }
    `, {
      input: { name: form.name, description: form.description || undefined, bqlSource: form.bqlSource },
    })
    showCreate.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create filter'
  } finally {
    saving.value = false
  }
}

async function handleUpdate() {
  if (!form.name || !form.bqlSource) {
    error.value = 'Name and BQL query are required.'
    return
  }
  saving.value = true
  error.value = ''
  try {
    if (!editTarget.value) return
    await mutation(gql`
      mutation UpdateFilter($id: UUID!, $input: WorkOpsSavedFilterInput!, $version: Long!) {
        workOps { savedFilters { update(id: $id, input: $input, version: $version) { id } } }
      }
    `, {
      id: editTarget.value.id,
      version: editTarget.value.version,
      input: { name: form.name, description: form.description || undefined, bqlSource: form.bqlSource },
    })
    showEdit.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to update filter'
  } finally {
    saving.value = false
  }
}

async function handleDelete() {
  deleting.value = true
  try {
    if (!deleteTarget.value) return
    await mutation(gql`
      mutation DeleteFilter($id: UUID!, $version: Long!) {
        workOps { savedFilters { delete(id: $id, version: $version) } }
      }
    `, { id: deleteTarget.value.id, version: deleteTarget.value.version })
    showDelete.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to delete filter'
  } finally {
    deleting.value = false
  }
}

function onRowClick(row: SavedFilter) {
  runFilter(row)
}

function menuItems() {
  return [
    { id: 'run', label: 'Run', icon: 'search' },
    { id: 'preview', label: 'Preview', icon: 'eye' },
    { id: 'edit', label: 'Edit', icon: 'edit' },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onMenuSelect(id: string, row: SavedFilter) {
  if (id === 'run') runFilter(row)
  else if (id === 'preview') previewFilter(row)
  else if (id === 'edit') openEdit(row)
  else if (id === 'delete') openDelete(row)
}

// ─── Preview ─────────────────────────────────────────────────────────────────
interface PreviewTask {
  id: string
  key: string
  summary: string
  status: { name: string; category: string }
  priority: { name: string; displayOrder: number }
}

const previewTarget = ref<SavedFilter | null>(null)
const previewResults = ref<PreviewTask[]>([])
const previewLoading = ref(false)

async function previewFilter(f: SavedFilter) {
  previewTarget.value = f
  previewLoading.value = true
  previewResults.value = []
  try {
    const result = await query<{
      workOps: { savedFilters: { searchTasks: { rows: PreviewTask[] } } }
    }>(gql`
      query PreviewFilter($source: String!) {
        workOps { savedFilters { searchTasks(source: $source, limit: 10, offset: 0) {
          rows { id key summary status { name category } priority { name displayOrder } }
        } } }
      }
    `, { source: f.bqlSource })
    previewResults.value = result.workOps?.savedFilters?.searchTasks?.rows ?? []
  } catch {
    toast.error('Failed to run filter preview')
    previewTarget.value = null
  } finally {
    previewLoading.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Filters')"
        title="Saved Filters"
        :subtitle="`${filters.length} filters`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Filter</Button>
        </template>
      </PageHeader>
    </template>

    <GlassTable
      :columns="columns"
      :rows="filters"
      row-key="id"
      :loading="isLoading"
      empty-text="No saved filters. Create one to save a BQL query."
      @row-click="onRowClick"
    >
      <template #col-name="{ value, row }">
        <div>
          <span class="filter-name">{{ value }}</span>
          <span v-if="row.description" class="filter-desc">{{ row.description }}</span>
        </div>
      </template>
      <template #col-bql="{ row }">
        <span class="mono bql-preview">{{ row.bqlSource }}</span>
      </template>
      <template #col-modified="{ row }">
        {{ formatDate(row.modifiedAt) }}
      </template>
      <template #actions="{ row }">
        <OverflowMenu :items="menuItems()" @select="onMenuSelect($event, row)">
          <template #default="{ toggle }">
            <button class="row-menu-btn" @click.stop="toggle">
              <Icon name="list" :size="14" color="var(--fg-3)" />
            </button>
          </template>
        </OverflowMenu>
      </template>
    </GlassTable>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Filter"
      icon="filter"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="Filter name" />
        <TextInput v-model="form.description" label="Description" placeholder="Optional description" />
        <div class="form-field">
          <label class="field-label">BQL Query</label>
          <textarea
            v-model="form.bqlSource"
            class="bql-editor"
            rows="4"
            placeholder='status = "In Progress" ORDER BY priority ASC'
            spellcheck="false"
          />
        </div>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreate">Create</Button>
      </template>
    </Modal>

    <!-- Edit Modal -->
    <Modal
      v-if="showEdit"
      title="Edit Filter"
      icon="pencil"
      :accent="accent"
      @close="showEdit = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="Filter name" />
        <TextInput v-model="form.description" label="Description" placeholder="Optional description" />
        <div class="form-field">
          <label class="field-label">BQL Query</label>
          <textarea
            v-model="form.bqlSource"
            class="bql-editor"
            rows="4"
            spellcheck="false"
          />
        </div>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showEdit = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleUpdate">Save</Button>
      </template>
    </Modal>

    <!-- Delete Confirm -->
    <ConfirmModal
      v-if="showDelete"
      title="Delete Filter"
      subtitle="This cannot be undone."
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete"
    >
      <p>Delete <strong>{{ deleteTarget?.name }}</strong>?</p>
    </ConfirmModal>

    <!-- Preview Modal -->
    <Modal
      v-if="previewTarget"
      :title="`Preview: ${previewTarget.name}`"
      icon="eye"
      :accent="accent"
      @close="previewTarget = null">
      <div class="preview-query">
        <span class="mono">{{ previewTarget.bqlSource }}</span>
      </div>
      <div v-if="previewLoading" class="preview-loading">Searching…</div>
      <div v-else-if="previewResults.length" class="preview-list">
        <div
          v-for="t in previewResults"
          :key="t.id"
          class="preview-row"
          @click="router.push(`/workops/tasks/${t.id}`); previewTarget = null">
          <span class="mono preview-key">{{ t.key }}</span>
          <span class="preview-summary">{{ t.summary }}</span>
          <Badge :color="t.status.category === 'DONE' ? '#34d99a' : t.status.category === 'IN_PROGRESS' ? '#a78bff' : '#6c7388'">
            {{ t.status.name }}
          </Badge>
        </div>
        <p v-if="previewResults.length >= 10" class="preview-more">Showing first 10 results.
          <button class="preview-run-link" @click="runFilter(previewTarget!); previewTarget = null">View all →</button>
        </p>
      </div>
      <div v-else class="preview-empty">No tasks match this filter.</div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="previewTarget = null">Close</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          @click="runFilter(previewTarget!); previewTarget = null">Run Full Search</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.filter-name {
  font-weight: 500;
  color: var(--fg-0);
  display: block;
}

.filter-desc {
  font-size: 11.5px;
  color: var(--fg-3);
  display: block;
  margin-top: 2px;
}

.bql-preview {
  font-size: 11.5px;
  color: var(--fg-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
  max-width: 100%;
}

.row-menu-btn {
  background: none;
  border: none;
  padding: 4px;
  cursor: pointer;
  border-radius: 4px;
  display: flex;
}

.row-menu-btn:hover { background: var(--bg-3); }

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
}

.bql-editor {
  font-family: var(--font-mono);
  font-size: 12.5px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 14px;
  color: var(--fg-0);
  resize: vertical;
}

.bql-editor:focus {
  outline: none;
  border-color: v-bind(accent);
}

.form-error {
  color: var(--err);
  font-size: 12px;
  margin: 0;
}

/* Preview */
.preview-query {
  padding: 10px 12px;
  background: var(--bg-2);
  border-radius: var(--r-sm);
  margin-bottom: 12px;
  font-size: 12px;
  color: var(--fg-2);
}

.preview-loading {
  color: var(--fg-3);
  font-size: 13px;
  text-align: center;
  padding: 20px;
}

.preview-list {
  display: flex;
  flex-direction: column;
}

.preview-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  transition: background 0.1s;
}

.preview-row:last-child { border-bottom: none; }
.preview-row:hover { background: var(--bg-1); }

.preview-key {
  font-size: 10.5px;
  padding: 2px 5px;
  background: var(--bg-3);
  border-radius: 3px;
  color: var(--fg-2);
  flex-shrink: 0;
}

.preview-summary {
  font-size: 13px;
  color: var(--fg-0);
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-empty {
  color: var(--fg-3);
  font-size: 13px;
  text-align: center;
  padding: 20px;
}

.preview-more {
  font-size: 12px;
  color: var(--fg-3);
  margin: 8px 0 0;
}

.preview-run-link {
  color: v-bind(accent);
  font-weight: 500;
  cursor: pointer;
}

.spacer { flex: 1; }
</style>
