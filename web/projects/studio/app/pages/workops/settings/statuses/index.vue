<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'
import type { WorkOpsStatusCategory } from '~/types/graphql'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

interface StatusItem {
  id: string
  name: string
  category: WorkOpsStatusCategory
  colorHex: string
  description: string | null
  version: number
}

const listGql = gql`
  query GetWorkOpsStatuses {
    workOps { statuses { all { id name category colorHex description version } } }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  workOps: { statuses: { all: StatusItem[] } }
}>('workops-statuses', listGql, {})
const items = computed(() => data.value?.workOps?.statuses?.all ?? [])

const categoryOptions = [
  { value: 'TODO', label: 'To Do' },
  { value: 'IN_PROGRESS', label: 'In Progress' },
  { value: 'DONE', label: 'Done' },
  { value: 'CANCELLED', label: 'Cancelled' },
]

const categoryColors: Record<string, string> = {
  TODO: '#6c7388',
  IN_PROGRESS: '#5ec5ff',
  DONE: '#4ade80',
  CANCELLED: '#ff5d6c',
}

const columns: GlassTableColumn[] = [
  { key: 'color', label: '', width: '40px' },
  { key: 'name', label: 'Name', width: 'minmax(160px, 2fr)' },
  { key: 'category', label: 'Category', width: 'minmax(120px, 1fr)' },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]

// ─── Create ──────────────────────────────────────────────────────────────────
const showCreate = ref(false)
const createForm = reactive({
  name: '',
  category: 'TODO' as string,
  colorHex: '#6c7388',
  description: '',
})
const saving = ref(false)

function resetCreateForm() {
  createForm.name = ''
  createForm.category = 'TODO'
  createForm.colorHex = '#6c7388'
  createForm.description = ''
}

async function handleCreate() {
  if (!createForm.name.trim()) return
  saving.value = true
  try {
    await gqlMutation(
      gql`mutation CreateStatus($input: CreateWorkOpsStatusInput!) {
        workOps { statuses { create(input: $input) { id } } }
      }`,
      {
        input: {
          name: createForm.name.trim(),
          category: createForm.category,
          colorHex: createForm.colorHex,
          description: createForm.description.trim() || null,
        },
      },
    )
    showCreate.value = false
    resetCreateForm()
    toast.success('Status created')
    refresh()
  } catch {
    toast.error('Failed to create status')
  } finally {
    saving.value = false
  }
}

// ─── Edit ────────────────────────────────────────────────────────────────────
const editTarget = ref<StatusItem | null>(null)
const editForm = reactive({
  name: '',
  category: 'TODO' as string,
  colorHex: '#6c7388',
  description: '',
  expectedVersion: 0 as number,
})
const editSaving = ref(false)

function openEdit(item: StatusItem) {
  editTarget.value = item
  editForm.name = item.name
  editForm.category = item.category
  editForm.colorHex = item.colorHex
  editForm.description = item.description ?? ''
  editForm.expectedVersion = item.version
}

async function handleUpdate() {
  if (!editTarget.value || !editForm.name.trim()) return
  editSaving.value = true
  try {
    await gqlMutation(
      gql`mutation UpdateStatus($id: UUID!, $input: UpdateWorkOpsStatusInput!) {
        workOps { statuses { update(id: $id, input: $input) { id } } }
      }`,
      {
        id: editTarget.value.id,
        input: {
          name: editForm.name.trim(),
          category: editForm.category,
          colorHex: editForm.colorHex,
          description: editForm.description.trim() || null,
          expectedVersion: editForm.expectedVersion,
        },
      },
    )
    editTarget.value = null
    toast.success('Status updated')
    refresh()
  } catch {
    toast.error('Failed to update — it may have been modified by another user')
  } finally {
    editSaving.value = false
  }
}

// ─── Delete ──────────────────────────────────────────────────────────────────
const deleteTarget = ref<StatusItem | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(
      gql`mutation DeleteStatus($id: UUID!) {
        workOps { statuses { delete(id: $id) } }
      }`,
      { id: deleteTarget.value.id },
    )
    deleteTarget.value = null
    toast.success('Status deleted')
    refresh()
  } catch {
    toast.error('Failed to delete status')
  } finally {
    deleteLoading.value = false
  }
}

function handleRowAction({ action, row }: { action: string; row: StatusItem }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'Statuses')"
        title="Statuses"
        :subtitle="`${items.length} statuses configured`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreate = true">
            New Status
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Statuses" subtitle="Define the lifecycle states available for tasks.">
      <GlassTable
        :columns="columns"
        :rows="items"
        :loading="status === 'pending' && items.length === 0"
        empty-text="No statuses configured. Create one to get started."
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-action="handleRowAction"
        @row-click="openEdit"
      >
        <template #col-color="{ row }">
          <span class="color-dot" :style="{ background: row.colorHex }" />
        </template>
        <template #col-name="{ row }">
          <span class="status-name">{{ row.name }}</span>
        </template>
        <template #col-category="{ row }">
          <Badge :color="categoryColors[row.category]">
            {{ categoryOptions.find(o => o.value === row.category)?.label ?? row.category }}
          </Badge>
        </template>
      </GlassTable>
    </SectionCard>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Status"
      icon="plus"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="createForm.name"
          label="Name"
          placeholder="e.g. In Review"
          autofocus />
        <Select v-model="createForm.category" label="Category" :options="categoryOptions" />
        <ColorPicker
          v-model="createForm.colorHex"
          label="Color"
          :presets="['#6c7388', '#5ec5ff', '#a78bff', '#ffb547', '#4ade80', '#ff5d6c', '#ec4899', '#f97316']"
        />
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
      title="Edit Status"
      icon="pencil"
      :accent="accent"
      @close="editTarget = null">
      <div class="form-stack">
        <TextInput v-model="editForm.name" label="Name" autofocus />
        <Select v-model="editForm.category" label="Category" :options="categoryOptions" />
        <ColorPicker
          v-model="editForm.colorHex"
          label="Color"
          :presets="['#6c7388', '#5ec5ff', '#a78bff', '#ffb547', '#4ade80', '#ff5d6c', '#ec4899', '#f97316']"
        />
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

.color-dot {
  display: inline-block;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  vertical-align: middle;
}

.status-name {
  font-weight: 500;
  color: var(--fg-0);
}
</style>
