<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

interface ResolutionItem {
  id: string
  name: string
  displayOrder: number
  description: string | null
  version: number
}

const listGql = gql`
  query GetWorkOpsResolutions {
    workOps { tasks { resolutions { id name displayOrder description version } } }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  workOps: { tasks: { resolutions: ResolutionItem[] } }
}>('workops-resolutions', listGql, {})
const items = computed(() =>
  [...(data.value?.workOps?.tasks?.resolutions ?? [])].sort((a, b) => a.displayOrder - b.displayOrder),
)

const columns: GlassTableColumn[] = [
  { key: 'order', label: '#', width: '48px' },
  { key: 'name', label: 'Name', width: 'minmax(160px, 2fr)' },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]

// ─── Create ──────────────────────────────────────────────────────────────────
const showCreate = ref(false)
const createForm = reactive({
  name: '',
  displayOrder: 0,
  description: '',
})
const saving = ref(false)

function resetCreateForm() {
  createForm.name = ''
  createForm.displayOrder = items.value.length
  createForm.description = ''
}

async function handleCreate() {
  if (!createForm.name.trim()) return
  saving.value = true
  try {
    await gqlMutation(
      gql`mutation CreateResolution($input: CreateWorkOpsResolutionInput!) {
        workOps { resolutions { create(input: $input) { id } } }
      }`,
      {
        input: {
          name: createForm.name.trim(),
          displayOrder: createForm.displayOrder,
          description: createForm.description.trim() || null,
        },
      },
    )
    showCreate.value = false
    resetCreateForm()
    toast.success('Resolution created')
    refresh()
  } catch {
    toast.error('Failed to create resolution')
  } finally {
    saving.value = false
  }
}

// ─── Edit ────────────────────────────────────────────────────────────────────
const editTarget = ref<ResolutionItem | null>(null)
const editForm = reactive({
  name: '',
  displayOrder: 0,
  description: '',
  expectedVersion: 0 as number,
})
const editSaving = ref(false)

function openEdit(item: ResolutionItem) {
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
      gql`mutation UpdateResolution($id: UUID!, $input: UpdateWorkOpsResolutionInput!) {
        workOps { resolutions { update(id: $id, input: $input) { id } } }
      }`,
      {
        id: editTarget.value.id,
        input: {
          name: editForm.name.trim(),
          displayOrder: editForm.displayOrder,
          description: editForm.description.trim() || null,
          expectedVersion: editForm.expectedVersion,
        },
      },
    )
    editTarget.value = null
    toast.success('Resolution updated')
    refresh()
  } catch {
    toast.error('Failed to update — it may have been modified by another user')
  } finally {
    editSaving.value = false
  }
}

// ─── Delete ──────────────────────────────────────────────────────────────────
const deleteTarget = ref<ResolutionItem | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(
      gql`mutation DeleteResolution($id: UUID!) {
        workOps { resolutions { delete(id: $id) } }
      }`,
      { id: deleteTarget.value.id },
    )
    deleteTarget.value = null
    toast.success('Resolution deleted')
    refresh()
  } catch {
    toast.error('Failed to delete resolution')
  } finally {
    deleteLoading.value = false
  }
}

function handleRowAction({ action, row }: { action: string; row: ResolutionItem }) {
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
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'Resolutions')"
        title="Resolutions"
        :subtitle="`${items.length} resolutions defined`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreateWithOrder">
            New Resolution
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Resolutions" subtitle="Define how completed tasks are categorized (e.g. Fixed, Won't Fix, Duplicate).">
      <GlassTable
        :columns="columns"
        :rows="items"
        :loading="status === 'pending' && items.length === 0"
        empty-text="No resolutions configured. Create one to get started."
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
          <span class="resolution-name">{{ row.name }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Resolution"
      icon="plus"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="createForm.name"
          label="Name"
          placeholder="e.g. Fixed, Won't Fix, Duplicate"
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
      title="Edit Resolution"
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

.resolution-name {
  font-weight: 500;
  color: var(--fg-0);
}
</style>
