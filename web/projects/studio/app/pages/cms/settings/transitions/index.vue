<script setup lang="ts">
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const transitionsGql = gql`
  query GetTransitions {
    transitions { all { fromStateId toStateId description } }
  }
`

const statesGql = gql`
  query GetStatesForTransitions {
    states { all { id name } }
  }
`

const jobsGql = gql`
  query GetJobsForTransitions {
    scheduler { availableJobDefinitions { name displayName } }
  }
`

const addGql = gql`
  mutation AddTransition($transition: TransitionInput!) {
    transitions { add(transition: $transition) { fromStateId toStateId } }
  }
`

const editGql = gql`
  mutation EditTransition($transition: TransitionInput!) {
    transitions { edit(transition: $transition) { fromStateId toStateId } }
  }
`

const deleteGql = gql`
  mutation DeleteTransition($fromStateId: String!, $toStateId: String!) {
    transitions { delete(fromStateId: $fromStateId, toStateId: $toStateId) }
  }
`

interface Transition {
  fromStateId: string
  toStateId: string
  description: string | null
}

interface State {
  id: string
  name: string
}

interface JobDefinition {
  name: string
  displayName: string
}

const { data, status, refresh } = useAsyncQuery<{
  transitions: { all: Transition[] }
}>('cms-transitions', transitionsGql, undefined, { server: false })

const { data: statesData } = useAsyncQuery<{
  states: { all: State[] }
}>('cms-transitions-states', statesGql, undefined, { server: false })

const { data: jobsData } = useAsyncQuery<{
  scheduler: { availableJobDefinitions: JobDefinition[] }
}>('cms-transitions-jobs', jobsGql, undefined, { server: false })

const items = computed(() => data.value?.transitions?.all ?? [])
const states = computed(() => statesData.value?.states?.all ?? [])
const jobs = computed(() => jobsData.value?.scheduler?.availableJobDefinitions ?? [])

const stateOptions = computed(() =>
  states.value.map(s => ({ value: s.id, label: s.name })),
)

const jobOptions = computed(() => [
  { value: '', label: '(None)' },
  ...jobs.value.map(j => ({ value: j.name, label: j.displayName || j.name })),
])

function getStateName(stateId: string): string {
  const state = states.value.find(s => s.id === stateId)
  return state?.name ?? stateId
}

const columns: GlassTableColumn[] = [
  { key: 'from', label: 'From State', width: 'minmax(140px, 1fr)' },
  { key: 'to', label: 'To State', width: 'minmax(140px, 1fr)' },
  { key: 'description', label: 'Description', width: 'minmax(200px, 2fr)', muted: true },
]

const showModal = ref(false)
const editingItem = ref<Transition | null>(null)
const deleteTarget = ref<Transition | null>(null)
const deleteLoading = ref(false)
const saving = ref(false)

const formFromStateId = ref('')
const formToStateId = ref('')
const formDescription = ref('')
const formEnterJobName = ref('')
const formExitJobName = ref('')
const formConfiguration = ref<Record<string, unknown>>({})

function openCreate() {
  editingItem.value = null
  formFromStateId.value = ''
  formToStateId.value = ''
  formDescription.value = ''
  formEnterJobName.value = ''
  formExitJobName.value = ''
  formConfiguration.value = {}
  showModal.value = true
}

function openEdit(item: Transition) {
  editingItem.value = item
  formFromStateId.value = item.fromStateId
  formToStateId.value = item.toStateId
  formDescription.value = item.description ?? ''
  formEnterJobName.value = ''
  formExitJobName.value = ''
  formConfiguration.value = {}
  showModal.value = true
}

function buildInput() {
  return {
    fromStateId: formFromStateId.value,
    toStateId: formToStateId.value,
    description: formDescription.value || null,
    enterJobName: formEnterJobName.value || null,
    exitJobName: formExitJobName.value || null,
    configuration: formConfiguration.value,
  }
}

async function handleSave() {
  if (!formFromStateId.value || !formToStateId.value) return
  saving.value = true
  try {
    if (editingItem.value) {
      await gqlMutation(editGql, { transition: buildInput() })
      toast.success('Transition updated')
    } else {
      await gqlMutation(addGql, { transition: buildInput() })
      toast.success('Transition created')
    }
    showModal.value = false
    refresh()
  } catch {
    toast.error('Failed to save transition')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, {
      fromStateId: deleteTarget.value.fromStateId,
      toStateId: deleteTarget.value.toStateId,
    })
    toast.success('Transition deleted')
    deleteTarget.value = null
    refresh()
  } catch {
    toast.error('Failed to delete transition')
  } finally {
    deleteLoading.value = false
  }
}

function onRowAction({ action, row }: { action: string; row: Transition }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Settings', 'Transitions')"
        title="Transitions"
        :subtitle="`${items.length} transitions`">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Transition</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Transitions">
      <GlassTable
        :columns="columns"
        :rows="items"
        :loading="status === 'pending' && items.length === 0"
        empty-text="No transitions configured."
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-click="(row: any) => openEdit(row)"
        @row-action="onRowAction"
      >
        <template #col-from="{ row }">
          <span style="font-weight: 500; color: var(--fg-0)">{{ getStateName(row.fromStateId) }}</span>
        </template>
        <template #col-to="{ row }">
          <span style="font-weight: 500; color: var(--fg-0)">{{ getStateName(row.toStateId) }}</span>
        </template>
        <template #col-description="{ row }">{{ row.description ?? '—' }}</template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showModal"
      :title="editingItem ? 'Edit Transition' : 'New Transition'"
      icon="arrow-right"
      :accent="accent"
      @close="showModal = false">
      <div class="transition-form">
        <Select
          v-model="formFromStateId"
          :options="stateOptions"
          label="From State"
          :disabled="!!editingItem" />
        <Select
          v-model="formToStateId"
          :options="stateOptions"
          label="To State"
          :disabled="!!editingItem" />
        <Textarea
          v-model="formDescription"
          label="Description"
          placeholder="Optional description"
          :rows="2" />
        <Select v-model="formEnterJobName" :options="jobOptions" label="Enter Job" />
        <Select v-model="formExitJobName" :options="jobOptions" label="Exit Job" />

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
          :disabled="!formFromStateId || !formToStateId || saving"
          @click="handleSave">
          {{ saving ? 'Saving…' : (editingItem ? 'Update' : 'Create') }}
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete transition '${getStateName(deleteTarget.fromStateId)} → ${getStateName(deleteTarget.toStateId)}'?`"
      subtitle="This action cannot be undone."
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.transition-form {
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
