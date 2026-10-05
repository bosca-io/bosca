<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

/**
 * The global environment type catalog (development / staging / production / preview / …).
 * Pipelines reference environments by TYPE — a portable identifier resolved within each run's own
 * program — so this list is platform-wide, not program-scoped. Program environments pick one of
 * these types on the Releases → Environments page.
 */

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

interface EnvironmentTypeItem {
  id: string
  name: string
  description: string | null
  displayOrder: number
  version: number
}

const listGql = gql`
  query GetWorkOpsEnvironmentTypes {
    workOps { multiRepo { environmentTypes { id name description displayOrder version } } }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  workOps: { multiRepo: { environmentTypes: EnvironmentTypeItem[] } }
}>('workops-environment-types', listGql, {})
const items = computed(() =>
  [...(data.value?.workOps?.multiRepo?.environmentTypes ?? [])].sort((a, b) => a.displayOrder - b.displayOrder),
)

const columns: GlassTableColumn[] = [
  { key: 'order', label: '#', width: '48px' },
  { key: 'name', label: 'Name', width: 'minmax(160px, 1fr)' },
  { key: 'description', label: 'Description', width: '2fr', muted: true },
]

// ─── Create ──────────────────────────────────────────────────────────────────
const showCreate = ref(false)
const createForm = reactive({ name: '', displayOrder: 0, description: '' })
const saving = ref(false)

function openCreate() {
  createForm.name = ''
  createForm.displayOrder = items.value.length
  createForm.description = ''
  showCreate.value = true
}

async function handleCreate() {
  if (!createForm.name.trim()) return
  saving.value = true
  try {
    await gqlMutation(
      gql`mutation CreateEnvironmentType($name: String!, $description: String, $displayOrder: Int) {
        workOps { multiRepo { createEnvironmentType(name: $name, description: $description, displayOrder: $displayOrder) { id } } }
      }`,
      {
        name: createForm.name.trim(),
        description: createForm.description.trim() || null,
        displayOrder: createForm.displayOrder,
      },
    )
    showCreate.value = false
    toast.success('Environment type created')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create the environment type')
  } finally {
    saving.value = false
  }
}

// ─── Edit ────────────────────────────────────────────────────────────────────
const editTarget = ref<EnvironmentTypeItem | null>(null)
const editForm = reactive({ name: '', displayOrder: 0, description: '', expectedVersion: 0 as number })
const editSaving = ref(false)

function openEdit(item: EnvironmentTypeItem) {
  editTarget.value = item
  editForm.name = item.name
  editForm.displayOrder = item.displayOrder
  editForm.description = item.description ?? ''
  editForm.expectedVersion = item.version
}

async function handleUpdate() {
  if (!editTarget.value || !editForm.name.trim()) return
  editSaving.value = true
  try {
    await gqlMutation(
      gql`mutation UpdateEnvironmentType($id: UUID!, $name: String!, $description: String, $displayOrder: Int, $expectedVersion: Long!) {
        workOps { multiRepo { updateEnvironmentType(id: $id, name: $name, description: $description, displayOrder: $displayOrder, expectedVersion: $expectedVersion) { id } } }
      }`,
      {
        id: editTarget.value.id,
        name: editForm.name.trim(),
        description: editForm.description.trim() || null,
        displayOrder: editForm.displayOrder,
        expectedVersion: editForm.expectedVersion,
      },
    )
    editTarget.value = null
    toast.success('Environment type updated')
    refresh()
  } catch {
    toast.error('Failed to update — it may have been modified by another user')
  } finally {
    editSaving.value = false
  }
}

// ─── Delete ──────────────────────────────────────────────────────────────────
const deleteTarget = ref<EnvironmentTypeItem | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(
      gql`mutation DeleteEnvironmentType($id: UUID!) {
        workOps { multiRepo { deleteEnvironmentType(id: $id) } }
      }`,
      { id: deleteTarget.value.id },
    )
    deleteTarget.value = null
    toast.success('Environment type deleted')
    refresh()
  } catch (e: unknown) {
    // The server refuses while any environment still instantiates the type — its message says which.
    toast.error(e instanceof Error ? e.message : 'Failed to delete the environment type')
  } finally {
    deleteLoading.value = false
  }
}

function handleRowAction({ action, row }: { action: string, row: EnvironmentTypeItem }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'Environment Types')"
        title="Environment Types"
        :subtitle="`${items.length} types`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">
            New Environment Type
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard
      title="Global Catalog"
      subtitle="Pipelines reference environments by type, resolved within each run's own program. Program environments pick a type on Releases → Environments.">
      <GlassTable
        :columns="columns"
        :rows="items"
        :loading="status === 'pending' && items.length === 0"
        empty-text="No environment types. Create the lifecycle stages your programs deploy through."
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
          <span class="type-name">{{ row.name }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Environment Type"
      icon="plus"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="createForm.name"
          label="Name"
          placeholder="e.g. qa"
          autofocus />
        <NumberInput
          v-model="createForm.displayOrder"
          label="Display Order"
          :min="0"
          :max="99" />
        <Textarea
          v-model="createForm.description"
          label="Description"
          :rows="2"
          placeholder="Optional description" />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!createForm.name.trim() || saving"
          @click="handleCreate"
        >
          Create
        </Button>
      </template>
    </Modal>

    <!-- Edit Modal -->
    <Modal
      v-if="editTarget"
      title="Edit Environment Type"
      icon="pencil"
      :accent="accent"
      @close="editTarget = null">
      <div class="form-stack">
        <TextInput v-model="editForm.name" label="Name" autofocus />
        <NumberInput
          v-model="editForm.displayOrder"
          label="Display Order"
          :min="0"
          :max="99" />
        <Textarea v-model="editForm.description" label="Description" :rows="2" />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="editTarget = null">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!editForm.name.trim() || editSaving"
          @click="handleUpdate"
        >
          Save Changes
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
.form-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.spacer {
  flex: 1;
}

.type-name {
  font-weight: 500;
  color: var(--fg-0);
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
</style>
