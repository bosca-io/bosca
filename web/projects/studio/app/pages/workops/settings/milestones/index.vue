<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

// ── Queries ──────────────────────────────────────────────────────────

const programsGql = gql`
  query MilestonePrograms {
    workOps { programs { all { id key name } } }
  }
`

const milestonesGql = gql`
  query MilestonesByProgram($programId: UUID!) {
    workOps { milestones {
      byProgram(programId: $programId) {
        id name description targetDate state closedAt version
      }
    } }
  }
`

// ── Data fetching ────────────────────────────────────────────────────

interface ProgramItem { id: string; key: string; name: string }

const { data: programsData } = useAsyncQuery<{
  workOps: { programs: { all: ProgramItem[] } }
}>('milestone-programs', programsGql, {})

const programs = computed(() => programsData.value?.workOps?.programs?.all ?? [])
const programOptions = computed<SelectOption[]>(() =>
  programs.value.map((p) => ({ value: p.id, label: `${p.key} - ${p.name}` })),
)

const selectedProgram = ref<string | undefined>(undefined)

watch(programs, (ps) => {
  if (!selectedProgram.value && ps.length > 0 && ps[0]) {
    selectedProgram.value = ps[0].id
  }
}, { immediate: true })

interface MilestoneItem { id: string; name: string; description: string | null; targetDate: string | null; state: string; closedAt: string | null; version: number }

const milestonesVars = computed(() => ({ programId: selectedProgram.value ?? '' }))
const { data: milestonesData, status, refresh } = useAsyncQuery<{
  workOps: { milestones: { byProgram: MilestoneItem[] } }
}>('workops-milestones', milestonesGql, milestonesVars)

const milestones = computed(() => milestonesData.value?.workOps?.milestones?.byProgram ?? [])

// ── Table ────────────────────────────────────────────────────────────

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(160px, 2fr)' },
  { key: 'state', label: 'State', width: '100px' },
  { key: 'targetDate', label: 'Target', width: '120px', muted: true },
  { key: 'closedAt', label: 'Closed', width: '120px', muted: true },
]

function formatDate(iso?: string | null): string {
  if (!iso) return '\u2014'
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

// ── Create ──────────────────────────────────────────────────────────

const showCreate = ref(false)
const createForm = reactive({ name: '', description: '', targetDate: '' })
const saving = ref(false)

function resetCreate() {
  createForm.name = ''
  createForm.description = ''
  createForm.targetDate = ''
}

async function handleCreate() {
  if (!createForm.name.trim() || !selectedProgram.value) return
  saving.value = true
  try {
    await gqlMutation(
      gql`mutation CreateMilestone($input: CreateWorkOpsMilestoneInput!) {
        workOps { milestones { create(input: $input) { id } } }
      }`,
      {
        input: {
          programId: selectedProgram.value,
          name: createForm.name.trim(),
          description: createForm.description.trim() || null,
          targetDate: createForm.targetDate ? new Date(createForm.targetDate).toISOString() : null,
        },
      },
    )
    showCreate.value = false
    resetCreate()
    toast.success('Milestone created')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create milestone')
  } finally {
    saving.value = false
  }
}

// ── Close ───────────────────────────────────────────────────────────

const closeTarget = ref<MilestoneItem | null>(null)
const closeLoading = ref(false)

async function confirmClose() {
  if (!closeTarget.value) return
  closeLoading.value = true
  try {
    await gqlMutation(
      gql`mutation CloseMilestone($id: UUID!, $expectedVersion: Long!) {
        workOps { milestones { close(id: $id, expectedVersion: $expectedVersion) { id } } }
      }`,
      { id: closeTarget.value.id, expectedVersion: closeTarget.value.version },
    )
    closeTarget.value = null
    toast.success('Milestone closed')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Close failed')
  } finally {
    closeLoading.value = false
  }
}

function handleRowAction({ action, row }: { action: string; row: MilestoneItem }) {
  if (action === 'close') closeTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'Milestones')"
        title="Milestones"
        :subtitle="`${milestones.length} milestone${milestones.length === 1 ? '' : 's'}`"
      >
        <template #actions>
          <Select
            v-if="programOptions.length"
            v-model="selectedProgram"
            :options="programOptions"
            :accent="accent"
          />
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedProgram"
            @click="showCreate = true">
            New Milestone
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Milestones" subtitle="Cross-project delivery commitments for the selected program.">
      <GlassTable
        :columns="columns"
        :rows="milestones"
        :loading="status === 'pending' && milestones.length === 0"
        empty-text="No milestones in this program."
        :row-actions="(row: MilestoneItem) => row.state === 'OPEN'
          ? [{ id: 'close', label: 'Close', icon: 'check' }]
          : []
        "
        @row-action="handleRowAction"
      >
        <template #col-name="{ row }">
          <span class="ms-name">{{ row.name }}</span>
          <span v-if="row.description" class="ms-desc">{{ row.description }}</span>
        </template>
        <template #col-state="{ row }">
          <Badge :color="row.state === 'CLOSED' ? '#4ade80' : '#ffb547'">
            {{ row.state }}
          </Badge>
        </template>
        <template #col-targetDate="{ row }">{{ formatDate(row.targetDate) }}</template>
        <template #col-closedAt="{ row }">{{ formatDate(row.closedAt) }}</template>
      </GlassTable>
    </SectionCard>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Milestone"
      icon="plus"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="createForm.name"
          label="Name"
          placeholder="e.g. Q2 Release"
          autofocus />
        <TextInput v-model="createForm.description" label="Description" placeholder="Optional" />
        <TextInput v-model="createForm.targetDate" label="Target Date" type="date" />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!createForm.name.trim() || saving"
          @click="handleCreate">
          Create
        </Button>
      </template>
    </Modal>

    <!-- Close Confirmation -->
    <ConfirmModal
      v-if="closeTarget"
      :title="`Close milestone '${closeTarget.name}'?`"
      confirm-label="Close"
      :loading="closeLoading"
      @close="closeTarget = null"
      @confirm="confirmClose"
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

.ms-name {
  font-weight: 500;
  color: var(--fg-0);
  display: block;
}

.ms-desc {
  font-size: 11.5px;
  color: var(--fg-3);
  display: block;
  margin-top: 2px;
}
</style>
