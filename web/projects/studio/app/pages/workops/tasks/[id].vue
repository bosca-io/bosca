<script setup lang="ts">
import gql from 'graphql-tag'
import type { Metadata, Profile } from '~/types/graphql'
import type { ProfileMentionOption } from '~/composables/useProfileSearch'
import DocumentEditor from '~/components/document/DocumentEditor.vue'
import { findActiveProfileMention, replaceProfileMention } from '~/utils/profileMentions'
import { captureYDocRevision, markYDocSaved } from '~/utils/editor/ydoc'

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { mutation, query: gqlQueryTop } = useGraphQL()
const { searchProfiles, searchProfileMentions } = useProfileSearch()

const rawId = computed(() => route.params.id as string)
const isUuid = computed(() => /^[0-9a-f]{8}-/.test(rawId.value))
const activeTab = ref('Comments')
const showLogWork = ref(false)
const editingSummary = ref(false)
const editingDescription = ref(false)
const summaryDraft = ref('')
const descriptionDraft = ref('')
const commentDraft = ref('')
const commentInput = ref<HTMLTextAreaElement>()
const commentMentionQuery = ref('')
const commentMentionResults = ref<ProfileMentionOption[]>([])
const commentMentionVisible = ref(false)
let commentMentionTimer: ReturnType<typeof setTimeout> | undefined
let commentMentionRequest = 0
const saving = ref(false)
const toast = useToast()

function closeCommentMentions() {
  commentMentionRequest++
  if (commentMentionTimer) clearTimeout(commentMentionTimer)
  commentMentionTimer = undefined
  commentMentionVisible.value = false
  commentMentionResults.value = []
}

function updateCommentMentionSuggestions(value: string, cursor: number) {
  const mention = findActiveProfileMention(value, cursor)
  if (!mention) {
    closeCommentMentions()
    return
  }

  const request = ++commentMentionRequest
  if (commentMentionTimer) clearTimeout(commentMentionTimer)
  commentMentionQuery.value = mention.query
  commentMentionTimer = setTimeout(async () => {
    const results = await searchProfileMentions(mention.query)
    if (request !== commentMentionRequest) return

    const currentCursor = commentInput.value?.selectionStart ?? commentDraft.value.length
    const currentMention = findActiveProfileMention(commentDraft.value, currentCursor)
    if (!currentMention || currentMention.query !== mention.query) return

    commentMentionResults.value = results
    commentMentionVisible.value = results.length > 0
  }, 150)
}

function onCommentInput(event: Event) {
  const input = event.currentTarget as HTMLTextAreaElement
  updateCommentMentionSuggestions(input.value, input.selectionStart ?? input.value.length)
}

function onCommentCaretChange(event: Event) {
  const input = event.currentTarget as HTMLTextAreaElement
  updateCommentMentionSuggestions(input.value, input.selectionStart ?? input.value.length)
}

function onCommentMentionSelect(member: { id: string; name: string; handle?: string }) {
  if (!member.handle) return
  const cursor = commentInput.value?.selectionStart ?? commentDraft.value.length
  const mention = findActiveProfileMention(commentDraft.value, cursor)
  if (!mention) return

  const replacement = replaceProfileMention(commentDraft.value, mention, member.handle)
  commentDraft.value = replacement.value
  closeCommentMentions()
  nextTick(() => {
    commentInput.value?.focus()
    commentInput.value?.setSelectionRange(replacement.cursor, replacement.cursor)
  })
}

onBeforeUnmount(closeCommentMentions)

const STATUS_COLORS: Record<string, string> = {
  TODO: '#6c7388',
  IN_PROGRESS: '#a78bff',
  IN_REVIEW: '#ffb547',
  DONE: '#34d99a',
  CANCELLED: '#6c7388',
}

const PRIORITY_COLORS: Record<number, string> = {
  1: '#ff5d6c',
  2: '#ff8a4d',
  3: '#ffb547',
  4: '#5ec5ff',
  5: '#6c7388',
}

const TASK_FIELDS = `
  id key summary metadataId descriptionMarkdown descriptionHtml
  status { id name category }
  taskType { id name }
  priority { id name displayOrder }
  resolution { id name }
  assignee { id name }
  assigneeProfileId
  reporter { id name }
  reporterProfileId
  project { id key name }
  affectedProjects { id key name }
  parentTask { id key summary }
  epicTask { id key summary }
  labelIds componentIds dueDate startDate
  originalEstimateSeconds remainingEstimateSeconds timeSpentSeconds
  sprintId milestoneId
  comments { id content profileId created modified visibility likes }
  links {
    sourceTask { id key summary status { name category } }
    targetTask { id key summary status { name category } }
    linkType { id name outwardName inwardName }
  }
  requirement { id key }
  transitions { id name toStateId toState { status { category } } }
  history { id changes { fieldKey fromValue toValue } changedByProfileId changedAt }
  watcherProfileIds createdAt modifiedAt version
`

const taskByIdGql = gql`
  query GetTaskById($id: UUID!) {
    workOps { tasks { task(id: $id) { ${TASK_FIELDS} } } }
  }
`

const taskByKeyGql = gql`
  query GetTaskByKey($key: String!) {
    workOps { tasks { taskByKey(key: $key) { ${TASK_FIELDS} } } }
  }
`

interface TaskDetail {
  id: string
  key: string
  summary: string
  metadataId: string | null
  descriptionMarkdown: string | null
  descriptionHtml: string | null
  status: { id: string; name: string; category: string }
  taskType: { id: string; name: string }
  priority: { id: string; name: string; displayOrder: number }
  resolution: { id: string; name: string } | null
  assignee: { id: string; name: string } | null
  assigneeProfileId: string | null
  reporter: { id: string; name: string } | null
  reporterProfileId: string
  project: { id: string; key: string; name: string }
  affectedProjects: Array<{ id: string; key: string; name: string }>
  parentTask: { id: string; key: string; summary: string } | null
  epicTask: { id: string; key: string; summary: string } | null
  labelIds: string[]
  componentIds: string[]
  dueDate: string | null
  startDate: string | null
  originalEstimateSeconds: number | null
  remainingEstimateSeconds: number | null
  timeSpentSeconds: number
  sprintId: string | null
  milestoneId: string | null
  comments: Array<{
    id: number
    content: string
    profileId: string
    created: string
    modified: string
    visibility: string
    likes: number
  }>
  links: Array<{
    sourceTask: { id: string; key: string; summary: string; status: { name: string; category: string } } | null
    targetTask: { id: string; key: string; summary: string; status: { name: string; category: string } } | null
    linkType: { id: string; name: string; outwardName: string; inwardName: string }
  }>
  requirement: { id: string; key: string } | null
  transitions: Array<{
    id: string
    name: string | null
    toStateId: string
    toState: { status: { category: string } }
  }>
  history: Array<{
    id: string
    changes: Array<{ fieldKey: string; fromValue: unknown; toValue: unknown }>
    changedByProfileId: string
    changedAt: string
  }>
  watcherProfileIds: string[]
  createdAt: string
  modifiedAt: string
  version: number
}

const task = ref<TaskDetail | null>(null)
const isLoading = ref(true)
const fetchError = ref('')

const headerTitle = computed(() => {
  if (task.value) return task.value.summary
  if (isLoading.value) return 'Loading…'
  if (fetchError.value) return 'Error'
  return 'Task not found'
})

const headerSubtitle = computed(() => {
  if (task.value) return `${task.value.taskType.name} in ${task.value.project.name}`
  if (fetchError.value) return fetchError.value
  return ''
})

async function loadTask() {
  if (!task.value) isLoading.value = true
  fetchError.value = ''
  try {
    const { query: gqlQuery } = useGraphQL()
    if (isUuid.value) {
      const result = await gqlQuery<{ workOps: { tasks: { task: TaskDetail | null } } }>(
        taskByIdGql, { id: rawId.value },
      )
      task.value = result.workOps?.tasks?.task ?? null
    } else {
      const result = await gqlQuery<{ workOps: { tasks: { taskByKey: TaskDetail | null } } }>(
        taskByKeyGql, { key: rawId.value },
      )
      task.value = result.workOps?.tasks?.taskByKey ?? null
    }
  } catch (e: unknown) {
    fetchError.value = e instanceof Error ? e.message : 'Failed to load task'
    task.value = null
  } finally {
    isLoading.value = false
  }
}

const profileNameMap = ref<Record<string, string>>({})

async function resolveProfileNames() {
  if (!task.value) return
  const resolved: Record<string, string> = { ...profileNameMap.value }

  if (task.value.assignee) resolved[task.value.assigneeProfileId!] = task.value.assignee.name
  if (task.value.reporter) resolved[task.value.reporterProfileId] = task.value.reporter.name

  const ids = new Set<string>()
  for (const c of task.value.comments) { if (!resolved[c.profileId]) ids.add(c.profileId) }
  for (const h of task.value.history) { if (h.changedByProfileId && !resolved[h.changedByProfileId]) ids.add(h.changedByProfileId) }
  for (const wl of worklogs.value) { if (!resolved[wl.profileId]) ids.add(wl.profileId) }

  if (ids.size) {
    const { query: gqlQuery } = useGraphQL()
    for (const id of ids) {
      try {
        const result = await gqlQuery<{ profile: { name: string } | null }>(gql`
          query GetProfile($id: UUID!) { profile(id: $id) { name } }
        `, { id })
        if (result.profile?.name) resolved[id] = result.profile.name
      } catch { /* skip */ }
    }
  }

  profileNameMap.value = resolved
}

function profileName(id: string | null): string {
  if (!id) return 'System'
  return profileNameMap.value[id] ?? id.slice(0, 8)
}

async function refresh() { await loadTask() }

// ─── Sprint Resolution & Editing ─────────────────────────────────────────────

const sprintName = ref<string | null>(null)
const editingSprint = ref(false)
const availableSprints = ref<Array<{ id: string; name: string; state: string }>>([])

async function resolveSprintName() {
  if (!task.value?.sprintId) { sprintName.value = null; return }
  try {
    const { query: gqlQuery } = useGraphQL()
    const result = await gqlQuery<{ workOps: { sprints: { sprint: { name: string } | null } } }>(gql`
      query GetSprintName($id: UUID!) {
        workOps { sprints { sprint(id: $id) { name } } }
      }
    `, { id: task.value.sprintId })
    sprintName.value = result.workOps?.sprints?.sprint?.name ?? null
  } catch { sprintName.value = null }
}

async function loadSprintsForTask() {
  if (!task.value?.project?.id) return
  if (availableSprints.value.length) return
  try {
    const { query: gqlQuery } = useGraphQL()
    const boardsResult = await gqlQuery<{
      workOps: { boards: { byProject: Array<{ id: string }> } }
    }>(gql`
      query GetBoardsForTask($projectId: UUID!) {
        workOps { boards { byProject(projectId: $projectId) { id } } }
      }
    `, { projectId: task.value.project.id })
    const boardIds = boardsResult.workOps?.boards?.byProject?.map(b => b.id) ?? []
    const all: Array<{ id: string; name: string; state: string }> = []
    for (const boardId of boardIds) {
      const sprintResult = await gqlQuery<{
        workOps: { sprints: { byBoard: Array<{ id: string; name: string; state: string }> } }
      }>(gql`
        query GetSprintsForBoard($boardId: UUID!) {
          workOps { sprints { byBoard(boardId: $boardId) { id name state } } }
        }
      `, { boardId })
      all.push(...(sprintResult.workOps?.sprints?.byBoard ?? []))
    }
    availableSprints.value = all.filter(s => s.state !== 'CLOSED')
  } catch { /* ignore */ }
}

async function saveSprint(sprintId: string | null) {
  if (!task.value) return
  editingSprint.value = false
  saving.value = true
  try {
    // The update input treats a null sprintId as "not provided" — moving a
    // task back to the backlog requires the explicit clearSprintId flag.
    const input: Record<string, unknown> = { expectedVersion: task.value.version }
    if (sprintId) input.sprintId = sprintId
    else input.clearSprintId = true
    await mutation(gql`
      mutation UpdateTask($id: UUID!, $input: UpdateWorkOpsTaskInput!) {
        workOps { tasks { update(id: $id, input: $input) { id } } }
      }
    `, { id: task.value.id, input })
    await refresh()
  } catch { toast.error('Failed to update sprint') }
  finally { saving.value = false }
}

// ─── Affected Projects ───────────────────────────────────────────────────────

const editingAffectedProjects = ref(false)
const allProjects = ref<Array<{ id: string; key: string; name: string }>>([])

async function loadAllProjects() {
  if (allProjects.value.length) return
  try {
    const { query: gqlQuery } = useGraphQL()
    const result = await gqlQuery<{
      workOps: { projects: { all: Array<{ id: string; key: string; name: string; archivedAt: string | null }> } }
    }>(gql`query { workOps { projects { all { id key name archivedAt } } } }`, {})
    allProjects.value = (result.workOps?.projects?.all ?? []).filter(p => !p.archivedAt)
  } catch { /* ignore */ }
}

const availableProjects = computed(() => {
  if (!task.value) return []
  const existingIds = new Set([
    task.value.project.id,
    ...task.value.affectedProjects.map(p => p.id),
  ])
  return allProjects.value.filter(p => !existingIds.has(p.id))
})

async function addAffectedProject(projectId: string) {
  if (!task.value) return
  saving.value = true
  try {
    await mutation(gql`
      mutation AddAffectedProject($id: UUID!, $projectId: UUID!) {
        workOps { tasks { addAffectedProject(id: $id, projectId: $projectId) } }
      }
    `, { id: task.value.id, projectId })
    await refresh()
  } catch { toast.error('Failed to add project') }
  finally { saving.value = false }
}

async function removeAffectedProject(projectId: string) {
  if (!task.value) return
  saving.value = true
  try {
    await mutation(gql`
      mutation RemoveAffectedProject($id: UUID!, $projectId: UUID!) {
        workOps { tasks { removeAffectedProject(id: $id, projectId: $projectId) } }
      }
    `, { id: task.value.id, projectId })
    await refresh()
  } catch { toast.error('Failed to remove project') }
  finally { saving.value = false }
}

onMounted(() => { loadTask() })
watch(rawId, () => { loadTask() })
watch(task, () => { resolveProfileNames(); resolveSprintName() }, { immediate: true })

function formatDuration(seconds: number | null): string {
  if (!seconds) return '—'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (h > 0 && m > 0) return `${h}h ${m}m`
  if (h > 0) return `${h}h`
  return `${m}m`
}

function formatDate(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' })
}

function relativeTime(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(ms / 60_000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  const days = Math.floor(hrs / 24)
  return `${days}d ago`
}

function startEditSummary() {
  summaryDraft.value = task.value?.summary ?? ''
  editingSummary.value = true
}

async function saveSummary() {
  if (!task.value || !summaryDraft.value.trim()) return
  saving.value = true
  try {
    await mutation(gql`
      mutation UpdateTask($id: UUID!, $input: UpdateWorkOpsTaskInput!) {
        workOps { tasks { update(id: $id, input: $input) { id } } }
      }
    `, {
      id: task.value.id,
      input: { summary: summaryDraft.value.trim(), expectedVersion: task.value.version },
    })
    editingSummary.value = false
    await refresh()
  } finally {
    saving.value = false
  }
}

function startEditDescription() {
  descriptionDraft.value = task.value?.descriptionMarkdown ?? ''
  editingDescription.value = true
}

async function saveDescription() {
  if (!task.value) return
  saving.value = true
  try {
    await mutation(gql`
      mutation UpdateTask($id: UUID!, $input: UpdateWorkOpsTaskInput!) {
        workOps { tasks { update(id: $id, input: $input) { id } } }
      }
    `, {
      id: task.value.id,
      input: { descriptionMarkdown: descriptionDraft.value, expectedVersion: task.value.version },
    })
    editingDescription.value = false
    await refresh()
  } finally {
    saving.value = false
  }
}

// Watch state lives in workops.task_watcher (what the notification
// dispatcher reads), NOT the task's watcherProfileIds column — the watch /
// unwatch mutations never touch that column, so both the count and the
// "am I watching" check must come from the watchers query.
const taskWatcherIds = ref<string[]>([])
const myProfileIds = ref<string[]>([])

const isCurrentlyWatching = computed(() =>
  taskWatcherIds.value.some(id => myProfileIds.value.includes(id)),
)

async function loadWatchers() {
  if (!task.value) return
  try {
    const { query: gqlQuery } = useGraphQL()
    const result = await gqlQuery<{
      workOps: { notifications: { watchers: Array<{ profileId: string }> } }
    }>(gql`
      query GetTaskWatchers($taskId: UUID!) {
        workOps { notifications { watchers(taskId: $taskId) { profileId } } }
      }
    `, { taskId: task.value.id })
    taskWatcherIds.value = (result.workOps?.notifications?.watchers ?? []).map(w => w.profileId)
  } catch { /* keep last known list */ }
}

async function loadMyProfileIds() {
  if (myProfileIds.value.length) return
  try {
    const { query: gqlQuery } = useGraphQL()
    const result = await gqlQuery<{
      profiles: { current: Array<{ id: string }> | null }
    }>(gql`query { profiles { current { id } } }`, {})
    myProfileIds.value = (result.profiles?.current ?? []).map(p => p.id)
  } catch { /* ignore */ }
}

async function toggleWatch() {
  if (!task.value) return
  try {
    if (isCurrentlyWatching.value) {
      await mutation(gql`
        mutation Unwatch($taskId: UUID!) { workOps { notifications { unwatch(taskId: $taskId) } } }
      `, { taskId: task.value.id })
      toast.success('Stopped watching')
    } else {
      await mutation(gql`
        mutation Watch($taskId: UUID!) { workOps { notifications { watch(taskId: $taskId) } } }
      `, { taskId: task.value.id })
      toast.success('Now watching')
    }
    await loadWatchers()
  } catch { toast.error('Failed to update watch status') }
}

watch(task, (t) => {
  if (t) {
    loadWatchers()
    loadMyProfileIds()
  }
})

// ─── Estimate Inline Edit ────────────────────────────────────────────────────
const editingEstimate = ref<'original' | 'remaining' | null>(null)
const estimateDraft = ref('')

// Mirrors DurationShorthand.parseToSeconds on the backend:
// w = 5×8h working week, d = 8h day, then h / m / s. Returns null when
// no component parses (the update input wants seconds, not shorthand).
function parseDurationSeconds(text: string): number | null {
  const UNIT_SECONDS: Record<string, number> = { w: 5 * 8 * 3600, d: 8 * 3600, h: 3600, m: 60, s: 1 }
  let total = 0
  for (const match of text.matchAll(/(\d+)\s*([wdhms])/gi)) {
    total += Number(match[1]) * (UNIT_SECONDS[match[2]!.toLowerCase()] ?? 0)
  }
  return total > 0 ? total : null
}

function startEditEstimate(kind: 'original' | 'remaining') {
  const seconds = kind === 'original'
    ? task.value?.originalEstimateSeconds
    : task.value?.remainingEstimateSeconds
  estimateDraft.value = seconds ? formatDuration(seconds) : ''
  editingEstimate.value = kind
}

async function saveEstimate() {
  if (!task.value || !editingEstimate.value) return
  const kind = editingEstimate.value
  const text = estimateDraft.value.trim()
  const seconds = text ? parseDurationSeconds(text) : null
  if (text && seconds == null) {
    toast.error('Use duration shorthand like "2h 30m", "1d", or "45m"')
    return
  }
  saving.value = true
  try {
    const input: Record<string, unknown> = { expectedVersion: task.value.version }
    if (kind === 'original') {
      if (seconds == null) input.clearOriginalEstimate = true
      else input.originalEstimateSeconds = seconds
    } else if (seconds == null) {
      input.clearRemainingEstimate = true
    } else {
      input.remainingEstimateSeconds = seconds
    }
    await mutation(gql`
      mutation UpdateTask($id: UUID!, $input: UpdateWorkOpsTaskInput!) {
        workOps { tasks { update(id: $id, input: $input) { id } } }
      }
    `, { id: task.value.id, input })
    editingEstimate.value = null
    await refresh()
  } catch { toast.error('Failed to update estimate') }
  finally { saving.value = false }
}

// ─── Due Date Inline Edit ────────────────────────────────────────────────────
const editingDueDate = ref(false)
const dueDateDraft = ref('')

const isDueDateOverdue = computed(() => {
  if (!task.value?.dueDate) return false
  const due = task.value.dueDate.slice(0, 10)
  const today = new Date().toISOString().slice(0, 10)
  return due < today
})

function startEditDueDate() {
  dueDateDraft.value = task.value?.dueDate ? task.value.dueDate.slice(0, 10) : ''
  editingDueDate.value = true
}

async function saveDueDate() {
  if (!task.value) return
  saving.value = true
  try {
    await mutation(gql`
      mutation UpdateTask($id: UUID!, $input: UpdateWorkOpsTaskInput!) {
        workOps { tasks { update(id: $id, input: $input) { id } } }
      }
    `, {
      id: task.value.id,
      input: {
        dueDate: dueDateDraft.value ? new Date(dueDateDraft.value).toISOString() : null,
        expectedVersion: task.value.version,
      },
    })
    editingDueDate.value = false
    await refresh()
  } finally {
    saving.value = false
  }
}

// ─── Transitions & Resolutions ───────────────────────────────────────────────
interface Resolution { id: string; name: string }
const resolutions = ref<Resolution[]>([])
const pendingTransitionId = ref<string | null>(null)
const showResolutionPicker = ref(false)
const selectedResolutionId = ref('')

async function loadResolutions() {
  if (resolutions.value.length) return
  try {
    const { query: gqlQuery } = useGraphQL()
    const result = await gqlQuery<{ workOps: { tasks: { resolutions: Resolution[] } } }>(gql`
      query { workOps { tasks { resolutions { id name } } } }
    `, {})
    resolutions.value = result.workOps?.tasks?.resolutions ?? []
  } catch { /* ignore */ }
}

function isTerminalTransition(transitionId: string): boolean {
  const t = task.value?.transitions.find(tr => tr.id === transitionId)
  const category = t?.toState?.status?.category
  return category === 'DONE' || category === 'CANCELLED'
}

function initiateTransition(transitionId: string) {
  if (isTerminalTransition(transitionId)) {
    pendingTransitionId.value = transitionId
    selectedResolutionId.value = ''
    loadResolutions()
    showResolutionPicker.value = true
  } else {
    doTransition(transitionId)
  }
}

async function confirmTransitionWithResolution() {
  if (!pendingTransitionId.value) return
  await doTransition(pendingTransitionId.value, selectedResolutionId.value || undefined)
  showResolutionPicker.value = false
  pendingTransitionId.value = null
}

async function doTransition(transitionId: string, resolutionId?: string) {
  if (!task.value) return
  saving.value = true
  try {
    await mutation(gql`
      mutation TransitionTask($id: UUID!, $transitionId: UUID!, $expectedVersion: Long!, $resolutionId: UUID) {
        workOps { tasks { transition(id: $id, transitionId: $transitionId, expectedVersion: $expectedVersion, resolutionId: $resolutionId) { id } } }
      }
    `, {
      id: task.value.id,
      transitionId,
      expectedVersion: task.value.version,
      resolutionId: resolutionId ?? null,
    })
    await refresh()
  } finally {
    saving.value = false
  }
}

// ─── Priority Edit ───────────────────────────────────────────────────────────
const editingPriority = ref(false)
const allPriorities = ref<Array<{ id: string; name: string; displayOrder: number }>>([])

async function loadPriorities() {
  if (allPriorities.value.length) return
  try {
    const { query: gqlQuery } = useGraphQL()
    const result = await gqlQuery<{
      workOps: { tasks: { priorities: Array<{ id: string; name: string; displayOrder: number }> } }
    }>(gql`query { workOps { tasks { priorities { id name displayOrder } } } }`, {})
    allPriorities.value = (result.workOps?.tasks?.priorities ?? []).sort((a, b) => a.displayOrder - b.displayOrder)
  } catch { /* ignore */ }
}

async function savePriority(priorityId: string) {
  if (!task.value) return
  editingPriority.value = false
  saving.value = true
  try {
    await mutation(gql`
      mutation UpdateTask($id: UUID!, $input: UpdateWorkOpsTaskInput!) {
        workOps { tasks { update(id: $id, input: $input) { id } } }
      }
    `, {
      id: task.value.id,
      input: { priorityId, expectedVersion: task.value.version },
    })
    await refresh()
  } catch { toast.error('Failed to update priority') }
  finally { saving.value = false }
}

// ─── Assignee Edit ───────────────────────────────────────────────────────────
const editingAssignee = ref(false)

async function saveAssignee(profileId: string) {
  if (!task.value) return
  editingAssignee.value = false
  saving.value = true
  try {
    await mutation(gql`
      mutation UpdateTask($id: UUID!, $input: UpdateWorkOpsTaskInput!) {
        workOps { tasks { update(id: $id, input: $input) { id } } }
      }
    `, {
      id: task.value.id,
      input: {
        assigneeProfileId: profileId || null,
        expectedVersion: task.value.version,
      },
    })
    await refresh()
  } catch { toast.error('Failed to update assignee') }
  finally { saving.value = false }
}

// ─── Link Tasks ──────────────────────────────────────────────────────────────
const showLinkTask = ref(false)
const linkForm = reactive({ targetTaskKey: '', linkTypeId: '' })
const linkTypes = ref<Array<{ id: string; name: string; outwardLabel: string }>>([])

async function loadLinkTypes() {
  if (linkTypes.value.length) return
  try {
    const { query: gqlQuery } = useGraphQL()
    const result = await gqlQuery<{
      workOps: { links: { linkTypes: Array<{ id: string; name: string; outwardLabel: string }> } }
    }>(gql`
      query { workOps { links { linkTypes { id name outwardLabel } } } }
    `, {})
    linkTypes.value = result.workOps?.links?.linkTypes ?? []
    if (linkTypes.value.length && !linkForm.linkTypeId) {
      linkForm.linkTypeId = linkTypes.value[0]!.id
    }
  } catch { /* ignore */ }
}

async function createLink() {
  if (!task.value || !linkForm.targetTaskKey.trim() || !linkForm.linkTypeId) return
  saving.value = true
  try {
    const { query: gqlQuery } = useGraphQL()
    const result = await gqlQuery<{
      workOps: { tasks: { taskByKey: { id: string } | null } }
    }>(gql`
      query($key: String!) { workOps { tasks { taskByKey(key: $key) { id } } } }
    `, { key: linkForm.targetTaskKey.trim().toUpperCase() })
    const targetId = result.workOps?.tasks?.taskByKey?.id
    if (!targetId) { toast.error('Task not found'); saving.value = false; return }

    await mutation(gql`
      mutation LinkTask($input: WorkOpsTaskLinkInput!) {
        workOps { links { link(input: $input) { id } } }
      }
    `, {
      input: {
        sourceTaskId: task.value.id,
        targetTaskId: targetId,
        linkTypeId: linkForm.linkTypeId,
      },
    })
    showLinkTask.value = false
    linkForm.targetTaskKey = ''
    toast.success('Link created')
    await refresh()
  } catch { toast.error('Failed to create link') }
  finally { saving.value = false }
}

async function addComment() {
  if (!task.value || !commentDraft.value.trim()) return
  saving.value = true
  try {
    await mutation(gql`
      mutation AddComment($taskId: UUID!, $input: WorkOpsTaskCommentInput!) {
        workOps { taskComments { add(taskId: $taskId, input: $input) { id } } }
      }
    `, {
      taskId: task.value.id,
      input: { content: commentDraft.value.trim(), visibility: 'USER' },
    })
    commentDraft.value = ''
    closeCommentMentions()
    await refresh()
  } finally {
    saving.value = false
  }
}

// ─── Work Log ───��────────────────────────────────────────────────────────────
interface WorkLogEntry {
  id: string
  timeSpentSeconds: number
  timeSpentShort: string
  startedAt: string
  comment: string | null
  profileId: string
  createdAt: string
}

const worklogs = ref<WorkLogEntry[]>([])
const worklogForm = reactive({
  timeSpent: '1h',
  startedAt: new Date().toISOString().slice(0, 16),
  comment: '',
  estimateAdjustment: '',
})

async function loadWorklogs() {
  if (!task.value) return
  try {
    const { query: gqlQuery } = useGraphQL()
    const result = await gqlQuery<{
      workOps: { worklogs: { forTask: WorkLogEntry[] } }
    }>(gql`
      query GetWorklogs($taskId: UUID!) {
        workOps { worklogs { forTask(taskId: $taskId, limit: 50, offset: 0) {
          id timeSpentSeconds timeSpentShort startedAt comment profileId createdAt
        } } }
      }
    `, { taskId: task.value.id })
    worklogs.value = result.workOps?.worklogs?.forTask ?? []
    resolveProfileNames()
  } catch {
    worklogs.value = []
  }
}

async function logWork() {
  if (!task.value || !worklogForm.timeSpent.trim()) return
  saving.value = true
  try {
    await mutation(gql`
      mutation LogWork($taskId: UUID!, $input: WorkOpsWorkLogInput!) {
        workOps { worklogs { logWork(taskId: $taskId, input: $input) { id } } }
      }
    `, {
      taskId: task.value.id,
      input: {
        timeSpent: worklogForm.timeSpent.trim(),
        startedAt: new Date(worklogForm.startedAt).toISOString(),
        comment: worklogForm.comment.trim() || null,
        estimateAdjustment: worklogForm.estimateAdjustment.trim() || null,
        visibility: 'PUBLIC',
      },
    })
    showLogWork.value = false
    worklogForm.timeSpent = '1h'
    worklogForm.comment = ''
    worklogForm.estimateAdjustment = ''
    toast.success('Work logged')
    await loadWorklogs()
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to log work')
  } finally {
    saving.value = false
  }
}

async function deleteWorklog(id: string) {
  saving.value = true
  try {
    await mutation(gql`
      mutation DeleteWorklog($id: UUID!) {
        workOps { worklogs { deleteWorklog(id: $id) } }
      }
    `, { id })
    await loadWorklogs()
    await refresh()
  } finally {
    saving.value = false
  }
}

watch(activeTab, (tab) => {
  if (tab === 'Work Log' && task.value && worklogs.value.length === 0) {
    loadWorklogs()
  }
})

// ─── Document (optional Tiptap editor) ──────────────────────────────────────

const taskDocMetadataGql = gql`
  query GetTaskDocMetadata($id: UUID!) {
    profiles { current { id name } }
    content {
      metadata(id: $id) {
        __typename id version name slug type languageTag attributes
        created modified public publicContent publicSupplementary
        searchable locked labels ready uploaded
        content { type }
        document {
          content title
          template {
            id version
            documentTemplate {
              schema
              attributes { key name description type ui location list configuration supplementaryKey }
              containers { id name description type filters }
            }
          }
        }
        workflow { state stateValid pending running }
        parentCollections(offset: 0, limit: 100) { id name attributes itemAttributes }
        relationships { metadata { id name content { type } } relationship attributes }
      }
    }
  }
`

const taskDocMetadata = ref<Metadata | null>(null)
const taskDocProfile = ref<Profile>({ id: '', name: 'Unknown' } as Profile)
const editorRef = ref<InstanceType<typeof DocumentEditor> | null>(null)
const editorSaving = ref(false)
const editorStatus = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')

async function loadTaskDocMetadata() {
  if (!task.value?.metadataId) return
  try {
    const result = await gqlQueryTop<{
      profiles: { current: Profile }
      content: { metadata: Metadata | null }
    }>(taskDocMetadataGql, { id: task.value.metadataId })
    taskDocMetadata.value = result.content?.metadata ?? null
    if (result.profiles?.current) taskDocProfile.value = result.profiles.current
  } catch { /* metadata not available */ }
}

const collabItem = computed(() => taskDocMetadata.value)
const collabProfile = computed(() => taskDocProfile.value)
const collab = useCollaborationAndAttributes(collabItem, collabProfile)
const ydoc = collab.ydoc
const ydocReady = collab.ready

const saveTaskDocGql = gql`
  mutation SaveTaskDocument($id: UUID!, $version: Int!, $document: DocumentInput!) {
    content { metadata { setMetadataDocument(id: $id, version: $version, document: $document) } }
  }
`

async function saveTaskDocument() {
  if (!taskDocMetadata.value || !task.value || editorSaving.value) return
  const saveRevision = collab.ydoc.value ? captureYDocRevision(collab.ydoc.value) : null
  editorSaving.value = true
  editorStatus.value = 'saving'
  try {
    const doc = editorRef.value?.getDocument?.()
    if (doc) {
      await mutation(saveTaskDocGql, {
        id: taskDocMetadata.value.id,
        version: taskDocMetadata.value.version,
        document: doc,
      })
    }
    if (collab.ydoc.value && saveRevision) {
      markYDocSaved(collab.ydoc.value, saveRevision)
    }
    editorStatus.value = 'saved'
    setTimeout(() => { editorStatus.value = 'idle' }, 3000)
  } catch (e: unknown) {
    console.error('Save failed:', e)
    toast.error(e instanceof Error ? e.message : 'Failed to save document')
    editorStatus.value = 'error'
  } finally {
    editorSaving.value = false
  }
}

const hasUnsavedChanges = ref(false)

watch(ydoc, (doc) => {
  if (!doc) return
  const changesText = doc.getText('changes')
  hasUnsavedChanges.value = changesText.getAttribute('changes') === 'true'
  changesText.observe(() => {
    hasUnsavedChanges.value = changesText.getAttribute('changes') === 'true'
  })
}, { immediate: true })

const docSaveStatusLabel = computed(() => {
  switch (editorStatus.value) {
    case 'saving': return 'Saving…'
    case 'saved': return 'Saved'
    case 'error': return 'Save failed'
    default: return hasUnsavedChanges.value ? 'Draft' : ''
  }
})

const docSaveStatusDot = computed(() => {
  switch (editorStatus.value) {
    case 'saved': return 'ok'
    case 'error': return 'err'
    default: return hasUnsavedChanges.value ? 'warn' : 'info'
  }
})

async function createTaskDocument() {
  if (!task.value) return
  saving.value = true
  try {
    await mutation(gql`
      mutation CreateTaskDocument($id: UUID!, $expectedVersion: Long!) {
        workOps { tasks { createDocument(id: $id, expectedVersion: $expectedVersion) { id metadataId version } } }
      }
    `, { id: task.value.id, expectedVersion: task.value.version })
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create document')
  } finally {
    saving.value = false
  }
}

watch(task, (t) => {
  if (t?.metadataId) loadTaskDocMetadata()
})
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Tasks', task?.key || rawId)"
        :title="headerTitle"
        :subtitle="headerSubtitle"
        @dblclick="task && startEditSummary()"
      >
        <template #actions>
          <Button
            v-for="t in (task?.transitions ?? [])"
            :key="t.id"
            size="sm"
            :accent="accent"
            :disabled="saving"
            @click="initiateTransition(t.id)"
          >
            → {{ t.name || 'Transition' }}
          </Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading" class="loading-state">Loading task…</div>

    <div v-else-if="task" class="task-layout">
      <div class="task-main">
        <!-- Inline Summary Edit -->
        <div v-if="editingSummary" class="summary-edit-block">
          <input
            v-model="summaryDraft"
            class="summary-input"
            autofocus
            @keydown.enter="saveSummary"
            @keydown.escape="editingSummary = false"
          >
          <div class="summary-actions">
            <Button size="sm" @click="editingSummary = false">Cancel</Button>
            <Button
              size="sm"
              primary
              :accent="accent"
              :disabled="saving"
              @click="saveSummary">Save</Button>
          </div>
        </div>

        <!-- Description -->
        <SectionCard title="Description" glass class="hoverable-card">
          <template #right>
            <button v-if="!editingDescription" class="edit-link" @click="startEditDescription">
              <Icon name="pencil" :size="12" color="var(--fg-3)" />
            </button>
          </template>
          <div class="card-body">
            <template v-if="editingDescription">
              <textarea
                v-model="descriptionDraft"
                class="description-editor"
                rows="10"
                placeholder="Describe the task in Markdown…"
              />
              <div class="description-actions">
                <Button size="sm" @click="editingDescription = false">Cancel</Button>
                <Button
                  size="sm"
                  primary
                  :accent="accent"
                  :disabled="saving"
                  @click="saveDescription">Save</Button>
              </div>
            </template>
            <template v-else>
              <!-- eslint-disable-next-line vue/no-v-html -- server-rendered task description HTML -->
              <div v-if="task.descriptionHtml" class="description" v-html="task.descriptionHtml" />
              <pre v-else-if="task.descriptionMarkdown" class="description-md">{{ task.descriptionMarkdown }}</pre>
              <p v-else class="empty-desc" @click="startEditDescription">Click to add a description…</p>
            </template>
          </div>
        </SectionCard>

        <!-- Document (optional Tiptap editor) -->
        <SectionCard
          v-if="task.metadataId"
          title="Document"
          glass
          class="editor-card">
          <template #right>
            <span v-if="docSaveStatusLabel" class="save-status">
              <span :class="['dot', docSaveStatusDot]" />
              {{ docSaveStatusLabel }}
            </span>
            <Button
              size="sm"
              icon="save"
              primary
              :accent="accent"
              :disabled="editorSaving"
              @click="saveTaskDocument">
              Save
            </Button>
          </template>
          <div class="editor-body">
            <ClientOnly>
              <DocumentEditor
                v-if="taskDocMetadata && ydoc && collab.attributes"
                ref="editorRef"
                :metadata="taskDocMetadata"
                :profile="taskDocProfile"
                :attributes="collab.attributes"
                :ydoc="ydoc"
                :editable="ydocReady"
                :on-document="() => {}"
                :on-title-update="() => {}"
              />
              <div v-else class="editor-loading">Loading editor…</div>
            </ClientOnly>
          </div>
        </SectionCard>
        <SectionCard v-else title="Document" glass>
          <div class="card-body">
            <p class="empty-desc" @click="createTaskDocument">Add a rich document to this task…</p>
          </div>
        </SectionCard>

        <!-- Links -->
        <SectionCard title="Links" glass class="hoverable-card">
          <template #right>
            <button class="edit-link" @click="loadLinkTypes(); showLinkTask = true">
              <Icon name="plus" :size="12" color="var(--fg-3)" />
            </button>
          </template>
          <div class="card-body">
            <div v-for="(link, i) in task.links" :key="i" class="link-row">
              <span class="link-type">{{ link.linkType.outwardName }}</span>
              <NuxtLink
                v-if="link.targetTask"
                :to="`/workops/tasks/${link.targetTask.id}`"
                class="link-target"
              >
                <span class="mono link-key">{{ link.targetTask.key }}</span>
                {{ link.targetTask.summary }}
              </NuxtLink>
            </div>
            <div v-if="!task.links.length" class="empty-msg">No links.</div>
          </div>
        </SectionCard>

        <!-- Requirements — only for standalone tasks, not tasks auto-created by a requirement -->
        <TaskRequirements
          v-if="task && !task.requirement"
          :task-id="task.id"
          :accent="accent"
          @updated="refresh()" />

        <!-- Tabs: Comments / History / Work Log -->
        <SectionCard :title="activeTab" glass>
          <template #right>
            <div class="tab-btns">
              <button
                v-for="t in ['Comments', 'History', 'Work Log']"
                :key="t"
                :class="['tab-btn', { active: activeTab === t }]"
                @click="activeTab = t"
              >{{ t }}</button>
            </div>
          </template>

          <div class="card-body">
            <!-- Comments -->
            <div v-show="activeTab === 'Comments'">
              <div v-for="c in task.comments" :key="c.id" class="comment-item">
                <div class="comment-header">
                  <Avatar :name="profileName(c.profileId)" :idx="0" :size="22" />
                  <span class="comment-author">{{ profileName(c.profileId) }}</span>
                  <span class="comment-time">{{ relativeTime(c.created) }}</span>
                </div>
                <p class="comment-body">{{ c.content }}</p>
              </div>
              <div v-if="!task.comments.length" class="empty-msg">No comments yet.</div>
              <div class="comment-composer">
                <div class="comment-input-wrap">
                  <MentionPicker
                    :query="commentMentionQuery"
                    :members="commentMentionResults"
                    :visible="commentMentionVisible"
                    @select="onCommentMentionSelect"
                    @close="closeCommentMentions"
                  />
                  <textarea
                    ref="commentInput"
                    v-model="commentDraft"
                    class="comment-input"
                    rows="3"
                    placeholder="Add a comment…"
                    @input="onCommentInput"
                    @click="onCommentCaretChange"
                  />
                </div>
                <div class="comment-composer-footer">
                  <span class="comment-hint">Markdown supported · Type @ to mention a profile</span>
                  <Button
                    size="sm"
                    primary
                    :accent="accent"
                    :disabled="!commentDraft.trim() || saving"
                    @click="addComment">
                    Post Comment
                  </Button>
                </div>
              </div>
            </div>

            <!-- History -->
            <div v-show="activeTab === 'History'">
              <div v-for="h in task.history" :key="h.id" class="history-item">
                <div class="history-header">
                  <span class="history-who">{{ profileName(h.changedByProfileId) }}</span>
                  <span class="history-when">{{ relativeTime(h.changedAt) }}</span>
                </div>
                <div v-for="(ch, ci) in h.changes" :key="ci" class="history-change">
                  <span class="history-field">{{ ch.fieldKey }}</span>:
                  <span class="history-old">{{ ch.fromValue ?? '—' }}</span> →
                  <span class="history-new">{{ ch.toValue ?? '—' }}</span>
                </div>
              </div>
              <div v-if="!task.history.length" class="empty-msg">No history yet.</div>
            </div>

            <!-- Work Log -->
            <div v-show="activeTab === 'Work Log'">
              <div class="worklog-header-row">
                <Button
                  size="sm"
                  primary
                  :accent="accent"
                  icon="plus"
                  @click="showLogWork = true">Log Work</Button>
              </div>
              <div v-for="wl in worklogs" :key="wl.id" class="worklog-item">
                <div class="worklog-row">
                  <span class="worklog-who">{{ profileName(wl.profileId) }}</span>
                  <Badge color="#a78bff">{{ wl.timeSpentShort }}</Badge>
                  <span class="worklog-date">{{ formatDate(wl.startedAt) }}</span>
                  <button class="worklog-delete" @click="deleteWorklog(wl.id)">
                    <Icon name="trash" :size="12" color="var(--fg-3)" />
                  </button>
                </div>
                <p v-if="wl.comment" class="worklog-comment">{{ wl.comment }}</p>
              </div>
              <div v-if="!worklogs.length" class="empty-msg">No work logged yet.</div>
            </div>
          </div>
        </SectionCard>
      </div>

      <!-- Sidebar -->
      <aside class="task-sidebar">
        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Status</div>
            <Badge :color="STATUS_COLORS[task.status.category] || '#6c7388'" solid>
              {{ task.status.name }}
            </Badge>
          </div>
          <div class="sidebar-section">
            <div class="sidebar-label">Priority</div>
            <div v-if="editingPriority" class="sidebar-edit-row">
              <Select
                :model-value="task.priority.id"
                :options="allPriorities.map(p => ({ value: p.id, label: p.name }))"
                size="sm"
                @update:model-value="savePriority($event as string)"
              />
              <div class="sidebar-edit-actions">
                <button class="sidebar-cancel-link" @click="editingPriority = false">cancel</button>
              </div>
            </div>
            <div v-else class="sidebar-clickable" @click="loadPriorities(); editingPriority = true">
              <Badge :color="PRIORITY_COLORS[task.priority.displayOrder] || '#6c7388'">
                {{ task.priority.name }}
              </Badge>
            </div>
          </div>
          <div class="sidebar-section">
            <div class="sidebar-label">Type</div>
            <span class="sidebar-value">{{ task.taskType.name }}</span>
          </div>
          <div v-if="task.resolution" class="sidebar-section">
            <div class="sidebar-label">Resolution</div>
            <Badge color="#4ade80">{{ task.resolution.name }}</Badge>
          </div>
        </div>

        <div class="sidebar-divider" />

        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Assignee</div>
            <div v-if="editingAssignee" class="sidebar-edit-row">
              <Select
                :model-value="task.assigneeProfileId ?? ''"
                :on-search="searchProfiles"
                searchable
                placeholder="Search people…"
                size="sm"
                @update:model-value="saveAssignee($event as string)"
              />
              <div class="sidebar-edit-actions">
                <button class="sidebar-cancel-link" @click="editingAssignee = false">cancel</button>
              </div>
            </div>
            <template v-else>
              <div class="sidebar-clickable" @click="editingAssignee = true">
                <div v-if="task.assignee" class="sidebar-person">
                  <Avatar :name="task.assignee.name" :idx="0" :size="20" />
                  <span class="sidebar-value">{{ task.assignee.name }}</span>
                </div>
                <span v-else class="sidebar-muted">Unassigned</span>
              </div>
            </template>
          </div>
          <div class="sidebar-section">
            <div class="sidebar-label">Reporter</div>
            <div v-if="task.reporter" class="sidebar-person">
              <Avatar :name="task.reporter.name" :idx="1" :size="20" />
              <span class="sidebar-value">{{ task.reporter.name }}</span>
            </div>
            <span v-else class="sidebar-muted">—</span>
          </div>
        </div>

        <div class="sidebar-divider" />

        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Project</div>
            <NuxtLink :to="`/workops/projects/${task.project.id}`" class="sidebar-link">
              {{ task.project.key }} — {{ task.project.name }}
            </NuxtLink>
          </div>
          <div class="sidebar-section">
            <div class="sidebar-label">
              <span>Also In</span>
              <button class="sidebar-action" @click="loadAllProjects(); editingAffectedProjects = !editingAffectedProjects">
                {{ editingAffectedProjects ? 'done' : '+ add' }}
              </button>
            </div>
            <div v-if="task.affectedProjects.length" class="affected-projects">
              <div v-for="p in task.affectedProjects" :key="p.id" class="affected-project-row">
                <NuxtLink :to="`/workops/projects/${p.id}`" class="sidebar-link">
                  {{ p.key }} — {{ p.name }}
                </NuxtLink>
                <button v-if="editingAffectedProjects" class="remove-btn" @click="removeAffectedProject(p.id)">×</button>
              </div>
            </div>
            <span v-else-if="!editingAffectedProjects" class="sidebar-muted">None</span>
            <template v-if="editingAffectedProjects && availableProjects.length">
              <Select
                model-value=""
                :options="availableProjects.map(p => ({ value: p.id, label: `${p.key} — ${p.name}` }))"
                placeholder="Add project…"
                size="sm"
                @update:model-value="addAffectedProject($event as string)"
              />
            </template>
          </div>
          <div class="sidebar-section">
            <div class="sidebar-label">Due Date</div>
            <div v-if="editingDueDate" class="sidebar-edit-row">
              <input
                v-model="dueDateDraft"
                type="date"
                class="sidebar-date-input"
                autofocus
                @keydown.enter="saveDueDate"
                @keydown.escape="editingDueDate = false" >
              <div class="sidebar-edit-actions">
                <button class="sidebar-cancel-link" @click="editingDueDate = false">cancel</button>
                <button class="sidebar-cancel-link" style="color: v-bind(accent)" @click="saveDueDate">save</button>
              </div>
            </div>
            <div v-else class="sidebar-clickable" @click="startEditDueDate">
              <span v-if="task.dueDate" class="sidebar-value" :class="{ overdue: isDueDateOverdue }">{{ formatDate(task.dueDate) }}</span>
              <span v-else class="sidebar-muted">Not set</span>
            </div>
          </div>
          <div v-if="task.startDate" class="sidebar-section">
            <div class="sidebar-label">Start Date</div>
            <span class="sidebar-value">{{ formatDate(task.startDate) }}</span>
          </div>
          <div class="sidebar-section">
            <div class="sidebar-label">Sprint</div>
            <div v-if="editingSprint" class="sidebar-edit-row">
              <Select
                :model-value="task.sprintId ?? ''"
                :options="[
                  { value: '', label: 'Backlog (none)' },
                  ...availableSprints.map(s => ({ value: s.id, label: `${s.name} (${s.state})` })),
                ]"
                size="sm"
                @update:model-value="saveSprint(($event as string) || null)"
              />
              <div class="sidebar-edit-actions">
                <button class="sidebar-cancel-link" @click="editingSprint = false">cancel</button>
              </div>
            </div>
            <div v-else class="sidebar-clickable" @click="loadSprintsForTask(); editingSprint = true">
              <NuxtLink
                v-if="task.sprintId && sprintName"
                :to="`/workops/sprints/${task.sprintId}`"
                class="sidebar-link"
                @click.stop>
                {{ sprintName }}
              </NuxtLink>
              <span v-else class="sidebar-muted">Backlog</span>
            </div>
          </div>
        </div>

        <div class="sidebar-divider" />

        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Estimate</div>
            <div v-if="editingEstimate === 'original'" class="sidebar-edit-row">
              <input
                v-model="estimateDraft"
                class="sidebar-text-input"
                placeholder="e.g. 2h 30m"
                autofocus
                @keydown.enter="saveEstimate"
                @keydown.escape="editingEstimate = null" >
              <div class="sidebar-edit-actions">
                <button class="sidebar-cancel-link" @click="editingEstimate = null">cancel</button>
                <button class="sidebar-cancel-link" style="color: v-bind(accent)" @click="saveEstimate">save</button>
              </div>
            </div>
            <div v-else class="sidebar-clickable" @click="startEditEstimate('original')">
              <span class="sidebar-value mono">{{ formatDuration(task.originalEstimateSeconds) }}</span>
            </div>
          </div>
          <div class="sidebar-section">
            <div class="sidebar-label">Remaining</div>
            <div v-if="editingEstimate === 'remaining'" class="sidebar-edit-row">
              <input
                v-model="estimateDraft"
                class="sidebar-text-input"
                placeholder="e.g. 3h"
                autofocus
                @keydown.enter="saveEstimate"
                @keydown.escape="editingEstimate = null" >
              <div class="sidebar-edit-actions">
                <button class="sidebar-cancel-link" @click="editingEstimate = null">cancel</button>
                <button class="sidebar-cancel-link" style="color: v-bind(accent)" @click="saveEstimate">save</button>
              </div>
            </div>
            <div v-else class="sidebar-clickable" @click="startEditEstimate('remaining')">
              <span class="sidebar-value mono">{{ formatDuration(task.remainingEstimateSeconds) }}</span>
            </div>
          </div>
          <div class="sidebar-section">
            <div class="sidebar-label">Time Spent</div>
            <span class="sidebar-value mono">{{ formatDuration(task.timeSpentSeconds) }}</span>
          </div>
        </div>

        <div v-if="task.requirement || task.parentTask || task.epicTask" class="sidebar-divider" />

        <div v-if="task.requirement || task.parentTask || task.epicTask" class="sidebar-group">
          <div v-if="task.requirement" class="sidebar-section">
            <div class="sidebar-label">Requirement</div>
            <NuxtLink :to="`/workops/requirements/${task.requirement.id}`" class="sidebar-link">
              {{ task.requirement.key }}
            </NuxtLink>
          </div>
          <div v-if="task.parentTask" class="sidebar-section">
            <div class="sidebar-label">Parent</div>
            <NuxtLink :to="`/workops/tasks/${task.parentTask.id}`" class="sidebar-link">
              {{ task.parentTask.key }}
            </NuxtLink>
          </div>
          <div v-if="task.epicTask" class="sidebar-section">
            <div class="sidebar-label">Epic</div>
            <NuxtLink :to="`/workops/tasks/${task.epicTask.id}`" class="sidebar-link">
              {{ task.epicTask.key }} — {{ task.epicTask.summary }}
            </NuxtLink>
          </div>
        </div>

        <div class="sidebar-divider" />

        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Watchers</div>
            <div class="sidebar-row">
              <span class="sidebar-value mono">{{ taskWatcherIds.length }}</span>
              <button class="sidebar-action" :class="{ 'always-visible': isCurrentlyWatching }" @click="toggleWatch">
                {{ isCurrentlyWatching ? '✓ watching' : '+ watch' }}
              </button>
            </div>
          </div>
          <div class="sidebar-meta">
            <span>Created {{ relativeTime(task.createdAt) }}</span>
            <span>Updated {{ relativeTime(task.modifiedAt) }}</span>
          </div>
        </div>
      </aside>
    </div>

    <div v-else-if="fetchError" class="loading-state error-state">{{ fetchError }}</div>
    <div v-else class="loading-state">No task found for this ID.</div>

    <!-- Transition Confirmation with Resolution -->
    <!-- Log Work Modal -->
    <Modal
      v-if="showLogWork && task"
      title="Log Work"
      icon="clock"
      :accent="accent"
      @close="showLogWork = false">
      <div class="form-stack">
        <TextInput
          v-model="worklogForm.timeSpent"
          label="Time Spent"
          placeholder="e.g. 2h 30m, 1d, 45m"
          autofocus />
        <TextInput v-model="worklogForm.startedAt" label="Started At" type="datetime-local" />
        <TextInput v-model="worklogForm.estimateAdjustment" label="Remaining Estimate" placeholder="e.g. 3h (leave blank to auto-adjust)" />
        <Textarea
          v-model="worklogForm.comment"
          label="Comment"
          :rows="2"
          placeholder="Optional work description" />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showLogWork = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!worklogForm.timeSpent.trim() || saving"
          @click="logWork">Log Work</Button>
      </template>
    </Modal>

    <!-- Link Task Modal -->
    <Modal
      v-if="showLinkTask"
      title="Link Task"
      icon="link"
      :accent="accent"
      @close="showLinkTask = false">
      <div class="form-stack">
        <TextInput
          v-model="linkForm.targetTaskKey"
          label="Target Task Key"
          placeholder="e.g. STUDIO-42"
          autofocus />
        <Select
          v-model="linkForm.linkTypeId"
          label="Link Type"
          :options="linkTypes.map(lt => ({ value: lt.id, label: `${lt.name} (${lt.outwardLabel})` }))"
        />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showLinkTask = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!linkForm.targetTaskKey.trim() || !linkForm.linkTypeId || saving"
          @click="createLink">
          Link
        </Button>
      </template>
    </Modal>

    <!-- Transition Confirmation -->
    <Modal
      v-if="showResolutionPicker"
      title="Confirm Transition"
      icon="check"
      :accent="accent"
      @close="showResolutionPicker = false; pendingTransitionId = null">
      <p class="resolution-hint">Optionally set a resolution for this transition:</p>
      <div class="resolution-options">
        <button
          v-for="r in resolutions"
          :key="r.id"
          :class="['resolution-option', { selected: selectedResolutionId === r.id }]"
          @click="selectedResolutionId = selectedResolutionId === r.id ? '' : r.id"
        >
          {{ r.name }}
        </button>
        <span v-if="!resolutions.length" class="resolution-none">No resolutions configured.</span>
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showResolutionPicker = false; pendingTransitionId = null">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="saving"
          @click="confirmTransitionWithResolution">
          {{ selectedResolutionId ? 'Transition with Resolution' : 'Transition' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.save-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--fg-3);
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--fg-3);
}

.dot.ok { background: var(--ok); }
.dot.err { background: var(--err); }
.dot.warn { background: var(--warn); }
.dot.info { background: var(--brand-2); }

.editor-card { min-height: 300px; }

.editor-body { padding: 0; }

.editor-body :deep(.editor-textarea) {
  padding: 20px 24px;
  min-height: 250px;
  max-width: 100%;
  font-size: 14px;
  line-height: 1.7;
  color: var(--fg-0);
  outline: none;
  overflow-wrap: break-word;
  word-break: break-word;
}

.editor-loading {
  color: var(--fg-3);
  font-size: 13px;
  padding: 40px 24px;
  text-align: center;
}

.loading-state {
  color: var(--fg-3);
  text-align: center;
  padding: 60px 0;
  font-size: 14px;
}

.error-state {
  color: var(--err);
}

.task-layout {
  display: grid;
  grid-template-columns: 1fr 280px;
  gap: 20px;
}

.task-main {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-body {
  padding: 14px 16px;
}

.summary-edit-block {
  padding: 16px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
}

.summary-input {
  width: 100%;
  font-size: 16px;
  font-weight: 600;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px 14px;
  color: var(--fg-0);
}

.summary-input:focus {
  outline: none;
  border-color: v-bind(accent);
}

.summary-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  justify-content: flex-end;
}

.hoverable-card .edit-link {
  opacity: 0;
  transition: opacity 0.15s;
}

.hoverable-card:hover .edit-link {
  opacity: 1;
}

.edit-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--fg-3);
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: color 0.15s, background 0.15s;
}

.edit-link:hover {
  color: var(--fg-1);
  background: var(--bg-2);
}

.description {
  font-size: 13.5px;
  line-height: 1.65;
  color: var(--fg-1);
}

.description-md {
  font-family: var(--font-mono);
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--fg-1);
  white-space: pre-wrap;
  margin: 0;
}

.description-editor {
  width: 100%;
  font-family: var(--font-mono);
  font-size: 12.5px;
  line-height: 1.6;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 12px 14px;
  color: var(--fg-0);
  resize: vertical;
}

.description-editor:focus {
  outline: none;
  border-color: v-bind(accent);
}

.description-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.empty-desc {
  color: var(--fg-3);
  font-size: 13px;
  font-style: italic;
  cursor: pointer;
  margin: 0;
  padding: 8px 0;
}

.empty-desc:hover {
  color: var(--fg-2);
}

.link-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--line);
}

.link-row:last-child { border-bottom: none; }

.link-type {
  font-size: 11.5px;
  color: var(--fg-3);
  min-width: 80px;
}

.link-target {
  font-size: 13px;
  color: var(--fg-0);
  text-decoration: none;
  display: flex;
  align-items: center;
  gap: 6px;
}

.link-target:hover { color: v-bind(accent); }

.link-key {
  font-size: 10.5px;
  padding: 1px 5px;
  background: var(--bg-3);
  border-radius: 3px;
  color: var(--fg-2);
}

.tab-btns {
  display: flex;
  gap: 4px;
}

.tab-btn {
  background: none;
  border: none;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--fg-3);
  cursor: pointer;
}

.tab-btn.active {
  background: var(--bg-3);
  color: var(--fg-0);
  font-weight: 600;
}

.comment-item {
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
}

.comment-item:last-child { border-bottom: none; }

.comment-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.comment-author {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-1);
}

.comment-time {
  font-size: 11px;
  color: var(--fg-3);
}

.comment-body {
  font-size: 13px;
  line-height: 1.5;
  color: var(--fg-1);
  margin: 0;
  white-space: pre-wrap;
}

.comment-composer {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.comment-composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.comment-hint {
  font-size: 11px;
  color: var(--fg-4);
}

.comment-input-wrap {
  position: relative;
}

.comment-input {
  width: 100%;
  box-sizing: border-box;
  font-size: 13px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 14px;
  color: var(--fg-0);
  resize: vertical;
}

.comment-input:focus {
  outline: none;
  border-color: v-bind(accent);
}

.history-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
}

.history-item:last-child { border-bottom: none; }

.history-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.history-who {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-1);
}

.history-when {
  font-size: 11px;
  color: var(--fg-3);
}

.history-change {
  font-size: 12.5px;
  color: var(--fg-2);
  margin-top: 2px;
}

.history-field { font-weight: 600; color: var(--fg-1); }
.history-old { color: var(--err); }
.history-new { color: var(--ok); }

.empty-msg {
  color: var(--fg-3);
  font-size: 13px;
  padding: 16px 0;
}

/* Sidebar */
.task-sidebar {
  display: flex;
  flex-direction: column;
  gap: 0;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  padding: 0;
  height: fit-content;
  position: sticky;
  top: 20px;
  overflow: hidden;
}

.sidebar-group {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 14px 18px;
}

.sidebar-divider {
  height: 1px;
  background: color-mix(in oklch, var(--fg-0) 10%, transparent);
}

.sidebar-section {}

.sidebar-label {
  font-size: 10px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  font-weight: 600;
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sidebar-clickable {
  cursor: pointer;
  border-radius: var(--r-sm);
  padding: 4px 6px;
  margin: -4px -6px;
  transition: background 0.1s;
}

.sidebar-clickable:hover {
  background: color-mix(in oklch, var(--fg-0) 6%, transparent);
}

.sidebar-cancel-link {
  font-size: 11px;
  color: var(--fg-3);
  cursor: pointer;
}

.sidebar-cancel-link:hover {
  color: var(--fg-1);
}

.sidebar-person {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sidebar-value {
  font-size: 13px;
  color: var(--fg-0);
}

.sidebar-muted {
  font-size: 13px;
  color: var(--fg-3);
}

.sidebar-link {
  font-size: 13px;
  color: v-bind(accent);
  text-decoration: none;
}

.sidebar-link:hover { text-decoration: underline; }

.affected-projects {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.affected-project-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.remove-btn {
  background: none;
  border: none;
  color: var(--fg-3);
  cursor: pointer;
  font-size: 16px;
  padding: 0 2px;
  line-height: 1;
}

.remove-btn:hover {
  color: var(--err);
}

.sidebar-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sidebar-action {
  font-size: 11px;
  color: v-bind(accent);
  font-weight: 500;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
  transition: background 0.15s, opacity 0.15s;
  opacity: 0;
}

.sidebar-section:hover .sidebar-action,
.sidebar-row:hover .sidebar-action {
  opacity: 1;
}

.sidebar-action.always-visible {
  opacity: 1;
}

.sidebar-action:hover {
  background: color-mix(in oklch, v-bind(accent) 12%, transparent);
}

.sidebar-edit-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.sidebar-date-input {
  font-size: 12px;
  padding: 6px 8px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-0);
  width: 100%;
}

.sidebar-date-input:focus {
  outline: none;
  border-color: v-bind(accent);
}

.sidebar-edit-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sidebar-edit-row > :first-child {
  flex: 1;
  min-width: 0;
}

.sidebar-edit-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}

.sidebar-text-input {
  font-size: 12px;
  padding: 6px 8px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-0);
  width: 100%;
}

.sidebar-text-input:focus {
  outline: none;
  border-color: v-bind(accent);
}

.overdue {
  color: var(--err) !important;
  font-weight: 600;
}

.sidebar-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11px;
  color: var(--fg-4);
}

/* Work Log */
.worklog-header-row {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.worklog-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
}

.worklog-item:last-child { border-bottom: none; }

.worklog-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.worklog-who {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-1);
}

.worklog-date {
  font-size: 12px;
  color: var(--fg-3);
  margin-left: auto;
}

.worklog-delete {
  opacity: 0;
  padding: 4px;
  transition: opacity 0.15s;
}

.worklog-item:hover .worklog-delete {
  opacity: 1;
}

.worklog-comment {
  font-size: 12.5px;
  color: var(--fg-2);
  margin: 4px 0 0;
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.spacer { flex: 1; }

/* Resolution Picker */
.resolution-hint {
  font-size: 13px;
  color: var(--fg-2);
  margin: 0 0 12px;
}

.resolution-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.resolution-option {
  padding: 8px 14px;
  border-radius: 6px;
  font-size: 13px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  color: var(--fg-1);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.resolution-option:hover {
  border-color: v-bind(accent);
}

.resolution-option.selected {
  background: color-mix(in oklch, v-bind(accent) 15%, transparent);
  border-color: v-bind(accent);
  color: var(--fg-0);
  font-weight: 500;
}

.resolution-none {
  font-size: 13px;
  color: var(--fg-3);
  font-style: italic;
}
</style>
