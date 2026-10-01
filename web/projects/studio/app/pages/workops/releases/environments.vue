<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

/**
 * Program environments: the deploy targets a program's releases promote through
 * (dev → staging → production). Each environment instantiates a global Environment Type (managed in
 * Settings → Environment Types) — pipelines reference the type; the concrete environment resolves
 * per-run within the release's program. Promotion edges are many-to-many ("promotes from").
 */

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

interface Program { id: string, key: string, name: string }
interface EnvironmentItem {
  id: string
  key: string
  name: string
  description: string | null
  displayOrder: number
  requiresApproval: boolean
  autoPromote: boolean
  typeId: string
  promotionSourceIds: string[]
  version: number
  permissions: PermissionEntry[]
}
interface EnvironmentType { id: string, name: string, displayOrder: number }
interface PermissionEntry { groupId: string; action: string }
interface GroupItem { id: string; name: string }

const groupsGql = gql`query EnvironmentPermissionGroups { security { groups { all(offset: 0, limit: 100) { id name } } } }`
const { data: groupsData } = useAsyncQuery<{ security: { groups: { all: GroupItem[] } } }>('environment-permission-groups', groupsGql)
const groups = computed(() => groupsData.value?.security?.groups?.all ?? [])
const groupOptions = computed<SelectOption[]>(() => groups.value.map(group => ({ value: group.id, label: group.name })))

// ─── Program selection (same idiom as the release dashboard) ─────────────────
const programsGql = gql`query { workOps { programs { all { id key name } } } }`
const { data: programsData } = useAsyncQuery<{ workOps: { programs: { all: Program[] } } }>('environments-programs', programsGql)
const programs = computed(() => programsData.value?.workOps?.programs?.all ?? [])
const programOptions = computed(() => programs.value.map(p => ({ value: p.id, label: p.name })))
const selectedProgramId = ref(typeof route.query.programId === 'string' ? route.query.programId : '')
watch(programs, (list) => {
  if (!selectedProgramId.value && list.length) selectedProgramId.value = list[0]!.id
}, { immediate: true })

// ─── Environments + the global type catalog ──────────────────────────────────
const envGql = gql`
  query ProgramEnvironments($programId: UUID!) {
    workOps { multiRepo {
      environments(programId: $programId) {
        id key name description displayOrder requiresApproval autoPromote typeId promotionSourceIds version
        permissions { groupId action }
      }
      environmentTypes { id name displayOrder }
    } }
  }
`
const { data, status, refresh } = useAsyncQuery<{
  workOps: { multiRepo: { environments: EnvironmentItem[], environmentTypes: EnvironmentType[] } }
}>('program-environments', envGql, { programId: computed(() => selectedProgramId.value || undefined) }, { server: false })

const environments = computed(() =>
  [...(data.value?.workOps?.multiRepo?.environments ?? [])].sort((a, b) => a.displayOrder - b.displayOrder),
)
const envById = computed(() => new Map(environments.value.map(e => [e.id, e])))
const environmentTypes = computed(() => data.value?.workOps?.multiRepo?.environmentTypes ?? [])
const typeById = computed(() => new Map(environmentTypes.value.map(t => [t.id, t])))
const typeOptions = computed(() => environmentTypes.value.map(t => ({ value: t.id, label: t.name })))

const columns: GlassTableColumn[] = [
  { key: 'order', label: '#', width: '48px' },
  { key: 'name', label: 'Name', width: 'minmax(140px, 1.5fr)' },
  { key: 'type', label: 'Type', width: '120px' },
  { key: 'promotesFrom', label: 'Promotes from', width: '1.5fr', muted: true },
  { key: 'flags', label: '', width: '180px' },
]

function sourceNames(env: EnvironmentItem): string {
  return env.promotionSourceIds.map(id => envById.value.get(id)?.name ?? '—').join(', ')
}

// ─── Create / Edit (one modal, two modes) ────────────────────────────────────
// The release-pipeline YAML is the source of truth for topology (key, promotes-from, approval);
// WorkOps owns the display fields. So the modal edits name/description/type, the key is set once
// at create (the YAML link), and the YAML-owned fields render read-only.
const editTarget = ref<EnvironmentItem | null>(null)
const showForm = ref(false)
const form = reactive({
  key: '',
  name: '',
  description: '',
  typeId: '',
  autoPromote: false,
})
const keyTouched = ref(false)
const saving = ref(false)
const permissionGroupId = ref<string | undefined>()
const permissionBusy = ref('')

function slugify(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
}

// Until the key is edited directly, it follows the name (create only).
watch(() => form.name, (name) => {
  if (!editTarget.value && !keyTouched.value) form.key = slugify(name)
})

function openCreate() {
  editTarget.value = null
  form.key = ''
  form.name = ''
  form.description = ''
  form.typeId = ''
  form.autoPromote = false
  keyTouched.value = false
  showForm.value = true
}

function openEdit(env: EnvironmentItem) {
  editTarget.value = env
  form.key = env.key
  form.name = env.name
  form.description = env.description ?? ''
  form.typeId = env.typeId
  form.autoPromote = env.autoPromote
  permissionGroupId.value = undefined
  showForm.value = true
}

function groupName(groupId: string): string {
  return groups.value.find(group => group.id === groupId)?.name ?? groupId.slice(0, 8)
}

async function addExecutePermission() {
  if (!editTarget.value || !permissionGroupId.value) return
  permissionBusy.value = permissionGroupId.value
  try {
    await gqlMutation(gql`
      mutation AddEnvironmentExecutePermission($id: UUID!, $groupId: UUID!) {
        workOps { multiRepo { addEnvironmentPermission(id: $id, groupId: $groupId, action: EXECUTE) } }
      }
    `, { id: editTarget.value.id, groupId: permissionGroupId.value })
    toast.success('Environment execute permission added')
    permissionGroupId.value = undefined
    await refresh()
    editTarget.value = environments.value.find(environment => environment.id === editTarget.value?.id) ?? editTarget.value
  }
  catch (error: unknown) { toast.error(error instanceof Error ? error.message : 'Failed to add permission') }
  finally { permissionBusy.value = '' }
}

async function removePermission(permission: PermissionEntry) {
  if (!editTarget.value) return
  permissionBusy.value = `${permission.groupId}:${permission.action}`
  try {
    await gqlMutation(gql`
      mutation RemoveEnvironmentPermission($id: UUID!, $groupId: UUID!, $action: PermissionAction!) {
        workOps { multiRepo { removeEnvironmentPermission(id: $id, groupId: $groupId, action: $action) } }
      }
    `, { id: editTarget.value.id, groupId: permission.groupId, action: permission.action })
    toast.success('Environment permission removed')
    await refresh()
    editTarget.value = environments.value.find(environment => environment.id === editTarget.value?.id) ?? editTarget.value
  }
  catch (error: unknown) { toast.error(error instanceof Error ? error.message : 'Failed to remove permission') }
  finally { permissionBusy.value = '' }
}

async function save() {
  if (!form.name.trim() || !form.typeId) return
  saving.value = true
  try {
    if (editTarget.value) {
      await gqlMutation(gql`
        mutation UpdateEnvironment($id: UUID!, $input: UpdateWorkOpsEnvironmentInput!, $expectedVersion: Long!) {
          workOps { multiRepo { updateEnvironment(id: $id, input: $input, expectedVersion: $expectedVersion) { id } } }
        }
      `, {
        id: editTarget.value.id,
        expectedVersion: editTarget.value.version,
        input: {
          name: form.name.trim(),
          description: form.description.trim() || null,
          typeId: form.typeId,
          // YAML-owned fields pass through unchanged — the pipeline sync owns them.
          promotionSourceIds: editTarget.value.promotionSourceIds,
          requiresApproval: editTarget.value.requiresApproval,
          autoPromote: form.autoPromote,
        },
      })
      toast.success('Environment updated')
    }
    else {
      await gqlMutation(gql`
        mutation CreateEnvironment($input: CreateWorkOpsEnvironmentInput!) {
          workOps { multiRepo { createEnvironment(input: $input) { id } } }
        }
      `, {
        input: {
          programId: selectedProgramId.value,
          key: form.key.trim(),
          name: form.name.trim(),
          description: form.description.trim() || null,
          typeId: form.typeId,
          displayOrder: environments.value.length, // append to the end of the promotion order
          autoPromote: form.autoPromote,
        },
      })
      toast.success('Environment created')
    }
    showForm.value = false
    await refresh()
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to save the environment')
  }
  finally {
    saving.value = false
  }
}

// ─── Delete ──────────────────────────────────────────────────────────────────
const deleteTarget = ref<EnvironmentItem | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(gql`
      mutation DeleteEnvironment($id: UUID!) {
        workOps { multiRepo { deleteEnvironment(id: $id) } }
      }
    `, { id: deleteTarget.value.id })
    deleteTarget.value = null
    toast.success('Environment deleted')
    await refresh()
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to delete the environment')
  }
  finally {
    deleteLoading.value = false
  }
}

function handleRowAction({ action, row }: { action: string, row: EnvironmentItem }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Releases', 'Environments')"
        title="Environments"
        :subtitle="selectedProgramId ? `${environments.length} deploy targets` : 'Pick a program'">
        <template #actions>
          <div class="header-actions">
            <Select
              v-model="selectedProgramId"
              :options="programOptions"
              placeholder="Program"
              size="sm"
              :accent="accent" />
            <Button
              primary
              size="sm"
              icon="plus"
              :accent="accent"
              :disabled="!selectedProgramId"
              @click="openCreate">
              New Environment
            </Button>
          </div>
        </template>
      </PageHeader>
    </template>

    <div v-if="!selectedProgramId" class="page-empty">Select a program to manage its environments.</div>

    <SectionCard
      v-else
      title="Promotion Graph"
      subtitle="Releases deploy to the first environment and promote along the 'promotes from' edges. Types are the global catalog in Settings → Environment Types.">
      <GlassTable
        :columns="columns"
        :rows="environments"
        :loading="status === 'pending' && environments.length === 0"
        empty-text="No environments yet. Add the deploy targets releases promote through (e.g. Development → Staging → Production)."
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-action="handleRowAction"
        @row-click="openEdit"
      >
        <template #col-order="{ row }">
          <span class="order-badge">{{ row.displayOrder }}</span>
        </template>
        <template #col-name="{ row }">
          <div class="env-name-cell">
            <span class="env-name">{{ row.name }}</span>
            <span v-if="row.description" class="env-desc">{{ row.description }}</span>
          </div>
        </template>
        <template #col-type="{ row }">
          <span class="env-type">{{ typeById.get(row.typeId)?.name ?? '—' }}</span>
        </template>
        <template #col-promotesFrom="{ row }">
          <span class="env-sources">{{ sourceNames(row) || '—' }}</span>
        </template>
        <template #col-flags="{ row }">
          <div class="env-flags">
            <span v-if="row.requiresApproval" class="env-flag">approval</span>
            <span v-if="row.autoPromote" class="env-flag">auto-promote</span>
          </div>
        </template>
      </GlassTable>
    </SectionCard>

    <!-- Create / Edit Modal -->
    <Modal
      v-if="showForm"
      :title="editTarget ? 'Edit Environment' : 'New Environment'"
      :icon="editTarget ? 'pencil' : 'plus'"
      :accent="accent"
      @close="showForm = false">
      <div class="form-stack">
        <TextInput
          v-model="form.name"
          label="Name"
          placeholder="e.g. Production"
          autofocus />
        <div v-if="!editTarget" class="key-field">
          <TextInput
            v-model="form.key"
            label="Key"
            placeholder="e.g. production"
            @input="keyTouched = true" />
          <p class="yaml-note">The stable identifier release-pipeline YAML links to. Cannot change later.</p>
        </div>
        <div v-else class="readonly-field">
          <span class="readonly-label">Key</span>
          <span class="readonly-value">{{ editTarget.key }}</span>
        </div>
        <Select
          v-model="form.typeId"
          :options="typeOptions"
          label="Environment type"
          placeholder="Select a type…" />
        <TextInput v-model="form.description" label="Description" placeholder="Optional" />
        <Checkbox v-model="form.autoPromote" label="Auto-promote from its sources" />
        <div v-if="editTarget" class="readonly-field">
          <span class="readonly-label">Promotes from</span>
          <span class="readonly-value">{{ sourceNames(editTarget) || '—' }}</span>
        </div>
        <div v-if="editTarget" class="readonly-field">
          <span class="readonly-label">Requires approval</span>
          <span class="readonly-value">{{ editTarget.requiresApproval ? 'Yes' : 'No' }}</span>
        </div>
        <p v-if="editTarget" class="yaml-note">
          Promotion edges and approval policy come from the release pipeline YAML and sync automatically.
        </p>
        <section v-if="editTarget" class="permissions-section">
          <div>
            <h4>Deployment approval grants</h4>
            <p class="yaml-note">EXECUTE lets a group approve, reject, deploy, and roll back jobs targeting this environment. Program grants are inherited.</p>
          </div>
          <div class="permission-add">
            <Select v-model="permissionGroupId" :options="groupOptions" placeholder="Select group…" />
            <Button size="sm" :disabled="!permissionGroupId || !!permissionBusy" @click="addExecutePermission">Grant execute</Button>
          </div>
          <ul v-if="editTarget.permissions.length" class="permission-list">
            <li v-for="permission in editTarget.permissions" :key="`${permission.groupId}:${permission.action}`">
              <span>{{ groupName(permission.groupId) }}</span>
              <Badge color="var(--info, #38bdf8)">{{ permission.action }}</Badge>
              <button
                type="button"
                class="permission-remove"
                title="Remove this explicit environment grant"
                :disabled="permissionBusy === `${permission.groupId}:${permission.action}`"
                @click="removePermission(permission)"><Icon name="x" :size="12" /></button>
            </li>
          </ul>
          <p v-else class="yaml-note">No explicit grants. Access currently comes only from the parent program.</p>
        </section>
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showForm = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="saving || !form.name.trim() || (!editTarget && !form.key.trim()) || !form.typeId"
          @click="save"
        >
          {{ editTarget ? 'Save Changes' : 'Create' }}
        </Button>
      </template>
    </Modal>

    <!-- Delete Confirmation -->
    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-empty {
  padding: 48px 0;
  text-align: center;
  font-size: 13px;
  color: var(--fg-3);
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.permissions-section { display: flex; flex-direction: column; gap: 10px; padding-top: 12px; border-top: 1px solid var(--line); }
.permissions-section h4 { margin: 0 0 3px; font-size: 11px; text-transform: uppercase; letter-spacing: .07em; color: var(--fg-2); }
.permission-add { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; align-items: end; }
.permission-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 5px; }
.permission-list li { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 8px; padding: 6px 8px; border: 1px solid var(--line); border-radius: 7px; font-size: 11.5px; color: var(--fg-1); }
.permission-remove { display: inline-flex; padding: 3px; border: 0; background: transparent; color: var(--fg-3); cursor: pointer; }
.permission-remove:hover { color: var(--err, #f87171); }

.key-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.readonly-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.readonly-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-2);
}

.readonly-value {
  font-size: 13px;
  color: var(--fg-3);
}

.yaml-note {
  margin: 0;
  font-size: 12px;
  color: var(--fg-3);
}

.spacer {
  flex: 1;
}

.order-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: var(--r-sm);
  background: var(--bg-2);
  font-size: 11px;
  font-weight: 600;
  color: var(--fg-2);
}

.env-name-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.env-name {
  font-weight: 500;
  color: var(--fg-0);
}

.env-desc {
  font-size: 11px;
  color: var(--fg-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.env-type {
  font-size: 12px;
  color: var(--fg-1);
}

.env-sources {
  font-size: 12px;
}

.env-flags {
  display: flex;
  gap: 6px;
  justify-content: flex-end;
}

.env-flag {
  font-size: 9px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  font-weight: 600;
  color: var(--fg-3);
  background: var(--bg-2);
  padding: 2px 6px;
  border-radius: 4px;
}
</style>
