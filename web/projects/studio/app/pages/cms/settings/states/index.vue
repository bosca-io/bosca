<script setup lang="ts">
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const statesGql = gql`
  query GetStates {
    states { all { id name description type jobName } }
  }
`

const jobsGql = gql`
  query GetJobDefinitions {
    scheduler { availableJobDefinitions { name displayName } }
  }
`

const addGql = gql`
  mutation AddState($state: WorkflowStateInput!) {
    states { add(state: $state) { id } }
  }
`

const editGql = gql`
  mutation EditState($state: WorkflowStateInput!) {
    states { edit(state: $state) { id } }
  }
`

const deleteGql = gql`
  mutation DeleteState($id: String!) {
    states { delete(id: $id) }
  }
`

interface WorkflowState {
  id: string
  name: string
  description: string | null
  type: string
  jobName: string | null
}

interface JobDefinition {
  name: string
  displayName: string
}

const { data, status, refresh } = useAsyncQuery<{
  states: { all: WorkflowState[] }
}>('cms-states', statesGql, undefined, { server: false })

const { data: jobsData } = useAsyncQuery<{
  scheduler: { availableJobDefinitions: JobDefinition[] }
}>('cms-states-jobs', jobsGql, undefined, { server: false })

const items = computed(() => data.value?.states?.all ?? [])
const jobs = computed(() => jobsData.value?.scheduler?.availableJobDefinitions ?? [])

const jobOptions = computed(() => [
  { value: '', label: '(None)' },
  ...jobs.value.map(j => ({ value: j.name, label: j.displayName || j.name })),
])

function getJobDisplayName(jobName: string | null): string {
  if (!jobName) return '—'
  const job = jobs.value.find(j => j.name === jobName)
  return job?.displayName || jobName
}

const TYPE_COLORS: Record<string, string> = {
  DRAFT: '#6c7388',
  PENDING: '#ffb547',
  PROCESSING: '#60a5fa',
  PUBLISHED: '#34d99a',
  APPROVAL: '#a78bfa',
  APPROVED: '#34d99a',
  FAILURE: '#f87171',
  ADVERTISED: '#fbbf24',
}

const typeOptions = [
  { value: 'DRAFT', label: 'Draft' },
  { value: 'PENDING', label: 'Pending' },
  { value: 'PROCESSING', label: 'Processing' },
  { value: 'PUBLISHED', label: 'Published' },
  { value: 'APPROVAL', label: 'Approval' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'FAILURE', label: 'Failure' },
  { value: 'ADVERTISED', label: 'Advertised' },
]

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(160px, 2fr)' },
  { key: 'type', label: 'Type', width: '120px' },
  { key: 'job', label: 'Job', width: 'minmax(140px, 1fr)' },
]

const showModal = ref(false)
const editingItem = ref<WorkflowState | null>(null)
const deleteTarget = ref<WorkflowState | null>(null)
const deleteLoading = ref(false)
const saving = ref(false)

const formId = ref('')
const formName = ref('')
const formDescription = ref('')
const formType = ref('DRAFT')
const formJobName = ref('')
const formConfiguration = ref<Record<string, unknown>>({})

function openCreate() {
  editingItem.value = null
  formId.value = ''
  formName.value = ''
  formDescription.value = ''
  formType.value = 'DRAFT'
  formJobName.value = ''
  formConfiguration.value = {}
  showModal.value = true
}

function openEdit(item: WorkflowState) {
  editingItem.value = item
  formId.value = item.id
  formName.value = item.name
  formDescription.value = item.description ?? ''
  formType.value = item.type
  formJobName.value = item.jobName ?? ''
  formConfiguration.value = {}
  showModal.value = true
}

function buildInput() {
  return {
    id: formId.value,
    name: formName.value,
    description: formDescription.value || null,
    type: formType.value,
    jobName: formJobName.value || null,
    configuration: formConfiguration.value,
  }
}

async function handleSave() {
  if (!formId.value.trim() || !formName.value.trim()) return
  saving.value = true
  try {
    if (editingItem.value) {
      await gqlMutation(editGql, { state: buildInput() })
      toast.success('State updated')
    } else {
      await gqlMutation(addGql, { state: buildInput() })
      toast.success('State created')
    }
    showModal.value = false
    refresh()
  } catch {
    toast.error('Failed to save state')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    toast.success('State deleted')
    deleteTarget.value = null
    refresh()
  } catch {
    toast.error('Failed to delete state')
  } finally {
    deleteLoading.value = false
  }
}

function onRowAction({ action, row }: { action: string; row: WorkflowState }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Settings', 'States')"
        title="Workflow States"
        :subtitle="`${items.length} states`">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New State</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="States">
      <GlassTable
        :columns="columns"
        :rows="items"
        :loading="status === 'pending' && items.length === 0"
        empty-text="No states configured."
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-click="(row: any) => openEdit(row)"
        @row-action="onRowAction"
      >
        <template #col-name="{ row }">
          <div>
            <div style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</div>
            <div v-if="row.description" style="font-size: 11.5px; color: var(--fg-3); margin-top: 1px">{{ row.description }}</div>
          </div>
        </template>
        <template #col-type="{ row }">
          <Badge :color="TYPE_COLORS[row.type] ?? '#6c7388'">{{ row.type }}</Badge>
        </template>
        <template #col-job="{ row }">
          <span style="color: var(--fg-2)">{{ getJobDisplayName(row.jobName) }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showModal"
      :title="editingItem ? 'Edit State' : 'New State'"
      icon="workflow"
      :accent="accent"
      @close="showModal = false">
      <div class="state-form">
        <TextInput
          v-model="formId"
          label="ID"
          placeholder="state-id"
          :disabled="!!editingItem"
          autofocus />
        <TextInput v-model="formName" label="Name" placeholder="State name" />
        <Textarea
          v-model="formDescription"
          label="Description"
          placeholder="Optional description"
          :rows="2" />
        <Select v-model="formType" :options="typeOptions" label="Type" />
        <Select v-model="formJobName" :options="jobOptions" label="Job" />

        <div class="field-group">
          <label class="field-label">Configuration</label>
          <JsonEditorVue
            v-model="formConfiguration"
            class="json-editor"
            mode="text"
            :main-menu-bar="false"
            :status-bar="false"
            style="height: 200px" />
        </div>
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showModal = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!formId.trim() || !formName.trim() || saving"
          @click="handleSave">
          {{ saving ? 'Saving…' : (editingItem ? 'Update' : 'Create') }}
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      subtitle="This action cannot be undone."
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.state-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-2);
}
</style>
