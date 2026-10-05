<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem, SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const router = useRouter()
const toast = useToast()

const searchQuery = ref('')
const offset = ref(0)
const limit = ref(24)
const viewMode = ref<'grid' | 'table'>('table')

watch(viewMode, (mode) => {
  limit.value = mode === 'grid' ? 24 : 10
  offset.value = 0
})

const activeTab = ref('All')
const typeFacets = ref<Array<{ value: string; count: number }>>([])

const tabs = computed(() => {
  const facetTabs = typeFacets.value.map(f => formatTypeName(f.value))
  return ['All', ...facetTabs]
})

function formatTypeName(raw: string): string {
  const last = raw.split('/').pop() ?? raw
  return last
    .replace(/^v-/, '')
    .replace(/-/g, ' ')
    .replace(/\b\w/g, c => c.toUpperCase())
}

function facetValueForTab(tab: string): string | undefined {
  return typeFacets.value.find(f => formatTypeName(f.value) === tab)?.value
}

const baseFilter = '_type = "collection"'

const facetsGql = gql`
  query GetCollectionFacets($filter: String!) {
    search {
      search(query: {
        query: ""
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        facets: ["type"]
      }) {
        facets {
          field
          value
          count
        }
      }
    }
  }
`

async function loadFacets() {
  try {
    const facetsResult = await gqlQuery<{
      search: { search: { facets: Array<{ field: string; value: string; count: number }> } }
    }>(facetsGql, { filter: baseFilter })
    typeFacets.value = facetsResult.search.search.facets
      .filter(f => f.field === 'type')
      .sort((a, b) => b.count - a.count)
  } catch (e) {
    console.error('Failed to load facets', e)
  }
}

onMounted(() => loadFacets())

watch([searchQuery, activeTab], () => {
  offset.value = 0
})

const filter = computed(() => {
  const parts = [baseFilter]
  const typeValue = facetValueForTab(activeTab.value)
  if (typeValue) {
    parts.push(`type = "${typeValue}"`)
  }
  return parts.join(' AND ')
})

const collectionsGql = gql`
  query GetAllCollections($query: String!, $filter: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          collection {
            __typename
            id
            name
            slug
            modified
            attributes
            type
            public
            locked
            searchable
            recommendable
            itemsCount
            metadataRelationships {
              relationship
              attributes
              metadata {
                id
                slug
                attributes
              }
            }
            workflow {
              state
              pending
            }
          }
        }
        estimatedHits
      }
    }
  }
`

interface SearchCollectionRelationship {
  relationship: string
  attributes: Record<string, unknown> | null
  metadata: { id: string; slug: string | null; attributes: Record<string, unknown> | null } | null
}

interface SearchCollection {
  __typename?: string
  id: string
  name: string
  slug: string | null
  modified: string | null
  attributes: Record<string, unknown> | null
  type: string
  public: boolean
  locked: boolean
  searchable: boolean
  recommendable: boolean
  itemsCount: number
  metadataRelationships: SearchCollectionRelationship[]
  workflow: { state: string; pending: string | null } | null
}

function hasThumbnail(c: SearchCollection): boolean {
  const rels = c.metadataRelationships ?? []
  return rels.some(r =>
    r.relationship === 'image.featured'
    || r.relationship === 'image.featured.square'
    || r.relationship === 'image.preview',
  )
}

const { data, status, refresh } = useAsyncQuery<{
  search: {
    search: {
      documents: Array<{ collection: SearchCollection | null }>
      estimatedHits: number
    }
  }
}>('collections', collectionsGql, {
  query: searchQuery,
  filter,
  limit,
  offset,
})

const collections = computed<SearchCollection[]>(() =>
  data.value?.search?.search?.documents
    ?.map(d => d.collection)
    ?.filter((c): c is SearchCollection => c != null) ?? [],
)

const totalHits = computed(() => data.value?.search?.search?.estimatedHits ?? 0)
const totalPages = computed(() => Math.ceil(totalHits.value / limit.value))
const currentPage = computed(() => Math.floor(offset.value / limit.value) + 1)
const isLoading = computed(() => status.value === 'pending')

function goToPage(page: number) {
  const clamped = Math.max(1, Math.min(page, totalPages.value))
  offset.value = (clamped - 1) * limit.value
}

const STATUS: Record<string, [string, string]> = {
  draft:     ['Draft',       '#6c7388'],
  review:    ['Review',      '#5ec5ff'],
  translate: ['Translation', '#ffb547'],
  scheduled: ['Scheduled',   '#a78bff'],
  published: ['Published',   '#34d99a'],
  archived:  ['Archived',    '#3a4256'],
}

function getStatus(c: SearchCollection): [string, string] {
  const state = c.workflow?.pending ?? c.workflow?.state ?? 'draft'
  return STATUS[state] ?? STATUS.draft!
}

function formatPublishDate(c: SearchCollection): string {
  const published = c.attributes?.published
  if (!published) return '--'
  let dt: Date
  if (typeof published === 'string') {
    const n = parseInt(published)
    dt = isNaN(n) ? new Date(Date.parse(published)) : new Date(n)
  } else {
    dt = new Date(published as string | number)
  }
  try {
    return dt.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
  } catch {
    return '--'
  }
}

const COVER_HUES: number[] = [25, 250, 160, 200, 80, 220, 50, 310, 135, 185]

function getCover(index: number): [string, string] {
  const hue = COVER_HUES[index % COVER_HUES.length]!
  return [`oklch(0.68 0.16 ${hue})`, `oklch(0.42 0.18 ${hue + 20})`]
}

function getTypeLabel(c: SearchCollection): string {
  if (c.type === 'folder') return 'Folder'
  if (c.type === 'queue') return 'Queue'
  return 'Collection'
}

// ── Bulk actions ────────────────────────────────────────────────────────────
const bulk = useCollectionBulkActions({
  refreshAll: async () => { await refresh(); await loadFacets() },
  data: collections,
})

const pageIds = computed(() => collections.value.map(c => c.id))
const allOnPageSelected = computed(() => bulk.allOnPageSelected(pageIds.value))
const someOnPageSelected = computed(() => bulk.someOnPageSelected(pageIds.value))

const deleteGql = gql`
  mutation DeleteCollection($id: UUID!) {
    content { collection { delete(id: $id) } }
  }
`

// ── Row actions ──────────────────────────────────────────────────────────────
const deleteTarget = ref<SearchCollection | null>(null)
const deleteLoading = ref(false)

function getRowActions(): OverflowMenuItem[] {
  return [
    { id: 'open', label: 'View details', icon: 'eye' },
    { id: 'copy', label: 'Copy ID', icon: 'copy' },
    { id: 'sep', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onRowAction(id: string, row: SearchCollection) {
  if (id === 'open') {
    router.push(`/cms/collections/${row.id}`)
  } else if (id === 'copy') {
    navigator.clipboard.writeText(row.id)
    toast.success('Collection ID copied')
  } else if (id === 'delete') {
    deleteTarget.value = row
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    deleteTarget.value = null
    await refresh()
    await loadFacets()
  } catch (e) {
    console.error('Failed to delete collection', e)
  } finally {
    deleteLoading.value = false
  }
}

// ── Create collection ────────────────────────────────────────────────────────
const createGql = gql`
  mutation CreateCollection($collection: CollectionInput!) {
    content { collection { add(collection: $collection) { id } } }
  }
`

const COLLECTION_TYPE_OPTIONS: SelectOption[] = [
  { value: 'STANDARD', label: 'Standard' },
  { value: 'FOLDER', label: 'Folder' },
  { value: 'QUEUE', label: 'Queue' },
]

const showCreate = ref(false)
const createName = ref('')
const createType = ref('STANDARD')
const createSaving = ref(false)
const createError = ref('')

function openCreate() {
  createName.value = ''
  createType.value = 'STANDARD'
  createError.value = ''
  createSaving.value = false
  showCreate.value = true
}
useCreateFromQuery(openCreate)

async function onCreateCollection() {
  const name = createName.value.trim()
  if (!name || createSaving.value) return
  createSaving.value = true
  createError.value = ''
  try {
    const result = await gqlMutation<{ content: { collection: { add: { id: string } } } }>(
      createGql,
      { collection: { name, collectionType: createType.value } },
    )
    const newId = result.content.collection.add.id
    showCreate.value = false
    toast.success('Collection created')
    router.push(`/cms/collections/${newId}`)
  } catch (e) {
    /* eslint-disable @typescript-eslint/no-explicit-any */
    createError.value = (e as unknown as any)?.message || 'Failed to create collection'
  } finally {
    createSaving.value = false
  }
}

// ── Table columns ────────────────────────────────────────────────────────────
const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'published', label: 'Publish Date', width: '130px', muted: true },
  { key: 'type', label: 'Type', width: '120px', muted: true },
  { key: 'status', label: 'Status', width: '120px' },
  { key: 'visibility', label: 'Visibility', width: '120px' },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Collections')"
        title="Collections"
        :subtitle="`${totalHits.toLocaleString()} total`"
        :tabs="tabs"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Collection</Button>
        </template>
      </PageHeader>
    </template>

    <div class="filter-bar">
      <SearchInput v-model="searchQuery" placeholder="Search collections…" />
      <span class="filter-spacer" />
      <div class="view-toggle">
        <button
          class="view-toggle-btn"
          :class="{ active: viewMode === 'grid' }"
          @click="viewMode = 'grid'"
        >
          <Icon name="grid9" :size="16" :color="viewMode === 'grid' ? 'var(--fg-0)' : 'var(--fg-3)'" />
        </button>
        <button
          class="view-toggle-btn"
          :class="{ active: viewMode === 'table' }"
          @click="viewMode = 'table'"
        >
          <Icon name="list" :size="16" :color="viewMode === 'table' ? 'var(--fg-0)' : 'var(--fg-3)'" />
        </button>
      </div>
    </div>

    <div v-if="isLoading && collections.length === 0" class="loading-state">
      Loading…
    </div>

    <div v-else-if="collections.length === 0" class="empty-state">
      No collections found.
    </div>

    <!-- Grid view -->
    <template v-else-if="viewMode === 'grid'">
      <div class="card-grid">
        <div
          v-for="(c, i) in collections"
          :key="c.id"
          class="card"
          @click="navigateTo(`/cms/collections/${c.id}`)"
        >
          <div
            class="card-cover"
            :style="hasThumbnail(c) ? undefined : {
              background: `linear-gradient(135deg, ${getCover(i)[0]} 0%, ${getCover(i)[1]} 100%)`,
            }"
          >
            <SPicture
              v-if="hasThumbnail(c)"
              :item="(c as any)"
              picture-class="card-cover-img"
            />
            <div v-else class="card-cover-shine" />
            <div class="status-badge">
              <span :style="{ width: '6px', height: '6px', borderRadius: '3px', background: getStatus(c)[1] }" />
              {{ getStatus(c)[0] }}
            </div>
            <div class="card-type-label">{{ getTypeLabel(c) }}</div>
          </div>
          <div class="card-body">
            <div class="card-name">{{ c.name }}</div>
            <div class="card-meta">
              <span><span class="mono tabular card-meta-value">{{ c.itemsCount.toLocaleString() }}</span> items</span>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- Table view -->
    <template v-else>
      <SectionCard title="Collections" glass>
        <template #right>
          <span v-if="totalPages > 0" class="mono page-info">
            page {{ currentPage }} of {{ totalPages.toLocaleString() }}
          </span>
        </template>

        <GlassTable
          :columns="columns"
          :rows="collections"
          :loading="isLoading && collections.length === 0"
          row-key="id"
          :arrow="false"
          empty-text="No collections found."
          @row-click="(row: any) => router.push(`/cms/collections/${row.id}`)"
        >
          <template #header-name>
            <div class="header-name-cell">
              <Checkbox
                :model-value="allOnPageSelected"
                :indeterminate="someOnPageSelected"
                @click.stop
                @update:model-value="bulk.toggleSelectAll(pageIds)"
              />
              <span>Name</span>
            </div>
          </template>
          <template #col-name="{ row }">
            <div class="name-cell">
              <Checkbox
                :model-value="bulk.isSelected(row.id)"
                @click.stop
                @update:model-value="bulk.toggleSelect(row.id)"
              />
              <SPicture
                v-if="hasThumbnail(row as SearchCollection)"
                :item="(row as any)"
                picture-class="name-thumb"
              />
              <div v-else class="type-icon">
                <Icon name="boxes" :size="14" color="var(--fg-3)" />
              </div>
              <div class="name-text">
                <div class="item-name">{{ row.name }}</div>
                <div class="mono item-id">{{ row.id }}</div>
              </div>
            </div>
          </template>
          <template #col-published="{ row }">
            {{ formatPublishDate(row as SearchCollection) }}
          </template>
          <template #col-type="{ row }">
            {{ getTypeLabel(row as SearchCollection) }}
          </template>
          <template #col-status="{ row }">
            <Badge :color="getStatus(row as SearchCollection)[1]">
              {{ getStatus(row as SearchCollection)[0] }}
            </Badge>
          </template>
          <template #col-visibility="{ row }">
            <VisibilityCell
              :locked="(row as SearchCollection).locked"
              :public="(row as SearchCollection).public"
              :searchable="(row as SearchCollection).searchable"
              :recommendable="(row as SearchCollection).recommendable"
            />
          </template>
          <template #actions="{ row }">
            <OverflowMenu
              :items="getRowActions()"
              @select="(id: string) => onRowAction(id, row as SearchCollection)"
            >
              <template #default="{ toggle }">
                <button class="action-btn" @click.stop="toggle">
                  <Icon name="more" :size="16" color="var(--fg-3)" />
                </button>
              </template>
            </OverflowMenu>
          </template>
        </GlassTable>
      </SectionCard>
    </template>

    <Pagination
      v-if="totalPages > 1"
      :page="currentPage"
      :total-pages="totalPages"
      @prev="goToPage(currentPage - 1)"
      @next="goToPage(currentPage + 1)"
    />

    <CollectionBulkActions :actions="bulk" :accent="accent" />
  </PageShell>

  <!-- Delete confirmation -->
  <ConfirmModal
    v-if="deleteTarget"
    :title="`Delete '${deleteTarget.name}'?`"
    subtitle="This will delete the collection. This action cannot be undone."
    :loading="deleteLoading"
    @close="deleteTarget = null"
    @confirm="confirmDelete"
  />

  <!-- New collection modal -->
  <Modal
    v-if="showCreate"
    title="New Collection"
    icon="boxes"
    width="440px"
    :accent="accent"
    @close="showCreate = false">
    <div class="create-form">
      <TextInput
        v-model="createName"
        label="Name"
        placeholder="Collection name"
        autofocus
        @keydown.enter="onCreateCollection" />
      <Select
        v-model="createType"
        :options="COLLECTION_TYPE_OPTIONS"
        label="Type" />
    </div>
    <p v-if="createError" class="create-error">{{ createError }}</p>
    <template #footer>
      <span class="filter-spacer" />
      <Button size="sm" @click="showCreate = false">Cancel</Button>
      <Button
        size="sm"
        primary
        :accent="accent"
        :disabled="!createName.trim() || createSaving"
        @click="onCreateCollection">
        {{ createSaving ? 'Creating…' : 'Create' }}
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-spacer {
  flex: 1;
}

.loading-state,
.empty-state {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

/* ── View toggle ─────────────────────────────────────────────────────────── */
.view-toggle {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  background: var(--bg-2);
  border-radius: var(--r-sm);
  border: 1px solid var(--line);
}

.view-toggle-btn {
  width: 28px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  background: none;
  border: none;
  cursor: pointer;
  transition: background 0.15s ease;
}

.view-toggle-btn.active {
  background: var(--bg-0);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.15);
}

.view-toggle-btn:hover:not(.active) {
  background: var(--bg-3);
}

/* ── Card grid ───────────────────────────────────────────────────────────── */
.card-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}

.card {
  border-radius: var(--r-md);
  overflow: hidden;
  background: var(--bg-1);
  border: 1px solid var(--line);
  cursor: pointer;
  display: flex;
  flex-direction: column;
}

.card-cover {
  height: 100px;
  position: relative;
}

.card-cover-shine {
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse at 70% 30%, rgba(255,255,255,.25), transparent 55%);
}

.status-badge {
  position: absolute;
  top: 8px;
  right: 8px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  border-radius: 999px;
  font-size: 10.5px;
  font-weight: 600;
  background: rgba(0,0,0,.45);
  backdrop-filter: blur(8px);
  color: #fff;
}

.card-type-label {
  position: absolute;
  bottom: 8px;
  left: 10px;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: .06em;
  text-transform: uppercase;
  color: rgba(255,255,255,.92);
}

.card-body {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.card-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg-0);
}

.card-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11.5px;
  color: var(--fg-3);
}

.card-meta-value {
  color: var(--fg-1);
}

/* ── Table view ──────────────────────────────────────────────────────────── */
.page-info {
  font-size: 11px;
  color: var(--fg-3);
}

.header-name-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.name-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.name-cell :deep(.name-thumb),
.name-cell :deep(.name-thumb .s-picture-img),
.name-cell :deep(.name-thumb.s-picture-placeholder) {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: var(--r-sm);
  object-fit: cover;
}

.type-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  background: var(--bg-2);
  border: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.card-cover :deep(.card-cover-img),
.card-cover :deep(.card-cover-img .s-picture-img) {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 0;
}

.name-text {
  min-width: 0;
}

.item-name {
  font-weight: 500;
  color: var(--fg-0);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.item-id {
  font-size: 11px;
  color: var(--fg-4);
  margin-top: 1px;
}


.action-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-sm);
  background: none;
  border: none;
  cursor: pointer;
}

.action-btn:hover {
  background: var(--bg-3);
}

.create-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.create-error {
  font-size: 12px;
  color: var(--err);
  margin: 8px 0 0;
}

</style>
