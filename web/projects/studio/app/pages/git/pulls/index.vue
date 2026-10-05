<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query } = useGraphQL()
const router = useRouter()

const activeTab = ref('Open')

interface RepoRow {
  id: string; name: string; slug: string; ownerId: string
}

interface PullRequest {
  id: string; number: number; title: string; description: string | null
  status: string; sourceBranch: string; targetBranch: string
  authorId: string; mergeable: boolean; created: string; updated: string
  repositoryId: string
}

const { data: repoData } = useAsyncQuery<{
  git: { repositories: RepoRow[] }
}>('git-all-repos-for-prs', gql`
  query Repositories {
    git { repositories { id name slug ownerId } }
  }
`, {}, { server: false })

const repos = computed(() => repoData.value?.git?.repositories ?? [])
const repoMap = computed(() => new Map(repos.value.map(r => [r.id, r])))

const allPrs = ref<PullRequest[]>([])
const isLoading = ref(false)
const loadError = ref('')
const PR_BATCH_SIZE = 100

async function fetchPullRequests(status: string | null): Promise<PullRequest[]> {
  const pullRequests: PullRequest[] = []
  let offset = 0

  while (true) {
    const result = await query<{ git: { allPullRequests: PullRequest[] } }>(gql`
      query AllPullRequests($status: GitPullRequestStatus, $offset: Long, $limit: Int) {
        git { allPullRequests(status: $status, offset: $offset, limit: $limit) {
          id number title description status sourceBranch targetBranch
          authorId created updated repositoryId
        } }
      }
    `, { status, offset, limit: PR_BATCH_SIZE })
    const page = result.git?.allPullRequests ?? []
    pullRequests.push(...page)
    if (page.length < PR_BATCH_SIZE) break
    offset += page.length
  }

  return pullRequests
}

watch([repos, () => activeTab.value], async ([rs, tab]) => {
  if (!rs.length) {
    allPrs.value = []
    return
  }
  isLoading.value = true
  loadError.value = ''
  try {
    const status = tab === 'All' ? null : tab.toUpperCase()
    allPrs.value = await fetchPullRequests(status)
  } catch {
    allPrs.value = []
    loadError.value = 'Could not load pull requests.'
  } finally {
    isLoading.value = false
  }
}, { immediate: true })

const PAGE_SIZE = 50
const visibleCount = ref(PAGE_SIZE)

watch(() => activeTab.value, () => { visibleCount.value = PAGE_SIZE })

const allFilteredPrs = computed(() => allPrs.value)

const filteredPrs = computed(() => allFilteredPrs.value.slice(0, visibleCount.value))
const hasMore = computed(() => visibleCount.value < allFilteredPrs.value.length)

function loadMore() {
  visibleCount.value += PAGE_SIZE
}

function relativeTime(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(ms / 60_000)
  if (mins < 1) return 'now'
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.floor(hrs / 24)}d ago`
}

const PR_STATUS_COLORS: Record<string, string> = {
  OPEN: '#34d99a', DRAFT: '#6c7388', MERGED: '#a78bff', CLOSED: '#ff5d6c',
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', 'Pull Requests')"
        title="Pull Requests"
        :subtitle="`${allPrs.length} ${activeTab === 'All' ? 'total' : activeTab.toLowerCase()} across ${repos.length} repositories`"
        :tabs="['Open', 'Draft', 'Merged', 'Closed', 'All']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      />
    </template>

    <div v-if="loadError" class="error-state">{{ loadError }}</div>
    <div v-if="isLoading" class="loading-state">Loading pull requests…</div>

    <div v-else class="pr-list">
      <div
        v-for="pr in filteredPrs"
        :key="pr.id"
        class="pr-row"
        @click="router.push({ path: `/git/pulls/${pr.id}`, query: { repo: pr.repositoryId, number: String(pr.number) } })"
      >
        <div class="pr-icon" :style="{ background: `color-mix(in oklch, ${PR_STATUS_COLORS[pr.status] || '#6c7388'} 14%, transparent)` }">
          <Icon name="git-pull-request" :size="14" :color="PR_STATUS_COLORS[pr.status] || '#6c7388'" />
        </div>
        <div class="pr-body">
          <div class="pr-title">
            <span class="mono pr-number">#{{ pr.number }}</span>
            {{ pr.title }}
          </div>
          <div class="pr-meta">
            <Badge :color="PR_STATUS_COLORS[pr.status] || '#6c7388'">{{ pr.status.toLowerCase() }}</Badge>
            <span v-if="repoMap.get(pr.repositoryId)" class="pr-repo-name">{{ repoMap.get(pr.repositoryId)!.name }}</span>
            <span class="mono pr-branches">{{ pr.sourceBranch }} → {{ pr.targetBranch }}</span>
          </div>
        </div>
        <span class="mono pr-time">{{ relativeTime(pr.updated) }}</span>
      </div>
      <div v-if="!filteredPrs.length" class="empty-msg">
        No {{ activeTab.toLowerCase() }} pull requests found.
      </div>
    </div>
    <div v-if="hasMore" class="load-more">
      <Button size="sm" :accent="accent" @click="loadMore">
        Load more ({{ allFilteredPrs.length - visibleCount }} remaining)
      </Button>
    </div>
  </PageShell>
</template>

<style scoped>
.loading-state, .empty-msg, .error-state {
  color: var(--fg-3); text-align: center; padding: 48px 0; font-size: 13.5px;
}

.error-state { color: var(--danger); padding: 12px 0; }

.pr-list {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  overflow: hidden;
}

.pr-row {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 16px; border-bottom: 1px solid var(--line);
  cursor: pointer; transition: background 0.1s;
}
.pr-row:last-child { border-bottom: none; }
.pr-row:hover { background: var(--bg-2); }

.pr-icon {
  width: 32px; height: 32px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  flex: 0 0 32px;
}

.pr-body { flex: 1; min-width: 0; }

.pr-title {
  font-size: 13.5px; color: var(--fg-0); font-weight: 500;
  display: flex; align-items: center; gap: 6px;
}

.pr-number {
  font-size: 10px; padding: 1px 5px; background: var(--bg-3);
  border-radius: 3px; color: var(--fg-3); font-weight: 600;
}

.pr-meta { display: flex; align-items: center; gap: 8px; margin-top: 3px; }
.pr-repo-name { font-size: 12px; color: var(--fg-2); font-weight: 500; }
.pr-branches { font-size: 11px; color: var(--fg-3); }
.pr-time { font-size: 11px; color: var(--fg-3); flex: 0 0 auto; }
.load-more { display: flex; justify-content: center; padding: 16px 0; }
</style>
