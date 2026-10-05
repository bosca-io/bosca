<script setup lang="ts">
import gql from 'graphql-tag'
import { emptyFeedSourceForm, useFeedSourceForm } from '~/composables/useFeedSourceForm'

definePageMeta({ middleware: 'feeds-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()
const route = useRoute()
const router = useRouter()

const sourceId = computed(() => route.params.id as string)

interface FeedItem {
  id: string
  name: string
  created: string
  ready: string | null
  source: { url: string | null; status: string | null } | null
  document: { content: unknown } | null
  attributes: { summary?: string } | null
}
interface SourceDetail {
  id: string
  name: string
  description: string
  enabled: boolean
  url: string
  ownerProfileId: string | null
  configuration: Record<string, unknown> | null
  created: string
  modified: string
}
interface DetailData { feeds: { source: SourceDetail | null; items: FeedItem[] } }

const detailGql = gql`
  query GetFeedSource($id: UUID!) {
    feeds {
      source(id: $id) {
        id name description enabled url ownerProfileId configuration created modified
      }
      items(sourceId: $id, offset: 0, limit: 25) {
        id name created ready
        source { url status }
        document { content }
        attributes
      }
    }
  }
`
const { data, status, error, refresh } = useAsyncQuery<DetailData>('feed-source', detailGql, { id: sourceId })
const source = computed(() => data.value?.feeds?.source ?? null)
const items = computed(() => data.value?.feeds?.items ?? [])

// Edit form
const { toForm, toInput, validate } = useFeedSourceForm()
const form = reactive(emptyFeedSourceForm())
watch(source, (s) => { Object.assign(form, toForm(s)) }, { immediate: true })

const saving = ref(false)
const actionError = ref('')
const savedAt = ref('')

const editGql = gql`
  mutation EditFeedSource($id: UUID!, $input: FeedSourceInput!) {
    feeds { sources { source(id: $id) { edit(input: $input) { id } } } }
  }
`
async function handleSave() {
  const err = validate(form)
  if (err) { actionError.value = err; return }
  saving.value = true
  actionError.value = ''
  try {
    await mutation(editGql, { id: sourceId.value, input: toInput(form) })
    savedAt.value = new Date().toLocaleTimeString()
    await refresh()
  } catch (e: unknown) {
    actionError.value = e instanceof Error ? e.message : 'Failed to save the source'
  } finally {
    saving.value = false
  }
}

// Enable / disable
const toggling = ref(false)
const enableGql = gql`mutation EnableFeedSource($id: UUID!) { feeds { sources { source(id: $id) { enable { id enabled } } } } }`
const disableGql = gql`mutation DisableFeedSource($id: UUID!) { feeds { sources { source(id: $id) { disable { id enabled } } } } }`
async function toggleEnabled() {
  if (!source.value) return
  toggling.value = true
  try {
    await mutation(source.value.enabled ? disableGql : enableGql, { id: sourceId.value })
    await refresh()
    toast.success(source.value?.enabled ? 'Fetching disabled' : 'Fetching enabled')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to change fetching state')
  } finally {
    toggling.value = false
  }
}

// Fetch now — enqueues an immediate fetch (runs on the feeds runner, like a scheduled fetch).
const fetching = ref(false)
const fetchGql = gql`mutation FetchFeedSourceNow($id: UUID!) { feeds { sources { source(id: $id) { fetch } } } }`
async function handleFetch() {
  fetching.value = true
  try {
    await mutation(fetchGql, { id: sourceId.value })
    toast.success('Fetch queued — new items will appear shortly')
    // Best-effort refresh once the runner has had a moment to ingest.
    setTimeout(() => { refresh() }, 3000)
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to queue the fetch')
  } finally {
    fetching.value = false
  }
}

// Delete
const showDelete = ref(false)
const deleting = ref(false)
const deleteGql = gql`mutation DeleteFeedSource($id: UUID!) { feeds { sources { source(id: $id) { delete } } } }`
async function handleDelete() {
  deleting.value = true
  try {
    await mutation(deleteGql, { id: sourceId.value })
    router.push('/feeds/sources')
  } catch (e: unknown) {
    actionError.value = e instanceof Error ? e.message : 'Failed to delete the source'
    deleting.value = false
    showDelete.value = false
  }
}

// Item preview
const previewItem = ref<FeedItem | null>(null)
function openPreview(item: FeedItem) { previewItem.value = item }

function fmtDate(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Feeds', { label: 'Sources', to: '/feeds/sources' }, source?.name ?? 'Source')"
        :title="source?.name ?? 'Source'"
        subtitle="Edit configuration, control fetching, and preview ingested items">
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            primary
            :accent="accent"
            :disabled="fetching || !source"
            @click="handleFetch">{{ fetching ? 'Fetching…' : 'Fetch now' }}</Button>
          <Button
            :icon="source?.enabled ? 'pause' : 'play'"
            size="sm"
            :accent="accent"
            :disabled="toggling || !source"
            @click="toggleEnabled">
            {{ source?.enabled ? 'Disable' : 'Enable' }}
          </Button>
          <Button
            icon="trash"
            size="sm"
            :disabled="!source"
            @click="showDelete = true">Delete</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="error" class="query-error">Couldn't load source — {{ error.message }}</div>
    <div v-else-if="status === 'pending' && !source" class="state">Loading…</div>
    <div v-else-if="!source" class="state">Feed source not found.</div>

    <template v-else>
      <SectionCard title="Configuration" padded>
        <template #right>
          <Badge :color="source.enabled ? '#10b981' : '#64748b'">{{ source.enabled ? 'Fetching' : 'Disabled' }}</Badge>
        </template>
        <FeedsSourceFormFields v-model:form="form" :accent="accent" edit />
        <p v-if="actionError" class="form-error">{{ actionError }}</p>
        <div class="save-row">
          <span v-if="savedAt" class="saved-note">Saved at {{ savedAt }}</span>
          <Button
            primary
            :accent="accent"
            :disabled="saving"
            @click="handleSave">{{ saving ? 'Saving…' : 'Save changes' }}</Button>
        </div>
      </SectionCard>

      <SectionCard title="Details" padded>
        <dl class="meta">
          <div><dt>Canonical URL</dt><dd>{{ source.url }}</dd></div>
          <div><dt>Owner</dt><dd>{{ source.ownerProfileId ? 'User-owned' : 'Managed (global)' }}</dd></div>
          <div><dt>Created</dt><dd>{{ fmtDate(source.created) }}</dd></div>
          <div><dt>Modified</dt><dd>{{ fmtDate(source.modified) }}</dd></div>
        </dl>
      </SectionCard>

      <SectionCard :title="`Ingested items (${items.length})`" subtitle="Newest first — click an item to preview its content" padded>
        <div v-if="!items.length" class="state-sm">No items ingested yet.</div>
        <ul v-else class="item-list">
          <li
            v-for="item in items"
            :key="item.id"
            class="item-row"
            @click="openPreview(item)">
            <div class="item-main">
              <span class="item-name">{{ item.name }}</span>
              <span class="item-sub">{{ fmtDate(item.created) }}</span>
            </div>
            <Badge :color="item.ready ? '#10b981' : '#f59e0b'">{{ item.ready ? 'Published' : 'Pending' }}</Badge>
            <Icon name="chevron-right" :size="16" class="item-arrow" />
          </li>
        </ul>
      </SectionCard>
    </template>

    <ConfirmModal
      v-if="showDelete"
      title="Delete feed source"
      subtitle="Soft-deletes the source, stops its scheduled fetching, and removes its stored auth secret."
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete">
      <p>Delete <strong>{{ source?.name }}</strong>?</p>
    </ConfirmModal>

    <Modal
      v-if="previewItem"
      :title="previewItem.name"
      icon="book-open"
      :accent="accent"
      width="760px"
      @close="previewItem = null">
      <div class="preview-meta">
        <Badge :color="previewItem.ready ? '#10b981' : '#f59e0b'">{{ previewItem.ready ? 'Published' : 'Pending' }}</Badge>
        <span class="preview-date">{{ fmtDate(previewItem.created) }}</span>
        <a
          v-if="previewItem.source?.url"
          class="preview-link"
          :href="previewItem.source.url"
          target="_blank"
          rel="noopener noreferrer">View original ↗</a>
      </div>
      <FeedsDocumentView :content="previewItem.document?.content" :fallback="previewItem.attributes?.summary ?? ''" />
    </Modal>
  </PageShell>
</template>

<style scoped>
.query-error {
  background: var(--bg-3);
  border-left: 3px solid var(--err, #ff5c5c);
  padding: 8px 10px;
  font-size: 12.5px;
  color: var(--fg-2);
  border-radius: 4px;
  margin-bottom: 12px;
}
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.state-sm { padding: 16px 0; color: var(--fg-3); font-size: 13px; }
.form-error { color: var(--err, #ff5c5c); font-size: 12px; margin: 10px 0 0; }
.save-row { display: flex; align-items: center; justify-content: flex-end; gap: 12px; margin-top: 14px; }
.saved-note { font-size: 12px; color: var(--fg-3); }
.meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; margin: 0; }
.meta dt { font-size: 11.5px; color: var(--fg-3); margin-bottom: 2px; }
.meta dd { font-size: 13px; color: var(--fg-1); margin: 0; word-break: break-all; }
.item-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.item-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 6px;
  border-bottom: 1px solid var(--line-2);
  cursor: pointer;
}
.item-row:hover { background: var(--bg-2); }
.item-row:last-child { border-bottom: none; }
.item-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.item-name { font-size: 14px; color: var(--fg-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item-sub { font-size: 11.5px; color: var(--fg-3); }
.item-arrow { color: var(--fg-3); }
.preview-meta { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; padding-bottom: 14px; border-bottom: 1px solid var(--line-2); }
.preview-date { font-size: 12px; color: var(--fg-3); }
.preview-link { font-size: 12px; color: #f97316; text-decoration: none; margin-left: auto; }
.preview-link:hover { text-decoration: underline; }
</style>
