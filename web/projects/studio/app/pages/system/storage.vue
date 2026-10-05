<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

const showCreate = ref(false)
const showEdit = ref(false)
const showDelete = ref(false)
const showReindex = ref(false)
const editTarget = ref<StorageSystemRow | null>(null)
const deleteTarget = ref<StorageSystemRow | null>(null)
// null target = reindex every storage system
const reindexTarget = ref<StorageSystemRow | null>(null)
const saving = ref(false)
const deleting = ref(false)
const reindexing = ref(false)
const error = ref('')

const reindexForm = reactive({
  deleteFirst: false,
  metadata: true,
  collections: true,
  profiles: true,
})

const form = reactive({
  name: '',
  description: '',
  type: 'SEARCH',
  configuration: '{}',
  models: [] as Array<{ modelId: string; configuration: string }>,
})

const TYPE_COLORS: Record<string, string> = {
  SEARCH: '#5ec5ff',
  VECTOR: '#a78bff',
  SUPPLEMENTARY: '#34d99a',
}

const typeOptions = [
  { value: 'SEARCH', label: 'Search' },
  { value: 'VECTOR', label: 'Vector' },
  { value: 'SUPPLEMENTARY', label: 'Supplementary' },
]

const listGql = gql`
  query {
    storageSystems {
      all { id name description type configuration models { modelId model { id name } configuration } }
    }
  }
`

const modelsGql = gql`query { ai { models { all { id name key } } } }`

interface StorageSystemRow {
  id: string
  name: string
  description: string
  type: string
  configuration: unknown
  models: Array<{ modelId: string; model: { id: string; name: string } | null; configuration: unknown }>
}

const { data, status, refresh } = useAsyncQuery<{
  storageSystems: { all: StorageSystemRow[] }
}>('system-storage', listGql)

const { data: allModelsData } = useAsyncQuery<{
  ai: { models: { all: Array<{ id: string; name: string; key: string }> } }
}>('system-storage-models', modelsGql)

const storageSystems = computed(() => data.value?.storageSystems?.all ?? [])
const allModels = computed(() => allModelsData.value?.ai?.models?.all ?? [])
const modelOptions = computed(() => allModels.value.map(m => ({ value: m.id, label: `${m.key} — ${m.name}` })))
const isLoading = computed(() => status.value === 'pending')

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: '1.5fr' },
  { key: 'type', label: 'Type', width: '130px' },
  { key: 'description', label: 'Description', width: '2fr', muted: true },
  { key: 'models', label: 'Models', width: '80px', align: 'center' },
]

function parseJson(str: string): unknown | null {
  try { return JSON.parse(str) } catch { return null }
}

function resetForm() {
  form.name = ''; form.description = ''; form.type = 'SEARCH'; form.configuration = '{}'; form.models = []
  error.value = ''
}

function openCreate() {
  resetForm()
  showCreate.value = true
}

function openEdit(ss: StorageSystemRow) {
  editTarget.value = ss
  form.name = ss.name
  form.description = ss.description
  form.type = ss.type
  form.configuration = ss.configuration ? JSON.stringify(ss.configuration, null, 2) : '{}'
  form.models = ss.models.map(m => ({
    modelId: m.modelId,
    configuration: m.configuration ? JSON.stringify(m.configuration, null, 2) : '{}',
  }))
  error.value = ''
  showEdit.value = true
}

function addModelEntry() {
  form.models.push({ modelId: '', configuration: '{}' })
}

function removeModelEntry(idx: number) {
  form.models.splice(idx, 1)
}

function buildInput() {
  const cfg = parseJson(form.configuration)
  if (cfg === null) { error.value = 'Invalid system configuration JSON.'; return null }
  const modelInputs = []
  for (const m of form.models) {
    const mc = parseJson(m.configuration)
    if (mc === null) { error.value = 'Invalid model configuration JSON.'; return null }
    modelInputs.push({ modelId: m.modelId, configuration: mc })
  }
  return { name: form.name, description: form.description, type: form.type, configuration: cfg, models: modelInputs }
}

async function handleCreate() {
  if (!form.name) { error.value = 'Name required.'; return }
  const input = buildInput()
  if (!input) return
  saving.value = true; error.value = ''
  try {
    await mutation(gql`
      mutation AddStorageSystem($storageSystem: StorageSystemInput!) { storageSystems { add(storageSystem: $storageSystem) { id } } }
    `, { storageSystem: input })
    showCreate.value = false; await refresh()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Unknown error' } finally { saving.value = false }
}

async function handleEdit() {
  if (!form.name) { error.value = 'Name required.'; return }
  const input = buildInput()
  if (!input) return
  saving.value = true; error.value = ''
  try {
    if (!editTarget.value) return
    await mutation(gql`
      mutation EditStorageSystem($id: UUID!, $storageSystem: StorageSystemInput!) { storageSystems { edit(id: $id, storageSystem: $storageSystem) { id } } }
    `, { id: editTarget.value.id, storageSystem: input })
    showEdit.value = false; await refresh()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Unknown error' } finally { saving.value = false }
}

async function handleDelete() {
  deleting.value = true
  try {
    if (!deleteTarget.value) return
    await mutation(gql`mutation($id: UUID!) { storageSystems { delete(id: $id) } }`, { id: deleteTarget.value.id })
    showDelete.value = false; await refresh()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Unknown error' } finally { deleting.value = false }
}

function openReindex(ss: StorageSystemRow | null) {
  reindexTarget.value = ss
  reindexForm.deleteFirst = false
  reindexForm.metadata = true
  reindexForm.collections = true
  reindexForm.profiles = true
  showReindex.value = true
}

const reindexScopeSelected = computed(() => reindexForm.metadata || reindexForm.collections || reindexForm.profiles)

async function handleReindex() {
  reindexing.value = true
  try {
    await mutation(gql`
      mutation Reindex($storageName: String, $deleteFirst: Boolean, $metadata: Boolean, $collections: Boolean, $profiles: Boolean) {
        storageSystems { reindex(storageName: $storageName, deleteFirst: $deleteFirst, metadata: $metadata, collections: $collections, profiles: $profiles) }
      }
    `, {
      storageName: reindexTarget.value?.name ?? null,
      deleteFirst: reindexForm.deleteFirst,
      metadata: reindexForm.metadata,
      collections: reindexForm.collections,
      profiles: reindexForm.profiles,
    })
    toast.success(reindexTarget.value ? `Reindex of ${reindexTarget.value.name} started` : 'Full reindex started')
    showReindex.value = false
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to start reindex')
  } finally { reindexing.value = false }
}

function onMenuSelect(id: string, row: StorageSystemRow) {
  if (id === 'edit') openEdit(row)
  else if (id === 'reindex') openReindex(row)
  else if (id === 'delete') { deleteTarget.value = row; showDelete.value = true }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Storage')"
        title="Storage Systems"
        :subtitle="`${storageSystems.length} systems`"
      >
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :accent="accent"
            @click="openReindex(null)">Reindex All</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Storage System</Button>
        </template>
      </PageHeader>
    </template>

    <GlassTable
      :columns="columns"
      :rows="storageSystems"
      row-key="id"
      :loading="isLoading"
      empty-text="No storage systems configured."
      @row-click="(row: StorageSystemRow) => openEdit(row)"
    >
      <template #col-type="{ value }">
        <Badge :color="TYPE_COLORS[value] || '#6c7388'">{{ value }}</Badge>
      </template>
      <template #col-models="{ row }">
        <span class="mono">{{ row.models.length }}</span>
      </template>
      <template #actions="{ row }">
        <OverflowMenu
          :items="[
            { id: 'edit', label: 'Edit', icon: 'edit' },
            { id: 'reindex', label: 'Reindex', icon: 'refresh' },
            { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
          ]"
          @select="onMenuSelect($event, row)"
        >
          <template #default="{ toggle }">
            <button class="row-menu-btn" @click.stop="toggle">
              <Icon name="list" :size="14" color="var(--fg-3)" />
            </button>
          </template>
        </OverflowMenu>
      </template>
    </GlassTable>

    <!-- Create/Edit Modal -->
    <Modal
      v-if="showCreate || showEdit"
      :title="showCreate ? 'New Storage System' : 'Edit Storage System'"
      icon="database"
      :accent="accent"
      width="600px"
      @close="showCreate = false; showEdit = false"
    >
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="Admin Search Index" />
        <TextInput v-model="form.description" label="Description" placeholder="Description" />
        <Select v-model="form.type" :options="typeOptions" :accent="accent" />
        <div class="form-field">
          <label class="field-label">Configuration (JSON)</label>
          <textarea
            v-model="form.configuration"
            class="config-editor"
            rows="4"
            spellcheck="false" />
        </div>

        <div class="models-section">
          <div class="models-header">
            <span class="field-label">Models</span>
            <Button size="sm" icon="plus" @click="addModelEntry">Add</Button>
          </div>
          <div v-for="(m, idx) in form.models" :key="idx" class="model-entry">
            <Select
              v-model="m.modelId"
              :options="modelOptions"
              placeholder="Select model"
              :accent="accent"
              searchable />
            <textarea
              v-model="m.configuration"
              class="config-editor model-config"
              rows="2"
              spellcheck="false"
              placeholder="{}" />
            <button class="remove-btn" @click="removeModelEntry(idx)">
              <Icon name="trash" :size="12" color="var(--err)" />
            </button>
          </div>
        </div>

        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false; showEdit = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="showCreate ? handleCreate() : handleEdit()">
          {{ saving ? 'Saving…' : (showCreate ? 'Create' : 'Save') }}
        </Button>
      </template>
    </Modal>

    <!-- Reindex -->
    <Modal
      v-if="showReindex"
      :title="reindexTarget ? `Reindex ${reindexTarget.name}` : 'Reindex All Storage Systems'"
      icon="refresh"
      :accent="accent"
      width="480px"
      @close="showReindex = false"
    >
      <div class="form-stack">
        <div class="form-field">
          <span class="field-label">Include</span>
          <Checkbox v-model="reindexForm.metadata" label="Metadata" :accent="accent" />
          <Checkbox v-model="reindexForm.collections" label="Collections" :accent="accent" />
          <Checkbox v-model="reindexForm.profiles" label="Profiles" :accent="accent" />
        </div>
        <div class="form-field">
          <span class="field-label">Options</span>
          <Checkbox v-model="reindexForm.deleteFirst" label="Delete existing index data first (clean rebuild)" :accent="accent" />
          <p v-if="reindexForm.deleteFirst" class="reindex-warning">
            Existing index data is removed before reindexing, so search results will be incomplete until the rebuild finishes.
          </p>
        </div>
      </div>
      <template #footer>
        <Button @click="showReindex = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="reindexing || !reindexScopeSelected"
          @click="handleReindex">
          {{ reindexing ? 'Starting…' : 'Reindex' }}
        </Button>
      </template>
    </Modal>

    <!-- Delete -->
    <ConfirmModal
      v-if="showDelete"
      title="Delete Storage System"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete">
      <p>Delete <strong>{{ deleteTarget?.name }}</strong>? This cannot be undone.</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.row-menu-btn {
  background: none;
  border: none;
  padding: 4px;
  cursor: pointer;
  border-radius: 4px;
  display: flex;
}
.row-menu-btn:hover { background: var(--bg-3); }

.form-stack { display: flex; flex-direction: column; gap: 12px; }
.form-field { display: flex; flex-direction: column; gap: 6px; }
.field-label { font-size: 12px; font-weight: 600; color: var(--fg-2); }

.config-editor {
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 14px;
  color: var(--fg-0);
  resize: vertical;
}
.config-editor:focus { outline: none; border-color: v-bind(accent); }

.models-section { display: flex; flex-direction: column; gap: 10px; }
.models-header { display: flex; align-items: center; justify-content: space-between; }

.model-entry {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 8px;
  align-items: start;
  padding: 10px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 8px;
}

.model-config { min-height: 36px; }

.remove-btn {
  background: none;
  border: none;
  padding: 6px;
  cursor: pointer;
  border-radius: 4px;
  display: flex;
  align-items: center;
  margin-top: 4px;
}
.remove-btn:hover { background: color-mix(in oklch, var(--err) 12%, transparent); }

.form-error { color: var(--err); font-size: 12px; margin: 0; }

.reindex-warning { color: var(--warn); font-size: 12px; margin: 0; }
</style>
