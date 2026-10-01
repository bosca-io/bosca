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

// ─── Spec Fields ─────────────────────────────────────────────────────────────

const SPEC_FIELDS = `
  id key metadataId
  metadata { id name }
  status { id name category }
  ownerProfileId
  owner { id name }
  programId
  projectId
  project { id key name }
  parentSpecId
  parentSpec { id key }
  sortOrder
  childCount childDoneCount
  children(offset: 0, limit: 50) {
    id key metadataId
    metadata { id name }
    status { id name category }
    childCount childDoneCount
  }
  gitRepositoryId gitPath
  watcherProfileIds labelIds
  transitions { id name toStateId toState { status { category } } }
  requirementCount
  contexts { id specId contextType targetId label attributes addedByProfileId createdAt }
  taskGenerations(offset: 0, limit: 20) { id specId metadataVersion source agentSessionId generatedTaskIds createdAt createdByPrincipalId }
  comments(offset: 0, limit: 50) { id parentId specId profileId profile { id name } visibility created modified status content likes deleted }
  history(offset: 0, limit: 50) { id specId changedAt changedByPrincipalId changedByProfileId changes { fieldKey fromValue toValue } }
  deletedAt createdAt modifiedAt version
`

const specByIdGql = gql`
  query GetSpecById($id: UUID!) {
    workOps { specs { spec(id: $id) { ${SPEC_FIELDS} } } }
  }
`

const specByKeyGql = gql`
  query GetSpecByKey($key: String!) {
    workOps { specs { specByKey(key: $key) { ${SPEC_FIELDS} } } }
  }
`

// ─── Types ───────────────────────────────────────────────────────────────────

interface SpecContext {
  id: string
  specId: string
  contextType: string
  targetId: string
  label: string | null
  attributes: Record<string, unknown> | null
  addedByProfileId: string
  createdAt: string
}

interface TaskGeneration {
  id: string
  specId: string
  metadataVersion: number
  source: string
  agentSessionId: string | null
  generatedTaskIds: string[]
  createdAt: string
  createdByPrincipalId: string
}

interface SpecComment {
  id: number
  parentId: number | null
  specId: string
  profileId: string
  profile: { id: string; name: string } | null
  visibility: string
  created: string
  modified: string
  status: string
  content: string
  likes: number
  deleted: boolean
}

interface HistoryEntry {
  id: string
  specId: string
  changedAt: string
  changedByPrincipalId: string
  changedByProfileId: string | null
  changes: Array<{ fieldKey: string; fromValue: unknown; toValue: unknown }>
}

interface ChildSpec {
  id: string
  key: string
  metadataId: string
  metadata: { id: string; name: string } | null
  status: { id: string; name: string; category: string }
  childCount: number
  childDoneCount: number
}

interface SpecDetail {
  id: string
  key: string
  metadataId: string
  metadata: { id: string; name: string } | null
  status: { id: string; name: string; category: string }
  ownerProfileId: string
  owner: { id: string; name: string } | null
  programId: string | null
  projectId: string | null
  project: { id: string; key: string; name: string } | null
  parentSpecId: string | null
  parentSpec: { id: string; key: string } | null
  sortOrder: number
  childCount: number
  childDoneCount: number
  children: ChildSpec[]
  gitRepositoryId: string | null
  gitPath: string | null
  watcherProfileIds: string[]
  labelIds: string[]
  transitions: WorkflowTransition[]
  requirementCount: number
  contexts: SpecContext[]
  taskGenerations: TaskGeneration[]
  comments: SpecComment[]
  history: HistoryEntry[]
  deletedAt: string | null
  createdAt: string
  modifiedAt: string
  version: number
}

// ─── Load Spec ───────────────────────────────────────────────────────────────

const spec = ref<SpecDetail | null>(null)
const specName = ref('')
const isLoading = ref(true)
const fetchError = ref('')

async function loadSpec() {
  if (!spec.value) isLoading.value = true
  fetchError.value = ''
  try {
    if (isUuid.value) {
      const result = await gqlQuery<{ workOps: { specs: { spec: SpecDetail | null } } }>(
        specByIdGql, { id: rawId.value },
      )
      spec.value = result.workOps?.specs?.spec ?? null
    } else {
      const result = await gqlQuery<{ workOps: { specs: { specByKey: SpecDetail | null } } }>(
        specByKeyGql, { key: rawId.value },
      )
      spec.value = result.workOps?.specs?.specByKey ?? null
    }
    if (spec.value) specName.value = spec.value.metadata?.name ?? 'Untitled'
  } catch (e: unknown) {
    fetchError.value = e instanceof Error ? e.message : 'Failed to load spec'
    spec.value = null
  } finally {
    isLoading.value = false
  }
}

// ─── Document Editor ─────────────────────────────────────────────────────────

const metadataGql = gql`
  query GetSpecMetadata($id: UUID!) {
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

const specMetadata = ref<Metadata | null>(null)
const specProfile = ref<Profile>({ id: '', name: 'Unknown' } as Profile)
const editorRef = ref<InstanceType<typeof DocumentEditor> | null>(null)
const editorSaving = ref(false)
const editorStatus = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')

async function loadMetadata() {
  if (!spec.value) return
  try {
    const result = await gqlQuery<{
      profiles: { current: Profile }
      content: { metadata: Metadata | null }
    }>(metadataGql, { id: spec.value.metadataId })
    specMetadata.value = result.content?.metadata ?? null
    if (result.profiles?.current) specProfile.value = result.profiles.current
  } catch { /* metadata not available */ }
}

const collabItem = computed(() => specMetadata.value)
const collabProfile = computed(() => specProfile.value)
const collab = useCollaborationAndAttributes(collabItem, collabProfile)
const ydoc = collab.ydoc
const ydocReady = collab.ready

const saveDocumentGql = gql`
  mutation SaveSpecDocument($id: UUID!, $version: Int!, $document: DocumentInput!) {
    content { metadata { setMetadataDocument(id: $id, version: $version, document: $document) } }
  }
`

async function saveDocument() {
  if (!specMetadata.value || !spec.value || editorSaving.value) return
  const saveRevision = collab.ydoc.value ? captureYDocRevision(collab.ydoc.value) : null
  editorSaving.value = true
  editorStatus.value = 'saving'
  try {
    const doc = editorRef.value?.getDocument?.()
    if (doc) {
      await mutation(saveDocumentGql, {
        id: specMetadata.value.id,
        version: specMetadata.value.version,
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
  if (trimmed) specName.value = trimmed
}

const skipMetadataReload = ref(false)

onMounted(() => { loadSpec() })
watch(rawId, () => { loadSpec() })
watch(spec, () => {
  if (spec.value && !skipMetadataReload.value) loadMetadata()
  skipMetadataReload.value = false
})

async function refresh() { await loadSpec() }

// ─── Header ──────────────────────────────────────────────────────────────────

const headerTitle = computed(() => {
  if (specName.value) return specName.value
  if (isLoading.value) return 'Loading…'
  if (fetchError.value) return 'Error'
  return 'Spec not found'
})

const headerSubtitle = computed(() => {
  if (spec.value) {
    const parts = [spec.value.key]
    if (spec.value.project) parts.push(`in ${spec.value.project.name}`)
    return parts.join(' ')
  }
  if (fetchError.value) return fetchError.value
  return ''
})

// ─── Name Edit ───────────────────────────────────────────────────────────────

function startEditName() {
  nameDraft.value = specName.value
  editingName.value = true
}

async function saveName() {
  if (!spec.value || !nameDraft.value.trim()) return
  saving.value = true
  try {
    await mutation(gql`
      mutation UpdateMetadataName($id: UUID!, $metadata: MetadataInput!) {
        content { metadata { edit(id: $id, metadata: $metadata) { id } } }
      }
    `, { id: spec.value.metadataId, metadata: { name: nameDraft.value.trim(), contentType: 'bosca/v-document', languageTag: 'en' } })
    specName.value = nameDraft.value.trim()
    editingName.value = false
  } catch { toast.error('Failed to update name') }
  finally { saving.value = false }
}

// ─── Transitions & Resolutions ───────────────────────────────────────────────

interface WorkflowTransition {
  id: string
  name: string
  toStateId: string
  toState: { status: { category: string } } | null
}

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
  const t = spec.value?.transitions.find(tr => tr.id === transitionId)
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
  if (!spec.value) return
  saving.value = true
  try {
    await mutation(gql`
      mutation TransitionSpec($id: UUID!, $transitionId: UUID!, $expectedVersion: Long!, $resolutionId: UUID) {
        workOps { specs { transition(id: $id, transitionId: $transitionId, expectedVersion: $expectedVersion, resolutionId: $resolutionId) { id } } }
      }
    `, {
      id: spec.value.id,
      transitionId,
      expectedVersion: spec.value.version,
      resolutionId: resolutionId ?? null,
    })
    await refresh()
  } catch { toast.error('Failed to transition spec') }
  finally { saving.value = false }
}

// ─── Owner Edit ──────────────────────────────────────────────────────────────

const editingOwner = ref(false)

async function saveOwner(profileId: string) {
  if (!spec.value) return
  editingOwner.value = false
  saving.value = true
  try {
    await mutation(gql`
      mutation UpdateSpec($id: UUID!, $input: UpdateWorkOpsSpecInput!) {
        workOps { specs { update(id: $id, input: $input) { id } } }
      }
    `, {
      id: spec.value.id,
      input: { ownerProfileId: profileId, expectedVersion: spec.value.version },
    })
    await refresh()
  } catch { toast.error('Failed to update owner') }
  finally { saving.value = false }
}

// ─── Git Sync ────────────────────────────────────────────────────────────────

const gitSyncing = ref(false)

async function pushToGit() {
  if (!spec.value) return
  gitSyncing.value = true
  try {
    await mutation(gql`
      mutation PushToGit($specId: UUID!, $content: String!, $authorName: String!, $authorEmail: String!) {
        workOps { specs { pushToGit(specId: $specId, content: $content, authorName: $authorName, authorEmail: $authorEmail) } }
      }
    `, {
      specId: spec.value.id,
      content: specName.value,
      authorName: spec.value.owner?.name || 'Unknown',
      authorEmail: 'noreply@bosca.io',
    })
    toast.success('Pushed to git')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to push to git')
  } finally {
    gitSyncing.value = false
  }
}

// ─── Comments ────────────────────────────────────────────────────────────────

const commentDraft = ref('')

async function addComment() {
  if (!spec.value || !commentDraft.value.trim()) return
  saving.value = true
  try {
    await mutation(gql`
      mutation AddSpecComment($specId: UUID!, $input: WorkOpsSpecCommentInput!) {
        workOps { specComments { add(specId: $specId, input: $input) { id } } }
      }
    `, {
      specId: spec.value.id,
      input: { content: commentDraft.value.trim(), visibility: 'USER' },
    })
    commentDraft.value = ''
    await refresh()
  } catch { toast.error('Failed to add comment') }
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

function profileName(comment: SpecComment): string {
  return comment.profile?.name ?? comment.profileId.slice(0, 8)
}

// ─── Child progress ──────────────────────────────────────────────────────────

function childProgress(child: ChildSpec): number {
  if (!child.childCount) return 0
  return Math.round((child.childDoneCount / child.childCount) * 100)
}

const specProgress = computed(() => {
  if (!spec.value || !spec.value.childCount) return 0
  return Math.round((spec.value.childDoneCount / spec.value.childCount) * 100)
})
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Specs', spec?.key || rawId)"
        :title="headerTitle"
        :subtitle="headerSubtitle"
        @dblclick="spec && startEditName()"
      >
        <template #actions>
          <Button
            v-for="t in (spec?.transitions ?? [])"
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
            v-if="specMetadata"
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="editorSaving"
            @click="saveDocument"
          >
            Save
          </Button>
          <Button
            v-if="spec?.gitRepositoryId"
            size="sm"
            icon="git-branch"
            :disabled="gitSyncing"
            @click="pushToGit"
          >
            {{ gitSyncing ? 'Syncing…' : 'Push to Git' }}
          </Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading" class="loading-state">Loading spec…</div>

    <div v-else-if="spec" class="spec-layout">
      <div class="spec-main">
        <!-- Name Edit -->
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

        <!-- Document Editor -->
        <SectionCard title="Document" glass class="editor-card">
          <div class="editor-body">
            <ClientOnly>
              <DocumentEditor
                v-if="specMetadata && ydoc && collab.attributes"
                ref="editorRef"
                :metadata="specMetadata"
                :profile="specProfile"
                :attributes="collab.attributes"
                :ydoc="ydoc"
                :editable="ydocReady"
                :on-document="() => {}"
                :on-title-update="onDocTitleUpdate"
              />
              <div v-else-if="spec" class="editor-loading">
                Loading editor…
              </div>
            </ClientOnly>
          </div>
        </SectionCard>

        <!-- Child Specs Hierarchy -->
        <SectionCard v-if="spec.children.length > 0" title="Child Specs" glass>
          <template #right>
            <div v-if="spec.childCount > 0" class="hierarchy-progress">
              <div class="hierarchy-progress-track">
                <div class="hierarchy-progress-fill" :style="{ width: `${specProgress}%` }" />
              </div>
              <span class="hierarchy-progress-text">{{ spec.childDoneCount }}/{{ spec.childCount }}</span>
            </div>
          </template>
          <div class="card-body">
            <NuxtLink
              v-for="child in spec.children"
              :key="child.id"
              :to="`/workops/specs/${child.id}`"
              class="child-spec-row"
            >
              <Badge :color="STATUS_COLORS[child.status?.category] || '#6c7388'" class="child-status">
                {{ child.status?.name }}
              </Badge>
              <span class="child-key">{{ child.key }}</span>
              <span class="child-name">{{ child.metadata?.name || 'Untitled' }}</span>
              <div v-if="child.childCount > 0" class="child-progress-mini">
                <div class="child-progress-mini-track">
                  <div class="child-progress-mini-fill" :style="{ width: `${childProgress(child)}%` }" />
                </div>
              </div>
            </NuxtLink>
          </div>
        </SectionCard>

        <!-- Requirements -->
        <SpecRequirements :spec-id="spec.id" :accent="accent" @updated="refresh()" />

        <!-- Contexts -->
        <SpecContexts
          :spec-id="spec.id"
          :contexts="spec.contexts"
          :accent="accent"
          @updated="refresh()" />

        <!-- Task Generations -->
        <SpecTaskGenerations
          :spec-id="spec.id"
          :generations="spec.taskGenerations"
          :accent="accent"
          @updated="refresh()" />

        <!-- Tabs: Comments / History -->
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
            <!-- Comments -->
            <div v-show="activeTab === 'Comments'">
              <div v-for="c in spec.comments.filter(c => !c.deleted)" :key="c.id" class="comment-item">
                <div class="comment-header">
                  <Avatar :name="profileName(c)" :idx="0" :size="22" />
                  <span class="comment-author">{{ profileName(c) }}</span>
                  <span class="comment-time">{{ relativeTime(c.created) }}</span>
                </div>
                <p class="comment-body">{{ c.content }}</p>
              </div>
              <div v-if="!spec.comments.filter(c => !c.deleted).length" class="empty-msg">No comments yet.</div>
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

            <!-- History -->
            <div v-show="activeTab === 'History'">
              <div v-for="h in spec.history" :key="h.id" class="history-item">
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
              <div v-if="!spec.history.length" class="empty-msg">No history yet.</div>
            </div>
          </div>
        </SectionCard>
      </div>

      <!-- Sidebar -->
      <aside class="spec-sidebar">
        <div class="sidebar-header">Details</div>
        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Status</div>
            <Badge :color="STATUS_COLORS[spec.status.category] || '#6c7388'" solid>
              {{ spec.status.name }}
            </Badge>
          </div>
          <div class="sidebar-section">
            <div class="sidebar-label">Key</div>
            <span class="sidebar-value mono">{{ spec.key }}</span>
          </div>
        </div>

        <div class="sidebar-divider" />

        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Owner</div>
            <template v-if="editingOwner">
              <Select
                :model-value="spec.ownerProfileId"
                :on-search="searchProfiles"
                searchable
                placeholder="Search people…"
                size="sm"
                @update:model-value="saveOwner($event as string)"
              />
              <button class="sidebar-cancel-link" @click="editingOwner = false">cancel</button>
            </template>
            <template v-else>
              <div class="sidebar-clickable" @click="editingOwner = true">
                <div v-if="spec.owner" class="sidebar-person">
                  <Avatar :name="spec.owner.name" :idx="0" :size="20" />
                  <span class="sidebar-value">{{ spec.owner.name }}</span>
                </div>
                <span v-else class="sidebar-muted">Unassigned</span>
              </div>
            </template>
          </div>
        </div>

        <div class="sidebar-divider" />

        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Project</div>
            <NuxtLink v-if="spec.project" :to="`/workops/projects/${spec.project.id}`" class="sidebar-link">
              {{ spec.project.key }} — {{ spec.project.name }}
            </NuxtLink>
            <span v-else class="sidebar-muted">None</span>
          </div>
          <div v-if="spec.parentSpec" class="sidebar-section">
            <div class="sidebar-label">Parent Spec</div>
            <NuxtLink :to="`/workops/specs/${spec.parentSpec.id}`" class="sidebar-link">
              {{ spec.parentSpec.key }}
            </NuxtLink>
          </div>
        </div>

        <div class="sidebar-divider" />

        <!-- Progress -->
        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Requirements</div>
            <span class="sidebar-value mono">{{ spec.requirementCount }}</span>
          </div>
          <div class="sidebar-section">
            <div class="sidebar-label">Children</div>
            <div v-if="spec.childCount > 0" class="sidebar-progress">
              <div class="sidebar-progress-track">
                <div class="sidebar-progress-fill" :style="{ width: `${specProgress}%` }" />
              </div>
              <span class="sidebar-progress-text">{{ spec.childDoneCount }}/{{ spec.childCount }}</span>
            </div>
            <span v-else class="sidebar-value mono">0</span>
          </div>
        </div>

        <!-- Git -->
        <template v-if="spec.gitRepositoryId">
          <div class="sidebar-divider" />
          <div class="sidebar-group">
            <div class="sidebar-section">
              <div class="sidebar-label">
                <span>Git Sync</span>
                <Icon name="git-branch" :size="11" color="var(--fg-3)" />
              </div>
              <span v-if="spec.gitPath" class="sidebar-value mono git-path">{{ spec.gitPath }}</span>
              <Badge color="#34d99a">Linked</Badge>
            </div>
          </div>
        </template>

        <div class="sidebar-divider" />

        <div class="sidebar-group">
          <div class="sidebar-section">
            <div class="sidebar-label">Watchers</div>
            <span class="sidebar-value mono">{{ spec.watcherProfileIds.length }}</span>
          </div>
          <div class="sidebar-meta">
            <span>Created {{ relativeTime(spec.createdAt) }}</span>
            <span>Updated {{ relativeTime(spec.modifiedAt) }}</span>
            <span class="mono">v{{ spec.version }}</span>
          </div>
        </div>
      </aside>
    </div>

    <div v-else-if="fetchError" class="loading-state error-state">{{ fetchError }}</div>
    <div v-else class="loading-state">No spec found for this ID.</div>

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

/* ─── Save Status ─────────────────────────────────────────────────────────── */

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

/* ─── Document Editor ─────────────────────────────────────────────────────── */

.editor-card {
  min-height: 300px;
}

.editor-body {
  padding: 0;
}

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

.editor-body :deep(.editor-textarea:focus) {
  outline: none;
}

.editor-loading {
  color: var(--fg-3);
  font-size: 13px;
  padding: 40px 24px;
  text-align: center;
}


.spec-layout {
  display: grid;
  grid-template-columns: 1fr 280px;
  gap: 20px;
}

.spec-main {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-body {
  padding: 14px 16px;
}

/* ─── Name Edit ───────────────────────────────────────────────────────────── */

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

/* ─── Child Specs ─────────────────────────────────────────────────────────── */

.hierarchy-progress {
  display: flex;
  align-items: center;
  gap: 6px;
}

.hierarchy-progress-track {
  width: 48px;
  height: 4px;
  background: var(--bg-3);
  border-radius: 2px;
  overflow: hidden;
}

.hierarchy-progress-fill {
  height: 100%;
  background: #34d99a;
  border-radius: 2px;
  transition: width 0.3s ease;
}

.hierarchy-progress-text {
  font-size: 11px;
  color: var(--fg-3);
  font-family: var(--font-mono);
}

.child-spec-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 4px;
  border-bottom: 1px solid var(--line);
  text-decoration: none;
  color: var(--fg-0);
  transition: background 0.1s;
}

.child-spec-row:last-child { border-bottom: none; }

.child-spec-row:hover {
  background: color-mix(in oklch, var(--fg-0) 3%, transparent);
}

.child-status { flex-shrink: 0; }

.child-key {
  font-family: var(--font-mono);
  font-size: 10.5px;
  padding: 1px 5px;
  background: var(--bg-3);
  border-radius: 3px;
  color: var(--fg-2);
  flex-shrink: 0;
}

.child-name {
  font-size: 13px;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.child-progress-mini {
  width: 40px;
  flex-shrink: 0;
}

.child-progress-mini-track {
  height: 3px;
  background: var(--bg-3);
  border-radius: 2px;
  overflow: hidden;
}

.child-progress-mini-fill {
  height: 100%;
  background: #34d99a;
  border-radius: 2px;
}

/* ─── Tabs ────────────────────────────────────────────────────────────────── */

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

/* ─── Comments ────────────────────────────────────────────────────────────── */

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

/* ─── History ─────────────────────────────────────────────────────────────── */

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

/* ─── Sidebar ─────────────────────────────────────────────────────────────── */

.spec-sidebar {
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
  margin-top: 4px;
  cursor: pointer;
}

.sidebar-cancel-link:hover { color: var(--fg-1); }

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

.sidebar-progress {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sidebar-progress-track {
  flex: 1;
  height: 4px;
  background: var(--bg-3);
  border-radius: 2px;
  overflow: hidden;
}

.sidebar-progress-fill {
  height: 100%;
  background: #34d99a;
  border-radius: 2px;
  transition: width 0.3s ease;
}

.sidebar-progress-text {
  font-size: 11px;
  color: var(--fg-3);
  font-family: var(--font-mono);
}

.git-path {
  font-size: 11px;
  color: var(--fg-2);
  word-break: break-all;
  margin-bottom: 4px;
}

.sidebar-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11px;
  color: var(--fg-4);
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
