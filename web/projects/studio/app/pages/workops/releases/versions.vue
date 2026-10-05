<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

// ── Queries ──────────────────────────────────────────────────────────

const projectsGql = gql`
  query VersionProjects {
    workOps { projects { all { id key name } } }
  }
`

const versionsGql = gql`
  query VersionsByProject($projectId: UUID!) {
    workOps { versions {
      byProject(projectId: $projectId) {
        id name description startDate releaseDate
        released archived sequenceNumber version
      }
    } }
  }
`

// ── Data fetching ────────────────────────────────────────────────────

interface ProjectItem { id: string; key: string; name: string }

const { data: projectsData } = useAsyncQuery<{
  workOps: { projects: { all: ProjectItem[] } }
}>('workops-version-projects', projectsGql, {})

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

interface VersionItem { id: string; name: string; description: string | null; startDate: string | null; releaseDate: string | null; released: boolean; archived: boolean; sequenceNumber: number; version: number }

const versionsVars = computed(() => ({ projectId: selectedProject.value ?? '' }))
const { data: versionsData, status, refresh } = useAsyncQuery<{
  workOps: { versions: { byProject: VersionItem[] } }
}>('workops-versions', versionsGql, versionsVars)

const versions = computed(() =>
  [...(versionsData.value?.workOps?.versions?.byProject ?? [])]
    .sort((a, b) => b.sequenceNumber - a.sequenceNumber),
)

// ── Table ────────────────────────────────────────────────────────────

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(140px, 2fr)' },
  { key: 'status', label: 'Status', width: '100px' },
  { key: 'startDate', label: 'Start', width: '110px', muted: true },
  { key: 'releaseDate', label: 'Release', width: '110px', muted: true },
  { key: 'seq', label: '#', width: '50px', muted: true },
]

function formatDate(iso?: string | null): string {
  if (!iso) return '\u2014'
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

// ── Create ──────────────────────────────────────────────────────────

const showCreate = ref(false)
const createForm = reactive({ name: '', description: '', startDate: '', releaseDate: '' })
const saving = ref(false)

function resetCreate() {
  createForm.name = ''
  createForm.description = ''
  createForm.startDate = ''
  createForm.releaseDate = ''
}

async function handleCreate() {
  if (!createForm.name.trim() || !selectedProject.value) return
  saving.value = true
  try {
    await gqlMutation(
      gql`mutation CreateVersion($input: CreateWorkOpsVersionInput!) {
        workOps { versions { create(input: $input) { id } } }
      }`,
      {
        input: {
          projectId: selectedProject.value,
          name: createForm.name.trim(),
          description: createForm.description.trim() || null,
          startDate: createForm.startDate ? new Date(createForm.startDate).toISOString() : null,
          releaseDate: createForm.releaseDate ? new Date(createForm.releaseDate).toISOString() : null,
        },
      },
    )
    showCreate.value = false
    resetCreate()
    toast.success('Version created')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create version')
  } finally {
    saving.value = false
  }
}

// ── Release ─────────────────────────────────────────────────────────

async function releaseVersion(v: VersionItem) {
  try {
    await gqlMutation(
      gql`mutation ReleaseVersion($id: UUID!, $expectedVersion: Long!) {
        workOps { versions { release(id: $id, expectedVersion: $expectedVersion) { id } } }
      }`,
      { id: v.id, expectedVersion: v.version },
    )
    toast.success(`${v.name} released`)
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Release failed')
  }
}

// ── Delete ──────────────────────────────────────────────────────────

const deleteTarget = ref<VersionItem | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(
      gql`mutation DeleteVersion($id: UUID!) {
        workOps { versions { delete(id: $id) } }
      }`,
      { id: deleteTarget.value.id },
    )
    deleteTarget.value = null
    toast.success('Version deleted')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Delete failed')
  } finally {
    deleteLoading.value = false
  }
}

function handleRowAction({ action, row }: { action: string; row: VersionItem }) {
  if (action === 'release' && !row.released) releaseVersion(row)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Releases', 'Versions')"
        title="Versions"
        :subtitle="`${versions.length} version${versions.length === 1 ? '' : 's'}`"
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
            New Version
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Versions" subtitle="Manage release versions for the selected project.">
      <GlassTable
        :columns="columns"
        :rows="versions"
        :loading="status === 'pending' && versions.length === 0"
        empty-text="No versions in this project."
        :row-actions="(row: VersionItem) => [
          ...(!row.released ? [{ id: 'release', label: 'Release', icon: 'check' }] : []),
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-action="handleRowAction"
      >
        <template #col-name="{ row }">
          <span class="version-name">{{ row.name }}</span>
          <span v-if="row.description" class="version-desc">{{ row.description }}</span>
        </template>
        <template #col-status="{ row }">
          <Badge :color="row.released ? '#4ade80' : row.archived ? 'var(--fg-3)' : '#5ec5ff'">
            {{ row.released ? 'Released' : row.archived ? 'Archived' : 'Unreleased' }}
          </Badge>
        </template>
        <template #col-startDate="{ row }">{{ formatDate(row.startDate) }}</template>
        <template #col-releaseDate="{ row }">{{ formatDate(row.releaseDate) }}</template>
        <template #col-seq="{ row }">{{ row.sequenceNumber }}</template>
      </GlassTable>
    </SectionCard>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Version"
      icon="plus"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="createForm.name"
          label="Name"
          placeholder="e.g. 1.0.0"
          autofocus />
        <TextInput v-model="createForm.description" label="Description" placeholder="Optional" />
        <TextInput v-model="createForm.startDate" label="Start Date" type="date" />
        <TextInput v-model="createForm.releaseDate" label="Release Date" type="date" />
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
      :title="`Delete version '${deleteTarget.name}'?`"
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

.version-name {
  font-weight: 500;
  color: var(--fg-0);
  display: block;
}

.version-desc {
  font-size: 11.5px;
  color: var(--fg-3);
  display: block;
  margin-top: 2px;
}
</style>
