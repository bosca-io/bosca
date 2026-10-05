<script setup lang="ts">
import gql from 'graphql-tag'
import { useDropZone } from '@vueuse/core'
import type { GlassTableColumn, SelectOption , OverflowMenuItem  } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const router = useRouter()

const TYPE_META: Record<string, { icon: string; color: string }> = {
  document: { icon: 'file', color: '#5ec5ff' },
  video: { icon: 'video', color: '#ff7ac6' },
  image: { icon: 'image', color: '#a78bff' },
  audio: { icon: 'audio', color: '#ffb547' },
  guide: { icon: 'route', color: '#34d99a' },
  data: { icon: 'database', color: '#5ec5ff' },
  bible: { icon: 'book', color: '#7c5cff' },
}

const STATUS: Record<string, [string, string]> = {
  draft: ['Draft', '#6c7388'],
  review: ['Review', '#5ec5ff'],
  translate: ['Translation', '#ffb547'],
  scheduled: ['Scheduled', '#a78bff'],
  published: ['Published', '#34d99a'],
  archived: ['Archived', '#3a4256'],
}

// ── View mode ────────────────────────────────────────────────────────────────
const viewMode = ref<'grid' | 'table'>('table')

watch(viewMode, (mode) => {
  limit.value = mode === 'grid' ? 24 : 10
  offset.value = 0
})

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
const contentTypeFacets = ref<Array<{ value: string; count: number }>>([])

const facetsGql = gql`
  query GetMetadataAllFacets($filter: String!) {
    search {
      search(query: {
        query: ""
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        facets: ["type", "contentType"]
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
  let f = '_type = "metadata"'
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
    contentTypeFacets.value = facets
      .filter(f => f.field === 'contentType')
      .sort((a, b) => b.count - a.count)
  } catch (e) {
    console.error('Failed to load facets', e)
  }
}

onMounted(() => loadFacets())

// ── Tabs from type facets ────────────────────────────────────────────────────
function formatTypeName(raw: string): string {
  const last = raw.split('/').pop() ?? raw
  return last.replace(/^v-/, '').replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase())
}

const activeTab = ref('All')
const tabs = computed(() => {
  const facetTabs = typeFacets.value.map(f => formatTypeName(f.value))
  return ['All', ...facetTabs]
})

function facetValueForTab(tab: string): string | undefined {
  return typeFacets.value.find(f => formatTypeName(f.value) === tab)?.value
}

// ── Filters ──────────────────────────────────────────────────────────────────
const searchQuery = ref('')
const contentTypeFilter = ref('')
const offset = ref(0)
const limit = ref(10)

const contentTypeOptions = computed<SelectOption[]>(() =>
  contentTypeFacets.value.map(f => ({ value: f.value, label: `${f.value} (${f.count})` })),
)

watch([searchQuery, activeTab, selectedLanguage, contentTypeFilter], () => {
  offset.value = 0
})

watch(selectedLanguage, () => loadFacets())

// ── Search query ─────────────────────────────────────────────────────────────
const filter = computed(() => {
  const parts = ['_type = "metadata"']
  const typeValue = facetValueForTab(activeTab.value)
  if (typeValue) parts.push(`type = "${typeValue}"`)
  if (contentTypeFilter.value) parts.push(`contentType = "${contentTypeFilter.value}"`)
  if (selectedLanguage.value) parts.push(`languageTag = "${selectedLanguage.value}"`)
  return parts.join(' AND ')
})

const sort = computed(() => searchQuery.value ? [] : ['published:desc'])

const metadataGql = gql`
  query GetAllMetadata($query: String!, $filter: String!, $limit: Int!, $offset: Int!, $sort: [String!]) {
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
            __typename
            id
            name
            slug
            attributes
            modified
            content {
              type
            }
            media {
              status
            }
            categories {
              id
              name
            }
            relationships {
              relationship
              attributes
              metadata {
                id
                slug
                attributes
              }
            }
            ready
            public
            publicContent
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

interface SearchMetadataRelationship {
  relationship: string
  attributes: Record<string, unknown> | null
  metadata: { id: string; slug: string | null; attributes: Record<string, unknown> | null } | null
}

interface SearchMetadata {
  __typename?: string
  id: string
  name: string
  slug: string | null
  attributes: Record<string, unknown> | null
  modified: string
  content: { type: string } | null
  media: { status: string } | null
  categories: Array<{ id: string; name: string }> | null
  relationships: SearchMetadataRelationship[]
  ready: boolean
  public: boolean
  publicContent: boolean
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
}>('all-metadata', metadataGql, {
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

// ── Helpers ──────────────────────────────────────────────────────────────────
function getStatus(m: SearchMetadata): [string, string] {
  const state = m.workflow?.pending ?? m.workflow?.state ?? 'draft'
  return STATUS[state] ?? STATUS.draft!
}

function hasThumbnail(m: SearchMetadata): boolean {
  if (m.content?.type?.startsWith('image/')) return true
  const rels = m.relationships ?? []
  return rels.some(r =>
    r.relationship === 'image.featured'
    || r.relationship === 'image.featured.square'
    || r.relationship === 'image.preview',
  )
}

function getTypeKey(m: SearchMetadata): string {
  const ct = m.content?.type ?? ''
  if (ct.startsWith('application/vnd.bosca.v-bible')) return 'bible'
  if (ct.startsWith('application/vnd.bosca.v-guide')) return 'guide'
  if (ct.startsWith('application/vnd.bosca')) return 'document'
  if (ct.startsWith('video/')) return 'video'
  if (ct.startsWith('image/')) return 'image'
  if (ct.startsWith('audio/')) return 'audio'
  return 'document'
}

function getContentLabel(m: SearchMetadata): string {
  const ct = m.content?.type
  if (!ct) return '--'
  const parts = ct.split('/')
  const last = parts[parts.length - 1] ?? ''
  return last.replace(/^v-/, '').replace(/-/g, ' ')
}

function formatTimeSince(dateStr: string): string {
  const ms = Date.now() - new Date(dateStr).getTime()
  const mins = Math.floor(ms / 60_000)
  if (mins < 1) return 'now'
  if (mins < 60) return `${mins}m`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h`
  const days = Math.floor(hrs / 24)
  return `${days}d`
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

// ── Card view helpers ────────────────────────────────────────────────────────
const COVER_HUES: number[] = [25, 250, 160, 200, 80, 220, 50, 310, 135, 185]

function getCover(index: number): [string, string] {
  const hue = COVER_HUES[index % COVER_HUES.length]!
  return [`oklch(0.68 0.16 ${hue})`, `oklch(0.42 0.18 ${hue + 20})`]
}

function getCardCover(m: SearchMetadata, index: number): { gradient: [string, string]; icon: string; color: string } {
  const typeKey = getTypeKey(m)
  const meta = TYPE_META[typeKey] ?? { icon: 'file', color: '#888' }
  return { gradient: getCover(index), icon: meta.icon, color: meta.color }
}

// ── Bulk actions ────────────────────────────────────────────────────────────
const bulk = useMetadataBulkActions({
  refreshAll: async () => { await refresh(); await loadFacets() },
  itemLabel: 'item(s)',
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
    { id: 'open', label: 'View details', icon: 'eye' },
    { id: 'copy', label: 'Copy ID', icon: 'copy' },
    { id: 'sep', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  ]
}

function onRowAction(id: string, row: SearchMetadata) {
  if (id === 'open') {
    router.push(getEditorRoute(row.id, row.content?.type))
  } else if (id === 'copy') {
    navigator.clipboard.writeText(row.id)
  } else if (id === 'delete') {
    deleteTarget.value = row
  }
}

const deleteGql = gql`
  mutation DeleteMetadata($metadataId: UUID!) {
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
    console.error('Failed to delete metadata', e)
  } finally {
    deleteLoading.value = false
  }
}

// ── File upload (drag-and-drop) ──────────────────────────────────────────────
const dropzoneRef = ref<HTMLElement>()
const uploader = useUploader()
const toast = useToast()

const { isOverDropZone } = useDropZone(dropzoneRef, {
  onDrop: async (files: File[] | null) => {
    if (!files || files.length === 0) return
    const totalFiles = files.length
    const progress = toast.showProgress(`Uploading 0 of ${totalFiles} file(s)…`)
    const ids: string[] = []
    try {
      for (let i = 0; i < files.length; i++) {
        const file = files[i]!
        const id = await uploader.upload(file, selectedLanguage.value || null, (pct, phase) => {
          const verb = phase === 'processing' ? 'Finalizing' : 'Uploading'
          progress.update(pct, `${verb} ${i + 1} of ${totalFiles}: ${file.name} (${pct}%)`)
        })
        ids.push(id)
      }
      progress.complete(totalFiles === 1 ? 'File uploaded' : `${totalFiles} files uploaded`)
      await refresh()
      await loadFacets()
      if (ids.length === 1) {
        await router.push(`/cms/metadata/${ids[0]}`)
      }
    } catch (err) {
      console.error('Upload failed', err)
      progress.dismiss()
      toast.error(err instanceof Error ? err.message : 'Upload failed')
    }
  },
})

// ── Table columns ────────────────────────────────────────────────────────────
const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'published', label: 'Publish Date', width: '130px', muted: true },
  { key: 'contentType', label: 'Type', width: '140px', muted: true },
  { key: 'status', label: 'Status', width: '120px' },
  { key: 'visibility', label: 'Visibility', width: '120px' },
]

function hasActiveFilters(): boolean {
  return !!(searchQuery.value || contentTypeFilter.value || activeTab.value !== 'All')
}

function resetFilters() {
  searchQuery.value = ''
  contentTypeFilter.value = ''
  activeTab.value = 'All'
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        title="Everything"
        :subtitle="`${totalHits.toLocaleString()} items`"
        :breadcrumb="buildBreadcrumb('CMS', 'Everything')"
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
          <ImportContentModal :accent="accent" :language-tag="selectedLanguage" />
          <AddDocumentMenu :accent="accent" />
        </template>
      </PageHeader>
    </template>

    <div
      ref="dropzoneRef"
      class="page-content"
    >
      <!-- Drop overlay -->
      <div v-if="isOverDropZone" class="drop-overlay">
        <div class="drop-prompt">
          <Icon name="inbox" :size="36" color="var(--brand-accent)" />
          <span class="drop-label">Drop files to upload</span>
        </div>
      </div>

      <!-- Filter bar -->
      <div class="filter-bar">
        <SearchInput v-model="searchQuery" placeholder="Search metadata…" max-width="300px" />
        <Select
          v-model="contentTypeFilter"
          :options="contentTypeOptions"
          placeholder="All Content Types"
          searchable
          size="sm"
          icon="filter"
        />
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

      <!-- Grid view -->
      <template v-if="viewMode === 'grid'">
        <div v-if="isLoading && items.length === 0" class="loading-state">
          Loading…
        </div>

        <div v-else-if="items.length === 0" class="empty-state">
          No metadata found.
        </div>

        <div v-else class="card-grid">
          <div
            v-for="(m, i) in items"
            :key="m.id"
            class="card"
            @click="router.push(getEditorRoute(m.id, m.content?.type))"
          >
            <div
              class="card-cover"
              :style="hasThumbnail(m) ? undefined : {
                background: `linear-gradient(135deg, ${getCardCover(m, i).gradient[0]} 0%, ${getCardCover(m, i).gradient[1]} 100%)`,
              }"
            >
              <SPicture
                v-if="hasThumbnail(m)"
                :item="(m as any)"
                picture-class="card-cover-img"
              />
              <div v-else class="card-cover-shine" />
              <div class="status-badge">
                <span :style="{ width: '6px', height: '6px', borderRadius: '3px', background: getStatus(m)[1] }" />
                {{ getStatus(m)[0] }}
              </div>
              <div class="card-type-icon">
                <Icon
                  :name="getCardCover(m, i).icon"
                  :size="14"
                  color="rgba(255,255,255,.9)"
                />
              </div>
            </div>
            <div class="card-body">
              <div class="card-name">{{ m.name }}</div>
              <div class="card-meta">
                <span>{{ getContentLabel(m) }}</span>
                <span class="card-meta-dot" />
                <span>{{ formatTimeSince(m.modified) }}</span>
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- Table view -->
      <template v-else>
        <SectionCard title="Metadata" glass>
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
            empty-text="No metadata found."
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
                <SPicture
                  v-if="hasThumbnail(row as SearchMetadata)"
                  :item="(row as any)"
                  picture-class="name-thumb"
                />
                <div v-else class="type-icon">
                  <Icon
                    :name="TYPE_META[getTypeKey(row as SearchMetadata)]?.icon ?? 'file'"
                    :size="14"
                    color="var(--fg-3)"
                  />
                </div>
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
              {{ row.attributes?.type }}
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
        </SectionCard>
      </template>

      <Pagination
        v-if="totalPages > 1"
        :page="currentPage"
        :total-pages="totalPages"
        @prev="goToPage(currentPage - 1)"
        @next="goToPage(currentPage + 1)"
      />
    </div>

    <MetadataBulkActions :actions="bulk" :accent="accent" />
  </PageShell>

  <!-- Delete confirmation -->
  <ConfirmModal
    v-if="deleteTarget"
    :title="`Delete '${deleteTarget.name}'?`"
    subtitle="This will soft-delete the metadata. It can be restored later."
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
  position: relative;
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

/* ── Drop overlay ─────────────────────────────────────────────────────────── */
.drop-overlay {
  position: absolute;
  inset: 0;
  z-index: 50;
  background: color-mix(in oklch, var(--brand-accent) 8%, rgba(0, 0, 0, 0.4));
  border-radius: var(--r-lg);
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.drop-prompt {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 32px 48px;
  background: var(--bg-1);
  border: 2px dashed var(--brand-accent);
  border-radius: var(--r-lg);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.35);
}

.drop-label {
  font-size: 16px;
  font-weight: 600;
  color: var(--fg-1);
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

/* ── Loading / empty states ──────────────────────────────────────────────── */
.loading-state,
.empty-state {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
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

.card-type-icon {
  position: absolute;
  bottom: 8px;
  left: 10px;
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0,0,0,.3);
  border-radius: 6px;
  backdrop-filter: blur(4px);
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
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--fg-3);
}

.card-meta-dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--fg-4);
}

/* ── Table view cells ────────────────────────────────────────────────────── */
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
  background: var(--bg-2);
  border: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
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
