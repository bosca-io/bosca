<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const campaignId = computed(() => route.params.id as string)

const STATUS_COLORS: Record<string, string> = {
  DRAFT: '#6c7388', SCHEDULED: '#5ec5ff', SENDING: '#a78bff',
  SENT: '#34d99a', ACTIVE: '#34d99a', FAILED: '#ff5d6c', CANCELLED: '#6c7388',
}

const campaignGql = gql`
  query GetCampaignById($id: UUID!) {
    campaigns {
      campaign(id: $id) {
        id name channel status scheduledAt endedAt sentAt sentCount
        content created modified placement weight
        segments { id name memberCount status }
      }
    }
  }
`

const segmentsGql = gql`
  query GetSegmentsForCampaignEdit {
    segments { all(limit: 200, offset: 0) { id name memberCount status } }
  }
`

const editGql = gql`
  mutation EditCampaign($id: UUID!, $campaign: CampaignInput!) {
    campaigns { edit(id: $id, campaign: $campaign) { id } }
  }
`

const sendGql = gql`
  mutation SendCampaign($id: UUID!) {
    campaigns { send(id: $id) { status sentAt sentCount } }
  }
`

const cancelGql = gql`
  mutation CancelCampaign($id: UUID!) {
    campaigns { cancel(id: $id) { status } }
  }
`

const reactivateGql = gql`
  mutation ReactivateCampaign($id: UUID!) {
    campaigns { reactivate(id: $id) { status } }
  }
`

const sendTestGql = gql`
  mutation SendTestCampaign($id: UUID!, $segmentId: UUID!) {
    campaigns { sendTest(id: $id, segmentId: $segmentId) }
  }
`

const deleteGql = gql`
  mutation DeleteCampaign($id: UUID!) {
    campaigns { delete(id: $id) }
  }
`

interface CampaignSegment { id: string; name: string; memberCount: number; status: string }

interface Campaign {
  id: string; name: string; channel: string; status: string
  scheduledAt: string | null; endedAt: string | null; sentAt: string | null; sentCount: number
  content: any; created: string; modified: string; placement: string | null; weight: number
  segments: CampaignSegment[]
}

const { data, status: queryStatus, refresh } = useAsyncQuery<{
  campaigns: { campaign: Campaign | null }
}>('campaign-detail', campaignGql, { id: campaignId })

const { data: segmentsData } = useAsyncQuery<{
  segments: { all: CampaignSegment[] }
}>('campaign-edit-segments', segmentsGql)

const campaign = computed(() => data.value?.campaigns?.campaign ?? null)
const availableSegments = computed(() => segmentsData.value?.segments?.all ?? [])
const isLoading = computed(() => queryStatus.value === 'pending')

const name = ref('')
const content = ref<Record<string, any>>({})
const selectedSegmentIds = ref<Set<string>>(new Set())
const scheduledAt = ref('')
const endedAt = ref('')

const saving = ref(false)
const sending = ref(false)
const cancelling = ref(false)
const reactivating = ref(false)
const deleting = ref(false)
const deleteModalOpen = ref(false)
const testSendModalOpen = ref(false)
const testSegmentId = ref<string | null>(null)
const testSending = ref(false)

watch(campaign, (c) => {
  if (!c) return
  name.value = c.name
  content.value = c.content ? { ...c.content } : {}
  selectedSegmentIds.value = new Set(c.segments.map((s) => s.id))
  scheduledAt.value = c.scheduledAt ? c.scheduledAt.slice(0, 16) : ''
  endedAt.value = c.endedAt ? c.endedAt.slice(0, 16) : ''
}, { immediate: true })

const isDraft = computed(() => campaign.value?.status === 'DRAFT')
const isScheduled = computed(() => campaign.value?.status === 'SCHEDULED')
const isActive = computed(() => ['SCHEDULED', 'SENDING', 'ACTIVE'].includes(campaign.value?.status ?? ''))
const isCancelled = computed(() => campaign.value?.status === 'CANCELLED')
const canEdit = computed(() => isDraft.value || isScheduled.value || isCancelled.value)
const canTestSend = computed(() => isDraft.value || isScheduled.value)
const channelTyped = computed(() => (campaign.value?.channel as 'PUSH' | 'EMAIL' | 'BANNER') ?? 'PUSH')

function toggleSegment(id: string) {
  const next = new Set(selectedSegmentIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedSegmentIds.value = next
}

async function onSave() {
  if (!campaign.value) return
  if (campaign.value.channel === 'EMAIL'
    && (typeof content.value.project !== 'string' || !content.value.project.trim()
      || typeof content.value.templateKey !== 'string' || !content.value.templateKey.trim())) {
    toast.error('Select a BML message project and template')
    return
  }
  saving.value = true
  try {
    await gqlMutation(editGql, {
      id: campaignId.value,
      campaign: {
        name: name.value,
        channel: campaign.value.channel,
        segmentIds: Array.from(selectedSegmentIds.value),
        content: Object.keys(content.value).length > 0 ? content.value : null,
        scheduledAt: scheduledAt.value || null,
        endedAt: campaign.value.channel === 'BANNER' && endedAt.value ? endedAt.value : null,
      },
    })
    toast.success('Campaign saved')
    refresh()
  } catch (e: any) {
    toast.error(e?.message || 'Failed to save')
  } finally {
    saving.value = false
  }
}

async function onSend() {
  sending.value = true
  try {
    await gqlMutation(sendGql, { id: campaignId.value })
    toast.success('Campaign sent')
    refresh()
  } catch (e: any) {
    toast.error(e?.message || 'Failed to send')
  } finally {
    sending.value = false
  }
}

async function onCancel() {
  cancelling.value = true
  try {
    await gqlMutation(cancelGql, { id: campaignId.value })
    toast.success('Campaign cancelled')
    refresh()
  } catch (e: any) {
    toast.error(e?.message || 'Failed to cancel')
  } finally {
    cancelling.value = false
  }
}

async function onReactivate() {
  reactivating.value = true
  try {
    await gqlMutation(reactivateGql, { id: campaignId.value })
    toast.success('Campaign reactivated')
    refresh()
  } catch (e: any) {
    toast.error(e?.message || 'Failed to reactivate')
  } finally {
    reactivating.value = false
  }
}

async function onTestSend() {
  if (!testSegmentId.value) return
  testSending.value = true
  try {
    await gqlMutation(sendTestGql, { id: campaignId.value, segmentId: testSegmentId.value })
    toast.success('Test sent')
    testSendModalOpen.value = false
  } catch (e: any) {
    toast.error(e?.message || 'Failed to send test')
  } finally {
    testSending.value = false
  }
}

async function onDelete() {
  deleting.value = true
  try {
    await gqlMutation(deleteGql, { id: campaignId.value })
    toast.success('Campaign deleted')
    router.push('/communications/campaigns')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to delete')
  } finally {
    deleting.value = false
  }
}

function formatDate(d: string | null): string {
  if (!d) return '—'
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Communications', 'Campaigns', campaign?.name ?? '…')"
        :title="campaign?.name ?? 'Loading…'"
        :subtitle="campaign ? `${campaign.channel} · ${campaign.status}` : ''"
      >
        <template #actions>
          <Button
            v-if="canEdit"
            size="sm"
            icon="save"
            :disabled="saving"
            @click="onSave">Save</Button>
          <Button
            v-if="canTestSend"
            size="sm"
            icon="arrowUpRight"
            @click="testSendModalOpen = true">Test</Button>
          <Button
            v-if="isDraft"
            size="sm"
            icon="globe"
            primary
            :accent="accent"
            :disabled="sending"
            @click="onSend">Send</Button>
          <Button
            v-if="isActive"
            size="sm"
            icon="x"
            :disabled="cancelling"
            @click="onCancel">Cancel</Button>
          <Button
            v-if="isCancelled"
            size="sm"
            icon="arrowRight"
            :disabled="reactivating"
            @click="onReactivate">Reactivate</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !campaign" style="padding: 40px; text-align: center; color: var(--fg-3)">Loading…</div>

    <template v-else-if="campaign">
      <div class="detail-layout">
        <div class="main-content">
          <!-- General -->
          <SectionCard title="General">
            <div class="form-grid">
              <div class="field">
                <label class="field-label">Name</label>
                <input v-model="name" class="field-input" :disabled="!canEdit">
              </div>
              <div class="field">
                <label class="field-label">Channel</label>
                <input :value="campaign.channel" class="field-input" disabled>
              </div>
            </div>
          </SectionCard>

          <!-- Content -->
          <SectionCard title="Content">
            <CampaignContentEditor
              v-model="content"
              :channel="channelTyped"
              :disabled="!canEdit"
            />
          </SectionCard>

          <!-- Target Segments -->
          <SectionCard title="Target Segments">
            <div v-if="canEdit" class="segment-picker">
              <div
                v-for="seg in availableSegments"
                :key="seg.id"
                class="segment-option"
                :class="{ 'segment-option--selected': selectedSegmentIds.has(seg.id) }"
                @click="toggleSegment(seg.id)"
              >
                <input type="checkbox" :checked="selectedSegmentIds.has(seg.id)" class="segment-check">
                <span class="segment-name">{{ seg.name }}</span>
                <span class="mono tabular segment-count">{{ seg.memberCount.toLocaleString() }}</span>
              </div>
              <div v-if="!availableSegments.length" class="empty-text">No segments available.</div>
            </div>
            <div v-else class="segment-list">
              <div
                v-for="seg in campaign.segments"
                :key="seg.id"
                class="segment-row"
                @click="router.push(`/audience/segments/${seg.id}`)">
                <Icon name="segment" :size="14" :color="accent" />
                <span class="segment-name">{{ seg.name }}</span>
                <span class="mono tabular segment-count">{{ seg.memberCount.toLocaleString() }}</span>
              </div>
              <div v-if="!campaign.segments.length" class="empty-text">No segments targeted.</div>
            </div>
          </SectionCard>
        </div>

        <div class="sidebar">
          <!-- Status -->
          <SectionCard title="Status">
            <div class="status-info">
              <div class="status-row">
                <span class="status-label">Status</span>
                <Badge :color="STATUS_COLORS[campaign.status] ?? '#6c7388'">{{ campaign.status }}</Badge>
              </div>
              <div class="status-row">
                <span class="status-label">Sent</span>
                <span class="mono tabular" style="font-size: 13px; color: var(--fg-0)">{{ campaign.sentCount.toLocaleString() }}</span>
              </div>
            </div>
          </SectionCard>

          <!-- Schedule -->
          <SectionCard title="Schedule">
            <template v-if="canEdit">
              <div class="field">
                <label class="field-label">Send at</label>
                <input v-model="scheduledAt" type="datetime-local" class="field-input">
              </div>
              <div v-if="campaign.channel === 'BANNER'" class="field" style="margin-top: 10px">
                <label class="field-label">End date</label>
                <input v-model="endedAt" type="datetime-local" class="field-input">
              </div>
            </template>
            <template v-else>
              <div class="meta-grid">
                <div class="meta-item"><span class="meta-label">Scheduled</span><span class="meta-value">{{ formatDate(campaign.scheduledAt) }}</span></div>
                <div v-if="campaign.sentAt" class="meta-item"><span class="meta-label">Sent</span><span class="meta-value">{{ formatDate(campaign.sentAt) }}</span></div>
                <div v-if="campaign.endedAt" class="meta-item"><span class="meta-label">Ended</span><span class="meta-value">{{ formatDate(campaign.endedAt) }}</span></div>
                <div class="meta-item"><span class="meta-label">Created</span><span class="meta-value">{{ formatDate(campaign.created) }}</span></div>
              </div>
            </template>
          </SectionCard>

          <Button
            size="sm"
            icon="trash"
            style="color: var(--err); margin-top: 8px"
            @click="deleteModalOpen = true">Delete</Button>
        </div>
      </div>
    </template>

    <!-- Delete confirmation -->
    <ConfirmModal
      v-if="deleteModalOpen"
      title="Delete Campaign"
      :subtitle="`Delete '${campaign?.name}'?`"
      :loading="deleting"
      @close="deleteModalOpen = false"
      @confirm="onDelete"
    />

    <!-- Test send modal -->
    <Teleport to="body">
      <div v-if="testSendModalOpen" class="modal-overlay" @click.self="testSendModalOpen = false">
        <div class="modal-dialog">
          <h3 class="modal-title">Send Test</h3>
          <p class="modal-text">Select a segment to send a test delivery. This won't change the campaign status.</p>
          <div class="field" style="margin: 12px 0">
            <label class="field-label">Segment</label>
            <select v-model="testSegmentId" class="field-select">
              <option :value="null" disabled>Select a segment…</option>
              <option v-for="seg in campaign?.segments ?? []" :key="seg.id" :value="seg.id">
                {{ seg.name }} ({{ seg.memberCount.toLocaleString() }})
              </option>
            </select>
          </div>
          <div class="modal-actions">
            <button class="modal-btn modal-btn--cancel" @click="testSendModalOpen = false">Cancel</button>
            <button class="modal-btn modal-btn--send" :disabled="!testSegmentId || testSending" @click="onTestSend">
              {{ testSending ? 'Sending…' : 'Send Test' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </PageShell>
</template>

<style scoped>
.detail-layout { display: grid; grid-template-columns: 1fr 280px; gap: 18px; align-items: start; }
.main-content { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
.sidebar { display: flex; flex-direction: column; gap: 14px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }

.field { display: flex; flex-direction: column; gap: 4px; }
.field-label { font-size: 12px; font-weight: 500; color: var(--fg-3); }

.field-input {
  padding: 7px 10px; font-size: 13px; background: var(--bg-2);
  border: 1px solid var(--line); border-radius: var(--r-sm);
  color: var(--fg-1); outline: none; width: 100%;
}
.field-input:focus { border-color: var(--brand-2); }
.field-input:disabled { opacity: 0.5; cursor: not-allowed; }

.field-select {
  padding: 7px 10px; font-size: 13px; background: var(--bg-2);
  border: 1px solid var(--line); border-radius: var(--r-sm);
  color: var(--fg-1); outline: none; width: 100%;
}

.segment-picker { display: flex; flex-direction: column; gap: 2px; }
.segment-option {
  display: flex; align-items: center; gap: 8px; padding: 7px 10px;
  border-radius: var(--r-sm); cursor: pointer; transition: background 0.15s;
}
.segment-option:hover { background: var(--bg-2); }
.segment-option--selected { background: color-mix(in oklch, var(--brand-2) 8%, transparent); }
.segment-check { flex-shrink: 0; }

.segment-list { display: flex; flex-direction: column; gap: 4px; }
.segment-row {
  display: flex; align-items: center; gap: 8px; padding: 6px 8px;
  border-radius: var(--r-sm); cursor: pointer; transition: background 0.15s;
}
.segment-row:hover { background: color-mix(in oklch, var(--brand-2) 6%, transparent); }

.segment-name { flex: 1; font-size: 13px; font-weight: 500; color: var(--fg-0); }
.segment-count { font-size: 12px; color: var(--fg-3); }
.empty-text { font-size: 13px; color: var(--fg-3); }

.status-info { display: flex; flex-direction: column; gap: 12px; }
.status-row { display: flex; align-items: center; justify-content: space-between; }
.status-label { font-size: 12px; color: var(--fg-3); font-weight: 500; }
.meta-grid { display: flex; flex-direction: column; gap: 10px; }
.meta-item { display: flex; justify-content: space-between; }
.meta-label { font-size: 12px; color: var(--fg-3); }
.meta-value { font-size: 12px; color: var(--fg-1); }

.modal-overlay {
  position: fixed; inset: 0; z-index: 9999;
  background: rgba(0, 0, 0, 0.5); display: flex;
  align-items: center; justify-content: center;
}
.modal-dialog {
  background: var(--bg-1); border: 1px solid var(--line);
  border-radius: var(--r-lg); padding: 24px;
  max-width: 420px; width: 90%;
}
.modal-title { font-size: 16px; font-weight: 600; color: var(--fg-0); margin: 0 0 8px; }
.modal-text { font-size: 13px; color: var(--fg-3); margin: 0; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
.modal-btn { padding: 6px 14px; font-size: 13px; border-radius: var(--r-sm); border: none; cursor: pointer; }
.modal-btn--cancel { background: var(--bg-2); color: var(--fg-2); }
.modal-btn--send { background: var(--brand-2); color: #fff; }
.modal-btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
