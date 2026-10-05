<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation, query: gqlQuery } = useGraphQL()
const toast = useToast()

const segmentId = computed(() => route.params.id as string)

const STATUS_COLORS: Record<string, string> = {
  ACTIVE: '#34d99a', DRAFT: '#6c7388', PAUSED: '#ffb547', ARCHIVED: '#6c7388',
}

const segmentGql = gql`
  query GetSegmentById($id: UUID!) {
    segments { segment(id: $id) {
      id name description type status memberCount
      analyticsQueryId evaluationSchedule lastEvaluated
      configuration created modified
    } }
  }
`

const membersGql = gql`
  query GetSegmentMembers($id: UUID!, $offset: Long!, $limit: Int!) {
    segments { segment(id: $id) { members(offset: $offset, limit: $limit) {
      profile { id name slug }
      addedAt
    } } }
  }
`

const editGql = gql`
  mutation EditSegment($id: UUID!, $segment: SegmentInput!) {
    segments { edit(id: $id, segment: $segment) { id } }
  }
`

const evaluateGql = gql`
  mutation EvaluateSegment($id: UUID!) {
    segments { evaluate(segmentId: $id) { lastEvaluated memberCount } }
  }
`

const deleteGql = gql`
  mutation DeleteSegment($id: UUID!) {
    segments { delete(id: $id) }
  }
`

const addMembersGql = gql`
  mutation AddSegmentMembers($segmentId: UUID!, $profileIds: [UUID!]!) {
    segments { addMembers(segmentId: $segmentId, profileIds: $profileIds) }
  }
`

const removeMembersGql = gql`
  mutation RemoveSegmentMembers($segmentId: UUID!, $profileIds: [UUID!]!) {
    segments { removeMembers(segmentId: $segmentId, profileIds: $profileIds) }
  }
`

const removeFromSegmentGql = gql`
  mutation RemoveFromSegment($segmentId: UUID!, $removeSegmentId: UUID!) {
    segments { removeFromSegment(segmentId: $segmentId, removeSegmentId: $removeSegmentId) { memberCount } }
  }
`

const analyticsQueriesGql = gql`
  query GetAnalyticsQueries { analytics { queries { all { id name } } } }
`

const searchProfilesGql = gql`
  query SearchProfilesForSegment($query: String!, $filter: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        offset: $offset
        storageSystemName: "Admin Search Index"
        limit: $limit
      }) {
        documents { profile { id name } }
      }
    }
  }
`

const allSegmentsGql = gql`
  query GetAllSegmentsForSubtract { segments { all(limit: 100, offset: 0) { id name memberCount } } }
`

const runPipelineGql = gql`
  mutation RunSegmentPipeline($segmentId: UUID!, $pipelineId: UUID!) {
    segments { runPipeline(segmentId: $segmentId, pipelineId: $pipelineId) }
  }
`

const pipelinesGql = gql`
  query GetPipelinesForSegmentRun { pipelines { all { id name } } }
`

interface Segment {
  id: string; name: string; description: string | null; type: string; status: string
  memberCount: number; analyticsQueryId: string | null; evaluationSchedule: string | null
  lastEvaluated: string | null; configuration: any; created: string; modified: string
}

interface SegmentMember { profile: { id: string; name: string; slug: string }; addedAt: string }
interface AnalyticsQuery { id: string; name: string }
interface SimpleSegment { id: string; name: string; memberCount: number }
interface PipelineOption { id: string; name: string }

const { data, status, refresh } = useAsyncQuery<{
  segments: { segment: Segment | null }
}>('segment-detail', segmentGql, { id: segmentId })

const segment = computed(() => data.value?.segments?.segment ?? null)
const isLoading = computed(() => status.value === 'pending')
const isStatic = computed(() => segment.value?.type === 'STATIC')
const isDynamic = computed(() => segment.value?.type === 'DYNAMIC')

const membersOffset = ref(0)
const membersLimit = ref(20)

const { data: membersData, refresh: refreshMembers } = useAsyncQuery<{
  segments: { members: SegmentMember[] }
}>('segment-members', membersGql, { id: segmentId, offset: membersOffset, limit: membersLimit })

const members = computed(() => (membersData.value?.segments as any)?.segment?.members ?? [])

// Edit state
const editName = ref('')
const editDescription = ref('')
const editStatus = ref('')
const editType = ref('')
const editQueryId = ref<string | null>(null)
const editSchedule = ref('')
const saving = ref(false)
const evaluating = ref(false)
const deleting = ref(false)
const deleteModalOpen = ref(false)
const editModalOpen = ref(false)

// Member management state
const addMemberModalOpen = ref(false)
const profileSearch = ref('')
const profileSearchResults = ref<{ id: string; name: string; slug: string }[]>([])
const searchingProfiles = ref(false)
const addingMembers = ref(false)
const selectedProfileIds = ref<Set<string>>(new Set())
const removingMemberId = ref<string | null>(null)

// Bulk ops state
const removeFromSegmentModalOpen = ref(false)
const subtractSegmentId = ref<string | null>(null)
const removingFromSegment = ref(false)
const availableSegments = ref<SimpleSegment[]>([])

// Run pipeline state
const runPipelineModalOpen = ref(false)
const availablePipelines = ref<PipelineOption[]>([])
const selectedPipelineId = ref<string | null>(null)
const runningPipeline = ref(false)
const loadingPipelines = ref(false)
const pipelineOptions = computed(() => availablePipelines.value.map(p => ({ value: p.id, label: p.name })))

// Analytics queries for dropdown
const analyticsQueries = ref<AnalyticsQuery[]>([])

watch(segment, (s) => {
  if (!s) return
  editName.value = s.name
  editDescription.value = s.description ?? ''
  editStatus.value = s.status
  editType.value = s.type
  editQueryId.value = s.analyticsQueryId
  editSchedule.value = s.evaluationSchedule ?? ''
}, { immediate: true })

async function loadAnalyticsQueries() {
  try {
    const result = await gqlQuery<{ analytics?: { queries?: { all?: AnalyticsQuery[] } } }>(analyticsQueriesGql)
    analyticsQueries.value = result?.analytics?.queries?.all ?? []
  } catch (e: any) {
    toast.error(e?.message || 'Failed to load analytics queries')
  }
}

async function onOpenEditModal() {
  editModalOpen.value = true
  await loadAnalyticsQueries()
}

async function onSave() {
  saving.value = true
  try {
    await gqlMutation(editGql, {
      id: segmentId.value,
      segment: {
        name: editName.value,
        description: editDescription.value,
        type: editType.value,
        status: editStatus.value,
        analyticsQueryId: editQueryId.value || null,
        evaluationSchedule: editSchedule.value || null,
      },
    })
    editModalOpen.value = false
    toast.success('Segment updated')
    refresh()
  } catch (e: any) {
    toast.error(e?.message || 'Failed to update')
  } finally {
    saving.value = false
  }
}

async function onEvaluate() {
  evaluating.value = true
  try {
    await gqlMutation(evaluateGql, { id: segmentId.value })
    toast.success('Segment evaluated')
    refresh()
    refreshMembers()
  } catch (e: any) {
    toast.error(e?.message || 'Failed to evaluate')
  } finally {
    evaluating.value = false
  }
}

async function onDelete() {
  deleting.value = true
  try {
    await gqlMutation(deleteGql, { id: segmentId.value })
    toast.success('Segment deleted')
    router.push('/audience/segments')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to delete')
  } finally {
    deleting.value = false
  }
}

// Member management
let searchTimer: ReturnType<typeof setTimeout> | undefined
function onProfileSearchInput() {
  clearTimeout(searchTimer)
  if (!profileSearch.value.trim()) { profileSearchResults.value = []; return }
  searchTimer = setTimeout(() => onSearchProfiles(), 300)
}

async function onSearchProfiles() {
  if (!profileSearch.value.trim()) { profileSearchResults.value = []; return }
  searchingProfiles.value = true
  try {
    const result = await gqlQuery<any>(searchProfilesGql, {
      query: profileSearch.value || '*',
      filter: '_type = "profile" AND contentType = "bosca/v-profile-generic"',
      limit: 20,
      offset: 0,
    })
    profileSearchResults.value = (result?.search?.search?.documents ?? [])
      .map((d: any) => d.profile)
      .filter((p: any) => p?.id)
  } catch { profileSearchResults.value = [] }
  finally { searchingProfiles.value = false }
}

function toggleProfileSelection(id: string) {
  const next = new Set(selectedProfileIds.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  selectedProfileIds.value = next
}

async function onAddMembers() {
  if (selectedProfileIds.value.size === 0) return
  addingMembers.value = true
  try {
    await gqlMutation(addMembersGql, {
      segmentId: segmentId.value,
      profileIds: Array.from(selectedProfileIds.value),
    })
    toast.success(`Added ${selectedProfileIds.value.size} member(s)`)
    addMemberModalOpen.value = false
    selectedProfileIds.value = new Set()
    profileSearch.value = ''
    profileSearchResults.value = []
    refresh()
    refreshMembers()
  } catch (e: any) {
    toast.error(e?.message || 'Failed to add members')
  } finally {
    addingMembers.value = false
  }
}

async function onRemoveMember(profileId: string) {
  removingMemberId.value = profileId
  try {
    await gqlMutation(removeMembersGql, { segmentId: segmentId.value, profileIds: [profileId] })
    toast.success('Member removed')
    refresh()
    refreshMembers()
  } catch (e: any) {
    toast.error(e?.message || 'Failed to remove member')
  } finally {
    removingMemberId.value = null
  }
}

async function onOpenRemoveFromSegment() {
  removeFromSegmentModalOpen.value = true
  try {
    const result = await gqlQuery<any>(allSegmentsGql)
    availableSegments.value = (result?.segments?.all ?? []).filter(
      (s: SimpleSegment) => s.id !== segmentId.value,
    )
  } catch { availableSegments.value = [] }
}

async function onRemoveFromSegment() {
  if (!subtractSegmentId.value) return
  removingFromSegment.value = true
  try {
    await gqlMutation(removeFromSegmentGql, {
      segmentId: segmentId.value,
      removeSegmentId: subtractSegmentId.value,
    })
    toast.success('Subtracted segment members')
    removeFromSegmentModalOpen.value = false
    subtractSegmentId.value = null
    refresh()
    refreshMembers()
  } catch (e: any) {
    toast.error(e?.message || 'Failed to subtract')
  } finally {
    removingFromSegment.value = false
  }
}

async function onOpenRunPipeline() {
  runPipelineModalOpen.value = true
  selectedPipelineId.value = null
  loadingPipelines.value = true
  try {
    const result = await gqlQuery<{ pipelines?: { all?: PipelineOption[] } }>(pipelinesGql)
    availablePipelines.value = result?.pipelines?.all ?? []
  } catch (e: any) {
    toast.error(e?.message || 'Failed to load pipelines')
  } finally {
    loadingPipelines.value = false
  }
}

async function onRunPipeline() {
  if (!selectedPipelineId.value) return
  runningPipeline.value = true
  try {
    await gqlMutation(runPipelineGql, {
      segmentId: segmentId.value,
      pipelineId: selectedPipelineId.value,
    })
    toast.success('Pipeline queued for segment members')
    runPipelineModalOpen.value = false
    selectedPipelineId.value = null
  } catch (e: any) {
    toast.error(e?.message || 'Failed to run pipeline')
  } finally {
    runningPipeline.value = false
  }
}

function formatDate(d: string | null): string {
  if (!d) return '—'
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

const memberColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Profile', width: 'minmax(200px, 2fr)' },
  { key: 'added', label: 'Added', width: '120px', muted: true },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Audience', 'Segments', segment?.name ?? '…')"
        :title="segment?.name ?? 'Loading…'"
        :subtitle="segment ? `${segment.memberCount.toLocaleString()} members · ${segment.type}` : ''"
      >
        <template #actions>
          <Button
            v-if="isStatic"
            size="sm"
            icon="plus"
            @click="addMemberModalOpen = true">Add Members</Button>
          <Button size="sm" icon="columns" @click="onOpenRemoveFromSegment">Remove by Segment</Button>
          <Button
            v-if="isDynamic"
            size="sm"
            icon="pulse"
            :disabled="evaluating"
            @click="onEvaluate">
            {{ evaluating ? 'Evaluating…' : 'Evaluate' }}
          </Button>
          <Button size="sm" icon="play" @click="onOpenRunPipeline">Run Pipeline</Button>
          <Button size="sm" icon="pencil" @click="onOpenEditModal">Edit</Button>
          <Button
            size="sm"
            icon="trash"
            style="color: var(--err)"
            @click="deleteModalOpen = true" />
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !segment" style="padding: 40px; text-align: center; color: var(--fg-3)">Loading…</div>

    <template v-else-if="segment">
      <div class="detail-layout">
        <div class="main-content">
          <!-- Members table -->
          <SectionCard>
            <GlassTable
              :columns="memberColumns"
              :rows="members"
              empty-text="No members in this segment."
              :row-actions="() => [
                { id: 'view', label: 'View', icon: 'eye' },
                ...(isStatic ? [{ id: 'remove', label: 'Remove', icon: 'x', danger: true }] : []),
              ]"
              @row-click="(row: any) => router.push(`/audience/profiles/${row.profile?.id}`)"
              @row-action="({ action, row }: any) => action === 'view' ? router.push(`/audience/profiles/${row.profile?.id}`) : action === 'remove' ? onRemoveMember(row.profile?.id) : null"
            >
              <template #col-name="{ row }">
                <span style="font-weight: 500; color: var(--fg-0)">{{ row.profile?.name ?? row.profile?.id }}</span>
              </template>
              <template #col-added="{ row }">
                {{ formatDate(row.addedAt) }}
              </template>
            </GlassTable>

            <div v-if="members.length >= membersLimit" class="pagination">
              <Button v-if="membersOffset > 0" size="xs" @click="membersOffset -= membersLimit">Previous</Button>
              <span class="pagination-info">Showing {{ membersOffset + 1 }}–{{ membersOffset + members.length }}</span>
              <Button size="xs" @click="membersOffset += membersLimit">Next</Button>
            </div>
          </SectionCard>
        </div>

        <div class="sidebar">
          <SectionCard title="Status" padded>
            <div class="status-info">
              <div class="status-row">
                <span class="status-label">Status</span>
                <Badge :color="STATUS_COLORS[segment.status] ?? '#6c7388'">{{ segment.status }}</Badge>
              </div>
              <div class="status-row">
                <span class="status-label">Type</span>
                <Badge :color="segment.type === 'DYNAMIC' ? accent : 'var(--fg-3)'">{{ segment.type }}</Badge>
              </div>
              <div class="status-row">
                <span class="status-label">Members</span>
                <span class="mono tabular" style="font-size: 13px; color: var(--fg-0)">{{ segment.memberCount.toLocaleString() }}</span>
              </div>
            </div>
          </SectionCard>

          <SectionCard title="Details" padded>
            <div class="meta-grid">
              <div class="meta-item"><span class="meta-label">Created</span><span class="meta-value">{{ formatDate(segment.created) }}</span></div>
              <div class="meta-item"><span class="meta-label">Modified</span><span class="meta-value">{{ formatDate(segment.modified) }}</span></div>
              <div v-if="segment.lastEvaluated" class="meta-item"><span class="meta-label">Last Evaluated</span><span class="meta-value">{{ formatDate(segment.lastEvaluated) }}</span></div>
              <div v-if="segment.evaluationSchedule" class="meta-item"><span class="meta-label">Schedule</span><span class="meta-value mono">{{ segment.evaluationSchedule }}</span></div>
              <div v-if="segment.analyticsQueryId" class="meta-item"><span class="meta-label">Query</span><span class="meta-value mono" style="font-size: 11px">{{ segment.analyticsQueryId }}</span></div>
            </div>
          </SectionCard>

          <div v-if="segment.configuration" class="config-section">
            <SectionCard title="Configuration" padded>
              <pre class="config-json">{{ JSON.stringify(segment.configuration, null, 2) }}</pre>
            </SectionCard>
          </div>

        </div>
      </div>
    </template>

    <!-- Edit modal -->
    <Modal
      v-if="editModalOpen"
      title="Edit Segment"
      icon="segment"
      :accent="accent"
      @close="editModalOpen = false">
      <TextInput v-model="editName" label="Name" />
      <Textarea v-model="editDescription" label="Description" :rows="2" />
      <Select
        v-model="editType"
        :options="[
          { value: 'STATIC', label: 'Static' },
          { value: 'DYNAMIC', label: 'Dynamic' },
        ]"
        label="Type" />
      <Select
        v-model="editStatus"
        :options="[
          { value: 'ACTIVE', label: 'Active' },
          { value: 'DRAFT', label: 'Draft' },
          { value: 'PAUSED', label: 'Paused' },
          { value: 'ARCHIVED', label: 'Archived' },
        ]"
        label="Status" />
      <Select
        v-if="editType === 'DYNAMIC'"
        v-model="editQueryId"
        :options="analyticsQueries.map(q => ({ value: q.id, label: q.name }))"
        label="Analytics Query"
        placeholder="Select a query…"
      />
      <TextInput
        v-if="editType === 'DYNAMIC'"
        v-model="editSchedule"
        label="Evaluation Schedule (cron)"
        placeholder="0 0 * * *"
      />
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="editModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="saving"
          @click="onSave">Save</Button>
      </template>
    </Modal>

    <!-- Add member modal -->
    <Modal
      v-if="addMemberModalOpen"
      title="Add Members"
      icon="plus"
      :accent="accent"
      @close="addMemberModalOpen = false">
      <input
        v-model="profileSearch"
        class="search-input"
        placeholder="Search profiles…"
        @input="onProfileSearchInput"
      >

      <div v-if="profileSearchResults.length" class="profile-results">
        <div
          v-for="p in profileSearchResults"
          :key="p.id"
          class="profile-result"
        >
          <Switch :model-value="selectedProfileIds.has(p.id)" @update:model-value="toggleProfileSelection(p.id)" />
          <span class="profile-name">{{ p.name || p.slug || p.id }}</span>
        </div>
      </div>
      <div v-else-if="profileSearch && !searchingProfiles" class="empty-text">No profiles found.</div>

      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="addMemberModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="selectedProfileIds.size === 0 || addingMembers"
          @click="onAddMembers"
        >
          {{ addingMembers ? 'Adding…' : `Add ${selectedProfileIds.size} Member${selectedProfileIds.size !== 1 ? 's' : ''}` }}
        </Button>
      </template>
    </Modal>

    <!-- Remove from segment modal -->
    <Modal
      v-if="removeFromSegmentModalOpen"
      title="Remove by Segment"
      icon="columns"
      :accent="accent"
      @close="removeFromSegmentModalOpen = false">
      <p style="font-size: 13px; color: var(--fg-3); margin: 0 0 12px">
        Remove all profiles that exist in the selected segment from this segment.
      </p>
      <div v-if="availableSegments.length" class="segment-picker">
        <div
          v-for="s in availableSegments"
          :key="s.id"
          class="segment-option"
          :class="{ 'segment-option--selected': subtractSegmentId === s.id }"
          @click="subtractSegmentId = s.id"
        >
          <input type="radio" :checked="subtractSegmentId === s.id">
          <span class="segment-name">{{ s.name }}</span>
          <span class="mono tabular segment-count">{{ s.memberCount.toLocaleString() }}</span>
        </div>
      </div>
      <div v-else class="empty-text">No other segments available.</div>

      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="removeFromSegmentModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!subtractSegmentId || removingFromSegment"
          @click="onRemoveFromSegment"
        >
          {{ removingFromSegment ? 'Removing…' : 'Remove Members' }}
        </Button>
      </template>
    </Modal>

    <!-- Run pipeline modal -->
    <Modal
      v-if="runPipelineModalOpen"
      title="Run Pipeline"
      icon="play"
      :accent="accent"
      @close="runPipelineModalOpen = false">
      <p class="run-note">
        Runs a pipeline once for each of this segment's
        <strong>{{ (segment?.memberCount ?? 0).toLocaleString() }}</strong>
        member{{ segment?.memberCount === 1 ? '' : 's' }}. Each run starts in the background and
        receives that member's profile id.
      </p>
      <Select
        v-model="selectedPipelineId"
        :options="pipelineOptions"
        :loading="loadingPipelines"
        :accent="accent"
        label="Pipeline"
        placeholder="Select a pipeline…"
        searchable
      />
      <p v-if="!loadingPipelines && !availablePipelines.length" class="run-empty">
        No pipelines have been created yet.
      </p>

      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="runPipelineModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!selectedPipelineId || runningPipeline"
          @click="onRunPipeline"
        >
          {{ runningPipeline ? 'Starting…' : 'Run Pipeline' }}
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deleteModalOpen"
      title="Delete Segment"
      :subtitle="`Delete '${segment?.name}'? Members will not be affected.`"
      :loading="deleting"
      @close="deleteModalOpen = false"
      @confirm="onDelete"
    />
  </PageShell>
</template>

<style scoped>
.detail-layout { display: grid; grid-template-columns: 1fr 280px; gap: 18px; align-items: start; }
.main-content { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
.sidebar { display: flex; flex-direction: column; gap: 14px; }
.status-info { display: flex; flex-direction: column; gap: 12px; }
.status-row { display: flex; align-items: center; justify-content: space-between; }
.status-label { font-size: 12px; color: var(--fg-3); font-weight: 500; }
.meta-grid { display: flex; flex-direction: column; gap: 10px; }
.meta-item { display: flex; justify-content: space-between; }
.meta-label { font-size: 12px; color: var(--fg-3); }
.meta-value { font-size: 12px; color: var(--fg-1); }

.member-toolbar {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
}
.member-toolbar-title { font-size: 14px; font-weight: 600; color: var(--fg-0); }
.member-toolbar-spacer { flex: 1; }

.remove-btn {
  width: 24px; height: 24px; background: none; border: none;
  color: var(--fg-3); cursor: pointer; border-radius: var(--r-sm);
  display: flex; align-items: center; justify-content: center;
}
.remove-btn:hover { color: var(--err); background: var(--bg-2); }
.remove-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.pagination {
  display: flex; align-items: center; gap: 8px; padding: 10px 14px;
  border-top: 1px solid var(--line); font-size: 12px; color: var(--fg-3);
}
.pagination-info { flex: 1; text-align: center; }

.config-json {
  font-size: 11px; font-family: var(--font-mono, monospace);
  color: var(--fg-2); background: var(--bg-2); padding: 10px;
  border-radius: var(--r-sm); overflow-x: auto; margin: 0;
  max-height: 200px; white-space: pre-wrap; word-break: break-all;
}

.search-row { display: flex; gap: 8px; margin-bottom: 12px; }
.search-input {
  flex: 1; padding: 7px 10px; font-size: 13px; background: var(--bg-2);
  border: 1px solid var(--line); border-radius: var(--r-sm); color: var(--fg-1); outline: none;
}
.search-input:focus { border-color: var(--brand-2); }

.profile-results { display: flex; flex-direction: column; gap: 2px; max-height: 300px; overflow-y: auto; }
.profile-result {
  display: flex; align-items: center; gap: 8px; padding: 7px 10px;
  border-radius: var(--r-sm); cursor: pointer;
}
.profile-result:hover { background: var(--bg-2); }
.profile-result--selected { background: color-mix(in oklch, var(--brand-2) 8%, transparent); }
.profile-name { font-size: 13px; font-weight: 500; color: var(--fg-0); }

.segment-picker { display: flex; flex-direction: column; gap: 2px; max-height: 300px; overflow-y: auto; }
.segment-option {
  display: flex; align-items: center; gap: 8px; padding: 7px 10px;
  border-radius: var(--r-sm); cursor: pointer;
}
.segment-option:hover { background: var(--bg-2); }
.segment-option--selected { background: color-mix(in oklch, var(--brand-2) 8%, transparent); }
.segment-name { flex: 1; font-size: 13px; font-weight: 500; color: var(--fg-0); }
.segment-count { font-size: 12px; color: var(--fg-3); }
.empty-text { font-size: 13px; color: var(--fg-3); }

.run-note { font-size: 13px; line-height: 1.5; color: var(--fg-3); margin: 0 0 16px; }
.run-note strong { color: var(--fg-1); font-weight: 600; }
.run-empty { font-size: 12px; color: var(--fg-3); margin: 8px 0 0; }
</style>
