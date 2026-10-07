<script setup lang="ts">
import gql from 'graphql-tag'
import GitHubSyncSecret from './GitHubSyncSecret.vue'

const props = defineProps<{ repositoryId: string }>()
const emit = defineEmits<{ enabled: [value: boolean] }>()
const { query, mutation } = useGraphQL()
const { accent } = useCurrentSubsystem()
const runtimeConfig = useRuntimeConfig()

interface Pair {
  repositoryId: string; owner: string; name: string
  webhookSecretName: string; tokenSecretName: string; enabled: boolean; version: number
}

const pairFields = gql`
  fragment SyncPairFields on GitHubRepositoryPair {
    repositoryId owner name webhookSecretName tokenSecretName enabled version
  }
`
const loadDocument = gql`
  query GitHubSyncPair($repositoryId: UUID!) {
    github { pair(repositoryId: $repositoryId) { ...SyncPairFields } }
    pipelines { secrets { name } }
  }
  ${pairFields}
`
const saveDocument = gql`
  mutation SaveGitHubSyncPair($input: GitHubRepositoryPairInput!) {
    github { savePair(input: $input) { ...SyncPairFields } }
  }
  ${pairFields}
`
const pair = ref<Pair | null>(null)
const secrets = ref<string[]>([])
const loading = ref(true)
const saving = ref(false)
const loaded = ref(false)
const error = ref('')
const message = ref('')
const form = reactive({
  owner: '', name: '', webhookSecretName: '', tokenSecretName: '', enabled: false,
})
const secretOptions = computed(() => [...new Set([
  ...secrets.value, pair.value?.webhookSecretName, pair.value?.tokenSecretName,
])].filter((name): name is string => !!name).map(name => ({
  value: name, label: secrets.value.includes(name) ? name : `${name} (missing)`,
})))
const webhookUrl = computed(() => `${runtimeConfig.public.gitServerUrl.replace(/\/+$/, '')}/api/webhooks/github/${props.repositoryId}`)

function secretSaved(name: string) {
  if (!secrets.value.includes(name)) secrets.value.push(name)
  error.value = ''
  message.value = 'Secret saved. Save the pairing to use it for this repository.'
}

function fillForm(value: Pair | null) {
  Object.assign(form, {
    owner: value?.owner ?? '', name: value?.name ?? '',
    webhookSecretName: value?.webhookSecretName ?? '', tokenSecretName: value?.tokenSecretName ?? '',
    enabled: value?.enabled ?? false,
  })
}

async function load() {
  loading.value = true
  loaded.value = false
  error.value = ''
  message.value = ''
  try {
    const result = await query<{ github: { pair: Pair | null }; pipelines: { secrets: { name: string }[] } }>(
      loadDocument, { repositoryId: props.repositoryId },
    )
    pair.value = result.github.pair
    emit('enabled', pair.value?.enabled ?? false)
    secrets.value = result.pipelines.secrets.map(secret => secret.name)
    fillForm(pair.value)
    loaded.value = true
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load repository pairing.'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!loaded.value || saving.value) return
  error.value = ''
  message.value = ''
  if (!form.owner.trim() || !form.name.trim() || !form.webhookSecretName || !form.tokenSecretName) {
    error.value = 'Owner, repository name, and both secret names are required.'
    return
  }
  const needsLookup = !pair.value || pair.value.owner !== form.owner.trim() || pair.value.name !== form.name.trim()
  if (needsLookup && !secrets.value.includes(form.tokenSecretName)) {
    error.value = 'Add the token secret so Bosca can look up the GitHub repository.'
    return
  }
  if (form.enabled && (!secrets.value.includes(form.webhookSecretName) || !secrets.value.includes(form.tokenSecretName))) {
    error.value = 'Add both secrets before enabling synchronization.'
    return
  }
  saving.value = true
  try {
    const result = await mutation<{ github: { savePair: Pair } }>(saveDocument, {
      input: {
        repositoryId: props.repositoryId,
        owner: form.owner.trim(), name: form.name.trim(),
        webhookSecretName: form.webhookSecretName, tokenSecretName: form.tokenSecretName,
        enabled: form.enabled, version: pair.value?.version ?? 0,
      },
    })
    pair.value = result.github.savePair
    emit('enabled', pair.value.enabled)
    fillForm(pair.value)
    message.value = 'Repository pairing saved.'
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not save repository pairing.'
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <SectionCard title="Repository pairing" padded>
    <template #right>
      <Button size="sm" :disabled="loading || saving" @click="load">Reload pairing</Button>
    </template>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="loading">Loading pairing…</p>
    <Button v-else-if="!loaded" @click="load">Retry</Button>
    <form v-else class="pair-form" @submit.prevent="save">
      <div class="fields">
        <TextInput v-model="form.owner" label="GitHub owner" :disabled="saving" />
        <TextInput v-model="form.name" label="GitHub repository name" :disabled="saving" />
        <GitHubSyncSecret
          v-model="form.webhookSecretName"
          kind="webhook"
          :options="secretOptions"
          :names="secrets"
          :disabled="saving"
          @saved="secretSaved" />
        <GitHubSyncSecret
          v-model="form.tokenSecretName"
          kind="token"
          :options="secretOptions"
          :names="secrets"
          :disabled="saving"
          @saved="secretSaved" />
      </div>
      <p class="help">Add credentials here or select existing secrets. You can manage all secrets in <NuxtLink to="/pipelines/secrets">Pipelines → Secrets</NuxtLink>. Bosca looks up the repository using the selected token when you first save or change its owner or name.</p>
      <p v-if="pair && (!secrets.includes(pair.webhookSecretName) || !secrets.includes(pair.tokenSecretName))" class="error">A referenced secret is missing. Restore it or select a replacement.</p>
      <label class="enable"><input v-model="form.enabled" type="checkbox" :disabled="saving"> Enable synchronization</label>
      <p class="help">In GitHub, configure a JSON webhook for push and pull request events using the selected webhook secret and this payload URL:</p>
      <code class="webhook-url">{{ webhookUrl }}</code>
      <p class="help">User mappings grant no permissions. Inbound changes require repository Edit; builds also require Execute. Enable the GitHub package's import, export, and reconciliation pipelines in <NuxtLink to="/pipelines/all">Pipelines</NuxtLink>.</p>
      <p v-if="message" role="status">{{ message }}</p>
      <div><Button
        primary
        :accent="accent"
        :disabled="saving"
        type="submit">{{ saving ? 'Saving…' : 'Save pairing' }}</Button></div>
    </form>
  </SectionCard>
</template>

<style scoped>
.pair-form { display: flex; flex-direction: column; gap: 16px; }
.fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr)); gap: 16px; }
.help { color: var(--fg-2); font-size: 13px; margin: 0; line-height: 1.6; }
.help a { color: var(--fg-0); text-decoration: underline; }
.error { color: var(--err); }
.webhook-url { overflow-wrap: anywhere; font-size: 12px; }
.enable { display: flex; align-items: center; gap: 8px; font-size: 13px; }
</style>
