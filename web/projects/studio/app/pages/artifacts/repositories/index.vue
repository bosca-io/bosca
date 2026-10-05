<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const { stage: stageRawArtifactFiles } = useRawArtifactUploadStaging()

const reposGql = gql`
  query GetArtifactRepositories {
    artifactsAdmin {
      repositories {
        id
        namespaceId
        name
        type
        created
        modified
        namespace {
          id
          name
        }
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

interface Repo {
  id: string
  namespaceId: string
  name: string
  type: string
  created: string
  modified: string
  namespace: { id: string; name: string } | null
}

const { data, status, refresh } = useAsyncQuery<{
  artifactsAdmin: { repositories: Repo[] }
}>('artifact-repositories', reposGql, {}, { server: false })

const repos = computed(() => data.value?.artifactsAdmin?.repositories ?? [])
const isLoading = computed(() => status.value === 'pending')

const typeFilter = ref('all')
const filteredRepos = computed(() => {
  if (typeFilter.value === 'all') return repos.value
  return repos.value.filter(r => r.type === typeFilter.value)
})

const deleteTarget = ref<Repo | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteRepoGql, { id: deleteTarget.value.id })
    deleteTarget.value = null
    toast.success('Repository deleted')
    refresh()
  } catch { toast.error('Failed to delete repository') }
  finally { deleteLoading.value = false }
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
  { key: 'path', label: 'Repository', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Type', width: '100px' },
  { key: 'created', label: 'Created', width: '160px', muted: true },
  { key: 'modified', label: 'Modified', width: '160px', muted: true },
]

function getRowActions(): OverflowMenuItem[] {
  return [
    { id: 'open', label: 'View', icon: 'eye' },
    { id: 'copy', label: 'Copy ID', icon: 'copy' },
    { id: 'namespace', label: 'Go to Namespace', icon: 'folder' },
    { id: 'sep', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onRowAction(action: string, row: Repo) {
  if (action === 'open') router.push(`/artifacts/repositories/${row.id}`)
  else if (action === 'copy') { navigator.clipboard.writeText(row.id); toast.success('ID copied') }
  else if (action === 'namespace' && row.namespace) router.push(`/artifacts/namespaces/${row.namespace.id}`)
  else if (action === 'delete') deleteTarget.value = row
}

async function startRawUpload(files: File[]) {
  stageRawArtifactFiles(files)
  await router.push('/artifacts/repositories/upload')
}

const filterOptions = [
  { value: 'all', label: 'All types' },
  { value: 'docker', label: 'Docker' },
  { value: 'helm', label: 'Helm' },
  { value: 'maven', label: 'Maven' },
  { value: 'npm', label: 'npm' },
  { value: 'raw', label: 'Raw' },
  { value: 'ml', label: 'ML' },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Artifacts', 'Repositories')"
        title="Repositories"
        :subtitle="`${filteredRepos.length} repositories`">
        <template #actions>
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
      </PageHeader>
    </template>

    <SectionCard
      title="Upload Raw Artifacts"
      subtitle="Drop files to open the raw artifact upload form"
      padded
    >
      <RawArtifactDropZone @files="startRawUpload" />
    </SectionCard>

    <SectionCard title="All Repositories">
      <GlassTable
        :columns="columns"
        :rows="filteredRepos"
        :loading="isLoading && repos.length === 0"
        :empty-text="typeFilter !== 'all' ? 'No repositories of this type.' : 'No repositories. Create one from a namespace.'"
        :row-actions="getRowActions"
        arrow
        @row-click="(row: any) => router.push(`/artifacts/repositories/${row.id}`)"
        @row-action="({ action, row }) => onRowAction(action, row as Repo)">
        <template #col-path="{ row }">
          <span class="repo-path">
            <span class="ns-part">{{ row.namespace?.name ?? '?' }}/</span>
            <span class="repo-part">{{ row.name }}</span>
          </span>
        </template>
        <template #col-type="{ row }">
          <span class="type-badge" :style="{ color: typeColors[row.type] || 'var(--fg-2)', background: `color-mix(in oklch, ${typeColors[row.type] || 'var(--fg-3)'} 12%, transparent)` }">{{ row.type }}</span>
        </template>
        <template #col-created="{ row }">{{ formatDate(row.created) }}</template>
        <template #col-modified="{ row }">{{ formatDate(row.modified) }}</template>
      </GlassTable>
    </SectionCard>

    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.namespace?.name}/${deleteTarget.name}'?`"
      subtitle="All versions and blobs will be permanently removed."
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete" />
  </PageShell>
</template>

<style scoped>
.filter-row { display: flex; gap: 2px; }
.filter-btn {
  font-size: 11.5px; font-weight: 500; padding: 3px 10px; border-radius: var(--r-xs);
  color: var(--fg-3); background: transparent; cursor: pointer; transition: all 0.15s;
}
.filter-btn:hover { color: var(--fg-1); background: color-mix(in oklch, var(--fg-3) 8%, transparent); }
.filter-btn.active { color: var(--fg-0); background: color-mix(in oklch, var(--fg-3) 14%, transparent); }

.repo-path { font-size: 13px; }
.ns-part { color: var(--fg-3); }
.repo-part { font-weight: 500; color: var(--fg-0); }

.type-badge {
  font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: var(--r-xs);
  text-transform: capitalize;
}
</style>
