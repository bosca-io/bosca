<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

interface LinkTypeItem {
  id: string
  name: string
  category: string
  inwardLabel: string
  outwardLabel: string
  version: number
}

const listGql = gql`
  query GetWorkOpsLinkTypes {
    workOps { links { linkTypes { id name category inwardLabel outwardLabel version } } }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  workOps: { links: { linkTypes: LinkTypeItem[] } }
}>('workops-link-types', listGql, {})
const items = computed(() => data.value?.workOps?.links?.linkTypes ?? [])

const categoryOptions = [
  { value: 'BLOCKS', label: 'Blocks' },
  { value: 'RELATES_TO', label: 'Relates To' },
  { value: 'DUPLICATES', label: 'Duplicates' },
  { value: 'CLONES', label: 'Clones' },
  { value: 'CAUSES', label: 'Causes' },
  { value: 'CUSTOM', label: 'Custom' },
]

const categoryColors: Record<string, string> = {
  BLOCKS: '#ff5d6c',
  RELATES_TO: '#5ec5ff',
  DUPLICATES: '#ffb547',
  CLONES: '#a78bff',
  CAUSES: '#f97316',
  CUSTOM: '#6c7388',
}

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(120px, 1.5fr)' },
  { key: 'category', label: 'Category', width: 'minmax(100px, 1fr)' },
  { key: 'outward', label: 'Outward (→)', width: 'minmax(120px, 1.5fr)' },
  { key: 'inward', label: 'Inward (←)', width: 'minmax(120px, 1.5fr)' },
]

// ─── Create ──────────────────────────────────────────────────────────────────
const showCreate = ref(false)
const createForm = reactive({
  name: '',
  category: 'RELATES_TO',
  outwardLabel: '',
  inwardLabel: '',
})
const saving = ref(false)

function resetCreateForm() {
  createForm.name = ''
  createForm.category = 'RELATES_TO'
  createForm.outwardLabel = ''
  createForm.inwardLabel = ''
}

async function handleCreate() {
  if (!createForm.name.trim() || !createForm.outwardLabel.trim() || !createForm.inwardLabel.trim()) return
  saving.value = true
  try {
    await gqlMutation(
      gql`mutation CreateLinkType($input: CreateWorkOpsTaskLinkTypeInput!) {
        workOps { linkTypes { create(input: $input) { id } } }
      }`,
      {
        input: {
          name: createForm.name.trim(),
          category: createForm.category,
          outwardLabel: createForm.outwardLabel.trim(),
          inwardLabel: createForm.inwardLabel.trim(),
        },
      },
    )
    showCreate.value = false
    resetCreateForm()
    toast.success('Link type created')
    refresh()
  } catch {
    toast.error('Failed to create link type')
  } finally {
    saving.value = false
  }
}

// ─── Edit ────────────────────────────────────────────────────────────────────
const editTarget = ref<LinkTypeItem | null>(null)
const editForm = reactive({
  name: '',
  category: 'RELATES_TO',
  outwardLabel: '',
  inwardLabel: '',
  expectedVersion: 0 as number,
})
const editSaving = ref(false)

function openEdit(item: LinkTypeItem) {
  editTarget.value = item
  editForm.name = item.name
  editForm.category = item.category
  editForm.outwardLabel = item.outwardLabel
  editForm.inwardLabel = item.inwardLabel
  editForm.expectedVersion = item.version
}

async function handleUpdate() {
  if (!editTarget.value || !editForm.name.trim()) return
  editSaving.value = true
  try {
    await gqlMutation(
      gql`mutation UpdateLinkType($id: UUID!, $input: UpdateWorkOpsTaskLinkTypeInput!) {
        workOps { linkTypes { update(id: $id, input: $input) { id } } }
      }`,
      {
        id: editTarget.value.id,
        input: {
          name: editForm.name.trim(),
          category: editForm.category,
          outwardLabel: editForm.outwardLabel.trim(),
          inwardLabel: editForm.inwardLabel.trim(),
          expectedVersion: editForm.expectedVersion,
        },
      },
    )
    editTarget.value = null
    toast.success('Link type updated')
    refresh()
  } catch {
    toast.error('Failed to update — it may have been modified by another user')
  } finally {
    editSaving.value = false
  }
}

// ─── Delete ──────────────────────────────────────────────────────────────────
const deleteTarget = ref<LinkTypeItem | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(
      gql`mutation DeleteLinkType($id: UUID!) {
        workOps { linkTypes { delete(id: $id) } }
      }`,
      { id: deleteTarget.value.id },
    )
    deleteTarget.value = null
    toast.success('Link type deleted')
    refresh()
  } catch {
    toast.error('Failed to delete link type')
  } finally {
    deleteLoading.value = false
  }
}

function handleRowAction({ action, row }: { action: string; row: LinkTypeItem }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'Link Types')"
        title="Link Types"
        :subtitle="`${items.length} link types`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreate = true">
            New Link Type
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Task Link Types" subtitle="Define relationships between tasks. Outward label is shown on the source task, inward on the target.">
      <GlassTable
        :columns="columns"
        :rows="items"
        :loading="status === 'pending' && items.length === 0"
        empty-text="No link types configured. Create one to get started."
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-action="handleRowAction"
        @row-click="openEdit"
      >
        <template #col-name="{ row }">
          <span class="link-name">{{ row.name }}</span>
        </template>
        <template #col-category="{ row }">
          <Badge :color="categoryColors[row.category]">
            {{ categoryOptions.find(o => o.value === row.category)?.label ?? row.category }}
          </Badge>
        </template>
        <template #col-outward="{ row }">
          <span class="direction-label">{{ row.outwardLabel }}</span>
        </template>
        <template #col-inward="{ row }">
          <span class="direction-label">{{ row.inwardLabel }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Link Type"
      icon="plus"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="createForm.name"
          label="Name"
          placeholder="e.g. Blocks, Duplicates"
          autofocus />
        <Select v-model="createForm.category" label="Category" :options="categoryOptions" />
        <TextInput v-model="createForm.outwardLabel" label="Outward Label" placeholder="e.g. blocks, is duplicated by" />
        <TextInput v-model="createForm.inwardLabel" label="Inward Label" placeholder="e.g. is blocked by, duplicates" />
        <div class="link-preview">
          <span class="preview-label">Preview:</span>
          <span class="preview-line">Task A <strong>{{ createForm.outwardLabel || '...' }}</strong> Task B</span>
          <span class="preview-line">Task B <strong>{{ createForm.inwardLabel || '...' }}</strong> Task A</span>
        </div>
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!createForm.name.trim() || !createForm.outwardLabel.trim() || !createForm.inwardLabel.trim() || saving"
          @click="handleCreate"
        >
          Create
        </Button>
      </template>
    </Modal>

    <!-- Edit Modal -->
    <Modal
      v-if="editTarget"
      title="Edit Link Type"
      icon="pencil"
      :accent="accent"
      @close="editTarget = null">
      <div class="form-stack">
        <TextInput v-model="editForm.name" label="Name" autofocus />
        <Select v-model="editForm.category" label="Category" :options="categoryOptions" />
        <TextInput v-model="editForm.outwardLabel" label="Outward Label" />
        <TextInput v-model="editForm.inwardLabel" label="Inward Label" />
        <div class="link-preview">
          <span class="preview-label">Preview:</span>
          <span class="preview-line">Task A <strong>{{ editForm.outwardLabel || '...' }}</strong> Task B</span>
          <span class="preview-line">Task B <strong>{{ editForm.inwardLabel || '...' }}</strong> Task A</span>
        </div>
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="editTarget = null">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!editForm.name.trim() || !editForm.outwardLabel.trim() || !editForm.inwardLabel.trim() || editSaving"
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

.link-name {
  font-weight: 500;
  color: var(--fg-0);
}

.direction-label {
  font-size: 13px;
  color: var(--fg-1);
  font-style: italic;
}

.link-preview {
  padding: 12px;
  background: var(--bg-2);
  border-radius: var(--r-md);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.preview-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--fg-2);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 4px;
}

.preview-line {
  font-size: 13px;
  color: var(--fg-1);
}

.preview-line strong {
  color: var(--fg-0);
}
</style>
