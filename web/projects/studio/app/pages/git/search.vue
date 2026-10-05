<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { query: gqlQuery } = useGraphQL()

// ─── Repository picker ──────────────────────────────────────────────────────
interface RepoOption { id: string; name: string; slug: string; defaultBranch: string }
const repos = ref<RepoOption[]>([])
const selectedRepoId = ref('')

onMounted(async () => {
  try {
    const result = await gqlQuery<{ git: { repositories: RepoOption[] } }>(gql`
      query SearchRepos {
        git { repositories { id name slug defaultBranch } }
      }
    `)
    repos.value = result.git?.repositories ?? []
    if (route.query.repo) selectedRepoId.value = route.query.repo as string
    else if (repos.value.length) selectedRepoId.value = repos.value[0]!.id
  } catch { /* ignore */ }
})

const repoOptions = computed(() => repos.value.map(r => ({ value: r.id, label: r.name })))
const selectedRepo = computed(() => repos.value.find(r => r.id === selectedRepoId.value) ?? null)

// ─── Search state ───────────────────────────────────────────────────────────
type SearchMode = 'content' | 'paths'
const searchMode = ref<SearchMode>('content')
const searchQuery = ref((route.query.q as string) || '')
const searchRef = ref('')
const searching = ref(false)

watch(selectedRepo, (r) => {
  if (r && !searchRef.value) searchRef.value = r.defaultBranch
})

// ─── Content search results ─────────────────────────────────────────────────
interface SearchResult { filePath: string; lineNumber: number; snippet: string }
const contentResults = ref<SearchResult[]>([])

// ─── Path search results ────────────────────────────────────────────────────
interface TreeEntry { name: string; path: string; type: string; sha: string; size: number | null; mode: number }
const pathResults = ref<TreeEntry[]>([])

async function doSearch() {
  if (!selectedRepoId.value || !searchQuery.value.trim()) return
  searching.value = true
  contentResults.value = []
  pathResults.value = []
  try {
    if (searchMode.value === 'content') {
      const result = await gqlQuery<{ git: { searchContent: SearchResult[] } }>(gql`
        query SearchContent($repositoryId: UUID!, $query: String!, $ref: String, $limit: Int) {
          git { searchContent(repositoryId: $repositoryId, query: $query, ref: $ref, limit: $limit) {
            filePath lineNumber snippet
          } }
        }
      `, {
        repositoryId: selectedRepoId.value,
        query: searchQuery.value,
        ref: searchRef.value || null,
        limit: 100,
      })
      contentResults.value = result.git?.searchContent ?? []
    } else {
      const result = await gqlQuery<{ git: { searchPaths: TreeEntry[] } }>(gql`
        query SearchPaths($repositoryId: UUID!, $query: String!, $ref: String) {
          git { searchPaths(repositoryId: $repositoryId, query: $query, ref: $ref) {
            name path type sha size mode
          } }
        }
      `, {
        repositoryId: selectedRepoId.value,
        query: searchQuery.value,
        ref: searchRef.value || null,
      })
      pathResults.value = result.git?.searchPaths ?? []
    }
  } catch { /* ignore */ }
  finally { searching.value = false }
}

function handleSearchSubmit() {
  router.replace({ query: { ...route.query, q: searchQuery.value, repo: selectedRepoId.value } })
  doSearch()
}

let debounceTimer: ReturnType<typeof setTimeout> | null = null
watch(searchQuery, (val) => {
  if (debounceTimer) clearTimeout(debounceTimer)
  if (!val.trim()) return
  debounceTimer = setTimeout(() => {
    router.replace({ query: { ...route.query, q: val, repo: selectedRepoId.value } })
    doSearch()
  }, 300)
})

function navigateToFile(filePath: string, line?: number) {
  router.push({
    path: `/git/repositories/${selectedRepoId.value}`,
    query: { file: filePath, ref: searchRef.value, line: line ? String(line) : undefined },
  })
}

function fileIcon(type: string): string {
  if (type === 'TREE') return 'folder'
  if (type === 'SUBMODULE') return 'link'
  return 'file'
}

function formatSize(bytes: number | null): string {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

const hasResults = computed(() =>
  searchMode.value === 'content' ? contentResults.value.length > 0 : pathResults.value.length > 0,
)

const resultCount = computed(() =>
  searchMode.value === 'content' ? contentResults.value.length : pathResults.value.length,
)
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', 'Search')"
        title="Search"
        subtitle="Search file content and paths across repositories"
      />
    </template>

    <div class="search-layout">
      <!-- ── Search bar ──────────────────────────────────────────────── -->
      <form class="search-bar" @submit.prevent="handleSearchSubmit">
        <div class="search-input-row">
          <div class="search-icon">
            <Icon name="search" :size="16" :color="accent" />
          </div>
          <input
            v-model="searchQuery"
            class="search-input"
            :placeholder="searchMode === 'content' ? 'Search file content…' : 'Search file paths…'"
            autofocus
          >
        </div>

        <div class="search-options">
          <div class="mode-toggle">
            <button
              type="button"
              :class="['mode-btn', { active: searchMode === 'content' }]"
              @click="searchMode = 'content'"
            >
              <Icon name="code" :size="12" />
              Content
            </button>
            <button
              type="button"
              :class="['mode-btn', { active: searchMode === 'paths' }]"
              @click="searchMode = 'paths'"
            >
              <Icon name="file" :size="12" />
              Paths
            </button>
          </div>

          <Select
            v-model="selectedRepoId"
            :options="repoOptions"
            placeholder="Repository"
            :accent="accent"
            size="sm" />
          <TextInput v-model="searchRef" placeholder="Branch or ref" size="sm" />
        </div>
      </form>

      <!-- ── Results ─────────────────────────────────────────────────── -->
      <div v-if="searching" class="loading-state">Searching…</div>

      <template v-else-if="hasResults">
        <div class="results-summary mono">
          {{ resultCount }} {{ resultCount === 1 ? 'result' : 'results' }}
          in {{ selectedRepo?.name ?? 'repository' }}
        </div>

        <!-- Content search results -->
        <div v-if="searchMode === 'content'" class="results-list">
          <div
            v-for="(r, i) in contentResults"
            :key="i"
            class="result-row"
            @click="navigateToFile(r.filePath, r.lineNumber)"
          >
            <div class="result-location">
              <Icon name="file" :size="13" :color="accent" />
              <span class="mono result-path">{{ r.filePath }}</span>
              <span class="mono result-line">:{{ r.lineNumber }}</span>
            </div>
            <pre class="result-snippet mono">{{ r.snippet }}</pre>
          </div>
        </div>

        <!-- Path search results -->
        <div v-if="searchMode === 'paths'" class="results-list">
          <div
            v-for="entry in pathResults"
            :key="entry.sha"
            class="result-row path-row"
            @click="navigateToFile(entry.path)"
          >
            <Icon :name="fileIcon(entry.type)" :size="14" :color="entry.type === 'TREE' ? accent : 'var(--fg-3)'" />
            <span :class="['result-name', { 'is-dir': entry.type === 'TREE' }]">{{ entry.name }}</span>
            <span class="mono result-full-path">{{ entry.path }}</span>
            <span v-if="entry.size != null && entry.type !== 'TREE'" class="mono result-size">
              {{ formatSize(entry.size) }}
            </span>
          </div>
        </div>
      </template>

      <div v-else-if="searchQuery && !searching" class="empty-msg">
        No results found. Try a different query or repository.
      </div>

      <div v-else class="empty-msg">
        Enter a search query to find content or files across your repositories.
      </div>
    </div>
  </PageShell>
</template>

<style scoped>
.loading-state, .empty-msg {
  color: var(--fg-3); text-align: center; padding: 48px 0; font-size: 13.5px;
}
.search-layout { display: flex; flex-direction: column; gap: 16px; }

/* ─── Search bar ─────────────────────────────────────────────────────────── */
.search-bar {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  padding: 16px; display: flex; flex-direction: column; gap: 12px;
}
.search-input-row {
  display: flex; align-items: center; gap: 10px;
}
.search-icon { flex: 0 0 auto; display: flex; }
.search-input {
  flex: 1; background: var(--bg-0); border: 1px solid var(--line); border-radius: 6px;
  padding: 9px 12px; font-size: 14px; color: var(--fg-0); outline: none;
  font-family: inherit;
}
.search-input:focus { border-color: v-bind(accent); }
.search-input::placeholder { color: var(--fg-3); }

.search-options {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
}

.mode-toggle {
  display: flex; gap: 2px; background: var(--bg-0); border-radius: 6px; padding: 2px;
  border: 1px solid var(--line);
}
.mode-btn {
  display: flex; align-items: center; gap: 5px;
  background: none; border: none; padding: 5px 10px; cursor: pointer;
  font-size: 12px; color: var(--fg-2); border-radius: 4px; font-weight: 500;
}
.mode-btn:hover { background: var(--bg-2); }
.mode-btn.active { background: var(--bg-3); color: var(--fg-0); }

/* ─── Results ────────────────────────────────────────────────────────────── */
.results-summary { font-size: 12px; color: var(--fg-3); }

.results-list {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  overflow: hidden;
}

.result-row {
  padding: 12px 16px; border-bottom: 1px solid var(--line);
  cursor: pointer; transition: background 0.1s;
}
.result-row:last-child { border-bottom: none; }
.result-row:hover { background: var(--bg-2); }

.result-location {
  display: flex; align-items: center; gap: 8px; margin-bottom: 6px;
}
.result-path { font-size: 13px; color: v-bind(accent); font-weight: 500; }
.result-line { font-size: 11px; color: var(--fg-3); }

.result-snippet {
  margin: 0; padding: 8px 12px; font-size: 12px; line-height: 1.6;
  color: var(--fg-1); background: var(--bg-0); border-radius: 6px;
  overflow-x: auto; white-space: pre;
}

/* path results */
.path-row {
  display: flex; align-items: center; gap: 10px;
}
.result-name { font-size: 13.5px; color: var(--fg-0); }
.result-name.is-dir { font-weight: 500; }
.result-full-path { font-size: 11px; color: var(--fg-3); flex: 1; }
.result-size { font-size: 11px; color: var(--fg-3); }
</style>
