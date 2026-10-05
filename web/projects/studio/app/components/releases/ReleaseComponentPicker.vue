<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

/**
 * Reusable project → version picker for bundling a component into a release. Self-fetches the program's
 * projects and the picked project's versions; emits the chosen component so the parent decides what to do
 * with it (stage it for a new release, or bundle it into an existing one). `disabledVersionIds` hides
 * versions already chosen so the same component can't be added twice.
 */

const props = defineProps<{ programId: string; disabledVersionIds?: string[] }>()
const emit = defineEmits<{ add: [component: { projectId: string; projectKey: string; versionId: string; versionName: string }] }>()

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface Project { id: string; key: string; name: string }
interface Version { id: string; name: string; released: boolean; sequenceNumber: number }

const projectsGql = gql`
  query ReleasePickerProjects($programId: UUID!) {
    workOps { projects { byProgram(programId: $programId) { id key name } } }
  }
`
const { data: projectsData } = useAsyncQuery<{
  workOps: { projects: { byProgram: Project[] } }
}>('release-picker-projects', projectsGql, { programId: computed(() => props.programId || undefined) }, { server: false })
const projects = computed(() => projectsData.value?.workOps?.projects?.byProgram ?? [])
const projectOptions = computed<SelectOption[]>(() => projects.value.map(p => ({ value: p.id, label: `${p.key} · ${p.name}` })))

const pickProjectId = ref('')
const pickVersionId = ref('')
const selectedProject = computed(() => projects.value.find(project => project.id === pickProjectId.value))

const versionsGql = gql`
  query ReleasePickerVersions($projectId: UUID!) {
    workOps { versions { byProject(projectId: $projectId) { id name released sequenceNumber } } }
  }
`
const { data: versionsData, status: versionsStatus, refresh: refreshVersions } = useAsyncQuery<{
  workOps: { versions: { byProject: Version[] } }
}>('release-picker-versions', versionsGql, { projectId: computed(() => pickProjectId.value || undefined) }, { server: false })
const locallyCreatedVersions = ref<Array<Version & { projectId: string }>>([])
const versions = computed(() => {
  const byId = new Map(
    (versionsData.value?.workOps?.versions?.byProject ?? []).map(version => [version.id, version]),
  )
  for (const version of locallyCreatedVersions.value) {
    if (version.projectId === pickProjectId.value && !byId.has(version.id)) byId.set(version.id, version)
  }
  return [...byId.values()].sort((a, b) => b.sequenceNumber - a.sequenceNumber)
})
const disabledSet = computed(() => new Set(props.disabledVersionIds ?? []))
const versionOptions = computed<SelectOption[]>(() =>
  versions.value
    .filter(v => !disabledSet.value.has(v.id))
    .map(v => ({ value: v.id, label: v.released ? `${v.name} · released` : v.name })),
)

const showCreateVersion = ref(false)
const createForm = reactive({ name: '', description: '', startDate: '', releaseDate: '' })
const creatingVersion = ref(false)
const createVersionError = ref('')

function resetCreateVersion() {
  createForm.name = ''
  createForm.description = ''
  createForm.startDate = ''
  createForm.releaseDate = ''
  createVersionError.value = ''
}

function openCreateVersion() {
  if (!pickProjectId.value) return
  resetCreateVersion()
  showCreateVersion.value = true
}

function closeCreateVersion() {
  if (creatingVersion.value) return
  showCreateVersion.value = false
  resetCreateVersion()
}

watch(pickProjectId, () => {
  pickVersionId.value = ''
  showCreateVersion.value = false
  resetCreateVersion()
})

async function createVersion() {
  const projectId = pickProjectId.value
  const name = createForm.name.trim()
  if (!projectId || !name) return

  creatingVersion.value = true
  createVersionError.value = ''
  try {
    const result = await mutation<{
      workOps: { versions: { create: Version } }
    }>(gql`
      mutation CreateReleasePickerVersion($input: CreateWorkOpsVersionInput!) {
        workOps { versions { create(input: $input) { id name released sequenceNumber } } }
      }
    `, {
      input: {
        projectId,
        name,
        description: createForm.description.trim() || null,
        startDate: createForm.startDate ? new Date(createForm.startDate).toISOString() : null,
        releaseDate: createForm.releaseDate ? new Date(createForm.releaseDate).toISOString() : null,
      },
    })
    const created = result?.workOps?.versions?.create
    if (!created?.id) throw new Error('The version could not be created.')

    locallyCreatedVersions.value = [
      { ...created, projectId },
      ...locallyCreatedVersions.value.filter(version => version.id !== created.id),
    ]
    if (pickProjectId.value === projectId) pickVersionId.value = created.id
    showCreateVersion.value = false
    resetCreateVersion()
    toast.success(`${created.name} created`)

    try {
      await refreshVersions()
    } catch (error: unknown) {
      toast.warn(error instanceof Error
        ? `Version created, but the list could not be refreshed: ${error.message}`
        : 'Version created, but the list could not be refreshed.')
    }
  } catch (error: unknown) {
    createVersionError.value = error instanceof Error ? error.message : 'Failed to create the version.'
  } finally {
    creatingVersion.value = false
  }
}

function add() {
  if (!pickProjectId.value || !pickVersionId.value) return
  const p = projects.value.find(x => x.id === pickProjectId.value)
  const v = versions.value.find(x => x.id === pickVersionId.value)
  if (!p || !v) return
  emit('add', { projectId: p.id, projectKey: p.key, versionId: v.id, versionName: v.name })
  pickVersionId.value = ''
}
</script>

<template>
  <div class="picker">
    <Select
      v-model="pickProjectId"
      :options="projectOptions"
      placeholder="Project"
      size="sm"
      :accent="accent"
    />
    <Select
      v-model="pickVersionId"
      :options="versionOptions"
      :placeholder="pickProjectId ? (versionsStatus === 'pending' ? 'Loading…' : 'Version') : 'Pick a project first'"
      size="sm"
      :accent="accent"
    />
    <Button
      size="sm"
      :disabled="!pickProjectId"
      @click="openCreateVersion"
    >
      New version
    </Button>
    <Button
      size="sm"
      icon="plus"
      :disabled="!pickProjectId || !pickVersionId"
      @click="add"
    >
      Add
    </Button>
  </div>

  <Modal
    v-if="showCreateVersion"
    :title="selectedProject ? `New ${selectedProject.key} version` : 'New version'"
    subtitle="Create and select a version for this release"
    icon="plus"
    :accent="accent"
    @close="closeCreateVersion">
    <div class="form-stack">
      <TextInput
        v-model="createForm.name"
        label="Name"
        placeholder="e.g. 1.0.0"
        autofocus />
      <TextInput
        v-model="createForm.description"
        label="Description"
        placeholder="Optional" />
      <TextInput v-model="createForm.startDate" label="Start date" type="date" />
      <TextInput v-model="createForm.releaseDate" label="Release date" type="date" />
      <p v-if="createVersionError" class="form-error">{{ createVersionError }}</p>
    </div>

    <template #footer>
      <span class="spacer" />
      <Button size="sm" :disabled="creatingVersion" @click="closeCreateVersion">Cancel</Button>
      <Button
        size="sm"
        primary
        :accent="accent"
        :disabled="!createForm.name.trim() || creatingVersion"
        @click="createVersion">
        Create
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.picker {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto auto;
  gap: 8px;
  align-items: center;
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-error {
  margin: 0;
  color: var(--err, #f87171);
  font-size: 12px;
}

.spacer {
  flex: 1;
}
</style>
