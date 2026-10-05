<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()

const showCreate = ref(false)
const saving = ref(false)
const error = ref('')
const selectedProgramId = ref('')

const form = reactive({ name: '', description: '', targetDate: '', programId: '' })

const programsGql = gql`
  query { workOps { programs { all { id key name } } } }
`

const { data: programsData } = useAsyncQuery<{
  workOps: { programs: { all: Array<{ id: string; key: string; name: string }> } }
}>('workops-milestones-programs', programsGql)

const programs = computed(() => programsData.value?.workOps?.programs?.all ?? [])
const programOptions = computed(() => programs.value.map(p => ({ value: p.id, label: `${p.key} — ${p.name}` })))

const milestonesGql = gql`
  query GetMilestones($programId: UUID!) {
    workOps {
      milestones {
        byProgram(programId: $programId) {
          id
          name
          description
          state
          targetDate
          closedAt
          programId
          program { id key name }
          version
        }
      }
    }
  }
`

interface Milestone {
  id: string
  name: string
  description: string | null
  state: string
  targetDate: string | null
  closedAt: string | null
  programId: string
  program: { id: string; key: string; name: string }
  version: number
}

const { data: milestonesData, status, refresh } = useAsyncQuery<{
  workOps: { milestones: { byProgram: Milestone[] } }
}>('workops-milestones', milestonesGql, { programId: selectedProgramId }, { server: false })

const milestones = computed(() => milestonesData.value?.workOps?.milestones?.byProgram ?? [])
const isLoading = computed(() => status.value === 'pending')

watch(programs, (ps) => {
  if (ps.length && !selectedProgramId.value && ps[0]) {
    selectedProgramId.value = ps[0].id
  }
}, { immediate: true })

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: '1.5fr' },
  { key: 'program', label: 'Program', width: '1fr' },
  { key: 'state', label: 'State', width: '100px' },
  { key: 'target', label: 'Target Date', width: '160px' },
  { key: 'closed', label: 'Closed', width: '140px', muted: true },
]

function targetDateStatus(m: Milestone): { color: string; label: string } {
  if (m.state === 'CLOSED') return { color: 'var(--fg-3)', label: '' }
  if (!m.targetDate) return { color: 'var(--fg-3)', label: '' }
  const diff = Math.floor((new Date(m.targetDate).getTime() - Date.now()) / 86_400_000)
  if (diff < 0) return { color: 'var(--err)', label: `${Math.abs(diff)}d overdue` }
  if (diff === 0) return { color: 'var(--warn)', label: 'Due today' }
  if (diff <= 7) return { color: 'var(--warn)', label: `${diff}d left` }
  return { color: 'var(--fg-2)', label: `${diff}d left` }
}

function formatDate(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

function openCreate() {
  form.name = ''
  form.description = ''
  form.targetDate = ''
  form.programId = selectedProgramId.value
  error.value = ''
  showCreate.value = true
}

async function handleCreate() {
  if (!form.name || !form.programId) { error.value = 'Name and program required.'; return }
  saving.value = true
  error.value = ''
  try {
    await mutation(gql`
      mutation CreateMilestone($input: CreateWorkOpsMilestoneInput!) {
        workOps { milestones { create(input: $input) { id } } }
      }
    `, {
      input: {
        name: form.name,
        description: form.description || null,
        programId: form.programId,
        targetDate: form.targetDate || null,
      },
    })
    showCreate.value = false
    await refresh()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to create milestone' } finally { saving.value = false }
}

async function closeMilestone(m: Milestone) {
  try {
    await mutation(gql`
      mutation CloseMilestone($id: UUID!, $expectedVersion: Long!) {
        workOps { milestones { close(id: $id, expectedVersion: $expectedVersion) { id } } }
      }
    `, { id: m.id, expectedVersion: m.version })
    await refresh()
  } catch (e: unknown) { error.value = e instanceof Error ? e.message : 'Failed to close milestone' }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Milestones')"
        title="Milestones"
        :subtitle="`${milestones.length} milestones`"
      >
        <template #actions>
          <div class="header-actions">
            <Select
              v-model="selectedProgramId"
              :options="programOptions"
              placeholder="Select program"
              size="sm"
              :accent="accent" />
            <Button
              primary
              icon="plus"
              size="sm"
              :accent="accent"
              :disabled="!selectedProgramId"
              @click="openCreate">
              New Milestone
            </Button>
          </div>
        </template>
      </PageHeader>
    </template>

    <GlassTable
      :columns="columns"
      :rows="milestones"
      row-key="id"
      :loading="isLoading"
      empty-text="No milestones for this program."
    >
      <template #col-name="{ value, row }">
        <div class="milestone-name-cell">
          <Icon name="flag" :size="14" :color="row.state === 'OPEN' ? accent : '#34d99a'" />
          <span class="milestone-name">{{ value }}</span>
        </div>
      </template>
      <template #col-program="{ row }">
        {{ row.program?.name ?? '—' }}
      </template>
      <template #col-state="{ row }">
        <Badge :color="row.state === 'OPEN' ? accent : '#34d99a'" :solid="row.state === 'CLOSED'">
          {{ row.state }}
        </Badge>
      </template>
      <template #col-target="{ row }">
        <div class="target-cell">
          <span>{{ formatDate(row.targetDate) }}</span>
          <span v-if="targetDateStatus(row).label" class="target-hint" :style="{ color: targetDateStatus(row).color }">
            {{ targetDateStatus(row).label }}
          </span>
        </div>
      </template>
      <template #col-closed="{ row }">
        {{ formatDate(row.closedAt) }}
      </template>
      <template #actions="{ row }">
        <Button v-if="row.state === 'OPEN'" size="sm" @click.stop="closeMilestone(row as Milestone)">Close</Button>
      </template>
    </GlassTable>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Milestone"
      icon="flag"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="Q2 Release" />
        <TextInput v-model="form.description" label="Description" placeholder="Optional description" />
        <Select
          v-model="form.programId"
          :options="programOptions"
          placeholder="Program"
          :accent="accent" />
        <TextInput
          v-model="form.targetDate"
          label="Target Date"
          placeholder="2025-06-30"
          mono />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreate">Create</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.header-actions { display: flex; align-items: center; gap: 8px; }

.milestone-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.milestone-name {
  font-weight: 500;
  color: var(--fg-0);
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-error {
  color: var(--err);
  font-size: 12px;
  margin: 0;
}

.target-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.target-hint {
  font-size: 11px;
  font-weight: 500;
}
</style>
