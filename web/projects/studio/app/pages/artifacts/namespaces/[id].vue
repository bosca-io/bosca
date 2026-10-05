<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const namespaceId = computed(() => route.params.id as string)

const namespaceGql = gql`
  query GetArtifactNamespace($id: UUID!, $type: ArtifactRegistryType, $limit: Int!, $offset: Long!) {
    artifactsAdmin {
      namespace(id: $id) {
        id
        name
        public
        created
        repositoryCount(type: $type)
        totalRepositoryCount: repositoryCount
        repositories(type: $type, limit: $limit, offset: $offset) {
          id
          namespaceId
          name
          type
          created
          modified
        }
      }
    }
  }
`

const createRepoGql = gql`
  mutation CreateArtifactRepository($namespaceId: UUID!, $name: String!, $type: ArtifactRegistryType!) {
    artifactsAdmin {
      createRepository(namespaceId: $namespaceId, name: $name, type: $type) {
        id
        name
        type
      }
    }
  }
`

const deleteRepoGql = gql`
  mutation DeleteArtifactRepository($id: UUID!) {
    artifactsAdmin {
      deleteRepository(id: $id)
    }
  }
`

const updateNamespaceGql = gql`
  mutation UpdateArtifactNamespace($id: UUID!, $public: Boolean!) {
    artifactsAdmin {
      updateNamespace(id: $id, public: $public) {
        id
        public
      }
    }
  }
`

const deleteNamespaceGql = gql`
  mutation DeleteArtifactNamespace($id: UUID!) {
    artifactsAdmin {
      deleteNamespace(id: $id)
    }
  }
`

interface Repo { id: string; namespaceId: string; name: string; type: string; created: string; modified: string }
interface Namespace {
  id: string
  name: string
  public: boolean
  created: string
  repositoryCount: number
  totalRepositoryCount: number
  repositories: Repo[]
}

const typeFilter = ref('all')
const limit = ref(15)
const offset = ref(0)
const typeVar = computed(() => (typeFilter.value === 'all' ? null : typeFilter.value))

watch(typeFilter, () => { offset.value = 0 })

const { data, status, refresh } = useAsyncQuery<{
  artifactsAdmin: { namespace: Namespace | null }
}>('artifact-namespace-detail', namespaceGql, { id: namespaceId, type: typeVar, limit, offset }, { server: false })

const ns = computed(() => data.value?.artifactsAdmin?.namespace)
const repos = computed(() => ns.value?.repositories ?? [])
const isLoading = computed(() => status.value === 'pending')

const repoCount = computed(() => ns.value?.repositoryCount ?? 0)
const totalPages = computed(() => Math.ceil(repoCount.value / limit.value))
const currentPage = computed(() => Math.floor(offset.value / limit.value) + 1)

function goToPage(page: number) {
  const clamped = Math.max(1, Math.min(page, totalPages.value))
  offset.value = (clamped - 1) * limit.value
}

// Deleting the last repository on the final page leaves the offset past the end;
// snap back to the new last page.
watch(repoCount, count => {
  if (count > 0 && offset.value >= count) goToPage(totalPages.value)
})

const showCreateRepo = ref(false)
const newRepoName = ref('')
const newRepoType = ref('docker')
const createRepoLoading = ref(false)

async function createRepo() {
  if (!newRepoName.value.trim()) return
  createRepoLoading.value = true
  try {
    await gqlMutation(createRepoGql, {
      namespaceId: namespaceId.value,
      name: newRepoName.value.trim(),
      type: newRepoType.value,
    })
    toast.success('Repository created')
    showCreateRepo.value = false
    newRepoName.value = ''
    newRepoType.value = 'docker'
    refresh()
  } catch { toast.error('Failed to create repository') }
  finally { createRepoLoading.value = false }
}

const deleteRepoTarget = ref<Repo | null>(null)
const deleteRepoLoading = ref(false)

async function confirmDeleteRepo() {
  if (!deleteRepoTarget.value) return
  deleteRepoLoading.value = true
  try {
    await gqlMutation(deleteRepoGql, { id: deleteRepoTarget.value.id })
    deleteRepoTarget.value = null
    toast.success('Repository deleted')
    refresh()
  } catch { toast.error('Failed to delete repository') }
  finally { deleteRepoLoading.value = false }
}

const updatingPublic = ref(false)

async function setPublic(value: boolean) {
  if (updatingPublic.value) return
  updatingPublic.value = true
  try {
    await gqlMutation(updateNamespaceGql, { id: namespaceId.value, public: value })
    toast.success(value ? 'Namespace is now public (anonymous pull enabled)' : 'Namespace is now private')
    refresh()
  } catch { toast.error('Failed to update visibility') }
  finally { updatingPublic.value = false }
}

const deleteNsLoading = ref(false)
const showDeleteNs = ref(false)

async function confirmDeleteNs() {
  deleteNsLoading.value = true
  try {
    await gqlMutation(deleteNamespaceGql, { id: namespaceId.value })
    toast.success('Namespace deleted')
    router.push('/artifacts/namespaces')
  } catch { toast.error('Failed to delete namespace') }
  finally { deleteNsLoading.value = false }
}

function formatDate(d: string) {
  if (!d) return '—'
  return new Date(d).toLocaleString()
}

const typeColors: Record<string, string> = {
  docker: '#5ec5ff',
  helm: '#0f7fff',
  maven: '#ffb547',
  npm: '#34d99a',
  raw: '#c084fc',
  ml: '#f472b6',
}

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 2fr)' },
  { key: 'type', label: 'Type', width: '100px' },
  { key: 'created', label: 'Created', width: '160px', muted: true },
  { key: 'modified', label: 'Modified', width: '160px', muted: true },
]

function getRowActions(): OverflowMenuItem[] {
  return [
    { id: 'open', label: 'View', icon: 'eye' },
    { id: 'copy', label: 'Copy ID', icon: 'copy' },
    { id: 'sep', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onRowAction(action: string, row: Repo) {
  if (action === 'open') router.push(`/artifacts/repositories/${row.id}`)
  else if (action === 'copy') { navigator.clipboard.writeText(row.id); toast.success('ID copied') }
  else if (action === 'delete') deleteRepoTarget.value = row
}

const repoTypes = [
  { value: 'docker', label: 'Docker' },
  { value: 'helm', label: 'Helm' },
  { value: 'maven', label: 'Maven' },
  { value: 'npm', label: 'npm' },
  { value: 'raw', label: 'Raw' },
  { value: 'ml', label: 'ML' },
]

const filterOptions = [
  { value: 'all', label: 'All types' },
  ...repoTypes,
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Artifacts', 'Namespaces', ns?.name ?? '…')"
        :title="ns?.name ?? 'Loading…'"
        :subtitle="ns ? `${ns.totalRepositoryCount} repositories` : undefined"
      >
        <template #actions>
          <Button
            size="sm"
            icon="plus"
            primary
            :accent="accent"
            @click="showCreateRepo = true">Add Repository</Button>
          <Button size="sm" icon="trash" @click="showDeleteNs = true">Delete</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !ns" class="loading-placeholder">
      <Icon name="refresh" :size="16" color="var(--fg-4)" />
      <span>Loading namespace…</span>
    </div>

    <div v-else-if="ns" class="content-stack">
      <SectionCard title="Details">
        <div class="detail-grid">
          <div class="detail-item">
            <span class="detail-label">Name</span>
            <span class="detail-value mono">{{ ns.name }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">ID</span>
            <span class="detail-value mono muted">{{ ns.id }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">Visibility</span>
            <div class="visibility-control">
              <span class="visibility-badge" :class="ns.public ? 'public' : 'private'">{{ ns.public ? 'Public' : 'Private' }}</span>
              <Switch
                :model-value="ns.public"
                :accent="accent"
                label="Allow anonymous pull"
                @update:model-value="setPublic" />
            </div>
          </div>
          <div class="detail-item">
            <span class="detail-label">Created</span>
            <span class="detail-value">{{ formatDate(ns.created) }}</span>
          </div>
        </div>
      </SectionCard>

      <SectionCard :title="`Repositories (${repoCount})`">
        <template #right>
          <div class="filter-row">
            <button
              v-for="opt in filterOptions"
              :key="opt.value"
              class="filter-btn"
              :class="{ active: typeFilter === opt.value }"
              @click="typeFilter = opt.value"
            >{{ opt.label }}</button>
          </div>
        </template>
        <GlassTable
          :columns="columns"
          :rows="repos"
          :loading="isLoading && repos.length === 0"
          :empty-text="typeFilter !== 'all' ? 'No repositories of this type.' : 'No repositories yet.'"
          :row-actions="getRowActions"
          arrow
          @row-click="(row: any) => router.push(`/artifacts/repositories/${row.id}`)"
          @row-action="({ action, row }) => onRowAction(action, row as Repo)">
          <template #col-name="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span></template>
          <template #col-type="{ row }">
            <span class="type-badge" :style="{ color: typeColors[row.type] || 'var(--fg-2)', background: `color-mix(in oklch, ${typeColors[row.type] || 'var(--fg-3)'} 12%, transparent)` }">{{ row.type }}</span>
          </template>
          <template #col-created="{ row }">{{ formatDate(row.created) }}</template>
          <template #col-modified="{ row }">{{ formatDate(row.modified) }}</template>
        </GlassTable>
        <Pagination
          v-if="totalPages > 1"
          :page="currentPage"
          :total-pages="totalPages"
          @prev="goToPage(currentPage - 1)"
          @next="goToPage(currentPage + 1)"
        />
      </SectionCard>
    </div>

    <div v-else class="loading-placeholder">
      <Icon name="alert" :size="16" color="var(--fg-4)" />
      <span>Namespace not found.</span>
    </div>

    <!-- Create Repository Modal -->
    <Teleport v-if="showCreateRepo" to="body">
      <div class="modal-backdrop" @click="showCreateRepo = false">
        <div class="form-box" @click.stop>
          <div class="form-header">
            <div class="form-title">Add Repository</div>
            <div class="form-subtitle">Repositories contain versioned artifacts</div>
          </div>
          <div class="form-body">
            <label class="form-label">
              <span class="label-text">Name</span>
              <TextInput
                v-model="newRepoName"
                placeholder="e.g. nginx, sdk, cli"
                mono
                @keyup.enter="createRepo" />
            </label>
            <label class="form-label">
              <span class="label-text">Type</span>
              <Select v-model="newRepoType" :options="repoTypes" placeholder="Select type" />
            </label>
          </div>
          <div class="form-footer">
            <span class="spacer" />
            <Button size="sm" @click="showCreateRepo = false">Cancel</Button>
            <Button
              primary
              size="sm"
              :accent="accent"
              :disabled="createRepoLoading || !newRepoName.trim()"
              @click="createRepo">
              {{ createRepoLoading ? 'Creating…' : 'Create' }}
            </Button>
          </div>
        </div>
      </div>
    </Teleport>

    <ConfirmModal
      v-if="deleteRepoTarget"
      :title="`Delete '${deleteRepoTarget.name}'?`"
      subtitle="All versions and blobs in this repository will be permanently removed."
      :loading="deleteRepoLoading"
      @close="deleteRepoTarget = null"
      @confirm="confirmDeleteRepo" />
    <ConfirmModal
      v-if="showDeleteNs"
      :title="`Delete '${ns?.name}'?`"
      subtitle="All repositories and their contents will be permanently removed."
      :loading="deleteNsLoading"
      @close="showDeleteNs = false"
      @confirm="confirmDeleteNs" />
  </PageShell>
</template>

<style scoped>
.content-stack { display: flex; flex-direction: column; gap: 14px; }
.loading-placeholder {
  display: flex; flex-direction: column; align-items: center; gap: 12px;
  padding: 64px; font-size: 13px; color: var(--fg-3);
}
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; padding: 16px; }
.detail-item { display: flex; flex-direction: column; gap: 3px; }
.detail-label { font-size: 11px; font-weight: 500; color: var(--fg-3); text-transform: uppercase; letter-spacing: 0.05em; }
.detail-value { font-size: 13px; color: var(--fg-1); }
.detail-value.mono { font-family: var(--font-mono, monospace); font-size: 12px; }
.detail-value.muted { color: var(--fg-3); }
.visibility-control { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.visibility-badge {
  display: inline-block; font-size: 11px; font-weight: 600; padding: 2px 8px;
  border-radius: var(--r-xs); text-transform: uppercase; letter-spacing: 0.04em; width: fit-content;
}
.visibility-badge.public { color: #34d99a; background: color-mix(in oklch, #34d99a 12%, transparent); }
.visibility-badge.private { color: var(--fg-3); background: color-mix(in oklch, var(--fg-3) 10%, transparent); }

.filter-row { display: flex; gap: 2px; }
.filter-btn {
  font-size: 11.5px; font-weight: 500; padding: 3px 10px; border-radius: var(--r-xs);
  color: var(--fg-3); background: transparent; cursor: pointer; transition: all 0.15s;
}
.filter-btn:hover { color: var(--fg-1); background: color-mix(in oklch, var(--fg-3) 8%, transparent); }
.filter-btn.active { color: var(--fg-0); background: color-mix(in oklch, var(--fg-3) 14%, transparent); }

.type-badge {
  font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: var(--r-xs);
  text-transform: capitalize;
}

.modal-backdrop {
  position: fixed; inset: 0; z-index: 9999;
  background: color-mix(in oklch, #000 55%, transparent);
  display: flex; align-items: center; justify-content: center; padding: 20px;
}
.form-box {
  width: min(440px, 100%);
  background: var(--bg-1); border: 1px solid var(--line); border-radius: var(--r-lg);
  box-shadow: 0 24px 60px -20px rgba(0,0,0,0.5);
  display: flex; flex-direction: column; overflow: hidden;
}
.form-header { padding: 14px 18px; border-bottom: 1px solid var(--line); }
.form-title { font-size: 14px; font-weight: 600; color: var(--fg-0); }
.form-subtitle { font-size: 11.5px; color: var(--fg-3); }
.form-body { padding: 18px; display: flex; flex-direction: column; gap: 14px; }
.form-label { display: flex; flex-direction: column; gap: 5px; }
.label-text { font-size: 12px; font-weight: 500; color: var(--fg-2); }
.form-footer {
  padding: 12px 18px; border-top: 1px solid var(--line); background: var(--bg-2);
  display: flex; align-items: center; gap: 10px;
}
.spacer { flex: 1; }
</style>
