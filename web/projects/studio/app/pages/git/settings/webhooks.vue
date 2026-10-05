<script setup lang="ts">
import gql from 'graphql-tag'

interface Webhook { id: string; url: string; active: boolean; events: string[]; created: string }

const { accent } = useCurrentSubsystem()
const { query, mutation } = useGraphQL()

const selectedRepoId = ref('')
const webhooks = ref<Webhook[]>([])
const loading = ref(false)
const showCreate = ref(false)
const saving = ref(false)
const error = ref('')

const form = reactive({
  url: '',
  secret: '',
  events: [] as string[],
  active: true,
})

const EVENT_OPTIONS = [
  { value: 'PUSH', label: 'Push' },
  { value: 'BRANCH_CREATED', label: 'Branch Created' },
  { value: 'BRANCH_DELETED', label: 'Branch Deleted' },
  { value: 'TAG_CREATED', label: 'Tag Created' },
  { value: 'TAG_DELETED', label: 'Tag Deleted' },
  { value: 'PULL_REQUEST_OPENED', label: 'PR Opened' },
  { value: 'PULL_REQUEST_CLOSED', label: 'PR Closed' },
  { value: 'PULL_REQUEST_MERGED', label: 'PR Merged' },
  { value: 'PULL_REQUEST_UPDATED', label: 'PR Updated' },
  { value: 'REVIEW_SUBMITTED', label: 'Review Submitted' },
]

// Load repos for selector
const repos = ref<Array<{ id: string; name: string }>>([])

onMounted(async () => {
  try {
    const result = await query<{ git: { repositories: Array<{ id: string; name: string }> } }>(gql`
      query Repos($ownerId: UUID!) {
        git { repositories(ownerId: $ownerId) { id name } }
      }
    `, { ownerId: '00000000-0000-0000-0000-000000000000' })
    repos.value = result.git?.repositories ?? []
    if (repos.value[0]) selectedRepoId.value = repos.value[0].id
  } catch { /* ignore */ }
})

const repoOptions = computed(() => repos.value.map(r => ({ value: r.id, label: r.name })))

watch(selectedRepoId, async (id) => {
  if (!id) return
  loading.value = true
  try {
    const result = await query<{ git: { webhooks: Webhook[] } }>(gql`
      query Webhooks($repositoryId: UUID!) {
        git { webhooks(repositoryId: $repositoryId) {
          id url active events created
        } }
      }
    `, { repositoryId: id })
    webhooks.value = result.git?.webhooks ?? []
  } catch { webhooks.value = [] }
  finally { loading.value = false }
}, { immediate: true })

function resetForm() {
  form.url = ''
  form.secret = ''
  form.events = ['PUSH']
  form.active = true
  error.value = ''
}

function openCreate() {
  resetForm()
  showCreate.value = true
}

function toggleEvent(event: string) {
  const idx = form.events.indexOf(event)
  if (idx >= 0) form.events.splice(idx, 1)
  else form.events.push(event)
}

async function handleCreate() {
  if (!form.url || !form.secret || !form.events.length) {
    error.value = 'URL, secret, and at least one event are required.'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await mutation(gql`
      mutation CreateWebhook($repositoryId: UUID!, $input: GitWebhookInput!) {
        git { createWebhook(repositoryId: $repositoryId, input: $input) { id } }
      }
    `, {
      repositoryId: selectedRepoId.value,
      input: { url: form.url, secret: form.secret, events: form.events, active: form.active },
    })
    showCreate.value = false
    // Reload
    const result = await query<{ git: { webhooks: Webhook[] } }>(gql`
      query Webhooks($repositoryId: UUID!) {
        git { webhooks(repositoryId: $repositoryId) { id url active events created } }
      }
    `, { repositoryId: selectedRepoId.value })
    webhooks.value = result.git?.webhooks ?? []
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create webhook'
  } finally {
    saving.value = false
  }
}

async function deleteWebhook(id: string) {
  try {
    await mutation(gql`mutation DeleteWebhook($id: UUID!) { git { deleteWebhook(id: $id) } }`, { id })
    webhooks.value = webhooks.value.filter(w => w.id !== id)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to delete webhook'
  }
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', 'Settings', 'Webhooks')"
        title="Webhooks"
        subtitle="Manage webhook endpoints for repository events"
      >
        <template #actions>
          <Select
            v-model="selectedRepoId"
            :options="repoOptions"
            placeholder="Select repository"
            :accent="accent"
            size="sm" />
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedRepoId"
            @click="openCreate">New Webhook</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loading" class="loading-state">Loading webhooks…</div>
    <div v-else-if="!selectedRepoId" class="loading-state">Select a repository to manage webhooks.</div>

    <div v-else class="webhook-list">
      <div v-for="w in webhooks" :key="w.id" class="webhook-row">
        <div class="webhook-icon" :style="{ background: `color-mix(in oklch, ${w.active ? '#34d99a' : '#6c7388'} 14%, transparent)` }">
          <Icon name="globe" :size="14" :color="w.active ? '#34d99a' : '#6c7388'" />
        </div>
        <div class="webhook-body">
          <div class="webhook-url mono">{{ w.url }}</div>
          <div class="webhook-meta">
            <Badge :color="w.active ? '#34d99a' : '#6c7388'">{{ w.active ? 'active' : 'inactive' }}</Badge>
            <span class="event-list">{{ w.events.map((e: string) => e.replace(/_/g, ' ').toLowerCase()).join(', ') }}</span>
          </div>
        </div>
        <span class="mono webhook-date">{{ formatDate(w.created) }}</span>
        <button class="delete-btn" @click="deleteWebhook(w.id)">
          <Icon name="trash" :size="13" color="var(--fg-3)" />
        </button>
      </div>
      <div v-if="!webhooks.length" class="empty-msg">No webhooks configured for this repository.</div>
    </div>

    <!-- Create Modal -->
    <Modal
      v-if="showCreate"
      title="New Webhook"
      icon="globe"
      :accent="accent"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput v-model="form.url" label="Payload URL" placeholder="https://example.com/webhook" />
        <TextInput v-model="form.secret" label="Secret" placeholder="webhook-secret" />
        <div class="field-group">
          <label class="field-label">Events</label>
          <div class="event-grid">
            <label v-for="evt in EVENT_OPTIONS" :key="evt.value" class="event-check">
              <input type="checkbox" :checked="form.events.includes(evt.value)" @change="toggleEvent(evt.value)" >
              <span>{{ evt.label }}</span>
            </label>
          </div>
        </div>
        <label class="checkbox-row">
          <input v-model="form.active" type="checkbox" >
          <span>Active</span>
        </label>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreate">
          {{ saving ? 'Creating…' : 'Create Webhook' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.loading-state, .empty-msg {
  color: var(--fg-3); text-align: center; padding: 48px 0; font-size: 13.5px;
}

.webhook-list {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  overflow: hidden;
}

.webhook-row {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 16px; border-bottom: 1px solid var(--line);
}
.webhook-row:last-child { border-bottom: none; }

.webhook-icon {
  width: 32px; height: 32px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center; flex: 0 0 32px;
}

.webhook-body { flex: 1; min-width: 0; }
.webhook-url { font-size: 13px; color: var(--fg-0); font-weight: 500; }
.webhook-meta { display: flex; align-items: center; gap: 8px; margin-top: 4px; }
.event-list { font-size: 11px; color: var(--fg-3); }
.webhook-date { font-size: 11px; color: var(--fg-3); }

.delete-btn {
  background: none; border: none; padding: 6px; cursor: pointer;
  border-radius: 4px; display: flex; opacity: 0.4; transition: opacity 0.1s;
}
.delete-btn:hover { opacity: 1; background: color-mix(in oklch, var(--err) 10%, transparent); }

.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.field-group { display: flex; flex-direction: column; gap: 8px; }
.field-label { font-size: 12px; font-weight: 600; color: var(--fg-2); }

.event-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 6px;
}

.event-check {
  display: flex; align-items: center; gap: 6px;
  font-size: 12.5px; color: var(--fg-1); cursor: pointer;
}
.event-check input { accent-color: v-bind(accent); }

.checkbox-row {
  display: flex; align-items: center; gap: 8px;
  font-size: 13px; color: var(--fg-1); cursor: pointer;
}
.checkbox-row input { accent-color: v-bind(accent); }
</style>
