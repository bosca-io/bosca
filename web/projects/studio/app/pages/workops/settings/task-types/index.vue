<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

interface TaskTypeItem {
  id: string
  name: string
  colorHex: string
  iconKey: string
  hierarchyLevel: string
  description: string | null
  version: number
}

const listGql = gql`
  query GetWorkOpsTaskTypes {
    workOps { tasks { taskTypes { id name colorHex iconKey hierarchyLevel description version } } }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  workOps: { tasks: { taskTypes: TaskTypeItem[] } }
}>('workops-task-types', listGql, {})
const items = computed(() => data.value?.workOps?.tasks?.taskTypes ?? [])

const hierarchyOptions = [
  { value: 'INITIATIVE', label: 'Initiative' },
  { value: 'EPIC', label: 'Epic' },
  { value: 'STANDARD', label: 'Standard' },
  { value: 'SUBTASK', label: 'Subtask' },
]

const hierarchyColors: Record<string, string> = {
  INITIATIVE: '#a78bff',
  EPIC: '#ec4899',
  STANDARD: '#5ec5ff',
  SUBTASK: '#6c7388',
}

const columns: GlassTableColumn[] = [
  { key: 'visual', label: '', width: '44px' },
  { key: 'name', label: 'Name', width: 'minmax(140px, 2fr)' },
  { key: 'hierarchy', label: 'Hierarchy', width: 'minmax(100px, 1fr)' },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]

// ─── Create ──────────────────────────────────────────────────────────────────
const showCreate = ref(false)
const createForm = reactive({
  name: '',
  colorHex: '#5ec5ff',
  iconKey: 'check-square',
  hierarchyLevel: 'STANDARD',
  description: '',
})
const saving = ref(false)

function resetCreateForm() {
  createForm.name = ''
  createForm.colorHex = '#5ec5ff'
  createForm.iconKey = 'check-square'
  createForm.hierarchyLevel = 'STANDARD'
  createForm.description = ''
}

async function handleCreate() {
  if (!createForm.name.trim()) return
  saving.value = true
  try {
    await gqlMutation(
      gql`mutation CreateTaskType($input: CreateWorkOpsTaskTypeInput!) {
        workOps { taskTypes { create(input: $input) { id } } }
      }`,
      {
        input: {
          name: createForm.name.trim(),
          colorHex: createForm.colorHex,
          iconKey: createForm.iconKey,
          hierarchyLevel: createForm.hierarchyLevel,
          description: createForm.description.trim() || null,
        },
      },
    )
    showCreate.value = false
    resetCreateForm()
    toast.success('Task type created')
    refresh()
  } catch {
    toast.error('Failed to create task type')
  } finally {
    saving.value = false
  }
}

// ─── Edit ────────────────────────────────────────────────────────────────────
const editTarget = ref<TaskTypeItem | null>(null)
const editForm = reactive({
  name: '',
  colorHex: '#5ec5ff',
  iconKey: 'check-square',
  hierarchyLevel: 'STANDARD',
  description: '',
  expectedVersion: 0 as number,
})
const editSaving = ref(false)

function openEdit(item: TaskTypeItem) {
  editTarget.value = item
  editForm.name = item.name
  editForm.colorHex = item.colorHex
  editForm.iconKey = item.iconKey
  editForm.hierarchyLevel = item.hierarchyLevel
  editForm.description = item.description ?? ''
  editForm.expectedVersion = item.version
}

async function handleUpdate() {
  if (!editTarget.value || !editForm.name.trim()) return
  editSaving.value = true
  try {
    await gqlMutation(
      gql`mutation UpdateTaskType($id: UUID!, $input: UpdateWorkOpsTaskTypeInput!) {
        workOps { taskTypes { update(id: $id, input: $input) { id } } }
      }`,
      {
        id: editTarget.value.id,
        input: {
          name: editForm.name.trim(),
          colorHex: editForm.colorHex,
          iconKey: editForm.iconKey,
          hierarchyLevel: editForm.hierarchyLevel,
          description: editForm.description.trim() || null,
          expectedVersion: editForm.expectedVersion,
        },
      },
    )
    editTarget.value = null
    toast.success('Task type updated')
    refresh()
  } catch {
    toast.error('Failed to update — it may have been modified by another user')
  } finally {
    editSaving.value = false
  }
}

// ─── Delete ──────────────────────────────────────────────────────────────────
const deleteTarget = ref<TaskTypeItem | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(
      gql`mutation DeleteTaskType($id: UUID!) {
        workOps { taskTypes { delete(id: $id) } }
      }`,
      { id: deleteTarget.value.id },
    )
    deleteTarget.value = null
    toast.success('Task type deleted')
    refresh()
  } catch {
    toast.error('Failed to delete task type')
  } finally {
    deleteLoading.value = false
  }
}

function handleRowAction({ action, row }: { action: string; row: TaskTypeItem }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'Task Types')"
        title="Task Types"
        :subtitle="`${items.length} types defined`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreate = true">
            New Task Type
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Task Types" subtitle="Define the kinds of work items your team creates.">
      <GlassTable
        :columns="columns"
        :rows="items"
        :loading="status === 'pending' && items.length === 0"
        empty-text="No task types configured. Create one to get started."
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-action="handleRowAction"
        @row-click="openEdit"
      >
        <template #col-visual="{ row }">
          <span class="type-icon" :style="{ background: row.colorHex }">
            <Icon :name="row.iconKey" :size="14" />
          </span>
        </template>
        <template #col-name="{ row }">
          <span class="type-name">{{ row.name }}</span>
        </template>
        <template #col-hierarchy="{ row }">
          <Badge :color="hierarchyColors[row.hierarchyLevel]">
            {{ hierarchyOptions.find(o => o.value === row.hierarchyLevel)?.label ?? row.hierarchyLevel }}
          </Badge>
        </template>
      </GlassTable>
    </SectionCard>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Task Type"
      icon="plus"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="createForm.name"
          label="Name"
          placeholder="e.g. Bug, Feature, Story"
          autofocus />
        <Select v-model="createForm.hierarchyLevel" label="Hierarchy Level" :options="hierarchyOptions" />
        <ColorPicker
          v-model="createForm.colorHex"
          label="Color"
          :presets="['#5ec5ff', '#4ade80', '#a78bff', '#ec4899', '#ffb547', '#ff5d6c', '#f97316', '#6c7388']"
        />
        <TextInput v-model="createForm.iconKey" label="Icon Key" placeholder="e.g. check-square, bug, zap" />
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
      title="Edit Task Type"
      icon="pencil"
      :accent="accent"
      @close="editTarget = null">
      <div class="form-stack">
        <TextInput v-model="editForm.name" label="Name" autofocus />
        <Select v-model="editForm.hierarchyLevel" label="Hierarchy Level" :options="hierarchyOptions" />
        <ColorPicker
          v-model="editForm.colorHex"
          label="Color"
          :presets="['#5ec5ff', '#4ade80', '#a78bff', '#ec4899', '#ffb547', '#ff5d6c', '#f97316', '#6c7388']"
        />
        <TextInput v-model="editForm.iconKey" label="Icon Key" />
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

.type-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  color: #fff;
}

.type-name {
  font-weight: 500;
  color: var(--fg-0);
}
</style>
