<script setup lang="ts">
import gql from 'graphql-tag'
import jsonata from 'jsonata'

type DocType = 'metadata' | 'collection' | 'profile'

interface SearchHit {
  id: string
  name: string
  type: DocType
}

interface Permission {
  action: string
  group: { id: string; name: string }
}

interface SearchConfigValue {
  expressions?: {
    metadata?: string
    collection?: string
    profile?: string
  }
}

interface SearchConfig {
  id: string
  key: string
  description: string
  public: boolean
  value: SearchConfigValue | null
  permissions: Permission[]
}

// The default storage system used for the typeahead document search.
// Matches the legacy admin page (web/projects/administration) so the same
// index is targeted.
const STORAGE_SYSTEM_NAME = 'Admin Search Index'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation, query: gqlQuery } = useGraphQL()
const toast = useToast()

const getConfigGql = gql`
  query GetSearchIndexingConfig {
    configurations {
      configuration(key: "search") {
        id key description public value
        permissions { action group { id name } }
      }
    }
  }
`

const setConfigGql = gql`
  mutation SetSearchIndexingConfig($configuration: ConfigurationInput!) {
    configurations { setConfiguration(configuration: $configuration) { id value } }
  }
`

const searchDocsGql = gql`
  query SearchIndexingPreviewDocs($query: String!, $storage: String!, $limit: Int!) {
    search {
      search(query: { query: $query, storageSystemName: $storage, limit: $limit, offset: 0 }) {
        documents {
          metadata { id name }
          collection { id name }
          profile { id name }
        }
      }
    }
  }
`

const previewMetadataGql = gql`
  query SearchIndexingPreviewMetadata($id: UUID!) {
    search { configuration { previewMetadata(id: $id) } }
  }
`
const previewCollectionGql = gql`
  query SearchIndexingPreviewCollection($id: UUID!) {
    search { configuration { previewCollection(id: $id) } }
  }
`
const previewProfileGql = gql`
  query SearchIndexingPreviewProfile($id: UUID!) {
    search { configuration { previewProfile(id: $id) } }
  }
`

const { data: configData, status: configStatus, refresh } = useAsyncQuery<{
  configurations: { configuration: SearchConfig | null }
}>('cms-search-indexing-config', getConfigGql, undefined, { server: false })

const metadataExpression = ref('')
const collectionExpression = ref('')
const profileExpression = ref('')
const permissions = ref<Permission[]>([])

watch(configData, (val) => {
  const cfg = val?.configurations?.configuration
  if (!cfg) return
  permissions.value = cfg.permissions ?? []
  const expressions = cfg.value?.expressions
  metadataExpression.value = expressions?.metadata ?? ''
  collectionExpression.value = expressions?.collection ?? ''
  profileExpression.value = expressions?.profile ?? ''
}, { immediate: true })

const saving = ref(false)

async function onSave() {
  saving.value = true
  try {
    await gqlMutation(setConfigGql, {
      configuration: {
        key: 'search',
        description: 'Search Transformation Configuration',
        public: false,
        value: {
          expressions: {
            metadata: metadataExpression.value,
            collection: collectionExpression.value,
            profile: profileExpression.value,
          },
        },
        permissions: permissions.value.map(p => ({
          action: p.action,
          groupId: p.group.id,
          entityId: configData.value?.configurations?.configuration?.id,
        })),
      },
    })
    toast.success('Search indexing configuration saved')
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to save')
  } finally {
    saving.value = false
  }
}

// --- Typeahead document search ---
const docSearch = ref('')
const docResults = ref<SearchHit[]>([])
const isSearching = ref(false)
const dropdownOpen = ref(false)
const searchWrapRef = ref<HTMLElement | null>(null)
let searchTimer: ReturnType<typeof setTimeout> | null = null

watch(docSearch, (val) => {
  if (searchTimer) clearTimeout(searchTimer)
  if (!val || val.trim().length < 2) {
    docResults.value = []
    dropdownOpen.value = false
    return
  }
  dropdownOpen.value = true
  searchTimer = setTimeout(() => { void runDocSearch(val.trim()) }, 250)
})

function onSearchFocus() {
  if (docResults.value.length) dropdownOpen.value = true
}

function onDocumentClick(e: MouseEvent) {
  if (!dropdownOpen.value) return
  const wrap = searchWrapRef.value
  if (wrap && !wrap.contains(e.target as Node)) {
    dropdownOpen.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocumentClick, true))
onUnmounted(() => document.removeEventListener('click', onDocumentClick, true))

async function runDocSearch(q: string) {
  isSearching.value = true
  try {
    const data = await gqlQuery<{
      search: {
        search: {
          documents: Array<{
            metadata: { id: string; name: string } | null
            collection: { id: string; name: string } | null
            profile: { id: string; name: string } | null
          }>
        }
      }
    }>(searchDocsGql, { query: q, storage: STORAGE_SYSTEM_NAME, limit: 10 })

    const hits: SearchHit[] = []
    for (const d of data.search.search.documents ?? []) {
      if (d.metadata) hits.push({ id: d.metadata.id, name: d.metadata.name, type: 'metadata' })
      if (d.collection) hits.push({ id: d.collection.id, name: d.collection.name, type: 'collection' })
      if (d.profile) hits.push({ id: d.profile.id, name: d.profile.name, type: 'profile' })
    }
    docResults.value = hits
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Search failed')
    docResults.value = []
  } finally {
    isSearching.value = false
  }
}

// --- Document context + preview ---
const selectedDoc = ref<SearchHit | null>(null)
const docContext = ref<unknown>(null)
const docContextText = ref('')
const previewOutput = ref('')
const previewError = ref('')
const loadingContext = ref(false)

const TYPE_COLORS: Record<DocType, string> = {
  metadata: '#4a8cff',
  collection: '#34d99a',
  profile: '#ec4899',
}

async function selectDoc(hit: SearchHit) {
  selectedDoc.value = hit
  docResults.value = []
  dropdownOpen.value = false
  docSearch.value = hit.name
  await loadContext(hit)
}

async function loadContext(hit: SearchHit) {
  loadingContext.value = true
  docContext.value = null
  docContextText.value = ''
  previewOutput.value = ''
  previewError.value = ''
  try {
    let result: unknown
    if (hit.type === 'metadata') {
      const data = await gqlQuery<{ search: { configuration: { previewMetadata: unknown } } }>(previewMetadataGql, { id: hit.id })
      result = data.search.configuration.previewMetadata
    } else if (hit.type === 'collection') {
      const data = await gqlQuery<{ search: { configuration: { previewCollection: unknown } } }>(previewCollectionGql, { id: hit.id })
      result = data.search.configuration.previewCollection
    } else {
      const data = await gqlQuery<{ search: { configuration: { previewProfile: unknown } } }>(previewProfileGql, { id: hit.id })
      result = data.search.configuration.previewProfile
    }
    docContext.value = result
    docContextText.value = result == null ? '' : JSON.stringify(result, null, 2)
    if (result != null) await evaluatePreview()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to load document')
  } finally {
    loadingContext.value = false
  }
}

async function evaluatePreview() {
  previewOutput.value = ''
  previewError.value = ''
  const hit = selectedDoc.value
  if (!hit || docContext.value == null) return

  const expression = hit.type === 'metadata'
    ? metadataExpression.value
    : hit.type === 'collection'
      ? collectionExpression.value
      : profileExpression.value

  if (!expression.trim()) {
    previewOutput.value = JSON.stringify(docContext.value, null, 2)
    return
  }

  try {
    const expr = jsonata(expression)
    const result = await expr.evaluate(docContext.value)
    previewOutput.value = JSON.stringify(result, null, 2)
  } catch (e: unknown) {
    previewError.value = e instanceof Error ? e.message : 'Failed to evaluate expression'
  }
}

watch([metadataExpression, collectionExpression, profileExpression], () => {
  if (docContext.value != null) void evaluatePreview()
})

const isLoading = computed(() => configStatus.value === 'pending' && !configData.value)

const expanded = reactive<Record<DocType, boolean>>({
  metadata: false,
  collection: false,
  profile: false,
})

function toggleExpanded(t: DocType) {
  expanded[t] = !expanded[t]
}

const COMPACT_ROWS = 8
const EXPANDED_ROWS = 24
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Settings', 'Search Indexing')"
        title="Search Indexing"
        subtitle="JSONata expressions that transform content before it's indexed for search"
      >
        <template #actions>
          <Button
            size="sm"
            primary
            icon="save"
            :accent="accent"
            :disabled="saving || isLoading"
            @click="onSave">
            {{ saving ? 'Saving…' : 'Save' }}
          </Button>
        </template>
      </PageHeader>
    </template>

    <div class="page-stack">
      <SectionCard title="JSONata Expressions" padded>
        <p class="section-desc">
          Each expression transforms its document type into the JSON payload that's sent to the search index.
          Leave blank to index the raw document context unchanged.
        </p>

        <div class="expr-stack">
          <div class="expr-block">
            <div class="expr-header">
              <span class="expr-label">Metadata Expression</span>
              <span class="expr-lang">JSON</span>
              <button
                type="button"
                class="expr-toggle"
                :title="expanded.metadata ? 'Collapse' : 'Expand'"
                @click="toggleExpanded('metadata')">
                <Icon :name="expanded.metadata ? 'minimize' : 'maximize'" :size="14" />
                {{ expanded.metadata ? 'Collapse' : 'Expand' }}
              </button>
            </div>
            <CodeEditor
              v-model="metadataExpression"
              language="json"
              :rows="expanded.metadata ? EXPANDED_ROWS : COMPACT_ROWS"
              placeholder="$" />
          </div>

          <div class="expr-block">
            <div class="expr-header">
              <span class="expr-label">Collection Expression</span>
              <span class="expr-lang">JSON</span>
              <button
                type="button"
                class="expr-toggle"
                :title="expanded.collection ? 'Collapse' : 'Expand'"
                @click="toggleExpanded('collection')">
                <Icon :name="expanded.collection ? 'minimize' : 'maximize'" :size="14" />
                {{ expanded.collection ? 'Collapse' : 'Expand' }}
              </button>
            </div>
            <CodeEditor
              v-model="collectionExpression"
              language="json"
              :rows="expanded.collection ? EXPANDED_ROWS : COMPACT_ROWS"
              placeholder="$" />
          </div>

          <div class="expr-block">
            <div class="expr-header">
              <span class="expr-label">Profile Expression</span>
              <span class="expr-lang">JSON</span>
              <button
                type="button"
                class="expr-toggle"
                :title="expanded.profile ? 'Collapse' : 'Expand'"
                @click="toggleExpanded('profile')">
                <Icon :name="expanded.profile ? 'minimize' : 'maximize'" :size="14" />
                {{ expanded.profile ? 'Collapse' : 'Expand' }}
              </button>
            </div>
            <CodeEditor
              v-model="profileExpression"
              language="json"
              :rows="expanded.profile ? EXPANDED_ROWS : COMPACT_ROWS"
              placeholder="$" />
          </div>
        </div>
      </SectionCard>

      <SectionCard title="Preview" padded>
        <p class="section-desc">
          Pick a document to see its raw indexing context (left) and the output after applying the matching JSONata expression (right).
        </p>

        <div ref="searchWrapRef" class="search-block">
          <div class="search-row" @focusin="onSearchFocus">
            <SearchInput
              v-model="docSearch"
              placeholder="Search metadata, collections, or profiles…"
              max-width="100%" />
            <span v-if="isSearching" class="search-loading mono">searching…</span>
          </div>
          <Transition name="dropdown-fade">
            <div v-if="dropdownOpen && docResults.length" class="results">
              <button
                v-for="hit in docResults"
                :key="`${hit.type}:${hit.id}`"
                class="result-row"
                @click="selectDoc(hit)"
              >
                <span class="result-name">{{ hit.name }}</span>
                <Badge :color="TYPE_COLORS[hit.type]">{{ hit.type }}</Badge>
              </button>
            </div>
          </Transition>
        </div>

        <div v-if="selectedDoc" class="preview-block">
          <div class="preview-meta">
            <span class="preview-label">Selected:</span>
            <span class="preview-name">{{ selectedDoc.name }}</span>
            <Badge :color="TYPE_COLORS[selectedDoc.type]">{{ selectedDoc.type }}</Badge>
            <Button
              size="xs"
              icon="refresh"
              :disabled="loadingContext"
              @click="loadContext(selectedDoc)">
              {{ loadingContext ? 'Loading…' : 'Refresh' }}
            </Button>
          </div>

          <div class="preview-grid">
            <div class="preview-pane">
              <span class="preview-pane-label">Context (input)</span>
              <CodeEditor
                v-model="docContextText"
                language="json"
                :rows="14"
                readonly />
            </div>
            <div class="preview-pane">
              <span class="preview-pane-label">Output</span>
              <div v-if="previewError" class="preview-error mono">{{ previewError }}</div>
              <CodeEditor
                v-else
                v-model="previewOutput"
                language="json"
                :rows="14"
                readonly />
            </div>
          </div>
        </div>
      </SectionCard>
    </div>
  </PageShell>
</template>

<style scoped>
.page-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-desc {
  margin: 0 0 14px;
  font-size: 12.5px;
  color: var(--fg-3);
  line-height: 1.5;
}

.expr-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.expr-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.expr-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.expr-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.expr-lang {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--bg-3);
  color: var(--fg-3);
}

.expr-toggle {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  font-size: 11.5px;
  font-weight: 500;
  color: var(--fg-2);
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.12s, border-color 0.12s, color 0.12s;
}

.expr-toggle:hover {
  background: var(--bg-3);
  color: var(--fg-1);
  border-color: var(--line-2);
}

.search-block {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}

.search-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.search-loading {
  font-size: 11.5px;
  color: var(--fg-3);
}

.results {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  z-index: 20;
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  background: var(--bg-1);
  max-height: 320px;
  overflow-y: auto;
  box-shadow: 0 12px 32px -8px rgba(0, 0, 0, 0.45), 0 2px 6px rgba(0, 0, 0, 0.2);
}

.dropdown-fade-enter-active,
.dropdown-fade-leave-active {
  transition: opacity 0.12s ease, transform 0.12s ease;
}

.dropdown-fade-enter-from,
.dropdown-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.result-row {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border: none;
  background: none;
  color: var(--fg-1);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: background 0.12s;
}

.result-row + .result-row {
  border-top: 1px solid color-mix(in oklch, var(--line) 50%, transparent);
}

.result-row:hover {
  background: color-mix(in oklch, var(--fg-2) 5%, transparent);
}

.result-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.preview-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--fg-2);
}

.preview-label {
  color: var(--fg-3);
}

.preview-name {
  color: var(--fg-0);
  font-weight: 500;
}

.preview-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.preview-pane {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.preview-pane-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.preview-error {
  padding: 12px;
  font-size: 12px;
  color: var(--err);
  background: color-mix(in oklch, var(--err) 12%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--err) 35%, var(--line));
  border-radius: var(--r-sm);
  white-space: pre-wrap;
  line-height: 1.5;
}

@media (max-width: 900px) {
  .preview-grid { grid-template-columns: 1fr; }
}
</style>
