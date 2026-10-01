<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'
import { emptyFeedSourceForm, useFeedSourceForm } from '~/composables/useFeedSourceForm'

definePageMeta({ middleware: 'feeds-admin' })

const { accent } = useCurrentSubsystem()
const { mutation } = useGraphQL()
const router = useRouter()

interface SourceRow {
  id: string
  name: string
  enabled: boolean
  url: string
  ownerProfileId: string | null
  configuration: { type?: string } | null
  created: string
}

const listGql = gql`
  query FeedSources($offset: Int!, $limit: Int!) {
    feeds {
      sources(offset: $offset, limit: $limit) {
        id name enabled url ownerProfileId configuration created
      }
    }
  }
`
const { rows, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<SourceRow>(
  'feed-sources', listGql, {},
  d => (d as { feeds?: { sources?: SourceRow[] } })?.feeds?.sources,
)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Source', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Type', width: '120px' },
  { key: 'enabled', label: 'Status', width: '110px' },
  { key: 'owner', label: 'Owner', width: '110px', muted: true },
  { key: 'url', label: 'URL', width: '1fr', muted: true },
]

function typeLabel(type: string): string {
  return type.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase())
}

// Create
const { toInput, validate } = useFeedSourceForm()
const showCreate = ref(false)
const saving = ref(false)
const formError = ref('')
const form = reactive(emptyFeedSourceForm())

function openCreate() {
  Object.assign(form, emptyFeedSourceForm())
  formError.value = ''
  showCreate.value = true
}

const addGql = gql`
  mutation AddFeedSource($input: FeedSourceInput!) {
    feeds { sources { add(input: $input) { id } } }
  }
`
async function handleCreate() {
  const err = validate(form)
  if (err) { formError.value = err; return }
  saving.value = true
  formError.value = ''
  try {
    const result = await mutation<{ feeds: { sources: { add: { id: string } } } }>(
      addGql, { input: toInput(form) },
    )
    showCreate.value = false
    await refresh()
    const id = result?.feeds?.sources?.add?.id
    if (id) router.push(`/feeds/sources/${id}`)
  } catch (e: unknown) {
    formError.value = e instanceof Error ? e.message : 'Failed to create the feed source'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Feeds', 'Sources')"
        title="Sources"
        subtitle="Registered feeds Bosca fetches on a schedule and ingests as content">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New source</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load sources — {{ loadError.message }}</div>

    <GlassTable
      :columns="columns"
      :rows="rows"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No feed sources yet. Add one to start ingesting content."
      arrow
      @row-click="(row) => router.push(`/feeds/sources/${row.id}`)">
      <template #col-name="{ row }">{{ row.name }}</template>
      <template #col-type="{ row }"><Badge :color="accent">{{ typeLabel(row.configuration?.type ?? 'Unknown') }}</Badge></template>
      <template #col-enabled="{ row }">
        <Badge :color="row.enabled ? '#10b981' : '#64748b'">{{ row.enabled ? 'Fetching' : 'Disabled' }}</Badge>
      </template>
      <template #col-owner="{ row }">{{ row.ownerProfileId ? 'User' : 'Managed' }}</template>
      <template #col-url="{ row }">{{ row.url }}</template>
    </GlassTable>
    <ListPager
      v-model:offset="offset"
      :page-size="pageSize"
      :count="rows.length"
      :has-more="hasMore" />

    <Modal
      v-if="showCreate"
      title="New feed source"
      icon="rss"
      :accent="accent"
      width="600px"
      @close="showCreate = false">
      <FeedsSourceFormFields v-model:form="form" :accent="accent" />
      <p v-if="formError" class="form-error">{{ formError }}</p>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreate">{{ saving ? 'Creating…' : 'Create' }}</Button>
      </template>
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
.form-error { color: var(--err, #ff5c5c); font-size: 12px; margin: 10px 0 0; }
</style>
