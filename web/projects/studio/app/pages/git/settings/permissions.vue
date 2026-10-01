<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'
import { useAuth } from '@bosca/auth-client-browser'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const { searchProfiles } = useProfileSearch()
const toast = useToast()
const { profile } = import.meta.client ? useAuth() : { profile: ref(null) }

// ── Owner & Repository selection ────────────────────────────────────

const { save: saveLastOwner, load: loadLastOwner } = useLastGitOwner()
const savedOwner = loadLastOwner()
const selectedOwner = ref(savedOwner?.id ?? '')
const knownProfiles = ref<Map<string, string>>(new Map())
if (savedOwner) knownProfiles.value.set(savedOwner.id, savedOwner.label)

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
  for (const opt of results) knownProfiles.value.set(opt.value, opt.label)
  return results
}

watch(() => profile.value?.id, (id) => {
  if (id && !selectedOwner.value) selectedOwner.value = id
}, { immediate: true })

watch(selectedOwner, (id) => {
  if (!id) return
  const label = knownProfiles.value.get(id) ?? id
  saveLastOwner({ id, label })
  selectedRepoId.value = undefined
  permissions.value = []
})

const selectedRepoId = ref<string | undefined>(undefined)

const reposGql = gql`
  query PermRepos($ownerId: UUID!) {
    git { repositories(ownerId: $ownerId, includeArchived: true) { id name slug } }
  }
`

const { data: reposData, status: reposStatus } = useAsyncQuery<{
  git: { repositories: Array<{ id: string; name: string; slug: string }> }
}>('perm-git-repos', reposGql, { ownerId: computed(() => selectedOwner.value || '') }, { server: false })

const repos = computed(() => reposData.value?.git?.repositories ?? [])
const reposLoading = computed(() => reposStatus.value === 'pending')

const repoOptions = computed<SelectOption[]>(() =>
  repos.value.map((r) => ({ value: r.id, label: r.name })),
)

// ── Security Groups ─────────────────────────────────────────────────

interface GroupItem { id: string; name: string; description: string | null }

const groups = ref<GroupItem[]>([])

onMounted(async () => {
  try {
    const result = await gqlQuery<{
      security: { groups: { all: GroupItem[] } }
    }>(gql`
      query PermGroups { security { groups { all(offset: 0, limit: 100) { id name description } } } }
    `, {})
    groups.value = result.security?.groups?.all ?? []
  } catch { /* ignore */ }
})

const groupOptions = computed<SelectOption[]>(() =>
  groups.value.map((g) => ({ value: g.id, label: g.name })),
)

function groupName(groupId: string): string {
  return groups.value.find((g) => g.id === groupId)?.name ?? groupId.slice(0, 8)
}

// ── Permissions ─────────────────────────────────────────────────────

interface PermissionEntry { groupId: string; action: string }

const permissions = ref<PermissionEntry[]>([])
const loadingPerms = ref(false)

async function loadPermissions() {
  const id = selectedRepoId.value
  if (!id) { permissions.value = []; return }
  loadingPerms.value = true
  try {
    const result = await gqlQuery<{
      git: { repositoryById: { id: string; permissions: PermissionEntry[] } | null }
    }>(gql`
      query RepoPerms($id: UUID!) {
        git { repositoryById(id: $id) { id permissions { groupId action } } }
      }
    `, { id })
    permissions.value = result.git?.repositoryById?.permissions ?? []
  } catch {
    permissions.value = []
  } finally {
    loadingPerms.value = false
  }
}

watch(selectedRepoId, () => loadPermissions())

// ── Table ────────────────────────────────────────────────────────────

const columns: GlassTableColumn[] = [
  { key: 'group', label: 'Group', width: 'minmax(140px, 2fr)' },
  { key: 'action', label: 'Action', width: '120px' },
]

const actionOptions: SelectOption[] = [
  { value: 'VIEW', label: 'VIEW' },
  { value: 'EDIT', label: 'EDIT' },
  { value: 'DELETE', label: 'DELETE' },
  { value: 'MANAGE', label: 'MANAGE' },
  { value: 'LIST', label: 'LIST' },
]

const actionColors: Record<string, string> = {
  VIEW: '#5ec5ff',
  EDIT: '#4ade80',
  DELETE: '#ffb547',
  MANAGE: '#ff5d6c',
  LIST: '#6c7388',
}

// ── Add Permission ──────────────────────────────────────────────────

const showAdd = ref(false)
const addGroupId = ref<string | undefined>(undefined)
const addAction = ref<string | undefined>('VIEW')
const adding = ref(false)

async function handleAdd() {
  if (!selectedRepoId.value || !addGroupId.value || !addAction.value) return
  adding.value = true
  try {
    await gqlMutation(gql`
      mutation AddGitPerm($permission: PermissionInput!) {
        git { addPermission(permission: $permission) }
      }
    `, {
      permission: {
        entityId: selectedRepoId.value,
        groupId: addGroupId.value,
        action: addAction.value,
      },
    })
    showAdd.value = false
    toast.success('Permission added')
    await loadPermissions()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to add permission')
  } finally {
    adding.value = false
  }
}

// ── Remove Permission ───────────────────────────────────────────────

const deleteTarget = ref<PermissionEntry | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  const p = deleteTarget.value
  if (!p || !selectedRepoId.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(gql`
      mutation RemGitPerm($permission: PermissionInput!) {
        git { removePermission(permission: $permission) }
      }
    `, {
      permission: {
        entityId: selectedRepoId.value,
        groupId: p.groupId,
        action: p.action,
      },
    })
    deleteTarget.value = null
    toast.success('Permission removed')
    await loadPermissions()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to remove permission')
  } finally {
    deleteLoading.value = false
  }
}

function handleRowAction({ action, row }: { action: string; row: PermissionEntry }) {
  if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', 'Settings', 'Permissions')"
        title="Permissions"
        subtitle="Manage access control for git repositories"
      >
        <template #actions>
          <Select
            v-model="selectedOwner"
            :options="initialOwnerOption"
            placeholder="Owner"
            searchable
            :search-fn="searchProfilesAndTrack"
            :accent="accent"
            size="sm"
          />
          <Select
            v-if="repoOptions.length"
            v-model="selectedRepoId"
            placeholder="Select repository..."
            :options="repoOptions"
            :accent="accent"
            size="sm"
          />
          <Button
            v-if="selectedRepoId"
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showAdd = true"
          >
            Add Permission
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard
      v-if="selectedRepoId"
      title="Permissions"
      :subtitle="loadingPerms ? 'Loading...' : `${permissions.length} permission${permissions.length === 1 ? '' : 's'}`"
    >
      <GlassTable
        :columns="columns"
        :rows="permissions"
        :loading="loadingPerms"
        empty-text="No permissions configured. Add a group below to grant access."
        :row-actions="() => [
          { id: 'delete', label: 'Remove', icon: 'x', danger: true },
        ]"
        @row-action="handleRowAction"
      >
        <template #col-group="{ row }">
          <span class="perm-group">{{ groupName(row.groupId) }}</span>
        </template>
        <template #col-action="{ row }">
          <Badge :color="actionColors[row.action] ?? 'var(--fg-3)'">{{ row.action }}</Badge>
        </template>
      </GlassTable>
    </SectionCard>

    <div v-else-if="!selectedOwner" class="empty-centered">
      Select an owner to browse repositories.
    </div>
    <div v-else-if="reposLoading" class="empty-centered">
      Loading repositories...
    </div>
    <div v-else-if="!repoOptions.length" class="empty-centered">
      No repositories found for this owner.
    </div>
    <div v-else class="empty-centered">
      Select a repository to manage its permissions.
    </div>

    <!-- Add Modal -->
    <Modal
      v-if="showAdd"
      title="Add Permission"
      icon="plus"
      :accent="accent"
      @close="showAdd = false"
    >
      <div class="form-stack">
        <Select
          v-model="addGroupId"
          label="Group"
          :options="groupOptions"
          searchable
          :accent="accent"
        />
        <Select
          v-model="addAction"
          label="Action"
          :options="actionOptions"
          :accent="accent"
        />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showAdd = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!addGroupId || !addAction || adding"
          @click="handleAdd"
        >
          Add
        </Button>
      </template>
    </Modal>

    <!-- Delete Confirmation -->
    <ConfirmModal
      v-if="deleteTarget"
      :title="`Remove ${deleteTarget.action} from ${groupName(deleteTarget.groupId)}?`"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.form-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.spacer {
  flex: 1;
}

.empty-centered {
  padding: 64px;
  text-align: center;
  font-size: 13px;
  color: var(--fg-3);
}

.perm-group {
  font-weight: 500;
  color: var(--fg-0);
}
</style>
