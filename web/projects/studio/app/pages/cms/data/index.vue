<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption , OverflowMenuItem  } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const router = useRouter()
const toast = useToast()
const showAddData = ref(false)
useCreateFromQuery(() => { showAddData.value = true })

const TYPE_META: Record<string, { icon: string; color: string }> = {
  document: { icon: 'file', color: '#5ec5ff' },
  video: { icon: 'video', color: '#ff7ac6' },
  image: { icon: 'image', color: '#a78bff' },
  audio: { icon: 'audio', color: '#ffb547' },
  data: { icon: 'database', color: '#5ec5ff' },
}

const STATUS: Record<string, [string, string]> = {
  draft: ['Draft', '#6c7388'],
  review: ['Review', '#5ec5ff'],
  translate: ['Translation', '#ffb547'],
  scheduled: ['Scheduled', '#a78bff'],
  published: ['Published', '#34d99a'],
  archived: ['Archived', '#3a4256'],
}

// ── Language ──────────────────────────────────────────────────────────────────
const language = useLanguage()
const selectedLanguage = computed({
  get: () => language.current.value.tag === 'en' && !language.languages.find(l => l.tag === 'en') ? '' : language.current.value.tag,
  set: (tag: string) => { if (tag) language.setLanguageTag(tag) },
})
const languageOptions = computed<SelectOption[]>(() =>
  language.languages
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name))
    .map(l => ({ value: l.tag, label: `${l.name} (${l.tag})` })),
)

// ── Facets ───────────────────────────────────────────────────────────────────
const typeFacets = ref<Array<{ value: string; count: number }>>([])

const facetsGql = gql`
  query GetDataFacets($filter: String!) {
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

const facetBaseFilter = computed(() => {
  let f = 'hasData = true'
  if (selectedLanguage.value) f += ` AND languageTag = "${selectedLanguage.value}"`
  return f
})

async function loadFacets() {
  try {
    const result = await gqlQuery<{
      search: { search: { facets: Array<{ field: string; value: string; count: number }> } }
    }>(facetsGql, { filter: facetBaseFilter.value })
    const facets = result.search.search.facets
    typeFacets.value = facets
      .filter(f => f.field === 'type')
      .sort((a, b) => b.count - a.count)
  } catch (e) {
    console.error('Failed to load facets', e)
  }
}

onMounted(() => loadFacets())

// ── Tabs from type facets ────────────────────────────────────────────────────
const activeTab = ref('All')

function formatTypeName(raw: string): string {
  const last = raw.split('/').pop() ?? raw
  return last.replace(/^v-/, '').replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase())
}

const tabs = computed(() => {
  const facetTabs = typeFacets.value.map(f => formatTypeName(f.value))
  return ['All', ...facetTabs]
})

function facetValueForTab(tab: string): string | undefined {
  return typeFacets.value.find(f => formatTypeName(f.value) === tab)?.value
}

// ── Filters ──────────────────────────────────────────────────────────────────
const searchQuery = ref('')
const offset = ref(0)
const limit = ref(10)

watch([searchQuery, activeTab, selectedLanguage], () => {
  offset.value = 0
})

watch(selectedLanguage, () => loadFacets())

// ── Search query ─────────────────────────────────────────────────────────────
const filter = computed(() => {
  const parts = [`hasData = true`]
  const typeValue = facetValueForTab(activeTab.value)
  if (typeValue) parts.push(`type = "${typeValue}"`)
  if (selectedLanguage.value) parts.push(`languageTag = "${selectedLanguage.value}"`)
  return parts.join(' AND ')
})

const sort = computed(() => searchQuery.value ? [] : ['published:desc'])

const dataGql = gql`
  query GetAllData($query: String!, $filter: String!, $limit: Int!, $offset: Int!, $sort: [String!]) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        offset: $offset
        storageSystemName: "Admin Search Index"
        limit: $limit
        sort: $sort
      }) {
        documents {
          metadata {
            id
            name
            attributes
            modified
            content {
              type
            }
            categories {
              id
              name
            }
            ready
            public
            publicContent
            publicSupplementary
            locked
            searchable
            recommendable
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

interface SearchMetadata {
  id: string
  name: string
  attributes: Record<string, unknown> | null
  modified: string
  content: { type: string } | null
  categories: Array<{ id: string; name: string }> | null
  ready: boolean
  public: boolean
  publicContent: boolean
  publicSupplementary: boolean
  locked: boolean
  searchable: boolean
  recommendable: boolean
  workflow: { state: string; pending: string | null } | null
}

const { data, status, refresh } = useAsyncQuery<{
  search: {
    search: {
      documents: Array<{ metadata: SearchMetadata | null }>
      estimatedHits: number
    }
  }
}>('all-data', dataGql, {
  query: searchQuery,
  filter,
  limit,
  offset,
  sort,
})

const items = computed<SearchMetadata[]>(() =>
  data.value?.search?.search?.documents
    ?.map(d => d.metadata)
    ?.filter((m): m is SearchMetadata => m != null) ?? [],
)

const totalHits = computed(() => data.value?.search?.search?.estimatedHits ?? 0)
const totalPages = computed(() => Math.ceil(totalHits.value / limit.value))
const currentPage = computed(() => Math.floor(offset.value / limit.value) + 1)
const isLoading = computed(() => status.value === 'pending')

function goToPage(page: number) {
  const clamped = Math.max(1, Math.min(page, totalPages.value))
  offset.value = (clamped - 1) * limit.value
}

// ── Subscription ─────────────────────────────────────────────────────────────
const { useSubscription } = useGraphQL()

const subscriptionGql = gql`
  subscription DataChanges {
    metadata {
      type
      id
    }
  }
`

useSubscription<{ metadata: { type: string; id: string } }>(
  subscriptionGql,
  undefined,
  () => {
    refresh()
    loadFacets()
  },
)

// ── Helpers ──────────────────────────────────────────────────────────────────
function getStatus(m: SearchMetadata): [string, string] {
  const state = m.workflow?.pending ?? m.workflow?.state ?? 'draft'
  return STATUS[state] ?? STATUS.draft!
}

function getTypeKey(m: SearchMetadata): string {
  const ct = m.content?.type ?? ''
  if (ct.startsWith('video/')) return 'video'
  if (ct.startsWith('image/')) return 'image'
  if (ct.startsWith('audio/')) return 'audio'
  return 'data'
}

function getContentLabel(m: SearchMetadata): string {
  const ct = m.content?.type
  if (!ct) return '--'
  const parts = ct.split('/')
  const last = parts[parts.length - 1] ?? ''
  return last.replace(/^v-/, '').replace(/-/g, ' ')
}

function formatPublishDate(m: SearchMetadata): string {
  const published = m.attributes?.published
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

// ── Bulk actions ────────────────────────────────────────────────────────────
const bulk = useMetadataBulkActions({
  refreshAll: async () => { await refresh(); await loadFacets() },
  itemLabel: 'data item(s)',
  data: items,
})

const pageIds = computed(() => items.value.map(m => m.id))
const allOnPageSelected = computed(() => bulk.allOnPageSelected(pageIds.value))
const someOnPageSelected = computed(() => bulk.someOnPageSelected(pageIds.value))

// ── Row actions ──────────────────────────────────────────────────────────────
const deleteTarget = ref<SearchMetadata | null>(null)
const deleteLoading = ref(false)

function getRowActions(): OverflowMenuItem[] {
  return [
    { id: 'open', label: 'Edit data', icon: 'list' },
    { id: 'copy', label: 'Copy metadata ID', icon: 'copy' },
    { id: 'sep', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onRowAction(id: string, row: SearchMetadata) {
  if (id === 'open') {
    router.push(getEditorRoute(row.id, row.content?.type))
  } else if (id === 'copy') {
    navigator.clipboard.writeText(row.id)
    toast.success('Metadata ID copied to clipboard')
  } else if (id === 'delete') {
    deleteTarget.value = row
  }
}

const deleteGql = gql`
  mutation DeleteData($metadataId: UUID!) {
    content { metadata { delete(metadataId: $metadataId) } }
  }
`

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { metadataId: deleteTarget.value.id })
    deleteTarget.value = null
    await refresh()
    await loadFacets()
  } catch (e) {
    console.error('Failed to delete data', e)
  } finally {
    deleteLoading.value = false
  }
}

// ── Table columns ────────────────────────────────────────────────────────────
const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'published', label: 'Publish Date', width: '130px', muted: true },
  { key: 'contentType', label: 'Type', width: '140px', muted: true },
  { key: 'status', label: 'Status', width: '120px' },
  { key: 'visibility', label: 'Visibility', width: '120px' },
]

function hasActiveFilters(): boolean {
  return !!(searchQuery.value || activeTab.value !== 'All')
}

function resetFilters() {
  searchQuery.value = ''
  activeTab.value = 'All'
}

async function onDataCreated(id: string) {
  showAddData.value = false
  await refresh()
  await loadFacets()
  router.push(getEditorRoute(id, 'bosca/v-data'))
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        title="Data"
        :subtitle="`${totalHits.toLocaleString()} items`"
        :breadcrumb="buildBreadcrumb('CMS', 'Data')"
        :accent="accent"
        :tabs="tabs"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #actions>
          <Select
            v-model="selectedLanguage"
            :options="languageOptions"
            placeholder="All Languages"
            searchable
            size="sm"
            icon="globe"
          />
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showAddData = true">Add Data</Button>
        </template>
      </PageHeader>
    </template>

    <div class="page-content">
      <!-- Filter bar -->
      <div class="filter-bar">
        <SearchInput v-model="searchQuery" placeholder="Search data…" max-width="300px" />
        <span class="filter-spacer" />
        <Button
          v-if="hasActiveFilters()"
          size="sm"
          icon="x"
          @click="resetFilters"
        >
          Clear
        </Button>
        <span class="result-count">
          {{ totalHits.toLocaleString() }} result{{ totalHits !== 1 ? 's' : '' }}
        </span>
      </div>

      <!-- Table -->
      <SectionCard title="Data" glass>
        <template #right>
          <span v-if="totalPages > 0" class="mono page-info">
            page {{ currentPage }} of {{ totalPages.toLocaleString() }}
          </span>
        </template>

        <GlassTable
          :columns="columns"
          :rows="items"
          :loading="isLoading && items.length === 0"
          row-key="id"
          :arrow="false"
          empty-text="No data found."
          @row-click="(row: any) => router.push(getEditorRoute(row.id, row.content?.type))"
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
              <span
                class="type-icon"
                :style="{
                  background: `color-mix(in oklch, ${TYPE_META[getTypeKey(row as SearchMetadata)]?.color ?? '#888'} 18%, var(--bg-2))`,
                  border: `1px solid color-mix(in oklch, ${TYPE_META[getTypeKey(row as SearchMetadata)]?.color ?? '#888'} 30%, transparent)`,
                }"
              >
                <Icon
                  :name="TYPE_META[getTypeKey(row as SearchMetadata)]?.icon ?? 'database'"
                  :size="14"
                  :color="TYPE_META[getTypeKey(row as SearchMetadata)]?.color ?? '#888'"
                />
              </span>
              <div class="name-text">
                <div class="item-name">{{ row.name }}</div>
                <div class="mono item-id">{{ row.id }}</div>
              </div>
            </div>
          </template>
          <template #col-published="{ row }">
            {{ formatPublishDate(row as SearchMetadata) }}
          </template>
          <template #col-contentType="{ row }">
            {{ getContentLabel(row as SearchMetadata) }}
          </template>
          <template #col-status="{ row }">
            <Badge :color="getStatus(row as SearchMetadata)[1]">
              {{ getStatus(row as SearchMetadata)[0] }}
            </Badge>
          </template>
          <template #col-visibility="{ row }">
            <VisibilityCell
              :locked="(row as SearchMetadata).locked"
              :public="(row as SearchMetadata).public"
              :public-content="(row as SearchMetadata).publicContent"
              :public-supplementary="(row as SearchMetadata).publicSupplementary"
              :searchable="(row as SearchMetadata).searchable"
              :recommendable="(row as SearchMetadata).recommendable"
            />
          </template>
          <template #actions="{ row }">
            <OverflowMenu
              :items="getRowActions()"
              @select="(id: string) => onRowAction(id, row as SearchMetadata)"
            >
              <template #default="{ toggle }">
                <button class="action-btn" @click.stop="toggle">
                  <Icon name="more" :size="16" color="var(--fg-3)" />
                </button>
              </template>
            </OverflowMenu>
          </template>
        </GlassTable>

        <Pagination
          v-if="totalPages > 1"
          :page="currentPage"
          :total-pages="totalPages"
          @prev="goToPage(currentPage - 1)"
          @next="goToPage(currentPage + 1)"
        />
      </SectionCard>
    </div>

    <MetadataBulkActions :actions="bulk" :accent="accent" />
  </PageShell>

  <!-- Add data modal -->
  <AddDataModal
    v-if="showAddData"
    :accent="accent"
    :language-tag="selectedLanguage"
    @close="showAddData = false"
    @created="onDataCreated"
  />

  <!-- Delete confirmation -->
  <ConfirmModal
    v-if="deleteTarget"
    :title="`Delete '${deleteTarget.name}'?`"
    subtitle="This will soft-delete the data. It can be restored later."
    :loading="deleteLoading"
    @close="deleteTarget = null"
    @confirm="confirmDelete"
  />
</template>

<style scoped>
.page-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* ── Filter bar ───────────────────────────────────────────────────────────── */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.filter-spacer {
  flex: 1;
}

.result-count {
  font-size: 12px;
  color: var(--fg-4);
  white-space: nowrap;
}

.page-info {
  font-size: 11px;
  color: var(--fg-3);
}

/* ── Name cell ────────────────────────────────────────────────────────────── */
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

.type-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
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


/* ── Action button ────────────────────────────────────────────────────────── */
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

</style>
