<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import gql from 'graphql-tag'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const searchSegmentsGql = gql`
  query SearchSegmentsForCampaign($limit: Int!, $offset: Long!) {
    segments { all(limit: $limit, offset: $offset) { id name memberCount status } }
  }
`

const addGql = gql`
  mutation AddCampaign($campaign: CampaignInput!) {
    campaigns { add(campaign: $campaign) { id } }
  }
`

interface Segment { id: string; name: string; memberCount: number; status: string }

const segmentSearch = ref('')
const segmentSearchResults = ref<Segment[]>([])
const searchingSegments = ref(false)

let segmentSearchTimer: ReturnType<typeof setTimeout> | undefined

function onSegmentSearchInput() {
  clearTimeout(segmentSearchTimer)
  if (!segmentSearch.value.trim()) { segmentSearchResults.value = []; return }
  segmentSearchTimer = setTimeout(() => doSegmentSearch(), 300)
}

async function doSegmentSearch() {
  searchingSegments.value = true
  try {
    const result = await gqlQuery<any>(searchSegmentsGql, { limit: 50, offset: 0 })
    const all: Segment[] = result?.segments?.all ?? []
    const q = segmentSearch.value.toLowerCase()
    segmentSearchResults.value = q ? all.filter(s => s.name.toLowerCase().includes(q)) : all
  } catch { segmentSearchResults.value = [] }
  finally { searchingSegments.value = false }
}

const segmentModalOpen = ref(false)
const selectedSegmentsMap = ref<Map<string, Segment>>(new Map())
const selectedSegmentIds = computed(() => new Set(selectedSegmentsMap.value.keys()))
const selectedSegmentsList = computed(() => Array.from(selectedSegmentsMap.value.values()))

const name = ref('')
const channel = ref('PUSH')
const scheduledAt = ref('')
const endedAt = ref('')
const content = ref<Record<string, any>>({})
const creating = ref(false)

function toggleSegment(id: string) {
  const next = new Map(selectedSegmentsMap.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    const seg = segmentSearchResults.value.find(s => s.id === id)
    if (seg) next.set(id, seg)
  }
  selectedSegmentsMap.value = next
}

async function onCreate() {
  if (!name.value.trim()) {
    toast.error('Name is required')
    return
  }
  if (selectedSegmentIds.value.size === 0) {
    toast.error('Select at least one segment')
    return
  }
  if (channel.value === 'EMAIL'
    && (typeof content.value.project !== 'string' || !content.value.project.trim()
      || typeof content.value.templateKey !== 'string' || !content.value.templateKey.trim())) {
    toast.error('Select a BML message project and template')
    return
  }

  creating.value = true
  try {
    const result = await gqlMutation<any>(addGql, {
      campaign: {
        name: name.value,
        channel: channel.value,
        segmentIds: Array.from(selectedSegmentIds.value),
        content: Object.keys(content.value).length > 0 ? content.value : null,
        scheduledAt: scheduledAt.value || null,
        endedAt: channel.value === 'BANNER' && endedAt.value ? endedAt.value : null,
      },
    })
    const id = result?.campaigns?.add?.id
    if (id) {
      toast.success('Campaign created')
      router.push(`/communications/campaigns/${id}`)
    }
  } catch (e: any) {
    toast.error(e?.message || 'Failed to create campaign')
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Communications', 'Campaigns', 'New')"
        title="Create Campaign"
        subtitle="Set up and send a new campaign"
      >
        <template #actions>
          <Button size="sm" icon="x" @click="router.push('/communications/campaigns')">Cancel</Button>
          <Button
            size="sm"
            icon="plus"
            primary
            :accent="accent"
            :disabled="creating"
            @click="onCreate">
            {{ creating ? 'Creating...' : 'Create Campaign' }}
          </Button>
        </template>
      </PageHeader>
    </template>

    <div class="create-layout">
      <div class="main-content">
        <!-- General -->
        <SectionCard padded title="General">
          <div class="form-grid">
            <div class="field">
              <label class="field-label">Name *</label>
              <input
                v-model="name"
                class="field-input"
                placeholder="Campaign name"
                autofocus>
            </div>
            <Select
              v-model="channel"
              label="Channel *"
              :options="[
                { value: 'PUSH', label: 'Push Notification' },
                { value: 'EMAIL', label: 'Email' },
                { value: 'BANNER', label: 'Banner' },
              ]"
            />
          </div>
        </SectionCard>

        <!-- Content -->
        <SectionCard padded title="Content">
          <CampaignContentEditor
            v-model="content"
            :channel="channel as any"
          />
        </SectionCard>

        <!-- Target Segments -->
        <SectionCard padded title="Target Segments *">
          <template #right>
            <Button
              size="sm"
              icon="plus"
              :accent="accent"
              @click="segmentModalOpen = true">Add Segments</Button>
          </template>
          <div v-if="selectedSegmentsList.length" class="segment-picker">
            <div v-for="seg in selectedSegmentsList" :key="seg.id" class="segment-option">
              <span class="segment-name">{{ seg.name }}</span>
              <span class="mono tabular segment-count">{{ seg.memberCount?.toLocaleString() ?? '' }}</span>
              <button class="segment-remove" @click="toggleSegment(seg.id)"><Icon name="x" :size="12" /></button>
            </div>
          </div>
          <div v-else class="empty-text">No segments selected. Click "Add Segments" to choose.</div>
        </SectionCard>
      </div>

      <div class="sidebar">
        <!-- Schedule -->
        <SectionCard padded title="Schedule">
          <div class="field">
            <label class="field-label">Send at (optional)</label>
            <input v-model="scheduledAt" type="datetime-local" class="field-input">
            <span class="field-hint">Leave empty to send immediately on creation.</span>
          </div>
          <div v-if="channel === 'BANNER'" class="field" style="margin-top: 12px">
            <label class="field-label">End date (optional)</label>
            <input v-model="endedAt" type="datetime-local" class="field-input">
            <span class="field-hint">When the banner should stop showing.</span>
          </div>
        </SectionCard>
      </div>
    </div>
    <!-- Add Segments Modal -->
    <Modal
      v-if="segmentModalOpen"
      title="Add Segments"
      icon="segment"
      :accent="accent"
      @close="segmentModalOpen = false">
      <TextInput v-model="segmentSearch" placeholder="Search segments…" @input="onSegmentSearchInput" />
      <div v-if="segmentSearchResults.length" class="segment-picker" style="margin-top: 10px">
        <div v-for="seg in segmentSearchResults" :key="seg.id" class="segment-option">
          <Switch :model-value="selectedSegmentIds.has(seg.id)" @update:model-value="toggleSegment(seg.id)" @click.stop />
          <span class="segment-name">{{ seg.name }}</span>
          <span class="mono tabular segment-count">{{ seg.memberCount?.toLocaleString() ?? '' }}</span>
        </div>
      </div>
      <div v-else-if="segmentSearch && !searchingSegments" class="empty-text" style="margin-top: 10px">No segments found.</div>
      <div v-else-if="!segmentSearch" class="empty-text" style="margin-top: 10px">Type to search segments.</div>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="segmentModalOpen = false">Done</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.create-layout { display: grid; grid-template-columns: 1fr 280px; gap: 18px; align-items: start; }
.main-content { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
.sidebar { display: flex; flex-direction: column; gap: 14px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }

.field { display: flex; flex-direction: column; gap: 4px; }
.field-label { font-size: 12px; font-weight: 500; color: var(--fg-3); }

.field-input {
  padding: 7px 10px;
  font-size: 13px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-1);
  outline: none;
  width: 100%;
}
.field-input:focus { border-color: var(--brand-2); }

.field-select {
  padding: 7px 10px;
  font-size: 13px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-1);
  outline: none;
  width: 100%;
}
.field-select:focus { border-color: var(--brand-2); }

.field-hint { font-size: 11px; color: var(--fg-3); margin-top: 2px; }

.segment-picker { display: flex; flex-direction: column; gap: 2px; }
.segment-option {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: background 0.15s;
}
.segment-option:hover { background: var(--bg-2); }
.segment-option--selected { background: color-mix(in oklch, var(--brand-2) 8%, transparent); }
.segment-check { flex-shrink: 0; }
.segment-name { flex: 1; font-size: 13px; font-weight: 500; color: var(--fg-0); }
.segment-count { font-size: 12px; color: var(--fg-3); }
.segment-remove {
  width: 22px; height: 22px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 4px; color: var(--fg-3);
  transition: all 0.15s;
}
.segment-remove:hover { color: var(--err); background: color-mix(in oklch, var(--err) 10%, transparent); }
.empty-text { font-size: 13px; color: var(--fg-3); }
</style>
