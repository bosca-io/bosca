<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

interface SlaGoal {
  id: string
  name: string
  targetMinutes: number
  atRiskAtPercent: number
  startConditions: string
  stopConditions: string
  pauseConditions: string | null
  displayOrder: number
}

interface SlaPolicy {
  id: string
  name: string
  description: string | null
  goals: SlaGoal[]
  version: number
}

interface WorkingCalendar {
  id: string
  name: string
  description: string | null
  timeZone: string
  weeklyHours: Record<string, unknown>
  holidays: unknown[]
  version: number
}

const listGql = gql`
  query GetWorkOpsSla {
    workOps {
      sla {
        slaPolicies { id name description goals { id name targetMinutes atRiskAtPercent startConditions stopConditions pauseConditions displayOrder } version }
        workingCalendars { id name description timeZone weeklyHours holidays version }
      }
    }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  workOps: { sla: { slaPolicies: SlaPolicy[]; workingCalendars: WorkingCalendar[] } }
}>('workops-sla', listGql, {})
const policies = computed(() => data.value?.workOps?.sla?.slaPolicies ?? [])
const calendars = computed(() => data.value?.workOps?.sla?.workingCalendars ?? [])

const policyColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(160px, 2fr)' },
  { key: 'goals', label: 'Goals', width: '80px' },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]

const calendarColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(160px, 2fr)' },
  { key: 'timezone', label: 'Time Zone', width: 'minmax(140px, 1fr)' },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]

// ─── Create Policy ───────────────────────────────────────────────────────────
const showCreatePolicy = ref(false)
const policyForm = reactive({ name: '', description: '' })
const policySaving = ref(false)

async function handleCreatePolicy() {
  if (!policyForm.name.trim()) return
  policySaving.value = true
  try {
    await gqlMutation(
      gql`mutation CreateSlaPolicy($name: String!, $description: String) {
        workOps { sla { createSlaPolicy(name: $name, description: $description) { id } } }
      }`,
      { name: policyForm.name.trim(), description: policyForm.description.trim() || null },
    )
    showCreatePolicy.value = false
    policyForm.name = ''
    policyForm.description = ''
    toast.success('SLA policy created')
    refresh()
  } catch {
    toast.error('Failed to create SLA policy')
  } finally {
    policySaving.value = false
  }
}

// ─── Create Calendar ─────────────────────────────────────────────────────────
const showCreateCalendar = ref(false)
const calendarForm = reactive({
  name: '',
  description: '',
  timeZone: 'America/New_York',
  weeklyHours: '{\n  "MONDAY": [{"startLocal": "09:00", "endLocal": "17:00"}],\n  "TUESDAY": [{"startLocal": "09:00", "endLocal": "17:00"}],\n  "WEDNESDAY": [{"startLocal": "09:00", "endLocal": "17:00"}],\n  "THURSDAY": [{"startLocal": "09:00", "endLocal": "17:00"}],\n  "FRIDAY": [{"startLocal": "09:00", "endLocal": "17:00"}]\n}',
  holidays: '[]',
})
const calendarSaving = ref(false)

function isValidJson(str: string): boolean {
  try { JSON.parse(str); return true } catch { return false }
}

const calendarValid = computed(() =>
  calendarForm.name.trim()
  && calendarForm.timeZone.trim()
  && isValidJson(calendarForm.weeklyHours)
  && isValidJson(calendarForm.holidays),
)

async function handleCreateCalendar() {
  if (!calendarValid.value) return
  calendarSaving.value = true
  try {
    await gqlMutation(
      gql`mutation CreateWorkingCalendar($input: WorkOpsWorkingCalendarInput!) {
        workOps { sla { createWorkingCalendar(input: $input) { id } } }
      }`,
      {
        input: {
          name: calendarForm.name.trim(),
          description: calendarForm.description.trim() || null,
          timeZone: calendarForm.timeZone.trim(),
          weeklyHours: JSON.parse(calendarForm.weeklyHours),
          holidays: JSON.parse(calendarForm.holidays),
        },
      },
    )
    showCreateCalendar.value = false
    calendarForm.name = ''
    calendarForm.description = ''
    toast.success('Working calendar created')
    refresh()
  } catch {
    toast.error('Failed to create working calendar')
  } finally {
    calendarSaving.value = false
  }
}

// ─── Policy Detail ───────────────────────────────────────────────────────────
const selectedPolicy = ref<SlaPolicy | null>(null)

function formatMinutes(mins: number): string {
  if (mins < 60) return `${mins}m`
  const h = Math.floor(mins / 60)
  const m = mins % 60
  return m ? `${h}h ${m}m` : `${h}h`
}

// ─── Calendar Detail ─────────────────────────────────────────────────────────
const selectedCalendar = ref<WorkingCalendar | null>(null)
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'SLA')"
        title="SLA"
        :subtitle="`${policies.length} policies, ${calendars.length} calendars`"
      >
        <template #actions>
          <Button
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreateCalendar = true">
            New Calendar
          </Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreatePolicy = true">
            New Policy
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="SLA Policies" subtitle="Define service level agreements with time-based goals for task resolution.">
      <GlassTable
        :columns="policyColumns"
        :rows="policies"
        :loading="status === 'pending' && policies.length === 0"
        empty-text="No SLA policies configured."
        @row-click="(row: SlaPolicy) => selectedPolicy = row"
      >
        <template #col-name="{ row }">
          <span class="item-name">{{ row.name }}</span>
        </template>
        <template #col-goals="{ row }">
          <Badge color="#a78bff">{{ row.goals.length }}</Badge>
        </template>
      </GlassTable>
    </SectionCard>

    <SectionCard title="Working Calendars" subtitle="Define business hours and holidays used to calculate SLA timers.">
      <GlassTable
        :columns="calendarColumns"
        :rows="calendars"
        :loading="status === 'pending' && calendars.length === 0"
        empty-text="No working calendars configured."
        @row-click="(row: WorkingCalendar) => selectedCalendar = row"
      >
        <template #col-name="{ row }">
          <span class="item-name">{{ row.name }}</span>
        </template>
        <template #col-timezone="{ row }">
          <span class="tz-label">{{ row.timeZone }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <!-- Create Policy Modal -->
    <Modal
      v-if="showCreatePolicy"
      title="New SLA Policy"
      icon="clock"
      :accent="accent"
      @close="showCreatePolicy = false">
      <div class="form-stack">
        <TextInput
          v-model="policyForm.name"
          label="Name"
          placeholder="e.g. Standard Support SLA"
          autofocus />
        <Textarea
          v-model="policyForm.description"
          label="Description"
          :rows="2"
          placeholder="Optional description" />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showCreatePolicy = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!policyForm.name.trim() || policySaving"
          @click="handleCreatePolicy">
          Create
        </Button>
      </template>
    </Modal>

    <!-- Create Calendar Modal -->
    <Modal
      v-if="showCreateCalendar"
      title="New Working Calendar"
      icon="calendar"
      :accent="accent"
      @close="showCreateCalendar = false">
      <div class="form-stack">
        <TextInput
          v-model="calendarForm.name"
          label="Name"
          placeholder="e.g. US Business Hours"
          autofocus />
        <Textarea
          v-model="calendarForm.description"
          label="Description"
          :rows="2"
          placeholder="Optional description" />
        <TextInput v-model="calendarForm.timeZone" label="Time Zone" placeholder="e.g. America/New_York" />
        <Textarea
          v-model="calendarForm.weeklyHours"
          label="Weekly Hours (JSON)"
          :rows="6"
          class="mono-input" />
        <Textarea
          v-model="calendarForm.holidays"
          label="Holidays (JSON)"
          :rows="3"
          class="mono-input" />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showCreateCalendar = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!calendarValid || calendarSaving"
          @click="handleCreateCalendar">
          Create
        </Button>
      </template>
    </Modal>

    <!-- Policy Detail Modal -->
    <Modal
      v-if="selectedPolicy"
      :title="selectedPolicy.name"
      icon="clock"
      :accent="accent"
      @close="selectedPolicy = null">
      <div class="detail-content">
        <p v-if="selectedPolicy.description" class="detail-desc">{{ selectedPolicy.description }}</p>

        <div class="detail-section">
          <h4 class="section-heading">Goals</h4>
          <div v-if="selectedPolicy.goals.length === 0" class="empty-goals">No goals defined for this policy.</div>
          <div v-for="goal in selectedPolicy.goals" :key="goal.id" class="goal-row">
            <div class="goal-header">
              <span class="goal-name">{{ goal.name }}</span>
              <Badge color="#ffb547">{{ formatMinutes(goal.targetMinutes) }}</Badge>
              <span class="goal-risk">At risk: {{ goal.atRiskAtPercent }}%</span>
            </div>
            <div class="goal-conditions">
              <span>Start: <code>{{ goal.startConditions }}</code></span>
              <span>Stop: <code>{{ goal.stopConditions }}</code></span>
              <span v-if="goal.pauseConditions">Pause: <code>{{ goal.pauseConditions }}</code></span>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="selectedPolicy = null">Close</Button>
      </template>
    </Modal>

    <!-- Calendar Detail Modal -->
    <Modal
      v-if="selectedCalendar"
      :title="selectedCalendar.name"
      icon="calendar"
      :accent="accent"
      @close="selectedCalendar = null">
      <div class="detail-content">
        <p v-if="selectedCalendar.description" class="detail-desc">{{ selectedCalendar.description }}</p>
        <div class="detail-row">
          <span class="detail-label">Time Zone:</span>
          <span class="detail-value">{{ selectedCalendar.timeZone }}</span>
        </div>
        <div class="detail-section">
          <h4 class="section-heading">Weekly Hours</h4>
          <pre class="json-block">{{ JSON.stringify(selectedCalendar.weeklyHours, null, 2) }}</pre>
        </div>
        <div v-if="(selectedCalendar.holidays as unknown[]).length > 0" class="detail-section">
          <h4 class="section-heading">Holidays</h4>
          <pre class="json-block">{{ JSON.stringify(selectedCalendar.holidays, null, 2) }}</pre>
        </div>
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="selectedCalendar = null">Close</Button>
      </template>
    </Modal>
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

.item-name {
  font-weight: 500;
  color: var(--fg-0);
}

.tz-label {
  font-size: 12px;
  font-family: var(--font-mono);
  color: var(--fg-2);
}

.detail-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail-desc {
  margin: 0;
  color: var(--fg-2);
  font-size: 13px;
}

.detail-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.detail-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
}

.detail-value {
  font-size: 13px;
  font-family: var(--font-mono);
  color: var(--fg-0);
}

.detail-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.section-heading {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0;
}

.empty-goals {
  font-size: 13px;
  color: var(--fg-3);
  font-style: italic;
}

.goal-row {
  padding: 10px 12px;
  background: var(--bg-2);
  border-radius: var(--r-sm);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.goal-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.goal-name {
  font-weight: 500;
  font-size: 13px;
  color: var(--fg-0);
}

.goal-risk {
  font-size: 11px;
  color: var(--fg-2);
  margin-left: auto;
}

.goal-conditions {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  color: var(--fg-2);
}

.goal-conditions code {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-1);
}

.json-block {
  margin: 0;
  padding: 12px;
  background: var(--bg-2);
  border-radius: var(--r-sm);
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-1);
  overflow-x: auto;
  white-space: pre;
}

:deep(.mono-input textarea) {
  font-family: var(--font-mono);
  font-size: 12px;
}
</style>
