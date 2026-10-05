<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

/**
 * Automation Rules — the WorkOps engine. Rules are scoped to a portfolio / program / project
 * (or global) and fire on task lifecycle events (create / update / transition / comment / delete),
 * acting on tasks. Platform-wide event→action bindings live under System → Triggers instead.
 */

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation, query: gqlQuery } = useGraphQL()
const toast = useToast()

// Enums matched to the backend (the previous stub used SKIP/RETRY/HALT and only GLOBAL/PROJECT).
const scopeOptions = [
  { value: 'GLOBAL', label: 'Global' },
  { value: 'PORTFOLIO', label: 'Portfolio' },
  { value: 'PROGRAM', label: 'Program' },
  { value: 'PROJECT', label: 'Project' },
]
const failureModeOptions = [
  { value: 'STOP_ON_ERROR', label: 'Stop on error' },
  { value: 'CONTINUE', label: 'Continue' },
]
const triggerTypeOptions = [
  { value: 'TaskCreated', label: 'Task created' },
  { value: 'TaskUpdated', label: 'Task updated' },
  { value: 'TaskTransitioned', label: 'Task transitioned' },
  { value: 'TaskCommented', label: 'Task commented' },
  { value: 'TaskDeleted', label: 'Task deleted' },
  { value: 'SlaBreached', label: 'SLA breached' },
  { value: 'SlaAtRisk', label: 'SLA at risk' },
]

function isValidJson(str: string): boolean {
  try { JSON.parse(str); return true }
  catch { return false }
}

interface AutomationRule {
  id: string
  name: string
  description: string | null
  enabled: boolean
  scope: string
  scopeId: string | null
  trigger: Record<string, unknown>
  conditions: unknown[]
  actions: unknown[]
  failureMode: string
  maxFiresPerTaskPerHour: number
  executionLogRetentionDays: number
  runAsProfileId: string
  version: number
}

const scope = ref('GLOBAL')
const { data, status, refresh } = useAsyncQuery<{
  workOps: { automation: { rules: AutomationRule[] } }
}>('workops-automation', gql`
  query GetWorkOpsAutomationRules($scope: String!, $scopeId: UUID) {
    workOps { automation { rules(scope: $scope, scopeId: $scopeId) {
      id name description enabled scope scopeId
      trigger conditions actions
      failureMode maxFiresPerTaskPerHour executionLogRetentionDays runAsProfileId version
    } } }
  }
`, { scope })
const rules = computed(() => data.value?.workOps?.automation?.rules ?? [])

const columns: GlassTableColumn[] = [
  { key: 'enabled', label: '', width: '48px' },
  { key: 'name', label: 'Name', width: 'minmax(160px, 2fr)' },
  { key: 'trigger', label: 'Trigger', width: 'minmax(120px, 1fr)' },
  { key: 'scope', label: 'Scope', width: '110px' },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]
const rowActions = [
  { id: 'edit', label: 'Edit', icon: 'pencil' },
  { id: 'history', label: 'Run history', icon: 'pulse' },
  { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
]

function triggerType(trigger: Record<string, unknown> | undefined): string {
  return (trigger?.type as string) ?? 'Unknown'
}

// ─── Create / edit ─────────────────────────────────────────────────────────────────────────
const emptyForm = () => ({
  name: '',
  description: '',
  enabled: true,
  scope: 'GLOBAL',
  scopeId: '',
  triggerType: 'TaskCreated',
  trigger: '{\n  "type": "TaskCreated"\n}',
  conditions: '[]',
  actions: '[]',
  failureMode: 'STOP_ON_ERROR',
  maxFiresPerTaskPerHour: 5,
  executionLogRetentionDays: 30,
  runAsProfileId: '',
  expectedVersion: 0,
})
const form = reactive(emptyForm())
const editId = ref<string | null>(null)
const showModal = ref(false)
const saving = ref(false)

const valid = computed(() =>
  !!form.name.trim()
  && !!form.runAsProfileId.trim()
  && isValidJson(form.trigger)
  && isValidJson(form.conditions)
  && isValidJson(form.actions),
)

function openCreate() {
  Object.assign(form, emptyForm())
  editId.value = null
  showModal.value = true
}
function openEdit(rule: AutomationRule) {
  Object.assign(form, {
    name: rule.name,
    description: rule.description ?? '',
    enabled: rule.enabled,
    scope: rule.scope,
    scopeId: rule.scopeId ?? '',
    triggerType: triggerType(rule.trigger),
    trigger: JSON.stringify(rule.trigger ?? {}, null, 2),
    conditions: JSON.stringify(rule.conditions ?? [], null, 2),
    actions: JSON.stringify(rule.actions ?? [], null, 2),
    failureMode: rule.failureMode,
    maxFiresPerTaskPerHour: rule.maxFiresPerTaskPerHour,
    executionLogRetentionDays: rule.executionLogRetentionDays,
    runAsProfileId: rule.runAsProfileId,
    expectedVersion: rule.version,
  })
  editId.value = rule.id
  showModal.value = true
}

// Keep the trigger JSON's discriminant in sync with the picker, preserving any extra keys.
function onTriggerTypeChange(value: string) {
  form.triggerType = value
  try {
    const parsed = JSON.parse(form.trigger) as Record<string, unknown>
    parsed.type = value
    form.trigger = JSON.stringify(parsed, null, 2)
  }
  catch {
    form.trigger = JSON.stringify({ type: value }, null, 2)
  }
}

function input() {
  return {
    name: form.name.trim(),
    description: form.description.trim() || null,
    enabled: form.enabled,
    scope: form.scope,
    scopeId: form.scopeId.trim() || null,
    trigger: JSON.parse(form.trigger),
    conditions: JSON.parse(form.conditions),
    actions: JSON.parse(form.actions),
    failureMode: form.failureMode,
    maxFiresPerTaskPerHour: form.maxFiresPerTaskPerHour,
    executionLogRetentionDays: form.executionLogRetentionDays,
    runAsProfileId: form.runAsProfileId.trim(),
  }
}

async function save() {
  if (!valid.value) return
  saving.value = true
  try {
    if (editId.value) {
      await gqlMutation(gql`
        mutation UpdateAutomationRule($id: UUID!, $input: WorkOpsAutomationRuleInput!, $expectedVersion: Long!) {
          workOps { automation { update(id: $id, input: $input, expectedVersion: $expectedVersion) { id } } }
        }
      `, { id: editId.value, input: input(), expectedVersion: form.expectedVersion })
      toast.success('Automation rule updated')
    }
    else {
      await gqlMutation(gql`
        mutation CreateAutomationRule($input: WorkOpsAutomationRuleInput!) {
          workOps { automation { create(input: $input) { id } } }
        }
      `, { input: input() })
      toast.success('Automation rule created')
    }
    showModal.value = false
    refresh()
  }
  catch {
    toast.error('Failed to save rule — it may have been modified by another user')
  }
  finally {
    saving.value = false
  }
}

async function toggle(rule: AutomationRule) {
  try {
    await gqlMutation(gql`
      mutation SetAutomationRuleEnabled($id: UUID!, $enabled: Boolean!, $expectedVersion: Long!) {
        workOps { automation { setEnabled(id: $id, enabled: $enabled, expectedVersion: $expectedVersion) { id } } }
      }
    `, { id: rule.id, enabled: !rule.enabled, expectedVersion: rule.version })
    refresh()
  }
  catch {
    toast.error('Failed to toggle rule')
  }
}

const deleteTarget = ref<AutomationRule | null>(null)
const deleting = ref(false)
async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await gqlMutation(gql`
      mutation DeleteAutomationRule($id: UUID!) { workOps { automation { delete(id: $id) } } }
    `, { id: deleteTarget.value.id })
    deleteTarget.value = null
    toast.success('Automation rule deleted')
    refresh()
  }
  catch {
    toast.error('Failed to delete rule')
  }
  finally {
    deleting.value = false
  }
}

function onRowAction({ action, row }: { action: string, row: AutomationRule }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'history') openHistory(row)
  else if (action === 'delete') deleteTarget.value = row
}

// ─── Run history ───────────────────────────────────────────────────────────────────────────
interface ExecutionRow { id: string, outcome: string, startedAt: string, durationMs: number | null, detail: string | null }
const historyOpen = ref(false)
const historyTitle = ref('')
const historyLoading = ref(false)
const historyRows = ref<ExecutionRow[]>([])
const historyColumns: GlassTableColumn[] = [
  { key: 'outcome', label: 'Outcome', width: '160px' },
  { key: 'detail', label: 'Detail', width: 'minmax(140px, 1fr)', muted: true },
  { key: 'startedAt', label: 'Started', width: '180px' },
  { key: 'durationMs', label: 'Duration', width: '100px' },
]

async function openHistory(rule: AutomationRule) {
  historyTitle.value = rule.name
  historyOpen.value = true
  historyLoading.value = true
  historyRows.value = []
  try {
    const res = await gqlQuery<{ workOps: { automation: { executionLogs: Array<{
      id: string, outcome: string, startedAt: string, durationMs: number | null, errorMessage: string | null, taskId: string | null
    }> } } }>(gql`
      query GetAutomationExecutionLogs($ruleId: UUID!) {
        workOps { automation { executionLogs(ruleId: $ruleId, offset: 0, limit: 50) {
          id outcome startedAt durationMs errorMessage taskId
        } } }
      }
    `, { ruleId: rule.id })
    historyRows.value = (res?.workOps?.automation?.executionLogs ?? []).map(r => ({
      id: r.id,
      outcome: r.outcome,
      startedAt: r.startedAt,
      durationMs: r.durationMs,
      detail: r.errorMessage ?? (r.taskId ? `task ${r.taskId.slice(0, 8)}` : null),
    }))
  }
  catch {
    toast.error('Failed to load run history')
  }
  finally {
    historyLoading.value = false
  }
}

function outcomeColor(outcome: string): string {
  if (outcome === 'OK') return '#34d399'
  if (outcome === 'SKIPPED') return '#94a3b8'
  if (outcome === 'NOT_IMPLEMENTED') return '#fbbf24'
  return '#f87171'
}
function fmtTime(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'Automation')"
        title="Automation"
        subtitle="Rules that run when task events fire"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate"
          >
            New Rule
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard
      title="Automation Rules"
      subtitle="Scoped task-event rules run by the WorkOps engine. Platform event bindings live under System → Triggers."
    >
      <template #right>
        <Select
          v-model="scope"
          :options="scopeOptions"
          size="sm"
          :accent="accent"
        />
      </template>
      <GlassTable
        :columns="columns"
        :rows="rules"
        :loading="status === 'pending' && rules.length === 0"
        empty-text="No automation rules in this scope yet."
        :row-actions="rowActions"
        @row-action="onRowAction"
        @row-click="openEdit"
      >
        <template #col-enabled="{ row }">
          <Switch
            :model-value="row.enabled"
            :accent="accent"
            @update:model-value="() => toggle(row)"
            @click.stop
          />
        </template>
        <template #col-name="{ row }">
          <span class="strong">{{ row.name }}</span>
        </template>
        <template #col-trigger="{ row }">
          <Badge :color="accent">
            {{ triggerType(row.trigger) }}
          </Badge>
        </template>
        <template #col-scope="{ row }">
          <span class="muted">{{ row.scope }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showModal"
      :title="editId ? 'Edit Automation Rule' : 'New Automation Rule'"
      icon="wand"
      :accent="accent"
      width="640px"
      @close="showModal = false"
    >
      <div class="form-stack">
        <div class="grid2">
          <TextInput
            v-model="form.name"
            label="Name"
            placeholder="Auto-assign on create"
            autofocus
          />
          <Select
            :model-value="form.triggerType"
            :options="triggerTypeOptions"
            label="Trigger"
            @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && onTriggerTypeChange(v)"
          />
        </div>
        <TextInput
          v-model="form.description"
          label="Description"
          placeholder="Optional"
        />
        <div class="grid2">
          <Select
            v-model="form.scope"
            :options="scopeOptions"
            label="Scope"
          />
          <TextInput
            v-model="form.scopeId"
            label="Scope ID"
            placeholder="UUID (blank for GLOBAL)"
            mono
          />
        </div>
        <div class="grid2">
          <Select
            v-model="form.failureMode"
            :options="failureModeOptions"
            label="On action failure"
          />
          <TextInput
            v-model="form.runAsProfileId"
            label="Run as profile ID"
            placeholder="UUID"
            mono
          />
        </div>
        <div class="grid2">
          <NumberInput
            v-model="form.maxFiresPerTaskPerHour"
            label="Max fires / task / hour"
            :min="1"
          />
          <NumberInput
            v-model="form.executionLogRetentionDays"
            label="Log retention (days)"
            :min="1"
          />
        </div>
        <Textarea
          v-model="form.trigger"
          label="Trigger filters (JSON)"
          :rows="3"
          class="mono-area"
        />
        <Textarea
          v-model="form.conditions"
          label="Conditions (JSON array)"
          :rows="3"
          class="mono-area"
        />
        <Textarea
          v-model="form.actions"
          label="Actions (JSON array)"
          :rows="4"
          class="mono-area"
        />
        <Switch
          v-model="form.enabled"
          label="Enabled"
          :accent="accent"
        />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showModal = false">
          Cancel
        </Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!valid || saving"
          @click="save"
        >
          {{ editId ? 'Save' : 'Create' }}
        </Button>
      </template>
    </Modal>

    <Modal
      v-if="historyOpen"
      title="Run history"
      :subtitle="historyTitle"
      icon="pulse"
      :accent="accent"
      width="720px"
      @close="historyOpen = false"
    >
      <GlassTable
        :columns="historyColumns"
        :rows="historyRows"
        :loading="historyLoading"
        empty-text="No executions recorded yet."
      >
        <template #col-outcome="{ row }">
          <Badge :color="outcomeColor(row.outcome)">
            {{ row.outcome }}
          </Badge>
        </template>
        <template #col-startedAt="{ row }">
          <span class="muted">{{ fmtTime(row.startedAt) }}</span>
        </template>
        <template #col-durationMs="{ row }">
          <span class="muted">{{ row.durationMs == null ? '—' : `${row.durationMs} ms` }}</span>
        </template>
      </GlassTable>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="historyOpen = false">
          Close
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deleteTarget"
      title="Delete automation rule?"
      :subtitle="deleteTarget.name"
      :loading="deleting"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    >
      This permanently deletes the rule and its run history.
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.form-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.grid2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.spacer {
  flex: 1;
}
.strong {
  font-weight: 600;
}
.muted {
  color: var(--text-muted, #94a3b8);
}
.mono-area :deep(textarea) {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
}
</style>
