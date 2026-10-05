<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption , OverflowMenuItem  } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, useSubscription, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const router = useRouter()
const toast = useToast()

const showNewDocModal = ref(false)
useCreateFromQuery(() => { showNewDocModal.value = true })

function onDocumentCreated(id: string) {
  showNewDocModal.value = false
  navigateTo(getEditorRoute(id, 'bosca/v-document'))
}

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

const commonFilterParts = [
  'hasDocument = true',
  'hasGuide != true',
  'contentType != "bosca/v-document-template"',
  'contentType != "bosca/v-guide-step"',
  'contentType != "bosca/v-guide"',
  'contentType != "bosca/v-guide-step-template"',
  'contentType != "bosca/v-guide-template"',
]

const facetsGql = gql`
  query GetDocumentFacets($filter: String!) {
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

const language = useLanguage()
const selectedLanguage = ref(language.current.value?.tag)
const languageOptions = computed<SelectOption[]>(() =>
  language.languages
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name))
    .map(l => ({ value: l.tag, label: `${l.name} (${l.tag})` })),
)

watch(selectedLanguage, (value) => {
  if (value) {
    language.setLanguageTag(value)
  }
})

const facetFilter = computed(() =>
  [...commonFilterParts, `languageTag = "${language.current.value?.tag}"`].join(' AND '),
)

async function loadFacets() {
  try {
    const facetsResult = await gqlQuery<{
      search: { search: { facets: Array<{ field: string; value: string; count: number }> } }
    }>(facetsGql, { filter: facetFilter.value })
    typeFacets.value = facetsResult.search.search.facets
      .filter(f => f.field === 'type')
      .sort((a, b) => b.count - a.count)
  } catch (e) {
    console.error('Failed to load facets', e)
  }
}

onMounted(() => loadFacets())
watch(facetFilter, () => loadFacets())

const searchQuery = ref('')
const offset = ref(0)
const limit = ref(15)

watch([searchQuery, activeTab, () => language.current.value?.tag], () => {
  offset.value = 0
})

const filter = computed(() => {
  const parts = [...commonFilterParts, `languageTag = "${language.current.value?.tag}"`]
  const typeValue = facetValueForTab(activeTab.value)
  if (typeValue) {
    parts.push(`type = "${typeValue}"`)
  }
  return parts.join(' AND ')
})

const sort = computed(() => searchQuery.value ? [] : ['published:desc'])

const documentsGql = gql`
  query GetAllDocuments($query: String!, $filter: String!, $limit: Int!, $offset: Int!, $sort: [String!]) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
        sort: $sort
      }) {
        documents {
          metadata {
            __typename
            id
            name
            slug
            type
            content {
              type
            }
            languageTag
            created
            modified
            attributes
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
  type: string
  content: { type: string } | null
  languageTag: string | null
  created: string
  modified: string
  attributes: Record<string, unknown> | null
  relationships: SearchMetadataRelationship[]
  ready: boolean
  public: boolean
  publicContent: boolean
  locked: boolean
  searchable: boolean
  recommendable: boolean
  workflow: { state: string; pending: string | null } | null
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

const { data, status, refresh } = useAsyncQuery<{
  search: {
    search: {
      documents: Array<{ metadata: SearchMetadata | null }>
      estimatedHits: number
    }
  }
}>('documents', documentsGql, {
  query: searchQuery,
  filter,
  limit,
  offset,
  sort,
})

const docs = computed<SearchMetadata[]>(() =>
  data.value?.search?.search?.documents
    ?.map(d => d.metadata)
    ?.filter((m): m is SearchMetadata => m != null) ?? [],
)

const documentsChangesGql = gql`
  subscription DocumentsChangesSub {
    metadata { id type }
  }
`

useSubscription<{ metadata?: { id?: string } }>(documentsChangesGql, {}, (event) => {
  const id = event?.metadata?.id
  if (!id) return
  if (docs.value.some(d => d.id === id)) {
    refresh()
    loadFacets()
  }
})

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

function getStatus(m: SearchMetadata): [string, string] {
  const state = m.workflow?.pending ?? m.workflow?.state ?? 'draft'
  return STATUS[state] ?? STATUS.draft!
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
  itemLabel: 'document(s)',
  data: docs,
})

const pageIds = computed(() => docs.value.map(d => d.id))
const allOnPageSelected = computed(() => bulk.allOnPageSelected(pageIds.value))
const someOnPageSelected = computed(() => bulk.someOnPageSelected(pageIds.value))

const deleteGql = gql`
  mutation DeleteDocument($metadataId: UUID!) {
    content { metadata { delete(metadataId: $metadataId) } }
  }
`

// ── Row actions ──────────────────────────────────────────────────────────────
const deleteTarget = ref<SearchMetadata | null>(null)
const deleteLoading = ref(false)

function getRowActions(): OverflowMenuItem[] {
  return [
    { id: 'open', label: 'Edit document', icon: 'eye' },
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
    toast.success('Document ID copied')
  } else if (id === 'delete') {
    deleteTarget.value = row
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { metadataId: deleteTarget.value.id })
    deleteTarget.value = null
    await refresh()
    await loadFacets()
  } catch (e) {
    console.error('Failed to delete document', e)
  } finally {
    deleteLoading.value = false
  }
}

const docColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'published', label: 'Publish Date', width: '130px', muted: true },
  { key: 'contentType', label: 'Type', width: '140px', muted: true },
  { key: 'status', label: 'Status', width: '120px' },
  { key: 'visibility', label: 'Visibility', width: '120px' },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Documents')"
        title="Documents"
        :subtitle="`${totalHits.toLocaleString()} total`"
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
            @click="showNewDocModal = true">New Document</Button>
        </template>
      </PageHeader>
    </template>

    <div class="search-bar">
      <SearchInput v-model="searchQuery" placeholder="Search documents…" />
    </div>

    <SectionCard title="Documents" glass>
      <template #right>
        <span v-if="totalPages > 0" class="mono page-info">
          page {{ currentPage }} of {{ totalPages.toLocaleString() }}
        </span>
      </template>

      <GlassTable
        :columns="docColumns"
        :rows="docs"
        :loading="isLoading && docs.length === 0"
        row-key="id"
        :arrow="false"
        empty-text="No documents found."
        @row-click="(row: any) => navigateTo(getEditorRoute(row.id, row.content?.type))"
      >
        <template #header-name>
          <div class="header-name-cell">
            <Checkbox
              :model-value="allOnPageSelected"
              :indeterminate="someOnPageSelected"
              @click.stop
              @update:model-value="bulk.toggleSelectAll(pageIds)"
            />
            <span>Title</span>
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
            <div v-else class="doc-thumb-placeholder">
              <Icon name="file" :size="14" color="var(--fg-3)" />
            </div>
            <div class="name-text">
              <div class="doc-title">{{ row.name }}</div>
              <div class="mono doc-id">{{ row.content?.type }}</div>
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

      <Pagination
        v-if="totalPages > 1"
        :page="currentPage"
        :total-pages="totalPages"
        @prev="goToPage(currentPage - 1)"
        @next="goToPage(currentPage + 1)"
      />
    </SectionCard>

    <MetadataBulkActions :actions="bulk" :accent="accent" />
  </PageShell>

  <NewDocumentModal
    v-if="showNewDocModal"
    :accent="accent"
    @close="showNewDocModal = false"
    @created="onDocumentCreated"
  />

  <!-- Delete confirmation -->
  <ConfirmModal
    v-if="deleteTarget"
    :title="`Delete '${deleteTarget.name}'?`"
    subtitle="This will soft-delete the document. It can be restored later."
    :loading="deleteLoading"
    @close="deleteTarget = null"
    @confirm="confirmDelete"
  />
</template>

<style scoped>

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

.name-cell :deep(.name-thumb),
.name-cell :deep(.name-thumb .s-picture-img),
.name-cell :deep(.name-thumb.s-picture-placeholder) {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: var(--r-sm);
  object-fit: cover;
}

.doc-thumb-placeholder {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: var(--r-sm);
  background: var(--bg-2);
  border: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: center;
}

.name-text {
  min-width: 0;
}

.doc-title {
  white-space: normal;
  font-weight: 500;
  color: var(--fg-0);
}

.doc-id {
  font-size: 11px;
  color: var(--fg-3);
  margin-top: 2px;
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
