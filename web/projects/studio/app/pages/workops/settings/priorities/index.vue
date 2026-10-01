<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

interface PriorityItem {
  id: string
  name: string
  colorHex: string
  iconKey: string
  displayOrder: number
  description: string | null
  version: number
}

const listGql = gql`
  query GetWorkOpsPriorities {
    workOps { tasks { priorities { id name colorHex iconKey displayOrder description version } } }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  workOps: { tasks: { priorities: PriorityItem[] } }
}>('workops-priorities', listGql, {})
const items = computed(() =>
  [...(data.value?.workOps?.tasks?.priorities ?? [])].sort((a, b) => a.displayOrder - b.displayOrder),
)

const iconOptions = [
  { value: 'arrow-up-double', label: 'Critical (↑↑)' },
  { value: 'arrow-up', label: 'High (↑)' },
  { value: 'arrow-right', label: 'Medium (→)' },
  { value: 'arrow-down', label: 'Low (↓)' },
  { value: 'arrow-down-double', label: 'Lowest (↓↓)' },
]

const columns: GlassTableColumn[] = [
  { key: 'order', label: '#', width: '48px' },
  { key: 'color', label: '', width: '40px' },
  { key: 'name', label: 'Name', width: 'minmax(140px, 2fr)' },
  { key: 'icon', label: 'Icon', width: '100px' },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]

// ─── Create ──────────────────────────────────────────────────────────────────
const showCreate = ref(false)
const createForm = reactive({
  name: '',
  colorHex: '#5ec5ff',
  iconKey: 'arrow-right',
  displayOrder: 0,
  description: '',
})
const saving = ref(false)

function resetCreateForm() {
  createForm.name = ''
  createForm.colorHex = '#5ec5ff'
  createForm.iconKey = 'arrow-right'
  createForm.displayOrder = items.value.length
  createForm.description = ''
}

async function handleCreate() {
  if (!createForm.name.trim()) return
  saving.value = true
  try {
    await gqlMutation(
      gql`mutation CreatePriority($input: CreateWorkOpsPriorityInput!) {
        workOps { priorities { create(input: $input) { id } } }
      }`,
      {
        input: {
          name: createForm.name.trim(),
          colorHex: createForm.colorHex,
          iconKey: createForm.iconKey,
          displayOrder: createForm.displayOrder,
          description: createForm.description.trim() || null,
        },
      },
    )
    showCreate.value = false
    resetCreateForm()
    toast.success('Priority created')
    refresh()
  } catch {
    toast.error('Failed to create priority')
  } finally {
    saving.value = false
  }
}

// ─── Edit ────────────────────────────────────────────────────────────────────
const editTarget = ref<PriorityItem | null>(null)
const editForm = reactive({
  name: '',
  colorHex: '#5ec5ff',
  iconKey: 'arrow-right',
  displayOrder: 0,
  description: '',
  expectedVersion: 0 as number,
})
const editSaving = ref(false)

function openEdit(item: PriorityItem) {
  editTarget.value = item
  editForm.name = item.name
  editForm.colorHex = item.colorHex
  editForm.iconKey = item.iconKey
  editForm.displayOrder = item.displayOrder
  editForm.description = item.description ?? ''
  editForm.expectedVersion = item.version
}

async function handleUpdate() {
  if (!editTarget.value || !editForm.name.trim()) return
  editSaving.value = true
  try {
    await gqlMutation(
      gql`mutation UpdatePriority($id: UUID!, $input: UpdateWorkOpsPriorityInput!) {
        workOps { priorities { update(id: $id, input: $input) { id } } }
      }`,
      {
        id: editTarget.value.id,
        input: {
          name: editForm.name.trim(),
          colorHex: editForm.colorHex,
          iconKey: editForm.iconKey,
          displayOrder: editForm.displayOrder,
          description: editForm.description.trim() || null,
          expectedVersion: editForm.expectedVersion,
        },
      },
    )
    editTarget.value = null
    toast.success('Priority updated')
    refresh()
  } catch {
    toast.error('Failed to update — it may have been modified by another user')
  } finally {
    editSaving.value = false
  }
}

// ─── Delete ──────────────────────────────────────────────────────────────────
const deleteTarget = ref<PriorityItem | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(
      gql`mutation DeletePriority($id: UUID!) {
        workOps { priorities { delete(id: $id) } }
      }`,
      { id: deleteTarget.value.id },
    )
    deleteTarget.value = null
    toast.success('Priority deleted')
    refresh()
  } catch {
    toast.error('Failed to delete priority')
  } finally {
    deleteLoading.value = false
  }
}

function handleRowAction({ action, row }: { action: string; row: PriorityItem }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'delete') deleteTarget.value = row
}

function openCreateWithOrder() {
  createForm.displayOrder = items.value.length
  showCreate.value = true
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'Priorities')"
        title="Priorities"
        :subtitle="`${items.length} priority levels`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreateWithOrder">
            New Priority
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Priority Levels" subtitle="Ordered from highest urgency (0) to lowest. Lower display order = higher priority.">
      <GlassTable
        :columns="columns"
        :rows="items"
        :loading="status === 'pending' && items.length === 0"
        empty-text="No priorities configured. Create one to get started."
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
        <template #col-color="{ row }">
          <span class="color-dot" :style="{ background: row.colorHex }" />
        </template>
        <template #col-name="{ row }">
          <span class="priority-name">{{ row.name }}</span>
        </template>
        <template #col-icon="{ row }">
          <span class="icon-label">{{ iconOptions.find(o => o.value === row.iconKey)?.label ?? row.iconKey }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Priority"
      icon="plus"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="createForm.name"
          label="Name"
          placeholder="e.g. Critical"
          autofocus />
        <ColorPicker
          v-model="createForm.colorHex"
          label="Color"
          :presets="['#ff5d6c', '#ff8a4d', '#ffb547', '#5ec5ff', '#6c7388', '#4ade80', '#a78bff', '#ec4899']"
        />
        <Select v-model="createForm.iconKey" label="Icon" :options="iconOptions" />
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
      title="Edit Priority"
      icon="pencil"
      :accent="accent"
      @close="editTarget = null">
      <div class="form-stack">
        <TextInput v-model="editForm.name" label="Name" autofocus />
        <ColorPicker
          v-model="editForm.colorHex"
          label="Color"
          :presets="['#ff5d6c', '#ff8a4d', '#ffb547', '#5ec5ff', '#6c7388', '#4ade80', '#a78bff', '#ec4899']"
        />
        <Select v-model="editForm.iconKey" label="Icon" :options="iconOptions" />
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

.color-dot {
  display: inline-block;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  vertical-align: middle;
}

.priority-name {
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

.icon-label {
  font-size: 12px;
  color: var(--fg-2);
}
</style>
