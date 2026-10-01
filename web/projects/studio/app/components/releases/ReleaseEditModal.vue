<script setup lang="ts">
import gql from 'graphql-tag'
import ReleaseComponentPicker from '~/components/releases/ReleaseComponentPicker.vue'

/**
 * Edit a release: its details (name, description, planned date) and the projects it
 * bundles (add/remove). Everything about a release's contents lives here, so the dashboard stays read-only.
 * Details save via `updateRelease` (optimistic-locked on the release version); projects via
 * `bundleVersion`/`unbundleVersion`. Deleting a release is a separate dashboard action, not part of editing.
 * Self-fetches the release + its projects so it's always current.
 */

const props = defineProps<{ programId: string; releaseId: string }>()
const emit = defineEmits<{ close: []; changed: [] }>()

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface Release { id: string; name: string; description: string | null; releaseDate: string | null; version: number }
interface ProjectVersion { projectId: string; versionId: string; project: { key: string; name: string } | null; version: { name: string } | null }

// ── Load the release + its bundled projects ──────────────────────────────────
const releaseGql = gql`
  query EditRelease($id: UUID!) {
    workOps { crossProject { release(id: $id) { id name description releaseDate version } } }
  }
`
const { data: releaseData, refresh: refreshRelease } = useAsyncQuery<{
  workOps: { crossProject: { release: Release | null } }
}>('edit-release', releaseGql, { id: computed(() => props.releaseId) }, { server: false })
const release = computed(() => releaseData.value?.workOps?.crossProject?.release ?? null)

const projectsGql = gql`
  query EditReleaseProjects($releaseId: UUID!) {
    workOps { crossProject { versionsForRelease(releaseId: $releaseId) {
      projectId versionId project { key name } version { name }
    } } }
  }
`
const { data: projectsData, refresh: refreshProjects } = useAsyncQuery<{
  workOps: { crossProject: { versionsForRelease: ProjectVersion[] } }
}>('edit-release-projects', projectsGql, { releaseId: computed(() => props.releaseId) }, { server: false })
const projects = computed(() => projectsData.value?.workOps?.crossProject?.versionsForRelease ?? [])
const bundledVersionIds = computed(() => projects.value.map(p => p.versionId))

// ── Details form ─────────────────────────────────────────────────────────────
const form = reactive({ name: '', description: '', releaseDate: '' })
const savingDetails = ref(false)
const error = ref('')

watch(release, (r) => {
  if (!r) return
  form.name = r.name
  form.description = r.description ?? ''
  form.releaseDate = r.releaseDate ? new Date(r.releaseDate).toISOString().slice(0, 10) : ''
}, { immediate: true })

const detailsDirty = computed(() => {
  const r = release.value
  if (!r) return false
  const date = r.releaseDate ? new Date(r.releaseDate).toISOString().slice(0, 10) : ''
  return form.name.trim() !== r.name || (form.description.trim() || '') !== (r.description ?? '') || form.releaseDate !== date
})

async function saveDetails() {
  const r = release.value
  if (!r || !form.name.trim()) { error.value = 'A release name is required.'; return }
  savingDetails.value = true
  error.value = ''
  try {
    await mutation(gql`
      mutation UpdateRelease($id: UUID!, $input: UpdateWorkOpsReleaseInput!, $expectedVersion: Long!) {
        workOps { crossProject { updateRelease(id: $id, input: $input, expectedVersion: $expectedVersion) { id version } } }
      }
    `, {
      id: r.id,
      expectedVersion: r.version,
      input: {
        name: form.name.trim(),
        description: form.description.trim() || null,
        releaseDate: form.releaseDate ? new Date(form.releaseDate).toISOString() : null,
      },
    })
    toast.success('Release updated')
    await refreshRelease()
    emit('changed')
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to update the release'
  } finally {
    savingDetails.value = false
  }
}

// ── Projects ─────────────────────────────────────────────────────────────────
const removingKey = ref('')

async function addProject(c: { projectId: string; versionId: string }) {
  try {
    await mutation(gql`
      mutation BundleVersion($releaseId: UUID!, $projectId: UUID!, $versionId: UUID!) {
        workOps { crossProject { bundleVersion(releaseId: $releaseId, projectId: $projectId, versionId: $versionId) } }
      }
    `, { releaseId: props.releaseId, projectId: c.projectId, versionId: c.versionId })
    toast.success('Project added')
    await refreshProjects()
    emit('changed')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to add the project')
  }
}

async function removeProject(p: ProjectVersion) {
  removingKey.value = p.versionId
  try {
    await mutation(gql`
      mutation UnbundleVersion($releaseId: UUID!, $versionId: UUID!) {
        workOps { crossProject { unbundleVersion(releaseId: $releaseId, versionId: $versionId) } }
      }
    `, { releaseId: props.releaseId, versionId: p.versionId })
    toast.success('Project removed')
    await refreshProjects()
    emit('changed')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to remove the project')
  } finally {
    removingKey.value = ''
  }
}

function short(id: string): string { return id.slice(0, 8) }
</script>

<template>
  <Modal
    title="Edit release"
    :subtitle="release?.name ?? 'Loading…'"
    icon="edit"
    :accent="accent"
    width="600px"
    @close="emit('close')">
    <div class="edit-stack">
      <!-- Details -->
      <section class="edit-section">
        <h4 class="edit-title">Details</h4>
        <TextInput v-model="form.name" label="Name" placeholder="Release name" />
        <Textarea
          v-model="form.description"
          label="Description"
          placeholder="What does this release ship?"
          :rows="2" />
        <DateInput v-model="form.releaseDate" label="Planned release date" type="date" />
        <div class="edit-actions">
          <Button
            primary
            size="sm"
            :accent="accent"
            :disabled="!detailsDirty || savingDetails || !form.name.trim()"
            @click="saveDetails">Save details</Button>
        </div>
      </section>

      <!-- Projects -->
      <section class="edit-section">
        <h4 class="edit-title">Projects <span class="edit-count">{{ projects.length }}</span></h4>
        <ReleaseComponentPicker
          :program-id="programId"
          :disabled-version-ids="bundledVersionIds"
          @add="addProject" />
        <ul v-if="projects.length" class="proj-list">
          <li v-for="p in projects" :key="p.versionId" class="proj-row">
            <span class="proj-key mono">{{ p.project?.key ?? short(p.projectId) }}</span>
            <span class="proj-name">{{ p.project?.name ?? '—' }}</span>
            <span class="proj-version">{{ p.version?.name ?? short(p.versionId) }}</span>
            <button
              type="button"
              class="proj-remove"
              title="Remove from release"
              :disabled="removingKey === p.versionId"
              @click="removeProject(p)">
              <Icon name="x" :size="13" />
            </button>
          </li>
        </ul>
        <p v-else class="edit-empty">No projects in this release yet — add them above.</p>
      </section>

      <p v-if="error" class="edit-error">{{ error }}</p>
    </div>

    <template #footer>
      <Button @click="emit('close')">Done</Button>
    </template>
  </Modal>
</template>

<style scoped>
.edit-stack { display: flex; flex-direction: column; gap: 20px; }
.edit-section { display: flex; flex-direction: column; gap: 12px; }
.edit-title { margin: 0; font-size: 12px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--fg-3); display: flex; align-items: baseline; gap: 8px; }
.edit-count { font-size: 10px; color: var(--fg-2); background: var(--bg-2); padding: 1px 7px; border-radius: 4px; }
.edit-actions { display: flex; justify-content: flex-end; }
.edit-empty { margin: 0; font-size: 12.5px; color: var(--fg-3); line-height: 1.5; }
.edit-error { margin: 0; color: var(--err, #f87171); font-size: 12px; }
.proj-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
/* Columns line up across rows: key | name | version | remove. */
.proj-row {
  display: grid; grid-template-columns: 72px minmax(0, 1fr) auto auto; align-items: center; gap: 10px;
  padding: 7px 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--bg-1);
}
.proj-key { font-size: 12px; color: var(--fg-1); }
.proj-name { font-size: 12.5px; color: var(--fg-0); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.proj-version { font-size: 12px; color: var(--fg-2); }
.proj-remove {
  display: inline-flex; padding: 3px; border: 1px solid var(--line); border-radius: 6px;
  background: transparent; color: var(--fg-3); cursor: pointer; flex: 0 0 auto;
}
.proj-remove:hover:not(:disabled) { color: var(--err, #f87171); border-color: var(--err, #f87171); }
.proj-remove:disabled { opacity: 0.5; cursor: default; }
</style>
