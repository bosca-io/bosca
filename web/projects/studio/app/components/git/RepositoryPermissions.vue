<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const props = defineProps<{ repositoryId: string }>()
const selectedRepoId = computed(() => props.repositoryId)
const error = ref('')

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
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load security groups.'
  }
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
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load permissions.'
  } finally {
    loadingPerms.value = false
  }
}

watch(selectedRepoId, () => loadPermissions(), { immediate: true })

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
  { value: 'EXECUTE', label: 'EXECUTE' },
]

const actionColors: Record<string, string> = {
  VIEW: '#5ec5ff',
  EDIT: '#4ade80',
  DELETE: '#ffb547',
  MANAGE: '#ff5d6c',
  LIST: '#6c7388',
  EXECUTE: '#a78bff',
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
  <div class="repository-settings-panel">
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <SectionCard
      title="Permissions"
      :subtitle="loadingPerms ? 'Loading...' : `${permissions.length} permission${permissions.length === 1 ? '' : 's'}`"
    >
      <template #right>
        <Button
          primary
          icon="plus"
          size="sm"
          :accent="accent"
          @click="showAdd = true">Add Permission</Button>
      </template>
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
  </div>
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
