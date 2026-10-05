<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'
import { useAuth } from '@bosca/auth-client-browser'

const { accent } = useCurrentSubsystem()
const { searchProfiles } = useProfileSearch()
const { useAsyncQuery, mutation } = useGraphQL()
const router = useRouter()
const { profile } = import.meta.client ? useAuth() : { profile: ref(null) }

const activeTab = ref('All')
const showCreate = ref(false)
const showImport = ref(false)
const saving = ref(false)
const error = ref('')

const { save: saveLastOwner, load: loadLastOwner } = useLastGitOwner()
const savedOwner = loadLastOwner()

const selectedOwner = ref(savedOwner?.id ?? '')
const knownProfiles = ref<Map<string, string>>(new Map())

if (savedOwner) {
  knownProfiles.value.set(savedOwner.id, savedOwner.label)
}

const initialOwnerOption = computed(() => {
  const options: { value: string; label: string }[] = []
  const p = profile.value
  if (p?.id) {
    const label = p.name || p.slug || p.id
    options.push({ value: p.id, label })
    knownProfiles.value.set(p.id, label)
  }
  if (savedOwner && savedOwner.id !== p?.id) {
    options.push({ value: savedOwner.id, label: savedOwner.label })
  }
  return options
})

async function searchProfilesAndTrack(query: string) {
  const results = await searchProfiles(query)
  for (const opt of results) {
    knownProfiles.value.set(opt.value, opt.label)
  }
  return results
}

watch(() => profile.value?.id, (id) => {
  if (id && !selectedOwner.value) {
    selectedOwner.value = id
  }
}, { immediate: true })

watch(selectedOwner, (id) => {
  if (!id) return
  const label = knownProfiles.value.get(id) ?? id
  saveLastOwner({ id, label })
})

const form = reactive({
  name: '',
  slug: '',
  description: '',
  ownerId: '',
  visibility: 'PRIVATE',
  contentType: 'GENERAL',
  defaultBranch: 'main',
  initializeWithReadme: true,
})

const importForm = reactive({
  name: '',
  slug: '',
  cloneUrl: '',
  ownerId: '',
})

function resetForm() {
  form.name = ''
  form.slug = ''
  form.description = ''
  form.ownerId = ''
  form.visibility = 'PRIVATE'
  form.contentType = 'GENERAL'
  form.defaultBranch = 'main'
  form.initializeWithReadme = true
  error.value = ''
}

function resetImportForm() {
  importForm.name = ''
  importForm.slug = ''
  importForm.cloneUrl = ''
  importForm.ownerId = ''
  error.value = ''
}

watch(() => form.name, (v) => {
  form.slug = v.toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '')
})

watch(() => importForm.name, (v) => {
  importForm.slug = v.toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '')
})

interface RepoRow {
  id: string
  name: string
  slug: string
  description: string | null
  visibility: string
  contentType: string | null
  defaultBranch: string
  archived: boolean
  deleted: boolean
  diskSizeBytes: number
  forkedFromId: string | null
  ownerId: string
  created: string
  updated: string
}

const listGql = gql`
  query Repositories($ownerId: UUID!, $includeArchived: Boolean) {
    git {
      repositories(ownerId: $ownerId, includeArchived: $includeArchived) {
        id name slug description visibility contentType defaultBranch
        archived deleted diskSizeBytes forkedFromId ownerId
        created updated
      }
    }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  git: { repositories: RepoRow[] }
}>('git-repositories', listGql, { ownerId: computed(() => selectedOwner.value || ''), includeArchived: true }, { server: false })

const allRepos = computed(() => data.value?.git?.repositories ?? [])

const repos = computed(() => {
  if (activeTab.value === 'Active') return allRepos.value.filter(r => !r.archived && !r.deleted)
  if (activeTab.value === 'Archived') return allRepos.value.filter(r => r.archived)
  return allRepos.value.filter(r => !r.deleted)
})

const isLoading = computed(() => status.value === 'pending')

const subtitle = computed(() => {
  const active = allRepos.value.filter(r => !r.archived && !r.deleted).length
  const archived = allRepos.value.filter(r => r.archived).length
  return `${active} active · ${archived} archived`
})

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Repository', width: '1.5fr' },
  { key: 'visibility', label: 'Visibility', width: '100px' },
  { key: 'contentType', label: 'Type', width: '120px' },
  { key: 'defaultBranch', label: 'Branch', width: '100px' },
  { key: 'size', label: 'Size', width: '80px', muted: true },
  { key: 'updated', label: 'Updated', width: '120px', muted: true },
]

const visibilityColors: Record<string, string> = {
  PUBLIC: '#34d99a',
  INTERNAL: '#ffb547',
  PRIVATE: '#6c7388',
}

const visibilityOptions = [
  { value: 'PRIVATE', label: 'Private' },
  { value: 'INTERNAL', label: 'Internal' },
  { value: 'PUBLIC', label: 'Public' },
]

const contentTypeOptions = [
  { value: 'GENERAL', label: 'General' },
  { value: 'SCRIPT_PROJECT', label: 'Script Project' },
  { value: 'DOCUMENTATION', label: 'Documentation' },
  { value: 'ANALYTIC_QUERY_PROJECT', label: 'Analytic Query Project' },
  { value: 'AGENT_PROJECT', label: 'Agent Project' },
  { value: 'PIPELINE_PROJECT', label: 'Pipeline Project' },
]

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function contentTypeLabel(ct: string | null): string {
  if (!ct) return '—'
  return ct.replace(/_/g, ' ').split(' ').map(w => w.charAt(0) + w.slice(1).toLowerCase()).join(' ')
}

const selectedOwnerOption = computed(() => {
  const id = selectedOwner.value
  if (!id) return []
  const label = knownProfiles.value.get(id) ?? id
  return [{ value: id, label }]
})

function openCreate() {
  resetForm()
  form.ownerId = selectedOwner.value
  showCreate.value = true
}
useCreateFromQuery(openCreate)

function openImport() {
  resetImportForm()
  importForm.ownerId = selectedOwner.value
  showImport.value = true
}

const createGql = gql`
  mutation CreateRepo($input: CreateGitRepositoryInput!) {
    git { createRepository(input: $input) { id } }
  }
`

const importGql = gql`
  mutation ImportRepo($name: String!, $slug: String!, $ownerId: UUID!, $cloneUrl: String!) {
    git { importRepository(name: $name, slug: $slug, ownerId: $ownerId, cloneUrl: $cloneUrl) { id } }
  }
`

async function handleCreate() {
  if (!form.name || !form.slug || !form.ownerId) {
    error.value = 'Name, slug, and owner are required.'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await mutation(createGql, {
      input: {
        name: form.name,
        slug: form.slug,
        description: form.description || null,
        ownerId: form.ownerId,
        visibility: form.visibility,
        contentType: form.contentType,
        defaultBranch: form.defaultBranch,
        initializeWithReadme: form.initializeWithReadme,
      },
    })
    showCreate.value = false
    if (form.ownerId && form.ownerId !== selectedOwner.value) {
      selectedOwner.value = form.ownerId
    }
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create repository'
  } finally {
    saving.value = false
  }
}

async function handleImport() {
  if (!importForm.name || !importForm.slug || !importForm.ownerId || !importForm.cloneUrl) {
    error.value = 'All fields are required.'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await mutation(importGql, {
      name: importForm.name,
      slug: importForm.slug,
      ownerId: importForm.ownerId,
      cloneUrl: importForm.cloneUrl,
    })
    showImport.value = false
    if (importForm.ownerId && importForm.ownerId !== selectedOwner.value) {
      selectedOwner.value = importForm.ownerId
    }
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to import repository'
  } finally {
    saving.value = false
  }
}

function onRowClick(row: RepoRow) {
  router.push(`/git/repositories/${row.id}`)
}

function menuItems(repo: RepoRow) {
  const items: Array<{ id: string; label: string; icon: string; danger?: boolean }> = [
    { id: 'archive', label: repo.archived ? 'Unarchive' : 'Archive', icon: 'archive' },
  ]
  if (!repo.archived) {
    items.push({ id: 'delete', label: 'Delete', icon: 'trash', danger: true })
  }
  return items
}

const archiveGql = gql`mutation ArchiveRepo($id: UUID!) { git { archiveRepository(id: $id) { id } } }`
const deleteGql = gql`mutation DeleteRepo($id: UUID!) { git { deleteRepository(id: $id) { id } } }`
const restoreGql = gql`mutation RestoreRepo($id: UUID!) { git { restoreRepository(id: $id) { id } } }`

async function onMenuSelect(id: string, repo: RepoRow) {
  try {
    if (id === 'archive') {
      if (repo.archived) {
        await mutation(restoreGql, { id: repo.id })
      } else {
        await mutation(archiveGql, { id: repo.id })
      }
    } else if (id === 'delete') {
      await mutation(deleteGql, { id: repo.id })
    }
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Operation failed'
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', 'Repositories')"
        title="Repositories"
        :subtitle="subtitle"
        :tabs="['All', 'Active', 'Archived']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #actions>
          <Select
            v-model="selectedOwner"
            :options="initialOwnerOption"
            :on-search="searchProfilesAndTrack"
            searchable
            placeholder="Select owner…"
            icon="user"
            size="sm"
            :accent="accent"
          />
          <Button
            icon="download"
            size="sm"
            :accent="accent"
            @click="openImport">Import</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Repository</Button>
        </template>
      </PageHeader>
    </template>

    <GlassTable
      :columns="columns"
      :rows="repos"
      row-key="id"
      :loading="isLoading"
      empty-text="No repositories found"
      @row-click="onRowClick"
    >
      <template #col-name="{ value, row }">
        <span class="repo-name-cell">
          <Icon :name="row.forkedFromId ? 'git-branch' : 'folder'" :size="14" :color="accent" />
          <span class="repo-name">{{ value }}</span>
          <span v-if="row.forkedFromId" class="fork-badge">fork</span>
          <Badge v-if="row.archived" color="#6c7388">Archived</Badge>
        </span>
      </template>
      <template #col-visibility="{ value }">
        <Badge :color="visibilityColors[value] || '#6c7388'">{{ value.toLowerCase() }}</Badge>
      </template>
      <template #col-contentType="{ value }">
        <span class="fg-2">{{ contentTypeLabel(value) }}</span>
      </template>
      <template #col-defaultBranch="{ value }">
        <span class="mono branch-name">{{ value }}</span>
      </template>
      <template #col-size="{ row }">
        <span class="mono">{{ formatSize(row.diskSizeBytes) }}</span>
      </template>
      <template #col-updated="{ row }">
        {{ formatDate(row.updated) }}
      </template>
      <template #actions="{ row }">
        <OverflowMenu :items="menuItems(row)" @select="onMenuSelect($event, row)">
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
      title="New Repository"
      icon="folder"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="my-project" />
        <TextInput
          v-model="form.slug"
          label="Slug"
          placeholder="my-project"
          mono />
        <TextInput v-model="form.description" label="Description" placeholder="Optional description" />
        <Select
          v-model="form.ownerId"
          :options="selectedOwnerOption"
          :on-search="searchProfilesAndTrack"
          searchable
          placeholder="Search profiles…"
          label="Owner" />
        <Select
          v-model="form.visibility"
          :options="visibilityOptions"
          label="Visibility"
          :accent="accent" />
        <Select
          v-model="form.contentType"
          :options="contentTypeOptions"
          label="Content Type"
          :accent="accent" />
        <TextInput
          v-model="form.defaultBranch"
          label="Default Branch"
          placeholder="main"
          mono />
        <label class="checkbox-row">
          <input v-model="form.initializeWithReadme" type="checkbox" >
          <span>Initialize with README</span>
        </label>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreate">
          {{ saving ? 'Creating…' : 'Create Repository' }}
        </Button>
      </template>
    </Modal>

    <!-- Import Modal -->
    <Modal
      v-if="showImport"
      title="Import Repository"
      icon="download"
      :accent="accent"
      @close="showImport = false">
      <div class="form-stack">
        <TextInput v-model="importForm.cloneUrl" label="Clone URL" placeholder="https://github.com/org/repo.git" />
        <TextInput v-model="importForm.name" label="Name" placeholder="my-project" />
        <TextInput
          v-model="importForm.slug"
          label="Slug"
          placeholder="my-project"
          mono />
        <Select
          v-model="importForm.ownerId"
          :options="selectedOwnerOption"
          :on-search="searchProfilesAndTrack"
          searchable
          placeholder="Search profiles…"
          label="Owner" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showImport = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleImport">
          {{ saving ? 'Importing…' : 'Import Repository' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.repo-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.repo-name {
  font-weight: 500;
}

.fork-badge {
  font-size: 10px;
  padding: 1px 5px;
  background: var(--bg-3);
  border-radius: 3px;
  color: var(--fg-3);
  font-weight: 600;
}

.branch-name {
  font-size: 12px;
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

.checkbox-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--fg-1);
  cursor: pointer;
}

.checkbox-row input {
  accent-color: v-bind(accent);
}

.row-menu-btn {
  background: none;
  border: none;
  padding: 4px;
  cursor: pointer;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.row-menu-btn:hover {
  background: var(--bg-3);
}

.fg-2 { color: var(--fg-2); }
</style>
