<script setup lang="ts">
import gql from 'graphql-tag'
import ChangedFileTree from '~/components/git/ChangedFileTree.vue'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { query, mutation } = useGraphQL()
const { searchProfiles } = useProfileSearch()

const activeTab = ref('Overview')
const error = ref('')

// ─── Pull Request ────────────────────────────────────────────────────────────
interface ReviewComment {
  id: string; authorId: string; content: string; filePath: string
  oldLineNumber: number | null; newLineNumber: number | null
  commitSha: string; outdated: boolean; resolved: boolean
  created: string; updated: string
}

interface Review {
  id: string; reviewerId: string; status: string
  body: string | null; created: string
  dismissedAt: string | null; dismissReason: string | null
  comments: ReviewComment[]
}

interface DiffLine {
  content: string; type: string
  oldLineNumber: number | null; newLineNumber: number | null
}

interface DiffHunk {
  oldStart: number; oldCount: number; newStart: number; newCount: number
  totalLineCount: number
  lines: DiffLine[]
}

interface DiffFile {
  oldPath: string | null; newPath: string | null; changeType: string
  hunks: DiffHunk[]
}

interface Assignee {
  id: string; name: string
}

interface PullRequestLink {
  id: string; repositoryId: string; number: number; title: string
  status: string; mergeable?: boolean
}

interface PullRequest {
  id: string; number: number; title: string; description: string | null
  status: string; sourceBranch: string; targetBranch: string
  authorId: string; mergeable: boolean; created: string; updated: string
  mergedAt: string | null; mergedBy: string | null
  mergeSha: string | null; mergeStrategy: string | null
  repositoryId: string; sourceRepositoryId: string | null
  conflictingFiles: string[]
  assignees: Assignee[]
  dependencies: PullRequestLink[]
  dependents: PullRequestLink[]
  reviews: Review[]
  diff: DiffFile[]
}

const pr = ref<PullRequest | null>(null)

const repositoryId = computed(() => route.query.repo as string || '')
const prNumber = computed(() => Number(route.query.number) || 0)

const prLoading = ref(true)
const diffLoading = ref(false)
const diffError = ref('')
const reviewsLoading = ref(false)
const reviewsError = ref('')
const DIFF_LINES_PER_HUNK = 2_000

watch([repositoryId, prNumber], async ([repoId, num]) => {
  if (!repoId || !num) {
    prLoading.value = false
    return
  }
  prLoading.value = true
  diffError.value = ''
  reviewsError.value = ''
  try {
    const result = await query<{ git: { pullRequest: Omit<PullRequest, 'reviews' | 'diff'> | null } }>(gql`
      query GetPR($repositoryId: UUID!, $number: Int!) {
        git { pullRequest(repositoryId: $repositoryId, number: $number) {
          id number title description status sourceBranch targetBranch
          authorId mergeable created updated mergedAt mergedBy
          mergeSha mergeStrategy repositoryId sourceRepositoryId
          conflictingFiles
          assignees { id name }
          dependencies { id repositoryId number title status }
          dependents { id repositoryId number title status }
        } }
      }
    `, { repositoryId: repoId, number: num })
    const prData = result.git?.pullRequest ?? null
    if (prData) {
      pr.value = { ...prData, reviews: [], diff: [] }
      loadReviews(repoId, num)
      loadDiff(repoId, num)
    } else {
      pr.value = null
    }
  } catch { pr.value = null }
  finally { prLoading.value = false }
}, { immediate: true })

async function loadDiff(repoId: string, num: number) {
  diffLoading.value = true
  diffError.value = ''
  try {
    const result = await query<{ git: { pullRequest: { diff: DiffFile[] } | null } }>(gql`
      query GetPRDiff($repositoryId: UUID!, $number: Int!, $lineLimit: Int!) {
        git { pullRequest(repositoryId: $repositoryId, number: $number) {
          diff {
            oldPath newPath changeType
            hunks { oldStart oldCount newStart newCount totalLineCount
              lines(limit: $lineLimit) { content type oldLineNumber newLineNumber }
            }
          }
        } }
      }
    `, { repositoryId: repoId, number: num, lineLimit: DIFF_LINES_PER_HUNK })
    if (pr.value && result.git?.pullRequest?.diff) {
      pr.value.diff = result.git.pullRequest.diff
      initializeDiffReview(result.git.pullRequest.diff)
    }
  } catch (e: unknown) {
    diffError.value = e instanceof Error ? e.message : 'Failed to load diff'
  } finally {
    diffLoading.value = false
  }
}

async function loadReviews(repoId: string, num: number) {
  reviewsLoading.value = true
  reviewsError.value = ''
  try {
    const result = await query<{ git: { pullRequest: { reviews: Review[] } | null } }>(gql`
      query GetPRReviews($repositoryId: UUID!, $number: Int!) {
        git { pullRequest(repositoryId: $repositoryId, number: $number) {
          reviews {
            id reviewerId status body created dismissedAt dismissReason
            comments {
              id authorId content filePath oldLineNumber newLineNumber
              commitSha outdated resolved created updated
            }
          }
        } }
      }
    `, { repositoryId: repoId, number: num })
    if (pr.value && result.git?.pullRequest?.reviews) {
      pr.value.reviews = result.git.pullRequest.reviews
    }
  } catch (e: unknown) {
    reviewsError.value = e instanceof Error ? e.message : 'Failed to load reviews'
  } finally {
    reviewsLoading.value = false
  }
}

// ─── Repo info ───────────────────────────────────────────────────────────────
interface RepoInfo { id: string; name: string; slug: string; defaultBranch: string }
const repoInfo = ref<RepoInfo | null>(null)
const repositories = ref<RepoInfo[]>([])
const repositoryMap = computed(() => new Map(repositories.value.map(repository => [repository.id, repository])))

watch(repositoryId, async (id) => {
  if (!id) return
  try {
    const result = await query<{ git: { repositoryById: RepoInfo | null; repositories: RepoInfo[] } }>(gql`
      query RepositoriesForPR($id: UUID!) {
        git {
          repositoryById(id: $id) { id name slug defaultBranch }
          repositories { id name slug defaultBranch }
        }
      }
    `, { id })
    repoInfo.value = result.git?.repositoryById ?? null
    repositories.value = result.git?.repositories ?? []
  } catch { /* ignore */ }
}, { immediate: true })

// ─── Merge ───────────────────────────────────────────────────────────────────
const merging = ref(false)
const preparingMerge = ref(false)
const mergeStrategy = ref('MERGE_COMMIT')
const mergePlan = ref<PullRequestLink[]>([])
const showMergeDependenciesModal = ref(false)

const mergeStrategyOptions = [
  { value: 'MERGE_COMMIT', label: 'Merge Commit' },
  { value: 'SQUASH', label: 'Squash' },
  { value: 'REBASE', label: 'Rebase' },
  { value: 'FAST_FORWARD', label: 'Fast Forward' },
]

async function handleMerge() {
  if (!pr.value) return
  preparingMerge.value = true
  error.value = ''
  try {
    const result = await query<{ git: { pullRequestMergePlan: PullRequestLink[] } }>(gql`
      query GetPullRequestMergePlan($id: UUID!) {
        git { pullRequestMergePlan(id: $id) {
          id repositoryId number title status mergeable
        } }
      }
    `, { id: pr.value.id })
    const plan = result.git?.pullRequestMergePlan ?? []
    if (plan.length > 1) {
      mergePlan.value = plan
      showMergeDependenciesModal.value = true
      return
    }
    await mergePullRequest()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to prepare merge'
  } finally {
    preparingMerge.value = false
  }
}

async function mergePullRequest() {
  if (!pr.value) return
  merging.value = true
  error.value = ''
  try {
    await mutation(gql`
      mutation MergePR($id: UUID!, $strategy: GitMergeStrategy!) {
        git { mergePullRequest(id: $id, strategy: $strategy) { id status } }
      }
    `, { id: pr.value.id, strategy: mergeStrategy.value })
    // Reload
    pr.value = { ...pr.value, status: 'MERGED' }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to merge'
  } finally {
    merging.value = false
  }
}

const mergePlanHasBlockers = computed(() => mergePlan.value.some(item => (
  item.status !== 'OPEN' || item.mergeable === false
)))

async function mergePullRequestWithDependencies() {
  if (!pr.value || mergePlanHasBlockers.value) return
  merging.value = true
  error.value = ''
  try {
    await mutation(gql`
      mutation MergePRWithDependencies($id: UUID!, $strategy: GitMergeStrategy!) {
        git { mergePullRequestWithDependencies(id: $id, strategy: $strategy) { id status } }
      }
    `, { id: pr.value.id, strategy: mergeStrategy.value })
    pr.value = { ...pr.value, status: 'MERGED' }
    showMergeDependenciesModal.value = false
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to merge pull requests'
  } finally {
    merging.value = false
  }
}

async function handleClose() {
  if (!pr.value) return
  try {
    await mutation(gql`
      mutation ClosePR($id: UUID!) {
        git { closePullRequest(id: $id) { id status } }
      }
    `, { id: pr.value.id })
    pr.value = { ...pr.value, status: 'CLOSED' }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to close'
  }
}

async function handleReopen() {
  if (!pr.value) return
  try {
    await mutation(gql`
      mutation ReopenPR($id: UUID!) {
        git { reopenPullRequest(id: $id) { id status } }
      }
    `, { id: pr.value.id })
    pr.value = { ...pr.value, status: 'OPEN' }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to reopen'
  }
}

async function handleMarkReady() {
  if (!pr.value) return
  try {
    await mutation(gql`
      mutation MarkReady($id: UUID!) {
        git { markPullRequestReady(id: $id) { id status } }
      }
    `, { id: pr.value.id })
    pr.value = { ...pr.value, status: 'OPEN' }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to mark ready'
  }
}

// ─── Submit Review ───────────────────────────────────────────────────────────
const showReviewModal = ref(false)
const reviewBody = ref('')
const reviewStatus = ref('COMMENT_ONLY')
const reviewSaving = ref(false)

const reviewStatusOptions = [
  { value: 'APPROVED', label: 'Approve' },
  { value: 'CHANGES_REQUESTED', label: 'Request Changes' },
  { value: 'COMMENT_ONLY', label: 'Comment' },
]

async function handleSubmitReview() {
  if (!pr.value) return
  reviewSaving.value = true
  error.value = ''
  try {
    await mutation(gql`
      mutation SubmitReview($input: SubmitGitReviewInput!) {
        git { submitReview(input: $input) { id } }
      }
    `, { input: { pullRequestId: pr.value.id, status: reviewStatus.value, body: reviewBody.value || null } })
    showReviewModal.value = false
    reviewBody.value = ''
    loadReviews(repositoryId.value, prNumber.value)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to submit review'
  } finally {
    reviewSaving.value = false
  }
}

// ─── Inline review comments ─────────────────────────────────────────────────
interface CommentAnchor { filePath: string; lineNumber: number }
const inlineCommentTarget = ref<CommentAnchor | null>(null)
const inlineCommentText = ref('')
const inlineCommentSaving = ref(false)

function commentKey(fp: string, line: number | null): string {
  return `${fp}:${line ?? 0}`
}

const commentsByLine = computed(() => {
  const map = new Map<string, ReviewComment[]>()
  if (!pr.value) return map
  for (const review of pr.value.reviews) {
    for (const c of review.comments) {
      const key = commentKey(c.filePath, c.newLineNumber ?? c.oldLineNumber)
      const arr = map.get(key) ?? []
      arr.push(c)
      map.set(key, arr)
    }
  }
  return map
})

function openInlineComment(fp: string, line: DiffLine) {
  inlineCommentTarget.value = { filePath: fp, lineNumber: line.newLineNumber ?? line.oldLineNumber ?? 0 }
  inlineCommentText.value = ''
}

function cancelInlineComment() {
  inlineCommentTarget.value = null
  inlineCommentText.value = ''
}

function isCommentTarget(fp: string, line: DiffLine): boolean {
  if (!inlineCommentTarget.value) return false
  const ln = line.newLineNumber ?? line.oldLineNumber ?? 0
  return inlineCommentTarget.value.filePath === fp && inlineCommentTarget.value.lineNumber === ln
}

function getLineComments(fp: string, line: DiffLine): ReviewComment[] {
  const ln = line.newLineNumber ?? line.oldLineNumber ?? 0
  return commentsByLine.value.get(commentKey(fp, ln)) ?? []
}

async function submitInlineComment() {
  if (!pr.value || !inlineCommentTarget.value || !inlineCommentText.value.trim()) return
  inlineCommentSaving.value = true
  error.value = ''
  try {
    const latestReview = pr.value.reviews.find(r => !r.dismissedAt)
    if (!latestReview) {
      error.value = 'Submit a review first before adding inline comments.'
      return
    }
    await mutation(gql`
      mutation AddReviewComment($input: AddGitReviewCommentInput!) {
        git { addReviewComment(input: $input) { id } }
      }
    `, {
      input: {
        pullRequestId: pr.value.id,
        reviewId: latestReview.id,
        filePath: inlineCommentTarget.value.filePath,
        newLineNumber: inlineCommentTarget.value.lineNumber,
        commitSha: '',
        content: inlineCommentText.value,
      },
    })
    inlineCommentTarget.value = null
    inlineCommentText.value = ''
    loadReviews(repositoryId.value, prNumber.value)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to add comment'
  } finally {
    inlineCommentSaving.value = false
  }
}

// ─── Resolve thread ─────────────────────────────────────────────────────────
async function resolveThread(fp: string, lineNumber: number) {
  if (!pr.value) return
  try {
    await mutation(gql`
      mutation ResolveThread($pullRequestId: UUID!, $filePath: String!, $lineNumber: Int!) {
        git { resolveReviewThread(pullRequestId: $pullRequestId, filePath: $filePath, lineNumber: $lineNumber) }
      }
    `, { pullRequestId: pr.value.id, filePath: fp, lineNumber })
    loadReviews(repositoryId.value, prNumber.value)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to resolve thread'
  }
}

// ─── Assignees ──────────────────────────────────────────────────────────────
const editingAssignees = ref(false)

async function assignProfile(profileId: string) {
  if (!pr.value || !profileId) return
  editingAssignees.value = false
  try {
    const result = await mutation<{ git: { assignPullRequest: { assignees: Assignee[] } } }>(gql`
      mutation AssignPR($id: UUID!, $profileId: UUID!) {
        git { assignPullRequest(id: $id, profileId: $profileId) { assignees { id name } } }
      }
    `, { id: pr.value.id, profileId })
    pr.value.assignees = result.git.assignPullRequest.assignees
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to assign'
  }
}

async function unassignProfile(profileId: string) {
  if (!pr.value) return
  try {
    const result = await mutation<{ git: { unassignPullRequest: { assignees: Assignee[] } } }>(gql`
      mutation UnassignPR($id: UUID!, $profileId: UUID!) {
        git { unassignPullRequest(id: $id, profileId: $profileId) { assignees { id name } } }
      }
    `, { id: pr.value.id, profileId })
    pr.value.assignees = result.git.unassignPullRequest.assignees
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to unassign'
  }
}

// ─── Dependencies ────────────────────────────────────────────────────────────
const editingDependencies = ref(false)
const dependencySaving = ref(false)
const dependencyCandidates = ref<PullRequestLink[]>([])
const dependencyCandidatesLoaded = ref(false)

function pullRequestLabel(pullRequest: PullRequestLink): string {
  const repository = repositoryMap.value.get(pullRequest.repositoryId)
  const repositoryName = repository?.name ?? pullRequest.repositoryId.slice(0, 8)
  return `${repositoryName} #${pullRequest.number}: ${pullRequest.title}`
}

async function loadDependencyCandidates() {
  if (dependencyCandidatesLoaded.value) return
  const candidates: PullRequestLink[] = []
  let offset = 0
  const limit = 100
  while (true) {
    const result = await query<{ git: { allPullRequests: PullRequestLink[] } }>(gql`
      query DependencyCandidates($offset: Long!, $limit: Int!) {
        git { allPullRequests(offset: $offset, limit: $limit) {
          id repositoryId number title status
        } }
      }
    `, { offset, limit })
    const page = result.git?.allPullRequests ?? []
    candidates.push(...page)
    if (page.length < limit) break
    offset += page.length
  }
  dependencyCandidates.value = candidates
  dependencyCandidatesLoaded.value = true
}

async function searchDependencyPullRequests(search: string) {
  await loadDependencyCandidates()
  const currentId = pr.value?.id
  const existing = new Set(pr.value?.dependencies.map(dependency => dependency.id) ?? [])
  const normalizedSearch = search.trim().toLowerCase()
  return dependencyCandidates.value
    .filter(candidate => (
      candidate.id !== currentId
      && !existing.has(candidate.id)
      && (candidate.status === 'OPEN' || candidate.status === 'DRAFT')
      && (!normalizedSearch || pullRequestLabel(candidate).toLowerCase().includes(normalizedSearch))
    ))
    .map(candidate => ({ value: candidate.id, label: pullRequestLabel(candidate) }))
}

async function addDependency(dependencyId: string) {
  if (!pr.value || !dependencyId) return
  dependencySaving.value = true
  error.value = ''
  try {
    const result = await mutation<{ git: { addPullRequestDependency: { dependencies: PullRequestLink[] } } }>(gql`
      mutation AddPullRequestDependency($id: UUID!, $dependencyId: UUID!) {
        git { addPullRequestDependency(id: $id, dependencyId: $dependencyId) {
          dependencies { id repositoryId number title status }
        } }
      }
    `, { id: pr.value.id, dependencyId })
    pr.value.dependencies = result.git.addPullRequestDependency.dependencies
    editingDependencies.value = false
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to add dependency'
  } finally {
    dependencySaving.value = false
  }
}

async function removeDependency(dependencyId: string) {
  if (!pr.value) return
  dependencySaving.value = true
  error.value = ''
  try {
    const result = await mutation<{ git: { removePullRequestDependency: { dependencies: PullRequestLink[] } } }>(gql`
      mutation RemovePullRequestDependency($id: UUID!, $dependencyId: UUID!) {
        git { removePullRequestDependency(id: $id, dependencyId: $dependencyId) {
          dependencies { id repositoryId number title status }
        } }
      }
    `, { id: pr.value.id, dependencyId })
    pr.value.dependencies = result.git.removePullRequestDependency.dependencies
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to remove dependency'
  } finally {
    dependencySaving.value = false
  }
}

function openPullRequest(pullRequest: PullRequestLink) {
  router.push(`/git/pulls/${pullRequest.id}?repo=${pullRequest.repositoryId}&number=${pullRequest.number}`)
}

// ─── Diff helpers ────────────────────────────────────────────────────────────
const expandedFiles = ref<Set<string>>(new Set())
const viewedFileRevisions = ref<Record<string, string>>({})
const fileRevisions = ref<Record<string, string>>({})
const selectedFilePath = ref('')
const fileSearch = ref('')
const reviewFileFilter = ref<'ALL' | 'UNVIEWED'>('ALL')

const reviewedFileCount = computed(() => pr.value?.diff.filter(isFileViewed).length ?? 0)
const unreviewedFileCount = computed(() => (pr.value?.diff.length ?? 0) - reviewedFileCount.value)
const reviewProgress = computed(() => {
  const count = pr.value?.diff.length ?? 0
  return count ? Math.round((reviewedFileCount.value / count) * 100) : 0
})
const fileStatsByPath = computed(() => new Map((pr.value?.diff ?? []).map(file => {
  let additions = 0
  let deletions = 0
  for (const hunk of file.hunks) {
    for (const line of hunk.lines) {
      if (line.type === 'ADD') additions += 1
      if (line.type === 'DELETE') deletions += 1
    }
  }
  return [filePath(file), { additions, deletions }]
})))
const filteredDiffFiles = computed(() => {
  const normalizedSearch = fileSearch.value.trim().toLowerCase()
  return (pr.value?.diff ?? []).filter(file => {
    if (reviewFileFilter.value === 'UNVIEWED' && isFileViewed(file)) return false
    return !normalizedSearch || filePath(file).toLowerCase().includes(normalizedSearch)
  })
})
const changedFileTreeEntries = computed(() => filteredDiffFiles.value.map(file => ({
  path: filePath(file),
  changeType: file.changeType,
  viewed: isFileViewed(file),
  commentCount: fileCommentCount(file),
})))
const filteredUnreviewedFileCount = computed(() => filteredDiffFiles.value.filter(file => !isFileViewed(file)).length)

function initializeDiffReview(files: DiffFile[]) {
  fileRevisions.value = Object.fromEntries(files.map(file => [filePath(file), calculateFileRevision(file)]))
  const firstPath = files[0] ? filePath(files[0]) : ''
  selectedFilePath.value = firstPath
  expandedFiles.value = firstPath ? new Set([firstPath]) : new Set()
  restoreViewedFiles(files)
}

function toggleFile(path: string) {
  const next = new Set(expandedFiles.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  expandedFiles.value = next
  selectedFilePath.value = path
}

function filePath(file: DiffFile): string {
  return file.newPath || file.oldPath || 'unknown'
}

function fileLineStats(file: DiffFile): { additions: number; deletions: number } {
  return fileStatsByPath.value.get(filePath(file)) ?? { additions: 0, deletions: 0 }
}

function fileCommentCount(file: DiffFile): number {
  const path = filePath(file)
  return pr.value?.reviews.flatMap(review => review.comments).filter(comment => comment.filePath === path).length ?? 0
}

function calculateFileRevision(file: DiffFile): string {
  let hash = 2166136261
  const update = (value: string) => {
    for (let index = 0; index < value.length; index += 1) {
      hash ^= value.charCodeAt(index)
      hash = Math.imul(hash, 16777619)
    }
  }
  update(`${file.oldPath}|${file.newPath}|${file.changeType}`)
  for (const hunk of file.hunks) {
    update(`${hunk.oldStart}|${hunk.oldCount}|${hunk.newStart}|${hunk.newCount}|${hunk.totalLineCount}`)
    for (const line of hunk.lines) {
      update(`${line.type}|${line.oldLineNumber}|${line.newLineNumber}|${line.content}`)
    }
  }
  return (hash >>> 0).toString(36)
}

function fileRevision(file: DiffFile): string {
  return fileRevisions.value[filePath(file)] ?? calculateFileRevision(file)
}

function reviewStorageKey(): string | null {
  return pr.value ? `bosca:git:pull-request:${pr.value.id}:viewed-files` : null
}

function restoreViewedFiles(files: DiffFile[]) {
  const key = reviewStorageKey()
  if (!key || typeof window === 'undefined') {
    viewedFileRevisions.value = {}
    return
  }
  try {
    const stored = JSON.parse(window.localStorage.getItem(key) ?? '{}') as Record<string, string>
    viewedFileRevisions.value = Object.fromEntries(files.flatMap(file => {
      const path = filePath(file)
      const revision = fileRevision(file)
      return stored[path] === revision ? [[path, revision]] : []
    }))
    persistViewedFiles()
  } catch {
    viewedFileRevisions.value = {}
  }
}

function persistViewedFiles() {
  const key = reviewStorageKey()
  if (!key || typeof window === 'undefined') return
  try {
    window.localStorage.setItem(key, JSON.stringify(viewedFileRevisions.value))
  } catch { /* Browser storage may be disabled. */ }
}

function isFileViewed(file: DiffFile): boolean {
  return viewedFileRevisions.value[filePath(file)] === fileRevision(file)
}

function setFileViewed(file: DiffFile, viewed: boolean) {
  const path = filePath(file)
  viewedFileRevisions.value = viewed
    ? { ...viewedFileRevisions.value, [path]: fileRevision(file) }
    : Object.fromEntries(Object.entries(viewedFileRevisions.value).filter(([key]) => key !== path))
  persistViewedFiles()
}

function markFilteredFilesViewed(viewed: boolean) {
  if (viewed) {
    viewedFileRevisions.value = {
      ...viewedFileRevisions.value,
      ...Object.fromEntries(filteredDiffFiles.value.map(file => [filePath(file), fileRevision(file)])),
    }
  } else {
    const filteredPaths = new Set(filteredDiffFiles.value.map(filePath))
    viewedFileRevisions.value = Object.fromEntries(
      Object.entries(viewedFileRevisions.value).filter(([path]) => !filteredPaths.has(path)),
    )
  }
  persistViewedFiles()
}

function setFilteredFilesExpanded(expanded: boolean) {
  const next = new Set(expandedFiles.value)
  for (const file of filteredDiffFiles.value) {
    const path = filePath(file)
    if (expanded) next.add(path)
    else next.delete(path)
  }
  expandedFiles.value = next
}

async function scrollToFile(file: DiffFile) {
  const path = filePath(file)
  selectedFilePath.value = path
  if (!expandedFiles.value.has(path)) {
    expandedFiles.value = new Set([...expandedFiles.value, path])
  }
  await nextTick()
  const element = Array.from(document.querySelectorAll<HTMLElement>('[data-diff-file]'))
    .find(candidate => candidate.dataset.diffFile === path)
  element?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
}

function scrollToFilePath(path: string) {
  const file = pr.value?.diff.find(candidate => filePath(candidate) === path)
  if (file) scrollToFile(file)
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

// ─── Helpers ─────────────────────────────────────────────────────────────────
function relativeTime(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(ms / 60_000)
  if (mins < 1) return 'now'
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.floor(hrs / 24)}d ago`
}

function formatDate(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

const PR_STATUS_COLORS: Record<string, string> = {
  OPEN: '#34d99a', DRAFT: '#6c7388', MERGED: '#a78bff', CLOSED: '#ff5d6c',
}

const REVIEW_STATUS_COLORS: Record<string, string> = {
  APPROVED: '#34d99a', CHANGES_REQUESTED: '#ff5d6c', COMMENT_ONLY: '#ffb547',
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', 'Pull Requests', pr ? `#${pr.number}` : '…')"
        :title="pr ? pr.title : 'Loading…'"
        :subtitle="pr ? `#${pr.number} · ${pr.sourceBranch} → ${pr.targetBranch}` : ''"
        :tabs="['Overview', 'Files Changed', 'Reviews']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #title>
          <span class="title-row">
            <span class="pr-mark" :style="{ background: PR_STATUS_COLORS[pr?.status || ''] || '#6c7388' }">
              <Icon name="git-pull-request" :size="16" color="#fff" />
            </span>
            {{ pr?.title || 'Loading…' }}
            <Badge v-if="pr?.status" :color="PR_STATUS_COLORS[pr.status] || '#6c7388'">{{ pr.status.toLowerCase() }}</Badge>
          </span>
        </template>
        <template #actions>
          <Button
            v-if="pr?.status === 'OPEN' || pr?.status === 'DRAFT'"
            icon="eye"
            size="sm"
            :accent="accent"
            @click="showReviewModal = true">Review</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="prLoading" class="loading-state">Loading pull request…</div>
    <div v-else-if="!pr" class="loading-state">Pull request not found. Navigate from a repository's PR list.</div>

    <template v-else>
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Overview                                                        -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Overview'" class="overview-layout">
        <div class="overview-main">
          <!-- Diff error banner -->
          <div v-if="diffError" class="diff-error">
            <Icon name="alert" :size="14" color="#ff5d6c" />
            <span>{{ diffError }}</span>
          </div>

          <!-- Description (author-written Markdown; rendered with raw HTML disabled) -->
          <SectionCard title="Description">
            <div class="pr-description">
              <CommonMarkdown
                v-if="pr.description"
                :value="pr.description"
                :cache-key="`pull-request-description-${pr.id}-${pr.updated}`" />
              <p v-else class="fg-3">No description provided.</p>
            </div>
          </SectionCard>

          <!-- Recent reviews -->
          <SectionCard title="Reviews">
            <template #right>
              <span v-if="reviewsLoading" class="mono section-meta">loading…</span>
              <span v-else class="mono section-meta">{{ pr.reviews.length }} reviews</span>
            </template>
            <div v-if="reviewsError" class="diff-error">
              <Icon name="alert" :size="14" color="#ff5d6c" />
              <span>{{ reviewsError }}</span>
            </div>
            <div v-else-if="pr.reviews.length" class="review-list">
              <div v-for="r in pr.reviews" :key="r.id" class="review-row">
                <div class="review-icon" :style="{ background: `color-mix(in oklch, ${REVIEW_STATUS_COLORS[r.status] || '#6c7388'} 14%, transparent)` }">
                  <Icon name="eye" :size="13" :color="REVIEW_STATUS_COLORS[r.status] || '#6c7388'" />
                </div>
                <div class="review-body">
                  <div class="review-title">
                    <Badge :color="REVIEW_STATUS_COLORS[r.status] || '#6c7388'">{{ r.status.replace(/_/g, ' ').toLowerCase() }}</Badge>
                    <span v-if="r.comments.length" class="mono review-comments">{{ r.comments.length }} comments</span>
                  </div>
                  <p v-if="r.body" class="review-text">{{ r.body }}</p>
                </div>
                <span class="mono review-time">{{ relativeTime(r.created) }}</span>
              </div>
            </div>
            <div v-else class="empty-msg">No reviews yet.</div>
          </SectionCard>

          <!-- Conflicts -->
          <SectionCard v-if="pr.conflictingFiles.length" title="Merge Conflicts">
            <template #right>
              <Badge color="#ff5d6c">{{ pr.conflictingFiles.length }} {{ pr.conflictingFiles.length === 1 ? 'conflict' : 'conflicts' }}</Badge>
            </template>
            <div class="conflict-banner">
              <Icon name="alert" :size="16" color="#ff5d6c" />
              <div class="conflict-banner-text">
                <p class="conflict-banner-title">This branch has conflicts that must be resolved</p>
                <p class="conflict-banner-subtitle">
                  The following files have conflicting changes between
                  <code class="mono conflict-branch">{{ pr.sourceBranch }}</code>
                  and
                  <code class="mono conflict-branch">{{ pr.targetBranch }}</code>.
                  Resolve conflicts locally and push to update this pull request.
                </p>
              </div>
            </div>
            <div class="conflict-file-list">
              <div v-for="f in pr.conflictingFiles" :key="f" class="conflict-file-row">
                <Icon name="alert" :size="13" color="#ff5d6c" />
                <span class="mono conflict-file-path">{{ f }}</span>
              </div>
            </div>
            <div class="conflict-instructions">
              <div class="conflict-instructions-header">
                <Icon name="code" :size="14" :color="accent" />
                <span>Resolve via command line</span>
              </div>
              <pre class="conflict-cmd mono">git fetch origin
git checkout {{ pr.sourceBranch }}
git merge origin/{{ pr.targetBranch }}
# Resolve conflicts in your editor, then:
git add .
git commit
git push origin {{ pr.sourceBranch }}</pre>
            </div>
          </SectionCard>
        </div>

        <div class="overview-sidebar">
          <!-- Info card -->
          <div class="card">
            <div class="card-section-title">Details</div>
            <div class="card-info-list">
              <div class="card-info-row">
                <span class="card-info-label">Status</span>
                <Badge :color="PR_STATUS_COLORS[pr.status] || '#6c7388'">{{ pr.status.toLowerCase() }}</Badge>
              </div>
              <div class="card-info-row">
                <span class="card-info-label">Mergeable</span>
                <Badge :color="pr.mergeable ? '#34d99a' : '#ff5d6c'">{{ pr.mergeable ? 'Yes' : 'No' }}</Badge>
              </div>
              <div class="card-info-row">
                <span class="card-info-label">Source</span>
                <span class="mono card-info-value">{{ pr.sourceBranch }}</span>
              </div>
              <div class="card-info-row">
                <span class="card-info-label">Target</span>
                <span class="mono card-info-value">{{ pr.targetBranch }}</span>
              </div>
              <div v-if="repoInfo" class="card-info-row">
                <span class="card-info-label">Repository</span>
                <button class="link-btn" @click="router.push(`/git/repositories/${repoInfo.id}`)">{{ repoInfo.name }}</button>
              </div>
              <div class="card-info-row">
                <span class="card-info-label">Files</span>
                <span class="card-info-value">{{ pr.diff.length }} changed</span>
              </div>
              <div class="card-info-row">
                <span class="card-info-label">Created</span>
                <span class="card-info-value">{{ formatDate(pr.created) }}</span>
              </div>
              <div v-if="pr.mergedAt" class="card-info-row">
                <span class="card-info-label">Merged</span>
                <span class="card-info-value">{{ formatDate(pr.mergedAt) }}</span>
              </div>
              <div v-if="pr.mergeStrategy" class="card-info-row">
                <span class="card-info-label">Strategy</span>
                <span class="card-info-value">{{ pr.mergeStrategy.replace(/_/g, ' ').toLowerCase() }}</span>
              </div>
            </div>
          </div>

          <!-- Assignees card -->
          <div class="card">
            <div class="card-section-title">Assignees</div>
            <div v-if="pr.assignees.length" class="assignee-list">
              <div v-for="a in pr.assignees" :key="a.id" class="assignee-row">
                <Avatar :name="a.name" :idx="0" :size="20" />
                <span class="assignee-name">{{ a.name }}</span>
                <button
                  v-if="pr.status === 'OPEN' || pr.status === 'DRAFT'"
                  class="assignee-remove"
                  title="Remove assignee"
                  @click="unassignProfile(a.id)"
                >
                  <Icon name="x" :size="12" color="var(--fg-3)" />
                </button>
              </div>
            </div>
            <div v-else class="empty-assignees">No assignees</div>
            <template v-if="pr.status === 'OPEN' || pr.status === 'DRAFT'">
              <div v-if="editingAssignees" class="assignee-search">
                <Select
                  model-value=""
                  :on-search="searchProfiles"
                  searchable
                  placeholder="Search people…"
                  size="sm"
                  @update:model-value="assignProfile($event as string)"
                />
                <button class="sidebar-cancel-link" @click="editingAssignees = false">cancel</button>
              </div>
              <button v-else class="add-assignee-btn" @click="editingAssignees = true">
                <Icon name="plus" :size="12" :color="accent" />
                <span>Add assignee</span>
              </button>
            </template>
          </div>

          <!-- Dependencies card -->
          <div class="card">
            <div class="card-section-title">Dependencies</div>
            <div v-if="pr.dependencies.length" class="dependency-list">
              <div v-for="dependency in pr.dependencies" :key="dependency.id" class="dependency-row">
                <button class="dependency-link" @click="openPullRequest(dependency)">
                  <span>{{ pullRequestLabel(dependency) }}</span>
                  <Badge :color="PR_STATUS_COLORS[dependency.status] || '#6c7388'" small>
                    {{ dependency.status.toLowerCase() }}
                  </Badge>
                </button>
                <button
                  v-if="pr.status === 'OPEN' || pr.status === 'DRAFT'"
                  class="dependency-remove"
                  title="Remove dependency"
                  :disabled="dependencySaving"
                  @click="removeDependency(dependency.id)"
                >
                  <Icon name="x" :size="12" color="var(--fg-3)" />
                </button>
              </div>
            </div>
            <div v-else class="empty-assignees">No dependencies</div>
            <template v-if="pr.status === 'OPEN' || pr.status === 'DRAFT'">
              <div v-if="editingDependencies" class="assignee-search">
                <Select
                  class="dependency-select"
                  model-value=""
                  :on-search="searchDependencyPullRequests"
                  searchable
                  placeholder="Search pull requests…"
                  size="sm"
                  :disabled="dependencySaving"
                  @update:model-value="addDependency($event as string)"
                />
                <button class="sidebar-cancel-link" @click="editingDependencies = false">cancel</button>
              </div>
              <button v-else class="add-assignee-btn add-dependency-action" @click="editingDependencies = true">
                <Icon name="plus" :size="12" :color="accent" />
                <span>Add dependency</span>
              </button>
            </template>

            <template v-if="pr.dependents.length">
              <div class="dependency-divider" />
              <div class="card-section-title dependent-title">Used by</div>
              <div class="dependency-list">
                <button
                  v-for="dependent in pr.dependents"
                  :key="dependent.id"
                  class="dependency-link dependent-link"
                  @click="openPullRequest(dependent)"
                >
                  <span>{{ pullRequestLabel(dependent) }}</span>
                  <Badge :color="PR_STATUS_COLORS[dependent.status] || '#6c7388'" small>
                    {{ dependent.status.toLowerCase() }}
                  </Badge>
                </button>
              </div>
            </template>
          </div>

          <!-- Actions card -->
          <div v-if="pr.status === 'OPEN' || pr.status === 'DRAFT'" class="card">
            <div class="card-section-title">Actions</div>
            <div class="action-stack">
              <template v-if="pr.status === 'DRAFT'">
                <Button
                  primary
                  :accent="accent"
                  size="sm"
                  @click="handleMarkReady">Mark Ready for Review</Button>
              </template>
              <template v-if="pr.status === 'OPEN'">
                <div class="merge-row">
                  <Select
                    v-model="mergeStrategy"
                    :options="mergeStrategyOptions"
                    size="sm"
                    :accent="accent" />
                </div>
                <Button
                  class="merge-action"
                  primary
                  :accent="accent"
                  size="sm"
                  :disabled="!pr.mergeable || merging || preparingMerge"
                  @click="handleMerge"
                >
                  {{ merging ? 'Merging…' : preparingMerge ? 'Checking dependencies…' : 'Merge Pull Request' }}
                </Button>
                <p v-if="!pr.mergeable" class="merge-warning">Cannot merge — conflicts detected.</p>
                <p v-else-if="pr.dependencies.some(dependency => dependency.status !== 'MERGED')" class="dependency-warning">
                  Open dependencies will be included in the merge confirmation.
                </p>
              </template>
              <Button v-if="pr.status === 'OPEN' || pr.status === 'DRAFT'" size="sm" @click="handleClose">Close</Button>
              <p v-if="error" class="form-error">{{ error }}</p>
            </div>
          </div>

          <div v-if="pr.status === 'CLOSED'" class="card">
            <div class="card-section-title">Actions</div>
            <div class="action-stack">
              <Button
                primary
                :accent="accent"
                size="sm"
                @click="handleReopen">Reopen</Button>
            </div>
          </div>
        </div>
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Files Changed (Diff)                                            -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Files Changed'">
        <div v-if="diffLoading" class="loading-state">Loading diff…</div>
        <div v-else-if="diffError" class="diff-error">
          <Icon name="alert" :size="14" color="#ff5d6c" />
          <span>{{ diffError }}</span>
        </div>
        <template v-else>
          <div class="review-toolbar">
            <div
              class="review-progress-copy"
              title="Viewed files are saved in this browser and reset when their diff changes"
            >
              <span class="review-progress-title">
                {{ reviewedFileCount }} of {{ pr.diff.length }} files viewed
              </span>
              <span class="mono review-progress-percent">{{ reviewProgress }}%</span>
            </div>
            <div
              class="review-progress-track"
              role="progressbar"
              :aria-valuenow="reviewedFileCount"
              :aria-valuemin="0"
              :aria-valuemax="pr.diff.length"
              aria-label="Files reviewed"
            >
              <span class="review-progress-fill" :style="{ width: `${reviewProgress}%` }" />
            </div>
            <div class="review-toolbar-actions">
              <button class="review-toolbar-button" @click="setFilteredFilesExpanded(true)">Expand all</button>
              <button class="review-toolbar-button" @click="setFilteredFilesExpanded(false)">Collapse all</button>
              <button
                v-if="filteredUnreviewedFileCount"
                class="review-toolbar-button review-toolbar-primary"
                @click="markFilteredFilesViewed(true)"
              >
                Mark visible viewed
              </button>
              <button
                v-else-if="filteredDiffFiles.length"
                class="review-toolbar-button"
                @click="markFilteredFilesViewed(false)"
              >
                Mark visible unviewed
              </button>
            </div>
          </div>

          <div class="review-workspace">
            <aside class="changed-files-sidebar" aria-label="Changed files">
              <div class="changed-files-sidebar-header">
                <div>
                  <strong>Changed files</strong>
                  <span class="mono">{{ pr.diff.length }}</span>
                </div>
                <div class="review-file-filters" aria-label="Review file filter">
                  <button
                    :class="{ active: reviewFileFilter === 'ALL' }"
                    @click="reviewFileFilter = 'ALL'"
                  >
                    All
                  </button>
                  <button
                    class="review-filter-unviewed"
                    :class="{ active: reviewFileFilter === 'UNVIEWED' }"
                    @click="reviewFileFilter = 'UNVIEWED'"
                  >
                    Unviewed {{ unreviewedFileCount }}
                  </button>
                </div>
              </div>
              <label class="changed-file-search">
                <Icon name="search" :size="13" color="var(--fg-3)" />
                <input
                  v-model="fileSearch"
                  type="search"
                  placeholder="Filter changed files"
                  aria-label="Filter changed files">
              </label>
              <ChangedFileTree
                v-if="filteredDiffFiles.length"
                :files="changedFileTreeEntries"
                :selected-path="selectedFilePath"
                @select="scrollToFilePath"
              />
              <div v-else class="changed-files-empty">
                {{ reviewFileFilter === 'UNVIEWED' && !fileSearch ? 'All files viewed.' : 'No matching files.' }}
              </div>
            </aside>

            <div class="review-diff-column">
              <div class="diff-summary">
                <span class="mono">{{ filteredDiffFiles.length }} of {{ pr.diff.length }} files shown</span>
                <span v-if="fileSearch" class="diff-summary-filter">matching “{{ fileSearch }}”</span>
              </div>

              <div class="diff-files">
                <div
                  v-for="file in filteredDiffFiles"
                  :key="filePath(file)"
                  class="diff-file"
                  :class="{ 'diff-file-viewed': isFileViewed(file) }"
                  :data-diff-file="filePath(file)"
                >
                  <div class="diff-file-header">
                    <button class="diff-file-title" @click="toggleFile(filePath(file))">
                      <Icon :name="expandedFiles.has(filePath(file)) ? 'chevronDown' : 'chevron'" :size="12" color="var(--fg-3)" />
                      <Badge :color="CHANGE_TYPE_COLORS[file.changeType] || '#6c7388'" small>
                        {{ changeTypeLabel(file.changeType) }}
                      </Badge>
                      <span class="mono diff-file-path">{{ filePath(file) }}</span>
                      <span v-if="file.changeType === 'RENAME' && file.oldPath" class="mono diff-old-path">← {{ file.oldPath }}</span>
                    </button>
                    <span class="diff-file-stats mono">
                      <span class="diff-stat-add">+{{ fileLineStats(file).additions }}</span>
                      <span class="diff-stat-delete">−{{ fileLineStats(file).deletions }}</span>
                    </span>
                    <label class="viewed-file-toggle" @click.stop>
                      <input
                        type="checkbox"
                        :checked="isFileViewed(file)"
                        :aria-label="`Mark ${filePath(file)} viewed`"
                        @change="setFileViewed(file, ($event.target as HTMLInputElement).checked)"
                      >
                      <span>Viewed</span>
                    </label>
                  </div>

                  <div v-if="expandedFiles.has(filePath(file))" class="diff-hunks">
                    <template v-for="(hunk, hi) in file.hunks" :key="hi">
                      <div class="diff-hunk-header mono">
                        @@ -{{ hunk.oldStart }},{{ hunk.oldCount }} +{{ hunk.newStart }},{{ hunk.newCount }} @@
                      </div>
                      <template v-for="(line, li) in hunk.lines" :key="li">
                        <div :class="['diff-line', `diff-${line.type.toLowerCase()}`]" class="diff-line-hover-parent">
                          <span class="diff-line-num mono">{{ line.oldLineNumber ?? '' }}</span>
                          <span class="diff-line-num mono">{{ line.newLineNumber ?? '' }}</span>
                          <span class="diff-line-prefix mono">{{ line.type === 'ADD' ? '+' : line.type === 'DELETE' ? '-' : ' ' }}</span>
                          <span class="diff-line-content">{{ lineContent(line) }}</span>
                          <button
                            v-if="pr.status === 'OPEN' || pr.status === 'DRAFT'"
                            class="inline-comment-btn"
                            title="Add comment"
                            @click.stop="openInlineComment(filePath(file), line)"
                          >
                            <Icon name="plus" :size="10" color="var(--fg-3)" />
                          </button>
                        </div>

                        <!-- Existing comments on this line -->
                        <div v-if="getLineComments(filePath(file), line).length" class="inline-thread">
                          <div v-for="c in getLineComments(filePath(file), line)" :key="c.id" class="inline-comment">
                            <div class="inline-comment-header">
                              <span class="mono inline-comment-time">{{ relativeTime(c.created) }}</span>
                              <Badge v-if="c.outdated" color="#6c7388" small>outdated</Badge>
                              <Badge v-if="c.resolved" color="#34d99a" small>resolved</Badge>
                            </div>
                            <p class="inline-comment-body">{{ c.content }}</p>
                          </div>
                          <button
                            v-if="getLineComments(filePath(file), line).some(c => !c.resolved)"
                            class="resolve-btn"
                            @click="resolveThread(filePath(file), (line.newLineNumber ?? line.oldLineNumber ?? 0))"
                          >
                            <Icon name="check" :size="11" color="#34d99a" />
                            Resolve thread
                          </button>
                        </div>

                        <!-- Inline comment form -->
                        <div v-if="isCommentTarget(filePath(file), line)" class="inline-comment-form">
                          <textarea
                            v-model="inlineCommentText"
                            class="inline-textarea"
                            placeholder="Write a comment…"
                            rows="3"
                          />
                          <div class="inline-comment-actions">
                            <Button size="sm" @click="cancelInlineComment">Cancel</Button>
                            <Button
                              primary
                              :accent="accent"
                              size="sm"
                              :disabled="inlineCommentSaving || !inlineCommentText.trim()"
                              @click="submitInlineComment"
                            >
                              {{ inlineCommentSaving ? 'Saving…' : 'Comment' }}
                            </Button>
                          </div>
                        </div>
                      </template>
                      <div v-if="hunk.totalLineCount > hunk.lines.length" class="diff-truncated mono">
                        Showing the first {{ hunk.lines.length.toLocaleString() }} of
                        {{ hunk.totalLineCount.toLocaleString() }} lines in this hunk. View the file locally for the complete diff.
                      </div>
                    </template>
                  </div>
                </div>

                <div v-if="!filteredDiffFiles.length && pr.diff.length" class="empty-msg changed-files-filter-empty">
                  No files match the current review filter.
                </div>
                <div v-if="!pr.diff.length" class="empty-msg">No file changes.</div>
              </div>
            </div>
          </div>
        </template>
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Reviews                                                         -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Reviews'">
        <div v-if="reviewsLoading" class="loading-state">Loading reviews…</div>
        <div v-else-if="reviewsError" class="diff-error">
          <Icon name="alert" :size="14" color="#ff5d6c" />
          <span>{{ reviewsError }}</span>
        </div>
        <div v-else-if="pr.reviews.length" class="reviews-full">
          <div v-for="r in pr.reviews" :key="r.id" class="review-card">
            <div class="review-card-header">
              <Badge :color="REVIEW_STATUS_COLORS[r.status] || '#6c7388'">{{ r.status.replace(/_/g, ' ').toLowerCase() }}</Badge>
              <span class="mono review-time">{{ relativeTime(r.created) }}</span>
              <Badge v-if="r.dismissedAt" color="#6c7388">Dismissed</Badge>
            </div>
            <p v-if="r.body" class="review-card-body">{{ r.body }}</p>

            <div v-if="r.comments.length" class="review-comments">
              <div v-for="c in r.comments" :key="c.id" class="review-comment">
                <div class="comment-location mono">
                  {{ c.filePath }}
                  <span v-if="c.newLineNumber">:{{ c.newLineNumber }}</span>
                  <Badge v-if="c.outdated" color="#6c7388" small>outdated</Badge>
                  <Badge v-if="c.resolved" color="#34d99a" small>resolved</Badge>
                </div>
                <p class="comment-content">{{ c.content }}</p>
                <span class="mono comment-time">{{ relativeTime(c.created) }}</span>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="empty-msg">No reviews yet.</div>

        <div class="review-actions">
          <Button
            primary
            icon="plus"
            :accent="accent"
            size="sm"
            @click="showReviewModal = true">Submit Review</Button>
        </div>
      </div>
    </template>

    <!-- ─── Review Modal ─────────────────────────────────────────────────── -->
    <Modal
      v-if="showReviewModal"
      title="Submit Review"
      icon="eye"
      :accent="accent"
      @close="showReviewModal = false">
      <div class="form-stack">
        <Select
          v-model="reviewStatus"
          :options="reviewStatusOptions"
          label="Verdict"
          :accent="accent" />
        <TextInput v-model="reviewBody" label="Comment" placeholder="Leave a comment…" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showReviewModal = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="reviewSaving"
          @click="handleSubmitReview">
          {{ reviewSaving ? 'Submitting…' : 'Submit Review' }}
        </Button>
      </template>
    </Modal>

    <!-- ─── Dependency merge confirmation ──────────────────────────────── -->
    <Modal
      v-if="showMergeDependenciesModal"
      title="Merge dependent pull requests?"
      icon="git-pull-request"
      :accent="accent"
      @close="showMergeDependenciesModal = false"
    >
      <div class="merge-plan-copy">
        <p>
          This pull request has unresolved dependencies. Bosca will merge these pull requests
          in order, ending with the current pull request.
        </p>
        <ol class="merge-plan-list">
          <li v-for="(item, index) in mergePlan" :key="item.id" class="merge-plan-item">
            <span class="merge-plan-order mono">{{ index + 1 }}</span>
            <button class="merge-plan-link" @click="openPullRequest(item)">{{ pullRequestLabel(item) }}</button>
            <Badge :color="PR_STATUS_COLORS[item.status] || '#6c7388'" small>
              {{ item.status.toLowerCase() }}
            </Badge>
            <span v-if="item.mergeable === false" class="merge-plan-blocker">conflicts</span>
          </li>
        </ol>
        <p v-if="mergePlanHasBlockers" class="merge-warning">
          Every pull request must be open and conflict-free before this merge can start.
        </p>
        <p v-else class="merge-plan-note">
          Permissions, branch protection, status checks, and mergeability are checked again before any merge starts.
        </p>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button :disabled="merging" @click="showMergeDependenciesModal = false">Cancel</Button>
        <Button
          class="merge-all-action"
          primary
          :accent="accent"
          :disabled="merging || mergePlanHasBlockers"
          @click="mergePullRequestWithDependencies"
        >
          {{ merging ? 'Merging…' : `Merge ${mergePlan.length} Pull Requests` }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
/* ─── Shared ──────────────────────────────────────────────────────────────── */
.loading-state, .empty-msg {
  color: var(--fg-3); text-align: center; padding: 48px 0; font-size: 13.5px;
}
.title-row { display: inline-flex; align-items: center; gap: 12px; }
.pr-mark {
  width: 36px; height: 36px; border-radius: 10px;
  display: inline-flex; align-items: center; justify-content: center;
  flex: 0 0 36px;
}
.section-meta { font-size: 11px; color: var(--fg-3); }

/* ─── Overview layout ─────────────────────────────────────────────────────── */
.overview-layout {
  display: grid; grid-template-columns: 1fr 320px; gap: 16px; align-items: start;
}
.overview-main { display: flex; flex-direction: column; gap: 12px; }
.overview-sidebar { display: flex; flex-direction: column; gap: 12px; }

.pr-description { padding: 14px 16px; font-size: 13.5px; line-height: 1.6; color: var(--fg-1); }
.pr-description > p { margin: 0; }
.fg-3 { color: var(--fg-3); }

/* ─── Card ────────────────────────────────────────────────────────────────── */
.card {
  background: var(--bg-1); border: 1px solid var(--line);
  border-radius: 10px; padding: 16px;
}
.card-section-title {
  font-size: 11px; color: var(--fg-3); text-transform: uppercase;
  letter-spacing: .08em; font-weight: 600; margin-bottom: 10px;
}
.card-info-list { display: flex; flex-direction: column; gap: 8px; }
.card-info-row { display: flex; justify-content: space-between; align-items: center; }
.card-info-label { font-size: 12px; color: var(--fg-3); }
.card-info-value { font-size: 12.5px; color: var(--fg-0); font-weight: 500; }

.link-btn {
  background: none; border: none; padding: 0; cursor: pointer;
  font-size: 12.5px; font-weight: 500; color: v-bind(accent);
}
.link-btn:hover { text-decoration: underline; }

/* ─── Actions ─────────────────────────────────────────────────────────────── */
.action-stack { display: flex; flex-direction: column; gap: 10px; }
.merge-row { display: flex; gap: 8px; }
.merge-warning { font-size: 12px; color: #ff5d6c; margin: 0; }
.dependency-warning { font-size: 12px; color: var(--fg-3); margin: 0; line-height: 1.45; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }

/* ─── Review list (overview) ──────────────────────────────────────────────── */
.review-list { padding: 4px 0; }
.review-row {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 16px; border-top: 1px solid var(--line);
}
.review-icon {
  width: 28px; height: 28px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center; flex: 0 0 auto;
}
.review-body { flex: 1; min-width: 0; }
.review-title { display: flex; align-items: center; gap: 8px; }
.review-comments { font-size: 11px; color: var(--fg-3); }
.review-text { font-size: 12.5px; color: var(--fg-2); margin: 4px 0 0; white-space: pre-wrap; }
.review-time { font-size: 11px; color: var(--fg-3); flex: 0 0 auto; }

/* ─── Conflicts ───────────────────────────────────────────────────────────── */
.conflict-banner {
  display: flex; align-items: flex-start; gap: 12px;
  padding: 14px 16px;
  background: color-mix(in oklch, #ff5d6c 6%, transparent);
  border: 1px solid color-mix(in oklch, #ff5d6c 18%, transparent);
  border-radius: 8px; margin: 0 16px;
}
.conflict-banner-text { flex: 1; }
.conflict-banner-title { margin: 0; font-size: 13.5px; font-weight: 600; color: #ff5d6c; }
.conflict-banner-subtitle { margin: 6px 0 0; font-size: 12.5px; color: var(--fg-2); line-height: 1.5; }
.conflict-branch {
  padding: 1px 5px; background: var(--bg-2); border-radius: 3px;
  font-size: 11px; color: var(--fg-1);
}
.conflict-file-list { padding: 8px 16px; }
.conflict-file-row {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; border-radius: 6px;
  border-bottom: 1px solid var(--line);
}
.conflict-file-row:last-child { border-bottom: none; }
.conflict-file-path { font-size: 12.5px; color: #ff5d6c; }
.conflict-instructions {
  margin: 0 16px 16px; background: var(--bg-0); border: 1px solid var(--line); border-radius: 8px;
  overflow: hidden;
}
.conflict-instructions-header {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 14px; border-bottom: 1px solid var(--line);
  font-size: 12.5px; font-weight: 500; color: var(--fg-1);
}
.conflict-cmd {
  margin: 0; padding: 12px 14px; font-size: 12px; line-height: 1.7;
  color: var(--fg-2); overflow-x: auto; white-space: pre;
}

/* ─── Diff viewer ─────────────────────────────────────────────────────────── */
.diff-error {
  display: flex; align-items: center; gap: 8px;
  padding: 14px 16px; font-size: 13px; color: #ff5d6c;
  background: color-mix(in oklch, #ff5d6c 6%, transparent);
  border: 1px solid color-mix(in oklch, #ff5d6c 20%, transparent);
  border-radius: 10px;
}
.review-toolbar {
  position: sticky; top: 0; z-index: 8;
  display: grid; grid-template-columns: minmax(180px, 260px) minmax(120px, 1fr) auto;
  align-items: center; gap: 14px; margin-bottom: 12px; padding: 11px 14px;
  background: color-mix(in oklch, var(--bg-1) 94%, transparent);
  border: 1px solid var(--line); border-radius: 10px; backdrop-filter: blur(12px);
}
.review-progress-copy { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.review-progress-title { color: var(--fg-1); font-size: 12.5px; font-weight: 600; }
.review-progress-percent { color: var(--fg-3); font-size: 10.5px; }
.review-progress-track {
  height: 6px; overflow: hidden; background: var(--bg-3); border-radius: 999px;
}
.review-progress-fill {
  display: block; height: 100%; border-radius: inherit; background: v-bind(accent);
  transition: width .2s ease;
}
.review-toolbar-actions { display: flex; align-items: center; gap: 6px; }
.review-toolbar-button {
  padding: 5px 8px; color: var(--fg-2); background: transparent; border: 1px solid var(--line);
  border-radius: 6px; cursor: pointer; font: inherit; font-size: 11px; white-space: nowrap;
}
.review-toolbar-button:hover { color: var(--fg-0); background: var(--bg-2); }
.review-toolbar-primary { color: v-bind(accent); border-color: color-mix(in oklch, v-bind(accent) 42%, var(--line)); }
.review-workspace {
  display: grid; grid-template-columns: minmax(220px, 280px) minmax(0, 1fr);
  gap: 14px; align-items: start;
}
.changed-files-sidebar {
  position: sticky; top: 62px; max-height: calc(100vh - 94px); min-width: 0;
  display: flex; flex-direction: column; overflow: hidden;
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
}
.changed-files-sidebar-header { padding: 12px 12px 8px; border-bottom: 1px solid var(--line); }
.changed-files-sidebar-header > div:first-child {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  margin-bottom: 10px; color: var(--fg-1); font-size: 12.5px;
}
.changed-files-sidebar-header strong { font-weight: 600; }
.changed-files-sidebar-header > div:first-child span { color: var(--fg-3); font-size: 10.5px; }
.review-file-filters {
  display: grid; grid-template-columns: 1fr 1fr; padding: 2px;
  background: var(--bg-0); border: 1px solid var(--line); border-radius: 7px;
}
.review-file-filters button {
  padding: 4px 6px; background: transparent; border: none; border-radius: 5px;
  color: var(--fg-3); cursor: pointer; font: inherit; font-size: 10.5px;
}
.review-file-filters button:hover { color: var(--fg-1); }
.review-file-filters button.active { color: var(--fg-0); background: var(--bg-3); }
.changed-file-search {
  display: flex; align-items: center; gap: 7px; margin: 9px 10px;
  padding: 6px 8px; background: var(--bg-0); border: 1px solid var(--line); border-radius: 7px;
}
.changed-file-search:focus-within { border-color: v-bind(accent); }
.changed-file-search input {
  width: 100%; min-width: 0; padding: 0; background: transparent; border: none; outline: none;
  color: var(--fg-1); font: inherit; font-size: 11.5px;
}
.changed-file-search input::placeholder { color: var(--fg-3); }
.changed-files-empty { padding: 22px 8px; color: var(--fg-3); text-align: center; font-size: 11px; }
.review-diff-column { min-width: 0; }
.diff-summary {
  display: flex; align-items: center; gap: 8px; margin: 1px 0 10px;
  font-size: 12px; color: var(--fg-2);
}
.diff-summary-filter { color: var(--fg-3); }
.diff-files { display: flex; flex-direction: column; gap: 8px; }
.diff-file {
  scroll-margin-top: 62px;
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  overflow: hidden;
}
.diff-file-viewed { border-color: color-mix(in oklch, #34d99a 32%, var(--line)); }
.diff-file-header {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 10px 8px 12px;
}
.diff-file-header:hover { background: var(--bg-2); }
.diff-file-title {
  min-width: 0; flex: 1; display: flex; align-items: center; gap: 8px;
  padding: 2px 4px; background: transparent; border: none; color: inherit; cursor: pointer; text-align: left;
}
.diff-file-path { font-size: 13px; color: var(--fg-0); flex: 1; }
.diff-old-path { font-size: 11px; color: var(--fg-3); }
.diff-file-stats { display: inline-flex; align-items: center; gap: 5px; font-size: 10.5px; }
.diff-stat-add { color: #34d99a; }
.diff-stat-delete { color: #ff5d6c; }
.viewed-file-toggle {
  display: inline-flex; align-items: center; gap: 6px; padding: 4px 6px;
  color: var(--fg-2); border-radius: 5px; cursor: pointer; font-size: 11px; user-select: none;
}
.viewed-file-toggle:hover { background: var(--bg-3); color: var(--fg-0); }
.viewed-file-toggle input { width: 14px; height: 14px; margin: 0; accent-color: #34d99a; cursor: pointer; }
.changed-files-filter-empty { border: 1px dashed var(--line); border-radius: 10px; }

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
.diff-truncated {
  padding: 10px 16px; font-size: 11px; color: var(--fg-3);
  background: var(--bg-2); border-top: 1px solid var(--line);
}

@media (max-width: 980px) {
  .review-toolbar { position: static; grid-template-columns: 1fr; gap: 9px; }
  .review-toolbar-actions { flex-wrap: wrap; }
  .review-workspace { grid-template-columns: 1fr; }
  .changed-files-sidebar { position: static; max-height: 280px; }
}

@media (max-width: 720px) {
  .overview-layout { grid-template-columns: 1fr; }
  .diff-file-header { align-items: flex-start; flex-wrap: wrap; }
  .diff-file-title { flex-basis: calc(100% - 72px); }
  .diff-file-path { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .inline-thread, .inline-comment-form { padding-left: 16px; }
}

/* ─── Reviews tab ─────────────────────────────────────────────────────────── */
.reviews-full { display: flex; flex-direction: column; gap: 12px; }
.review-card {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  padding: 16px;
}
.review-card-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.review-card-body { font-size: 13px; color: var(--fg-1); line-height: 1.55; margin: 0 0 12px; }

.review-comments {
  display: flex; flex-direction: column; gap: 8px;
  border-top: 1px solid var(--line); padding-top: 12px;
}
.review-comment {
  padding: 10px 14px; background: var(--bg-0); border-radius: 8px;
  border: 1px solid var(--line);
}
.comment-location {
  font-size: 11px; color: var(--fg-3); margin-bottom: 6px;
  display: flex; align-items: center; gap: 6px;
}
.comment-content { font-size: 13px; color: var(--fg-1); line-height: 1.55; margin: 0; }
.comment-time { font-size: 10px; color: var(--fg-3); display: block; margin-top: 6px; }

.review-actions { margin-top: 16px; display: flex; justify-content: flex-end; }

/* ─── Inline comments ────────────────────────────────────────────────────── */
.diff-line-hover-parent { position: relative; }
.inline-comment-btn {
  position: absolute; right: 8px; top: 50%; transform: translateY(-50%);
  width: 20px; height: 20px; border-radius: 4px;
  background: var(--bg-3); border: 1px solid var(--line);
  display: none; align-items: center; justify-content: center;
  cursor: pointer;
}
.diff-line-hover-parent:hover .inline-comment-btn { display: flex; }
.inline-comment-btn:hover { background: v-bind(accent); border-color: v-bind(accent); }
.inline-comment-btn:hover :deep(svg) { color: #fff !important; }

.inline-thread {
  padding: 8px 16px 8px 128px; background: var(--bg-0);
  border-top: 1px solid var(--line); border-bottom: 1px solid var(--line);
}
.inline-comment {
  padding: 8px 12px; background: var(--bg-1); border: 1px solid var(--line);
  border-radius: 6px; margin-bottom: 6px;
}
.inline-comment:last-of-type { margin-bottom: 0; }
.inline-comment-header {
  display: flex; align-items: center; gap: 6px; margin-bottom: 4px;
}
.inline-comment-time { font-size: 10px; color: var(--fg-3); }
.inline-comment-body { margin: 0; font-size: 12.5px; color: var(--fg-1); line-height: 1.5; white-space: pre-wrap; }

.resolve-btn {
  display: flex; align-items: center; gap: 5px; margin-top: 8px;
  background: none; border: none; padding: 4px 8px; border-radius: 4px;
  font-size: 11px; color: #34d99a; cursor: pointer; font-weight: 500;
}
.resolve-btn:hover { background: color-mix(in oklch, #34d99a 10%, transparent); }

.inline-comment-form {
  padding: 10px 16px 10px 128px; background: var(--bg-0);
  border-top: 1px solid var(--line); border-bottom: 1px solid var(--line);
}
.inline-textarea {
  width: 100%; padding: 8px 10px; font-size: 13px; line-height: 1.5;
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 6px;
  color: var(--fg-0); resize: vertical; font-family: inherit; outline: none;
}
.inline-textarea:focus { border-color: v-bind(accent); }
.inline-comment-actions {
  display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px;
}

/* ─── Assignees ──────────────────────────────────────────────────────────── */
.assignee-list { display: flex; flex-direction: column; gap: 6px; }
.assignee-row {
  display: flex; align-items: center; gap: 8px;
  padding: 4px 0;
}
.assignee-name { font-size: 12.5px; color: var(--fg-0); flex: 1; }
.assignee-remove {
  background: none; border: none; padding: 2px; cursor: pointer;
  border-radius: 4px; display: flex; align-items: center; justify-content: center;
  opacity: 0; transition: opacity .15s;
}
.assignee-row:hover .assignee-remove { opacity: 1; }
.assignee-remove:hover { background: var(--bg-3); }
.empty-assignees { font-size: 12px; color: var(--fg-3); padding: 2px 0; }
.assignee-search { margin-top: 8px; }
.sidebar-cancel-link {
  background: none; border: none; padding: 0; margin-top: 4px;
  font-size: 11px; color: var(--fg-3); cursor: pointer;
}
.sidebar-cancel-link:hover { color: var(--fg-1); }
.add-assignee-btn {
  display: flex; align-items: center; gap: 6px;
  background: none; border: none; padding: 6px 0 0; cursor: pointer;
  font-size: 12px; color: v-bind(accent); font-weight: 500;
}
.add-assignee-btn:hover { text-decoration: underline; }

/* ─── Pull request dependencies ───────────────────────────────────────────── */
.dependency-list { display: flex; flex-direction: column; gap: 6px; }
.dependency-row { display: flex; align-items: center; gap: 4px; }
.dependency-link {
  min-width: 0; flex: 1; display: flex; align-items: center; justify-content: space-between; gap: 8px;
  background: none; border: none; padding: 4px 0; color: var(--fg-1); cursor: pointer;
  font-size: 12px; text-align: left;
}
.dependency-link span:first-child { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dependency-link:hover span:first-child { color: v-bind(accent); text-decoration: underline; }
.dependency-remove {
  flex: 0 0 auto; display: flex; align-items: center; justify-content: center;
  background: none; border: none; border-radius: 4px; padding: 3px; cursor: pointer; opacity: 0;
}
.dependency-row:hover .dependency-remove { opacity: 1; }
.dependency-remove:hover { background: var(--bg-3); }
.dependency-remove:disabled { cursor: not-allowed; opacity: .4; }
.dependency-divider { border-top: 1px solid var(--line); margin: 12px 0; }
.dependent-title { margin-bottom: 6px; }
.dependent-link { width: 100%; }

/* ─── Dependency merge confirmation ──────────────────────────────────────── */
.merge-plan-copy { display: flex; flex-direction: column; gap: 14px; }
.merge-plan-copy > p { margin: 0; color: var(--fg-2); font-size: 13px; line-height: 1.5; }
.merge-plan-list {
  list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column;
  border: 1px solid var(--line); border-radius: 8px; overflow: hidden;
}
.merge-plan-item {
  display: flex; align-items: center; gap: 8px; padding: 9px 10px;
  background: var(--bg-0); border-bottom: 1px solid var(--line);
}
.merge-plan-item:last-child { border-bottom: none; }
.merge-plan-order {
  width: 20px; height: 20px; flex: 0 0 20px; display: inline-flex; align-items: center; justify-content: center;
  border-radius: 50%; background: var(--bg-3); color: var(--fg-3); font-size: 10px;
}
.merge-plan-link {
  min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  background: none; border: none; padding: 0; color: var(--fg-1); cursor: pointer; text-align: left;
  font-size: 12.5px;
}
.merge-plan-link:hover { color: v-bind(accent); text-decoration: underline; }
.merge-plan-blocker { color: var(--err); font-size: 11px; }
.merge-plan-note { color: var(--fg-3) !important; font-size: 11.5px !important; }

/* ─── Forms ───────────────────────────────────────────────────────────────── */
.form-stack { display: flex; flex-direction: column; gap: 14px; }
</style>
