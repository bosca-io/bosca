<script setup lang="ts">
import gql from 'graphql-tag'
import GitHubSyncSecret from '../git/GitHubSyncSecret.vue'

const props = defineProps<{ repositoryId: string }>()
const { query, mutation } = useGraphQL()
const { accent } = useCurrentSubsystem()
interface Destination {
  id: string; key: string; owner: string; githubRepository: string; githubRepositoryId: number
  tagPrefix: string; tokenSecretName: string; enabled: boolean; version: number
}
interface Publication {
  id: string; destinationId: string; tagName: string; commitSha: string; attempts: number
  published: string | null; verified: string | null; error: string | null
  modified?: string | null
  files: { filename: string; digest: string; size: number }[]
}
interface Version { id: string; version: string; completed: boolean; finalized: boolean; completedCommitSha: string | null }
interface StoredFile { digest: string; role: string; filename: string | null; mediaType: string | null; size: number | null }
const fields = gql`
  fragment ArtifactPublicationDestinationSettings on ArtifactPublicationDestination {
    id key owner githubRepository githubRepositoryId tagPrefix tokenSecretName enabled version
  }
`
const settingsDocument = gql`
  query ArtifactPublicationSettings($repositoryId: UUID!) {
    artifactsAdmin { publicationDestinations(repositoryId: $repositoryId) { ...ArtifactPublicationDestinationSettings } }
    pipelines { secrets { name } }
  }
  ${fields}
`
const createDocument = gql`
  mutation CreateArtifactPublicationDestination($input: ArtifactPublicationDestinationInput!) {
    artifactsAdmin { createPublicationDestination(input: $input) { ...ArtifactPublicationDestinationSettings } }
  }
  ${fields}
`
const updateDocument = gql`
  mutation UpdateArtifactPublicationDestination($id: UUID!, $version: Long!, $enabled: Boolean!, $tokenSecretName: String!) {
    artifactsAdmin { updatePublicationDestination(id: $id, version: $version, enabled: $enabled, tokenSecretName: $tokenSecretName) { ...ArtifactPublicationDestinationSettings } }
  }
  ${fields}
`
const publishDocument = gql`
  mutation PublishArtifactRelease($destinationId: UUID!, $versionId: UUID!, $commitSha: String!) {
    artifactsAdmin { publishRelease(destinationId: $destinationId, versionId: $versionId, commitSha: $commitSha) }
  }
`
const versionsDocument = gql`
  query ArtifactPublicationVersions($repositoryId: UUID!, $limit: Int!, $offset: Long!) {
    artifactsAdmin { repository(id: $repositoryId) { versionCount versions(limit: $limit, offset: $offset) { id version completed finalized completedCommitSha } } }
  }
`
const versionFilesDocument = gql`
  query ArtifactPublicationVersionFiles($versionId: UUID!) {
    artifactsAdmin { version(id: $versionId) {
      id version completed finalized completedCommitSha blobs { digest role filename mediaType size }
    } }
  }
`
const completeDocument = gql`
  mutation CompleteArtifactUploads($versionId: UUID!, $commitSha: String!, $expectedBlobs: [ArtifactVersionBlobInput!]!) {
    artifactsAdmin { completeVersion(versionId: $versionId, commitSha: $commitSha, expectedBlobs: $expectedBlobs) {
      id version completed finalized completedCommitSha
    } }
  }
`
const historyDocument = gql`
  query ArtifactPublicationHistory($versionId: UUID!, $limit: Int!, $offset: Long!) {
    artifactsAdmin { publications(versionId: $versionId, limit: $limit, offset: $offset) {
      id destinationId tagName commitSha attempts published verified error modified files { filename digest size }
    } }
  }
`
const destinations = ref<Destination[]>([])
const secrets = ref<string[]>([])
const loading = ref(false)
const loaded = ref(false)
const error = ref('')
const message = ref('')
const editing = ref(false)
const selected = ref<Destination | null>(null)
const saving = ref(false)
const formError = ref('')
const form = reactive({ key: '', owner: '', githubRepository: '', githubRepositoryId: '', tagPrefix: '', tokenSecretName: '', enabled: false })
const publishTarget = ref<Destination | null>(null)
const publishing = ref(false)
const publishError = ref('')
const publishCommitSha = ref('')
const publishRunId = ref('')
const storedFiles = ref<StoredFile[]>([])
const filesLoading = ref(false)
const filesLoaded = ref(false)
const filesError = ref('')
const uploadsConfirmed = ref(false)
const recordingCompletion = ref(false)
let filesRequest = 0
const secretOptions = computed(() => secrets.value.map(name => ({ value: name, label: name })))
let settingsRequest = 0

async function loadSettings() {
  const request = ++settingsRequest
  loading.value = true
  loaded.value = false
  error.value = ''
  try {
    const result = await query<{ artifactsAdmin: { publicationDestinations: Destination[] }; pipelines: { secrets: { name: string }[] } }>(settingsDocument, { repositoryId: props.repositoryId })
    if (request !== settingsRequest) return
    destinations.value = result.artifactsAdmin.publicationDestinations
    secrets.value = result.pipelines.secrets.map(secret => secret.name)
    loaded.value = true
  } catch (e) {
    if (request === settingsRequest) error.value = e instanceof Error ? e.message : 'Could not load release destinations.'
  } finally {
    if (request === settingsRequest) loading.value = false
  }
}
function open(destination: Destination | null) {
  selected.value = destination
  Object.assign(form, {
    key: destination?.key ?? '', owner: destination?.owner ?? '', githubRepository: destination?.githubRepository ?? '',
    githubRepositoryId: destination ? String(destination.githubRepositoryId) : '', tagPrefix: destination?.tagPrefix ?? '',
    tokenSecretName: destination?.tokenSecretName ?? '', enabled: destination?.enabled ?? false,
  })
  formError.value = ''
  message.value = ''
  editing.value = true
}
function secretSaved(name: string) {
  if (!secrets.value.includes(name)) secrets.value.push(name)
}
async function save() {
  if (saving.value || !loaded.value) return
  const githubRepositoryId = Number(form.githubRepositoryId)
  if (!form.key.trim() || !form.owner.trim() || !form.githubRepository.trim() || !Number.isSafeInteger(githubRepositoryId) || githubRepositoryId <= 0 || !form.tokenSecretName.trim()) {
    formError.value = 'Destination name, owner, repository name, numeric repository ID, and token secret are required.'
    return
  }
  if (!secrets.value.includes(form.tokenSecretName.trim())) {
    formError.value = 'Add the token secret before saving the destination.'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    let destination: Destination
    if (selected.value) {
      const result = await mutation<{ artifactsAdmin: { updatePublicationDestination: Destination } }>(updateDocument, {
        id: selected.value.id, version: selected.value.version, enabled: form.enabled, tokenSecretName: form.tokenSecretName.trim(),
      })
      destination = result.artifactsAdmin.updatePublicationDestination
    } else {
      const result = await mutation<{ artifactsAdmin: { createPublicationDestination: Destination } }>(createDocument, {
        input: { repositoryId: props.repositoryId, key: form.key.trim(), owner: form.owner.trim(), githubRepository: form.githubRepository.trim(),
          githubRepositoryId, tagPrefix: form.tagPrefix.trim(), tokenSecretName: form.tokenSecretName.trim(), enabled: form.enabled },
      })
      destination = result.artifactsAdmin.createPublicationDestination
    }
    destinations.value = [...destinations.value.filter(item => item.id !== destination.id), destination]
    editing.value = false
    message.value = 'Release destination saved.'
  } catch (e) {
    formError.value = e instanceof Error ? e.message : 'Could not save release destination.'
  } finally { saving.value = false }
}

const pageSize = 15
const versions = ref<Version[]>([])
const versionsOffset = ref(0)
const versionsTotal = ref(0)
const versionsLoading = ref(false)
const versionsLoaded = ref(false)
const versionsError = ref('')
const versionId = ref('')
const versionOptions = computed(() => versions.value.map(version => ({ value: version.id, label: version.version })))
const selectedVersionCompleted = computed(() => versions.value.find(version => version.id === versionId.value)?.completed === true)
const selectedVersion = computed(() => versions.value.find(version => version.id === versionId.value))
const history = ref<Publication[]>([])
const historyOffset = ref(0)
const historyLoading = ref(false)
const historyError = ref('')
const rows = computed(() => history.value.slice(0, pageSize))
const hasNext = computed(() => history.value.length > pageSize)
let versionsRequest = 0
let historyRequest = 0
async function loadVersions() {
  const request = ++versionsRequest
  versionsLoading.value = true
  versionsLoaded.value = false
  versionsError.value = ''
  try {
    const result = await query<{ artifactsAdmin: { repository: { versionCount: number; versions: Version[] } | null } }>(versionsDocument, {
      repositoryId: props.repositoryId, limit: pageSize, offset: versionsOffset.value,
    })
    if (request !== versionsRequest) return
    versions.value = result.artifactsAdmin.repository?.versions ?? []
    versionsTotal.value = result.artifactsAdmin.repository?.versionCount ?? 0
    versionsLoaded.value = true
    if (!versions.value.some(version => version.id === versionId.value)) versionId.value = versions.value[0]?.id ?? ''
  } catch (e) {
    if (request === versionsRequest) versionsError.value = e instanceof Error ? e.message : 'Could not load versions.'
  } finally { if (request === versionsRequest) versionsLoading.value = false }
}
async function loadHistory() {
  const request = ++historyRequest
  history.value = []
  historyError.value = ''
  historyLoading.value = !!versionId.value
  if (!versionId.value) return
  try {
    const result = await query<{ artifactsAdmin: { publications: Publication[] } }>(historyDocument, {
      versionId: versionId.value, limit: pageSize + 1, offset: historyOffset.value,
    })
    if (request === historyRequest) history.value = result.artifactsAdmin.publications
  } catch (e) {
    if (request === historyRequest) historyError.value = e instanceof Error ? e.message : 'Could not load publication status.'
  } finally { if (request === historyRequest) historyLoading.value = false }
}
function changeVersionsPage(direction: number) {
  versionsOffset.value = Math.max(0, versionsOffset.value + direction * pageSize)
  void loadVersions()
}
function changeHistoryPage(direction: number) {
  historyOffset.value = Math.max(0, historyOffset.value + direction * pageSize)
  void loadHistory()
}
function openPublish(destination: Destination) {
  if (!destination.enabled || !secrets.value.includes(destination.tokenSecretName) || !versionsLoaded.value) return
  publishTarget.value = destination
  publishError.value = ''
  publishRunId.value = ''
  message.value = ''
  publishCommitSha.value = selectedVersion.value?.completedCommitSha
    ?? history.value.find(publication => publication.destinationId === destination.id)?.commitSha ?? ''
  void loadVersionFiles()
}
async function loadVersionFiles() {
  const request = ++filesRequest
  const id = versionId.value
  storedFiles.value = []
  filesLoaded.value = false
  filesError.value = ''
  uploadsConfirmed.value = false
  filesLoading.value = !!id
  if (!id) return
  try {
    const result = await query<{ artifactsAdmin: { version: (Version & { blobs: StoredFile[] }) | null } }>(versionFilesDocument, { versionId: id })
    if (request !== filesRequest) return
    const version = result.artifactsAdmin.version
    if (!version || version.id !== id) throw new Error('Artifact version not found.')
    versions.value = versions.value.map(item => item.id === id ? version : item)
    storedFiles.value = version.blobs
    filesLoaded.value = true
    if (!publishCommitSha.value) publishCommitSha.value = version.completedCommitSha ?? ''
  } catch (e) {
    if (request === filesRequest) filesError.value = e instanceof Error ? e.message : 'Could not load stored files.'
  } finally { if (request === filesRequest) filesLoading.value = false }
}
async function completeUploads() {
  if (recordingCompletion.value || publishing.value || !uploadsConfirmed.value || !filesLoaded.value || !storedFiles.value.length || selectedVersion.value?.finalized) return
  const commitSha = publishCommitSha.value.trim().toLowerCase()
  if (!/^[0-9a-f]{40}$/.test(commitSha)) {
    publishError.value = 'Enter the exact 40-character build commit SHA.'
    return
  }
  recordingCompletion.value = true
  publishError.value = ''
  try {
    const result = await mutation<{ artifactsAdmin: { completeVersion: Version } }>(completeDocument, {
      versionId: versionId.value, commitSha,
      expectedBlobs: storedFiles.value.map(({ digest, role, filename, mediaType }) => ({ versionId: versionId.value, digest, role, filename, mediaType })),
    })
    versions.value = versions.value.map(item => item.id === result.artifactsAdmin.completeVersion.id ? result.artifactsAdmin.completeVersion : item)
    uploadsConfirmed.value = false
    message.value = 'Upload completion recorded. Enabled destinations can publish this version automatically.'
    await loadHistory()
  } catch (e) {
    publishError.value = e instanceof Error ? e.message : 'Could not record upload completion.'
    uploadsConfirmed.value = false
  } finally { recordingCompletion.value = false }
}
function publicationStatus(publication: Publication) {
  if (publication.error) return 'Failed'
  if (publication.verified && (!publication.modified || new Date(publication.verified) >= new Date(publication.modified))) return 'Verified'
  return publication.published ? 'Published · awaiting verification' : 'Queued'
}
async function publishRelease() {
  const destination = publishTarget.value
  if (!destination || !destination.enabled || publishing.value || recordingCompletion.value || versionsLoading.value || !versionsLoaded.value || !versionId.value || !selectedVersionCompleted.value) return
  const commitSha = publishCommitSha.value.trim().toLowerCase()
  if (!/^[0-9a-f]{40}$/.test(commitSha)) {
    publishError.value = 'Enter the exact 40-character build commit SHA.'
    return
  }
  publishing.value = true
  publishError.value = ''
  try {
    const result = await mutation<{ artifactsAdmin: { publishRelease: string } }>(publishDocument, {
      destinationId: destination.id, versionId: versionId.value, commitSha,
    })
    publishRunId.value = result.artifactsAdmin.publishRelease
    publishTarget.value = null
    message.value = 'Publication queued.'
    historyOffset.value = 0
    await loadHistory()
  } catch (e) {
    publishError.value = e instanceof Error ? e.message : 'Could not queue publication.'
  } finally { publishing.value = false }
}
watch(versionId, () => {
  historyOffset.value = 0
  publishCommitSha.value = selectedVersion.value?.completedCommitSha ?? ''
  void loadHistory()
  if (publishTarget.value) void loadVersionFiles()
})
onMounted(() => { void loadSettings(); void loadVersions() })
onBeforeUnmount(() => { settingsRequest++; versionsRequest++; historyRequest++; filesRequest++ })
</script>

<template>
  <SectionCard title="GitHub release sync" subtitle="Publish completed raw artifact versions as release assets" padded>
    <template #right><Button size="sm" :disabled="!loaded || saving || !!publishTarget" @click="open(null)">Add destination</Button></template>
    <div class="stack">
      <p class="help">Completed builds sync through the Sync Completed Raw Artifacts to GitHub pipeline. Install Default Artifact Sync Pipelines in System → Packages. GitHub tags must already exist and match the build commit.</p>
      <p v-if="loading" class="help">Loading destinations…</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <Button v-if="!loading && !loaded" @click="loadSettings">Retry loading destinations</Button>
      <p v-if="message" role="status" class="help">{{ message }}</p>
      <p v-if="loaded && !destinations.length" class="help">No release destinations configured.</p>
      <div v-for="destination in destinations" :key="destination.id" class="destination">
        <div class="stack compact">
          <strong>{{ destination.key }}</strong>
          <code>{{ destination.owner }}/{{ destination.githubRepository }}</code>
          <span class="help">{{ destination.enabled ? 'Enabled' : 'Disabled' }} · Tag: {{ destination.tagPrefix }}&lt;version&gt; · Secret: {{ destination.tokenSecretName }}</span>
        </div>
        <div class="actions">
          <Button
            size="sm"
            :disabled="!loaded || saving || editing || !!publishTarget || !destination.enabled || !secrets.includes(destination.tokenSecretName) || !versionsLoaded || versionsLoading || !versions.length"
            @click="openPublish(destination)">Publish release</Button>
          <Button size="sm" :disabled="!loaded || saving || !!publishTarget" @click="open(destination)">Edit destination</Button>
        </div>
      </div>
      <p class="help">Use Publish release to publish an existing version to an enabled destination. The existing GitHub tag must point to its build commit.</p>
      <NuxtLink v-if="publishRunId" :to="{ path: '/pipelines/runs', query: { runId: publishRunId } }">View publication run</NuxtLink>
    </div>
  </SectionCard>
  <SectionCard title="Release sync status" subtitle="Publication and verification results for each version" padded>
    <template #right><Button size="sm" :disabled="!versionId || historyLoading" @click="loadHistory">Refresh status</Button></template>
    <div class="stack">
      <p v-if="versionsLoading" class="help">Loading versions…</p>
      <p v-if="versionsError" role="alert" class="error">{{ versionsError }}</p>
      <Button v-if="!versionsLoading && !versionsLoaded" @click="loadVersions">Retry loading versions</Button>
      <Select
        v-if="versionsLoaded && versions.length"
        v-model="versionId"
        label="Artifact version"
        :options="versionOptions"
        :accent="accent" />
      <p v-if="versionsLoaded && !versions.length" class="help">No versions published.</p>
      <div v-if="versionsOffset > 0 || versionsOffset + pageSize < versionsTotal" class="actions">
        <Button size="sm" :disabled="versionsLoading || versionsOffset === 0" @click="changeVersionsPage(-1)">Previous versions</Button>
        <Button size="sm" :disabled="versionsLoading || versionsOffset + pageSize >= versionsTotal" @click="changeVersionsPage(1)">Next versions</Button>
      </div>
      <p v-if="historyError" role="alert" class="error">{{ historyError }}</p>
      <p v-if="historyLoading" class="help">Loading release status…</p>
      <template v-else-if="versionId && !historyError">
        <p v-if="!rows.length" class="help">No publications on this page. Completed builds publish to enabled release destinations.</p>
        <div v-for="publication in rows" :key="publication.id" class="stack publication">
          <strong>{{ destinations.find(destination => destination.id === publication.destinationId)?.key ?? publication.destinationId }} · {{ publication.tagName }}</strong>
          <span class="help">{{ publicationStatus(publication) }} · {{ publication.attempts }} attempts</span>
          <code>{{ publication.commitSha }}</code>
          <p v-if="publication.published" class="help">Published: {{ new Date(publication.published).toLocaleString() }}</p>
          <p v-if="publication.verified" class="help">Last verified: {{ new Date(publication.verified).toLocaleString() }}</p>
          <p v-if="publication.error" role="alert" class="error">{{ publication.error }}</p>
          <span v-for="file in publication.files" :key="file.filename" class="help">{{ file.filename }} · {{ file.size }} bytes · <code>{{ file.digest }}</code></span>
        </div>
        <div v-if="historyOffset > 0 || hasNext" class="actions">
          <Button size="sm" :disabled="historyOffset === 0" @click="changeHistoryPage(-1)">Previous publications</Button>
          <Button size="sm" :disabled="!hasNext" @click="changeHistoryPage(1)">Next publications</Button>
        </div>
      </template>
      <p class="help">Open <NuxtLink to="/pipelines/runs">pipeline run history</NuxtLink> to inspect release runs or replay failures from the Dead Letter tab.</p>
    </div>
  </SectionCard>
  <Modal
    v-if="publishTarget"
    :title="`Publish release to '${publishTarget.key}'`"
    :accent="accent"
    @close="!publishing && !recordingCompletion && (publishTarget = null)">
    <form class="stack" @submit.prevent="publishRelease">
      <p class="help">Publish the stored files to {{ publishTarget.owner }}/{{ publishTarget.githubRepository }}. GitHub tag {{ publishTarget.tagPrefix }}&lt;version&gt; must already exist at the exact build commit.</p>
      <Select
        v-model="versionId"
        label="Artifact version"
        :options="versionOptions"
        :disabled="publishing || recordingCompletion || versionsLoading"
        :accent="accent" />
      <div v-if="versionsOffset > 0 || versionsOffset + pageSize < versionsTotal" class="actions">
        <Button
          type="button"
          size="sm"
          :disabled="publishing || recordingCompletion || versionsLoading || versionsOffset === 0"
          @click="changeVersionsPage(-1)">Previous versions</Button>
        <Button
          type="button"
          size="sm"
          :disabled="publishing || recordingCompletion || versionsLoading || versionsOffset + pageSize >= versionsTotal"
          @click="changeVersionsPage(1)">Next versions</Button>
      </div>
      <p v-if="versionsError" role="alert" class="error">{{ versionsError }}</p>
      <Button v-if="!versionsLoading && !versionsLoaded" type="button" @click="loadVersions">Retry loading versions</Button>
      <p v-if="versionId && !selectedVersionCompleted" role="alert" class="help">This version has no recorded upload completion. Wait for its producer jobs to finish, or review and confirm the completed files below.</p>
      <Button
        v-if="versionId && !selectedVersionCompleted"
        type="button"
        :disabled="publishing || recordingCompletion || versionsLoading"
        @click="loadVersions">Refresh versions</Button>
      <TextInput v-model="publishCommitSha" label="Build commit SHA" :disabled="publishing || recordingCompletion" />
      <p v-if="filesLoading" class="help">Loading stored files…</p>
      <p v-if="filesError" role="alert" class="error">{{ filesError }}</p>
      <div v-if="filesLoaded" class="stack compact">
        <strong>Stored files</strong>
        <span v-for="(file, index) in storedFiles" :key="`${file.digest}:${file.role}:${index}`" class="help">
          {{ file.filename ?? file.role }} · {{ file.size ?? 'Unknown' }} bytes · <code>{{ file.digest }}</code>
        </span>
        <p v-if="!storedFiles.length" class="help">No files stored.</p>
      </div>
      <template v-if="!selectedVersion?.finalized">
        <p class="help">For an existing version without completion history, confirm every file has finished uploading and was built from the commit above. Enabled release destinations can publish automatically after confirmation.</p>
        <label class="enable"><input v-model="uploadsConfirmed" type="checkbox" :disabled="publishing || recordingCompletion || !filesLoaded || !storedFiles.length"> All files have finished uploading and match this build commit</label>
        <Button
          type="button"
          :disabled="publishing || recordingCompletion || !uploadsConfirmed || !filesLoaded || !storedFiles.length"
          @click="completeUploads">{{ recordingCompletion ? 'Recording…' : 'Confirm completed uploads' }}</Button>
      </template>
      <Button type="button" :disabled="publishing || recordingCompletion || filesLoading" @click="loadVersionFiles">Refresh stored files</Button>
      <p v-if="publishError" role="alert" class="error">{{ publishError }}</p>
      <div class="actions">
        <Button type="button" :disabled="publishing || recordingCompletion" @click="publishTarget = null">Cancel</Button>
        <Button
          type="submit"
          primary
          :accent="accent"
          :disabled="publishing || recordingCompletion || versionsLoading || !versionsLoaded || !versionId || !selectedVersionCompleted">{{ publishing ? 'Queuing…' : 'Queue publication' }}</Button>
      </div>
    </form>
  </Modal>
  <Modal
    v-if="editing"
    :title="selected ? 'Edit GitHub release destination' : 'Add GitHub release destination'"
    :accent="accent"
    @close="!saving && (editing = false)">
    <form class="stack" @submit.prevent="save">
      <TextInput v-model="form.key" label="Destination name" :disabled="saving || !!selected" />
      <TextInput
        v-model="form.owner"
        label="GitHub owner"
        placeholder="bosca-io"
        :disabled="saving || !!selected" />
      <TextInput
        v-model="form.githubRepository"
        label="GitHub repository"
        placeholder="bosca"
        :disabled="saving || !!selected" />
      <TextInput v-model="form.githubRepositoryId" label="GitHub repository ID" :disabled="saving || !!selected" />
      <p class="help">Use the numeric ID from the GitHub repository API. The server verifies this identity before saving.</p>
      <TextInput
        v-model="form.tagPrefix"
        label="Tag prefix"
        :disabled="saving || !!selected" />
      <p class="help">Optional. The release tag is this prefix followed by the version; leave it empty so version 1.2.3 publishes to tag 1.2.3, as the Bosca CLI does. Repository identity and tag prefix are fixed after creation.</p>
      <GitHubSyncSecret
        v-model="form.tokenSecretName"
        kind="token"
        token-help="Enter a GitHub token with Contents read and write permission for this repository."
        :options="secretOptions"
        :names="secrets"
        :disabled="saving"
        @saved="secretSaved" />
      <label class="enable"><input v-model="form.enabled" type="checkbox" :disabled="saving"> Enable synchronization</label>
      <p v-if="formError" role="alert" class="error">{{ formError }}</p>
      <div class="actions">
        <Button type="button" :disabled="saving" @click="editing = false">Cancel</Button>
        <Button
          type="submit"
          primary
          :accent="accent"
          :disabled="saving">{{ saving ? 'Saving…' : 'Save destination' }}</Button>
      </div>
    </form>
  </Modal>
</template>

<style scoped>
.stack { display: flex; flex-direction: column; gap: 16px; }
.compact { gap: 6px; }
.actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 12px; }
.destination { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 0; border-bottom: 1px solid var(--line); }
.publication { padding: 12px 0; border-bottom: 1px solid var(--line); }
.help { color: var(--fg-2); font-size: 13px; line-height: 1.6; margin: 0; overflow-wrap: anywhere; }
.error { color: var(--err); font-size: 13px; margin: 0; overflow-wrap: anywhere; }
code { font-size: 12px; overflow-wrap: anywhere; }
.enable { display: flex; align-items: center; gap: 8px; font-size: 13px; }
</style>
