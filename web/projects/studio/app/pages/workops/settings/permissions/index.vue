<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

// ── Queries ──────────────────────────────────────────────────────────

const groupsGql = gql`
  query PermGroups {
    security { groups { all(offset: 0, limit: 100) { id name description } } }
  }
`

const entitiesGql = gql`
  query PermEntities {
    workOps {
      portfolios { all { id key name } }
      programs { all { id key name } }
      projects { all { id key name } }
    }
  }
`

const portfolioPermsGql = gql`
  query PortfolioPerms($id: UUID!) {
    workOps { portfolios { portfolio(id: $id) { id permissions { groupId action } } } }
  }
`

const programPermsGql = gql`
  query ProgramPerms($id: UUID!) {
    workOps { programs { program(id: $id) { id permissions { groupId action } } } }
  }
`

const projectPermsGql = gql`
  query ProjectPerms($id: UUID!) {
    workOps { projects { project(id: $id) { id permissions { groupId action } } } }
  }
`

// ── Data fetching ────────────────────────────────────────────────────

interface GroupItem { id: string; name: string; description: string | null }
interface EntityItem { id: string; key: string; name: string }

const { data: groupsData } = useAsyncQuery<{
  security: { groups: { all: GroupItem[] } }
}>('perm-groups', groupsGql, {})

const { data: entitiesData } = useAsyncQuery<{
  workOps: { portfolios: { all: EntityItem[] }; programs: { all: EntityItem[] }; projects: { all: EntityItem[] } }
}>('perm-entities', entitiesGql, {})

const groups = computed(() => groupsData.value?.security?.groups?.all ?? [])
const groupOptions = computed<SelectOption[]>(() =>
  groups.value.map((g) => ({ value: g.id, label: g.name })),
)

const entityTypes = ['Portfolio', 'Program', 'Project'] as const
const entityTypeOptions: SelectOption[] = entityTypes.map(t => ({ value: t, label: t }))

const selectedEntityType = ref<string>('Portfolio')
const selectedEntityId = ref<string | undefined>(undefined)

const entities = computed(() => {
  switch (selectedEntityType.value) {
    case 'Portfolio': return entitiesData.value?.workOps?.portfolios?.all ?? []
    case 'Program': return entitiesData.value?.workOps?.programs?.all ?? []
    case 'Project': return entitiesData.value?.workOps?.projects?.all ?? []
    default: return []
  }
})

const entityOptions = computed<SelectOption[]>(() =>
  entities.value.map((e) => ({ value: e.id, label: `${e.key} - ${e.name}` })),
)

watch(selectedEntityType, () => {
  selectedEntityId.value = undefined
  permissions.value = []
})

// ── Permissions ─────────────────────────────────────────────────────

interface PermissionEntry { groupId: string; action: string }

const permissions = ref<PermissionEntry[]>([])
const loadingPerms = ref(false)

async function loadPermissions() {
  const id = selectedEntityId.value
  if (!id) { permissions.value = []; return }
  loadingPerms.value = true
  try {
    let queryDoc
    let path: string
    if (selectedEntityType.value === 'Portfolio') {
      queryDoc = portfolioPermsGql
      path = 'portfolios.portfolio'
    } else if (selectedEntityType.value === 'Program') {
      queryDoc = programPermsGql
      path = 'programs.program'
    } else {
      queryDoc = projectPermsGql
      path = 'projects.project'
    }
    const result = await gqlQuery<Record<string, unknown>>(queryDoc, { id })
    const parts = path.split('.')
    let node: unknown = (result as Record<string, unknown>).workOps
    for (const p of parts) node = (node as Record<string, unknown> | undefined)?.[p]
    permissions.value = ((node as Record<string, unknown> | undefined)?.permissions as PermissionEntry[] | undefined) ?? []
  } catch {
    permissions.value = []
  } finally {
    loadingPerms.value = false
  }
}

watch(selectedEntityId, () => loadPermissions())

function groupName(groupId: string): string {
  return groups.value.find((g) => g.id === groupId)?.name ?? groupId.slice(0, 8)
}

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
  if (!selectedEntityId.value || !addGroupId.value || !addAction.value) return
  adding.value = true
  try {
    let mutationStr: string
    if (selectedEntityType.value === 'Portfolio') {
      mutationStr = 'mutation AddPerm($id: UUID!, $groupId: UUID!, $action: PermissionAction!) { workOps { portfolios { addPermission(id: $id, groupId: $groupId, action: $action) } } }'
    } else if (selectedEntityType.value === 'Program') {
      mutationStr = 'mutation AddPerm($id: UUID!, $groupId: UUID!, $action: PermissionAction!) { workOps { programs { addPermission(id: $id, groupId: $groupId, action: $action) } } }'
    } else {
      mutationStr = 'mutation AddPerm($id: UUID!, $groupId: UUID!, $action: PermissionAction!) { workOps { projects { addPermission(id: $id, groupId: $groupId, action: $action) } } }'
    }
    await gqlMutation(gql(mutationStr), {
      id: selectedEntityId.value,
      groupId: addGroupId.value,
      action: addAction.value,
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
  if (!p || !selectedEntityId.value) return
  deleteLoading.value = true
  try {
    let mutationStr: string
    if (selectedEntityType.value === 'Portfolio') {
      mutationStr = 'mutation RemPerm($id: UUID!, $groupId: UUID!, $action: PermissionAction!) { workOps { portfolios { removePermission(id: $id, groupId: $groupId, action: $action) } } }'
    } else if (selectedEntityType.value === 'Program') {
      mutationStr = 'mutation RemPerm($id: UUID!, $groupId: UUID!, $action: PermissionAction!) { workOps { programs { removePermission(id: $id, groupId: $groupId, action: $action) } } }'
    } else {
      mutationStr = 'mutation RemPerm($id: UUID!, $groupId: UUID!, $action: PermissionAction!) { workOps { projects { removePermission(id: $id, groupId: $groupId, action: $action) } } }'
    }
    await gqlMutation(gql(mutationStr), {
      id: selectedEntityId.value,
      groupId: p.groupId,
      action: p.action,
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
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'Permissions')"
        title="Permissions"
        subtitle="Manage access control for portfolios, programs, and projects"
      >
        <template #actions>
          <Select v-model="selectedEntityType" :options="entityTypeOptions" :accent="accent" />
          <Select
            v-if="entityOptions.length"
            v-model="selectedEntityId"
            placeholder="Select entity..."
            :options="entityOptions"
            :accent="accent"
          />
          <Button
            v-if="selectedEntityId"
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
      v-if="selectedEntityId"
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
      Select a {{ selectedEntityType.toLowerCase() }} to manage its permissions.
    </div>

    <!-- Add Modal -->
    <Modal
      v-if="showAdd"
      title="Add Permission"
      icon="plus"
      :accent="accent"
      @close="showAdd = false">
      <div class="form-stack">
        <Select
          v-model="addGroupId"
          label="Group"
          :options="groupOptions"
          searchable
          :accent="accent" />
        <Select
          v-model="addAction"
          label="Action"
          :options="actionOptions"
          :accent="accent" />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showAdd = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!addGroupId || !addAction || adding"
          @click="handleAdd">
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
