<script setup lang="ts">
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'

interface CommitStatus {
  id: string; context: string; state: string; description: string | null
  targetUrl: string | null; created: string; commitSha: string; repositoryId: string
}

interface CommitSummary { sha: string; message: string; authorName: string; authorDate: string }

const { accent } = useCurrentSubsystem()
const { query } = useGraphQL()

const selectedRepoId = ref('')
const commitSha = ref('')
const statuses = ref<CommitStatus[]>([])
const loadingStatuses = ref(false)

const reposGql = gql`
  query ReposForStatuses($ownerId: UUID!) {
    git { repositories(ownerId: $ownerId) { id name slug } }
  }
`

const repos = ref<Array<{ id: string; name: string; slug: string }>>([])

const { load: loadLastOwner } = useLastGitOwner()
const savedOwner = loadLastOwner()
const selectedOwner = ref(savedOwner?.id ?? '')
const { searchProfiles } = useProfileSearch()
const { profile } = import.meta.client ? useAuth() : { profile: ref(null) }

watch(() => profile.value?.id, (id) => {
  if (id && !selectedOwner.value) selectedOwner.value = id
}, { immediate: true })

const ownerOptions = computed(() => {
  const opts: { value: string; label: string }[] = []
  const p = profile.value
  if (p?.id) opts.push({ value: p.id, label: p.name || p.slug || p.id })
  if (savedOwner && savedOwner.id !== p?.id) opts.push({ value: savedOwner.id, label: savedOwner.label })
  return opts
})

watch(selectedOwner, async (id) => {
  if (!id) return
  try {
    const result = await query<{ git: { repositories: Array<{ id: string; name: string; slug: string }> } }>(reposGql, { ownerId: id })
    repos.value = result.git?.repositories ?? []
    if (repos.value[0] && !selectedRepoId.value) selectedRepoId.value = repos.value[0].id
  } catch { repos.value = [] }
}, { immediate: true })

const repoOptions = computed(() => repos.value.map(r => ({ value: r.id, label: r.name })))

async function loadStatuses() {
  if (!selectedRepoId.value || !commitSha.value.trim()) return
  loadingStatuses.value = true
  try {
    const result = await query<{ git: { commitStatuses: CommitStatus[] } }>(gql`
      query CommitStatuses($repositoryId: UUID!, $commitSha: String!) {
        git {
          commitStatuses(repositoryId: $repositoryId, commitSha: $commitSha) {
            id context state description targetUrl created commitSha repositoryId
          }
        }
      }
    `, { repositoryId: selectedRepoId.value, commitSha: commitSha.value.trim() })
    statuses.value = result.git?.commitStatuses ?? []
  } catch { statuses.value = [] }
  finally { loadingStatuses.value = false }
}

const recentCommitsGql = gql`
  query RecentCommits($repositoryId: UUID!, $limit: Int) {
    git {
      commits(repositoryId: $repositoryId, limit: $limit) {
        sha message authorName authorDate
      }
    }
  }
`

const recentCommits = ref<CommitSummary[]>([])
const loadingCommits = ref(false)

watch(selectedRepoId, async (id) => {
  if (!id) return
  loadingCommits.value = true
  try {
    const result = await query<{ git: { commits: CommitSummary[] } }>(recentCommitsGql, { repositoryId: id, limit: 20 })
    recentCommits.value = result.git?.commits ?? []
  } catch { recentCommits.value = [] }
  finally { loadingCommits.value = false }
}, { immediate: true })

function selectCommit(sha: string) {
  commitSha.value = sha
  loadStatuses()
}

function stateColor(state: string): string {
  if (state === 'SUCCESS') return 'var(--ok)'
  if (state === 'FAILURE') return 'var(--err)'
  if (state === 'ERROR') return 'var(--err)'
  if (state === 'PENDING') return 'var(--warn)'
  return 'var(--fg-3)'
}

function stateBadgeBg(state: string): string {
  return `color-mix(in oklch, ${stateColor(state)} 16%, transparent)`
}

function stateIcon(state: string): string {
  if (state === 'SUCCESS') return 'check'
  if (state === 'FAILURE' || state === 'ERROR') return 'x'
  if (state === 'PENDING') return 'dots'
  return 'dots'
}

function formatTime(d: string): string {
  return new Date(d).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', 'Commit Statuses')"
        title="Commit Statuses"
        subtitle="CI/CD status checks reported for commits"
      >
        <template #actions>
          <Select
            v-model="selectedOwner"
            :options="ownerOptions"
            :on-search="searchProfiles"
            searchable
            placeholder="Owner…"
            icon="user"
            size="sm"
            :accent="accent"
          />
          <Select
            v-model="selectedRepoId"
            :options="repoOptions"
            placeholder="Repository"
            :accent="accent"
            size="sm" />
        </template>
      </PageHeader>
    </template>

    <div class="status-layout">
      <!-- Commit selector -->
      <div class="commit-panel">
        <SectionCard title="Recent Commits" :subtitle="selectedRepoId ? `${recentCommits.length} commits` : 'Select a repository'" glass>
          <div v-if="loadingCommits" class="panel-loading">Loading commits…</div>
          <div v-else-if="recentCommits.length === 0" class="panel-empty">No commits found.</div>
          <div v-else class="commit-list">
            <div
              v-for="c in recentCommits"
              :key="c.sha"
              class="commit-item"
              :class="{ active: commitSha === c.sha }"
              @click="selectCommit(c.sha)"
            >
              <div class="commit-sha mono">{{ c.sha.slice(0, 8) }}</div>
              <div class="commit-msg">{{ c.message.split('\n')[0] }}</div>
              <div class="commit-meta">{{ c.authorName }} · {{ formatTime(c.authorDate) }}</div>
            </div>
          </div>
        </SectionCard>
      </div>

      <!-- Status results -->
      <div class="status-panel">
        <div class="sha-input-row">
          <TextInput v-model="commitSha" placeholder="Enter or select a commit SHA…" mono />
          <Button
            size="sm"
            :accent="accent"
            :disabled="!commitSha.trim() || !selectedRepoId"
            @click="loadStatuses">
            Load Statuses
          </Button>
        </div>

        <SectionCard
          v-if="statuses.length > 0 || loadingStatuses"
          title="Status Checks"
          :subtitle="`${statuses.length} checks`"
          glass>
          <div v-if="loadingStatuses" class="panel-loading">Loading statuses…</div>
          <div v-else class="status-list">
            <div v-for="s in statuses" :key="s.id" class="status-row">
              <div class="status-icon" :style="{ background: stateBadgeBg(s.state) }">
                <Icon :name="stateIcon(s.state)" :size="14" :color="stateColor(s.state)" />
              </div>
              <div class="status-info">
                <div class="status-context">{{ s.context }}</div>
                <div v-if="s.description" class="status-desc">{{ s.description }}</div>
              </div>
              <span class="status-time">{{ formatTime(s.created) }}</span>
              <span class="status-state-badge" :style="{ background: stateBadgeBg(s.state), color: stateColor(s.state) }">
                {{ s.state }}
              </span>
              <a
                v-if="s.targetUrl"
                :href="s.targetUrl"
                target="_blank"
                rel="noopener"
                class="status-link"
                @click.stop>
                <Icon name="globe" :size="12" color="var(--fg-3)" />
              </a>
            </div>
          </div>
        </SectionCard>

        <div v-else-if="commitSha && !loadingStatuses" class="panel-empty">
          <p>No status checks found for this commit.</p>
          <p class="hint">Status checks are reported by CI agents via the <code>recordCommitStatus</code> mutation.</p>
        </div>

        <div v-else class="panel-empty">
          Select a commit from the left panel or enter a SHA to view its status checks.
        </div>
      </div>
    </div>
  </PageShell>
</template>

<style scoped>
.status-layout {
  display: grid;
  grid-template-columns: 360px 1fr;
  gap: 18px;
  align-items: start;
}

.commit-panel {
  min-width: 0;
}

.status-panel {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.sha-input-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}

.sha-input-row > :first-child {
  flex: 1;
}

.panel-loading, .panel-empty {
  text-align: center;
  padding: 32px 16px;
  color: var(--fg-3);
  font-size: 13px;
}

.hint {
  font-size: 11.5px;
  color: var(--fg-4);
  margin-top: 4px;
}

.hint code {
  background: var(--bg-3);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 11px;
}

.commit-list {
  max-height: 600px;
  overflow-y: auto;
}

.commit-item {
  padding: 10px 14px;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  transition: background 0.12s;
}

.commit-item:last-child {
  border-bottom: none;
}

.commit-item:hover {
  background: var(--bg-2);
}

.commit-item.active {
  background: color-mix(in oklch, v-bind(accent) 10%, transparent);
  border-left: 2px solid v-bind(accent);
}

.commit-sha {
  font-size: 11.5px;
  color: v-bind(accent);
  font-weight: 600;
}

.commit-msg {
  font-size: 12.5px;
  color: var(--fg-0);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.commit-meta {
  font-size: 11px;
  color: var(--fg-3);
  margin-top: 2px;
}

.status-list {
  display: flex;
  flex-direction: column;
}

.status-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line);
}

.status-row:last-child {
  border-bottom: none;
}

.status-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.status-info {
  flex: 1;
  min-width: 0;
}

.status-context {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
}

.status-desc {
  font-size: 11.5px;
  color: var(--fg-2);
  margin-top: 2px;
}

.status-time {
  font-size: 11px;
  color: var(--fg-3);
  flex-shrink: 0;
}

.status-state-badge {
  font-size: 10.5px;
  padding: 3px 9px;
  border-radius: 8px;
  width: 80px;
  text-align: center;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  flex-shrink: 0;
}

.status-link {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  transition: background 0.12s;
  flex-shrink: 0;
}

.status-link:hover {
  background: var(--bg-3);
}
</style>
