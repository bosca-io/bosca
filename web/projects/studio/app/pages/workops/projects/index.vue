<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { searchProfiles } = useProfileSearch()
const { useAsyncQuery, mutation } = useGraphQL()
const router = useRouter()

const activeTab = ref('Active')
const showCreate = ref(false)
const showEdit = ref(false)
const showMove = ref(false)
const showDelete = ref(false)
const editTarget = ref<ProjectRow | null>(null)
const moveTarget = ref<ProjectRow | null>(null)
const deleteTarget = ref<ProjectRow | null>(null)
const saving = ref(false)
const deleting = ref(false)
const error = ref('')

const form = reactive({
  key: '',
  name: '',
  description: '',
  ownerProfileId: '',
  programId: '',
})

watch(() => form.key, (v) => { if (v !== v.toUpperCase()) form.key = v.toUpperCase() })

function resetForm() {
  form.key = ''
  form.name = ''
  form.description = ''
  form.ownerProfileId = ''
  form.programId = ''
  error.value = ''
}

const listGql = gql`
  query {
    workOps {
      projects {
        all {
          id
          key
          name
          description
          ownerProfileId
          owner { id name }
          program { id key name }
          archivedAt
          createdAt
          modifiedAt
          version
        }
      }
    }
  }
`

const programsGql = gql`
  query {
    workOps {
      programs {
        all { id key name }
      }
    }
  }
`

interface ProjectRow {
  id: string
  key: string
  name: string
  description: string | null
  ownerProfileId: string
  owner: { id: string; name: string } | null
  program: { id: string; key: string; name: string } | null
  archivedAt: string | null
  createdAt: string
  modifiedAt: string
  version: number
}

const { data, status, refresh } = useAsyncQuery<{
  workOps: { projects: { all: ProjectRow[] } }
}>('workops-projects', listGql)

const { data: programsData } = useAsyncQuery<{
  workOps: { programs: { all: Array<{ id: string; key: string; name: string }> } }
}>('workops-programs', programsGql)

const allProjects = computed(() => data.value?.workOps?.projects?.all ?? [])
const programs = computed(() => programsData.value?.workOps?.programs?.all ?? [])
const programOptions = computed(() => programs.value.map(p => ({ value: p.id, label: `${p.key} — ${p.name}` })))

const projects = computed(() => {
  if (activeTab.value === 'Active') return allProjects.value.filter(p => !p.archivedAt)
  if (activeTab.value === 'Archived') return allProjects.value.filter(p => p.archivedAt)
  return allProjects.value
})

const isLoading = computed(() => status.value === 'pending')

const subtitle = computed(() => {
  const active = allProjects.value.filter(p => !p.archivedAt).length
  const archived = allProjects.value.filter(p => p.archivedAt).length
  return `${active} active · ${archived} archived`
})

const columns: GlassTableColumn[] = [
  { key: 'key', label: 'Key', width: '120px' },
  { key: 'name', label: 'Name', width: '1.5fr' },
  { key: 'program', label: 'Program', width: '1fr' },
  { key: 'owner', label: 'Owner', width: '1fr' },
  { key: 'modified', label: 'Modified', width: '140px', muted: true },
]

function keyColor(key: string): string {
  let hash = 0
  for (let i = 0; i < key.length; i++) hash = key.charCodeAt(i) + ((hash << 5) - hash)
  const hues = ['#ff7ac6', '#a78bff', '#5ec5ff', '#34d99a', '#ffb547', '#06b6d4']
  return hues[Math.abs(hash) % hues.length]!
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function openCreate() {
  resetForm()
  showCreate.value = true
}
useCreateFromQuery(openCreate)

function openEdit(project: ProjectRow) {
  editTarget.value = project
  form.key = project.key
  form.name = project.name
  form.description = project.description ?? ''
  form.ownerProfileId = project.ownerProfileId
  form.programId = project.program?.id ?? ''
  error.value = ''
  showEdit.value = true
}

function openDelete(project: ProjectRow) {
  deleteTarget.value = project
  showDelete.value = true
}

function openMove(project: ProjectRow) {
  moveTarget.value = project
  showMove.value = true
}

const createGql = gql`
  mutation CreateProject($input: WorkOpsProjectInput!) {
    workOps { projects { create(input: $input) { id } } }
  }
`

const updateGql = gql`
  mutation UpdateProject($id: UUID!, $input: WorkOpsProjectInput!, $expectedVersion: Long!) {
    workOps { projects { update(id: $id, input: $input, expectedVersion: $expectedVersion) { id } } }
  }
`

const archiveGql = gql`
  mutation ArchiveProject($id: UUID!, $expectedVersion: Long!) {
    workOps { projects { archive(id: $id, expectedVersion: $expectedVersion) { id } } }
  }
`

const unarchiveGql = gql`
  mutation UnarchiveProject($id: UUID!, $expectedVersion: Long!) {
    workOps { projects { unarchive(id: $id, expectedVersion: $expectedVersion) { id } } }
  }
`

async function handleCreate() {
  if (!form.key || !form.name || !form.programId || !form.ownerProfileId) {
    error.value = 'Key, name, program, and owner are required.'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await mutation(createGql, {
      input: {
        key: form.key.toUpperCase(),
        name: form.name,
        description: form.description || null,
        ownerProfileId: form.ownerProfileId,
        programId: form.programId,
      },
    })
    showCreate.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create project'
  } finally {
    saving.value = false
  }
}

async function handleUpdate() {
  if (!form.name || !form.ownerProfileId) {
    error.value = 'Name and owner are required.'
    return
  }
  saving.value = true
  error.value = ''
  try {
    if (!editTarget.value) return
    await mutation(updateGql, {
      id: editTarget.value.id,
      expectedVersion: editTarget.value.version,
      input: {
        key: editTarget.value.key,
        name: form.name,
        description: form.description || null,
        ownerProfileId: form.ownerProfileId,
        programId: form.programId,
      },
    })
    showEdit.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to update project'
  } finally {
    saving.value = false
  }
}

async function handleArchive(project: ProjectRow) {
  try {
    if (project.archivedAt) {
      await mutation(unarchiveGql, { id: project.id, expectedVersion: project.version })
    } else {
      await mutation(archiveGql, { id: project.id, expectedVersion: project.version })
    }
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to archive/unarchive project'
  }
}

async function handleDelete() {
  deleting.value = true
  try {
    if (!deleteTarget.value) return
    await mutation(gql`
      mutation DeleteProject($id: UUID!, $expectedVersion: Long!) {
        workOps { projects { archive(id: $id, expectedVersion: $expectedVersion) { id } } }
      }
    `, { id: deleteTarget.value.id, expectedVersion: deleteTarget.value.version })
    showDelete.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to delete project'
  } finally {
    deleting.value = false
  }
}

function onRowClick(row: ProjectRow) {
  router.push(`/workops/projects/${row.id}`)
}

function menuItems(project: ProjectRow) {
  return [
    { id: 'edit', label: 'Edit', icon: 'edit' },
    ...(!project.archivedAt ? [{ id: 'move', label: 'Move to Program', icon: 'arrowRight' }] : []),
    { id: 'archive', label: project.archivedAt ? 'Unarchive' : 'Archive', icon: 'archive' },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onMenuSelect(id: string, project: ProjectRow) {
  if (id === 'edit') openEdit(project)
  else if (id === 'move') openMove(project)
  else if (id === 'archive') handleArchive(project)
  else if (id === 'delete') openDelete(project)
}

async function handleMoved() {
  showMove.value = false
  moveTarget.value = null
  await refresh()
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Projects')"
        title="Projects"
        :subtitle="subtitle"
        :tabs="['Active', 'All', 'Archived']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Project</Button>
        </template>
      </PageHeader>
    </template>

    <GlassTable
      :columns="columns"
      :rows="projects"
      row-key="id"
      :loading="isLoading"
      empty-text="No projects found"
      @row-click="onRowClick"
    >
      <template #col-key="{ value }">
        <span class="project-key">
          <span class="key-dot" :style="{ background: keyColor(value) }" />
          <span class="mono">{{ value }}</span>
        </span>
      </template>
      <template #col-name="{ value }">
        <span class="project-name">{{ value }}</span>
      </template>
      <template #col-program="{ row }">
        <Badge v-if="row.program" :color="accent">{{ row.program.name }}</Badge>
        <span v-else class="fg-3">—</span>
      </template>
      <template #col-owner="{ row }">
        <span v-if="row.owner">{{ row.owner.name }}</span>
        <span v-else class="fg-3">—</span>
      </template>
      <template #col-modified="{ row }">
        {{ formatDate(row.modifiedAt) }}
      </template>
      <template #actions="{ row }">
        <OverflowMenu :items="menuItems(row)" @select="onMenuSelect($event, row)">
          <template #default="{ toggle }">
            <button class="row-menu-btn" @click.stop="toggle">
              <Icon name="list" :size="14" color="var(--fg-3)" />
            </button>
          </template>
        </OverflowMenu>
      </template>
    </GlassTable>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Project"
      icon="folder"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput
          v-model="form.key"
          label="Key"
          placeholder="PROJ (2-10 uppercase)"
          mono />
        <TextInput v-model="form.name" label="Name" placeholder="Project name" />
        <TextInput v-model="form.description" label="Description" placeholder="Optional description" />
        <Select
          v-model="form.programId"
          :options="programOptions"
          label="Program"
          placeholder="Select program"
          :accent="accent" />
        <Select
          v-model="form.ownerProfileId"
          :on-search="searchProfiles"
          searchable
          placeholder="Search profiles…"
          label="Owner" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreate">
          {{ saving ? 'Creating…' : 'Create Project' }}
        </Button>
      </template>
    </Modal>

    <!-- Edit Modal -->
    <Modal
      v-if="showEdit"
      title="Edit Project"
      icon="pencil"
      :accent="accent"
      @close="showEdit = false">
      <div class="form-stack">
        <TextInput
          :model-value="editTarget?.key"
          label="Key"
          disabled
          mono />
        <TextInput v-model="form.name" label="Name" placeholder="Project name" />
        <TextInput v-model="form.description" label="Description" placeholder="Optional description" />
        <Select
          v-model="form.ownerProfileId"
          :on-search="searchProfiles"
          searchable
          placeholder="Search profiles…"
          label="Owner" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showEdit = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleUpdate">
          {{ saving ? 'Saving…' : 'Save Changes' }}
        </Button>
      </template>
    </Modal>

    <MoveProjectModal
      v-if="showMove && moveTarget"
      :project="moveTarget"
      :accent="accent"
      @close="showMove = false"
      @moved="handleMoved"
    />

    <!-- Delete Confirmation -->
    <ConfirmModal
      v-if="showDelete"
      title="Archive Project"
      subtitle="This will archive the project and hide it from the active list."
      confirm-label="Archive"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete"
    >
      <p>Are you sure you want to archive <strong>{{ deleteTarget?.name }}</strong>?</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.project-key {
  display: flex;
  align-items: center;
  gap: 8px;
}

.key-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 8px;
}

.project-name {
  font-weight: 500;
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

.row-menu-btn {
  background: none;
  border: none;
  padding: 4px;
  cursor: pointer;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.row-menu-btn:hover {
  background: var(--bg-3);
}

.fg-3 {
  color: var(--fg-3);
}
</style>
