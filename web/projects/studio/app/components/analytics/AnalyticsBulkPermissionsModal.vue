<script setup lang="ts">
import gql from 'graphql-tag'
import type { DocumentNode } from 'graphql'
import type { SelectOption } from '@bosca/ui'

/**
 * Grants or revokes a single group permission across many analytics entities at
 * once. Applies the existing per-entity addPermission/deletePermission mutations
 * independently (they are idempotent and re-check MANAGE per entity), so a
 * failure on one entity never blocks the rest — partial failures are reported.
 */

type BulkPermissionEntityType = 'queries' | 'dashboards' | 'visualizations'

interface EntityTypeConfig {
  noun: string
  nounPlural: string
  actions: SelectOption[]
  addGql: DocumentNode
  removeGql: DocumentNode
}

const BASE_ACTIONS: SelectOption[] = [
  { value: 'VIEW', label: 'VIEW' },
  { value: 'EDIT', label: 'EDIT' },
  { value: 'MANAGE', label: 'MANAGE' },
  { value: 'DELETE', label: 'DELETE' },
]

// Queries additionally support EXECUTE (running the query); dashboards and
// visualizations have no executable behavior so the action is omitted for them.
const QUERY_ACTIONS: SelectOption[] = [
  { value: 'VIEW', label: 'VIEW' },
  { value: 'EDIT', label: 'EDIT' },
  { value: 'EXECUTE', label: 'EXECUTE' },
  { value: 'MANAGE', label: 'MANAGE' },
  { value: 'DELETE', label: 'DELETE' },
]

const ENTITY_CONFIG: Record<BulkPermissionEntityType, EntityTypeConfig> = {
  queries: {
    noun: 'query',
    nounPlural: 'queries',
    actions: QUERY_ACTIONS,
    addGql: gql`
      mutation BulkAddAnalyticsQueryPermission($permission: PermissionInput!) {
        analytics { queries { addPermission(permission: $permission) { action groupId } } }
      }
    `,
    removeGql: gql`
      mutation BulkRemoveAnalyticsQueryPermission($permission: PermissionInput!) {
        analytics { queries { deletePermission(permission: $permission) { action groupId } } }
      }
    `,
  },
  dashboards: {
    noun: 'dashboard',
    nounPlural: 'dashboards',
    actions: BASE_ACTIONS,
    addGql: gql`
      mutation BulkAddAnalyticsDashboardPermission($permission: PermissionInput!) {
        analytics { dashboards { addPermission(permission: $permission) { action groupId } } }
      }
    `,
    removeGql: gql`
      mutation BulkRemoveAnalyticsDashboardPermission($permission: PermissionInput!) {
        analytics { dashboards { deletePermission(permission: $permission) { action groupId } } }
      }
    `,
  },
  visualizations: {
    noun: 'visualization',
    nounPlural: 'visualizations',
    actions: BASE_ACTIONS,
    addGql: gql`
      mutation BulkAddAnalyticsVisualizationPermission($permission: PermissionInput!) {
        analytics { visualizations { addPermission(permission: $permission) { action groupId } } }
      }
    `,
    removeGql: gql`
      mutation BulkRemoveAnalyticsVisualizationPermission($permission: PermissionInput!) {
        analytics { visualizations { deletePermission(permission: $permission) { action groupId } } }
      }
    `,
  },
}

const groupsGql = gql`
  query AnalyticsBulkPermissionGroups { security { groups { all(offset: 0, limit: 100) { id name description } } } }
`

const props = withDefaults(defineProps<{
  entityType: BulkPermissionEntityType
  entityIds: string[]
  accent?: string
}>(), { accent: '#5ec5ff' })

const emit = defineEmits<{ close: []; applied: [] }>()

const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const config = computed(() => ENTITY_CONFIG[props.entityType])
const countLabel = computed(() => {
  const c = props.entityIds.length
  return `${c} ${c === 1 ? config.value.noun : config.value.nounPlural}`
})

interface SecurityGroup { id: string; name: string; description: string | null }
const allGroups = ref<SecurityGroup[]>([])
const loadingGroups = ref(false)
const groupId = ref<string | undefined>(undefined)
const action = ref<string | undefined>('VIEW')
const applying = ref<'grant' | 'revoke' | null>(null)

const groupOptions = computed<SelectOption[]>(() =>
  allGroups.value.map(g => ({ value: g.id, label: g.name })),
)

onMounted(async () => {
  loadingGroups.value = true
  try {
    const result = await gqlQuery<{ security: { groups: { all: SecurityGroup[] } } }>(groupsGql, {})
    allGroups.value = result.security?.groups?.all ?? []
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to load groups')
  } finally {
    loadingGroups.value = false
  }
})

async function apply(mode: 'grant' | 'revoke') {
  const selectedGroup = groupId.value
  const selectedAction = action.value
  if (!selectedGroup || !selectedAction || props.entityIds.length === 0 || applying.value) return
  applying.value = mode
  const doc = mode === 'grant' ? config.value.addGql : config.value.removeGql
  try {
    const results = await Promise.allSettled(props.entityIds.map(entityId =>
      gqlMutation(doc, {
        permission: { action: selectedAction, entityId, groupId: selectedGroup },
      }),
    ))
    const failures = results.filter((r): r is PromiseRejectedResult => r.status === 'rejected')
    const succeeded = results.length - failures.length
    const verb = mode === 'grant' ? 'Granted' : 'Revoked'
    const preposition = mode === 'grant' ? 'to' : 'from'
    if (failures.length === 0) {
      toast.success(`${verb} ${selectedAction} ${preposition} ${countLabel.value}`)
      emit('applied')
      emit('close')
    } else {
      const reason = failures[0]?.reason
      const detail = reason instanceof Error ? `: ${reason.message}` : ''
      toast.error(`Failed for ${failures.length} of ${results.length} ${config.value.nounPlural}${detail}`)
      if (succeeded > 0) emit('applied')
    }
  } finally {
    applying.value = null
  }
}
</script>

<template>
  <Modal
    title="Bulk Permissions"
    icon="lock"
    :accent="accent"
    width="520px"
    @close="emit('close')">
    <div class="form-stack">
      <p class="hint">
        Grant or revoke a group permission across the selected {{ countLabel }}.
        Each item is updated independently and requires MANAGE access; failures are reported.
      </p>
      <Select
        v-model="groupId"
        label="Group"
        :options="groupOptions"
        searchable
        :disabled="loadingGroups || !!applying"
        :placeholder="loadingGroups ? 'Loading…' : 'Select a group…'"
        :accent="accent" />
      <Select
        v-model="action"
        label="Action"
        :options="config.actions"
        :disabled="!!applying"
        :accent="accent" />
    </div>
    <template #footer>
      <span class="spacer" />
      <Button size="sm" :disabled="!!applying" @click="emit('close')">Cancel</Button>
      <Button
        size="sm"
        primary
        accent="var(--err)"
        :disabled="!groupId || !action || !!applying"
        @click="apply('revoke')">{{ applying === 'revoke' ? 'Revoking…' : 'Revoke' }}</Button>
      <Button
        size="sm"
        primary
        :accent="accent"
        :disabled="!groupId || !action || !!applying"
        @click="apply('grant')">{{ applying === 'grant' ? 'Granting…' : 'Grant' }}</Button>
    </template>
  </Modal>
</template>

<style scoped>
.form-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.hint {
  font-size: 12px;
  color: var(--fg-2);
  line-height: 1.5;
}

.spacer { flex: 1; }
</style>
