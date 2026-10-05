<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { query: gqlQuery } = useGraphQL()
const { searchProfiles } = useProfileSearch({ includeEmailInLabel: true })
const toast = useToast()

const messageId = ref('')
const recipientId = ref('')
const searchMode = ref<'message' | 'recipient'>('recipient')
const results = ref<DeliveryStatus[]>([])
const isLoading = ref(false)
const resultSource = ref<'recent' | 'message' | 'recipient'>('recent')
const recentOffset = ref(0)
const recentTotal = ref(0)
const recentLimit = 25
const selectedDelivery = ref<DeliveryStatus | null>(null)
let resultsRequest = 0

interface BmlMessageTemplateRender {
  project: string
  templateKey: string
  version: string | null
  parameters: unknown
}

interface DeliveryStatus {
  messageId: string
  recipientId: string
  recipientName?: string | null
  recipientEmail?: string | null
  channel: string
  status: string
  attempts: number
  lastAttemptAt: string | null
  deliveredAt: string | null
  bouncedAt: string | null
  openedAt: string | null
  clickedAt: string | null
  errorCode: string | null
  errorMessage: string | null
  bmlTemplate: BmlMessageTemplateRender | null
  createdAt: string
  updatedAt: string
}

const recentStatusesGql = gql`
  query GetRecentDeliveryStatuses($offset: Long!, $limit: Int!) {
    communications {
      deliveryStatuses(offset: $offset, limit: $limit) {
        statuses {
          recipientName
          recipientEmail
          delivery {
            messageId recipientId channel status attempts
            lastAttemptAt deliveredAt bouncedAt openedAt clickedAt
            errorCode errorMessage createdAt updatedAt
            bmlTemplate { project templateKey version parameters }
          }
        }
        total
      }
    }
  }
`

const messageStatusesGql = gql`
  query GetMessageStatuses($messageId: UUID!) {
    communications {
      messageStatuses(messageId: $messageId) {
        messageId recipientId recipientName recipientEmail channel status attempts
        lastAttemptAt deliveredAt bouncedAt openedAt clickedAt
        errorCode errorMessage createdAt updatedAt
        bmlTemplate { project templateKey version parameters }
      }
    }
  }
`

const recipientHistoryGql = gql`
  query GetRecipientHistory($recipientId: UUID!, $offset: Long!, $limit: Int!) {
    communications {
      recipientHistory(recipientId: $recipientId, offset: $offset, limit: $limit) {
        messageId recipientId recipientName recipientEmail channel status attempts
        lastAttemptAt deliveredAt bouncedAt openedAt clickedAt
        errorCode errorMessage createdAt updatedAt
        bmlTemplate { project templateKey version parameters }
      }
    }
  }
`

const currentPage = computed(() => Math.floor(recentOffset.value / recentLimit) + 1)
const totalPages = computed(() => Math.max(1, Math.ceil(recentTotal.value / recentLimit)))
const resultsTitle = computed(() =>
  resultSource.value === 'recent'
    ? `${recentTotal.value.toLocaleString()} Recent Deliveries`
    : `${results.value.length.toLocaleString()} Results`,
)
const emptyText = computed(() =>
  resultSource.value === 'recent'
    ? 'No delivery statuses have been recorded yet.'
    : 'No delivery records found for this search.',
)

async function loadRecentStatuses() {
  const request = ++resultsRequest
  isLoading.value = true
  resultSource.value = 'recent'
  results.value = []
  try {
    const data = await gqlQuery(recentStatusesGql, {
      offset: recentOffset.value,
      limit: recentLimit,
    }) as {
      communications: {
        deliveryStatuses: {
          statuses: Array<{
            recipientName: string | null
            recipientEmail: string | null
            delivery: Omit<DeliveryStatus, 'recipientName' | 'recipientEmail'>
          }>
          total: number
        }
      }
    } | null
    if (request !== resultsRequest) return
    const deliveryStatuses = data?.communications?.deliveryStatuses
    recentTotal.value = deliveryStatuses?.total ?? 0
    results.value = (deliveryStatuses?.statuses ?? []).map(status => ({
      ...status.delivery,
      recipientName: status.recipientName,
      recipientEmail: status.recipientEmail,
    }))
  } catch (e: unknown) {
    if (request !== resultsRequest) return
    toast.error(e instanceof Error ? e.message : 'Failed to load recent deliveries')
  } finally {
    if (request === resultsRequest) isLoading.value = false
  }
}

async function searchByMessage() {
  if (!messageId.value) return
  const request = ++resultsRequest
  isLoading.value = true
  resultSource.value = 'message'
  results.value = []
  try {
    const data = await gqlQuery(messageStatusesGql, { messageId: messageId.value }) as
      { communications: { messageStatuses: DeliveryStatus[] } } | null
    if (request !== resultsRequest) return
    results.value = data?.communications?.messageStatuses ?? []
  } catch (e: unknown) {
    if (request !== resultsRequest) return
    toast.error(e instanceof Error ? e.message : 'Search failed')
  } finally {
    if (request === resultsRequest) isLoading.value = false
  }
}

async function onRecipientSelected(id: string) {
  recipientId.value = id
  if (!id) {
    recentOffset.value = 0
    await loadRecentStatuses()
    return
  }
  const request = ++resultsRequest
  isLoading.value = true
  resultSource.value = 'recipient'
  results.value = []
  try {
    const data = await gqlQuery(recipientHistoryGql, {
      recipientId: id, offset: 0, limit: 50,
    }) as { communications: { recipientHistory: DeliveryStatus[] } } | null
    if (request !== resultsRequest) return
    results.value = data?.communications?.recipientHistory ?? []
  } catch (e: unknown) {
    if (request !== resultsRequest) return
    toast.error(e instanceof Error ? e.message : 'Search failed')
  } finally {
    if (request === resultsRequest) isLoading.value = false
  }
}

async function goToPage(page: number) {
  const clamped = Math.max(1, Math.min(page, totalPages.value))
  recentOffset.value = (clamped - 1) * recentLimit
  await loadRecentStatuses()
}

async function setSearchMode(mode: 'message' | 'recipient') {
  if (searchMode.value === mode) return
  ++resultsRequest
  isLoading.value = false
  searchMode.value = mode
  results.value = []
  if (mode === 'message') {
    resultSource.value = 'message'
    return
  }
  if (recipientId.value) {
    await onRecipientSelected(recipientId.value)
  }
  else {
    recentOffset.value = 0
    await loadRecentStatuses()
  }
}

function formatDate(d: string | null) {
  if (!d) return '—'
  return new Date(d).toLocaleString()
}

function formatParameters(parameters: unknown) {
  return JSON.stringify(parameters, null, 2) ?? 'null'
}

const statusColors: Record<string, string> = {
  PENDING: '#94a3b8', SENT: '#38bdf8', DELIVERED: '#34d99a', DEFERRED: '#ffb547',
  BOUNCED: '#f87171', DROPPED: '#f87171', OPENED: '#a78bff', CLICKED: '#c084fc',
  SPAM_REPORT: '#f97316', UNSUBSCRIBED: '#94a3b8', FAILED: '#ef4444',
}

const columns: GlassTableColumn[] = [
  { key: 'status', label: 'Status', width: '120px' },
  { key: 'channel', label: 'Channel', width: '90px' },
  { key: 'recipient', label: 'Recipient', width: 'minmax(220px, 1fr)' },
  { key: 'template', label: 'Template', width: 'minmax(180px, 1fr)' },
  { key: 'messageId', label: 'Message ID', width: 'minmax(160px, 1fr)' },
  { key: 'attempts', label: 'Attempts', width: '80px', muted: true },
  { key: 'lastAttemptAt', label: 'Last Attempt', width: '160px', muted: true },
  { key: 'deliveredAt', label: 'Delivered', width: '160px', muted: true },
]

onMounted(loadRecentStatuses)
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Messaging', 'Delivery Status')"
        title="Delivery Status"
        subtitle="Track email and push delivery across messages and recipients"
      />
    </template>

    <SectionCard title="Search" padded>
      <div class="mode-toggle">
        <button
          class="mode-btn"
          :class="{ active: searchMode === 'recipient' }"
          @click="setSearchMode('recipient')"
        >By Recipient</button>
        <button
          class="mode-btn"
          :class="{ active: searchMode === 'message' }"
          @click="setSearchMode('message')"
        >By Message ID</button>
      </div>

      <div v-if="searchMode === 'recipient'" class="field-row">
        <label class="field-label">Select a recipient to view delivery history</label>
        <Select
          :model-value="recipientId"
          :on-search="searchProfiles"
          searchable
          placeholder="Search people…"
          @update:model-value="onRecipientSelected($event as string)"
        />
      </div>

      <div v-else class="field-row">
        <label class="field-label">Enter a message ID to view delivery statuses</label>
        <div class="message-search-row">
          <input
            v-model="messageId"
            type="text"
            class="search-input"
            placeholder="Message ID (UUID)…"
            @keydown.enter="searchByMessage"
          >
          <button class="search-btn" :disabled="isLoading" @click="searchByMessage">
            {{ isLoading ? 'Searching…' : 'Search' }}
          </button>
        </div>
      </div>
    </SectionCard>

    <SectionCard :title="resultsTitle">
      <GlassTable
        :columns="columns"
        :rows="results"
        :loading="isLoading"
        :empty-text="emptyText"
        arrow
        @row-click="selectedDelivery = $event">
        <template #col-status="{ row }">
          <span
            class="status-badge"
            :style="{
              color: statusColors[row.status] || 'var(--fg-2)',
              background: `color-mix(in oklch, ${statusColors[row.status] || 'var(--fg-3)'} 12%, transparent)`
            }"
          >{{ row.status }}</span>
        </template>
        <template #col-recipient="{ row }">
          <div class="recipient-cell">
            <span class="recipient-name">{{ row.recipientName || row.recipientId }}</span>
            <span v-if="row.recipientEmail" class="recipient-email">{{ row.recipientEmail }}</span>
          </div>
        </template>
        <template #col-messageId="{ row }">
          <span class="mono truncate">{{ row.messageId }}</span>
        </template>
        <template #col-template="{ row }">
          <div v-if="row.bmlTemplate" class="template-cell">
            <span>{{ row.bmlTemplate.project }}/{{ row.bmlTemplate.templateKey }}</span>
            <span v-if="row.bmlTemplate.version" class="template-version">{{ row.bmlTemplate.version }}</span>
          </div>
          <span v-else>—</span>
        </template>
        <template #col-lastAttemptAt="{ row }">{{ formatDate(row.lastAttemptAt) }}</template>
        <template #col-deliveredAt="{ row }">{{ formatDate(row.deliveredAt) }}</template>
      </GlassTable>
      <Pagination
        v-if="resultSource === 'recent' && totalPages > 1"
        :page="currentPage"
        :total-pages="totalPages"
        @prev="goToPage(currentPage - 1)"
        @next="goToPage(currentPage + 1)"
      />
    </SectionCard>

    <Modal
      v-if="selectedDelivery"
      title="Delivery Details"
      icon="inspect"
      :accent="accent"
      width="720px"
      @close="selectedDelivery = null"
    >
      <div class="detail-sections">
        <div class="detail-section">
          <div class="detail-heading">Delivery</div>
          <div class="detail-grid">
            <span class="detail-label">Message ID</span>
            <span class="detail-value mono">{{ selectedDelivery.messageId }}</span>
            <span class="detail-label">Recipient</span>
            <span class="detail-value">{{ selectedDelivery.recipientName || selectedDelivery.recipientId }}</span>
            <span class="detail-label">Channel</span>
            <span class="detail-value">{{ selectedDelivery.channel }}</span>
            <span class="detail-label">Status</span>
            <span class="detail-value">{{ selectedDelivery.status }}</span>
          </div>
        </div>

        <div v-if="selectedDelivery.bmlTemplate" class="detail-section">
          <div class="detail-heading">Message Template</div>
          <div class="detail-grid">
            <span class="detail-label">Template</span>
            <span class="detail-value mono template-name">
              {{ selectedDelivery.bmlTemplate.project }}/{{ selectedDelivery.bmlTemplate.templateKey }}
            </span>
            <span class="detail-label">Version</span>
            <span class="detail-value mono">{{ selectedDelivery.bmlTemplate.version || '—' }}</span>
          </div>
          <div class="parameters-heading">Parameters</div>
          <pre class="template-parameters">{{ formatParameters(selectedDelivery.bmlTemplate.parameters) }}</pre>
        </div>

        <div v-else class="detail-section empty-template">
          This delivery did not use a message template.
        </div>
      </div>
    </Modal>
  </PageShell>
</template>

<style scoped>
.mode-toggle { display: flex; gap: 2px; margin-bottom: 12px; }
.mode-btn {
  font-size: 11.5px; font-weight: 500; padding: 5px 12px; border-radius: var(--r-xs);
  color: var(--fg-3); background: transparent; cursor: pointer; transition: all 0.15s;
}
.mode-btn:hover { color: var(--fg-1); background: color-mix(in oklch, var(--fg-3) 8%, transparent); }
.mode-btn.active { color: var(--fg-0); background: color-mix(in oklch, var(--fg-3) 14%, transparent); }

.field-row { max-width: 400px; }
.field-label { display: block; font-size: 12px; color: var(--fg-3); margin-bottom: 6px; }

.message-search-row { display: flex; gap: 8px; }
.search-input {
  flex: 1; font-size: 12px; padding: 5px 10px; border-radius: var(--r-xs);
  background: color-mix(in oklch, var(--fg-3) 8%, transparent);
  color: var(--fg-1); border: 1px solid color-mix(in oklch, var(--fg-3) 16%, transparent);
  outline: none; font-family: var(--font-mono);
}
.search-input:focus { border-color: color-mix(in oklch, var(--fg-3) 30%, transparent); }
.search-btn {
  font-size: 12px; font-weight: 500; padding: 5px 16px; border-radius: var(--r-xs);
  color: #fff; background: color-mix(in oklch, var(--accent, #38bdf8) 80%, black);
  cursor: pointer; transition: all 0.15s; white-space: nowrap;
}
.search-btn:hover { background: var(--accent, #38bdf8); }
.search-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.status-badge { font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: var(--r-xs); }
.recipient-cell { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
.recipient-name { color: var(--fg-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.recipient-email { color: var(--fg-3); font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.template-cell { display: flex; flex-direction: column; min-width: 0; color: var(--fg-1); }
.template-cell > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.template-version { color: var(--fg-3); font-family: var(--font-mono); font-size: 10px; }
.detail-sections { display: flex; flex-direction: column; gap: 16px; }
.detail-section { display: flex; flex-direction: column; gap: 10px; }
.detail-heading { color: var(--fg-1); font-size: 12px; font-weight: 600; }
.detail-grid { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 8px 14px; }
.detail-label { color: var(--fg-3); font-size: 11px; }
.detail-value { color: var(--fg-1); min-width: 0; overflow-wrap: anywhere; }
.parameters-heading { color: var(--fg-3); font-size: 11px; }
.template-parameters {
  max-height: 340px; margin: 0; padding: 12px; overflow: auto; border-radius: var(--r-xs);
  color: var(--fg-1); background: color-mix(in oklch, var(--fg-3) 8%, transparent);
  font-family: var(--font-mono); font-size: 11px; line-height: 1.5; white-space: pre-wrap;
}
.empty-template { color: var(--fg-3); font-size: 12px; }
.mono { font-family: var(--font-mono); font-size: 11px; }
.truncate { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 200px; display: inline-block; }
</style>
