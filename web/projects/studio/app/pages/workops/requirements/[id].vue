<script setup lang="ts">
import gql from 'graphql-tag'
import type { Metadata, Profile } from '~/types/graphql'
import DocumentEditor from '~/components/document/DocumentEditor.vue'
import { captureYDocRevision, markYDocSaved } from '~/utils/editor/ydoc'

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { query: gqlQuery, mutation } = useGraphQL()
const { searchProfiles } = useProfileSearch()
const toast = useToast()

const rawId = computed(() => route.params.id as string)
const isUuid = computed(() => /^[0-9a-f]{8}-/.test(rawId.value))
const activeTab = ref('Comments')
const saving = ref(false)
const editingName = ref(false)
const nameDraft = ref('')

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

const REQ_FIELDS = `
  id key metadataId
  parentType parentId
  status { id name category }
  priority { id name displayOrder }
  assignee { id name }
  assigneeProfileId
  taskId
  task {
    id key summary version
    status { id name category }
    transitions { id name toStateId toState { status { category } } }
  }
  sortOrder labelIds
  history(offset: 0, limit: 50) { id requirementId changedAt changedByPrincipalId changedByProfileId changes { fieldKey fromValue toValue } }
  deletedAt createdAt modifiedAt
  createdByPrincipalId modifiedByPrincipalId
  version
`

const reqByIdGql = gql`
  query GetRequirementById($id: UUID!) {
    workOps { requirements { requirement(id: $id) { ${REQ_FIELDS} } } }
  }
`

const reqByKeyGql = gql`
  query GetRequirementByKey($key: String!) {
    workOps { requirements { requirementByKey(key: $key) { ${REQ_FIELDS} } } }
  }
`

// ─── Types ───────────────────────────────────────────────────────────────────

interface HistoryEntry {
  id: string
  requirementId: string
  changedAt: string
  changedByPrincipalId: string
  changedByProfileId: string | null
  changes: Array<{ fieldKey: string; fromValue: unknown; toValue: unknown }>
}

interface WorkflowTransition {
  id: string
  name: string
  toStateId: string
  toState: { status: { category: string } } | null
}

interface LinkedTask {
  id: string
  key: string
  summary: string
  version: number
  status: { id: string; name: string; category: string }
  transitions: WorkflowTransition[]
}

interface RequirementDetail {
  id: string
  key: string
  metadataId: string
  parentType: string
  parentId: string
  status: { id: string; name: string; category: string }
  priority: { id: string; name: string; displayOrder: number }
  assignee: { id: string; name: string } | null
  assigneeProfileId: string | null
  taskId: string | null
  task: LinkedTask | null
  sortOrder: number
  labelIds: string[]
  history: HistoryEntry[]
  deletedAt: string | null
  createdAt: string
  modifiedAt: string
  createdByPrincipalId: string
  modifiedByPrincipalId: string
  version: number
}

// ─── Load Requirement ────────────────────────────────────────────────────────

const req = ref<RequirementDetail | null>(null)
const reqName = ref('')
const parentName = ref('')
const parentKey = ref('')
const isLoading = ref(true)
const fetchError = ref('')

async function loadRequirement() {
  if (!req.value) isLoading.value = true
  fetchError.value = ''
  try {
    if (isUuid.value) {
      const result = await gqlQuery<{ workOps: { requirements: { requirement: RequirementDetail | null } } }>(
        reqByIdGql, { id: rawId.value },
      )
      req.value = result.workOps?.requirements?.requirement ?? null
    } else {
      const result = await gqlQuery<{ workOps: { requirements: { requirementByKey: RequirementDetail | null } } }>(
        reqByKeyGql, { key: rawId.value },
      )
      req.value = result.workOps?.requirements?.requirementByKey ?? null
    }
    if (req.value) {
      await resolveReqName()
      await resolveParentName()
      await loadComments()
    }
  } catch (e: unknown) {
    fetchError.value = e instanceof Error ? e.message : 'Failed to load requirement'
    req.value = null
  } finally {
    isLoading.value = false
  }
}

async function resolveReqName() {
  if (!req.value) return
  try {
    const result = await gqlQuery<{ content: { metadata: { name: string } | null } }>(gql`
      query($id: UUID!) { content { metadata(id: $id) { name } } }
    `, { id: req.value.metadataId })
    reqName.value = result.content?.metadata?.name ?? 'Untitled'
  } catch { reqName.value = 'Untitled' }
}

async function resolveParentName() {
  if (!req.value) return
  try {
    if (req.value.parentType === 'SPEC') {
      const result = await gqlQuery<{ workOps: { specs: { spec: { key: string; metadataId: string } | null } } }>(gql`
        query($id: UUID!) { workOps { specs { spec(id: $id) { key metadataId } } } }
      `, { id: req.value.parentId })
      const spec = result.workOps?.specs?.spec
      if (spec) {
        const mr = await gqlQuery<{ content: { metadata: { name: string } | null } }>(gql`
          query($id: UUID!) { content { metadata(id: $id) { name } } }
        `, { id: spec.metadataId })
        parentName.value = mr.content?.metadata?.name ?? spec.key
      }
    } else if (req.value.parentType === 'TASK') {
      const result = await gqlQuery<{ workOps: { tasks: { task: { key: string; summary: string } | null } } }>(gql`
        query($id: UUID!) { workOps { tasks { task(id: $id) { key summary } } } }
      `, { id: req.value.parentId })
      const task = result.workOps?.tasks?.task
      if (task) {
        parentKey.value = task.key
        parentName.value = task.summary
      }
    }
  } catch { parentName.value = '' }
}

// ─── Document Editor ─────────────────────────────────────────────────────────

const metadataGql = gql`
  query GetRequirementMetadata($id: UUID!) {
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

const reqMetadata = ref<Metadata | null>(null)
const reqProfile = ref<Profile>({ id: '', name: 'Unknown' } as Profile)
const editorRef = ref<InstanceType<typeof DocumentEditor> | null>(null)
const editorSaving = ref(false)
const editorStatus = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')

async function loadMetadata() {
  if (!req.value) return
  try {
    const result = await gqlQuery<{
      profiles: { current: Profile }
      content: { metadata: Metadata | null }
    }>(metadataGql, { id: req.value.metadataId })
    reqMetadata.value = result.content?.metadata ?? null
    if (result.profiles?.current) reqProfile.value = result.profiles.current
  } catch { /* metadata not available */ }
}

const collabItem = computed(() => reqMetadata.value)
const collabProfile = computed(() => reqProfile.value)
const collab = useCollaborationAndAttributes(collabItem, collabProfile)
const ydoc = collab.ydoc
const ydocReady = collab.ready

const saveDocumentGql = gql`
  mutation SaveReqDocument($id: UUID!, $version: Int!, $document: DocumentInput!) {
    content { metadata { setMetadataDocument(id: $id, version: $version, document: $document) } }
  }
`

async function saveDocument() {
  if (!reqMetadata.value || !req.value || editorSaving.value) return
  const saveRevision = collab.ydoc.value ? captureYDocRevision(collab.ydoc.value) : null
  editorSaving.value = true
  editorStatus.value = 'saving'
  try {
    const doc = editorRef.value?.getDocument?.()
    if (doc) {
      await mutation(saveDocumentGql, {
        id: reqMetadata.value.id,
        version: reqMetadata.value.version,
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

const saveStatusLabel = computed(() => {
  switch (editorStatus.value) {
    case 'saving': return 'Saving…'
    case 'saved': return 'Saved'
    case 'error': return 'Save failed'
    default: return hasUnsavedChanges.value ? 'Draft' : ''
  }
})

const saveStatusDot = computed(() => {
  switch (editorStatus.value) {
    case 'saved': return 'ok'
    case 'error': return 'err'
    default: return hasUnsavedChanges.value ? 'warn' : 'info'
  }
})

function onDocTitleUpdate(title: string) {
  const trimmed = title.trim()
  if (trimmed) reqName.value = trimmed
}

const skipMetadataReload = ref(false)

onMounted(() => { loadRequirement() })
watch(rawId, () => { loadRequirement() })
watch(req, () => {
  if (req.value && !skipMetadataReload.value) loadMetadata()
  skipMetadataReload.value = false
})

async function refresh() { await loadRequirement() }

// ─── Header ──────────────────────────────────────────────────────────────────

const breadcrumb = computed(() => {
  if (!req.value) return buildBreadcrumb('Work Ops', 'Tasks', rawId.value)
  const parentLabel = req.value.parentType === 'SPEC' ? 'Specs' : 'Tasks'
  if (parentName.value) {
    return buildBreadcrumb(
      'Work Ops',
      parentLabel,
      { label: parentName.value, to: parentRoute.value! },
      req.value.key,
    )
  }
  return buildBreadcrumb('Work Ops', parentLabel, req.value.key)
})

const headerTitle = computed(() => {
  if (reqName.value) return reqName.value
  if (isLoading.value) return 'Loading…'
  if (fetchError.value) return 'Error'
  return 'Requirement not found'
})

const headerSubtitle = computed(() => {
  if (req.value) {
    const parts = [req.value.key]
    if (parentName.value) parts.push(`in ${parentName.value}`)
    return parts.join(' ')
  }
  if (fetchError.value) return fetchError.value
  return ''
})

// ─── Name Edit ───────────────────────────────────────────────────────────────

function startEditName() {
  nameDraft.value = reqName.value
  editingName.value = true
}

async function saveName() {
  if (!req.value || !nameDraft.value.trim()) return
  saving.value = true
  try {
    await mutation(gql`
      mutation UpdateMetadataName($id: UUID!, $metadata: MetadataInput!) {
        content { metadata { edit(id: $id, metadata: $metadata) { id } } }
      }
    `, { id: req.value.metadataId, metadata: { name: nameDraft.value.trim(), contentType: 'bosca/v-document', languageTag: 'en' } })
    reqName.value = nameDraft.value.trim()
    editingName.value = false
  } catch { toast.error('Failed to update name') }
  finally { saving.value = false }
}

// ─── Transitions & Resolutions ───────────────────────────────────────────────
// A requirement's status mirrors its linked task, so status changes go
// through the task's workflow transition.

interface Resolution { id: string; name: string }

const resolutions = ref<Resolution[]>([])
const pendingTransitionId = ref<string | null>(null)
const showResolutionPicker = ref(false)
const selectedResolutionId = ref('')

async function loadResolutions() {
  if (resolutions.value.length) return
  try {
    const result = await gqlQuery<{ workOps: { tasks: { resolutions: Resolution[] } } }>(gql`
      query { workOps { tasks { resolutions { id name } } } }
    `, {})
    resolutions.value = result.workOps?.tasks?.resolutions ?? []
  } catch { /* ignore */ }
}

function isTerminalTransition(transitionId: string): boolean {
  const t = req.value?.task?.transitions.find(tr => tr.id === transitionId)
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
  const task = req.value?.task
  if (!task) return
  saving.value = true
  try {
    await mutation(gql`
      mutation TransitionRequirementTask($id: UUID!, $transitionId: UUID!, $expectedVersion: Long!, $resolutionId: UUID) {
        workOps { tasks { transition(id: $id, transitionId: $transitionId, expectedVersion: $expectedVersion, resolutionId: $resolutionId) { id } } }
      }
    `, {
      id: task.id,
      transitionId,
      expectedVersion: task.version,
      resolutionId: resolutionId ?? null,
    })
    await refresh()
  } catch { toast.error('Failed to transition requirement') }
  finally { saving.value = false }
}

// ─── Priority Edit ───────────────────────────────────────────────────────────

const editingPriority = ref(false)
const allPriorities = ref<Array<{ id: string; name: string; displayOrder: number }>>([])

async function loadPriorities() {
  if (allPriorities.value.length) return
  try {
    const result = await gqlQuery<{
      workOps: { tasks: { priorities: Array<{ id: string; name: string; displayOrder: number }> } }
    }>(gql`query { workOps { tasks { priorities { id name displayOrder } } } }`, {})
    allPriorities.value = (result.workOps?.tasks?.priorities ?? []).sort((a, b) => a.displayOrder - b.displayOrder)
  } catch { /* ignore */ }
}

function startEditPriority() {
  loadPriorities()
  editingPriority.value = true
}

async function savePriority(priorityId: string) {
  if (!req.value) return
  editingPriority.value = false
  saving.value = true
  try {
    await mutation(gql`
      mutation UpdateRequirement($id: UUID!, $input: UpdateWorkOpsRequirementInput!) {
        workOps { requirements { update(id: $id, input: $input) { id } } }
      }
    `, {
      id: req.value.id,
      input: { priorityId, expectedVersion: req.value.version },
    })
    await refresh()
  } catch { toast.error('Failed to update priority') }
  finally { saving.value = false }
}

// ─── Assignee Edit ───────────────────────────────────────────────────────────

const editingAssignee = ref(false)

async function saveAssignee(profileId: string) {
  if (!req.value) return
  editingAssignee.value = false
  saving.value = true
  try {
    await mutation(gql`
      mutation UpdateRequirement($id: UUID!, $input: UpdateWorkOpsRequirementInput!) {
        workOps { requirements { update(id: $id, input: $input) { id } } }
      }
    `, {
      id: req.value.id,
      input: {
        assigneeProfileId: profileId || null,
        clearAssignee: !profileId,
        expectedVersion: req.value.version,
      },
    })
    await refresh()
  } catch { toast.error('Failed to update assignee') }
  finally { saving.value = false }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

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

const parentRoute = computed(() => {
  if (!req.value) return null
  return req.value.parentType === 'SPEC'
    ? `/workops/specs/${req.value.parentId}`
    : `/workops/tasks/${req.value.parentId}`
})


// ─── Comments ───────────────────────────────────────────────────────────────

interface RequirementCommentEntry {
  id: number
  content: string
  profileId: string
  profile: { id: string; name: string } | null
  created: string
  modified: string
  visibility: string
  likes: number
}

const comments = ref<RequirementCommentEntry[]>([])
const commentDraft = ref('')

async function loadComments() {
  if (!req.value) return
  try {
    const result = await gqlQuery<{
      workOps: { requirementComments: { forRequirement: RequirementCommentEntry[] } }
    }>(gql`
      query($requirementId: UUID!) {
        workOps { requirementComments { forRequirement(requirementId: $requirementId, offset: 0, limit: 50) {
          id content profileId profile { id name } created modified visibility likes
        } } }
      }
    `, { requirementId: req.value.id })
    comments.value = result.workOps?.requirementComments?.forRequirement ?? []
  } catch {
    comments.value = []
  }
}

function commentProfileName(c: RequirementCommentEntry): string {
  return c.profile?.name ?? c.profileId.slice(0, 8)
}

async function addComment() {
  if (!req.value || !commentDraft.value.trim()) return
  saving.value = true
  try {
    await mutation(gql`
      mutation AddRequirementComment($requirementId: UUID!, $input: WorkOpsRequirementCommentInput!) {
        workOps { requirementComments { add(requirementId: $requirementId, input: $input) { id } } }
      }
    `, {
      requirementId: req.value.id,
      input: { content: commentDraft.value.trim(), visibility: 'USER' },
    })
    commentDraft.value = ''
    await loadComments()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to add comment')
  } finally {
    saving.value = false
  }
}

watch(activeTab, (tab) => {
  if (tab === 'Comments' && req.value && comments.value.length === 0) {
    loadComments()
  }
})
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="breadcrumb"
        :title="headerTitle"
        :subtitle="headerSubtitle"
        @dblclick="req && startEditName()"
      >
        <template #actions>
          <Button
            v-for="t in (req?.task?.transitions ?? [])"
            :key="t.id"
            size="sm"
            :accent="accent"
            :disabled="saving"
            @click="initiateTransition(t.id)"
          >
            → {{ t.name || 'Transition' }}
          </Button>
          <span v-if="saveStatusLabel" class="save-status">
            <span :class="['dot', saveStatusDot]" />
            {{ saveStatusLabel }}
          </span>
          <Button
            v-if="reqMetadata"
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="editorSaving"
            @click="saveDocument"
          >
            Save
          </Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading" class="loading-state">Loading requirement…</div>

    <div v-else-if="req" class="req-layout">
      <div class="req-main">
        <div v-if="editingName" class="name-edit-block">
          <input
            v-model="nameDraft"
            class="name-input"
            autofocus
            @keydown.enter="saveName"
            @keydown.escape="editingName = false"
          >
          <div class="name-actions">
            <Button size="sm" @click="editingName = false">Cancel</Button>
            <Button
              size="sm"
              primary
              :accent="accent"
              :disabled="saving"
              @click="saveName">Save</Button>
          </div>
        </div>

        <SectionCard title="Document" glass class="editor-card">
          <div class="editor-body">
            <ClientOnly>
              <DocumentEditor
                v-if="reqMetadata && ydoc && collab.attributes"
                ref="editorRef"
                :metadata="reqMetadata"
                :profile="reqProfile"
                :attributes="collab.attributes"
                :ydoc="ydoc"
                :editable="ydocReady"
                :on-document="() => {}"
                :on-title-update="onDocTitleUpdate"
              />
              <div v-else-if="req" class="editor-loading">
                Loading editor…
              </div>
            </ClientOnly>
          </div>
        </SectionCard>

        <SectionCard :title="activeTab" glass>
          <template #right>
            <div class="tab-btns">
              <button
                v-for="t in ['Comments', 'History']"
                :key="t"
                :class="['tab-btn', { active: activeTab === t }]"
                @click="activeTab = t"
              >{{ t }}</button>
            </div>
          </template>

          <div class="card-body">
            <div v-show="activeTab === 'Comments'">
              <div v-for="c in comments" :key="c.id" class="comment-item">
                <div class="comment-header">
                  <Avatar :name="commentProfileName(c)" :idx="0" :size="22" />
                  <span class="comment-author">{{ commentProfileName(c) }}</span>
                  <span class="comment-time">{{ relativeTime(c.created) }}</span>
                </div>
                <p class="comment-body">{{ c.content }}</p>
              </div>
              <div v-if="!comments.length" class="empty-msg">No comments yet.</div>
              <div class="comment-composer">
                <textarea
                  v-model="commentDraft"
                  class="comment-input"
                  rows="3"
                  placeholder="Add a comment…"
                />
                <div class="comment-composer-footer">
                  <span class="comment-hint">Markdown supported</span>
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

            <div v-show="activeTab === 'History'">
              <div v-for="h in req.history" :key="h.id" class="history-item">
                <div class="history-header">
                  <span class="history-who">{{ h.changedByProfileId?.slice(0, 8) || 'System' }}</span>
                  <span class="history-when">{{ relativeTime(h.changedAt) }}</span>
                </div>
                <div v-for="(ch, ci) in h.changes" :key="ci" class="history-change">
                  <span class="history-field">{{ ch.fieldKey }}</span>:
                  <span class="history-old">{{ ch.fromValue ?? '—' }}</span> →
                  <span class="history-new">{{ ch.toValue ?? '—' }}</span>
                </div>
              </div>
              <div v-if="!req.history.length" class="empty-msg">No history yet.</div>
            </div>
          </div>
        </SectionCard>
      </div>

      <aside class="req-sidebar">
        <div class="sidebar-header">Details</div>
        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Status</div>
            <Badge :color="STATUS_COLORS[req.status.category] || '#6c7388'" solid>
              {{ req.status.name }}
            </Badge>
          </div>
          <div class="sidebar-section">
            <div class="sidebar-label">Key</div>
            <span class="sidebar-value mono">{{ req.key }}</span>
          </div>
        </div>

        <div class="sidebar-divider" />

        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Priority</div>
            <template v-if="editingPriority">
              <Select
                :model-value="req.priority.id"
                :options="allPriorities.map(p => ({ value: p.id, label: p.name }))"
                size="sm"
                @update:model-value="savePriority($event as string)"
              />
              <button class="sidebar-cancel-link" @click="editingPriority = false">cancel</button>
            </template>
            <template v-else>
              <div class="sidebar-clickable" @click="startEditPriority">
                <Badge :color="PRIORITY_COLORS[req.priority.displayOrder] || '#6c7388'" solid>
                  {{ req.priority.name }}
                </Badge>
              </div>
            </template>
          </div>
        </div>

        <div class="sidebar-divider" />

        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Assignee</div>
            <template v-if="editingAssignee">
              <Select
                :model-value="req.assigneeProfileId ?? ''"
                :on-search="searchProfiles"
                searchable
                placeholder="Search people…"
                size="sm"
                @update:model-value="saveAssignee($event as string)"
              />
              <button class="sidebar-cancel-link" @click="editingAssignee = false">cancel</button>
            </template>
            <template v-else>
              <div class="sidebar-clickable" @click="editingAssignee = true">
                <div v-if="req.assignee" class="sidebar-person">
                  <Avatar :name="req.assignee.name" :idx="0" :size="20" />
                  <span class="sidebar-value">{{ req.assignee.name }}</span>
                </div>
                <span v-else class="sidebar-muted">Unassigned</span>
              </div>
            </template>
          </div>
        </div>

        <div class="sidebar-divider" />

        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Parent {{ req.parentType === 'SPEC' ? 'Spec' : 'Task' }}</div>
            <NuxtLink v-if="parentRoute" :to="parentRoute" class="generated-task-link">
              <span v-if="parentKey" class="generated-task-key">{{ parentKey }}</span>
              <span class="generated-task-summary">{{ parentName || req.parentId.slice(0, 8) }}</span>
            </NuxtLink>
          </div>
          <div v-if="req.task" class="sidebar-section">
            <div class="sidebar-label">Task</div>
            <NuxtLink :to="`/workops/tasks/${req.task.id}`" class="generated-task-link">
              <span class="generated-task-key">{{ req.task.key }}</span>
              <span class="generated-task-summary">{{ req.task.summary }}</span>
              <Badge :color="STATUS_COLORS[req.task.status.category] || '#6c7388'" style="margin-left: auto; flex-shrink: 0;">
                {{ req.task.status.name }}
              </Badge>
            </NuxtLink>
          </div>
        </div>

        <div class="sidebar-divider" />

        <div class="sidebar-group">
          <div class="sidebar-meta">
            <span>Created {{ relativeTime(req.createdAt) }}</span>
            <span>Updated {{ relativeTime(req.modifiedAt) }}</span>
            <span class="mono">v{{ req.version }}</span>
          </div>
        </div>
      </aside>
    </div>

    <div v-else-if="fetchError" class="loading-state error-state">{{ fetchError }}</div>
    <div v-else class="loading-state">No requirement found for this ID.</div>

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
.loading-state {
  color: var(--fg-3);
  text-align: center;
  padding: 60px 0;
  font-size: 14px;
}

.error-state { color: var(--err); }

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

.req-layout {
  display: grid;
  grid-template-columns: 1fr 280px;
  gap: 20px;
}

.req-main {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-body { padding: 14px 16px; }

.name-edit-block {
  padding: 16px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
}

.name-input {
  width: 100%;
  font-size: 16px;
  font-weight: 600;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px 14px;
  color: var(--fg-0);
}

.name-input:focus {
  outline: none;
  border-color: v-bind(accent);
}

.name-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  justify-content: flex-end;
}

.tab-btns { display: flex; gap: 4px; }

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

.history-who { font-size: 12px; font-weight: 600; color: var(--fg-1); }
.history-when { font-size: 11px; color: var(--fg-3); }

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

.req-sidebar {
  display: flex;
  flex-direction: column;
  gap: 0;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  padding: 0;
  height: fit-content;
  position: sticky;
  top: 0;
  overflow: hidden;
}

.sidebar-header {
  font-size: 13px;
  font-weight: 600;
  padding: 12px 16px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 42%, transparent);
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

.sidebar-label {
  font-size: 10px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  font-weight: 600;
  margin-bottom: 4px;
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
  margin-top: 4px;
  cursor: pointer;
}

.sidebar-cancel-link:hover { color: var(--fg-1); }

.sidebar-person {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sidebar-value { font-size: 13px; color: var(--fg-0); }
.sidebar-muted { font-size: 13px; color: var(--fg-3); }

.sidebar-link {
  font-size: 13px;
  color: v-bind(accent);
  text-decoration: none;
}

.sidebar-link:hover { text-decoration: underline; }

.generated-tasks {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.generated-task-link {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: v-bind(accent);
  text-decoration: none;
  padding: 3px 0;
  overflow: hidden;
}

.generated-task-link:hover { text-decoration: underline; }

.generated-task-key {
  font-family: var(--font-mono);
  font-size: 10px;
  padding: 1px 5px;
  background: var(--bg-3);
  border-radius: 3px;
  color: var(--fg-3);
  flex-shrink: 0;
}

.generated-task-summary {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11px;
  color: var(--fg-4);
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

.comment-input {
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

/* ─── Resolution Picker ───────────────────────────────────────────────────── */

.spacer { flex: 1; }

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
