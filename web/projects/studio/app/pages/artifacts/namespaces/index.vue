<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const namespacesGql = gql`
  query GetArtifactNamespaces {
    artifactsAdmin {
      namespaces {
        id
        name
        public
        created
        repositories {
          id
          type
        }
      }
    }
  }
`

const createNamespaceGql = gql`
  mutation CreateArtifactNamespace($name: String!, $public: Boolean) {
    artifactsAdmin {
      createNamespace(name: $name, public: $public) {
        id
        name
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

interface Namespace {
  id: string
  name: string
  public: boolean
  created: string
  repositories: { id: string; type: string }[]
}

const { data, status, refresh } = useAsyncQuery<{
  artifactsAdmin: { namespaces: Namespace[] }
}>('artifact-namespaces', namespacesGql, {}, { server: false })

const namespaces = computed(() => data.value?.artifactsAdmin?.namespaces ?? [])
const isLoading = computed(() => status.value === 'pending')

const showCreate = ref(false)
const createName = ref('')
const createPublic = ref(false)
const createLoading = ref(false)

async function createNamespace() {
  if (!createName.value.trim()) return
  createLoading.value = true
  try {
    await gqlMutation(createNamespaceGql, { name: createName.value.trim(), public: createPublic.value })
    toast.success('Namespace created')
    showCreate.value = false
    createName.value = ''
    createPublic.value = false
    refresh()
  } catch { toast.error('Failed to create namespace') }
  finally { createLoading.value = false }
}

const deleteTarget = ref<Namespace | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteNamespaceGql, { id: deleteTarget.value.id })
    deleteTarget.value = null
    toast.success('Namespace deleted')
    refresh()
  } catch { toast.error('Failed to delete namespace') }
  finally { deleteLoading.value = false }
}

function repoCount(repos: { type: string }[], type: string) {
  return repos.filter(r => r.type === type).length
}

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 2fr)' },
  { key: 'visibility', label: 'Visibility', width: '100px' },
  { key: 'repos', label: 'Repositories', width: 'minmax(140px, 1fr)' },
  { key: 'created', label: 'Created', width: '160px', muted: true },
]

function getRowActions(): OverflowMenuItem[] {
  return [
    { id: 'open', label: 'View', icon: 'eye' },
    { id: 'copy', label: 'Copy ID', icon: 'copy' },
    { id: 'sep', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onRowAction(action: string, row: Namespace) {
  if (action === 'open') router.push(`/artifacts/namespaces/${row.id}`)
  else if (action === 'copy') { navigator.clipboard.writeText(row.id); toast.success('ID copied') }
  else if (action === 'delete') deleteTarget.value = row
}

function formatDate(d: string) {
  if (!d) return '—'
  return new Date(d).toLocaleString()
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Artifacts', 'Namespaces')"
        title="Namespaces"
        :subtitle="`${namespaces.length} namespaces`">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreate = true">New Namespace</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Namespaces">
      <GlassTable
        :columns="columns"
        :rows="namespaces"
        :loading="isLoading && namespaces.length === 0"
        empty-text="No namespaces. Create one to start organizing artifact repositories."
        :row-actions="getRowActions"
        arrow
        @row-click="(row: any) => router.push(`/artifacts/namespaces/${row.id}`)"
        @row-action="({ action, row }) => onRowAction(action, row as Namespace)">
        <template #col-name="{ row }"><span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span></template>
        <template #col-visibility="{ row }">
          <span class="visibility-badge" :class="row.public ? 'public' : 'private'">{{ row.public ? 'Public' : 'Private' }}</span>
        </template>
        <template #col-repos="{ row }">
          <span class="repo-counts">
            <span v-if="repoCount(row.repositories, 'docker')" class="repo-tag docker">{{ repoCount(row.repositories, 'docker') }} docker</span>
            <span v-if="repoCount(row.repositories, 'helm')" class="repo-tag helm">{{ repoCount(row.repositories, 'helm') }} helm</span>
            <span v-if="repoCount(row.repositories, 'maven')" class="repo-tag maven">{{ repoCount(row.repositories, 'maven') }} maven</span>
            <span v-if="repoCount(row.repositories, 'npm')" class="repo-tag npm">{{ repoCount(row.repositories, 'npm') }} npm</span>
            <span v-if="repoCount(row.repositories, 'raw')" class="repo-tag raw">{{ repoCount(row.repositories, 'raw') }} raw</span>
            <span v-if="repoCount(row.repositories, 'ml')" class="repo-tag ml">{{ repoCount(row.repositories, 'ml') }} ml</span>
            <span v-if="row.repositories.length === 0" class="empty-repos">None</span>
          </span>
        </template>
        <template #col-created="{ row }">{{ formatDate(row.created) }}</template>
      </GlassTable>
    </SectionCard>

    <!-- Create Namespace Modal -->
    <Teleport v-if="showCreate" to="body">
      <div class="modal-backdrop" @click="showCreate = false">
        <div class="form-box" @click.stop>
          <div class="form-header">
            <div class="form-title">Create Namespace</div>
            <div class="form-subtitle">Namespaces group related artifact repositories</div>
          </div>
          <div class="form-body">
            <label class="form-label">
              <span class="label-text">Name</span>
              <TextInput
                v-model="createName"
                placeholder="e.g. library, com.acme, @scope"
                mono
                @keyup.enter="createNamespace" />
            </label>
            <Switch
              v-model="createPublic"
              :accent="accent"
              label="Public (allow anonymous pull access)" />
          </div>
          <div class="form-footer">
            <span class="spacer" />
            <Button size="sm" @click="showCreate = false">Cancel</Button>
            <Button
              primary
              size="sm"
              :accent="accent"
              :disabled="createLoading || !createName.trim()"
              @click="createNamespace">
              {{ createLoading ? 'Creating…' : 'Create' }}
            </Button>
          </div>
        </div>
      </div>
    </Teleport>

    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      subtitle="All repositories and their versions will be permanently removed."
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete" />
  </PageShell>
</template>

<style scoped>
.visibility-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: var(--r-xs);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.visibility-badge.public {
  color: #34d99a;
  background: color-mix(in oklch, #34d99a 12%, transparent);
}
.visibility-badge.private {
  color: var(--fg-3);
  background: color-mix(in oklch, var(--fg-3) 10%, transparent);
}
.repo-counts { display: flex; gap: 6px; flex-wrap: wrap; }
.repo-tag {
  font-size: 11px;
  font-weight: 500;
  padding: 1px 6px;
  border-radius: var(--r-xs);
}
.repo-tag.docker { color: #5ec5ff; background: color-mix(in oklch, #5ec5ff 12%, transparent); }
.repo-tag.helm { color: #0f7fff; background: color-mix(in oklch, #0f7fff 12%, transparent); }
.repo-tag.maven { color: #ffb547; background: color-mix(in oklch, #ffb547 12%, transparent); }
.repo-tag.npm { color: #34d99a; background: color-mix(in oklch, #34d99a 12%, transparent); }
.repo-tag.raw { color: #c084fc; background: color-mix(in oklch, #c084fc 12%, transparent); }
.repo-tag.ml { color: #f472b6; background: color-mix(in oklch, #f472b6 12%, transparent); }
.empty-repos { font-size: 12px; color: var(--fg-4); }

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
