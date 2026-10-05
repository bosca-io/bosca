<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

interface WorkflowState {
  id: string
  displayOrder: number
  status: { id: string; name: string; category: string; colorHex: string }
}

interface WorkflowTransition {
  id: string
  name: string
  fromStateIds: string[]
  toStateId: string
  description: string | null
}

interface WorkflowItem {
  id: string
  name: string
  description: string | null
  initialStateId: string | null
  states: WorkflowState[]
  transitions: WorkflowTransition[]
  version: number
}

interface WorkflowScheme {
  id: string
  name: string
  description: string | null
  defaultWorkflowId: string
  version: number
}

const listGql = gql`
  query GetWorkOpsWorkflows {
    workOps {
      workflows {
        all {
          id name description initialStateId version
          states { id displayOrder status { id name category colorHex } }
          transitions { id name fromStateIds toStateId description }
        }
        schemes { id name description defaultWorkflowId version }
      }
    }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  workOps: { workflows: { all: WorkflowItem[]; schemes: WorkflowScheme[] } }
}>('workops-workflows', listGql, {})
const workflows = computed(() => data.value?.workOps?.workflows?.all ?? [])
const schemes = computed(() => data.value?.workOps?.workflows?.schemes ?? [])

const workflowOptions = computed(() => workflows.value.map(w => ({ value: w.id, label: w.name })))

const workflowColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(160px, 2fr)' },
  { key: 'states', label: 'States', width: '80px' },
  { key: 'transitions', label: 'Transitions', width: '100px' },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]

const schemeColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(160px, 2fr)' },
  { key: 'workflow', label: 'Default Workflow', width: 'minmax(140px, 1.5fr)' },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]

const categoryColors: Record<string, string> = {
  TODO: '#6c7388',
  IN_PROGRESS: '#5ec5ff',
  DONE: '#4ade80',
  CANCELLED: '#ff5d6c',
}

function getWorkflowName(id: string): string {
  return workflows.value.find(w => w.id === id)?.name ?? 'Unknown'
}

function getStateName(workflow: WorkflowItem, stateId: string): string {
  const state = workflow.states.find(s => s.id === stateId)
  return state?.status.name ?? 'Unknown'
}

// ─── Workflow Detail View ───────────────────────────────────────────────────
const selectedWorkflow = ref<WorkflowItem | null>(null)

const sortedStates = computed(() => {
  if (!selectedWorkflow.value) return []
  return [...selectedWorkflow.value.states].sort((a, b) => a.displayOrder - b.displayOrder)
})

function openDetail(wf: WorkflowItem) {
  selectedWorkflow.value = wf
}

// ─── Workflow Create ────────────────────────────────────────────────────────
const showWfCreate = ref(false)
const wfCreateForm = reactive({ name: '', description: '' })
const wfCreating = ref(false)

function resetWfCreateForm() {
  wfCreateForm.name = ''
  wfCreateForm.description = ''
}

async function handleWfCreate() {
  if (!wfCreateForm.name.trim()) return
  wfCreating.value = true
  try {
    await gqlMutation(
      gql`mutation CreateWorkflow($input: CreateWorkOpsWorkflowInput!) {
        workOps { workflows { create(input: $input) { id } } }
      }`,
      {
        input: {
          name: wfCreateForm.name.trim(),
          description: wfCreateForm.description.trim() || null,
        },
      },
    )
    showWfCreate.value = false
    resetWfCreateForm()
    toast.success('Workflow created')
    refresh()
  } catch {
    toast.error('Failed to create workflow')
  } finally {
    wfCreating.value = false
  }
}

// ─── Workflow Edit ──────────────────────────────────────────────────────────
const wfEditTarget = ref<WorkflowItem | null>(null)
const wfEditForm = reactive({ name: '', description: '', expectedVersion: 0 })
const wfEditing = ref(false)

function openWfEdit(wf: WorkflowItem) {
  wfEditTarget.value = wf
  wfEditForm.name = wf.name
  wfEditForm.description = wf.description ?? ''
  wfEditForm.expectedVersion = wf.version
}

async function handleWfUpdate() {
  if (!wfEditTarget.value || !wfEditForm.name.trim()) return
  wfEditing.value = true
  try {
    await gqlMutation(
      gql`mutation UpdateWorkflow($id: UUID!, $input: UpdateWorkOpsWorkflowInput!) {
        workOps { workflows { update(id: $id, input: $input) { id } } }
      }`,
      {
        id: wfEditTarget.value.id,
        input: {
          name: wfEditForm.name.trim(),
          description: wfEditForm.description.trim() || null,
          expectedVersion: wfEditForm.expectedVersion,
        },
      },
    )
    wfEditTarget.value = null
    toast.success('Workflow updated')
    refresh()
  } catch {
    toast.error('Failed to update — it may have been modified by another user')
  } finally {
    wfEditing.value = false
  }
}

// ─── Workflow Delete ────────────────────────────────────────────────────────
const wfDeleteTarget = ref<WorkflowItem | null>(null)
const wfDeleting = ref(false)

async function confirmWfDelete() {
  if (!wfDeleteTarget.value) return
  wfDeleting.value = true
  try {
    await gqlMutation(
      gql`mutation DeleteWorkflow($id: UUID!) {
        workOps { workflows { delete(id: $id) } }
      }`,
      { id: wfDeleteTarget.value.id },
    )
    wfDeleteTarget.value = null
    toast.success('Workflow deleted')
    refresh()
  } catch {
    toast.error('Failed to delete workflow')
  } finally {
    wfDeleting.value = false
  }
}

function handleWfAction({ action, row }: { action: string; row: WorkflowItem }) {
  if (action === 'edit') openWfEdit(row)
  else if (action === 'view') openDetail(row)
  else if (action === 'delete') wfDeleteTarget.value = row
}

// ─── Scheme Create ──────────────────────────────────────────────────────────
const showSchemeCreate = ref(false)
const schemeCreateForm = reactive({ name: '', description: '', defaultWorkflowId: '' })
const schemeCreating = ref(false)

function resetSchemeCreateForm() {
  schemeCreateForm.name = ''
  schemeCreateForm.description = ''
  schemeCreateForm.defaultWorkflowId = workflows.value[0]?.id ?? ''
}

async function handleSchemeCreate() {
  if (!schemeCreateForm.name.trim() || !schemeCreateForm.defaultWorkflowId) return
  schemeCreating.value = true
  try {
    await gqlMutation(
      gql`mutation CreateScheme($input: CreateWorkOpsWorkflowSchemeInput!) {
        workOps { workflows { createScheme(input: $input) { id } } }
      }`,
      {
        input: {
          name: schemeCreateForm.name.trim(),
          description: schemeCreateForm.description.trim() || null,
          defaultWorkflowId: schemeCreateForm.defaultWorkflowId,
        },
      },
    )
    showSchemeCreate.value = false
    resetSchemeCreateForm()
    toast.success('Scheme created')
    refresh()
  } catch {
    toast.error('Failed to create scheme')
  } finally {
    schemeCreating.value = false
  }
}

// ─── Scheme Edit ────────────────────────────────────────────────────────────
const schemeEditTarget = ref<WorkflowScheme | null>(null)
const schemeEditForm = reactive({ name: '', description: '', defaultWorkflowId: '', expectedVersion: 0 })
const schemeEditing = ref(false)

function openSchemeEdit(scheme: WorkflowScheme) {
  schemeEditTarget.value = scheme
  schemeEditForm.name = scheme.name
  schemeEditForm.description = scheme.description ?? ''
  schemeEditForm.defaultWorkflowId = scheme.defaultWorkflowId
  schemeEditForm.expectedVersion = scheme.version
}

async function handleSchemeUpdate() {
  if (!schemeEditTarget.value || !schemeEditForm.name.trim() || !schemeEditForm.defaultWorkflowId) return
  schemeEditing.value = true
  try {
    await gqlMutation(
      gql`mutation UpdateScheme($id: UUID!, $input: UpdateWorkOpsWorkflowSchemeInput!) {
        workOps { workflows { updateScheme(id: $id, input: $input) { id } } }
      }`,
      {
        id: schemeEditTarget.value.id,
        input: {
          name: schemeEditForm.name.trim(),
          description: schemeEditForm.description.trim() || null,
          defaultWorkflowId: schemeEditForm.defaultWorkflowId,
          expectedVersion: schemeEditForm.expectedVersion,
        },
      },
    )
    schemeEditTarget.value = null
    toast.success('Scheme updated')
    refresh()
  } catch {
    toast.error('Failed to update — it may have been modified by another user')
  } finally {
    schemeEditing.value = false
  }
}

// ─── Scheme Delete ──────────────────────────────────────────────────────────
const schemeDeleteTarget = ref<WorkflowScheme | null>(null)
const schemeDeleting = ref(false)

async function confirmSchemeDelete() {
  if (!schemeDeleteTarget.value) return
  schemeDeleting.value = true
  try {
    await gqlMutation(
      gql`mutation DeleteScheme($id: UUID!) {
        workOps { workflows { deleteScheme(id: $id) } }
      }`,
      { id: schemeDeleteTarget.value.id },
    )
    schemeDeleteTarget.value = null
    toast.success('Scheme deleted')
    refresh()
  } catch {
    toast.error('Failed to delete scheme')
  } finally {
    schemeDeleting.value = false
  }
}

function handleSchemeAction({ action, row }: { action: string; row: WorkflowScheme }) {
  if (action === 'edit') openSchemeEdit(row)
  else if (action === 'delete') schemeDeleteTarget.value = row
}

function openSchemeCreateWithDefault() {
  resetSchemeCreateForm()
  showSchemeCreate.value = true
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'Workflows')"
        title="Workflows"
        :subtitle="`${workflows.length} workflows, ${schemes.length} schemes`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showWfCreate = true">
            New Workflow
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Workflows" subtitle="State machines that govern task lifecycle.">
      <GlassTable
        :columns="workflowColumns"
        :rows="workflows"
        :loading="status === 'pending' && workflows.length === 0"
        empty-text="No workflows defined. Create one to get started."
        :row-actions="() => [
          { id: 'view', label: 'View Detail', icon: 'eye' },
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-action="handleWfAction"
        @row-click="openWfEdit"
      >
        <template #col-name="{ row }">
          <span class="wf-name">{{ row.name }}</span>
        </template>
        <template #col-states="{ row }">
          <Badge color="#5ec5ff">{{ row.states.length }}</Badge>
        </template>
        <template #col-transitions="{ row }">
          <Badge color="#a78bff">{{ row.transitions.length }}</Badge>
        </template>
      </GlassTable>
    </SectionCard>

    <SectionCard title="Workflow Schemes" subtitle="Map workflows to projects. The default workflow applies unless overridden per task type.">
      <template #right>
        <Button
          icon="plus"
          size="sm"
          :accent="accent"
          @click="openSchemeCreateWithDefault">
          New Scheme
        </Button>
      </template>
      <GlassTable
        :columns="schemeColumns"
        :rows="schemes"
        :loading="status === 'pending' && schemes.length === 0"
        empty-text="No workflow schemes defined. Create one to get started."
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-action="handleSchemeAction"
        @row-click="openSchemeEdit"
      >
        <template #col-name="{ row }">
          <span class="wf-name">{{ row.name }}</span>
        </template>
        <template #col-workflow="{ row }">
          <Badge color="#a78bff">{{ getWorkflowName(row.defaultWorkflowId) }}</Badge>
        </template>
      </GlassTable>
    </SectionCard>

    <!-- Workflow Create Modal -->
    <Modal
      v-if="showWfCreate"
      title="New Workflow"
      icon="plus"
      :accent="accent"
      @close="showWfCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="wfCreateForm.name"
          label="Name"
          placeholder="e.g. Software Development"
          autofocus />
        <Textarea
          v-model="wfCreateForm.description"
          label="Description"
          :rows="2"
          placeholder="Optional description" />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showWfCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!wfCreateForm.name.trim() || wfCreating"
          @click="handleWfCreate"
        >
          Create
        </Button>
      </template>
    </Modal>

    <!-- Workflow Edit Modal -->
    <Modal
      v-if="wfEditTarget"
      title="Edit Workflow"
      icon="pencil"
      :accent="accent"
      @close="wfEditTarget = null">
      <div class="form-stack">
        <TextInput v-model="wfEditForm.name" label="Name" autofocus />
        <Textarea v-model="wfEditForm.description" label="Description" :rows="2" />
      </div>

      <div v-if="wfEditTarget.states.length > 0" class="detail-section">
        <h4 class="section-heading">States</h4>
        <div class="state-list">
          <div
            v-for="state in [...wfEditTarget.states].sort((a, b) => a.displayOrder - b.displayOrder)"
            :key="state.id"
            class="state-row"
          >
            <span class="state-dot" :style="{ background: state.status.colorHex }" />
            <span class="state-name">{{ state.status.name }}</span>
            <Badge :color="categoryColors[state.status.category]" class="state-category">
              {{ state.status.category.replace('_', ' ') }}
            </Badge>
            <span v-if="state.id === wfEditTarget.initialStateId" class="initial-badge">Initial</span>
          </div>
        </div>
      </div>

      <div v-if="wfEditTarget.transitions.length > 0" class="detail-section">
        <h4 class="section-heading">Transitions</h4>
        <div class="transition-list">
          <div v-for="t in wfEditTarget.transitions" :key="t.id" class="transition-row">
            <span class="transition-name">{{ t.name }}</span>
            <span class="transition-arrow">
              <span class="from-states">
                {{ t.fromStateIds.includes('*') ? 'Any state' : t.fromStateIds.map(id => getStateName(wfEditTarget!, id)).join(', ') }}
              </span>
              →
              <span class="to-state">{{ getStateName(wfEditTarget!, t.toStateId) }}</span>
            </span>
          </div>
        </div>
      </div>

      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="wfEditTarget = null">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!wfEditForm.name.trim() || wfEditing"
          @click="handleWfUpdate"
        >
          Save Changes
        </Button>
      </template>
    </Modal>

    <!-- Workflow Detail Modal (View Only) -->
    <Modal
      v-if="selectedWorkflow && !wfEditTarget"
      :title="selectedWorkflow.name"
      icon="git-branch"
      :accent="accent"
      @close="selectedWorkflow = null"
    >
      <div class="workflow-detail">
        <p v-if="selectedWorkflow.description" class="wf-desc">{{ selectedWorkflow.description }}</p>

        <div class="detail-section">
          <h4 class="section-heading">States</h4>
          <div class="state-list">
            <div v-for="state in sortedStates" :key="state.id" class="state-row">
              <span class="state-dot" :style="{ background: state.status.colorHex }" />
              <span class="state-name">{{ state.status.name }}</span>
              <Badge :color="categoryColors[state.status.category]" class="state-category">
                {{ state.status.category.replace('_', ' ') }}
              </Badge>
              <span v-if="state.id === selectedWorkflow.initialStateId" class="initial-badge">Initial</span>
            </div>
          </div>
        </div>

        <div class="detail-section">
          <h4 class="section-heading">Transitions</h4>
          <div class="transition-list">
            <div v-for="t in selectedWorkflow.transitions" :key="t.id" class="transition-row">
              <span class="transition-name">{{ t.name }}</span>
              <span class="transition-arrow">
                <span class="from-states">
                  {{ t.fromStateIds.includes('*') ? 'Any state' : t.fromStateIds.map(id => getStateName(selectedWorkflow!, id)).join(', ') }}
                </span>
                →
                <span class="to-state">{{ getStateName(selectedWorkflow!, t.toStateId) }}</span>
              </span>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <Button size="sm" @click="openWfEdit(selectedWorkflow!); selectedWorkflow = null">Edit</Button>
        <span class="spacer" />
        <Button size="sm" @click="selectedWorkflow = null">Close</Button>
      </template>
    </Modal>

    <!-- Scheme Create Modal -->
    <Modal
      v-if="showSchemeCreate"
      title="New Workflow Scheme"
      icon="plus"
      :accent="accent"
      @close="showSchemeCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="schemeCreateForm.name"
          label="Name"
          placeholder="e.g. Default Scheme"
          autofocus />
        <Select v-model="schemeCreateForm.defaultWorkflowId" label="Default Workflow" :options="workflowOptions" />
        <Textarea
          v-model="schemeCreateForm.description"
          label="Description"
          :rows="2"
          placeholder="Optional description" />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showSchemeCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!schemeCreateForm.name.trim() || !schemeCreateForm.defaultWorkflowId || schemeCreating"
          @click="handleSchemeCreate"
        >
          Create
        </Button>
      </template>
    </Modal>

    <!-- Scheme Edit Modal -->
    <Modal
      v-if="schemeEditTarget"
      title="Edit Workflow Scheme"
      icon="pencil"
      :accent="accent"
      @close="schemeEditTarget = null">
      <div class="form-stack">
        <TextInput v-model="schemeEditForm.name" label="Name" autofocus />
        <Select v-model="schemeEditForm.defaultWorkflowId" label="Default Workflow" :options="workflowOptions" />
        <Textarea v-model="schemeEditForm.description" label="Description" :rows="2" />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="schemeEditTarget = null">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!schemeEditForm.name.trim() || !schemeEditForm.defaultWorkflowId || schemeEditing"
          @click="handleSchemeUpdate"
        >
          Save Changes
        </Button>
      </template>
    </Modal>

    <!-- Delete Confirmations -->
    <ConfirmModal
      v-if="wfDeleteTarget"
      :title="`Delete '${wfDeleteTarget.name}'?`"
      :loading="wfDeleting"
      @close="wfDeleteTarget = null"
      @confirm="confirmWfDelete"
    />
    <ConfirmModal
      v-if="schemeDeleteTarget"
      :title="`Delete '${schemeDeleteTarget.name}'?`"
      :loading="schemeDeleting"
      @close="schemeDeleteTarget = null"
      @confirm="confirmSchemeDelete"
    />
  </PageShell>
</template>

<style scoped>
.form-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.wf-name {
  font-weight: 500;
  color: var(--fg-0);
}

.spacer {
  flex: 1;
}

.workflow-detail {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.wf-desc {
  color: var(--fg-2);
  font-size: 13px;
  margin: 0;
}

.detail-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 16px;
}

.section-heading {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0;
}

.state-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.state-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: var(--bg-2);
  border-radius: var(--r-sm);
}

.state-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.state-name {
  font-weight: 500;
  font-size: 13px;
  color: var(--fg-0);
}

.state-category {
  margin-left: auto;
}

.initial-badge {
  font-size: 10px;
  font-weight: 600;
  color: var(--brand-2);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.transition-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.transition-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: var(--bg-2);
  border-radius: var(--r-sm);
}

.transition-name {
  font-weight: 500;
  font-size: 13px;
  color: var(--fg-0);
  min-width: 100px;
}

.transition-arrow {
  font-size: 12px;
  color: var(--fg-2);
  display: flex;
  align-items: center;
  gap: 6px;
}

.from-states {
  color: var(--fg-1);
}

.to-state {
  color: var(--fg-0);
  font-weight: 500;
}
</style>
