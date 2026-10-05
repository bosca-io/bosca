<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

// ── Namespaces ──────────────────────────────────────────────────────

interface Namespace { id: string; name: string }

const { data: nsData } = useAsyncQuery<{
  artifactsAdmin: { namespaces: Namespace[] }
}>('perm-artifact-ns', gql`
  query PermArtifactNs { artifactsAdmin { namespaces { id name } } }
`, {}, { server: false })

const namespaces = computed(() => nsData.value?.artifactsAdmin?.namespaces ?? [])
const selectedNamespaceId = ref<string | undefined>(undefined)

const namespaceOptions = computed<SelectOption[]>(() =>
  namespaces.value.map((ns) => ({ value: ns.id, label: ns.name })),
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
  const id = selectedNamespaceId.value
  if (!id) { permissions.value = []; return }
  loadingPerms.value = true
  try {
    const result = await gqlQuery<{
      artifactsAdmin: { namespace: { id: string; permissions: PermissionEntry[] } | null }
    }>(gql`
      query NsPerms($id: UUID!) {
        artifactsAdmin { namespace(id: $id) { id permissions { groupId action } } }
      }
    `, { id })
    permissions.value = result.artifactsAdmin?.namespace?.permissions ?? []
  } catch {
    permissions.value = []
  } finally {
    loadingPerms.value = false
  }
}

watch(selectedNamespaceId, () => loadPermissions())

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
  if (!selectedNamespaceId.value || !addGroupId.value || !addAction.value) return
  adding.value = true
  try {
    await gqlMutation(gql`
      mutation AddArtifactPerm($permission: PermissionInput!) {
        artifactsAdmin { addPermission(permission: $permission) }
      }
    `, {
      permission: {
        entityId: selectedNamespaceId.value,
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
  if (!p || !selectedNamespaceId.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(gql`
      mutation RemArtifactPerm($permission: PermissionInput!) {
        artifactsAdmin { removePermission(permission: $permission) }
      }
    `, {
      permission: {
        entityId: selectedNamespaceId.value,
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
        :breadcrumb="buildBreadcrumb('Artifacts', 'Settings', 'Permissions')"
        title="Permissions"
        subtitle="Manage access control for artifact namespaces"
      >
        <template #actions>
          <Select
            v-model="selectedNamespaceId"
            placeholder="Select namespace..."
            :options="namespaceOptions"
            :accent="accent"
            size="sm"
          />
          <Button
            v-if="selectedNamespaceId"
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
      v-if="selectedNamespaceId"
      title="Permissions"
      :subtitle="loadingPerms ? 'Loading...' : `${permissions.length} permission${permissions.length === 1 ? '' : 's'}`"
    >
      <GlassTable
        :columns="columns"
        :rows="permissions"
        :loading="loadingPerms"
        empty-text="No permissions configured. Admin and SA groups have implicit access."
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

    <div v-else class="empty-centered">
      Select a namespace to manage its permissions.
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
