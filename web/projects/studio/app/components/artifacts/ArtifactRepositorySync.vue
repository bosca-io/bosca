<script setup lang="ts">
import gql from 'graphql-tag'
import GitHubSyncSecret from '../git/GitHubSyncSecret.vue'

const props = defineProps<{ repositoryId: string }>()
const { query, mutation } = useGraphQL()
const { accent } = useCurrentSubsystem()

interface Destination {
  id: string; key: string; remoteRepository: string; username: string
  tokenSecretName: string; enabled: boolean; version: number
}
interface Sync {
  id: string; destinationId: string; tagName: string; manifestDigest: string
  attempts: number; synced: string | null; error: string | null; modified: string | null
}

const destinationFields = gql`
  fragment ArtifactSyncDestinationFields on ArtifactSyncDestination {
    id key remoteRepository username tokenSecretName enabled version
  }
`
const loadDocument = gql`
  query ArtifactSyncSettings($repositoryId: UUID!) {
    artifactsAdmin { syncDestinations(repositoryId: $repositoryId) { ...ArtifactSyncDestinationFields } }
    pipelines { secrets { name } }
  }
  ${destinationFields}
`
const createDocument = gql`
  mutation CreateArtifactSyncDestination($input: ArtifactSyncDestinationInput!) {
    artifactsAdmin { createSyncDestination(input: $input) { ...ArtifactSyncDestinationFields } }
  }
  ${destinationFields}
`
const updateDocument = gql`
  mutation UpdateArtifactSyncDestination($id: UUID!, $version: Long!, $enabled: Boolean!, $username: String!, $tokenSecretName: String!) {
    artifactsAdmin {
      updateSyncDestination(id: $id, version: $version, enabled: $enabled, username: $username, tokenSecretName: $tokenSecretName) {
        ...ArtifactSyncDestinationFields
      }
    }
  }
  ${destinationFields}
`
const historyDocument = gql`
  query ArtifactSyncHistory($repositoryId: UUID!, $limit: Int!, $offset: Long!) {
    artifactsAdmin {
      syncs(repositoryId: $repositoryId, limit: $limit, offset: $offset) {
        id destinationId tagName manifestDigest attempts synced error modified
      }
    }
  }
`
const retryDocument = gql`
  mutation RetryArtifactSync($id: UUID!) { artifactsAdmin { retrySync(id: $id) { id } } }
`

const destinations = ref<Destination[]>([])
const secrets = ref<string[]>([])
const loading = ref(true)
const loaded = ref(false)
const error = ref('')
const message = ref('')
const editing = ref(false)
const selected = ref<Destination | null>(null)
const saving = ref(false)
const formError = ref('')
const form = reactive({ key: '', remoteRepository: '', username: '', tokenSecretName: '', enabled: false })
const secretOptions = computed(() => [...new Set([
  ...secrets.value, form.tokenSecretName,
])].filter(Boolean).map(name => ({
  value: name, label: secrets.value.includes(name) ? name : `${name} (missing)`,
})))

const history = ref<Sync[]>([])
const historyLoading = ref(true)
const historyLoaded = ref(false)
const historyError = ref('')
const offset = ref(0)
const pageSize = 15
const rows = computed(() => history.value.slice(0, pageSize))
const hasNext = computed(() => history.value.length > pageSize)
const retrying = ref('')
let settingsRequest = 0
let historyRequest = 0

async function loadSettings() {
  const request = ++settingsRequest
  loading.value = true
  loaded.value = false
  error.value = ''
  try {
    const result = await query<{ artifactsAdmin: { syncDestinations: Destination[] }; pipelines: { secrets: { name: string }[] } }>(
      loadDocument, { repositoryId: props.repositoryId },
    )
    if (request !== settingsRequest) return
    destinations.value = result.artifactsAdmin.syncDestinations
    secrets.value = result.pipelines.secrets.map(secret => secret.name)
    loaded.value = true
  } catch (e) {
    if (request === settingsRequest) error.value = e instanceof Error ? e.message : 'Could not load sync destinations.'
  } finally {
    if (request === settingsRequest) loading.value = false
  }
}

async function loadHistory() {
  const request = ++historyRequest
  historyLoading.value = true
  historyLoaded.value = false
  historyError.value = ''
  try {
    const result = await query<{ artifactsAdmin: { syncs: Sync[] } }>(
      historyDocument, { repositoryId: props.repositoryId, limit: pageSize + 1, offset: offset.value },
    )
    if (request !== historyRequest) return
    history.value = result.artifactsAdmin.syncs
    historyLoaded.value = true
  } catch (e) {
    if (request === historyRequest) historyError.value = e instanceof Error ? e.message : 'Could not load sync status.'
  } finally {
    if (request === historyRequest) historyLoading.value = false
  }
}

function open(destination: Destination | null) {
  selected.value = destination
  Object.assign(form, {
    key: destination?.key ?? '', remoteRepository: destination?.remoteRepository ?? '',
    username: destination?.username ?? '', tokenSecretName: destination?.tokenSecretName ?? '',
    enabled: destination?.enabled ?? false,
  })
  formError.value = ''
  message.value = ''
  editing.value = true
}

function secretSaved(name: string) {
  if (!secrets.value.includes(name)) secrets.value.push(name)
  formError.value = ''
}

async function save() {
  if (saving.value || !loaded.value) return
  formError.value = ''
  const fields = {
    key: form.key.trim(), remoteRepository: form.remoteRepository.trim(),
    username: form.username.trim(), tokenSecretName: form.tokenSecretName.trim(), enabled: form.enabled,
  }
  if (!fields.key || !fields.remoteRepository || !fields.username || !fields.tokenSecretName) {
    formError.value = 'Destination name, image path, username, and token secret are required.'
    return
  }
  if (fields.enabled && !secrets.value.includes(fields.tokenSecretName)) {
    formError.value = 'Add the token secret before enabling synchronization.'
    return
  }
  saving.value = true
  try {
    let destination: Destination
    if (selected.value) {
      const result = await mutation<{ artifactsAdmin: { updateSyncDestination: Destination } }>(updateDocument, {
        id: selected.value.id, version: selected.value.version,
        enabled: fields.enabled, username: fields.username, tokenSecretName: fields.tokenSecretName,
      })
      destination = result.artifactsAdmin.updateSyncDestination
    } else {
      const result = await mutation<{ artifactsAdmin: { createSyncDestination: Destination } }>(createDocument, {
        input: { repositoryId: props.repositoryId, ...fields },
      })
      destination = result.artifactsAdmin.createSyncDestination
    }
    destinations.value = [...destinations.value.filter(item => item.id !== destination.id), destination]
    editing.value = false
    message.value = 'Sync destination saved.'
  } catch (e) {
    formError.value = e instanceof Error ? e.message : 'Could not save sync destination.'
  } finally {
    saving.value = false
  }
}

function destinationFor(sync: Sync) {
  return destinations.value.find(destination => destination.id === sync.destinationId)
}

async function retry(sync: Sync) {
  if (retrying.value || !loaded.value || !destinationFor(sync)?.enabled) return
  retrying.value = sync.id
  historyError.value = ''
  message.value = ''
  try {
    await mutation(retryDocument, { id: sync.id })
    message.value = 'Sync queued for retry.'
    offset.value = 0
    await loadHistory()
  } catch (e) {
    historyError.value = e instanceof Error ? e.message : 'Could not retry sync.'
  } finally {
    retrying.value = ''
  }
}

function changePage(delta: number) {
  offset.value = Math.max(0, offset.value + delta * pageSize)
  loadHistory()
}

function formatDate(value: string | null) {
  return value ? new Date(value).toLocaleString() : '—'
}

onMounted(() => {
  loadSettings()
  loadHistory()
})
onBeforeUnmount(() => {
  settingsRequest++
  historyRequest++
})
</script>

<template>
  <SectionCard title="GitHub Container Registry sync" subtitle="Push each newly published tag to configured GHCR images" padded>
    <template #right>
      <div class="actions">
        <Button size="sm" :disabled="loading || saving || editing" @click="loadSettings">Reload destinations</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!loaded || saving || editing"
          @click="open(null)">Add destination</Button>
      </div>
    </template>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="loading" class="help">Loading sync destinations…</p>
    <Button v-else-if="!loaded" @click="loadSettings">Retry loading destinations</Button>
    <div v-else class="stack">
      <p v-if="!destinations.length" class="help">No sync destinations configured for this artifact.</p>
      <div v-for="destination in destinations" :key="destination.id" class="destination">
        <div class="stack compact">
          <strong>{{ destination.key }}</strong>
          <code>ghcr.io/{{ destination.remoteRepository }}</code>
          <span class="help">{{ destination.enabled ? 'Enabled' : 'Disabled' }} · {{ destination.username }} · {{ destination.tokenSecretName }}</span>
          <span v-if="!secrets.includes(destination.tokenSecretName)" class="error">Token secret is missing. Restore it or select a replacement.</span>
        </div>
        <Button size="sm" :disabled="saving || editing" @click="open(destination)">Edit destination</Button>
      </div>
      <p class="help">Source tags are preserved. Enabling a destination applies to future publishes; publish an existing tag again to sync it.</p>
    </div>
    <p v-if="message" role="status">{{ message }}</p>
  </SectionCard>

  <SectionCard title="Sync status" subtitle="Latest requested manifest for each destination and tag" padded>
    <template #right>
      <Button size="sm" :disabled="historyLoading || !!retrying" @click="loadHistory">Refresh status</Button>
    </template>
    <p v-if="historyError" role="alert" class="error">{{ historyError }}</p>
    <p v-if="historyLoading" class="help">Loading sync status…</p>
    <Button v-else-if="!historyLoaded" @click="loadHistory">Retry loading status</Button>
    <div v-else class="stack">
      <p v-if="!rows.length" class="help">No sync requests on this page.</p>
      <div v-for="sync in rows" :key="sync.id" class="sync-row">
        <div class="stack compact">
          <strong>{{ destinationFor(sync)?.key ?? sync.destinationId }} · {{ sync.tagName }}</strong>
          <code v-if="destinationFor(sync)">ghcr.io/{{ destinationFor(sync)?.remoteRepository }}:{{ sync.tagName }}</code>
          <code>{{ sync.manifestDigest }}</code>
          <span class="help">{{ sync.synced ? 'Synced' : sync.error ? 'Failed · awaiting retry' : 'Queued' }} · {{ sync.attempts }} attempts · {{ formatDate(sync.synced || sync.modified) }}</span>
          <p v-if="sync.error" class="error">{{ sync.error }}</p>
          <span v-if="destinationFor(sync)?.enabled === false" class="help">Destination disabled</span>
        </div>
        <Button
          v-if="sync.error && !sync.synced"
          size="sm"
          :disabled="!!retrying || !loaded || !destinationFor(sync)?.enabled"
          @click="retry(sync)">{{ retrying === sync.id ? 'Queuing…' : 'Retry sync' }}</Button>
      </div>
      <div v-if="offset > 0 || hasNext" class="actions pagination">
        <Button size="sm" :disabled="offset === 0 || historyLoading || !!retrying" @click="changePage(-1)">Previous</Button>
        <span class="help">Page {{ Math.floor(offset / pageSize) + 1 }}</span>
        <Button size="sm" :disabled="!hasNext || historyLoading || !!retrying" @click="changePage(1)">Next</Button>
      </div>
    </div>
  </SectionCard>

  <Modal
    v-if="editing"
    :title="selected ? 'Edit GHCR destination' : 'Add GHCR destination'"
    :accent="accent"
    @close="!saving && (editing = false)">
    <form class="stack" @submit.prevent="save">
      <TextInput v-model="form.key" label="Destination name" :disabled="saving || !!selected" />
      <TextInput
        v-model="form.remoteRepository"
        label="GHCR image path"
        placeholder="owner/image"
        mono
        :disabled="saving || !!selected" />
      <p class="help">Use a lowercase owner/image path without ghcr.io or a tag. The destination name and image path are fixed after creation.</p>
      <TextInput v-model="form.username" label="GitHub username" :disabled="saving" />
      <GitHubSyncSecret
        v-model="form.tokenSecretName"
        kind="token"
        token-help="Enter a GitHub access token with permission to push packages to this GHCR image."
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
.compact { gap: 6px; min-width: 0; }
.actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 12px; }
.destination, .sync-row { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 0; border-bottom: 1px solid var(--line); }
.help { color: var(--fg-2); font-size: 13px; line-height: 1.6; margin: 0; }
.error { color: var(--err); font-size: 13px; margin: 0; overflow-wrap: anywhere; }
code { font-size: 12px; overflow-wrap: anywhere; }
.enable { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.pagination { justify-content: center; }
</style>
