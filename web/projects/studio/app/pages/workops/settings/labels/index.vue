<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

interface LabelItem {
  id: string
  name: string
  colorHex: string | null
  scope: string
  version: number
}

const listGql = gql`
  query GetWorkOpsGlobalLabels {
    workOps { labels { global { id name colorHex scope version } } }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  workOps: { labels: { global: LabelItem[] } }
}>('workops-labels-global', listGql, {})
const items = computed(() => data.value?.workOps?.labels?.global ?? [])

const scopeOptions = [
  { value: 'GLOBAL', label: 'Global' },
  { value: 'PORTFOLIO', label: 'Portfolio' },
  { value: 'PROGRAM', label: 'Program' },
  { value: 'PROJECT', label: 'Project' },
]

const scopeColors: Record<string, string> = {
  GLOBAL: '#a78bff',
  PORTFOLIO: '#5ec5ff',
  PROGRAM: '#4ade80',
  PROJECT: '#ffb547',
}

const columns: GlassTableColumn[] = [
  { key: 'color', label: '', width: '40px' },
  { key: 'name', label: 'Name', width: 'minmax(160px, 2fr)' },
  { key: 'scope', label: 'Scope', width: 'minmax(100px, 1fr)' },
]

// ─── Create ──────────────────────────────────────────────────────────────────
const showCreate = ref(false)
const createForm = reactive({
  name: '',
  colorHex: '#a78bff',
  scope: 'GLOBAL',
})
const saving = ref(false)

function resetCreateForm() {
  createForm.name = ''
  createForm.colorHex = '#a78bff'
  createForm.scope = 'GLOBAL'
}

async function handleCreate() {
  if (!createForm.name.trim()) return
  saving.value = true
  try {
    await gqlMutation(
      gql`mutation CreateLabel($input: CreateWorkOpsLabelInput!) {
        workOps { labels { create(input: $input) { id } } }
      }`,
      {
        input: {
          name: createForm.name.trim(),
          colorHex: createForm.colorHex,
          scope: createForm.scope,
        },
      },
    )
    showCreate.value = false
    resetCreateForm()
    toast.success('Label created')
    refresh()
  } catch {
    toast.error('Failed to create label')
  } finally {
    saving.value = false
  }
}

// ─── Edit ────────────────────────────────────────────────────────────────────
const editTarget = ref<LabelItem | null>(null)
const editForm = reactive({
  name: '',
  colorHex: '#a78bff',
  expectedVersion: 0 as number,
})
const editSaving = ref(false)

function openEdit(item: LabelItem) {
  editTarget.value = item
  editForm.name = item.name
  editForm.colorHex = item.colorHex ?? '#a78bff'
  editForm.expectedVersion = item.version
}

async function handleUpdate() {
  if (!editTarget.value || !editForm.name.trim()) return
  editSaving.value = true
  try {
    await gqlMutation(
      gql`mutation UpdateLabel($id: UUID!, $name: String!, $colorHex: String, $expectedVersion: Long!) {
        workOps { labels { update(id: $id, name: $name, colorHex: $colorHex, expectedVersion: $expectedVersion) { id } } }
      }`,
      {
        id: editTarget.value.id,
        name: editForm.name.trim(),
        colorHex: editForm.colorHex,
        expectedVersion: editForm.expectedVersion,
      },
    )
    editTarget.value = null
    toast.success('Label updated')
    refresh()
  } catch {
    toast.error('Failed to update — it may have been modified by another user')
  } finally {
    editSaving.value = false
  }
}

// ─── Delete ──────────────────────────────────────────────────────────────────
const deleteTarget = ref<LabelItem | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(
      gql`mutation DeleteLabel($id: UUID!) {
        workOps { labels { delete(id: $id) } }
      }`,
      { id: deleteTarget.value.id },
    )
    deleteTarget.value = null
    toast.success('Label deleted')
    refresh()
  } catch {
    toast.error('Failed to delete label')
  } finally {
    deleteLoading.value = false
  }
}

function handleRowAction({ action, row }: { action: string; row: LabelItem }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'Labels')"
        title="Labels"
        :subtitle="`${items.length} global labels`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreate = true">
            New Label
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Global Labels" subtitle="Labels available across all projects. Project-scoped labels are managed within each project.">
      <GlassTable
        :columns="columns"
        :rows="items"
        :loading="status === 'pending' && items.length === 0"
        empty-text="No global labels configured. Create one to get started."
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-action="handleRowAction"
        @row-click="openEdit"
      >
        <template #col-color="{ row }">
          <span class="color-chip" :style="{ background: row.colorHex || '#6c7388' }" />
        </template>
        <template #col-name="{ row }">
          <span class="label-name">{{ row.name }}</span>
        </template>
        <template #col-scope="{ row }">
          <Badge :color="scopeColors[row.scope]">
            {{ scopeOptions.find(o => o.value === row.scope)?.label ?? row.scope }}
          </Badge>
        </template>
      </GlassTable>
    </SectionCard>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Label"
      icon="plus"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="createForm.name"
          label="Name"
          placeholder="e.g. needs-triage, frontend, P0"
          autofocus />
        <ColorPicker
          v-model="createForm.colorHex"
          label="Color"
          :presets="['#a78bff', '#5ec5ff', '#4ade80', '#ffb547', '#ff5d6c', '#ec4899', '#f97316', '#6c7388']"
        />
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
      title="Edit Label"
      icon="pencil"
      :accent="accent"
      @close="editTarget = null">
      <div class="form-stack">
        <TextInput v-model="editForm.name" label="Name" autofocus />
        <ColorPicker
          v-model="editForm.colorHex"
          label="Color"
          :presets="['#a78bff', '#5ec5ff', '#4ade80', '#ffb547', '#ff5d6c', '#ec4899', '#f97316', '#6c7388']"
        />
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

.color-chip {
  display: inline-block;
  width: 20px;
  height: 12px;
  border-radius: 3px;
  vertical-align: middle;
}

.label-name {
  font-weight: 500;
  color: var(--fg-0);
}
</style>
