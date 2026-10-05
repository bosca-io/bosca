<script setup lang="ts">
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'
import { detectCodeLanguage } from '~/utils/detectCodeLanguage'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation, query } = useGraphQL()
const toast = useToast()

// File-edit commits are attributed to the active profile (same path used by
// the analytics-query → git push-back flow).
const auth = import.meta.client ? useAuth() : null
const profile = auth?.profile ?? null
const commitAuthor = computed(() => resolveGitCommitAuthor(profile?.value))

const repoId = computed(() => route.params.id as string)
const validTabs = ['Code', 'Commits', 'Branches', 'Tags', 'Pull Requests', 'Pipelines', 'Statuses', 'Secrets']
const DEFAULT_TAB = 'Code'

/**
 * Navigation state (tab, ref, path, file) lives in the URL so that refreshing
 * the page restores the same view and the browser back button steps through
 * the in-repo history.
 *
 * Each writable navigation state is exposed as a `computed({ get, set })` over
 * `route.query`. Setters call `pushNavQuery`, which merges the patch with the
 * existing query and pushes a new history entry.
 *
 * Patch semantics: an explicit `undefined` value removes that key from the
 * URL; an absent key preserves whatever's already there.
 */
type NavPatch = {
  tab?: string | undefined
  ref?: string | undefined
  path?: string | undefined
  file?: string | undefined
}
const NAV_KEYS = ['tab', 'ref', 'path', 'file'] as const

function pushNavQuery(patch: NavPatch) {
  const next: Record<string, string> = {}
  // Preserve any non-nav query params untouched.
  for (const [k, v] of Object.entries(route.query)) {
    if ((NAV_KEYS as readonly string[]).includes(k)) continue
    if (typeof v === 'string') next[k] = v
    else if (Array.isArray(v) && typeof v[0] === 'string') next[k] = v[0]
  }
  for (const key of NAV_KEYS) {
    const value = key in patch ? patch[key] : (route.query[key] as string | undefined)
    if (value) next[key] = value
  }
  void router.push({ query: next })
}

const activeTab = computed<string>({
  get: () => {
    const t = route.query.tab
    return typeof t === 'string' && validTabs.includes(t) ? t : DEFAULT_TAB
  },
  set: (v: string) => pushNavQuery({ tab: v === DEFAULT_TAB ? undefined : v }),
})

// ─── Repository ──────────────────────────────────────────────────────────────
interface Repository {
  id: string; name: string; slug: string; description: string | null
  visibility: string; contentType: string | null; defaultBranch: string
  archived: boolean; deleted: boolean; diskSizeBytes: number
  forkedFromId: string | null; ownerId: string
  created: string; updated: string
  configuration: {
    deleteBranchOnMerge: boolean; mergeStrategies: string[]
    requireSignedCommits: boolean; squashByDefault: boolean
  }
}

const { data: repoData, status: repoStatus, refresh: refreshRepo } = useAsyncQuery<{
  git: { repositoryById: Repository | null }
}>('git-repo-detail', gql`
  query GetRepo($id: UUID!) {
    git { repositoryById(id: $id) {
      id name slug description visibility contentType defaultBranch
      archived deleted diskSizeBytes forkedFromId ownerId
      created updated
      configuration { deleteBranchOnMerge mergeStrategies requireSignedCommits squashByDefault }
    } }
  }
`, { id: repoId }, { server: false })

const repo = computed(() => repoData.value?.git?.repositoryById ?? null)

// ─── Owner profile (for clone URL) ─────────────────────────────────────────
const ownerSlug = ref('')

watch(repo, async (r) => {
  if (!r) return
  try {
    const result = await query<{ profiles: { profile: { slug: string } | null } }>(gql`
      query OwnerProfile($id: UUID!) {
        profiles { profile(id: $id) { slug } }
      }
    `, { id: r.ownerId })
    ownerSlug.value = result.profiles?.profile?.slug ?? ''
  } catch { /* ignore */ }
}, { immediate: true })
const isLoading = computed(() => repoStatus.value === 'pending')

// ─── Stats ───────────────────────────────────────────────────────────────────
interface RepoStats {
  branchCount: number; commitCount: number; contributorCount: number
  diskSizeBytes: number; tagCount: number
}

const stats = ref<RepoStats | null>(null)

watch(repo, async (r) => {
  if (!r) return
  try {
    const result = await query<{ git: { stats: RepoStats } }>(gql`
      query RepoStats($repositoryId: UUID!) {
        git { stats(repositoryId: $repositoryId) {
          branchCount commitCount contributorCount diskSizeBytes tagCount
        } }
      }
    `, { repositoryId: r.id })
    stats.value = result.git?.stats ?? null
  } catch { /* ignore */ }
}, { immediate: true })

// ─── Current branch/ref ─────────────────────────────────────────────────────
// URL-as-truth. When the URL lacks `?ref=`, we fall back to the repo's default
// branch in the getter so we never have to write the default into the URL.
const currentRef = computed<string>({
  get: () => (route.query.ref as string) || repo.value?.defaultBranch || '',
  set: (v: string) => {
    const isDefault = !!repo.value && v === repo.value.defaultBranch
    // Switching branch always resets the path/file — they're branch-scoped.
    pushNavQuery({ ref: isDefault ? undefined : v, path: undefined, file: undefined })
  },
})

// ─── Branches ────────────────────────────────────────────────────────────────
interface BranchInfo {
  name: string; sha: string; ahead: number; behind: number
}

const branches = ref<BranchInfo[]>([])

async function loadBranches(repositoryId: string) {
  try {
    const result = await query<{ git: { branches: BranchInfo[] } }>(gql`
      query Branches($repositoryId: UUID!) {
        git { branches(repositoryId: $repositoryId) { name sha ahead behind } }
      }
    `, { repositoryId })
    branches.value = result.git?.branches ?? []
  } catch { /* ignore */ }
}

watch(repo, async (r) => {
  if (r) await loadBranches(r.id)
}, { immediate: true })

// Deleting a branch confirms first; the server refuses the default branch and protected branches.
const deleteBranchTarget = ref<BranchInfo | null>(null)
const deleteBranchLoading = ref(false)

async function confirmDeleteBranch() {
  const target = deleteBranchTarget.value
  if (!target || !repo.value) return
  deleteBranchLoading.value = true
  try {
    await mutation(gql`
      mutation DeleteBranch($repositoryId: UUID!, $branchName: String!) {
        git { deleteBranch(repositoryId: $repositoryId, branchName: $branchName) }
      }
    `, { repositoryId: repo.value.id, branchName: target.name })
    deleteBranchTarget.value = null
    toast.success(`Branch '${target.name}' deleted`)
    await loadBranches(repo.value.id)
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to delete the branch')
  }
  finally {
    deleteBranchLoading.value = false
  }
}

const branchOptions = computed(() => branches.value.map(b => ({ value: b.name, label: b.name })))

// ─── Tags ────────────────────────────────────────────────────────────────────
interface TagInfo {
  name: string; sha: string; isAnnotated: boolean; message: string | null
  taggerName: string | null; taggerEmail: string | null; targetSha: string | null
}

const tags = ref<TagInfo[]>([])

async function loadTags(repositoryId: string) {
  try {
    const result = await query<{ git: { tags: TagInfo[] } }>(gql`
      query Tags($repositoryId: UUID!) {
        git { tags(repositoryId: $repositoryId) {
          name sha isAnnotated message taggerName taggerEmail targetSha
        } }
      }
    `, { repositoryId })
    tags.value = result.git?.tags ?? []
  } catch { /* ignore */ }
}

watch(repo, async (r) => {
  if (r) await loadTags(r.id)
}, { immediate: true })

// Deleting a tag is destructive (releases reference tags), so it confirms first.
const deleteTagTarget = ref<TagInfo | null>(null)
const deleteTagLoading = ref(false)

async function confirmDeleteTag() {
  const target = deleteTagTarget.value
  if (!target || !repo.value) return
  deleteTagLoading.value = true
  try {
    await mutation(gql`
      mutation DeleteTag($repositoryId: UUID!, $tag: String!) {
        git { deleteTag(repositoryId: $repositoryId, tag: $tag) }
      }
    `, { repositoryId: repo.value.id, tag: target.name })
    deleteTagTarget.value = null
    toast.success(`Tag '${target.name}' deleted`)
    await loadTags(repo.value.id)
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to delete the tag')
  }
  finally {
    deleteTagLoading.value = false
  }
}

// ─── File tree ───────────────────────────────────────────────────────────────
interface TreeEntry {
  name: string; path: string; type: string; sha: string; size: number | null; mode: number
}

const currentPath = computed<string>({
  get: () => (route.query.path as string) || '',
  // Navigating into a folder always closes any open file.
  set: (v: string) => pushNavQuery({ path: v || undefined, file: undefined }),
})
const treeEntries = ref<TreeEntry[]>([])
const treeLoading = ref(false)

// When only `?file=` is in the URL (e.g. arriving from a deep link), derive
// the breadcrumb directory from the file's parent so "click `src` to go up"
// still works.
const pathSegments = computed(() => {
  const file = (route.query.file as string | undefined) ?? ''
  const base = file
    ? (file.includes('/') ? file.substring(0, file.lastIndexOf('/')) : '')
    : currentPath.value
  if (!base) return []
  return base.split('/').map((seg, i, arr) => ({
    name: seg,
    path: arr.slice(0, i + 1).join('/'),
  }))
})

async function loadTree() {
  if (!repo.value || !currentRef.value) return
  treeLoading.value = true
  try {
    const result = await query<{ git: { tree: TreeEntry[] } }>(gql`
      query Tree($repositoryId: UUID!, $ref: String, $path: String) {
        git { tree(repositoryId: $repositoryId, ref: $ref, path: $path) {
          name path type sha size mode
        } }
      }
    `, { repositoryId: repo.value.id, ref: currentRef.value, path: currentPath.value || null })
    const entries = result.git?.tree ?? []
    entries.sort((a, b) => {
      if (a.type === 'TREE' && b.type !== 'TREE') return -1
      if (a.type !== 'TREE' && b.type === 'TREE') return 1
      return a.name.localeCompare(b.name)
    })
    treeEntries.value = entries
  } catch { treeEntries.value = [] }
  finally { treeLoading.value = false }
}

watch([repo, currentRef, currentPath], () => {
  if (!repo.value || !currentRef.value) return
  loadTree()
}, { immediate: true })

// ─── File viewer ─────────────────────────────────────────────────────────────
interface BlobData {
  content: string | null; isBinary: boolean; mimeType: string | null; sha: string; size: number
}

const viewingFile = computed<string | null>({
  get: () => (route.query.file as string) || null,
  set: (v: string | null) => pushNavQuery({ file: v ?? undefined }),
})
const fileContent = ref<BlobData | null>(null)
const fileLoading = ref(false)

// Load the blob whenever the URL points to one. Because `viewingFile` is
// reactive to the URL, the back button and direct deep links flow through
// here automatically.
watch([repo, currentRef, viewingFile], async ([r, ref, file]) => {
  if (!r || !ref || !file) {
    fileContent.value = null
    return
  }
  fileLoading.value = true
  try {
    const result = await query<{ git: { blob: BlobData | null } }>(gql`
      query Blob($repositoryId: UUID!, $ref: String!, $path: String!) {
        git { blob(repositoryId: $repositoryId, ref: $ref, path: $path) {
          content isBinary mimeType sha size
        } }
      }
    `, { repositoryId: r.id, ref, path: file })
    fileContent.value = result.git?.blob ?? null
  } catch { fileContent.value = null }
  finally { fileLoading.value = false }
}, { immediate: true })

const detectedFileLanguage = computed(() => detectCodeLanguage(viewingFile.value ?? ''))

// ─── File edit / create / rename / delete ──────────────────────────────────
// Edit/create state is intentionally NOT persisted in the URL — it's a
// per-session modal mode on top of a file view. The unsaved-changes guard
// below blocks navigation when these buffers are dirty.
const isEditing = ref(false)
const editBuffer = ref('')
const commitMessage = ref('')
const isCommitting = ref(false)

// New-file mode (replaces the tree view when active).
const creatingFile = ref(false)
const newFileName = ref('')

// Rename modal.
const renameOriginalPath = ref<string | null>(null)
const renameNewPath = ref('')

// New-folder modal.
const showNewFolder = ref(false)
const newFolderName = ref('')

const isEditDirty = computed(() =>
  isEditing.value && editBuffer.value !== (fileContent.value?.content ?? ''),
)
const isCreatingDirty = computed(() =>
  creatingFile.value && (newFileName.value !== '' || editBuffer.value !== ''),
)
/** Any unsaved buffer that nav should warn about. */
const hasUnsavedChanges = computed(() => isEditDirty.value || isCreatingDirty.value)

function defaultCommitMessage(path: string, verb: 'Update' | 'Create' | 'Rename' | 'Delete'): string {
  const name = path.split('/').pop() || path
  return `${verb} ${name}`
}

function buildCommitInput(path: string, content: string, message: string) {
  return {
    repositoryId: repo.value!.id,
    branch: currentRef.value,
    path,
    content,
    message: message.trim(),
    authorName: commitAuthor.value.authorName,
    authorEmail: commitAuthor.value.authorEmail,
  }
}

const commitFileGql = gql`
  mutation CommitFile($input: CommitFileInput!) {
    git { commitFile(input: $input) { commitSha branch path } }
  }
`
const deleteFileGql = gql`
  mutation DeleteFile(
    $repositoryId: UUID!, $branch: String!, $path: String!,
    $authorName: String!, $authorEmail: String!,
  ) {
    git { deleteFile(
      repositoryId: $repositoryId, branch: $branch, path: $path,
      authorName: $authorName, authorEmail: $authorEmail,
    ) { commitSha branch path } }
  }
`

function startEdit() {
  if (!fileContent.value || fileContent.value.isBinary) return
  editBuffer.value = fileContent.value.content ?? ''
  commitMessage.value = defaultCommitMessage(viewingFile.value ?? '', 'Update')
  isEditing.value = true
}

function cancelEdit() {
  isEditing.value = false
  editBuffer.value = ''
  commitMessage.value = ''
}

async function saveEdit() {
  if (!repo.value || !viewingFile.value || !currentRef.value) return
  if (!commitMessage.value.trim()) {
    toast.error('Commit message is required')
    return
  }
  isCommitting.value = true
  try {
    const result = await mutation<{ git: { commitFile: { commitSha: string } } }>(
      commitFileGql,
      { input: buildCommitInput(viewingFile.value, editBuffer.value, commitMessage.value) },
    )
    const sha = result.git?.commitFile?.commitSha
    toast.success(sha ? `Committed ${sha.slice(0, 7)}` : 'File committed')
    isEditing.value = false
    commitMessage.value = ''
    await reloadFile()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to commit')
  } finally {
    isCommitting.value = false
  }
}

async function reloadFile() {
  if (!repo.value || !currentRef.value || !viewingFile.value) return
  fileLoading.value = true
  try {
    const result = await query<{ git: { blob: BlobData | null } }>(gql`
      query Blob($repositoryId: UUID!, $ref: String!, $path: String!) {
        git { blob(repositoryId: $repositoryId, ref: $ref, path: $path) {
          content isBinary mimeType sha size
        } }
      }
    `, { repositoryId: repo.value.id, ref: currentRef.value, path: viewingFile.value })
    fileContent.value = result.git?.blob ?? null
  } catch { /* keep stale content */ }
  finally { fileLoading.value = false }
}

// ─── New file ──────────────────────────────────────────────────────────────
function startCreateFile() {
  if (!repo.value) return
  creatingFile.value = true
  newFileName.value = ''
  editBuffer.value = ''
  commitMessage.value = ''
}

function cancelCreateFile() {
  creatingFile.value = false
  newFileName.value = ''
  editBuffer.value = ''
  commitMessage.value = ''
}

function newFileFullPath(): string {
  const trimmed = newFileName.value.trim().replace(/^\/+/, '')
  if (!trimmed) return ''
  return currentPath.value ? `${currentPath.value}/${trimmed}` : trimmed
}

async function commitNewFile() {
  if (!repo.value || !currentRef.value) return
  const path = newFileFullPath()
  if (!path) { toast.error('File name is required'); return }
  const message = commitMessage.value.trim() || defaultCommitMessage(path, 'Create')
  isCommitting.value = true
  try {
    const result = await mutation<{ git: { commitFile: { commitSha: string } } }>(
      commitFileGql,
      { input: buildCommitInput(path, editBuffer.value, message) },
    )
    const sha = result.git?.commitFile?.commitSha
    toast.success(sha ? `Committed ${sha.slice(0, 7)}` : 'File created')
    creatingFile.value = false
    newFileName.value = ''
    editBuffer.value = ''
    commitMessage.value = ''
    // Navigate to the newly created file.
    pushNavQuery({ file: path })
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create file')
  } finally {
    isCommitting.value = false
  }
}

// ─── New folder ────────────────────────────────────────────────────────────
function openNewFolder() {
  newFolderName.value = ''
  showNewFolder.value = true
}

async function commitNewFolder() {
  if (!repo.value || !currentRef.value) return
  const name = newFolderName.value.trim().replace(/^\/+|\/+$/g, '')
  if (!name) { toast.error('Folder name is required'); return }
  // Git has no empty-folder concept — write a .gitkeep inside it.
  const path = (currentPath.value ? `${currentPath.value}/${name}` : name) + '/.gitkeep'
  isCommitting.value = true
  try {
    await mutation(commitFileGql, {
      input: buildCommitInput(path, '', `Add ${name}/`),
    })
    toast.success(`Folder ${name}/ created`)
    showNewFolder.value = false
    newFolderName.value = ''
    // Navigate into the new folder.
    pushNavQuery({ path: currentPath.value ? `${currentPath.value}/${name}` : name, file: undefined })
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create folder')
  } finally {
    isCommitting.value = false
  }
}

// ─── Rename file ───────────────────────────────────────────────────────────
function openRename(path: string) {
  renameOriginalPath.value = path
  renameNewPath.value = path
  commitMessage.value = ''
}

function cancelRename() {
  renameOriginalPath.value = null
  renameNewPath.value = ''
  commitMessage.value = ''
}

async function commitRename() {
  if (!repo.value || !currentRef.value || !renameOriginalPath.value) return
  const oldPath = renameOriginalPath.value
  const newPath = renameNewPath.value.trim().replace(/^\/+/, '')
  if (!newPath) { toast.error('New path is required'); return }
  if (newPath === oldPath) { toast.error('Path is unchanged'); return }
  const message = commitMessage.value.trim() || `Rename ${oldPath} → ${newPath}`

  isCommitting.value = true
  try {
    // Need the current content to commit at the new path.
    const blobResult = await query<{ git: { blob: BlobData | null } }>(gql`
      query RenameBlob($repositoryId: UUID!, $ref: String!, $path: String!) {
        git { blob(repositoryId: $repositoryId, ref: $ref, path: $path) {
          content isBinary
        } }
      }
    `, { repositoryId: repo.value.id, ref: currentRef.value, path: oldPath })
    if (blobResult.git?.blob?.isBinary) {
      toast.error('Renaming binary files is not supported')
      return
    }
    const content = blobResult.git?.blob?.content ?? ''

    // Commit the new file first; only delete the old once the new write
    // succeeds, so we never leave the user with neither copy.
    await mutation(commitFileGql, { input: buildCommitInput(newPath, content, message) })
    await mutation(deleteFileGql, {
      repositoryId: repo.value.id,
      branch: currentRef.value,
      path: oldPath,
      authorName: commitAuthor.value.authorName,
      authorEmail: commitAuthor.value.authorEmail,
    })
    toast.success('Renamed')
    renameOriginalPath.value = null
    renameNewPath.value = ''
    commitMessage.value = ''
    // If the user was viewing the renamed file, follow it. Otherwise just
    // refresh the tree by re-pushing the current path.
    if (viewingFile.value === oldPath) {
      pushNavQuery({ file: newPath })
    } else {
      await loadTree()
    }
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to rename')
  } finally {
    isCommitting.value = false
  }
}

// ─── Shared confirm modal ──────────────────────────────────────────────────
// One promise-resolved dialog for destructive confirmations and the unsaved-
// changes guard, so we never reach for window.confirm in this page.
interface ConfirmOpts {
  title: string
  message: string
  confirmLabel?: string
  danger?: boolean
}
const confirmDialog = ref<(ConfirmOpts & { resolve: (ok: boolean) => void }) | null>(null)

function askConfirm(opts: ConfirmOpts): Promise<boolean> {
  return new Promise((resolve) => {
    confirmDialog.value = { ...opts, resolve }
  })
}

function resolveConfirm(ok: boolean) {
  confirmDialog.value?.resolve(ok)
  confirmDialog.value = null
}

// ─── Delete file ───────────────────────────────────────────────────────────
async function deleteEntry(path: string) {
  if (!repo.value || !currentRef.value) return
  const ok = await askConfirm({
    title: 'Delete file?',
    message: `Delete ${path}? This creates a commit on ${currentRef.value} and cannot be undone from this UI.`,
    confirmLabel: 'Delete',
    danger: true,
  })
  if (!ok) return
  isCommitting.value = true
  try {
    await mutation(deleteFileGql, {
      repositoryId: repo.value.id,
      branch: currentRef.value,
      path,
      authorName: commitAuthor.value.authorName,
      authorEmail: commitAuthor.value.authorEmail,
    })
    toast.success(`Deleted ${path.split('/').pop()}`)
    if (viewingFile.value === path) {
      // Drop the file from the URL so we land back in the tree.
      pushNavQuery({ file: undefined })
    } else {
      await loadTree()
    }
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to delete')
  } finally {
    isCommitting.value = false
  }
}

// Any URL-driven change of the file being viewed exits edit/create mode.
// Pairs with the URL-as-truth navigation model — back-button, branch switch,
// breadcrumb click, etc. all flow through here. The unsaved-changes guard
// below catches dirty state *before* the URL changes, so this watcher only
// fires after the user has either confirmed discard or had no dirty buffer.
watch(viewingFile, () => {
  if (isEditing.value) cancelEdit()
  if (creatingFile.value) cancelCreateFile()
})

// ─── Unsaved-changes guard ─────────────────────────────────────────────────
// Blocks router navigations and tab-close when an edit/create buffer is dirty.
// Browser back/forward also flows through `router.beforeEach` in Vue Router 4.
function onBeforeUnload(e: BeforeUnloadEvent) {
  if (hasUnsavedChanges.value) {
    e.preventDefault()
    // Older Chrome needs returnValue set to show the prompt.
    e.returnValue = ''
  }
}

let removeRouteGuard: (() => void) | null = null

onMounted(() => {
  removeRouteGuard = router.beforeEach(() => {
    if (!hasUnsavedChanges.value) return true
    return askConfirm({
      title: 'Discard unsaved changes?',
      message: 'You have unsaved changes that will be lost if you leave this page.',
      confirmLabel: 'Discard',
      danger: true,
    })
  })
  window.addEventListener('beforeunload', onBeforeUnload)
})

onBeforeUnmount(() => {
  removeRouteGuard?.()
  removeRouteGuard = null
  window.removeEventListener('beforeunload', onBeforeUnload)
})

// Auto-size to whichever buffer is active so the editor grows with the
// content the user is actually looking at: their edit/create draft while
// editing, the on-disk blob while viewing. CodeEditor enforces its own 80-px
// floor for ~1-line files, so view mode can hug short content tightly.
const fileViewerHeight = computed(() => {
  const usingBuffer = isEditing.value || creatingFile.value
  const content = usingBuffer ? editBuffer.value : (fileContent.value?.content ?? '')
  const lineCount = content ? content.split('\n').length : 1
  const lineHeight = 20.8
  const padding = 24
  const max = 800
  // Creating starts with an empty buffer — give the user breathing room to
  // start typing before the height kicks in from line count.
  const min = creatingFile.value ? 320 : 0
  return `${Math.min(max, Math.max(min, Math.round(lineCount * lineHeight + padding)))}px`
})

function openFile(entry: TreeEntry) {
  // Both branches push a new history entry via the computed setters, so the
  // browser back button can step through tree navigation and file opens.
  if (entry.type === 'TREE') {
    currentPath.value = entry.path
  } else {
    viewingFile.value = entry.path
  }
}

function navigateToPath(path: string) {
  currentPath.value = path
}

function navigateToRoot() {
  currentPath.value = ''
}

// ─── Commits ─────────────────────────────────────────────────────────────────
interface CommitInfo {
  sha: string; message: string
  authorName: string; authorEmail: string; authorDate: string
  committerName: string; committerDate: string
  parentShas: string[]
}

const PAGE_SIZE = 50
const commits = ref<CommitInfo[]>([])
const commitsLoading = ref(false)
const commitsOffset = ref(0)
const commitsHasMore = ref(false)

async function loadCommits(offset: number = 0) {
  if (!repo.value || !currentRef.value) return
  commitsLoading.value = true
  try {
    const result = await query<{ git: { commits: CommitInfo[] } }>(gql`
      query Commits($repositoryId: UUID!, $ref: String, $limit: Int, $offset: Long) {
        git { commits(repositoryId: $repositoryId, ref: $ref, limit: $limit, offset: $offset) {
          sha message authorName authorEmail authorDate committerName committerDate parentShas
        } }
      }
    `, { repositoryId: repo.value.id, ref: currentRef.value, limit: PAGE_SIZE, offset })
    const fetched = result.git?.commits ?? []
    commitsHasMore.value = fetched.length === PAGE_SIZE
    if (offset === 0) {
      commits.value = fetched
    } else {
      commits.value = [...commits.value, ...fetched]
    }
    commitsOffset.value = offset + fetched.length
  } catch { if (offset === 0) commits.value = [] }
  finally { commitsLoading.value = false }
}

watch([repo, currentRef, activeTab], ([r, ref, tab]) => {
  if (!r || !ref || tab !== 'Commits') return
  commitsOffset.value = 0
  loadCommits(0)
}, { immediate: true })

// ─── Pull Requests ───────────────────────────────────────────────────────────
interface PullRequest {
  id: string; number: number; title: string; description: string | null
  status: string; sourceBranch: string; targetBranch: string
  authorId: string; mergeable: boolean; created: string; updated: string
  mergedAt: string | null
}

const pullRequests = ref<PullRequest[]>([])
const prsLoading = ref(false)
const prStatusFilter = ref<string | null>(null)
const prsOffset = ref(0)
const prsHasMore = ref(false)

async function loadPrs(offset: number = 0) {
  if (!repo.value) return
  prsLoading.value = true
  try {
    const vars: Record<string, unknown> = { repositoryId: repo.value.id, limit: PAGE_SIZE, offset }
    if (prStatusFilter.value) vars.status = prStatusFilter.value
    const result = await query<{ git: { pullRequests: PullRequest[] } }>(gql`
      query PullRequests($repositoryId: UUID!, $limit: Int, $offset: Long, $status: GitPullRequestStatus) {
        git { pullRequests(repositoryId: $repositoryId, limit: $limit, offset: $offset, status: $status) {
          id number title description status sourceBranch targetBranch
          authorId mergeable created updated mergedAt
        } }
      }
    `, vars)
    const fetched = result.git?.pullRequests ?? []
    prsHasMore.value = fetched.length === PAGE_SIZE
    if (offset === 0) {
      pullRequests.value = fetched
    } else {
      pullRequests.value = [...pullRequests.value, ...fetched]
    }
    prsOffset.value = offset + fetched.length
  } catch { if (offset === 0) pullRequests.value = [] }
  finally { prsLoading.value = false }
}

watch([repo, activeTab], ([r, tab]) => {
  if (!r || tab !== 'Pull Requests') return
  prsOffset.value = 0
  loadPrs(0)
}, { immediate: true })

watch(prStatusFilter, () => {
  if (activeTab.value === 'Pull Requests' && repo.value) {
    prsOffset.value = 0
    loadPrs(0)
  }
})

// ─── Pipelines ──────────────────────────────────────────────────────────────
interface PipelineJob { id: string; name: string; status: string; runnerLabel: string }
interface PipelineRun {
  id: string; number: number; status: string; triggerType: string
  ref: string; commitSha: string; created: string
  started: string | null; finished: string | null; durationSeconds: number | null
  jobs: PipelineJob[]
}
interface Pipeline {
  id: string; repositoryId: string; filePath: string; name: string
  created: string; updated: string; runs: PipelineRun[]
}

const pipelines = ref<Pipeline[]>([])
const pipelinesLoading = ref(false)

async function loadPipelines() {
  if (!repo.value) return
  pipelinesLoading.value = true
  try {
    const result = await query<{ git: { pipelines: Pipeline[] } }>(gql`
      query RepoPipelines($repositoryId: UUID!) {
        git {
          pipelines(repositoryId: $repositoryId) {
            id repositoryId filePath name created updated
            runs(limit: 5) {
              id number status triggerType ref commitSha
              created started finished durationSeconds
              jobs { id name status runnerLabel }
            }
          }
        }
      }
    `, { repositoryId: repo.value.id })
    pipelines.value = result.git?.pipelines ?? []
  } catch { pipelines.value = [] }
  finally { pipelinesLoading.value = false }
}

watch([repo, activeTab], ([r, tab]) => {
  if (!r || tab !== 'Pipelines') return
  loadPipelines()
}, { immediate: true })

function pipelineStatusColor(s: string): string {
  if (s === 'SUCCESS') return 'var(--ok)'
  if (s === 'FAILURE') return 'var(--err)'
  if (s === 'RUNNING') return 'var(--brand-2)'
  if (s === 'CANCELLED') return 'var(--fg-4)'
  if (s === 'QUEUED') return 'var(--warn)'
  if (s === 'SKIPPED') return 'var(--fg-4)'
  return 'var(--fg-3)'
}

function pipelineStatusBadgeBg(s: string): string {
  return `color-mix(in oklch, ${pipelineStatusColor(s)} 16%, transparent)`
}

function pipelineStatusIcon(s: string): string {
  if (s === 'SUCCESS') return 'check'
  if (s === 'FAILURE') return 'x'
  if (s === 'RUNNING') return 'pulse'
  if (s === 'CANCELLED') return 'x'
  if (s === 'QUEUED') return 'dots'
  if (s === 'SKIPPED') return 'arrowsLeftRight'
  return 'dots'
}

function formatDuration(sec: number | null): string {
  if (sec == null) return '—'
  if (sec < 60) return `${sec}s`
  const m = Math.floor(sec / 60)
  const s = sec % 60
  if (m < 60) return `${m}m ${s}s`
  const h = Math.floor(m / 60)
  return `${h}h ${m % 60}m`
}

function triggerLabel(type: string): string {
  return type.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase())
}

function latestRun(p: Pipeline): PipelineRun | undefined {
  return p.runs[0]
}

function latestStatus(p: Pipeline): string {
  return latestRun(p)?.status ?? 'QUEUED'
}

// ─── Pipeline Secrets ───────────────────────────────────────────────────────
interface PipelineSecret { name: string; created: string; updated: string }

const secrets = ref<PipelineSecret[]>([])
const secretsLoading = ref(false)
const showAddSecret = ref(false)
const secretForm = reactive({ name: '', value: '' })
const secretSaving = ref(false)
const secretError = ref('')

async function loadSecrets() {
  if (!repo.value) return
  secretsLoading.value = true
  try {
    const result = await query<{ git: { pipelineSecrets: PipelineSecret[] } }>(gql`
      query PipelineSecrets($repositoryId: UUID!) {
        git { pipelineSecrets(repositoryId: $repositoryId) { name created updated } }
      }
    `, { repositoryId: repo.value.id })
    secrets.value = result.git?.pipelineSecrets ?? []
  } catch { secrets.value = [] }
  finally { secretsLoading.value = false }
}

watch([repo, activeTab], ([r, tab]) => {
  if (!r || tab !== 'Secrets') return
  loadSecrets()
}, { immediate: true })

function openAddSecret() {
  secretForm.name = ''
  secretForm.value = ''
  secretError.value = ''
  showAddSecret.value = true
}

async function handleAddSecret() {
  if (!secretForm.name.trim() || !secretForm.value.trim()) {
    secretError.value = 'Name and value are required.'
    return
  }
  secretSaving.value = true
  secretError.value = ''
  try {
    await mutation(gql`
      mutation SetPipelineSecret($repositoryId: UUID!, $name: String!, $value: String!) {
        git { setPipelineSecret(repositoryId: $repositoryId, name: $name, value: $value) { name } }
      }
    `, { repositoryId: repo.value!.id, name: secretForm.name.trim(), value: secretForm.value })
    showAddSecret.value = false
    loadSecrets()
  } catch (e: unknown) {
    secretError.value = e instanceof Error ? e.message : 'Failed to save secret'
  } finally { secretSaving.value = false }
}

async function deleteSecret(name: string) {
  try {
    await mutation(gql`
      mutation DeletePipelineSecret($repositoryId: UUID!, $name: String!) {
        git { deletePipelineSecret(repositoryId: $repositoryId, name: $name) }
      }
    `, { repositoryId: repo.value!.id, name })
    loadSecrets()
  } catch { /* ignore */ }
}

// ─── Commit Statuses ────────────────────────────────────────────────────────
interface CommitStatus {
  id: string; context: string; state: string; description: string | null
  targetUrl: string | null; created: string; commitSha: string; repositoryId: string
}

const statusCommitSha = ref('')
const commitStatuses = ref<CommitStatus[]>([])
const statusesLoading = ref(false)
const recentStatusCommits = ref<CommitInfo[]>([])
const statusCommitsLoading = ref(false)

async function loadRecentStatusCommits() {
  if (!repo.value) return
  statusCommitsLoading.value = true
  try {
    const result = await query<{ git: { commits: CommitInfo[] } }>(gql`
      query StatusCommits($repositoryId: UUID!, $ref: String, $limit: Int) {
        git { commits(repositoryId: $repositoryId, ref: $ref, limit: $limit) {
          sha message authorName authorEmail authorDate committerName committerDate parentShas
        } }
      }
    `, { repositoryId: repo.value.id, ref: currentRef.value || repo.value.defaultBranch, limit: 20 })
    recentStatusCommits.value = result.git?.commits ?? []
  } catch { recentStatusCommits.value = [] }
  finally { statusCommitsLoading.value = false }
}

async function loadCommitStatuses() {
  if (!repo.value || !statusCommitSha.value.trim()) return
  statusesLoading.value = true
  try {
    const result = await query<{ git: { commitStatuses: CommitStatus[] } }>(gql`
      query CommitStatuses($repositoryId: UUID!, $commitSha: String!) {
        git {
          commitStatuses(repositoryId: $repositoryId, commitSha: $commitSha) {
            id context state description targetUrl created commitSha repositoryId
          }
        }
      }
    `, { repositoryId: repo.value.id, commitSha: statusCommitSha.value.trim() })
    commitStatuses.value = result.git?.commitStatuses ?? []
  } catch { commitStatuses.value = [] }
  finally { statusesLoading.value = false }
}

function selectStatusCommit(sha: string) {
  statusCommitSha.value = sha
  loadCommitStatuses()
}

watch([repo, activeTab], ([r, tab]) => {
  if (!r || tab !== 'Statuses') return
  loadRecentStatusCommits()
}, { immediate: true })

function statusStateColor(state: string): string {
  if (state === 'SUCCESS') return 'var(--ok)'
  if (state === 'FAILURE' || state === 'ERROR') return 'var(--err)'
  if (state === 'PENDING') return 'var(--warn)'
  return 'var(--fg-3)'
}

function statusStateBadgeBg(state: string): string {
  return `color-mix(in oklch, ${statusStateColor(state)} 16%, transparent)`
}

function statusStateIcon(state: string): string {
  if (state === 'SUCCESS') return 'check'
  if (state === 'FAILURE' || state === 'ERROR') return 'x'
  if (state === 'PENDING') return 'dots'
  return 'dots'
}

// ─── Create PR ───────────────────────────────────────────────────────────────
const showCreatePr = ref(false)
const prForm = reactive({ title: '', description: '', sourceBranch: '', targetBranch: '', isDraft: false })
const prSaving = ref(false)
const prError = ref('')

function openCreatePr() {
  prForm.title = ''
  prForm.description = ''
  prForm.sourceBranch = ''
  prForm.targetBranch = repo.value?.defaultBranch ?? 'main'
  prForm.isDraft = false
  prError.value = ''
  showCreatePr.value = true
}

async function handleCreatePr() {
  if (!prForm.title || !prForm.sourceBranch || !prForm.targetBranch) {
    prError.value = 'Title, source, and target branches are required.'
    return
  }
  prSaving.value = true
  prError.value = ''
  try {
    const result = await mutation<{ git: { createPullRequest: { id: string; number: number } } }>(gql`
      mutation CreatePR($input: CreateGitPullRequestInput!) {
        git { createPullRequest(input: $input) { id number } }
      }
    `, {
      input: {
        repositoryId: repo.value!.id,
        title: prForm.title,
        description: prForm.description || null,
        sourceBranch: prForm.sourceBranch,
        targetBranch: prForm.targetBranch,
        isDraft: prForm.isDraft,
      },
    })
    showCreatePr.value = false
    const prId = result.git?.createPullRequest?.id
    if (prId) router.push({ path: `/git/pulls/${prId}`, query: { repo: repo.value!.id, number: String(result.git?.createPullRequest?.number ?? 0) } })
  } catch (e: unknown) {
    prError.value = e instanceof Error ? e.message : 'Failed to create pull request'
  } finally {
    prSaving.value = false
  }
}

// ─── Create Branch ──────────────────────────────────────────────────────────
const showCreateBranch = ref(false)
const branchForm = reactive({ name: '', sourceRef: '' })
const branchSaving = ref(false)
const branchError = ref('')

function openCreateBranch() {
  branchForm.name = ''
  branchForm.sourceRef = repo.value?.defaultBranch ?? 'main'
  branchError.value = ''
  showCreateBranch.value = true
}

async function handleCreateBranch() {
  if (!branchForm.name.trim()) {
    branchError.value = 'Branch name is required.'
    return
  }
  if (!branchForm.sourceRef) {
    branchError.value = 'Source branch is required.'
    return
  }
  if (branches.value.some(b => b.name === branchForm.name.trim())) {
    branchError.value = 'A branch with that name already exists.'
    return
  }
  branchSaving.value = true
  branchError.value = ''
  try {
    await mutation<{ git: { createBranch: { name: string } } }>(gql`
      mutation CreateBranch($repositoryId: UUID!, $branchName: String!, $sourceRef: String!) {
        git { createBranch(repositoryId: $repositoryId, branchName: $branchName, sourceRef: $sourceRef) { name } }
      }
    `, {
      repositoryId: repo.value!.id,
      branchName: branchForm.name.trim(),
      sourceRef: branchForm.sourceRef,
    })
    showCreateBranch.value = false
    // Reload branches
    const result = await query<{ git: { branches: BranchInfo[] } }>(gql`
      query Branches($repositoryId: UUID!) {
        git { branches(repositoryId: $repositoryId) { name sha ahead behind } }
      }
    `, { repositoryId: repo.value!.id })
    branches.value = result.git?.branches ?? []
  } catch (e: unknown) {
    branchError.value = e instanceof Error ? e.message : 'Failed to create branch'
  } finally {
    branchSaving.value = false
  }
}

// ─── Clone URL ──────────────────────────────────────────────────────────────
const runtimeConfig = useRuntimeConfig()

const cloneUrl = computed(() => {
  if (!repo.value || !ownerSlug.value) return ''
  const origin = runtimeConfig.public.gitServerUrl || 'http://localhost:8080'
  let gitBase = runtimeConfig.public.gitBaseUrl || '/git'
  if (gitBase === '/') gitBase = ''
  return `${origin}${gitBase}/${ownerSlug.value}/${repo.value.slug}.git`
})

const cloneCopied = ref(false)
const isEmptyRepo = computed(() => !treeLoading.value && treeEntries.value.length === 0 && !currentPath.value)

async function copyCloneUrl() {
  await navigator.clipboard.writeText(cloneUrl.value)
  cloneCopied.value = true
  setTimeout(() => { cloneCopied.value = false }, 2000)
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

function shortSha(sha: string): string {
  return sha.slice(0, 7)
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function fileIcon(entry: TreeEntry): string {
  if (entry.type === 'TREE') return 'folder'
  if (entry.type === 'SUBMODULE') return 'link'
  return 'file'
}

const PR_STATUS_COLORS: Record<string, string> = {
  OPEN: '#34d99a', DRAFT: '#6c7388', MERGED: '#a78bff', CLOSED: '#ff5d6c',
}

const VISIBILITY_COLORS: Record<string, string> = {
  PUBLIC: '#34d99a', INTERNAL: '#ffb547', PRIVATE: '#6c7388',
}

// ─── Setup Help Popup ──────────────────────────────────────────────────────
const showSetupHelp = ref(false)

// ─── Edit Repository ───────────────────────────────────────────────────────
const visibilityOptions = [
  { value: 'PRIVATE', label: 'Private' },
  { value: 'INTERNAL', label: 'Internal' },
  { value: 'PUBLIC', label: 'Public' },
]

const contentTypeOptions = [
  { value: 'GENERAL', label: 'General' },
  { value: 'SCRIPT_PROJECT', label: 'Script Project' },
  { value: 'DOCUMENTATION', label: 'Documentation' },
  { value: 'ANALYTIC_QUERY_PROJECT', label: 'Analytic Query Project' },
  { value: 'AGENT_PROJECT', label: 'Agent Project' },
  { value: 'PIPELINE_PROJECT', label: 'Pipeline Project' },
]

const showEdit = ref(false)
const editForm = reactive({
  name: '',
  description: '',
  visibility: 'PRIVATE',
  contentType: 'GENERAL',
  defaultBranch: 'main',
})
const editSaving = ref(false)
const editError = ref('')

function openEdit() {
  if (!repo.value) return
  editForm.name = repo.value.name
  editForm.description = repo.value.description ?? ''
  editForm.visibility = repo.value.visibility
  editForm.contentType = repo.value.contentType ?? 'GENERAL'
  editForm.defaultBranch = repo.value.defaultBranch
  editError.value = ''
  showEdit.value = true
}

async function handleSaveEdit() {
  if (!repo.value) return
  if (!editForm.name.trim()) {
    editError.value = 'Name is required.'
    return
  }
  if (!editForm.defaultBranch.trim()) {
    editError.value = 'Default branch is required.'
    return
  }
  editSaving.value = true
  editError.value = ''
  try {
    await mutation(gql`
      mutation UpdateRepo($id: UUID!, $input: UpdateGitRepositoryInput!) {
        git { updateRepository(id: $id, input: $input) { id } }
      }
    `, {
      id: repo.value.id,
      input: {
        name: editForm.name.trim(),
        description: editForm.description.trim() === '' ? null : editForm.description.trim(),
        visibility: editForm.visibility,
        contentType: editForm.contentType,
        defaultBranch: editForm.defaultBranch.trim(),
      },
    })
    showEdit.value = false
    await refreshRepo()
  } catch (e: unknown) {
    editError.value = e instanceof Error ? e.message : 'Failed to update repository'
  } finally {
    editSaving.value = false
  }
}

// ─── Rename Repository ─────────────────────────────────────────────────────
// The slug forms part of the clone URL, so a rename is a breaking change for
// anyone who has already cloned the repository. It is kept deliberately separate
// from the general "Edit" form and calls the dedicated renameRepository mutation.
// This regex mirrors the server's slug validation; the server remains the source
// of truth (it re-validates and checks the new slug is free within the owner).
const SLUG_PATTERN = /^[a-z0-9][a-z0-9._-]{0,99}$/
const showRenameSlug = ref(false)
const renameSlug = ref('')
const renameSaving = ref(false)
const renameError = ref('')

function openRenameSlug() {
  if (!repo.value) return
  renameSlug.value = repo.value.slug
  renameError.value = ''
  showRenameSlug.value = true
}

async function handleRenameSlug() {
  if (!repo.value) return
  const next = renameSlug.value.trim()
  if (next === repo.value.slug) {
    showRenameSlug.value = false
    return
  }
  if (!SLUG_PATTERN.test(next)) {
    renameError.value = 'Slug must be lowercase alphanumeric with hyphens, dots, or underscores (1–100 characters) and start with a letter or number.'
    return
  }
  renameSaving.value = true
  renameError.value = ''
  try {
    await mutation(gql`
      mutation RenameRepo($id: UUID!, $newSlug: String!) {
        git { renameRepository(id: $id, newSlug: $newSlug) { id slug } }
      }
    `, {
      id: repo.value.id,
      newSlug: next,
    })
    showRenameSlug.value = false
    toast.success('Repository renamed')
    await refreshRepo()
  } catch (e: unknown) {
    renameError.value = e instanceof Error ? e.message : 'Failed to rename repository'
  } finally {
    renameSaving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', 'Repositories', repo?.name || '…')"
        :title="repo?.name || 'Loading…'"
        :subtitle="repo ? `${repo.slug} · ${repo.visibility.toLowerCase()} · ${repo.defaultBranch}` : ''"
        :tabs="['Code', 'Commits', 'Branches', 'Tags', 'Pull Requests', 'Pipelines', 'Statuses', 'Secrets']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #title>
          <span class="title-row">
            <span class="repo-mark" :style="{ background: accent }">
              <Icon :name="repo?.forkedFromId ? 'git-branch' : 'folder'" :size="16" color="#fff" />
            </span>
            {{ repo?.name || 'Loading…' }}
            <Badge v-if="repo?.archived" color="#6c7388">Archived</Badge>
            <Badge v-if="repo?.visibility" :color="VISIBILITY_COLORS[repo.visibility] || '#6c7388'">{{ repo.visibility.toLowerCase() }}</Badge>
          </span>
        </template>
        <template #actions>
          <template v-if="activeTab === 'Code' || activeTab === 'Commits'">
            <Select
              v-model="currentRef"
              :options="branchOptions"
              placeholder="Branch"
              :accent="accent"
              size="sm" />
          </template>
          <template v-if="activeTab === 'Branches'">
            <Button
              primary
              icon="plus"
              size="sm"
              :accent="accent"
              @click="openCreateBranch">New Branch</Button>
          </template>
          <template v-if="activeTab === 'Pull Requests'">
            <Button
              primary
              icon="plus"
              size="sm"
              :accent="accent"
              @click="openCreatePr">New PR</Button>
          </template>
          <template v-if="activeTab === 'Pipelines'">
            <Button size="sm" icon="pulse" @click="loadPipelines()">Refresh</Button>
          </template>
          <template v-if="activeTab === 'Secrets'">
            <Button
              primary
              size="sm"
              icon="plus"
              :accent="accent"
              @click="openAddSecret">Add Secret</Button>
            <Button size="sm" icon="pulse" @click="loadSecrets()">Refresh</Button>
          </template>
          <template v-if="activeTab === 'Statuses'">
            <Button size="sm" icon="pulse" @click="loadRecentStatusCommits()">Refresh</Button>
          </template>
          <OverflowMenu
            v-if="repo"
            :items="[
              { id: 'edit', label: 'Edit', icon: 'edit' },
              { id: 'rename', label: 'Rename', icon: 'pencil' },
            ]"
            @select="(id: string) => id === 'edit' ? openEdit() : openRenameSlug()"
          >
            <template #default="{ toggle }">
              <button class="help-btn" title="Repository actions" @click.stop="toggle">
                <Icon name="list" :size="18" color="var(--fg-3)" />
              </button>
            </template>
          </OverflowMenu>
          <button class="help-btn" title="Setup instructions" @click="showSetupHelp = true">
            <Icon name="help" :size="18" color="var(--fg-3)" />
          </button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading" class="loading-state">Loading repository…</div>

    <template v-else-if="repo">
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Code (File Browser)                                             -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Code'" class="tab-panel">
        <!-- ── Empty repo: Quick Setup (GitHub-style) ──────────────────── -->
        <template v-if="isEmptyRepo">
          <div class="setup-banner">
            <div class="setup-banner-header">
              <div class="setup-banner-icon" :style="{ background: accent }">
                <Icon name="folder" :size="20" color="#fff" />
              </div>
              <div class="setup-banner-text">
                <h2 class="setup-banner-title">Quick setup — if you've done this kind of thing before</h2>
                <div class="clone-url-row">
                  <input
                    :value="cloneUrl"
                    readonly
                    class="clone-url-input mono"
                    @focus="($event.target as HTMLInputElement).select()" >
                  <Button size="sm" :accent="accent" @click="copyCloneUrl">
                    {{ cloneCopied ? 'Copied!' : 'Copy' }}
                  </Button>
                </div>
              </div>
            </div>
          </div>

          <div class="setup-section">
            <div class="setup-section-header">
              <Icon name="code" :size="16" :color="accent" />
              <span class="setup-section-title">…or create a new repository on the command line</span>
            </div>
            <pre class="setup-cmd mono">echo "# {{ repo.name }}" >> README.md
git init
git add README.md
git commit -m "first commit"
git branch -M {{ repo.defaultBranch }}
git remote add origin {{ cloneUrl }}
git push -u origin {{ repo.defaultBranch }}</pre>
          </div>

          <div class="setup-section">
            <div class="setup-section-header">
              <Icon name="upload" :size="16" :color="accent" />
              <span class="setup-section-title">…or push an existing repository from the command line</span>
            </div>
            <pre class="setup-cmd mono">git remote add origin {{ cloneUrl }}
git branch -M {{ repo.defaultBranch }}
git push -u origin {{ repo.defaultBranch }}</pre>
          </div>
        </template>

        <!-- ── Populated repo: normal file browser ─────────────────────── -->
        <template v-else>
          <StatGrid v-if="stats" :columns="5">
            <StatTile label="Commits" :value="String(stats.commitCount)" :accent="accent" />
            <StatTile label="Branches" :value="String(stats.branchCount)" :accent="accent" />
            <StatTile label="Tags" :value="String(stats.tagCount)" :accent="accent" />
            <StatTile label="Contributors" :value="String(stats.contributorCount)" :accent="accent" />
            <StatTile label="Size" :value="formatSize(stats.diskSizeBytes)" :accent="accent" />
          </StatGrid>

          <div class="path-bar">
            <button class="path-segment root" @click="navigateToRoot">
              <Icon name="folder" :size="13" :color="accent" />
              {{ repo.name }}
            </button>
            <template v-for="seg in pathSegments" :key="seg.path">
              <span class="path-sep">/</span>
              <button class="path-segment" @click="navigateToPath(seg.path)">{{ seg.name }}</button>
            </template>
            <span class="path-bar-spacer" />
            <div v-if="!viewingFile && !creatingFile" class="path-bar-actions">
              <Button size="sm" icon="plus" @click="startCreateFile">New file</Button>
              <Button size="sm" icon="folder" @click="openNewFolder">New folder</Button>
            </div>
          </div>

          <!-- ── New-file pane (replaces tree while creating) ──────────── -->
          <div v-if="creatingFile" class="file-viewer">
            <div class="file-header">
              <Icon name="file" :size="14" :color="accent" />
              <input
                v-model="newFileName"
                class="filename-input mono"
                type="text"
                :disabled="isCommitting"
                placeholder="filename.ext" >
              <span v-if="currentPath" class="mono file-size">in {{ currentPath }}/</span>
              <span v-if="isCreatingDirty" class="dirty-dot" title="Unsaved changes" />
              <span class="file-header-spacer" />
              <Button size="sm" :disabled="isCommitting" @click="cancelCreateFile">Cancel</Button>
              <Button
                size="sm"
                :accent="accent"
                :disabled="isCommitting || !newFileName.trim()"
                @click="commitNewFile">
                {{ isCommitting ? 'Creating…' : 'Create file' }}
              </Button>
            </div>
            <CodeEditor
              v-model="editBuffer"
              :language="detectCodeLanguage(newFileName)"
              :height="fileViewerHeight"
              class="file-viewer-editor" />
            <div class="commit-bar">
              <label class="commit-label" for="new-file-msg">Commit message</label>
              <input
                id="new-file-msg"
                v-model="commitMessage"
                class="commit-input"
                type="text"
                :disabled="isCommitting"
                :placeholder="`Create ${newFileName.trim() || 'file'}`" >
              <p class="commit-hint">
                Committing to <span class="mono">{{ currentRef }}</span>
                as <span class="mono">{{ commitAuthor.authorName }} &lt;{{ commitAuthor.authorEmail }}&gt;</span>
              </p>
            </div>
          </div>

          <div v-else-if="viewingFile" class="file-viewer">
            <div class="file-header">
              <button v-if="!isEditing" class="back-btn" @click="viewingFile = null">
                <Icon name="arrowLeft" :size="14" color="var(--fg-2)" />
              </button>
              <Icon name="file" :size="14" :color="accent" />
              <span class="file-path mono">{{ viewingFile }}</span>
              <span v-if="fileContent && !isEditing" class="mono file-size">{{ formatSize(Number(fileContent.size)) }}</span>
              <span v-if="isEditDirty" class="dirty-dot" title="Unsaved changes" />
              <span class="file-header-spacer" />
              <template v-if="!isEditing">
                <Button
                  size="sm"
                  :disabled="isCommitting"
                  @click="openRename(viewingFile)">Rename</Button>
                <Button
                  size="sm"
                  :disabled="isCommitting"
                  @click="deleteEntry(viewingFile)">Delete</Button>
                <Button
                  v-if="fileContent && !fileContent.isBinary"
                  size="sm"
                  primary
                  icon="edit"
                  :accent="accent"
                  @click="startEdit">Edit</Button>
              </template>
              <template v-else>
                <Button size="sm" :disabled="isCommitting" @click="cancelEdit">Cancel</Button>
                <Button
                  size="sm"
                  :accent="accent"
                  :disabled="isCommitting || !isEditDirty || !commitMessage.trim()"
                  @click="saveEdit">
                  {{ isCommitting ? 'Committing…' : 'Commit' }}
                </Button>
              </template>
            </div>
            <div v-if="fileLoading" class="loading-state">Loading file…</div>
            <div v-else-if="fileContent?.isBinary" class="binary-notice">Binary file — cannot display</div>
            <template v-else-if="isEditing">
              <CodeEditor
                v-model="editBuffer"
                :language="detectedFileLanguage"
                :height="fileViewerHeight"
                class="file-viewer-editor" />
              <div class="commit-bar">
                <label class="commit-label" for="commit-msg">Commit message</label>
                <input
                  id="commit-msg"
                  v-model="commitMessage"
                  class="commit-input"
                  type="text"
                  :disabled="isCommitting"
                  placeholder="Describe your change…" >
                <p class="commit-hint">
                  Committing to <span class="mono">{{ currentRef }}</span>
                  as <span class="mono">{{ commitAuthor.authorName }} &lt;{{ commitAuthor.authorEmail }}&gt;</span>
                </p>
              </div>
            </template>
            <CodeEditor
              v-else-if="fileContent?.content"
              :model-value="fileContent.content"
              :language="detectedFileLanguage"
              :readonly="true"
              :height="fileViewerHeight"
              class="file-viewer-editor"
            />
            <div v-else class="empty-msg">File is empty or too large to display.</div>
          </div>

          <div v-else class="tree-list">
            <div v-if="treeLoading" class="loading-state">Loading…</div>
            <template v-else>
              <div v-if="!treeEntries.length" class="empty-msg">This folder is empty.</div>
              <div
                v-for="entry in treeEntries"
                :key="entry.sha"
                class="tree-row"
                @click="openFile(entry)"
              >
                <Icon :name="fileIcon(entry)" :size="14" :color="entry.type === 'TREE' ? accent : 'var(--fg-3)'" />
                <span :class="['tree-name', { 'is-dir': entry.type === 'TREE' }]">{{ entry.name }}</span>
                <span v-if="entry.size != null && entry.type !== 'TREE'" class="mono tree-size">{{ formatSize(entry.size) }}</span>
                <button
                  v-if="entry.type !== 'TREE'"
                  class="row-action"
                  title="Rename"
                  :disabled="isCommitting"
                  @click.stop="openRename(entry.path)">
                  <Icon name="edit" :size="12" color="var(--fg-2)" />
                </button>
                <button
                  v-if="entry.type !== 'TREE'"
                  class="row-action danger"
                  title="Delete"
                  :disabled="isCommitting"
                  @click.stop="deleteEntry(entry.path)">
                  <Icon name="x" :size="12" color="var(--err, #f87171)" />
                </button>
              </div>
            </template>
          </div>

          <RepositoryReadme
            v-if="!currentPath && !viewingFile && !creatingFile && !treeLoading"
            :repository-id="repo.id"
            :git-ref="currentRef"
            :entries="treeEntries"
            @open="viewingFile = $event" />

          <div v-if="repo.description && !currentPath && !viewingFile" class="card">
            <div class="card-section-title">About</div>
            <p class="card-description">{{ repo.description }}</p>
          </div>
        </template>
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Commits                                                         -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Commits'" class="tab-panel">
        <div v-if="commitsLoading && !commits.length" class="loading-state">Loading commits…</div>
        <div v-else class="commit-list">
          <div v-for="c in commits" :key="c.sha" class="commit-row">
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
          <div v-if="!commits.length && !commitsLoading" class="empty-msg">No commits found.</div>
        </div>
        <div v-if="commitsHasMore" class="load-more">
          <Button
            size="sm"
            :accent="accent"
            :disabled="commitsLoading"
            @click="loadCommits(commitsOffset)">
            {{ commitsLoading ? 'Loading…' : 'Load more commits' }}
          </Button>
        </div>
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Branches                                                        -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Branches'" class="tab-panel">
        <SectionCard title="Branches">
          <template #right>
            <span class="mono section-meta">{{ branches.length }} total</span>
          </template>
          <div v-if="branches.length" class="branch-list">
            <div v-for="b in branches" :key="b.name" class="branch-row">
              <Icon name="git-branch" :size="14" :color="b.name === repo.defaultBranch ? accent : 'var(--fg-3)'" />
              <span class="mono branch-name">{{ b.name }}</span>
              <Badge v-if="b.name === repo.defaultBranch" :color="accent">default</Badge>
              <span v-if="b.ahead || b.behind" class="mono branch-delta">
                <span v-if="b.ahead" class="delta-ahead">+{{ b.ahead }}</span>
                <span v-if="b.behind" class="delta-behind">-{{ b.behind }}</span>
              </span>
              <span class="mono commit-sha">{{ shortSha(b.sha) }}</span>
              <button
                v-if="b.name !== repo.defaultBranch"
                class="tag-delete-btn"
                title="Delete branch"
                :disabled="deleteBranchLoading"
                @click="deleteBranchTarget = b">
                <Icon name="trash" :size="13" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <div v-else class="empty-msg">No branches.</div>
        </SectionCard>

        <ConfirmModal
          v-if="deleteBranchTarget"
          :title="`Delete branch '${deleteBranchTarget.name}'?`"
          :loading="deleteBranchLoading"
          @close="deleteBranchTarget = null"
          @confirm="confirmDeleteBranch"
        />
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Tags                                                            -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Tags'" class="tab-panel">
        <SectionCard title="Tags">
          <template #right>
            <span class="mono section-meta">{{ tags.length }} total</span>
          </template>
          <div v-if="tags.length" class="branch-list">
            <div v-for="t in tags" :key="t.name" class="branch-row">
              <Icon name="tag" :size="14" :color="accent" />
              <span class="mono branch-name">{{ t.name }}</span>
              <Badge v-if="t.isAnnotated" :color="accent">annotated</Badge>
              <span v-if="t.message" class="tag-message">{{ t.message.split('\n')[0] }}</span>
              <span class="mono commit-sha">{{ shortSha(t.sha) }}</span>
              <button
                class="tag-delete-btn"
                title="Delete tag"
                :disabled="deleteTagLoading"
                @click="deleteTagTarget = t">
                <Icon name="trash" :size="13" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <div v-else class="empty-msg">No tags.</div>
        </SectionCard>

        <ConfirmModal
          v-if="deleteTagTarget"
          :title="`Delete tag '${deleteTagTarget.name}'?`"
          :loading="deleteTagLoading"
          @close="deleteTagTarget = null"
          @confirm="confirmDeleteTag"
        />
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Pull Requests                                                   -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Pull Requests'" class="tab-panel">
        <div class="pr-toolbar">
          <div class="pr-filters">
            <button :class="['pr-filter-btn', { active: !prStatusFilter }]" @click="prStatusFilter = null">All</button>
            <button :class="['pr-filter-btn', { active: prStatusFilter === 'OPEN' }]" @click="prStatusFilter = 'OPEN'">Open</button>
            <button :class="['pr-filter-btn', { active: prStatusFilter === 'MERGED' }]" @click="prStatusFilter = 'MERGED'">Merged</button>
            <button :class="['pr-filter-btn', { active: prStatusFilter === 'CLOSED' }]" @click="prStatusFilter = 'CLOSED'">Closed</button>
          </div>
          <span class="mono section-meta">{{ pullRequests.length }} results</span>
        </div>

        <div v-if="prsLoading && !pullRequests.length" class="loading-state">Loading pull requests…</div>
        <div v-else class="pr-list">
          <div
            v-for="pr in pullRequests"
            :key="pr.id"
            class="pr-row"
            @click="router.push({ path: `/git/pulls/${pr.id}`, query: { repo: repo!.id, number: String(pr.number) } })"
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
                <span class="mono pr-branches">{{ pr.sourceBranch }} → {{ pr.targetBranch }}</span>
              </div>
            </div>
            <span class="mono pr-time">{{ relativeTime(pr.updated) }}</span>
          </div>
          <div v-if="!pullRequests.length && !prsLoading" class="empty-msg">No pull requests found.</div>
        </div>
        <div v-if="prsHasMore" class="load-more">
          <Button
            size="sm"
            :accent="accent"
            :disabled="prsLoading"
            @click="loadPrs(prsOffset)">
            {{ prsLoading ? 'Loading…' : 'Load more pull requests' }}
          </Button>
        </div>
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Pipelines                                                       -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Pipelines'" class="tab-panel">
        <div v-if="pipelinesLoading && pipelines.length === 0" class="loading-state">Loading pipelines…</div>
        <div v-else-if="pipelines.length === 0" class="pipeline-empty">
          <div class="empty-icon"><Icon name="workflow" :size="32" color="var(--fg-4)" /></div>
          <p>No pipelines found.</p>
          <p class="empty-hint">Add a <code>.bosca/pipelines/*.yaml</code> file to define a pipeline.</p>
        </div>

        <div v-else class="pipeline-list">
          <div v-for="p in pipelines" :key="p.id" class="pipeline-card">
            <div class="pipeline-header">
              <div class="pipeline-status-dot" :style="{ background: pipelineStatusColor(latestStatus(p)) }" />
              <div class="pipeline-title">
                <span class="pipeline-name">{{ p.name }}</span>
                <span class="pipeline-path mono">{{ p.filePath }}</span>
              </div>
              <span class="pipeline-updated">Updated {{ relativeTime(p.updated) }}</span>
            </div>

            <div v-if="p.runs.length" class="run-list">
              <div
                v-for="run in p.runs"
                :key="run.id"
                class="run-row"
                @click="router.push(`/git/pipelines/${run.id}`)"
              >
                <span class="run-status-icon" :style="{ background: pipelineStatusBadgeBg(run.status) }">
                  <Icon :name="pipelineStatusIcon(run.status)" :size="12" :color="pipelineStatusColor(run.status)" />
                </span>
                <span class="run-number mono">#{{ run.number }}</span>
                <span class="run-ref mono">{{ run.ref }}</span>
                <span class="run-trigger">
                  <Icon name="git-branch" :size="10" color="var(--fg-3)" />
                  {{ triggerLabel(run.triggerType) }}
                </span>
                <span class="run-sha mono">{{ run.commitSha.slice(0, 8) }}</span>
                <div class="job-dots">
                  <div
                    v-for="job in run.jobs"
                    :key="job.id"
                    class="job-dot"
                    :style="{ background: pipelineStatusColor(job.status) }"
                    :title="`${job.name}: ${job.status}`"
                  />
                </div>
                <span class="run-duration mono">{{ formatDuration(run.durationSeconds) }}</span>
                <span class="run-time">{{ relativeTime(run.created) }}</span>
                <span class="run-status-badge" :style="{ background: pipelineStatusBadgeBg(run.status), color: pipelineStatusColor(run.status) }">
                  {{ run.status }}
                </span>
              </div>
            </div>
            <div v-else class="no-runs">No runs yet</div>
          </div>
        </div>

      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Commit Statuses                                                 -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Statuses'" class="tab-panel">
        <div class="status-layout">
          <div class="status-commit-panel">
            <SectionCard title="Recent Commits" :subtitle="recentStatusCommits.length ? `${recentStatusCommits.length} commits` : ''">
              <div v-if="statusCommitsLoading" class="loading-state">Loading commits…</div>
              <div v-else-if="recentStatusCommits.length === 0" class="empty-msg">No commits found.</div>
              <div v-else class="status-commit-list">
                <div
                  v-for="c in recentStatusCommits"
                  :key="c.sha"
                  class="status-commit-item"
                  :class="{ active: statusCommitSha === c.sha }"
                  @click="selectStatusCommit(c.sha)"
                >
                  <div class="mono status-commit-sha">{{ shortSha(c.sha) }}</div>
                  <div class="status-commit-msg">{{ c.message.split('\n')[0] }}</div>
                  <div class="status-commit-meta">{{ c.authorName }} · {{ relativeTime(c.authorDate) }}</div>
                </div>
              </div>
            </SectionCard>
          </div>

          <div class="status-results-panel">
            <div class="status-sha-input-row">
              <TextInput v-model="statusCommitSha" placeholder="Enter or select a commit SHA…" mono />
              <Button
                size="sm"
                :accent="accent"
                :disabled="!statusCommitSha.trim()"
                @click="loadCommitStatuses">
                Load
              </Button>
            </div>

            <SectionCard v-if="commitStatuses.length > 0 || statusesLoading" title="Status Checks" :subtitle="`${commitStatuses.length} checks`">
              <div v-if="statusesLoading" class="loading-state">Loading statuses…</div>
              <div v-else class="cs-list">
                <div v-for="s in commitStatuses" :key="s.id" class="cs-row">
                  <div class="cs-icon" :style="{ background: statusStateBadgeBg(s.state) }">
                    <Icon :name="statusStateIcon(s.state)" :size="14" :color="statusStateColor(s.state)" />
                  </div>
                  <div class="cs-info">
                    <div class="cs-context">{{ s.context }}</div>
                    <div v-if="s.description" class="cs-desc">{{ s.description }}</div>
                  </div>
                  <span class="cs-time">{{ relativeTime(s.created) }}</span>
                  <span class="cs-state-badge" :style="{ background: statusStateBadgeBg(s.state), color: statusStateColor(s.state) }">
                    {{ s.state }}
                  </span>
                  <a
                    v-if="s.targetUrl"
                    :href="s.targetUrl"
                    target="_blank"
                    rel="noopener"
                    class="cs-link"
                    @click.stop>
                    <Icon name="globe" :size="12" color="var(--fg-3)" />
                  </a>
                </div>
              </div>
            </SectionCard>

            <div v-else-if="statusCommitSha && !statusesLoading" class="empty-msg">No status checks found for this commit.</div>
            <div v-else class="empty-msg">Select a commit or enter a SHA to view status checks.</div>
          </div>
        </div>
      </div>

      <!-- ══════════════════════════════════════════════════════════════════ -->
      <!--  Secrets                                                         -->
      <!-- ══════════════════════════════════════════════════════════════════ -->
      <div v-if="activeTab === 'Secrets'" class="tab-panel">
        <div v-if="secretsLoading" class="loading-state">Loading secrets…</div>
        <div v-else-if="secrets.length === 0" class="pipeline-empty">
          <div class="empty-icon"><Icon name="key" :size="32" color="var(--fg-4)" /></div>
          <p>No pipeline secrets configured.</p>
          <p class="empty-hint">Secrets are available to pipelines as environment variables.</p>
        </div>
        <div v-else class="secret-list">
          <div v-for="s in secrets" :key="s.name" class="secret-row">
            <Icon name="key" :size="14" :color="accent" />
            <span class="secret-name mono">{{ s.name }}</span>
            <span class="secret-date">Updated {{ relativeTime(s.updated) }}</span>
            <button class="secret-delete" title="Delete secret" @click="deleteSecret(s.name)">
              <Icon name="x" :size="12" color="var(--fg-3)" />
            </button>
          </div>
        </div>
      </div>
    </template>

    <!-- ─── Create PR Modal ──────────────────────────────────────────────── -->
    <Modal
      v-if="showCreatePr"
      title="New Pull Request"
      icon="git-pull-request"
      :accent="accent"
      @close="showCreatePr = false">
      <div class="form-stack">
        <TextInput v-model="prForm.title" label="Title" placeholder="What does this PR do?" />
        <TextInput v-model="prForm.description" label="Description" placeholder="Describe the changes" />
        <Select
          v-model="prForm.sourceBranch"
          :options="branchOptions"
          label="Source Branch"
          placeholder="Select source"
          :accent="accent" />
        <Select
          v-model="prForm.targetBranch"
          :options="branchOptions"
          label="Target Branch"
          placeholder="Select target"
          :accent="accent" />
        <label class="checkbox-row">
          <input v-model="prForm.isDraft" type="checkbox" >
          <span>Create as draft</span>
        </label>
        <p v-if="prError" class="form-error">{{ prError }}</p>
      </div>
      <template #footer>
        <Button @click="showCreatePr = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="prSaving"
          @click="handleCreatePr">
          {{ prSaving ? 'Creating…' : 'Create Pull Request' }}
        </Button>
      </template>
    </Modal>
    <!-- ─── Setup Help Modal ──────────────────────────────────────────────── -->
    <Modal
      v-if="showSetupHelp"
      title="Repository Setup"
      icon="help"
      :accent="accent"
      width="680px"
      @close="showSetupHelp = false">
      <div class="setup-help-content">
        <div class="setup-help-section">
          <div class="setup-help-label">Clone URL</div>
          <div class="clone-url-row">
            <input
              :value="cloneUrl"
              readonly
              class="clone-url-input mono"
              @focus="($event.target as HTMLInputElement).select()" >
            <Button size="sm" :accent="accent" @click="copyCloneUrl">
              {{ cloneCopied ? 'Copied!' : 'Copy' }}
            </Button>
          </div>
        </div>

        <div class="setup-help-section">
          <div class="setup-help-section-header">
            <Icon name="code" :size="14" :color="accent" />
            <span>Create a new repository on the command line</span>
          </div>
          <pre class="setup-cmd mono">echo "# {{ repo?.name }}" >> README.md
git init
git add README.md
git commit -m "first commit"
git branch -M {{ repo?.defaultBranch }}
git remote add origin {{ cloneUrl }}
git push -u origin {{ repo?.defaultBranch }}</pre>
        </div>

        <div class="setup-help-section">
          <div class="setup-help-section-header">
            <Icon name="upload" :size="14" :color="accent" />
            <span>Push an existing repository from the command line</span>
          </div>
          <pre class="setup-cmd mono">git remote add origin {{ cloneUrl }}
git branch -M {{ repo?.defaultBranch }}
git push -u origin {{ repo?.defaultBranch }}</pre>
        </div>
      </div>
      <template #footer>
        <Button @click="showSetupHelp = false">Close</Button>
      </template>
    </Modal>

    <!-- ─── Add Secret Modal ───────────────────────────────────────────────── -->
    <Modal
      v-if="showAddSecret"
      title="Add Pipeline Secret"
      icon="key"
      :accent="accent"
      @close="showAddSecret = false">
      <div class="form-stack">
        <TextInput v-model="secretForm.name" label="Name" placeholder="SECRET_NAME" />
        <TextInput
          v-model="secretForm.value"
          label="Value"
          placeholder="secret value"
          type="password" />
        <p v-if="secretError" class="form-error">{{ secretError }}</p>
      </div>
      <template #footer>
        <Button @click="showAddSecret = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="secretSaving"
          @click="handleAddSecret">
          {{ secretSaving ? 'Saving…' : 'Save Secret' }}
        </Button>
      </template>
    </Modal>

    <!-- ─── Edit Repository Modal ──────────────────────────────────────────── -->
    <Modal
      v-if="showEdit"
      title="Edit Repository"
      icon="edit"
      :accent="accent"
      @close="showEdit = false">
      <div class="form-stack">
        <TextInput v-model="editForm.name" label="Name" placeholder="my-project" />
        <TextInput
          v-model="editForm.description"
          label="Description"
          placeholder="Optional description" />
        <Select
          v-model="editForm.visibility"
          :options="visibilityOptions"
          label="Visibility"
          :accent="accent" />
        <Select
          v-model="editForm.contentType"
          :options="contentTypeOptions"
          label="Content Type"
          :accent="accent" />
        <TextInput
          v-model="editForm.defaultBranch"
          label="Default Branch"
          placeholder="main"
          mono />
        <p v-if="editError" class="form-error">{{ editError }}</p>
      </div>
      <template #footer>
        <Button @click="showEdit = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="editSaving"
          @click="handleSaveEdit">
          {{ editSaving ? 'Saving…' : 'Save Changes' }}
        </Button>
      </template>
    </Modal>

    <!-- ─── Rename Repository Modal ─────────────────────────────────────────── -->
    <Modal
      v-if="showRenameSlug"
      title="Rename Repository"
      icon="edit"
      :accent="accent"
      @close="showRenameSlug = false">
      <div class="form-stack">
        <p class="form-hint">
          The slug is part of the clone URL (<code>{{ repo?.slug }}.git</code>).
          Renaming it breaks existing clones — anyone using this repository must
          update their remote with <code>git remote set-url</code>. The
          repository's history and stored data are unaffected.
        </p>
        <TextInput
          v-model="renameSlug"
          label="Slug"
          placeholder="repository-slug"
          mono />
        <p v-if="renameError" class="form-error">{{ renameError }}</p>
      </div>
      <template #footer>
        <Button @click="showRenameSlug = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="renameSaving"
          @click="handleRenameSlug">
          {{ renameSaving ? 'Renaming…' : 'Rename' }}
        </Button>
      </template>
    </Modal>

    <!-- ─── Create Branch Modal ────────────────────────────────────────────── -->
    <Modal
      v-if="showCreateBranch"
      title="New Branch"
      icon="git-branch"
      :accent="accent"
      @close="showCreateBranch = false">
      <div class="form-stack">
        <TextInput v-model="branchForm.name" label="Branch name" placeholder="feature/my-branch" />
        <Select
          v-model="branchForm.sourceRef"
          :options="branchOptions"
          label="Source"
          placeholder="Branch from…"
          :accent="accent" />
        <p v-if="branchError" class="form-error">{{ branchError }}</p>
      </div>
      <template #footer>
        <Button @click="showCreateBranch = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="branchSaving"
          @click="handleCreateBranch">
          {{ branchSaving ? 'Creating…' : 'Create Branch' }}
        </Button>
      </template>
    </Modal>

    <!-- ─── New Folder Modal ──────────────────────────────────────────────── -->
    <Modal
      v-if="showNewFolder"
      title="New Folder"
      icon="folder"
      :accent="accent"
      @close="showNewFolder = false">
      <div class="form-stack">
        <TextInput v-model="newFolderName" label="Folder name" placeholder="my-folder" />
        <p class="form-hint">
          Created at <span class="mono">{{ currentPath ? currentPath + '/' : '' }}{{ newFolderName.trim() || '…' }}/</span>.
          A <span class="mono">.gitkeep</span> file is committed so the folder is tracked by git.
        </p>
      </div>
      <template #footer>
        <Button :disabled="isCommitting" @click="showNewFolder = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="isCommitting || !newFolderName.trim()"
          @click="commitNewFolder">
          {{ isCommitting ? 'Creating…' : 'Create Folder' }}
        </Button>
      </template>
    </Modal>

    <!-- ─── Confirm Modal (delete, discard unsaved changes) ───────────────── -->
    <Modal
      v-if="confirmDialog"
      :title="confirmDialog.title"
      icon="help"
      :accent="confirmDialog.danger ? '#f87171' : accent"
      @close="resolveConfirm(false)">
      <p class="form-hint">{{ confirmDialog.message }}</p>
      <template #footer>
        <Button @click="resolveConfirm(false)">Cancel</Button>
        <Button
          primary
          :accent="confirmDialog.danger ? '#f87171' : accent"
          @click="resolveConfirm(true)">
          {{ confirmDialog.confirmLabel ?? 'Confirm' }}
        </Button>
      </template>
    </Modal>

    <!-- ─── Rename File Modal ─────────────────────────────────────────────── -->
    <Modal
      v-if="renameOriginalPath"
      title="Rename File"
      icon="edit"
      :accent="accent"
      @close="cancelRename">
      <div class="form-stack">
        <p class="form-hint">
          From <span class="mono">{{ renameOriginalPath }}</span>
        </p>
        <TextInput
          v-model="renameNewPath"
          label="New path"
          placeholder="path/to/new-name.ext"
          mono />
        <TextInput
          v-model="commitMessage"
          label="Commit message (optional)"
          :placeholder="`Rename ${renameOriginalPath} → ${renameNewPath || '…'}`" />
        <p class="form-hint">
          Rename creates a commit on <span class="mono">{{ currentRef }}</span> that writes the file at the new path and deletes the old path.
        </p>
      </div>
      <template #footer>
        <Button :disabled="isCommitting" @click="cancelRename">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="isCommitting || !renameNewPath.trim() || renameNewPath.trim() === renameOriginalPath"
          @click="commitRename">
          {{ isCommitting ? 'Renaming…' : 'Rename' }}
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
.repo-mark {
  width: 36px; height: 36px; border-radius: 10px;
  display: inline-flex; align-items: center; justify-content: center;
  flex: 0 0 36px;
}
.section-meta { font-size: 11px; color: var(--fg-3); }
.tab-panel { display: flex; flex-direction: column; gap: 12px; }

/* ─── Path bar ────────────────────────────────────────────────────────────── */
.path-bar {
  display: flex; align-items: center; gap: 2px;
  padding: 10px 16px;
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 8px;
}
.path-segment {
  background: none; border: none; padding: 4px 8px; cursor: pointer;
  font-size: 13px; color: var(--fg-1); border-radius: 4px;
  display: flex; align-items: center; gap: 6px;
}
.path-segment:hover { background: var(--bg-2); }
.path-segment.root { font-weight: 600; }
.path-sep { color: var(--fg-3); font-size: 13px; }
.path-bar-spacer { flex: 1; }
.path-bar-actions { display: flex; gap: 8px; align-items: center; }

/* ─── New-file pane filename input ────────────────────────────────────────── */
.filename-input {
  background: var(--bg-2); border: 1px solid var(--line);
  border-radius: 6px; padding: 4px 8px;
  font-size: 13px; color: var(--fg-0);
  min-width: 240px;
}
.filename-input:focus { outline: none; border-color: var(--brand-2, var(--accent, #6c7388)); }

/* ─── Row actions (rename / delete on tree rows) ──────────────────────────── */
.row-action {
  background: none; border: none; padding: 4px;
  border-radius: 4px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  opacity: 0;
  transition: opacity 0.1s, background 0.1s;
}
.tree-row:hover .row-action { opacity: 1; }
.row-action:hover { background: var(--bg-3); }
.row-action[disabled] { opacity: 0.4; cursor: not-allowed; }
.row-action.danger:hover { background: color-mix(in oklch, var(--err, #f87171) 16%, transparent); }

/* ─── Modal form helpers ──────────────────────────────────────────────────── */
.form-hint { font-size: 12px; color: var(--fg-3); margin: 0; line-height: 1.5; }

/* ─── Tree listing ────────────────────────────────────────────────────────── */
.tree-list {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  overflow: hidden;
}
.tree-row {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 16px; border-bottom: 1px solid var(--line);
  cursor: pointer; transition: background 0.1s;
}
.tree-row:last-child { border-bottom: none; }
.tree-row:hover { background: var(--bg-2); }
.tree-name { font-size: 13.5px; color: var(--fg-0); flex: 1; }
.tree-name.is-dir { font-weight: 500; }
.tree-size { font-size: 11px; color: var(--fg-3); }

/* ─── File viewer ─────────────────────────────────────────────────────────── */
.file-viewer {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  overflow: hidden;
}
.file-header {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 16px; border-bottom: 1px solid var(--line);
}
.back-btn {
  background: none; border: none; padding: 4px; cursor: pointer;
  border-radius: 4px; display: flex;
}
.back-btn:hover { background: var(--bg-3); }
.file-path { font-size: 13px; color: var(--fg-1); }
.file-size { font-size: 11px; color: var(--fg-3); }
.file-content {
  margin: 0; padding: 16px; overflow-x: auto;
  font-size: 12.5px; line-height: 1.6; color: var(--fg-1);
  background: var(--bg-0);
}
.file-content code { font-family: 'Geist Mono', monospace; }

/* CodeEditor renders the file body — strip its outer border/radius so it
   sits flush inside the file-viewer card. */
.file-viewer-editor :deep(.editor) {
  border: none;
  border-radius: 0;
}

.file-header-spacer { flex: 1; }

.dirty-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--warn, #ffb547);
  flex: 0 0 8px;
}

.commit-bar {
  display: flex; flex-direction: column; gap: 6px;
  padding: 12px 16px;
  border-top: 1px solid var(--line);
  background: var(--bg-1);
}
.commit-label {
  font-size: 11px; color: var(--fg-3);
  text-transform: uppercase; letter-spacing: .08em; font-weight: 600;
}
.commit-input {
  background: var(--bg-2); border: 1px solid var(--line);
  border-radius: 6px; padding: 8px 10px;
  font-size: 13px; color: var(--fg-0);
  font-family: inherit;
}
.commit-input:focus { outline: none; border-color: var(--brand-2, var(--accent, #6c7388)); }
.commit-input:disabled { opacity: 0.6; cursor: not-allowed; }
.commit-hint { margin: 0; font-size: 11.5px; color: var(--fg-3); }
.binary-notice {
  padding: 48px 0; text-align: center; color: var(--fg-3); font-size: 13px;
}

/* ─── Description card ────────────────────────────────────────────────────── */
.card {
  background: var(--bg-1); border: 1px solid var(--line);
  border-radius: 10px; padding: 16px;
}
.card-section-title {
  font-size: 11px; color: var(--fg-3); text-transform: uppercase;
  letter-spacing: .08em; font-weight: 600; margin-bottom: 10px;
}
.card-description { font-size: 12.5px; line-height: 1.55; color: var(--fg-2); margin: 0; }

/* ─── Quick Setup (empty repo) ───────────────────────────────────────────── */
.setup-banner {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  padding: 20px;
}
.setup-banner-header {
  display: flex; gap: 16px; align-items: flex-start;
}
.setup-banner-icon {
  width: 44px; height: 44px; border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  flex: 0 0 44px;
}
.setup-banner-text { flex: 1; min-width: 0; }
.setup-banner-title {
  font-size: 14px; font-weight: 600; color: var(--fg-0);
  margin: 0 0 12px;
}
.clone-url-row {
  display: flex; gap: 8px; align-items: center;
}
.clone-url-input {
  flex: 1; padding: 8px 12px; font-size: 12.5px;
  background: var(--bg-0); border: 1px solid var(--line); border-radius: 6px;
  color: var(--fg-1); outline: none;
}
.clone-url-input:focus { border-color: v-bind(accent); }

.setup-section {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  overflow: hidden;
}
.setup-section-header {
  display: flex; align-items: center; gap: 10px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
  font-size: 13px; font-weight: 500; color: var(--fg-1);
}
.setup-section-title { flex: 1; }
.setup-cmd {
  margin: 0; padding: 16px 18px; font-size: 12.5px; line-height: 1.7;
  color: var(--fg-1); background: var(--bg-0);
  overflow-x: auto; white-space: pre;
}

/* ─── Commit list ─────────────────────────────────────────────────────────── */
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

/* ─── Branch list ─────────────────────────────────────────────────────────── */
.branch-list { padding: 4px 0; }
.branch-row {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 16px; border-top: 1px solid var(--line);
}
.branch-name { font-size: 13px; color: var(--fg-0); flex: 1; }
.branch-delta { display: flex; gap: 6px; font-size: 11px; }
.delta-ahead { color: #34d99a; }
.delta-behind { color: #ff5d6c; }
.tag-message { font-size: 12px; color: var(--fg-2); flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tag-delete-btn {
  background: none; border: none; padding: 4px; cursor: pointer; border-radius: 4px;
  display: flex; flex: 0 0 auto;
}
.tag-delete-btn:hover:not(:disabled) { background: var(--bg-3); }
.tag-delete-btn:disabled { opacity: 0.5; cursor: default; }

/* ─── PR list ─────────────────────────────────────────────────────────────── */
.pr-toolbar {
  display: flex; align-items: center; justify-content: space-between;
}
.pr-filters { display: flex; gap: 2px; background: var(--bg-1); border-radius: 6px; padding: 2px; border: 1px solid var(--line); }
.pr-filter-btn {
  background: none; border: none; padding: 5px 12px; cursor: pointer;
  font-size: 12px; color: var(--fg-2); border-radius: 4px; font-weight: 500;
}
.pr-filter-btn:hover { background: var(--bg-2); }
.pr-filter-btn.active { background: var(--bg-3); color: var(--fg-0); }

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
.pr-branches { font-size: 11px; color: var(--fg-3); }
.pr-time { font-size: 11px; color: var(--fg-3); flex: 0 0 auto; }

/* ─── Pipelines ──────────────────────────────────────────────────────────── */
.pipeline-empty { text-align: center; padding: 48px 0; color: var(--fg-3); font-size: 13.5px; }
.pipeline-empty .empty-icon { margin-bottom: 12px; }
.pipeline-empty .empty-hint { font-size: 12px; color: var(--fg-4); margin-top: 4px; }
.pipeline-empty .empty-hint code { background: var(--bg-3); padding: 1px 5px; border-radius: 3px; font-size: 11px; }

.pipeline-list { display: flex; flex-direction: column; gap: 14px; }

.pipeline-card {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px; overflow: hidden;
}

.pipeline-header {
  display: flex; align-items: center; gap: 12px; padding: 14px 16px;
  border-bottom: 1px solid var(--line);
}

.pipeline-status-dot {
  width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0;
}

.pipeline-title { flex: 1; min-width: 0; }
.pipeline-name { font-weight: 600; font-size: 14px; color: var(--fg-0); }
.pipeline-path { font-size: 11px; color: var(--fg-3); margin-left: 10px; }
.pipeline-updated { font-size: 11px; color: var(--fg-3); flex-shrink: 0; }

.run-list { display: flex; flex-direction: column; }

.run-row {
  display: flex; align-items: center; gap: 12px; padding: 10px 16px;
  border-bottom: 1px solid var(--line); cursor: pointer; transition: background 0.12s;
}
.run-row:last-child { border-bottom: none; }
.run-row:hover { background: var(--bg-2); }

.run-status-icon {
  width: 24px; height: 24px; border-radius: 6px;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}

.run-number { font-size: 12px; color: var(--fg-1); width: 40px; font-weight: 500; }
.run-ref {
  font-size: 11px; color: var(--fg-2); max-width: 140px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.run-trigger {
  display: inline-flex; align-items: center; gap: 3px;
  font-size: 10.5px; color: var(--fg-3);
}
.run-sha { font-size: 11px; color: v-bind(accent); }

.job-dots { display: flex; align-items: center; gap: 3px; flex: 1; }
.job-dot { width: 8px; height: 8px; border-radius: 50%; }

.run-duration { font-size: 11px; color: var(--fg-2); width: 60px; text-align: right; }
.run-time { font-size: 11px; color: var(--fg-3); width: 80px; text-align: right; }
.run-status-badge {
  font-size: 10px; padding: 2px 8px; border-radius: 6px;
  width: 80px; text-align: center; font-weight: 600;
  text-transform: uppercase; letter-spacing: 0.05em; flex-shrink: 0;
}

.no-runs { padding: 20px 16px; text-align: center; color: var(--fg-4); font-size: 12px; }

/* ─── Secrets ────────────────────────────────────────────────────────────── */
.secret-list {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px; overflow: hidden;
}
.secret-row {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 16px; border-bottom: 1px solid var(--line);
}
.secret-row:last-child { border-bottom: none; }
.secret-name { font-size: 13px; color: var(--fg-0); flex: 1; }
.secret-date { font-size: 11px; color: var(--fg-3); }
.secret-delete {
  background: none; border: none; padding: 4px; cursor: pointer;
  border-radius: 4px; display: flex; opacity: 0; transition: opacity 0.15s;
}
.secret-row:hover .secret-delete { opacity: 1; }
.secret-delete:hover { background: var(--bg-3); }

/* ─── Commit Statuses ────────────────────────────────────────────────────── */
.status-layout {
  display: grid; grid-template-columns: 340px 1fr; gap: 16px; align-items: start;
}
.status-commit-panel { min-width: 0; }
.status-results-panel { min-width: 0; display: flex; flex-direction: column; gap: 14px; }

.status-sha-input-row { display: flex; gap: 8px; align-items: flex-end; }
.status-sha-input-row > :first-child { flex: 1; }

.status-commit-list { max-height: 560px; overflow-y: auto; }
.status-commit-item {
  padding: 10px 14px; border-bottom: 1px solid var(--line);
  cursor: pointer; transition: background 0.12s;
}
.status-commit-item:last-child { border-bottom: none; }
.status-commit-item:hover { background: var(--bg-2); }
.status-commit-item.active {
  background: color-mix(in oklch, v-bind(accent) 10%, transparent);
  border-left: 2px solid v-bind(accent);
}
.status-commit-sha { font-size: 11.5px; color: v-bind(accent); font-weight: 600; }
.status-commit-msg {
  font-size: 12.5px; color: var(--fg-0); margin-top: 2px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.status-commit-meta { font-size: 11px; color: var(--fg-3); margin-top: 2px; }

.cs-list { display: flex; flex-direction: column; }
.cs-row {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 16px; border-bottom: 1px solid var(--line);
}
.cs-row:last-child { border-bottom: none; }
.cs-icon {
  width: 32px; height: 32px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.cs-info { flex: 1; min-width: 0; }
.cs-context { font-size: 13px; font-weight: 500; color: var(--fg-0); }
.cs-desc { font-size: 11.5px; color: var(--fg-2); margin-top: 2px; }
.cs-time { font-size: 11px; color: var(--fg-3); flex-shrink: 0; }
.cs-state-badge {
  font-size: 10.5px; padding: 3px 9px; border-radius: 8px;
  width: 80px; text-align: center; font-weight: 600;
  text-transform: uppercase; letter-spacing: 0.06em; flex-shrink: 0;
}
.cs-link {
  display: flex; align-items: center; justify-content: center;
  width: 28px; height: 28px; border-radius: 6px; transition: background 0.12s; flex-shrink: 0;
}
.cs-link:hover { background: var(--bg-3); }

/* ─── Load more ──────────────────────────────────────────────────────────── */
.load-more { display: flex; justify-content: center; padding: 16px 0; }

/* ─── Forms ───────────────────────────────────────────────────────────────── */
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.checkbox-row {
  display: flex; align-items: center; gap: 8px;
  font-size: 13px; color: var(--fg-1); cursor: pointer;
}
.checkbox-row input { accent-color: v-bind(accent); }

/* ─── Help button ────────────────────────────────────────────────────────── */
.help-btn {
  background: none; border: 1px solid var(--line); border-radius: 8px;
  width: 32px; height: 32px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: background 0.15s, border-color 0.15s;
}
.help-btn:hover { background: var(--bg-2); border-color: var(--fg-3); }
.help-btn:hover svg { stroke: var(--fg-1); }

/* ─── Setup help modal ──────────────────────────────────────────────────── */
.setup-help-content { display: flex; flex-direction: column; gap: 16px; }
.setup-help-label {
  font-size: 11px; color: var(--fg-3); text-transform: uppercase;
  letter-spacing: .08em; font-weight: 600;
  padding: 12px 14px 0;
}
.setup-help-section {
  background: var(--bg-0); border: 1px solid var(--line); border-radius: 8px;
  overflow: hidden;
}
.setup-help-section-header {
  display: flex; align-items: center; gap: 8px;
  padding: 12px 14px; border-bottom: 1px solid var(--line);
  font-size: 12.5px; font-weight: 500; color: var(--fg-1);
}
.setup-help-section .clone-url-row { padding: 12px 14px; }
.setup-help-section .setup-cmd {
  margin: 0; padding: 12px 14px; font-size: 12px; line-height: 1.7;
  color: var(--fg-1); background: var(--bg-0);
  overflow-x: auto; white-space: pre;
}
</style>
