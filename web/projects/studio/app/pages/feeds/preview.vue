<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

definePageMeta({ middleware: 'feeds-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query } = useGraphQL()

interface FeedItem {
  id: string
  name: string
  created: string
  ready: string | null
  source: { url: string | null; status: string | null } | null
  document: { content: unknown } | null
  attributes: { summary?: string } | null
}

// Sources to pick from.
const sourcesGql = gql`
  query FeedPreviewSources { feeds { sources(offset: 0, limit: 200) { id name } } }
`
const { data: sourcesData } = useAsyncQuery<{ feeds: { sources: { id: string; name: string }[] } }>(
  'feed-preview-sources', sourcesGql,
)
const sourceOptions = computed<SelectOption[]>(() =>
  (sourcesData.value?.feeds?.sources ?? []).map(s => ({ value: s.id, label: s.name })),
)

const selectedSourceId = ref('')
// Default to the first source once the list loads.
watch(sourceOptions, (opts) => {
  if (!selectedSourceId.value && opts.length) selectedSourceId.value = String(opts[0]?.value ?? '')
}, { immediate: true })

// Items for the selected source, with their document content for the reader.
const itemsGql = gql`
  query FeedPreviewItems($sourceId: UUID!) {
    feeds {
      items(sourceId: $sourceId, offset: 0, limit: 25) {
        id name created ready
        source { url status }
        document { content }
        attributes
      }
    }
  }
`
const items = ref<FeedItem[]>([])
const selectedItem = ref<FeedItem | null>(null)
const loading = ref(false)
const itemsError = ref('')

async function loadItems() {
  if (!selectedSourceId.value) { items.value = []; selectedItem.value = null; return }
  loading.value = true
  itemsError.value = ''
  try {
    const d = await query<{ feeds: { items: FeedItem[] } }>(itemsGql, { sourceId: selectedSourceId.value })
    items.value = d?.feeds?.items ?? []
    selectedItem.value = items.value[0] ?? null
  } catch (e: unknown) {
    itemsError.value = e instanceof Error ? e.message : 'Failed to load items'
    items.value = []
    selectedItem.value = null
  } finally {
    loading.value = false
  }
}
watch(selectedSourceId, loadItems)

function fmtDate(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Feeds', 'Preview')"
        title="Preview"
        subtitle="Read the content a feed source has ingested, exactly as it was normalized for serving" />
    </template>

    <div v-if="itemsError" class="query-error">{{ itemsError }}</div>

    <div class="reader">
      <aside class="reader-list">
        <Select
          v-model="selectedSourceId"
          label="Source"
          placeholder="Choose a source"
          :options="sourceOptions"
          :accent="accent" />

        <div v-if="loading" class="list-state">Loading…</div>
        <div v-else-if="!selectedSourceId" class="list-state">Pick a source to preview its feed.</div>
        <div v-else-if="!items.length" class="list-state">No items ingested for this source yet.</div>
        <ul v-else class="items">
          <li
            v-for="item in items"
            :key="item.id"
            class="item"
            :class="{ active: selectedItem?.id === item.id }"
            @click="selectedItem = item">
            <span class="item-name">{{ item.name }}</span>
            <span class="item-sub">{{ fmtDate(item.created) }}</span>
          </li>
        </ul>
      </aside>

      <section class="reader-pane">
        <template v-if="selectedItem">
          <div class="pane-meta">
            <Badge :color="selectedItem.ready ? '#10b981' : '#f59e0b'">{{ selectedItem.ready ? 'Published' : 'Pending' }}</Badge>
            <span class="pane-date">{{ fmtDate(selectedItem.created) }}</span>
            <a
              v-if="selectedItem.source?.url"
              class="pane-link"
              :href="selectedItem.source.url"
              target="_blank"
              rel="noopener noreferrer">View original ↗</a>
          </div>
          <h1 class="pane-title">{{ selectedItem.name }}</h1>
          <FeedsDocumentView :content="selectedItem.document?.content" :fallback="selectedItem.attributes?.summary ?? ''" />
        </template>
        <div v-else class="pane-empty">
          <Icon name="book-open" :size="28" />
          <p>Select an item to read it here.</p>
        </div>
      </section>
    </div>
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
.reader { display: grid; grid-template-columns: 320px 1fr; gap: 18px; align-items: start; }
@media (max-width: 860px) { .reader { grid-template-columns: 1fr; } }
.reader-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  position: sticky;
  top: 0;
}
.list-state { color: var(--fg-3); font-size: 13px; padding: 8px 2px; }
.items { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; max-height: calc(100vh - 220px); overflow-y: auto; }
.item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid transparent;
}
.item:hover { background: var(--bg-2); }
.item.active { background: var(--bg-2); border-color: var(--line-2); }
.item-name { font-size: 13.5px; color: var(--fg-1); line-height: 1.35; }
.item-sub { font-size: 11px; color: var(--fg-3); }
.reader-pane {
  background: var(--bg-2);
  border: 1px solid var(--border-1, rgba(255, 255, 255, 0.06));
  border-radius: 12px;
  padding: 28px 32px;
  min-height: 360px;
}
.pane-meta { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.pane-date { font-size: 12px; color: var(--fg-3); }
.pane-link { font-size: 12px; color: #f97316; text-decoration: none; margin-left: auto; }
.pane-link:hover { text-decoration: underline; }
.pane-title { font-size: 26px; line-height: 1.25; color: var(--fg-1); margin: 0 0 20px; font-weight: 650; }
.pane-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 320px;
  color: var(--fg-3);
}
.pane-empty p { margin: 0; font-size: 13px; }
</style>
