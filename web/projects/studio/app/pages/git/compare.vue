<script setup lang="ts">
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { query: gqlQuery } = useGraphQL()
const { profile } = import.meta.client ? useAuth() : { profile: ref(null) }

// ─── Repository picker ──────────────────────────────────────────────────────
interface RepoOption { id: string; name: string; slug: string; defaultBranch: string }
const repos = ref<RepoOption[]>([])
const selectedRepoId = ref('')

watch(() => profile.value?.id, async (ownerId) => {
  if (!ownerId) return
  try {
    const result = await gqlQuery<{ git: { repositories: RepoOption[] } }>(gql`
      query CompareRepos($ownerId: UUID!) {
        git { repositories(ownerId: $ownerId) { id name slug defaultBranch } }
      }
    `, { ownerId })
    repos.value = result.git?.repositories ?? []
    if (route.query.repo) selectedRepoId.value = route.query.repo as string
    else if (repos.value.length) selectedRepoId.value = repos.value[0]!.id
  } catch { /* ignore */ }
}, { immediate: true })

const repoOptions = computed(() => repos.value.map(r => ({ value: r.id, label: r.name })))

// ─── Branches ───────────────────────────────────────────────────────────────
interface BranchInfo { name: string; sha: string }
const branches = ref<BranchInfo[]>([])

watch(selectedRepoId, async (id) => {
  if (!id) return
  branches.value = []
  baseRef.value = ''
  headRef.value = ''
  comparison.value = null
  try {
    const result = await gqlQuery<{ git: { branches: BranchInfo[] } }>(gql`
      query CompareBranches($repositoryId: UUID!) {
        git { branches(repositoryId: $repositoryId) { name sha } }
      }
    `, { repositoryId: id })
    branches.value = result.git?.branches ?? []
    const repo = repos.value.find(r => r.id === id)
    if (repo) {
      baseRef.value = repo.defaultBranch
    }
    if (route.query.base) baseRef.value = route.query.base as string
    if (route.query.head) headRef.value = route.query.head as string
  } catch { /* ignore */ }
}, { immediate: true })

const branchOptions = computed(() => branches.value.map(b => ({ value: b.name, label: b.name })))

// ─── Compare state ──────────────────────────────────────────────────────────
const baseRef = ref((route.query.base as string) || '')
const headRef = ref((route.query.head as string) || '')
const comparing = ref(false)
const compareError = ref('')

interface DiffLine {
  content: string; type: string
  oldLineNumber: number | null; newLineNumber: number | null
}
interface DiffHunk {
  oldStart: number; oldCount: number; newStart: number; newCount: number
  lines: DiffLine[]
}
interface DiffFile {
  oldPath: string | null; newPath: string | null; changeType: string
  hunks: DiffHunk[]
}
interface CommitInfo {
  sha: string; message: string
  authorName: string; authorEmail: string; authorDate: string
  parentShas: string[]
}
interface ComparisonResult {
  baseRef: string; headRef: string
  filesChanged: number; insertions: number; deletions: number
  files: DiffFile[]; commits: CommitInfo[]
}

const comparison = ref<ComparisonResult | null>(null)

async function doCompare() {
  if (!selectedRepoId.value || !baseRef.value || !headRef.value) return
  comparing.value = true
  compareError.value = ''
  comparison.value = null
  try {
    const result = await gqlQuery<{ git: { compare: ComparisonResult } }>(gql`
      query Compare($repositoryId: UUID!, $baseRef: String!, $headRef: String!) {
        git { compare(repositoryId: $repositoryId, baseRef: $baseRef, headRef: $headRef) {
          baseRef headRef filesChanged insertions deletions
          commits {
            sha message authorName authorEmail authorDate parentShas
          }
          files {
            oldPath newPath changeType
            hunks { oldStart oldCount newStart newCount
              lines { content type oldLineNumber newLineNumber }
            }
          }
        } }
      }
    `, { repositoryId: selectedRepoId.value, baseRef: baseRef.value, headRef: headRef.value })
    comparison.value = result.git?.compare ?? null
  } catch (e: unknown) {
    compareError.value = e instanceof Error ? e.message : 'Failed to compare refs'
  } finally {
    comparing.value = false
  }
}

function swapRefs() {
  const tmp = baseRef.value
  baseRef.value = headRef.value
  headRef.value = tmp
}

// ─── Diff helpers ───────────────────────────────────────────────────────────
const expandedFiles = ref<Set<string>>(new Set())

function toggleFile(path: string) {
  if (expandedFiles.value.has(path)) expandedFiles.value.delete(path)
  else expandedFiles.value.add(path)
}

function filePath(file: DiffFile): string {
  return file.newPath || file.oldPath || 'unknown'
}

function changeTypeLabel(ct: string): string {
  return { ADD: 'Added', DELETE: 'Deleted', MODIFY: 'Modified', RENAME: 'Renamed', COPY: 'Copied' }[ct] || ct
}

function lineContent(line: DiffLine): string {
  const c = line.content
  if (!c) return ''
  const first = c[0]
  if ((line.type === 'ADD' && first === '+') || (line.type === 'DELETE' && first === '-') || (line.type === 'CONTEXT' && first === ' ')) {
    return c.slice(1)
  }
  return c
}

const CHANGE_TYPE_COLORS: Record<string, string> = {
  ADD: '#34d99a', DELETE: '#ff5d6c', MODIFY: '#ffb547', RENAME: '#5ec5ff', COPY: '#a78bff',
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

function shortSha(sha: string): string {
  return sha.slice(0, 7)
}

const activeTab = ref<'Files' | 'Commits'>('Files')
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', 'Compare')"
        title="Compare"
        subtitle="Compare branches, tags, or commits"
      />
    </template>

    <div class="compare-layout">
      <!-- ── Ref selector ────────────────────────────────────────────── -->
      <div class="compare-bar">
        <div class="ref-selector">
          <div class="ref-group">
            <span class="ref-label">Base</span>
            <Select
              v-model="baseRef"
              :options="branchOptions"
              placeholder="base ref"
              :accent="accent"
              size="sm" />
          </div>

          <button class="swap-btn" title="Swap base and head" @click="swapRefs">
            <Icon name="arrowsLeftRight" :size="14" color="var(--fg-2)" />
          </button>

          <div class="ref-group">
            <span class="ref-label">Compare</span>
            <Select
              v-model="headRef"
              :options="branchOptions"
              placeholder="head ref"
              :accent="accent"
              size="sm" />
          </div>
        </div>

        <div class="compare-actions">
          <Select
            v-model="selectedRepoId"
            :options="repoOptions"
            placeholder="Repository"
            :accent="accent"
            size="sm" />
          <Button
            primary
            :accent="accent"
            size="sm"
            :disabled="comparing || !baseRef || !headRef || baseRef === headRef"
            @click="doCompare"
          >
            {{ comparing ? 'Comparing…' : 'Compare' }}
          </Button>
        </div>
      </div>

      <!-- ── Error ───────────────────────────────────────────────────── -->
      <div v-if="compareError" class="compare-error">
        <Icon name="alert" :size="14" color="#ff5d6c" />
        <span>{{ compareError }}</span>
      </div>

      <!-- ── Loading ─────────────────────────────────────────────────── -->
      <div v-if="comparing" class="loading-state">Comparing refs…</div>

      <!-- ── Results ─────────────────────────────────────────────────── -->
      <template v-else-if="comparison">
        <!-- Stats -->
        <StatGrid :columns="4">
          <StatTile label="Commits" :value="String(comparison.commits.length)" :accent="accent" />
          <StatTile label="Files Changed" :value="String(comparison.filesChanged)" :accent="accent" />
          <StatTile label="Insertions" :value="`+${comparison.insertions}`" accent="#34d99a" />
          <StatTile label="Deletions" :value="`-${comparison.deletions}`" accent="#ff5d6c" />
        </StatGrid>

        <!-- Tab toggle -->
        <div class="tab-toggle">
          <button :class="['tab-btn', { active: activeTab === 'Files' }]" @click="activeTab = 'Files'">
            <Icon name="file" :size="12" />
            Files ({{ comparison.files.length }})
          </button>
          <button :class="['tab-btn', { active: activeTab === 'Commits' }]" @click="activeTab = 'Commits'">
            <Icon name="git-commit" :size="12" />
            Commits ({{ comparison.commits.length }})
          </button>
        </div>

        <!-- Files tab -->
        <template v-if="activeTab === 'Files'">
          <div class="diff-files">
            <div v-for="file in comparison.files" :key="filePath(file)" class="diff-file">
              <div class="diff-file-header" @click="toggleFile(filePath(file))">
                <Icon :name="expandedFiles.has(filePath(file)) ? 'chevronDown' : 'chevron'" :size="12" color="var(--fg-3)" />
                <Badge :color="CHANGE_TYPE_COLORS[file.changeType] || '#6c7388'" small>
                  {{ changeTypeLabel(file.changeType) }}
                </Badge>
                <span class="mono diff-file-path">{{ filePath(file) }}</span>
                <span v-if="file.changeType === 'RENAME' && file.oldPath" class="mono diff-old-path">
                  ← {{ file.oldPath }}
                </span>
              </div>

              <div v-if="expandedFiles.has(filePath(file))" class="diff-hunks">
                <template v-for="(hunk, hi) in file.hunks" :key="hi">
                  <div class="diff-hunk-header mono">
                    @@ -{{ hunk.oldStart }},{{ hunk.oldCount }} +{{ hunk.newStart }},{{ hunk.newCount }} @@
                  </div>
                  <div
                    v-for="(line, li) in hunk.lines"
                    :key="li"
                    :class="['diff-line', `diff-${line.type.toLowerCase()}`]"
                  >
                    <span class="diff-line-num mono">{{ line.oldLineNumber ?? '' }}</span>
                    <span class="diff-line-num mono">{{ line.newLineNumber ?? '' }}</span>
                    <span class="diff-line-prefix mono">{{ line.type === 'ADD' ? '+' : line.type === 'DELETE' ? '-' : ' ' }}</span>
                    <span class="diff-line-content">{{ lineContent(line) }}</span>
                  </div>
                </template>
              </div>
            </div>

            <div v-if="!comparison.files.length" class="empty-msg">No file differences between these refs.</div>
          </div>
        </template>

        <!-- Commits tab -->
        <template v-if="activeTab === 'Commits'">
          <div class="commit-list">
            <div v-for="c in comparison.commits" :key="c.sha" class="commit-row">
              <div class="commit-icon">
                <Icon name="git-commit" :size="14" :color="accent" />
              </div>
              <div class="commit-body">
                <div class="commit-message">{{ c.message.split('\n')[0] }}</div>
                <div class="commit-meta">
                  <span class="commit-author">{{ c.authorName }}</span>
                  <span class="commit-time">{{ relativeTime(c.authorDate) }}</span>
                </div>
              </div>
              <span class="mono commit-sha">{{ shortSha(c.sha) }}</span>
            </div>
            <div v-if="!comparison.commits.length" class="empty-msg">No commits between these refs.</div>
          </div>
        </template>
      </template>

      <div v-else class="empty-msg">
        Select two branches or refs and click Compare to see the differences.
      </div>
    </div>
  </PageShell>
</template>

<style scoped>
.loading-state, .empty-msg {
  color: var(--fg-3); text-align: center; padding: 48px 0; font-size: 13.5px;
}
.compare-layout { display: flex; flex-direction: column; gap: 16px; }

/* ─── Compare bar ────────────────────────────────────────────────────────── */
.compare-bar {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  padding: 16px; display: flex; flex-direction: column; gap: 12px;
}
.ref-selector {
  display: flex; align-items: flex-end; gap: 10px;
}
.ref-group { display: flex; flex-direction: column; gap: 4px; flex: 1; }
.ref-label { font-size: 11px; color: var(--fg-3); text-transform: uppercase; letter-spacing: .06em; font-weight: 600; }

.swap-btn {
  background: var(--bg-2); border: 1px solid var(--line); border-radius: 6px;
  width: 32px; height: 32px; display: flex; align-items: center; justify-content: center;
  cursor: pointer; flex: 0 0 auto;
}
.swap-btn:hover { background: var(--bg-3); }

.compare-actions { display: flex; align-items: center; gap: 10px; justify-content: flex-end; }

.compare-error {
  display: flex; align-items: center; gap: 8px;
  padding: 14px 16px; font-size: 13px; color: #ff5d6c;
  background: color-mix(in oklch, #ff5d6c 6%, transparent);
  border: 1px solid color-mix(in oklch, #ff5d6c 20%, transparent);
  border-radius: 10px;
}

/* ─── Tab toggle ─────────────────────────────────────────────────────────── */
.tab-toggle {
  display: flex; gap: 2px; background: var(--bg-1); border-radius: 6px;
  padding: 2px; border: 1px solid var(--line); align-self: flex-start;
}
.tab-btn {
  display: flex; align-items: center; gap: 5px;
  background: none; border: none; padding: 6px 12px; cursor: pointer;
  font-size: 12px; color: var(--fg-2); border-radius: 4px; font-weight: 500;
}
.tab-btn:hover { background: var(--bg-2); }
.tab-btn.active { background: var(--bg-3); color: var(--fg-0); }

/* ─── Diff viewer ────────────────────────────────────────────────────────── */
.diff-files { display: flex; flex-direction: column; gap: 8px; }
.diff-file {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  overflow: hidden;
}
.diff-file-header {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px; cursor: pointer;
}
.diff-file-header:hover { background: var(--bg-2); }
.diff-file-path { font-size: 13px; color: var(--fg-0); flex: 1; }
.diff-old-path { font-size: 11px; color: var(--fg-3); }

.diff-hunks { border-top: 1px solid var(--line); overflow-x: auto; }
.diff-hunk-header {
  padding: 4px 16px; font-size: 11px; color: var(--fg-3);
  background: var(--bg-2); border-bottom: 1px solid var(--line);
}
.diff-line {
  display: flex; font-size: 12px; line-height: 1.7;
  font-family: 'Geist Mono', monospace;
}
.diff-line-num {
  width: 48px; text-align: right; padding: 0 6px;
  color: var(--fg-3); user-select: none; flex: 0 0 48px;
}
.diff-line-prefix { width: 16px; text-align: center; flex: 0 0 16px; user-select: none; }
.diff-line-content { flex: 1; white-space: pre; padding-right: 16px; }

.diff-add {
  background: color-mix(in oklch, #34d99a 8%, transparent);
  color: #34d99a;
}
.diff-delete {
  background: color-mix(in oklch, #ff5d6c 8%, transparent);
  color: #ff5d6c;
}
.diff-context { color: var(--fg-2); }

/* ─── Commit list ────────────────────────────────────────────────────────── */
.commit-list {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  overflow: hidden;
}
.commit-row {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 16px; border-bottom: 1px solid var(--line);
}
.commit-row:last-child { border-bottom: none; }
.commit-icon {
  width: 28px; height: 28px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  background: color-mix(in oklch, v-bind(accent) 10%, transparent);
  flex: 0 0 28px;
}
.commit-body { flex: 1; min-width: 0; }
.commit-message {
  font-size: 13.5px; color: var(--fg-0); font-weight: 500;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.commit-meta { display: flex; gap: 10px; margin-top: 3px; }
.commit-author { font-size: 12px; color: var(--fg-2); }
.commit-time { font-size: 11px; color: var(--fg-3); }
.commit-sha {
  font-size: 11px; color: var(--fg-3); padding: 2px 6px;
  background: var(--bg-2); border-radius: 4px; flex: 0 0 auto;
}
</style>
