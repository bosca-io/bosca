<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

// ── Queries ──────────────────────────────────────────────────────────

const projectsGql = gql`
  query ComponentProjects {
    workOps { projects { all { id key name } } }
  }
`

const componentsGql = gql`
  query ComponentsByProject($projectId: UUID!) {
    workOps { components {
      byProject(projectId: $projectId) {
        id name description assigneeMode
        defaultAssigneeProfileId leadProfileId
        version
      }
    } }
  }
`

// ── Data fetching ────────────────────────────────────────────────────

interface ProjectItem { id: string; key: string; name: string }

const { data: projectsData } = useAsyncQuery<{
  workOps: { projects: { all: ProjectItem[] } }
}>('workops-component-projects', projectsGql, {})

const projects = computed(() => projectsData.value?.workOps?.projects?.all ?? [])
const projectOptions = computed<SelectOption[]>(() =>
  projects.value.map((p) => ({ value: p.id, label: `${p.key} - ${p.name}` })),
)

const selectedProject = ref<string | undefined>(undefined)

watch(projects, (ps) => {
  if (!selectedProject.value && ps.length > 0 && ps[0]) {
    selectedProject.value = ps[0].id
  }
}, { immediate: true })

interface ComponentItem { id: string; name: string; description: string | null; assigneeMode: string; defaultAssigneeProfileId: string | null; leadProfileId: string | null; version: number }

const componentsVars = computed(() => ({ projectId: selectedProject.value ?? '' }))
const { data: componentsData, status, refresh } = useAsyncQuery<{
  workOps: { components: { byProject: ComponentItem[] } }
}>('workops-components', componentsGql, componentsVars)

const components = computed(() => componentsData.value?.workOps?.components?.byProject ?? [])

// ── Table ────────────────────────────────────────────────────────────

const assigneeModeOptions: SelectOption[] = [
  { value: 'UNASSIGNED', label: 'Unassigned' },
  { value: 'COMPONENT_LEAD', label: 'Component Lead' },
  { value: 'PROJECT_DEFAULT', label: 'Project Default' },
  { value: 'COMPONENT_LEAD_OR_PROJECT_DEFAULT', label: 'Lead or Project Default' },
]

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(160px, 2fr)' },
  { key: 'assigneeMode', label: 'Assignee Mode', width: 'minmax(140px, 1fr)' },
  { key: 'description', label: 'Description', width: '1fr', muted: true },
]

// ── Create ──────────────────────────────────────────────────────────

const showCreate = ref(false)
const createForm = reactive({ name: '', description: '', assigneeMode: 'UNASSIGNED' })
const saving = ref(false)

function resetCreate() {
  createForm.name = ''
  createForm.description = ''
  createForm.assigneeMode = 'UNASSIGNED'
}

async function handleCreate() {
  if (!createForm.name.trim() || !selectedProject.value) return
  saving.value = true
  try {
    await gqlMutation(
      gql`mutation CreateComponent($input: CreateWorkOpsComponentInput!) {
        workOps { components { create(input: $input) { id } } }
      }`,
      {
        input: {
          projectId: selectedProject.value,
          name: createForm.name.trim(),
          description: createForm.description.trim() || null,
          assigneeMode: createForm.assigneeMode,
        },
      },
    )
    showCreate.value = false
    resetCreate()
    toast.success('Component created')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create component')
  } finally {
    saving.value = false
  }
}

// ── Delete ──────────────────────────────────────────────────────────

const deleteTarget = ref<ComponentItem | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(
      gql`mutation DeleteComponent($id: UUID!) {
        workOps { components { delete(id: $id) } }
      }`,
      { id: deleteTarget.value.id },
    )
    deleteTarget.value = null
    toast.success('Component deleted')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Delete failed')
  } finally {
    deleteLoading.value = false
  }
}

function handleRowAction({ action, row }: { action: string; row: ComponentItem }) {
  if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'Components')"
        title="Components"
        :subtitle="`${components.length} component${components.length === 1 ? '' : 's'}`"
      >
        <template #actions>
          <Select
            v-if="projectOptions.length"
            v-model="selectedProject"
            :options="projectOptions"
            :accent="accent"
          />
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedProject"
            @click="showCreate = true">
            New Component
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Components" subtitle="Ownership areas for tasks within the selected project.">
      <GlassTable
        :columns="columns"
        :rows="components"
        :loading="status === 'pending' && components.length === 0"
        empty-text="No components in this project."
        :row-actions="() => [
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-action="handleRowAction"
      >
        <template #col-name="{ row }">
          <span class="comp-name">{{ row.name }}</span>
        </template>
        <template #col-assigneeMode="{ row }">
          <Badge color="var(--fg-3)">
            {{ assigneeModeOptions.find(o => o.value === row.assigneeMode)?.label ?? row.assigneeMode }}
          </Badge>
        </template>
        <template #col-description="{ row }">{{ row.description || '\u2014' }}</template>
      </GlassTable>
    </SectionCard>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Component"
      icon="plus"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="createForm.name"
          label="Name"
          placeholder="e.g. Frontend"
          autofocus />
        <TextInput v-model="createForm.description" label="Description" placeholder="Optional" />
        <Select v-model="createForm.assigneeMode" label="Assignee Mode" :options="assigneeModeOptions" />
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

    <!-- Delete Confirmation -->
    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete component '${deleteTarget.name}'?`"
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

.comp-name {
  font-weight: 500;
  color: var(--fg-0);
}
</style>
