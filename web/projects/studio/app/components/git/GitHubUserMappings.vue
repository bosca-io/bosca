<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { query, mutation } = useGraphQL()
interface Mapping { githubUserId: number; principalId: string }
interface Principal { id: string; primaryProfileId: string | null; profiles: { id: string; name: string }[]; credentials: { identifier: string }[] }
const mappings = ref<Mapping[]>([])
const principals = ref<Principal[]>([])
const githubUserId = ref('')
const principalId = ref('')
const offset = ref(0)
const principalOffset = ref(0)
const limit = 25
const canNext = ref(false)
const canNextPrincipal = ref(false)
const loading = ref(true)
const principalsLoading = ref(true)
const busy = ref(false)
const error = ref('')
const principalError = ref('')
const message = ref('')
const columns: GlassTableColumn[] = [
  { key: 'githubUserId', label: 'GitHub user ID', width: '1fr' },
  { key: 'principalId', label: 'Bosca principal', width: '1fr' },
  { key: 'actions', label: '', width: '1fr' },
]
const principalOptions = computed(() => principals.value.map(principal => ({
  value: principal.id,
  label: principal.profiles.find(profile => profile.id === principal.primaryProfileId)?.name
    || principal.credentials[0]?.identifier || principal.id,
})))

async function loadMappings() {
  loading.value = true
  error.value = ''
  try {
    const result = await query<{ github: { users: Mapping[] } }>(gql`
      query GitHubSyncUsers($offset: Long!, $limit: Int!) {
        github { users(offset: $offset, limit: $limit) { githubUserId principalId } }
      }
    `, { offset: offset.value, limit: limit + 1 })
    mappings.value = result.github.users.slice(0, limit)
    canNext.value = result.github.users.length > limit
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load user mappings.'
    mappings.value = []
    canNext.value = false
  } finally {
    loading.value = false
  }
}

async function loadPrincipals() {
  principalsLoading.value = true
  principalError.value = ''
  principalId.value = ''
  try {
    const result = await query<{ security: { principals: { all: Principal[] } } }>(gql`
      query GitHubSyncPrincipals($offset: Long!, $limit: Int!) {
        security { principals { all(offset: $offset, limit: $limit, includeDeleted: false) {
          id primaryProfileId profiles { id name } credentials { identifier }
        } } }
      }
    `, { offset: principalOffset.value, limit: limit + 1 })
    principals.value = result.security.principals.all.slice(0, limit)
    canNextPrincipal.value = result.security.principals.all.length > limit
  } catch (e) {
    principalError.value = e instanceof Error ? e.message : 'Could not load principals.'
    principals.value = []
    canNextPrincipal.value = false
  } finally {
    principalsLoading.value = false
  }
}

async function save() {
  if (busy.value || loading.value || principalsLoading.value) return
  error.value = ''
  message.value = ''
  const id = Number(githubUserId.value)
  if (!/^\d+$/.test(githubUserId.value) || !Number.isSafeInteger(id) || id <= 0 || !principalId.value) {
    error.value = 'Enter a positive GitHub human user ID and select a Bosca principal.'
    return
  }
  busy.value = true
  try {
    await mutation(gql`mutation MapGitHubSyncUser($githubUserId: Long!, $principalId: UUID!) {
      github { mapUser(githubUserId: $githubUserId, principalId: $principalId) { githubUserId principalId } }
    }`, { githubUserId: id, principalId: principalId.value })
    githubUserId.value = ''
    principalId.value = ''
    message.value = 'User mapping saved.'
    offset.value = 0
    await loadMappings()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not save user mapping.'
  } finally {
    busy.value = false
  }
}

async function remove(id: number) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await mutation(gql`mutation UnmapGitHubSyncUser($githubUserId: Long!) {
      github { unmapUser(githubUserId: $githubUserId) }
    }`, { githubUserId: id })
    if (mappings.value.length === 1 && offset.value > 0) offset.value -= limit
    await loadMappings()
    message.value = 'User mapping removed. Previous deliveries retain their original attribution.'
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not remove user mapping.'
  } finally {
    busy.value = false
  }
}

function page(direction: number) {
  offset.value += direction * limit
  void loadMappings()
}
function principalPage(direction: number) {
  principalOffset.value += direction * limit
  void loadPrincipals()
}
onMounted(async () => {
  await loadMappings()
  await loadPrincipals()
})
</script>

<template>
  <SectionCard title="GitHub user mappings" padded>
    <p class="help">Mappings apply to all paired repositories. Select an authenticated Bosca principal for each GitHub human user ID. Permissions come from their Bosca groups. PR imports also require the original author's primary profile.</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="principalError" role="alert" class="error">{{ principalError }} <Button :disabled="principalsLoading" @click="loadPrincipals">Retry principals</Button></p>
    <form class="mapping-form" @submit.prevent="save">
      <TextInput
        v-model="githubUserId"
        label="GitHub human user ID"
        :disabled="busy"
        mono />
      <Select
        v-model="principalId"
        label="Bosca principal"
        :options="principalOptions"
        searchable
        :loading="principalsLoading"
        :disabled="busy || principalsLoading" />
      <Button type="submit" :disabled="busy || loading || principalsLoading" primary>{{ busy ? 'Saving…' : 'Save mapping' }}</Button>
    </form>
    <div class="pager">
      <Button :disabled="principalOffset === 0 || principalsLoading || busy" @click="principalPage(-1)">Previous principals</Button>
      <span>Principals page {{ principalOffset / limit + 1 }}</span>
      <Button :disabled="!canNextPrincipal || principalsLoading || busy" @click="principalPage(1)">Next principals</Button>
    </div>
    <p v-if="message" role="status">{{ message }}</p>
    <GlassTable
      :columns="columns"
      :rows="mappings"
      :loading="loading"
      :arrow="false"
      empty-text="No user mappings on this page.">
      <template #col-principalId="{ row }"><NuxtLink :to="`/system/security/principals/${row.principalId}`">{{ row.principalId }}</NuxtLink></template>
      <template #col-actions="{ row }"><Button :disabled="busy || loading" size="sm" @click="remove(row.githubUserId)">Remove mapping</Button></template>
    </GlassTable>
    <div class="pager">
      <Button :disabled="offset === 0 || busy || loading" @click="page(-1)">Previous mappings</Button>
      <span>Mappings page {{ offset / limit + 1 }}</span>
      <Button :disabled="!canNext || busy || loading" @click="page(1)">Next mappings</Button>
      <Button :disabled="busy || loading" @click="loadMappings">Refresh mappings</Button>
    </div>
  </SectionCard>
</template>

<style scoped>
.help { color: var(--fg-2); font-size: 13px; line-height: 1.6; margin-top: 0; }
.mapping-form { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 16px; }
.mapping-form > :first-child, .mapping-form > :nth-child(2) { flex: 1 1 220px; }
.pager { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 16px 0; font-size: 12px; color: var(--fg-2); }
.error { color: var(--err); }
a { text-decoration: underline; }
</style>
