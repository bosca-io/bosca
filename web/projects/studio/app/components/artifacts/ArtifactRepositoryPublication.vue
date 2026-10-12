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
  files: { filename: string; digest: string; size: number }[]
}
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
const versionsDocument = gql`
  query ArtifactPublicationVersions($repositoryId: UUID!, $limit: Int!, $offset: Long!) {
    artifactsAdmin { repository(id: $repositoryId) { versionCount versions(limit: $limit, offset: $offset) { id version } } }
  }
`
const historyDocument = gql`
  query ArtifactPublicationHistory($versionId: UUID!, $limit: Int!, $offset: Long!) {
    artifactsAdmin { publications(versionId: $versionId, limit: $limit, offset: $offset) {
      id destinationId tagName commitSha attempts published verified error files { filename digest size }
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
const versions = ref<{ id: string; version: string }[]>([])
const versionsOffset = ref(0)
const versionsTotal = ref(0)
const versionsLoading = ref(false)
const versionsLoaded = ref(false)
const versionsError = ref('')
const versionId = ref('')
const versionOptions = computed(() => versions.value.map(version => ({ value: version.id, label: version.version })))
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
  versionId.value = ''
  try {
    const result = await query<{ artifactsAdmin: { repository: { versionCount: number; versions: { id: string; version: string }[] } | null } }>(versionsDocument, {
      repositoryId: props.repositoryId, limit: pageSize, offset: versionsOffset.value,
    })
    if (request !== versionsRequest) return
    versions.value = result.artifactsAdmin.repository?.versions ?? []
    versionsTotal.value = result.artifactsAdmin.repository?.versionCount ?? 0
    versionsLoaded.value = true
    versionId.value = versions.value[0]?.id ?? ''
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
watch(versionId, () => { historyOffset.value = 0; void loadHistory() })
onMounted(() => { void loadSettings(); void loadVersions() })
onBeforeUnmount(() => { settingsRequest++; versionsRequest++; historyRequest++ })
</script>

<template>
  <SectionCard title="GitHub release sync" subtitle="Publish completed raw artifact versions as release assets" padded>
    <template #right><Button size="sm" :disabled="!loaded || saving" @click="open(null)">Add destination</Button></template>
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
        <Button size="sm" :disabled="!loaded || saving" @click="open(destination)">Edit destination</Button>
      </div>
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
          <span class="help">{{ publication.verified ? 'Verified' : publication.error ? 'Failed' : publication.published ? 'Published · awaiting verification' : 'Queued' }} · {{ publication.attempts }} attempts</span>
          <code>{{ publication.commitSha }}</code>
          <p v-if="publication.published" class="help">Published: {{ new Date(publication.published).toLocaleString() }}</p>
          <p v-if="publication.verified" class="help">Verified: {{ new Date(publication.verified).toLocaleString() }}</p>
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
        placeholder="cli-v"
        :disabled="saving || !!selected" />
      <p class="help">For the Bosca CLI, use cli-v so version 1.2.3 publishes to cli-v1.2.3. Repository identity and tag prefix are fixed after creation.</p>
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
